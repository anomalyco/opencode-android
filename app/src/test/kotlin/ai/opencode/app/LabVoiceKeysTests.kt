package ai.opencode.app

import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.testing.TestIsolationRule
import android.app.Application
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Port of iOS `LabVoiceKeysTests`: the lab's launch extras seed the voice keys, and `NONE` clears
 * one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LabVoiceKeysTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun seedsKeysAtLaunchAndNoneClears() {
        val shared = AppTestShared()
        shared.run {
            val openAI = shared(SharedKeys.openAIAPIKey)
            val xAI = shared(SharedKeys.xaiAPIKey)
            xAI.set("old")
            LabLaunch.apply(Intent().putExtra("lab-openai-key", "sk-lab").putExtra("lab-xai-key", "NONE"))
            assertEquals("sk-lab", openAI.value)
            assertNull(xAI.value)
            LabLaunch.apply(Intent())
            assertEquals("sk-lab", openAI.value)
        }
    }
}
