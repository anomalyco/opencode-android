package ai.opencode.feature.drawer.list

import ai.opencode.core.motion.AmbientClock
import ai.opencode.core.ui.openCodeHairline
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.drawer.ServerSwitchNote
import ai.opencode.feature.drawer.motion.ServerSwapMotion
import ai.opencode.feature.drawer.style.DrawerMetrics
import ai.opencode.feature.drawer.style.DrawerTunables
import ai.opencode.feature.session.ServerFlowTunables
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Port of iOS `DrawerListServerFlowCells.swift` (Server Flow, d8a311bd): the ghost rows that hold
// the list while the server picked answers, and the note left after a switch went back.

/** The ghost bars' lengths, as fractions of the row, in turn (iOS `DrawerListGhostCell.lengths`). */
internal val drawerGhostLengths = listOf(0.72f, 0.54f, 0.8f, 0.46f, 0.64f)

/**
 * iOS `DrawerListGhostCell`: a row's place while the server picked answers, a 12dp bar of a title's
 * length with a slow sweep (1.6 s, every bar in step; held under Reduce Motion). Hidden from TalkBack.
 */
@Composable
fun DrawerListGhostCell(index: Int, reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    val base = colors.tertiaryText.copy(alpha = colors.tertiaryText.alpha * 0.35f)
    val shine = colors.tertiaryText.copy(alpha = colors.tertiaryText.alpha * 0.2f)
    val fraction = drawerGhostLengths[index % drawerGhostLengths.size]
    // One ambient sweep per pass (iOS 03adf07a: `AmbientClock`, linear, in step with every sweep).
    // A hidden drawer's ghosts hold still: no frame ticks for a drawer nobody sees.
    val hidden = LocalDrawerHidden.current.value
    val sweeps = !reducedMotion && !hidden
    val sweep = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(sweeps) {
        if (!sweeps) return@LaunchedEffect
        while (true) {
            withFrameNanos { sweep.floatValue = AmbientClock.sweep(AmbientClock.mediaTime()).toFloat() }
        }
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(DrawerMetrics.rowHeight + DrawerTunables.rowGap)
            .clearAndSetSemantics {},
    ) {
        val leading = (DrawerMetrics.rowEdge + DrawerMetrics.textInset).toPx()
        val available = (size.width - leading - (DrawerMetrics.trailingInset + DrawerMetrics.rowEdge).toPx()).coerceAtLeast(0f)
        val barHeight = 12.dp.toPx()
        val top = (size.height - DrawerTunables.rowGap.toPx() - barHeight) / 2
        val width = available * fraction
        val radius = CornerRadius(6.dp.toPx())
        drawRoundRect(base, topLeft = Offset(leading, top), size = Size(width, barHeight), cornerRadius = radius)
        if (sweeps && width > 0f) {
            val phase = sweep.floatValue
            // The sweep runs from the bar's right to its left, three bars wide with the shine in its middle.
            val sweepStart = leading + width - phase * 2 * width - width
            val brush = Brush.horizontalGradient(
                0.3f to Color.Transparent, 0.5f to shine, 0.7f to Color.Transparent,
                startX = sweepStart, endX = sweepStart + width * 3,
            )
            drawRoundRect(brush, topLeft = Offset(leading, top), size = Size(width, barHeight), cornerRadius = radius)
        }
    }
}

/**
 * iOS `DrawerServerNoteRow`: the note left after a switch went back on its own, with the way to try
 * again (or pair again, for a sign-in it refused).
 */
@Composable
fun DrawerServerNoteRow(note: ServerSwitchNote, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    val tint = when (note.reason) {
        is ServerSwitchNote.Reason.TimedOut -> colors.secondaryText
        ServerSwitchNote.Reason.Unreachable -> colors.destructive
        ServerSwitchNote.Reason.SignInFailed -> Color(0xFFFF9F0A)
    }
    Column(
        modifier
            .padding(horizontal = DrawerMetrics.rowEdge)
            .padding(top = 4.dp, bottom = 8.dp)
            .fillMaxWidth()
            // The desktop card: floating fill, hairline ring, small radius.
            .background(OpenCodeSurfaces.color, RoundedCornerShape(OpenCodeRadius.card))
            .openCodeHairline(RoundedCornerShape(OpenCodeRadius.card))
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("drawer-server-note"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OpenCodeIcon(note.systemImage, contentDescription = null, size = 20.dp, tint = tint)
            Text(
                note.title,
                modifier = Modifier.padding(start = 8.dp),
                style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold),
                color = colors.text,
            )
        }
        Text(
            note.detail,
            modifier = Modifier.padding(start = 28.dp),
            style = OpenCodeTextStyles.footnote,
            color = colors.secondaryText,
        )
        OpenCodePrimaryButton(
            text = note.action,
            onClick = onAction,
            modifier = Modifier
                .padding(start = 28.dp, top = 10.dp)
                .testTag("drawer-server-note-action"),
        )
    }
}

/**
 * Title Leads: a row of the server shown hangs off the title's name, the [index]th on screen
 * following the one above it ([ServerSwapMotion.row]). Off without a swap target. Under Reduce
 * Motion the rows only fade (iOS `swapTravel`).
 */
