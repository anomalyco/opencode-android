package ai.opencode.feature.session

import ai.opencode.core.motion.Motion
import androidx.compose.animation.core.FiniteAnimationSpec

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
    /** Reduce Motion's cross-fade, for the capsule's counts. */
    fun <T> fade(): FiniteAnimationSpec<T> = Motion.reduced.animation(reduceMotion = true)

    /**
     * The capsule following its counts: its surface, words and glyph on one spring (`layout`),
     * unbouncy so a word leaving is never cut by the surface coming back.
     */
    fun <T> resize(): FiniteAnimationSpec<T> = Motion.layout.animation()

    const val capsuleInset: Float = 5f
    /** The desktop dock's 12: the capsule is a 34 dp chip with 12 dp corners. */
    const val panelRadius: Float = 12f
}
