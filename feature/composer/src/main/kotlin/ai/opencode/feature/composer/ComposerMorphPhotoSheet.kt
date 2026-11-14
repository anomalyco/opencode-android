package ai.opencode.feature.composer

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.feature.composer.modelpicker.ModelPickerTunables
import ai.opencode.feature.composer.plusmenu.PhotoFlights
import ai.opencode.feature.composer.plusmenu.PhotoLibraryAccessHost
import ai.opencode.feature.composer.plusmenu.PlusCameraPresentation
import ai.opencode.feature.composer.plusmenu.PlusCameraSurface
import ai.opencode.feature.composer.plusmenu.PlusPhotoLibrary
import ai.opencode.feature.composer.plusmenu.PlusPhotoSheetContent
import ai.opencode.feature.composer.plusmenu.PlusPhotoSheetDetent
import ai.opencode.feature.composer.plusmenu.PlusPhotoSheetFoot
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * The New Plus Menu's photo sheet in the composer's morph (iOS `SessionView+PhotoSheet`): the
 * + menu's surface grows into a sheet over the keyboard's place that a drag or the grabber
 * raises to [ceiling]. Keep it composed for the session's lifetime and drive it with
 * [isPresented]; pass the + menu overlay `handedOff = isPresented`.
 *
 * @param isPresented Photos was chosen in the menu; false folds the sheet away.
 * @param menuOpen the menu is still up: going away, the sheet folds back onto it (Back);
 *   otherwise it slides down (iOS `photoSheetFoldsToComposer`).
 * @param ceiling the expanded detent's top, root px (iOS `headerHeight + headerGap`).
 * @param hadKeyboard the message field had the keyboard when Photos was chosen: the compact
 *   detent takes the keyboard's height, and the sheet's bottom edge rides the keyboard's curve.
 * @param morph hoist it to sink the composer under the sheet with
 *   `Modifier.handsGlassOver(morph, reduceMotion, sinksUnderSheet = true)`.
 * @param onBack ‹ Back: the session goes back to the menu (sets [isPresented] false, menu up).
 * @param onAdd the picked photo IDs, in tap order ([PlusPhotoLibrary.takePicks] already ran).
 * @param onCapture a photo from the sheet's camera (JPEG), with no review.
 * @param onDismiss a tap outside, a drag or throw down, or system Back: close the menu too.
 */
