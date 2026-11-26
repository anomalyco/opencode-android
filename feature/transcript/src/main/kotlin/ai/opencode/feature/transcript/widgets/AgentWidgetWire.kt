package ai.opencode.feature.transcript.widgets

import ai.opencode.server.model.OpenCodeWidgetMessageValue
import ai.opencode.server.model.OpenCodeWidgetResponse
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/**
 * The widget binary format, version 1 (specs/v2/widgets-binary.md), as widget-mobile's encoder writes step outputs and
 * live posts: read into the same values the JSON form gives ([AgentWidgetNode.build] makes the nodes for both,
 * [AgentWidgetDrawList] gets the same streams). Every read is bounds-checked. Bytes that don't follow the format are
 * [Malformed], a client error; a well-formed view that breaks a node's rules is [AgentWidgetNode.InvalidView], a program
 * error, as its JSON would be.
 */
internal object AgentWidgetWire {
    /** Not the format: a client error (log in dev mode, drop the message, keep the previous state). */
    class Malformed(message: String) : Exception(message)

    /** A live post. */
    sealed interface Post {
        class Render(val view: AgentWidgetNode, val fullscreen: Boolean?) : Post
        /** A canvas's commands: section `D` at [section] in [message], decoded against the theme where it's drawn. */
        class Draw(val id: String, val message: ByteArray, val section: Int) : Post
        /** A frame request (with or without a GPU batch: the runtime replays batches itself). */
        data object Frame : Post
        /** A GPU batch on its own: nothing for the app. */
        data object Gpu : Post
        class Submit(val value: String) : Post
        data object Dismiss : Post
        class Send(val value: String, val wake: Boolean) : Post
        class Capture(val on: Boolean) : Post
    }

    private const val VERSION = 1
    private const val STEP = 'S'.code
    const val RENDER = 1
    const val DRAW = 2
    private const val FRAME = 3
    private const val SUBMIT = 4
    private const val DISMISS = 5
    private const val SEND = 6
    private const val CAPTURE = 7
    private const val FRAME_GPU = 9
    private const val GPU = 10

    const val MAX_DEPTH = 100
    private const val MAX_ARG_DEPTH = 64
    private const val MAX_GPU_OPS = 20_000L
    private const val MAX_GPU_BYTES = 4 * 1024 * 1024L

    private val json = Json { ignoreUnknownKeys = true }

    /** An init or event step's output (`01 'S'`). */
    fun output(bytes: ByteArray): AgentWidgetOutput {
        val c = Cursor(bytes, 0, bytes.size)
        version(c)
        if (c.u8() != STEP) throw Malformed("not a step output")
        val flags = c.u8()
        if (flags and 0x0f.inv() != 0) throw Malformed("unknown step flags $flags")
        val state = c.json()
        val submit = if (flags and 1 != 0) c.json() else null
        val send = if (flags and 4 != 0) c.json() else null
        val view = Views(c).root()
        c.end("the message")
        val response = when {
            submit != null -> OpenCodeWidgetResponse.Submit(submit)
            flags and 2 != 0 -> OpenCodeWidgetResponse.Dismiss
            else -> null
        }
        val message = if (response == null && send != null) OpenCodeWidgetMessageValue(send, flags and 8 != 0) else null
        return AgentWidgetOutput(state, view.node, response, message, view.fullscreen)
    }

    /** A draw step's output (`01 'D'`) into [into], its colours resolved against [palette]. */
    fun drawOutput(bytes: ByteArray, palette: AgentWidgetDrawList.Palette, into: AgentWidgetDrawList) {
        val c = Cursor(bytes, 0, bytes.size)
        version(c)
        draw(c, palette, into)
        c.end("the message")
    }

    /** A live draw's commands (section `D` at [section] in [message], to its end) into [into]. */
    fun draw(message: ByteArray, section: Int, palette: AgentWidgetDrawList.Palette, into: AgentWidgetDrawList) {
        val c = Cursor(message, section, message.size)
        draw(c, palette, into)
        c.end("the message")
    }

