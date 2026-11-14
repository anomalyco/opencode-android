package ai.opencode.feature.composer

import ai.opencode.core.motion.Motion
import ai.opencode.core.tuning.AnimationTimeScale
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Port of iOS `ComposerMorphTunables` (`composer.morph.*`): the composer, or a control in it,
 * morphing into a panel fitted to the keyboard's region. Baked defaults: lengths in dp, times in
 * seconds.
 */
object ComposerMorphTunables {
    /** Where a sheet folding into the composer gives its glass up (progress 1 → 0): on the tap it vanished and left the photos as a ghost. */
    const val photoGlassOut = 0.35
    /** How far (dp) a pull down past compact brings the composer back up under it. */
    const val composerRise = 160.0
    const val photoContentDelay = 0.0
    const val photoBackdropBlur = 0.0
    const val photoBackdropDim = 0.0
    /** Going from narrow to wide, the width leads the height. */
    const val widthLead = 1.6
    /** What it grows out of (the + menu's rows) dissolves into a blur this strong (dp) as the content arrives, and grows this much with the glass. */
    const val outgoingBlur = 10.0
    const val outgoingGrowth = 0.12
    const val contentBlur = 14.0
    /**
     * Cross-fading with what it grows out of, the content is whole by this share of its reveal (1
     * is a plain cross-fade).
     */
    const val photoContentLead = 0.4
    const val exitBlur = 10.0
    /** iOS's backdrop blur (dp). Not drawn on Android, which dims with a scrim only (see [noBlurDimBoost]). */
    const val backdropBlur = 18.0
    /**
     * iOS Reduce Transparency's dim boost, used when there is no backdrop blur. Android never blurs
     * what's behind a sheet (Material scrims only), so its dim is always boosted.
     */
    const val noBlurDimBoost = 1.6
    const val dimDark = 0.3
    const val dimLight = 0.12
    const val topRadius = 30.0
    /** Concentric with the display's corners, a sheet's inset in. */
    const val bottomRadius = 46.0
    const val bottomInset = 8.0
    const val sideInset = 8.0
    /** How far above the composer a sheet stands without a software keyboard to take the height of. */
    const val leastLift = 300.0
    /** Drawn down this far, or thrown down this fast (dp/s), it closes. */
    const val dragCloses = 90.0
    const val throwCloses = 700.0
    const val photoExpandDrag = 36.0
    const val photoExpandVelocity = 450.0
    const val photoCollapseDrag = 48.0
    const val photoScrollExpand = 28.0
    /** `composer.morph.photoDismissDrop`. */
    const val dismissDrop = 88.0
    /** `composer.morph.photoComposerSink`. */
    const val composerSink = 28.0

    /**
     * The curve the morph's bottom edge rides when a keyboard leaves or returns beneath it (iOS
     * `DockedKeyboard.animation`). While the IME animates, the rest reads `WindowInsets.ime` frame
     * by frame instead.
     */
    fun keyboard(): FiniteAnimationSpec<Float> = Motion.keyboard.animation()
}

/**
 * Port of iOS `ComposerMorph`: whether a morph is open and how far it has grown. A plain object, so
 * only the surface redraws as a finger drags it. Opening and closing in any order only retarget its
 * springs.
 */
@Stable
class ComposerMorph(private val scope: CoroutineScope) {
    /** `Armed`: drawn on its source, its content built but not yet shown, for the frame before it grows. */
    enum class Phase { Closed, Armed, Open, Closing }

    var phase by mutableStateOf(Phase.Closed)
        private set
    /** The glass's travel from its source (0) to its rest (1). */
    val progress = Animatable(0f)
    /** The bottom edge's travel (iOS `bottomOpen`). */
    val bottomProgress = Animatable(0f)
    /** How much of its content shows (0–1), on its own clock. */
    val content = Animatable(0f)
    /** How far a finger has drawn it down (dp; 0 at rest). */
    var drag by mutableFloatStateOf(0f)
    /** Bumped on every open, close and reset: deferred work from an earlier one checks it and does nothing ([after]). */
    var generation by mutableIntStateOf(0)
        private set
    /** Open, its fold starting on the next turn ([launchThenFold]). */
    var foldPending by mutableStateOf(false)
        private set
    private var dragTask: Job? = null

