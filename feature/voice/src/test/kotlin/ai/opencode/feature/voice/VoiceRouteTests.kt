package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.asContextElement
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class VoiceRouteTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "route", serverTitle = "Routing", projectID = "p", directory = "/project", updatedAt = Instant.EPOCH)

    /**
     * 40 combinations. Expected routes are tabulated independently of the resolver: a model on a
     * broker whose support isn't unsupported routes by its engine; otherwise there's no route.
     */
    @Test fun completePrecedenceTable() {
        var count = 0
        val models = testVoiceModels.models + listOf(null, ServerVoiceModel(model = "gpt-live-1", available = true))
        for (model in models) {
            for (enabled in listOf(false, true)) {
                for (support in listOf(ServerVoiceSupport.unknown, ServerVoiceSupport.checking, ServerVoiceSupport.supported, ServerVoiceSupport.unsupported)) {
                    val expected: VoiceRoute = when {
                        !enabled || support == ServerVoiceSupport.unsupported -> VoiceRoute.Unavailable(VoiceRoute.Reason.ServerUnsupported)
                        model?.engine == "openai-live" -> VoiceRoute.serverOpenAI(VoiceRoute.OpenAI.gptLive)
                        model?.engine == "openai-realtime" -> VoiceRoute.serverOpenAI(VoiceRoute.OpenAI.realtime)
                        model?.engine == "gemini-live" -> VoiceRoute.serverGeminiLive
                        else -> VoiceRoute.Unavailable(VoiceRoute.Reason.NoVoiceModels)
                    }
                    val route = VoiceRoute.resolve(model = model, serverVoiceEnabled = enabled, serverVoiceSupport = support)
                    assertEquals(expected, route)
                    count += 1
                }
            }
        }
        assertEquals(40, count)
    }

    /** Each test's in-memory shared store starts from these values. */
    private fun preserveSettings(): VoiceTestShared {
        val shared = VoiceTestShared()
        shared.set(SharedKeys.voiceModeEnabled, true)
        shared.set(VoiceSharedKeys.voiceModelsByServer, mapOf(voiceServerID(server) to testVoiceModels))
        shared.set(VoiceSharedKeys.voiceServerChoice, VoiceServerChoice.Automatic)
        shared.set(VoiceSharedKeys.serverVoiceSupportByServer, emptyMap())
        shared.set(VoiceSharedKeys.voiceTranscripts, emptyMap())
        return shared
    }

    @Test fun serverEnabledStartupUsesResolvedModelAndCredentials() = runTest {
        val shared = preserveSettings()
        shared.set(VoiceSharedKeys.voiceModel, "gemini-3.8-live")
        val route: VoiceRoute = VoiceRoute.serverGeminiLive
        val state = shared.read { VoiceFeature.State(server, session) }.copy(serverVoiceSupport = ServerVoiceSupport.supported)
        val configs = mutableListOf<RealtimeSessionConfig>()
        val stream = CallEvents()
        val directory = Files.createTempDirectory("voice-route-").toFile()
        val logID = UUID.randomUUID()
        val recorder = DependencyValues.withValues(DependencyValues.testing().update { this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100)) }) {
            VoiceCallLogRecorder(directory = directory)
        }
        try {
            val store = voiceTestStore(shared, { state }) {
                this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100))
                this[UuidClient] = UuidClient { logID }
                this[VoiceCallLogClient] = VoiceCallLogClient(recorder = recorder)
                this[VoiceWorkClient] = testVoiceWork()
                this[ServerVoiceClient] = ServerVoiceClient(
                    checkSupport = { throw AssertionError("No probe needed") },
                    models = { null },
                    createSession = { _, _ -> throw AssertionError("Unused") },
                )
                this[VoiceCallClient] = testVoiceCall(
                    start = { config ->
                        configs += config
                        stream.yieldEvent(VoiceCallEvent.Connected)
                        stream.flow
                    },
                    setAtEar = { },
                    end = { stream.finish() },
                )
            }
            val activeVoice = shared.read { state.callVoice.resolved(VoiceEngine.gptLive) }
            store.send(VoiceFeature.Action.Start) {
                it.copy(
                    callLogID = logID,
                    startedTarget = state.target,
                    activeRoute = route,
                    activeVoice = activeVoice,
                    diagnosesFailure = true,
                    callPlace = VoiceCallPlace("Localhost", VoiceProviderOption.Brand.gemini),
                    activeModel = "gemini-3.8-live",
                    // Android: the call's ongoing notification, started with the call.
                    callPresenceID = logID,
                )
            }
            store.receive(VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected)) {
                it.withPhase(VoiceFeature.State.Phase.Live).copy(connectedAt = Instant.ofEpochSecond(100))
            }
            stream.finish()
            store.receive(VoiceFeature.Action.CallEnded(errorMessage = null)) {
                it.withPhase(VoiceFeature.State.Phase.Ended).copy(callLogID = null)
            }
            assertEquals(
                mapOf("${server.id}:${session.id}" to VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(100), tail = emptyList(), log = emptyList())),
                shared.get(VoiceSharedKeys.voiceTranscripts),
            )
            store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
            store.finish()
            val config = configs.firstOrNull() ?: throw AssertionError("The call started")
            assertEquals(route.engine, config.engine)
            assertEquals(server, config.serverVoice)
            assertEquals(route.logEngine, recorder.list().firstOrNull()?.engine)
            assertTrue(config.instructions.contains("Gemini Live (gemini-3.8-live) from Google"))
            assertFalse(config.instructions.contains("voice layer is the Realtime model"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun hangingUpDuringProbeOrPreflightNeverStartsTheCall() = runTest {
        val shared = preserveSettings()
        val clock = TestClock()
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val state = shared.read { VoiceFeature.State(server, session) }
        val store = voiceTestStore(shared, { state }) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100))
            this[Clock] = clock
            this[VoiceWorkClient] = testVoiceWork()
            this[ServerVoiceClient] = ServerVoiceClient(
                checkSupport = {
                    entered.trySend(Unit)
                    clock.sleep(60.seconds)
                    ServerVoiceSupport.supported
                },
                models = { null },
                createSession = { _, _ -> throw AssertionError("Unused") },
            )
            this[VoiceCallClient] = testVoiceCall(
                start = { _ -> throw AssertionError("A cancelled startup must never open a call") },
                end = { },
            )
        }
        store.send(VoiceFeature.Action.Start) {
            it.copy(
                startedTarget = state.target,
                activeRoute = VoiceRoute.serverOpenAI(VoiceRoute.OpenAI.gptLive),
                activeVoice = CallVoice.marin,
                diagnosesFailure = true,
                callPlace = VoiceCallPlace("Localhost", VoiceProviderOption.Brand.openAI),
                activeModel = "gpt-live-1",
                // Android: the call's ongoing notification, started with the call.
                callPresenceID = UUID(0, 0),
            )
        }
        entered.receive()
        store.send(VoiceFeature.Action.EndTapped) { it.withPhase(VoiceFeature.State.Phase.Ended) }
        assertEquals(
            mapOf("${server.id}:${session.id}" to VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(100), tail = emptyList(), log = emptyList())),
            shared.get(VoiceSharedKeys.voiceTranscripts),
        )
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        store.finish()
    }

    /** The server brokers the call's own session and hands back its SDP answer. */
    @Test fun theServerBrokerSendsTheCallsSessionAndReturnsItsAnswer() = runTest {
        val config = LiveSessionConfig(model = "gpt-live-1", instructions = "context")
        val expectedRequest = ServerVoiceSessionRequest(
            model = config.model, session = config.sessionPayload(includeAudioFormat = false),
            transport = ServerVoiceTransportRequest.Webrtc(sdp = "offer", dcid = LiveRTC.dataChannelID),
        )
        val values = DependencyValues.testing().update {
            this[ServerVoiceClient] = ServerVoiceClient(
                checkSupport = { ServerVoiceSupport.supported },
                models = { null },
                createSession = { requested, request ->
                    assertEquals(server, requested)
                    assertEquals(expectedRequest, request)
                    ServerVoiceTransportResponse.Webrtc(sdp = "server-answer")
                },
            )
        }
        val answer = withContext(values.asContextElement()) { VoiceBrokerTransport(server).exchange(config = config, sdp = "offer") }
        assertEquals("server-answer", answer)
    }

    /**
     * A refusal keeps its status: 0 when the server didn't answer, 404 without
     * the voice route, otherwise the server's own.
     */
    @Test fun serverBrokerRejectsWrongTransportAndPreservesRefusals() = runTest {
        val refusals: List<Pair<ServerVoiceError, LiveRTCRejected>> = listOf(
            ServerVoiceError.UnsupportedRoute to LiveRTCRejected(status = 404, message = ServerVoiceError.unsupportedServerNotice),
            ServerVoiceError.Rejected(status = 0, message = "Couldn’t start voice: The network connection was lost.") to
                LiveRTCRejected(status = 0, message = "Couldn’t start voice: The network connection was lost."),
            ServerVoiceError.Rejected(status = 503, message = "Couldn’t start voice: Voice needs an OpenAI API key on the server") to
                LiveRTCRejected(status = 503, message = "Couldn’t start voice: Voice needs an OpenAI API key on the server"),
        )
        suspend fun exchange(createSession: suspend (OpenCodeServer, ServerVoiceSessionRequest) -> ServerVoiceTransportResponse): Throwable? {
            val values = DependencyValues.testing().update {
                this[ServerVoiceClient] = ServerVoiceClient(checkSupport = { ServerVoiceSupport.supported }, models = { null }, createSession = createSession)
            }
            return withContext(values.asContextElement()) {
                runCatching { VoiceBrokerTransport(server).exchange(config = LiveSessionConfig(model = "gpt-live-1", instructions = "test"), sdp = "offer") }.exceptionOrNull()
            }
        }
        for ((error, rejected) in refusals) {
            assertEquals(rejected, exchange { _, _ -> throw error })
        }
        assertEquals(
            LiveRTCRejected(status = 0, message = ServerVoiceError.InvalidResponse.errorDescription),
            exchange { _, _ -> ServerVoiceTransportResponse.Websocket(url = "unused", token = "unused", expiresAt = null) },
        )
    }
}
