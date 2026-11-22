package ai.opencode.feature.transcript.widgets

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.ByteArrayOutputStream

/**
 * The runtime's widget encoder (psychoscript `widget-wire`, `encode.rs`) ported to JSON values, so tests can state a
 * step output or post as JSON and read it back through [AgentWidgetWire]. `binaryWorkloadsMatchTheRuntimesEncoder`
 * checks it against the runtime's own bytes. An output the encoder can't write throws [Invalid] (a program error).
 */
internal object AgentWidgetWireEncoder {
    class Invalid(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true }

    fun output(text: String): ByteArray {
        val v = json.parseToJsonElement(text) as? JsonObject ?: throw Invalid("a step must return an object with state and view")
        val out = Bytes().u8(1)
        val commands = v["commands"]
        if (commands != null) draw(commands, out) else step(v, out)
        return out.bytes()
    }

    fun post(text: String): ByteArray {
        val m = json.parseToJsonElement(text) as? JsonObject ?: throw Invalid("a post is an object with a type")
        val out = Bytes().u8(1)
        when (val type = m.str("type") ?: throw Invalid("a post is an object with a type")) {
            "render" -> {
                out.u8(1)
                Streams().also { it.node(m["node"] ?: JsonNull, 0) }.into(out)
            }
            "draw" -> {
                out.u8(2)
                out.str(m.str("id") ?: throw Invalid("a draw post needs its canvas's id"))
                draw(m["commands"] ?: throw Invalid("a draw post needs commands"), out)
            }
            "frame" -> {
                if (m["gpu"].let { it != null && it !is JsonNull }) throw Invalid("GPU batches aren't written here")
                out.u8(3)
            }
            "submit" -> out.u8(4).str((m["value"] ?: JsonNull).toString())
            "dismiss" -> out.u8(5)
            "send" -> out.u8(6, if ((m["wake"] as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == false } == true) 0 else 1)
                .str((m["value"] ?: JsonNull).toString())
            "capture" -> out.u8(7, if (m.bool("value") == true) 1 else 0)
            else -> throw Invalid("unknown post type \"$type\"")
        }
        return out.bytes()
    }

    private fun step(v: JsonObject, out: Bytes) {
        val submit = v["submit"]
        val dismiss = v.bool("dismiss") == true
        val send = v["send"] as? JsonObject
        val wake = (send?.get("wake") as? JsonPrimitive)?.let { !it.isString && it.booleanOrNull == false } != true
        out.u8('S'.code, (if (submit != null) 1 else 0) or (if (dismiss) 2 else 0) or (if (send != null) 4 else 0) or (if (send != null && wake) 8 else 0))
        out.str((v["state"] ?: JsonNull).toString())
        if (submit != null) out.str(submit.toString())
        if (send != null) out.str((send["value"] ?: JsonNull).toString())
        Streams().also { it.node(v["view"] ?: JsonNull, 0) }.into(out)
    }

    private class Bytes {
        val out = ByteArrayOutputStream()
        fun u8(vararg v: Int) = apply { v.forEach(out::write) }
        fun varint(value: Long) = apply {
            var v = value
            while (v >= 0x80) { out.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }
            out.write(v.toInt())
        }
        fun str(s: String) = apply { val b = s.encodeToByteArray(); varint(b.size.toLong()); out.write(b) }
        fun u32(v: Int) = apply { for (i in 0 until 4) out.write((v ushr (8 * i)) and 0xff) }
        fun f32(v: Float) = u32(v.toRawBits())
        fun f64(v: Double) = apply { val bits = v.toRawBits(); for (i in 0 until 8) out.write(((bits ushr (8 * i)) and 0xff).toInt()) }
        fun bytes(b: ByteArray) = apply { out.write(b) }
        fun size() = out.size()
        fun bytes(): ByteArray = out.toByteArray()
    }

    // ---- views ----

    private val TYPES = listOf("column", "row", "text", "markdown", "image", "carousel", "card", "button", "checkbox", "slider", "select", "input", "divider", "canvas")
    private val PROPS = listOf(
        "gap", "align", "wrap", "text", "style", "tone", "src", "alt", "aspect", "id", "index", "selected", "label", "variant",
        "disabled", "checked", "value", "min", "max", "step", "options", "multiple", "placeholder", "multiline", "height",
        "width", "fps", "duration", "wgsl", "values", "poster", "fill", "fullscreen", "capture", "pointer", "place", "inset",
    )
    private val ENUMS = mapOf(
        2 to listOf("start", "center", "end", "stretch"),
        5 to listOf("title", "body", "caption", "code"),
        6 to listOf("default", "muted", "accent", "success", "warning", "error"),
        14 to listOf("primary", "secondary", "danger"),
        36 to listOf("top-left", "top", "top-right", "left", "center", "right", "bottom-left", "bottom", "bottom-right", "fill"),
    )
    private val BOOLS = setOf(3, 12, 15, 16, 22, 24, 32, 33, 35)
    private val STRS = setOf(4, 7, 8, 10, 13, 23, 29, 31, 34)

