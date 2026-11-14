package ai.opencode.feature.composer

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.motion.transitionBlur
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.AccessibilityFocusRequest
import ai.opencode.core.ui.accessibilityFocusRequest
import ai.opencode.feature.composer.plusmenu.PlusMenuPlacement
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where the attachment menu is (iOS `AttachmentMenuPhase`). */
enum class AttachmentMenuPhase {
    Closed,
    Menu,
    /** The lab's photo-card fixture; the Plus Menu opens the photo sheet from [Menu] and never stands here. */
    Photos,
}

/** The menu's curves (iOS `AttachmentMenuMotion`). */
object AttachmentMenuMotion {
    /** The surface grows up out of + while the + turns into a ×. */
    fun <T> open(reduceMotion: Boolean): FiniteAnimationSpec<T> = Motion.panelOpen.animation(reduceMotion = reduceMotion)

    fun <T> close(reduceMotion: Boolean): FiniteAnimationSpec<T> = Motion.panelClose.animation(reduceMotion = reduceMotion)
}

/** iOS `AttachmentMenuMetrics` (points → dp). */
object AttachmentMenuMetrics {
    /** The composer's, so the menu reads as the same surface (the desktop dock's 12). */
    val cornerRadius: Dp = 12.dp
    /** Between the panel's edge and its rows; the highlight is concentric (desktop context menu: 4). */
    val padding: Dp = 4.dp
    /** Between the composer's top edge and the menu's bottom edge. */
    val composerGap: Dp = 10.dp
    val minimumWidth: Dp = 210.dp
    /** The surface the menu grows out of and shrinks back into, above the +. */
    val seedSize: Dp = 34.dp
    /** The menu's foot stays this far above the screen's bottom edge. */
    val overComposerFloor: Dp = 12.dp
}

/**
 * The New Plus Menu's values (iOS `PlusMenuTunables`, baked to their defaults). Seconds and points.
 */
object PlusMenuTunables {
    const val MENU_BLUR = 3.5f
    const val MENU_SCALE = 0.96f
    /** A row's reveal spans this much of the open (× open). */
    const val ROW_SPAN = 0.65f
    const val HOLD_DURATION = 0.18
    /** iOS's peek backdrop blur (pt). Not drawn on Android, which dims the band with a scrim. */
    const val PEEK_BLUR = 10f
    const val PEEK_DIM = 0f
    const val PEEK_FEATHER = 56f
    const val VIDEO_BADGE_INSET = 6f
    const val VIDEO_BADGE_RADIUS = 6f
    const val VIDEO_BADGE_OPACITY = 0.58
    /** Rim inset around the photo grid inside the photo sheet. */
    const val GRID_INSET = 0f
    const val SELECTION_DIM = 0.18
    /** The unpicked photos' wash once the limit is picked. */
    const val LIMIT_DIM = 0.5
    const val SELECTION_BADGE_SIZE = 24f
    const val SELECTION_BADGE_INSET = 6f
    /** A picked photo draws this far inside its tile, rounded (dp): the system photo picker's selection. */
    const val SELECTED_TILE_INSET = 10f
    const val SELECTED_TILE_RADIUS = 12f
    /** How far the menu's foot sits below the composer's (iOS overlaps the keys in its own window). */
    const val MENU_DROP = 80f
    /** The menu's width, × the screen's. */
    const val MENU_WIDTH = 0.63f
    /** The open menu's corners: the desktop popover radius (`OpenCodeRadius.card`), as on the session’s ⋮ menu. */
    const val MENU_RADIUS = 10f
    const val MENU_ROW_HEIGHT = 56f
    /** The menu's leading edge in from the composer's. */
    const val MENU_INSET = 0f

    // iOS `PlusMenuGlassSurface.swift`. The collapsing glass's size where it leaves, as a fraction
    // of the + frame (1 leaves a +-sized disc, which reads as a pop).
    const val COLLAPSE_END = 0.25f
    /** The scale the + pops back from as the glass arrives (1 is a fade only). */
    const val PLUS_POP = 0.8
    /** How far into the fold (× `Motion.panelClose`) the + starts popping back: the glass reaches it about two thirds of the way. */
    const val PLUS_POP_LEAD = 0.65
}

// region Rows

/**
 * The menu's rows (iOS `AttachmentMenuRows`). A hidden measuring copy reports where each row
 * rests ([onRowFrame], root coordinates), so choosing by a slide from + never depends on the
 * surface mid-flight. [onPaste] asks the session to read the clipboard.
 *
 * @param buttonCenterX where the + glyph's center sits from the panel's leading edge (dp); the
 *   rows' icons line up under it.
 */
