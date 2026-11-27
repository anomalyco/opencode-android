package ai.opencode.feature.session

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import androidx.compose.animation.core.FiniteAnimationSpec

/** Where the background-work panel is. The capsule stays in its place throughout. */
enum class BackgroundWorkStage {
    /** Only the capsule shows. */
    closed,

    /** The panel is leaving. */
    capsule,
    panel,
}

/** Shipped defaults; there's no tuning UI. */
object BackgroundWorkTunables {
    /**
     * The capsule's glyph frame from its leading edge, and its count lowered so the digits' caps
     * sit centred.
     */
    const val glyphLeading: Float = 8.25f
    const val countNudge: Float = 1f / 3f

    /** The orb's and the shell glyph's disc sits a little high in its frame. */
    const val orbGlyphNudgeY: Float = 1f / 3f

    /** Between the orb and the shell disc when both run. */
    const val pairGap: Float = 4f
}

object BackgroundWorkMotion {
    /** The panel arrives above the capsule on `swap` and leaves on its shorter exit. */
    fun <T> present(opening: Boolean, reduceMotion: Boolean): FiniteAnimationSpec<T> =
        Motion.swap.animation(if (opening) MotionPhase.Enter else MotionPhase.Exit, reduceMotion)

    /** Reduce Motion's cross-fade, for the capsule's counts. */
    fun <T> fade(): FiniteAnimationSpec<T> = Motion.reduced.animation(reduceMotion = true)

    /**
     * The capsule following its counts, and the panel following its rows: surface, words and glyph
     * on one spring (`layout`), unbouncy so a word leaving is never cut by the surface coming back.
     */
    fun <T> resize(): FiniteAnimationSpec<T> = Motion.layout.animation()

    const val capsuleInset: Float = 5f

    /** Between the capsule and the panel above it (dp). */
    const val panelGap: Float = 8f

    /** The panel is as wide as its longest row, within these (dp), and scrolls past this height. */
    const val panelMinWidth: Float = 240f
    const val panelMaxWidth: Float = 360f
    const val panelMaxHeight: Float = 320f

    /** The panel grows from this scale out of the capsule's top leading corner, and shrinks back into it. */
    const val presentScale: Float = 0.85f

    /** Let go, the panel closes when pulled down past [dragCloses] (dp), or past [throwFrom] (dp) and thrown down faster than [throwCloses] (dp/s). */
    const val dragCloses: Float = 56f
    const val throwFrom: Float = 12f
    const val throwCloses: Float = 550f
}
