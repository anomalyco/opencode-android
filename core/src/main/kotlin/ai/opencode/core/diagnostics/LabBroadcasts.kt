package ai.opencode.core.diagnostics

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.tuning.InternalBuild
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Port of iOS `LabDarwinNotifications`, as broadcasts: `adb shell am broadcast -p <app id> -a <app
 * id>.<name>`. Debug only; exported because `adb`'s shell user counts as another app.
 */
object LabBroadcasts {
    private val handlers = HashMap<String, (String) -> Unit>()
    private val posted = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** What [forward] reposts (iOS's `NotificationCenter.default` names), for in-process observers to collect. */
    val notifications: SharedFlow<String> = posted.asSharedFlow()

    /**
     * Runs [handler] on the main thread, with the name, each time one of [names] is broadcast.
     * Observing a name again replaces its handler.
     */
    fun observe(names: List<String>, handler: (String) -> Unit) {
        if (!InternalBuild.isDebug) return
        val context = runCatching { dependency(AppContext) }.getOrNull() ?: return
        val prefix = context.packageName + "."
        val added = synchronized(handlers) { names.filter { handlers.put(it, handler) == null } }
        if (added.isEmpty()) return
        val filter = IntentFilter().apply { for (name in added) addAction(prefix + name) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val name = intent.action?.removePrefix(prefix) ?: return
                val handler = synchronized(handlers) { handlers[name] } ?: return
                handler(name)
            }
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    }

    /** Reposts each of [names] in-process as [notification] of its name, on [notifications]. */
    fun forward(names: List<String>, notification: (String) -> String) {
        observe(names) { posted.tryEmit(notification(it)) }
    }
}
