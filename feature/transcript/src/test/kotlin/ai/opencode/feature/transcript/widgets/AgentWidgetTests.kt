package ai.opencode.feature.transcript.widgets

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.ui.OpenCodeColors
import ai.opencode.core.ui.AppTheme
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.Closed
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.Effect
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.Event
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.Phase
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.RespondFailure
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.Status
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeWidgetMessageValue
import ai.opencode.server.model.OpenCodeWidgetProgram
import ai.opencode.server.model.OpenCodeWidgetRef
import ai.opencode.server.model.OpenCodeWidgetResponse
import ai.opencode.core.testing.eventually
import com.anomalyco.psychoscript.PsychoScriptException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Agent widgets: the step output and draw command readers, the widget machine, and reading a widget from its tool call. */
class AgentWidgetTests {
    @get:Rule val isolation = TestIsolationRule()

    // Step output

    @Test
    fun readsAStepOutputAndDropsUnknownNodes() {
        val out = stepOutput(
            """
            {"state":{"picked":1},"view":{"type":"column","gap":2,"children":[
              {"type":"text","text":"Hi","style":"title","tone":"accent"},
              {"type":"sparkle"},
              [{"type":"divider"}, null, false],
              {"type":"slider","id":"s","value":3,"min":0,"max":10,"step":1},
              {"type":"select","id":"area","options":[{"value":"n","label":"North"}],"value":"n"}
            ]}}
            """.trimIndent(),
        )
        val column = out.view as AgentWidgetNode.Column
        assertEquals(2, column.gap)
        assertEquals(AgentWidgetNode.Align.Stretch, column.align)
        assertEquals(4, column.children.size)
        assertEquals(AgentWidgetNode.Text("Hi", AgentWidgetNode.TextStyle.Title, AgentWidgetNode.Tone.Accent), column.children[0])
        assertEquals(AgentWidgetNode.Divider, column.children[1])
        assertEquals("""{"picked":1}""", out.state)
        assertNull(out.response)
    }

    @Test
    fun readsSubmitDismissAndSend() {
        val view = """"view":{"type":"divider"}"""
        assertEquals(OpenCodeWidgetResponse.Submit("""{"ok":true}"""), stepOutput("""{"state":{},$view,"submit":{"ok":true}}""").response)
        assertEquals(OpenCodeWidgetResponse.Submit("null"), stepOutput("""{"state":{},$view,"submit":null}""").response)
        assertEquals(OpenCodeWidgetResponse.Dismiss, stepOutput("""{"state":{},$view,"dismiss":true}""").response)
        val sent = stepOutput("""{"state":{},$view,"send":{"value":2,"wake":false}}""")
        assertNull(sent.response)
        assertEquals(OpenCodeWidgetMessageValue("2", wake = false), sent.message)
    }

    @Test
    fun rejectsAnInvalidView() {
        for (text in listOf("""{"state":{},"view":{"type":"sparkle"}}""", """{"state":{},"view":{"type":"button","id":"x"}}""", """{"state":{}}""")) {
            val failure = runCatching { stepOutput(text) }.exceptionOrNull()
            assertTrue(text, failure is AgentWidgetNode.InvalidView)
        }
    }

