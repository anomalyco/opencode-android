package ai.opencode.feature.session

import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.CancelID
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeJob
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.SessionActivity
import java.time.Instant

// Background jobs, as the desktop's activity pill reads them: listed once when the session opens or
// the connection returns, then kept by `experimental.job.updated` events. Never polled.

/** A script the session moved to the background, while its job runs. */
data class BackgroundScript(val id: String, val label: String, val startedAt: Instant?)

/** Lists the open session's running jobs, its subagents' included. */
internal fun SessionFeature.refreshJobs(state: State): Reduced<State, Action> {
    val sessionID = state.session?.id ?: return Reduced(state)
    val server = state.server
    val client = openCodeClient
    return Reduced(
        state,
        Effect.run<Action> { send ->
            try {
                send(Action.JobsResponse(sessionID, Result.success(client.runningJobs(server, sessionID))))
            } catch (error: OpenCodeClientError) {
                send(Action.JobsResponse(sessionID, Result.failure(error)))
            }
        }.cancellable(CancelID.Jobs, cancelInFlight = true),
    )
}

internal fun SessionFeature.reduceJobs(state: State, action: Action): Reduced<State, Action>? = when (action) {
    Action.JobsRefreshRequested -> refreshJobs(state)

    is Action.JobsResponse -> {
        val listed = action.result.getOrNull()
        if (listed == null || state.session?.id != action.sessionID) Reduced(state)
        else Reduced(state.copy(jobs = listed.filter { it.isRunning }.associateBy { it.id }))
    }

    is Action.JobUpdated -> {
        val job = action.job
        when {
            job.isRunning -> Reduced(state.copy(jobs = state.jobs + (job.id to job)))
            job.id in state.jobs -> Reduced(state.copy(jobs = state.jobs - job.id))
            else -> Reduced(state)
        }
    }

    is Action.BackgroundScriptStopTapped -> cancelJobs(state, listOf(action.jobID))

    is Action.BackgroundSubagentStopTapped -> {
        val sessionID = action.sessionID
        val jobs = state.subagentJobIDs(sessionID)
        val interrupts = sessionID in state.activeFamilySessionIDs
        val server = state.server
        val client = openCodeClient
        val cancel = cancelJobs(state, jobs)
        Reduced(
            cancel.state,
            Effect.merge(
                if (interrupts) Effect.run<Action> { runCatching { client.interrupt(server, sessionID) } } else Effect.none(),
                cancel.effect,
            ),
        )
    }

    else -> null
}

/** Cancelling a job that already finished does nothing; its update removes it. */
private fun SessionFeature.cancelJobs(state: State, ids: List<String>): Reduced<State, Action> {
    if (ids.isEmpty()) return Reduced(state)
    val server = state.server
    val client = openCodeClient
    return Reduced(state, Effect.run<Action> { for (id in ids) runCatching { client.cancelJob(server, id) } })
}

/** The session's parents, as far as its family is known. */
private val State.familyParents: Map<String, String>
    get() = HashMap<String, String>().also { parents -> for (member in familySessions) member.parentID?.let { parents[member.id] = it } }

/** Running jobs owned by [sessionID] or any session below it; a subagent job belongs to the child it runs. */
internal fun State.subagentJobIDs(sessionID: String, parents: Map<String, String> = familyParents): List<String> =
    jobs.values.filter { job ->
        val owner = job.ownerSessionID
        owner == sessionID || sessionID in SessionActivity.ancestors(setOf(owner), parents)
    }.map { it.id }

/** The session's own Code Mode scripts running in the background, named by their first line. */
val State.backgroundScripts: List<BackgroundScript>
    get() {
        val sessionID = session?.id ?: return emptyList()
        val running = jobs.values.filter { it.kind == OpenCodeJob.Kind.CodeMode && it.sessionID == sessionID }
        if (running.isEmpty()) return emptyList()
        val labels = HashMap<String, String?>()
        for (item in items) {
            val call = (item as? OpenCodeTranscriptItem.Tool)?.call ?: continue
            if (call.name != "execute" || call.status != OpenCodeToolCall.Status.Completed) continue
            val jobID = call.metadata["jobID"]?.string ?: continue
            labels[jobID] = firstLine((call.input as? OpenCodeJSONValue.Object)?.values?.get("code")?.string)
        }
        return running.map { BackgroundScript(it.id, labels[it.id] ?: it.id, it.startedAt) }.sortedBy { it.startedAt }
    }

private fun firstLine(code: String?): String? = code?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
