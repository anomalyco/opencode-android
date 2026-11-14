package ai.opencode.feature.session

import ai.opencode.core.motion.MotionTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.min
import kotlin.math.roundToInt

// Port of iOS `Session/SessionView+Chrome.swift`: the drawer glyph and the composer's and request
// bar's swap transitions.

/**
 * The drawer button's glyph: a long stroke over a short one, drawn at whole pixels so it reads at
 * full strength beside the Material Symbols around it. Scales with the body text up to 22 dp.
 */
@Composable
fun DrawerGlyph(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val width = min(18f * density.fontScale, 22f)
    val color = LocalContentColor.current
    with(density) {
        fun pixels(points: Float): Float = (points * this.density).roundToInt().toFloat()
        val stroke = pixels(2.5f)
        val gap = pixels(width * 0.3f)
        val long = pixels(width)
        val short = pixels(width * 0.6f)
        Canvas(modifier.size(long.toDp(), (stroke * 2 + gap).toDp()).clearAndSetSemantics {}) {
            val radius = CornerRadius(stroke / 2, stroke / 2)
            drawRoundRect(color, Offset.Zero, Size(long, stroke), radius)
            drawRoundRect(color, Offset(0f, stroke + gap), Size(short, stroke), radius)
        }
    }
}

/**
 * What the composer's row swaps as a request takes it or gives it back (the placeholder, the mark
 * and +, the trailing controls): the outgoing leaves on the same frame the incoming arrives
 * (`Motion.swap`), so the row never stands empty. Reduce Motion cross-fades.
 */
object RequestBarSwap {
    fun enter(reducedMotion: Boolean): EnterTransition = MotionTransition.swap(reducedMotion).enter

    fun exit(reducedMotion: Boolean): ExitTransition = MotionTransition.swap(reducedMotion).exit
}

/** The composer and the call trade places on one surface, on the same frame (`Motion.swap`); a cross-fade under Reduce Motion. */
object ComposerSwap {
    fun enter(reducedMotion: Boolean): EnterTransition = MotionTransition.swap(reducedMotion).enter

    fun exit(reducedMotion: Boolean): ExitTransition = MotionTransition.swap(reducedMotion).exit
}