    /** widgets.md, Canvas: drawn one way; `wgsl` makes it a GPU canvas (fps 60 by default), `values` need `wgsl`. */
    @Test
    fun readsACanvasDrawnOneWay() {
        fun canvas(props: String) = stepOutput("""{"state":{},"view":{"type":"canvas","height":120$props}}""").view as AgentWidgetNode.Canvas
        val flat = canvas("")
        assertTrue(!flat.gpu)
        assertTrue(!flat.pointer)
        assertTrue(canvas(""","id":"0/:a","pointer":true""").pointer)
        assertEquals(0, flat.fps)
        assertNull(flat.values)
        val gpu = canvas(""","id":"0/:sky","wgsl":"fn shade(frag: vec2f) -> vec4f { return vec4f(1.0); }","values":[0.5,2],"poster":"sky.png","duration":3""")
        assertTrue(gpu.gpu)
        assertEquals(60, gpu.fps)
        assertEquals(16, gpu.values?.size)
        assertEquals(listOf(0.5f, 2f, 0f), gpu.values?.take(3))
        assertEquals("sky.png", gpu.poster)
        assertEquals(3f, gpu.duration)
        assertEquals(0, canvas(""","wgsl":"fn shade(frag: vec2f) -> vec4f { return vec4f(1.0); }","fps":0""").fps)
        // Only values change: the same node apart from them.
        assertTrue(gpu != canvas(""","id":"0/:sky","wgsl":"fn shade(frag: vec2f) -> vec4f { return vec4f(1.0); }","values":[0.6,2],"poster":"sky.png","duration":3"""))
        assertEquals(gpu, canvas(""","id":"0/:sky","wgsl":"fn shade(frag: vec2f) -> vec4f { return vec4f(1.0); }","values":[0.5,2],"poster":"sky.png","duration":3"""))
        for (props in listOf(""","values":[1]""", ""","wgsl":"${"x".repeat(AgentWidgetNode.MAX_WGSL_BYTES + 1)}"""")) {
            val failure = runCatching { canvas(props) }.exceptionOrNull()
            assertTrue(props.take(40), failure is AgentWidgetNode.InvalidView)
        }
        // The node type is gone.
        assertTrue(runCatching { stepOutput("""{"state":{},"view":{"type":"shader","height":1,"wgsl":""}}""") }.exceptionOrNull() is AgentWidgetNode.InvalidView)
    }

    /**
     * widgets.md, Stack: a HUD over a GPU canvas. `place` and `inset` are read off each child; a stack with a `fill`
     * canvas child grows in full screen and offers it; only the button and the canvas with `pointer: true` take pointer
     * input, not a canvas with just an id (a key, for live `widget.draw`).
     */
    @Test
    fun readsAStackWithPlaceAndInset() {
        val view = stepOutput(
            """
            {"state":{},"view":{"type":"stack","children":[
              {"type":"canvas","id":"0/:sky","pointer":true,"height":200,"wgsl":"fn shade(frag: vec2f) -> vec4f { return vec4f(1.0); }","place":"fill"},
              {"type":"text","text":"Score 3","inset":12},
              [{"type":"button","id":"0/1","label":"Jump","place":"bottom-right","inset":{"right":12,"bottom":8}}],
              {"type":"canvas","height":20,"place":"sideways","inset":-3},
              {"type":"canvas","id":"0/:fx","height":20,"place":"top-right"}
            ]}}
            """.trimIndent(),
        ).view as AgentWidgetNode.Stack
        assertEquals(5, view.children.size)
        assertEquals(
            listOf(
                AgentWidgetNode.Layer(AgentWidgetNode.Place.Fill),
                AgentWidgetNode.Layer(AgentWidgetNode.Place.TopLeft, 12f, 12f, 12f, 12f),
                AgentWidgetNode.Layer(AgentWidgetNode.Place.BottomRight, top = 0f, right = 12f, bottom = 8f, left = 0f),
                AgentWidgetNode.Layer(AgentWidgetNode.Place.TopLeft),
                AgentWidgetNode.Layer(AgentWidgetNode.Place.TopRight),
            ),
            view.layers,
        )
        assertTrue(AgentWidgetNode.fills(view))
        assertTrue(AgentWidgetNode.drawsPixels(view))
        assertEquals(listOf(true, false, true, false, false), view.children.map(AgentWidgetNode::takesPointer))
        // Every canvas child with `fill: false` keeps the stack at its height.
        val fixed = stepOutput("""{"state":{},"view":{"type":"stack","children":[{"type":"canvas","height":90,"fill":false},{"type":"text","text":"Hi"}]}}""").view
        assertTrue(!AgentWidgetNode.fills(fixed))
        assertTrue(AgentWidgetNode.drawsPixels(fixed))
    }

    /**
     * widgets-input.md: keyboard focus (its ring and the Esc hint) only with a reported hardware keyboard. A phone's or
     * the emulator's built-in keyboard and the virtual one don't count; a USB or Bluetooth keyboard and a Chromebook do.
     */
    @Test
    fun keyboardFocusNeedsAReportedHardwareKeyboard() {
        val virtual = AgentWidgetHardwareKeyboard.Device(alphabetic = true, external = false, virtual = true)
        val builtIn = AgentWidgetHardwareKeyboard.Device(alphabetic = true, external = false, virtual = false)
        val buttons = AgentWidgetHardwareKeyboard.Device(alphabetic = false, external = false, virtual = false)
        val usb = AgentWidgetHardwareKeyboard.Device(alphabetic = true, external = true, virtual = false)
        val headset = AgentWidgetHardwareKeyboard.Device(alphabetic = false, external = true, virtual = false)
        assertTrue(!AgentWidgetHardwareKeyboard.isAttached(reported = false, listOf(virtual, buttons), chromeOS = false))
        assertTrue(!AgentWidgetHardwareKeyboard.isAttached(reported = true, listOf(virtual, buttons, builtIn), chromeOS = false))
        assertTrue(!AgentWidgetHardwareKeyboard.isAttached(reported = true, listOf(virtual, headset), chromeOS = false))
        assertTrue(AgentWidgetHardwareKeyboard.isAttached(reported = true, listOf(virtual, builtIn, usb), chromeOS = false))
        assertTrue(AgentWidgetHardwareKeyboard.isAttached(reported = true, listOf(builtIn), chromeOS = true))
        assertTrue(!AgentWidgetHardwareKeyboard.isAttached(reported = false, listOf(usb), chromeOS = true))
    }

    /**
     * Steps and draws run within the same 32 MB heap as live runs; going over it is the widget template's error,
     * reported as one plain sentence without a position.
     */
    @Test
    fun stepsAndDrawsShareTheLiveHeapCap() {
        assertEquals(33_554_432uL, NativeAgentWidgetEngine.STEP.maxHeapBytes)
        assertEquals(33_554_432uL, NativeAgentWidgetEngine.DRAW.maxHeapBytes)
        for (draw in listOf(false, true)) {
            val error = NativeAgentWidgetEngine.mapped(PsychoScriptException.MemoryLimitExceeded("heap limit exceeded (line 3, column 9)", 33_554_432uL), draw)
            assertEquals("It used more than 32 MB of memory", error.message)
            assertNull(error.line)
            assertTrue(error.isProgramError())
            assertTrue(!error.isTimeout && !error.isBudget)
        }
    }

    // Binary format (specs/v2/widgets-binary.md)

    private fun assertSameDrawing(name: String, a: AgentWidgetDrawList, b: AgentWidgetDrawList) {
        assertEquals(name, a.commandCount, b.commandCount)
        assertEquals(name, a.ints.copyOf(a.intCount).toList(), b.ints.copyOf(b.intCount).toList())
        assertEquals(name, a.floats.copyOf(a.floatCount).map { it.toRawBits() }, b.floats.copyOf(b.floatCount).map { it.toRawBits() })
        assertEquals(name, a.strings, b.strings)
    }

    /** The runtime's encoder names the phase of an output it can't write (widgets-binary.md, Errors). */
    @Test
    fun theEncodersFailuresNameTheirPhase() {
        fun phase(kind: String) = NativeAgentWidgetEngine.mapped(PsychoScriptException.Runtime(kind, "invalid view: missing field `label`", 3u, 5u), draw = false)
        assertEquals("view", phase("InvalidView").phase)
        assertEquals("draw", phase("InvalidDraw").phase)
        assertEquals("gpu", phase("InvalidGpu").phase)
        assertEquals("main", phase("InvalidDataValue").phase)
        assertNull(phase("TypeError").phase)
        assertTrue(phase("InvalidView").isProgramError())
        // Another widget protocol: an older program is its own error (reported in the refused phase), a newer one the
        // app's; neither can be retried.
        val older = phase("OlderProtocol")
        assertEquals(AgentWidgetEngineException.Protocol.Older, older.protocol)
        assertNull(older.phase)
        assertTrue(older.isProgramError())
        val newer = phase("NewerProtocol")
        assertEquals(AgentWidgetEngineException.Protocol.Newer, newer.protocol)
        assertFalse(newer.isProgramError())
        assertEquals("This widget was built with an older widget runtime; ask the agent to post it again", AgentWidgetMachine.message(older))
        assertEquals("This widget needs a newer version of this app", AgentWidgetMachine.message(newer))
        val (failed, _) = AgentWidgetMachine.Machine("{}", Phase.Starting(Status.Open)).handle(Event.Ran(Result.failure(older)))
        assertEquals(Phase.Failed("This widget was built with an older widget runtime; ask the agent to post it again", Status.Open, retryable = false), failed.phase)
        val (plain, _) = AgentWidgetMachine.Machine("{}", Phase.Starting(Status.Open)).handle(Event.Ran(Result.failure(AgentWidgetEngineException("boom"))))
        assertEquals(true, (plain.phase as Phase.Failed).retryable)
    }

    /**
     * Views go 100 levels deep (widgets.md, Keys and depth): a 100-level view crosses, is written and decodes; the
     * 101-level fixtures (widget-wire `tests/fixtures/wire`) fail in phase view. Skipped without the desktop runtime.
     */
    @Test
    fun viewsGoOneHundredLevelsDeep() {
        org.junit.Assume.assumeTrue(AgentWidgetWorkloads.missing ?: "", AgentWidgetWorkloads.missing == null)
        val (source, input, error) = AgentWidgetWorkloads.fixture("depth-101")
        assertEquals("The view nests more than 100 levels", (error?.get("message") as kotlinx.serialization.json.JsonPrimitive).content)
        val hundred = source.replace("level < 101", "level < 100")
        assertTrue(hundred != source)
        var node = AgentWidgetWire.output(AgentWidgetWorkloads.nativeStep(hundred, input)).view
        var levels = 1
        while (node is AgentWidgetNode.Column) {
            node = node.children.single()
            levels++
        }
        assertEquals(100, levels)
        assertTrue(node is AgentWidgetNode.Button)
        for (name in listOf("depth-101", "depth-101-in-arrays")) {
            val (deep, deepInput, _) = AgentWidgetWorkloads.fixture(name)
            val failure = runCatching { AgentWidgetWorkloads.nativeStep(deep, deepInput) }.exceptionOrNull()
            val mapped = NativeAgentWidgetEngine.mapped(failure as PsychoScriptException, draw = false)
            assertEquals(name, "view", mapped.phase)
            assertEquals(name, "The view nests more than 100 levels", programMessage(mapped.message!!))
        }
    }

    /** A step output stated as JSON, written as the runtime's encoder would and read by the app. */
    private fun stepOutput(text: String): AgentWidgetOutput = AgentWidgetWire.output(AgentWidgetWireEncoder.output(text))

    /** A draw step's `{ commands }` stated as JSON, read into [list]. */
    private fun drawOutput(text: String, list: AgentWidgetDrawList) = AgentWidgetWire.drawOutput(AgentWidgetWireEncoder.output(text), palette, list)

    /**
     * psychoscript-mobile's committed workloads, run on its desktop build: the runtime's bytes (the native tree) decode
     * to the values the tests' encoder writes from the same program's JSON output (every node and prop, step field, draw
     * stream, and the live posts of workloads that run the same twice). Skipped without the desktop build or the
     * workloads (scripts/psychoscript.sh --host).
     */
    @Test
    fun binaryWorkloadsMatchTheRuntimesEncoder() {
        org.junit.Assume.assumeTrue(AgentWidgetWorkloads.missing ?: "", AgentWidgetWorkloads.missing == null)
        assertTrue(AgentWidgetWorkloads.steps.any { it.draw } && AgentWidgetWorkloads.steps.any { !it.draw } && AgentWidgetWorkloads.live.isNotEmpty())
        val json = Json { ignoreUnknownKeys = true }
        for (step in AgentWidgetWorkloads.steps) {
            val name = step.name
            if (step.draw) {
                val a = AgentWidgetDrawList().also { AgentWidgetWire.drawOutput(step.bytes, palette, it) }
                val b = AgentWidgetDrawList().also { drawOutput(step.json, it) }
                assertTrue(name, a.commandCount > 0)
                assertSameDrawing(name, a, b)
            } else {
                val a = AgentWidgetWire.output(step.bytes)
                val b = stepOutput(step.json)
                assertEquals(name, a.view, b.view)
                assertEquals(name, json.parseToJsonElement(a.state), json.parseToJsonElement(b.state))
                assertEquals(name, a.response, b.response)
                assertEquals(name, a.message, b.message)
                assertEquals(name, a.fullscreen, b.fullscreen)
            }
        }
        assertTrue(AgentWidgetWorkloads.live.any { it.deterministic })
        for (live in AgentWidgetWorkloads.live) {
            if (!live.deterministic) {
                // Random or timed content: the native run's posts decode, draws too.
                for (b in live.bytes) decodeAny(b, post = true)
                continue
            }
            assertEquals(live.name, live.posts.size, live.bytes.size)
            live.posts.zip(live.bytes).forEachIndexed { i, (text, b) ->
                val at = "${live.name} post $i"
                val ours = AgentWidgetWire.post(AgentWidgetWireEncoder.post(text))
                when (val post = AgentWidgetWire.post(b)) {
                    is AgentWidgetWire.Post.Render -> assertEquals(at, post.view, (ours as AgentWidgetWire.Post.Render).view)
                    is AgentWidgetWire.Post.Draw -> {
                        assertEquals(at, post.id, (ours as AgentWidgetWire.Post.Draw).id)
                        val a = AgentWidgetDrawList().also { AgentWidgetWire.draw(post.message, post.section, palette, it) }
                        val d = AgentWidgetDrawList().also { AgentWidgetWire.draw(ours.message, ours.section, palette, it) }
                        assertSameDrawing(at, a, d)
                    }
                    AgentWidgetWire.Post.Frame -> assertEquals(at, AgentWidgetWire.Post.Frame, ours)
                    else -> throw AssertionError("$at: $post")
                }
            }
        }
    }

    /** Messages that use every node type, prop kind, draw op, paint and post kind, written by the tests' encoder. */
    private val wireSamples: List<Pair<ByteArray, Boolean>> by lazy {
        val view = """
            {"type":"column","gap":2,"align":"stretch","fullscreen":true,"children":[
              {"type":"row","wrap":true,"align":"center","children":[{"type":"text","text":"Hi ✓","style":"title","tone":"accent"},{"type":"markdown","text":"**b**"}]},
              {"type":"image","src":"a.png","alt":"A","aspect":1.5},
              {"type":"carousel","id":"0/2","index":1,"children":[{"type":"card","id":"0/2/0","selected":true,"children":[{"type":"divider"}]}]},
              {"type":"button","id":"0/3","label":"Go","variant":"danger","disabled":true},
              {"type":"checkbox","id":"0/4","label":"On","checked":true},
              {"type":"slider","id":"0/5","label":"S","value":3,"min":0,"max":10,"step":0.5},
              {"type":"select","id":"0/6","options":[{"value":"a","label":"A","description":"d"},{"value":"b","label":"B"}],"value":["a","b"],"multiple":true},
              {"type":"input","id":"0/7","value":"x","placeholder":"p","multiline":true},
              {"type":"stack","children":[
                {"type":"canvas","id":"0/8/0","pointer":true,"height":120,"width":200,"fps":30,"duration":2,"alt":"c","poster":"p.png","wgsl":"fn shade(frag: vec2f) -> vec4f { return vec4f(1.0); }","values":[1,2,3],"fill":false,"place":"fill"},
                {"type":"text","text":"HUD","place":"bottom-right","inset":{"top":1,"right":2,"bottom":3,"left":4}},
                {"type":"canvas","height":20,"inset":5}
              ]}
            ]}
        """.trimIndent()
        val commands = """
            [["save"],["translate",1,2],["rotate",0.5],["scale",2,2],["alpha",0.5],
             ["rect",0,0,10,20,{"fill":"#f00","stroke":"muted","width":2,"cap":"round"}],
             ["rrect",0,0,10,20,3,{"fill":{"linear":[0,0,10,0],"stops":[[0,"#000"],[1,"accent"]]}}],
             ["circle",5,5,4,{"fill":{"radial":[5,5,4],"stops":[[0,"#ffffff80"],[0.5,"bg"],[1,"error"]]}}],
             ["line",0,0,5,5,{"stroke":"text"}],
             ["path",[["M",0,0],["L",1,1],["Q",1,2,3,4],["C",1,2,3,4,5,6],["Z"]],{"fill":"surface"}],
             ["text",1,2,"Mon",{"size":11,"weight":700,"align":"right","color":"success","mono":true}],
             ["image","a.png",0,0,4,4],["restore"]]
        """.trimIndent()
        listOf(
            AgentWidgetWireEncoder.output("""{"state":{"n":[1,"two"]},"view":$view,"submit":{"ok":true},"send":{"value":"m","wake":false}}""") to false,
            AgentWidgetWireEncoder.output("""{"commands":$commands}""") to false,
            AgentWidgetWireEncoder.post("""{"type":"render","node":$view}""") to true,
            AgentWidgetWireEncoder.post("""{"type":"draw","id":"0/:board","commands":$commands}""") to true,
            AgentWidgetWireEncoder.post("""{"type":"frame"}""") to true,
            AgentWidgetWireEncoder.post("""{"type":"submit","value":{"score":4}}""") to true,
            AgentWidgetWireEncoder.post("""{"type":"dismiss"}""") to true,
            AgentWidgetWireEncoder.post("""{"type":"send","value":[1],"wake":false}""") to true,
            AgentWidgetWireEncoder.post("""{"type":"capture","value":true}""") to true,
        )
    }

    /** Decodes [bytes] the way the app reads them: a step or draw output, or a live post (a draw's commands too). */
    private fun decodeAny(bytes: ByteArray, post: Boolean) {
        if (post) {
            val p = AgentWidgetWire.post(bytes)
            if (p is AgentWidgetWire.Post.Draw) AgentWidgetWire.draw(p.message, p.section, palette, AgentWidgetDrawList())
        } else if (bytes.size > 1 && bytes[1] == 'D'.code.toByte()) {
            AgentWidgetWire.drawOutput(bytes, palette, AgentWidgetDrawList())
        } else {
            AgentWidgetWire.output(bytes)
        }
    }

    /**
     * Malformed bytes are a client error and nothing else: every truncation of each message (the samples, and the
     * workloads when the desktop runtime is here) fails as
     * [AgentWidgetWire.Malformed], and random corruptions either decode or fail as malformed or as an invalid view
     * (a corrupted but well-formed message), never with another exception.
     */
    @Test
    fun malformedBytesAreAClientErrorUnderFuzzing() {
        val random = java.util.Random(0x51DE)
        val workloads = if (AgentWidgetWorkloads.missing == null) {
            AgentWidgetWorkloads.steps.map { it.bytes to false } + AgentWidgetWorkloads.live.flatMap { live -> live.bytes.map { it to true } }
        } else emptyList()
        val messages = wireSamples + workloads
        var corrupted = 0
        for ((bytes, post) in messages) {
            decodeAny(bytes, post)
            val cuts = if (bytes.size <= 4096) (0 until bytes.size).toList() else (0 until 256).toList() + (256 until bytes.size step bytes.size / 1500)
            for (n in cuts) {
                val failure = runCatching { decodeAny(bytes.copyOf(n), post) }.exceptionOrNull()
                assertTrue("a ${bytes.size}-byte message cut to $n: $failure", failure is AgentWidgetWire.Malformed)
            }
            repeat(if (bytes.size > 50_000) 60 else 250) {
                val copy = bytes.copyOf()
                repeat(1 + random.nextInt(3)) { copy[random.nextInt(copy.size)] = random.nextInt(256).toByte() }
                val failure = runCatching { decodeAny(copy, post) }.exceptionOrNull()
                if (failure != null) corrupted++
                assertTrue("a corrupted message: $failure", failure == null || failure is AgentWidgetWire.Malformed || failure is AgentWidgetNode.InvalidView)
            }
        }
        assertTrue(corrupted > 0)
    }

    /** A message written by hand: `01`, then [body]. */
    private class Wire {
        val out = java.io.ByteArrayOutputStream()
        fun u8(vararg v: Int) = apply { v.forEach { out.write(it) } }
        fun varint(v: Long) = apply {
            var x = v
            while (x >= 0x80) { out.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }
            out.write(x.toInt())
        }
        fun str(s: String) = apply { val b = s.encodeToByteArray(); varint(b.size.toLong()); out.write(b) }
        fun u32(v: Long) = apply { for (i in 0 until 4) out.write(((v ushr (8 * i)) and 0xff).toInt()) }
        fun bytes(b: ByteArray) = apply { out.write(b) }
        fun build(): ByteArray = out.toByteArray()

        companion object {
            /** Section N from its ints (varints), floats and strings. */
            fun view(ints: List<Long>, floats: List<Double> = emptyList(), strs: List<String> = emptyList()): ByteArray {
                val i = Wire().apply { ints.forEach { varint(it) } }.build()
                val f = Wire().apply { floats.forEach { val bits = it.toRawBits(); for (k in 0 until 8) u8(((bits ushr (8 * k)) and 0xff).toInt()) } }.build()
                val s = Wire().apply { strs.forEach { str(it) } }.build()
                return Wire().u8('N'.code).varint(i.size.toLong()).varint(f.size.toLong()).varint(s.size.toLong()).bytes(i).bytes(f).bytes(s).build()
            }

            fun step(view: ByteArray, flags: Int = 0, state: String = "{}") = Wire().u8(1, 'S'.code, flags).str(state).bytes(view).build()
        }
    }

    private fun tag(id: Int, kind: Int) = (id shl 3 or kind).toLong()

    /** The format's edges: version, unknown props and types, GPU batches, flags, nesting and which error wins. */
    @Test
    fun binaryMessagesFollowTheFormatsRules() {
        fun malformed(block: () -> Unit) = assertTrue(runCatching(block).exceptionOrNull() is AgentWidgetWire.Malformed)
        fun invalid(block: () -> Unit) = assertTrue(runCatching(block).exceptionOrNull() is AgentWidgetNode.InvalidView)
        // A text node with an unknown prop (id 60, a string): skipped by its kind.
        val text = Wire.view(listOf(3, tag(4, 2), tag(60, 2), 0), strs = listOf("Hi", "later"))
        assertEquals(AgentWidgetNode.Text("Hi", AgentWidgetNode.TextStyle.Body, AgentWidgetNode.Tone.Default), AgentWidgetWire.output(Wire.step(text)).view)
        // Another version.
        malformed { AgentWidgetWire.output(Wire.step(text).also { it[0] = 2 }) }
        // A column holding an unknown type ("sparkle", dropped) and a typeless node (an invalid view).
        val sparkle = Wire.view(listOf(1, 0, 2, 63, 0, 3, tag(4, 2), 0), strs = listOf("sparkle", "Hi"))
        assertEquals(1, AgentWidgetWire.output(Wire.step(sparkle)).view.children.size)
        invalid { AgentWidgetWire.output(Wire.step(Wire.view(listOf(1, 0, 1, 63, 0), strs = listOf("")))) }
        invalid { AgentWidgetWire.output(Wire.step(Wire.view(listOf(63, 0), strs = listOf("sparkle")))) }
        // A button without its label is invalid, but bytes left in a stream make the message malformed first.
        val button = listOf(8L, tag(10, 2), 0)
        invalid { AgentWidgetWire.output(Wire.step(Wire.view(button, strs = listOf("0/1")))) }
        malformed { AgentWidgetWire.output(Wire.step(Wire.view(button + 0, strs = listOf("0/1")))) }
        // A boolean is 0 or 1; a submit flag carries its JSON, which must be JSON.
        malformed { AgentWidgetWire.output(Wire.step(Wire.view(listOf(2, tag(3, 0), 2, 0, 0)))) }
        assertEquals(OpenCodeWidgetResponse.Submit("{\"ok\":true}"), AgentWidgetWire.output(Wire().u8(1, 'S'.code, 1).str("{}").str("{\"ok\":true}").bytes(text).build()).response)
        malformed { AgentWidgetWire.output(Wire().u8(1, 'S'.code, 1).str("{}").str("{oops").bytes(text).build()) }
        // A stack child's place and inset (the list form: top, right, bottom, left).
        val stack = Wire.view(listOf(16, 0, 1, 13, tag(36, 0), 8, tag(37, 3), 4, 0), floats = listOf(1.0, 2.0, 3.0, 4.0))
        assertEquals(
            AgentWidgetNode.Layer(AgentWidgetNode.Place.BottomRight, 1f, 2f, 3f, 4f),
            (AgentWidgetWire.output(Wire.step(stack)).view as AgentWidgetNode.Stack).layers.single(),
        )
        // Nesting past 100 levels.
        val deep = (0..101).flatMap { listOf(1L, 0L, 1L) } + listOf(13L, 0L)
        malformed { AgentWidgetWire.output(Wire.step(Wire.view(deep))) }
        // GPU batches are the runtime's: a frame request with one is a frame request, one on its own is nothing.
        fun gpu(bytes: Long) = Wire().u8('G'.code).u32(1).varint(0).u32(bytes).bytes(ByteArray(minOf(bytes, 16).toInt())).build()
        assertEquals(AgentWidgetWire.Post.Frame, AgentWidgetWire.post(Wire().u8(1, 9).bytes(gpu(16)).build()))
        assertEquals(AgentWidgetWire.Post.Gpu, AgentWidgetWire.post(Wire().u8(1, 10).bytes(gpu(16)).build()))
        malformed { AgentWidgetWire.post(Wire().u8(1, 10).bytes(gpu(5L * 1024 * 1024)).build()) }
        malformed { AgentWidgetWire.post(Wire().u8(1, 11).build()) }
        // Send and capture.
        val send = AgentWidgetWire.post(Wire().u8(1, 6, 0).str("[1,2]").build()) as AgentWidgetWire.Post.Send
        assertEquals("[1,2]" to false, send.value to send.wake)
        assertEquals(true, (AgentWidgetWire.post(Wire().u8(1, 7, 1).build()) as AgentWidgetWire.Post.Capture).on)
        // A draw with an op outside the table, and one with too many commands.
        malformed { AgentWidgetWire.drawOutput(Wire().u8(1, 'D'.code).u32(1).u32(1).u32(0).u32(0).u32(99).build(), palette, AgentWidgetDrawList()) }
        malformed { AgentWidgetWire.drawOutput(Wire().u8(1, 'D'.code).u32(2001).u32(0).u32(0).u32(0).build(), palette, AgentWidgetDrawList()) }
    }

    // Draw commands

    private val palette = AgentWidgetDrawList.Palette(IntArray(AgentWidgetDrawList.Token.entries.size) { 0x10 + it })

    @Test
    fun readsDrawCommands() {
        val list = AgentWidgetDrawList()
        drawOutput(
            """
            {"commands":[["save"],["rect",0,0,10,20,{"fill":"#f00"}],["line",0,0,5,5,{"stroke":"muted"}],
              ["text",1,2,"Mon",{"size":11,"align":"center","weight":600}],["image","a.png",0,0,4,4],["restore"]]}
            """.trimIndent(),
            list,
        )
        assertEquals(6, list.commandCount)
        assertEquals(listOf("Mon", "a.png"), list.strings)
        assertEquals(AgentWidgetDrawList.SAVE, list.ints[0])
        assertEquals(AgentWidgetDrawList.RECT, list.ints[1])
        assertEquals(AgentWidgetDrawList.FILL_COLOR, list.ints[2])
        assertEquals(0xFFFF0000.toInt(), list.ints[3])
        assertEquals(listOf(0f, 0f, 10f, 20f), list.floats.take(4))
        assertEquals(AgentWidgetDrawList.LINE, list.ints[7])
        assertEquals(1, list.ints[10])
        assertEquals(palette.tokens[AgentWidgetDrawList.Token.Muted.ordinal], list.ints[11])
    }

    /** More than 2,000 commands is an invalid draw: the runtime's encoder refuses it, a program error in phase `draw`. */
    @Test
    fun rejectsTooManyDrawCommands() {
        fun saves(n: Int) = (1..n).joinToString(",", """{"commands":[""", "]}") { """["save"]""" }
        val list = AgentWidgetDrawList()
        drawOutput(saves(AgentWidgetDrawList.MAX_COMMANDS), list)
        assertEquals(AgentWidgetDrawList.MAX_COMMANDS, list.commandCount)
        assertTrue(runCatching { AgentWidgetWireEncoder.output(saves(AgentWidgetDrawList.MAX_COMMANDS + 1)) }.exceptionOrNull() is AgentWidgetWireEncoder.Invalid)
        // An invalid command fails the draw too, rather than being skipped.
        assertTrue(runCatching { AgentWidgetWireEncoder.output("""{"commands":[["save"],["bogus"]]}""") }.exceptionOrNull() is AgentWidgetWireEncoder.Invalid)
    }

    /** A game awaiting `widget.frame()` when its Done handler submits ends in a deadlock exit right after the submit. */
    @Test
    fun liveSubmitEndsTheRunWithoutReportingItsExit() = runBlocking {
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        val log = CopyOnWriteArrayList<String>()
        val stopped = AtomicBoolean(false)
        val program = object : AgentWidgetLiveProgram {
            override fun start(inputJson: String, listener: AgentWidgetLiveListener, httpsHosts: List<String>?): AgentWidgetLiveRun {
                val run = object : AgentWidgetLiveRun {
                    override fun deliver(messageJson: String) = Unit
                    override fun pause() = Unit
                    override fun resume() = Unit
                    override fun snapshot() = true
                    override fun stop() = stopped.set(true)
                }
                thread {
                    listener.posted(listOf(Wire().u8(1, 4).str("""{"score":40}""").build()))
                    listener.exited(AgentWidgetEngineException("main: Error: The program is waiting on a promise that can never settle: nothing else is running"))
                }
                return run
            }
        }
        val events = object : AgentWidgetLiveDriver.Events {
            override fun rendered(view: AgentWidgetNode, fullscreen: Boolean?) = Unit
            override fun drew(draw: AgentWidgetWire.Post.Draw) = Unit
            override fun wantsFrame() = Unit
            override fun answered(response: OpenCodeWidgetResponse) { log += "answered ${(response as OpenCodeWidgetResponse.Submit).json}" }
            override fun sent(message: OpenCodeWidgetMessageValue) = Unit
            override fun failed(error: AgentWidgetEngineException) { log += "failed" }
            override fun reported(phase: String, message: String) = Unit
            override fun malformed(message: String) = Unit
            override fun ended() { log += "ended" }
        }
        val driver = AgentWidgetLiveDriver(program, "{}", null, scope, events)
        withContext(main) { driver.setVisible(true) }
        assertTrue(eventually(wallClock = true) { log.isNotEmpty() })
        withContext(Dispatchers.IO) { Thread.sleep(300) }
        assertEquals(listOf("""answered {"score":40}"""), log.toList())
        assertTrue(stopped.get())
        assertTrue(!withContext(main) { driver.isRunning })
        scope.cancel()
        main.close()
    }

    /**
     * widgets-live.md, Post rate: renders and draws the runtime held back arrive in a later wake-up, and all of them at
     * the run's end ahead of its submit. The app keeps the latest render and each canvas's latest draw, asks for a
     * frame for each request, and the last view is in place when the submit lands.
     */
    @Test
    fun heldBackPostsKeepTheLatestViewAndDrawings() = runBlocking {
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + main)
        val log = CopyOnWriteArrayList<String>()
        fun render(n: Int) = AgentWidgetWireEncoder.post("""{"type":"render","node":{"type":"text","text":"Frame $n"}}""")
        fun draw(id: String, x: Int) = AgentWidgetWireEncoder.post("""{"type":"draw","id":"$id","commands":[["rect",$x,0,1,1,{"fill":"accent"}]]}""")
        val frame = AgentWidgetWireEncoder.post("""{"type":"frame"}""")
        val program = object : AgentWidgetLiveProgram {
            override fun start(inputJson: String, listener: AgentWidgetLiveListener, httpsHosts: List<String>?): AgentWidgetLiveRun {
                thread {
                    listener.posted(listOf(render(1), draw("0/:a", 1), frame))
                    Thread.sleep(50)
                    listener.posted(listOf(frame))
                    Thread.sleep(50)
                    // The run ends: what it held back, then the submit.
                    listener.posted(listOf(render(2), draw("0/:a", 2), render(3), draw("0/:b", 7), draw("0/:a", 3)))
                    listener.posted(listOf(AgentWidgetWireEncoder.post("""{"type":"submit","value":{"score":3}}""")))
                    listener.exited(null)
                }
                return object : AgentWidgetLiveRun {
                    override fun deliver(messageJson: String) = Unit
                    override fun pause() = Unit
                    override fun resume() = Unit
                    override fun snapshot() = true
                    override fun stop() = Unit
                }
            }
        }
        val events = object : AgentWidgetLiveDriver.Events {
            override fun rendered(view: AgentWidgetNode, fullscreen: Boolean?) { log += "render ${(view as AgentWidgetNode.Text).text}" }
            override fun drew(draw: AgentWidgetWire.Post.Draw) {
                val list = AgentWidgetDrawList().also { AgentWidgetWire.draw(draw.message, draw.section, palette, it) }
                log += "draw ${draw.id} ${list.floats[0].toInt()}"
            }
            override fun wantsFrame() { log += "frame" }
            override fun answered(response: OpenCodeWidgetResponse) { log += "answered" }
            override fun sent(message: OpenCodeWidgetMessageValue) = Unit
            override fun failed(error: AgentWidgetEngineException) { log += "failed" }
            override fun reported(phase: String, message: String) { log += "reported" }
            override fun malformed(message: String) { log += "malformed" }
            override fun ended() { log += "ended" }
        }
        val driver = AgentWidgetLiveDriver(program, "{}", null, scope, events)
        withContext(main) { driver.setVisible(true) }
        assertTrue(eventually(wallClock = true) { "answered" in log })
        withContext(Dispatchers.IO) { Thread.sleep(200) }
        val seen = log.toList()
        assertEquals(seen.toString(), 2, seen.count { it == "frame" })
        assertEquals(seen.toString(), "render Frame 3", seen.last { it.startsWith("render") })
        assertEquals(seen.toString(), "draw 0/:a 3", seen.last { it.startsWith("draw 0/:a") })
        assertEquals(seen.toString(), "draw 0/:b 7", seen.last { it.startsWith("draw 0/:b") })
        assertTrue(seen.toString(), seen.indexOf("render Frame 3") < seen.indexOf("answered"))
        assertEquals(seen.toString(), "answered", seen.last())
        assertTrue(seen.toString(), seen.none { it == "failed" || it == "reported" || it == "malformed" })
        scope.cancel()
        main.close()
    }

