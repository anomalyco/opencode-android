package ai.opencode.feature.composer.plusmenu

import ai.opencode.core.motion.transitionBlurEffect

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.feature.composer.AttachmentPlusPeek
import ai.opencode.feature.composer.PlusMenuTunables
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Holding + with the New Plus Menu on: a strip of the newest photos stands over the composer;
 * sliding along it picks one and letting go adds it (iOS `RecentPhotoPeek`). Only shown when the
 * library is already readable, so a hold never raises the access dialog. Hold for
 * [PlusMenuTunables.HOLD_DURATION] before calling [show]. Geometry is in screen pixels; set
 * [density].
 */
class RecentPhotoPeek(private val haptics: HapticsClient = dependency(HapticsClient)) : AttachmentPlusPeek {
    override var isShown: Boolean by mutableStateOf(false)
        private set

    /** The photo under the finger. */
    var highlighted: String? by mutableStateOf(null)
        private set

    /** The photos on the strip and where each rests on screen, fixed as it opens. */
    var tiles: List<Tile> by mutableStateOf(emptyList())
        private set

    /** Pixels per dp. */
    var density: Float = 1f

    data class Tile(val id: String, val frame: Rect)

    fun show(ids: List<String>, above: Rect) {
        tiles = layout(ids, above, density)
        if (tiles.isEmpty()) return
        highlighted = null
        isShown = true
        haptics.play(Haptic.lightTap)
    }

    /** Sliding reads only the finger's x, once it has left +. */
    override fun moved(to: Offset, button: Rect) {
        if (!isShown) return
        val left = !button.inflate(BUTTON_SLOP * density).contains(to)
        val id = if (left) tile(to.x, tiles, density) else null
        if (id == highlighted) return
        highlighted = id
        if (id != null) haptics.play(Haptic.selection)
    }

    /** The photo to add, if the finger let go on one. */
    override fun ended(): String? {
        val chosen = highlighted
        if (chosen != null) haptics.play(Haptic.lightTap)
        hide()
        return chosen
    }

    override fun hide() {
        highlighted = null
        isShown = false
    }

    companion object {
        /** dp. */
        const val SPACING = 8f

        /** Between the composer's top and the strip's bottom (dp). */
        const val GAP = 14f

        /** Slop around + before the finger counts as having left it (dp). */
        private const val BUTTON_SLOP = 8f

        /** Where tiles rest above [composer], as many as fit its width (five, or seven past 600 dp). */
        fun layout(ids: List<String>, composer: Rect, density: Float = 1f): List<Tile> {
            val columns = if (composer.width / density > 600) 7 else 5
            val n = min(ids.size, columns)
            if (n <= 0 || composer.width <= 0) return emptyList()
            val spacing = SPACING * density
            val side = kotlin.math.floor((composer.width - spacing * (columns - 1)) / columns)
            val y = composer.top - GAP * density - side
            return ids.take(n).mapIndexed { i, id ->
                Tile(id, Rect(Offset(composer.left + i * (side + spacing), y), Size(side, side)))
            }
        }

        fun tile(x: Float, tiles: List<Tile>, density: Float = 1f): String? {
            val first = tiles.firstOrNull() ?: return null
            val last = tiles.last()
            // Past either end, the end tile: a thumb overshoots.
            if (x < first.frame.left) return first.id
            if (x > last.frame.right) return last.id
            return tiles.firstOrNull { x <= it.frame.right + SPACING * density / 2 }?.id
        }
    }
}

/**
 * The bottom band's scrim: Android never blurs behind an overlay, so it dims with a Material scrim
 * where iOS blurs.
 */
private const val PEEK_SCRIM_DIM = 0.32f
private const val PEEK_RADIUS = 14f
private const val PEEK_LIFT = 1.14f
private const val PEEK_RISE = 6f

/**
 * The strip (iOS `RecentPhotoPeekOverlay` + `RecentPhotoPeekBackdrop`). Mount it full-screen over
 * the session; it takes no touches and is hidden from TalkBack. Tiles arrive out of + one after
 * another, out of a blur, and leave the same way; Reduce Motion fades them in place.
 */