    val isOpen: Boolean get() = phase == Phase.Open
    /** Drawn: armed, open, or shrinking back into its source. */
    val isShown: Boolean get() = phase != Phase.Closed
    /** Open or about to be: what a tap on its source toggles. */
    val isOpening: Boolean get() = (phase == Phase.Open && !foldPending) || phase == Phase.Armed

    /** Open at once, with no growth. */
    fun open() {
        generation += 1
        foldPending = false
        phase = Phase.Open
        scope.launch { progress.snapTo(1f); bottomProgress.snapTo(1f); content.snapTo(1f) }
    }

    /** Opens on [animation], its bottom edge on [edge] if given. */
    private fun open(animation: FiniteAnimationSpec<Float>, edge: FiniteAnimationSpec<Float>?, contentDelay: Double?) {
        generation += 1
        phase = Phase.Open
        scope.launch { progress.animateTo(1f, animation) }
        scope.launch { bottomProgress.animateTo(1f, edge ?: animation) }
        // Its content arrives a beat after the glass starts to grow. The delay is part of the spec,
        // so a fold that starts meanwhile retargets it.
        val arrival = Motion.panelOpen.animation<Float>(delaySeconds = contentDelay ?: Motion.contentDelay)
        scope.launch { content.animateTo(1f, arrival) }
    }

    /**
     * Opens in two steps. In the tap it stands on its source while [prepare] builds its
     * content; on the next frame [launch] runs (a keyboard going down) and the spring starts
     * right after, so no frame of it is skipped. Reopened mid-fold, it grows back from where it
     * is, at once.
     */
    fun armThenOpen(
        animation: FiniteAnimationSpec<Float>,
        edge: FiniteAnimationSpec<Float>? = null,
        contentDelay: Double? = null,
        prepare: () -> Unit,
        launch: () -> Unit,
        opened: () -> Unit,
    ) {
        if (phase == Phase.Closing || foldPending) {
            foldPending = false
            prepare()
            launch()
            open(animation, edge, contentDelay)
            return opened()
        }
        generation += 1
        // Where it stands and rests is set, not animated from where it was.
        phase = Phase.Armed
        prepare()
        after(1.milliseconds) {
            if (phase != Phase.Armed) return@after
            launch()
            open(animation, edge, contentDelay)
            opened()
        }
    }

    /**
     * Folds back into its source on [animation] from wherever a finger drew it, its bottom edge on
     * [edge] if given. [contentFollowsFold] keeps content on this clock too. [completion] runs once
     * the fold's spring is done (or replaced).
     */
    fun fold(
        animation: FiniteAnimationSpec<Float>,
        edge: FiniteAnimationSpec<Float>? = null,
        reduceMotion: Boolean,
        contentFollowsFold: Boolean = false,
        completion: () -> Unit = {},
    ) {
        foldPending = false
        if (phase == Phase.Open && content.value > 0f && !contentFollowsFold) {
            scope.launch { content.animateTo(0f, Motion.panelClose.animation()) }
        }
        animateDrag(0f, animation)
        close(reduceMotion, movesEdge = edge == null, animation = animation, onFinished = completion)
        if (edge != null && phase == Phase.Closing) scope.launch { bottomProgress.animateTo(0f, edge) }
    }

    /**
     * Folds in two steps, like [armThenOpen]: [launch] runs in the tap (a keyboard coming back
     * lays the screen out again), and the fold starts on the next frame, after that commit.
     * Reopened meanwhile, it stays open. [landed] runs once the fold's spring is done, while the
     * morph is still closing, unless it reopened, closed or reset since.
     */
    fun launchThenFold(
        animation: FiniteAnimationSpec<Float>,
        reduceMotion: Boolean,
        launch: () -> Unit,
        edge: () -> FiniteAnimationSpec<Float>?,
        landed: () -> Unit = {},
    ) {
        if (foldPending) return
        if (phase != Phase.Open) {
            launch()
            return close(reduceMotion)
        }
        generation += 1
        foldPending = true
        scope.launch { content.animateTo(0f, Motion.panelClose.animation()) }
        launch()
        after(1.milliseconds) {
            if (!foldPending) return@after
            val folding = generation + 1
            fold(animation, edge(), reduceMotion) {
                if (generation == folding && phase == Phase.Closing) landed()
            }
        }
    }