    /** A restarted draw effect leaves its cancelled decode running; the next one must not share its list. */
    @Test
    fun canvasDecodeCancelledMidWayNeverWritesTheShownList() = runBlocking {
        val buffers = CanvasDrawBuffers()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = launch(Dispatchers.Default) {
            buffers.update<Boolean>({ list ->
                withContext(Dispatchers.IO) {
                    list.clear()
                    list.int(1)
                    started.countDown()
                    release.await()
                    list.int(2)
                    list.int(3)
                    true
                }
            }) { it }
        }
        withContext(Dispatchers.IO) { started.await() }
        slow.cancel()
        val fast = launch(Dispatchers.Default) {
            buffers.update<Boolean>({ list ->
                withContext(Dispatchers.IO) {
                    list.clear()
                    list.int(9)
                    true
                }
            }) { it }
        }
        delay(150)
        release.countDown()
        slow.join()
        fast.join()
        assertEquals(1, buffers.shown.intCount)
        assertEquals(9, buffers.shown.ints[0])
    }

    /** specs/v2/widgets.md, Failures: only the widget template's own errors are reported; the client's are logged. */
    @Test
    fun onlyProgramErrorsAreReported() {
        assertTrue(AgentWidgetEngineException("x is not defined", line = 3, column = 7).isProgramError())
        assertTrue(AgentWidgetEngineException("ran over 50 ms without waiting").isProgramError())
        assertTrue(AgentWidgetNode.InvalidView("a step must return a view").isProgramError())
        val info = OpenCodeWidgetProgram("main()")
        assertTrue(AgentWidgetPrograms.CompileFailed(AgentWidgetEngineException("Unexpected token"), info).isProgramError())
        val unavailable = runCatching { AgentWidgetEngine.Unavailable.compile("main()") }.exceptionOrNull()!!
        assertFalse(unavailable.isProgramError())
        assertFalse(AgentWidgetPrograms.CompileFailed(unavailable as AgentWidgetEngineException, info).isProgramError())
        assertFalse(AgentWidgetEngineException("snapshot failed", isProgramError = false).isProgramError())
        assertFalse(IllegalStateException("The widget's program isn't loaded.").isProgramError())
    }

