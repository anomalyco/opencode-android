package ai.opencode.core.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Generation-guarded mount/unmount gate (iOS `PresenceGate`): unmounts only when the latest
 * generation's outro completes while still closed, so a quick reopen never unmounts. For custom
 * drivers; `AnimatedVisibility` already does this.
 */
data class PresenceGate(
    val wantsOpen: Boolean,
    val isPresented: Boolean,
    val isMounted: Boolean,
    val generation: Int = 0,
    /** True while an outro is in flight waiting for [complete]. */
    private val isClosing: Boolean = false,
) {
    constructor(isOpen: Boolean = false) : this(wantsOpen = isOpen, isPresented = isOpen, isMounted = isOpen)

    /**
     * Prepares the gate for [open]. A null token means just mounted (call [beginAnimation] on its
     * first frame) or no transition needed; otherwise pass it to [complete].
     */
    fun request(open: Boolean): Pair<PresenceGate, Int?> {
        if (open && !isMounted) {
            return copy(wantsOpen = true, isMounted = true, isClosing = false) to null
        }
        return copy(wantsOpen = open).beginAnimation(open)
    }

    /**
     * Starts or retargets the presentation toward [open], returning the new generation if a
     * transition is needed. Repeated `false` calls during an outro are no-ops.
     */
    fun beginAnimation(open: Boolean): Pair<PresenceGate, Int?> {
        val wanting = copy(wantsOpen = open)
        if (isPresented == open) {
            // Unmount at once only if mounted by request(true) and cancelled before any intro began.
            val next = if (!open && !isClosing) wanting.copy(isMounted = false) else wanting
            return next to null
        }
        val nextGeneration = generation + 1
        return wanting.copy(generation = nextGeneration, isPresented = open, isClosing = !open) to nextGeneration
    }

    /** Call when the animation for [completedGeneration] finishes. Returns whether it unmounted. */
    fun complete(completedGeneration: Int?): Pair<PresenceGate, Boolean> {
        if (completedGeneration != generation || wantsOpen) return this to false
        return copy(isMounted = false, isClosing = false) to true
    }
}

/**
 * Blur, opacity and optional rise for content entering a morphing surface (iOS `MotionBlurReveal`).
 * Blur runs only for 0.02 < [progress] < 0.98; Reduce Motion is opacity-only.
 */
fun Modifier.motionBlurReveal(
    progress: Float,
    maxBlur: Dp = Motion.blurPanel,
    rise: Dp = 0.dp,
    reduceMotion: Boolean = false,
): Modifier {
    val clamped = progress.coerceIn(0f, 1f)
    val radius = MotionBlurReveal.blurRadius(clamped, maxBlur.value, reduceMotion)
    val offset = MotionBlurReveal.verticalOffset(clamped, rise.value, reduceMotion)
    val opacity = if (clamped <= 0.02f) 0f else clamped
    val blurred = this.transitionBlur(radius.dp)
    return blurred.graphicsLayer {
        alpha = opacity
        translationY = offset.dp.toPx()
    }
}

/** The pure maths behind [motionBlurReveal] (iOS's testable statics). */
object MotionBlurReveal {
    fun blurRadius(progress: Float, maxBlur: Float, reduceMotion: Boolean): Float {
        val clamped = progress.coerceIn(0f, 1f)
        if (reduceMotion || clamped >= 0.98f) return 0f
        return (1 - clamped) * maxBlur
    }

    fun verticalOffset(progress: Float, rise: Float, reduceMotion: Boolean): Float {
        val clamped = progress.coerceIn(0f, 1f)
        if (reduceMotion) return 0f
        return (1 - clamped) * rise
    }
}

// Presence

/**
 * iOS `MotionPresence`: mounts [content] through a [PresenceGate] and animates it in and out on
 * [role]. A leaving view takes no touches and is hidden from accessibility.
 */
@Composable
fun MotionPresence(
    isOpen: Boolean,
    modifier: Modifier = Modifier,
    role: MotionRole = MotionRole.MicroIn,
    reduceMotion: Boolean = rememberReduceMotion(),
    content: @Composable () -> Unit,
) {
    var gate by remember { mutableStateOf(PresenceGate(isOpen)) }
    val progress = remember { Animatable(if (isOpen) 1f else 0f) }
    LaunchedEffect(isOpen) {
        val (requested, requestToken) = gate.request(isOpen)
        gate = requested
        var token = requestToken
        if (isOpen && requested.isMounted && !requested.isPresented) {
            // Just mounted at its entering pose: start the entrance on the next frame (iOS onAppear).
            withFrameNanos { }
            val (begun, beginToken) = gate.beginAnimation(true)
            gate = begun
            token = beginToken
        }
        if (token == null) return@LaunchedEffect
        val phase = if (isOpen) MotionPhase.Enter else MotionPhase.Exit
        progress.animateTo(if (isOpen) 1f else 0f, role.animation(phase, reduceMotion))
        gate = gate.complete(token).first
    }
    if (!gate.isMounted) return
    val resting = if (gate.wantsOpen) MotionPose.entering(role) else MotionPose.leaving(role)
    val pose = resting.scaled(by = 1f - progress.value)
    var boxModifier = modifier.motionPose(pose, reduceMotion)
    if (!gate.wantsOpen) {
        boxModifier = boxModifier
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        for (change in event.changes) change.consume()
                    }
                }
            }
    }
    Box(boxModifier) { content() }
}

