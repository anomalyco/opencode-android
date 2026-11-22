package ai.opencode.feature.transcript.widgets

import ai.opencode.core.dependencies.DependencyKey

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

    /** A `shader` node's `wgsl` (its `shade` function) in the spec's header and entry point, as GLSL ES 3.00, with the vertex stage. */
    fun translateShader(wgsl: String): AgentWidgetShaderStages

    companion object Key : DependencyKey<AgentWidgetEngine> {
        override val liveValue: AgentWidgetEngine by lazy { NativeAgentWidgetEngine.load() ?: Unavailable }
        override val testValue: AgentWidgetEngine get() = Unavailable
    }

    /** Without the native runtime every widget shows its error card. */
    object Unavailable : AgentWidgetEngine {
        override val isAvailable: Boolean = false
        override fun compile(program: String): AgentWidgetProgram = throw AgentWidgetEngineException(UNAVAILABLE, isProgramError = false)
        override fun compileLive(program: String): AgentWidgetLiveProgram = throw AgentWidgetEngineException(UNAVAILABLE, isProgramError = false)
        override fun translateShader(wgsl: String): AgentWidgetShaderStages = throw AgentWidgetEngineException(UNAVAILABLE, isProgramError = false)
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
) : Exception(message)

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

/** Translated GLSL ES 3.00 and its uniform blocks, each with the binding point to give it. */
class AgentWidgetShaderCode(val code: String, val uniformBlocks: List<Pair<String, Int>> = emptyList())

/** A shader node's two GPU stages: the full-screen triangle and its fragment shader. */
class AgentWidgetShaderStages(val vertex: AgentWidgetShaderCode, val fragment: AgentWidgetShaderCode)

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
}
