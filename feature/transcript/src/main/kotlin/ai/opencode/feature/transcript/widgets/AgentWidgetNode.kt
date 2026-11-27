package ai.opencode.feature.transcript.widgets

import androidx.compose.runtime.Immutable

/**
 * The view tree a step returns (the contract's Nodes): every node is `{ type, ...props }`, read from the binary
 * format ([AgentWidgetWire]). Unknown props are ignored and unknown node types are dropped from their parent; a known
 * node with a missing or mistyped required prop fails the whole view. An unknown value of an enumerated prop (a tone,
 * a style) falls back to its default.
 */
@Immutable
sealed interface AgentWidgetNode {
    enum class Tone { Default, Muted, Accent, Success, Warning, Error }
    enum class TextStyle { Title, Body, Caption, Code }
    enum class Align { Start, Center, End, Stretch }
    enum class Variant { Primary, Secondary, Danger }

    data class Column(override val children: List<AgentWidgetNode>, val gap: Int?, val align: Align) : AgentWidgetNode
    data class Row(override val children: List<AgentWidgetNode>, val gap: Int?, val align: Align, val wrap: Boolean) : AgentWidgetNode
    data class Text(val text: String, val style: TextStyle, val tone: Tone) : AgentWidgetNode
    data class Markdown(val text: String) : AgentWidgetNode
    data class Image(val src: String, val alt: String?, val aspect: Float?) : AgentWidgetNode
    data class Carousel(val id: String?, val index: Int?, override val children: List<AgentWidgetNode>) : AgentWidgetNode
    data class Card(val id: String?, val selected: Boolean, override val children: List<AgentWidgetNode>) : AgentWidgetNode
    data class Button(val id: String, val label: String, val variant: Variant, val disabled: Boolean) : AgentWidgetNode
    data class Checkbox(val id: String, val label: String, val checked: Boolean, val disabled: Boolean) : AgentWidgetNode
    data class Slider(val id: String, val label: String?, val value: Float, val min: Float, val max: Float, val step: Float?) : AgentWidgetNode
    data class Select(val id: String, val label: String?, val options: List<Option>, val value: List<String>, val multiple: Boolean) : AgentWidgetNode {
        data class Option(val value: String, val label: String, val description: String?)
    }
    data class Input(val id: String, val label: String?, val value: String, val placeholder: String?, val multiline: Boolean) : AgentWidgetNode
    data object Divider : AgentWidgetNode

    /**
     * Rows of text under a header row, each row as wide as [columns]. Only when [selectable] do rows take presses, and
     * only when [sortable] do headers; [sort] marks a header, the program sorts the rows itself.
     */
    data class Table(
        val id: String?,
        val columns: List<Column>,
        val rows: List<List<String>>,
        val sort: Sort?,
        val selected: Int?,
        val selectable: Boolean,
        val sortable: Boolean,
    ) : AgentWidgetNode {
        data class Column(val label: String, val end: Boolean)
        data class Sort(val column: Int, val descending: Boolean)
    }
    /**
     * A drawing surface, drawn one way: by the widget template's `draw` commands (2D), with [wgsl] by that fragment
     * shader on the GPU, fed [values] (16 floats, zero padded; null for 2D), or with [gpu] by a live program's WebGPU
     * batches (widgets-gpu.md), which the runtime replays into a surface the app attaches. Only with [pointer] (the
     * prelude sets it for an `onPointer` handler) does it take pointer input; an [id] alone is for live `widget.draw`.
     */
    data class Canvas(
        val id: String?,
        val height: Float,
        val width: Float?,
        val fps: Int,
        val duration: Float?,
        val alt: String?,
        val fill: Boolean = true,
        val wgsl: String? = null,
        val values: FloatArray? = null,
        val poster: String? = null,
        val pointer: Boolean = false,
        val gpu: Boolean = false,
    ) : AgentWidgetNode {
        val shader: Boolean get() = wgsl != null

        override fun equals(other: Any?): Boolean = other is Canvas && id == other.id && height == other.height &&
            width == other.width && fps == other.fps && duration == other.duration && alt == other.alt &&
            fill == other.fill && wgsl == other.wgsl && values.contentEquals(other.values) && poster == other.poster &&
            pointer == other.pointer && gpu == other.gpu

        override fun hashCode(): Int = ((wgsl.hashCode() * 31 + values.contentHashCode()) * 31 + height.hashCode()) * 31 + id.hashCode()
    }

    /** Children layered in one box, the first at the bottom, each placed by its [layers] entry. */
    data class Stack(override val children: List<AgentWidgetNode>, val layers: List<Layer>) : AgentWidgetNode

    /** Where a stack's child sits in the box, as fractions of the free space ([Fill] takes the whole area). */
    enum class Place(val x: Float, val y: Float) {
        TopLeft(0f, 0f), Top(0.5f, 0f), TopRight(1f, 0f),
        Left(0f, 0.5f), Center(0.5f, 0.5f), Right(1f, 0.5f),
        BottomLeft(0f, 1f), Bottom(0.5f, 1f), BottomRight(1f, 1f),
        Fill(0f, 0f),
    }

