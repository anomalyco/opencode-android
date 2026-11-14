package ai.opencode.feature.composer

import ai.opencode.core.motion.transitionBlurEffect

import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.excludedFromDrawerPan
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Port of iOS `ComposerMorphSurface`: the morph on screen, with its backdrop, glass and content.
 * Always mounted (it draws nothing closed), so opening grows it from the source; under Reduce
 * Motion it cross-fades in place. Frames are root px, radii dp. [target] receives the surface's
 * frame and the keyboard's top (or null), root px.
 */
@Composable
fun ComposerMorphSurface(
    morph: ComposerMorph,
    /** Where it folds back into, root px: the source as it is now. */
    source: Rect,
    sourceRadiusDp: Double,
    target: (screen: Rect, keyboardTop: Float?) -> Rect,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Where it grows from: the source as it was when it opened (it may move beneath it meanwhile). */
    openedFrom: Rect? = null,
    /** Where it folds back into when that's known as it closes, rather than the source as it moves. */
    landsOn: Rect? = null,
    /** Its bottom corners at rest (dp), `composer.morph.bottomRadius` by default. */
    bottomRadiusDp: Double? = null,
    /** A field in the content has the keyboard: while open, the sheet rises above it. */
    contentHasKeyboard: Boolean = false,
    /**
     * The keyboard stays as it folds (the photo sheet's, which never took it): its rest stays on
     * the keyboard.
     */
    keyboardStaysAsItFolds: Boolean = false,
    /** The photo sheet stretches its top toward the expanded detent, its bottom anchored. */
    stretchesUpward: Boolean = false,
    /** A dismissal drag from compact carries the whole sheet rather than compressing it. */
    translatesDownward: Boolean = false,
    /** Backdrop dim override (iOS `backdropDim`); iOS's `backdropBlur` has no Android counterpart. */
    backdropDim: Double? = null,
    /** Controls along the sheet's foot, over its content but outside its mask; they arrive and leave with the content. */
    foot: (@Composable () -> Unit)? = null,
    /** What it grows out of, dissolving as the content arrives. */
    outgoing: (@Composable () -> Unit)? = null,
    outgoingReturns: Boolean = false,
    outgoingAlignedToBottom: Boolean = false,
    /** The + seed (root px, 34 × 34) a downward-dismissing photo sheet lands on when [landsOn] isn't given. */
    seed: Rect = Rect.Zero,
    seedRadiusDp: Double = AttachmentMenuMetrics.seedSize.value / 2.0,
    /** The photo sheet returns to a small seed or the menu, not a replacement surface: opaque throughout its fold. */
    continuousGlass: Boolean = false,
    /** Dismissing slides it down and fades it out while the composer rises back from behind it. */
    dismissesDown: Boolean = false,
    /**
     * Keep [content] composed while closed so opening doesn't rebuild it. Closed, it's off screen,
     * transparent and outside semantics, and reads [LocalComposerMorphContentActive] false.
     */
    retainsContent: Boolean = false,
    content: @Composable () -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val px = density.density
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    var screen by remember { mutableStateOf(Rect.Zero) }
    val imeBottom = WindowInsets.ime.getBottom(density)

    // The morph's phase is read here, not by whoever hosts it, so only the surface redraws.
    val shown = morph.isShown
    val progress = morph.progress.value
    val bottomProgress = morph.bottomProgress.value
    val sheetActive = morph.isOpening || morph.foldPending
    val usesDownwardDismiss = shown && dismissesDown && !sheetActive
    val keyboardUp = contentHasKeyboard && (morph.isOpen || (keyboardStaysAsItFolds && shown))
    // The keyboard carries the sheet up and down on its own curve.
    val keyboardBlend by animateFloatAsState(
        if (keyboardUp) 1f else 0f,
        if (reduceMotion) snap() else ComposerMorphTunables.keyboard(),
        label = "morph-keyboard",
    )
    // Until its fold starts it is still open, so it keeps growing from where it opened.
    val from = ComposerMorphGeometry.resolvedSource(shown, sheetActive, source, openedFrom, landsOn)
    val outgoingShown = sheetActive || outgoingReturns
    // Retained content moves between its open and closed places with its state (one movable slot).
    val currentContent by rememberUpdatedState(content)
    val movableContent = remember { movableContentOf { currentContent() } }
    var warm by remember { mutableStateOf(false) }
    if (retainsContent && !warm) {
        if (shown) {
            SideEffect { warm = true }
        } else {
            LaunchedEffect(Unit) {
                // Prewarm once the host's own first frames have gone, not during its entrance.
                delay(RETAINED_CONTENT_PREWARM_MILLIS)
                warm = true
            }
        }
    }
    val retained = retainsContent && warm

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { screen = it.boundsInRoot() },
    ) {
        if (!shown || screen == Rect.Zero) {
            if (retained && screen != Rect.Zero) RetainedClosedContent(screen, target, movableContent)
            return@Box
        }
        val keyboardTop = if (imeBottom > 0) screen.bottom - imeBottom else null
        val restFree = target(screen, null)
        val rest = if (keyboardBlend <= 0f || keyboardTop == null) restFree else lerpRect(restFree, target(screen, keyboardTop), keyboardBlend)
        val origin = screen.topLeft
        val targetInSpace = rest.translate(-origin)
        val topRadius = (ComposerMorphTunables.topRadius * px).toFloat()
        val geometry = ComposerMorphGeometry(
            source = if (usesDownwardDismiss) (landsOn ?: seed).translate(-origin) else from.translate(-origin),
            sourceRadius = ((if (usesDownwardDismiss) seedRadiusDp else sourceRadiusDp) * px).toFloat(),
            target = targetInSpace,
            topRadius = topRadius,
            bottomRadius = ((bottomRadiusDp ?: ComposerMorphTunables.bottomRadius) * px).toFloat(),
        )
        val minHeight = 120 * px
        val dimBase = backdropDim ?: if (dark) ComposerMorphTunables.dimDark else ComposerMorphTunables.dimLight
        // No backdrop blur on Android, so the scrim takes iOS's no-blur strength.
        val dim = (dimBase * ComposerMorphTunables.noBlurDimBoost).toFloat()

        // A zero-dim backdrop may draw nothing: the modal target still covers the whole session
        // and swallows the dismissing tap.
        Box(
            Modifier
                .fillMaxSize()
                // Before the clickable, or its semantics would stay as a full-screen "Unlabelled"
                // button.
                .clearAndSetSemantics {}
                .then(
                    if (morph.isOpen) {
                        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                    } else {
                        Modifier
                    },
                )
                .excludedFromDrawerPan(morph.isOpen),
        )
        // The backdrop: everything above the sheet dims, growing with the morph; it takes no touches.
        val strength = progress.coerceIn(0f, 1f)
        if (strength > 0.001f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = dim * strength))
                    .clearAndSetSemantics {},
            )
        }

        // MorphBody: the glass and its content, placed from one progress.
        val placed = geometry
        // Under Reduce Motion it stands where it rests and only fades.
        val travel = if (!shown) 0f else if (reduceMotion) 1f else progress
        val bottomTravel = if (!shown) 0f else if (reduceMotion) 1f else bottomProgress
        val frame = placed.frame(travel, bottomTravel, 0f)
        // A finger's drag moves the glass and its content together (or stretches the photo sheet's top).
        val pull = if (shown && !reduceMotion) morph.drag * px else 0f
        val (stretchedRect, downward) = ComposerMorphGeometry.stretched(frame.rect, pull, stretchesUpward, minHeight, translatesDownward)
        val (stretchedTarget, _) = ComposerMorphGeometry.stretched(placed.target, pull, stretchesUpward, minHeight, translatesDownward)
        val armed = morph.phase == ComposerMorph.Phase.Armed
        val fade = ComposerMorphGeometry.glassFade(
            progress = progress,
            shown = shown,
            armed = armed,
            continuousGlass = continuousGlass,
            dismissesDown = usesDownwardDismiss,
            reduceMotion = reduceMotion,
        )
        val glassShape = MorphShape(frame.top, frame.bottom)
        val contentY = min(stretchedTarget.top, stretchedRect.top)

        // Folding into the +, the glass goes once the fold is mostly done, leaving no disc on the + as its spring settles.
        if (!usesDownwardDismiss || progress > ComposerMorphTunables.photoGlassOut) {
            PlacedBox(stretchedRect.translate(0f, downward), Modifier.graphicsLayer { alpha = fade }.background(MaterialTheme.colorScheme.surfaceContainerHigh, glassShape))
        }


        if (shown) {
            val contentFade: Float? = if (usesDownwardDismiss) fade else if (reduceMotion) progress.coerceIn(0f, 1f) else null
            val revealBlur = if (usesDownwardDismiss) 0.0 else if (morph.isOpening) ComposerMorphTunables.contentBlur else ComposerMorphTunables.exitBlur
            val lead = if (outgoing != null && outgoingShown) ComposerMorphTunables.photoContentLead.toFloat() else 1f
            val maskOffset = Offset(stretchedRect.left - stretchedTarget.left, stretchedRect.top - contentY)
            PlacedBox(
                // Folding into the +, the content shrinks with the glass rather than being cut by it.
                if (usesDownwardDismiss) Rect(stretchedRect.topLeft + Offset(0f, downward), stretchedTarget.size)
                else Rect(Offset(stretchedTarget.left, contentY + downward), stretchedTarget.size),
                Modifier
                    // The glass's shape, where it is now, in the content's own frame.
                    .graphicsLayer {
                        clip = true
                        shape = if (usesDownwardDismiss) MorphShape(frame.top, frame.bottom, Offset.Zero, stretchedRect.size)
                        else MorphShape(frame.top, frame.bottom, maskOffset, stretchedRect.size)
                    }
                    .then(
                        if (!usesDownwardDismiss) {
                            Modifier
                        } else {
                            Modifier.graphicsLayer {
                                scaleX = stretchedRect.width / max(stretchedTarget.width, 1f)
                                scaleY = stretchedRect.height / max(stretchedTarget.height, 1f)
                                transformOrigin = TransformOrigin(0f, 0f)
                            }
                        },
                    )
                    .morphReveal({ morph.content.value }, contentFade, (revealBlur * px).toFloat(), lead)
                    .semantics { if (!morph.isOpening) hideFromAccessibility() },
            ) {
                CompositionLocalProvider(LocalComposerMorphContentActive provides true) {
                    if (retainsContent) movableContent() else content()
                }
                // Taps reach the content only once it has mostly grown (iOS `allowsHitTesting(progress > 0.5)`).
                if (progress <= 0.5f) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clearAndSetSemantics {}
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                    )
                }
            }
        }

        if (!shown && retained) RetainedClosedContent(screen, target, movableContent)

        if (shown && outgoing != null) {
            val src = placed.source
            val clamped = travel.coerceIn(0f, 1f)
            // It rides the glass's top-leading corner (or bottom edge folding into the composer), growing a little with it.
            val growth = if (outgoingAlignedToBottom) 1f else (1 + ComposerMorphTunables.outgoingGrowth * clamped).toFloat()
            // As the real composer fades back in over the last stretch of the fold, the stand-in fades out with it.
            val outgoingFade = if (outgoingAlignedToBottom && !morph.isOpening && !reduceMotion) ((progress - 0.04f) / 0.24f).coerceIn(0f, 1f) else fade
            val innerSize = if (outgoingAlignedToBottom) stretchedRect.size else src.size
            PlacedBox(
                stretchedRect.translate(0f, downward),
                Modifier
                    .graphicsLayer {
                        clip = true
                        shape = glassShape
                    }
                    .clearAndSetSemantics {},
            ) {
                PlacedBox(
                    Rect(Offset.Zero, innerSize),
                    Modifier
                        .graphicsLayer {
                            scaleX = growth
                            scaleY = growth
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                        .morphDissolve(
                            reveal = { morph.content.value },
                            shown = outgoingShown && !reduceMotion,
                            fade = outgoingFade,
                            scales = outgoingAlignedToBottom,
                            blurPx = (ComposerMorphTunables.outgoingBlur * px).toFloat(),
                        ),
                    contentAlignment = if (outgoingAlignedToBottom) Alignment.BottomCenter else Alignment.TopStart,
                ) { outgoing() }
            }
        }

        if (foot != null && shown) {
            // MorphFootPlacement: along the morphing glass's bottom edge, riding up with a fold.
            val footTravel = if (reduceMotion) 1f else progress
            val footFrame = geometry.frame(footTravel, if (reduceMotion) 1f else bottomProgress, 0f)
            val footPull = if (reduceMotion) 0f else morph.drag * px
            val (footRect, footDownward) = ComposerMorphGeometry.stretched(footFrame.rect, footPull, stretchesUpward, minHeight, translatesDownward)
            val footBlur = if (reduceMotion || usesDownwardDismiss) 0f else ((if (morph.isOpen) ComposerMorphTunables.contentBlur else ComposerMorphTunables.exitBlur) * px).toFloat()
            // Folding into the +, the foot keeps its resting size and scales with the glass.
            val scalesToSource = usesDownwardDismiss
            val footTarget = geometry.target
            PlacedBox(
                if (scalesToSource) Rect(footRect.topLeft + Offset(0f, footDownward), footTarget.size) else footRect.translate(0f, footDownward),
                Modifier
                    .then(
                        if (!scalesToSource) {
                            Modifier
                        } else {
                            Modifier.graphicsLayer {
                                scaleX = footRect.width / max(footTarget.width, 1f)
                                scaleY = footRect.height / max(footTarget.height, 1f)
                                transformOrigin = TransformOrigin(0f, 0f)
                            }
                        },
                    )
                    .morphReveal({ if (usesDownwardDismiss) progress else morph.content.value }, null, footBlur)
                    .semantics { if (!morph.isOpen) hideFromAccessibility() },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box {
                    foot()
                    // iOS `allowsHitTesting(morph.isOpen)`: folding, its controls take no taps.
                    if (!morph.isOpen) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .clearAndSetSemantics {}
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                        )
                    }
                }
            }
        }
    }
}

