package ai.opencode.core.ui

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

/**
 * iOS `ScreenCoveredTrait`: true while another screen rests over this one. Continuous animations
 * beneath read it to pause.
 */
val LocalScreenCovered: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }
