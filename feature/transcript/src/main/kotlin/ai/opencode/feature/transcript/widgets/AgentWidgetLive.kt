package ai.opencode.feature.transcript.widgets

import ai.opencode.core.log.AppLog
import ai.opencode.server.model.OpenCodeWidgetMessageValue
import ai.opencode.server.model.OpenCodeWidgetResponse
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One open live widget's program (specs/v2/widgets-live.md): started when the widget first shows,
 * paused while it's out of view or the app is in the background (a paused run costs no CPU), and
 * after 30 s paused snapshotted to free its VM. What the program posts is only queued on its thread,
 * read on a background thread and handed to [events] on the main thread through [scope].
 */
internal class AgentWidgetLiveDriver(
    private val program: AgentWidgetLiveProgram,
    private val data: String,
    private val httpsHosts: List<String>?,
    private val scope: CoroutineScope,
    private val events: Events,
) {
    interface Events {
        fun rendered(view: AgentWidgetNode, fullscreen: Boolean?)
        fun drew(draw: AgentWidgetWire.Post.Draw)
        /** The program asked for a display frame: called on the run's thread, the only event that isn't main. */
        fun wantsFrame()
        fun answered(response: OpenCodeWidgetResponse)
        fun sent(message: OpenCodeWidgetMessageValue)
        fun failed(error: AgentWidgetEngineException)
        /** A post that couldn't be used (an invalid view), or a fetch that failed: reported, the run goes on. */
        fun reported(phase: String, message: String)
        /** A post that wasn't the format (a client error): dropped. */
        fun malformed(message: String)
        /** `main` returned without an answer: the last view stays. */
        fun ended()
        /** A GPU canvas's state ([key] null: every GPU canvas of the run). */
        fun gpuCanvas(key: String?, state: AgentWidgetGpuState) = Unit
    }

    @Volatile private var run: AgentWidgetLiveRun? = null
    private var visible = false
    private var paused = false
    private var pausedSince = 0L
    private var snapshotJob: Job? = null
    /** The run was dropped while paused (a snapshot that couldn't be taken): start over when shown. */
    private var restartWhenShown = false
    @Volatile private var finished = false
    @Volatile private var generation = 0

    val isRunning: Boolean get() = run != null && !finished

    fun setVisible(shown: Boolean) {
        if (shown == visible) return
        visible = shown
        if (shown) show() else hide()
    }

    /** A message for the program (an event or a frame); held by the runner while paused. Any thread. */
    fun deliver(messageJson: String) {
        if (!finished) run?.deliver(messageJson)
    }

    /** Starts over from `main` (Restart after an error). */
    fun restart() {
        stopRun()
        finished = false
        restartWhenShown = false
        if (visible) start()
    }

    fun stop() {
        finished = true
        stopRun()
    }

    private fun show() {
        snapshotJob?.cancel()
        snapshotJob = null
        when {
            finished -> Unit
            run == null || restartWhenShown -> {
                restartWhenShown = false
                start()
            }
            paused -> {
                paused = false
                AppLog.perf.debug { "Live widget resumed after ${(System.nanoTime() - pausedSince) / 1_000_000} ms" }
                run?.resume()
                // A run restored from its snapshot presents again into the surfaces it had.
                attachAll()
            }
        }
    }

    private fun hide() {
        val run = run ?: return
        if (finished || paused) return
        paused = true
        pausedSince = System.nanoTime()
        run.pause()
        snapshotJob = scope.launch {
            delay(SNAPSHOT_AFTER_MILLIS)
            if (!paused || this@AgentWidgetLiveDriver.run !== run) return@launch
            if (!run.snapshot()) {
                // A fetch was in flight: stop now and start over from `main` when shown again.
                stopRun()
                restartWhenShown = true
            }
        }
    }

    private fun start() {
        val current = ++generation
        paused = false
        val listener = object : AgentWidgetLiveListener {
            override fun posted(messages: List<ByteArray>) {
                // A frame request goes to the frame thread from here, never through the main thread.
                if (current == generation && messages.any(AgentWidgetWire::isFrame)) events.wantsFrame()
                inbox.trySend(Batch(current, messages))
            }

            override fun warned(message: String) {
                // A fetch the runtime denied, as the program got it.
                if (message.startsWith("CapabilityDenied")) {
                    scope.launch { if (current == generation) events.reported("fetch", message) }
                } else {
                    AppLog.app.notice { "Live widget: ${message.take(160)}" }
                }
            }

            override fun fetchFailed(message: String) {
                scope.launch { if (current == generation) events.reported("fetch", message) }
            }

            override fun gpuCanvas(key: String?, state: AgentWidgetGpuState) {
                scope.launch { if (current == generation) gpuCanvasChanged(key, state) }
            }

            override fun exited(failure: AgentWidgetEngineException?) {
                inbox.trySend(Exit(current, failure))
            }
        }
        run = try {
            program.start("{\"data\":$data}", listener, httpsHosts)
        } catch (error: AgentWidgetEngineException) {
            finished = true
            events.failed(error)
            null
        }
        attachAll()
    }

    private fun stopRun() {
        generation++
        snapshotJob?.cancel()
        snapshotJob = null
        run?.let { run -> surfaces.keys.forEach(run::detachSurface) }
        run?.stop()
        run = null
        paused = false
        surfaceFailed.clear()
    }

    private class Attached(val surface: AgentWidgetGpuSurface, var width: Int, var height: Int)

    /**
     * The GPU canvases' surfaces by key, attached to whichever run is current and kept across restarts. A key can have
     * several (the inline row and full screen): the newest presents, and the one under it takes over when it goes.
     */
    private val surfaces = HashMap<String, ArrayList<Attached>>()

    /** Presents the GPU canvas [key] into [surface] (again on a new size). Main thread; never blocks. */
    fun attachSurface(key: String, surface: AgentWidgetGpuSurface, width: Int, height: Int) {
        val list = surfaces.getOrPut(key) { ArrayList() }
        val known = list.firstOrNull { it.surface === surface }
        if (known != null) {
            known.width = width
            known.height = height
            if (known !== list.last()) return
        } else {
            list += Attached(surface, width, height)
        }
        if (finished) return
        run?.attachSurface(key, surface, width, height)
        if (known == null) presentsAgain(key)
    }

    /** Before [surface] goes: once this returns, no run touches it again. */
    fun detachSurface(key: String, surface: AgentWidgetGpuSurface) {
        val list = surfaces[key] ?: return
        val top = list.lastOrNull()
        if (!list.removeAll { it.surface === surface }) return
        if (list.isEmpty()) surfaces.remove(key)
        if (top?.surface !== surface) return
        // A new surface applies only at the next present, so the old one is detached (waited for) first.
        val run = run ?: return
        run.detachSurface(key)
        val next = list.lastOrNull()
        if (next != null && !finished) {
            run.attachSurface(key, next.surface, next.width, next.height)
            presentsAgain(key)
        }
    }

    /** GPU canvases whose surface failed: the runtime dropped it and presents again once another is attached. */
    private val surfaceFailed = HashSet<String>()

    /** Each GPU canvas's last told opacity (the runtime tells it only on a change). */
    private val liveOpaque = HashMap<String, Boolean>()

    private fun gpuCanvasChanged(key: String?, state: AgentWidgetGpuState) {
        // The runtime's surface failures all start so ("the surface was lost", "the surface failed: …").
        if (state is AgentWidgetGpuState.Live) {
            if (key != null) liveOpaque[key] = state.opaque else liveOpaque.keys.forEach { liveOpaque[it] = state.opaque }
        }
        val surface = key != null && state is AgentWidgetGpuState.Stopped && !state.program && state.message.startsWith("the surface")
        if (surface) {
            surfaceFailed += key!!
            if (surfaces[key]?.lastOrNull()?.surface?.isValid?.invoke() == true) {
                AgentWidgetDiagnostics.log { "GPU canvas $key: the surface failed while its Surface is still valid" }
            }
        } else if (key != null) {
            surfaceFailed -= key
        } else {
            surfaceFailed.clear()
        }
        events.gpuCanvas(key, state)
    }

    /** A new surface presents for [key]: a canvas stopped only by its old surface's failure is live again. */
    private fun presentsAgain(key: String) {
        if (surfaceFailed.remove(key)) events.gpuCanvas(key, AgentWidgetGpuState.Live(liveOpaque[key] ?: false))
    }

    private fun attachAll() {
        val run = run ?: return
        for ((key, list) in surfaces) list.lastOrNull()?.let { run.attachSurface(key, it.surface, it.width, it.height) }
    }

    private sealed interface Inbound { val generation: Int }
    private class Batch(override val generation: Int, val messages: List<ByteArray>) : Inbound
    private class Exit(override val generation: Int, val failure: AgentWidgetEngineException?) : Inbound

    /**
     * The run's posts and its exit, in the order it made them: its thread only enqueues them. An exit is handled after
     * the posts before it, so a submit that ends the run lands before the run's end does.
     */
    private val inbox = Channel<Inbound>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.Default) {
            val waiting = ArrayList<Inbound>()
            for (first in inbox) {
                waiting += first
                while (true) waiting += inbox.tryReceive().getOrNull() ?: break
                var start = 0
                while (start < waiting.size) {
                    val item = waiting[start]
                    if (item is Exit) {
                        scope.launch { exited(item.generation, item.failure) }
                        start++
                        continue
                    }
                    var end = start
                    while (end < waiting.size && (waiting[end] as? Batch)?.generation == item.generation) end++
                    val actions = read(waiting.subList(start, end).flatMap { (it as Batch).messages })
                    if (actions.isNotEmpty()) scope.launch { if (item.generation == generation) actions.forEach { it() } }
                    start = end
                }
                waiting.clear()
            }
        }
    }

    private fun exited(current: Int, failure: AgentWidgetEngineException?) {
        if (current != generation) return
        run?.let { run -> surfaces.keys.forEach(run::detachSurface) }
        run = null
        if (finished) return
        finished = true
        AppLog.perf.info { "Live widget exited: ${failure?.message ?: "finished"}" }
        if (failure != null) events.failed(failure) else events.ended()
    }

    /** A submit or dismiss is final: the run is stopped, and how it then ends isn't reported. */
    private fun answered(response: OpenCodeWidgetResponse) {
        stop()
        events.answered(response)
    }

    /**
     * Posts in order: what to do with each on the main thread. When posts piled up, only the last `render` and each
     * canvas's last `draw` are read; the rest keep their order. A post that isn't the format is dropped.
     */
    private fun read(messages: List<ByteArray>): List<() -> Unit> {
        val kinds = messages.map(AgentWidgetWire::kind)
        val lastRender = kinds.lastIndexOf(AgentWidgetWire.RENDER)
        val lastDraw = HashMap<String, Int>()
        val ids = arrayOfNulls<String>(messages.size)
        messages.forEachIndexed { index, m ->
            if (kinds[index] == AgentWidgetWire.DRAW) AgentWidgetWire.drawId(m)?.let { ids[index] = it; lastDraw[it] = index }
        }
        return messages.mapIndexedNotNull { index, m ->
            when (kinds[index]) {
                AgentWidgetWire.RENDER -> if (index != lastRender) return@mapIndexedNotNull null
                AgentWidgetWire.DRAW -> if (ids[index] != null && lastDraw[ids[index]] != index) return@mapIndexedNotNull null
            }
            read(m)
        }
    }

    /** One post: what to do with it on the main thread, or null to drop it. */
    private fun read(message: ByteArray): (() -> Unit)? {
        val post = try {
            AgentWidgetWire.post(message)
        } catch (error: AgentWidgetWire.Malformed) {
            val text = error.message ?: "malformed post"
            return { events.malformed(text) }
        } catch (error: AgentWidgetNode.InvalidView) {
            AppLog.app.notice { "Live widget render dropped: ${error.message}" }
            val text = error.message ?: "invalid view"
            return { events.reported("view", text) }
        }
        return when (post) {
            is AgentWidgetWire.Post.Render -> ({ events.rendered(post.view, post.fullscreen) })
            is AgentWidgetWire.Post.Draw -> ({ events.drew(post) })
            AgentWidgetWire.Post.Frame -> null
            is AgentWidgetWire.Post.Submit -> ({ answered(OpenCodeWidgetResponse.Submit(post.value)) })
            AgentWidgetWire.Post.Dismiss -> ({ answered(OpenCodeWidgetResponse.Dismiss) })
            is AgentWidgetWire.Post.Send -> ({ events.sent(OpenCodeWidgetMessageValue(post.value, post.wake)) })
            is AgentWidgetWire.Post.Capture, AgentWidgetWire.Post.Gpu -> null
        }
    }

    private companion object {
        const val SNAPSHOT_AFTER_MILLIS = 30_000L
    }
}

/** A live canvas's size (logical px) and pointer, which the frames the program asks for carry. */
internal class AgentWidgetLiveCanvas {
    @Volatile var width = 0f
    @Volatile var height = 0f
    /** Where it is in its window (px), for the full-screen close area over it. */
    @Volatile var bounds: Rect? = null
    @Volatile var pointer: FloatArray? = null
}
