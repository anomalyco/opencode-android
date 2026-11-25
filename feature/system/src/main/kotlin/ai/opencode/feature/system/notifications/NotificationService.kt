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
        SystemNotificationChannels.ensure(context)
        val builder = NotificationCompat.Builder(context, SystemNotificationChannels.forType(shown.type))
            .setSmallIcon(R.drawable.ic_opencode_mark)
            .setContentTitle(shown.title)
            .setContentText(shown.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(shown.body))
            .setAutoCancel(true)
            .setOnlyAlertOnce(verified == null)
            .setSilent(presentation == NotificationPresentation.ListOnly)
            .setPriority(if (presentation == NotificationPresentation.ListOnly) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
        builder.extras.putString(EXTRA_SESSION, sessionID)
        builder.extras.putString(EXTRA_STATUS, shown.type)
        builder.extras.putString(EXTRA_ENVELOPE, shown.envelope?.toString())
        builder.extras.putString(EXTRA_ID, verified?.payload?.id)
        if (verified != null && sessionID != null) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context, sessionID.hashCode(),
                    SystemActionActivity.pushIntent(context, verified.ref, sessionID),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                builder.setContentIntent(PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE))
            }
        }
        val tag = tag(verified, data)
        runCatching { NotificationManagerCompat.from(context).notify(tag, NOTIFICATION_ID, builder.build()) }
            .onFailure { AppLog.app.error { "Session notification not posted: ${it::class.java.simpleName}" } }
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
}