// Transitions

/** iOS `MotionRollDirection`: which way a rolling value moves as it changes. */
enum class MotionRollDirection { Up, Down }

/**
 * iOS `AnyTransition.motionMicro` / `.motionSwap` / `.motionRoll`: role-timed transitions for
 * `AnimatedVisibility` and `AnimatedContent`; reduced cross-fades under Reduce Motion.
 */
data class MotionTransition(val enter: EnterTransition, val exit: ExitTransition) {
    val content: ContentTransform get() = enter togetherWith exit

    companion object {
        /** Arrives in the micro-in pose on micro in, leaves in the swap pose on micro out. */
        fun micro(reduceMotion: Boolean): MotionTransition {
            if (reduceMotion) return reduced()
            return MotionTransition(
                enter = pose(MotionPose.microIn, MotionRole.MicroIn, MotionPhase.Enter),
                exit = poseOut(MotionPose.swap, MotionRole.MicroOut, MotionPhase.Exit),
            )
        }

        /** Swaps in place: the swap pose in and out on the swap role. */
        fun swap(reduceMotion: Boolean): MotionTransition {
            if (reduceMotion) return reduced()
            return MotionTransition(
                enter = pose(MotionPose.swap, MotionRole.Swap, MotionPhase.Enter),
                exit = poseOut(MotionPose.swap, MotionRole.Swap, MotionPhase.Exit),
            )
        }

        /**
         * Rolls like a counter: in from [MotionTunables.rollDistance] away, out the other way, on
         * [role] (layout by default; a title roll, iOS `.titleRoll`, passes `Motion.swap`).
         */
        fun roll(
            direction: MotionRollDirection,
            reduceMotion: Boolean,
            density: Density,
            role: MotionRole = Motion.layout,
        ): MotionTransition {
            if (reduceMotion) return reduced()
            val sign = if (direction == MotionRollDirection.Up) 1 else -1
            val distance = with(density) { MotionTunables.rollDistance.value.roundToPx() } * sign
            val swapPose = MotionPose.swap
            val enter = fadeIn(role.animation(MotionPhase.Enter)) +
                scaleIn(role.animation(MotionPhase.Enter), initialScale = swapPose.scale) +
                slideInVertically(role.animation(MotionPhase.Enter)) { distance }
            val exit = fadeOut(role.animation(MotionPhase.Enter)) +
                scaleOut(role.animation(MotionPhase.Enter), targetScale = swapPose.scale) +
                slideOutVertically(role.animation(MotionPhase.Enter)) { -distance }
            return MotionTransition(enter, exit)
        }

        private fun reduced(): MotionTransition = MotionTransition(
            enter = fadeIn(MotionRole.Reduced.animation(reduceMotion = true)),
            exit = fadeOut(MotionRole.Reduced.animation(reduceMotion = true)),
        )

        private fun pose(from: MotionPose, role: MotionRole, phase: MotionPhase): EnterTransition =
            fadeIn(role.animation(phase), initialAlpha = from.opacity) +
                scaleIn(role.animation(phase), initialScale = from.scale)

        private fun poseOut(to: MotionPose, role: MotionRole, phase: MotionPhase): ExitTransition =
            fadeOut(role.animation(phase), targetAlpha = to.opacity) +
                scaleOut(role.animation(phase), targetScale = to.scale)
    }
}

/**
 * iOS `MotionSwap`: shows [value] through [content], swapping (or rolling, given [roll]) when
 * it changes.
 */
@Composable
fun <T> MotionSwap(
    value: T,
    modifier: Modifier = Modifier,
    roll: MotionRollDirection? = null,
    reduceMotion: Boolean = rememberReduceMotion(),
    content: @Composable (T) -> Unit,
) {
    val density = LocalDensity.current
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            val transition = if (roll != null) {
                MotionTransition.roll(roll, reduceMotion, density)
            } else {
                MotionTransition.swap(reduceMotion)
            }
            transition.content
        },
        label = "motionSwap",
    ) { shown -> content(shown) }
}
