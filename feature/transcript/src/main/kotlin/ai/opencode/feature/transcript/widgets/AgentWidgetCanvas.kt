package ai.opencode.feature.transcript.widgets

import ai.opencode.core.log.AppLog
import ai.opencode.core.dependencies.dependency
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.server.OpenCodeClient
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.excludedFromDrawerPan
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * A 2D `canvas` node: the widget template's `draw` runs on the widget's lane when the canvas appears or
 * its state changes, then every frame while `fps > 0` (and `duration` hasn't run out) and the
 * row is on screen; frames come from `withFrameNanos`, so a hidden or stopped screen draws
 * nothing. Its commands are decoded into flat arrays and replayed on the native canvas. Three
 * frames over the 8 ms budget in a row stop the animation on its last frame; more than 2,000
 * commands fail the draw (the runtime's encoder refuses them).
 */
@Composable
fun AgentWidgetCanvas(node: AgentWidgetNode.Canvas, scope: AgentWidgetScope, modifier: Modifier = Modifier) {
    val controller = scope.controller
    val colors = OpenCodeTheme.colors
    val density = LocalDensity.current.density
    val palette = remember(colors) { AgentWidgetDrawList.Palette.of(colors) }
    val dark = colors.canvas.luminance() < 0.5f
    val state = remember { CanvasState() }
    var revision by remember { mutableIntStateOf(0) }
    val row = LocalTranscriptRowContext.current
    val context = LocalContext.current
    val loads = rememberCoroutineScope()
    val images = remember(row.server, row.directory) {
        CanvasImages { src, widthPx ->
            loads.launch {
                val client = dependency(OpenCodeClient)
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching { loadWidgetImage(context, client, row.server, row.directory, src, widthPx) }.getOrNull()
                }
                state.images.arrived(src, bitmap)
                revision++
            }
        }
    }
    state.images = images
    val drawState = controller?.phase?.state
    val currentScope by rememberUpdatedState(scope)
    var visible by remember { mutableStateOf(false) }
    val fps = rememberAgentWidgetFpsMeter()

    val fullscreen = LocalAgentWidgetFullscreen.current
    val grows = fullscreen && LocalAgentWidgetGrows.current && node.fill
    // Live mode: the program posts this canvas's commands; it draws the latest and reports its size and pointer.
    val liveMode = controller?.isLiveMode == true
    val liveKey = node.id ?: ""
    val liveCanvas = remember(controller, liveKey) { AgentWidgetLiveCanvas() }
    if (liveMode && controller != null) {
        DisposableEffect(controller, liveKey) {
            controller.liveCanvases[liveKey] = liveCanvas
            onDispose { if (controller.liveCanvases[liveKey] === liveCanvas) controller.liveCanvases.remove(liveKey) }
        }
        // Read outside composition: a draw post decodes and redraws this canvas without recomposing it.
        LaunchedEffect(controller, liveKey, palette) {
            snapshotFlow { controller.liveDraws[liveKey] }.collectLatest { commands ->
                commands ?: return@collectLatest
                val started = System.nanoTime()
                val decoded = state.buffers.update({ list ->
                    withContext(Dispatchers.Default) {
                        try {
                            AgentWidgetWire.draw(commands.message, commands.section, palette, list)
                            true
                        } catch (error: AgentWidgetWire.Malformed) {
                            controller.clientError("draw", error.message ?: "malformed draw")
                            false
                        }
                    }
                }) { it }
                if (decoded) revision++
                state.noteFrame(0, (System.nanoTime() - started) / 1_000)
            }
        }
    }
    state.pointerSink = if (liveMode) liveCanvas else null

    LaunchedEffect(controller, drawState, node.fps, node.duration, palette, state.size, dark, visible, fullscreen, liveMode) {
        controller ?: return@LaunchedEffect
        if (liveMode) return@LaunchedEffect
        // Off screen it keeps its last frame; the first one waits until it's seen, and every one for a positive size.
        if (state.size.width == 0 || state.size.height == 0 || !visible) return@LaunchedEffect
        var last = 0L
        var overruns = 0
        val interval = if (node.fps > 0) 1_000_000_000L / node.fps else Long.MAX_VALUE
        var first = true
        while (true) {
            val now = withFrameNanos { it }
            if (state.startNanos == 0L) state.startNanos = controller.startNanos("canvas:${node.id}", now)
            if (!first && now - last < interval - 2_000_000L) continue
            val t = (now - state.startNanos) / 1e9
            val dt = if (last == 0L) 0.0 else (now - last) / 1e9
            last = now
            val step = state.step(controller, state.frameJSON(node.id, t, dt, density, dark, fullscreen, controller.closeOver(state.bounds)), palette) ?: break
            if (step.drawn) revision++
            overruns = if (step.overBudget) overruns + 1 else 0
            first = false
            val duration = node.duration
            if (overruns >= 3) {
                AppLog.perf.notice { "Widget canvas stopped: over budget three frames in a row" }
                // An intro stopped early still lands on its end state.
                if (duration != null && t < duration) {
                    val end = state.step(controller, state.frameJSON(node.id, duration.toDouble(), 0.0, density, dark, fullscreen, controller.closeOver(state.bounds)), palette)
                    if (end?.drawn == true) revision++
                }
                break
            }
            if (node.fps <= 0 || (duration != null && t >= duration)) break
        }
        state.report()
    }

    val width = node.width
    val alt = node.alt
    Canvas(
        modifier
            .then(if (width != null) Modifier.width(width.dp) else Modifier.fillMaxWidth())
            .then(if (grows) Modifier.heightIn(min = node.height.dp).fillMaxHeight() else Modifier.height(node.height.dp))
            .clip(OpenCodeShape.control)
            .onSizeChanged {
                state.size = it
                liveCanvas.width = it.width / density
                liveCanvas.height = it.height / density
            }
            .onGloballyPositioned {
                val bounds = it.boundsInWindow()
                state.bounds = bounds
                liveCanvas.bounds = bounds
            }
            .onVisibilityChanged(minFractionVisible = VISIBLE_FRACTION) { visible = it }
            .excludedFromDrawerPan(node.pointer && scope.live)
            .then(if (alt != null) Modifier.semantics { contentDescription = alt } else Modifier)
            .agentWidgetPointer(node.id.takeIf { node.pointer }, density, { currentScope.send(it) }, { controller?.closeOver(state.bounds) }) { state.pointer = it }
            .agentWidgetFpsBadge(fps),
    ) {
        val drawn = revision
        if (fps != null && fps.revision != drawn) {
            fps.revision = drawn
            fps.frames++
        }
        state.renderer.replay(this, state.buffers.shown, density, state.images)
    }
}

/**
 * A canvas's pointer: [track] gets it while down (null after) for `frame.pointer`, and with an [id] (a canvas with
 * `pointer: true`) its `pointer` events go out: `down`, `move` while down (at most every 16 ms) and `up`, in logical
 * px with its size. A vertical drag ends the press and scrolls the list; only a canvas that sends keeps a sideways one.
 */
internal fun Modifier.agentWidgetPointer(
    id: String?,
    density: Float,
    send: (AgentWidgetEvent) -> Unit,
    close: () -> AgentWidgetRect? = { null },
    track: (FloatArray?) -> Unit = {},
): Modifier = pointerInput(id) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitPointerEvent()
            val first = down.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
            val w = size.width / density
            val h = size.height / density
            track(floatArrayOf(first.position.x / density, first.position.y / density))
            if (id != null) send(AgentWidgetEvent.pointer(id, "down", first.position.x / density, first.position.y / density, w, h, close()))
            var horizontal: Boolean? = null
            var lastMove = 0L
            val origin = first.position
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == first.id } ?: break
                val x = change.position.x / density
                val y = change.position.y / density
                if (!change.pressed) {
                    track(null)
                    if (id != null) send(AgentWidgetEvent.pointer(id, "up", x, y, w, h, close()))
                    break
                }
                val dx = abs(change.position.x - origin.x)
                val dy = abs(change.position.y - origin.y)
                if (horizontal == null && (dx > viewConfiguration.touchSlop || dy > viewConfiguration.touchSlop)) horizontal = dx >= dy
                if (horizontal == false) {
                    track(null)
                    if (id != null) send(AgentWidgetEvent.pointer(id, "up", x, y, w, h, close()))
                    break
                }
                if (horizontal == true && id != null) change.consume()
                track(floatArrayOf(x, y))
                if (id != null && change.uptimeMillis - lastMove >= 16) {
                    lastMove = change.uptimeMillis
                    send(AgentWidgetEvent.pointer(id, "move", x, y, w, h, close()))
                }
            }
        }
    }
}

