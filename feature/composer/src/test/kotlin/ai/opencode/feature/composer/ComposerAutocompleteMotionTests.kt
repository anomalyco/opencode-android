package ai.opencode.feature.composer

import ai.opencode.core.motion.Motion
import ai.opencode.server.model.OpenCodeAutocompleteSkill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Port of iOS `ComposerAutocompleteMotionTests.swift`: search results swap near-instantly and
 * retarget instead of queueing; a drag through the list scrolls and never picks a row. Lengths in
 * dp (iOS points).
 */
class ComposerAutocompleteMotionTests {
    private val width = 360.0
    private val dt = 1.0 / 120.0

    private fun skill(name: String): AutocompleteItem = AutocompleteItem.skill(OpenCodeAutocompleteSkill(id = name, name = name, description = null))

    private fun sync(simulation: AutocompleteSimulation, items: List<AutocompleteItem>, query: String) {
        simulation.sync(
            isOpen = true,
            mode = AutocompleteTrigger.Mode.Mention,
            query = query,
            key = "at:0",
            items = items,
            selectedRowID = null,
            userMoved = false,
            composerWidth = width,
        )
    }

    private fun settle(simulation: AutocompleteSimulation, seconds: Double) {
        repeat((seconds / dt).roundToInt()) { simulation.step(dt = dt, composerWidth = width) }
    }

    // Search motion

    @Test fun searchUpdatesCommitAtOnceAndSettleOnTheCollapseRole() {
        val simulation = AutocompleteSimulation(AutocompleteParameters())
        val all = listOf("kit-motion", "puckett", "plain-prose", "effect", "solidjs").map(::skill)
        sync(simulation, all, query = "")
        settle(simulation, seconds = 1.0)

        sync(simulation, listOf(all[1], all[2]), query = "p")
        assertNull("A keystroke's results commit at once, never queued", simulation.pendingCommitAt)
        assertEquals(listOf(all[1].id, all[2].id), simulation.rows.filter { it.isEntering }.map { it.id })

        // Typing stays crisp: the reflow is `Motion.collapse`, settled within twice its duration.
        settle(simulation, seconds = 2 * Motion.collapse.curve().duration)
        val frame = simulation.frame(composerWidth = width)
        assertTrue("Height settles to 2 rows on the collapse role: ${frame.listHeight}", abs(frame.listHeight - 80) < 1)
        assertTrue("Search rows never blur or scale", frame.rows.all { it.blur == 0.0 && it.scale == 1.0 })
    }

    @Test fun aListPulledPastItsEndRubberBandsAndSpringsHome() {
        val simulation = AutocompleteSimulation(AutocompleteParameters())
        val all = (0 until 12).map { skill("row-$it") }
        sync(simulation, all, query = "")
        settle(simulation, seconds = 1.0)

        simulation.scroll(-60.0, width - 24)
        val first = simulation.overscroll.x
        assertTrue("Past the end the list gives less than the finger moved: $first", first < 0 && first > -60)
        assertEquals(0.0, simulation.scrollOffset, 0.0)
        simulation.scroll(-60.0, width - 24)
        assertTrue(simulation.overscroll.x < first && abs(simulation.overscroll.x - first) < abs(first))

        simulation.endScroll()
        assertEquals(0.0, simulation.scrollExcess, 0.0)
        settle(simulation, seconds = 1.0)
        assertTrue(abs(simulation.overscroll.x) < 0.01)
    }

    @Test fun reduceMotionHoldsTheListAtItsEnd() {
        val simulation = AutocompleteSimulation(AutocompleteParameters(reducedMotion = true))
        sync(simulation, (0 until 12).map { skill("row-$it") }, query = "")
        settle(simulation, seconds = 1.0)
        simulation.scroll(-60.0, width - 24)
        assertTrue(simulation.overscroll.x == 0.0 && simulation.scrollOffset == 0.0)
    }

    @Test fun resultsArrivingMidAnimationRetargetInsteadOfQueueing() {
        val simulation = AutocompleteSimulation(AutocompleteParameters())
        val all = listOf("kit-motion", "puckett", "plain-prose", "effect", "solidjs").map(::skill)
        sync(simulation, all, query = "")
        settle(simulation, seconds = 1.0)

        sync(simulation, listOf(all[1], all[2]), query = "p")
        settle(simulation, seconds = 0.03)
        val midHeight = simulation.frame(composerWidth = width).listHeight
        sync(simulation, listOf(all[1]), query = "pu")
        assertNull(simulation.pendingCommitAt)
        assertEquals(listOf(all[1].id), simulation.rows.filter { it.isEntering }.map { it.id })
        val afterRetarget = simulation.frame(composerWidth = width).listHeight
        assertTrue("Retargeting never jumps the height", abs(afterRetarget - midHeight) < 1.5)

        var previous = afterRetarget
        repeat(ceil(2 * Motion.collapse.curve().duration / dt).toInt()) {
            simulation.step(dt = dt, composerWidth = width)
            val height = simulation.frame(composerWidth = width).listHeight
            assertTrue("Height eases straight to the new target with no overshoot", height <= previous + 0.01)
            previous = height
        }
        assertTrue(abs(previous - 40) < 1)
    }

