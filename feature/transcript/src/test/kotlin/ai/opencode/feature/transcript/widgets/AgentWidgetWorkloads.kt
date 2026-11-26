package ai.opencode.feature.transcript.widgets

import ai.opencode.server.model.OpenCodeWidgetProgram
import ai.opencode.widgets.widgetEncoder
import com.anomalyco.psychoscript.LiveExit
import com.anomalyco.psychoscript.LiveHandler
import com.anomalyco.psychoscript.LiveOptions
import com.anomalyco.psychoscript.Program
import com.anomalyco.psychoscript.RunOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * widget-mobile's committed workloads (`packages/widgets-rs/crates/widget-mobile/tests/fixtures/workloads` in the
 * `.widgets-rs` checkout), run on the runtime's desktop build (`scripts/psychoscript.sh --host`) with the widget
 * package's preludes (`packages/widget`): each step's output as JSON (the scripted prelude, which still builds the tree
 * in the program) and as the binary format (widget protocol 2, the native host), and a live workload's first posts both
 * ways. [missing] says why they can't run.
 */
internal object AgentWidgetWorkloads {
    class Step(val name: String, val draw: Boolean, val json: String, val bytes: ByteArray)
    /** [posts] from a JSON run, [bytes] from a native one; [deterministic] when two JSON runs posted the same. */
    class Live(val name: String, val posts: List<String>, val bytes: List<ByteArray>, val deterministic: Boolean)

    private val dir = System.getProperty("opencode.widgetWorkloads")?.let(::File)
    private val widget = System.getProperty("opencode.widgetPackage")?.let(::File)