/**
 * Whether the morph's content is on screen: false while [ComposerMorphSurface] keeps it
 * composed closed (`retainsContent`). Content reads it to hold back what only an open sheet
 * should do (asking for access, reporting frames to flights).
 */
val LocalComposerMorphContentActive = compositionLocalOf { true }

/** How long after the surface first appears its retained content is composed closed (ms). */
private const val RETAINED_CONTENT_PREWARM_MILLIS = 700L

/**
 * The retained content while closed: measured at the rest's size (so opening only places it),
 * never placed while closed so nothing draws, takes touches, or registers bounds in RectManager.
 */
@Composable
private fun RetainedClosedContent(screen: Rect, target: (screen: Rect, keyboardTop: Float?) -> Rect, content: @Composable () -> Unit) {
    val rest = target(screen, null)
    val focusManager = LocalFocusManager.current
    Layout(
        content = { CompositionLocalProvider(LocalComposerMorphContentActive provides false) { content() } },
        modifier = Modifier
            .clearAndSetSemantics {}
            .inertToFocus(focusManager),
    ) { measurables, _ ->
        val w = rest.width.roundToInt().coerceAtLeast(1)
        val h = rest.height.roundToInt().coerceAtLeast(1)
        val fixed = Constraints.fixed(w, h)
        for (i in measurables.indices) measurables[i].measure(fixed)
        layout(0, 0) {}
    }
}

