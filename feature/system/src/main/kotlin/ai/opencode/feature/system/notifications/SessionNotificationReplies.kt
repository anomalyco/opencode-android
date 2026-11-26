package ai.opencode.feature.system.notifications

import ai.opencode.core.log.AppLog
import ai.opencode.feature.system.R
import ai.opencode.feature.system.liveactivity.LiveActivityCommands
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

/** What a needs-input notification asks and the answers it offers, built by the drawer. */
data class SessionNotificationRequest(
    val sessionID: String,
    val requestID: String,
    val isPermission: Boolean,
    val heading: String,
    val detail: String,
    val choices: List<Choice>,
    /** The inline reply's action title and hint; null when the request takes no typed answer. */
    val textReply: TextReply? = null,
) {
    data class Choice(val label: String, val value: String)
    data class TextReply(val label: String, val hint: String)
}

/** An answer given from a needs-input notification, for the drawer to send. */
data class SessionNotificationReply(
    val serverKey: String,
    val rootSessionID: String,
    val title: String,
    val sessionID: String,
    val requestID: String,
    val isPermission: Boolean,
    val value: String,
)

/** A needs-input notification's answer buttons and inline reply. */
class SessionNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return
        val ref = intent.getStringExtra(EXTRA_REF) ?: return
        val root = intent.getStringExtra(EXTRA_ROOT) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val sessionID = intent.getStringExtra(EXTRA_REQUEST_SESSION) ?: return
        val requestID = intent.getStringExtra(EXTRA_REQUEST) ?: return
        val isPermission = intent.getBooleanExtra(EXTRA_IS_PERMISSION, false)
        var value = intent.getStringExtra(EXTRA_VALUE)
        if (value == null) {
            value = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()
        }
        val serverKey = SessionNotifications.shared.destination(ref, root)?.serverKey
        if (value == null || serverKey == null) return
        val reply = SessionNotificationReply(serverKey, root, title, sessionID, requestID, isPermission, value)
        if (replies.send(reply)) return
        AppLog.app.notice { "Notification answer had no listener; showing it unsent" }
        SessionPushNotifications.postLocal(
            context, serverKey, root, title, SessionPushNotifications.STATUS_NEEDS_INPUT,
            failure = context.getString(R.string.system_session_answer_unsent),
        )
    }

    companion object {
        private const val ACTION_REPLY = "ai.opencode.mobilecode.notification.REPLY"
        private const val EXTRA_REF = "ref"
        private const val EXTRA_ROOT = "root"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_REQUEST_SESSION = "requestSession"
        private const val EXTRA_REQUEST = "request"
        private const val EXTRA_IS_PERMISSION = "isPermission"
        private const val EXTRA_VALUE = "value"
        private const val KEY_TEXT = "text"

        /** Answers on their way to the drawer; false from `send` when the app isn't listening. */
        val replies = LiveActivityCommands<SessionNotificationReply>()

        /** The notification's actions: a button per choice, then the inline reply. Each needs the phone unlocked. */
        fun actions(context: Context, ref: String, rootSessionID: String, title: String, request: SessionNotificationRequest): List<NotificationCompat.Action> {
            val actions = mutableListOf<NotificationCompat.Action>()
            for ((index, choice) in request.choices.withIndex()) {
                val intent = intent(context, ref, rootSessionID, title, request, index).putExtra(EXTRA_VALUE, choice.value)
                val pending = PendingIntent.getBroadcast(
                    context, "${request.requestID}:$index".hashCode(), intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                actions += NotificationCompat.Action.Builder(null, choice.label, pending)
                    .setAuthenticationRequired(true)
                    .setShowsUserInterface(false)
                    .build()
            }
            val textReply = request.textReply
            if (textReply != null) {
                val index = request.choices.size
                val pending = PendingIntent.getBroadcast(
                    context, "${request.requestID}:$index".hashCode(), intent(context, ref, rootSessionID, title, request, index),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val input = RemoteInput.Builder(KEY_TEXT).setLabel(textReply.hint).build()
                actions += NotificationCompat.Action.Builder(null, textReply.label, pending)
                    .addRemoteInput(input)
                    .setAllowGeneratedReplies(false)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setAuthenticationRequired(true)
                    .setShowsUserInterface(false)
                    .build()
            }
            return actions
        }

        private fun intent(context: Context, ref: String, rootSessionID: String, title: String, request: SessionNotificationRequest, index: Int): Intent =
            Intent(context, SessionNotificationActionReceiver::class.java)
                .setAction(ACTION_REPLY)
                .setData(Uri.parse("opencode-notification://reply/${request.requestID}/$index"))
                .putExtra(EXTRA_REF, ref)
                .putExtra(EXTRA_ROOT, rootSessionID)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_REQUEST_SESSION, request.sessionID)
                .putExtra(EXTRA_REQUEST, request.requestID)
                .putExtra(EXTRA_IS_PERMISSION, request.isPermission)
    }
}
