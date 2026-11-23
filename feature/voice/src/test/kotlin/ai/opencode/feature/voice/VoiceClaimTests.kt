package ai.opencode.feature.voice

import ai.opencode.feature.voice.VoiceFeatureTestSupport.copy
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.preserveSettings
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the voice agent said in an earlier call doesn't come back as something to act on.
 */
class VoiceClaimTests {

    /**
     * A past call's tail line ("Voice: No. Just to Olive.") is dropped at the source by continuity
     * stripping and mid-call retarget, so it's never replayed.
     */
    @Test fun oliveCallPastVoiceLinesAreNotReplayedAcrossCallsOrRetarget() {
        val shared = preserveSettings()
        val server = OpenCodeServer(url = "https://example.test", username = "", password = "")
        val ios = OpenCodeSession(id = "ses_olive_a", serverTitle = "OPENCODE IOS", projectID = "p", directory = "/tmp/ios", updatedAt = VoiceFeatureTestSupport.distantPast)
        val hex = OpenCodeSession(id = "ses_olive_b", serverTitle = "HEX", projectID = "p", directory = "/tmp/hex", updatedAt = VoiceFeatureTestSupport.distantPast)
        val key = "${server.id}:${hex.id}"

        val pastOliveTail = listOf(
            "user: Did you check notifications?",
            "assistant: And that text to Olive went through, delivered.",
            "Voice: No. Just to Olive.",
        )
        val record = VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(100), tail = emptyList(), log = emptyList())
            .appending(tail = pastOliveTail, log = pastOliveTail, endedAt = Instant.ofEpochSecond(200))
        assertEquals(listOf("user: Did you check notifications?"), record.tail)

        // Even if an older persisted record still held the raw past-call Voice lines, neither a new call nor a mid-call retarget replays them.
        shared.set(
            VoiceSharedKeys.voiceTranscripts,
            mapOf(key to VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(200), tail = pastOliveTail, log = emptyList())),
        )
        val freshCall = shared.read { VoiceFeature.State(server, hex) }
        assertEquals(listOf("user: Did you check notifications?"), freshCall.previousCallTail)
        val built = VoiceInstructions.build(freshCall.instructionsContext, engine = VoiceEngine.gptLive)
        assertFalse(built.contains("Olive"))
        assertFalse(built.contains("went through"))

        val activeCall = shared.read {
            VoiceFeature.State(server, ios)
                .withPhase(VoiceFeature.State.Phase.Live)
                .copy(connectedAt = Instant.ofEpochSecond(300))
                .retarget(hex, reason = "you opened it", endedAt = Instant.ofEpochSecond(310))
        }
        assertTrue(activeCall.previousCallTail.isEmpty())
        assertFalse(VoiceInstructions.buildRetarget(activeCall.instructionsContext, engine = VoiceEngine.gptLive).contains("Olive"))
    }
}