private const val DRAW_BUDGET_MICROS = 8_000L

/**
 * A canvas's two draw lists: the one on screen and the one the next draw decodes into. One update at a time: a
 * restarted draw effect leaves a cancelled decode still writing its list, which must finish before the next decode
 * starts, or both write the list that gets shown.
 */
internal class CanvasDrawBuffers {
    var shown = AgentWidgetDrawList()
        private set
    private var spare = AgentWidgetDrawList()
    private val lock = Mutex()

    /** Runs [fill] on the spare list and shows it when [show] says the result is a complete draw. */
    suspend fun <T> update(fill: suspend (AgentWidgetDrawList) -> T, show: (T) -> Boolean): T = lock.withLock {
        val result = fill(spare)
        if (show(result)) {
            val previous = shown
            shown = spare
            spare = previous
        }
        result
    }
}

/** One canvas's frames: its draw lists, the pointer, timing. */
private class CanvasState {
    val buffers = CanvasDrawBuffers()
    var images = CanvasImages { _, _ -> }
    val renderer = CanvasRenderer()
    var size by mutableStateOf(IntSize.Zero)
    /** Where it is in its window (px), for the full-screen close area over it. */
    var bounds: Rect? = null
    var startNanos = 0L
    /** A live canvas's frames carry the pointer too. */
    var pointerSink: AgentWidgetLiveCanvas? = null
    @Volatile var pointer: FloatArray? = null
        set(value) {
            field = value
            pointerSink?.pointer = value
        }
    private var frames = 0
    private var stepTotal = 0L
    private var decodeTotal = 0L
    private var stepMax = 0L

