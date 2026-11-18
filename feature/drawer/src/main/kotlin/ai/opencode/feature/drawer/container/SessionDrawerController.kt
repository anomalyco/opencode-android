package ai.opencode.feature.drawer.container

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionCurve
import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.SpringSolver
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.feature.drawer.catchup.CatchUpDisposition
import ai.opencode.feature.drawer.catchup.CatchUpFinishTunables
import ai.opencode.feature.drawer.catchup.CatchUpMetrics
import ai.opencode.feature.drawer.catchup.CatchUpStackController
import ai.opencode.feature.drawer.catchup.CatchUpStage
import ai.opencode.feature.drawer.motion.DrawerDepth
import ai.opencode.feature.drawer.motion.DrawerDim
import ai.opencode.feature.drawer.motion.DrawerKeyboardHold
import ai.opencode.feature.drawer.motion.DrawerMotionState
import ai.opencode.feature.drawer.motion.DrawerPanEvent
import ai.opencode.feature.session.ComposerTunables
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Port of the wide-window metrics `SessionDrawerContainer` uses: a window at least
 * [minimumWindowWidth] wide lists the sessions in a [width]-wide sidebar beside the session instead
 * of a drawer under it.
 */
object SidebarMetrics {
    val minimumWindowWidth: Dp = 700.dp
    val width: Dp = 320.dp
}

/**
 * Mutes the session card while the drawer is open. Its alpha is the drawer's progress, so it
 * follows the drag and the settle exactly. Light darkens the white card toward grey; a black scrim
 * would vanish on the black dark-mode card, so dark blends toward grey. Draw it at `alpha =
 * progress`.
 */
object DrawerScrim {
    fun color(isDark: Boolean, highContrast: Boolean): Color =
        if (isDark) {
            Color(0.24f, 0.24f, 0.24f, if (highContrast) 0.7f else 0.6f)
        } else {
            Color(0f, 0f, 0f, if (highContrast) 0.45f else 0.32f)
        }
}

/**
 * Whether closing the drawer gives the keyboard back to the composer that had it when it opened.
 */
object DrawerKeyboard {
    /** The session the drawer closes onto: one just chosen in it (maybe still loading behind the old card), else the one shown. */
    fun closingSession(chosen: String?, shown: String?): String? = chosen ?: shown

    /** Only on the same session, with nothing presented over it. A draft (`null`) keeps its own focus. */
    fun restoresFocus(openedOn: String?, closedOn: String?, sheetPresented: Boolean): Boolean {
        if (sheetPresented) return false
        if (closedOn == null) return true
        return openedOn == closedOn
    }
}

/** What a Catch Up mode change is morphing. */
enum class CatchUpModeMorph {
    /** The card insets into the stack from wherever it was (the open drawer's offset, or full screen). */
    EnterCatchUp,
    /** Leaving with a card showing: the session lays out full screen, grown from the stack's frame. */
    OpenIntoSession,
    /** The last card went (Catch Up finished) onto the session: it rises in from a receded pose. */
    FinishIntoSession,
    /** Back or finishing onto the open drawer it was opened from: entering, in reverse. */
    ReturnToDrawer,
}

/**
 * The plain object behind [SessionDrawerContainer]: owns the drawer's motion ([motion]), Catch Up's
 * stack ([catchUpStack]), the Catch Up mode morphs and the wide-window sidebar. Everything per
 * frame is snapshot state read in layer/draw blocks; Home hears only coarse callbacks.
 *
 * Create it with [rememberSessionDrawerController]. Units: dp unless a name says px.
 */