    /** Why the workloads can't run here (a skip message), or null. */
    val missing: String? = run {
        val host = System.getProperty("jna.library.path")?.let(::File)
        when {
            host?.listFiles()?.any { it.name.startsWith("libwidget_mobile.") } != true ->
                "no desktop build of widget-mobile in vendor/psychoscript/host: run scripts/psychoscript.sh --host"
            dir == null || !File(dir, "workloads.json").isFile ->
                "no widget-mobile workloads at $dir: run scripts/psychoscript.sh --host"
            widget == null || !File(widget, "src/prelude.js").isFile ->
                "no widget preludes at $widget: run scripts/psychoscript.sh --host"
            else -> null
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val STEP = RunOptions(250uL, 4uL * 1024uL * 1024uL, 33_554_432uL)
    private val OPTIONS = LiveOptions(50uL, null, false, 33_554_432uL)
    private val encoder by lazy { widgetEncoder() }
    private val workloads by lazy { json.parseToJsonElement(File(dir, "workloads.json").readText()).jsonObject }
    /** A program is the prelude, a newline, (live: the live prelude and a newline,) the source (widgets.md, Prelude). */
    private val prelude by lazy { File(widget, "src/prelude.js").readText() + "\n" }
    private val livePrelude by lazy { prelude + File(widget, "src/prelude-live.js").readText() + "\n" }
    /** The prelude that still built the tree in the script: the JSON path the binary one is held to. */
    private val scripted by lazy { File(widget, "test/fixtures/scripted-prelude.js").readText() + "\n" }
    private val wire by lazy { File(widget, "test/fixtures/wire") }

    /** The protocol 2 step entry (widgets.md, Native hosts). */
    const val STEP_ENTRY = "\nreturn __widget_native_step(__input())"

    private fun source(name: String) = File(dir, "templates/$name.js").readText()

    /** A wire fixture's `source` and `input` (`packages/widget/test/fixtures/wire`), and its expected `error`, if any. */
    fun fixture(name: String): Triple<String, String, JsonObject?> {
        val o = json.parseToJsonElement(File(wire, "$name.json").readText()).jsonObject
        return Triple(o.getValue("source").jsonPrimitive.content, o.getValue("input").toString(), o["error"] as? JsonObject)
    }

    /**
     * A live widget template's program as the server gives it: the preludes, then [source], with the lines before it
     * ([OpenCodeWidgetProgram.sourceLineOffset]) and the hosts it may fetch from.
     */
    fun liveProgram(source: String, httpsHosts: List<String>? = null) =
        OpenCodeWidgetProgram(livePrelude + source, isLive = true, httpsHosts = httpsHosts, sourceLineOffset = livePrelude.count { it == '\n' })

    /** A step of [source] on the native host: its output's bytes. */
    fun nativeStep(source: String, input: String): ByteArray =
        Program(prelude + source + STEP_ENTRY, "__input").runBytes(input, encoder, STEP)

    val steps: List<Step> by lazy {
        workloads.getValue("step").jsonArray.map { it.jsonObject }.map { w ->
            val name = w.getValue("name").jsonPrimitive.content
            val input = json.parseToJsonElement(File(dir, "inputs/$name.json").readText()).jsonObject
            val own = JsonObject(input.filterKeys { it != "data" }).toString()
            val fields = "{\"data\":${input["data"]}}"
            val source = source(w.getValue("program").jsonPrimitive.content)
            Step(
                name,
                w.getValue("phase").jsonPrimitive.content == "draw",
                Program(scripted + source + "\nreturn __widget_step(__input())", "__input").session(fields).run(own, STEP),
                Program(prelude + source + STEP_ENTRY, "__input").session(fields).runBytes(own, encoder, STEP),
            )
        }
    }

    /**
     * The first two frames' posts, from a JSON run and from a native one (`__widget_live(__input(), true)`, where the
     * encoder builds the tree). Some programs use `Math.random` or the clock, so the two runs agree only when two JSON
     * runs do.
     */
    val live: List<Live> by lazy {
        workloads.getValue("live").jsonArray.map { it.jsonObject }.map { w ->
            val name = w.getValue("name").jsonPrimitive.content
            val source = source(w.getValue("program").jsonPrimitive.content)
            val input = File(dir, "inputs/$name.json").readText()
            fun run(native: Boolean): Posts {
                val posts = Posts()
                val program = Program((if (native) livePrelude else scripted) + source + "\nreturn await __widget_live(__input()${if (native) ", true" else ""})", "__input")
                val run = program.startLive(input, posts, OPTIONS, null, if (native) encoder else null)
                posts.await()
                repeat(2) {
                    run.deliver(frame(1 + it / 60.0))
                    posts.await()
                }
                run.stop()
                return posts
            }
            val texts = run(native = false).texts.toList()
            val again = run(native = false).texts.toList()
            Live(name, texts, run(native = true).bytes.toList(), deterministic = texts == again)
        }
    }

    private fun frame(t: Double) =
        "{\"type\":\"frame\",\"frame\":{\"t\":$t,\"dt\":0.016666666666666666,\"width\":600,\"height\":320,\"scale\":2,\"pointer\":null,\"dark\":false,\"fullscreen\":false,\"close\":null}}"

    /** A live run's posts, and its frame requests. */
    private class Posts : LiveHandler {
        val frames = Semaphore(0)
        val texts = ArrayList<String>()
        val bytes = ArrayList<ByteArray>()
        @Volatile var failure: String? = null

        override fun onPosts(messagesJson: List<String>) {
            synchronized(this) { texts += messagesJson }
            if (messagesJson.any { ((json.parseToJsonElement(it) as JsonObject)["type"] as JsonPrimitive).content == "frame" }) frames.release()
        }

        override fun onPostsBytes(messages: List<ByteArray>) {
            synchronized(this) { bytes += messages }
            if (messages.any { AgentWidgetWire.kind(it) == 3 || AgentWidgetWire.kind(it) == 9 }) frames.release()
        }

        override fun onWarning(message: String) = Unit

        override fun onGpuCanvas(key: String?, state: com.anomalyco.psychoscript.GpuCanvasState) = Unit

        override fun onExit(exit: LiveExit) {
            if (exit is LiveExit.Failed) failure = exit.toString()
            frames.release(1000)
        }

        fun await() {
            check(frames.tryAcquire(10, TimeUnit.SECONDS)) { "no frame request" }
            failure?.let { error(it) }
        }
    }
}
