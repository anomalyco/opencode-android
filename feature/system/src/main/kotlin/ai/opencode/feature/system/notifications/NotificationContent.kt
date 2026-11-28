package ai.opencode.feature.system.notifications

import ai.opencode.core.strings.localized
import ai.opencode.feature.system.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * What a push carries. FCM data values are strings, so the envelope arrives as a JSON object
 * string under `opencode` (`{"v":2,"ref":…,"box":…}`).
 */
object NotificationContent {
    const val ENVELOPE_KEY = "opencode"
    const val PLACEHOLDER_TITLE = "OpenCode"
    val PLACEHOLDER_BODY: String get() = localized(R.string.system_notification_placeholder_body)

    /** The v2 fields as they arrived, not yet authenticated. */
    data class Envelope(val ref: String, val box: String) {
        companion object {
            fun from(data: Map<String, String>): Envelope? {
                val envelope = data[ENVELOPE_KEY]?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
                val version = (envelope["v"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                if (version != 2) return null
                val ref = (envelope["ref"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
                val box = (envelope["box"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
                return Envelope(ref, box)
            }
        }
    }
}