/**
 * No descendant can take keyboard or D-pad focus: focus moving or requested into the group is
 * cancelled (`onEnter`, which `requestFocus` on a descendant also goes through), and focus
 * already inside when it turns inert (a sheet closing on a focused tile) is cleared.
 */
internal fun Modifier.inertToFocus(focusManager: FocusManager): Modifier = this
    .onFocusChanged { if (it.hasFocus) focusManager.clearFocus(force = true) }
    .focusProperties { onEnter = { cancelFocusChange() } }
    .focusGroup()

/** UIKit's normal deceleration (0.998 per ms): a throw's predicted travel is velocity × 0.499 s (SwiftUI `predictedEndTranslation`). */
internal const val PREDICT_SECONDS = 0.499f

/**
 * iOS `MorphReveal`: the content arriving out of a blur and leaving into one on its own clock
 * ([reveal]), whole by [lead] of it (`photoContentLead`; 1 follows it); under Reduce Motion (or a
 * downward dismissal) [fade], the glass's own, instead.
 * No blur on content all but gone or all but whole: it costs a pass and shows nothing.
 */
internal fun Modifier.morphReveal(reveal: () -> Float, fade: Float?, blurPx: Float, lead: Float = 1f): Modifier = graphicsLayer {
    val showing = fade ?: (reveal() / max(lead, 0.01f)).coerceIn(0f, 1f)
    val radius = if (fade != null || showing < 0.02f || showing > 0.98f) 0f else (1 - showing) * blurPx
    renderEffect = transitionBlurEffect(radius)
    alpha = showing
}

