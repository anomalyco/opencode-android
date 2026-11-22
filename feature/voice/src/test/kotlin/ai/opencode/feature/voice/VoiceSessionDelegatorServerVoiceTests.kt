package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.asContextElement
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.store.TestStore
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.admittingFirstTurn
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.hearing
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.hungUp
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.mockServerID
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.preserveSettings
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.server
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.session
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.settlingFirstTurn
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.startingTurn
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.store
import ai.opencode.server.friendlyName
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.ServerIDClient
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Server Voice × delegator matrix, console fallback and reconnect. */
class VoiceSessionDelegatorServerVoiceTests {
    private val clientDelegation: RealtimeJSON = RealtimeJSONs.obj("type" to RealtimeJSONs.string("client"))

    private fun sessionDelegation(config: RealtimeSessionConfig): RealtimeJSON? =
        config.liveSessionConfig().sessionPayload(includeAudioFormat = false).jsonObject["delegation"]

    private fun functionCall(callID: String) = VoiceFeature.Action.CallEvent(
        VoiceCallEvent.FunctionCall(callID = callID, name = "delegate", arguments = RealtimeJSONs.obj()),
    )

    private fun userTranscript(itemID: String, text: String) =
        VoiceFeature.Action.CallEvent(VoiceCallEvent.UserTranscript(itemID = itemID, text = text, final = true))

    /** A built-in delegation starting. */
    private fun VoiceFeature.State.startingBuiltIn(callID: String): VoiceFeature.State {
        val acted = copy(replyWatch = replyWatch.noteUserActed())
        val captured = acted.tail.capture()
        return acted.copy(tail = captured.tail.accept(captured.capture), delegatorWork = listOf(callID))
            .stepping(ReplyWatch.Event.DelegatorStarted(requestID = callID))
    }

    private fun VoiceFeature.State.finishingBuiltIn(callID: String, reply: String): VoiceFeature.State =
        copy(delegatorWork = emptyList())
            .stepping(ReplyWatch.Event.Sent(emptyList()))
            .stepping(ReplyWatch.Event.DelegatorFinished(requestID = callID, spoken = reply))

    /** The GPT-Live engine, provider and voice, and the call's ongoing-notification ID. */
    private fun VoiceFeature.State.started(route: VoiceRoute, usesDelegator: Boolean, usesSessionDelegator: Boolean): VoiceFeature.State =
        copy(
            startedTarget = VoiceWorkTarget(server = server, sessionID = session.id, directory = session.directory),
            activeRoute = route,
            activeVoice = CallVoice.marin,
            usesDelegator = usesDelegator,
            usesSessionDelegator = usesSessionDelegator,
            // Android: the call's ongoing notification, started with the call.
            callPresenceID = UUID(0, 0),
        )

    private fun VoiceFeature.State.serverStarted(brand: VoiceProviderOption.Brand, usesDelegator: Boolean, usesSessionDelegator: Boolean) =
        started(VoiceRoute.serverOpenAI(VoiceRoute.OpenAI.gptLive), usesDelegator, usesSessionDelegator)
            .copy(diagnosesFailure = true, callPlace = VoiceCallPlace(server.friendlyName, brand))

    private fun connected(now: Instant): VoiceFeature.State.() -> VoiceFeature.State = {
        withPhase(VoiceFeature.State.Phase.Live).copy(connectedAt = now)
    }

    private fun admitting(promptText: String, submits: Locked<Int>) = VoiceSessionDelegator.Client(submit = { submission ->
        submits.update { it + 1 }
        VoiceSessionDelegator.Admission(
            sessionID = session.id,
            title = session.title,
            promptID = submission.messageID,
            promptText = promptText,
            explained = true,
            contextLine = submission.contextLine,
        )
    })

    private fun refusingSubmits(submits: Locked<Int>) = VoiceSessionDelegator.Client(submit = {
        submits.update { it + 1 }
        throw OpenCodeClientError("Unexpected session delegator submit")
    })

    private class CallStream {
        val channel = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        val configs = Locked(listOf<RealtimeSessionConfig>())

        fun start(config: RealtimeSessionConfig): Flow<VoiceCallEvent> {
            configs.update { it + config }
            channel.trySend(VoiceCallEvent.Connected)
            return channel.consumeAsFlow()
        }

        fun finish() {
            channel.close()
        }
    }

