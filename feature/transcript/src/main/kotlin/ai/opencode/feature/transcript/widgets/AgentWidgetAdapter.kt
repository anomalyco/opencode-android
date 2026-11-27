package ai.opencode.feature.transcript.widgets

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.server.AgentWidgetClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.live.isWidgetClosed
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeWidgetError
import ai.opencode.server.model.OpenCodeWidgetFailure
import ai.opencode.server.model.OpenCodeWidgetMessageValue
import ai.opencode.server.model.OpenCodeWidgetProgram
import ai.opencode.server.model.OpenCodeWidgetRef
import ai.opencode.server.model.OpenCodeWidgetResponse
import ai.opencode.server.model.OpenCodeWidgetState
import ai.opencode.server.model.OpenCodeWidgetStatus
import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonPrimitive
import androidx.compose.runtime.compositionLocalOf

/**
 * One widget as the transcript shows it: a `widget` tool call ([messageID], [callID]) with what the
 * widget plugin says of it. [data] and [response] are JSON text.
 */
@Immutable
data class AgentWidgetState(
    val messageID: String,
    val callID: String,
    val title: String?,
    val widgetTemplate: OpenCodeWidgetRef,
    val data: String,
    val status: Status,
    val response: String? = null,
    /** Hosts a live widget template may fetch from; shown on the widget. */
    val httpsHosts: List<String>? = null,
    /** The widget template declares storage; shown on the widget. */
    val storage: Boolean = false,
    /** The program error the server recorded (from any client); shown while the widget is open. */
    val failure: OpenCodeWidgetFailure? = null,
) {
    /** [Superseded]: a newer widget replaced it; it shows as one quiet line. */
    enum class Status { Open, Submitted, Dismissed, Superseded }


    /** One widget in a session: a call ID is unique only within its message. */
    val key: String get() = OpenCodeWidgetState.key(messageID, callID)

    /** The title, else the widget template's name. */
    val label: String get() = title?.trim()?.ifEmpty { null } ?: widgetTemplate.name
}

/**
 * The seam between widgets and the server: where widget states come from and how a widget's
 * program, answer and messages travel. Everything else (runtime, machine, renderer) sees only
 * [AgentWidgetState] and [AgentWidgetTransport].
 */
object AgentWidgetAdapter {
    const val TOOL = "widget"

    fun isWidget(call: OpenCodeToolCall): Boolean = call.name == TOOL

    /** The title the tool call asked for, shown while its widget loads. */
    fun title(call: OpenCodeToolCall): String? =
        call.input.obj?.get("title")?.string?.trim()?.ifEmpty { null } ?: call.input.obj?.get("widgetTemplate")?.string

    /** [value] as JSON text, whole numbers without a fraction (as the server wrote them). */
    private fun json(value: OpenCodeJSONValue): String = buildString { write(value) }

    private fun StringBuilder.write(value: OpenCodeJSONValue) {
        when (value) {
            is OpenCodeJSONValue.Number -> append(AgentWidgetEvent.number(value.value))
            is OpenCodeJSONValue.Array -> {
                append('[')
                value.values.forEachIndexed { index, item -> if (index > 0) append(','); write(item) }
                append(']')
            }
            is OpenCodeJSONValue.Object -> {
                append('{')
                var first = true
                for ((key, item) in value.values) {
                    if (!first) append(',')
                    first = false
                    append(JsonPrimitive(key).toString()).append(':')
                    write(item)
                }
                append('}')
            }
            else -> append(OpenCodeJSONValue.toElement(value).toString())
        }
    }

    /** The key a tool call's widget is found by in [states]. */
    fun key(item: OpenCodeTranscriptItem.Tool): String = OpenCodeWidgetState.key(messageID(item), item.call.callID)

    private fun messageID(item: OpenCodeTranscriptItem.Tool): String =
        item.call.messageID ?: item.id.indexOf("-tool-").takeIf { it >= 0 }?.let { item.id.substring(0, it) } ?: ""

    /**
     * The session's widgets by `messageID/callID`: every `widget` tool call in [items], with its
     * state from the widget plugin ([records]), else from the call alone (its `metadata.widgetTemplate`,
     * or the widget template's latest version, open).
     */
    fun states(items: List<OpenCodeTranscriptItem>, records: Map<String, OpenCodeWidgetState> = emptyMap()): Map<String, AgentWidgetState> {
        var states: HashMap<String, AgentWidgetState>? = null
        for (item in items) {
            if (item !is OpenCodeTranscriptItem.Tool) continue
            val base = widget(item.call, messageID(item)) ?: continue
            val record = records[base.key]
            val state = if (record == null) base else base.copy(
                title = base.title ?: record.title,
                widgetTemplate = record.widgetTemplate,
                status = status(record.status),
                response = record.response,
                httpsHosts = record.httpsHosts,
                storage = record.storage,
                failure = record.failure,
            )
            (states ?: HashMap<String, AgentWidgetState>().also { states = it })[state.key] = state
        }
        return states ?: emptyMap()
    }

