package ai.opencode.feature.session

import ai.opencode.core.haptics.Haptic
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.Shared
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.core.strings.localized
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.composer.AgentPickerSharedKeys
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentDisplayName
import ai.opencode.server.model.OpenCodeAutocompleteAgent
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.model
import ai.opencode.server.model.savedLevel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.time.Duration.Companion.seconds

// Agent Selection (`SharedKeys.agentSelection`): the composer's agent menu. A pick is
// remembered per session (`sessionAgents`) and, like a model pick, applied (`POST …/agent`) just
// before the next send; a draft starts with its pick, else the Settings default (`defaultAgentID`).

/** Agent Selection's part of the session's state. */
data class AgentSelectionState(
    /** The server's primary agents, as last listed. */
    val primaryAgents: List<OpenCodeAutocompleteAgent> = emptyList(),
    /** The server's `/api/model/default`, as last read. */
    val defaultModel: OpenCodeModel? = null,
    /** Whether the person picked a model by hand on this draft. */
    val hasPickedModel: Boolean = false,
    /** The agent a draft starts with, when picked before its first send. */
    val draftAgent: String? = null,
    /** The agent each queued send was sent with, applied when its message starts or Send Now makes it a steer (as `queuedPicks`). */
    val queuedPicks: Map<String, String> = emptyMap(),
    val queuedPickDue: String? = null,
    /**
     * The folder `primaryAgents` were listed from: the chip mounting again (each time the
     * composer expands) asks only for another folder's.
     */
    val listedDirectory: ListedDirectory? = null,
    val isEnabled: Shared<Boolean> = shared(SharedKeys.agentSelection),
    val sessionAgents: Shared<Map<String, String>> = shared(AgentPickerSharedKeys.sessionAgents),
    val defaultAgentID: Shared<String> = shared(AgentPickerSharedKeys.defaultAgentID),
    val knownPrimaryAgents: Shared<List<OpenCodeAutocompleteAgent>> = shared(AgentPickerSharedKeys.knownPrimaryAgents),
)

/** The folder an agents list came from (null: the server's own). */
data class ListedDirectory(val directory: String?)

/** Tries for the agents list, the first, then retries after 1 s and 2 s. */
internal const val agentsAttempts = 3