    /** The position the runtime appends counts the prelude; the report carries the widget template's own line and column. */
    @Test
    fun aReportedMessageDropsTheRuntimesPosition() {
        assertEquals("ReferenceError: Unknown identifier 'x'.", programMessage("ReferenceError: Unknown identifier 'x'. (line 464, col 66)"))
        assertEquals("Unexpected token", programMessage("Unexpected token"))
    }

    /**
     * The spec's colour tokens in the app's themes: `accent` is a saturated colour white labels read on (the web host's
     * blue), never the OpenCode theme's neutral grey accent; the status tokens keep their hues.
     */
    @Test
    fun widgetTokensKeepTheirIntentInDarkAndLight() {
        fun tokens(theme: AppTheme, dark: Boolean) = AgentWidgetDrawList.Palette.of(OpenCodeColors.resolve(theme, isDark = dark)).tokens
        fun token(tokens: IntArray, token: AgentWidgetDrawList.Token) = tokens[token.ordinal]
        fun rgb(argb: Int) = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
        fun luminance(argb: Int): Double {
            fun channel(c: Int) = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
            val (r, g, b) = rgb(argb)
            return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
        }
        assertEquals(0xFF5B9CFF.toInt(), token(tokens(AppTheme.OpenCode, dark = true), AgentWidgetDrawList.Token.Accent))
        assertEquals(0xFF3B5CF6.toInt(), token(tokens(AppTheme.OpenCode, dark = false), AgentWidgetDrawList.Token.Accent))
        for (theme in listOf(AppTheme.OpenCode, AppTheme.System)) for (dark in listOf(true, false)) {
            val t = tokens(theme, dark)
            val (ar, ag, ab) = rgb(token(t, AgentWidgetDrawList.Token.Accent))
            assertTrue("$theme dark=$dark accent is blue", ab > ar + 60 && ab > ag)
            val (sr, sg, sb) = rgb(token(t, AgentWidgetDrawList.Token.Success))
            assertTrue("$theme dark=$dark success is green", sg > sr && sg > sb)
            val (er, eg, eb) = rgb(token(t, AgentWidgetDrawList.Token.Error))
            assertTrue("$theme dark=$dark error is red", er > eg + 60 && er > eb + 60)
            val (wr, wg, wb) = rgb(token(t, AgentWidgetDrawList.Token.Warning))
            assertTrue("$theme dark=$dark warning is orange", wr > wg && wg > wb)
            val text = luminance(token(t, AgentWidgetDrawList.Token.Text))
            val bg = luminance(token(t, AgentWidgetDrawList.Token.Bg))
            assertTrue("$theme dark=$dark text reads on bg", (maxOf(text, bg) + 0.05) / (minOf(text, bg) + 0.05) > 7)
            assertTrue("$theme dark=$dark bg follows the mode", if (dark) bg < 0.1 else bg > 0.8)
        }
    }

