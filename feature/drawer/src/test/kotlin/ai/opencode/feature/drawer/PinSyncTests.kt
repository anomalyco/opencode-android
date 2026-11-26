package ai.opencode.feature.drawer

import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeatureTestSupport.LockIsolated
import ai.opencode.feature.drawer.HomeFeatureTestSupport.append
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.session
import ai.opencode.feature.drawer.HomeFeatureTestSupport.settle
import ai.opencode.feature.drawer.HomeFeatureTestSupport.snapshot
import ai.opencode.feature.settings.HomeSettingsFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.spaces.SpacesClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Sync Pinned Sessions: the `pins` space and this phone's pins. */
class PinSyncTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private val otherKey = "other-server|x"
    private val sessions = listOf("a", "b", "c").map { session(it) }

    private fun key(id: String) = "${server.persistenceID}|$id"

    /** The server's `pins` space, and what the phone wrote to it. */
    private class Spaces {
        val pins = MutableStateFlow<Map<String, String>?>(null)
        val writes = LockIsolated<List<String>>(emptyList())
        val received = LockIsolated<List<OpenCodeServerEvent>>(emptyList())
        val client = SpacesClient(
            entries = { _, space -> if (space == "pins") pins.filterNotNull() else throw AssertionError(space) },
            receive = { _, event -> received.append(event) },
            set = { _, space, key, value -> writes.append("set $space/$key=$value") },
            delete = { _, space, key -> writes.append("delete $space/$key") },
        )

        fun remote(vararg ids: String) {
            pins.value = ids.associateWith { "1" }
        }
    }

    private fun switched(on: Boolean) =
        Action.Settings(HomeSettingsFeature.Action.ExperimentalFeatureChanged(ExperimentalFeature.syncPinnedSessions, on))

    private val syncOn = ExperimentalFeatures.enabling(setOf(ExperimentalFeature.syncPinnedSessions))

    @Test fun turningSyncOnMergesThisPhonesPinsIntoTheSpaceOnce() = homeTest { h ->
        h.setShared(SharedKeys.pinnedSessionKeys, listOf(key("a"), otherKey, key("b")))
        val spaces = Spaces()
        val store = h.store(h.state { HomeFeature.State(server, snapshot(sessions)) }) {
            this[ExperimentalFeatures] = syncOn
            this[SpacesClient] = spaces.client
        }
        store.send(switched(true))
        spaces.remote("b", "c")
        store.receive(Action.PinSpaceChanged(listOf("b", "c"))) {
            it.copy(pinnedSessionKeys = listOf(key("a"), otherKey, key("b"), key("c")))
        }
        settle()
        assertEquals("only the pin the space lacked is written", listOf("set pins/a=1"), spaces.writes.value)
        assertEquals(setOf(server.persistenceID), h.shared(SharedKeys.pinSyncServerIDs))

        // Merged: from now on the space decides.
        spaces.remote("c")
        store.receive(Action.PinSpaceChanged(listOf("c"))) { it.copy(pinnedSessionKeys = listOf(otherKey, key("c"))) }
        settle()
        assertEquals(listOf("set pins/a=1"), spaces.writes.value)

        store.send(Action.ServerEvent(OpenCodeServerEvent.StreamOpened("s1")))
        store.send(Action.ServerEvent(OpenCodeServerEvent.SpaceResync("pins")))
        settle()
        assertEquals(
            "the stream's space events go to the spaces client",
            listOf(OpenCodeServerEvent.StreamOpened("s1"), OpenCodeServerEvent.SpaceResync("pins")),
            spaces.received.value,
        )
    }

    @Test fun theSpacesAdditionsPinAtTheEndAndItsRemovalsUnpin() = homeTest { h ->
        h.setShared(SharedKeys.pinnedSessionKeys, listOf(key("b"), otherKey, key("a")))
        h.setShared(SharedKeys.pinSyncServerIDs, setOf(server.persistenceID))
        val spaces = Spaces()
        val store = h.store(h.state { HomeFeature.State(server, snapshot(sessions)) }) {
            this[ExperimentalFeatures] = syncOn
            this[SpacesClient] = spaces.client
        }
        store.send(Action.PinSpaceChanged(listOf("c", "a", "b"))) {
            it.copy(pinnedSessionKeys = listOf(key("b"), otherKey, key("a"), key("c")))
        }
        assertEquals("the order stays this phone's", listOf("b", "a", "c"), store.state.pinnedSessions.map { it.id })
        store.send(Action.PinSpaceChanged(listOf("c", "b"))) { it.copy(pinnedSessionKeys = listOf(key("b"), otherKey, key("c"))) }
        store.send(Action.PinSpaceChanged(listOf("b", "c")))
        settle()
        assertTrue("the space's own changes aren't written back", spaces.writes.value.isEmpty())
    }

    @Test fun pinningAndUnpinningHereWritesTheSpaceOnceMerged() = homeTest { h ->
        h.setShared(SharedKeys.pinnedSessionKeys, listOf(key("a"), key("b")))
        h.setShared(SharedKeys.pinSyncServerIDs, setOf(server.persistenceID))
        val spaces = Spaces()
        val store = h.store(h.state { HomeFeature.State(server, snapshot(sessions)) }) {
            this[ExperimentalFeatures] = syncOn
            this[SpacesClient] = spaces.client
        }
        store.send(Action.TogglePin("c")) { it.copy(pinnedSessionKeys = listOf(key("a"), key("b"), key("c"))) }
        store.send(Action.TogglePin("a")) { it.copy(pinnedSessionKeys = listOf(key("b"), key("c"))) }
        store.send(Action.MovePinUp("c")) { it.copy(pinnedSessionKeys = listOf(key("c"), key("b"))) }
        store.send(Action.DrawerRowDropped("b", pinnedIndex = null)) { it.copy(pinnedSessionKeys = listOf(key("c"))) }
        settle()
        assertEquals(
            "membership goes to the space; order doesn't",
            listOf("set pins/c=1", "delete pins/a", "delete pins/b"),
            spaces.writes.value,
        )
    }

    @Test fun turningSyncOffForgetsTheMergeAndStopsFollowing() = homeTest { h ->
        h.setShared(SharedKeys.pinSyncServerIDs, setOf(server.persistenceID, "other-server"))
        val spaces = Spaces()
        val store = h.store(h.state { HomeFeature.State(server, snapshot(sessions)) }) {
            this[ExperimentalFeatures] = syncOn
            this[SpacesClient] = spaces.client
        }
        store.send(switched(true))
        store.send(switched(false))
        spaces.remote("a")
        settle()
        assertTrue("turning it on again merges again", h.shared(SharedKeys.pinSyncServerIDs).isEmpty())
    }

    @Test fun withSyncOffPinsStayThisPhonesAlone() = homeTest { h ->
        h.setShared(SharedKeys.pinnedSessionKeys, listOf(key("a")))
        h.setShared(SharedKeys.pinSyncServerIDs, setOf(server.persistenceID))
        val spaces = Spaces()
        spaces.remote("b")
        val store = h.store(h.state { HomeFeature.State(server, snapshot(sessions)) }) {
            this[SpacesClient] = spaces.client
        }
        store.send(Action.TogglePin("c")) { it.copy(pinnedSessionKeys = listOf(key("a"), key("c"))) }
        store.send(Action.TogglePin("a")) { it.copy(pinnedSessionKeys = listOf(key("c"))) }
        store.send(Action.PinSpaceChanged(listOf("b")))
        settle()
        assertTrue(spaces.writes.value.isEmpty())
        assertEquals(listOf(key("c")), h.shared(SharedKeys.pinnedSessionKeys))
    }
}
