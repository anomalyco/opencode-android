package ai.opencode.feature.transcript.widgets

import ai.opencode.core.strings.localized
import ai.opencode.feature.transcript.R
import ai.opencode.server.model.OpenCodeWidgetMessageValue
import ai.opencode.server.model.OpenCodeWidgetResponse
import androidx.compose.runtime.Immutable

/**
 * One widget message's lifecycle on this client, as an explicit machine:
 *
 * ```text
 * Loading ──Loaded──▶ Starting ──Ran(ok)──▶ Ready ◀──Ran(ok, no answer)── Stepping
 *    │                   │                   │  └────────Input────────────▶ │
 * LoadFailed          Ran(err)               │                               │ Ran(ok, submit/dismiss)
 *    ▼                   ▼                   │                               ▼
 * Failed ◀───────────────┘                   └──Status(closed)──▶ Closed ◀─ Responding
 * ```
 *
 * A failed step keeps the previous state and view and shows its error inline; `init` failing
 * shows the error card. A widget the server reports answered runs `init` only to draw its final
 * view read-only. Effects are returned for [AgentWidgetController] to run; their results come
 * back as events.
 */
object AgentWidgetMachine {
    @Immutable
    sealed interface Closed {
        /** [json]: the submitted value. */
        data class Submitted(val json: String) : Closed
        data object Dismissed : Closed

        companion object {
            fun of(response: OpenCodeWidgetResponse): Closed = when (response) {
                is OpenCodeWidgetResponse.Submit -> Submitted(response.json)
                OpenCodeWidgetResponse.Dismiss -> Dismissed
            }
        }
    }

    /** The message's `status` on the server. */
    @Immutable
    sealed interface Status {
        data object Open : Status
        data class Answered(val how: Closed) : Status
    }

    @Immutable
    sealed interface Phase {
        data class Loading(val status: Status) : Phase
        data class Starting(val status: Status) : Phase
        data class Ready(override val state: String, override val view: AgentWidgetNode, val error: String? = null) : Phase
        data class Stepping(override val state: String, override val view: AgentWidgetNode) : Phase
        data class Responding(override val state: String, override val view: AgentWidgetNode, val response: OpenCodeWidgetResponse, val attempt: Int) : Phase
        data class Closed(override val view: AgentWidgetNode?, val how: AgentWidgetMachine.Closed, override val state: String? = null) : Phase
        /** [retryable]: Try again (or Restart) can help; not for a program of another widget protocol. */
        data class Failed(val message: String, val status: Status, val retryable: Boolean = true) : Phase

        /** The view drawn now, live or final. */
        val view: AgentWidgetNode? get() = null

        /** The state the view was drawn from, when this client ran it. */
        val state: String? get() = null
    }

    sealed interface RespondFailure {
        /** 409: answered already, here or on another client; its update is on the way. */
        data object Conflict : RespondFailure
        data class Failed(val message: String) : RespondFailure
    }

    sealed interface Event {
        data object Loaded : Event
        data class LoadFailed(val message: String) : Event
        data class Ran(val result: Result<AgentWidgetOutput>) : Event
        data class Input(val event: AgentWidgetEvent) : Event
        data class Responded(val attempt: Int, val failure: RespondFailure?) : Event
        data class StatusChanged(val status: Status) : Event
        /** A `send` message didn't go: [message] shows inline. */
        data class SendFailed(val message: String) : Event
        data object Retry : Event
    }

    sealed interface Effect {
        data object Fetch : Effect
        /** Run this step input (JSON). */
        data class Run(val input: String) : Effect
        data class Respond(val response: OpenCodeWidgetResponse, val attempt: Int) : Effect
        /** Post a message to the agent; the widget stays open. */
        data class Send(val message: OpenCodeWidgetMessageValue) : Effect
    }

    data class Machine(val data: String, val phase: Phase, val attempts: Int = 0)

    fun open(data: String, status: Status): Pair<Machine, List<Effect>> = Machine(data, Phase.Loading(status)) to listOf(Effect.Fetch)