    @Test
    fun alphaSetsOpacityAndRestoreBringsItBack() {
        val opacity = CanvasOpacity()
        opacity.set(0.5f)
        opacity.save()
        opacity.set(0.8f)
        assertEquals(0.8f, opacity.value)
        assertTrue(opacity.restore())
        assertEquals(0.5f, opacity.value)
        opacity.set(2f)
        assertEquals(1f, opacity.value)
        opacity.set(-1f)
        assertEquals(0f, opacity.value)
        assertTrue(!opacity.restore())
        assertEquals(0f, opacity.value)
    }

    /** Colours as the runtime resolves them (`#rgb`, `#rrggbb`, `#rrggbbaa`, the tokens), against the app's palette. */
    @Test
    fun readsColours() {
        fun fill(color: String): Int {
            val list = AgentWidgetDrawList()
            drawOutput("""{"commands":[["rect",0,0,1,1,{"fill":"$color"}]]}""", list)
            return list.ints[2]
        }
        assertEquals(0xFFFFFFFF.toInt(), fill("#fff"))
        assertEquals(0x80000000.toInt(), fill("#00000080"))
        assertEquals(0xFF336699.toInt(), fill("#336699"))
        assertEquals(palette.tokens[AgentWidgetDrawList.Token.Accent.ordinal], fill("accent"))
        assertTrue(runCatching { fill("red") }.exceptionOrNull() is AgentWidgetWireEncoder.Invalid)
    }

