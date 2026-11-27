package ai.opencode.feature.transcript.widgets

import ai.opencode.server.model.OpenCodeWidgetMessageValue
import ai.opencode.server.model.OpenCodeWidgetResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** A rectangle in a node's logical pixels (widgets-fullscreen.md `Rect`). */
data class AgentWidgetRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val json: String
        get() = "{\"x\":${n(x)},\"y\":${n(y)},\"width\":${n(width)},\"height\":${n(height)}}"

    private fun n(value: Float) = AgentWidgetEvent.number(Math.round(value * 100.0) / 100.0)
}

/** `{ id, type, value? }`; [value] is JSON text, [extra] more top-level fields (`"name":json`). */
data class AgentWidgetEvent(val id: String, val type: Type, val value: String? = null, val extra: String? = null) {
    enum class Type(val wire: String) {
        Press("press"), Change("change"), Pointer("pointer"), Fullscreen("fullscreen"), Key("key"), Focus("focus"),
    }

    val json: String
        get() = buildString {
            append("{\"id\":").append(JsonPrimitive(id).toString()).append(",\"type\":\"").append(type.wire).append('"')
            if (value != null) append(",\"value\":").append(value)
            if (extra != null) append(',').append(extra)
            append('}')
        }

    /** A queued event [next] can stand in for: a newer value of the same control, or a newer move of the same press. */
    fun replaceable(next: AgentWidgetEvent): Boolean = id == next.id && type == next.type && when (type) {
        Type.Change -> true
        Type.Pointer -> value.isMove() && next.value.isMove()
        else -> false
    }

    private fun String?.isMove() = this?.startsWith("{\"kind\":\"move\"") == true

    companion object {
        fun press(id: String) = AgentWidgetEvent(id, Type.Press)
        /** A press on one of a node's items (a table's row): `{ id, type: "press", value: index }`. */
        fun press(id: String, index: Int) = AgentWidgetEvent(id, Type.Press, index.toString())
        /** Entering ([on]) or leaving full screen: `{ id: "", type: "fullscreen", value, close }`, [close] in root coordinates. */
        fun fullscreen(on: Boolean, close: AgentWidgetRect?) =
            AgentWidgetEvent("", Type.Fullscreen, on.toString(), "\"close\":" + (close?.json ?: "null"))
        /** Keyboard focus gained or lost: `{ id: "", type: "focus", value }`. */
        fun focus(on: Boolean) = AgentWidgetEvent("", Type.Focus, on.toString())
        /** A key while the widget has keyboard focus; [key] and [code] as in DOM `KeyboardEvent`. */
        fun key(kind: String, key: String, code: String, repeat: Boolean, shift: Boolean, alt: Boolean, ctrl: Boolean, meta: Boolean) =
            AgentWidgetEvent(
                "",
                Type.Key,
                "{\"kind\":\"$kind\",\"key\":${JsonPrimitive(key)},\"code\":${JsonPrimitive(code)},\"repeat\":$repeat," +
                    "\"shift\":$shift,\"alt\":$alt,\"ctrl\":$ctrl,\"meta\":$meta}",
            )
        fun change(id: String, value: Boolean) = AgentWidgetEvent(id, Type.Change, value.toString())
        fun change(id: String, value: Number) = AgentWidgetEvent(id, Type.Change, number(value.toDouble()))
        fun change(id: String, value: String) = AgentWidgetEvent(id, Type.Change, JsonPrimitive(value).toString())
        fun change(id: String, values: List<String>) =
            AgentWidgetEvent(id, Type.Change, values.joinToString(",", "[", "]") { JsonPrimitive(it).toString() })
        /** An absolute pointer value; [close] (in the node's coordinates, full screen only) goes with it when set. */
        fun pointer(id: String, kind: String, x: Float, y: Float, width: Float, height: Float, close: AgentWidgetRect? = null) = AgentWidgetEvent(
            id,
            Type.Pointer,
            "{\"kind\":\"$kind\",\"x\":${number(x.toDouble())},\"y\":${number(y.toDouble())}," +
                "\"width\":${number(width.toDouble())},\"height\":${number(height.toDouble())}" +
                (if (close != null) ",\"close\":${close.json}}" else "}"),
        )

        /** Whole numbers without a fraction, as JavaScript prints them. */
        fun number(value: Double): String =
            if (value.isFinite() && value == Math.rint(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString()
            else if (value.isFinite()) value.toString() else "0"
    }
}

/**
 * What a step returned: the next state (JSON text), the view, an answer when it ended the widget,
 * or a message for the agent that leaves it open.
 */
data class AgentWidgetOutput(
    val state: String,
    val view: AgentWidgetNode,
    val response: OpenCodeWidgetResponse?,
    val message: OpenCodeWidgetMessageValue? = null,
    /** The root node's `fullscreen`: true offers full screen, false never, null decides by content. */
    val fullscreen: Boolean? = null,
)

/** The step ABI's inputs, built as JSON text around the message's data and state (which stay text). */
object AgentWidgetStep {
    private val json = Json { ignoreUnknownKeys = true }

    /** The fields every step of a widget shares, parsed once by its run session. */
    fun shared(data: String): String = "{\"data\":$data}"

    // Steps run in a session holding the widget's `data` ([shared]); the input carries the rest.
    @Suppress("UNUSED_PARAMETER")
    fun init(data: String): String = "{\"phase\":\"init\"}"

    @Suppress("UNUSED_PARAMETER")
    fun event(data: String, state: String, event: AgentWidgetEvent): String =
        "{\"phase\":\"event\",\"state\":$state,\"event\":${event.json}}"

    @Suppress("UNUSED_PARAMETER")
    fun draw(data: String, state: String, frame: String): String =
        "{\"phase\":\"draw\",\"state\":$state,\"frame\":$frame}"

    /** A submitted value in one line: an object's fields as `key: value`, lists joined, at most 160 characters. */
    fun summary(responseJSON: String?): String {
        val value = responseJSON?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } ?: return ""
        fun one(v: JsonElement): String = when (v) {
            is JsonNull -> "—"
            is JsonPrimitive -> if (v.isString) v.content else v.doubleOrNull?.let { AgentWidgetEvent.number(it) } ?: v.content
            is kotlinx.serialization.json.JsonArray -> v.joinToString(", ", transform = ::one)
            is JsonObject -> v.toString()
        }
        val text = if (value is JsonObject) value.entries.joinToString(" · ") { (k, v) -> "$k: ${one(v)}" } else one(value)
        return if (text.length > 160) text.take(159) + "…" else text
    }
}
