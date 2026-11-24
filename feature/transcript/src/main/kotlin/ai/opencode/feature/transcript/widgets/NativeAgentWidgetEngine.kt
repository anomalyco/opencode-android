package ai.opencode.feature.transcript.widgets

import ai.opencode.core.log.AppLog
import com.anomalyco.psychoscript.LiveExit
import com.anomalyco.psychoscript.LiveHandler
import com.anomalyco.psychoscript.LiveOptions
import com.anomalyco.psychoscript.LiveRun
import com.anomalyco.psychoscript.Program
import com.anomalyco.psychoscript.PsychoScriptException
import com.anomalyco.psychoscript.RunOptions
import com.anomalyco.psychoscript.GpuCanvasState
import com.anomalyco.psychoscript.GpuErrorKind
import com.anomalyco.psychoscript.GpuFailure
import com.anomalyco.psychoscript.GpuSurface
import com.anomalyco.psychoscript.PsychoScriptGpu
import com.anomalyco.psychoscript.ShaderCanvas
import com.anomalyco.psychoscript.ValueEncoder
import android.view.Surface

/**
 * The step ABI and GPU canvases on the Rust PsychoScript (`psychoscript-mobile`), when this
 * build bundles it: a widget template's program compiles once with the step entry appended, steps run
 * under the contract's 250 ms (draws 8 ms; a timeout there is an overrun), every run (steps, draws, live)
 * within a 32 MB heap; GPU canvases (live `gpu: true` and `wgsl`) are replayed and presented by the
 * runtime into surfaces the app attaches.
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
                    override fun onGpuCanvas(key: String?, state: GpuCanvasState) = listener.gpuCanvas(
                        key,
                        when (state) {
                            is GpuCanvasState.Live -> AgentWidgetGpuState.Live(state.opaque)
                            is GpuCanvasState.Unavailable -> AgentWidgetGpuState.Unavailable(state.reason)
                            is GpuCanvasState.Lost -> AgentWidgetGpuState.Lost(state.reason)
                            is GpuCanvasState.Stopped -> AgentWidgetGpuState.Stopped(state.kind == GpuErrorKind.PROGRAM, state.message, state.report)
                        },
                    )
                }
                val options = LiveOptions(
                    sliceBudgetMs = 50uL,
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
        override fun attachSurface(key: String, surface: AgentWidgetGpuSurface, width: Int, height: Int) =
            live.attachSurface(key, surface.handle as GpuSurface, width.coerceAtLeast(0).toUInt(), height.coerceAtLeast(0).toUInt())
        override fun detachSurface(key: String) = live.detachSurface(key)

        private inline fun quietly(block: () -> Unit) {
            try {
                block()
            } catch (error: PsychoScriptException) {
                AppLog.app.notice { "Live widget call failed: ${error.javaClass.simpleName}" }
            }
        }
    }

    override fun gpuSurface(surface: Surface): AgentWidgetGpuSurface = try {
        AgentWidgetGpuSurface(PsychoScriptGpu.surface(surface)) { surface.isValid }
    } catch (error: Exception) {
        throw AgentWidgetEngineException(error.message ?: error.javaClass.simpleName, isProgramError = false)
    }

    override fun shaderCanvas(wgsl: String, surface: AgentWidgetGpuSurface, width: Int, height: Int): AgentWidgetShaderCanvas {
        val canvas = ShaderCanvas(wgsl, surface.handle as GpuSurface, width.coerceAtLeast(0).toUInt(), height.coerceAtLeast(0).toUInt())
        return object : AgentWidgetShaderCanvas {
            override fun render(time: Float, scale: Float, pointer: FloatArray, values: FloatArray): AgentWidgetGpuFailure? = try {
                canvas.render(time, scale, listOf(pointer[0], pointer[1], pointer[2], 0f), values.asList())
                null
            } catch (failure: GpuFailure) {
                when (failure) {
                    is GpuFailure.Program -> AgentWidgetGpuFailure(program = true, failure.reason)
                    is GpuFailure.Client -> AgentWidgetGpuFailure(program = false, failure.reason, keepsLastFrame = failure.reason == SLOW_FRAMES)
                }
            }

            override fun resize(width: Int, height: Int) = canvas.resize(width.coerceAtLeast(0).toUInt(), height.coerceAtLeast(0).toUInt())
            override fun close() = canvas.close()
        }
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
        val kind = (error as? PsychoScriptException.Runtime)?.kind
        val protocol = when (kind) {
            "OlderProtocol" -> AgentWidgetEngineException.Protocol.Older
            "NewerProtocol" -> AgentWidgetEngineException.Protocol.Newer
            else -> null
        }
        val client = error is PsychoScriptException.Snapshot || error is PsychoScriptException.InvalidState || error is PsychoScriptException.InvalidInput ||
            protocol == AgentWidgetEngineException.Protocol.Newer
        // The encoder's failures name their phase (specs/v2/widgets-binary.md, Errors).
        val phase = when (kind) {
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
            protocol = protocol,
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
    private const val SLOW_FRAMES = "the shader took over 50 ms a frame, three frames in a row"
    /** Writes the binary format: steps hand their output to its global, live posts go through it. */
    private val encoder: ValueEncoder by lazy { ValueEncoder.widget() }
    // Widget protocol 2 (widgets.md, Native hosts): the encoder builds the view tree from what the prelude emits.
    private const val ENTRY = "\nreturn __widget_native_step(__input())"
    private const val LIVE_ENTRY = "\nreturn await __widget_live(__input(), true)"
    internal val STEP = RunOptions(timeoutMs = 250uL, maxOutputBytes = 4uL * 1024uL * 1024uL, maxHeapBytes = HEAP_BYTES)
    internal val DRAW = RunOptions(timeoutMs = 8uL, maxOutputBytes = 1024uL * 1024uL, maxHeapBytes = HEAP_BYTES)
}
