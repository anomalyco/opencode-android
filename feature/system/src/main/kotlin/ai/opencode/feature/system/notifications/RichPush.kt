package ai.opencode.feature.system.notifications

import ai.opencode.core.strings.localized
import ai.opencode.core.strings.localizedPlural
import ai.opencode.feature.system.R

/** What a verified session push shows and which buttons it offers. */
internal data class RichPush(
    val title: String,
    /** The collapsed line. */
    val text: String?,
    /** The expanded text. */
    val bigText: String?,
    val subText: String? = null,
    val isFailure: Boolean = false,
    val actions: List<PushAction> = emptyList(),
    /** A permission's resources as code, in the expanded notification's own layout. */
    val detail: PushDetail? = null,
) {
    companion object {
        /** Code rows the expanded notification shows, one under the 11 that fit. */
        const val DETAIL_ROWS = 10

        /** Code characters per row at font scale 1, under the ~37 a 412 dp phone fits. */
        const val DETAIL_ROW_CHARACTERS = 34

        fun from(payload: NotificationCrypto.Payload, fontScale: Float = 1f): RichPush = when (payload.type) {
            "permission" -> permission(payload, fontScale)
            "session.idle" -> finished(payload)
            else -> RichPush(payload.title, payload.body, payload.body)
        }

        private fun permission(payload: NotificationCrypto.Payload, fontScale: Float): RichPush {
            val action = payload.data["action"]
            if (action == null || payload.data["requestID"] == null) return RichPush(payload.title, payload.body, payload.body)
            val resources = payload.resources.filter { it.isNotBlank() }
            // "*" names no particular resource, so there is nothing to show.
            if (resources.isEmpty() || resources == listOf("*")) {
                return RichPush(heading(action, 1), null, null, subText = payload.title, actions = listOf(PushAction.Review, PushAction.Deny))
            }
            val cut = cutDetail(resources, fontScale)
            val partial = cut != null || payload.data["truncated"] == "true"
            val code = cut ?: resources.joinToString("\n")
            // Allow only approves what the person could read.
            val allow = if (partial) PushAction.Review else PushAction.Allow
            return RichPush(
                title = heading(action, resources.size),
                text = resources.joinToString(if (action == "shell") "; " else ", "),
                bigText = code,
                subText = payload.title,
                actions = listOf(allow, PushAction.Deny),
                detail = PushDetail(code, partial),
            )
        }

        /**
         * The resources cut to what the expanded notification shows, ending in "…", or null when all of them fit.
         * Android clips the rest without a mark.
         */
        fun cutDetail(lines: List<String>, fontScale: Float): String? {
            val perRow = (DETAIL_ROW_CHARACTERS / fontScale.coerceAtLeast(1f)).toInt()
            var rows = 0
            for (line in lines) {
                rows += rowsFor(line, perRow)
            }
            if (rows <= DETAIL_ROWS) return null
            val kept = mutableListOf<String>()
            var left = DETAIL_ROWS
            for (line in lines) {
                val needed = rowsFor(line, perRow)
                if (needed < left) {
                    kept.add(line)
                    left -= needed
                    continue
                }
                kept.add(line.take(left * perRow - 1).trimEnd() + "…")
                break
            }
            return kept.joinToString("\n")
        }

        private fun rowsFor(line: String, perRow: Int): Int = maxOf(1, (line.length + perRow - 1) / perRow)

        /** What the permission asks, as a question. */
        fun heading(action: String, count: Int): String = when (action) {
            "shell" -> localizedPlural(R.plurals.system_push_permission_shell, count, count)
            "edit" -> localizedPlural(R.plurals.system_push_permission_edit, count, count)
            "read" -> localizedPlural(R.plurals.system_push_permission_read, count, count)
            "webfetch" -> localized(R.string.system_push_permission_webfetch)
            "websearch" -> localized(R.string.system_push_permission_websearch)
            "external_directory" -> localized(R.string.system_push_permission_external_directory)
            else -> localized(R.string.system_push_permission_other, action)
        }

        private fun finished(payload: NotificationCrypto.Payload): RichPush {
            val failed = payload.outcome == "failed"
            return RichPush(
                title = payload.title,
                text = payload.body,
                bigText = payload.body,
                subText = if (failed) localized(R.string.system_session_failed) else null,
                isFailure = failed,
                actions = listOf(PushAction.FollowUp),
            )
        }
    }
}

/** A permission's resources as code; [cut] when not all of them are there. */
internal data class PushDetail(val text: String, val cut: Boolean)

/** A button on a session notification. */
internal sealed interface PushAction {
    /** Allow once, from the notification. */
    data object Allow : PushAction

    /** Allow by opening the request in the app, when the notification can't show all of it. */
    data object Review : PushAction

    data object Deny : PushAction

    /** Send the session a message. */
    data object FollowUp : PushAction
}
