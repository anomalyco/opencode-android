package ai.opencode.feature.system.notifications

import ai.opencode.core.log.AppLog
import ai.opencode.feature.system.R
import ai.opencode.feature.system.intents.SystemActionActivity
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Turns a session push into a notification, and decides how it shows in the foreground and where a
 * tap leads.
 *
 * A push carries only the encrypted `v2` envelope under `opencode`. Its title,
 * body, session and status show only once [NotificationContent.verifiedPayload] opens the box
 * with this phone's key for its ref; anything else ("no key yet", a tampered box, a v1 push)
 * shows the generic "OpenCode" / "New activity" notification, never clear text from the
 * transport. The badge counts verified pushes only.
 *
 * Taps: the content intent is built from the verified payload and targets the unexported
 * [SystemActionActivity], so only this app can send it.
 *
 * The transport (FCM's `onMessageReceived`) calls [handle]; nothing calls it until a push
 * channel exists (see [SessionNotifications]).
 */
object SessionPushNotifications {
    /** Statuses a needs-input notification carries. */
    val needsInputStatuses = setOf("needs-input", "permission", "question", "form")

    private const val EXTRA_SESSION = "opencode.sessionID"
    private const val EXTRA_STATUS = "opencode.status"
    private const val EXTRA_ENVELOPE = "opencode.envelope"
    private const val EXTRA_LOCAL_KEY = "opencode.localKey"

    @SuppressLint("MissingPermission")
    fun handle(context: Context, data: Map<String, String>) {
        val verified = NotificationContent.verifiedPayload(data)
        val shown = NotificationContent.content(data, verified)
        val sessionID = verified?.payload?.sessionID
        val notifications = SessionNotifications.shared
        val presentation = notifications.presentation(sessionID)
        if (presentation == NotificationPresentation.None) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        SystemNotificationChannels.ensure(context)
        val builder = NotificationCompat.Builder(context, SystemNotificationChannels.SESSIONS)
            .setSmallIcon(R.drawable.ic_opencode_mark)
            .setContentTitle(shown.title)
            .setContentText(shown.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(shown.body))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setSilent(presentation == NotificationPresentation.ListOnly)
            .setPriority(if (presentation == NotificationPresentation.ListOnly) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
        // The thread is "<ref>:<session>", set only after verifying.
        shown.thread?.let { builder.setGroup(it) }
        builder.extras.putString(EXTRA_SESSION, sessionID)
        builder.extras.putString(EXTRA_STATUS, verified?.payload?.status)
        builder.extras.putString(EXTRA_ENVELOPE, shown.envelope?.toString())
        if (verified != null) {
            // The badge the ledger counts for its session.
            val defaults = AppBadgeLedger.groupDefaults(context)
            AppBadgeLedger.load(defaults)?.badgeAfterPush(sessionID = verified.payload.sessionID, ref = verified.ref)?.let { (ledger, badge) ->
                ledger.save(defaults)
                builder.setNumber(badge)
            }
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context, verified.payload.sessionID.hashCode(),
                    SystemActionActivity.pushIntent(context, verified.ref, verified.payload.sessionID),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                builder.setContentIntent(PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE))
            }
        }
        val tag = sessionID ?: shown.envelope?.toString()?.hashCode()?.toString()
        runCatching { NotificationManagerCompat.from(context).notify(tag, NOTIFICATION_ID, builder.build()) }
            .onFailure { AppLog.app.error { "Session notification not posted: ${it::class.java.simpleName}" } }
    }

    const val STATUS_NEEDS_INPUT = "needs-input"
    const val STATUS_SUCCEEDED = "succeeded"
    const val STATUS_FAILED = "failed"

    /**
     * A session's notification from the app's own live stream (Stay Connected During Runs), in the
     * same slot a push for that session takes, so the two replace each other rather than stack.
     */
    @SuppressLint("MissingPermission")
    fun postLocal(
        context: Context,
        serverKey: String,
        sessionID: String,
        title: String,
        status: String,
        request: SessionNotificationRequest? = null,
        failure: String? = null,
    ) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val shownKey = listOf(status, request?.requestID, failure).joinToString("|")
        for (shown in manager.activeNotifications) {
            if (shown.id != NOTIFICATION_ID || shown.tag != sessionID) continue
            if (shown.notification.extras.getString(EXTRA_LOCAL_KEY) == shownKey) return
        }
        SystemNotificationChannels.ensure(context)
        val ref = SessionNotifications.shared.ref(serverKey)
        val body = when (status) {
            STATUS_NEEDS_INPUT -> context.getString(R.string.system_session_needs_input)
            STATUS_FAILED -> context.getString(R.string.system_session_failed)
            else -> context.getString(R.string.system_session_done)
        }
        val tap = PendingIntent.getActivity(
            context, sessionID.hashCode(),
            SystemActionActivity.pushIntent(context, ref, sessionID),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, SystemNotificationChannels.SESSIONS)
            .setSmallIcon(R.drawable.ic_opencode_mark)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup("$ref:$sessionID")
            .setContentIntent(tap)
        if (request != null || failure != null) {
            val lines = mutableListOf<String>()
            if (request != null) {
                lines += request.heading
                if (request.detail.isNotEmpty()) lines += request.detail
            }
            if (failure != null) lines += failure
            val expanded = lines.joinToString("\n")
            builder.setContentText(if (failure != null) failure else request?.heading ?: body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, SystemNotificationChannels.SESSIONS)
                        .setSmallIcon(R.drawable.ic_opencode_mark)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setContentIntent(tap)
                        .build(),
                )
        }
        if (request != null && failure == null) {
            for (action in SessionNotificationActionReceiver.actions(context, ref, sessionID, title, request)) {
                builder.addAction(action)
            }
        }
        builder.extras.putString(EXTRA_SESSION, sessionID)
        builder.extras.putString(EXTRA_STATUS, status)
        builder.extras.putString(EXTRA_LOCAL_KEY, shownKey)
        runCatching { NotificationManagerCompat.from(context).notify(sessionID, NOTIFICATION_ID, builder.build()) }
            .onFailure { AppLog.app.error { "Session notification not posted: ${it::class.java.simpleName}" } }
    }

    /** Clears a session's notification after an answer from it went through. */
    fun cancel(context: Context, sessionID: String) {
        NotificationManagerCompat.from(context).cancel(sessionID, NOTIFICATION_ID)
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
            val content = Delivered(
                tag = notification.tag,
                group = notification.notification.group,
                sessionID = extras.getString(EXTRA_SESSION),
                status = extras.getString(EXTRA_STATUS),
                envelope = extras.getString(EXTRA_ENVELOPE),
            )
            if (needsInputOnly && !isNeedsInput(content)) continue
            val id = sessionID(content) ?: continue
            if (id !in sessionIDs) continue
            manager.cancel(notification.tag, notification.id)
        }
    }

    /** What [remove] reads from a shown notification. */
    internal data class Delivered(
        val tag: String? = null,
        val group: String? = null,
        val sessionID: String? = null,
        val status: String? = null,
        val envelope: String? = null,
    )

    /**
     * An encrypted preview names its session by the thread [handle] wrote after verifying, else by
     * verifying again (a push that couldn't be decrypted when it arrived); anything else by its
     * session extra, then its tag.
     */
    internal fun sessionID(
        content: Delivered,
        sharedKey: (String) -> ByteArray = { NotificationKeyStore.live.sharedKey(it) },
    ): String? {
        if (encrypted(content) != null) {
            val envelope = NotificationContent.Envelope.from(content.envelope)
            return NotificationContent.threadSessionID(content.group, envelope)
                ?: envelope?.let { NotificationContent.verify(it, sharedKey)?.payload?.sessionID }
        }
        return content.sessionID ?: content.tag
    }

    internal fun isNeedsInput(content: Delivered): Boolean {
        // Encrypted previews are run completions, not local pending-request notices.
        if (encrypted(content) != null) return false
        return content.status in needsInputStatuses
    }

    /** The `opencode` envelope when it is an encrypted one. */
    private fun encrypted(content: Delivered): JsonObject? =
        content.envelope
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?.takeIf { "v" in it }

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
            runCatching {
                val updated = android.app.Notification.Builder.recoverBuilder(context, shown.notification)
                    .setNumber(count)
                    .setOnlyAlertOnce(true)
                    .build()
                manager.notify(shown.tag, shown.id, updated)
            }.onFailure { AppLog.app.debug { "App badge not set: ${it::class.java.simpleName}" } }
        }
    }

    private const val NOTIFICATION_ID = 4201
}
