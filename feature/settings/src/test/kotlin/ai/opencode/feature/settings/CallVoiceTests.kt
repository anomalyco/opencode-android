package ai.opencode.feature.settings

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.voice.CallVoice
import ai.opencode.feature.voice.VoiceEngine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CallVoiceTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun anUnsupportedChoiceFallsBackToTheDefault() {
        assertEquals("Cedar", CallVoicePicker.rowValue(CallVoice.cedar, engine = VoiceEngine.realtime))
        assertEquals("Quartz", CallVoicePicker.rowValue(CallVoice.quartz, engine = VoiceEngine.gptLive))
        assertEquals("Marin · Quartz unavailable", CallVoicePicker.rowValue(CallVoice.quartz, engine = VoiceEngine.realtime))
    }
}