/**
 * iOS `MorphDissolve`: what the morph grows out of, leaving into a blur as the content arrives
 * and returning as it leaves (`1 − reveal`, on the content's clock, so the two add up to a
 * whole); [scales] shrinks it 4 % toward its foot as it goes.
 */
internal fun Modifier.morphDissolve(reveal: () -> Float, shown: Boolean, fade: Float, scales: Boolean, blurPx: Float): Modifier = graphicsLayer {
    val r = reveal()
    val clamped = r.coerceIn(0f, 1f)
    val showing = if (shown) 1 - clamped else 0f
    val radius = if (showing < 0.02f || showing > 0.98f) 0f else (1 - showing) * blurPx
    val scale = if (scales) 0.96f + 0.04f * (1 - clamped) else 1f
    scaleX = scale
    scaleY = scale
    transformOrigin = TransformOrigin(0.5f, 1f)
    renderEffect = transitionBlurEffect(radius)
    alpha = ComposerMorphGeometry.outgoingOpacity(r, shown, fade)
}

/** iOS `UnevenRoundedRectangle` with equal top and equal bottom corners, optionally at [offset] / [size] inside the layer (a mask). */
private class MorphShape(
    private val top: Float,
    private val bottom: Float,
    private val offset: Offset = Offset.Zero,
    private val maskSize: Size? = null,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val s = maskSize ?: size
        val rect = Rect(offset, s)
        val limit = min(s.width, s.height) / 2
        val t = CornerRadius(min(top, limit))
        val b = CornerRadius(min(bottom, limit))
        return Outline.Generic(Path().apply { addRoundRect(RoundRect(rect, t, t, b, b)) })
    }
}

/**
 * A box at [rect] (px, in its parent's space) with exactly that size, whatever its content
 * measures, without reading the rect in a layout lambda (the surface recomposes per frame anyway).
 */
@Composable
private fun PlacedBox(
    rect: Rect,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable () -> Unit = {},
) {
    val width = max(rect.width, 1f).roundToInt()
    val height = max(rect.height, 1f).roundToInt()
    Layout(
        content = { Box(modifier, contentAlignment = contentAlignment) { content() } },
        modifier = Modifier,
    ) { measurables, constraints ->
        val placeable = measurables.first().measure(Constraints.fixed(width, height))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(rect.left.roundToInt(), rect.top.roundToInt())
        }
    }
}

private fun lerpRect(a: Rect, b: Rect, t: Float): Rect = Rect(
    a.left + (b.left - a.left) * t,
    a.top + (b.top - a.top) * t,
    a.right + (b.right - a.right) * t,
    a.bottom + (b.bottom - a.bottom) * t,
)
