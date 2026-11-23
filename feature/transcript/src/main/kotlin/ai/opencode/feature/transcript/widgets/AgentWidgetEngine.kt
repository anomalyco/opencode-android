package ai.opencode.feature.transcript.widgets

import ai.opencode.core.dependencies.DependencyKey
import android.view.Surface

/**
 * Runs widget programs on the Rust PsychoScript (`psychoscript-mobile`). A program compiles once
 * per widget template version; each step takes the step input's JSON and returns the step output (or
 * a draw step's `{ commands }`) in the binary format ([AgentWidgetWire]). Every call throws
 * [AgentWidgetEngineException] when it fails.
 */
interface AgentWidgetEngine {
    val isAvailable: Boolean

    fun compile(program: String): AgentWidgetProgram

    /** A live widget template's program, run with `return await __widget_live(__input())` (specs/v2/widgets-live.md). */
    fun compileLive(program: String): AgentWidgetLiveProgram

    /** The runtime's handle on [surface] (an `ANativeWindow`) for a GPU canvas; close it once nothing presents into it. */
    fun gpuSurface(surface: Surface): AgentWidgetGpuSurface

    /** A `wgsl` canvas presenting into [surface], [width] × [height] physical pixels; it returns at once. */
    fun shaderCanvas(wgsl: String, surface: AgentWidgetGpuSurface, width: Int, height: Int): AgentWidgetShaderCanvas

    companion object Key : DependencyKey<AgentWidgetEngine> {
        override val liveValue: AgentWidgetEngine by lazy { NativeAgentWidgetEngine.load() ?: Unavailable }
        override val testValue: AgentWidgetEngine get() = Unavailable
    }

    /** Without the native runtime every widget shows its error card. */
    object Unavailable : AgentWidgetEngine {
        override val isAvailable: Boolean = false
        override fun compile(program: String): AgentWidgetProgram = throw AgentWidgetEngineException(UNAVAILABLE, isProgramError = false)
        override fun compileLive(program: String): AgentWidgetLiveProgram = throw AgentWidgetEngineException(UNAVAILABLE, isProgramError = false)
        override fun gpuSurface(surface: Surface): AgentWidgetGpuSurface = throw AgentWidgetEngineException(UNAVAILABLE, isProgramError = false)
        override fun shaderCanvas(wgsl: String, surface: AgentWidgetGpuSurface, width: Int, height: Int): AgentWidgetShaderCanvas =
            throw AgentWidgetEngineException(UNAVAILABLE, isProgramError = false)
        private const val UNAVAILABLE = "The widget runtime isn't available in this build."
    }
}

fun interface AgentWidgetProgram : AutoCloseable {
    /** An `init` or `event` step (250 ms): its output as `01 'S'` bytes. */
    fun step(input: String): ByteArray

    /** A draw step (8 ms; over it fails with `isBudget`): `01 'D'` bytes. */
    fun draw(input: String): ByteArray = step(input)

    /** Frees the interpreter now rather than when the collector gets to it. */
    override fun close() = Unit

    /**
     * Runs whose input starts with [fieldsJson] (a JSON object: a widget's `{ data }`), parsed once; each step's
     * input then carries only its own fields.
     */
    fun session(fieldsJson: String): AgentWidgetProgram {
        val base = this
        val head = fieldsJson.trim().removeSuffix("}")
        fun merged(input: String): String {
            val rest = input.trim().removePrefix("{")
            return if (rest.trimStart().startsWith("}")) "$head}" else "$head,$rest"
        }
        return object : AgentWidgetProgram {
            override fun step(input: String): ByteArray = base.step(merged(input))
            override fun draw(input: String): ByteArray = base.draw(merged(input))
        }
    }
}

/**
 * [isBudget]: a draw step over 8 ms or 2,000 commands; the canvas counts these and stops after three.
 * [isProgramError]: the widget template's code failed or broke a limit (specs/v2/widgets.md, Failures); false for the
 * runtime's own failures, which are client errors.
 */
class AgentWidgetEngineException(
    message: String,
    val isTimeout: Boolean = false,
    val isBudget: Boolean = false,
    /** Where in the compiled program (or a shader's WGSL in the widget template) it failed, 1-based, when known. */
    val line: Int? = null,
    val column: Int? = null,
    val isProgramError: Boolean = true,
    /** The phase the runtime named for an output it couldn't write (`view`, `draw`, `gpu`, `main`), if it did. */
    val phase: String? = null,
    /** The program is of another widget protocol: it isn't run, and no retry can run it. */
    val protocol: Protocol? = null,
) : Exception(message) {
    /** Older: the program's error (post it again); newer: the app's (update it). */
    enum class Protocol { Older, Newer }
}

