package ai.opencode.feature.system.notifications

import ai.opencode.core.log.AppLog
import ai.opencode.feature.system.R
import ai.opencode.feature.system.intents.SystemActionActivity
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.delay

/**
 * Turns a session push into a notification, and decides how it shows in the foreground and where a
 * tap leads.
 *
 * A push carries only the encrypted `v2` envelope under `opencode`. Its title, body, session and
 * type show only once [NotificationContent.verifiedPayload] opens the box with this phone's key for
 * its ref; a high-priority push whose box can't be opened or read shows the generic "OpenCode" /
 * "New activity" notification, one per server, never clear text from the transport. Any other
 * unreadable push may be a `clear` and shows nothing. Each type posts on its own channel. No
 * notification sets a `number`: launchers add them up, so the icon counts the notifications
 * showing. A verified `clear` removes the notification posted for its `id`, if it still shows,
 * and posts nothing.
 *
 * All share one group with a silent summary, so Android never autogroups them: Android 16 marks an
 * autogrouped notification silent, and it no longer pops up.
 *
 * Taps: the content intent is built from the verified payload and targets the unexported
 * [SystemActionActivity], so only this app can send it.
 *
 * The transport (FCM's `onMessageReceived`) calls [handle]; nothing calls it until a push
 * channel exists (see [SessionNotifications]).
 */
object SessionPushNotifications {
    /** Types a needs-input notification carries. */
    val needsInputStatuses = setOf("permission", "question")

    private const val EXTRA_SESSION = "opencode.sessionID"
    private const val EXTRA_STATUS = "opencode.status"
    private const val EXTRA_ENVELOPE = "opencode.envelope"
    private const val EXTRA_ID = "opencode.id"

    @SuppressLint("MissingPermission")
    fun handle(
        context: Context,
        data: Map<String, String>,
        highPriority: Boolean = true,
        sharedKey: (String) -> ByteArray = { NotificationKeyStore.live.sharedKey(it) },
    ) {
        val verified = NotificationContent.verifiedPayload(data, sharedKey)
        if (verified?.payload?.type == "clear") {
            clear(context, verified.payload.id)
            return
        }
        // Only an alert is sent at high priority; anything else that can't be read may be a clear.
        if (verified == null && !highPriority) return
        val shown = NotificationContent.content(data, verified) ?: return
        val sessionID = verified?.payload?.sessionID
        val notifications = SessionNotifications.shared
        val presentation = notifications.presentation(sessionID)
        if (presentation == NotificationPresentation.None) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        post(context, data, shown, verified, presentation, Stage.Shown)
    }

    /** Where a notification stands after one of its buttons was pressed. */
    internal enum class Stage { Shown, Sending, Failed }

