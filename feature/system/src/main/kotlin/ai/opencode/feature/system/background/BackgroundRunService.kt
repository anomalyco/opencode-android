package ai.opencode.feature.system.background

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.feature.system.R
import ai.opencode.feature.system.notifications.SystemNotificationChannels
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stay Connected During Runs (Experimental): keeps the process, and with it Home's event stream,
 * running while an agent runs and the app is away. Home decides when it runs; this only holds.
 */
class BackgroundRunService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            running.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            // A start sent after this stop keeps the service.
            stopSelf(startId)
            AppLog.stream.info("background_run_service_stopped")
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            running.value = true
            AppLog.stream.info("background_run_service_started")
        } catch (error: Exception) {
            AppLog.stream.notice("background_run_service_failed error=${error.javaClass.simpleName}")
            end()
        }
        return START_NOT_STICKY
    }

    /** The day's six hours of `dataSync` are used up. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        AppLog.stream.notice("background_run_service_timed_out")
        end()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        end()
    }

    override fun onDestroy() {
        running.value = false
        super.onDestroy()
    }

    /** Ended here rather than asked by Home: Home's next start may bring it back. */
    private fun end() {
        synchronized(lock) { isRequested = false }
        running.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        SystemNotificationChannels.ensure(this)
        val builder = Notification.Builder(this, SystemNotificationChannels.BACKGROUND_RUNS)
            .setSmallIcon(R.drawable.ic_opencode_mark)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentTitle("OpenCode")
            .setContentText(getString(R.string.system_background_runs_notification_text))
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            builder.setContentIntent(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        return builder.build()
    }

    companion object {
        private const val NOTIFICATION_ID = 4104
        private const val STOP = "ai.opencode.background-run.STOP"

        private val lock = Any()
        private var isRequested = false
        private val running = MutableStateFlow(false)

        /** Call while the app is on screen: Android refuses a foreground service started from the background. */
        internal fun start(context: Context) {
            synchronized(lock) {
                if (isRequested) return
                try {
                    context.startForegroundService(Intent(context, BackgroundRunService::class.java))
                    isRequested = true
                } catch (error: Exception) {
                    AppLog.stream.notice("background_run_service_failed error=${error.javaClass.simpleName}")
                }
            }
        }

        /** In order after [start], so it always follows its `startForeground`. */
        internal fun stop(context: Context) {
            synchronized(lock) {
                if (!isRequested) return
                isRequested = false
                try {
                    context.startService(Intent(context, BackgroundRunService::class.java).setAction(STOP))
                } catch (_: Exception) {
                    context.stopService(Intent(context, BackgroundRunService::class.java))
                }
            }
        }

        internal val isRunning: Flow<Boolean> = running.asStateFlow()
    }
}

class BackgroundRunServiceClient(
    val start: () -> Unit,
    val stop: () -> Unit,
    /** Whether the service holds the app now; it can end on its own (the day's limit, the app swiped away). */
    val isRunning: () -> Flow<Boolean>,
) {
    companion object Key : DependencyKey<BackgroundRunServiceClient> {
        override val liveValue: BackgroundRunServiceClient by lazy {
            val context = dependency(AppContext).applicationContext
            BackgroundRunServiceClient(
                start = { BackgroundRunService.start(context) },
                stop = { BackgroundRunService.stop(context) },
                isRunning = { BackgroundRunService.isRunning },
            )
        }

        override val testValue = BackgroundRunServiceClient(start = {}, stop = {}, isRunning = { emptyFlow() })
    }
}
