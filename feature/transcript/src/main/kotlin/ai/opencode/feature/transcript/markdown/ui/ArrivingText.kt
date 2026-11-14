package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.motion.TransitionBlur

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.feature.transcript.selection.LocalTranscriptReplySelection
import ai.opencode.feature.transcript.selection.TranscriptSelectionLeafKey
import ai.opencode.feature.transcript.selection.transcriptSelectionAnchor
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntSize
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.media.LinkMenuAction
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.feature.transcript.TranscriptAction
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy as LayerCompositingStrategy
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import ai.opencode.feature.media.LinkMenu
import ai.opencode.feature.media.LinkMenuHandler
import ai.opencode.feature.media.LinkMenuText
import ai.opencode.feature.media.LocalLinkMenu
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What a row does with a link: a file link opens the viewer, a session link opens its session,
 * a web link opens (iOS `onOpenFile` / the transcript's link handling), and the hold menu's
 * actions go to the session (iOS `LinkMenuHandler.perform`).
 */
internal object MarkdownLinkActions {
    fun tap(url: String, send: (TranscriptAction) -> Unit) {
        val file = MarkdownLinks.fileReference(url)
        send(if (file != null) TranscriptAction.OpenFile(file) else TranscriptAction.LinkTapped(url))
    }

    fun perform(action: LinkMenuAction, url: String, send: (TranscriptAction) -> Unit) {
        send(if (action == LinkMenuAction.OpenSession) TranscriptAction.LinkTapped(url) else TranscriptAction.LinkMenu(action, url))
    }
}

/** A link listener for `LinkAnnotation`s (TalkBack's activation; touches go through [ArrivingText]'s own gesture). */
@Composable
internal fun rememberLinkListener(): LinkInteractionListener {
    val send by rememberUpdatedState(LocalTranscriptRowContext.current.send)
    return remember {
        LinkInteractionListener { link -> (link as? LinkAnnotation.Clickable)?.tag?.let { MarkdownLinkActions.tap(it, send) } }
    }
}

/**
 * Text whose newest characters fade in (iOS `ArrivingText`). The text is laid out once as it
 * is; while chunks arrive, each chunk's own characters are erased by `1 - opacity` over the
 * drawn text (opacity only, read in the draw phase from a frame clock), so no glyph moves and
 * none takes another chunk's clock. Once every fade has run it is plain text again.
 *
 * Also draws the text's session chips under their glyphs (iOS `SessionChipRenderer`), opens
 * links on tap and their hold menu on long press (iOS `linkMenu(for:)`), and gives TalkBack the
 * menu's actions.
 */
@Composable
internal fun ArrivingText(
    presented: PresentedText,
    arrivals: List<TranscriptTextReveal.Arrival>,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    softWrap: Boolean = true,
    selectionKey: TranscriptSelectionLeafKey? = null,
) {
    val context = LocalTranscriptRowContext.current
    // In a reply the list owns one selection across all its text (iOS `selectableTranscriptText`).
    val replySelection = if (selectionKey != null) LocalTranscriptReplySelection.current else null
    val send by rememberUpdatedState(context.send)
    val reveal = LocalTranscriptTextReveal.current ?: remember { TranscriptTextReveal() }
    val colors = OpenCodeTheme.colors
    val density = LocalDensity.current
    val now = reveal.now()
    // Typeset math sits in the text as images; it appears without the per-character fade.
    val active = if (presented.hasMath) emptyList() else arrivals.filter { now - it.time < it.duration }
    val ends = active.maxOfOrNull { it.time + it.duration }
    val frame = rememberRevealFrame(ends, reveal.now)
    // iOS reads the style each body pass: a change shows on the next chunk.
    val revealStyle = StreamTextRevealStyle.current
    val reduceMotion = rememberReduceMotion()
    val sweep = if (revealStyle == StreamTextRevealStyle.Chunk) {
        null
    } else {
        SweepParameters(
            focus = revealStyle == StreamTextRevealStyle.Focus,
            feather = TextRevealTunables.feather.value,
            // Points → px; Reduce Motion drops the blur (iOS `StreamSweepOverlay`).
            blurRadius = if (reduceMotion || !TransitionBlur.enabled) 0f else with(density) { TextRevealTunables.blurRadius.value.toFloat().dp.toPx() },
            blurPoints = if (reduceMotion || !TransitionBlur.enabled) 0.0 else TextRevealTunables.blurRadius.value,
            lift = TextRevealTunables.phosphorLift.value,
        )
    }
    // Focus's blur steps share a few layers, one per step (iOS: `ctx.drawLayer` per step).
    val blurLayers = if (sweep?.focus == true && sweep.blurPoints > 0) List(SweepParameters.STEPS) { rememberGraphicsLayer() } else null
    // Offscreen layer for DstOut/Plus chunk fades (drawn inside `drawWithContent` instead of
    // `Modifier.graphicsLayer`, whose `BlockGraphicsLayerModifier` invalidated Text semantics on
    // every chunk).
    val offscreenLayer = if (ends != null) rememberGraphicsLayer() else null
    val quoteAction = LocalQuoteAccessibilityAction.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var pressed by remember { mutableStateOf<String?>(null) }
    // Web, pairing and session links hold to media's link menu (iOS `linkMenu(for:)`: the
    // shared `LinkMenuView`, with the link's preview card, its actions, and dragging the link out
    // from the hold); file links stay this text's own (tap opens the viewer).
    val linkMenu = remember { LinkMenuHandler { action, url -> MarkdownLinkActions.perform(action, url, send) } }
    val menuText = remember(presented.text) { linkMenuText(presented.text) }
    // A paragraph with menu links has one TalkBack node for the links' actions and Quote, the link
    // menu's (AL-01: an action on the Text too made TalkBack stop there, short of the links').
    val hasMenuLinks = remember(menuText) { LinkMenuText.webLinks(menuText).isNotEmpty() }
    val quoteOnLinkMenu = quoteAction != null && hasMenuLinks
    val textQuoteAction = quoteAction.takeUnless { quoteOnLinkMenu }
    val chipGlyphs = if (presented.chips.isEmpty()) null else ChipGlyphs(
        session = painterResource(SFSymbol.BubbleLeftFill.drawable),
        subagent = painterResource(SFSymbol.ArrowTurnDownRight.drawable),
        muted = painterResource(SFSymbol.Number.drawable),
    )
    val emPx = with(density) { style.fontSize.toPx() }
    val chipStyle = ChipStyle(
        ink = colors.text,
        glyph = colors.secondaryText,
        fillScale = (if (colors.isDark) 1.6f else 1f) * (if (colors.highContrast) 1.8f else 1f),
    )
    val links = presented.menuLinks
    val viewConfiguration = LocalViewConfiguration.current
    val touchTargets = remember(viewConfiguration) { exactTouchTargets(viewConfiguration) }

    CompositionLocalProvider(LocalLinkMenu provides linkMenu) {
    LinkMenu(
        menuText,
        textLayout = { layout },
        onOpen = { MarkdownLinkActions.tap(it, send) },
        modifier = modifier,
        trailingActions = if (quoteOnLinkMenu) listOfNotNull(quoteAction) else emptyList(),
    ) {
        // Compose widens each link's clickable node to a 48 dp touch target, past the link's glyphs
        // and this text's box. Links take touches only on their glyphs, so the link nodes get no
        // minimum target; the gesture below keeps touches that miss the glyphs from them.
        CompositionLocalProvider(LocalViewConfiguration provides touchTargets) {
            Text(
                text = presented.text,
                style = style,
                color = color,
                softWrap = softWrap,
                inlineContent = presented.inlineContent,
                onTextLayout = { layout = it },
                modifier = Modifier
                    .then(
                        if (replySelection != null && selectionKey != null) {
                            Modifier.transcriptSelectionAnchor(selectionKey, replySelection) { layout }
                        } else {
                            Modifier
                        },
                    )
                    .drawWithContent {
                        val result = layout
                        val drawNow = if (result != null && active.isNotEmpty()) {
                            frame.longValue
                            reveal.now()
                        } else {
                            0.0
                        }
                        val fading = result != null && offscreenLayer != null && active.isNotEmpty() &&
                            ends != null && drawNow < ends && size.width > 0f && size.height > 0f
                        if (fading) {
                            offscreenLayer.compositingStrategy = LayerCompositingStrategy.Offscreen
                            offscreenLayer.record {
                                if (chipGlyphs != null) {
                                    for (chip in presented.chips) drawChipBackground(result, chip, emPx, chipStyle, chip.url == pressed)
                                }
                                this@drawWithContent.drawContent()
                                if (chipGlyphs != null) {
                                    for (chip in presented.chips) drawChipGlyph(result, chip, emPx, chipStyle, chipGlyphs)
                                }
                                if (sweep == null) {
                                    eraseArriving(result, active, drawNow)
                                } else {
                                    sweepArriving(result, active, drawNow, sweep, blurLayers)
                                }
                            }
                            drawLayer(offscreenLayer)
                        } else {
                            if (result != null && chipGlyphs != null) {
                                for (chip in presented.chips) drawChipBackground(result, chip, emPx, chipStyle, chip.url == pressed)
                            }
                            drawContent()
                            if (result != null && chipGlyphs != null) {
                                for (chip in presented.chips) drawChipGlyph(result, chip, emPx, chipStyle, chipGlyphs)
                            }
                        }
                    }
                    .then(
                        if (presented.menuLinks.isEmpty() && presented.text.getLinkAnnotations(0, presented.text.length).isEmpty()) {
                            Modifier
                        } else {
                            Modifier.pointerInput(presented) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    val laidOut = layout ?: return@awaitEachGesture
                                    val url = linkAt(laidOut, presented, down.position)
                                    if (url == null) {
                                        // Off a link's glyphs the touch goes to the text: keep it from
                                        // the node Compose lays over each link's whole box, which for a
                                        // wrapped link spans both lines' full width.
                                        if (!down.isConsumed && inLinkBox(laidOut, presented, down.position)) down.consume()
                                        return@awaitEachGesture
                                    }
                                    // The link menu above owns the links it offers.
                                    if (MarkdownLinks.offersMenu(url)) return@awaitEachGesture
                                    down.consume()
                                    pressed = url
                                    val slop = viewConfiguration.touchSlop
                                    // Holding decides a little before selection's own long press, so the
                                    // menu wins and the consumed events cancel selection's.
                                    val holdAfter = (viewConfiguration.longPressTimeoutMillis * 0.85).toLong()
                                    // 0 cancelled, 1 tap, 2 hold
                                    val decided = withTimeoutOrNull(holdAfter) {
                                        var result = -1
                                        while (result < 0) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                            result = when {
                                                change == null -> 0
                                                !change.pressed -> { change.consume(); 1 }
                                                (change.position - down.position).getDistance() > slop -> 0
                                                else -> -1
                                            }
                                        }
                                        result
                                    }
                                    if (decided == 1) MarkdownLinkActions.tap(url, send)
                                    pressed = null
                                }
                            }
                        },
                    )
                    .semantics {
                        // iOS `.accessibilityHint("Links to …")`: read after the text.
                        presented.linkHint?.let { stateDescription = it }
                        // With menu links, Quote joins the links' actions on the link menu's box.
                        if (textQuoteAction != null) customActions = listOf(textQuoteAction)
                    },
            )
        }
    }
    }
}

