package ai.opencode.feature.drawer.miniapps

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.experiments.ExperimentalFeatures
import ai.opencode.core.experiments.key
import ai.opencode.core.sharing.shared
import ai.opencode.feature.settings.HomeSettingsFeature
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/** Experimental › Mini Apps, off by default: while it's off nothing of Mini Apps shows. */
internal val isMiniAppsOn: Boolean get() = dependency(ExperimentalFeatures)(ExperimentalFeature.miniApps)

/** [isMiniAppsOn] for a view, following the switch. */
@Composable
internal fun rememberMiniAppsOn(): Boolean {
    val feature = ExperimentalFeature.miniApps
    val stored by remember(feature) { shared(feature.key).flow }.collectAsState()
    return feature.isActive(stored)
}

/** The switch, when [this] turns Mini Apps on or off. */
internal val HomeSettingsFeature.Action.miniAppsSwitch: Boolean?
    get() = when (this) {
        is HomeSettingsFeature.Action.ExperimentalFeatureChanged -> on.takeIf { feature == ExperimentalFeature.miniApps }
        is HomeSettingsFeature.Action.ExperimentalFeatureApplied -> on.takeIf { feature == ExperimentalFeature.miniApps }
        else -> null
    }