    /** A live post. A `draw`'s commands are checked where they're drawn ([draw]). */
    fun post(bytes: ByteArray): Post {
        val c = Cursor(bytes, 0, bytes.size)
        version(c)
        val post = when (val kind = c.u8()) {
            RENDER -> Views(c).root().let { Post.Render(it.node, it.fullscreen) }
            DRAW -> {
                val id = c.str()
                val section = c.at
                c.at = c.end
                return Post.Draw(id, bytes, section)
            }
            FRAME -> Post.Frame
            FRAME_GPU -> { gpu(c); Post.Frame }
            GPU -> { gpu(c); Post.Gpu }
            SUBMIT -> Post.Submit(c.json())
            DISMISS -> Post.Dismiss
            SEND -> {
                val wake = flag(c.u8())
                Post.Send(c.json(), wake)
            }
            CAPTURE -> Post.Capture(flag(c.u8()))
            else -> throw Malformed("unknown post kind $kind")
        }
        c.end("the message")
        return post
    }

    /** A post's kind byte without reading the rest, or -1 when it isn't a version 1 message. */
    fun kind(message: ByteArray): Int =
        if (message.size >= 2 && message[0].toInt() == VERSION) message[1].toInt() and 0xff else -1

    /** Whether a post is a frame request, from its kind byte alone. */
    fun isFrame(message: ByteArray): Boolean = kind(message).let { it == FRAME || it == FRAME_GPU }

    /** A `draw` post's canvas id without reading its commands, or null when it can't be read. */
    fun drawId(message: ByteArray): String? = try {
        Cursor(message, 2, message.size).str()
    } catch (_: Malformed) {
        null
    }

    private fun version(c: Cursor) {
        val v = c.u8()
        if (v != VERSION) throw Malformed("version $v, not $VERSION")
    }

    private fun flag(v: Long): Boolean = when (v) {
        0L -> false
        1L -> true
        else -> throw Malformed("a flag is 0 or 1, got $v")
    }

    private fun flag(v: Int): Boolean = flag(v.toLong())

    /** A bounds-checked reader over `[at, end)` of [b]. */
    private class Cursor(val b: ByteArray, var at: Int, val end: Int) {
        private var utf8: CharsetDecoder? = null

        val remaining: Int get() = end - at

        fun take(n: Long): Int {
            if (n < 0 || n > remaining) throw Malformed("truncated")
            val start = at
            at += n.toInt()
            return start
        }

        fun u8(): Int {
            if (at >= end) throw Malformed("truncated")
            return b[at++].toInt() and 0xff
        }

        fun u32(): Long {
            val p = take(4)
            return ((b[p].toLong() and 0xff) or ((b[p + 1].toLong() and 0xff) shl 8) or
                ((b[p + 2].toLong() and 0xff) shl 16) or ((b[p + 3].toLong() and 0xff) shl 24))
        }

        fun i32(): Int {
            val p = take(4)
            return (b[p].toInt() and 0xff) or ((b[p + 1].toInt() and 0xff) shl 8) or
                ((b[p + 2].toInt() and 0xff) shl 16) or ((b[p + 3].toInt() and 0xff) shl 24)
        }

        fun f32(): Float = Float.fromBits(i32())

        fun f64(): Double {
            val p = take(8)
            var bits = 0L
            for (i in 7 downTo 0) bits = (bits shl 8) or (b[p + i].toLong() and 0xff)
            return Double.fromBits(bits)
        }

        /** Unsigned LEB128, at most 10 bytes; values past 2^63 are malformed here (no count or int is that large). */
        fun varint(): Long {
            var v = 0L
            for (i in 0 until 10) {
                val byte = u8()
                val bits = (byte and 0x7f).toLong()
                if (i == 9 && bits > 1) throw Malformed("varint overflow")
                v = v or (bits shl (7 * i))
                if (byte < 0x80) {
                    if (v < 0) throw Malformed("a varint over 63 bits")
                    return v
                }
            }
            throw Malformed("varint over 10 bytes")
        }

