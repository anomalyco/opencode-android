package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import androidx.compose.animation.core.FiniteAnimationSpec

// Port of iOS `Composer/ModelPicker/ModelPickerTunables.swift`, baked to iOS's names and defaults
// (lengths in dp, times in seconds).

/**
 * iOS `ModelPickerTunables` (`modelPicker.*`): the model list's rests and glass, which One
 * Picker's detents ([ModelListDetent]), the photo sheet and the call's composer expansion still read.
 */
object ModelPickerTunables {
    const val settle = 0.6
    const val dimDark = 0.35
    const val dimLight = 0.18

    /** `modelPicker.fused.mediumMax`: how high the photo sheet first rests with the keyboard down. */
    const val mediumMax = 470.0
    const val mediumFraction = 0.5
    const val mediumMinimum = 300.0
    const val headerGap = 6.0
    const val rubberMax = 48.0
    const val rubberSoftness = 160.0
    const val closeBelow = 0.55
    /** `modelPicker.sheet.flickCloses`: a flick down from full closes the sheet at this speed (dp/s). */
    const val flickCloses = 800.0

    const val barFadeStart = 10.0
    const val barFadeEnd = 50.0
    const val contentFadeStart = 34.0
    const val contentFadeEnd = 150.0
    const val revealBlur = 8.0

    /** The glass grows on `Motion.panelOpen` and folds on `Motion.panelClose`. */
    fun <T> glass(opening: Boolean): FiniteAnimationSpec<T> =
        Motion.panelOpen.animation(if (opening) MotionPhase.Enter else MotionPhase.Exit, reduceMotion = false)
}
