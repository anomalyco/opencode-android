package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class CallVoiceTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_voice", serverTitle = "Voice lab", projectID = "p", directory = "/Users/kit/project", updatedAt = Instant.parse("0001-01-01T00:00:00Z"))

    @Test fun defaultsToMarinAndPersists() {
        SpeechTestShared().run {
            val callVoice = shared(VoiceSharedKeys.callVoice)
            callVoice.set(CallVoice.default)
            assertEquals(CallVoice.marin, callVoice.value)

            callVoice.set(CallVoice.cedar)
            val reread = shared(VoiceSharedKeys.callVoice)
            assertEquals("Another reader of the key sees the choice", CallVoice.cedar, reread.value)

            callVoice.set(CallVoice.default)
        }
    }

    @Test fun eachEngineOffersItsDocumentedVoices() {
        assertEquals(
            listOf("alloy", "ash", "ballad", "cedar", "coral", "echo", "marin", "sage", "shimmer", "verse"),
            CallVoice.supported(VoiceEngine.realtime).map { it.rawValue },
        )
        assertEquals(22, CallVoice.supported(VoiceEngine.gptLive).size)
        assertEquals(CallVoice.allCases, CallVoice.supported(VoiceEngine.gptLive))
        for (voice in listOf(
            CallVoice.quartz, CallVoice.ripple, CallVoice.vesper, CallVoice.willow, CallVoice.stone, CallVoice.gleam,
            CallVoice.meridian, CallVoice.bossa, CallVoice.tempo, CallVoice.beacon, CallVoice.delta, CallVoice.cinder,
        )) {
            assertFalse("$voice is GPT-Live only", voice.isSupported(VoiceEngine.realtime))
            assertNotNull(voice.accent)
        }
        assertNull(CallVoice.marin.accent)
    }

    @Test fun anUnsupportedChoiceFallsBackToTheDefault() {
        assertEquals(CallVoice.quartz, CallVoice.quartz.resolved(VoiceEngine.gptLive))
        assertEquals(CallVoice.marin, CallVoice.quartz.resolved(VoiceEngine.realtime))
        assertEquals(CallVoice.cedar, CallVoice.cedar.resolved(VoiceEngine.realtime))
    }

    @Test fun startConfigsCarryTheVoice() {
        val live = LiveSessionConfig(model = "gpt-live-1", voice = CallVoice.quartz, instructions = "Be concise.")
        val start = Json.parseToJsonElement(LiveCommand.SessionStart(live).encoded().decodeToString())
        assertEquals(JsonPrimitive("quartz"), start["session"]?.get("audio")?.get("output")?.get("voice"))
        assertEquals(JsonPrimitive("quartz"), live.sessionPayload(includeAudioFormat = false)["audio"]?.get("output")?.get("voice"))

        val realtime = RealtimeSessionConfig(model = "gpt-realtime-2.1", voice = CallVoice.cedar, instructions = "Be brief.", tools = emptyList())
        assertEquals(JsonPrimitive("cedar"), realtime.sessionUpdate["session"]?.get("audio")?.get("output")?.get("voice"))
        assertEquals(JsonPrimitive("cedar"), realtime.sessionUpdate(includeAudioFormat = false)["session"]?.get("audio")?.get("output")?.get("voice"))
        assertEquals("GPT-Live's start is built from the same config", CallVoice.cedar, realtime.liveSessionConfig().voice)

        assertEquals(CallVoice.marin, LiveSessionConfig(model = "gpt-live-1", instructions = "").voice)
        assertEquals(CallVoice.marin, RealtimeSessionConfig(model = "gpt-realtime-2.1", instructions = "", tools = emptyList()).voice)
    }

    @Test fun callsStartWithTheChosenVoiceForTheirEngine() = runTest {
        val shared = SpeechTestShared()
        val started = Locked(listOf<RealtimeSessionConfig>())
        val store = TestStore(shared.run { VoiceFeature.State(server, session) }, VoiceFeature(), this) {
            this[SharedStore] = shared.store
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            // Android: the call's ongoing-notification ID.
            this[UuidClient] = UuidClient.incrementing()
            this[VoiceCallClient] = speechVoiceCall(start = { config ->
                started.update { it + config }
                emptyFlow()
            })
        }
        store.exhaustive = false
        store.withDependencies { shared(VoiceSharedKeys.voiceModelsByServer).set(mapOf(server.persistenceID to testVoiceModels)) }
        val voiceModel = store.withDependencies { shared(VoiceSharedKeys.voiceModel) }
        val callVoice = store.withDependencies { shared(VoiceSharedKeys.callVoice) }
        val callEnded = { action: VoiceFeature.Action -> action is VoiceFeature.Action.CallEnded }

        // GPT-Live speaks with its own regional voice.
        voiceModel.set("gpt-live-1")
        callVoice.set(CallVoice.quartz)
        store.send(VoiceFeature.Action.Start)
        store.receive(callEnded, timeout = 1.seconds)
        assertEquals(VoiceEngine.gptLive, started.value.lastOrNull()?.engine)
        assertEquals(CallVoice.quartz, started.value.lastOrNull()?.voice)
        assertEquals(JsonPrimitive("quartz"), started.value.lastOrNull()?.liveSessionConfig()?.sessionStart()?.get("session")?.get("audio")?.get("output")?.get("voice"))
        assertEquals(CallVoice.quartz, store.state.activeVoice)

        // Realtime doesn't offer it: the call uses the default, and the choice stays saved.
        voiceModel.set("gpt-realtime-2.1")
        store.send(VoiceFeature.Action.Start)
        store.receive(callEnded, timeout = 1.seconds)
        assertEquals(VoiceEngine.realtime, started.value.lastOrNull()?.engine)
        assertEquals(CallVoice.marin, started.value.lastOrNull()?.voice)
        assertEquals(CallVoice.quartz, callVoice.value)

        // A voice Realtime offers goes into its session update.
        callVoice.set(CallVoice.cedar)
        store.send(VoiceFeature.Action.Start)
        store.receive(callEnded, timeout = 1.seconds)
        assertEquals(CallVoice.cedar, started.value.lastOrNull()?.voice)
        assertEquals(JsonPrimitive("cedar"), started.value.lastOrNull()?.sessionUpdate?.get("session")?.get("audio")?.get("output")?.get("voice"))

        voiceModel.set("")
        callVoice.set(CallVoice.default)
        store.skipReceivedActions()
    }

    @Test fun theCallLogNamesTheVoice() = runTest {
        val shared = SpeechTestShared()
        shared.run {
            shared(VoiceSharedKeys.voiceModelsByServer).set(mapOf(server.persistenceID to testVoiceModels))
            shared(VoiceSharedKeys.callVoice).set(CallVoice.cedar)
        }
        val directory = Files.createTempDirectory("call-voice-").toFile()
        try {
            val date = DateClient.constant(Instant.EPOCH)
            val recorder = DependencyValues.withValues(DependencyValues.testing().update { this[DateClient] = date }) {
                VoiceCallLogRecorder(directory = directory)
            }
            val id = UUID.randomUUID()
            val store = TestStore(shared.run { VoiceFeature.State(server, session) }, VoiceFeature(), this) {
                this[SharedStore] = shared.store
                this[DateClient] = date
                this[UuidClient] = UuidClient { id }
                this[VoiceCallClient] = speechVoiceCall(start = { _ -> emptyFlow() })
                this[VoiceCallLogClient] = VoiceCallLogClient(recorder = recorder)
            }
            store.exhaustive = false

            store.send(VoiceFeature.Action.Start)
            store.receive({ it is VoiceFeature.Action.CallEnded }, timeout = 1.seconds)
            store.skipReceivedActions()
            val log = recorder.list().firstOrNull { it.id == id }
            assertNotNull(log)
            assertTrue(log!!.notes.any { it.kind == VoiceCallLog.Note.Kind.marker && it.text == "Voice: cedar" })

            val client = VoiceCallLogClient(recorder = recorder)
            assertEquals(listOf(id), client.list().map { it.id })
            client.delete(id)
            assertTrue(client.list().isEmpty())

            store.withDependencies { shared(VoiceSharedKeys.callVoice).set(CallVoice.default) }
        } finally {
            directory.deleteRecursively()
        }
    }
}
