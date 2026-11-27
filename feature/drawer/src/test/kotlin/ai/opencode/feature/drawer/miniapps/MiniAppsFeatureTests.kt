package ai.opencode.feature.drawer.miniapps

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MiniAppsFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    @Test
    fun removingTheLastTileLeavesEditMode() {
        val tiles = listOf(MiniAppsTile("a", 0, 0, 2, 2), MiniAppsTile("b", 2, 0, 2, 2))
        val editing = MiniAppsFeature.State(server, tiles = tiles, isEditing = true)
        val one = MiniAppsFeature().reduce(editing, MiniAppsFeature.Action.RemoveTapped("a")).state
        assertEquals(listOf("b"), one.tiles.map { it.key })
        assertTrue(one.isEditing)
        // With no tile left there is no Done (nor the views it hides), so edit mode ends with the last tile.
        val none = MiniAppsFeature().reduce(one, MiniAppsFeature.Action.RemoveTapped("b")).state
        assertEquals(emptyList<MiniAppsTile>(), none.tiles)
        assertFalse(none.isEditing)
    }
}