/** [base] without a minimum touch target: a node takes touches only inside its own bounds. */
private fun exactTouchTargets(base: ViewConfiguration): ViewConfiguration = object : ViewConfiguration by base {
    override val minimumTouchTargetSize: DpSize get() = DpSize.Zero
}

/** [text] with each link as a `LinkAnnotation.Url` over the same ranges, as media's [LinkMenu] reads links. */
private fun linkMenuText(text: AnnotatedString): AnnotatedString {
    val links = text.getLinkAnnotations(0, text.length).mapNotNull { range ->
        (range.item as? LinkAnnotation.Clickable)?.tag?.takeIf(MarkdownLinks::offersMenu)?.let { Triple(it, range.start, range.end) }
    }
    if (links.isEmpty()) return AnnotatedString(text.text)
    return buildAnnotatedString {
        append(text.text)
        for ((url, start, end) in links) addLink(LinkAnnotation.Url(url), start, end)
    }
}

/** The link under [position], if a character's box holds it (taps beside a line's end miss). */
private fun linkAt(layout: TextLayoutResult, presented: PresentedText, position: Offset): String? {
    if (presented.text.isEmpty()) return null
    val offset = layout.getOffsetForPosition(position)
    for (candidate in listOf(offset, offset - 1)) {
        if (candidate !in 0 until presented.text.length) continue
        val box = layout.getBoundingBox(candidate).inflate(4f)
        if (!box.contains(position)) continue
        val link = presented.text.getLinkAnnotations(candidate, candidate + 1).firstOrNull()?.item as? LinkAnnotation.Clickable
        if (link != null) return link.tag
    }
    return null
}

