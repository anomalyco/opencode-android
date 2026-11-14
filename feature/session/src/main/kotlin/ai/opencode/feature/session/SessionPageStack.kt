package ai.opencode.feature.session

import ai.opencode.core.log.AppLog
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.PageTransitions
import ai.opencode.core.motion.SpringSolver
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.core.motion.rememberFrameDurationSeconds
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.touch.TouchRecovery
import ai.opencode.core.touch.rememberTouchRecovery
import ai.opencode.core.ui.BackSwipeRules
import ai.opencode.core.ui.DrawerPanExclusion
import ai.opencode.core.ui.LocalDrawerPanExclusion
import ai.opencode.core.ui.OpenCodeTheme
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A screen pushed over the session (a subagent, or a web page), keyed by its identity (iOS
 * `SessionStackPage`). The page's UI comes from [SessionPageStack]'s `content` slot, not from
 * this value.
 *
 * @property id the page's identity; a page keeps its composition (scroll, tasks) while its id stays.
 * @property paneTitle what TalkBack announces when the stack comes to rest with this page on top
 *   (Android's form of iOS's `UIAccessibility.screenChanged`); null announces nothing.
 * @property yieldsBackSwipe while true the stack's back gestures stand aside on this page: a web
 *   page with its own history goes back through it first (its own back handler and pan), as
 *   Safari does. Read when the stack recomposes and when a pan starts.
 */
@Stable
class SessionStackPage(
    val id: Int,
    val paneTitle: String? = null,
    val yieldsBackSwipe: () -> Boolean = { false },
)

/**
 * Subagent (and browser) screens pushed over the session, like a navigation stack (iOS
 * `SessionPageStack`). Pushes and pops use Material's shared axis X ([PageTransitions]); going back
 * (a rightward pan under [BackSwipeRules], or predictive back) is Android's back preview, springing
 * back on cancel ([Motion.drawerSlide]). Under Reduce Motion they cross-fade.
 *
 * Covered screens stay composed (each in its own
 * [androidx.compose.runtime.saveable.SaveableStateHolder] entry), so their scroll position and
 * effects survive and going back is instant. They're hidden from TalkBack and their back handlers
 * parked, so only the top screen handles system back.
 *
 * @param pages the screens over the root, bottom first. New ids push (the last with the push
 * motion), missing ids pop (the top with the pop motion), and pages that stay take the new value. A
 * page removed while it animates out is still drawn through [content] until it has left, so the
 * host must be able to draw a page that's no longer in its list.
 * @param onPop the reader went back until `depth` pages remain; the host removes the pages above
 * `depth` from its state. The page is already animating out.
 * @param modifier the stack's frame (the session card); pages fill it and are clipped to it.
 * @param enabled whether the back gestures may run (not under the open drawer or Catch Up).
 * @param rootPaneTitle what TalkBack announces when the stack comes back to rest on the root.
 * @param onTopChanged a push or pop moved the screen on top.
 * @param root the screen at the bottom (the session), always composed.
 * @param content draws a pushed page.
 */