    @Test fun unchangedRowsKeepTheirIdentityAndDoNotReanimate() {
        val simulation = AutocompleteSimulation(AutocompleteParameters())
        val all = listOf("kit-motion", "puckett", "plain-prose").map(::skill)
        sync(simulation, all, query = "")
        settle(simulation, seconds = 1.0)
        val kept = simulation.rows.first { it.id == all[0].id }
        val keptP = kept.p.x
        val keptSlot = kept.slot.x

        sync(simulation, listOf(all[0], all[2]), query = "e")
        val after = simulation.rows.first { it.id == all[0].id }
        assertTrue("A row that stays never fades again", after.p.x == keptP && after.p.t == 1.0)
        assertTrue("A row that stays never reopens its slot", after.slot.x == keptSlot && after.slot.t == 1.0)
        assertTrue("A row that keeps its slot never glides", after.dy.x == 0.0 && after.dy.t == 0.0)
    }

    @Test fun reduceMotionSnapsHeightAndRowsOnSearch() {
        val simulation = AutocompleteSimulation(AutocompleteParameters(reducedMotion = true))
        val all = listOf("kit-motion", "puckett", "plain-prose", "effect").map(::skill)
        sync(simulation, all, query = "")
        settle(simulation, seconds = 1.0)

        sync(simulation, listOf(all[1]), query = "pu")
        val frame = simulation.frame(composerWidth = width)
        assertTrue("Height snaps under Reduce Motion", abs(frame.listHeight - 40) < 0.5)
        assertTrue("Rows never glide under Reduce Motion", simulation.rows.all { it.dy.x == 0.0 && it.dx.x == 0.0 })
        assertTrue(frame.rows.all { it.scale == 1.0 && it.blur == 0.0 })
    }

    // Scroll vs select

    @Test fun aTouchWithinTheTapSlopIsATap() {
        val tracker = AutocompleteTouchTracker()
        val start = AutocompletePoint(100.0, 100.0)
        assertNull(tracker.move(start, AutocompletePoint(103.0, 104.0)))
        assertFalse(tracker.isScrolling)
        assertEquals(AutocompletePoint(103.0, 104.0), tracker.end(start, AutocompletePoint(103.0, 104.0)))
    }

    @Test fun aDragPastTheSlopScrollsWithoutAJumpAndPicksNothing() {
        val tracker = AutocompleteTouchTracker()
        val start = AutocompletePoint(100.0, 100.0)
        assertNull(tracker.move(start, AutocompletePoint(100.0, 106.0)))
        assertNull("Crossing the slop starts scrolling from there", tracker.move(start, AutocompletePoint(100.0, 112.0)))
        assertTrue(tracker.isScrolling)
        assertEquals(18.0, tracker.move(start, AutocompletePoint(100.0, 130.0)))
        assertEquals(-29.0, tracker.move(start, AutocompletePoint(100.0, 101.0)))
        assertNull("Lifting after a scroll picks nothing, even back near the start", tracker.end(start, AutocompletePoint(100.0, 101.0)))
        // iOS `tracker == AutocompleteTouchTracker()`: back to a fresh tracker.
        assertNull(tracker.start)
        assertFalse(tracker.isScrolling)
    }

    @Test fun aFlickThatEndsPastTheSlopPicksNothing() {
        val tracker = AutocompleteTouchTracker()
        val start = AutocompletePoint(100.0, 100.0)
        assertNull(tracker.end(start, AutocompletePoint(100.0, 140.0)))
    }

    @Test fun aNewTouchForgetsACancelledDrag() {
        val tracker = AutocompleteTouchTracker()
        val first = AutocompletePoint(100.0, 100.0)
        tracker.move(first, AutocompletePoint(100.0, 160.0))
        assertTrue(tracker.isScrolling)
        val second = AutocompletePoint(50.0, 50.0)
        assertNull(tracker.move(second, AutocompletePoint(51.0, 51.0)))
        assertFalse(tracker.isScrolling)
        assertEquals(AutocompletePoint(51.0, 51.0), tracker.end(second, AutocompletePoint(51.0, 51.0)))
    }

    @Test fun tapsPickOnlySettledSelectableRows() {
        fun row(id: String, y: Double, leaving: Boolean = false, alpha: Double = 1.0, item: AutocompleteItem? = null) =
            AutocompleteSimulation.DrawnRow(
                id = id,
                item = item ?: skill(id),
                rect = AutocompleteRect(0.0, y, 300.0, 40.0),
                fullSize = AutocompleteSize(300.0, 40.0),
                isLeaving = leaving,
                alpha = alpha,
                blur = 0.0,
                scale = 1.0,
                isHighlighted = false,
                cornerRadius = 16.0,
            )
        val rows = listOf(
            row("a", y = 0.0),
            row("b", y = 40.0, leaving = true),
            row("c", y = 80.0, alpha = 0.2),
            row("empty", y = 120.0, item = AutocompleteItem.empty(query = "zz")),
        )
        assertEquals("a", AutocompleteTouchTracker.selectableRow(AutocompletePoint(10.0, 20.0), rows))
        assertNull(AutocompleteTouchTracker.selectableRow(AutocompletePoint(10.0, 60.0), rows))
        assertNull(AutocompleteTouchTracker.selectableRow(AutocompletePoint(10.0, 100.0), rows))
        assertNull(AutocompleteTouchTracker.selectableRow(AutocompletePoint(10.0, 140.0), rows))
    }
}