    /**
     * The user's words, the delegate call admitted into the viewed session, its reply settling the
     * turn, and the hang-up.
     */
    private suspend fun TestStore<VoiceFeature.State, VoiceFeature.Action>.sessionTurn(
        itemID: String,
        text: String,
        callID: String,
        promptID: String,
        promptText: String,
        reply: String,
        now: Instant,
    ) {
        send(userTranscript(itemID, text)) { it.hearing(itemID, text) }
        send(functionCall(callID)) {
            it.startingTurn(callID = callID, sessionID = session.id, admitting = true, saidTurn = 2, activeSessionID = session.id)
        }
        val expectedContext = "[Phone context] Call on session \"Session View\" (ses_view) · " + VoiceScreenVisibility.notLooking.unseenNote!!
        val expectedAdmission = VoiceSessionDelegator.Admission(
            sessionID = session.id,
            title = session.title,
            promptID = promptID,
            promptText = promptText,
            explained = true,
            contextLine = expectedContext,
        )
        receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.PromptAdmitted(sessionID = session.id)))
        receive(VoiceFeature.Action.SessionDelegatorAdmitted(callID = callID, admission = expectedAdmission)) {
            it.admittingFirstTurn(sessionID = session.id, title = session.title, promptID = promptID, saidTurn = 2, contextLine = expectedContext, now = now)
        }
        receive(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = promptID, reply = reply))) {
            it.settlingFirstTurn(sessionID = session.id, promptID = promptID, reply = reply)
        }
        send(VoiceFeature.Action.EndTapped) { it.hungUp() }
        receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
    }

    // MARK: - Server Voice × Delegator Matrix, Console Fallback & Reconnect

    @Test fun fourRouteByDelegatorCombinationsConfigurePayloadAndDispatchDelegationsExhaustively() = runBlocking {
        val shared = preserveSettings()
        val at = 9_000L
        val now = Instant.ofEpochSecond(at)

        // 1. Server Voice + Built-in -> `delegation: {type: "responses", ...}`, `unboundSpeechUsesInstructions: false`, handled by `VoiceDelegator.runOnCall`.
        shared.set(VoiceSharedKeys.voiceServerChoice, VoiceServerChoice.Automatic)
        shared.set(VoiceSharedKeys.serverVoiceProvider, ServerVoiceProvider.openAI)
        shared.set(VoiceSharedKeys.serverVoiceSupportByServer, mapOf(server.persistenceID to ServerVoiceSupport.supported))
        shared.set(VoiceSharedKeys.voiceDelegatorChoice, VoiceDelegatorChoice.BuiltIn)

        val svBuiltInStream = CallStream()
        val svBuiltInRounds = Locked(0)
        val svBuiltInSessionSubmits = Locked(0)
        val svBuiltInInitial = shared.read { VoiceFeature.State(server, session) }.copy(serverVoiceSupport = ServerVoiceSupport.supported)
        val svBuiltInEffects = detachedScope(coroutineContext)
        val svBuiltInStore = store(shared, svBuiltInInitial, svBuiltInEffects, now = at) {
            this[UuidClient] = UuidClient.incrementing()
            this[ExperimentalFeatures] = ExperimentalFeatures.enabling(setOf(ExperimentalFeature.voiceSessionDelegator))
            this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true)
            this[VoiceSessionDelegator.Client] = refusingSubmits(svBuiltInSessionSubmits)
            this[VoiceWorkClient] = testVoiceWork(recentMessages = { _, _, _ -> emptyList() })
            this[VoiceCallClient] = speechVoiceCall(
                start = svBuiltInStream::start,
                delegatorRound = { id ->
                    assertEquals("del_sv_bi", id)
                    svBuiltInRounds.update { it + 1 }
                    Result.success(VoiceDelegator.Response(id = "resp_sv_bi", calls = emptyList(), text = "Server built-in reply."))
                },
                end = { svBuiltInStream.finish() },
            )
        }

        svBuiltInStore.send(VoiceFeature.Action.Start) {
            it.serverStarted(VoiceProviderOption.Brand.openAI, usesDelegator = true, usesSessionDelegator = false)
        }
        svBuiltInStore.receive(VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected), update = connected(now))
        assertEquals(false, svBuiltInStream.configs.value.first().unboundSpeechUsesInstructions)
        val svBuiltInDelegation = sessionDelegation(svBuiltInStream.configs.value.first())?.jsonObject
        assertEquals(RealtimeJSONs.string("responses"), svBuiltInDelegation?.get("type"))
        assertEquals(RealtimeJSONs.string(VoiceDelegator.model), svBuiltInDelegation?.get("responses")?.jsonObject?.get("model"))

        svBuiltInStore.send(VoiceFeature.Action.CallEvent(VoiceCallEvent.ServerDelegation(callID = "del_sv_bi"))) {
            it.startingBuiltIn("del_sv_bi")
        }
        val svOutcome = VoiceDelegator.Outcome(reply = "Server built-in reply.", unanswered = emptyList(), spoken = true)
        svBuiltInStore.receive(VoiceFeature.Action.DelegatorFinished(callID = "del_sv_bi", result = Result.success(svOutcome))) {
            it.finishingBuiltIn("del_sv_bi", reply = "Server built-in reply.")
        }
        svBuiltInStore.send(VoiceFeature.Action.EndTapped) { it.hungUp() }
        svBuiltInStore.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        svBuiltInStore.finish()
        svBuiltInEffects.skipInFlightEffects()
        assertEquals(1, svBuiltInRounds.value)
        assertEquals(0, svBuiltInSessionSubmits.value)

        // 2. Server Voice + OpenCode Session -> `delegation: {type: "client"}`, `unboundSpeechUsesInstructions: true`, handled by `VoiceSessionDelegator`.
        shared.set(VoiceSharedKeys.voiceDelegatorChoice, VoiceDelegatorChoice.ViewedSession)
        val svSessionStream = CallStream()
        val svSessionSubmits = Locked(0)
        val svSessionBuiltInRounds = Locked(0)
        val svSessionInitial = shared.read { VoiceFeature.State(server, session) }.copy(serverVoiceSupport = ServerVoiceSupport.supported)
        val svSessionEffects = detachedScope(coroutineContext)
        val svSessionStore = store(shared, svSessionInitial, svSessionEffects, now = at) {
            this[UuidClient] = UuidClient.incrementing()
            this[ExperimentalFeatures] = ExperimentalFeatures.enabling(setOf(ExperimentalFeature.voiceSessionDelegator))
            this[ServerIDClient] = mockServerID(session = { "ses_unused" }, message = { "msg_sv_s" })
            this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true)
            this[VoiceSessionDelegator.Client] = admitting("server-delta", svSessionSubmits)
            this[VoiceWorkClient] = testVoiceWork(
                awaitReply = { _, _, _ -> "Server session reply." },
                recentMessages = { _, _, _ -> emptyList() },
            )
            this[VoiceCallClient] = speechVoiceCall(
                start = svSessionStream::start,
                delegatorRound = {
                    svSessionBuiltInRounds.update { it + 1 }
                    Result.failure(OpenCodeClientError("Unexpected server round"))
                },
                end = { svSessionStream.finish() },
            )
        }

        svSessionStore.send(VoiceFeature.Action.Start) {
            it.serverStarted(VoiceProviderOption.Brand.openAI, usesDelegator = false, usesSessionDelegator = true)
        }
        svSessionStore.receive(VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected), update = connected(now))
        assertEquals(true, svSessionStream.configs.value.first().unboundSpeechUsesInstructions)
        assertEquals(clientDelegation, sessionDelegation(svSessionStream.configs.value.first()))
        svSessionStore.sessionTurn(
            itemID = "u4", text = "Deploy.", callID = "del_sv_s", promptID = "msg_sv_s",
            promptText = "server-delta", reply = "Server session reply.", now = now,
        )
        svSessionStore.finish()
        svSessionEffects.skipInFlightEffects()
        assertEquals(1, svSessionSubmits.value)
        assertEquals(0, svSessionBuiltInRounds.value)
    }

    @Test fun serverVoiceConsoleClientDelegationRejectionFallsBackToBuiltInWithNoteExhaustively() = runBlocking {
        val shared = preserveSettings()
        val at = 9_500L
        val now = Instant.ofEpochSecond(at)

        shared.set(VoiceSharedKeys.serverVoiceProvider, ServerVoiceProvider.openAI)
        shared.set(VoiceSharedKeys.serverVoiceSupportByServer, mapOf(server.persistenceID to ServerVoiceSupport.supported))
        shared.set(
            VoiceSharedKeys.voiceModelsByServer,
            mapOf(server.persistenceID to ServerVoiceModelsAnswer.Listed(listOf(ServerVoiceModel(model = "gpt-live-1", provider = "opencode", available = true)))),
        )
        shared.set(VoiceSharedKeys.voiceDelegatorChoice, VoiceDelegatorChoice.AutomaticSession)

        val initial = shared.read { VoiceFeature.State(server, session) }.copy(serverVoiceSupport = ServerVoiceSupport.supported)
        val callStream = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        val startedConfigs = Locked(listOf<RealtimeSessionConfig>())
        val builtInRounds = Locked(0)
        val effects = detachedScope(coroutineContext)
        val store = store(shared, initial, effects, now = at) {
            this[UuidClient] = UuidClient.incrementing()
            this[ExperimentalFeatures] = ExperimentalFeatures.enabling(setOf(ExperimentalFeature.voiceSessionDelegator))
            this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true)
            this[VoiceWorkClient] = testVoiceWork(recentMessages = { _, _, _ -> emptyList() })
            this[VoiceCallClient] = speechVoiceCall(
                start = { config ->
                    startedConfigs.update { it + config }
                    if (startedConfigs.value.size == 1) {
                        throw RealtimeClientError.Closed("Couldn’t start voice: Invalid value: ’client’.")
                    }
                    callStream.trySend(VoiceCallEvent.Connected)
                    callStream.consumeAsFlow()
                },
                delegatorRound = { id ->
                    assertEquals("del_console_fb", id)
                    builtInRounds.update { it + 1 }
                    Result.success(VoiceDelegator.Response(id = "resp_console_fb", calls = emptyList(), text = "Handled via Built-in fallback."))
                },
                end = { callStream.close() },
            )
        }

        store.send(VoiceFeature.Action.Start) {
            it.serverStarted(VoiceProviderOption.Brand.openCode, usesDelegator = false, usesSessionDelegator = true)
        }
        store.receive(
            VoiceFeature.Action.CallRouteResolved(
                VoiceRoute.serverOpenAI(VoiceRoute.OpenAI.gptLive),
                usesDelegator = true,
                usesSessionDelegator = false,
                note = VoiceFeature.builtInFallbackNote,
            ),
        ) {
            it.copy(usesDelegator = true, usesSessionDelegator = false, callNote = VoiceFeature.builtInFallbackNote)
        }
        store.receive(VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected), update = connected(now))

        assertEquals(2, startedConfigs.value.size)
        assertEquals(LiveDelegation.client, startedConfigs.value[0].liveDelegation)
        assertEquals(true, startedConfigs.value[0].unboundSpeechUsesInstructions)
        val retryDelegation = sessionDelegation(startedConfigs.value[1])?.jsonObject
        assertEquals(RealtimeJSONs.string("responses"), retryDelegation?.get("type"))
        assertEquals(false, startedConfigs.value[1].unboundSpeechUsesInstructions)
        assertEquals(VoiceFeature.builtInFallbackNote, store.state.captionTargets.firstOrNull()?.text)
        assertEquals(
            "Saved preference stays untouched when one call falls back",
            VoiceDelegatorChoice.AutomaticSession,
            shared.get(VoiceSharedKeys.voiceDelegatorChoice),
        )

        store.send(VoiceFeature.Action.CallEvent(VoiceCallEvent.ServerDelegation(callID = "del_console_fb"))) {
            it.startingBuiltIn("del_console_fb")
        }
        val outcome = VoiceDelegator.Outcome(reply = "Handled via Built-in fallback.", unanswered = emptyList(), spoken = true)
        store.receive(VoiceFeature.Action.DelegatorFinished(callID = "del_console_fb", result = Result.success(outcome))) {
            it.finishingBuiltIn("del_console_fb", reply = "Handled via Built-in fallback.")
        }
        store.send(VoiceFeature.Action.EndTapped) { it.hungUp() }
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        store.finish()
        effects.skipInFlightEffects()
        assertEquals(1, builtInRounds.value)
    }

    @Test fun serverVoiceReconnectPreservesClientDelegationInBrokerSessionRequests() = runTest {
        val brokerRequests = Locked(listOf<ServerVoiceSessionRequest>())
        val opened = Channel<FakeLiveSocket>(Channel.UNLIMITED)
        val capture = Channel<ByteArray>(Channel.UNLIMITED)
        val audioEvents = Channel<VoiceAudioEvent>(Channel.UNLIMITED)
        // The shared harness only lends its virtual-time coordinator: this call connects through the broker.
        val harness = LiveHarness(
            this,
            VoiceCallTiming(
                rotateAfter = 3_000.0,
                forceRotateAfter = 3_500.0,
                retryDelays = listOf(0.01, 0.01),
                liveDelegationSettle = Duration.ZERO,
                liveTranscriptSettle = 5.seconds,
                liveAcknowledgementWait = Duration.ZERO,
                liveAppendQuiet = Duration.ZERO,
            ),
        )

        val route = VoiceRoute.serverOpenAI(VoiceRoute.OpenAI.gptLive)
        val context = VoiceInstructions.Context(
            sessionTitle = session.title,
            directoryName = "project",
            recentMessages = emptyList(),
            previousTail = emptyList(),
            previousEndedAt = null,
            isNewSession = false,
            managesSessions = true,
            viewing = null,
        )
        val realtimeConfig = route.sessionConfig(
            voice = CallVoice.marin,
            context = context,
            isAtEar = false,
            server = server,
            delegation = LiveDelegation.client,
            unboundSpeechUsesInstructions = true,
        )

        val brokerValues = DependencyValues.testing().update {
            this[ServerVoiceClient] = ServerVoiceClient(
                checkSupport = { ServerVoiceSupport.supported },
                models = { null },
                createSession = { requestedServer, request ->
                    assertEquals(server, requestedServer)
                    brokerRequests.update { it + request }
                    ServerVoiceTransportResponse.Webrtc(sdp = "v=0-answer")
                },
            )
        }
        val live = LiveClient { config ->
            val broker = VoiceBrokerTransport(requireNotNull(config.serverVoice))
            val answer = withContext(brokerValues.asContextElement()) { broker.exchange(config = config, sdp = "v=0-offer") }
            assertEquals("v=0-answer", answer)
            val socket = FakeLiveSocket(config)
            opened.trySend(socket)
            LiveConnection(events = socket.events, send = { socket.send(it) }, close = { socket.close() })
        }
        val audio = VoiceAudioClient(start = {
            VoiceAudioLevels.shared.reset()
            VoiceAudioSession(
                capture = capture.consumeAsFlow(),
                events = audioEvents.consumeAsFlow(),
                enqueue = { _, _ -> },
                flush = { 0 },
                setMuted = { },
                stop = { },
            )
        })

        val events = EventIterator(
            harness.coordinator.start(config = realtimeConfig.liveSessionConfig(), live = live, audio = audio),
            backgroundScope,
        )

        val firstSocket = opened.receive()
        firstSocket.yieldEvent(LiveServerEvent.SessionStarted(id = "live_sv_1", expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, events.next())
        assertEquals(1, brokerRequests.value.size)
        assertEquals(clientDelegation, brokerRequests.value[0].session?.jsonObject?.get("delegation"))
        assertNull(brokerRequests.value[0].session?.jsonObject?.get("input"))

        firstSocket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Check server voice.", startMilliseconds = 0, endMilliseconds = 300))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Check server voice.", final = true), events.next())
        firstSocket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_sv_1", target = "client", offsetMilliseconds = 300))
        assertEquals(VoiceCallEvent.FunctionCall(callID = "del_sv_1", name = "delegate", arguments = RealtimeJSONs.obj()), events.next())

        // Expire the first WebRTC session so `LiveCallCoordinator` reconnects via `open(seeded: true)` through `VoiceBrokerTransport`.
        firstSocket.yieldEvent(LiveServerEvent.SessionClosed(reason = LiveCloseReason.Expired, usageSeconds = null))
        val secondSocket = opened.receive()
        secondSocket.yieldEvent(LiveServerEvent.SessionStarted(id = "live_sv_2", expiresAt = null))
        while (true) {
            val next = events.next() ?: break
            if (next == VoiceCallEvent.Reconnected) break
        }

        assertEquals(2, brokerRequests.value.size)
        assertEquals(
            "Server Voice reconnect must re-send client delegation alongside seeded input",
            clientDelegation,
            brokerRequests.value[1].session?.jsonObject?.get("delegation"),
        )
        assertNotNull(brokerRequests.value[1].session?.jsonObject?.get("input"))

        harness.coordinator.end()
        capture.close()
        audioEvents.close()
    }

}
