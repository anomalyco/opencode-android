package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.tuning.Tunable
import ai.opencode.core.tuning.TunableChoice
import ai.opencode.core.tuning.TuningPreset
import ai.opencode.core.tuning.TuningPresets
import ai.opencode.core.tuning.TuningStore
import ai.opencode.feature.transcript.TranscriptTunables
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalGraphicsContext

/**
 * How streamed assistant text reveals as chunks arrive (iOS `StreamTextRevealStyle`),
 * picked live in the Tuning Panel (`transcript.textReveal.style`; [TextRevealTunables]).
 */
enum class StreamTextRevealStyle(override val rawValue: String) : TunableChoice {
    /** Today's look: each chunk fades its opacity over 0.2 s on its own clock. */
    Chunk("chunk"),
    /** Soft reading-order frontier sweeping left-to-right across each arriving chunk. */
    Frontier("frontier"),
    /** Soft frontier sweep condensing out of a 2.5 pt focus blur with a slight phosphor lift. */
    Focus("focus");

    /**
     * One arriving chunk's reveal, captured when it arrives (`transcript.textReveal.duration`;
     * the presets set 0.22 s and 0.24 s for 2 and 3).
     */
    val duration: Double get() = TranscriptTextReveal.duration

    companion object {
        val current: StreamTextRevealStyle get() = TextRevealTunables.style.value

        /**
         * iOS `StreamSweepRenderer.alpha`: a feathered frontier crossing the chunk (positions
         * 0…1) as `progress` runs 0…1, reaching full opacity everywhere by the end, so handing
         * the chunk to the base text never changes a pixel. The head moves evenly and the
         * feather spans most of a chunk, so each glyph fades over about half the reveal.
         */
        fun sweepAlpha(progress: Double, position: Double, feather: Double): Double {
            if (progress >= 1) return 1.0
            val f = 0.9 * maxOf(0.05, feather)
            val head = -f * 0.25 + (1 + f * 1.1) * progress
            val lo = head - f * 0.65
            val hi = head + f * 0.35
            val t = ((position - lo) / (hi - lo)).coerceIn(0.0, 1.0)
            val sweep = 1 - t * t * (3 - 2 * t)
            val settledT = ((progress - 0.76) / 0.24).coerceIn(0.0, 1.0)
            val settled = settledT * settledT * (3 - 2 * settledT)
            return (sweep + (1 - sweep) * settled).coerceIn(0.0, 1.0)
        }
    }
}

/**
 * Tunables (Developer Mode → Tuning → Transcript → Text Reveal; iOS `TextRevealTunables`). Each
 * chunk reads them as it arrives, so a change shows on the next chunk without a relaunch.
 */
object TextRevealTunables {
    /** The Tuning Panel sections it registers (the key segment after the group), in order. */
    val sectionTitles = listOf("Text Reveal")

    val style = Tunable("transcript.textReveal.style", "Stream reveal style", default = StreamTextRevealStyle.Chunk)
    val feather = Tunable("transcript.textReveal.feather", "Frontier feather (×)", default = 1.0, range = 0.25..3.0, step = 0.05)
    val blurRadius = Tunable("transcript.textReveal.blurRadius", "Focus blur radius (pt)", default = 2.5, range = 0.0..8.0, step = 0.25)
    val phosphorLift = Tunable("transcript.textReveal.phosphorLift", "Phosphor lift", default = 0.09, range = 0.0..0.4, step = 0.01)
    val stagger = Tunable("transcript.textReveal.stagger", "Per-chunk stagger (s)", default = 0.0, range = 0.0..0.2, step = 0.005)

    /** 1 Today · 2 Soft Frontier Sweep · 3 Focus Frontier. */
    val options: TuningPresets by lazy {
        TuningPresets(
            "transcript.textReveal", "Text Reveal",
            listOf(
                TuningPreset("Today"),
                TuningPreset("Soft Frontier Sweep", style.preset(StreamTextRevealStyle.Frontier), TranscriptTunables.Knobs.textFadeDuration.preset(0.22)),
                TuningPreset("Focus Frontier", style.preset(StreamTextRevealStyle.Focus), TranscriptTunables.Knobs.textFadeDuration.preset(0.24)),
            ),
        )
    }

    fun register(store: TuningStore = TuningStore.shared) {
        store.register(options)
        store.register(style)
        store.register(TranscriptTunables.Knobs.textFadeDuration, feather, blurRadius, phosphorLift, stagger)
    }
}

/**
 * When a streamed reply's text arrived, so what just landed fades in while earlier text stays
 * put (iOS `TranscriptTextReveal`). The list owns it, like disclosure, so a row LazyColumn
 * recomposes or brings back never fades its text again; a block it sees first (opening a
 * session, scrolling back) is history and appears at once. A plain object, read during
 * composition as iOS reads it in `body`: rendering the same blocks again records nothing.
 */
