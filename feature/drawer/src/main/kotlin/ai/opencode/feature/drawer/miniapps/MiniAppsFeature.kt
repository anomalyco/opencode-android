package ai.opencode.feature.drawer.miniapps

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.FileDirectory
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.feature.transcript.widgets.AgentWidgetInstances
import ai.opencode.server.AgentWidgetClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.live.isWidgetRPCUnavailable
import ai.opencode.server.model.OpenCodeWidgetInstance
import ai.opencode.server.model.OpenCodeWidgetState

/**
 * Mini apps: the server's widgets outside their sessions. All Widgets lists every one; the dashboard holds the ones
 * added to it, live, on a grid ([MiniAppsGrid]) laid out in edit mode and kept per server on this device.
 */
class MiniAppsFeature : Reducer<MiniAppsFeature.State, MiniAppsFeature.Action> {
    data class State(
        val server: OpenCodeServer,
        val page: Page = Page.Dashboard,
        val instances: AgentWidgetInstances = AgentWidgetInstances(),
        val isLoading: Boolean = false,
        val hasLoaded: Boolean = false,
        /** The read failed (not "the plugin isn't loaded yet", which waits for `plugin.updated`). */
        val loadFailed: Boolean = false,
        val tiles: List<MiniAppsTile> = emptyList(),
        val isEditing: Boolean = false,
    ) {
        val dashboardKeys: Set<String> get() = tiles.mapTo(HashSet()) { it.key }
    }

    enum class Page { Dashboard, AllWidgets }

    sealed interface Action {
        data object Task : Action
        data object RetryTapped : Action
        data class InstancesLoaded(val instances: List<OpenCodeWidgetInstance>) : Action
        data class InstancesFailed(val unavailable: Boolean) : Action
        data class PageSelected(val page: Page) : Action
        /** All Widgets' toggle: adds the widget to the dashboard, or takes it off. */
        data class DashboardToggled(val key: String) : Action
        data class RemoveTapped(val key: String) : Action
        data object EditTapped : Action
        data object DoneTapped : Action
        /** A tile dropped after a move or resize: the whole layout as it settled. */
        data class LayoutDropped(val tiles: List<MiniAppsTile>) : Action
        data class WidgetChanged(val sessionID: String, val state: OpenCodeWidgetState) : Action
        data object PluginsUpdated : Action
    }

    private enum class CancelID { Load }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Task -> {
            val tiles = shared(layouts).value[state.server.persistenceID].orEmpty()
            load(state.copy(tiles = tiles))
        }
        Action.RetryTapped -> load(state)
        Action.PluginsUpdated -> if (state.hasLoaded && !state.loadFailed) Reduced(state) else load(state)
        is Action.InstancesLoaded -> {
            AppLog.app.info { "Mini apps: ${action.instances.size} widgets" }
            following(state.copy(instances = state.instances.loaded(action.instances), isLoading = false, hasLoaded = true, loadFailed = false))
        }
        is Action.InstancesFailed -> Reduced(state.copy(isLoading = false, loadFailed = !action.unavailable))
        is Action.PageSelected -> Reduced(state.copy(page = action.page))
        is Action.DashboardToggled -> {
            val tiles = if (action.key in state.dashboardKeys) {
                MiniAppsGrid.removing(state.tiles, action.key)
            } else {
                MiniAppsGrid.adding(state.tiles, action.key)
            }
            saving(state.copy(tiles = tiles))
        }
        is Action.RemoveTapped -> {
            val tiles = MiniAppsGrid.removing(state.tiles, action.key)
            // Edit mode has nothing left to arrange, and without tiles there is no Done to leave it.
            saving(state.copy(tiles = tiles, isEditing = state.isEditing && tiles.isNotEmpty()))
        }
        Action.EditTapped -> Reduced(state.copy(isEditing = true, page = Page.Dashboard))
        Action.DoneTapped -> saving(state.copy(isEditing = false))
        is Action.LayoutDropped -> {
            val keys = state.dashboardKeys
            val tiles = action.tiles.filter { it.key in keys }
            if (tiles.size != state.tiles.size || !MiniAppsGrid.isValid(tiles)) Reduced(state) else saving(state.copy(tiles = tiles))
        }
        is Action.WidgetChanged -> {
            val (instances, change) = state.instances.changed(action.sessionID, action.state)
            val next = following(state.copy(instances = instances))
            if (change == AgentWidgetInstances.Change.NeedsRefetch) {
                Reduced(next.state, Effect.merge(next.effect, fetch(state.server)))
            } else {
                next
            }
        }
    }

    private fun load(state: State): Reduced<State, Action> = Reduced(state.copy(isLoading = true), fetch(state.server))

    private fun fetch(server: OpenCodeServer): Effect<Action> = Effect.run<Action> { send ->
        try {
            send(Action.InstancesLoaded(dependency(AgentWidgetClient).instances(server)))
        } catch (error: OpenCodeClientError) {
            AppLog.app.notice { "Mini apps: widgets read failed (${error.rpcErrorType ?: "transport"})" }
            send(Action.InstancesFailed(unavailable = error.isWidgetRPCUnavailable))
        }
    }.cancellable(CancelID.Load, cancelInFlight = true)

    /** Tiles of replaced widgets move to the widget that replaced them, where they were. */
    private fun following(state: State): Reduced<State, Action> {
        val tiles = state.tiles.map { it.copy(key = state.instances.current(it.key)) }.distinctBy { it.key }
        if (tiles == state.tiles) return Reduced(state)
        return saving(state.copy(tiles = MiniAppsGrid.compacted(tiles)))
    }

    private fun saving(state: State): Reduced<State, Action> {
        val id = state.server.persistenceID
        shared(layouts).update { all -> if (all[id] == state.tiles) all else all + (id to state.tiles) }
        return Reduced(state)
    }

    companion object {
        /** Each server's dashboard on this device, by server persistence ID. */
        val layouts = SharedKey.fileStorage<Map<String, List<MiniAppsTile>>>(FileDirectory.Documents, "mini-apps-layouts.json", emptyMap())
    }
}