@Composable
fun AttachmentMenuRows(
    items: List<AttachmentMenuItem>,
    buttonCenterX: Dp,
    modifier: Modifier = Modifier,
    highlighted: AttachmentMenuItem? = null,
    staggeredEntrance: Boolean = false,
    entranceProgress: Float = 1f,
    entranceBlur: Float = 0f,
    entranceScale: Float = 1f,
    /** New Plus Menu: taller rows with the icon on a circle. */
    largeMenu: Boolean = false,
    /** New Plus Menu: the menu's fixed width; otherwise the rows size to their content. */
    menuWidth: Dp? = null,
    onRowFrame: ((AttachmentMenuItem, Rect) -> Unit)? = null,
    /** Moves TalkBack's focus onto the top row once ([accessibilityFocusRequest]). */
    focusRequest: AccessibilityFocusRequest? = null,
    onChoose: (AttachmentMenuItem) -> Unit = {},
    onPaste: () -> Unit = {},
) {
    val fontScale = min(LocalDensity.current.fontScale, 2f)
    val rowHeight = 48.dp * fontScale
    val iconWidth = 26.dp * fontScale
    val iconInset = max((buttonCenterX - AttachmentMenuMetrics.padding - iconWidth / 2).value, 10f).dp
    val count = items.size
    Column(
        modifier
            .then(if (menuWidth != null) Modifier.width(max(menuWidth.value, AttachmentMenuMetrics.minimumWidth.value).dp) else Modifier.width(IntrinsicSize.Max))
            .widthIn(min = AttachmentMenuMetrics.minimumWidth)
            .padding(AttachmentMenuMetrics.padding),
    ) {
        items.forEachIndexed { index, item ->
            val label = @Composable { lit: Boolean ->
                AttachmentMenuRowLabel(
                    item,
                    iconWidth = if (largeMenu) iconWidth * 1.3f else iconWidth,
                    inset = iconInset,
                    height = if (largeMenu) rowHeight * (PlusMenuTunables.MENU_ROW_HEIGHT / 48f) else rowHeight,
                    isLit = lit,
                    circularIcon = largeMenu,
                )
            }
            when {
                onRowFrame != null -> Box(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { onRowFrame(item, Rect(it.positionInRoot(), it.size.toSize())) },
                ) { label(false) }
                else -> {
                    val reveal = if (staggeredEntrance) {
                        Modifier.menuRowEntranceReveal(entranceProgress, max(count - 1 - index, 0), count, entranceBlur, entranceScale)
                    } else Modifier
                    // Android divergence: a ripple answers a tap (iOS lights the row while pressed);
                    // the press-and-slide highlight stays.
                    Box(
                        reveal
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(AttachmentMenuRowHighlightRadius))
                            .clickable(role = Role.Button) {
                                if (item == AttachmentMenuItem.Paste) onPaste() else onChoose(item)
                            }
                            .semantics { testTag = "attachment-menu-${item.name.lowercase()}" }
                            .accessibilityFocusRequest(if (index == 0) focusRequest else null),
                    ) { label(highlighted == item) }
                }
            }
        }
    }
}

private val AttachmentMenuRowHighlightRadius: Dp = AttachmentMenuMetrics.cornerRadius - AttachmentMenuMetrics.padding

/** The system menus' highlight: a quiet fill concentric with the panel, for a press or a finger sliding over the row from +. */
@Composable
private fun AttachmentMenuRowLabel(item: AttachmentMenuItem, iconWidth: Dp, inset: Dp, height: Dp, isLit: Boolean, circularIcon: Boolean = false) {
    val reduceMotion = rememberReduceMotion()
    // The shared press: lit at once under the finger, let go on its exit.
    val lit by animateFloatAsState(
        if (isLit) 1f else 0f,
        Motion.press.animation(if (isLit) MotionPhase.Enter else MotionPhase.Exit, reduceMotion),
        label = "menu-row-highlight",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = lit), RoundedCornerShape(AttachmentMenuRowHighlightRadius))
            .padding(start = inset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .width(iconWidth)
                .then(
                    if (circularIcon) {
                        // Desktop context menus draw bare glyphs, no discs.
                        Modifier.height(iconWidth)
                    } else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            OpenCodeIcon(item.systemImage, contentDescription = null, size = 20.dp, tint = OpenCodeTheme.colors.secondaryText)
        }
        Text(item.title, style = OpenCodeTextStyles.callout.copy(fontWeight = FontWeight.Medium), color = OpenCodeTheme.colors.text, maxLines = 1)
        Spacer(Modifier.width(24.dp))
    }
}