        /** A varint that fits 32 bits unsigned. */
        fun uint(): Long {
            val v = varint()
            if (v > 0xffffffffL) throw Malformed("int over 32 bits")
            return v
        }

        /** A count of items that each take at least [min] bytes of this cursor. */
        fun count(min: Int): Int {
            val n = varint()
            if (n > Int.MAX_VALUE || n * min > remaining) throw Malformed("a count of $n past the end")
            return n.toInt()
        }

        fun str(): String {
            val n = count(1)
            val p = take(n.toLong())
            var ascii = true
            for (i in p until p + n) if (b[i] < 0) { ascii = false; break }
            if (ascii) return String(b, p, n, Charsets.ISO_8859_1)
            val decoder = utf8 ?: Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .also { utf8 = it }
            return try {
                decoder.reset().decode(ByteBuffer.wrap(b, p, n)).toString()
            } catch (_: CharacterCodingException) {
                throw Malformed("bad UTF-8")
            }
        }

        /** A str holding JSON text (state, submit, send): checked, kept as text. */
        fun json(): String {
            val text = str()
            try {
                json.parseToJsonElement(text)
            } catch (_: Exception) {
                throw Malformed("bad JSON")
            }
            return text
        }

        fun end(what: String) {
            if (at != end) throw Malformed("${end - at} bytes left after $what")
        }

        /** A sub-cursor over the next [n] bytes. */
        fun section(n: Long): Cursor {
            val p = take(n)
            return Cursor(b, p, p + n.toInt())
        }
    }

    // ---- views: section N ----

    private const val K_INT = 0
    private const val K_F64 = 1
    private const val K_STR = 2
    private const val K_F64S = 3
    private const val K_STRS = 4
    private const val K_OPTS = 5
    private const val T_UNKNOWN = 63L
    private const val PROPS = 39

    private val TYPE_NAMES = arrayOf(
        null, "column", "row", "text", "markdown", "image", "carousel", "card", "button", "checkbox", "slider", "select",
        "input", "divider", "canvas", null, "stack",
    )
    private val ALIGN = arrayOf("start", "center", "end", "stretch")
    private val STYLE = arrayOf("title", "body", "caption", "code")
    private val TONE = arrayOf("default", "muted", "accent", "success", "warning", "error")
    private val VARIANT = arrayOf("primary", "secondary", "danger")
    private val PLACE = arrayOf("top-left", "top", "top-right", "left", "center", "right", "bottom-left", "bottom", "bottom-right", "fill")

    /** The enum table for a prop id, or null. */
    private fun table(id: Int): Array<String>? = when (id) {
        2 -> ALIGN
        5 -> STYLE
        6 -> TONE
        14 -> VARIANT
        36 -> PLACE
        else -> null
    }

    private fun isBool(id: Int) = id == 3 || id == 12 || id == 15 || id == 16 || id == 22 || id == 24 || id == 32 || id == 33 || id == 35 || id == 38

    private fun propId(key: String): Int = when (key) {
        "gap" -> 1
        "align" -> 2
        "wrap" -> 3
        "text" -> 4
        "style" -> 5
        "tone" -> 6
        "src" -> 7
        "alt" -> 8
        "aspect" -> 9
        "id" -> 10
        "index" -> 11
        "selected" -> 12
        "label" -> 13
        "variant" -> 14
        "disabled" -> 15
        "checked" -> 16
        "value" -> 17
        "min" -> 18
        "max" -> 19
        "step" -> 20
        "options" -> 21
        "multiple" -> 22
        "placeholder" -> 23
        "multiline" -> 24
        "height" -> 25
        "width" -> 26
        "fps" -> 27
        "duration" -> 28
        "wgsl" -> 29
        "values" -> 30
        "poster" -> 31
        "fill" -> 32
        "fullscreen" -> 33
        "capture" -> 34
        "pointer" -> 35
        "place" -> 36
        "inset" -> 37
        "gpu" -> 38
        else -> 0
    }

