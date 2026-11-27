package ai.opencode.feature.system.notifications

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * One message of the server's push contract (`specs/v2/push.md` in the opencode repo). Every key
 * but `type` is optional and unknown keys are ignored, as the contract asks.
 */
data class PushMessage(
    val version: Int,
    val type: String,
    val id: String? = null,
    val time: Long = 0,
    val sessionID: String? = null,
    val directory: String? = null,
    val title: String? = null,
    val body: String? = null,
    /** A `clear`'s notifications to remove. */
    val ids: List<String> = emptyList(),
    val permission: Permission? = null,
    val form: Form? = null,
    val turn: Turn? = null,
) {
    data class Permission(
        val requestID: String,
        val replySessionID: String,
        val action: String,
        val resources: List<String> = emptyList(),
        /** How many resources the request has, when [resources] were left out. */
        val resourceCount: Int? = null,
        val save: List<String> = emptyList(),
        val decisions: List<String> = emptyList(),
        val truncated: Boolean = false,
    )

    data class Option(val label: String, val value: String? = null, val description: String? = null)

    data class Field(
        val key: String,
        val type: String,
        val title: String? = null,
        val description: String? = null,
        val custom: Boolean = false,
        val options: List<Option> = emptyList(),
    )

    data class Form(
        val formID: String,
        val replySessionID: String,
        val fieldCount: Int = 0,
        val inlineReply: Boolean = false,
        val fields: List<Field> = emptyList(),
    )

    data class Turn(
        val outcome: String,
        val text: String? = null,
        val errorType: String? = null,
        val errorMessage: String? = null,
        val retryAfterMs: Long? = null,
        /** How many background jobs of each kind are still running. */
        val background: Map<String, Int> = emptyMap(),
    )

    companion object {
        /** Null when [text] isn't a push message at all. */
        fun parse(text: String): PushMessage? {
            val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
            val type = root.string("type") ?: return null
            val version = root.int("v") ?: return null
            val data = root["data"] as? JsonObject
            return PushMessage(
                version = version,
                type = type,
                id = root.string("id"),
                time = root.long("time") ?: 0,
                sessionID = root.string("sessionID")?.takeIf { NotificationCrypto.isValid(sessionID = it) },
                directory = root.string("directory"),
                title = root.string("title"),
                body = root.string("body"),
                ids = root.strings("ids"),
                permission = if (type == "permission") data?.let(::permission) else null,
                form = if (type == "form") data?.let(::form) else null,
                turn = if (type == "turn") data?.let(::turn) else null,
            )
        }

        private fun permission(data: JsonObject): Permission? {
            val replySessionID = data.string("replySessionID")?.takeIf { NotificationCrypto.isValid(sessionID = it) }
            return Permission(
                requestID = data.string("requestID") ?: return null,
                replySessionID = replySessionID ?: return null,
                action = data.string("action") ?: return null,
                resources = data.strings("resources"),
                resourceCount = data.int("resourceCount"),
                save = data.strings("save"),
                decisions = data.strings("decisions"),
                truncated = data.boolean("truncated"),
            )
        }

        private fun form(data: JsonObject): Form? {
            val replySessionID = data.string("replySessionID")?.takeIf { NotificationCrypto.isValid(sessionID = it) }
            val fields = mutableListOf<Field>()
            for (element in data["fields"] as? JsonArray ?: JsonArray(emptyList())) {
                val field = element as? JsonObject ?: continue
                val options = mutableListOf<Option>()
                for (item in field["options"] as? JsonArray ?: JsonArray(emptyList())) {
                    val option = item as? JsonObject ?: continue
                    val label = option.string("label") ?: continue
                    options += Option(label, option.string("value"), option.string("description"))
                }
                fields += Field(
                    key = field.string("key") ?: continue,
                    type = field.string("type") ?: continue,
                    title = field.string("title"),
                    description = field.string("description"),
                    custom = field.boolean("custom"),
                    options = options,
                )
            }
            return Form(
                formID = data.string("formID") ?: return null,
                replySessionID = replySessionID ?: return null,
                fieldCount = data.int("fieldCount") ?: fields.size,
                inlineReply = data.boolean("inlineReply"),
                fields = fields,
            )
        }

        private fun turn(data: JsonObject): Turn? {
            val error = data["error"] as? JsonObject
            val background = mutableMapOf<String, Int>()
            for ((kind, count) in data["background"] as? JsonObject ?: JsonObject(emptyMap())) {
                val number = (count as? JsonPrimitive)?.intOrNull ?: continue
                if (number > 0) background[kind] = number
            }
            return Turn(
                outcome = data.string("outcome") ?: return null,
                text = data.string("text"),
                errorType = error?.string("type"),
                errorMessage = error?.string("message"),
                retryAfterMs = error?.long("retryAfterMs"),
                background = background,
            )
        }

        private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
        private fun JsonObject.string(key: String): String? = primitive(key)?.takeIf { it.isString }?.content
        private fun JsonObject.int(key: String): Int? = primitive(key)?.takeUnless { it.isString }?.intOrNull
        private fun JsonObject.long(key: String): Long? = primitive(key)?.takeUnless { it.isString }?.longOrNull
        private fun JsonObject.boolean(key: String): Boolean = primitive(key)?.takeUnless { it.isString }?.booleanOrNull == true

        private fun JsonObject.strings(key: String): List<String> {
            val result = mutableListOf<String>()
            for (element: JsonElement in this[key] as? JsonArray ?: return result) {
                val primitive = element as? JsonPrimitive ?: continue
                if (primitive.isString) result += primitive.content
            }
            return result
        }
    }
}
