package ai.opencode.feature.transcript.widgets

import ai.opencode.core.log.AppLog
import com.anomalyco.psychoscript.LiveExit
import com.anomalyco.psychoscript.LiveHandler
import com.anomalyco.psychoscript.LiveOptions
import com.anomalyco.psychoscript.LiveRun
import com.anomalyco.psychoscript.Program
import com.anomalyco.psychoscript.PsychoScriptException
import com.anomalyco.psychoscript.RunOptions
import com.anomalyco.psychoscript.ShaderException
import com.anomalyco.psychoscript.ShaderStage
import com.anomalyco.psychoscript.ShaderTarget
import com.anomalyco.psychoscript.ValueEncoder
import com.anomalyco.psychoscript.translateWgsl

/**
 * The step ABI and shader wrapping on the Rust PsychoScript (`psychoscript-mobile`), when this
 * build bundles it: a widget template's program compiles once with the step entry appended, steps run
 * under the contract's 250 ms (draws 8 ms; a timeout there is an overrun), every run (steps, draws, live)
 * within a 32 MB heap, and a shader's WGSL goes in the spec's header and a GL entry point that flips
 * `frag.y` to a top-left origin.
 */
internal object NativeAgentWidgetEngine : AgentWidgetEngine {
    override val isAvailable: Boolean = true

    /** The engine, or null when the native library isn't in this build or doesn't load. */
    fun load(): AgentWidgetEngine? = try {
        val started = System.nanoTime()
        Program("1").use { it.run("null") }
        AppLog.perf.info { "Widget runtime loaded in ${(System.nanoTime() - started) / 1_000} µs" }
        this
    } catch (error: Throwable) {
        AppLog.app.notice { "Widget runtime unavailable: ${error.javaClass.simpleName}" }
        null
    }

    override fun compile(program: String): AgentWidgetProgram {
        val compiled = mapping { Program(program + ENTRY) }
        return object : AgentWidgetProgram {
            override fun step(input: String): ByteArray = mapping { compiled.runBytes(input, encoder, STEP) }
            override fun draw(input: String): ByteArray = mapping(draw = true) { compiled.runBytes(input, encoder, DRAW) }
            override fun close() = compiled.close()
            override fun session(fieldsJson: String): AgentWidgetProgram {
                val session = mapping { compiled.session(fieldsJson) }
                return object : AgentWidgetProgram {
                    override fun step(input: String): ByteArray = mapping { session.runBytes(input, encoder, STEP) }
                    override fun draw(input: String): ByteArray = mapping(draw = true) { session.runBytes(input, encoder, DRAW) }
                    override fun close() = session.close()
                }
            }
        }
    }

    override fun compileLive(program: String): AgentWidgetLiveProgram {
        val compiled = mapping { Program(program + LIVE_ENTRY) }
        return object : AgentWidgetLiveProgram {
            override fun start(inputJson: String, listener: AgentWidgetLiveListener, httpsHosts: List<String>?): AgentWidgetLiveRun {
                val handler = object : LiveHandler {
                    override fun onPosts(messagesJson: List<String>) = Unit
                    override fun onPostsBytes(messages: List<ByteArray>) = listener.posted(messages)
                    override fun onWarning(message: String) = listener.warned(message)
                    override fun onExit(exit: LiveExit) = listener.exited((exit as? LiveExit.Failed)?.error?.let { mapped(it, draw = false) })
                }
                val options = LiveOptions(
                    sliceBudgetMs = 50uL,
                    maxPostsPerSecond = 240u,
                    httpsHosts = httpsHosts,
                    allowLocalNetwork = AgentWidgetCapabilities.allowsLocalNetwork,
                    maxHeapBytes = HEAP_BYTES,
                )
                val transport = if (httpsHosts.isNullOrEmpty()) null else AgentWidgetFetchTransport(listener::fetchFailed)
                val live = mapping { compiled.startLive(inputJson, handler, options, transport, encoder) }
                return NativeLiveRun(live)
            }

            override fun close() = compiled.close()
        }
    }

    private class NativeLiveRun(private val live: LiveRun) : AgentWidgetLiveRun {
        override fun deliver(messageJson: String) = quietly { live.deliver(messageJson) }
        override fun pause() = quietly { live.pause() }
        override fun resume() = quietly { live.resume() }
        override fun snapshot(): Boolean = try {
            live.snapshot()
            true
        } catch (error: PsychoScriptException) {
            AppLog.app.notice { "Live widget snapshot failed: ${reason(error).take(120)}" }
            false
        }
        override fun stop() = live.stop()

        private inline fun quietly(block: () -> Unit) {
            try {
                block()
            } catch (error: PsychoScriptException) {
                AppLog.app.notice { "Live widget call failed: ${error.javaClass.simpleName}" }
            }
        }
    }

