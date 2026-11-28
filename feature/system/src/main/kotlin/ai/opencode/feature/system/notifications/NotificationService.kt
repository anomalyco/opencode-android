package ai.opencode.feature.system.notifications

import ai.opencode.core.log.AppLog
import android.app.NotificationManager
import android.content.Context

/**
 * A server's push on its way from the transport (FCM's `onMessageReceived`) to the notification
 * shade, and what the app later removes there.
 *
 * A push carries only the encrypted `v2` envelope under `opencode`. One this phone can't open
 * shows nothing.
 */
object SessionPushNotifications {
    internal const val EXTRA_SESSION = "opencode.sessionID"
    internal const val EXTRA_STATUS = "opencode.status"

    /** The push types that wait on an answer. */
    private val needsInputStatuses = setOf("permission", "form")

    fun handle(context: Context, data: Map<String, String>) {
        val envelope = NotificationContent.Envelope.from(data) ?: return
        val message = PushNotifications.open(envelope)
        if (message == null) {
            AppLog.app.info { "Push not opened" }
            return
        }
        PushNotifications.handle(context, envelope.ref, message)
    }

    /**
     * Every notification for these sessions, or only their needs-input ones once the request is
     * answered. The drawer's `DeliveredNotificationsClient` calls this.
     */
    fun remove(context: Context, sessionIDs: Set<String>, needsInputOnly: Boolean) {
        if (sessionIDs.isEmpty()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val keys = HashSet<String>()
        for (notification in manager.activeNotifications) {
            if (notification.id != NOTIFICATION_ID) continue
            val key = notification.tag ?: continue
            val extras = notification.notification.extras
            if (needsInputOnly && extras.getString(EXTRA_STATUS) !in needsInputStatuses) continue
            if (extras.getString(EXTRA_SESSION) !in sessionIDs) continue
            keys.add(key)
        }
        PushNotifications.cancel(context, keys)
    }

    /**
     * This server's notifications its fresh session list has settled: a needs-input one whose
     * session isn't [waiting] any more, and a finished or failed one whose session is [read].
     */
    fun removeStale(context: Context, serverKey: String, read: Set<String>, waiting: Set<String>) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val ref = SessionNotifications.shared.savedRef(serverKey) ?: return
        val keys = HashSet<String>()
        for (notification in manager.activeNotifications) {
            if (notification.id != NOTIFICATION_ID) continue
            val key = notification.tag ?: continue
            if (!key.startsWith("$ref:")) continue
            val extras = notification.notification.extras
            val status = extras.getString(EXTRA_STATUS) ?: continue
            val sessionID = extras.getString(EXTRA_SESSION) ?: continue
            val stale = if (status in needsInputStatuses) sessionID !in waiting else sessionID in read
            if (stale) keys.add(key)
        }
        PushNotifications.cancel(context, keys)
    }

    /** Every notification of a server that is being removed from the app. */
    fun removeServer(context: Context, ref: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val keys = HashSet<String>()
        for (notification in manager.activeNotifications) {
            if (notification.id != NOTIFICATION_ID) continue
            val key = notification.tag ?: continue
            if (key.startsWith("$ref:")) keys.add(key)
        }
        PushNotifications.cancel(context, keys)
    }

    internal const val NOTIFICATION_ID = 4201
}