    /**
     * Shrinks back into its source on [animation] ([Motion.panelClose] unless given), drawn until
     * its spring lands. Armed, it's gone at once; a null [animation] snaps.
     */
    fun close(
        reduceMotion: Boolean,
        movesEdge: Boolean = true,
        animation: FiniteAnimationSpec<Float>? = Motion.panelClose.animation(reduceMotion = reduceMotion),
        onFinished: (() -> Unit)? = null,
    ) {
        if (phase == Phase.Armed) return reset().also { onFinished?.invoke() }
        if (phase != Phase.Open) return onFinished?.invoke() ?: Unit
        generation += 1
        // Its pending fold's turn is dropped by the bump above: the flag goes too.
        foldPending = false
        phase = Phase.Closing
        val closing = generation
        scope.launch {
            try {
                if (animation != null) progress.animateTo(0f, animation) else progress.snapTo(0f)
            } finally {
                onFinished?.invoke()
            }
            // The fold has landed: unless it reopened, closed again or reset since, it stops
            // being drawn (`PresenceGate`'s rule, on the morph's generation).
            endClose(closing)
        }
        if (movesEdge) scope.launch { if (animation != null) bottomProgress.animateTo(0f, animation) else bottomProgress.snapTo(0f) }
        if (animation == null) scope.launch { content.snapTo(0f) }
    }

    private fun endClose(closing: Int) {
        if (closing != generation || phase != Phase.Closing) return
        phase = Phase.Closed
        drag = 0f
        scope.launch { progress.snapTo(0f); bottomProgress.snapTo(0f); content.snapTo(0f) }
    }

    /** Gone at once, with no shrink (a session switch, a call, a request). */
    fun reset() {
        generation += 1
        dragTask?.cancel()
        foldPending = false
        phase = Phase.Closed
        drag = 0f
        scope.launch { progress.snapTo(0f); bottomProgress.snapTo(0f); content.snapTo(0f) }
    }

    /**
     * Animates [drag] (a release springing back, or a fold), starting at [initialVelocity]
     * (dp/s) so a settle continues the finger (iOS `interpolatingSpring(initialVelocity:)`).
     */
    fun animateDrag(to: Float, animation: FiniteAnimationSpec<Float>?, initialVelocity: Float = 0f) {
        dragTask?.cancel()
        if (animation == null) {
            drag = to
            return
        }
        val from = drag
        dragTask = scope.launch {
            val a = Animatable(from)
            a.animateTo(to, animation, initialVelocity) { drag = value }
        }
    }

    /** Sets [drag] at once, stopping any spring on it (a finger tracking 1:1). */
    fun dragTo(value: Float) {
        dragTask?.cancel()
        drag = value
    }

    /** Runs [work] after [delay] unless the morph opened, closed or reset meanwhile. */
    fun after(delay: Duration, work: () -> Unit) {
        val expected = generation
        scope.launch {
            delay(delay)
            if (generation == expected) work()
        }
    }

    companion object {
        /** Wall-clock seconds for [seconds] of animation time, under slow motion too. */
        fun wallSeconds(seconds: Double): Double = AnimationTimeScale.shared.wallSeconds(seconds)

        /** Whether a drag let go closes it: drawn past `dragCloses`, or thrown down (dp, dp/s). */
        fun closes(translation: Double, predicted: Double, velocity: Double): Boolean =
            translation > ComposerMorphTunables.dragCloses ||
                (velocity > ComposerMorphTunables.throwCloses && predicted > translation)
    }
}