@Composable
fun RecentPhotoPeekOverlay(
    peek: RecentPhotoPeek,
    library: PlusPhotoLibrary,
    flights: PhotoFlights,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current.density
    peek.density = density
    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(Size.Zero) }
    val progress by animateFloatAsState(
        targetValue = if (peek.isShown) 1f else 0f,
        animationSpec = Motion.panelOpen.animation(if (peek.isShown) MotionPhase.Enter else MotionPhase.Exit, reduceMotion),
        label = "peek-backdrop",
    )
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned {
                origin = it.positionOnScreen()
                size = Size(it.size.width.toFloat(), it.size.height.toFloat())
            }
            .clearAndSetSemantics {},
    ) {
        if (peek.isShown || progress > 0.001f) {
            val stripTop = (peek.tiles.firstOrNull()?.frame?.top ?: (size.height + origin.y - 160 * density)) - origin.y
            val top = max(stripTop - PlusMenuTunables.PEEK_FEATHER * density, 0f)
            val dim = max(PlusMenuTunables.PEEK_DIM, PEEK_SCRIM_DIM)
            if (dim > 0f) Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = dim * progress)), startY = top, endY = this.size.height),
                    topLeft = Offset(0f, top),
                    size = Size(this.size.width, this.size.height - top),
                )
            }
        }
        val firstLeft = peek.tiles.firstOrNull()?.frame?.left ?: 0f
        peek.tiles.forEachIndexed { index, tile ->
            val shown by animateFloatAsState(
                targetValue = if (peek.isShown) 1f else 0f,
                // One after another out of +, all at once back into it.
                animationSpec = if (peek.isShown) {
                    Motion.microIn.staggered(index, reduceMotion = reduceMotion)
                } else {
                    Motion.microOut.animation(reduceMotion = reduceMotion)
                },
                label = "peek-tile",
            )
            if (shown <= 0.001f && !peek.isShown) return@forEachIndexed
            // From just above +, the first tile's corner.
            val fromX = -tile.frame.left + firstLeft - index * 6 * density
            val fromY = tile.frame.height * 0.35f
            val p = shown.coerceIn(0f, 1.2f)
            val away = if (reduceMotion) 0f else 1 - p
            RecentPhotoPeekTile(
                id = tile.id,
                cacheKey = library.cacheKey(tile.id, tile.frame.width / density, density),
                duration = library.photo(tile.id)?.formattedDuration,
                sidePx = tile.frame.width,
                scale = density,
                isHighlighted = peek.highlighted == tile.id,
                inFlight = flights.hidesPhoto(tile.id),
                flights = flights,
                cached = library.cachedThumbnail(tile.id, tile.frame.width / density, density)
                    ?: library.cachedThumbnail(tile.id, PlusPhotoLibrary.GRID_THUMBNAIL_SIDE, density),
                load = { id, side, scale, onDegraded -> library.thumbnail(id, side, scale, onDegraded) },
                modifier = Modifier
                    .offset { IntOffset((tile.frame.left - origin.x).roundToInt(), (tile.frame.top - origin.y).roundToInt()) }
                    .graphicsLayer {
                        val s = if (reduceMotion) 1f else 0.4f + 0.6f * p
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(0f, 1f)
                        translationX = fromX * away
                        translationY = fromY * away
                        val clamped = shown.coerceIn(0f, 1f)
                        alpha = clamped
                        // Out of a blur (iOS `PeekArrival`: (1 − shown) × 10).
                        val blur = if (reduceMotion || clamped > 0.98f || clamped < 0.02f) 0f else (1 - clamped) * 10f * density
                        renderEffect = transitionBlurEffect(blur)
                    },
            )
        }
    }
}

@Composable
private fun RecentPhotoPeekTile(
    id: String,
    cacheKey: PlusPhotoLibrary.ThumbnailCacheKey,
    duration: String?,
    sidePx: Float,
    scale: Float,
    isHighlighted: Boolean,
    inFlight: Boolean,
    flights: PhotoFlights,
    cached: ImageBitmap?,
    load: suspend (String, Float, Float, (ImageBitmap) -> Unit) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    var image by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    val resolved = image ?: cached
    val place = remember { PhotoTilePlace() }
    val sideDp = sidePx / scale

    /** Highlighted, where it's drawn (lifted and scaled from its foot), with its ring: where a flight leaves from. */
    fun report() {
        val frame = place.frame
        if (!isHighlighted || frame == Rect.Zero) {
            flights.source(id, null)
            return
        }
        val lifted = if (reduceMotion) 1f else PEEK_LIFT
        val width = frame.width * lifted
        val height = frame.height * lifted
        val rise = if (reduceMotion) 0f else PEEK_RISE * scale
        val drawn = Rect(Offset(frame.center.x - width / 2, frame.bottom - height - rise), Size(width, height))
        flights.source(id, PhotoFlights.Source(frame = drawn, radius = PEEK_RADIUS * scale * lifted, ring = 0.9f, image = resolved))
    }

    LaunchedEffect(cacheKey) {
        if (cached != null) image = cached
        val loaded = load(id, sideDp, scale) { preview -> if (image == null) image = preview }
        if (loaded != null) image = loaded
    }
    LaunchedEffect(isHighlighted, resolved) { report() }

    val lift by animateFloatAsState(
        targetValue = if (isHighlighted && !reduceMotion) 1f else 0f,
        animationSpec = Motion.microIn.animation(if (isHighlighted) MotionPhase.Enter else MotionPhase.Exit, reduceMotion),
        label = "peek-lift",
    )
    val shape = RoundedCornerShape(PEEK_RADIUS.dp)
    Box(
        modifier
            .onGloballyPositioned {
                place.frame = it.boundsOnScreen()
                if (isHighlighted) report()
            }
            .graphicsLayer {
                val s = 1f + (PEEK_LIFT - 1f) * lift
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0.5f, 1f)
                translationY = -PEEK_RISE * scale * lift
                // Its photo in the air: gone from the strip at once.
                alpha = if (inFlight) 0f else 1f
            }
            .size(sideDp.dp)
            .shadow(if (isHighlighted) 12.dp else 6.dp, shape, clip = false)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .drawWithContent {
                resolved?.let { drawAspectFill(it, Rect(Offset.Zero, this.size)) }
                drawContent()
            }
            .border(2.dp, Color.White.copy(alpha = if (isHighlighted) 0.9f else 0f), shape),
    ) {
        if (duration != null) {
            VideoDurationBadge(duration, compact = true, modifier = Modifier.align(Alignment.BottomStart).padding(5.dp))
        }
    }
}
