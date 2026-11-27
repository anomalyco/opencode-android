package ai.opencode.feature.drawer.miniapps

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.random.Random

class MiniAppsGridTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun tile(key: String, x: Int, y: Int, w: Int, h: Int) = MiniAppsTile(key, x, y, w, h)

    @Test
    fun addsAtTheFirstFreeSpot() {
        var tiles = MiniAppsGrid.adding(emptyList(), "a", w = 2, h = 2)
        tiles = MiniAppsGrid.adding(tiles, "b", w = 2, h = 2)
        tiles = MiniAppsGrid.adding(tiles, "c", w = 4, h = 2)
        tiles = MiniAppsGrid.adding(tiles, "d", w = 2, h = 2)
        assertEquals(
            listOf(tile("a", 0, 0, 2, 2), tile("b", 2, 0, 2, 2), tile("c", 0, 2, 4, 2), tile("d", 0, 4, 2, 2)),
            tiles,
        )
        assertEquals(tiles, MiniAppsGrid.adding(tiles, "a"))
    }

    @Test
    fun addsIntoAGapBeforeTheEnd() {
        val tiles = listOf(tile("a", 0, 0, 2, 2), tile("b", 0, 2, 4, 2))
        assertEquals(tile("c", 2, 0, 2, 2), MiniAppsGrid.adding(tiles, "c", w = 2, h = 2).last())
    }

    @Test
    fun clampsToTheColumnsAndTheMinimumSize() {
        assertEquals(tile("a", 2, 0, 2, 2), MiniAppsGrid.clamped(tile("a", 3, -2, 1, 1)))
        assertEquals(tile("a", 0, 5, 4, 3), MiniAppsGrid.clamped(tile("a", 2, 5, 9, 3)))
    }

    @Test
    fun removingLetsTheRestFallUp() {
        val tiles = listOf(tile("a", 0, 0, 4, 2), tile("b", 0, 2, 2, 3), tile("c", 2, 2, 2, 2))
        assertEquals(listOf(tile("b", 0, 0, 2, 3), tile("c", 2, 0, 2, 2)), MiniAppsGrid.removing(tiles, "a"))
    }

    @Test
    fun movingOntoATilePushesItDownAndTheRestFollow() {
        val tiles = listOf(tile("a", 0, 0, 2, 2), tile("b", 2, 0, 2, 2), tile("c", 2, 2, 2, 2))
        // a slides right onto b's column, a row down: b has no room above it, so it goes below a, and c below b.
        val moved = MiniAppsGrid.moving(tiles, "a", tile("a", 2, 1, 2, 2))
        assertEquals(listOf(tile("a", 2, 1, 2, 2), tile("b", 2, 3, 2, 2), tile("c", 2, 5, 2, 2)), moved)
        assertTrue(MiniAppsGrid.isValid(moved))
    }

    @Test
    fun draggingDownOntoATileSwapsThem() {
        val tiles = listOf(tile("a", 0, 0, 4, 2), tile("b", 0, 2, 4, 2))
        val moved = MiniAppsGrid.moving(tiles, "a", tile("a", 0, 2, 4, 2))
        assertEquals(listOf(tile("a", 0, 2, 4, 2), tile("b", 0, 0, 4, 2)), moved)
        // Dropped, the layout compacts and stays swapped.
        assertEquals(moved, MiniAppsGrid.compacted(moved))
    }

    @Test
    fun othersFallUpWhileTheMovedTileIsHeld() {
        val tiles = listOf(tile("a", 0, 0, 2, 2), tile("b", 0, 2, 2, 2), tile("c", 2, 0, 2, 4))
        // a held below c at the bottom right: b falls into a's old spot, c stays.
        val moved = MiniAppsGrid.moving(tiles, "a", tile("a", 2, 6, 2, 2))
        assertEquals(listOf(tile("a", 2, 6, 2, 2), tile("b", 0, 0, 2, 2), tile("c", 2, 0, 2, 4)), moved)
        // Dropped, a falls up under c.
        assertEquals(tile("a", 2, 4, 2, 2), MiniAppsGrid.compacted(moved).first())
    }

    @Test
    fun movingBackRestoresTheLayout() {
        val tiles = listOf(tile("a", 0, 0, 2, 2), tile("b", 2, 0, 2, 2), tile("c", 0, 2, 4, 2))
        val away = MiniAppsGrid.moving(tiles, "a", tile("a", 0, 2, 2, 2))
        assertTrue(MiniAppsGrid.isValid(away))
        assertEquals(tiles, MiniAppsGrid.moving(tiles, "a", tile("a", 0, 0, 2, 2)))
    }

    @Test
    fun resizingPushesNeighboursDown() {
        val tiles = listOf(tile("a", 0, 0, 2, 2), tile("b", 2, 0, 2, 2), tile("c", 0, 2, 4, 2))
        val resized = MiniAppsGrid.moving(tiles, "a", tile("a", 0, 0, 4, 3))
        assertEquals(listOf(tile("a", 0, 0, 4, 3), tile("b", 2, 3, 2, 2), tile("c", 0, 5, 4, 2)), resized)
        assertTrue(MiniAppsGrid.isValid(resized))
    }

    @Test
    fun resizingSmallerLetsOthersFallUp() {
        val tiles = listOf(tile("a", 0, 0, 4, 4), tile("b", 0, 4, 2, 2))
        val resized = MiniAppsGrid.moving(tiles, "a", tile("a", 0, 0, 2, 2))
        assertEquals(listOf(tile("a", 0, 0, 2, 2), tile("b", 0, 2, 2, 2)), resized)
    }

    @Test
    fun neverOverlaps() {
        val random = Random(7)
        repeat(500) {
            var tiles = emptyList<MiniAppsTile>()
            repeat(random.nextInt(1, 8)) { i ->
                tiles = MiniAppsGrid.adding(tiles, "t$i", w = random.nextInt(1, 5), h = random.nextInt(1, 6))
            }
            assertTrue(MiniAppsGrid.isValid(tiles))
            repeat(6) {
                val moving = tiles.random(random)
                val target = MiniAppsTile(moving.key, random.nextInt(-2, 6), random.nextInt(-2, 12), random.nextInt(1, 6), random.nextInt(1, 7))
                val layout = MiniAppsGrid.moving(tiles, moving.key, target)
                assertTrue("$layout", MiniAppsGrid.isValid(layout))
                assertEquals(MiniAppsGrid.clamped(target), layout.first { it.key == moving.key })
                tiles = MiniAppsGrid.compacted(layout)
                assertTrue(MiniAppsGrid.isValid(tiles))
            }
        }
    }
}