class TranscriptTextReveal {
    /** Seconds on the frame clock's timebase (`System.nanoTime`), injectable for deterministic checks. */
    var now: () -> Double = { System.nanoTime() / 1e9 }
    var chunkDuration: () -> Double = { StreamTextRevealStyle.current.duration }
    var chunkStagger: () -> Double = { TextRevealTunables.stagger.value }

    data class Arrival(
        /** Where in the block's characters the new text starts. */
        val offset: Int,
        val time: Double,
        val duration: Double = TranscriptTextReveal.duration,
    )

    private class Record(
        var kind: Int,
        var length: Int,
        var arrivals: MutableList<Arrival>,
        var cachedKind: OpenCodeMarkdownBlock.Kind?,
        /** Text arrived into it on screen. */
        var streamed: Boolean = false,
    )

    private val records = HashMap<String, HashMap<Int, Record>>()
    /** The item text last streamed into: only its last block can still grow. */
    private var growingItem: String? = null
    /** Pieces of streamed text that arrived into blocks already shown, each fading in on its own. */
    var grownArrivals = 0
        private set

    /** Whether text streamed into [blockID] of [itemID] on screen and it is still the growing tail. */
    fun streamed(itemID: String, blockID: Int): Boolean = records[itemID]?.get(blockID)?.streamed ?: false

    /** The run ended: every reply lets go of its line hold. */
    fun settleAll() {
        for (itemID in records.keys.toList()) settle(itemID)
        growingItem = null
    }

    /** The reply stopped growing: its text renders as settled text does. */
    fun settle(itemID: String) {
        val item = records[itemID] ?: return
        if (item.values.none { it.streamed }) return
        for (record in item.values) record.streamed = false
        if (growingItem == itemID) growingItem = null
    }

    /** Cached character length of [blockID] in [itemID], if already measured. */
    fun length(itemID: String, blockID: Int): Int? = records[itemID]?.get(blockID)?.length

    /**
     * Recent arrivals per block of [itemID], recording what grew since its last render.
     * Rendering the same blocks again records nothing.
     */
    fun arrivals(
        itemID: String,
        blocks: List<OpenCodeMarkdownBlock>,
        now: Double,
        useCache: Boolean = true,
    ): Map<Int, List<Arrival>> {
        val activeDuration = chunkDuration()
        // The reply's first render (the row's own entrance fades it in) or history.
        val isNew = records[itemID] == null
        val previous = records[itemID] ?: HashMap()
        // Only the blocks shown now: one merged away and then streamed in again arrives afresh.
        val item = HashMap<Int, Record>()
        val result = HashMap<Int, List<Arrival>>()
        for (block in blocks) {
            val unchanged = previous[block.id]
            if (useCache && unchanged != null && unchanged.cachedKind == block.kind) {
                unchanged.arrivals.removeAll { now - it.time >= it.duration || it.offset >= unchanged.length }
                item[block.id] = unchanged
                if (unchanged.arrivals.isNotEmpty()) result[block.id] = unchanged.arrivals.toList()
                continue
            }
            val (kind, length) = measure(block.kind)
            val record = previous[block.id]
            if (record == null || record.kind != kind) {
                // A new block in a reply already on screen streamed in whole. One whose kind
                // changed (pipes becoming a table) replaces itself.
                val arrives = !isNew && record == null && length > 0
                val arrivals = if (arrives) mutableListOf(Arrival(0, now, activeDuration)) else mutableListOf()
                item[block.id] = Record(kind, length, arrivals, if (useCache) block.kind else null, streamed = arrives)
                if (arrives) result[block.id] = arrivals.toList()
                continue
            }
            record.arrivals.removeAll { now - it.time >= it.duration || it.offset >= length }
            if (length > record.length) {
                val stagger = chunkStagger()
                val start = if (stagger > 0) maxOf(now, (record.arrivals.lastOrNull()?.time ?: Double.NEGATIVE_INFINITY) + stagger) else now
                record.arrivals.add(Arrival(record.length, start, activeDuration))
                record.streamed = true
                grownArrivals += 1
            }
            record.length = length
            record.cachedKind = if (useCache) block.kind else null
            item[block.id] = record
            if (record.arrivals.isNotEmpty()) result[block.id] = record.arrivals.toList()
        }
        // Only the last block can still grow; text streaming into another item leaves this one behind.
        val tail = blocks.lastOrNull()?.id
        for ((id, record) in item) if (id != tail) record.streamed = false
        if (item.values.any { it.streamed }) {
            growingItem?.takeIf { it != itemID }?.let { settle(it) }
            growingItem = itemID
        }
        records[itemID] = item
        return result
    }

