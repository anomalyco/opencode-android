package ai.opencode.feature.transcript.widgets

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer

/**
 * The thread live programs' frames go out on: a looper of its own with its own Choreographer, so a frame request and
 * its delivery never wait behind the main thread's work. Views and HUD updates stay on the main thread.
 */
internal object AgentWidgetFrameThread {
    private val handler: Handler by lazy {
        val thread = HandlerThread("widget-frames", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        Handler(thread.looper).also { it.post { choreographer = Choreographer.getInstance() } }
    }

    @Volatile private var choreographer: Choreographer? = null

    /** Runs [callback] on the frame thread at its next vsync. Any thread. */
    fun post(callback: Choreographer.FrameCallback) {
        val handler = handler
        choreographer?.postFrameCallback(callback) ?: handler.post { checkNotNull(choreographer).postFrameCallback(callback) }
    }
}