@Composable
fun SessionPageStack(
    pages: List<SessionStackPage>,
    onPop: (depth: Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    rootPaneTitle: String? = null,
    onTopChanged: () -> Unit = {},
    root: @Composable () -> Unit,
    content: @Composable (page: SessionStackPage) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val holder = rememberSaveableStateHolder()
    val focusManager = LocalFocusManager.current
    val reduceMotion = rememberReduceMotion()
    val frameDuration = rememberFrameDurationSeconds()
    val currentOnPop by rememberUpdatedState(onPop)
    val currentOnTopChanged by rememberUpdatedState(onTopChanged)
    val motion = remember { SessionPageStackMotion(scope, pages) }
    rememberTouchRecovery(
        owner = TouchRecovery.Owner.Browser,
        inspect = { if (enabled) motion.strandedPopFault() else null },
        release = { motion.finishTransition() },
    )
    SideEffect {
        motion.reducedMotion = reduceMotion
        motion.frameDuration = frameDuration
        motion.onPopped = { currentOnPop(it) }
        motion.onTopChanged = { currentOnTopChanged() }
        motion.onRemoved = { holder.removeState(it) }
        motion.clearFocus = { focusManager.clearFocus() }
    }
    LaunchedEffect(pages) { motion.update(pages) }

    val mounted = motion.pages
    val backActive = enabled && motion.canGoBack && !motion.isPopping && mounted.lastOrNull()?.yieldsBackSwipe?.invoke() != true
    PredictiveBackHandler(enabled = backActive) { progress ->
        var began = false
        try {
            progress.collect { event ->
                if (!began) {
                    began = motion.dragBegan(leanSign = if (event.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f)
                    if (!began) return@collect
                }
                motion.dragChanged(event.progress * motion.width)
            }
            // Committed: a gesture lands from where it is; a button or TalkBack back pops.
            if (began) motion.dragEnded(velocity = 0f, ended = true, forceBack = true) else motion.popTop()
        } catch (cancelled: CancellationException) {
            if (began) motion.dragEnded(velocity = 0f, ended = false)
            throw cancelled
        }
    }

    val realOwner = LocalOnBackPressedDispatcherOwner.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val inertOwner = remember(lifecycleOwner) {
        object : OnBackPressedDispatcherOwner {
            override val onBackPressedDispatcher = OnBackPressedDispatcher()
            override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
        }
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val canvas = OpenCodeTheme.colors.canvas
    val panEnabled by rememberUpdatedState(enabled)
    val drawerExclusion = LocalDrawerPanExclusion.current
    val pageScrollers = remember { HashMap<Any, PageHorizontalScrollers>() }
    val stackCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val topScrollerClaims: (Offset) -> Boolean = { point ->
        val stack = stackCoordinates[0]
        val topKey: Any = motion.pages.lastOrNull()?.id ?: ROOT_KEY
        val scrollers = pageScrollers[topKey]
        stack != null && scrollers != null && scrollers.claimsBackSwipe(stack, point)
    }

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { motion.width = with(density) { it.width.toDp().value } }
            .onGloballyPositioned { stackCoordinates[0] = it }
            .pointerInput(motion, rtl) {
                backPan(motion, rtl, topScrollerClaims) { panEnabled }
            },
    ) {
        val count = mounted.size + 1
        for (index in 0 until count) {
            val page = if (index == 0) null else mounted[index - 1]
            val stateKey: Any = page?.id ?: ROOT_KEY
            key(stateKey) {
                val isTop = index == count - 1
                val scrollers = remember(drawerExclusion) { PageHorizontalScrollers(drawerExclusion) }
                DisposableEffect(scrollers) {
                    pageScrollers[stateKey] = scrollers
                    onDispose {
                        if (pageScrollers[stateKey] === scrollers) pageScrollers.remove(stateKey)
                    }
                }
                val isBeneath = index == count - 2
                val visible = isTop || (isBeneath && motion.beneathShown)
                val semantics = when {
                    !isTop -> Modifier.clearAndSetSemantics {}
                    motion.atRest -> (page?.paneTitle ?: rootPaneTitle)?.let { title -> Modifier.semantics { paneTitle = title } } ?: Modifier
                    else -> Modifier
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val width = max(motion.width, 1f)
                            val progress = min(max(motion.offset / width, 0f), 1f)
                            val direction = if (rtl) -1f else 1f
                            val slide = PageTransitions.SlideDistance.toPx() * direction
                            val transition = motion.transition
                            val sharedAxis = !motion.reducedMotion && transition != StackTransition.Back
                            scaleX = 1f
                            scaleY = 1f
                            shape = RectangleShape
                            clip = false
                            when {
                                !visible -> {
                                    alpha = 0f
                                    translationX = 0f
                                }
                                isTop && transition == StackTransition.Back -> {
                                    // Android's back preview: the page shrinks, leans toward the
                                    // swipe and rounds its corners; a commit fades it away.
                                    val scale = 1f - (1f - PageTransitions.BACK_PREVIEW_SCALE) * progress
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = PageTransitions.BackPreviewShift.toPx() * progress * motion.leanSign
                                    shape = RoundedCornerShape(PageTransitions.BackPreviewCorner * progress)
                                    clip = true
                                    alpha = motion.commitAlpha(progress)
                                }
                                isTop -> {
                                    val incoming = transition == StackTransition.Push
                                    alpha = if (sharedAxis) sharedAxisAlpha(progress, incoming) else motion.topAlpha
                                    translationX = if (sharedAxis) slide * progress else 0f
                                }
                                else -> {
                                    val incoming = transition == StackTransition.Pop
                                    alpha = if (sharedAxis) sharedAxisAlpha(1f - progress, incoming) else 1f
                                    translationX = if (sharedAxis) -slide * (1f - progress) else 0f
                                }
                            }
                        }
                        .then(if (page != null) Modifier.background(canvas) else Modifier)
                        // Takes the hit so touches never fall through to a covered screen.
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                        .then(semantics),
                ) {
                    val owner = if (isTop) realOwner else inertOwner
                    val provided = if (owner != null) arrayOf(LocalOnBackPressedDispatcherOwner provides owner) else emptyArray()
                    CompositionLocalProvider(LocalDrawerPanExclusion provides scrollers, *provided) {
                        holder.SaveableStateProvider(stateKey) {
                            if (page == null) root() else content(page)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Material shared axis's fade: the outgoing page fades out over the first part of the move, the
 * incoming one fades in over the rest. [distance] is how far the page is from where it rests (0
 * there, 1 fully away).
 */
private fun sharedAxisAlpha(distance: Float, incoming: Boolean): Float {
    val split = PageTransitions.FADE_SPLIT
    val alpha = if (incoming) (1f - distance - split) / (1f - split) else 1f - distance / split
    return alpha.coerceIn(0f, 1f)
}

/**
 * One page's horizontal scrollers (code, tables, attachments), also passed on to the drawer's
 * regions: the back pan leaves a rightward drag to a scroller on the top page that can still
 * scroll back toward its start (iOS `BackSwipeRules.scrollerClaims`). Covered pages stay laid
 * out under the top one, so each page keeps its own.
 */
private class PageHorizontalScrollers(private val drawer: DrawerPanExclusion?) : DrawerPanExclusion {
    private class Scroller(val coordinates: LayoutCoordinates, val canScrollBackward: () -> Boolean)

    private val scrollers = HashMap<Any, Scroller>()

    override fun updateExclusion(id: Any, coordinates: LayoutCoordinates?) {
        drawer?.updateExclusion(id, coordinates)
    }

    override fun updateSelectionHandles(id: Any, coordinates: LayoutCoordinates?, handleClaims: (Offset) -> Boolean) {
        drawer?.updateSelectionHandles(id, coordinates, handleClaims)
    }

    override fun updateHorizontalScroller(
        id: Any,
        coordinates: LayoutCoordinates?,
        canScrollBackward: () -> Boolean,
        canScrollForward: () -> Boolean,
    ) {
        drawer?.updateHorizontalScroller(id, coordinates, canScrollBackward, canScrollForward)
        if (coordinates == null) scrollers.remove(id) else scrollers[id] = Scroller(coordinates, canScrollBackward)
    }

    /** Whether a scroller under [point] (px in [stack]) can still scroll back toward its start. */
    fun claimsBackSwipe(stack: LayoutCoordinates, point: Offset): Boolean {
        if (!stack.isAttached) return false
        for (scroller in scrollers.values) {
            val region = scroller.coordinates
            if (!region.isAttached) continue
            val local = region.localPositionOf(stack, point)
            val inside = local.x >= 0 && local.y >= 0 && local.x < region.size.width && local.y < region.size.height
            if (inside && scroller.canScrollBackward()) return true
        }
        return false
    }
}

/** What moves the top page: a push, a pop, or a back gesture (drag, then its settle). */
internal enum class StackTransition { Push, Pop, Back }


private const val ROOT_KEY = "ai.opencode.session.stack.root"

/**
 * The rightward back pan over a pushed page (iOS `BackPanGestureRecognizer`), from anywhere,
 * under [BackSwipeRules]: an edge touch always takes it; elsewhere a touch on a horizontal
 * scroller that can still scroll back ([scrollerClaims], decided at touch-down as iOS does), a
 * drag already claimed by content under the finger (a slider), or one begun after a rest keeps
 * doing that. It watches after the content (main pass) until it chooses, then takes the touch (initial
 * pass, consuming), so content under the finger lets go.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.backPan(
    motion: SessionPageStackMotion,
    rtl: Boolean,
    scrollerClaims: (Offset) -> Boolean,
    enabled: () -> Boolean,
) {
    val direction = if (rtl) -1f else 1f
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        if (!enabled() || !motion.panActive()) return@awaitEachGesture
        val edge = BackSwipeRules.edgeWidth.toPx()
        val atEdge = if (rtl) down.position.x >= size.width - edge else down.position.x <= edge
        val decisionPx = BackSwipeRules.decisionDistance.toPx()
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(down)
        var claimed = down.isConsumed || scrollerClaims(down.position)
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed) return@awaitEachGesture
            if (change.isConsumed) claimed = true
            tracker.addPointerInputChange(change)
            val dx = (change.position.x - down.position.x) * direction
            val dy = change.position.y - down.position.y
            val held = (change.uptimeMillis - down.uptimeMillis) / 1000.0
            when (BackSwipeRules.decision(dx, dy, decisionPx, atEdge, claimed, held, holdBegan = false)) {
                BackSwipeRules.Decision.Wait -> continue
                BackSwipeRules.Decision.Fail -> return@awaitEachGesture
                BackSwipeRules.Decision.Begin -> {}
            }
            change.consume()
            if (!motion.dragBegan(leanSign = direction)) return@awaitEachGesture
            motion.dragChanged(dx.toDp().value)
            break
        }
        var finished = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null) {
                    finished = true
                    motion.dragEnded(velocity = 0f, ended = false)
                    return@awaitEachGesture
                }
                tracker.addPointerInputChange(change)
                change.consume()
                if (!change.pressed) {
                    val velocity = (tracker.calculateVelocity().x * direction).toDp().value
                    finished = true
                    motion.dragEnded(velocity = velocity, ended = true)
                    return@awaitEachGesture
                }
                motion.dragChanged(((change.position.x - down.position.x) * direction).toDp().value)
            }
        } finally {
            if (!finished) motion.dragEnded(velocity = 0f, ended = false)
        }
    }
}

/**
 * The plain object behind [SessionPageStack]: which pages are composed, the top page's offset (dp;
 * 0 in place, the width off screen), and the transitions and back drag that move it, on Compose's
 * frame clock. The host only sees [onPopped].
 */
@Stable
internal class SessionPageStackMotion(
    private val scope: CoroutineScope,
    initial: List<SessionStackPage>,
) {
    /** The composed pages, bottom first (pages animating out included). */
    var pages by mutableStateOf(initial)
        private set
    var offset by mutableFloatStateOf(0f)
        private set
    var width by mutableFloatStateOf(0f)
    var reducedMotion by mutableStateOf(false)
    var frameDuration = 1.0 / 60.0

    /** The screen beneath the top shows (a push, a pop or a drag back). */
    var beneathShown by mutableStateOf(false)
        private set

    /** What the running move is (it picks the look: shared axis or the back preview). */
    var transition by mutableStateOf(StackTransition.Pop)
        private set

    /** Which way the back preview leans on screen: +1 right, -1 left (toward the swipe). */
    var leanSign by mutableFloatStateOf(1f)
        private set

    /** The progress a committed back gesture fades away from; negative while not committing. */
    private var commitFrom by mutableFloatStateOf(-1f)

    /** The top page's opacity: Reduce Motion's cross-fade. */
    var topAlpha by mutableFloatStateOf(1f)
        private set

    /** Only the top screen shows; no transition runs. */
    var atRest by mutableStateOf(true)
        private set

    /** A pop is animating out; the back gestures wait for it. */
    var isPopping by mutableStateOf(false)
        private set

    var onPopped: (Int) -> Unit = {}
    var onTopChanged: () -> Unit = {}
    var onRemoved: (Int) -> Unit = {}
    var clearFocus: () -> Unit = {}

    val canGoBack: Boolean get() = pages.isNotEmpty()

    /** Pages already taken off by the reader, waiting for the state to follow. */
    private val poppedIDs = mutableSetOf<Int>()

    /** What the running transition does when it lands (or is cut short). */
    private var landing: (() -> Unit)? = null
    private var job: Job? = null
    private var fading = false
    private var dragging = false
    private var dragStart = 0f

    /** The top page's opacity in the back preview: 1, fading to 0 once the back commits. */
    fun commitAlpha(progress: Float): Float {
        if (commitFrom < 0f) return 1f
        val left = max(1f - commitFrom, 0.001f)
        return (1f - (progress - commitFrom) / left).coerceIn(0f, 1f)
    }

    /** iOS `pan.isActive`, minus `isEnabled` (the composable's `enabled`). */
    fun panActive(): Boolean = canGoBack && !isPopping && pages.lastOrNull()?.yieldsBackSwipe?.invoke() != true

    /**
     * Matches the pages to [items], bottom first: new ones push (the last with the push motion),
     * missing ones pop (the top with the pop motion), and pages that stay take their new value.
     */
    fun update(items: List<SessionStackPage>) {
        val ids = items.map { it.id }.toSet()
        poppedIDs.retainAll(ids)
        val wanted = items.filter { it.id !in poppedIDs }
        var common = 0
        while (common < pages.size && common < wanted.size && pages[common].id == wanted[common].id) common++
        if (common > 0 && (0 until common).any { pages[it] !== wanted[it] }) {
            pages = wanted.take(common) + pages.drop(common)
        }
        val leaving = pages.drop(common)
        val arriving = wanted.drop(common)
        if (leaving.isEmpty() && arriving.isEmpty()) return
        // The top is already animating out: an update meanwhile (the call retargeting, a
        // streamed batch) lets it finish rather than snapping it off.
        if (isPopping && arriving.isEmpty() && leaving.size == 1 && leaving[0] === pages.last()) return
        val animated = width > 0
        if (arriving.isEmpty()) {
            pop(common, animated)
            return
        }
        // A replacement (rare: the stack changed under a transition) lands at once.
        finishTransition()
        val leavingIDs = leaving.map { it.id }.toSet()
        pages.filter { it.id in leavingIDs }.forEach { onRemoved(it.id) }
        pages = pages.filter { it.id !in leavingIDs }
        arriving.forEachIndexed { index, item -> push(item, animated && index == arriving.lastIndex) }
    }

    /** System back without a gesture (a button, TalkBack) goes back one level. */
    fun popTop(): Boolean {
        if (!canGoBack || isPopping) return false
        poppedIDs.add(pages.last().id)
        pop(pages.size - 1, animated = width > 0) { onPopped(pages.size) }
        return true
    }

    private fun push(item: SessionStackPage, animated: Boolean) {
        supersedeDrag()
        // The parent gives up the keyboard, as a navigation push does.
        clearFocus()
        pages = pages + item
        onTopChanged()
        transition = StackTransition.Push
        atRest = false
        beneathShown = true
        if (!animated) {
            offset = 0f
            settleAtRest()
            return
        }
        if (reducedMotion) {
            offset = 0f
            topAlpha = 0f
            landing = { settleAtRest() }
            fade(to = 1f)
            return
        }
        offset = width
        slide(to = 0f) { settleAtRest() }
    }

    /** Takes pages off until [depth] remain; only the top one animates. */
    private fun pop(depth: Int, animated: Boolean, completion: () -> Unit = {}) {
        supersedeDrag()
        // A transition still running lands first, so the count below is what stays.
        finishTransition()
        if (pages.size <= depth) return completion()
        // Pages below the top that are going too leave at once.
        while (pages.size > depth + 1) {
            val gone = pages[pages.size - 2]
            onRemoved(gone.id)
            pages = pages.filter { it !== gone }
        }
        val top = pages.last()
        val finish = takeOff(top, completion)
        if (!animated) return finish()
        transition = StackTransition.Pop
        revealBeneath()
        isPopping = true
        if (reducedMotion) {
            landing = finish
            fade(to = 0f)
            return
        }
        slide(to = width, then = finish)
    }

    /** Removes [top] once it has left, and rests on the page beneath. */
    private fun takeOff(top: SessionStackPage, completion: () -> Unit): () -> Unit = {
        isPopping = false
        onRemoved(top.id)
        pages = pages.filter { it !== top }
        offset = 0f
        settleAtRest()
        onTopChanged()
        completion()
    }

    /** Settles a back gesture toward [to] on the drawer's spring (keeping its velocity), then lands. */
    private fun animate(to: Float, velocity: Float = 0f, then: () -> Unit) {
        stopMotion()
        landing = then
        val spring = PageStackSpring(position = offset, velocity = velocity, target = to, upper = max(width, 0f))
        job = scope.launch {
            motionFrameLoop(frameDurationSeconds = frameDuration) { tick ->
                val settled = spring.step(tick.dt)
                offset = spring.position
                if (settled) {
                    job = null
                    land()
                    MotionFrameAction.Stop
                } else {
                    MotionFrameAction.Continue
                }
            }
        }
    }

    /**
     * A push or pop: moves the top page toward [to] over Material's 0.3 s on FastOutSlowIn (it
     * follows Developer Mode slow motion), then lands.
     */
    private fun slide(to: Float, then: () -> Unit) {
        stopMotion()
        landing = then
        val from = offset
        val seconds = Motion.millis(PageTransitions.DURATION_SECONDS) / 1000.0
        var elapsed = 0.0
        job = scope.launch {
            motionFrameLoop(frameDurationSeconds = frameDuration) { tick ->
                elapsed += tick.dt
                val t = if (seconds > 0) min(elapsed / seconds, 1.0).toFloat() else 1f
                offset = from + (to - from) * FastOutSlowInEasing.transform(t)
                if (t >= 1f) {
                    job = null
                    land()
                    MotionFrameAction.Stop
                } else {
                    MotionFrameAction.Continue
                }
            }
        }
    }

    /** Reduce Motion's cross-fade of the top page to [to], then lands. */
    private fun fade(to: Float) {
        stopMotion()
        val from = topAlpha
        fading = true
        var elapsed = 0.0
        job = scope.launch {
            motionFrameLoop(frameDurationSeconds = frameDuration) { tick ->
                elapsed += tick.dt
                // iOS `Motion.reduced.animate(reduceMotion: true)`.
                val t = min(elapsed / Motion.reduced.curve(reduceMotion = true).duration, 1.0).toFloat()
                topAlpha = from + (to - from) * Motion.EaseInOut.transform(t)
                if (t >= 1f) {
                    job = null
                    fading = false
                    land()
                    MotionFrameAction.Stop
                } else {
                    MotionFrameAction.Continue
                }
            }
        }
    }

    private fun land() {
        val land = landing
        landing = null
        land?.invoke()
    }

    private fun stopMotion() {
        job?.cancel()
        job = null
        // A cross-fade cut short ends where it was going, as UIKit's animation would.
        if (fading) {
            fading = false
            topAlpha = 1f
        }
    }

    /** A drag in progress gives way to a push or pop the app makes. */
    private fun supersedeDrag() {
        dragging = false
    }

    /**
     * iOS `SessionPageStack.touchRecovery` inspect: a pop whose page has already left (slid off
     * the edge or faded out) but never landed still has the leaving page on top taking every
     * touch. A drag in flight or a pop still on screen keeps its transition.
     */
    fun strandedPopFault(): TouchRecovery.Fault? {
        if (!isPopping || dragging || landing == null) return null
        val top = pages.lastOrNull() ?: return null
        val offEdge = width > 0 && offset >= width - 1f
        val invisible = topAlpha <= 0.01f
        if (!offEdge && !invisible) return null
        return TouchRecovery.Fault(TouchRecovery.Reason.Transition, top)
    }

    /** A transition still running lands where it was going. */
    fun finishTransition() {
        stopMotion()
        val land = landing ?: return
        landing = null
        offset = 0f
        land()
    }

    // The back drag (in-app pan or predictive back)

    /**
     * The drag begins; false when it may not (nothing to go back from, or a pop is running).
     * [leanSign] is which way the preview leans on screen (+1 right, -1 left).
     */
    fun dragBegan(leanSign: Float): Boolean {
        if (width <= 0 || !canGoBack) return false
        // A finger already down as a pop began: the pop owns the page until it lands, so this
        // drag is ignored. Taking the page would drop the pop's landing.
        if (isPopping) {
            val depth = pages.size
            AppLog.app.info { "page_stack_back_drag_ignored reason=popping depth=$depth" }
            return false
        }
        // Caught mid-push: the drag takes the page from where it is.
        stopMotion()
        landing = null
        transition = StackTransition.Back
        this.leanSign = leanSign
        commitFrom = -1f
        dragging = true
        dragStart = offset
        atRest = false
        revealBeneath()
        clearFocus()
        return true
    }

    /** The finger's horizontal travel since it touched down (dp, toward the trailing side). */
    fun dragChanged(translation: Float) {
        if (!dragging) return
        offset = min(max(dragStart + translation, 0f), width)
    }

    /**
     * The drag ends: [ended] for a release (false for a cancel), [velocity] in dp/s toward the
     * trailing side, [forceBack] when the system already committed the back.
     */
    fun dragEnded(velocity: Float, ended: Boolean, forceBack: Boolean = false) {
        if (!dragging) return
        dragging = false
        val goesBack = forceBack || (ended && BackSwipeRules.goesBack(offset, width, velocity))
        val top = pages.lastOrNull()
        if (goesBack && top != null) {
            poppedIDs.add(top.id)
            val finish = takeOff(top) { onPopped(pages.size) }
            if (reducedMotion) return finish()
            commitFrom = if (width > 0) min(max(offset / width, 0f), 1f) else 0f
            isPopping = true
            animate(to = width, velocity = velocity, then = finish)
        } else {
            if (reducedMotion) {
                offset = 0f
                return settleAtRest()
            }
            animate(to = 0f, velocity = velocity) { settleAtRest() }
        }
    }

    /** The screen beneath the top shows (for a pop or a drag back). */
    private fun revealBeneath() {
        if (pages.isEmpty()) return
        atRest = false
        beneathShown = true
    }

    /**
     * At rest: only the top screen shows, takes touches, and reaches TalkBack (its pane title
     * announces the change); those beneath stay composed but invisible.
     */
    private fun settleAtRest() {
        beneathShown = false
        commitFrom = -1f
        topAlpha = 1f
        atRest = true
    }
}

/**
 * The drawer's spring for one page slide (iOS `BackSwipeSpring` driving `DrawerSpring.step`):
 * critically damped at [Motion.drawerSlide]'s ω, stopping on the edge rather than passing it.
 * Distances in dp (iOS points), velocity in dp/s.
 */
private class PageStackSpring(position: Float, var velocity: Float, target: Float, upper: Float) {
    private val lower = 0f
    private val upper = max(upper, 0f)
    val target = target.coerceIn(lower, this.upper)
    var position = position.coerceIn(lower, this.upper)
        private set
    private var hasArrived = false

    /** Advances [dt] seconds; true once at rest on the target. */
    fun step(dt: Double): Boolean {
        val x0 = position - target
        val (value, v) = SpringSolver.evaluate(
            from = position.toDouble(),
            to = target.toDouble(),
            initialVelocity = velocity.toDouble(),
            omega = Motion.drawerSlide.omega,
            zeta = 1.0,
            elapsed = dt,
        )
        var x = (value - target).toFloat()
        var nextVelocity = v.toFloat()
        if ((x0 != 0f && x * x0 < 0) || target + x !in lower..upper) {
            // A hard flick reaches the edge before the spring would, or a release right at the
            // edge still moves outwards: stop on the edge.
            x = 0f
            nextVelocity = 0f
        }
        position = target + x
        velocity = nextVelocity
        if (!hasArrived && abs(x) <= ARRIVAL_TOLERANCE) hasArrived = true
        if (hasArrived && abs(x) < 0.25f && abs(nextVelocity) < 8f) {
            position = target
            velocity = 0f
            return true
        }
        return false
    }

    private companion object {
        const val ARRIVAL_TOLERANCE = 1f
    }
}