    // Machine

    private fun output(state: String = "{}", response: OpenCodeWidgetResponse? = null) =
        AgentWidgetOutput(state, AgentWidgetNode.Divider, response)

    private fun AgentWidgetMachine.Machine.handle(event: Event): Pair<AgentWidgetMachine.Machine, List<Effect>> =
        AgentWidgetMachine.reduce(this, event)

    @Test
    fun runsInitThenStepsThenResponds() {
        var (machine, effects) = AgentWidgetMachine.open("""{"a":1}""", Status.Open)
        assertEquals(listOf(Effect.Fetch), effects)
        machine.handle(Event.Loaded).let { (m, e) -> machine = m; effects = e }
        // Android: `data` lives in the program's run session (AgentWidgetStep.shared), not in each step's input.
        assertEquals(listOf(Effect.Run("""{"phase":"init"}""")), effects)
        machine.handle(Event.Ran(Result.success(output("""{"n":1}""")))).let { (m, e) -> machine = m; effects = e }
        assertEquals(emptyList<Effect>(), effects)
        assertTrue(machine.phase is Phase.Ready)
        machine.handle(Event.Input(AgentWidgetEvent.press("go"))).let { (m, e) -> machine = m; effects = e }
        assertEquals(listOf(Effect.Run("""{"phase":"event","state":{"n":1},"event":{"id":"go","type":"press"}}""")), effects)
        val submit = OpenCodeWidgetResponse.Submit("true")
        machine.handle(Event.Ran(Result.success(output(response = submit)))).let { (m, e) -> machine = m; effects = e }
        assertEquals(listOf(Effect.Respond(submit, attempt = 1)), effects)
        machine.handle(Event.Responded(1, null)).let { (m, e) -> machine = m; effects = e }
        assertEquals(emptyList<Effect>(), effects)
        assertEquals(Phase.Closed(AgentWidgetNode.Divider, Closed.Submitted("true"), "{}"), machine.phase)
    }