    override fun translateShader(wgsl: String): AgentWidgetShaderStages = try {
        val fragment = translateWgsl(HEADER + wgsl + ENTRY_GL, ShaderTarget.GLSL_ES300, ShaderStage.FRAGMENT, "widget_main")
        val vertex = translateWgsl(VERTEX, ShaderTarget.GLSL_ES300, ShaderStage.VERTEX, "widget_vertex")
        AgentWidgetShaderStages(
            vertex = AgentWidgetShaderCode(vertex.code),
            fragment = AgentWidgetShaderCode(fragment.code, fragment.uniformBlocks.map { it.name to it.slot.toInt() }),
        )
    } catch (error: ShaderException) {
        val (line, column) = when (error) {
            is ShaderException.Parse -> error.line to error.column
            is ShaderException.Validation -> error.line to error.column
            else -> null to null
        }
        val at = line?.toInt()?.minus(HEADER_LINES)?.takeIf { it in 1..wgsl.lines().size }
        // Parse and validation reject the WGSL as written; Unsupported and Translation fail on valid WGSL.
        val invalid = error is ShaderException.Parse || error is ShaderException.Validation
        throw AgentWidgetEngineException(shaderMessage(error, wgsl.lines().size), line = at, column = if (at != null) column?.toInt() else null, isProgramError = invalid)
    }

    /** The reason, with where it happened as a line of the widget template when it happened there. */
    private fun shaderMessage(error: ShaderException, widgetTemplateLines: Int): String {
        val (reason, line, column) = when (error) {
            is ShaderException.Parse -> Triple(error.reason, error.line, error.column)
            is ShaderException.Validation -> Triple(error.reason, error.line, error.column)
            is ShaderException.Unsupported -> Triple(error.reason, null, null)
            is ShaderException.Translation -> Triple(error.reason, null, null)
        }
        val at = line?.toInt()?.minus(HEADER_LINES)
        return if (at != null && at in 1..widgetTemplateLines) "$reason (line $at, column ${column ?: 0u})" else reason
    }

    private inline fun <T> mapping(draw: Boolean = false, block: () -> T): T = try {
        block()
    } catch (error: PsychoScriptException) {
        throw mapped(error, draw)
    }

    internal fun mapped(error: PsychoScriptException, draw: Boolean): AgentWidgetEngineException {
        val timeout = error is PsychoScriptException.Timeout
        val (line, column) = when (error) {
            is PsychoScriptException.Syntax -> error.line to error.column
            is PsychoScriptException.Runtime -> error.line to error.column
            else -> null to null
        }
        val client = error is PsychoScriptException.Snapshot || error is PsychoScriptException.InvalidState || error is PsychoScriptException.InvalidInput
        // The encoder's failures name their phase (specs/v2/widgets-binary.md, Errors).
        val phase = when ((error as? PsychoScriptException.Runtime)?.kind) {
            "InvalidView" -> "view"
            "InvalidDraw" -> "draw"
            "InvalidGpu" -> "gpu"
            "InvalidDataValue" -> "main"
            else -> null
        }
        return AgentWidgetEngineException(
            reason(error),
            isTimeout = timeout && !draw,
            isBudget = timeout && draw,
            line = line?.toInt(),
            column = column?.toInt(),
            isProgramError = !client,
            phase = phase,
        )
    }

    private fun reason(error: PsychoScriptException): String = when (error) {
        is PsychoScriptException.Syntax -> error.reason
        is PsychoScriptException.Runtime -> error.reason
        is PsychoScriptException.Timeout -> error.reason
        is PsychoScriptException.OutputTooLarge -> error.reason
        is PsychoScriptException.InvalidInput -> error.reason
        is PsychoScriptException.SliceBudget -> error.reason
        is PsychoScriptException.Snapshot -> error.reason
        is PsychoScriptException.InvalidState -> error.reason
        is PsychoScriptException.MemoryLimitExceeded -> "It used more than ${error.maxHeapBytes shr 20} MB of memory"
    }

    private const val HEAP_BYTES: ULong = 33_554_432uL
    /** Writes the binary format: steps hand their output to its global, live posts go through it. */
    private val encoder: ValueEncoder by lazy { ValueEncoder.widget() }
    // `true`: the host builds the view tree natively (widgets.md, Native hosts).
    private val ENTRY: String by lazy { "\n${encoder.emitName()}(__widget_step(__input(), true))" }
    private const val LIVE_ENTRY = "\nreturn await __widget_live(__input(), true)"
    internal val STEP = RunOptions(timeoutMs = 250uL, maxOutputBytes = 4uL * 1024uL * 1024uL, maxHeapBytes = HEAP_BYTES)
    internal val DRAW = RunOptions(timeoutMs = 8uL, maxOutputBytes = 1024uL * 1024uL, maxHeapBytes = HEAP_BYTES)

    private const val HEADER = "struct Widget { resolution: vec2f, time: f32, scale: f32, pointer: vec4f, values: array<vec4f, 4> }\n" +
        "@group(0) @binding(0) var<uniform> widget: Widget;\n"
    private const val HEADER_LINES = 2
    private const val ENTRY_GL = "\n@fragment fn widget_main(@builtin(position) p: vec4f) -> @location(0) vec4f " +
        "{ return shade(vec2f(p.x, widget.resolution.y - p.y)); }\n"
    private const val VERTEX = "@vertex fn widget_vertex(@builtin(vertex_index) i: u32) -> @builtin(position) vec4f {\n" +
        "  let uv = vec2f(f32((i << 1u) & 2u), f32(i & 2u));\n" +
        "  return vec4f(uv * 2.0 - 1.0, 0.0, 1.0);\n" +
        "}\n"
}
