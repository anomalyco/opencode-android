package ai.opencode.feature.drawer.motion

import ai.opencode.core.ui.BackSwipeRules
import ai.opencode.core.ui.DrawerPanExclusion
import ai.opencode.core.ui.LocalDrawerPanExclusion
import ai.opencode.core.ui.excludedFromDrawerPan
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs

/**
 * Port of `OpenCode/Drawer/DirectionalPanGestureRecognizer.swift`: the session drawer's pan. It
 * watches every touch in the `Initial` pass, waits for 8 dp of travel
 * ([BackSwipeRules.decisionDistance]), then picks once: vertical or leftward on a shut drawer stays
 * with the content; rightward on the open drawer's list reveals the rows' times; rightward on a
 * scroller that can still scroll back, or a control that claims sideways drags, stays with it
 * except from the leading 28 dp edge; a finger that rested 0.45 s keeps its hold. Once chosen it
 * consumes the pointer. Touches are tested against the layout nodes registered in
 * [DrawerPanRegions].
 */
object DirectionalPan {
    enum class Decision {
        Begin,
        /** Slide the open drawer's rows to show their times. */
        Reveal,
        Fail,
        Wait,
    }

    /** Where a touch began (iOS `TouchOrigin`). */
    sealed interface TouchOrigin {
        data object DrawerRow : TouchOrigin
        data object DrawerList : TouchOrigin
        data object DrawerChrome : TouchOrigin
        data object ExposedCard : TouchOrigin
        data class Content(val horizontalScrollerOwnsRightwardDrag: Boolean) : TouchOrigin
    }

    data class CardSwipeOrigin(
        val onCard: Boolean,
        val scrollerOwnsRightwardDrag: Boolean = false,
        val scrollerOwnsLeftwardDrag: Boolean = false,
    ) {
        companion object {
            val outsideCard = CardSwipeOrigin(onCard = false)
        }
    }

    /** A card swipes either way, except while a horizontal scroller under the finger can still move that way. */
    fun cardSwipeDecision(origin: CardSwipeOrigin, dx: Float, dy: Float, decisionDistance: Float): Decision {
        if (dx * dx + dy * dy < decisionDistance * decisionDistance) return Decision.Wait
        if (!origin.onCard || abs(dx) <= abs(dy)) return Decision.Fail
        val scrollerOwnsDrag = if (dx > 0) origin.scrollerOwnsRightwardDrag else origin.scrollerOwnsLeftwardDrag
        return if (scrollerOwnsDrag) Decision.Fail else Decision.Begin
    }

    /** [dx] is toward the trailing side (mirror it in RTL); distances in one unit with [decisionDistance]. */
    fun decision(origin: TouchOrigin, presented: Boolean, dx: Float, dy: Float, decisionDistance: Float): Decision {
        if (dx * dx + dy * dy < decisionDistance * decisionDistance) return Decision.Wait
        if (abs(dx) <= abs(dy)) return Decision.Fail
        if (dx <= 0) return if (presented) Decision.Begin else Decision.Fail
        if (presented && (origin == TouchOrigin.DrawerRow || origin == TouchOrigin.DrawerList)) return Decision.Reveal
        if (!presented && origin == TouchOrigin.Content(horizontalScrollerOwnsRightwardDrag = true)) return Decision.Fail
        return Decision.Begin
    }

    /**
     * A touch that rested before moving (a text selection, a pin's lift, a context menu) keeps
     * doing what the hold began. In Catch Up, holding before dragging still drags the card.
     */
    fun yieldsToHold(heldForSeconds: Double, holdBegan: Boolean, isCardSwipe: Boolean = false): Boolean =
        !isCardSwipe && BackSwipeRules.yieldsToHold(heldForSeconds, holdBegan)
}

/** One phase of the drawer pan. Distances in dp, toward the trailing side positive (mirrored in RTL). */
sealed interface DrawerPanEvent {
    /**
     * The pan chose this touch. [isRevealingTimes]: it slides the drawer rows' times rather
     * than the card. [fingerTranslation] is the travel since touch-down (the card keeps the
     * distance the decision would swallow); [translation] since the pan was chosen.
     */
    data class Began(val isRevealingTimes: Boolean, val fingerTranslation: Float, val translation: Float) : DrawerPanEvent
    data class Changed(val isRevealingTimes: Boolean, val fingerTranslation: Float, val translation: Float) : DrawerPanEvent
    /** Lifted at [velocity] dp/s. */
    data class Ended(val isRevealingTimes: Boolean, val velocity: Float) : DrawerPanEvent
    data class Cancelled(val isRevealingTimes: Boolean) : DrawerPanEvent
}