/**
 * Staggers the + menu's rows bottom-up out of the + seed (and back into it on close), each row
 * unblurring, scaling from its bottom leading corner, and lifting into its slot as one layer
 * (iOS `MenuRowEntranceReveal`).
 */
fun Modifier.menuRowEntranceReveal(progress: Float, bottomIndex: Int, totalCount: Int, blur: Float, scale: Float): Modifier {
    val clamped = progress.coerceIn(0f, 1f)
    // `Motion.staggerStep` apart (the group within `staggerCap`), as shares of the one open
    // spring that carries every row.
    val open = max(Motion.panelOpen.curve().duration, 0.01)
    val stagger = if (totalCount > 1) (Motion.staggerStep / open).toFloat() else 0f
    val delay = (MotionRole.staggerDelay(bottomIndex) / open).toFloat()
    val span = max(PlusMenuTunables.ROW_SPAN - max(totalCount - 1, 0) * stagger, 0.25f)
    val eased = attachmentMenuSmoothstep(delay, delay + span, clamped)
    val currentScale = scale + (1 - scale) * eased
    val lift = (1 - eased) * (5 + bottomIndex * 3)
    val radius = if (blur > 0 && eased > 0.01f && eased < 0.99f) (1 - eased) * blur else 0f
    return this
        .graphicsLayer {
            scaleX = currentScale
            scaleY = currentScale
            transformOrigin = TransformOrigin(0f, 1f)
            translationY = if (blur > 0) lift.dp.toPx() else 0f
            alpha = eased
        }
        .then(if (radius > 0f) Modifier.transitionBlur(radius.dp) else Modifier)
}

// endregion

// region Menu

/**
 * Measures where the + menu stands: the seed the surface grows from, the menu's frame and where
 * each row rests (iOS `AttachmentMenuMeasure`). Draws nothing; put it in a full-screen box over the
 * session.
 */
@Composable
fun AttachmentMenuMeasure(
    anchors: AttachmentMenuAnchors,
    items: List<AttachmentMenuItem>,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // The docked keyboard's top in the measure's space (iOS `DockedKeyboard.frameInScreen`).
    val imeBottom = WindowInsets.ime.getBottom(density)
    val navigationBarBottom = WindowInsets.navigationBars.getBottom(density)
    /** Each row's frame within the rows, from the measuring copy. */
    val rowFrames = remember { mutableStateMapOf<AttachmentMenuItem, Rect>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var measureSize by remember { mutableStateOf(Size.Zero) }
    var rootHeight by remember { mutableFloatStateOf(0f) }

    var keyboardTop: Float? = null
    if (imeBottom > 0 && measureSize.height > 0f) {
        val top = rootHeight - imeBottom - origin.y
        if (top < measureSize.height) keyboardTop = top
    }
    // Keyboard down: the part of the navigation bar the measure reaches down over.
    val navigationBarInset = if (imeBottom == 0 && measureSize.height > 0f) {
        max(0f, navigationBarBottom - (rootHeight - origin.y - measureSize.height))
    } else 0f
    val frames = with(density) {
        attachmentMenuFrames(
            anchors = anchors,
            origin = origin,
            measureSize = measureSize,
            px = { it.toPx() },
            itemCount = items.size,
            fontScale = min(fontScale, 2f),
            keyboardTop = keyboardTop,
            navigationBarInset = navigationBarInset,
        )
    }

    // Publish what the + and the photo sheet lay out against.
    LaunchedEffect(frames.menu, rowFrames.toMap(), items, origin) {
        val menuOrigin = frames.menu.topLeft + origin
        val resting = HashMap<AttachmentMenuItem, Rect>()
        for ((item, frame) in rowFrames) {
            if (item in items) resting[item] = frame.translate(menuOrigin)
        }
        if (resting != anchors.rows) anchors.rows = resting
        anchors.seed = frames.seed.translate(origin)
        anchors.menu = frames.menu.translate(origin)
        val centerX = frames.button.center.x - frames.menu.left
        if (centerX > 0 && abs(anchors.buttonCenterXInMenu - centerX) > 0.5f) anchors.buttonCenterXInMenu = centerX
    }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned {
                origin = it.positionInRoot()
                measureSize = it.size.toSize()
                rootHeight = it.findRootCoordinates().size.height.toFloat()
            },
    ) {
        // Measures the rows where nothing can see or reach them, at the menu's resting frame.
        AttachmentMenuRows(
            items = items,
            buttonCenterX = with(density) { (frames.button.center.x - frames.menu.left).toDp() },
            largeMenu = true,
            menuWidth = with(density) { frames.menu.width.toDp() },
            modifier = Modifier
                .offset { IntOffset(frames.menu.left.roundToInt(), frames.menu.top.roundToInt()) }
                .alpha(0f)
                .clearAndSetSemantics {},
            onRowFrame = { item, frame ->
                val menuOrigin = frames.menu.topLeft + origin
                rowFrames[item] = frame.translate(-menuOrigin)
            },
        )
    }
}

