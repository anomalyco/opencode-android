package ai.opencode.feature.composer.modelpicker

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.max
import kotlin.math.min

// The level arc the deleted Sheet and Grow from Composer pickers shared with One Picker.

/** The level glyph's arc geometry. */
object LevelArc {
    const val TRACK_OPACITY = 0.28

    data class Pose(val fill: Double, val lit: Double)

    fun pose(index: Int, count: Int): Pose {
        val levels = max(count - 1, 1)
        if (index <= 0) return Pose((levels + 1).toDouble() / (2 * levels).toDouble(), 0.45)
        return Pose(min(index, levels).toDouble() / levels.toDouble(), 1.0)
    }

    /** Stroke width in dp for a glyph [size] dp across. */
    fun stroke(size: Double, needle: Boolean): Double = LevelScrubberTunables.arcStroke * size / 20 * (if (needle) 0.7 else 1.0)

    fun center(size: Size): Offset = Offset(size.width / 2, size.height * 0.55f)

    fun radius(size: Size): Float = min(size.width, size.height) * 0.37f

    /** Degrees from 3 o'clock, clockwise (y down). */
    fun start(span: Double): Double = 90 + (360 - span) / 2

    fun draw(scope: DrawScope, fill: Double, span: Double, color: Color, stroke: Float) {
        val clamped = fill.coerceIn(0.0, 1.0)
        if (clamped <= 0.001) return
        val center = center(scope.size)
        val r = radius(scope.size)
        scope.drawArc(
            color = color,
            startAngle = start(span).toFloat(),
            sweepAngle = (span * clamped).toFloat(),
            useCenter = false,
            topLeft = Offset(center.x - r, center.y - r),
            size = Size(r * 2, r * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * The level arc's stroke and span, at their shipped defaults (Android has no tuning store, so
 * `sectionTitles`, `register` and `unregister` are n/a).
 */
object LevelScrubberTunables {
    /** `arcStroke` "Arc stroke (pt at 20 pt)". */
    const val arcStroke = 2.45

    /** `arcSpan` "Arc span (°)". */
    const val arcSpan = 240.0
}