/**
 * The layout nodes the drawer pan tests touches against (iOS's `drawerView`, `exposedCardView`,
 * `dragCardView`, and the hit view's ancestors). Register them with [drawerPanDrawer],
 * [drawerPanList], [drawerPanCard], [drawerPanDragCard], [drawerPanHorizontalScroller] and
 * [drawerPanClaimsDrag]; provide it as `LocalDrawerPanExclusion` so the card's controls can use
 * `Modifier.excludedFromDrawerPan()`.
 */
@Stable
class DrawerPanRegions : DrawerPanExclusion {
    internal class Scroller(val coordinates: LayoutCoordinates, val canScrollBackward: () -> Boolean, val canScrollForward: () -> Boolean)
    internal class SelectionHandles(val coordinates: LayoutCoordinates, val handleClaims: (Offset) -> Boolean)

    internal var drawer: LayoutCoordinates? = null
    internal var card: LayoutCoordinates? = null
    internal var dragCard: LayoutCoordinates? = null
    internal var dragCardHidden: () -> Boolean = { false }
    internal val lists = HashMap<Any, LayoutCoordinates>()
    internal val scrollers = HashMap<Any, Scroller>()
    internal val claims = HashMap<Any, LayoutCoordinates>()
    internal val exclusions = HashMap<Any, LayoutCoordinates>()
    internal val selectionHandles = HashMap<Any, SelectionHandles>()

    override fun updateExclusion(id: Any, coordinates: LayoutCoordinates?) {
        if (coordinates == null) exclusions.remove(id) else exclusions[id] = coordinates
    }

    override fun updateSelectionHandles(id: Any, coordinates: LayoutCoordinates?, handleClaims: (Offset) -> Boolean) {
        if (coordinates == null) selectionHandles.remove(id) else selectionHandles[id] = SelectionHandles(coordinates, handleClaims)
    }

    override fun updateHorizontalScroller(
        id: Any,
        coordinates: LayoutCoordinates?,
        canScrollBackward: () -> Boolean,
        canScrollForward: () -> Boolean,
    ) {
        if (coordinates == null) scrollers.remove(id) else scrollers[id] = Scroller(coordinates, canScrollBackward, canScrollForward)
    }

    /** The horizontal scrollers under [point] (px in [pan]). */
    internal fun scrollersAt(pan: LayoutCoordinates, point: Offset): List<Scroller> =
        scrollers.values.filter { contains(it.coordinates, pan, point) }

    internal fun isExcluded(pan: LayoutCoordinates, point: Offset): Boolean =
        exclusions.values.any { contains(it, pan, point) }

    /** Whether [point] (px in [pan]) lands on a text selection's handle (iOS `BackSwipeRules.selectionHandleClaims`). */
    internal fun selectionHandleClaims(pan: LayoutCoordinates, point: Offset): Boolean {
        for (handles in selectionHandles.values) {
            if (!contains(handles.coordinates, pan, point)) continue
            if (handles.handleClaims(handles.coordinates.localPositionOf(pan, point))) return true
        }
        return false
    }

    internal fun contains(region: LayoutCoordinates?, pan: LayoutCoordinates, point: Offset): Boolean {
        if (region == null || !region.isAttached || !pan.isAttached) return false
        val local = region.localPositionOf(pan, point)
        return local.x >= 0 && local.y >= 0 && local.x < region.size.width && local.y < region.size.height
    }
}

private fun Modifier.region(regions: DrawerPanRegions, set: (Any, LayoutCoordinates?) -> Unit): Modifier = composed {
    val id = remember { Any() }
    DisposableEffect(regions) { onDispose { set(id, null) } }
    onGloballyPositioned { set(id, it) }
}

/** The drawer (iOS `drawerView`). */
fun Modifier.drawerPanDrawer(regions: DrawerPanRegions): Modifier =
    region(regions) { _, c -> regions.drawer = c }

/** The drawer's session list (iOS's `UICollectionView`): a rightward drag on the open drawer reveals times. */
fun Modifier.drawerPanList(regions: DrawerPanRegions): Modifier =
    region(regions) { id, c -> if (c == null) regions.lists.remove(id) else regions.lists[id] = c }

/** The session card (iOS `exposedCardView`). Put it on the translated card itself. */
fun Modifier.drawerPanCard(regions: DrawerPanRegions): Modifier =
    region(regions) { _, c -> regions.card = c }

/** Catch Up's top card (iOS `dragCardView`), hit only while not [isHidden]. */
fun Modifier.drawerPanDragCard(regions: DrawerPanRegions, isHidden: () -> Boolean = { false }): Modifier =
    region(regions) { _, c ->
        regions.dragCard = c
        regions.dragCardHidden = isHidden
    }