    @Test
    fun aFailedStepKeepsTheViewAndSaysWhy() {
        var machine = AgentWidgetMachine.open("null", Status.Open).first
        machine = machine.handle(Event.Loaded).first
        machine = machine.handle(Event.Ran(Result.success(output("""{"n":1}""")))).first
        machine = machine.handle(Event.Input(AgentWidgetEvent.press("go"))).first
        machine = machine.handle(Event.Ran(Result.failure(Exception("boom")))).first
        assertEquals(Phase.Ready("""{"n":1}""", AgentWidgetNode.Divider, "boom"), machine.phase)
    }

    @Test
    fun aConflictSaysItWasAlreadyAnswered() {
        var machine = AgentWidgetMachine.open("null", Status.Open).first
        machine = machine.handle(Event.Loaded).first
        machine = machine.handle(Event.Ran(Result.success(output()))).first
        machine = machine.handle(Event.Input(AgentWidgetEvent.press("go"))).first
        machine = machine.handle(Event.Ran(Result.success(output(response = OpenCodeWidgetResponse.Dismiss)))).first
        machine = machine.handle(Event.Responded(1, RespondFailure.Conflict)).first
        assertEquals(Phase.Ready("{}", AgentWidgetNode.Divider, "This widget was already answered."), machine.phase)
        machine = machine.handle(Event.StatusChanged(Status.Answered(Closed.Dismissed))).first
        assertEquals(Phase.Closed(AgentWidgetNode.Divider, Closed.Dismissed, "{}"), machine.phase)
    }

    @Test
    fun anAnsweredWidgetDrawsItsInitViewReadOnly() {
        var machine = AgentWidgetMachine.open("null", Status.Answered(Closed.Submitted("\"x\""))).first
        machine = machine.handle(Event.Loaded).first
        machine = machine.handle(Event.Ran(Result.success(output()))).first
        assertEquals(Phase.Closed(AgentWidgetNode.Divider, Closed.Submitted("\"x\""), "{}"), machine.phase)
    }

    @Test
    fun theFirstInitFailingFailsTheWidget() {
        var machine = AgentWidgetMachine.open("null", Status.Open).first
        machine = machine.handle(Event.Loaded).first
        machine = machine.handle(Event.Ran(Result.failure(Exception("no view")))).first
        assertEquals(Phase.Failed("no view", Status.Open), machine.phase)
        assertEquals(listOf(Effect.Fetch), machine.handle(Event.Retry).second)
    }

    // Tool call

    private fun json(text: String) = OpenCodeJSONValue.of(Json.parseToJsonElement(text))

    private fun call(
        input: String,
        status: OpenCodeToolCall.Status = OpenCodeToolCall.Status.Completed,
        metadata: Map<String, OpenCodeJSONValue> = emptyMap(),
        name: String = "widget",
        callID: String = "call_1",
    ) = OpenCodeToolCall(callID = callID, name = name, status = status, input = json(input), output = emptyList(), metadata = metadata)

    @Test
    fun readsAWidgetFromItsToolCall() {
        val widget = AgentWidgetAdapter.widget(
            call(
                """{"widgetTemplate":"confirm","title":"Deploy?","data":{"title":"Deploy?"}}""",
                metadata = mapOf(
                    "widgetTemplate" to json("""{"name":"confirm","version":"0123456789abcdef"}"""),
                    "widget" to json("""{"status":"submitted","response":{"confirmed":true}}"""),
                ),
            ),
        )!!
        assertEquals("call_1", widget.callID)
        assertEquals(OpenCodeWidgetRef("confirm", "0123456789abcdef"), widget.widgetTemplate)
        assertEquals(AgentWidgetState.Status.Submitted, widget.status)
        assertEquals("""{"confirmed":true}""", widget.response)
        assertEquals("Deploy?", widget.label)
    }

    /** The call names its widget template in the input and, once posted, the pinned version in `metadata.widgetTemplate`. */
    @Test
    fun readsAWidgetCallNamingItsWidgetTemplate() {
        val widget = AgentWidgetAdapter.widget(
            call(
                """{"widgetTemplate":"confirm","data":{"title":"Deploy?"}}""",
                metadata = mapOf("widgetTemplate" to json("""{"name":"confirm","version":"0123456789abcdef"}""")),
            ),
        )!!
        assertEquals(OpenCodeWidgetRef("confirm", "0123456789abcdef"), widget.widgetTemplate)
        assertEquals("confirm", AgentWidgetAdapter.title(call("""{"widgetTemplate":"confirm"}""")))
    }

    @Test
    fun anUnpinnedToolCallAsksForTheLatestWidgetTemplate() {
        val widget = AgentWidgetAdapter.widget(call("""{"widgetTemplate":"form","data":{}}"""))!!
        assertEquals("", widget.widgetTemplate.version)
        assertTrue(widget.widgetTemplate.isValid)
        assertEquals(AgentWidgetState.Status.Open, widget.status)
    }

    @Test
    fun otherCallsAndStreamingInputAreNotWidgets() {
        assertNull(AgentWidgetAdapter.widget(call("""{"widgetTemplate":"form"}""", status = OpenCodeToolCall.Status.Streaming)))
        assertNull(AgentWidgetAdapter.widget(call("""{"widgetTemplate":"../etc"}""")))
        assertNull(AgentWidgetAdapter.widget(call("""{"widgetTemplate":"form"}""", name = "read")))
    }

