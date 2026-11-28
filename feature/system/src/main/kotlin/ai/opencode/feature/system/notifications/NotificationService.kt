package ai.opencode.feature.system.notifications

import ai.opencode.core.log.AppLog
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * A server's push on its way from the transport (FCM's `onMessageReceived`) to the notification
 * shade, and what the app later removes or recounts there.
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
        for (notification in manager.activeNotifications) {
            if (notification.id != NOTIFICATION_ID) continue
            val extras = notification.notification.extras
            if (needsInputOnly && extras.getString(EXTRA_STATUS) !in needsInputStatuses) continue
            if (extras.getString(EXTRA_SESSION) !in sessionIDs) continue
            manager.cancel(notification.tag, notification.id)
        }
        PushNotifications.removeEmptySummaries(context)
    }

    /**
     * The app icon's count: launchers count from the shown notifications' `number`, so the session
     * notifications still in the shade are reposted silently with the ledger's count.
     */
    @SuppressLint("MissingPermission")
    fun applyBadge(context: Context, count: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        for (shown in manager.activeNotifications) {
            if (shown.id != NOTIFICATION_ID || shown.notification.number == count) continue
            if (shown.notification.extras.getBoolean(EXTRA_COUNTS_AS_ONE)) continue
            runCatching {
                val updated = android.app.Notification.Builder.recoverBuilder(context, shown.notification)
                    .setNumber(count)
                    .setOnlyAlertOnce(true)
                    .build()
                manager.notify(shown.tag, shown.id, updated)
            }.onFailure { AppLog.app.debug { "App badge not set: ${it::class.java.simpleName}" } }
        }
    }

    /** On a notification the launcher counts as one, whatever the ledger's count is. */
    internal const val EXTRA_COUNTS_AS_ONE = "opencode.push.countsAsOne"
    internal const val NOTIFICATION_ID = 4201
}