    private fun typeId(name: String) = if (name == "stack") 16 else TYPES.indexOf(name).let { if (it < 0) 63 else it + 1 }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    private fun number(v: JsonElement?) = (v as? JsonPrimitive)?.takeIf { !it.isString && it.booleanOrNull == null }?.doubleOrNull?.takeIf { it.isFinite() }

    private class Streams {
        val ints = Bytes()
        val floats = Bytes()
        val strs = Bytes()

        fun int(v: Int) = ints.varint(v.toLong())
        fun tag(id: Int, kind: Int) = int(id shl 3 or kind)

        fun into(out: Bytes) {
            out.u8('N'.code).varint(ints.size().toLong()).varint(floats.size().toLong()).varint(strs.size().toLong())
            out.bytes(ints.bytes()).bytes(floats.bytes()).bytes(strs.bytes())
        }

        fun node(v: JsonElement, depth: Int) {
            if (depth > AgentWidgetWire.MAX_DEPTH) throw Invalid("the view nests deeper than ${AgentWidgetWire.MAX_DEPTH} levels")
            if (v !is JsonObject) {
                int(63); int(0); strs.str("")
                return
            }
            val name = v.str("type") ?: ""
            val t = typeId(name)
            int(t)
            if (t == 63) {
                int(0); strs.str(name)
                return
            }
            var kids: JsonArray? = null
            for ((k, value) in v) when (k) {
                "type" -> Unit
                "children" -> if (value is JsonArray) kids = value
                else -> prop(k, value)
            }
            int(0)
            if (t in listOf(1, 2, 6, 7, 16)) {
                val list = ArrayList<JsonElement>()
                flatten(kids ?: JsonArray(emptyList()), list, depth + 1)
                int(list.size)
                for (c in list) node(c, depth + 1)
            }
        }

        fun flatten(items: JsonArray, out: MutableList<JsonElement>, depth: Int) {
            if (depth > AgentWidgetWire.MAX_DEPTH) throw Invalid("the view nests deeper than ${AgentWidgetWire.MAX_DEPTH} levels")
            for (i in items) when {
                i is JsonArray -> flatten(i, out, depth + 1)
                i is JsonNull -> Unit
                i is JsonPrimitive && !i.isString && i.booleanOrNull == false -> Unit
                else -> out += i
            }
        }

        fun prop(key: String, v: JsonElement) {
            val id = PROPS.indexOf(key) + 1
            if (id == 0) return
            val n = number(v)
            val p = v as? JsonPrimitive
            val enum = ENUMS[id]
            when {
                enum != null -> if (p != null && p.isString) enum.indexOf(p.content).takeIf { it >= 0 }?.let { tag(id, 0); int(it) }
                id in BOOLS -> p?.takeIf { !it.isString }?.booleanOrNull?.let { tag(id, 0); int(if (it) 1 else 0) }
                id in STRS -> if (p != null && p.isString) { tag(id, 2); strs.str(p.content) }
                id == 17 -> when {
                    n != null -> { tag(id, 1); floats.f64(n) }
                    p != null && p.isString -> { tag(id, 2); strs.str(p.content) }
                    v is JsonArray && v.all { it is JsonPrimitive && it.isString } -> {
                        tag(id, 4); int(v.size); v.forEach { strs.str((it as JsonPrimitive).content) }
                    }
                }
                id == 21 -> if (v is JsonArray) {
                    val options = v.map { o ->
                        if (o !is JsonObject) return
                        val description = when (val d = o["description"]) {
                            null, is JsonNull -> null
                            is JsonPrimitive -> if (d.isString) d.content else return
                            else -> return
                        }
                        Triple(o.str("value") ?: return, o.str("label") ?: return, description)
                    }
                    tag(id, 5); int(options.size)
                    for ((value, label, description) in options) {
                        int(if (description != null) 1 else 0)
                        strs.str(value); strs.str(label)
                        if (description != null) strs.str(description)
                    }
                }
                id == 30 -> if (v is JsonArray) {
                    val list = v.map { number(it) ?: return }
                    tag(id, 3); int(list.size); list.forEach { floats.f64(it) }
                }
                id == 37 -> when {
                    n != null -> { tag(id, 1); floats.f64(n) }
                    v is JsonObject -> {
                        val sides = listOf("top", "right", "bottom", "left").map { k -> if (v[k] == null) 0.0 else number(v[k]) ?: return }
                        tag(id, 3); int(4); sides.forEach { floats.f64(it) }
                    }
                }
                else -> if (n != null) { tag(id, 1); floats.f64(n) }
            }
        }
    }