    @Test
    fun aWidgetCallIsItsOwnRow() {
        val items = listOf(
            OpenCodeTranscriptItem.Tool("t1", call("null", name = "read", callID = "c0")),
            OpenCodeTranscriptItem.Tool("t2", call("""{"widgetTemplate":"form"}""")),
        )
        val entries = TranscriptEntry.build(items)
        assertTrue(entries.any { ((it as? TranscriptEntry.Single)?.item as? OpenCodeTranscriptItem.Tool)?.call?.name == "widget" })
    }

    /** Android: the plugin's state pins the version and says how the widget ended. */
    @Test
    fun aToolCallTakesItsStateFromThePlugin() {
        val tool = OpenCodeTranscriptItem.Tool("msg_a-tool-0", call("""{"widgetTemplate":"confirm","data":{"a":1}}""").copy(messageID = "msg_a"))
        val record = ai.opencode.server.model.OpenCodeWidgetState(
            sessionID = "ses", messageID = "msg_a", callID = "call_1", widgetTemplate = OpenCodeWidgetRef("confirm", "0123456789abcdef"),
            title = "Deploy?", status = ai.opencode.server.model.OpenCodeWidgetStatus.Submitted, response = "true",
        )
        val pinned = AgentWidgetAdapter.states(listOf(tool), mapOf(record.key to record))["msg_a/call_1"]!!
        assertEquals("0123456789abcdef", pinned.widgetTemplate.version)
        assertEquals(AgentWidgetState.Status.Submitted, pinned.status)
        assertEquals("true", pinned.response)
        assertEquals("Deploy?", pinned.label)
        assertEquals("""{"a":1}""", pinned.data)
        assertEquals("msg_a/call_1", AgentWidgetAdapter.key(tool))

        val unpinned = AgentWidgetAdapter.states(listOf(tool))["msg_a/call_1"]!!
        assertEquals("", unpinned.widgetTemplate.version)
        assertEquals(AgentWidgetState.Status.Open, unpinned.status)
    }

    /** Android: specs/v2/widgets-fullscreen.md: the root's `fullscreen`, `fill` canvases, and the event a widget template hears. */
    @Test
    fun readsFullScreenOffersFillAndTheEvent() {
        val asked = stepOutput("""{"state":{},"view":{"type":"column","fullscreen":true,"children":[{"type":"canvas","height":120,"fill":true}]}}""")
        assertEquals(true, asked.fullscreen)
        assertTrue(AgentWidgetNode.fills(asked.view))
        assertTrue(AgentWidgetNode.drawsPixels(asked.view))
        val plain = stepOutput("""{"state":{},"view":{"type":"column","children":[{"type":"text","text":"Hi"}]}}""")
        assertNull(plain.fullscreen)
        assertTrue(!AgentWidgetNode.drawsPixels(plain.view))
        assertEquals(false, stepOutput("""{"state":{},"view":{"type":"divider","fullscreen":false}}""").fullscreen)
        // widgets-fullscreen.md, Telling the program: the close button's area in root coordinates, null on leaving.
        assertEquals(
            """{"id":"","type":"fullscreen","value":true,"close":{"x":339,"y":8,"width":44,"height":44}}""",
            AgentWidgetEvent.fullscreen(true, AgentWidgetRect(339f, 8f, 44f, 44f)).json,
        )
        assertEquals("""{"id":"","type":"fullscreen","value":false,"close":null}""", AgentWidgetEvent.fullscreen(false, null).json)
        // An absolute pointer value carries the area over its node, and leaves it out when there is none.
        assertEquals(
            """{"id":"0/:board","type":"pointer","value":{"kind":"down","x":1,"y":2,"width":391,"height":700,"close":{"x":339,"y":-4.5,"width":44,"height":44}}}""",
            AgentWidgetEvent.pointer("0/:board", "down", 1f, 2f, 391f, 700f, AgentWidgetRect(339f, -4.5f, 44f, 44f)).json,
        )
        assertEquals(
            """{"id":"0/:board","type":"pointer","value":{"kind":"up","x":1,"y":2,"width":10,"height":10}}""",
            AgentWidgetEvent.pointer("0/:board", "up", 1f, 2f, 10f, 10f).json,
        )
        assertEquals("""{"x":338.29,"y":8,"width":44,"height":44}""", AgentWidgetRect(338.2857f, 8f, 44f, 44f).json)
    }

    /**
     * Android's close area (widgets-fullscreen.md, Chrome): 44 dp, 8 dp from the safe area's top and end, which is the
     * 12 dp padding outside the root, so { x: W - 40, y: -4 } in the root's coordinates; it moves only with the width.
     */
    @Test
    fun theCloseAreaFollowsTheRootsWidth() {
        assertEquals(AgentWidgetRect(351f, -4f, 44f, 44f), AgentWidgetController.closeArea(391f))
        assertEquals(AgentWidgetRect(874.29f, -4f, 44f, 44f).json, AgentWidgetController.closeArea(914.29f).json)
        // Right-to-left the button sits at the left: x = 8 - 12.
        assertEquals(AgentWidgetRect(-4f, -4f, 44f, 44f), AgentWidgetController.closeArea(391f, rtl = true))
    }

    @Test
    fun keyboardEventsAndQueuedReplacement() {
        // widgets-input.md: keys and focus belong to no node.
        assertEquals("""{"id":"","type":"focus","value":false}""", AgentWidgetEvent.focus(false).json)
        assertEquals(
            """{"id":"","type":"key","value":{"kind":"down","key":"a","code":"KeyA","repeat":false,"shift":false,"alt":false,"ctrl":false,"meta":false}}""",
            AgentWidgetEvent.key("down", "a", "KeyA", repeat = false, shift = false, alt = false, ctrl = false, meta = false).json,
        )
        assertEquals("KeyA", AgentWidgetKeys.code(android.view.KeyEvent.KEYCODE_A))
        assertEquals("Digit7", AgentWidgetKeys.code(android.view.KeyEvent.KEYCODE_7))
        assertEquals("ArrowLeft", AgentWidgetKeys.code(android.view.KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("Space", AgentWidgetKeys.code(android.view.KeyEvent.KEYCODE_SPACE))
        assertEquals("ShiftLeft", AgentWidgetKeys.code(android.view.KeyEvent.KEYCODE_SHIFT_LEFT))
        // Back is never a widget key: it always closes full screen.
        assertNull(AgentWidgetKeys.code(android.view.KeyEvent.KEYCODE_BACK))
        assertNull(AgentWidgetKeys.code(android.view.KeyEvent.KEYCODE_VOLUME_UP))

        // A queued event is replaced only by a newer value of the same control or a newer move of the same press.
        val down = AgentWidgetEvent.pointer("0/:board", "down", 1f, 2f, 10f, 10f)
        val move = AgentWidgetEvent.pointer("0/:board", "move", 3f, 4f, 10f, 10f)
        assertTrue(!down.replaceable(move))
        assertTrue(move.replaceable(AgentWidgetEvent.pointer("0/:board", "move", 5f, 6f, 10f, 10f)))
        assertTrue(!move.replaceable(AgentWidgetEvent.pointer("0/:board", "up", 5f, 6f, 10f, 10f)))
        assertTrue(AgentWidgetEvent.change("0/1", "a").replaceable(AgentWidgetEvent.change("0/1", "ab")))
        val keyDown = AgentWidgetEvent.key("down", "a", "KeyA", repeat = false, shift = false, alt = false, ctrl = false, meta = false)
        assertTrue(!keyDown.replaceable(AgentWidgetEvent.key("up", "a", "KeyA", repeat = false, shift = false, alt = false, ctrl = false, meta = false)))
        assertTrue(!AgentWidgetEvent.press("0/2").replaceable(AgentWidgetEvent.press("0/2")))
    }
}
