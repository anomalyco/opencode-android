package ai.opencode.feature.transcript.widgets

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeWidgetError
import ai.opencode.server.model.OpenCodeWidgetRef
import ai.opencode.widgets.widgetEncoder
import com.anomalyco.psychoscript.GpuCanvasState
import com.anomalyco.psychoscript.GpuErrorKind
import com.anomalyco.psychoscript.LiveExit
import com.anomalyco.psychoscript.LiveHandler
import com.anomalyco.psychoscript.LiveOptions
import com.anomalyco.psychoscript.LiveRun
import com.anomalyco.psychoscript.Program
import com.anomalyco.psychoscript.PsychoScriptException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * What a widget's errors send to the server (`report { error: { phase, message, line?, column? } }`), from live
 * programs on the runtime's desktop build with the widget package's preludes, through [AgentWidgetController]'s report
 * path (the template's own lines: [OpenCodeWidgetProgram.sourceLineOffset]). Skipped without the desktop build
 * (scripts/psychoscript.sh --host).
 *
 * A position the runtime gives inside the preludes (a recording API's `TypeError`, an encoder error at the prelude's
 * `__host.post`) isn't the template's, so the report has none; the runtime gives none at all for an uncaught rejection
 * (a denied fetch), a thrown value, or a GPU batch that fails (`onGpuCanvas(Stopped)`).
 */
class AgentWidgetReportTests {
    @get:Rule val isolation = TestIsolationRule()

    @Before
    fun runtime() = assumeTrue(AgentWidgetWorkloads.missing ?: "", AgentWidgetWorkloads.missing == null)

    /** A located error in `main` is reported at the template's line and column. */
    @Test
    fun aLiveProgramErrorIsReportedAtTheTemplatesLine() {
        val run = live(
            """
            |async function main() {
            |  widget.render(ui.text("x"))
            |  nope()
            |}
            """.trimMargin(),
        ) { it.reports.isNotEmpty() }
        assertEquals(listOf(OpenCodeWidgetError("main", "ReferenceError: Unknown identifier 'nope'.", 3, 3)), run.reports)
    }

    /**
     * A fetch the runtime denies rejects with `{ kind: "CapabilityDenied", message }`: a program that catches it reports
     * nothing (the app logs the denial in dev mode); uncaught, the run fails in `main`, with no position from the runtime.
     */
    @Test
    fun aDeniedFetchIsReportedOnlyWhenTheProgramLetsItThrow() {
        val caught = live(
            """
            |async function main() {
            |  try {
            |    await widget.fetch("https://denied.example/data")
            |  } catch (e) {
            |    widget.render(ui.text(e.kind))
            |  }
            |}
            """.trimMargin(),
            hosts = listOf("allowed.example"),
        ) { it.exited }
        assertEquals(listOf("warned: CapabilityDenied: denied.example isn't among the hosts this widget may reach", "exit: Finished"), caught.events)
        assertEquals(emptyList<OpenCodeWidgetError>(), caught.reports)

        val thrown = live(
            """
            |async function main() {
            |  widget.render(ui.text("x"))
            |  const r = await widget.fetch("https://denied.example/data")
            |  widget.render(ui.text(String(r.status)))
            |}
            """.trimMargin(),
            hosts = listOf("allowed.example"),
        ) { it.reports.isNotEmpty() }
        val denied = """{"kind":"CapabilityDenied","message":"denied.example isn't among the hosts this widget may reach"}"""
        assertEquals(listOf(OpenCodeWidgetError("main", "Uncaught: $denied")), thrown.reports)
    }

    /**
     * A GPU batch the device rejects stops the canvases and is reported in phase `gpu` with wgpu's message and no
     * position (the runtime replays batches after the program recorded them); a recording API misuse throws the
     * prelude's `TypeError`, reported in `main` without the prelude's position. Skipped without a GPU.
     */
    @Test
    fun gpuErrorsAreReportedWithoutAPosition() {
        val failed = live(
            """
            |async function main() {
            |  widget.render(ui.canvas({ key: "c", gpu: true, height: 10 }))
            |  const device = await widget.gpu.requestDevice()
            |  const buffer = device.createBuffer({ size: 4, usage: GPUBufferUsage.UNIFORM | GPUBufferUsage.COPY_DST })
            |  device.queue.writeBuffer(buffer, 0, new Float32Array(16))
            |  for (;;) await widget.frame()
            |}
            """.trimMargin(),
            frames = true,
        ) { run -> run.reports.isNotEmpty() || run.events.any { "Unavailable" in it } }
        assumeTrue("no GPU here: ${failed.events}", failed.events.none { "Unavailable" in it })
        val message = "In Queue::write_buffer: Copy at offset 0 for 64 bytes would end up overrunning the bounds of the Destination buffer of size 4"
        assertEquals(listOf(OpenCodeWidgetError("gpu", message)), failed.reports)

        val misused = live(
            """
            |async function main() {
            |  widget.render(ui.canvas({ key: "c", gpu: true, height: 10 }))
            |  const device = await widget.gpu.requestDevice()
            |  device.queue.writeBuffer(null, 0, new Float32Array(4))
            |}
            """.trimMargin(),
        ) { it.reports.isNotEmpty() }
        assertEquals(listOf(OpenCodeWidgetError("main", "TypeError: writeBuffer's buffer must be a GPU buffer")), misused.reports)
    }

    /** An encoder error at a host call is located at the call, the prelude's `__host.post`: not the template's line. */
    @Test
    fun anEncoderErrorInThePreludeIsReportedWithoutAPosition() {
        val run = live(
            """
            |async function main() {
            |  widget.render(ui.text("x"))
            |  widget.send(() => 1)
            |}
            """.trimMargin(),
        ) { it.reports.isNotEmpty() }
        assertEquals(
            listOf(OpenCodeWidgetError("main", "TypeError: Argument 1 to __host.post contains a function, which cannot be passed to the host.")),
            run.reports,
        )
        assertEquals(true, run.events.single { it.startsWith("exit") }.contains(Regex("""line=\d+, column=\d+""")))
    }

    private class Run(val reports: List<OpenCodeWidgetError>, val events: List<String>) {
        val exited: Boolean get() = events.any { it.startsWith("exit") }
    }

    /**
     * Runs [source] as a live widget on an [AgentWidgetController] until [done] (at most 10 s), with frames for its canvas
     * `c` (8 × 8) if [frames]; what it reported and what its run told the app.
     */
    private fun live(source: String, hosts: List<String>? = null, frames: Boolean = false, done: (Run) -> Boolean): Run {
        val program = AgentWidgetWorkloads.liveProgram(source, hosts)
        val engine = Engine()
        val reports = CopyOnWriteArrayList<OpenCodeWidgetError>()
        val transport = AgentWidgetTransport(
            program = { _, _, _ -> program },
            respond = { _, _, _, _, _ -> },
            send = { _, _, _, _, _ -> },
            report = { _, _, _, _, error, _ -> reports += error },
        )
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        val widget = AgentWidgetState("m", "c", null, OpenCodeWidgetRef("probe", "1"), "{}", AgentWidgetState.Status.Open, httpsHosts = hosts)
        val controller = AgentWidgetController(
            widget, OpenCodeServer("http://127.0.0.1:1", "u", "p"), "s", null, scope,
            AgentWidgetPrograms({ engine }, { transport }), transport, engine,
        )
        try {
            controller.start()
            controller.shownChanged(inline = true)
            val until = System.nanoTime() + 10_000_000_000L
            var sent = 0
            fun now() = Run(reports.toList(), engine.events.toList())
            while (!done(now()) && System.nanoTime() < until) {
                val run = engine.run
                if (frames && run != null) {
                    runCatching {
                        if (sent == 0) run.deliver("""{"type":"canvas","key":"c","width":8,"height":8}""")
                        run.deliver("""{"type":"frame","frame":{"t":${sent / 60.0},"dt":0.016,"width":8,"height":8,"scale":1,"pointer":null,"dark":false,"fullscreen":false,"close":null}}""")
                    }
                    sent++
                }
                Thread.sleep(20)
            }
            // Anything else it would report goes first.
            Thread.sleep(200)
            return now()
        } finally {
            controller.stopLive()
            scope.cancel()
            main.close()
        }
    }

    /**
     * [NativeAgentWidgetEngine] for live programs, its runs' events handed on as it does (its [NativeAgentWidgetEngine.mapped]
     * failures and GPU states), except that frames go through `deliver`: LiveFrames' JNI is only in the Android library.
     */
    private class Engine : AgentWidgetEngine by NativeAgentWidgetEngine {
        @Volatile var run: LiveRun? = null
        val events = CopyOnWriteArrayList<String>()

        override fun compileLive(program: String): AgentWidgetLiveProgram {
            val compiled = try {
                Program(program + "\nreturn await __widget_live(__input(), true)", "__input")
            } catch (error: PsychoScriptException) {
                throw NativeAgentWidgetEngine.mapped(error, draw = false)
            }
            return object : AgentWidgetLiveProgram {
                override fun start(inputJson: String, listener: AgentWidgetLiveListener, httpsHosts: List<String>?): AgentWidgetLiveRun {
                    val handler = object : LiveHandler {
                        override fun onPosts(messagesJson: List<String>) = Unit
                        override fun onPostsBytes(messages: List<ByteArray>) = listener.posted(messages)
                        override fun onWarning(message: String) {
                            events += "warned: $message"
                            listener.warned(message)
                        }
                        override fun onExit(exit: LiveExit) {
                            events += "exit: " + if (exit is LiveExit.Failed) exit.error.toString() else exit.javaClass.simpleName
                            listener.exited((exit as? LiveExit.Failed)?.error?.let { NativeAgentWidgetEngine.mapped(it, draw = false) })
                        }
                        override fun onGpuCanvas(key: String?, state: GpuCanvasState) {
                            events += "gpu $key: $state"
                            listener.gpuCanvas(
                                key,
                                when (state) {
                                    is GpuCanvasState.Live -> AgentWidgetGpuState.Live(state.opaque)
                                    is GpuCanvasState.Unavailable -> AgentWidgetGpuState.Unavailable(state.reason)
                                    is GpuCanvasState.Lost -> AgentWidgetGpuState.Lost(state.reason)
                                    is GpuCanvasState.Stopped -> AgentWidgetGpuState.Stopped(state.kind == GpuErrorKind.PROGRAM, state.message, state.report)
                                },
                            )
                        }
                    }
                    val options = LiveOptions(sliceBudgetMs = 50uL, httpsHosts = httpsHosts, allowLocalNetwork = false, maxHeapBytes = 33_554_432uL)
                    val transport = if (httpsHosts.isNullOrEmpty()) null else AgentWidgetFetchTransport(listener::fetchFailed)
                    val live = compiled.startLive(inputJson, handler, options, transport, widgetEncoder())
                    run = live
                    return object : AgentWidgetLiveRun {
                        override fun deliver(messageJson: String) = live.deliver(messageJson)
                        override fun pause() = live.pause()
                        override fun resume() = live.resume()
                        override fun snapshot() = runCatching { live.snapshot() }.isSuccess
                        override fun stop() = live.stop()
                    }
                }
            }
        }
    }
}