    /** One node's props, by prop id: the kind it came as (-1 absent) and its value. Reused per depth. */
    private class WireFields : AgentWidgetNode.Fields {
        val kinds = IntArray(PROPS)
        val ints = LongArray(PROPS)
        val nums = DoubleArray(PROPS)
        val objs = arrayOfNulls<Any>(PROPS)

        fun reset() {
            kinds.fill(-1)
            objs.fill(null)
        }

        private fun slot(key: String): Int = propId(key)

        override fun string(key: String): String? {
            val id = slot(key)
            if (id == 0) return null
            return when (kinds[id]) {
                K_STR -> objs[id] as String
                K_INT -> table(id)?.getOrNull(ints[id].toInt().takeIf { ints[id] <= Int.MAX_VALUE } ?: -1)
                else -> null
            }
        }

        override fun number(key: String): Double? {
            val id = slot(key)
            return if (id != 0 && kinds[id] == K_F64) nums[id].takeIf { it.isFinite() } else null
        }

        override fun boolean(key: String): Boolean? {
            val id = slot(key)
            return if (id != 0 && kinds[id] == K_INT && isBool(id)) ints[id] == 1L else null
        }

        override fun has(key: String): Boolean {
            val id = slot(key)
            return id != 0 && kinds[id] != -1
        }

        @Suppress("UNCHECKED_CAST")
        override fun strings(key: String): List<String> {
            val id = slot(key)
            if (id == 0) return emptyList()
            return when (kinds[id]) {
                K_STR -> listOf(objs[id] as String)
                K_STRS -> objs[id] as List<String>
                else -> emptyList()
            }
        }

        override fun values(into: FloatArray) {
            if (kinds[30] != K_F64S) return
            val list = objs[30] as DoubleArray
            for (i in 0 until minOf(into.size, list.size)) into[i] = list[i].toFloat()
        }

        @Suppress("UNCHECKED_CAST")
        override fun options(): List<AgentWidgetNode.Select.Option>? =
            if (kinds[21] == K_OPTS) objs[21] as List<AgentWidgetNode.Select.Option> else null

        override fun inset(): FloatArray? = when (kinds[37]) {
            K_F64 -> nums[37].toFloat().let { floatArrayOf(it, it, it, it) }
            K_F64S -> (objs[37] as DoubleArray).let { floatArrayOf(it[0].toFloat(), it[1].toFloat(), it[2].toFloat(), it[3].toFloat()) }
            else -> null
        }
    }

    private class Root(val node: AgentWidgetNode, val fullscreen: Boolean?)

    /** Section `N`: its three streams, read node by node. */
    private class Views(c: Cursor) {
        private val ints: Cursor
        private val floats: Cursor
        private val strs: Cursor
        private val pool = ArrayList<WireFields>()
        /** The first rule a node broke; reported once the whole view is known to be well formed. */
        private var invalid: AgentWidgetNode.InvalidView? = null
        /** The last node's layer, for a stack's child. */
        private var layer = AgentWidgetNode.Layer()

        init {
            if (c.u8() != 'N'.code) throw Malformed("expected section N")
            val li = c.count(0).toLong()
            val lf = c.count(0).toLong()
            val ls = c.count(0).toLong()
            ints = c.section(li)
            floats = c.section(lf)
            strs = c.section(ls)
        }

        fun root(): Root {
            val node = node(0, inStack = false)
            ints.end("the view's ints")
            floats.end("the view's floats")
            strs.end("the view's strings")
            invalid?.let { throw it }
            return when (node) {
                is Unknown -> throw AgentWidgetNode.InvalidView(
                    if (node.name.isEmpty()) "invalid view: a node needs a type" else "invalid view: unknown root node type",
                )
                is Known -> Root(node.node, node.fullscreen)
            }
        }