    @SuppressLint("MissingPermission")
    internal fun post(
        context: Context,
        data: Map<String, String>,
        shown: NotificationContent.Shown,
        verified: NotificationContent.Verified?,
        presentation: NotificationPresentation,
        stage: Stage,
    ) {
        SystemNotificationChannels.ensure(context)
        val payload = verified?.payload
        val rich = payload?.let { RichPush.from(it, context.resources.configuration.fontScale) }
        val quiet = presentation == NotificationPresentation.ListOnly || stage != Stage.Shown
        val failed = rich?.isFailure == true || stage == Stage.Failed
        val builder = NotificationCompat.Builder(context, SystemNotificationChannels.forType(shown.type, payload?.outcome))
            .setSmallIcon(R.drawable.ic_opencode_mark)
            .setColor(if (failed) FAILURE_COLOR else ACCENT_COLOR)
            .setAutoCancel(true)
            .setOnlyAlertOnce(verified == null || stage != Stage.Shown)
            .setSilent(quiet)
            .setPriority(if (presentation == NotificationPresentation.ListOnly) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        builder.extras.putString(EXTRA_SESSION, payload?.sessionID)
        builder.extras.putString(EXTRA_STATUS, shown.type)
        builder.extras.putString(EXTRA_ENVELOPE, shown.envelope?.toString())
        builder.extras.putString(EXTRA_ID, payload?.id)
        builder.setContentIntent(open(context, verified))
        val tag = tag(verified, data)
        if (rich == null) {
            builder.setContentTitle(shown.title)
                .setContentText(shown.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(shown.body))
        } else {
            builder.setContentTitle(rich.title).setSubText(rich.subText)
            val status = when (stage) {
                Stage.Shown -> null
                Stage.Sending -> context.getString(R.string.system_push_sending)
                Stage.Failed -> context.getString(R.string.system_push_send_failed)
            }
            if (status != null) {
                builder.setContentText(status).setStyle(NotificationCompat.BigTextStyle().bigText(status))
            } else {
                builder.setContentText(rich.text)
                if (rich.detail != null) {
                    builder.setStyle(NotificationCompat.DecoratedCustomViewStyle())
                        .setCustomBigContentView(PushViews.detail(context, rich.detail, rich.title))
                } else if (rich.bigText != null) {
                    builder.setStyle(NotificationCompat.BigTextStyle().bigText(rich.bigText))
                }
                val envelope = shown.envelope?.toString()
                if (envelope != null) {
                    for ((index, action) in rich.actions.withIndex()) {
                        builder.addAction(action(context, verified, envelope, tag, action, index))
                    }
                }
            }
        }
        runCatching {
            val manager = NotificationManagerCompat.from(context)
            manager.notify(SUMMARY_ID, summary(context))
            manager.notify(tag, NOTIFICATION_ID, builder.build())
        }.onFailure { AppLog.app.error { "Session notification not posted: ${it::class.java.simpleName}" } }
    }

    /** The tap: the session the verified message names, else the app. */
    private fun open(context: Context, verified: NotificationContent.Verified?): PendingIntent? {
        val sessionID = verified?.payload?.sessionID
        if (verified != null && sessionID != null) {
            return PendingIntent.getActivity(
                context, sessionID.hashCode(),
                SystemActionActivity.pushIntent(context, verified.ref, sessionID),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * A button. Allowing and replying need the phone unlocked; denying doesn't. Each sends the
     * notification's envelope, which [PushActionReceiver] verifies again.
     */
    private fun action(
        context: Context,
        verified: NotificationContent.Verified,
        envelope: String,
        tag: String,
        action: PushAction,
        index: Int,
    ): NotificationCompat.Action {
        if (action == PushAction.Review) {
            return NotificationCompat.Action.Builder(null, context.getString(R.string.system_push_allow), open(context, verified))
                .setAuthenticationRequired(true)
                .build()
        }
        val label = when (action) {
            PushAction.Allow -> context.getString(R.string.system_push_allow)
            PushAction.Deny -> context.getString(R.string.system_push_deny)
            else -> context.getString(R.string.system_push_reply)
        }
        val typed = action == PushAction.FollowUp
        val intent = PushActionReceiver.intent(context, envelope, tag, action, index)
        val flags = if (typed) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getBroadcast(context, 0, intent, flags or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Action.Builder(null, label, pending)
            .setAuthenticationRequired(action != PushAction.Deny)
            .setShowsUserInterface(false)
        if (typed) {
            builder.addRemoteInput(RemoteInput.Builder(PushActionReceiver.KEY_TEXT).setLabel(context.getString(R.string.system_push_message_hint)).build())
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setAllowGeneratedReplies(false)
        }
        return builder.build()
    }

    /**
     * The notification posted for [verified] is done with: its button's answer reached the server.
     * Android 15+ refuses to cancel an inline-replied notification until an update from the app has
     * landed (up to 200 ms after posting), so this waits for one and posts [repost] if needed.
     */
    internal suspend fun dismiss(context: Context, verified: NotificationContent.Verified, repost: () -> Unit) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val tag = tag(verified, emptyMap())
        for (attempt in 1..DISMISS_ATTEMPTS) {
            delay(UPDATE_LANDS_MS)
            manager.cancel(tag, NOTIFICATION_ID)
            delay(CANCEL_LANDS_MS)
            if (!isShowing(manager, tag)) break
            repost()
        }
        removeSummaryIfEmpty(manager)
    }

    private fun isShowing(manager: NotificationManager, tag: String): Boolean {
        for (active in manager.activeNotifications) {
            if (active.id == NOTIFICATION_ID && active.tag == tag) return true
        }
        return false
    }

    /**
     * A needs-input notification gets its own slot per request, so a second request doesn't hide the
     * first; any other replaces its session's last one. One that can't be read replaces the last such
     * notification for its server.
     */
    private fun tag(verified: NotificationContent.Verified?, data: Map<String, String>): String {
        if (verified == null) return "placeholder:" + (NotificationContent.Envelope.from(data)?.ref ?: "")
        val payload = verified.payload
        val sessionID = payload.sessionID ?: return "message:" + payload.id
        if (payload.type in needsInputStatuses) return sessionID + ":" + payload.id
        return sessionID
    }

    /**
     * Every notification for these sessions, or only their needs-input ones once the request is
     * answered. The drawer's `DeliveredNotificationsClient` calls this.
     */
    fun remove(context: Context, sessionIDs: Set<String>, needsInputOnly: Boolean) {
        if (sessionIDs.isEmpty()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        for (notification in manager.activeNotifications) {
            if (notification.id != NOTIFICATION_ID) continue
            val content = delivered(notification)
            if (needsInputOnly && !isNeedsInput(content)) continue
            val id = sessionID(content) ?: continue
            if (id !in sessionIDs) continue
            manager.cancel(notification.tag, notification.id)
        }
        removeSummaryIfEmpty(manager)
    }

    /**
     * This server's notifications its fresh session list says no longer need the person: a
     * needs-input one whose session isn't [waiting] any more, and any other whose session is [read].
     */
    fun removeStale(context: Context, serverKey: String, read: Set<String>, waiting: Set<String>) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        for (notification in manager.activeNotifications) {
            if (notification.id != NOTIFICATION_ID) continue
            val content = delivered(notification)
            if (content.status == null) continue
            val envelope = NotificationContent.Envelope.from(content.envelope) ?: continue
            val sessionID = sessionID(content) ?: continue
            if (SessionNotifications.shared.destination(envelope.ref, sessionID)?.serverKey != serverKey) continue
            val stale = if (isNeedsInput(content)) sessionID !in waiting else sessionID in read
            if (stale) manager.cancel(notification.tag, notification.id)
        }
        removeSummaryIfEmpty(manager)
    }

    private fun summary(context: Context) =
        NotificationCompat.Builder(context, SystemNotificationChannels.SESSION_OTHER)
            .setSmallIcon(R.drawable.ic_opencode_mark)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setSilent(true)
            .setAutoCancel(true)
            .build()

    private fun removeSummaryIfEmpty(manager: NotificationManager) {
        for (notification in manager.activeNotifications) {
            if (notification.id == NOTIFICATION_ID) return
        }
        manager.cancel(SUMMARY_ID)
    }

    private fun delivered(notification: StatusBarNotification): Delivered {
        val extras = notification.notification.extras
        return Delivered(
            sessionID = extras.getString(EXTRA_SESSION),
            status = extras.getString(EXTRA_STATUS),
            envelope = extras.getString(EXTRA_ENVELOPE),
        )
    }

    /** The notification posted for a message's [id]; it may have been replaced by a later one for its session. */
    private fun clear(context: Context, id: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        for (notification in manager.activeNotifications) {
            if (notification.id != NOTIFICATION_ID) continue
            if (notification.notification.extras.getString(EXTRA_ID) != id) continue
            manager.cancel(notification.tag, notification.id)
        }
        removeSummaryIfEmpty(manager)
    }

    /** What [remove] reads from a shown notification. */
    internal data class Delivered(
        val sessionID: String? = null,
        val status: String? = null,
        val envelope: String? = null,
    )

    /**
     * The session [handle] wrote after verifying, else verified again (a push that couldn't be
     * decrypted when it arrived).
     */
    internal fun sessionID(
        content: Delivered,
        sharedKey: (String) -> ByteArray = { NotificationKeyStore.live.sharedKey(it) },
    ): String? {
        if (content.sessionID != null) return content.sessionID
        val envelope = NotificationContent.Envelope.from(content.envelope) ?: return null
        return NotificationContent.verify(envelope, sharedKey)?.payload?.sessionID
    }

    internal fun isNeedsInput(content: Delivered): Boolean = content.status in needsInputStatuses

    private const val NOTIFICATION_ID = 4201
    private const val SUMMARY_ID = 4202
    private const val DISMISS_ATTEMPTS = 3
    private const val UPDATE_LANDS_MS = 500L
    private const val CANCEL_LANDS_MS = 300L
    private const val GROUP = "sessions"
    private val ACCENT_COLOR = 0xFF3A5BF6.toInt()
    private val FAILURE_COLOR = 0xFFE5484D.toInt()
}