/** A remembered [ComposerMorph] on the composition's scope. */
@Composable
fun rememberComposerMorph(): ComposerMorph {
    val scope = rememberCoroutineScope()
    return remember { ComposerMorph(scope) }
}

/**
 * Port of iOS `ComposerMorphGeometry`: where a morph rests, as frames in one coordinate space
 * (root px). Radii in px.
 */
data class ComposerMorphGeometry(
    /** Where it grows from and folds back into: kept live, so a close retargets if the source moves. */
    val source: Rect,
    val sourceRadius: Float,
    /** Where it rests open. */
    val target: Rect,
    val topRadius: Float,
    val bottomRadius: Float,
) {
    /** The same, its rest's top moved to [y]; [keepingBottom] keeps the bottom edge at `target.bottom`. */
    fun resting(y: Float, keepingBottom: Boolean = false, minHeightPx: Float): ComposerMorphGeometry {
        val bottom = target.bottom
        val height = if (keepingBottom) max(bottom - y, minHeightPx) else target.height
        return copy(target = Rect(target.left, y, target.right, y + height))
    }

    data class Frame(val rect: Rect, val top: Float, val bottom: Float)

    /** The morph's frame and corners at [progress] (0 the source, 1 the target, past 1 a spring's overshoot), drawn down by [drag]. */
    fun frame(progress: Float, bottom: Float? = null, drag: Float): Frame {
        val p = progress
        // Narrow to wide, the width gets there first.
        val lead = ComposerMorphTunables.widthLead
        val pw = if (p >= 1) p else (1 - (1 - max(p, 0f)).toDouble().pow(lead)).toFloat()
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        val minX = lerp(source.left, target.left, pw)
        val maxX = lerp(source.right, target.right, pw)
        val minY = lerp(source.top, target.top, p) + drag
        val maxY = lerp(source.bottom, target.bottom, bottom ?: p) + drag
        val rect = Rect(minX, minY, minX + max(maxX - minX, 1f), minY + max(maxY - minY, 1f))
        val clamped = p.coerceIn(0f, 1f)
        val limit = min(rect.width, rect.height) / 2
        return Frame(rect, min(lerp(sourceRadius, topRadius, clamped), limit), min(lerp(sourceRadius, bottomRadius, clamped), limit))
    }

    data class UnifiedMenuState(
        val usesDownwardDismiss: Boolean,
        val closingToMenu: Boolean,
        val menuProgress: Float,
        val menuInteractive: Boolean,
        val entranceProgress: Float,
    )

    companion object {
        /**
         * A sheet over the keyboard's region: as wide as [column] (or the screen, inset), from
         * [top] to the screen's bottom, or to a keyboard's top when one is up ([keyboardTop]),
         * its height then kept and its top raised, never above [ceiling]. [density] converts the
         * dp tunables.
         */
        fun sheet(
            top: Float,
            column: Rect?,
            screen: Rect,
            keyboardTop: Float?,
            ceiling: Float,
            density: Float,
            edgeToEdge: Boolean = false,
        ): Rect {
            val side = if (edgeToEdge) 0f else (ComposerMorphTunables.sideInset * density).toFloat()
            val inset = if (edgeToEdge) 0f else (ComposerMorphTunables.bottomInset * density).toFloat()
            val least = 120 * density
            val x = column?.left ?: (screen.left + side)
            val width = column?.width ?: (screen.width - side * 2)
            val floor = screen.bottom - inset
            val height = max(floor - top, least)
            val bottom = keyboardTop?.let { min(it - inset, floor) } ?: floor
            val rested = max(bottom - height, ceiling)
            return Rect(x, rested, x + width, rested + max(bottom - rested, least))
        }

        /** Interpolates the single `+` menu / photo sheet surface between its `+` seed (0) and its resting menu card (1). */
        fun menuSource(
            progress: Float,
            seed: Rect,
            seedRadius: Float,
            menu: Rect,
            menuRadius: Float,
            dragOffset: Float = 0f,
            reduceMotion: Boolean,
        ): Pair<Rect, Float> {
            val offsetMenu = menu.translate(0f, dragOffset)
            if (reduceMotion || seed == Rect.Zero) return offsetMenu to menuRadius
            fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
            val left = lerp(seed.left, offsetMenu.left, progress)
            val top = lerp(seed.top, offsetMenu.top, progress)
            val rect = Rect(
                left, top,
                left + max(lerp(seed.width, offsetMenu.width, progress), 1f),
                top + max(lerp(seed.height, offsetMenu.height, progress), 1f),
            )
            val clamped = progress.coerceIn(0f, 1f)
            val limit = min(rect.width, rect.height) / 2
            return rect to min(lerp(seedRadius, menuRadius, clamped), limit)
        }

        /**
         * Upward travel grows a sheet toward its expanded detent, its bottom edge kept. A dismissal
         * drag from compact ([translatesDownward]) carries the whole sheet, without compressing its
         * grid or clamping the grabber above the finger at a minimum height. Returns the rect and
         * the remaining downward offset.
         */
        fun stretched(rect: Rect, pull: Float, stretchesUpward: Boolean, minHeightPx: Float, translatesDownward: Boolean = false): Pair<Rect, Float> {
            if (!stretchesUpward || (translatesDownward && pull > 0f)) return rect to pull
            val minHeight = min(rect.height, minHeightPx)
            val height = max(rect.height - pull, minHeight)
            return Rect(rect.left, rect.bottom - height, rect.right, rect.bottom) to 0f
        }

        /** Glass opacity for a morphing sheet (see iOS `glassFade`). */
        fun glassFade(
            progress: Float,
            menuProgress: Float = 0f,
            shown: Boolean = true,
            armed: Boolean,
            continuousGlass: Boolean,
            dismissesDown: Boolean = false,
            reduceMotion: Boolean,
        ): Float {
            if (!shown) {
                val clampedMenu = menuProgress.coerceIn(0f, 1f)
                return if (reduceMotion) clampedMenu else (menuProgress / 0.35f).coerceIn(0f, 1f)
            }
            val clamped = progress.coerceIn(0f, 1f)
            if (dismissesDown) return clamped
            if (continuousGlass) return 1f
            if (reduceMotion) return clamped
            if (armed) return 0f
            return (progress / 0.1f).coerceIn(0f, 1f)
        }

        /** Effective opacity of the outgoing stand-in content (the + menu's rows dissolving into the photo sheet). */
        fun outgoingOpacity(reveal: Float, shown: Boolean, fade: Float): Float {
            val clamped = reveal.coerceIn(0f, 1f)
            val showing = if (shown) 1 - clamped else 0f
            return showing * fade
        }

        /** Where a sheet dismissing downward lands as progress goes from 1 to 0 ([dropPx] = `dismissDrop` in px). */
        fun downwardDismissSource(target: Rect, dropPx: Float): Rect = target.translate(0f, dropPx)

        /**
         * A dismissal destination belongs only to that fold. Once closed, the next menu must use
         * its full measured frame, not squeeze its rows into the + seed.
         */
        fun resolvedSource(shown: Boolean, sheetActive: Boolean, source: Rect, openedFrom: Rect?, landsOn: Rect?): Rect {
            if (sheetActive) return openedFrom ?: source
            return if (shown) landsOn ?: source else source
        }

        /** State of the unified `+` menu / photo sheet surface (closed, menu, photos, back to menu, downward dismiss). */
        fun unifiedMenuState(menuShown: Boolean, morph: ComposerMorph, dismissesDown: Boolean): UnifiedMenuState {
            val shown = morph.isShown
            val sheetActive = morph.isOpening || morph.foldPending
            val usesDownwardDismiss = shown && dismissesDown && !sheetActive
            val closingToMenu = shown && !dismissesDown && !sheetActive
            val menuProgress = if (menuShown || sheetActive || (closingToMenu && menuShown)) 1f else 0f
            val menuInteractive = menuShown && !sheetActive
            val entranceProgress = if (menuShown || sheetActive) 1f else 0f
            return UnifiedMenuState(usesDownwardDismiss, closingToMenu, menuProgress, menuInteractive, entranceProgress)
        }
    }
}