/** Whether [position] is inside the box of the lines a link covers, where Compose places the link's own node. */
private fun inLinkBox(layout: TextLayoutResult, presented: PresentedText, position: Offset): Boolean {
    val length = layout.layoutInput.text.length
    return presented.text.getLinkAnnotations(0, presented.text.length).any { range ->
        val end = minOf(range.end, length)
        range.start < end && layout.getPathForRange(range.start, end).getBounds().contains(position)
    }
}

/** Erases each arriving chunk's characters by `1 - opacity` (DstOut over the offscreen layer). */
private fun DrawScope.eraseArriving(layout: TextLayoutResult, active: List<TranscriptTextReveal.Arrival>, now: Double) {
    val length = layout.layoutInput.text.length
    val sorted = active.sortedBy { it.offset }
    for ((index, arrival) in sorted.withIndex()) {
        val start = arrival.offset.coerceIn(0, length)
        val end = (if (index + 1 < sorted.size) sorted[index + 1].offset else length).coerceIn(start, length)
        if (end <= start) continue
        val hidden = 1f - TranscriptTextReveal.opacity(arrival, now)
        if (hidden <= 0.004f) continue
        val path: Path = layout.getPathForRange(start, end)
        drawPath(path, Color.Black, alpha = hidden, blendMode = BlendMode.DstOut)
    }
}