/** The frames the measure lays out, in its own coordinates (px). */
@Immutable
internal data class AttachmentMenuFrames(
    val button: Rect,
    val seed: Rect,
    val menu: Rect,
)

internal fun attachmentMenuFrames(
    anchors: AttachmentMenuAnchors,
    origin: Offset,
    measureSize: Size,
    px: (Dp) -> Float,
    itemCount: Int = 0,
    fontScale: Float = 1f,
    /** The docked keyboard's top in the measure's space, if it covers part of it. */
    keyboardTop: Float? = null,
    /** Keyboard down, the navigation bar's height over the overlay's bottom (Android only). */
    navigationBarInset: Float = 0f,
): AttachmentMenuFrames {
    val button = anchors.button.translate(-origin)
    val composer = anchors.composer.translate(-origin)
    val seedSize = px(AttachmentMenuMetrics.seedSize)
    // Row membership can change while the measuring copy is hidden (Location): its old measured
    // height must never reserve a blank row on the next open, so the height comes from the rows.
    val rowHeight = px(48.dp) * fontScale * PlusMenuTunables.MENU_ROW_HEIGHT / 48f
    val rows = Size(
        measureSize.width * PlusMenuTunables.MENU_WIDTH,
        rowHeight * itemCount + px(AttachmentMenuMetrics.padding) * 2,
    )
    // Its foot below the composer's, short of the screen's bottom edge.
    val foot = PlusMenuPlacement.foot(
        composer = composer,
        screenBottom = measureSize.height,
        keyboardTop = keyboardTop,
        drop = px(PlusMenuTunables.MENU_DROP.dp),
        floor = px(AttachmentMenuMetrics.overComposerFloor),
        navigationBarInset = navigationBarInset,
    )
    val seed = Rect(Offset(button.center.x - seedSize / 2, button.center.y - seedSize / 2), Size(seedSize, seedSize))
    val menu = Rect(
        Offset(composer.left + px(PlusMenuTunables.MENU_INSET.dp), foot - rows.height),
        Size(min(rows.width, composer.width), rows.height),
    )
    return AttachmentMenuFrames(button, seed, menu)
}

/** An eased 0–1 ramp from [from] to [to] (iOS `attachmentMenuSmoothstep`). */
internal fun attachmentMenuSmoothstep(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3 - 2 * t)
}

/**
 * What the composer's dismiss area closes (iOS `ComposerDismissTarget`). It reaches over the
 * whole transcript, so it's mounted only for something on screen.
 */
enum class ComposerDismissTarget {
    AttachmentMenu,
    Autocomplete,
    ;

    companion object {
        fun current(attachmentMenuOpen: Boolean, autocompleteOpen: Boolean, autocompleteDrawn: Boolean): ComposerDismissTarget? {
            if (attachmentMenuOpen) return AttachmentMenu
            // A request panel or a call's rows hold the glass: its rows aren't there.
            if (autocompleteOpen && autocompleteDrawn) return Autocomplete
            return null
        }
    }
}

/** Dismisses the menu on a tap or a downward drag (iOS `AttachmentMenuDismissArea`). */
@Composable
fun AttachmentMenuDismissArea(onDismiss: () -> Unit, modifier: Modifier = Modifier, extent: Dp? = null) {
    Box(
        modifier
            .then(if (extent != null) Modifier.size(extent) else Modifier.fillMaxSize())
            .clearAndSetSemantics {}
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }
            .pointerInput(onDismiss) {
                val tracker = VelocityTracker()
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        total = 0f
                        tracker.resetTracking()
                    },
                    onDragEnd = {
                        val predicted = total + tracker.calculateVelocity().y * 0.5f
                        if (total.toDp().value > 30 || predicted.toDp().value > 120) onDismiss()
                    },
                ) { change, amount ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    total += amount
                }
            },
    ) {}
}

// endregion