/**
 * A horizontal scroller (code, table, attachments): while it can still scroll back toward its
 * start ([canScrollBackward], e.g. `scrollState.canScrollBackward`), a rightward drag is its
 * scroll; likewise leftward with [canScrollForward] in Catch Up.
 */
fun Modifier.drawerPanHorizontalScroller(
    regions: DrawerPanRegions,
    canScrollBackward: () -> Boolean,
    canScrollForward: () -> Boolean,
): Modifier = region(regions) { id, c ->
    if (c == null) regions.scrollers.remove(id) else regions.scrollers[id] = DrawerPanRegions.Scroller(c, canScrollBackward, canScrollForward)
}

/** A control that tracks a sideways drag (a slider, a switch): keeps its touch away from the leading edge (iOS `controlClaims`). */
fun Modifier.drawerPanClaimsDrag(regions: DrawerPanRegions): Modifier =
    region(regions) { id, c -> if (c == null) regions.claims.remove(id) else regions.claims[id] = c }

/**
 * The drawer's pan over the whole drawer container (put it on the untransformed root that holds
 * the drawer and the card). Reads its inputs as lambdas so it never restarts mid-gesture.
 *
 * @param isCardSwipe Catch Up turns the pan into a two-way card swipe.
 * @param isContentPushed a subagent is pushed over the session: the back pan has the drag, and
 *   only the root session's drag opens the drawer.
 * @param onTouchDown a touch that may move the drawer began (not in Catch Up).
 */
fun Modifier.drawerDirectionalPan(
    regions: DrawerPanRegions,
    isDrawerPresented: () -> Boolean,
    isCardSwipe: () -> Boolean = { false },
    isContentPushed: () -> Boolean = { false },
    enabled: () -> Boolean = { true },
    onTouchDown: () -> Unit = {},
    onPan: (DrawerPanEvent) -> Unit,
): Modifier = composed {
    val panCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val layoutDirection = LocalLayoutDirection.current
    Modifier
        .onGloballyPositioned { panCoordinates[0] = it }
        .pointerInput(regions, layoutDirection) {
            val decisionDistance = BackSwipeRules.decisionDistance.toPx()
            val edgeWidth = BackSwipeRules.edgeWidth.toPx()
            val rtl = layoutDirection == LayoutDirection.Rtl
            val sign = if (rtl) -1f else 1f
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val pan = panCoordinates[0] ?: return@awaitEachGesture
                if (!enabled()) return@awaitEachGesture
                val presented = isDrawerPresented()
                val cardSwipe = isCardSwipe()
                val point = down.position
                // Android's modal drawer lies over the card: a touch on the open drawer is the drawer's.
                val onExposedCard = regions.contains(regions.card, pan, point) &&
                    !(presented && regions.contains(regions.drawer, pan, point))
                // Controls that own a sideways drag (the + menu, the held mic) keep it.
                if (!presented && onExposedCard && regions.isExcluded(pan, point)) {
                    return@awaitEachGesture
                }
                // A text selection's handle drags the handle, even at the edge.
                if (!presented && regions.selectionHandleClaims(pan, point)) return@awaitEachGesture
                if (!presented && !cardSwipe && isContentPushed()) return@awaitEachGesture
                val startedAtEdge = !presented && !cardSwipe &&
                    (if (rtl) point.x >= size.width - edgeWidth else point.x <= edgeWidth)
                if (!cardSwipe) onTouchDown()

                // iOS walks the hit view's ancestors; here, every registered region under the finger.
                val scrollersHere = regions.scrollers.values.filter { regions.contains(it.coordinates, pan, point) }
                val ownsRightward = scrollersHere.any { if (rtl) it.canScrollForward() else it.canScrollBackward() }
                val ownsLeftward = scrollersHere.any { if (rtl) it.canScrollBackward() else it.canScrollForward() }
                val hitDrawer = regions.contains(regions.drawer, pan, point)
                val isDrawerList = regions.lists.values.any { regions.contains(it, pan, point) }
                val hitDragCard = cardSwipe && !regions.dragCardHidden() && regions.contains(regions.dragCard, pan, point)
                val hitExposedCard = hitDragCard || onExposedCard
                val cardSwipeOrigin = DirectionalPan.CardSwipeOrigin(hitExposedCard, ownsRightward, ownsLeftward)
                val touchOrigin: DirectionalPan.TouchOrigin = if (presented) {
                    when {
                        isDrawerList && hitDrawer -> DirectionalPan.TouchOrigin.DrawerList
                        hitExposedCard -> DirectionalPan.TouchOrigin.ExposedCard
                        else -> DirectionalPan.TouchOrigin.DrawerChrome
                    }
                } else {
                    // At the edge the drag is the drawer's; elsewhere a scroller or a control that tracks a drag keeps it.
                    val claimed = ownsRightward || regions.claims.values.any { regions.contains(it, pan, point) }
                    DirectionalPan.TouchOrigin.Content(horizontalScrollerOwnsRightwardDrag = claimed && !startedAtEdge)
                }

                val pointerId = down.id
                val downTime = down.uptimeMillis
                val tracker = VelocityTracker()
                tracker.addPointerInputChange(down)
                var holdBegan = false
                var chosen = false
                var revealing = false
                var chosenAt = 0f
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == pointerId }
                    if (change == null || !change.pressed) {
                        if (chosen) {
                            if (change != null) {
                                tracker.addPointerInputChange(change)
                                change.consume()
                                val velocity = tracker.calculateVelocity().x * sign / density
                                onPan(DrawerPanEvent.Ended(revealing, velocity))
                            } else {
                                onPan(DrawerPanEvent.Cancelled(revealing))
                            }
                        }
                        return@awaitEachGesture
                    }
                    tracker.addPointerInputChange(change)
                    val delta = change.position - point
                    val dx = delta.x * sign
                    if (chosen) {
                        change.consume()
                        val finger = dx / density
                        onPan(DrawerPanEvent.Changed(revealing, finger, finger - chosenAt))
                        continue
                    }
                    val decision = if (cardSwipe) {
                        DirectionalPan.cardSwipeDecision(cardSwipeOrigin, dx, delta.y, decisionDistance)
                    } else {
                        DirectionalPan.decision(touchOrigin, presented, dx, delta.y, decisionDistance)
                    }
                    when (decision) {
                        DirectionalPan.Decision.Wait -> {
                            // Children see this move next; one that consumes it before the pan chose
                            // (a long press's drag, a text selection) began a hold.
                            val final = awaitPointerEvent(PointerEventPass.Final)
                            if (final.changes.any { it.id == pointerId && it.positionChangeIgnoreConsumed() != Offset.Zero && it.isConsumed }) {
                                holdBegan = true
                            }
                        }
                        DirectionalPan.Decision.Fail -> return@awaitEachGesture
                        DirectionalPan.Decision.Begin, DirectionalPan.Decision.Reveal -> {
                            val heldFor = (change.uptimeMillis - downTime) / 1000.0
                            if (!startedAtEdge && DirectionalPan.yieldsToHold(heldFor, holdBegan, cardSwipe)) {
                                return@awaitEachGesture
                            }
                            chosen = true
                            revealing = decision == DirectionalPan.Decision.Reveal
                            change.consume()
                            val finger = dx / density
                            chosenAt = finger
                            onPan(DrawerPanEvent.Began(revealing, finger, 0f))
                        }
                    }
                }
            }
        }
}

