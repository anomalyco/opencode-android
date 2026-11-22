// Writes each workload's step output as JSON and as the binary format, and a live workload's first posts both ways.
// Args: <workloads dir> <out dir>.
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private val json = Json { ignoreUnknownKeys = true }
private val STEP = RunOptions(250uL, 4uL * 1024uL * 1024uL, 33_554_432uL)
private val encoder = ValueEncoder.widget()
private val options = LiveOptions(50uL, 1_000_000u, null, false, 33_554_432uL)

/** A live run's posts and its frame requests. */
private class Posts : LiveHandler {
    val frames = Semaphore(0)
    val texts = ArrayList<String>()
    val bytes = ArrayList<ByteArray>()
    var failure: String? = null

    override fun onPosts(messagesJson: List<String>) {
        texts += messagesJson
        if (messagesJson.any { ((json.parseToJsonElement(it) as JsonObject)["type"] as JsonPrimitive).content == "frame" }) frames.release()
    }

    override fun onPostsBytes(messages: List<ByteArray>) {
        bytes += messages
        if (messages.any { it.size >= 2 && (it[1].toInt() == 3 || it[1].toInt() == 9) }) frames.release()
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

private fun frame(t: Double) =
    "{\"type\":\"frame\",\"frame\":{\"t\":$t,\"dt\":0.016666666666666666,\"width\":600,\"height\":320,\"scale\":2,\"pointer\":null,\"dark\":false,\"fullscreen\":false,\"close\":null}}"

fun main(args: Array<String>) {
    val dir = File(args[0])
    val out = File(args[1]).apply { mkdirs() }
    val prelude = File(dir, "prelude.js").readText()
    fun template(name: String) = prelude + File(dir, "templates/$name.js").readText()
    val workloads = json.parseToJsonElement(File(dir, "workloads.json").readText()).jsonObject
    for (w in workloads.getValue("step").jsonArray.map { it.jsonObject }) {
        val name = w.getValue("name").jsonPrimitive.content
        val input = json.parseToJsonElement(File(dir, "inputs/$name.json").readText()).jsonObject
        val own = JsonObject(input.filterKeys { it != "data" }).toString()
        val fields = "{\"data\":${input["data"]}}"
        val source = template(w.getValue("program").jsonPrimitive.content)
        File(out, "$name.json").writeText(Program(source + "\nreturn __widget_step(__input())", "__input").session(fields).run(own, STEP))
        File(out, "$name.bin").writeBytes(Program(source + "\n${encoder.emitName()}(__widget_step(__input()))", "__input").session(fields).runBytes(own, encoder, STEP))
    }
    for (w in workloads.getValue("live").jsonArray.map { it.jsonObject }) {
        val name = w.getValue("name").jsonPrimitive.content
        val program = Program(template(w.getValue("program").jsonPrimitive.content) + "\nreturn await __widget_live(__input())", "__input")
        // The first two frames' posts as JSON; live programs use Math.random and the clock, so the bytes are the
        // same values posted again through the encoder rather than a second run.
        val json = Posts()
        val run = program.startLive(File(dir, "inputs/$name.json").readText(), json, options, null, null)
        json.await()
        repeat(2) { run.deliver(frame(1 + it / 60.0)); json.await() }
        run.stop()
        val bin = Posts()
        Program("for (const p of __input()) __host.post(p)\n__host.post({ type: \"frame\" })\nreturn null", "__input")
            .startLive(json.texts.joinToString(",", "[", "]"), bin, options, null, encoder)
        bin.await()
        File(out, "$name.posts.json").writeText(json.texts.joinToString(",", "[", "]"))
        val stream = ByteArrayOutputStream()
        for (b in bin.bytes.dropLast(1)) {
            for (i in 0 until 4) stream.write((b.size ushr (8 * i)) and 0xff)
            stream.write(b)
        }
        File(out, "$name.posts.bin").writeBytes(stream.toByteArray())
    }
}