    companion object {
        /** Short enough to keep up with a fast stream (`transcript.textFadeDuration`). */
        val duration: Double get() = TranscriptTunables.textFadeDuration

        /**
         * A block's kind and how many characters it shows (UTF-16, as Android text offsets
         * are). Kinds without characters fade in whole when they arrive.
         */
        private fun measure(kind: OpenCodeMarkdownBlock.Kind): Pair<Int, Int> = when (kind) {
            is OpenCodeMarkdownBlock.Kind.Paragraph -> 0 to kind.text.text.length
            is OpenCodeMarkdownBlock.Kind.NumberedItem -> 1 to kind.text.text.length
            is OpenCodeMarkdownBlock.Kind.ListItem -> 1 to kind.text.text.length
            is OpenCodeMarkdownBlock.Kind.Blockquote -> 2 to kind.text.text.length
            is OpenCodeMarkdownBlock.Kind.Heading -> 3 to kind.text.text.length
            is OpenCodeMarkdownBlock.Kind.Code -> 4 to kind.code.source.length
            is OpenCodeMarkdownBlock.Kind.Table -> 5 to 1
            is OpenCodeMarkdownBlock.Kind.Image -> 6 to 1
            is OpenCodeMarkdownBlock.Kind.PullRequest -> 7 to 1
            OpenCodeMarkdownBlock.Kind.Divider -> 8 to 1
            is OpenCodeMarkdownBlock.Kind.Files -> 9 to 1
            is OpenCodeMarkdownBlock.Kind.Math -> 10 to 1
            is OpenCodeMarkdownBlock.Kind.Mermaid -> 11 to 1
        }

        /** iOS `ArrivalFade`'s curve: ease-out from where the arrival's clock stands. */
        fun opacity(arrival: Arrival, now: Double): Float {
            val u = ((now - arrival.time) / maxOf(0.01, arrival.duration)).coerceIn(0.0, 1.0)
            return (1 - (1 - u) * (1 - u)).toFloat()
        }
    }
}

/**
 * iOS `EnvironmentValues.transcriptTextReveal`. The list provides one; without it,
 * `MarkdownBlocksView` keeps one per row (a row LazyColumn brings back starts as history).
 */
val LocalTranscriptTextReveal = staticCompositionLocalOf<TranscriptTextReveal?> { null }

/**
 * A frame clock that ticks only until [ends] (seconds on [TranscriptTextReveal.now]'s clock):
 * read the returned state in a draw lambda so each frame redraws without recomposing or
 * relaying out (iOS: the fades run on the render server).
 */
@Composable
internal fun rememberRevealFrame(ends: Double?, now: () -> Double): androidx.compose.runtime.MutableLongState {
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(ends) {
        if (ends == null) return@LaunchedEffect
        while (now() < ends) {
            withFrameNanos { frame.longValue = it }
        }
        frame.longValue = frame.longValue + 1
    }
    return frame
}

/**
 * iOS `ArrivalFade` for a whole block: fades it in from where its arrival's clock stands.
 * Only opacity changes per frame (a graphics layer property); the block is laid out once.
 * Whole blocks are always wrapped, arriving or not, so a fade never changes their identity.
 */
@Composable
internal fun Modifier.arrivalFade(arrival: TranscriptTextReveal.Arrival?, reveal: TranscriptTextReveal): Modifier {
    val frame = rememberRevealFrame(arrival?.let { it.time + it.duration }, reveal.now)
    return this then ArrivalFadeElement(arrival, reveal, frame)
}

private data class ArrivalFadeElement(
    val arrival: TranscriptTextReveal.Arrival?,
    val reveal: TranscriptTextReveal,
    val frame: LongState,
) : ModifierNodeElement<ArrivalFadeNode>() {
    override fun create() = ArrivalFadeNode(arrival, reveal, frame)
    override fun update(node: ArrivalFadeNode) {
        node.arrival = arrival
        node.reveal = reveal
        node.frame = frame
    }
}

private class ArrivalFadeNode(
    var arrival: TranscriptTextReveal.Arrival?,
    var reveal: TranscriptTextReveal,
    var frame: LongState,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private var layer: GraphicsLayer? = null
    private var layerOwner: GraphicsContext? = null

    override fun ContentDrawScope.draw() {
        val current = arrival
        if (current != null) frame.longValue
        val opacity = if (current == null) 1f else TranscriptTextReveal.opacity(current, reveal.now())
        if (opacity >= 0.999f || size.width <= 0f || size.height <= 0f) {
            dropLayer()
            drawContent()
            return
        }
        val graphics = currentValueOf(LocalGraphicsContext)
        if (layerOwner !== graphics) dropLayer()
        val activeLayer = layer ?: graphics.createGraphicsLayer().also {
            layer = it
            layerOwner = graphics
        }
        activeLayer.alpha = opacity
        activeLayer.record { this@draw.drawContent() }
        drawLayer(activeLayer)
    }

    override fun onReset() = dropLayer()

    override fun onDetach() = dropLayer()

    private fun dropLayer() {
        val existing = layer ?: return
        layer = null
        layerOwner?.releaseGraphicsLayer(existing)
        layerOwner = null
    }
}