@Composable
fun ComposerMorphPhotoSheet(
    isPresented: Boolean,
    menuOpen: Boolean,
    anchors: AttachmentMenuAnchors,
    library: PlusPhotoLibrary,
    flights: PhotoFlights,
    camera: PlusCameraPresentation,
    ceiling: Float,
    onBack: () -> Unit,
    onAdd: (List<String>) -> Unit,
    onCapture: (ByteArray) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    hadKeyboard: Boolean = false,
    morph: ComposerMorph = rememberComposerMorph(),
) {
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val px = density.density
    val scope = rememberCoroutineScope()
    val imeBottom = WindowInsets.ime.getBottom(density)
    val navigationBottom = WindowInsets.navigationBars.getBottom(density)

    // Detent state in root dp; [displayTop] is its top as drawn (iOS animates `photoSheetDetent`).
    var detent by remember { mutableStateOf(PlusPhotoSheetDetent()) }
    val displayTop = remember { Animatable(0f) }
    var source by remember { mutableStateOf(Rect.Zero) }
    var landsOn by remember { mutableStateOf<Rect?>(null) }
    var foldsToComposer by remember { mutableStateOf(false) }
    val currentCeiling by rememberUpdatedState(ceiling / px)
    val currentHadKeyboard by rememberUpdatedState(hadKeyboard)
    val currentImeBottom by rememberUpdatedState(imeBottom)
    val currentNavigationBottom by rememberUpdatedState(navigationBottom)
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    /** iOS `composerSheetTop(composer:)`: the compact detent's top (root dp). */
    fun compactTop(): Float {
        val composer = anchors.composer
        val keyboard = currentImeBottom / px
        val safeBottom = currentNavigationBottom / px
        val software = keyboard > 120
        val focused = currentHadKeyboard
        val composerTop = composer.top / px
        if (focused && software) return composerTop
        val minLift = max(ComposerMorphTunables.leastLift, ModelPickerTunables.mediumMax - max(composer.height / px.toDouble(), 56.0)).toFloat()
        val lift = if (software) keyboard - safeBottom else minLift
        val bar = if (focused) max(keyboard - safeBottom, 0f) else 0f
        return composerTop - max(lift - bar, 0f)
    }

    fun refreshDetentBounds(): PlusPhotoSheetDetent {
        val next = detent.copy(ceiling = currentCeiling)
        if (next.compactTop <= 0f) next.compactTop = compactTop()
        return next
    }

    fun open() {
        val menu = anchors.menu
        if (menu == Rect.Zero) return
        library.resetClosingPicks()
        flights.cancelAll()
        if (!morph.isOpening) source = menu
        morph.dragTo(0f)
        val next = PlusPhotoSheetDetent().apply { open(compactTop(), currentCeiling) }
        detent = next
        foldsToComposer = false
        landsOn = null
        scope.launch { displayTop.snapTo(next.top) }
        val edge = if (currentHadKeyboard && !reduceMotion) ComposerMorphTunables.keyboard() else null
        val animation = Motion.panelOpen.animation<Float>(reduceMotion = reduceMotion)
        // The session already gave the keyboard up as Photos was chosen.
        morph.armThenOpen(animation, edge, ComposerMorphTunables.photoContentDelay, prepare = {}, launch = {}, opened = {})
    }

    /** iOS `backFromPhotoSheet`: folds onto the menu's surface, which (and whose rows) come back as it lands. */
    fun back() {
        if (!morph.isOpening) return
        // The picks are dropped, but their badges stay until the sheet has folded away.
        library.setAsidePicks()
        foldsToComposer = false
        camera.expanded = false
        landsOn = if (source == Rect.Zero) null else source
        morph.fold(Motion.panelClose.animation(reduceMotion = reduceMotion), reduceMotion = reduceMotion)
    }

    /** iOS `closePhotoSheet` / `foldPhotoSheet`: slides down and fades out while the composer rises back. */
    fun foldDown() {
        if (!morph.isOpening) return
        library.clearPicks()
        // A drag down so far becomes where it rests, so the slide starts where the finger left it.
        val drag = morph.drag
        if (drag > 0f) {
            detent = detent.copy(top = detent.top + drag)
            scope.launch { displayTop.snapTo(displayTop.value + drag) }
            morph.dragTo(0f)
        }
        foldsToComposer = true
        // The sheet scales into the + and fades, keeping its content sharp.
        landsOn = anchors.seed.takeIf { it != Rect.Zero }
        camera.expanded = false
        morph.fold(Motion.panelClose.animation(reduceMotion = reduceMotion), reduceMotion = reduceMotion)
    }

    fun dragSheet(translation: Float) {
        if (!morph.isOpen) return
        val next = refreshDetentBounds()
        morph.dragTo(next.dragChanged(translation))
        detent = next
    }

    fun settleSheet(predicted: Float?, velocity: Float) {
        if (!morph.isOpen || morph.drag == 0f) return
        val next = refreshDetentBounds()
        next.drag = morph.drag
        when (val settlement = next.settle(predicted, velocity)) {
            PlusPhotoSheetDetent.Settlement.Close -> {
                detent = next.copy(drag = morph.drag)
                currentOnDismiss()
            }
            is PlusPhotoSheetDetent.Settlement.Stay -> {
                detent = next
                val spec = Motion.release.animation<Float>(reduceMotion = reduceMotion)
                val v0 = Motion.release.initialVelocity(settlement.initialVelocity, reduceMotion)
                // Each value continues at the finger's speed toward its own end (iOS's normalized initialVelocity).
                scope.launch { displayTop.animateTo(next.top, spec, initialVelocity = v0 * (next.top - displayTop.value)) }
                morph.animateDrag(0f, spec, initialVelocity = v0 * (0f - morph.drag))
            }
        }
    }

    fun toggleExpand() {
        if (!morph.isOpen) return
        val next = refreshDetentBounds()
        next.toggle()
        detent = next
        val phase = if (next.isExpanded) MotionPhase.Enter else MotionPhase.Exit
        val spec = Motion.layout.animation<Float>(phase, reduceMotion)
        scope.launch { displayTop.animateTo(next.top, spec) }
        morph.animateDrag(0f, spec)
    }

    // The session presents and takes it away; how it goes depends on whether the menu stays.
    LaunchedEffect(isPresented) {
        if (isPresented) {
            // The menu's anchor may land a frame after Photos is chosen.
            if (anchors.menu == Rect.Zero) snapshotFlow { anchors.menu }.first { it != Rect.Zero }
            open()
        } else if (menuOpen) {
            back()
        } else {
            foldDown()
        }
    }
    // Folded away: the menu's place is its own again.
    LaunchedEffect(morph) {
        snapshotFlow { morph.isShown }.drop(1).collect { shown ->
            if (!shown) {
                library.resetClosingPicks()
                foldsToComposer = false
                detent = detent.copy(isExpanded = false)
            }
        }
    }
    LaunchedEffect(library) { library.prime() }
    DisposableEffect(library) { onDispose { library.stop() } }
    // iOS's escape action: system Back dismisses the sheet and the menu.
    BackHandler(enabled = morph.isOpening) { onDismiss() }

    PhotoLibraryAccessHost()
    val expanded = detent.isExpanded
    val top = displayTop.value * px
    val ceilingPx = ceiling
    val menuSource = if (morph.isShown && source != Rect.Zero) source else if (anchors.menu == Rect.Zero) source else anchors.menu
    ComposerMorphSurface(
        morph = morph,
        source = menuSource,
        sourceRadiusDp = AttachmentMenuMetrics.cornerRadius.value.toDouble(),
        target = { screen, _ ->
            ComposerMorphGeometry.sheet(
                top = top,
                column = if (screen.width / px > 600) anchors.composer.takeIf { it != Rect.Zero } else null,
                screen = screen,
                keyboardTop = null,
                ceiling = ceilingPx,
                density = px,
            )
        },
        onDismiss = { onDismiss() },
        modifier = modifier,
        openedFrom = source.takeIf { it != Rect.Zero },
        landsOn = landsOn,
        stretchesUpward = true,
        backdropDim = ComposerMorphTunables.photoBackdropDim * ComposerMorphTunables.noBlurDimBoost,
        foot = {
            PlusPhotoSheetFoot(
                library = library,
                isExpanded = expanded,
                onBack = onBack,
                onAllPhotos = ::toggleExpand,
                onAdd = { onAdd(library.takePicks()) },
            )
        },
        outgoingReturns = menuOpen && !foldsToComposer,
        continuousGlass = true,
        dismissesDown = foldsToComposer,
        keyboardStaysAsItFolds = true,
        translatesDownward = detent.dragOriginExpanded == false,
        seed = anchors.seed,
        retainsContent = true,
    ) {
        PlusPhotoSheetContent(
            library = library,
            flights = flights,
            camera = camera,
            isExpanded = expanded,
            onCamera = { if (morph.isOpen) camera.expanded = true },
            onDismiss = { onDismiss() },
            onDrag = ::dragSheet,
            onDragEnded = { predicted, velocity -> settleSheet(predicted, velocity) },
            onDragCancelled = { settleSheet(null, 0f) },
            onToggleExpand = ::toggleExpand,
        )
    }
    PlusCameraSurface(presentation = camera, sheetIsOpen = morph.isOpen, onCapture = onCapture, sheetContent = { morph.content.value })
}

