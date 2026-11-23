package ai.opencode.feature.transcript.widgets

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.excludedFromDrawerPan
import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.Phase
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A `wgsl` canvas: a [AgentWidgetShaderCanvas] presenting into a `TextureView`, the runtime compiling and drawing on
 * its own thread. Frames are recorded from `withFrameNanos` at `fps` while the canvas has a size, is on screen and the
 * list isn't paused, or once (and on `values` changes) with `fps: 0`. A WGSL error (the widget template's, reported in
 * phase `gpu`) or no GPU or a GPU hang (the client's) shows its `poster` or `alt` with the error; a shader over 50 ms a
 * frame three frames in a row stops on its last frame.
 */
@Composable
fun AgentWidgetGPUCanvas(node: AgentWidgetNode.Canvas, scope: AgentWidgetScope, modifier: Modifier = Modifier) {
    val wgsl = checkNotNull(node.wgsl)
    val nodeValues = checkNotNull(node.values)
    val engine = remember { dependency(AgentWidgetEngine) }
    val density = LocalDensity.current.density
    val paused = LocalAgentWidgetPaused.current
    var failure by remember(wgsl) { mutableStateOf<String?>(null) }
    var programFailure by remember(wgsl) { mutableStateOf(false) }
    val dev = remember { AgentWidgetCapabilities.isDevMode }
    var canvas by remember(wgsl) { mutableStateOf<AgentWidgetShaderCanvas?>(null) }
    var onLastFrame by remember(wgsl) { mutableStateOf(false) }
    val frame = rememberGpuCanvasFrame(node, scope)
    val size = canvasSize(node, modifier)

    val error = failure
    if (error != null) {
        // A client's reason is for developers.
        ShaderFallback(node, error.takeIf { programFailure || dev }, size)
        return
    }

    val values by rememberUpdatedState(nodeValues)
    val start = remember { longArrayOf(0L) }
    val valuesKey = if (node.fps == 0) nodeValues.contentHashCode() else 0
    LaunchedEffect(canvas, paused, frame.visible, node.fps, valuesKey, frame.pixels) {
        val shader = canvas ?: return@LaunchedEffect
        if (onLastFrame || paused || !frame.visible || frame.pixels.width == 0 || frame.pixels.height == 0) return@LaunchedEffect
        val interval = if (node.fps > 0) 1_000_000_000L / node.fps else 0L
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (start[0] == 0L) start[0] = scope.controller?.startNanos("canvas:${node.id}", now) ?: now
            if (last != 0L && now - last < interval - 2_000_000L) continue
            last = now
            val stopped = shader.render((now - start[0]) / 1e9f, density, frame.pointer, values)
            if (stopped != null) {
                programFailure = stopped.program
                if (stopped.keepsLastFrame) onLastFrame = true else failure = stopped.message
                if (stopped.program) scope.controller?.reportError("gpu", stopped.message) else scope.controller?.clientError("gpu", stopped.message)
                break
            }
            if (node.fps == 0) break
        }
    }

    Box(size.then(frame.modifier)) {
        key(wgsl) {
            val held = remember { arrayOfNulls<AutoCloseable>(2) }
            GpuTexture(
                onAvailable = { surface, width, height ->
                    try {
                        val gpu = engine.gpuSurface(surface).also { held[0] = it }
                        canvas = engine.shaderCanvas(wgsl, gpu, width, height).also { held[1] = it }
                    } catch (e: AgentWidgetEngineException) {
                        failure = e.message ?: e.javaClass.simpleName
                        scope.controller?.clientError("gpu", failure!!)
                    }
                },
                onResized = { width, height -> canvas?.resize(width, height) },
                onGone = {
                    // The canvas goes before its surface: once closed it never touches the surface again.
                    canvas = null
                    held[1]?.close()
                    held[0]?.close()
                    held.fill(null)
                },
            )
        }
    }
}

