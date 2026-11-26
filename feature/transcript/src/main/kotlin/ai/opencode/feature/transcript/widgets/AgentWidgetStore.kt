package ai.opencode.feature.transcript.widgets

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import android.view.Choreographer
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.strings.localized
import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.Effect
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.Event
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.Phase
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.RespondFailure
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.Status
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeWidgetError
import ai.opencode.server.model.OpenCodeWidgetFailure
import ai.opencode.server.model.OpenCodeWidgetMessageValue
import ai.opencode.server.model.OpenCodeWidgetProgram
import ai.opencode.server.model.OpenCodeWidgetResponse
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The transcript list's widgets: one [AgentWidgetController] per widget message, kept while the
 * list is, so a widget scrolled away and back keeps its local state.
 */
class AgentWidgetStore(
    private val programs: AgentWidgetPrograms = dependency(AgentWidgetPrograms),
    private val transport: AgentWidgetTransport = dependency(AgentWidgetTransport),
    private val engine: AgentWidgetEngine = dependency(AgentWidgetEngine),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controllers = HashMap<String, AgentWidgetController>()

    fun controller(widget: AgentWidgetState, server: OpenCodeServer, sessionID: String, directory: String?): AgentWidgetController {
        val key = "${server.persistenceID}/$sessionID/${widget.key}"
        return controllers.getOrPut(key) { AgentWidgetController(widget, server, sessionID, directory, scope, programs, transport, engine).also { it.store = this } }
    }

    /** The one widget shown full screen, if any; its inline row shows a placeholder meanwhile. */
    var fullscreen: AgentWidgetFullscreen? by mutableStateOf(null)
        private set

    /** Shows [controller]'s widget full screen (closing another one first) and tells its program. */
    fun enterFullscreen(controller: AgentWidgetController, title: String) {
        if (fullscreen?.controller === controller) return
        fullscreen?.controller?.let {
            it.shownChanged(fullscreen = false)
            it.fullscreenChanged(false)
        }
        fullscreen = AgentWidgetFullscreen(controller, title)
        controller.shownChanged(fullscreen = true)
    }

    fun exitFullscreen() {
        val current = fullscreen ?: return
        fullscreen = null
        current.controller.shownChanged(fullscreen = false)
        current.controller.fullscreenChanged(false)
    }

    fun close() {
        fullscreen = null
        for (controller in controllers.values) controller.stopLive()
        scope.cancel()
        controllers.clear()
    }
}

val LocalAgentWidgetStore = staticCompositionLocalOf<AgentWidgetStore?> { null }

/** A widget open full screen: its controller and the title its cover is named by. */
@Stable
class AgentWidgetFullscreen(val controller: AgentWidgetController, val title: String)

/** True inside a full-screen widget: `fill` canvases grow, frames say `fullscreen: true`. */
val LocalAgentWidgetFullscreen = staticCompositionLocalOf { false }

/** False inside a stack's children: there only the stack grows in full screen, and its `fill` children with it. */
internal val LocalAgentWidgetGrows = staticCompositionLocalOf { true }

/** True while the list isn't on screen (its lifecycle is below resumed): shaders stop rendering. */
val LocalAgentWidgetPaused = androidx.compose.runtime.compositionLocalOf { false }