        private sealed interface Read
        private class Known(val node: AgentWidgetNode, val fullscreen: Boolean?) : Read
        private class Unknown(val name: String) : Read

        private fun fields(depth: Int): WireFields {
            while (pool.size <= depth) pool.add(WireFields())
            return pool[depth].also { it.reset() }
        }

        private fun node(depth: Int, inStack: Boolean): Read {
            if (depth > MAX_DEPTH) throw Malformed("a view deeper than $MAX_DEPTH levels")
            val t = ints.uint()
            if (t == T_UNKNOWN) {
                if (ints.uint() != 0L) throw Malformed("an unknown node has no props")
                return Unknown(strs.str())
            }
            val type = TYPE_NAMES.getOrNull(t.toInt()) ?: throw Malformed("unknown node type $t")
            val f = fields(depth)
            while (true) {
                val tag = ints.uint()
                if (tag == 0L) break
                prop(f, (tag ushr 3).toInt(), (tag and 7).toInt())
            }
            var children = emptyList<AgentWidgetNode>()
            var layers = emptyList<AgentWidgetNode.Layer>()
            if (type in AgentWidgetNode.CONTAINERS) {
                val n = ints.count(2)
                val stack = type == "stack"
                val kids = ArrayList<AgentWidgetNode>(n)
                val kidLayers = if (stack) ArrayList<AgentWidgetNode.Layer>(n) else null
                for (i in 0 until n) {
                    when (val child = node(depth + 1, stack)) {
                        is Known -> {
                            kids.add(child.node)
                            kidLayers?.add(layer)
                        }
                        is Unknown -> if (child.name.isEmpty()) note(AgentWidgetNode.InvalidView("invalid view: a node needs a type"))
                    }
                }
                children = kids
                if (kidLayers != null) layers = kidLayers
            }
            if (inStack) layer = AgentWidgetNode.layer(f)
            val node = try {
                AgentWidgetNode.build(type, f, children, layers)
            } catch (error: AgentWidgetNode.InvalidView) {
                note(error)
                AgentWidgetNode.Divider
            }
            return Known(node, f.boolean("fullscreen"))
        }

        private fun note(error: AgentWidgetNode.InvalidView) {
            if (invalid == null) invalid = error
        }

        private fun prop(f: WireFields, id: Int, kind: Int) {
            val known = id in 1 until PROPS
            when (kind) {
                K_INT -> {
                    val v = ints.uint()
                    if (!known || (table(id) == null && !isBool(id))) return
                    if (isBool(id)) flag(v)
                    f.kinds[id] = K_INT
                    f.ints[id] = v
                }
                K_F64 -> {
                    val v = floats.f64()
                    if (!known || table(id) != null || isBool(id) || id in STRING_PROPS || id == 21 || id == 30) return
                    f.kinds[id] = K_F64
                    f.nums[id] = v
                }
                K_STR -> {
                    val v = strs.str()
                    if (!known || (id !in STRING_PROPS && id != 17)) return
                    f.kinds[id] = K_STR
                    f.objs[id] = v
                }
                K_F64S -> {
                    val n = ints.uint()
                    if (n * 8 > floats.remaining) throw Malformed("an f64 list past the end")
                    val list = DoubleArray(n.toInt()) { floats.f64() }
                    if (id == 37 && list.size != 4) throw Malformed("an inset list has 4 numbers")
                    if (id != 30 && id != 37) return
                    f.kinds[id] = K_F64S
                    f.objs[id] = list
                }
                K_STRS -> {
                    val n = ints.uint()
                    if (n > strs.remaining) throw Malformed("a string list past the end")
                    val list = List(n.toInt()) { strs.str() }
                    if (id != 17) return
                    f.kinds[id] = K_STRS
                    f.objs[id] = list
                }
                K_OPTS -> {
                    val n = ints.count(1)
                    val list = ArrayList<AgentWidgetNode.Select.Option>(n)
                    for (i in 0 until n) {
                        val described = flag(ints.uint())
                        val value = strs.str()
                        val label = strs.str()
                        list.add(AgentWidgetNode.Select.Option(value, label, if (described) strs.str() else null))
                    }
                    if (id != 21) return
                    f.kinds[id] = K_OPTS
                    f.objs[id] = list
                }
                else -> throw Malformed("unknown prop kind $kind")
            }
        }
    }