/** The Frontier and Focus sweeps' knobs, read once per composition (iOS `StreamSweepOverlay`). */
private class SweepParameters(
    val focus: Boolean,
    val feather: Double,
    /** Px. */
    val blurRadius: Float,
    val blurPoints: Double,
    val lift: Double,
) {
    companion object {
        /** Focus blurs glyphs in this many steps, one layer each. */
        const val STEPS = 6
    }
}

/**
 * Frontier and Focus (iOS `StreamSweepRenderer`): each arriving chunk's glyphs reveal in reading
 * order, a feathered frontier crossing the chunk ([StreamTextRevealStyle.sweepAlpha]). A glyph
 * still sharp is erased by `1 - alpha` in place; under Focus one still arriving is erased whole
 * and drawn again into the layer of its blur step (blurred by how far it still has to go and
 * brightened by the phosphor lift), so glyphs share a few layers instead of one each. Untouched
 * text never moves; by the end every glyph is at full opacity, so handing the chunk back to the
 * plain text never changes a pixel.
 */
private fun DrawScope.sweepArriving(
    layout: TextLayoutResult,
    active: List<TranscriptTextReveal.Arrival>,
    now: Double,
    sweep: SweepParameters,
    blurLayers: List<GraphicsLayer>?,
) {
    val length = layout.layoutInput.text.length
    val sorted = active.sortedBy { it.offset }
    val steps = SweepParameters.STEPS
    val blurred = if (sweep.focus && sweep.blurPoints > 0 && blurLayers != null) Array(steps + 1) { mutableListOf<Pair<Rect, Float>>() } else null
    for ((index, arrival) in sorted.withIndex()) {
        val start = arrival.offset.coerceIn(0, length)
        val end = (if (index + 1 < sorted.size) sorted[index + 1].offset else length).coerceIn(start, length)
        if (end <= start) continue
        val u = ((now - arrival.time) / maxOf(0.01, arrival.duration)).coerceIn(0.0, 1.0)
        if (u >= 1) continue
        val count = end - start
        for (offset in start until end) {
            val position = (offset - start + 0.5) / count
            val alpha = StreamTextRevealStyle.sweepAlpha(u, position, sweep.feather)
            if (alpha >= 0.996) continue
            val box = layout.getBoundingBox(offset)
            if (box.width <= 0f || box.height <= 0f) continue
            val step = if (blurred != null) ((1 - alpha) * steps).roundToInt() else 0
            if (step == 0 || alpha <= 0.004) {
                drawRect(Color.Black, box.topLeft, box.size, alpha = (1 - alpha).toFloat(), blendMode = BlendMode.DstOut)
            } else {
                drawRect(Color.Black, box.topLeft, box.size, blendMode = BlendMode.DstOut)
                blurred!![step] += box to alpha.toFloat()
            }
        }
    }
    if (blurred == null || blurLayers == null) return
    for (step in 1..steps) {
        val glyphs = blurred[step]
        if (glyphs.isEmpty()) continue
        val layer = blurLayers[step - 1]
        val remaining = step.toDouble() / steps
        val radius = (remaining * sweep.blurRadius).toFloat()
        layer.renderEffect = if (remaining * sweep.blurPoints >= 0.25) BlurEffect(radius, radius, TileMode.Decal) else null
        val boost = (1 + sweep.lift * remaining).toFloat()
        layer.colorFilter = if (boost > 1.001f) {
            ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(boost, boost, boost, 1f) })
        } else {
            null
        }
        layer.record(IntSize(size.width.roundToInt(), size.height.roundToInt())) {
            for ((box, alpha) in glyphs) {
                clipRect(box.left, box.top, box.right, box.bottom) { drawText(layout, alpha = alpha) }
            }
        }
        drawLayer(layer)
    }
}