    // ---- draw commands ----

    private class DrawStreams {
        val ints = Bytes()
        val floats = Bytes()
        val strs = Bytes()
        var commands = 0
        fun i(v: Int) = ints.u32(v)
        fun f(v: Float) = floats.f32(v)
    }

    private fun draw(commands: JsonElement, out: Bytes) {
        val list = commands as? JsonArray ?: throw Invalid("draw commands must be a list")
        if (list.size > AgentWidgetDrawList.MAX_COMMANDS) throw Invalid("${list.size} draw commands is over the budget of ${AgentWidgetDrawList.MAX_COMMANDS} per frame")
        val d = DrawStreams()
        list.forEachIndexed { i, c ->
            try { command(d, c) } catch (e: Invalid) { throw Invalid("draw command $i: ${e.message}") }
        }
        out.u8('D'.code).u32(d.commands).u32(d.ints.size() / 4).u32(d.floats.size() / 4).u32(d.strs.size())
        out.bytes(d.ints.bytes()).bytes(d.floats.bytes()).bytes(d.strs.bytes())
    }

    private fun num(v: JsonElement?, what: String): Float = number(v)?.toFloat() ?: throw Invalid("$what must be a finite number")

    /** A colour as (kind, value): `#rgb`, `#rrggbb`, `#rrggbbaa` or a token. */
    private fun color(v: JsonElement?): Pair<Int, Int> {
        val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw Invalid("not a color")
        if (!s.startsWith("#")) {
            val token = listOf("text", "muted", "accent", "bg", "surface", "border", "success", "warning", "error").indexOf(s)
            if (token < 0) throw Invalid("not a color")
            return token + 1 to 0
        }
        val hex = s.substring(1)
        fun digit(i: Int) = hex.getOrNull(i)?.let { Character.digit(it, 16) }?.takeIf { it >= 0 } ?: throw Invalid("not a color")
        fun pair(i: Int) = digit(i) * 16 + digit(i + 1)
        val (r, g, b, a) = when (hex.length) {
            3 -> listOf(digit(0) * 17, digit(1) * 17, digit(2) * 17, 255)
            6 -> listOf(pair(0), pair(2), pair(4), 255)
            8 -> listOf(pair(0), pair(2), pair(4), pair(6))
            else -> throw Invalid("not a color")
        }
        return 0 to ((r shl 24) or (g shl 16) or (b shl 8) or a)
    }

    private fun present(m: JsonObject, key: String) = m[key]?.takeIf { it !is JsonNull }

    private fun putColor(d: DrawStreams, c: Pair<Int, Int>) { d.i(c.first); d.i(c.second) }

    private fun paint(d: DrawStreams, v: JsonElement?) {
        val m = v as? JsonObject ?: throw Invalid("a paint is an object")
        val fill = present(m, "fill")
        var gradient: Pair<Int, List<Float>>? = null
        var stops: List<Pair<Float, Pair<Int, Int>>> = emptyList()
        var fillColor: Pair<Int, Int>? = null
        if (fill is JsonObject) {
            fun coords(key: String, n: Int): List<Float>? {
                val a = fill[key] ?: return null
                if (a !is JsonArray || a.size != n) throw Invalid("a $key gradient takes $n numbers")
                return a.map { num(it, key) }
            }
            val linear = coords("linear", 4)
            val radial = coords("radial", 3)
            gradient = if (linear != null) 2 to linear else if (radial != null) 3 to radial else throw Invalid("a gradient needs linear or radial")
            val list = fill["stops"] as? JsonArray ?: throw Invalid("a gradient needs stops")
            stops = list.map { s ->
                if (s !is JsonArray || s.size != 2) throw Invalid("a stop is [offset, color]")
                num(s[0], "a stop's offset").coerceIn(0f, 1f) to color(s[1])
            }
        } else if (fill != null) {
            fillColor = color(fill)
        }
        val stroke = present(m, "stroke")?.let(::color)
        val width = when (val w = present(m, "width")) {
            null -> 1f
            else -> (number(w) ?: throw Invalid("a stroke's width must be a finite number")).toFloat().coerceAtLeast(0f)
        }
        val round = m.str("cap") == "round"
        when {
            gradient != null -> {
                d.i(gradient.first); d.i(stops.size)
                gradient.second.forEach(d::f)
                for ((o, c) in stops) { d.f(o); putColor(d, c) }
            }
            fillColor != null -> { d.i(1); putColor(d, fillColor) }
            else -> d.i(0)
        }
        if (stroke != null) { d.i(1); putColor(d, stroke) } else d.i(0)
        d.i(if (round) 1 else 0)
        d.f(width)
    }