@Stable
class SessionDrawerController internal constructor(
    private val scope: CoroutineScope,
    /** The drawer's drag, settle and open state. */
    val motion: DrawerMotionState,
    /** Catch Up's card stack. */
    val catchUpStack: CatchUpStackController,
) {
    // region Inputs (set by the composable each pass)

    internal var reducedMotion = false
    internal var frameDuration = 1.0 / 60.0
    internal var density = 1f
    internal var containerSizePx = IntSize.Zero
    /** Safe area (system bars ∪ cutout, never the keyboard) in px: left, top, right, bottom. */
    internal var safeLeftPx = 0f
    internal var safeTopPx = 0f
    internal var safeRightPx = 0f
    internal var safeBottomPx = 0f
    internal var displayCornerRadius = 0f
    internal var drawerWidth = 0f
    internal var keyboardHold: DrawerKeyboardHold? = null
    internal var isSheetPresented: () -> Boolean = { false }
    internal var isContentPushed: () -> Boolean = { false }
    internal var clearFocus: () -> Unit = {}

    var onArrived: (Boolean) -> Unit = {}
    var onCommitted: () -> Unit = {}
    var onTouchDown: () -> Unit = {}
    var onPresentationChanged: (Boolean) -> Unit = {}

    /**
     * The session the card shows, or is about to show once a session chosen in the drawer has
     * loaded (`null` for a draft). A session chosen in the open drawer, or a new one, opens with
     * the keyboard down: the hold lets go while the drawer still covers the card.
     */
    var contentSessionID: String? = null
        set(value) {
            val old = field
            field = value
            val hold = keyboardHold ?: return
            if (value == old || !hold.isHolding || value == sessionWhenSuspended) return
            hold.letGo(leavingCard = true)
        }

    // endregion

    // region Card snapshot

    /** Records the card's content each frame, for the morphs' outgoing pictures. */
    internal var cardLayer: GraphicsLayer? = null
    /** Records Catch Up's stage, which fades as a picture of itself when Catch Up ends. */
    internal var chromeLayer: GraphicsLayer? = null

    /** The stage's picture while an exit morph fades it (live glass and buttons stay out of reach). */
    internal var stageSnapshot by mutableStateOf<ImageBitmap?>(null)
        private set

    /** A picture of the card's content now, or `null` (Reduce Motion, not drawn yet). */
    suspend fun captureCard(): ImageBitmap? = capture(cardLayer)

    private suspend fun capture(layer: GraphicsLayer?): ImageBitmap? {
        if (reducedMotion || layer == null) return null
        if (layer.size.width <= 0 || layer.size.height <= 0) return null
        return runCatching { layer.toImageBitmap() }.getOrNull()
    }

    /**
     * Captures the card, then runs [then] (which changes what the card shows): the next morph fades
     * that picture out over the new content.
     */
    fun captureCardThen(then: () -> Unit) {
        catchUpStack.settle()
        // Finishing Catch Up: the last card has already flown away (`liveAlpha == 0`).
        val liveHidden = catchUp != null && catchUpStack.liveAlpha <= 0.01f
        if (reducedMotion || cardLayer == null || liveHidden) {
            pendingSnapshot = null
            pendingStageSnapshot = null
            then()
            return
        }
        scope.launch {
            pendingSnapshot = captureCard()
            pendingStageSnapshot = null
            then()
        }
    }

    internal val pendingCardSnapshot: ImageBitmap? get() = pendingSnapshot

    private var pendingSnapshot: ImageBitmap? = null
    private var pendingStageSnapshot: ImageBitmap? = null

    // endregion

    // region Keyboard and touches

    /** The card's session when it last stopped taking touches (the drawer began to open). */
    private var sessionWhenSuspended: String? = null

    /** The card doesn't take touches: the drawer is moving or open. */
    private var isContentSuspended = false

    private fun suspendContent() {
        if (!isContentSuspended) sessionWhenSuspended = contentSessionID
        isContentSuspended = true
        val hold = keyboardHold
        if (hold == null || !hold.isHolding) clearFocus()
    }

    /**
     * Closing onto the same session gives the keyboard back: the held field's keys come back up.
     * After a switch to another session, or under Settings presented meanwhile, the keyboard stays
     * down until the composer is tapped (`DrawerKeyboard`).
     */
    private fun contentBecameInteractive() {
        isContentSuspended = false
        val restores = DrawerKeyboard.restoresFocus(
            openedOn = sessionWhenSuspended,
            closedOn = contentSessionID,
            sheetPresented = isSheetPresented(),
        )
        sessionWhenSuspended = contentSessionID
        val hold = keyboardHold
        if (restores) {
            hold?.giveBack()
        } else {
            hold?.letGo()
            if (hold?.isHolding != true) clearFocus()
        }
    }

    /** A subagent pushed over the session lets go of the session's keyboard. */
    fun pagesChanged(pushed: Boolean) {
        if (pushed) keyboardHold?.letGo()
    }

    private var lastPushed = false

    /** The pushed stack changed. */
    internal fun updatePushed(pushed: Boolean) {
        if (pushed == lastPushed) return
        lastPushed = pushed
        pagesChanged(pushed)
    }

    // endregion

    // region Motion callbacks

    /** Mode changes place the drawer without a haptic or an arrival. */
    private var quiet = false

    private inline fun quietly(block: () -> Unit) {
        val was = quiet
        quiet = true
        try {
            block()
        } finally {
            quiet = was
        }
    }

    init {
        motion.callbacks = DrawerMotionState.Callbacks(
            onArrived = { if (!quiet) onArrived(it) },
            onCommitted = { if (!quiet) onCommitted() },
            onTouchDown = { onTouchDown() },
            onPresentationChanged = { onPresentationChanged(it) },
            onSuspendContent = ::suspendContent,
            onContentInteractive = { contentBecameInteractive() },
        )
        motion.canHoldKeyboard = { catchUp == null && !isContentPushed() && sidebarShown == null }
    }

    /**
     * Home's `isPresented`: every programmatic open and close animates; Reduce Motion lands at
     * once.
     */
    fun setPresented(presented: Boolean, animated: Boolean = true) {
        if (catchUp != null) return
        if (presented == motion.isPresented || (sidebarShown != null && presented)) return
        // Catch Up's return to the drawer hands over where it was headed.
        finishModeTransition()
        motion.setPresented(presented, animated)
    }

    /** A drag began on the card: a morph still landing lands now. */
    internal fun panBegan() = finishModeTransition()

    /**
     * A drag that began before Catch Up opened or closed: its remaining events belong to neither
     * the stack nor the drawer.
     */
    private var isPanInFlight = false
    private var ignoresPanUntilItEnds = false

    /** Whether the drawer takes this pan event. */
    internal fun acceptsPan(event: DrawerPanEvent): Boolean {
        when (event) {
            is DrawerPanEvent.Began -> {
                ignoresPanUntilItEnds = false
                isPanInFlight = true
            }
            is DrawerPanEvent.Ended, is DrawerPanEvent.Cancelled -> {
                isPanInFlight = false
                if (ignoresPanUntilItEnds) {
                    ignoresPanUntilItEnds = false
                    return false
                }
            }
            is DrawerPanEvent.Changed -> Unit
        }
        return !ignoresPanUntilItEnds
    }

    private fun cancelPanInFlight() {
        if (!isPanInFlight) return
        isPanInFlight = false
        ignoresPanUntilItEnds = true
    }

    /** Closing by hand: a tap on the card, back, TalkBack's escape. */
    fun closeDrawer(): Boolean {
        if (!motion.isPresented && motion.offset <= 0f) return false
        finishModeTransition()
        return motion.close()
    }

    // endregion

    // region Sidebar

    /** See `SessionDrawerContainer`'s `sidebarShown`: `null` slides the phone's drawer under the card. */
    var sidebarShown: Boolean? = null
        private set
    private var sidebarInitialized = false

    /** The card's leading edge, in dp: the sidebar's width while it shows, else 0. */
    internal val sidebarLeading = Animatable(0f)
    private var sidebarJob: Job? = null

    /** The sidebar is composed (shown, or still sliding away). */
    var isSidebarComposed by mutableStateOf(false)
        private set

    /** The sidebar shows beside the card, except while catching up. */
    val isSidebarVisible: Boolean get() = sidebarShown == true && catchUp == null

    fun setSidebar(shown: Boolean?, animated: Boolean) {
        if (sidebarInitialized && shown == sidebarShown) return
        val wasSidebar = sidebarInitialized && sidebarShown != null
        sidebarInitialized = true
        sidebarShown = shown
        // A return to the drawer still landing hands over to the sidebar.
        finishModeTransition()
        motion.recedes = shown == null
        if (shown != null) {
            keyboardHold?.letGo()
            // The drawer becomes the sidebar: anything open or moving rests shut.
            val wasPresented = motion.isPresented
            quietly {
                if (wasPresented) motion.setPresented(false, animated = false) else if (motion.offset > 0f) motion.settle(0f, animated = false)
            }
            if (wasPresented) onPresentationChanged(false)
            layoutSidebar(animated = animated && wasSidebar)
        } else {
            sidebarJob?.cancel()
            sidebarJob = null
            scope.launch { sidebarLeading.snapTo(0f) }
            isSidebarComposed = false
        }
    }

    /**
     * Moves the card's leading edge to the sidebar's (or the screen's), on `MotionRole.Layout` for
     * the sidebar's toggle (opening; closing on its exit, `Collapse`). The card's own frame changes,
     * so the session narrows and widens with the sidebar instead of sliding.
     */
    internal fun layoutSidebar(animated: Boolean) {
        if (sidebarShown == null || catchUp != null) return
        val visible = isSidebarVisible
        if (visible) isSidebarComposed = true
        val target = if (visible) drawerWidth else 0f
        sidebarJob?.cancel()
        sidebarJob = scope.launch {
            if (animated) sidebarLeading.animateTo(target, MotionRole.Layout.animation(if (visible) MotionPhase.Enter else MotionPhase.Exit)) else sidebarLeading.snapTo(target)
            if (!isSidebarVisible) isSidebarComposed = false
        }
    }

    /** The sidebar's width changed. */
    internal fun sidebarWidthChanged() {
        if (sidebarShown != null) layoutSidebar(animated = false)
    }

    // endregion

    // region Catch Up mode

    /** The stage on screen; `null` outside Catch Up. */
    var catchUp: CatchUpStage? = null
        private set

    /** The last stage shown, kept mounted while an exit morph plays. */
    internal var lastStage: CatchUpStage? = null

    /** The morph running, if any. Composition reads it to keep the stage and drawer mounted. */
    var morph: CatchUpModeMorph? by mutableStateOf(null)
        private set

    private var morphJob: Job? = null
    private var morphStep: ((Double) -> Unit)? = null
    private var morphCompletion: (() -> Unit)? = null

    // Morph values, read in layer/draw blocks only. NaN means "follow the drawer".
    internal val chromeAlpha = mutableFloatStateOf(0f)
    internal val chromeScale = mutableFloatStateOf(1f)
    internal val drawerAlpha = mutableFloatStateOf(1f)
    internal val drawerScale = mutableFloatStateOf(Float.NaN)
    internal val dimAlpha = mutableFloatStateOf(Float.NaN)
    internal val stackOffset = mutableFloatStateOf(0f)
    internal val stackTy = mutableFloatStateOf(0f)
    internal val stackAlpha = mutableFloatStateOf(1f)
    internal val scrimAlpha = mutableFloatStateOf(Float.NaN)
    internal val contentAlpha = mutableFloatStateOf(1f)

    /** The session card's pose while a morph owns it (`cardPosed`), else the drawer's offset places it. */
    internal var cardPosed by mutableStateOf(false)
        private set
    internal val cardTx = mutableFloatStateOf(0f)
    internal val cardTy = mutableFloatStateOf(0f)
    internal val cardScale = mutableFloatStateOf(1f)
    internal val cardAlpha = mutableFloatStateOf(1f)
    internal val cardRadius = mutableFloatStateOf(0f)
    internal val borderAlpha = mutableFloatStateOf(0f)
    /**
     * The card's visible frame runs from [cardMaskStart] to [cardMaskEnd] in its own px; `null` is
     * the whole card.
     */
    internal var cardMaskStart: Rect? = null
    internal var cardMaskEnd: Rect? = null
    internal val maskProgress = mutableFloatStateOf(1f)

    /** The outgoing session's picture, fading over the card. */
    internal var outgoing by mutableStateOf<ImageBitmap?>(null)
        private set
    /** Where [outgoing] is drawn, in the card's px; `null` fills the card. */
    internal var outgoingRect: Rect? = null
        private set
    internal val outgoingAlpha = mutableFloatStateOf(0f)

    /**
     * [returnsToDrawer]: the stack ends by going back to the open drawer it was opened from (Back,
     * or finishing the stack), rather than onto the session.
     */
    fun updateCatchUp(stage: CatchUpStage?, returnsToDrawer: Boolean, animated: Boolean) {
        val previous = catchUp
        if (stage == null && previous == null) return
        val togglesMode = (stage != null) != (previous != null)
        if (togglesMode) {
            // A drag under way belonged to the other mode: neither the stack nor the drawer takes the rest of it.
            cancelPanInFlight()
            // Set first, so a return to the drawer cut short by Catch Up opening again knows it never arrived.
            catchUp = stage
            finishModeTransition()
        }
        var startOffset = 0f
        var startProgress = 0f
        if (stage != null && previous == null) {
            keyboardHold?.letGo()
            startOffset = motion.offset
            startProgress = motion.progress
            val wasPresented = motion.isPresented
            if (wasPresented) onPresentationChanged(false)
            // Catch Up takes over from the drawer; that is not a close to feel.
            quietly {
                if (wasPresented) motion.setPresented(false, animated = false) else if (motion.offset > 0f) motion.settle(0f, animated = false)
            }
        }
        catchUp = stage
        if (stage != null) lastStage = stage
        // Once the last card has flown away (`liveAlpha == 0`), there is no live card left to
        // linger as `outgoing`.
        val wasShowingCard = previous != null && previous.cardID != null && catchUpStack.liveAlpha > 0.01f
        // A card drag under way is nobody's now. (The stack keeps its stage until the exit morph
        // unmounts it, so its preview fades with the stage.)
        if (previous != null && stage == null) catchUpStack.dropDrag()
        // Catching up takes the whole screen; afterwards the sidebar returns.
        if (sidebarShown != null) {
            sidebarJob?.cancel()
            val target = if (isSidebarVisible) drawerWidth else 0f
            scope.launch { sidebarLeading.snapTo(target) }
        }
        when {
            previous == null && stage != null -> enter(animated, startOffset, startProgress, stage.cardID != null)
            previous != null && stage == null && returnsToDrawer && sidebarShown == null -> returnToDrawer(animated, wasShowingCard)
            previous != null && stage == null -> exitIntoSession(animated, wasShowingCard)
            else -> Unit
        }
    }

    /** The chrome's Keep Unread / Mark as Read: a morph still landing lands first. */
    fun dismissCard(disposition: CatchUpDisposition) {
        finishModeTransition()
        catchUpStack.fling(disposition)
    }

    /** The chrome's Undo. */
    fun undoCard() {
        finishModeTransition()
        catchUpStack.undo()
    }

    private val stackFrame: Rect
        get() {
            val w = containerSizePx.width.toFloat()
            val h = containerSizePx.height.toFloat()
            val side = CatchUpMetrics.sideInset.value * density
            return Rect(
                left = safeLeftPx + side,
                top = safeTopPx + CatchUpMetrics.headerHeight.value * density,
                right = w - safeRightPx - side,
                bottom = h - safeBottomPx - CatchUpMetrics.footerHeight.value * density,
            )
        }

    private val closedScale: Float get() = DrawerDepth.scale(0f, reducedMotion || sidebarShown != null)

    /**
     * Entering: the card jumps to its inset frame and slides from the drawer's offset into the
     * stack on `Motion.surface`; the chrome fades in as the drawer recedes and fades out. From
     * the session itself the outgoing screen fades over the first card on `swap`'s exit.
     */
    private fun enter(animated: Boolean, startOffset: Float, startProgress: Float, showsCard: Boolean) {
        val snapshot = pendingSnapshot.takeIf { animated && startOffset <= 0f }
        pendingSnapshot = null
        val startScale = DrawerDepth.scale(startProgress, reducedMotion || sidebarShown != null)
        val startDim = if (sidebarShown != null) 0f else DrawerDim.alpha(startProgress, dimMaximum)
        val endDim = if (sidebarShown != null) 0f else DrawerDim.alpha(0f, dimMaximum)
        catchUpStack.liveAlpha = if (showsCard) 1f else 0f
        outgoing = snapshot
        outgoingRect = null
        val modalFromDrawer = sidebarShown == null && startProgress > 0f
        stackAlpha.floatValue = if (animated && modalFromDrawer) 0f else 1f
        stackTy.floatValue = if (animated && modalFromDrawer) FINISH_RISE else 0f
        runMorph(
            kind = CatchUpModeMorph.EnterCatchUp,
            animated = animated,
            duration = SURFACE.duration,
            step = { t ->
                val p = progress(t, SURFACE)
                val start = if (animated) PRERENDER_ALPHA else 0f
                chromeAlpha.floatValue = start + (1f - start) * p
                chromeScale.floatValue = 1f
                // Android's modal drawer stays opaque over the background session while Catch Up's
                // stage and card rise in above it.
                val modal = sidebarShown == null
                stackOffset.floatValue = if (modal) 0f else startOffset * (1f - p)
                stackTy.floatValue = if (modal && startProgress > 0f) FINISH_RISE * (1f - p) else 0f
                stackAlpha.floatValue = if (modal && startProgress > 0f) progress(t, SWAP_IN) else 1f
                drawerScale.floatValue = startScale + (closedScale - startScale) * p
                drawerAlpha.floatValue = if (modal) (if (startProgress <= 0f) 0f else 1f) else 1f - p
                dimAlpha.floatValue = startDim + (endDim - startDim) * p
                outgoingAlpha.floatValue = if (snapshot == null) 0f else 1f - progress(t, SWAP_OUT)
            },
            completion = {
                outgoing = null
                stackOffset.floatValue = 0f
                stackTy.floatValue = 0f
                stackAlpha.floatValue = 1f
                chromeAlpha.floatValue = 1f
                resetDrawerOverrides()
                if (catchUp != null) catchUpStack.revealPreview(animated = animated)
            },
        )
    }

    /**
     * The stack card's composer strip while the opened session grows from it: a picture of the
     * stack card's bottom, sliding from the stack's bottom to where the session's composer rests,
     * over [composerCoverTop]'s canvas, which hides the live composer meanwhile.
     */
    internal var composerSlice by mutableStateOf<ImageBitmap?>(null)
        private set
    /** The slice's clip, in the card's px, at the morph's start and end. */
    internal var composerSliceStart = Rect.Zero
        private set
    internal var composerSliceEnd = Rect.Zero
        private set
    /** The picture's frame inside the slice (bottom-aligned), heights at the start and end. */
    internal var composerSliceImageStartHeight = 0f
        private set
    internal var composerSliceImageEndHeight = 0f
        private set

    /** Leaving onto the session (no drawer to return to): grows from the stack, or rises when finished. */
    private fun exitIntoSession(animated: Boolean, wasShowingCard: Boolean) {
        val snapshot = pendingSnapshot.takeIf { animated && wasShowingCard }
        pendingSnapshot = null
        catchUpStack.liveAlpha = 0f
        val previewFrom = catchUpStack.previewAlpha
        // Beside a sidebar the session's card starts past it.
        val cardLeft = if (isSidebarVisible) drawerWidth * density else 0f
        val fullW = containerSizePx.width.toFloat() - cardLeft
        val fullH = containerSizePx.height.toFloat()
        stageSnapshot = pendingStageSnapshot
        pendingStageSnapshot = null
        if (!animated || fullW <= 0f || fullH <= 0f) {
            runMorph(CatchUpModeMorph.FinishIntoSession, animated = false, duration = 0.0, step = {}, completion = ::landInSession)
            return
        }
        if (wasShowingCard) {
            // The opened session is laid out full screen at once; its layer grows from the stack's
            // pose (translation + uniform scale) while the card's frame mask opens to its bounds.
            val stack = stackFrame.translate(-cardLeft, 0f)
            val scale = max(stack.width / max(fullW, 1f), 0.01f)
            val radius = CatchUpMetrics.cornerRadius.value
            val cardTop = safeTopPx + CARD_TOP_CLUSTER * density - CatchUpMetrics.composerInset.value * density / scale
            val startRect = Rect(0f, cardTop, fullW, cardTop + stack.height / scale)
            val tx = (stack.center.x - fullW / 2) / density
            val ty = (stack.top - (fullH / 2 + (cardTop - fullH / 2) * scale)) / density
            val scaledRadius = radius / scale
            cardMaskStart = startRect
            cardMaskEnd = Rect(0f, 0f, fullW, fullH)
            if (snapshot != null) {
                // A 68 pt strip of the stack card's bottom (its composer) slides to the session's
                // composer, inset from the stack's 12 pt clearance to the composer's margin.
                val sliceHeight = minOf(COMPOSER_STRIP_HEIGHT * density, stack.height)
                val scaledSliceHeight = sliceHeight / scale
                val startTop = startRect.bottom - scaledSliceHeight
                val clearance = CatchUpMetrics.composerInset.value * density
                val endTop = fullH - safeBottomPx + clearance - sliceHeight
                val insetDelta = max(ComposerTunables.horizontalMargin * density - clearance, 0f)
                composerSliceStart = Rect(0f, startTop, fullW, startTop + scaledSliceHeight)
                composerSliceEnd = Rect(insetDelta, endTop, insetDelta + max(fullW - insetDelta * 2, 1f), endTop + sliceHeight)
                composerSliceImageStartHeight = startRect.height
                composerSliceImageEndHeight = stack.height
                composerSlice = snapshot
            }
            cardPosed = true
            runMorph(
                kind = CatchUpModeMorph.OpenIntoSession,
                animated = true,
                duration = SURFACE.duration,
                step = { t ->
                    val p = progress(t, SURFACE)
                    cardScale.floatValue = scale + (1f - scale) * p
                    cardTx.floatValue = tx * (1f - p)
                    cardTy.floatValue = ty * (1f - p)
                    cardAlpha.floatValue = 1f
                    maskProgress.floatValue = p
                    cardRadius.floatValue = scaledRadius + (displayCornerRadius - scaledRadius) * p
                    borderAlpha.floatValue = 1f - p
                    scrimAlpha.floatValue = 0f
                    fadeStage(t, previewFrom, finishing = false)
                },
                completion = ::landInSession,
            )
        } else {
            // Finished: the returning session, laid out full screen, rises in from the receded pose.
            val radius = max(displayCornerRadius, CatchUpMetrics.cornerRadius.value)
            val depth = DrawerDepth.scale(0f, reducedMotion = false)
            cardPosed = true
            runMorph(
                kind = CatchUpModeMorph.FinishIntoSession,
                animated = true,
                duration = CatchUpFinishTunables.sessionReturn.duration,
                step = { t ->
                    val p = progress(t, CatchUpFinishTunables.sessionReturn)
                    cardScale.floatValue = depth + (1f - depth) * p
                    cardTx.floatValue = 0f
                    cardTy.floatValue = FINISH_RISE * (1f - p)
                    cardAlpha.floatValue = PRERENDER_ALPHA + (1f - PRERENDER_ALPHA) * p
                    maskProgress.floatValue = 1f
                    cardRadius.floatValue = radius * (1f - p)
                    borderAlpha.floatValue = 0f
                    scrimAlpha.floatValue = 0f
                    fadeStage(t, previewFrom, finishing = true)
                },
                completion = ::landInSession,
            )
        }
    }

    /** The stage and the card behind leave on `swap`'s exit, on the frame the session starts. */
    private fun fadeStage(t: Double, previewFrom: Float, finishing: Boolean) {
        catchUpStack.previewAlpha = previewFrom * (1f - progress(t, SWAP_OUT))
        val stage = progress(t, SWAP_OUT)
        chromeAlpha.floatValue = 1f - stage
        chromeScale.floatValue = if (finishing) 1f + 0.04f * stage else 1f
        if (sidebarShown == null) {
            drawerAlpha.floatValue = 0f
        } else {
            // Catching up kept the phone pose; the sidebar shows at rest.
            drawerAlpha.floatValue = 1f
            drawerScale.floatValue = 1f
        }
    }

    private fun landInSession() {
        catchUpStack.releasePreview()
        clearCardPose()
        resetDrawerOverrides()
        chromeAlpha.floatValue = 0f
        chromeScale.floatValue = 1f
        if (catchUp == null) {
            quietly { motion.settle(0f, animated = false) }
            layoutSidebar(animated = false)
        }
    }

    /**
     * Leaving Catch Up for the drawer it was opened from: entering, in reverse. The stage fades as
     * the list comes forward again, and the card slides aside to the open drawer's pose. The
     * returning session is laid out full screen at once and the stack's frame is a mask that grows
     * to its bounds, so nothing inside reflows; the stack's session lingers as a brief picture.
     * Touches and TalkBack take the open drawer's pose from the first frame.
     */
    private fun returnToDrawer(animated: Boolean, wasShowingCard: Boolean) {
        val snapshot = pendingSnapshot.takeIf { animated && wasShowingCard }
        pendingSnapshot = null
        catchUpStack.liveAlpha = 0f
        val previewFrom = catchUpStack.previewAlpha
        stageSnapshot = pendingStageSnapshot
        pendingStageSnapshot = null
        // The open drawer's pose for everything but the picture.
        quietly { motion.setPresented(true, animated = false) }
        val fullW = containerSizePx.width.toFloat()
        val fullH = containerSizePx.height.toFloat()
        val stack = stackFrame
        val stackRadius = CatchUpMetrics.cornerRadius.value
        val target = drawerWidth
        val closed = closedScale
        outgoing = snapshot
        outgoingRect = stack
        cardMaskStart = stack
        cardMaskEnd = Rect(0f, 0f, fullW, fullH)
        cardPosed = animated
        runMorph(
            kind = CatchUpModeMorph.ReturnToDrawer,
            animated = animated,
            duration = SURFACE.duration,
            step = { t ->
                val p = progress(t, SURFACE)
                val modal = sidebarShown == null
                drawerScale.floatValue = closed + (1f - closed) * p
                // The open drawer's own dim is none; a black one fading out under the stage flashes in light mode.
                dimAlpha.floatValue = 0f
                // Modal drawer stays opaque over the full-screen session card while Catch Up fades out above it.
                drawerAlpha.floatValue = if (modal) 1f else fadeIn(t)
                maskProgress.floatValue = p
                cardRadius.floatValue = stackRadius + (displayCornerRadius - stackRadius) * p
                borderAlpha.floatValue = 1f - p
                // Android's modal drawer opens over the card, so the card grows in place beneath it.
                cardTx.floatValue = if (modal) 0f else target * p
                cardTy.floatValue = 0f
                cardScale.floatValue = 1f
                val startAlpha = if (wasShowingCard) 1f else PRERENDER_ALPHA
                cardAlpha.floatValue = if (modal) 1f else startAlpha + (1f - startAlpha) * p
                scrimAlpha.floatValue = if (modal) 1f else p
                // What leaves goes on `swap`'s exit as the list comes forward on its entrance, on
                // the same frame: the exit is the quicker, so the list never shows through them.
                val stageFade = progress(t, SWAP_OUT)
                catchUpStack.previewAlpha = previewFrom * (1f - stageFade)
                chromeAlpha.floatValue = 1f - stageFade
                chromeScale.floatValue = 1f
                stackAlpha.floatValue = 1f
                stackTy.floatValue = 0f
                if (snapshot != null) {
                    // The two sessions swap through the card's own background: the stack's leaves
                    // quicker than the returning one arrives. Over a modal drawer the leaving card
                    // fades with the stage above it and the returning one is already there.
                    outgoingAlpha.floatValue = 1f - stageFade
                    contentAlpha.floatValue = if (modal) 1f else fadeIn(t)
                } else {
                    outgoingAlpha.floatValue = 0f
                    contentAlpha.floatValue = 1f
                }
            },
            completion = {
                catchUpStack.releasePreview()
                clearCardPose()
                resetDrawerOverrides()
                chromeAlpha.floatValue = 0f
                // Cut short by Catch Up opening again or the drawer becoming a sidebar: that owns
                // the card now, and the drawer never arrived.
                if (catchUp == null && sidebarShown == null) {
                    onArrived(true)
                }
            },
        )
    }

    private val dimMaximum: Float get() = DrawerDim.maximum(isDark)
    internal var isDark = false

    private fun clearCardPose() {
        cardPosed = false
        composerSlice = null
        stageSnapshot = null
        cardMaskStart = null
        cardMaskEnd = null
        outgoing = null
        outgoingRect = null
        outgoingAlpha.floatValue = 0f
        stackOffset.floatValue = 0f
        stackTy.floatValue = 0f
        stackAlpha.floatValue = 1f
        contentAlpha.floatValue = 1f
        scrimAlpha.floatValue = Float.NaN
        cardTx.floatValue = 0f
        cardTy.floatValue = 0f
        cardScale.floatValue = 1f
        cardAlpha.floatValue = 1f
        borderAlpha.floatValue = 0f
    }

    private fun resetDrawerOverrides() {
        drawerAlpha.floatValue = 1f
        drawerScale.floatValue = Float.NaN
        dimAlpha.floatValue = Float.NaN
    }

    private fun runMorph(
        kind: CatchUpModeMorph,
        animated: Boolean,
        duration: Double,
        step: (Double) -> Unit,
        completion: () -> Unit,
    ) {
        morphJob?.cancel()
        morph = kind
        morphStep = step
        morphCompletion = completion
        if (!animated || reducedMotion) {
            finishModeTransition()
            return
        }
        step(0.0)
        morphJob = scope.launch {
            var elapsed = 0.0
            motionFrameLoop(frameDuration) { tick ->
                elapsed += tick.dt
                step(elapsed)
                if (elapsed >= duration) MotionFrameAction.Stop else MotionFrameAction.Continue
            }
            morphJob = null
            landMorph()
        }
    }

    /**
     * Hands ownership from a morph to whatever comes next (a drag, a card's flight, Catch Up again,
     * the sidebar): the morph lands at its end now.
     */
    fun finishModeTransition() {
        if (morph == null) return
        morphJob?.cancel()
        morphJob = null
        morphStep?.invoke(Double.MAX_VALUE)
        landMorph()
    }

    private fun landMorph() {
        val completion = morphCompletion
        morphStep = null
        morphCompletion = null
        morph = null
        completion?.invoke()
    }

    // endregion

    internal fun dispose() {
        finishModeTransition()
        sidebarJob?.cancel()
    }

    private companion object {
        /** A mode change (entering, opening a card, returning) is a full-screen change: `Motion.surface`. */
        val SURFACE: MotionCurve get() = MotionRole.Surface.curve()
        /** What arrives fades in on `swap`'s entrance. */
        val SWAP_IN: MotionCurve get() = MotionRole.Swap.curve(MotionPhase.Enter)
        /** What leaves fades on `swap`'s exit. */
        val SWAP_OUT: MotionCurve get() = MotionRole.Swap.curve(MotionPhase.Exit)
        /** The stack card's composer strip that slides into the session. */
        const val COMPOSER_STRIP_HEIGHT = 68f
        /** The finished session rises from 18 pt below. */
        const val FINISH_RISE = 18f
        /** The stack card's top sits that far into the session. */
        const val CARD_TOP_CLUSTER = 8f

        /**
         * A spring whose envelope has decayed to ~0.1 % at [duration], landing exactly at 1 when it
         * ends (the same model as `CatchUpStackController`'s flights).
         */
        fun durationSpring(t: Double, duration: Double, damping: Double): Float {
            if (t >= duration) return 1f
            val zeta = damping.coerceIn(0.05, 2.0)
            val omega = if (zeta >= 1) 9.23 / max(duration, 0.01) else 7.5 / (zeta * max(duration, 0.01))
            return SpringSolver.evaluate(from = 0.0, to = 1.0, initialVelocity = 0.0, omega = omega, zeta = zeta, elapsed = t).first.toFloat()
        }

        /** How far along [curve] the morph is at [t]: a spring, or its ease. */
        fun progress(t: Double, curve: MotionCurve): Float {
            val fraction = (t / max(curve.duration, 0.01)).coerceIn(0.0, 1.0).toFloat()
            return when (curve) {
                is MotionCurve.Spring -> durationSpring(t, curve.duration, 1.0 - curve.bounce)
                is MotionCurve.EaseOut -> Motion.EaseOut.transform(fraction)
                is MotionCurve.EaseInOut -> Motion.EaseInOut.transform(fraction)
            }
        }

        /** From `prerenderAlpha` to 1 on `Motion.swap`, from the start. */
        fun fadeIn(t: Double): Float {
            val p = progress(t, MotionRole.Swap.curve())
            return PRERENDER_ALPHA + (1f - PRERENDER_ALPHA) * p
        }
    }
}

/** Too faint to see over what covers it, but drawn. */
private const val PRERENDER_ALPHA = 0.02f