/** Runs one widget's machine: fetches and compiles its widget template, steps it off the main thread, and answers it once. */
@Stable
class AgentWidgetController internal constructor(
    widget: AgentWidgetState,
    private val server: OpenCodeServer,
    private val sessionID: String,
    private val directory: String?,
    private val scope: CoroutineScope,
    private val programs: AgentWidgetPrograms,
    private val transport: AgentWidgetTransport,
    val engine: AgentWidgetEngine,
) {
    val key: String = widget.key
    val data: String = widget.data
    private val ref = widget.widgetTemplate
    private var widget = widget
    private var machine: AgentWidgetMachine.Machine
    private var pendingEffects: List<Effect>
    private var started = false
    private var program: AgentWidgetProgram? = null
    private val lane = AgentWidgetLane.make()
    private val queued = ArrayDeque<AgentWidgetEvent>()

    var phase: Phase by mutableStateOf(Phase.Loading(status(widget)))
        private set

    /** The current view's root `fullscreen`: true offers full screen, false never, null decides by content. */
    var rootFullscreen: Boolean? by mutableStateOf(null)
        private set

    /** Whether the widget offers full screen now: it's open and its view asks for it or draws pixels. */
    val offersFullscreen: Boolean
        get() {
            val phase = phase
            if (phase !is Phase.Ready && phase !is Phase.Stepping) return false
            return rootFullscreen ?: (isLiveMode || phase.view?.let(AgentWidgetNode::drawsPixels) == true)
        }

    /** The inline frame's last height in px, kept by its placeholder while the widget is full screen. */
    var inlineHeight: Int = 0

    /** When each canvas or shader first drew (frame nanos), so moving to and from full screen restarts no clock. */
    private val starts = HashMap<String, Long>()

    fun startNanos(key: String, now: Long): Long = starts.getOrPut(key) { now }

    // Full screen (widgets-fullscreen.md, Telling the program): the root's bounds in the full-screen window's px, and
    // the close area the program was last told. The root sits FULLSCREEN_PADDING inside the safe area and the button
    // CLOSE_INSET from the safe area's top and end, so its area follows from the root alone and moves only with the
    // root's width.
    @Volatile private var rootInWindow: Rect? = null
    @Volatile private var layoutDensity = 1f
    @Volatile private var rtl = false
    private var toldClose: AgentWidgetRect? = null

    /** The full-screen root node's bounds (laid out, or a new size); [rtl]: the button is at the left. */
    internal fun fullscreenRoot(bounds: Rect, density: Float, rtl: Boolean) {
        rootInWindow = bounds
        layoutDensity = density
        this.rtl = rtl
        tellFullscreen()
    }

    /** The close button's hit area in the window, while full screen. */
    private val closeInWindow: Rect?
        get() {
            val root = rootInWindow ?: return null
            val area = closeArea(root.width / layoutDensity, rtl)
            val left = root.left + area.x * layoutDensity
            val top = root.top + area.y * layoutDensity
            return Rect(left, top, left + area.width * layoutDensity, top + area.height * layoutDensity)
        }

    /** The close button's area in a node's coordinates when it overlaps [bounds] (full screen only), else null. */
    internal fun closeOver(bounds: Rect?): AgentWidgetRect? {
        val close = closeInWindow ?: return null
        if (bounds == null || !close.overlaps(bounds)) return null
        return AgentWidgetRect(
            (close.left - bounds.left) / layoutDensity,
            (close.top - bounds.top) / layoutDensity,
            CLOSE_AREA.value,
            CLOSE_AREA.value,
        )
    }

    /** Enter (once laid out) and each root width change: `fullscreen: true` with the close area in root coordinates. */
    private fun tellFullscreen() {
        if (store?.fullscreen?.controller !== this) return
        val root = rootInWindow ?: return
        val area = closeArea(root.width / layoutDensity, rtl)
        if (area == toldClose) return
        toldClose = area
        if (isLiveMode || status(widget) is Status.Open) send(AgentWidgetEvent.fullscreen(true, area))
    }

    /** Left full screen: `fullscreen: false, close: null`, when the program was told it entered. */
    internal fun fullscreenChanged(on: Boolean) {
        if (on) return
        rootInWindow = null
        val told = toldClose != null
        toldClose = null
        if (told && (isLiveMode || status(widget) is Status.Open)) send(AgentWidgetEvent.fullscreen(false, null))
    }

    /** The store that shows this widget full screen. */
    internal var store: AgentWidgetStore? = null

    /** The last event or init step's time on the lane, for the perf log. */
    @Volatile var lastStepMicros: Long = 0
        private set

    init {
        val (opened, effects) = AgentWidgetMachine.open(widget.data, status(widget))
        machine = opened
        pendingEffects = effects
    }

    fun start() {
        if (started) return
        started = true
        val effects = pendingEffects
        pendingEffects = emptyList()
        run(effects)
    }

    /** The program error the server recorded (from any client), while the widget is open. */
    var failure: OpenCodeWidgetFailure? by mutableStateOf(openFailure(widget))
        private set

    /** Ask Agent to Fix It went out for [failure]; shown as told until the server says so too. */
    var askedAgent: Boolean by mutableStateOf(false)
        private set

    /** Sends [failure] to the agent in the widget's session (`report` with `ask`), once. */
    fun askAgentToFix() {
        val failure = failure ?: return
        if (failure.sent || askedAgent) return
        askedAgent = true
        scope.launch {
            try {
                transport.report(server, directory, sessionID, widget, failure.error, true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.app.notice { "Widget ${ref.name} ask to fix didn't go: ${error.message?.take(80)}" }
                askedAgent = false
            }
        }
    }

    /** The widget as the server now reports it. */
    fun update(widget: AgentWidgetState) {
        this.widget = widget
        val recorded = openFailure(widget)
        if (recorded?.error != failure?.error) askedAgent = false
        failure = recorded
        val status = status(widget)
        if (isLiveMode) {
            if (status is Status.Answered) closeLive(status.how)
            return
        }
        if (status != Status.Open) feed(Event.StatusChanged(status))
    }

    /** Text typed into an input and not sent yet: it goes before the next event, or when it rests. */
    private val drafts = LinkedHashMap<String, String>()

    fun draft(id: String, text: String) {
        drafts[id] = text
    }

    fun hasDraft(id: String): Boolean = id in drafts

    /** Sends input [id]'s typing now (it rested, lost focus, or Done). */
    fun commit(id: String) {
        val text = drafts.remove(id) ?: return
        dispatch(AgentWidgetEvent.change(id, text))
    }

    /** What the user did, after any typing not yet sent, so its step sees what was typed. */
    fun send(event: AgentWidgetEvent) {
        if (drafts.isNotEmpty()) {
            for (id in drafts.keys.toList()) if (id != event.id) commit(id)
            drafts.remove(event.id)
        }
        dispatch(event)
    }

    /** An event while the previous one still steps waits for it. */
    private fun dispatch(event: AgentWidgetEvent) {
        if (isLiveMode) {
            if (isLive) live?.deliver("{\"type\":\"event\",\"event\":${event.json}}")
            return
        }
        when (phase) {
            is Phase.Ready -> feed(Event.Input(event))
            is Phase.Stepping -> {
                val last = queued.lastOrNull()
                if (last != null && last.replaceable(event)) {
                    queued[queued.lastIndex] = event
                } else {
                    queued.addLast(event)
                }
            }
            else -> Unit
        }
    }

    fun retry() {
        synchronized(diagnosed) { diagnosed.clear() }
        devNotice = null
        val live = live
        if (isLiveMode && live != null) {
            phase = Phase.Starting(Status.Open)
            gpuStates.clear()
            gpuAll = null
            live.restart()
        } else {
            feed(Event.Retry)
        }
    }

    /** It takes input now: a step widget that's ready or stepping, a live one whose program runs. */
    val isLive: Boolean
        get() = if (isLiveMode) phase is Phase.Ready && live?.isRunning == true else phase is Phase.Ready || phase is Phase.Stepping

    // Live mode (specs/v2/widgets-live.md).

    /** A live widget template: its program runs on its own and posts views, draws and answers. */
    var isLiveMode: Boolean by mutableStateOf(false)
        private set

    /** The hosts this live widget may fetch from, granted when it started (shown on the widget). */
    var httpsHosts: List<String>? by mutableStateOf(null)
        private set

    /** Each canvas's last posted commands (JSON array text), by canvas id. */
    internal val liveDraws = mutableStateMapOf<String, AgentWidgetWire.Post.Draw>()

    /** The program asked for one display frame. */
    private val liveWantsFrame = AtomicBoolean(false)

    /** What the shown widget's frames carry from its composition; null while no frames can go. */
    internal class LiveFrameParams(val scale: Float, val dark: Boolean, val fullscreen: Boolean)

    @Volatile private var liveFrameParams: LiveFrameParams? = null
    private val liveFramePosted = AtomicBoolean(false)
    private val liveFrameCallback = Choreographer.FrameCallback { nanos ->
        liveFramePosted.set(false)
        deliverLiveFrame(nanos)
    }

    /** The shown widget's frame parameters; a frame waiting for them goes now. */
    internal fun liveFrames(params: LiveFrameParams) {
        liveFrameParams = params
        scheduleLiveFrame()
    }

    /** [params] no longer show (unless others replaced them). */
    internal fun liveFramesGone(params: LiveFrameParams) {
        if (liveFrameParams === params) liveFrameParams = null
    }

    /** At the frame thread's next vsync, when a frame is wanted and can go. Any thread. */
    private fun scheduleLiveFrame() {
        if (!liveWantsFrame.get() || !isShown || liveFrameParams == null) return
        if (liveFramePosted.compareAndSet(false, true)) AgentWidgetFrameThread.post(liveFrameCallback)
    }

    /** Live canvases by id: their size and pointer go into the frames the program asks for. */
    internal val liveCanvases = ConcurrentHashMap<String, AgentWidgetLiveCanvas>()

    /** The widget's own size (logical px), for frames when the view has no canvas. */
    @Volatile internal var liveViewWidth = 0f
    @Volatile internal var liveViewHeight = 0f

    @Volatile private var live: AgentWidgetLiveDriver? = null

    // GPU canvases (widgets-gpu.md): each one's state by key, and the state the run gave every canvas at once.
    private val gpuStates = mutableStateMapOf<String, AgentWidgetGpuState>()
    private var gpuAll: AgentWidgetGpuState? by mutableStateOf(null)

    /** The GPU canvas [key]'s state: null until the runtime says (the surface shows meanwhile). */
    internal fun gpuState(key: String): AgentWidgetGpuState? = gpuStates[key] ?: gpuAll

    /** Presents the GPU canvas [key] into [surface] ([width] × [height] physical pixels); again on a new size. */
    internal fun attachGpuSurface(key: String, surface: AgentWidgetGpuSurface, width: Int, height: Int) {
        live?.attachSurface(key, surface, width, height)
    }

    /** Before [surface] goes: once this returns, the run never touches it again. */
    internal fun detachGpuSurface(key: String, surface: AgentWidgetGpuSurface) {
        live?.detachSurface(key, surface)
    }
    private var liveStartNanos = 0L
    private var lastFrameNanos = 0L
    @Volatile private var shownInline = false
    @Volatile private var shownFullscreen = false
    @Volatile private var listPaused = false

    private fun startLiveMode(program: AgentWidgetLiveProgram, declaredHosts: List<String>?) {
        isLiveMode = true
        val status = status(widget)
        if (status is Status.Answered) {
            phase = Phase.Closed(null, status.how)
            return
        }
        val hosts = AgentWidgetCapabilities.grantedHosts(widget.httpsHosts ?: declaredHosts)
        httpsHosts = hosts
        phase = Phase.Starting(Status.Open)
        live = AgentWidgetLiveDriver(program, data, hosts, scope, liveEvents).also { it.setVisible(isShown) }
    }

    private val liveEvents = object : AgentWidgetLiveDriver.Events {
        override fun rendered(view: AgentWidgetNode, fullscreen: Boolean?) {
            rootFullscreen = fullscreen
            when (val current = phase) {
                is Phase.Starting, is Phase.Ready -> phase = Phase.Ready("null", view, (current as? Phase.Ready)?.error)
                // The run's first views were invalid (shown as the error); a valid one replaces it.
                is Phase.Failed -> if (live != null) phase = Phase.Ready("null", view, null)
                else -> Unit
            }
        }

        override fun drew(draw: AgentWidgetWire.Post.Draw) {
            liveDraws[draw.id] = draw
        }

        override fun wantsFrame() {
            liveWantsFrame.set(true)
            scheduleLiveFrame()
        }

        override fun answered(response: OpenCodeWidgetResponse) {
            val view = phase.view ?: return closeLive(AgentWidgetMachine.Closed.of(response))
            live?.stop()
            val attempt = ++liveAttempts
            phase = Phase.Responding("null", view, response, attempt)
            scope.launch {
                try {
                    transport.respond(server, directory, sessionID, widget, response)
                    closeLive(AgentWidgetMachine.Closed.of(response))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: AgentWidgetTransportException) {
                    phase = if (error.isClosed) {
                        Phase.Ready("null", view, localized(R.string.transcript_widget_already_answered))
                    } else {
                        Phase.Ready("null", view, error.message ?: localized(R.string.transcript_widget_step_failed))
                    }
                }
            }
        }

        override fun sent(message: OpenCodeWidgetMessageValue) {
            run(listOf(Effect.Send(message)))
        }

        override fun failed(error: AgentWidgetEngineException) {
            phase = Phase.Failed(AgentWidgetMachine.message(error), Status.Open, AgentWidgetMachine.retryable(error))
            reportError(error.phase ?: "main", error)
        }

        override fun reported(phase: String, message: String) {
            // A failed fetch is the program's to handle (it gets the rejection): never the widget template's error here.
            if (phase == "fetch") clientError(phase, message) else reportError(phase, message)
            // A run whose views are all invalid would otherwise stay on Loading.
            if (phase == "view" && this@AgentWidgetController.phase is Phase.Starting) {
                this@AgentWidgetController.phase = Phase.Failed(message, Status.Open)
            }
        }

        override fun malformed(message: String) = clientError("post", message)

        override fun gpuCanvas(key: String?, state: AgentWidgetGpuState) {
            val before = if (key == null) gpuAll else gpuStates[key] ?: gpuAll
            if (before != state) AgentWidgetDiagnostics.log { "${ref.key} GPU canvas ${key ?: "(every canvas)"}: ${describe(state)}" }
            AgentWidgetDiagnostics.gpuStarted(state)
            if (key == null) {
                gpuAll = state
                gpuStates.clear()
            } else {
                gpuStates[key] = state
            }
            if (state is AgentWidgetGpuState.Stopped) {
                if (state.program && state.report) reportError("gpu", AgentWidgetEngineException(state.message, line = state.line, column = state.column))
            }
        }

        override fun ended() = Unit
    }

    private var liveAttempts = 0

    private fun closeLive(how: AgentWidgetMachine.Closed) {
        live?.stop()
        liveWantsFrame.set(false)
        phase = Phase.Closed(phase.view, how, phase.state)
    }

    /** Where the widget template's source sits in its program: lines of prelude before it, and its own lines. */
    private var sourceLineOffset = 0
    private var sourceLineCount = 0

    private fun sourceLines(info: OpenCodeWidgetProgram) {
        sourceLineOffset = info.sourceLineOffset
        sourceLineCount = if (info.sourceLineOffset > 0) info.program.count { it == '\n' } + 1 - info.sourceLineOffset else 0
    }
    private val reported = HashSet<String>()

    /**
     * Reports a program error in [phase] to the widget plugin, once per distinct error; a client error (the runtime's
     * own failure, anything not the widget template's) only goes to the dev-mode log.
     */
    fun reportError(phase: String, error: Throwable) {
        if (!error.isProgramError()) return clientError(phase, error.message ?: error.javaClass.simpleName)
        val engine = (error as? AgentWidgetPrograms.CompileFailed)?.cause ?: error as? AgentWidgetEngineException
        // Shader lines are already the widget template's; program lines count the prelude.
        val line = engine?.line?.let { if (phase == "shader") it else (it - sourceLineOffset).takeIf { l -> l in 1..sourceLineCount } }
        val message = error.message?.let { if (engine != null && phase != "shader") programMessage(it) else it } ?: error.javaClass.simpleName
        reportError(phase, message, line, if (line != null) engine?.column else null)
    }

    /** A client error: a bug to fix in the app, not the widget template's; logged and shown in dev mode only. */
    fun clientError(phase: String, message: String) {
        val new = diagnose("client\u0000$phase\u0000$message") { "${ref.key} client error ($phase): $message" }
        // A GPU canvas shows its own reason under its placeholder.
        if (new && phase != "gpu") devNotice = "$phase: $message"
    }

    /** Dev mode: the last client error, for a line on the widget (null outside dev mode). */
    var devNotice: String? by mutableStateOf(null)
        private set

    /** Errors logged in dev mode, each once until a retry. */
    private val diagnosed = HashSet<String>()

    private fun diagnose(key: String, message: () -> String): Boolean {
        if (!AgentWidgetCapabilities.isDevMode) return false
        if (!synchronized(diagnosed) { diagnosed.size < MAX_DIAGNOSED && diagnosed.add(key) }) return false
        AgentWidgetDiagnostics.log(message)
        return true
    }

    private fun describe(state: AgentWidgetGpuState): String = when (state) {
        is AgentWidgetGpuState.Live -> if (state.opaque) "live (opaque)" else "live (translucent)"
        is AgentWidgetGpuState.Unavailable -> "unavailable: ${state.reason}"
        is AgentWidgetGpuState.Lost -> "lost: ${state.reason}"
        is AgentWidgetGpuState.Stopped ->
            "stopped (${if (state.program) "program" else "client"}${if (state.report) ", reported" else ""}): ${state.message}"
    }

    /** A program error found by the app (an invalid view): [reportError] for errors the runtime threw. */
    fun reportError(phase: String, message: String, line: Int? = null, column: Int? = null) {
        diagnose("program\u0000$phase\u0000$message") {
            val at = line?.let { l -> " at line $l" + (column?.let { ":$it" } ?: "") } ?: ""
            "${ref.key} program error ($phase$at): $message"
        }
        // An answered widget's program is done; each error goes once, and a widget sends at most a few.
        if (status(widget) is Status.Answered || reported.size >= MAX_REPORTS || !reported.add("$phase\u0000$message")) return
        val error = OpenCodeWidgetError(phase, message, line, column)
        scope.launch {
            try {
                transport.report(server, directory, sessionID, widget, error, false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.app.notice { "Widget ${ref.name} report ($phase) didn't go: ${error.message?.take(80)}" }
            }
        }
    }

    internal fun stopLive() {
        live?.stop()
    }

    private val isShown: Boolean get() = (shownInline && !listPaused) || shownFullscreen

    /** Where the widget shows: its inline row on screen, the list resumed, or full screen. A live program runs only while shown. */
    fun shownChanged(inline: Boolean = shownInline, paused: Boolean = listPaused, fullscreen: Boolean = shownFullscreen) {
        shownInline = inline
        listPaused = paused
        shownFullscreen = fullscreen
        live?.setVisible(isShown)
        scheduleLiveFrame()
    }

    /**
     * Hands the program the frame it asked for, on the frame thread: the first live canvas's size (else the widget's)
     * and pointer. With canvases, a frame waits until one has a positive size; without, until the widget has been laid
     * out (it asks again at the next vsync).
     */
    private fun deliverLiveFrame(nanos: Long) {
        val params = liveFrameParams ?: return
        if (!liveWantsFrame.get() || !isShown) return
        val canvas = liveCanvases.values.firstOrNull { it.width > 0f && it.height > 0f }
        if (canvas == null && (liveCanvases.isNotEmpty() || liveViewWidth <= 0f)) return scheduleLiveFrame()
        val width = canvas?.width ?: liveViewWidth
        val height = canvas?.height ?: liveViewHeight
        if (!liveWantsFrame.compareAndSet(true, false)) return
        val scale = params.scale
        val dark = params.dark
        val fullscreen = params.fullscreen
        if (liveStartNanos == 0L) liveStartNanos = nanos
        val close = closeOver(if (canvas != null) canvas.bounds else rootInWindow)
        val p = canvas?.pointer
        val t = (nanos - liveStartNanos) / 1e9
        val dt = if (lastFrameNanos == 0L) 0.0 else (nanos - lastFrameNanos) / 1e9
        lastFrameNanos = nanos
        live?.deliverFrame(t, dt, width, height, scale, p, dark, fullscreen, close)
        liveFrameMeter?.let { it.frames++ }
    }

    /** Dev mode, a full-screen GPU canvas on a SurfaceView (no presents to count): counts the frames delivered. */
    @Volatile internal var liveFrameMeter: AgentWidgetFpsMeter? = null

    /**
     * One draw step for a canvas on this widget's lane, its output handed to [read] there too.
     * Returns the step's time in µs, or null when there's no program or state yet.
     */
    suspend fun draw(frame: String, read: (ByteArray) -> Unit): Long? {
        val program = program ?: return null
        val state = phase.state ?: return null
        val input = AgentWidgetStep.draw(data, state, frame)
        return lane.run {
            val started = System.nanoTime()
            val bytes = program.draw(input)
            val micros = (System.nanoTime() - started) / 1_000
            read(bytes)
            micros
        }
    }

    private fun feed(event: Event) {
        val (next, effects) = AgentWidgetMachine.reduce(machine, event)
        machine = next
        phase = next.phase
        if (!started) {
            pendingEffects = pendingEffects + effects
            return
        }
        run(effects)
        if (next.phase is Phase.Ready && queued.isNotEmpty()) feed(Event.Input(queued.removeFirst()))
        if (next.phase !is Phase.Ready && next.phase !is Phase.Stepping) queued.clear()
    }

    private fun run(effects: List<Effect>) {
        for (effect in effects) {
            when (effect) {
                Effect.Fetch -> scope.launch {
                    val event = try {
                        val compiled = programs.program(server, directory, ref)
                        sourceLines(compiled.info)
                        val live = compiled.live
                        if (live != null) {
                            startLiveMode(live, compiled.info.httpsHosts)
                            return@launch
                        }
                        program = compiled.step?.session(AgentWidgetStep.shared(data))
                        Event.Loaded
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        AppLog.app.notice { "Widget ${ref.key} didn't load: ${error.javaClass.simpleName}" }
                        if (error is AgentWidgetPrograms.CompileFailed) {
                            sourceLines(error.info)
                            reportError(if (error.info.isLive) "main" else "init", error.cause)
                        } else {
                            clientError("load", error.message ?: error.javaClass.simpleName)
                        }
                        Event.LoadFailed(error.message ?: error.javaClass.simpleName)
                    }
                    feed(event)
                }
                is Effect.Run -> scope.launch {
                    val program = program
                    val result = if (program == null) {
                        Result.failure(IllegalStateException("The widget's program isn't loaded."))
                    } else {
                        lane.run {
                            runCatching {
                                val started = System.nanoTime()
                                val bytes = program.step(effect.input)
                                val stepped = System.nanoTime()
                                val output = AgentWidgetWire.output(bytes)
                                lastStepMicros = (stepped - started) / 1_000
                                AppLog.perf.debug {
                                    "Widget ${ref.name} step ${lastStepMicros} µs, decode ${(System.nanoTime() - stepped) / 1_000} µs, ${bytes.size} bytes"
                                }
                                output
                            }
                        }
                    }
                    result.getOrNull()?.let { rootFullscreen = it.fullscreen }
                    result.exceptionOrNull()?.let { error ->
                        val phase = (error as? AgentWidgetEngineException)?.phase ?: when {
                            error is AgentWidgetNode.InvalidView -> "view"
                            effect.input.startsWith("{\"phase\":\"init\"") -> "init"
                            else -> "event"
                        }
                        reportError(phase, error)
                    }
                    feed(Event.Ran(result))
                }
                is Effect.Send -> scope.launch {
                    try {
                        transport.send(server, directory, sessionID, widget, effect.message)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: AgentWidgetTransportException) {
                        // Past 10 a second the server drops sends; so does the widget.
                        if (error.isRateLimited) {
                            AppLog.app.notice { "Widget ${ref.name} send dropped: rate limited" }
                        } else {
                            feed(Event.SendFailed(if (error.isClosed) localized(R.string.transcript_widget_not_open) else error.message ?: ""))
                        }
                    }
                }
                is Effect.Respond -> scope.launch {
                    val failure = try {
                        transport.respond(server, directory, sessionID, widget, effect.response)
                        null
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: AgentWidgetTransportException) {
                        if (error.isClosed) RespondFailure.Conflict else RespondFailure.Failed(error.message ?: "")
                    }
                    feed(Event.Responded(effect.attempt, failure))
                }
            }
        }
    }

    internal companion object {
        const val MAX_REPORTS = 8
        const val MAX_DIAGNOSED = 64

        /**
         * The close button's area in the coordinates of a full-screen root [width] dp wide: CLOSE_AREA square,
         * CLOSE_INSET from the safe area's top and end, which is FULLSCREEN_PADDING outside the root.
         */
        fun closeArea(width: Float, rtl: Boolean = false): AgentWidgetRect {
            val padding = FULLSCREEN_PADDING.value
            val x = if (rtl) CLOSE_INSET.value - padding else width + padding - CLOSE_INSET.value - CLOSE_AREA.value
            return AgentWidgetRect(x, CLOSE_INSET.value - padding, CLOSE_AREA.value, CLOSE_AREA.value)
        }

        fun openFailure(widget: AgentWidgetState): OpenCodeWidgetFailure? =
            widget.failure.takeIf { widget.status == AgentWidgetState.Status.Open }

        fun status(widget: AgentWidgetState): Status = when (widget.status) {
            AgentWidgetState.Status.Open -> Status.Open
            AgentWidgetState.Status.Submitted -> Status.Answered(AgentWidgetMachine.Closed.Submitted(widget.response ?: "null"))
            AgentWidgetState.Status.Dismissed, AgentWidgetState.Status.Superseded -> Status.Answered(AgentWidgetMachine.Closed.Dismissed)
        }
    }
}