    class Step(val drawn: Boolean, val overBudget: Boolean)

    /** One draw step decoded on the widget's lane, then shown; null when the canvas can't draw. */
    suspend fun step(controller: AgentWidgetController, frame: String, palette: AgentWidgetDrawList.Palette): Step? {
        var decoded = false
        var decodeMicros = 0L
        val micros = try {
            buffers.update({ list ->
                controller.draw(frame) { bytes ->
                    val started = System.nanoTime()
                    decoded = try {
                        AgentWidgetWire.drawOutput(bytes, palette, list)
                        true
                    } catch (error: AgentWidgetWire.Malformed) {
                        controller.clientError("draw", error.message ?: "malformed draw")
                        false
                    }
                    decodeMicros = (System.nanoTime() - started) / 1_000
                }
            }) { it != null && decoded } ?: return null
        } catch (error: AgentWidgetEngineException) {
            if (!error.isBudget) {
                AppLog.app.notice { "Widget canvas draw failed: ${error.message?.take(120)}" }
                controller.reportError(error.phase ?: "draw", error)
                return null
            }
            return Step(drawn = false, overBudget = true)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLog.app.notice { "Widget canvas draw failed: ${error.javaClass.simpleName}" }
            return null
        }
        noteFrame(micros, decodeMicros)
        return Step(drawn = decoded, overBudget = micros > DRAW_BUDGET_MICROS)
    }