/** The agent menu's list, picks and applications. */
internal fun SessionFeature.reduceAgents(state: State, action: Action): Reduced<State, Action> = when (action) {
    Action.AgentsRequested -> {
        if (!state.agentSelection.isEnabled.value) {
            Reduced(state)
        } else if (state.primaryAgents.isNotEmpty() && state.agentSelection.listedDirectory == ListedDirectory(state.effectiveDirectory)) {
            // Already listed for this folder: the chip shows that list.
            Reduced(state)
        } else {
            val directory = state.effectiveDirectory
            val server = state.server
            val clock = clock
            val client = openCodeClient
            Reduced(
                state,
                Effect.run<Action> { send ->
                    // The chip asks once, when it mounts: a dropped request would leave the menu on
                    // "Loading Agents…" until the app restarts, so a failure retries a few times
                    // before giving up.
                    var attempt = 0
                    while (true) {
                        try {
                            send(Action.AgentsResponse(Result.success(client.primaryAgents(server, directory))))
                            return@run
                        } catch (error: OpenCodeClientError) {
                            attempt += 1
                            if (attempt >= agentsAttempts || !currentCoroutineContext().isActive) {
                                send(Action.AgentsResponse(Result.failure(error)))
                                return@run
                            }
                            clock.sleep((1 shl (attempt - 1)).seconds)
                        }
                    }
                }.cancellable(SessionFeature.CancelID.Agents, cancelInFlight = true),
            )
        }
    }

    Action.AgentsChanged ->
        if (!state.agentSelection.isEnabled.value) {
            Reduced(state)
        } else {
            Reduced(state.copy(agentSelection = state.agentSelection.copy(listedDirectory = null)), Effect.send(Action.AgentsRequested))
        }

    is Action.AgentsResponse -> {
        val agents = action.result.getOrNull()
        if (agents != null) {
            // A momentarily empty answer never replaces a list already shown.
            if (agents.isEmpty() && state.primaryAgents.isNotEmpty()) {
                Reduced(state)
            } else {
                var next = state
                if (next.primaryAgents != agents) next = next.withPrimaryAgents(agents)
                val listed = ListedDirectory(next.effectiveDirectory)
                if (agents.isNotEmpty() && next.agentSelection.listedDirectory != listed) {
                    next = next.copy(agentSelection = next.agentSelection.copy(listedDirectory = listed))
                }
                if (agents.isNotEmpty() && next.knownPrimaryAgents != agents) {
                    next.agentSelection.knownPrimaryAgents.set(agents)
                }
                if (agents.isNotEmpty() && next.session == null && !next.agentSelection.hasPickedModel) {
                    next.resolvedDefaultModel()?.let { model ->
                        if (model != next.currentModel) next = next.copy(currentModel = model)
                    }
                }
                Reduced(next)
            }
        } else {
            val error = action.result.exceptionOrNull()
            val summary = (error as? OpenCodeClientError)?.logSummary ?: error?.let { it::class.java.simpleName } ?: "client error"
            AppLog.network.notice("Couldn’t load agents: $summary")
            Reduced(state)
        }
    }

    is Action.AgentPicked -> {
        val id = action.agent
        if (id == state.currentAgentID) {
            Reduced(state)
        } else {
            haptics.play(Haptic.selection)
            val session = state.session
            if (session != null) {
                state.agentSelection.sessionAgents.update { it + (session.id to id) }
                Reduced(state)
            } else {
                var next = state.withDraftAgent(id)
                if (next.currentAgentEntry()?.model != null) {
                    next.resolvedDefaultModel()?.let { model ->
                        if (model != next.currentModel) next = next.copy(currentModel = model)
                        if (next.agentSelection.hasPickedModel) {
                            next = next.copy(agentSelection = next.agentSelection.copy(hasPickedModel = false))
                        }
                    }
                } else if (!next.agentSelection.hasPickedModel) {
                    next.resolvedDefaultModel()?.let { model ->
                        if (model != next.currentModel) next = next.copy(currentModel = model)
                    }
                }
                Reduced(next)
            }
        }
    }

    is Action.AgentApplied -> Reduced(state.agentApplied(action.agent, action.sessionID))

    else -> Reduced(state)
}

/** The pick a send would apply now, while Agent Selection is on. */
internal fun SessionFeature.waitingAgent(state: State): String? =
    if (state.agentSelection.isEnabled.value) state.sendAgent else null

/** A queued send's message started with its agent still waiting. */
internal fun SessionFeature.applyDueAgentPick(state: State): Reduced<State, Action> {
    val agent = state.queuedAgentPickDue ?: return Reduced(state)
    val next = state.withQueuedAgentPickDue(null)
    val session = next.session ?: return Reduced(next)
    return Reduced(next, applyQueuedAgentPick(agent, session.id, next.server))
}

/**
 * Applies a queued send's agent once its message starts or becomes a steer. A refusal leaves the
 * pick waiting, so the next send applies it (and fails, saying why, if it's still refused).
 */
internal fun SessionFeature.applyQueuedAgentPick(agent: String, sessionID: String, server: OpenCodeServer): Effect<Action> {
    val client = openCodeClient
    return Effect.run { send ->
        try {
            client.switchAgent(server, sessionID, agent)
            send(Action.AgentApplied(sessionID, agent))
        } catch (error: OpenCodeClientError) {
            AppLog.send.notice("Queued agent pick not applied: ${error.logSummary}")
        }
    }
}

/** A draft's pick (or the default it started with) becomes its session's. */
internal fun SessionFeature.adoptDraftAgent(sessionID: String, state: State): State {
    val picked = state.draftAgent
    val next = state.withDraftAgent(null)
    if (!next.agentSelection.isEnabled.value) return next
    val agent = picked ?: next.defaultAgentID.ifEmpty { null } ?: return next
    if (next.sessionAgents[sessionID] != agent) {
        next.agentSelection.sessionAgents.update { it + (sessionID to agent) }
    }
    return next
}

// region `SessionFeature.State` members

