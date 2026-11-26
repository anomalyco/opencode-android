package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.CancelID
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.settings.HomeSettingsFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.spaces.SpacesClient

/*
 * Sync Pinned Sessions (experimental): which sessions are pinned on a server is kept in its `pins`
 * space (session ID → "1"), shared with the other apps; the order of the pins stays this phone's.
 * The first sync merges this phone's pins into the space; after that the space decides.
 */

internal const val PIN_SPACE = "pins"

internal val isPinSyncOn: Boolean get() = dependency(ExperimentalFeatures)(ExperimentalFeature.syncPinnedSessions)

private val State.pinnedIDs: List<String>
    get() {
        val prefix = sessionKey("")
        return pinnedSessionKeys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
    }

/** Follows this server's pins space; each sync and change arrives as [Action.PinSpaceChanged]. */
internal fun followPins(state: State): Effect<Action> {
    val spaces = dependency(SpacesClient)
    val server = state.server
    return Effect.run<Action> { send ->
        var previous: List<String>? = null
        spaces.entries(server, PIN_SPACE).collect {
            val ids = it.keys.toList()
            send(Action.PinSpaceChanged(ids, previous))
            previous = ids
        }
    }.cancellable(CancelID.PinSync, cancelInFlight = true)
}

/** The switch, when [this] turns Sync Pinned Sessions on or off. */
internal val HomeSettingsFeature.Action.pinSyncSwitch: Boolean?
    get() = when (this) {
        is HomeSettingsFeature.Action.ExperimentalFeatureChanged -> on.takeIf { feature == ExperimentalFeature.syncPinnedSessions }
        is HomeSettingsFeature.Action.ExperimentalFeatureApplied -> on.takeIf { feature == ExperimentalFeature.syncPinnedSessions }
        else -> null
    }

/** On follows the space; off stops, and forgets every merge so turning it on again merges again. */
internal fun pinSyncSwitched(state: State, on: Boolean): HomeReduced {
    if (on) return Reduced(state, followPins(state))
    val merged = shared(SharedKeys.pinSyncServerIDs)
    if (merged.value.isNotEmpty()) merged.set(emptySet())
    return Reduced(state, Effect.cancel(CancelID.PinSync))
}

/**
 * The space's pins: removals unpin here, additions pin at the end. Once followed, only what changed
 * since [previous] applies, so writes still landing one by one undo nothing.
 */
internal fun pinSpaceChanged(state: State, remote: List<String>, previous: List<String>?): HomeReduced {
    if (!isPinSyncOn) return Reduced(state)
    val serverID = state.server.persistenceID
    val local = state.pinnedIDs
    var members = remote.toSet()
    var writes: Effect<Action> = Effect.none()
    val merged = shared(SharedKeys.pinSyncServerIDs)
    if (serverID !in merged.value) {
        val missing = local.filterNot { it in members }
        members = members + missing
        writes = writePins(state.server, added = missing, removed = emptyList())
        merged.update { it + serverID }
    }
    val gone = if (previous == null) local.filterNot { it in members } else previous.filterNot { it in members }
    val removed = gone.map(state::sessionKey).toSet()
    val added = remote.filterNot { it in local || previous?.contains(it) == true }.map(state::sessionKey)
    if (removed.isEmpty() && added.isEmpty()) return Reduced(state, writes)
    val s = state.updatingPinnedSessionKeys { keys ->
        val kept = keys.filterNot { it in removed }
        kept + added.filterNot { it in kept }
    }
    return Reduced(s, writes)
}

/** Pins and unpins made here go to the merged space; the space's own changes don't echo back. */
internal fun syncingPins(old: State, reduced: HomeReduced, action: Action): HomeReduced {
    val new = reduced.state
    if (action is Action.PinSpaceChanged || old.pinnedSessionKeys == new.pinnedSessionKeys) return reduced
    if (!isPinSyncOn || new.server.persistenceID !in shared(SharedKeys.pinSyncServerIDs).value) return reduced
    val before = old.pinnedIDs.toSet()
    val after = new.pinnedIDs.toSet()
    val writes = writePins(new.server, added = after - before, removed = before - after)
    return Reduced(new, Effect.merge(reduced.effect, writes))
}

private fun writePins(server: OpenCodeServer, added: Collection<String>, removed: Collection<String>): Effect<Action> {
    if (added.isEmpty() && removed.isEmpty()) return Effect.none()
    val spaces = dependency(SpacesClient)
    return Effect.run<Action> {
        for (id in added) spaces.set(server, PIN_SPACE, id, "1")
        for (id in removed) spaces.delete(server, PIN_SPACE, id)
    }
}
