package ai.opencode.feature.system.notifications

import ai.opencode.core.strings.localized
import ai.opencode.core.strings.localizedPlural
import ai.opencode.feature.system.R

/** What one push notification shows and offers, before any Android type is involved. */
data class PushShown(
    val kind: Kind,
    val title: String,
    /** The one line of a collapsed notification. */
    val text: String,
    /** The expanded notification's text, when it differs from [text]. */
    val expandedText: String = text,
    /** Commands, paths or URLs, each on its own monospaced line. */
    val code: List<String> = emptyList(),
    val note: String? = null,
    /** A question's options that answer it when tapped. */
    val rows: List<Row> = emptyList(),
    /** How many options didn't fit in [rows]. */
    val more: Int = 0,
    val actions: List<Action> = emptyList(),
) {
    enum class Kind { Permission, Question, Finished, Failed, Other }

    enum class Action { Deny, AlwaysAllow, Allow, AnswerTyped, ReplyToSession, Open }

    data class Row(val label: String, val description: String?, val value: String)

    companion object {
        private const val MAXIMUM_ROWS = 3
        /** A longer question, or one with line breaks, leaves no room for option rows. */
        private const val MAXIMUM_QUESTION_WITH_ROWS = 120
        private const val MAXIMUM_CODE_LINES = 4
        /** What an expanded notification shows of an answer at a large font size. */
        private const val MAXIMUM_ANSWER_LINES = 7
        private const val CHARACTERS_PER_LINE = 38
        private val heading = Regex("^#{1,6}\\s+")
        private val bullet = Regex("^[-*+]\\s+")
        private val link = Regex("\\[([^\\]]+)]\\([^)]*\\)")

        /** Null for a message that shows nothing: a clear, or one without text. */
        fun from(message: PushMessage): PushShown? {
            if (message.type == "clear") return null
            val title = message.title?.takeIf { it.isNotBlank() } ?: NotificationContent.PLACEHOLDER_TITLE
            if (message.version == 1) {
                message.permission?.let { return permission(title, it) }
                message.form?.let { return question(title, it, message.body) }
                message.turn?.let { turn -> turn(title, turn)?.let { return it } }
            }
            val body = message.body?.takeIf { it.isNotBlank() } ?: return null
            return PushShown(Kind.Other, title, body)
        }

        private fun permission(title: String, permission: PushMessage.Permission): PushShown {
            val anything = permission.resources == listOf("*")
            val count = if (anything) 1 else permission.resourceCount ?: permission.resources.size.coerceAtLeast(1)
            val summary = when (val action = permission.action) {
                null -> localized(R.string.system_push_public_permission)
                "shell" -> localizedPlural(R.plurals.system_push_permission_shell, count, count)
                "edit" -> localizedPlural(R.plurals.system_push_permission_edit, count, count)
                "read" -> localizedPlural(R.plurals.system_push_permission_read, count, count)
                "webfetch" -> localized(R.string.system_push_permission_webfetch)
                "websearch" -> localized(R.string.system_push_permission_websearch)
                "external_directory" -> localized(R.string.system_push_permission_external_directory)
                else -> localized(R.string.system_push_permission_other, action)
            }
            val code = if (anything) emptyList() else permission.resources
            val text = if (code.isEmpty()) summary else localized(R.string.system_push_permission_collapsed, summary, code.first())
            val actions = mutableListOf<Action>()
            // A cut request can't be judged from the notification.
            if (permission.truncated) {
                actions += Action.Open
            } else {
                if ("reject" in permission.decisions) actions += Action.Deny
                if ("always" in permission.decisions) actions += Action.AlwaysAllow
                if ("once" in permission.decisions) actions += Action.Allow
            }
            val covers = permission.save.takeIf { Action.AlwaysAllow in actions && it.isNotEmpty() }
            val hidden = count - code.size.coerceAtMost(MAXIMUM_CODE_LINES)
            return PushShown(
                kind = Kind.Permission,
                title = title,
                text = text,
                expandedText = summary,
                code = code.take(MAXIMUM_CODE_LINES),
                note = when {
                    covers != null -> localized(R.string.system_push_permission_always_covers, covers.joinToString(", "))
                    hidden > 0 && code.isNotEmpty() -> localizedPlural(R.plurals.system_push_more, hidden, hidden)
                    else -> null
                },
                actions = actions,
            )
        }

        private fun question(title: String, form: PushMessage.Form, body: String?): PushShown {
            val field = form.fields.firstOrNull()
            val question = field?.description?.takeIf { it.isNotBlank() } ?: field?.title?.takeIf { it.isNotBlank() }
                ?: body?.takeIf { it.isNotBlank() } ?: localized(R.string.system_push_public_question)
            // A question that opens with a plan asks in its last line.
            val text = question.lines().lastOrNull { it.isNotBlank() }?.trim() ?: question
            val answerable = form.inlineReply && form.fieldCount == 1 && form.fields.size == 1 && field?.type == "string"
            val isShort = question.length <= MAXIMUM_QUESTION_WITH_ROWS && '\n' !in question
            if (field == null || !answerable || !isShort) {
                val options = field?.options?.size ?: 0
                return PushShown(
                    kind = Kind.Question,
                    title = title,
                    text = text,
                    expandedText = question,
                    note = when {
                        form.fieldCount > 1 -> localized(R.string.system_push_question_position, 1, form.fieldCount)
                        options > 0 -> localizedPlural(R.plurals.system_push_question_options, options, options)
                        else -> null
                    },
                    actions = listOf(Action.Open),
                )
            }
            val rows = field.options.take(MAXIMUM_ROWS).map { Row(it.label, it.description, it.value ?: it.label) }
            return PushShown(
                kind = Kind.Question,
                title = title,
                text = text,
                expandedText = question,
                rows = rows,
                more = field.options.size - rows.size,
                actions = if (field.custom) listOf(Action.AnswerTyped) else emptyList(),
            )
        }

        private fun turn(title: String, turn: PushMessage.Turn): PushShown? {
            val jobs = turn.background.values.sum()
            val note = if (jobs > 0) localizedPlural(R.plurals.system_push_background, jobs, jobs) else null
            if (turn.outcome == "succeeded") {
                val lines = plainLines(turn.text.orEmpty())
                if (lines.isEmpty()) {
                    return PushShown(Kind.Finished, title, localized(R.string.system_push_finished), note = note, actions = listOf(Action.ReplyToSession))
                }
                val room = if (note == null) MAXIMUM_ANSWER_LINES else MAXIMUM_ANSWER_LINES - 2
                return PushShown(
                    kind = Kind.Finished,
                    title = title,
                    text = lines.last(),
                    expandedText = ending(lines, room),
                    note = note,
                    actions = listOf(Action.ReplyToSession),
                )
            }
            if (turn.outcome != "failed") return null
            return PushShown(Kind.Failed, title, failure(turn), note = note, actions = listOf(Action.ReplyToSession))
        }

        /** Markdown as the plain lines a notification can show. */
        private fun plainLines(markdown: String): List<String> {
            val lines = mutableListOf<String>()
            for (raw in markdown.lines()) {
                var line = raw.trim()
                if (line.startsWith("```")) continue
                line = heading.replace(line, "")
                line = bullet.replace(line, "• ")
                line = link.replace(line, "$1")
                line = line.replace("**", "").replace("__", "").replace("`", "")
                if (line.isNotBlank()) lines += line
            }
            return lines
        }

        /** The last of [lines] that fit in [room] wrapped lines: an answer's conclusion comes last. */
        private fun ending(lines: List<String>, room: Int): String {
            val kept = ArrayDeque<String>()
            var left = room
            for (line in lines.asReversed()) {
                val height = (line.length + CHARACTERS_PER_LINE - 1) / CHARACTERS_PER_LINE
                if (height > left) {
                    // A paragraph longer than the room left is only worth cutting when it is all there is.
                    if (kept.isEmpty()) kept.addFirst("…" + line.takeLast(left * CHARACTERS_PER_LINE).substringAfter(' '))
                    break
                }
                kept.addFirst(line)
                left -= height
            }
            return kept.joinToString("\n")
        }

        private fun failure(turn: PushMessage.Turn): String {
            if (turn.errorType == "provider.rate-limit") {
                val seconds = ((turn.retryAfterMs ?: 0) / 1000).toInt()
                return when {
                    seconds <= 0 -> localized(R.string.system_push_failed_rate_limit)
                    seconds < 90 -> localizedPlural(R.plurals.system_push_failed_rate_limit_seconds, seconds, seconds)
                    else -> localizedPlural(R.plurals.system_push_failed_rate_limit_minutes, seconds / 60, seconds / 60)
                }
            }
            if (turn.errorType == "provider.auth") return localized(R.string.system_push_failed_auth)
            return turn.errorMessage?.takeIf { it.isNotBlank() } ?: localized(R.string.system_push_failed)
        }
    }
}
