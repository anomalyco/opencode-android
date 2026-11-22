package ai.opencode.feature.transcript.widgets

import ai.opencode.core.ui.OpenCodeColors
import androidx.compose.ui.graphics.toArgb

/**
 * A draw step's `{ commands }` as flat arrays, filled from the binary format ([AgentWidgetWire]) with colours resolved
 * against the theme, so a frame of 2,000 commands decodes without garbage beyond its strings. The renderer reads the
 * streams back in order.
 *
 * Per command: an op in [ints], its numbers in [floats], its texts in [strings]; a paint follows
 * a shape's numbers as `fill kind, fill colour | stop count, stroke kind, stroke colour, cap` in
 * [ints] and `width` (plus a gradient's geometry and stop offsets) in [floats], a gradient's stop
 * colours in [ints].
 */
class AgentWidgetDrawList {
    var ints = IntArray(1024)
        private set
    var floats = FloatArray(4096)
        private set
    val strings = ArrayList<String>()
    var intCount = 0
        private set
    var floatCount = 0
        private set
    var commandCount = 0
        private set

    fun clear() {
        intCount = 0
        floatCount = 0
        commandCount = 0
        strings.clear()
    }

    internal fun int(value: Int) {
        if (intCount == ints.size) ints = ints.copyOf(ints.size * 2)
        ints[intCount++] = value
    }

    internal fun counted() {
        commandCount++
    }

    internal fun float(value: Float) {
        if (floatCount == floats.size) floats = floats.copyOf(floats.size * 2)
        floats[floatCount++] = value
    }

    /** Theme colours a command may name instead of a value, in [Token] order, as ARGB. */
    class Palette(val tokens: IntArray) {
        companion object {
            /** The widgets spec's tokens in the app's theme: `accent` a saturated colour labels read on, never a neutral. */
            fun of(colors: OpenCodeColors) = Palette(
                intArrayOf(
                    colors.text.toArgb(), colors.secondaryText.toArgb(), colors.contentAccent.toArgb(), colors.canvas.toArgb(),
                    colors.userBubble.toArgb(), colors.border.toArgb(), colors.success.toArgb(), colors.warning.toArgb(), colors.danger.toArgb(),
                ),
            )
        }
    }

    /** The theme tokens, in the binary format's order (colour kinds 1–9). */
    enum class Token { Text, Muted, Accent, Bg, Surface, Border, Success, Warning, Error }

    companion object {
        const val MAX_COMMANDS = 2_000

        const val SAVE = 1
        const val RESTORE = 2
        const val TRANSLATE = 3
        const val ROTATE = 4
        const val SCALE = 5
        const val ALPHA = 6
        const val RECT = 7
        const val RRECT = 8
        const val CIRCLE = 9
        const val LINE = 10
        const val PATH = 11
        const val TEXT = 12
        const val IMAGE = 13

        const val FILL_NONE = 0
        const val FILL_COLOR = 1
        const val FILL_LINEAR = 2
        const val FILL_RADIAL = 3

        const val SEG_M = 1
        const val SEG_L = 2
        const val SEG_Q = 3
        const val SEG_C = 4
        const val SEG_Z = 5

        const val ALIGN_LEFT = 0
        const val ALIGN_CENTER = 1
        const val ALIGN_RIGHT = 2
    }
}