internal fun Modifier.serverSwapRow(motion: ServerSwapMotion?, serverID: String, reducedMotion: Boolean, index: () -> Int): Modifier {
    if (motion == null) return this
    return graphicsLayer {
        motion.frame // read so each frame redraws
        if (!motion.isMoving && motion.leaving.isEmpty()) return@graphicsLayer
        val pose = motion.row(serverID, index().coerceAtLeast(0))
        translationY = if (reducedMotion) 0f else pose.position * ServerFlowTunables.listTravel.dp.toPx()
        alpha = pose.opacity
    }
}

/**
 * iOS `DrawerListController.leavingRows`: the pictures of the rows a server switch leaves, hanging
 * off their name as it goes. A `LazyColumn` drops the old server's items at once, so each row
 * records what it draws into its own layer ([serverSwapPicture]), and a switch takes the visible
 * rows' layers, drawn over the list ([drawLeaving]) until the name has gone. A taken layer belongs
 * to the leaving server: its row records into a new one before it draws again.
 */
internal class ServerSwapPictures(private val graphics: GraphicsContext) {
    private class Picture(val layer: GraphicsLayer, val top: Float)

    /** The row drawing each key now. */
    private val rows = HashMap<Any, ServerSwapPictureNode>()
    private val leaving = HashMap<String, List<Picture>>()

    /** Bumped when pictures start leaving, so the overlay draws them on the switch's own frame. */
    private var version by mutableIntStateOf(0)

    internal fun createLayer(): GraphicsLayer = graphics.createGraphicsLayer()

    internal fun releaseLayer(layer: GraphicsLayer) = graphics.releaseGraphicsLayer(layer)

    internal fun attach(row: ServerSwapPictureNode) {
        rows[row.key] = row
    }

    internal fun detach(row: ServerSwapPictureNode) {
        if (rows[row.key] === row) rows.remove(row.key)
    }

    /**
     * iOS `swapRows(from:_:)`: before the next server's rows compose, the rows on screen (as last
     * laid out) become [server]'s leaving pictures. Rows coming back before they left turn round
     * as live rows.
     */
    fun swap(server: String, next: String, info: LazyListLayoutInfo, firstVisible: Int, motion: ServerSwapMotion) {
        for (id in listOf(server, next)) leaving.remove(id)?.forEach { releaseLayer(it.layer) }
        val pictures = info.visibleItemsInfo
            .filter { it.index >= firstVisible }
            .sortedBy { it.offset }
            .mapNotNull { item ->
                rows[item.key]?.handOver()?.let { Picture(it, (item.offset - info.viewportStartOffset).toFloat()) }
            }
        if (pictures.isEmpty()) return
        leaving[server] = pictures
        // Their chain starts where they are (iOS adds the snapshots at rest, then the exit moves
        // them), not where the name has got to by the first step.
        motion.rows(server, pictures.size)
        version += 1
    }

    /** iOS `placeSwapRows()` for the leaving pictures: each on its server's chain, faded with it. */
    fun DrawScope.drawLeaving(motion: ServerSwapMotion, travel: Float) {
        version
        if (leaving.isEmpty()) return
        val still = motion.leaving.toSet()
        val gone = leaving.keys.filter { it !in still }
        for (id in gone) leaving.remove(id)?.forEach { releaseLayer(it.layer) }
        for ((id, pictures) in leaving) {
            val poses = motion.rows(id, pictures.size)
            pictures.forEachIndexed { index, picture ->
                val pose = poses[index]
                if (pose.opacity <= 0f) return@forEachIndexed
                picture.layer.alpha = pose.opacity
                translate(top = picture.top + pose.position * travel) { drawLayer(picture.layer) }
            }
        }
    }

    /** The list left the screen (its rows release their own layers as they go). */
    fun release() {
        leaving.values.flatten().forEach { releaseLayer(it.layer) }
        leaving.clear()
        rows.clear()
    }
}

/** A row records what it draws for [ServerSwapPictures], so a server switch can fly it out. */
internal fun Modifier.serverSwapPicture(pictures: ServerSwapPictures?, key: Any): Modifier =
    if (pictures == null) this else this then ServerSwapPictureElement(pictures, key)

private data class ServerSwapPictureElement(val pictures: ServerSwapPictures, val key: Any) : ModifierNodeElement<ServerSwapPictureNode>() {
    override fun create() = ServerSwapPictureNode(pictures, key)
    override fun update(node: ServerSwapPictureNode) = node.update(pictures, key)
}

internal class ServerSwapPictureNode(var pictures: ServerSwapPictures, var key: Any) : Modifier.Node(), DrawModifierNode {
    private var layer: GraphicsLayer? = null

    /** What this row last drew, for the leaving server; the row records itself anew before it draws again. */
    fun handOver(): GraphicsLayer? {
        val taken = layer ?: return null
        layer = null
        invalidateDraw()
        return taken
    }

    fun update(pictures: ServerSwapPictures, key: Any) {
        if (pictures === this.pictures && key == this.key) return
        if (isAttached) this.pictures.detach(this)
        if (pictures !== this.pictures) dropLayer()
        this.pictures = pictures
        this.key = key
        if (isAttached) {
            pictures.attach(this)
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        val layer = layer ?: pictures.createLayer().also { layer = it }
        layer.record { this@draw.drawContent() }
        drawLayer(layer)
    }

    override fun onAttach() {
        pictures.attach(this)
        // Back from the reuse pool: what the list kept of it may draw a picture a switch took since.
        invalidateDraw()
    }

    override fun onDetach() {
        pictures.detach(this)
        dropLayer()
    }

    private fun dropLayer() {
        layer?.let(pictures::releaseLayer)
        layer = null
    }
}
