package ai.opencode.feature.system.notifications

import ai.opencode.core.log.AppLog
import ai.opencode.core.strings.localized
import ai.opencode.core.strings.localizedPlural
import ai.opencode.feature.system.R
import ai.opencode.feature.system.intents.SystemActionActivity
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import kotlinx.serialization.json.Json

/**
 * Shows the server's push messages (`specs/v2/push.md` in the opencode repo) as notifications: a
 * permission with Allow and Deny, a question with a row per option, a finished or failed session
 * with Reply, and a clear that takes them away again. [PushActionReceiver] sends what the buttons say.
 */
internal object PushNotifications {
    const val SUMMARY_ID = 4202
    private const val PREFERENCES = "push-notifications"
    private const val LEDGER = "ledger"
    /** `Notification.FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY`, which is only named from API 35. */
    private const val FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY = 0x00010000
    private const val EXTRA_LEAVING = "opencode.push.leaving"
    private const val LEAVING_MILLISECONDS = 300L
    private const val CODE_WIDEST_SCREEN_DP = 411
    private const val CODE_MARGIN_DP = 124
    private const val CODE_CHARACTER_DP = 7.8f
    private const val CODE_FEWEST_COLUMNS = 16
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    /** What a button needs to find its notification, server and session again. */
    data class Target(val ref: String, val key: String, val sessionID: String?, val title: String)

    /** The envelope's box as a push message; null when it can't be opened or isn't one. */
    fun open(envelope: NotificationContent.Envelope): PushMessage? {
        val key = runCatching { NotificationKeyStore.live.sharedKey(envelope.ref) }.getOrNull() ?: return null
        val text = runCatching { NotificationCrypto.openText(envelope.box, envelope.ref, key) }.getOrNull() ?: return null
        return PushMessage.parse(text)
    }

    fun handle(context: Context, ref: String, message: PushMessage) {
        val now = System.currentTimeMillis()
        if (message.type == "clear" && message.version == 1) {
            var removed = 0
            for (id in message.ids) {
                val key = "$ref:$id"
                if (updateLedger(context) { it.clear(key, message.time, now) }) {
                    cancel(context, key)
                    removed += 1
                }
            }
            AppLog.app.info { "Push clear ids=${message.ids.size} removed=$removed" }
            return
        }
        val shown = PushShown.from(message, codeColumns(context)) ?: return
        val key = "$ref:${message.id ?: message.sessionID ?: message.type}"
        if (!updateLedger(context) { it.message(key, message.time, now) }) {
            AppLog.app.info { "Push type=${message.type} dropped as stale" }
            return
        }
        post(context, ref, key, message, shown)
    }

    /** How many monospaced characters fit across the code block at this screen width and font size. */
    private fun codeColumns(context: Context): Int {
        val configuration = context.resources.configuration
        val width = minOf(configuration.smallestScreenWidthDp, CODE_WIDEST_SCREEN_DP) - CODE_MARGIN_DP
        return (width / (CODE_CHARACTER_DP * configuration.fontScale)).toInt().coerceAtLeast(CODE_FEWEST_COLUMNS)
    }

    /** [replied] when a reply typed into the notification is what it is being removed for. */
    fun cancel(context: Context, key: String, replied: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val shown = manager.activeNotifications.firstOrNull { it.id == SessionPushNotifications.NOTIFICATION_ID && it.tag == key }
        if (shown != null && shown.notification.extras.getBoolean(EXTRA_LEAVING)) return
        val kept = shown != null && (shown.notification.flags and FLAG_LIFETIME_EXTENDED_BY_DIRECT_REPLY) != 0
        if (shown != null && (replied || kept)) {
            // Android refuses an app's cancel of a notification that was replied to inline, so it is posted again to time out.
            runCatching {
                val again = android.app.Notification.Builder.recoverBuilder(context, shown.notification)
                    .setOnlyAlertOnce(true)
                    .setTimeoutAfter(LEAVING_MILLISECONDS)
                again.extras.putBoolean(EXTRA_LEAVING, true)
                manager.notify(key, SessionPushNotifications.NOTIFICATION_ID, again.build())
            }
        } else {
            manager.cancel(key, SessionPushNotifications.NOTIFICATION_ID)
        }
        removeEmptySummaries(context, leaving = key)
    }

