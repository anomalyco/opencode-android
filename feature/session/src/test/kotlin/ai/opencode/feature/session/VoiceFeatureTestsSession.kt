package ai.opencode.feature.session

import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.distantPast
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.voice.VoiceServerChoice
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared keys live in the test's own in-memory store. */
class VoiceFeatureTestsSession {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_voice", serverTitle = "Voice lab", projectID = "p", directory = "/Users/kit/project", updatedAt = distantPast)

    /** Voice needs its switch and a model the connected server lists; chosen automatically, that server takes the call. */
    @Test fun voiceAvailabilityRequiresBothAdvancedToggleAndAPIKey() = sessionTest { h ->
        val state = h.state { State.session(server = server, session = session, cachedItems = emptyList()) }
        state.voiceServer.set(VoiceServerChoice.Automatic)
        state.voiceModeEnabled.set(false)
        assertFalse(h.state { state.isVoiceAvailable })

        state.voiceModeEnabled.set(true)
        assertFalse("Its models aren't known yet", h.state { state.isVoiceAvailable })

        h.sharedStore.reference(VoiceSharedKeys.voiceModelsByServer).set(SessionTestSupport.voiceModels(server))
        assertTrue("The connected server lists a model, so it takes the call", h.state { state.isVoiceAvailable })
    }
}