/**
 * A live run's `gpu: true` canvas (widgets-gpu.md): the run replays the program's batches into the surface attached
 * under the canvas's key and says how the canvas is ([AgentWidgetGpuState]); anything but live shows the placeholder
 * (`poster`, else `alt`, else "GPU canvas not supported", with the error that stopped it). Input works as on a 2D
 * canvas; layers above it in a stack keep working.
 */
@Composable
internal fun AgentWidgetLiveGPUCanvas(node: AgentWidgetNode.Canvas, scope: AgentWidgetScope, modifier: Modifier = Modifier) {
    val controller = scope.controller
    val engine = remember { dependency(AgentWidgetEngine) }
    val canvasKey = node.id?.let(AgentWidgetNode::canvasKey)
    val running = controller != null && controller.isLiveMode && controller.phase !is Phase.Closed && canvasKey != null
    var noSurface by remember { mutableStateOf<String?>(null) }
    val dev = remember { AgentWidgetCapabilities.isDevMode }
    val state = if (running && canvasKey != null) controller.gpuState(canvasKey) else null
    val frame = rememberGpuCanvasFrame(node, scope)

    Box(canvasSize(node, modifier).then(frame.modifier)) {
        if (running && canvasKey != null && controller != null) {
            key(canvasKey) {
                val held = remember { arrayOfNulls<AgentWidgetGpuSurface>(1) }
                GpuTexture(
                    onAvailable = { surface, width, height ->
                        try {
                            val gpu = engine.gpuSurface(surface).also { held[0] = it }
                            controller.attachGpuSurface(canvasKey, gpu, width, height)
                        } catch (e: AgentWidgetEngineException) {
                            noSurface = e.message
                            controller.clientError("gpu", e.message ?: e.javaClass.simpleName)
                        }
                    },
                    onResized = { width, height -> held[0]?.let { controller.attachGpuSurface(canvasKey, it, width, height) } },
                    onGone = {
                        held[0]?.let { controller.detachGpuSurface(canvasKey, it) }
                        held[0]?.close()
                        held[0] = null
                    },
                )
            }
        }
        val shown = running && noSurface == null && (state == null || state == AgentWidgetGpuState.Live)
        if (!shown) {
            // A program error shows its message; the client's reasons are for developers.
            val reason = when (state) {
                is AgentWidgetGpuState.Stopped -> state.message.takeIf { state.program || dev }
                AgentWidgetGpuState.Unavailable -> "GPU unavailable".takeIf { dev }
                AgentWidgetGpuState.Lost -> "GPU device lost".takeIf { dev }
                else -> null
            } ?: noSurface?.takeIf { dev }
            GpuPlaceholder(node, reason, Modifier.matchParentSize())
        }
    }
}

/** What a GPU canvas's box tracks: its size, where it is, whether it's seen, and the pointer for frames. */
private class GpuCanvasFrame {
    var visible by mutableStateOf(false)
    var pixels by mutableStateOf(IntSize.Zero)
    /** `x, y, down` in physical pixels. */
    val pointer = FloatArray(3)
    var bounds: Rect? = null
    var modifier: Modifier = Modifier
}

