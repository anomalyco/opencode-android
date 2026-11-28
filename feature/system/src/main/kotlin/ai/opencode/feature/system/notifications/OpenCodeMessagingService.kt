package ai.opencode.feature.system.notifications

import ai.opencode.core.log.AppLog
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM, the push channel behind [PushTokenProvider]. Firebase's init provider makes the default
 * `FirebaseApp` from the `google_app_id`, `gcm_defaultSenderId`, `google_api_key` and
 * `project_id` string resources in `:app`; a build without them has no `FirebaseApp`, so
 * [FcmPushTokenProvider] fails at once and this service never runs.
 *
 * The relay sends a data-only, high-priority FCM message, so the app builds the notification
 * itself ([SessionPushNotifications]) whether it runs or not.
 */
class OpenCodeMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        SessionNotifications.shared.didRegister(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        SessionPushNotifications.handle(applicationContext, message.data)
    }
}

/** The FCM token, when the build carries a Firebase configuration. */
class FcmPushTokenProvider(private val context: Context) : PushTokenProvider {
    override fun register(onToken: (String) -> Unit, onFailure: () -> Unit) {
        if (!isConfigured(context)) return onFailure()
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token -> if (token.isNullOrEmpty()) onFailure() else onToken(token) }
                .addOnFailureListener {
                    AppLog.app.debug("Push token unavailable")
                    onFailure()
                }
        }.onFailure { onFailure() }
    }

    companion object {
        /** Whether the build carries a Firebase configuration, so FCM can mint tokens. */
        fun isConfigured(context: Context): Boolean =
            runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)
    }
}
