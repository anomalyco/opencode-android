package ai.opencode.feature.transcript.list

import ai.opencode.core.motion.Motion
import ai.opencode.feature.transcript.selection.TranscriptReplySelection
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The scrolls the transcript list makes itself. The list is reversed: index 0 is the newest row. */
internal class TranscriptListScroller(
    val state: LazyListState,
    private val scope: CoroutineScope,
    val selection: TranscriptReplySelection,
) {
    var flingBehavior: FlingBehavior? = null

    var glidingToEnd by mutableStateOf(false)
        private set

    var reduceMotion = false

    var selectionHoldsEnd by mutableStateOf(false)
        private set

    var holdsEarlierHistory by mutableStateOf(false)
        private set

    private var glide: Job? = null

    val isAtEnd: Boolean get() = !state.canScrollBackward

    val isFollowing: Boolean get() = isAtEnd && !selectionHoldsEnd

    val isCoasting: Boolean get() = state.isScrollInProgress || glidingToEnd || borrowed != null

    fun scrollToEnd() {
        if (selection.isSelected) selection.clear()
        selectionHoldsEnd = false
        cancelBorrowedDrag()
        glide?.cancel()
        glidingToEnd = true
        val job = scope.launch {
            if (reduceMotion) {
                state.scrollToItem(0)
                return@launch
            }
            val screen = visibleHeight()
            val cutTo = (screen * GLIDE_SCREENS).toInt()
            val measured = distanceToEnd()
            val far = if (measured != null) measured > screen * CUT_SCREENS else estimatedDistanceToEnd() > screen * ESTIMATE_CUT_SCREENS
            if (far) {
                state.scrollToItem(0, cutTo)
            }
            glideToEnd(if (far) cutTo else measured ?: estimatedDistanceToEnd(), exact = far || measured != null)
        }
        glide = job
        job.invokeOnCompletion { if (glide === job) glidingToEnd = false }
    }

    private fun visibleHeight(): Int {
        val info = state.layoutInfo
        return info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
    }

    private fun distanceToEnd(): Int? {
        val newest = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 } ?: return null
        return maxOf(0, -newest.offset)
    }

    private fun estimatedDistanceToEnd(): Int {
        val rows = state.layoutInfo.visibleItemsInfo
        val newestShown = rows.minByOrNull { it.index } ?: return Int.MAX_VALUE
        val averageHeight = rows.sumOf { it.size } / rows.size
        return maxOf(0, -newestShown.offset) + newestShown.index * averageHeight
    }

    private suspend fun glideToEnd(start: Int, exact: Boolean) {
        glide(start, towardEnd = true, measured = ::distanceToEnd, estimated = { if (exact) null else estimatedDistanceToEnd() })
    }

    /** Glides [start] pixels, re-aimed each frame at what's [measured] or else [estimated] to go. */
    private suspend fun glide(start: Int, towardEnd: Boolean, measured: () -> Int?, estimated: () -> Int? = { null }) {
        val direction = if (towardEnd) -1f else 1f
        val screen = visibleHeight().coerceAtLeast(1)
        val share = (abs(start).toFloat() / screen).coerceIn(0f, 1f)
        val seconds = JUMP_MIN_SECONDS + (JUMP_MAX_SECONDS - JUMP_MIN_SECONDS) * share
        val duration = Motion.millis(seconds) * 1_000_000L
        val limit = Motion.millis(JUMP_LIMIT_SECONDS) * 1_000_000L
        state.scroll {
            val begin = withFrameNanos { it }
            var travelled = 0f
            while (true) {
                val now = withFrameNanos { it }
                val target = (measured() ?: estimated())?.let { travelled + it } ?: start.toFloat()
                val fraction = ((now - begin).toFloat() / duration).coerceIn(0f, 1f)
                val position = target * JUMP_EASING.transform(fraction)
                travelled += direction * scrollBy(direction * (position - travelled))
                if (fraction >= 1f || measured() == 0 || now - begin > limit) break
            }
            val left = measured()
            if (left != null && left != 0) scrollBy(direction * left)
        }
    }

    /** Set each composition: indices of the rows that are user messages, oldest last. */
    var userMessageIndices: List<Int> = emptyList()

    /** The nearest user message whose top is above the visible area. */
    fun previousUserMessageIndex(): Int? {
        val rows = state.layoutInfo.visibleItemsInfo
        val top = rows.lastOrNull() ?: return null
        for (index in userMessageIndices) {
            if (index > top.index) return index
            val row = rows.firstOrNull { it.index == index } ?: continue
            if (rowTop(row.offset, row.size) < -LANDED_SLACK_PX) return index
        }
        return null
    }

    fun scrollToPreviousUserMessage() {
        val index = previousUserMessageIndex() ?: return
        val key = keys().getOrNull(index) ?: return
        if (selection.isSelected) selection.clear()
        selectionHoldsEnd = false
        cancelBorrowedDrag()
        glide?.cancel()
        glidingToEnd = false
        glide = scope.launch {
            val screen = visibleHeight()
            if (reduceMotion) {
                placeRowTop(index, above = 0)
                return@launch
            }
            val measured = distanceToRowTop(key)
            val far = measured == null || measured > screen * CUT_SCREENS
            val cutTo = (screen * GLIDE_SCREENS).toInt()
            if (far) placeRowTop(index, above = cutTo)
            glide(if (far) cutTo else measured ?: 0, towardEnd = false, measured = { distanceToRowTop(key) })
        }
    }

    /** How far the row's top is above the visible area's top; null while it isn't laid out. */
    private fun distanceToRowTop(key: Any): Int? {
        val row = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return null
        return -rowTop(row.offset, row.size)
    }

    private suspend fun placeRowTop(index: Int, above: Int) {
        if (index + 1 < state.layoutInfo.totalItemsCount) {
            state.scrollToItem(index + 1, -(visibleHeight() + above))
        } else {
            state.scrollToItem(index)
            val row = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
            state.scrollBy((-rowTop(row.offset, row.size) - above).toFloat())
        }
    }

    private var resizeHold: Job? = null

    private class Toggle(val key: Any, val top: Int, val height: Int, val atEnd: Boolean) {
        var target = height
    }

    private var toggle: Toggle? = null

    fun rowToggled(key: Any) {
        val row = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
        if (row == null || state.isScrollInProgress) {
            toggle = null
            return
        }
        val toggled = Toggle(key, rowTop(row.offset, row.size), row.size, isAtEnd)
        toggle = toggled
        resizeHold?.cancel()
        if (!toggled.atEnd) hold { holdToggledRow(toggled) }
    }

    fun rowResizing(key: Any, target: Int, nearEndDistance: Float) {
        if (state.isScrollInProgress) return
        val toggled = toggle
        if (toggled != null && toggled.key == key) {
            toggled.target = target
            if (toggled.atEnd && target - toggled.height > maxOf(toggled.top, 0) && resizeHold?.isActive != true) {
                hold { holdToggledRow(toggled) }
            }
            if (toggled.atEnd || resizeHold?.isActive == true) return
        }
        val nearEnd = state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset <= nearEndDistance
        if (nearEnd && !selectionHoldsEnd) return
        hold { holdTopRow(keys()) }
    }

    private fun hold(step: () -> Unit) {
        resizeHold?.cancel()
        step()
        resizeHold = scope.launch {
            val start = withFrameMillis { it }
            var sizes = visibleSizes()
            var still = 0
            while (!state.isScrollInProgress) {
                step()
                val now = withFrameMillis { it }
                val next = visibleSizes()
                still = if (next == sizes) still + 1 else 0
                sizes = next
                if (still >= SETTLED_FRAMES && now - start > RESIZE_START_MILLIS) break
                if (now - start > RESIZE_HOLD_LIMIT_MILLIS) break
            }
        }
    }

    private fun visibleSizes(): List<Int> = state.layoutInfo.visibleItemsInfo.map { it.size }

    private fun holdToggledRow(toggled: Toggle) {
        val row = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == toggled.key } ?: return
        val index = keys().indexOf(toggled.key)
        if (index < 0) return
        val height = visibleHeight()
        var top = toggled.top
        if (toggled.atEnd) {
            // Decided from the target height: the current one is a frame old.
            val room = maxOf(toggled.top, 0)
            val growth = toggled.target - toggled.height
            if (growth <= room) {
                state.requestScrollToItem(0)
                return
            }
            val grown = (row.size - toggled.height).coerceIn(0, growth)
            top = toggled.top - room * grown / growth
        }
        if (index + 1 < keys().size) {
            // Pinned by the row above: pinning its own bottom trails the growth by a frame.
            state.requestScrollToItem(index + 1, -(height - top))
        } else {
            state.requestScrollToItem(index, -(height - top - row.size))
        }
    }

    private fun rowTop(offset: Int, size: Int): Int = visibleHeight() - offset - size

    var keys: () -> List<Any> = { emptyList() }

    fun scrollToTop(): Boolean {
        cancelBorrowedDrag()
        val last = state.layoutInfo.totalItemsCount - 1
        if (last < 0) return false
        scope.launch { state.animateScrollToItem(last) }
        return true
    }

    private fun holdRowTop(key: Any, keys: List<Any>) {
        val row = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        // Looked up in this composition's rows: new rows shift every index.
        val index = keys.indexOf(key)
        if (index < 0) return
        if (index + 1 < keys.size) {
            state.requestScrollToItem(index + 1, -(row.offset + row.size))
        } else {
            state.requestScrollToItem(index, -row.offset)
        }
    }

    fun holdTopRow(keys: List<Any>) {
        val top = state.layoutInfo.visibleItemsInfo.lastOrNull() ?: return
        holdRowTop(top.key, keys)
    }

    fun showRowTop(index: Int): Job = scope.launch {
        holdsEarlierHistory = true
        snapshotFlow { state.layoutInfo }.drop(1).first()
        if (state.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            if (index >= state.layoutInfo.totalItemsCount) return@launch
            state.scrollToItem(index)
        }
        val info = state.layoutInfo
        val row = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return@launch
        val visibleHeight = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
        // Offsets count up from the bottom.
        val distance = (row.offset + row.size - visibleHeight).toFloat()
        if (reduceMotion) state.scrollBy(distance) else state.animateScrollBy(distance)
    }

    fun selectionActiveChanged(active: Boolean, byReader: Boolean) {
        if (active) {
            if (isAtEnd) selectionHoldsEnd = true
            return
        }
        if (!byReader) selectionHoldsEnd = false
        if (selectionHoldsEnd) {
            selectionHoldsEnd = false
            scrollToEnd()
        }
    }

    fun selectionScrolled() {
        selectionHoldsEnd = false
    }

    fun scrollInProgressChanged(scrolling: Boolean) {
        if (!scrolling && selection.isSelected && !selection.isDragging && !selection.menuWanted) selection.presentMenu()
    }

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) {
                selectionHoldsEnd = false
                holdsEarlierHistory = false
                glide?.cancel()
            }
            return Offset.Zero
        }
    }

    private sealed interface BorrowedStep {
        data class Move(val by: Float) : BorrowedStep
        data class Fling(val velocity: Float) : BorrowedStep
    }

    private class BorrowedDragRun {
        val steps = Channel<BorrowedStep>(Channel.UNLIMITED)
        var released = false
        var job: Job? = null
    }

    private var borrowed: BorrowedDragRun? = null

    private var borrowedHeld = false

    fun handleBorrowedDrag(event: TranscriptBorrowedDrag.Event) {
        when (event) {
            is TranscriptBorrowedDrag.Event.Moved -> {
                if (borrowedHeld) return
                var run = borrowed
                if (run == null) {
                    run = startBorrowedDrag()
                    if (run == null) return
                }
                // Reversed list: a finger moving down scrolls forward.
                run.steps.trySend(BorrowedStep.Move(event.dy))
            }
            is TranscriptBorrowedDrag.Event.Ended -> {
                borrowedHeld = false
                val run = borrowed ?: return
                run.released = true
                run.steps.trySend(BorrowedStep.Fling(event.velocity))
                run.steps.close()
            }
        }
    }

    private fun startBorrowedDrag(): BorrowedDragRun? {
        if (state.isScrollInProgress && borrowed == null && !glidingToEnd) {
            borrowedHeld = true
            return null
        }
        glide?.cancel()
        selectionHoldsEnd = false
        holdsEarlierHistory = false
        val run = BorrowedDragRun()
        borrowed = run
        run.job = scope.launch {
            var finished = false
            try {
                state.scroll(MutatePriority.UserInput) {
                    for (step in run.steps) {
                        when (step) {
                            is BorrowedStep.Move -> scrollBy(step.by)
                            is BorrowedStep.Fling -> {
                                val fling = flingBehavior
                                if (fling != null && step.velocity != 0f) {
                                    with(fling) { performFling(step.velocity) }
                                }
                            }
                        }
                    }
                }
                finished = true
            } finally {
                if (borrowed === run) borrowed = null
                if (!finished && !run.released) borrowedHeld = true
            }
        }
        return run
    }

    fun cancelBorrowedDrag() {
        val run = borrowed ?: return
        borrowed = null
        if (!run.released) borrowedHeld = true
        run.steps.close()
        run.job?.cancel()
    }

    private companion object {
        const val GLIDE_SCREENS = 1f
        const val CUT_SCREENS = 1.25f

        const val ESTIMATE_CUT_SCREENS = 2f

        const val JUMP_MIN_SECONDS = 0.35
        const val JUMP_MAX_SECONDS = 0.55

        val JUMP_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)

        const val JUMP_LIMIT_SECONDS = 1.5

        const val LANDED_SLACK_PX = 2

        const val RESIZE_START_MILLIS = 100L
        const val SETTLED_FRAMES = 3
        const val RESIZE_HOLD_LIMIT_MILLIS = 5_000L
    }
}