    /** A widget outside its transcript, from its plugin state and its tool call's `data` (JSON text). */
    fun state(record: OpenCodeWidgetState, data: String?): AgentWidgetState = AgentWidgetState(
        messageID = record.messageID,
        callID = record.callID,
        title = record.title,
        widgetTemplate = record.widgetTemplate,
        data = data ?: "null",
        status = status(record.status),
        response = record.response,
        httpsHosts = record.httpsHosts,
        storage = record.storage,
        failure = record.failure,
    )

    private fun status(status: OpenCodeWidgetStatus) = when (status) {
        OpenCodeWidgetStatus.Open -> AgentWidgetState.Status.Open
        OpenCodeWidgetStatus.Submitted -> AgentWidgetState.Status.Submitted
        OpenCodeWidgetStatus.Dismissed -> AgentWidgetState.Status.Dismissed
        OpenCodeWidgetStatus.Superseded -> AgentWidgetState.Status.Superseded
    }

    /**
     * The widget a tool call shows, from the call alone, or null when it isn't one (another tool,
     * input still streaming, a failed call, a bad name).
     */
    internal fun widget(call: OpenCodeToolCall, messageID: String = call.messageID ?: ""): AgentWidgetState? {
        if (!isWidget(call) || call.status == OpenCodeToolCall.Status.Streaming || call.status == OpenCodeToolCall.Status.Error) return null
        val input = call.input.obj ?: return null
        // The plugin's `metadata.widgetTemplate`; session-lab's `metadata.widget` may carry status and response.
        val legacy = call.metadata["widget"]?.obj
        val widgetTemplate = call.metadata["widgetTemplate"]?.obj
        val name = input["widgetTemplate"]?.string ?: widgetTemplate?.get("name")?.string ?: return null
        val ref = OpenCodeWidgetRef(name, widgetTemplate?.get("version")?.string ?: "")
        if (!ref.isValid) return null
        val status = when (legacy?.get("status")?.string) {
            "submitted" -> AgentWidgetState.Status.Submitted
            "dismissed" -> AgentWidgetState.Status.Dismissed
            "superseded" -> AgentWidgetState.Status.Superseded
            else -> AgentWidgetState.Status.Open
        }
        return AgentWidgetState(
            messageID = messageID,
            callID = call.callID,
            title = input["title"]?.string,
            widgetTemplate = ref,
            data = input["data"]?.let(::json) ?: "null",
            status = status,
            response = if (status == AgentWidgetState.Status.Submitted) legacy?.get("response")?.let(::json) ?: "null" else null,
        )
    }
}

/** Fetches widget template programs and answers widgets; throws [AgentWidgetTransportException]. */
class AgentWidgetTransport(
    val program: suspend (server: OpenCodeServer, directory: String?, ref: OpenCodeWidgetRef) -> OpenCodeWidgetProgram,
    val respond: suspend (server: OpenCodeServer, directory: String?, sessionID: String, widget: AgentWidgetState, response: OpenCodeWidgetResponse) -> Unit,
    /** A message from a widget that stays open (`send`). */
    val send: suspend (server: OpenCodeServer, directory: String?, sessionID: String, widget: AgentWidgetState, message: OpenCodeWidgetMessageValue) -> Unit,
    /** A program error on this device (`report`), recorded on the widget; [ask]: the user asks the agent to fix it. */
    val report: suspend (server: OpenCodeServer, directory: String?, sessionID: String, widget: AgentWidgetState, error: OpenCodeWidgetError, ask: Boolean) -> Unit,
) {
    companion object Key : DependencyKey<AgentWidgetTransport> {
        override val liveValue: AgentWidgetTransport by lazy {
            val client = dependency(AgentWidgetClient)
            AgentWidgetTransport(
                program = { server, directory, ref -> mapped { client.program(server, directory, ref) } },
                respond = { server, directory, sessionID, widget, response ->
                    mapped { client.respond(server, directory, sessionID, widget.messageID, widget.callID, response) }
                },
                send = { server, directory, sessionID, widget, message ->
                    mapped { client.send(server, directory, sessionID, widget.messageID, widget.callID, message) }
                },
                report = { server, directory, sessionID, widget, error, ask ->
                    mapped { client.report(server, directory, sessionID, widget.messageID, widget.callID, error, ask) }
                },
            )
        }

        override val testValue: AgentWidgetTransport
            get() = AgentWidgetTransport(
                program = { _, _, ref -> throw AgentWidgetTransportException("No widget template ${ref.key} under tests") },
                respond = { _, _, _, _, _ -> throw AssertionError("Unimplemented: AgentWidgetTransport.respond") },
                send = { _, _, _, _, _ -> throw AssertionError("Unimplemented: AgentWidgetTransport.send") },
                report = { _, _, _, _, _, _ -> },
            )

        private inline fun <T> mapped(block: () -> T): T = try {
            block()
        } catch (error: OpenCodeClientError) {
            throw AgentWidgetTransportException(error.message, isClosed = error.isWidgetClosed, isRateLimited = error.rpcErrorType == "rate_limited")
        }
    }
}

/** [isClosed]: the widget was already answered (here or on another client); [isRateLimited]: over 10 sends a second. */
class AgentWidgetTransportException(message: String, val isClosed: Boolean = false, val isRateLimited: Boolean = false) : Exception(message)

/** The list's widget states by `messageID/callID`. */
val LocalAgentWidgetStates = compositionLocalOf<Map<String, AgentWidgetState>> { emptyMap() }