    /** Props whose value is a string. */
    private val STRING_PROPS = setOf(4, 7, 8, 10, 13, 23, 29, 31, 34)

    // ---- draw commands: section D ----

    private fun draw(c: Cursor, palette: AgentWidgetDrawList.Palette, out: AgentWidgetDrawList) {
        if (c.u8() != 'D'.code) throw Malformed("expected section D")
        val n = c.u32()
        val ni = c.u32()
        val nf = c.u32()
        val ls = c.u32()
        if (n > AgentWidgetDrawList.MAX_COMMANDS) throw Malformed("$n draw commands is over the limit of ${AgentWidgetDrawList.MAX_COMMANDS}")
        val r = DrawReader(c.section(ni * 4), c.section(nf * 4), c.section(ls), palette, out)
        out.clear()
        for (i in 0 until n.toInt()) r.command()
        r.ints.end("the drawing's ints")
        r.floats.end("the drawing's floats")
        r.strs.end("the drawing's strings")
    }

    private class DrawReader(
        val ints: Cursor,
        val floats: Cursor,
        val strs: Cursor,
        private val palette: AgentWidgetDrawList.Palette,
        private val out: AgentWidgetDrawList,
    ) {
        private var offsets = FloatArray(8)
        private var colors = IntArray(8)

        private fun i(): Int = ints.i32()
        private fun f(): Float = floats.f32()
        private fun copy(n: Int) = repeat(n) { out.float(f()) }

        private fun color(): Int {
            val kind = ints.u32()
            val value = ints.u32()
            return when (kind) {
                0L -> {
                    val v = value.toInt()
                    ((v and 0xff) shl 24) or (v ushr 8)
                }
                in 1L..9L -> palette.tokens[kind.toInt() - 1]
                else -> throw Malformed("unknown color kind $kind")
            }
        }

        /** Wire paint into the list's: fill kind, fill colour | stop count, stroke flag, stroke colour, cap; width, gradient. */
        private fun paint() {
            val fillKind = ints.u32()
            var fill = 0
            var stops = 0
            val gradient = fillKind == 2L || fillKind == 3L
            var g0 = 0f
            var g1 = 0f
            var g2 = 0f
            var g3 = 0f
            when {
                fillKind == 0L -> Unit
                fillKind == 1L -> fill = color()
                gradient -> {
                    val count = ints.u32()
                    if (count * 4 > floats.remaining) throw Malformed("gradient stops past the end")
                    stops = count.toInt()
                    g0 = f(); g1 = f(); g2 = f()
                    if (fillKind == 2L) g3 = f()
                    if (offsets.size < stops) { offsets = FloatArray(stops); colors = IntArray(stops) }
                    for (s in 0 until stops) {
                        offsets[s] = f()
                        colors[s] = color()
                    }
                }
                else -> throw Malformed("unknown fill kind $fillKind")
            }
            val stroke = flag(ints.u32())
            val strokeColor = if (stroke) color() else 0
            val cap = if (flag(ints.u32())) 1 else 0
            val width = f()
            val kind = if (gradient && stops < 2) AgentWidgetDrawList.FILL_NONE else fillKind.toInt()
            out.int(kind)
            out.int(if (kind == AgentWidgetDrawList.FILL_COLOR) fill else if (gradient) stops else 0)
            out.int(if (stroke) 1 else 0)
            out.int(strokeColor)
            out.int(cap)
            out.float(width)
            if (kind == AgentWidgetDrawList.FILL_LINEAR || kind == AgentWidgetDrawList.FILL_RADIAL) {
                out.float(g0); out.float(g1); out.float(g2)
                if (kind == AgentWidgetDrawList.FILL_LINEAR) out.float(g3)
                for (s in 0 until stops) out.float(offsets[s])
                for (s in 0 until stops) out.int(colors[s])
            }
        }

        fun command() {
            when (val op = i()) {
                AgentWidgetDrawList.SAVE, AgentWidgetDrawList.RESTORE -> out.int(op)
                AgentWidgetDrawList.TRANSLATE, AgentWidgetDrawList.SCALE -> { out.int(op); copy(2) }
                AgentWidgetDrawList.ROTATE, AgentWidgetDrawList.ALPHA -> { out.int(op); copy(1) }
                AgentWidgetDrawList.RECT, AgentWidgetDrawList.LINE -> { out.int(op); copy(4); paint() }
                AgentWidgetDrawList.RRECT -> { out.int(op); copy(5); paint() }
                AgentWidgetDrawList.CIRCLE -> { out.int(op); copy(3); paint() }
                AgentWidgetDrawList.PATH -> {
                    val n = ints.u32()
                    if (n * 4 > ints.remaining) throw Malformed("path segments past the end")
                    out.int(op)
                    out.int(n.toInt())
                    for (s in 0 until n.toInt()) {
                        val verb = i()
                        val coords = when (verb) {
                            AgentWidgetDrawList.SEG_M, AgentWidgetDrawList.SEG_L -> 2
                            AgentWidgetDrawList.SEG_Q -> 4
                            AgentWidgetDrawList.SEG_C -> 6
                            AgentWidgetDrawList.SEG_Z -> 0
                            else -> throw Malformed("unknown path verb $verb")
                        }
                        out.int(verb)
                        copy(coords)
                    }
                    paint()
                }
                AgentWidgetDrawList.TEXT -> {
                    out.int(op)
                    copy(2)
                    out.strings += strs.str()
                    val weight = ints.u32()
                    if (weight > 65_535) throw Malformed("a text weight over 65535")
                    val align = ints.u32()
                    if (align > 2) throw Malformed("unknown text align $align")
                    val color = if (flag(ints.u32())) color() else palette.tokens[AgentWidgetDrawList.Token.Text.ordinal]
                    val mono = if (flag(ints.u32())) 1 else 0
                    out.float(f().coerceIn(1f, 200f))
                    out.int(weight.toInt())
                    out.int(align.toInt())
                    out.int(color)
                    out.int(mono)
                }
                AgentWidgetDrawList.IMAGE -> {
                    out.int(op)
                    out.strings += strs.str()
                    copy(4)
                }
                else -> throw Malformed("unknown draw op $op")
            }
            out.counted()
        }
    }

    // ---- GPU batch: section G (checked, then left to the runtime) ----

    private fun gpu(c: Cursor) {
        if (c.u8() != 'G'.code) throw Malformed("expected section G")
        val ops = c.section(c.u32())
        val n = ops.varint()
        if (n > MAX_GPU_OPS) throw Malformed("$n GPU ops is over the limit")
        for (i in 0 until n) arg(ops, 1)
        ops.end("GPU ops")
        val len = c.u32()
        if (len > MAX_GPU_BYTES) throw Malformed("$len GPU bytes is over the limit")
        c.take(len)
    }

    private fun arg(c: Cursor, depth: Int) {
        if (depth > MAX_ARG_DEPTH) throw Malformed("GPU args nest deeper than $MAX_ARG_DEPTH")
        when (val tag = c.u8()) {
            0, 1, 2 -> Unit
            3 -> c.f64()
            4 -> c.str()
            5 -> repeat(c.count(1)) { arg(c, depth + 1) }
            6 -> repeat(c.count(2)) {
                c.str()
                arg(c, depth + 1)
            }
            else -> throw Malformed("unknown GPU arg tag $tag")
        }
    }
}
