package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.util.UUID

class VoiceProviderTests {
    private val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "secret", name = "My Mac")
    private val studio = OpenCodeServer(url = "http://studio.local:4096", username = "opencode", password = "studio", name = "Studio")
    private val session = OpenCodeSession(
        id = "ses_voice",
        serverTitle = "Voice Provider",
        projectID = "proj_1",
        directory = "/Users/kit/code/open-source/opencode-ios",
        updatedAt = Instant.ofEpochSecond(1_700_000_000),
    )
    private val gptLive = ServerVoiceModel(model = "gpt-live-1", provider = "openai", available = true, engine = "openai-live")
    private val keyless = ServerVoiceModel(model = "gpt-live-1", provider = "openai", available = false, engine = "openai-live")

    /** The first ID the incrementing UUID client hands out: Android's call-presence ID. */
    private val presenceID = UUID(0, 0)

    /** Each test here gets a fresh in-memory store. */
    private fun reset(): SpeechTestShared = SpeechTestShared().listVoiceModels(server, studio)

    /** `server` connected, `servers` saved, `choice` stored. */
    private fun SpeechTestShared.save(servers: List<OpenCodeServer>, choice: VoiceServerChoice = VoiceServerChoice.Automatic) = run {
        shared(ServerSharedKeys.savedServers).set(servers)
        shared(ServerSharedKeys.savedServer).set(server)
        shared(VoiceSharedKeys.voiceServerChoice).set(choice)
    }

    private fun SpeechTestShared.know(
        support: Map<String, ServerVoiceSupport> = emptyMap(),
        models: Map<String, ServerVoiceModelsAnswer> = emptyMap(),
    ) = run {
        shared(VoiceSharedKeys.serverVoiceSupportByServer).set(support)
        shared(VoiceSharedKeys.voiceModelsByServer).set(models)
    }

    private fun transcriptRecord(shared: SpeechTestShared, on: OpenCodeServer = server): VoiceTranscriptRecord? =
        shared.run { shared(VoiceSharedKeys.voiceTranscripts).value["${on.id}:${session.id}"] }

    private fun callDependencies(
        shared: SpeechTestShared,
        at: Long,
        checkSupport: suspend (OpenCodeServer) -> ServerVoiceSupport,
        start: suspend (RealtimeSessionConfig) -> Flow<VoiceCallEvent>,
        end: suspend () -> Unit,
    ): DependencyValues.Builder.() -> Unit = {
        this[SharedStore] = shared.store
        this[DateClient] = DateClient.constant(Instant.ofEpochSecond(at))
        // Android: the call's ongoing-notification ID and the start cue's clock.
        this[UuidClient] = UuidClient.incrementing()
        this[Clock] = TestClock()
        this[ServerVoiceClient] = ServerVoiceClient.testValue.let { ServerVoiceClient(checkSupport = checkSupport, models = it.models, createSession = it.createSession) }
        this[VoiceWorkClient] = VoiceWorkClient.testValue.let { work ->
            VoiceWorkClient(submit = work.submit, awaitReply = work.awaitReply, checkSessionActive = work.checkSessionActive, recentMessages = { _, _, _ -> emptyList() })
        }
        this[VoiceCallClient] = speechVoiceCall(start = start, end = end)
    }

    private val gptLiveServerRoute = VoiceRoute.Server(VoiceRoute.ServerRoute.OpenAI(VoiceRoute.OpenAI.gptLive))

    // MARK: States

    /**
     * Ready, No key, Update OpenCode and Offline come from the models list,
     * the voice route probe and whether the server answered. A list without
     * engines comes from a server too old to name them: Update OpenCode.
     */
    @Test fun eachStateComesFromWhatTheServerReports() {
        assertEquals(VoiceProviderState.unknown, VoiceProviderState.server(null, support = null))
        assertEquals(VoiceProviderState.ready, VoiceProviderState.server(ServerVoiceModelsAnswer.Listed(listOf(gptLive)), support = null))
        assertEquals(VoiceProviderState.ready, VoiceProviderState.server(ServerVoiceModelsAnswer.Listed(listOf(gptLive)), support = ServerVoiceSupport.supported))
        assertEquals(VoiceProviderState.noKey, VoiceProviderState.server(ServerVoiceModelsAnswer.Listed(listOf(keyless)), support = ServerVoiceSupport.supported))
        assertEquals(VoiceProviderState.needsUpdate, VoiceProviderState.server(ServerVoiceModelsAnswer.Listed(listOf(gptLive)), support = ServerVoiceSupport.unsupported))
        assertEquals("No models route (a 404)", VoiceProviderState.needsUpdate, VoiceProviderState.server(ServerVoiceModelsAnswer.Listed(emptyList()), support = null))
        assertEquals(VoiceProviderState.needsUpdate, VoiceProviderState.server(ServerVoiceModelsAnswer.Listed(emptyList()), support = ServerVoiceSupport.supported))
        assertEquals(VoiceProviderState.offline, VoiceProviderState.server(ServerVoiceModelsAnswer.NoAnswer, support = null))
        assertEquals(VoiceProviderState.needsUpdate, VoiceProviderState.server(null, support = ServerVoiceSupport.unsupported))
        assertEquals(
            "A list without engines predates models.dev voice models",
            VoiceProviderState.needsUpdate,
            VoiceProviderState.server(ServerVoiceModelsAnswer.Listed(listOf(gptLive.copy(engine = null))), support = ServerVoiceSupport.supported),
        )

        assertEquals(
            listOf("Ready", "No key", "Update OpenCode", "Offline"),
            listOf(VoiceProviderState.ready, VoiceProviderState.noKey, VoiceProviderState.needsUpdate, VoiceProviderState.offline).map { it.label },
        )
        assertTrue(VoiceProviderState.unknown.label == null && VoiceProviderState.unknown.symbol == null)
    }

    // MARK: Automatic

    /**
     * Automatic takes the connected server while it can (or can't be known
     * not to), then another saved server that's ready, else the connected
     * server, whose failure says why.
     */
    @Test fun automaticTriesTheConnectedServerThenThePhoneThenAnotherReadyServer() {
        fun pick(readiness: VoiceReadiness): VoicePick =
            VoicePick.resolve(VoiceServerChoice.Automatic, connected = server, saved = listOf(server, studio), readiness = readiness)
        val ready = mapOf(
            voiceServerID(server) to ServerVoiceModelsAnswer.Listed(listOf(gptLive)),
            voiceServerID(studio) to ServerVoiceModelsAnswer.Listed(listOf(gptLive)),
        )
        assertEquals("Not heard from yet: its probe decides", server, pick(VoiceReadiness()).server)
        assertEquals(server, pick(VoiceReadiness(answers = ready)).server)

        val tooOld = mapOf(server.persistenceID to ServerVoiceSupport.unsupported)
        assertEquals(studio, pick(VoiceReadiness(answers = ready, support = tooOld)).server)
        assertEquals(
            studio,
            pick(
                VoiceReadiness(
                    answers = mapOf(
                        voiceServerID(server) to ServerVoiceModelsAnswer.Listed(listOf(keyless)),
                        voiceServerID(studio) to ServerVoiceModelsAnswer.Listed(listOf(gptLive)),
                    ),
                ),
            ).server,
        )
        val silent = mapOf(voiceServerID(server) to ServerVoiceModelsAnswer.NoAnswer, voiceServerID(studio) to ServerVoiceModelsAnswer.NoAnswer)
        assertEquals(server, pick(VoiceReadiness(answers = silent)).server)
        assertTrue(pick(VoiceReadiness(answers = silent)).isAutomatic)

        val picked = VoicePick.resolve(
            VoiceServerChoice.of(studio), connected = server, saved = listOf(server, studio),
            readiness = VoiceReadiness(answers = mapOf(voiceServerID(studio) to ServerVoiceModelsAnswer.NoAnswer)),
        )
        assertTrue("A pick stays, ready or not", picked.server == studio && !picked.isAutomatic)
    }

    /**
     * No key on this phone: Automatic moves on to a saved server that's
     * ready, and the call bar names it.
     */
    @Test fun automaticCallsThroughAnotherReadyServerWithoutAPhoneKey() = runBlocking {
        val shared = reset()
        shared.save(listOf(server, studio))
        shared.know(
            support = mapOf(server.persistenceID to ServerVoiceSupport.unsupported, studio.persistenceID to ServerVoiceSupport.supported),
            models = mapOf(voiceServerID(studio) to ServerVoiceModelsAnswer.Listed(listOf(gptLive))),
        )
        val brokers = Locked(listOf<OpenCodeServer?>())
        val stream = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        val connectGate = CompletableDeferred<Unit>()
        val effects = detachedScope(coroutineContext)
        val store = TestStore(
            shared.run { VoiceFeature.State(server, session) }, VoiceFeature(), effects,
            callDependencies(
                shared, at = 2_000,
                checkSupport = {
                    fail("Studio's support is known")
                    ServerVoiceSupport.supported
                },
                start = { config ->
                    brokers.update { it + config.serverVoice }
                    connectGate.await()
                    stream.trySend(VoiceCallEvent.Connected)
                    stream.consumeAsFlow()
                },
                end = { stream.close() },
            ),
        )

        store.send(VoiceFeature.Action.Start) {
            it.copy(
                serverVoiceBroker = studio,
                diagnosesFailure = true,
                callPlace = VoiceCallPlace("Studio", VoiceProviderOption.Brand.openAI),
                activeModel = "gpt-live-1",
                startedTarget = VoiceWorkTarget(server = server, sessionID = session.id, directory = session.directory),
                activeRoute = gptLiveServerRoute,
                activeVoice = CallVoice.marin,
                callPresenceID = presenceID,
            )
        }
        assertEquals("Connecting via Studio…", store.state.statusText)
        connectGate.complete(Unit)
        store.receive(VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected)) {
            it.withPhase(VoiceFeature.State.Phase.Live).copy(connectedAt = Instant.ofEpochSecond(2_000))
        }
        stream.close()
        store.receive(VoiceFeature.Action.CallEnded(errorMessage = null)) { it.withPhase(VoiceFeature.State.Phase.Ended) }
        assertEquals(VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(2_000), tail = emptyList(), log = emptyList()), transcriptRecord(shared))
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        store.finish()
        effects.cancel()
        assertEquals(listOf<OpenCodeServer?>(studio), brokers.value)
    }

    // MARK: A pick

    /**
     * The connected server picked, and too old: the call fails saying so,
     * with Update OpenCode.
     */
    @Test fun aPickedServerThatFailsIsNeverSwappedForThePhoneKey() = runBlocking {
        val shared = reset()
        shared.save(listOf(server), choice = VoiceServerChoice.of(server))
        val effects = detachedScope(coroutineContext)
        val store = TestStore(
            shared.run { VoiceFeature.State(server, session) }, VoiceFeature(), effects,
            callDependencies(
                shared, at = 4_000,
                checkSupport = { ServerVoiceSupport.unsupported },
                start = { _ ->
                    fail("A server too old for voice never starts a call")
                    throw CancellationException()
                },
                end = {},
            ),
        )

        store.send(VoiceFeature.Action.Start) {
            it.copy(
                diagnosesFailure = true,
                callPlace = VoiceCallPlace("My Mac", VoiceProviderOption.Brand.openAI),
                activeModel = "gpt-live-1",
                startedTarget = VoiceWorkTarget(server = server, sessionID = session.id, directory = session.directory),
                activeRoute = gptLiveServerRoute,
                activeVoice = CallVoice.marin,
                callPresenceID = presenceID,
            )
        }
        store.receive(VoiceFeature.Action.ServerVoiceSupportUpdated(ServerVoiceSupport.unsupported)) {
            it.copy(serverVoiceSupport = ServerVoiceSupport.unsupported)
        }
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.ServerVoiceSupportChanged(ServerVoiceSupport.unsupported)))
        store.receive(VoiceFeature.Action.CallRouteResolved(VoiceRoute.Unavailable(VoiceRoute.Reason.ServerUnsupported), usesDelegator = false)) {
            it.copy(activeRoute = VoiceRoute.Unavailable(VoiceRoute.Reason.ServerUnsupported))
        }
        store.receive(VoiceFeature.Action.CallEnded(errorMessage = ServerVoiceError.unsupportedServerNotice)) {
            it.withPhase(VoiceFeature.State.Phase.Failed(ServerVoiceError.unsupportedServerNotice))
        }
        assertEquals(ServerVoiceSupport.unsupported, shared.run { shared(VoiceSharedKeys.serverVoiceSupportByServer).value[server.persistenceID] })
        assertEquals(VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(4_000), tail = emptyList(), log = emptyList()), transcriptRecord(shared))
        assertEquals(
            VoiceCallDiagnosis(sentence = "My Mac needs a newer OpenCode for voice calls.", fix = VoiceCallFix.updateOpenCode, place = "My Mac"),
            store.state.failureDiagnosis,
        )
        store.skipReceivedActions()
        effects.skipInFlightEffects()
    }

    // MARK: Pins and reconnects

    /**
     * Choose Automatically off on My Mac pins My Mac itself: switched to
     * Studio, calls still go through My Mac.
     */
    @Test fun aPinnedConnectedServerStaysPinnedAfterASwitch() = runBlocking {
        val shared = reset()
        val readiness = VoiceReadiness(
            answers = mapOf(
                voiceServerID(server) to ServerVoiceModelsAnswer.Listed(listOf(gptLive)),
                voiceServerID(studio) to ServerVoiceModelsAnswer.Listed(listOf(gptLive)),
            ),
            support = mapOf(server.persistenceID to ServerVoiceSupport.supported, studio.persistenceID to ServerVoiceSupport.supported),
        )
        val catalog = VoiceProviderCatalog(VoiceServerChoice.Automatic, connected = server, others = listOf(studio), readiness = readiness)
        assertEquals(VoiceServerChoice.of(server), catalog.active?.choice)

        shared.save(listOf(server, studio), choice = VoiceServerChoice.of(server))
        shared.know(support = readiness.support, models = readiness.answers)
        val brokers = Locked(listOf<OpenCodeServer?>())
        val stream = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        val connectGate = CompletableDeferred<Unit>()
        val effects = detachedScope(coroutineContext)
        val store = TestStore(
            shared.run { VoiceFeature.State(studio, session) }, VoiceFeature(), effects,
            callDependencies(
                shared, at = 5_000,
                checkSupport = { ServerVoiceSupport.supported },
                start = { config ->
                    brokers.update { it + config.serverVoice }
                    connectGate.await()
                    stream.trySend(VoiceCallEvent.Connected)
                    stream.consumeAsFlow()
                },
                end = { stream.close() },
            ),
        )

        store.send(VoiceFeature.Action.Start) {
            it.copy(
                serverVoiceBroker = server,
                diagnosesFailure = true,
                callPlace = VoiceCallPlace("My Mac", VoiceProviderOption.Brand.openAI),
                activeModel = "gpt-live-1",
                startedTarget = VoiceWorkTarget(server = studio, sessionID = session.id, directory = session.directory),
                activeRoute = gptLiveServerRoute,
                activeVoice = CallVoice.marin,
                callPresenceID = presenceID,
            )
        }
        assertEquals("Connecting via My Mac…", store.state.statusText)
        connectGate.complete(Unit)
        store.receive(VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected)) {
            it.withPhase(VoiceFeature.State.Phase.Live).copy(connectedAt = Instant.ofEpochSecond(5_000))
        }
        stream.close()
        store.receive(VoiceFeature.Action.CallEnded(errorMessage = null)) { it.withPhase(VoiceFeature.State.Phase.Ended) }
        assertEquals(VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(5_000), tail = emptyList(), log = emptyList()), transcriptRecord(shared, on = studio))
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        store.finish()
        effects.cancel()
        assertEquals(listOf<OpenCodeServer?>(server), brokers.value)
    }

    /**
     * The pin Home writes for an earlier “connected server” choice keeps calls on that server after
     * a switch, and Automatic never becomes a pin.
     */
    @Test fun aLegacyConnectedPinResolvesToThatServer() {
        val pinned = VoiceServerChoice.Connected.pinningConnected(server)
        assertEquals(VoiceServerChoice.of(server), pinned)
        val pick = VoicePick.resolve(pinned, connected = studio, saved = listOf(server, studio), readiness = VoiceReadiness())
        assertEquals(VoicePick(VoiceProviderOption.Place.Server(server), isAutomatic = false), pick)
        assertEquals(VoiceServerChoice.Automatic, VoiceServerChoice.Automatic.pinningConnected(server))
    }

    // MARK: Failures

    /**
     * One sentence and the button that fixes it.
     */
    @Test fun aFailedCallSaysWhyAndOffersTheOneFix() {
        val serverRoute = gptLiveServerRoute
        fun diagnose(message: String, route: VoiceRoute, place: String? = "My Mac") = VoiceCallDiagnosis.diagnose(message, route, place)
        val missingServerKey = "Couldn’t start voice: Voice needs an OpenAI API key on the server (a stored key or OPENAI_API_KEY). ChatGPT sign-in can’t start voice sessions."
        assertEquals(
            VoiceCallDiagnosis(sentence = "My Mac doesn’t have a key for voice calls.", fix = VoiceCallFix.chooseProvider, place = "My Mac"),
            diagnose(missingServerKey, serverRoute),
        )
        assertEquals("Couldn’t reach My Mac.", diagnose("Could not connect to My Mac.", serverRoute).sentence)
        assertEquals(VoiceCallFix.chooseProvider, diagnose("Could not connect to My Mac.", serverRoute).fix)
        assertEquals("My Mac couldn’t start the call.", diagnose("Couldn’t start voice: boom", serverRoute).sentence)
        assertEquals(VoiceCallFix.updateOpenCode, diagnose(ServerVoiceError.unsupportedServerNotice, VoiceRoute.Unavailable(VoiceRoute.Reason.ServerUnsupported)).fix)

        assertEquals(VoiceCallFix.openSettings, diagnose(VoiceAudioError.microphoneDenied.message ?: "", serverRoute).fix)
        assertEquals(
            listOf("Choose Provider", "Update OpenCode"),
            listOf(VoiceCallFix.chooseProvider, VoiceCallFix.updateOpenCode).map { it.title },
        )
    }

    /**
     * The card's button opens the page that fixes it: Voice Provider for a
     * server's failure. Update OpenCode opens the download page.
     */
    @Test fun theFixButtonOpensThePageThatFixesIt() = runBlocking {
        val shared = reset()
        val opened = Locked(listOf<String>())
        val state = shared.run { VoiceFeature.State(server, session) }
            .withPhase(VoiceFeature.State.Phase.Failed("No connection."))
            .copy(diagnosesFailure = true)
        val effects = detachedScope(coroutineContext)
        val store = TestStore(state, VoiceFeature(), effects) {
            this[SharedStore] = shared.store
            this[VoiceCallFixClient] = VoiceCallFixClient(openURL = { url -> opened.update { it + url } }, openAppSettings = {})
        }
        store.send(VoiceFeature.Action.FailureFixTapped(VoiceCallFix.chooseProvider))
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.OpenVoiceSettings(VoiceSettingsPage.provider)))
        store.send(VoiceFeature.Action.FailureFixTapped(VoiceCallFix.updateOpenCode))
        eventually { opened.value.isNotEmpty() }
        assertEquals(listOf(VoiceCallFix.downloadURL), opened.value)
        store.finish()
        effects.cancel()
    }

}