    fun reduce(machine: Machine, event: Event): Pair<Machine, List<Effect>> {
        val data = machine.data
        val attempt = machine.attempts + 1
        val (next, effects) = transition(machine.phase, event, data, attempt)
        val attempts = if (effects.any { it is Effect.Respond }) attempt else machine.attempts
        return machine.copy(phase = next, attempts = attempts) to effects
    }

    private fun transition(phase: Phase, event: Event, data: String, attempt: Int): Pair<Phase, List<Effect>> {
        val none = emptyList<Effect>()
        return when {
            phase is Phase.Loading && event is Event.Loaded -> Phase.Starting(phase.status) to listOf(Effect.Run(AgentWidgetStep.init(data)))
            phase is Phase.Loading && event is Event.LoadFailed -> when (val status = phase.status) {
                // A closed widget still says how it ended without its view.
                is Status.Answered -> Phase.Closed(null, status.how) to none
                Status.Open -> Phase.Failed(event.message, status) to none
            }
            phase is Phase.Loading && event is Event.StatusChanged -> Phase.Loading(event.status) to none
            phase is Phase.Starting && event is Event.StatusChanged -> Phase.Starting(event.status) to none
            phase is Phase.Failed && event is Event.StatusChanged -> when (val status = event.status) {
                is Status.Answered -> Phase.Closed(null, status.how) to none
                Status.Open -> phase to none
            }
            phase is Phase.Starting && event is Event.Ran -> {
                val out = event.result.getOrNull()
                when (val status = phase.status) {
                    Status.Open ->
                        if (out != null) Phase.Ready(out.state, out.view) to none
                        else event.result.exceptionOrNull().let { Phase.Failed(message(it), Status.Open, retryable(it)) } to none
                    is Status.Answered -> Phase.Closed(out?.view, status.how, out?.state) to none
                }
            }
            phase is Phase.Ready && event is Event.Input ->
                Phase.Stepping(phase.state, phase.view) to listOf(Effect.Run(AgentWidgetStep.event(data, phase.state, event.event)))
            phase is Phase.Stepping && event is Event.Ran -> {
                val out = event.result.getOrNull()
                val response = out?.response
                when {
                    out == null -> Phase.Ready(phase.state, phase.view, message(event.result.exceptionOrNull())) to none
                    response != null -> Phase.Responding(out.state, out.view, response, attempt) to listOf(Effect.Respond(response, attempt))
                    out.message != null -> Phase.Ready(out.state, out.view) to listOf(Effect.Send(out.message))
                    else -> Phase.Ready(out.state, out.view) to none
                }
            }
            phase is Phase.Responding && event is Event.Responded && event.attempt == phase.attempt -> when (val failure = event.failure) {
                null -> Phase.Closed(phase.view, Closed.of(phase.response), phase.state) to none
                RespondFailure.Conflict -> Phase.Ready(phase.state, phase.view, localized(R.string.transcript_widget_already_answered)) to none
                is RespondFailure.Failed -> Phase.Ready(phase.state, phase.view, failure.message) to none
            }
            event is Event.StatusChanged && event.status is Status.Answered &&
                (phase is Phase.Ready || phase is Phase.Stepping || phase is Phase.Responding) ->
                Phase.Closed(phase.view, (event.status as Status.Answered).how, phase.state) to none
            phase is Phase.Ready && event is Event.SendFailed -> phase.copy(error = event.message) to none
            phase is Phase.Failed && event is Event.Retry -> Phase.Loading(phase.status) to listOf(Effect.Fetch)
            else -> phase to none
        }
    }

    fun message(error: Throwable?): String = when ((error as? AgentWidgetEngineException)?.protocol) {
        AgentWidgetEngineException.Protocol.Older -> localized(R.string.transcript_widget_older_runtime)
        AgentWidgetEngineException.Protocol.Newer -> localized(R.string.transcript_widget_newer_app)
        null -> error?.message?.takeIf { it.isNotBlank() } ?: localized(R.string.transcript_widget_step_failed)
    }

    fun retryable(error: Throwable?): Boolean = (error as? AgentWidgetEngineException)?.protocol == null
}