    /** A project's stack header goes once its last notification has; [leaving] is one being removed now. */
    fun removeEmptySummaries(context: Context, leaving: String? = null) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val active = manager.activeNotifications
        for (summary in active) {
            if (summary.id != SUMMARY_ID) continue
            val group = summary.notification.group
            val children = active.count {
                it.id == SessionPushNotifications.NOTIFICATION_ID && it.notification.group == group && it.tag != leaving &&
                    !it.notification.extras.getBoolean(EXTRA_LEAVING)
            }
            if (children == 0) manager.cancel(summary.tag, SUMMARY_ID)
        }
    }

    /** Replaces a notification whose button couldn't reach the server. */
    @SuppressLint("MissingPermission")
    fun showSendFailure(context: Context, target: Target) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        SystemNotificationChannels.ensure(context)
        val builder = NotificationCompat.Builder(context, SystemNotificationChannels.PUSH_ATTENTION)
            .setSmallIcon(R.drawable.ic_opencode_mark)
            .setContentTitle(target.title)
            .setContentText(localized(R.string.system_push_send_failed))
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, target))
        builder.extras.putString(SessionPushNotifications.EXTRA_SESSION, target.sessionID)
        runCatching { NotificationManagerCompat.from(context).notify(target.key, SessionPushNotifications.NOTIFICATION_ID, builder.build()) }
    }

    private fun updateLedger(context: Context, change: (PushLedger) -> Pair<PushLedger, Boolean>): Boolean = synchronized(lock) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val saved = preferences.getString(LEDGER, null)
        val ledger = saved?.let { runCatching { json.decodeFromString(PushLedger.serializer(), it) }.getOrNull() } ?: PushLedger()
        val (next, result) = change(ledger)
        if (next != ledger) preferences.edit().putString(LEDGER, json.encodeToString(PushLedger.serializer(), next)).apply()
        result
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, ref: String, key: String, message: PushMessage, shown: PushShown) {
        val presentation = SessionNotifications.shared.presentation(message.sessionID)
        if (presentation == NotificationPresentation.None) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        SystemNotificationChannels.ensure(context)
        val silent = presentation == NotificationPresentation.ListOnly
        val target = Target(ref, key, message.sessionID, shown.title)
        val open = openIntent(context, target)
        val channel = channel(shown.kind)
        val folder = message.directory?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
        val group = "$ref:${message.directory.orEmpty()}"
        val hidden = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_opencode_mark)
            .setContentTitle(NotificationContent.PLACEHOLDER_TITLE)
            .setContentText(hiddenText(shown.kind))
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_opencode_mark)
            .setContentTitle(shown.title)
            .setContentText(shown.text)
            .setSubText(folder)
            .setAutoCancel(true)
            .setCategory(category(shown.kind))
            .setSilent(silent)
            .setGroup(group)
            .setContentIntent(open)
            .setDeleteIntent(broadcast(context, PushActionReceiver.ACTION_DISMISSED, target))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(hidden.build())
        if (message.time > 0) builder.setWhen(message.time)
        if (shown.kind == PushShown.Kind.Permission || shown.kind == PushShown.Kind.Question) {
            builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
            builder.setCustomBigContentView(detail(context, target, shown, open, message.form))
        } else {
            val text = listOfNotNull(shown.expandedText, shown.note).joinToString("\n\n")
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }
        for (action in shown.actions) {
            action(context, target, action, open, message)?.let(builder::addAction)
        }
        builder.extras.putString(SessionPushNotifications.EXTRA_SESSION, message.sessionID)
        builder.extras.putString(SessionPushNotifications.EXTRA_STATUS, message.type)
        builder.extras.putBoolean(SessionPushNotifications.EXTRA_COUNTS_AS_ONE, true)
        builder.setNumber(1)
        if (message.sessionID != null) {
            val defaults = AppBadgeLedger.groupDefaults(context)
            AppBadgeLedger.load(defaults)?.badgeAfterPush(sessionID = message.sessionID, ref = ref)?.let { (ledger, badge) ->
                ledger.save(defaults)
                AppLog.app.info { "App badge count=$badge" }
            }
        }
        runCatching { NotificationManagerCompat.from(context).notify(key, SessionPushNotifications.NOTIFICATION_ID, builder.build()) }
            .onFailure { AppLog.app.error { "Push notification not posted: ${it::class.java.simpleName}" } }
        AppLog.app.info { "Push type=${message.type} shown actions=${shown.actions.size} rows=${shown.rows.size}" }
        postSummary(context, key, group, folder, channel)
    }

    /** One project's notifications stack under a header once there are two. */
    @SuppressLint("MissingPermission")
    private fun postSummary(context: Context, key: String, group: String, folder: String?, channel: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val children = mutableSetOf(key)
        for (active in manager.activeNotifications) {
            if (active.id != SessionPushNotifications.NOTIFICATION_ID || active.notification.group != group) continue
            if (active.notification.extras.getBoolean(EXTRA_LEAVING)) continue
            children += active.tag
        }
        if (children.size < 2) return
        val summary = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_opencode_mark)
            .setSubText(folder)
            .setStyle(NotificationCompat.InboxStyle().setSummaryText(folder))
            .setGroup(group)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(group, SUMMARY_ID, summary) }
    }

    private fun detail(context: Context, target: Target, shown: PushShown, open: PendingIntent?, form: PushMessage.Form?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.push_detail)
        views.setTextViewText(R.id.push_title, shown.title)
        views.setTextViewText(R.id.push_text, shown.expandedText)
        if (shown.code.isNotEmpty()) {
            views.setTextViewText(R.id.push_code, shown.code.joinToString("\n"))
            views.setViewVisibility(R.id.push_code, View.VISIBLE)
        }
        if (shown.note != null) {
            views.setTextViewText(R.id.push_note, shown.note)
            views.setViewVisibility(R.id.push_note, View.VISIBLE)
        }
        val rows = listOf(
            Triple(R.id.push_row_1, R.id.push_row_1_label, R.id.push_row_1_description),
            Triple(R.id.push_row_2, R.id.push_row_2_label, R.id.push_row_2_description),
            Triple(R.id.push_row_3, R.id.push_row_3_label, R.id.push_row_3_description),
        )
        val field = form?.fields?.firstOrNull()
        for ((index, row) in shown.rows.withIndex()) {
            if (index >= rows.size || form == null || field == null) break
            val (container, label, description) = rows[index]
            views.setViewVisibility(container, View.VISIBLE)
            views.setTextViewText(label, row.label)
            if (row.description.isNullOrBlank()) {
                views.setViewVisibility(description, View.GONE)
            } else {
                views.setTextViewText(description, row.description)
                if (shown.rows.size < rows.size) views.setInt(description, "setMaxLines", 2)
            }
            val answer = broadcast(context, PushActionReceiver.ACTION_ANSWER, target, discriminator = "row$index") {
                putExtra(PushActionReceiver.EXTRA_REPLY_SESSION, form.replySessionID)
                putExtra(PushActionReceiver.EXTRA_FORM, form.formID)
                putExtra(PushActionReceiver.EXTRA_FIELD, field.key)
                putExtra(PushActionReceiver.EXTRA_VALUE, row.value)
            }
            views.setOnClickPendingIntent(container, answer)
        }
        if (shown.rows.isNotEmpty()) views.setInt(R.id.push_text, "setMaxLines", 3)
        if (shown.more > 0) {
            views.setTextViewText(R.id.push_more, localizedPlural(R.plurals.system_push_question_more_options, shown.more, shown.more))
            views.setViewVisibility(R.id.push_more, View.VISIBLE)
            if (open != null) views.setOnClickPendingIntent(R.id.push_more, open)
        }
        return views
    }

    private fun action(
        context: Context, target: Target, action: PushShown.Action, open: PendingIntent?, message: PushMessage,
    ): NotificationCompat.Action? {
        val permission = message.permission
        val form = message.form
        return when (action) {
            PushShown.Action.Deny -> permission?.let { decision(context, target, it, "reject", R.string.system_push_action_deny, locked = false) }
            PushShown.Action.AlwaysAllow -> permission?.let { decision(context, target, it, "always", R.string.system_push_action_always_allow, locked = true) }
            PushShown.Action.Allow -> permission?.let { decision(context, target, it, "once", R.string.system_push_action_allow, locked = true) }
            PushShown.Action.AnswerTyped -> {
                val field = form?.fields?.firstOrNull() ?: return null
                val intent = broadcast(context, PushActionReceiver.ACTION_ANSWER, target, discriminator = "typed", mutable = true) {
                    putExtra(PushActionReceiver.EXTRA_REPLY_SESSION, form.replySessionID)
                    putExtra(PushActionReceiver.EXTRA_FORM, form.formID)
                    putExtra(PushActionReceiver.EXTRA_FIELD, field.key)
                }
                typed(intent, R.string.system_push_hint_answer)
            }
            PushShown.Action.ReplyToSession -> {
                target.sessionID ?: return null
                typed(broadcast(context, PushActionReceiver.ACTION_REPLY, target, mutable = true), R.string.system_push_hint_reply)
            }
            PushShown.Action.Open -> open?.let { NotificationCompat.Action.Builder(0, localized(R.string.system_push_action_open), it).build() }
        }
    }

    /** Allowing asks for the phone to be unlocked; denying doesn't. */
    private fun decision(
        context: Context, target: Target, permission: PushMessage.Permission, decision: String, title: Int, locked: Boolean,
    ): NotificationCompat.Action {
        val intent = broadcast(context, PushActionReceiver.ACTION_PERMISSION, target, discriminator = decision) {
            putExtra(PushActionReceiver.EXTRA_REPLY_SESSION, permission.replySessionID)
            putExtra(PushActionReceiver.EXTRA_REQUEST, permission.requestID)
            putExtra(PushActionReceiver.EXTRA_DECISION, decision)
        }
        return NotificationCompat.Action.Builder(0, localized(title), intent).setAuthenticationRequired(locked).build()
    }

    private fun typed(intent: PendingIntent, hint: Int): NotificationCompat.Action {
        val input = RemoteInput.Builder(PushActionReceiver.KEY_TEXT).setLabel(localized(hint)).build()
        return NotificationCompat.Action.Builder(0, localized(R.string.system_push_action_reply), intent)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .setAuthenticationRequired(true)
            .build()
    }

    private fun broadcast(
        context: Context, action: String, target: Target, discriminator: String = "", mutable: Boolean = false, fill: Intent.() -> Unit = {},
    ): PendingIntent {
        val intent = Intent(context, PushActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("opencode-push://${Uri.encode(target.key)}/$action/$discriminator"))
            .putExtra(PushActionReceiver.EXTRA_REF, target.ref)
            .putExtra(PushActionReceiver.EXTRA_KEY, target.key)
            .putExtra(PushActionReceiver.EXTRA_SESSION, target.sessionID)
            .putExtra(PushActionReceiver.EXTRA_TITLE, target.title)
        intent.fill()
        // A typed reply is added to the intent by the system, so that one can't be immutable.
        val mutability = if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutability)
    }

    private fun openIntent(context: Context, target: Target): PendingIntent? {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        if (target.sessionID != null) {
            val intent = SystemActionActivity.pushIntent(context, target.ref, target.sessionID)
            return PendingIntent.getActivity(context, target.sessionID.hashCode(), intent, flags)
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, launch, flags)
    }

    private fun channel(kind: PushShown.Kind): String = when (kind) {
        PushShown.Kind.Permission, PushShown.Kind.Question -> SystemNotificationChannels.PUSH_ATTENTION
        PushShown.Kind.Finished -> SystemNotificationChannels.PUSH_FINISHED
        PushShown.Kind.Failed -> SystemNotificationChannels.PUSH_FAILED
        PushShown.Kind.Other -> SystemNotificationChannels.SESSIONS
    }

    private fun category(kind: PushShown.Kind): String = when (kind) {
        PushShown.Kind.Failed -> NotificationCompat.CATEGORY_ERROR
        PushShown.Kind.Finished -> NotificationCompat.CATEGORY_STATUS
        else -> NotificationCompat.CATEGORY_MESSAGE
    }

    /** What a locked phone that hides notification content shows instead. */
    private fun hiddenText(kind: PushShown.Kind): String = when (kind) {
        PushShown.Kind.Permission -> localized(R.string.system_push_public_permission)
        PushShown.Kind.Question -> localized(R.string.system_push_public_question)
        PushShown.Kind.Finished -> localized(R.string.system_push_finished)
        PushShown.Kind.Failed -> localized(R.string.system_push_failed)
        PushShown.Kind.Other -> NotificationContent.PLACEHOLDER_BODY
    }
}