    /** A stack child's `place` and `inset` (logical px per side): its area is the stack's box minus the inset. */
    data class Layer(val place: Place = Place.TopLeft, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f, val left: Float = 0f)

    val children: List<AgentWidgetNode> get() = emptyList()

    companion object {
        const val MAX_FPS = 60
        const val MAX_WGSL_BYTES = 16 * 1024
        const val MAX_TABLE_COLUMNS = 16
        const val MAX_TABLE_ROWS = 500

        /** Whether [node] or one under it is a `canvas`: such a view offers full screen unless it says not to. */
        fun drawsPixels(node: AgentWidgetNode): Boolean =
            node is Canvas || node.children.any(::drawsPixels)

        /**
         * Whether [node] or one under it grows to fill the height in full screen: a `canvas` (unless `fill: false`), or a
         * stack with such a canvas among its children.
         */
        fun fills(node: AgentWidgetNode): Boolean = when (node) {
            is Canvas -> node.fill
            is Stack -> node.children.any { it is Canvas && it.fill }
            else -> node.children.any(::fills)
        }

        /** Whether [node] takes pointer input in a stack: an interactive node, or a container through one under it. */
        fun takesPointer(node: AgentWidgetNode): Boolean = when (node) {
            is Button, is Checkbox, is Slider, is Select, is Input -> true
            is Table -> node.selectable || node.sortable
            is Card -> node.id != null || node.children.any(::takesPointer)
            is Carousel -> node.id != null || node.children.any(::takesPointer)
            is Canvas -> node.pointer
            else -> node.children.any(::takesPointer)
        }

        /**
         * The `key` a keyed node's [id] carries (its last path segment, `:` and the key URI-encoded), or null without
         * one or for a repeat of a key among its siblings (`~2`), which a live run can't address.
         */
        fun canvasKey(id: String): String? {
            val last = id.substringAfterLast('/')
            if (!last.startsWith(":") || Regex("~\\d+$").containsMatchIn(last)) return null
            return runCatching { java.net.URLDecoder.decode(last.substring(1).replace("+", "%2B"), "UTF-8") }.getOrNull()
        }

        /** Visits [node] and every node under it, depth first. */
        fun walk(node: AgentWidgetNode, visit: (AgentWidgetNode) -> Unit) {
            visit(node)
            for (child in node.children) walk(child, visit)
        }

        internal val CONTAINERS = setOf("column", "row", "carousel", "card", "stack")

        /**
         * A node of a known [type] from its props: every default, clamp and required prop. A missing or mistyped
         * required prop fails the view.
         */
        internal fun build(type: String, f: Fields, children: List<AgentWidgetNode>, layers: List<Layer>): AgentWidgetNode = when (type) {
            "column" -> Column(children, f.gap(), f.enum("align", Align.Stretch))
            "row" -> Row(children, f.gap(), f.enum("align", Align.Start).let { if (it == Align.Stretch) Align.Start else it }, f.bool("wrap"))
            "text" -> Text(f.required(type, "text"), f.enum("style", TextStyle.Body), f.enum("tone", Tone.Default))
            "markdown" -> Markdown(f.required(type, "text"))
            "image" -> Image(f.required(type, "src"), f.string("alt"), f.number("aspect")?.toFloat()?.takeIf { it > 0f && it.isFinite() })
            "carousel" -> Carousel(f.string("id"), f.number("index")?.toInt(), children)
            "card" -> Card(f.string("id"), f.bool("selected"), children)
            "button" -> Button(f.required(type, "id"), f.required(type, "label"), f.enum("variant", Variant.Secondary), f.bool("disabled"))
            "checkbox" -> Checkbox(f.required(type, "id"), f.required(type, "label"), f.requiredBool(type, "checked"), f.bool("disabled"))
            "slider" -> {
                val min = f.requiredNumber(type, "min").toFloat()
                val max = f.requiredNumber(type, "max").toFloat()
                Slider(f.required(type, "id"), f.string("label"), f.requiredNumber(type, "value").toFloat(), min, maxOf(min, max), f.number("step")?.toFloat()?.takeIf { it > 0f })
            }
            "select" -> {
                val options = f.options() ?: throw InvalidView("invalid view: select needs options")
                Select(f.required(type, "id"), f.string("label"), options, f.strings("value"), f.bool("multiple"))
            }
            "input" -> Input(f.required(type, "id"), f.string("label"), f.required(type, "value"), f.string("placeholder"), f.bool("multiline"))
            "divider" -> Divider
            "table" -> table(f)
            "stack" -> Stack(children, layers)
            "canvas" -> {
                val wgsl = f.string("wgsl")
                if (wgsl != null && wgsl.encodeToByteArray().size > MAX_WGSL_BYTES) throw InvalidView("invalid view: a canvas's wgsl must be at most 16 KB")
                if (f.has("values") && wgsl == null) throw InvalidView("invalid view: canvas values need wgsl")
                Canvas(
                    f.string("id"),
                    f.requiredNumber(type, "height").toFloat().coerceIn(1f, 2000f),
                    f.number("width")?.toFloat()?.takeIf { it > 0f },
                    (f.number("fps")?.toInt() ?: if (wgsl != null) MAX_FPS else 0).coerceIn(0, MAX_FPS),
                    f.number("duration")?.toFloat()?.takeIf { it >= 0f },
                    f.string("alt"),
                    f.bool("fill", default = true),
                    wgsl,
                    if (wgsl == null) null else FloatArray(16).also { f.values(it) },
                    f.string("poster"),
                    f.bool("pointer"),
                    f.bool("gpu"),
                )
            }
            else -> throw IllegalArgumentException(type)
        }

        private fun table(f: Fields): Table {
            val labels = f.list("columns") ?: throw InvalidView("invalid view: table needs columns")
            val cells = f.list("rows") ?: throw InvalidView("invalid view: table needs rows")
            if (labels.isEmpty() || labels.size > MAX_TABLE_COLUMNS) throw InvalidView("invalid view: a table has 1 to $MAX_TABLE_COLUMNS columns")
            if (cells.size % labels.size != 0) throw InvalidView("invalid view: a table's cells don't fill its rows")
            val rows = cells.chunked(labels.size)
            if (rows.size > MAX_TABLE_ROWS) throw InvalidView("invalid view: a table has at most $MAX_TABLE_ROWS rows")
            val aligns = f.numbers("aligns")
            val sort = f.numbers("sort")?.let { s ->
                val column = s.getOrNull(0)
                if (s.size != 2 || column == null || column < 0 || column != Math.floor(column)) {
                    throw InvalidView("invalid view: a table's sort is a column and a direction")
                }
                if (column >= labels.size) throw InvalidView("invalid view: `sort.column` must be one of the columns")
                Table.Sort(column.toInt(), s[1] == 1.0)
            }
            val selected = f.int("row")?.let { row ->
                if (row >= rows.size) throw InvalidView("invalid view: `selected` must be one of the rows")
                row.toInt()
            }
            return Table(
                f.string("id"),
                labels.mapIndexed { i, label -> Table.Column(label, end = aligns?.getOrNull(i) == 1.0) },
                rows,
                sort,
                selected,
                f.bool("selectable"),
                f.bool("sortable"),
            )
        }

        /** A stack child's `place` and `inset`, from the child's own props. */
        internal fun layer(f: Fields): Layer {
            val place = f.string("place")?.replace("-", "")?.let { raw -> Place.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } } ?: Place.TopLeft
            val sides = f.inset() ?: return Layer(place)
            return Layer(place, sides[0].coerceAtLeast(0f), sides[1].coerceAtLeast(0f), sides[2].coerceAtLeast(0f), sides[3].coerceAtLeast(0f))
        }