/**
 * [drawerDirectionalPan] wired the way iOS `SessionDrawerViewController.handlePan` wires it:
 * Catch Up's card swipe goes to [onCardSwipe]; a reveal goes to [onReveal]; anything else drags
 * [motion]'s card under the finger and releases it onto its spring.
 */
fun Modifier.drawerPan(
    motion: DrawerMotionState,
    regions: DrawerPanRegions,
    onReveal: (DrawerRevealPhase) -> Unit,
    isCardSwipe: () -> Boolean = { false },
    isContentPushed: () -> Boolean = { false },
    enabled: () -> Boolean = { true },
    onCardSwipe: (DrawerPanEvent) -> Unit = {},
): Modifier = drawerDirectionalPan(
    regions = regions,
    isDrawerPresented = { motion.isPresented },
    isCardSwipe = isCardSwipe,
    isContentPushed = isContentPushed,
    enabled = enabled,
    onTouchDown = motion::touchDown,
) { event ->
    if (isCardSwipe()) {
        onCardSwipe(event)
        return@drawerDirectionalPan
    }
    when (event) {
        is DrawerPanEvent.Began -> if (event.isRevealingTimes) {
            onReveal(DrawerRevealPhase.Began)
            onReveal(DrawerRevealPhase.Changed(event.translation))
        } else {
            motion.beginDrag()
            motion.drag(event.fingerTranslation)
        }
        is DrawerPanEvent.Changed -> if (event.isRevealingTimes) {
            onReveal(DrawerRevealPhase.Changed(event.translation))
        } else {
            motion.drag(event.fingerTranslation)
        }
        is DrawerPanEvent.Ended -> if (event.isRevealingTimes) onReveal(DrawerRevealPhase.Ended) else motion.endDrag(event.velocity)
        is DrawerPanEvent.Cancelled -> if (event.isRevealingTimes) onReveal(DrawerRevealPhase.Ended) else motion.cancelDrag()
    }
}
