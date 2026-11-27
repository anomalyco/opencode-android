package ai.opencode.feature.system.notifications

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.shared
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodePermissionReply
import ai.opencode.server.retry.RetryBudget
import ai.opencode.server.sendPromptConfirming
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

/**
 * A session notification's buttons: allow or deny a permission, or reply to a finished session,
 * without opening the app. The notification shows "Sending…" until the server
 * answers, then goes; if the send fails it says so and opens the app on tap.
 */
class PushActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val envelope = intent.getStringExtra(EXTRA_ENVELOPE) ?: return
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        val index = intent.getIntExtra(EXTRA_INDEX, -1)
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                perform(app, envelope, action, index, typed)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun perform(context: Context, envelope: String, action: String, index: Int, typed: String?) {
        val data = mapOf(NotificationContent.ENVELOPE_KEY to envelope)
        val verified = NotificationContent.verifiedPayload(data) ?: return
        val shown = NotificationContent.content(data, verified) ?: return
        // The pressed button must still be one this message offers.
        val rich = RichPush.from(verified.payload, context.resources.configuration.fontScale)
        val offered = rich.actions.getOrNull(index) ?: return
        if (name(offered) != action) return
        val text = when (offered) {
            PushAction.FollowUp -> typed?.trim()?.takeIf { it.isNotEmpty() }
            else -> ""
        }
        // An empty reply: post the notification again so its reply field closes.
        val stage = if (text == null) SessionPushNotifications.Stage.Shown else SessionPushNotifications.Stage.Sending
        SessionPushNotifications.post(context, data, shown, verified, NotificationPresentation.ListOnly, stage)
        if (text == null) return
        try {
            withTimeout(budget) { send(verified, offered, text) }
            SessionPushNotifications.dismiss(context, verified) {
                SessionPushNotifications.post(context, data, shown, verified, NotificationPresentation.ListOnly, SessionPushNotifications.Stage.Sending)
            }
            AppLog.app.info { "Push action sent: $action" }
        } catch (error: TimeoutCancellationException) {
            fail(context, data, shown, verified, action, "timeout")
        } catch (error: OpenCodeClientError) {
            fail(context, data, shown, verified, action, error.statusCode?.let { "HTTP $it" } ?: "network")
        } catch (error: Failure) {
            fail(context, data, shown, verified, action, error.message.orEmpty())
        }
    }

    private fun fail(
        context: Context,
        data: Map<String, String>,
        shown: NotificationContent.Shown,
        verified: NotificationContent.Verified,
        action: String,
        reason: String,
    ) {
        AppLog.app.error { "Push action failed: $action, $reason" }
        SessionPushNotifications.post(context, data, shown, verified, NotificationPresentation.ListOnly, SessionPushNotifications.Stage.Failed)
    }

    private class Failure(message: String) : Exception(message)

    private suspend fun send(verified: NotificationContent.Verified, action: PushAction, text: String) {
        val payload = verified.payload
        val sessionID = payload.sessionID ?: throw Failure("no session")
        val destination = SessionNotifications.shared.destination(verified.ref, sessionID) ?: throw Failure("no server")
        val servers = shared(ServerSharedKeys.savedServers).value + listOfNotNull(shared(ServerSharedKeys.savedServer).value)
        val server = servers.firstOrNull { it.persistenceID == destination.serverKey } ?: throw Failure("no server")
        val client = dependency(OpenCodeClient)
        // A permission names the session that asked, which may be a subagent's.
        val asking = payload.data["sessionID"] ?: sessionID
        RetryBudget(budget).scope {
            when (action) {
                PushAction.Allow, PushAction.Deny -> {
                    val requestID = payload.data["requestID"] ?: throw Failure("no request")
                    val reply = if (action == PushAction.Allow) OpenCodePermissionReply.Once else OpenCodePermissionReply.Reject
                    client.replyPermission(server, asking, requestID, reply, null)
                }
                PushAction.FollowUp -> {
                    client.sendPromptConfirming(server, sessionID, text, OpenCodeDelivery.Queue)
                    PushSync.sentFromPhone(server, sessionID)
                }
                PushAction.Review -> Unit
            }
        }
    }

    companion object {
        /** The RemoteInput result's key. */
        const val KEY_TEXT = "opencode.reply"
        private const val EXTRA_ENVELOPE = "opencode.envelope"
        private const val EXTRA_ACTION = "opencode.action"
        private const val EXTRA_INDEX = "opencode.index"

        private val budget = 20.seconds
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun name(action: PushAction): String = when (action) {
            PushAction.Allow -> "allow"
            PushAction.Review -> "review"
            PushAction.Deny -> "deny"
            PushAction.FollowUp -> "reply"
        }

        /** The broadcast for button [index] of the notification posted under [tag]. */
        internal fun intent(context: Context, envelope: String, tag: String, action: PushAction, index: Int): Intent =
            Intent(context, PushActionReceiver::class.java)
                .setData(Uri.parse("opencode-push://action/" + Uri.encode(tag) + "/" + name(action) + "/" + index))
                .putExtra(EXTRA_ENVELOPE, envelope)
                .putExtra(EXTRA_ACTION, name(action))
                .putExtra(EXTRA_INDEX, index)

    }
}
