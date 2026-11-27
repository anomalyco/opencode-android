package ai.opencode.server.live

import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.internal.obj
import ai.opencode.server.internal.req
import ai.opencode.server.internal.str
import ai.opencode.server.model.OpenCodeWidgetError
import ai.opencode.server.model.OpenCodeWidgetFailure
import ai.opencode.server.model.OpenCodeWidgetCall
import ai.opencode.server.model.OpenCodeWidgetInstance
import ai.opencode.server.model.OpenCodeWidgetMessageValue
import ai.opencode.server.model.OpenCodeWidgetRef
import ai.opencode.server.model.OpenCodeWidgetResponse
import ai.opencode.server.internal.WireJson
import ai.opencode.server.model.OpenCodeWidgetStatus
import ai.opencode.server.model.OpenCodeWidgetProgram
import ai.opencode.server.model.OpenCodeWidgetState
import ai.opencode.server.retry.RequestIdempotency
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A `Program` (`Summary` + `program`): its program text, mode and https hosts. */
internal fun decodeWidgetProgram(element: JsonElement?): OpenCodeWidgetProgram {
    val o = element as? JsonObject ?: throw OpenCodeClientError("The widget template has no program.")
    val program = (o["program"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw OpenCodeClientError("The widget template has no program.")
    val mode = (o["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val hosts = ((o["capabilities"] as? JsonObject)?.get("https") as? JsonObject)?.get("hosts") as? JsonArray
    val source = (o["source"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val offset = if (source != null && program.endsWith(source)) program.substring(0, program.length - source.length).count { it == '\n' } else 0
    val storage = (o["capabilities"] as? JsonObject)?.get("storage") is JsonObject
    return OpenCodeWidgetProgram(program, mode == "live", hosts?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }, storage, offset)
}

/** `POST /api/rpc/opencode.widget/<method>` in [directory]'s location: `{ input }` in, `output` back. */
internal suspend fun requestWidgetRPC(
    server: OpenCodeServer,
    method: String,
    directory: String?,
    input: JsonObject,
    idempotency: RequestIdempotency? = null,
): JsonElement {
    val body = buildJsonObject { put("input", input) }.toString().toByteArray()
    val data = perform(
        "/api/rpc/opencode.widget/$method",
        query = locationQuery(directory),
        method = "POST",
        body = body,
        idempotency = idempotency,
        server = server,
    )
    return decoded(data.data, "widget $method") { (it.obj()["output"] ?: JsonNull) }
}

/** A widget template version's program (the latest without a version) from the widget plugin. */
internal suspend fun requestWidgetProgramRPC(server: OpenCodeServer, directory: String?, ref: OpenCodeWidgetRef): OpenCodeWidgetProgram {
    val input = buildJsonObject {
        put("name", ref.name)
        if (ref.isPinned) put("version", ref.version)
    }
    return decodeWidgetProgram(requestWidgetRPC(server, "program", directory, input, RequestIdempotency.Idempotent))
}

/** The session's widget states, oldest first; a record this client can't read is skipped. */
internal suspend fun requestWidgetStates(server: OpenCodeServer, directory: String?, sessionID: String): List<OpenCodeWidgetState> {
    val output = requestWidgetRPC(server, "states", directory, buildJsonObject { put("sessionID", sessionID) }, RequestIdempotency.Idempotent)
    return (output as? JsonArray)?.mapNotNull(::decodeWidgetState) ?: emptyList()
}

/** Every widget of the server but superseded ones, last updated first; one this client can't read is skipped. */
internal suspend fun requestWidgetInstances(server: OpenCodeServer): List<OpenCodeWidgetInstance> {
    val output = requestWidgetRPC(server, "instances", null, buildJsonObject {}, RequestIdempotency.Idempotent)
    return (output as? JsonArray)?.mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        val state = decodeWidgetState(o["state"]) ?: return@mapNotNull null
        val session = o["session"] as? JsonObject
        val location = session?.get("location") as? JsonObject
        val directory = (location?.get("directory") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
        OpenCodeWidgetInstance(
            state = state,
            data = o["data"]?.toString(),
            sessionTitle = (session["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            directory = directory,
            updated = (o["updated"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: 0L,
            replaced = (o["replaced"] as? JsonArray)?.mapNotNull(::decodeWidgetCall) ?: emptyList(),
        )
    } ?: emptyList()
}

/** Answers an open widget once; `closed` means it was answered already. */
internal suspend fun requestRespondToWidgetRPC(
    server: OpenCodeServer,
    directory: String?,
    sessionID: String,
    messageID: String,
    callID: String,
    response: OpenCodeWidgetResponse,
): OpenCodeWidgetState? {
    val input = buildJsonObject {
        put("sessionID", sessionID)
        put("messageID", messageID)
        put("callID", callID)
        put("response", WireJson.parseToJsonElement(response.body))
    }
    return decodeWidgetState(requestWidgetRPC(server, "respond", directory, input, RequestIdempotency.Idempotent))
}

/** A message from an open widget; `closed` (not open) and `rate_limited` (over 10 a second) fail. */
internal suspend fun requestWidgetSendRPC(
    server: OpenCodeServer,
    directory: String?,
    sessionID: String,
    messageID: String,
    callID: String,
    message: OpenCodeWidgetMessageValue,
) {
    val input = buildJsonObject {
        put("sessionID", sessionID)
        put("messageID", messageID)
        put("callID", callID)
        put("value", WireJson.parseToJsonElement(message.json))
        put("wake", message.wake)
    }
    requestWidgetRPC(server, "send", directory, input)
}

/** Records a program error on the widget; with [ask] the user asks the agent to fix it. */
internal suspend fun requestWidgetReportRPC(
    server: OpenCodeServer,
    directory: String?,
    sessionID: String,
    messageID: String,
    callID: String,
    error: OpenCodeWidgetError,
    ask: Boolean = false,
) {
    val input = buildJsonObject {
        put("sessionID", sessionID)
        put("messageID", messageID)
        put("callID", callID)
        put("error", buildJsonObject {
            put("phase", error.phase)
            put("message", error.message.take(2_000))
            error.line?.let { put("line", it) }
            error.column?.let { put("column", it) }
        })
        put("client", "android")
        if (ask) put("ask", true)
    }
    requestWidgetRPC(server, "report", directory, input)
}

/** A plugin `State`, or null when it isn't one this client reads. */
internal fun decodeWidgetState(element: JsonElement?): OpenCodeWidgetState? {
    val o = element as? JsonObject ?: return null
    fun str(key: String, from: JsonObject = o) = (from[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val widgetTemplate = o["widgetTemplate"] as? JsonObject ?: return null
    val ref = OpenCodeWidgetRef(str("name", widgetTemplate) ?: return null, str("version", widgetTemplate) ?: return null)
    if (!ref.isValid || !ref.isPinned) return null
    val status = OpenCodeWidgetStatus.of(str("status")) ?: return null
    val hosts = ((o["capabilities"] as? JsonObject)?.get("https") as? JsonObject)?.get("hosts") as? JsonArray
    return OpenCodeWidgetState(
        sessionID = str("sessionID") ?: return null,
        messageID = str("messageID") ?: return null,
        callID = str("callID") ?: return null,
        widgetTemplate = ref,
        title = str("title"),
        status = status,
        response = if (status == OpenCodeWidgetStatus.Submitted) (o["response"] ?: JsonNull).toString() else null,
        wait = (o["wait"] as? JsonPrimitive)?.booleanOrNull ?: false,
        httpsHosts = hosts?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
        storage = (o["capabilities"] as? JsonObject)?.get("storage") is JsonObject,
        failure = decodeWidgetFailure(o["failure"] as? JsonObject),
        supersededBy = decodeWidgetCall(o["supersededBy"]),
    )
}

private fun decodeWidgetCall(element: JsonElement?): OpenCodeWidgetCall? {
    val o = element as? JsonObject ?: return null
    val messageID = (o["messageID"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val callID = (o["callID"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return OpenCodeWidgetCall(messageID, callID)
}

private fun decodeWidgetFailure(o: JsonObject?): OpenCodeWidgetFailure? {
    o ?: return null
    fun str(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun int(key: String) = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toInt()
    val error = OpenCodeWidgetError(str("phase") ?: return null, str("message") ?: return null, int("line"), int("column"))
    return OpenCodeWidgetFailure(error, str("client") ?: "", (o["sent"] as? JsonPrimitive)?.booleanOrNull ?: false)
}

/** A plugin RPC error's type: the widget isn't open any more. */
val OpenCodeClientError.isWidgetClosed: Boolean get() = rpcErrorType == "closed"

/** The plugin isn't loaded in the location yet (a fresh server, until something boots it): try again shortly. */
val OpenCodeClientError.isWidgetRPCUnavailable: Boolean get() = rpcErrorType == "rpc.unavailable"