    fun frameJSON(id: String?, t: Double, dt: Double, scale: Float, dark: Boolean, fullscreen: Boolean, close: AgentWidgetRect?): String {
        val p = pointer
        val w = size.width / scale
        val h = size.height / scale
        return buildString(160) {
            append("{\"id\":")
            append(if (id == null) "null" else kotlinx.serialization.json.JsonPrimitive(id).toString())
            append(",\"t\":").append(t).append(",\"dt\":").append(dt)
            append(",\"width\":").append(w).append(",\"height\":").append(h).append(",\"scale\":").append(scale)
            append(",\"pointer\":")
            if (p == null) append("null") else append("{\"x\":").append(p[0]).append(",\"y\":").append(p[1]).append(",\"down\":true}")
            append(",\"dark\":").append(dark).append(",\"fullscreen\":").append(fullscreen)
            append(",\"close\":").append(close?.json ?: "null").append('}')
        }
    }

    fun noteFrame(stepMicros: Long, decodeMicros: Long) {
        frames++
        stepTotal += stepMicros
        decodeTotal += decodeMicros
        if (stepMicros > stepMax) stepMax = stepMicros
        if (frames == 120) report()
    }

    /** Logs the frames since the last report: every 120, and when an animation ends. */
    fun report() {
        if (frames == 0) return
        AppLog.perf.info {
            "Widget canvas: $frames frames, draw step avg ${stepTotal / frames} µs (max $stepMax), decode avg ${decodeTotal / frames} µs, ${buffers.shown.commandCount} commands"
        }
        frames = 0
        stepTotal = 0
        decodeTotal = 0
        stepMax = 0
    }
}

/**
 * A draw list's opacity: `alpha` sets it (clamped to 0…1, like Canvas 2D `globalAlpha`), `save`/`restore` keep and
 * bring back the value. Saves deeper than [capacity] still balance their restores but keep the current value.
 */
internal class CanvasOpacity(private val capacity: Int = 64) {
    private val saved = FloatArray(capacity)
    private var depth = 0

    var value = 1f
        private set

    fun reset() {
        value = 1f
        depth = 0
    }

    fun set(alpha: Float) {
        value = alpha.coerceIn(0f, 1f)
    }

    fun save() {
        if (depth < capacity) saved[depth] = value
        depth++
    }

    /** Returns false for an unmatched restore, which changes nothing. */
    fun restore(): Boolean {
        if (depth == 0) return false
        depth--
        if (depth < capacity) value = saved[depth]
        return true
    }
}

/** Replays a [AgentWidgetDrawList] on the native canvas with reused paints and path. */
private class CanvasRenderer {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF()
    private val opacity = CanvasOpacity()
    private val typefaces = HashMap<Int, Typeface>()
    var replayMicros = 0L
        private set

