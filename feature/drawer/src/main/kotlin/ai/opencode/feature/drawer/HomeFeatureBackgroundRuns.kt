package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.settings.HomeSettingsFeature
import ai.opencode.feature.system.background.BackgroundRunServiceClient

/** What Stay Connected During Runs' service follows. */
internal data class BackgroundRunDemand(val wanted: Boolean, val isInBackground: Boolean)

internal val State.backgroundRunDemand: BackgroundRunDemand
    get() = BackgroundRunDemand(
        wanted = isStreaming && hasConnected && !credentialsRejected && activeSessionIDs.isNotEmpty(),
        isInBackground = connection.isInBackground,
    )

/**
 * Stay Connected During Runs (Experimental): the service runs while any session on this server
 * works. Android refuses to start it from the background, so a run first seen there goes without.
 */
internal fun syncBackgroundRuns(state: State): HomeReduced {
    val client = dependency(BackgroundRunServiceClient)
    val demand = state.backgroundRunDemand
    if (!dependency(ExperimentalFeatures)(ExperimentalFeature.backgroundRuns) || !demand.wanted) {
        return Reduced(state, Effect.run { client.stop() })
    }
    if (demand.isInBackground) return Reduced(state)
    return Reduced(state, Effect.run { client.start() })
}

internal val HomeSettingsFeature.Action.switchesBackgroundRuns: Boolean
    get() {
        if (this is HomeSettingsFeature.Action.ExperimentalFeatureChanged) return feature == ExperimentalFeature.backgroundRuns
        if (this is HomeSettingsFeature.Action.ExperimentalFeatureApplied) return feature == ExperimentalFeature.backgroundRuns
        return false
    }