    private fun command(d: DrawStreams, v: JsonElement) {
        val a = v as? JsonArray ?: throw Invalid("a command is an array")
        val op = (a.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw Invalid("a command starts with its name")
        fun n(i: Int) = num(a.getOrNull(i), "\"$op\" argument $i")
        when (op) {
            "save" -> d.i(1)
            "restore" -> d.i(2)
            "translate", "scale" -> { val x = n(1); val y = n(2); d.i(if (op == "translate") 3 else 5); d.f(x); d.f(y) }
            "rotate" -> { val r = n(1); d.i(4); d.f(r) }
            "alpha" -> { val x = n(1).coerceIn(0f, 1f); d.i(6); d.f(x) }
            "rect", "rrect", "circle", "line" -> {
                val (code, count) = when (op) { "rect" -> 7 to 4; "rrect" -> 8 to 5; "circle" -> 9 to 3; else -> 10 to 4 }
                val coords = (1..count).map(::n)
                val paint = DrawStreams().also { paint(it, a.getOrNull(count + 1)) }
                d.i(code)
                coords.forEach(d::f)
                d.ints.bytes(paint.ints.bytes()); d.floats.bytes(paint.floats.bytes())
            }
            "path" -> {
                val segments = a.getOrNull(1) as? JsonArray ?: throw Invalid("\"path\" takes a list of segments")
                val verbs = ArrayList<Int>()
                val coords = ArrayList<Float>()
                for (s in segments) {
                    val seg = s as? JsonArray ?: throw Invalid("a path segment is an array")
                    val (verb, count) = when ((seg.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content) {
                        "M" -> 1 to 2; "L" -> 2 to 2; "Q" -> 3 to 4; "C" -> 4 to 6; "Z" -> 5 to 0
                        else -> throw Invalid("unknown path segment")
                    }
                    verbs += verb
                    for (i in 1..count) coords += num(seg.getOrNull(i), "a path coordinate")
                }
                val paint = DrawStreams().also { paint(it, a.getOrNull(2)) }
                d.i(11); d.i(verbs.size); verbs.forEach(d::i); coords.forEach(d::f)
                d.ints.bytes(paint.ints.bytes()); d.floats.bytes(paint.floats.bytes())
            }
            "text" -> {
                val x = n(1)
                val y = n(2)
                val t = a.getOrNull(3) ?: throw Invalid("\"text\" is missing argument 3")
                val text = if (t is JsonPrimitive && t.isString) t.content else t.toString()
                val m = a.getOrNull(4) as? JsonObject ?: JsonObject(emptyMap())
                val size = when (val s = present(m, "size")) {
                    null -> 13f
                    else -> (number(s) ?: throw Invalid("a text size must be a finite number")).toFloat().coerceIn(1f, 512f)
                }
                val weight = number(present(m, "weight"))?.takeIf { it >= 0 && it % 1.0 == 0.0 && it < 9.0e15 }
                    ?.let { (it.toLong().coerceIn(100, 900) / 100 * 100).toInt() } ?: 400
                val align = when (m.str("align")) { "center" -> 1; "right" -> 2; else -> 0 }
                val color = present(m, "color")?.let(::color)
                val mono = m.bool("mono") == true
                d.i(12); d.f(x); d.f(y); d.strs.str(text)
                d.i(weight); d.i(align)
                if (color != null) { d.i(1); putColor(d, color) } else d.i(0)
                d.i(if (mono) 1 else 0)
                d.f(size)
            }
            "image" -> {
                val src = (a.getOrNull(1) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw Invalid("\"image\" takes a src")
                val coords = (2..5).map(::n)
                d.i(13); d.strs.str(src); coords.forEach(d::f)
            }
            else -> throw Invalid("unknown draw command \"$op\"")
        }
        d.commands++
    }
}
