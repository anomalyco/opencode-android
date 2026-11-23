package ai.opencode.feature.session

import ai.opencode.core.sharing.SharedKeys
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class IdleVoiceSensorsTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_idle", serverTitle = "Idle", projectID = "p", directory = "/Users/kit/project", updatedAt = Instant.EPOCH)

    @Test fun aCoveredScreenStartsNoCallFromTheEar() = sessionTest { h ->
        h.sharedStore.reference(SharedKeys.voiceModeEnabled).set(true)
        val state = h.state { SessionFeature.State.session(server = server, session = session, cachedItems = emptyList()) }.copy(isCovered = true)
        val store = h.store(state)
        store.send(Action.RaisedToEar)
        store.finish()
        assertNull(store.state.voice)
    }

}
