package ai.opencode.feature.system.notifications

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodePermissionReply
import ai.opencode.server.sendPromptConfirming
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/** Sends what a push notification's button, option row or typed reply says, without opening the app. */
class PushActionReceiver : BroadcastReceiver() {
    private enum class Outcome { Sent, Gone, Failed }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DISMISSED) {
            PushNotifications.removeEmptySummaries(context)
            return
        }
        val ref = intent.getStringExtra(EXTRA_REF) ?: return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val target = PushNotifications.Target(ref, key, intent.getStringExtra(EXTRA_SESSION), intent.getStringExtra(EXTRA_TITLE).orEmpty())
        val application = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                val outcome = withTimeoutOrNull(9.seconds) { send(intent, target) } ?: Outcome.Failed
                AppLog.app.info { "Push action ${intent.action?.substringAfterLast('.')} outcome=$outcome" }
                // The server also answers a reply with a clear; removing here just doesn't wait for it.
                if (outcome == Outcome.Failed) PushNotifications.showSendFailure(application, target) else PushNotifications.cancel(application, key)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun send(intent: Intent, target: PushNotifications.Target): Outcome {
        val server = SessionNotifications.shared.server(target.ref) ?: return Outcome.Failed
        val client = dependency(OpenCodeClient)
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim()
        try {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    val reply = when (intent.getStringExtra(EXTRA_DECISION)) {
                        "once" -> OpenCodePermissionReply.Once
                        "always" -> OpenCodePermissionReply.Always
                        "reject" -> OpenCodePermissionReply.Reject
                        else -> return Outcome.Failed
                    }
                    val session = intent.getStringExtra(EXTRA_REPLY_SESSION) ?: return Outcome.Failed
                    val request = intent.getStringExtra(EXTRA_REQUEST) ?: return Outcome.Failed
                    client.replyPermission(server, session, request, reply, null)
                }
                ACTION_ANSWER -> {
                    val session = intent.getStringExtra(EXTRA_REPLY_SESSION) ?: return Outcome.Failed
                    val form = intent.getStringExtra(EXTRA_FORM) ?: return Outcome.Failed
                    val field = intent.getStringExtra(EXTRA_FIELD) ?: return Outcome.Failed
                    val value = intent.getStringExtra(EXTRA_VALUE) ?: typed
                    if (value.isNullOrEmpty()) return Outcome.Failed
                    client.replyForm(server, session, form, mapOf(field to OpenCodeFormValue.Str(value)))
                }
                ACTION_REPLY -> {
                    val session = target.sessionID ?: return Outcome.Failed
                    if (typed.isNullOrEmpty()) return Outcome.Failed
                    client.sendPromptConfirming(server, session, typed, OpenCodeDelivery.Steer)
                }
                else -> return Outcome.Failed
            }
            return Outcome.Sent
        } catch (error: OpenCodeClientError) {
            // Answered somewhere else, or gone with its session.
            if (error.statusCode == 404 || error.statusCode == 409) return Outcome.Gone
            AppLog.app.notice { "Push action failed: ${error.logSummary}" }
            return Outcome.Failed
        }
    }

    companion object {
        const val ACTION_PERMISSION = "ai.opencode.push.PERMISSION"
        const val ACTION_ANSWER = "ai.opencode.push.ANSWER"
        const val ACTION_REPLY = "ai.opencode.push.REPLY"
        const val ACTION_DISMISSED = "ai.opencode.push.DISMISSED"
        const val EXTRA_REF = "ref"
        const val EXTRA_KEY = "key"
        const val EXTRA_SESSION = "session"
        const val EXTRA_TITLE = "title"
        const val EXTRA_REPLY_SESSION = "replySession"
        const val EXTRA_REQUEST = "request"
        const val EXTRA_DECISION = "decision"
        const val EXTRA_FORM = "form"
        const val EXTRA_FIELD = "field"
        const val EXTRA_VALUE = "value"
        const val KEY_TEXT = "text"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
