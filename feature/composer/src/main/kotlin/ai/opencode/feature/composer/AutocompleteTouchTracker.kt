package ai.opencode.feature.composer

import kotlin.math.hypot

/**
 * Tells a tap on an autocomplete row from a drag that scrolls the list (iOS
 * `AutocompleteTouchTracker`). A touch is a tap until it moves past [TAP_SLOP]. Points are
 * list-local dp.
 */
class AutocompleteTouchTracker {
    var start: AutocompletePoint? = null
        private set
    var isScrolling: Boolean = false
        private set
    private var lastY: Double = 0.0

    private fun reset() {
        start = null
        isScrolling = false
        lastY = 0.0
    }

    /** Follows the finger; returns how far to scroll once the touch is a drag. Scrolling starts where the touch crossed the slop, so content never jumps. */
    fun move(touchStart: AutocompletePoint, location: AutocompletePoint): Double? {
        if (start != touchStart) {
            reset()
            start = touchStart
        }
        if (!isScrolling) {
            if (distance(touchStart, location) <= TAP_SLOP) return null
            isScrolling = true
            lastY = location.y
            return null
        }
        val dy = location.y - lastY
        lastY = location.y
        return if (dy == 0.0) null else dy
    }

    /** Ends the touch; returns where it lifted when it was a tap. */
    fun end(touchStart: AutocompletePoint, location: AutocompletePoint): AutocompletePoint? {
        val wasScrolling = isScrolling && start == touchStart
        reset()
        if (wasScrolling || distance(touchStart, location) > TAP_SLOP) return null
        return location
    }

    companion object {
        /** UIKit's tap allowance (`UITapGestureRecognizer`'s allowable movement), in dp. */
        const val TAP_SLOP: Double = 10.0

        /** The selectable, settled row under [point] (list-local coordinates). */
        fun selectableRow(point: AutocompletePoint, rows: List<AutocompleteSimulation.DrawnRow>): String? =
            rows.lastOrNull { row ->
                row.item.isSelectable && !row.isLeaving && row.alpha > 0.5 && row.rect.contains(point)
            }?.id

        private fun distance(a: AutocompletePoint, b: AutocompletePoint): Double = hypot(b.x - a.x, b.y - a.y)
    }
}