        private fun Fields.gap(): Int? = number("gap")?.toInt()?.coerceIn(0, 3)

        private fun Fields.bool(key: String, default: Boolean = false): Boolean = boolean(key) ?: default

        private fun Fields.required(type: String, key: String): String =
            string(key) ?: throw InvalidView("invalid view: $type needs a string $key")

        private fun Fields.requiredNumber(type: String, key: String): Double =
            number(key) ?: throw InvalidView("invalid view: $type needs a number $key")

        private fun Fields.requiredBool(type: String, key: String): Boolean =
            boolean(key) ?: throw InvalidView("invalid view: $type needs a boolean $key")

        private inline fun <reified E : Enum<E>> Fields.enum(key: String, default: E): E {
            val raw = string(key) ?: return default
            return enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: default
        }
    }

    class InvalidView(message: String) : Exception(message)

    /** A node's props as the reader has them; enumerated props come back as their wire strings (`"top-left"`). */
    interface Fields {
        /** A string prop; null when absent or not a string. */
        fun string(key: String): String?
        /** A finite number prop; null when absent or not a number. */
        fun number(key: String): Double?
        /** A boolean prop; null when absent or not a boolean. */
        fun boolean(key: String): Boolean?
        /** Whether the prop is there and not null. */
        fun has(key: String): Boolean
        /** `select.value`: one string, or a list's strings; empty otherwise. */
        fun strings(key: String): List<String>
        /** `canvas.values` into [into] (16 floats, zero padded). */
        fun values(into: FloatArray)
        /** `select.options`, or null when absent or not a list; an option without its value or label fails the view. */
        fun options(): List<Select.Option>?
        /** `inset` as top, right, bottom, left (unclamped), or null when absent. */
        fun inset(): FloatArray?
        /** A string list prop (a table's `columns` labels and `rows` cells), or null when absent. */
        fun list(key: String): List<String>?
        /** A number list prop (a table's column `aligns` and `sort`), or null when absent. */
        fun numbers(key: String): DoubleArray?
        /** An int prop (a table's selected `row`), or null when absent. */
        fun int(key: String): Long?
    }
}