/** The runtime's message without the position it appends, "(line N, col M)", which counts the prelude's lines. */
internal fun programMessage(message: String): String = message.replace(RUNTIME_POSITION, "")

private val RUNTIME_POSITION = Regex("""\s*\(line \d+, col \d+\)\s*$""")

/** A program error goes to the server with `report`; anything else is a client error, logged in dev mode only. */
internal fun Throwable.isProgramError(): Boolean = when (this) {
    is AgentWidgetEngineException -> isProgramError
    is AgentWidgetNode.InvalidView -> true
    is AgentWidgetPrograms.CompileFailed -> cause.isProgramError
    else -> false
}

/** The runtime's surface for a GPU canvas ([handle] is the binding's `GpuSurface`; [isValid] the Android `Surface`'s). */
class AgentWidgetGpuSurface(val handle: AutoCloseable, val isValid: () -> Boolean = { true }) : AutoCloseable by handle

/**
 * What stopped a GPU canvas for good: the widget template's ([program]: report it, phase `gpu`) or the client's. With
 * [keepsLastFrame] (the slow-frame stop) the canvas keeps its last frame instead of the placeholder.
 */
class AgentWidgetGpuFailure(val program: Boolean, val message: String, val keepsLastFrame: Boolean = false)

/** A `wgsl` canvas (widgets.md, GPU canvases): frames are recorded here and drawn on the runtime's own thread. */
interface AgentWidgetShaderCanvas : AutoCloseable {
    /** Records a frame ([pointer] `x, y, down` in physical pixels); returns, once, what stopped the canvas. */
    fun render(time: Float, scale: Float, pointer: FloatArray, values: FloatArray): AgentWidgetGpuFailure?
    fun resize(width: Int, height: Int)
}

/** A live run's GPU canvas state (widgets-gpu.md): [Live] shows the surface, anything else its placeholder. */
sealed interface AgentWidgetGpuState {
    data object Live : AgentWidgetGpuState
    /** No GPU canvases on this device; [reason] is for developers (the Android version, adapter, limits, …). */
    data class Unavailable(val reason: String) : AgentWidgetGpuState
    /** The device was lost until the program draws again; [reason] is wgpu's, for developers. */
    data class Lost(val reason: String) : AgentWidgetGpuState
    /** An error stopped it for the rest of the run; [report] a [program] one once, in phase `gpu`. */
    data class Stopped(val program: Boolean, val message: String, val report: Boolean) : AgentWidgetGpuState
}

/** A live program, compiled once: each open widget starts its own run of it. */
interface AgentWidgetLiveProgram : AutoCloseable {
    /** Starts `main(data)` on a thread of its own; [httpsHosts] are the hosts granted for `fetch`. */
    fun start(inputJson: String, listener: AgentWidgetLiveListener, httpsHosts: List<String>?): AgentWidgetLiveRun

    override fun close() = Unit
}

/** One running live program. Calls never block; a call on a run that ended does nothing. */
interface AgentWidgetLiveRun {
    fun deliver(messageJson: String)
    fun pause()
    /** The program's next message is `{ type: "resumed", pausedMs }`. */
    fun resume()
    /** Frees a paused run's VM (its state kept for [resume]); false when it can't (a fetch in flight). */
    fun snapshot(): Boolean
    fun stop()
    /** Presents the GPU canvas [key] into [surface], [width] × [height] physical pixels; again on a new size. Never blocks. */
    fun attachSurface(key: String, surface: AgentWidgetGpuSurface, width: Int, height: Int) = Unit
    /** Once this returns (at most a frame), the run never touches the canvas's surface again. */
    fun detachSurface(key: String) = Unit
}

/** What a live run tells its widget, on the run's own thread: hand it to the main thread and return. */
interface AgentWidgetLiveListener {
    /** A wake-up's posts, in order, in the binary format. Called on the run's thread: hand them off and return at once. */
    fun posted(messages: List<ByteArray>)
    fun warned(message: String)
    /** It ended: [failure] null when `main` returned or the run was stopped. */
    fun exited(failure: AgentWidgetEngineException?)

    /** A `fetch` transfer failed (the program gets the rejection). */
    fun fetchFailed(message: String)

    /** A GPU canvas's state changed: [key] null for every GPU canvas of the run. */
    fun gpuCanvas(key: String?, state: AgentWidgetGpuState) = Unit
}