val State.primaryAgents: List<OpenCodeAutocompleteAgent> get() = agentSelection.primaryAgents
fun State.withPrimaryAgents(value: List<OpenCodeAutocompleteAgent>): State = copy(agentSelection = agentSelection.copy(primaryAgents = value))

fun State.withDefaultModel(value: OpenCodeModel?): State =
    if (agentSelection.defaultModel == value) this else copy(agentSelection = agentSelection.copy(defaultModel = value))

val State.draftAgent: String? get() = agentSelection.draftAgent
fun State.withDraftAgent(value: String?): State = copy(agentSelection = agentSelection.copy(draftAgent = value))

val State.queuedAgentPicks: Map<String, String> get() = agentSelection.queuedPicks
fun State.withQueuedAgentPicks(value: Map<String, String>): State = copy(agentSelection = agentSelection.copy(queuedPicks = value))

val State.queuedAgentPickDue: String? get() = agentSelection.queuedPickDue

fun State.withQueuedAgentPickDue(value: String?): State = copy(agentSelection = agentSelection.copy(queuedPickDue = value))

val State.sessionAgents: Map<String, String> get() = agentSelection.sessionAgents.value
val State.defaultAgentID: String get() = agentSelection.defaultAgentID.value
val State.knownPrimaryAgents: List<OpenCodeAutocompleteAgent> get() = agentSelection.knownPrimaryAgents.value

/** The server has [agent] for [sessionID]. The pick stays remembered; it no longer waits once it matches. */
fun State.agentApplied(agent: String, sessionID: String): State {
    var next = this
    val index = familySessions.indexOfFirst { it.id == sessionID }
    if (index >= 0 && familySessions[index].agent != agent) {
        next = next.copy(familySessions = familySessions.toMutableList().also { it[index] = it[index].copy(agent = agent) })
    }
    val session = next.session
    if (session != null && session.id == sessionID && session.agent != agent) next = next.copy(session = session.copy(agent = agent))
    return next
}

/**
 * The agent the composer shows: this session's pick, else the agent the server reports for it; a
 * draft's pick, else the Settings default.
 */
val State.currentAgentID: String?
    get() {
        val session = session
        if (session != null) {
            return sessionAgents[session.id] ?: session.agent ?: primaryAgents.firstOrNull()?.id ?: knownPrimaryAgents.firstOrNull()?.id
        }
        return draftAgent ?: defaultAgentID.ifEmpty { null } ?: primaryAgents.firstOrNull()?.id ?: knownPrimaryAgents.firstOrNull()?.id
    }

internal fun State.currentAgentEntry(): OpenCodeAutocompleteAgent? {
    val id = currentAgentID
    return primaryAgents.firstOrNull { it.id == id }
        ?: knownPrimaryAgents.firstOrNull { it.id == id }
        ?: primaryAgents.firstOrNull()
}

/**
 * The composer's model: the current agent's configured model at its own or the remembered level,
 * else the server's default (a draft at the level remembered for it).
 */
internal fun State.resolvedDefaultModel(): OpenCodeModelRef? {
    val agentModel = currentAgentEntry()?.model
    if (agentModel != null) {
        val catalogModel = models.model(agentModel)
        val variant = agentModel.variant ?: catalogModel?.savedLevel(modelVariants.value)
        return catalogModel?.ref(variant) ?: agentModel.copy(variant = variant)
    }
    return if (session == null) {
        OpenCodeModelCatalog(models, agentSelection.defaultModel).newSessionModel(modelVariants.value)
    } else {
        agentSelection.defaultModel?.ref
    }
}

val State.currentAgentName: String
    get() {
        val id = currentAgentID
        return primaryAgents.firstOrNull { it.id == id }?.displayName ?: AgentDisplayName.of(id) ?: localized(R.string.session_agent_fallback_name)
    }

/** The agent a send applies first, or null when the session already has it. */
val State.sendAgent: String?
    get() {
        val session = session ?: return draftAgent ?: defaultAgentID.ifEmpty { null }
        val picked = sessionAgents[session.id] ?: return null
        return if (picked != session.agent) picked else null
    }

// endregion