@Composable
private fun rememberGpuCanvasFrame(node: AgentWidgetNode.Canvas, scope: AgentWidgetScope): GpuCanvasFrame {
    val density = LocalDensity.current.density
    val frame = remember { GpuCanvasFrame() }
    // Live mode: the program's frames carry this canvas's size and pointer, as a 2D canvas's do.
    val controller = scope.controller
    val liveKey = node.id ?: ""
    val liveCanvas = remember(controller, liveKey) { AgentWidgetLiveCanvas() }
    if (controller?.isLiveMode == true) {
        DisposableEffect(controller, liveKey) {
            controller.liveCanvases[liveKey] = liveCanvas
            onDispose { if (controller.liveCanvases[liveKey] === liveCanvas) controller.liveCanvases.remove(liveKey) }
        }
    }
    val currentScope by rememberUpdatedState(scope)
    frame.modifier = Modifier
        .onVisibilityChanged(minFractionVisible = VISIBLE_FRACTION) { frame.visible = it }
        .onSizeChanged {
            frame.pixels = it
            liveCanvas.width = it.width / density
            liveCanvas.height = it.height / density
        }
        .onGloballyPositioned {
            frame.bounds = it.boundsInWindow()
            liveCanvas.bounds = frame.bounds
        }
        .excludedFromDrawerPan(node.pointer && scope.live)
        .agentWidgetPointer(node.id.takeIf { node.pointer }, density, { currentScope.send(it) }, { scope.controller?.closeOver(frame.bounds) }) { liveCanvas.pointer = it }
        .then(if (node.alt != null) Modifier.semantics { contentDescription = node.alt } else Modifier)
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    frame.pointer[0] = change.position.x
                    frame.pointer[1] = change.position.y
                    frame.pointer[2] = if (change.pressed) 1f else 0f
                }
            }
        }
    return frame
}

@Composable
private fun canvasSize(node: AgentWidgetNode.Canvas, modifier: Modifier): Modifier {
    val width = node.width
    val grows = LocalAgentWidgetFullscreen.current && LocalAgentWidgetGrows.current && node.fill
    return modifier
        .then(if (width != null) Modifier.width(width.dp) else Modifier.fillMaxWidth())
        .then(if (grows) Modifier.heightIn(min = node.height.dp).fillMaxHeight() else Modifier.height(node.height.dp))
        .clip(OpenCodeShape.control)
}

/**
 * A `TextureView` the runtime presents into: [onAvailable] with its `Surface` and size in physical pixels, [onResized]
 * on a new size, and [onGone] before the surface is released (the view leaving, or its texture destroyed). Main thread.
 */
@Composable
private fun GpuTexture(onAvailable: (Surface, Int, Int) -> Unit, onResized: (Int, Int) -> Unit, onGone: () -> Unit) {
    val available by rememberUpdatedState(onAvailable)
    val resized by rememberUpdatedState(onResized)
    val gone by rememberUpdatedState(onGone)
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                isOpaque = false
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var surface: Surface? = null

                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        val surface = Surface(texture).also { surface = it }
                        available(surface, width, height)
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = resized(width, height)

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        gone()
                        surface?.release()
                        surface = null
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun GpuPlaceholder(node: AgentWidgetNode.Canvas, error: String?, modifier: Modifier) {
    val colors = OpenCodeTheme.colors
    val poster = node.poster
    Column(
        modifier.background(colors.userBubble).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (poster != null) {
            AgentWidgetImage(poster, node.alt, null, Modifier.weight(1f, fill = false))
        } else {
            Text(node.alt ?: stringResource(R.string.transcript_widget_gpu_unsupported), style = OpenCodeTextStyles.footnote, color = colors.secondaryText, textAlign = TextAlign.Center)
        }
        if (error != null) Text(error, style = OpenCodeTextStyles.caption1, color = colors.tertiaryText, textAlign = TextAlign.Center, maxLines = 4)
    }
}

@Composable
private fun ShaderFallback(node: AgentWidgetNode.Canvas, error: String?, modifier: Modifier) {
    val colors = OpenCodeTheme.colors
    val poster = node.poster
    if (poster != null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AgentWidgetImage(poster, node.alt, node.width?.let { it / node.height })
            if (error != null) Text(error, style = OpenCodeTextStyles.caption1, color = colors.tertiaryText, maxLines = 3)
        }
        return
    }
    Column(
        modifier.background(colors.userBubble).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(node.alt ?: stringResource(R.string.transcript_widget_shader_failed), style = OpenCodeTextStyles.footnote, color = colors.secondaryText, textAlign = TextAlign.Center)
        if (error != null) Text(error, style = OpenCodeTextStyles.caption1, color = colors.tertiaryText, textAlign = TextAlign.Center, maxLines = 4)
    }
}
