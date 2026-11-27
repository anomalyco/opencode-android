package ai.opencode.server

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.server.internal.WireJson
import ai.opencode.server.live.decodeWidgetProgram
import ai.opencode.server.live.isWidgetRPCUnavailable
import ai.opencode.server.live.requestRespondToWidgetRPC
import ai.opencode.server.live.requestWidgetProgramRPC
import ai.opencode.server.live.requestWidgetReportRPC
import ai.opencode.server.live.requestWidgetSendRPC
import ai.opencode.server.live.requestWidgetStates
import ai.opencode.server.model.OpenCodeWidgetError
import ai.opencode.server.model.OpenCodeWidgetMessageValue
import ai.opencode.server.model.OpenCodeWidgetProgram
import ai.opencode.server.model.OpenCodeWidgetRef
import ai.opencode.server.model.OpenCodeWidgetState
import ai.opencode.server.model.OpenCodeWidgetResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Agent widgets' server half, the `opencode.widget` plugin RPC: widget template programs by
 * `name@version` (immutable, so kept on disk forever), the session's widget states, and the one
 * answer to and messages from an open widget. Throws [OpenCodeClientError]; `isWidgetClosed` means
 * it isn't open any more, `isWidgetRPCUnavailable` that the plugin isn't loaded in the location yet.
 */
class AgentWidgetClient(
    val program: suspend (server: OpenCodeServer, directory: String?, ref: OpenCodeWidgetRef) -> OpenCodeWidgetProgram,
    val states: suspend (server: OpenCodeServer, directory: String?, sessionID: String) -> List<OpenCodeWidgetState>,
    val respond: suspend (server: OpenCodeServer, directory: String?, sessionID: String, messageID: String, callID: String, response: OpenCodeWidgetResponse) -> Unit,
    val send: suspend (server: OpenCodeServer, directory: String?, sessionID: String, messageID: String, callID: String, message: OpenCodeWidgetMessageValue) -> Unit,
    /** A program error on this device (`report`), recorded on the widget; [ask]: the user asks the agent to fix it. */
    val report: suspend (server: OpenCodeServer, directory: String?, sessionID: String, messageID: String, callID: String, error: OpenCodeWidgetError, ask: Boolean) -> Unit,
) {
    companion object Key : DependencyKey<AgentWidgetClient> {
        override val liveValue: AgentWidgetClient by lazy {
            val cache = AgentWidgetProgramCache(File(File(dependency(AppContext).filesDir, "support"), "widget-templates"))
            AgentWidgetClient(
                program = { server, directory, ref ->
                    cache.read(ref) ?: whileUnavailable { requestWidgetProgramRPC(server, directory, ref) }.also { cache.write(ref, it) }
                },
                states = ::requestWidgetStates,
                respond = { server, directory, sessionID, messageID, callID, response ->
                    whileUnavailable { requestRespondToWidgetRPC(server, directory, sessionID, messageID, callID, response) }
                },
                send = { server, directory, sessionID, messageID, callID, message ->
                    whileUnavailable { requestWidgetSendRPC(server, directory, sessionID, messageID, callID, message) }
                },
                report = { server, directory, sessionID, messageID, callID, error, ask ->
                    whileUnavailable { requestWidgetReportRPC(server, directory, sessionID, messageID, callID, error, ask) }
                },
            )
        }

        override val testValue: AgentWidgetClient
            get() = AgentWidgetClient(
                program = { _, _, ref -> throw OpenCodeClientError("No widget template ${ref.key} under tests") },
                states = { _, _, _ -> emptyList() },
                respond = { _, _, _, _, _, _ -> throw AssertionError("Unimplemented: AgentWidgetClient.respond") },
                send = { _, _, _, _, _, _ -> throw AssertionError("Unimplemented: AgentWidgetClient.send") },
                report = { _, _, _, _, _, _, _ -> },
            )

        /**
         * A fresh server answers `rpc.unavailable` until the session's location has booted (a
         * transcript read boots it), so the call goes again a few times, a little later each time.
         */
        private suspend fun <T> whileUnavailable(block: suspend () -> T): T {
            var attempt = 0
            while (true) {
                try {
                    return block()
                } catch (error: OpenCodeClientError) {
                    if (!error.isWidgetRPCUnavailable || attempt >= UNAVAILABLE_RETRIES) throw error
                    attempt++
                    delay(attempt * 750L)
                }
            }
        }

        private const val UNAVAILABLE_RETRIES = 4
    }
}

/**
 * One file per widget template version (`<name>@<version>.json`: program, mode, hosts), written once through
 * a temporary file so a torn write never reads back. Older `.js` files hold a step program alone.
 */
class AgentWidgetProgramCache(private val directory: File) {
    suspend fun read(ref: OpenCodeWidgetRef): OpenCodeWidgetProgram? {
        if (!ref.isValid || !ref.isPinned) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val json = File(directory, "${ref.key}.json")
                val legacy = File(directory, "${ref.key}.js")
                when {
                    json.isFile -> WireJson.parseToJsonElement(json.readText()).let { element ->
                        val offset = (element as? kotlinx.serialization.json.JsonObject)?.get("sourceLineOffset")?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() } ?: 0
                        decodeWidgetProgram(element).copy(sourceLineOffset = offset)
                    }
                    legacy.isFile -> OpenCodeWidgetProgram(legacy.readText())
                    else -> null
                }
            }.getOrNull()
        }
    }

    suspend fun write(ref: OpenCodeWidgetRef, program: OpenCodeWidgetProgram) {
        if (!ref.isValid || !ref.isPinned) return
        withContext(Dispatchers.IO) {
            runCatching {
                directory.mkdirs()
                val text = buildJsonObject {
                    put("program", program.program)
                    put("mode", if (program.isLive) "live" else "step")
                    put("sourceLineOffset", program.sourceLineOffset)
                    if (program.httpsHosts != null || program.storage) {
                        put("capabilities", buildJsonObject {
                            program.httpsHosts?.let { hosts -> put("https", buildJsonObject { put("hosts", JsonArray(hosts.map(::JsonPrimitive))) }) }
                            if (program.storage) put("storage", buildJsonObject {})
                        })
                    }
                }.toString()
                val temporary = File(directory, "${ref.key}.tmp")
                temporary.writeText(text)
                if (!temporary.renameTo(File(directory, "${ref.key}.json"))) temporary.delete()
            }.onFailure { AppLog.app.notice { "Widget template cache write failed: ${it.javaClass.simpleName}" } }
        }
    }
}