private class ChipGlyphs(val session: Painter, val subagent: Painter, val muted: Painter)

private class ChipStyle(val ink: Color, val glyph: Color, val fillScale: Float)

/** The chip's box (iOS `SessionChipMetrics`), on the live [SessionLinkTunables]. */
private object SessionChipMetrics {
    /** The chip's height and how far its centre sits above the baseline, in ems. */
    val height: Float get() = SessionLinkTunables.height.value
    val rise: Float get() = SessionLinkTunables.rise.value
    val fill: Float get() = SessionLinkTunables.fill.value.toFloat()
    /** The chip's corner radius at body size (17 pt); it scales with the text. */
    val radius: Float get() = SessionLinkTunables.radius.value
}

/** Each line's run of the chip, as boxes around the text (iOS `SessionChipMetrics.box`). */
private fun chipBoxes(layout: TextLayoutResult, chip: SessionChip, em: Float): List<Rect> {
    val length = layout.layoutInput.text.length
    val start = chip.start.coerceIn(0, length)
    val end = chip.end.coerceIn(start, length)
    if (end <= start) return emptyList()
    val boxes = mutableListOf<Rect>()
    var line = -1
    var minX = 0f
    var maxX = 0f
    fun flush() {
        if (line < 0) return
        val centre = layout.getLineBaseline(line) - em * SessionChipMetrics.rise
        val h = em * SessionChipMetrics.height
        boxes += Rect(minX, centre - h / 2, maxX, centre + h / 2)
    }
    for (offset in start until end) {
        val box = layout.getBoundingBox(offset)
        val l = layout.getLineForOffset(offset)
        if (l != line) {
            flush()
            line = l
            minX = box.left
            maxX = box.right
        } else {
            minX = minOf(minX, box.left)
            maxX = maxOf(maxX, box.right)
        }
    }
    flush()
    return boxes
}

private fun DrawScope.drawChipBackground(layout: TextLayoutResult, chip: SessionChip, em: Float, style: ChipStyle, pressed: Boolean) {
    val alpha = (SessionChipMetrics.fill * style.fillScale * (if (chip.muted) 0.6f else 1f) * (if (pressed) 2f else 1f)).coerceAtMost(1f)
    for (box in chipBoxes(layout, chip, em)) {
        val radius = minOf(SessionChipMetrics.radius * em / 17f, box.height / 2)
        drawRoundRect(style.ink.copy(alpha = style.ink.alpha * alpha), box.topLeft, box.size, CornerRadius(radius, radius))
    }
}

/** The glyph over the chip's leading end: as far from the leading edge as from the top and bottom. */
private fun DrawScope.drawChipGlyph(layout: TextLayoutResult, chip: SessionChip, em: Float, style: ChipStyle, glyphs: ChipGlyphs) {
    val box = chipBoxes(layout, chip, em).firstOrNull() ?: return
    val painter = when {
        chip.muted -> glyphs.muted
        chip.isSubagent -> glyphs.subagent
        else -> glyphs.session
    }
    val side = em * 0.6f
    val centre = Offset(box.left + box.height / 2, box.center.y)
    val tint = style.glyph.copy(alpha = style.glyph.alpha * (if (chip.muted) 0.7f else 1f))
    translate(centre.x - side / 2, centre.y - side / 2) {
        with(painter) { draw(Size(side, side), colorFilter = ColorFilter.tint(tint)) }
    }
}
