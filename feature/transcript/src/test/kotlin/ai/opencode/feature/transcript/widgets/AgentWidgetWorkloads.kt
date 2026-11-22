package ai.opencode.feature.transcript.widgets

import com.anomalyco.psychoscript.LiveExit
import com.anomalyco.psychoscript.LiveHandler
import com.anomalyco.psychoscript.LiveOptions
import com.anomalyco.psychoscript.Program
import com.anomalyco.psychoscript.RunOptions
import com.anomalyco.psychoscript.ValueEncoder
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
 * psychoscript-mobile's committed workloads (`tests/fixtures/workloads` in the `.psychoscript` checkout), run on the
 * runtime's desktop build (`scripts/psychoscript.sh --host`): each step's output as JSON and as the binary format, and a
 * live workload's first posts both ways. [missing] says why they can't run.
 */
internal object AgentWidgetWorkloads {
    class Step(val name: String, val draw: Boolean, val json: String, val bytes: ByteArray)
    /** [posts] from a JSON run, [bytes] from a native one; [deterministic] when two JSON runs posted the same. */
    class Live(val name: String, val posts: List<String>, val bytes: List<ByteArray>, val deterministic: Boolean)

    private val dir = System.getProperty("opencode.widgetWorkloads")?.let(::File)

    /** Why the workloads can't run here (a skip message), or null. */
    val missing: String? = run {
        val host = System.getProperty("jna.library.path")?.let(::File)
        when {
            host?.listFiles()?.any { it.name.startsWith("libpsychoscript_mobile.") } != true ->
                "no desktop build of psychoscript-mobile in vendor/psychoscript/host: run scripts/psychoscript.sh --host"
            dir == null || !File(dir, "workloads.json").isFile ->
                "no psychoscript-mobile workloads at $dir: run scripts/psychoscript.sh --host"
            else -> null
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val STEP = RunOptions(250uL, 4uL * 1024uL * 1024uL, 33_554_432uL)
    private val OPTIONS = LiveOptions(50uL, 1_000_000u, null, false, 33_554_432uL)
    private val encoder by lazy { ValueEncoder.widget() }
    private val workloads by lazy { json.parseToJsonElement(File(dir, "workloads.json").readText()).jsonObject }
    private val prelude by lazy { File(dir, "prelude.js").readText() }

    private fun template(name: String) = prelude + File(dir, "templates/$name.js").readText()

    val steps: List<Step> by lazy {
        workloads.getValue("step").jsonArray.map { it.jsonObject }.map { w ->
            val name = w.getValue("name").jsonPrimitive.content
            val input = json.parseToJsonElement(File(dir, "inputs/$name.json").readText()).jsonObject
            val own = JsonObject(input.filterKeys { it != "data" }).toString()
            val fields = "{\"data\":${input["data"]}}"
            val source = template(w.getValue("program").jsonPrimitive.content)
            Step(
                name,
                w.getValue("phase").jsonPrimitive.content == "draw",
                Program(source + "\nreturn __widget_step(__input())", "__input").session(fields).run(own, STEP),
                Program(source + "\n${encoder.emitName()}(__widget_step(__input(), true))", "__input").session(fields).runBytes(own, encoder, STEP),
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
            val source = template(w.getValue("program").jsonPrimitive.content)
            val input = File(dir, "inputs/$name.json").readText()
            fun run(native: Boolean): Posts {
                val posts = Posts()
                val program = Program(source + "\nreturn await __widget_live(__input()${if (native) ", true" else ""})", "__input")
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