    fun typeface(weight: Int, mono: Boolean): Typeface = typefaces.getOrPut(weight * 2 + if (mono) 1 else 0) {
        Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF, weight.coerceIn(100, 900), false)
    }

    fun replay(scope: DrawScope, list: AgentWidgetDrawList, density: Float, images: CanvasImages) {
        val started = System.nanoTime()
        scope.drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            val base = canvas.save()
            canvas.scale(density, density)
            val ints = list.ints
            val floats = list.floats
            var ip = 0
            var fp = 0
            var sp = 0
            opacity.reset()
            var alpha = opacity.value
            val end = list.intCount
            while (ip < end) {
                when (ints[ip++]) {
                    AgentWidgetDrawList.SAVE -> {
                        canvas.save()
                        opacity.save()
                    }
                    AgentWidgetDrawList.RESTORE -> if (opacity.restore()) {
                        canvas.restore()
                        alpha = opacity.value
                    }
                    AgentWidgetDrawList.TRANSLATE -> { canvas.translate(floats[fp], floats[fp + 1]); fp += 2 }
                    AgentWidgetDrawList.ROTATE -> { canvas.rotate(Math.toDegrees(floats[fp].toDouble()).toFloat()); fp += 1 }
                    AgentWidgetDrawList.SCALE -> { canvas.scale(floats[fp], floats[fp + 1]); fp += 2 }
                    AgentWidgetDrawList.ALPHA -> { opacity.set(floats[fp]); alpha = opacity.value; fp += 1 }
                    AgentWidgetDrawList.RECT -> {
                        val x = floats[fp]; val y = floats[fp + 1]; val w = floats[fp + 2]; val h = floats[fp + 3]
                        fp += 4
                        val (nip, nfp) = paints(ints, floats, ip, fp, alpha)
                        ip = nip; fp = nfp
                        if (hasFill) canvas.drawRect(x, y, x + w, y + h, fill)
                        if (hasStroke) canvas.drawRect(x, y, x + w, y + h, stroke)
                    }
                    AgentWidgetDrawList.RRECT -> {
                        val x = floats[fp]; val y = floats[fp + 1]; val w = floats[fp + 2]; val h = floats[fp + 3]; val r = floats[fp + 4]
                        fp += 5
                        val (nip, nfp) = paints(ints, floats, ip, fp, alpha)
                        ip = nip; fp = nfp
                        if (hasFill) canvas.drawRoundRect(x, y, x + w, y + h, r, r, fill)
                        if (hasStroke) canvas.drawRoundRect(x, y, x + w, y + h, r, r, stroke)
                    }
                    AgentWidgetDrawList.CIRCLE -> {
                        val cx = floats[fp]; val cy = floats[fp + 1]; val r = floats[fp + 2]
                        fp += 3
                        val (nip, nfp) = paints(ints, floats, ip, fp, alpha)
                        ip = nip; fp = nfp
                        if (hasFill) canvas.drawCircle(cx, cy, r, fill)
                        if (hasStroke) canvas.drawCircle(cx, cy, r, stroke)
                    }
                    AgentWidgetDrawList.LINE -> {
                        val x1 = floats[fp]; val y1 = floats[fp + 1]; val x2 = floats[fp + 2]; val y2 = floats[fp + 3]
                        fp += 4
                        val (nip, nfp) = paints(ints, floats, ip, fp, alpha)
                        ip = nip; fp = nfp
                        if (hasStroke) canvas.drawLine(x1, y1, x2, y2, stroke)
                    }
                    AgentWidgetDrawList.PATH -> {
                        path.rewind()
                        val count = ints[ip++]
                        repeat(count) {
                            when (ints[ip++]) {
                                AgentWidgetDrawList.SEG_M -> { path.moveTo(floats[fp], floats[fp + 1]); fp += 2 }
                                AgentWidgetDrawList.SEG_L -> { path.lineTo(floats[fp], floats[fp + 1]); fp += 2 }
                                AgentWidgetDrawList.SEG_Q -> { path.quadTo(floats[fp], floats[fp + 1], floats[fp + 2], floats[fp + 3]); fp += 4 }
                                AgentWidgetDrawList.SEG_C -> {
                                    path.cubicTo(floats[fp], floats[fp + 1], floats[fp + 2], floats[fp + 3], floats[fp + 4], floats[fp + 5])
                                    fp += 6
                                }
                                AgentWidgetDrawList.SEG_Z -> path.close()
                            }
                        }
                        val (nip, nfp) = paints(ints, floats, ip, fp, alpha)
                        ip = nip; fp = nfp
                        if (hasFill) canvas.drawPath(path, fill)
                        if (hasStroke) canvas.drawPath(path, stroke)
                    }
                    AgentWidgetDrawList.TEXT -> {
                        val x = floats[fp]; val y = floats[fp + 1]; val size = floats[fp + 2]
                        fp += 3
                        val weight = ints[ip]; val align = ints[ip + 1]; val color = ints[ip + 2]; val mono = ints[ip + 3] == 1
                        ip += 4
                        val string = list.strings[sp++]
                        text.textSize = size
                        text.typeface = typeface(weight, mono)
                        text.color = withAlpha(color, alpha)
                        text.textAlign = when (align) {
                            AgentWidgetDrawList.ALIGN_CENTER -> Paint.Align.CENTER
                            AgentWidgetDrawList.ALIGN_RIGHT -> Paint.Align.RIGHT
                            else -> Paint.Align.LEFT
                        }
                        canvas.drawText(string, x, y, text)
                    }
                    AgentWidgetDrawList.IMAGE -> {
                        val src = list.strings[sp++]
                        val x = floats[fp]; val y = floats[fp + 1]; val w = floats[fp + 2]; val h = floats[fp + 3]
                        fp += 4
                        val bitmap = images.bitmap(src, (w * density).toInt())
                        if (bitmap != null && w > 0f && h > 0f) {
                            bitmapPaint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
                            bounds.set(x, y, x + w, y + h)
                            canvas.drawBitmap(bitmap, null, bounds, bitmapPaint)
                        }
                    }
                }
            }
            canvas.restoreToCount(base)
        }
        replayMicros = (System.nanoTime() - started) / 1_000
    }

    private var hasFill = false
    private var hasStroke = false

    /** Reads a paint into [fill] and [stroke]; returns where the streams continue. */
    private fun paints(ints: IntArray, floats: FloatArray, ipStart: Int, fpStart: Int, alpha: Float): Pair<Int, Int> {
        var ip = ipStart
        var fp = fpStart
        val fillKind = ints[ip]; val fillValue = ints[ip + 1]; val strokeKind = ints[ip + 2]; val strokeColor = ints[ip + 3]; val cap = ints[ip + 4]
        ip += 5
        val width = floats[fp++]
        fill.shader = null
        hasFill = fillKind != AgentWidgetDrawList.FILL_NONE
        when (fillKind) {
            AgentWidgetDrawList.FILL_COLOR -> fill.color = withAlpha(fillValue, alpha)
            AgentWidgetDrawList.FILL_LINEAR, AgentWidgetDrawList.FILL_RADIAL -> {
                val stops = fillValue
                val geometry = if (fillKind == AgentWidgetDrawList.FILL_LINEAR) 4 else 3
                val g = fp
                fp += geometry
                val offsets = FloatArray(stops) { floats[fp + it] }
                fp += stops
                val colors = IntArray(stops) { ints[ip + it] }
                ip += stops
                fill.color = withAlpha(0xFFFFFFFF.toInt(), alpha)
                fill.shader = if (fillKind == AgentWidgetDrawList.FILL_LINEAR) {
                    LinearGradient(floats[g], floats[g + 1], floats[g + 2], floats[g + 3], colors, offsets, Shader.TileMode.CLAMP)
                } else {
                    RadialGradient(floats[g], floats[g + 1], maxOf(0.001f, floats[g + 2]), colors, offsets, Shader.TileMode.CLAMP)
                }
            }
        }
        hasStroke = strokeKind == 1
        if (hasStroke) {
            stroke.color = withAlpha(strokeColor, alpha)
            stroke.strokeWidth = width
            stroke.strokeCap = if (cap == 1) Paint.Cap.ROUND else Paint.Cap.BUTT
        }
        return ip to fp
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        if (alpha >= 1f) return color
        val a = ((color ushr 24) * alpha).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }
}

/**
 * A canvas's `image` commands' pictures by src: each is asked for once, at the width it's first
 * drawn, and drawn once it arrives (a failed one stays blank).
 */
private class CanvasImages(private val request: (src: String, widthPx: Int) -> Unit) {
    private val bitmaps = HashMap<String, Bitmap?>()

    fun bitmap(src: String, widthPx: Int): Bitmap? {
        if (src in bitmaps) return bitmaps[src]
        if (bitmaps.size >= LIMIT) return null
        bitmaps[src] = null
        request(src, widthPx.coerceIn(32, 1440))
        return null
    }

    fun arrived(src: String, bitmap: Bitmap?) {
        bitmaps[src] = bitmap
    }

    private companion object {
        const val LIMIT = 16
    }
}

/** Any part on screen counts as shown: a canvas or widget taller than the list would otherwise never be. */
internal const val VISIBLE_FRACTION = 0.01f
