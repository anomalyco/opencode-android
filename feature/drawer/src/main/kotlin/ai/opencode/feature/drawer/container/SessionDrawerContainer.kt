@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ai.opencode.feature.drawer.container

import ai.opencode.core.motion.rememberFrameDurationSeconds
import ai.opencode.core.ui.LocalDrawerKeyboardSignals
import ai.opencode.core.ui.LocalDrawerPanExclusion
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.drawer.R
import ai.opencode.feature.drawer.catchup.CatchUpMetrics
import ai.opencode.feature.drawer.catchup.CatchUpStack
import ai.opencode.feature.drawer.catchup.CatchUpStage
import ai.opencode.feature.drawer.catchup.CatchUpStageProxy
import ai.opencode.feature.drawer.catchup.rememberCatchUpStackController
import ai.opencode.feature.drawer.list.LocalDrawerHidden
import ai.opencode.feature.drawer.motion.DrawerCorner
import ai.opencode.feature.drawer.motion.DrawerDepth
import ai.opencode.feature.drawer.motion.DrawerDim
import ai.opencode.feature.drawer.motion.DrawerKeyboardHold
import ai.opencode.feature.drawer.motion.DrawerMotionTunables
import ai.opencode.feature.drawer.motion.DrawerPanEvent
import ai.opencode.feature.drawer.motion.DrawerPanRegions
import ai.opencode.feature.drawer.motion.DrawerRevealPhase
import ai.opencode.feature.drawer.motion.drawerDirectionalPan
import ai.opencode.feature.drawer.motion.drawerPanCard
import ai.opencode.feature.drawer.motion.drawerPanDrawer
import ai.opencode.feature.drawer.motion.rememberDisplayCornerRadius
import ai.opencode.feature.drawer.motion.rememberDrawerMotionState
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The frame-locked drawer and Catch Up container. The session card recedes over the drawer under
 * the finger and rides `DrawerSpring` when released ([SessionDrawerController.motion]); every
 * per-frame value is snapshot state read in layout/draw blocks, so dragging never recomposes
 * [content] (the session and its pushed subagents) or [drawer]. Home hears only [onArrived] and the
 * commit haptic.
 *
 * During Catch Up the same card, with the same composed session ([content] is movable content),
 * insets into [CatchUpStack] above [chrome], and morphs back out onto the session or the open
 * drawer (`SessionDrawerController.updateCatchUp`). A window at least
 * [SidebarMetrics.minimumWindowWidth] wide shows [drawer] as a sidebar beside the card instead
 * ([sidebarShown] non-null), and only Catch Up's card swipes remain.
 *
 * @param isPresented whether the phone's drawer is open (Home's `isSessionDrawerPresented`);
 *   [onPresentationChanged] writes a drag's or tap's decision back.
 * @param width the drawer's (or sidebar's) width.
 * @param sidebarShown beside a sidebar, whether it shows; `null` slides the phone's drawer.
 * @param catchUp the Catch Up stage, or `null` outside Catch Up.
 * @param onArrived the drawer visually arrived open (`true`) or shut, away from where it last rested.
 * @param onCommitted a release or tap commits to the other side: play `Haptic.snap` now.
 * @param onTouchDown a touch that may move the drawer began: prepare its haptic.
 * @param onReveal a drag on the open drawer toward the card, which reveals the rows' times.
 * @param contentSessionID the session the card shows or is about to show (`null` for a draft): a
 *   drawer that closes on another session than it opened on leaves the keyboard down.
 * @param isContentPushed a subagent or a web page is pushed over the session (its back gesture has the drag).
 * @param isSheetPresented something is presented over Home (Settings): closing never restores
 *   focus, and Back is left to it (the drawer's and Catch Up's back handlers stand down).
 * @param preview the inert card behind the open one in Catch Up.
 */
@Composable
fun SessionDrawerContainer(
    controller: SessionDrawerController,
    isPresented: Boolean,
    onPresentationChanged: (Boolean) -> Unit,
    width: Dp,
    sidebarShown: Boolean?,
    reducedMotion: Boolean,
    catchUp: CatchUpStage?,
    catchUpProxy: CatchUpStageProxy,
    keyboardHold: DrawerKeyboardHold,
    panRegions: DrawerPanRegions,
    content: @Composable () -> Unit,
    drawer: @Composable () -> Unit,
    chrome: @Composable () -> Unit,
    preview: @Composable (sessionID: String, revision: Int) -> Unit,
    modifier: Modifier = Modifier,
    previewRevision: Int = 0,
    onArrived: (Boolean) -> Unit = {},
    onCommitted: () -> Unit = {},
    onTouchDown: () -> Unit = {},
    onReveal: (DrawerRevealPhase) -> Unit = {},
    contentSessionID: String? = null,
    isContentPushed: Boolean = false,
    isSheetPresented: Boolean = false,
) {
    val motion = controller.motion
    val density = LocalDensity.current
    val colors = OpenCodeTheme.colors
    val isDark = colors.isDark
    val focusManager = LocalFocusManager.current
    val displayRadius = rememberDisplayCornerRadius()
    val frameDuration = rememberFrameDurationSeconds()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val safe = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
    val layoutDirection = LocalLayoutDirection.current
    val safeLeft = safe.getLeft(density, layoutDirection).toFloat()
    val safeTop = safe.getTop(density).toFloat()
    val safeRight = safe.getRight(density, layoutDirection).toFloat()
    val safeBottom = safe.getBottom(density).toFloat()

    val currentContent by rememberUpdatedState(content)
    val currentOnReveal by rememberUpdatedState(onReveal)
    val pushed by rememberUpdatedState(isContentPushed)
    val sheet by rememberUpdatedState(isSheetPresented)
    val currentCatchUp by rememberUpdatedState(catchUp)

    // Android only: Back on the shut card opens the session list, as the drawer button does,
    // instead of leaving the app; Back on a list Back opened leaves it. Registered before the card
    // composes, so the session's own handlers (menus, sheets, selection, a pushed subagent) still
    // take Back first. Predictive back pulls the card part of the way open; a button or TalkBack
    // back opens it at once.
    var openedByBack by remember { mutableStateOf(false) }
    LaunchedEffect(motion.isPresented) { if (!motion.isPresented) openedByBack = false }
    PredictiveBackHandler(
        enabled = !isSheetPresented && catchUp == null && sidebarShown == null && !isContentPushed && !motion.isPresented,
    ) { progress ->
        openedByBack = true
        var began = false
        try {
            progress.collect { event ->
                if (!began) {
                    controller.panBegan()
                    motion.beginDrag()
                    began = true
                }
                motion.drag(event.progress * motion.width * DrawerMotionTunables.BACK_PREVIEW_TRAVEL)
            }
            if (began) motion.endBackOpen() else onPresentationChanged(true)
        } catch (cancelled: CancellationException) {
            openedByBack = false
            if (began) motion.cancelDrag()
            throw cancelled
        }
    }

    val cardLayer = rememberGraphicsLayer()
    val chromeLayer = rememberGraphicsLayer()
    // One composed session moves between the full-screen card and Catch Up's stack, so entering and
    // leaving Catch Up never rebuilds it.
    val cardContent = remember(cardLayer, keyboardHold, panRegions) {
        movableContentOf {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        cardLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(cardLayer)
                    }
                    // A card the keyboard is leaving, or drawer search owning it, lays out as if
                    // there were no keyboard (the session's `imePadding` sees it consumed).
                    .then(if (keyboardHold.excludesKeyboard) Modifier.consumeWindowInsets(WindowInsets.ime) else Modifier),
            ) {
                CompositionLocalProvider(
                    LocalDrawerKeyboardSignals provides keyboardHold,
                    LocalDrawerPanExclusion provides panRegions,
                ) {
                    currentContent()
                }
            }
        }
    }

    val morph = controller.morph
    val exitMorph = morph == CatchUpModeMorph.OpenIntoSession ||
        morph == CatchUpModeMorph.FinishIntoSession ||
        morph == CatchUpModeMorph.ReturnToDrawer
    // Catch Up just ended and the morph hasn't started yet (it starts in this pass's effects):
    // the stage stays mounted so the morph can fade it.
    val exiting = catchUp == null && controller.catchUp != null
    val stackStage = catchUp ?: if (exiting || exitMorph) controller.lastStage else null
    val cardInStack = catchUp != null
    val chromeMounted = catchUp != null || exiting || exitMorph
    // Catch Up began this pass; its morph (which fades the drawer out) starts in this pass's effects.
    val entering = catchUp != null && controller.catchUp == null
    val drawerShows = morph == CatchUpModeMorph.EnterCatchUp || morph == CatchUpModeMorph.ReturnToDrawer || entering ||
        (catchUp == null && (motion.isDrawerAvailable || controller.isSidebarComposed))
    // Once shown, the drawer stays composed (hidden while it can't show), so its list keeps its
    // place across visits.
    val drawerEverShown = remember { booleanArrayOf(false) }
    if (drawerShows) drawerEverShown[0] = true
    // Android: composed hidden once the app has settled after launch, so the first open doesn't
    // build every visible row in the frames the drawer starts moving in.
    var drawerPrewarmed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(DRAWER_PREWARM_DELAY_MS)
        drawerPrewarmed = true
    }
    val drawerComposed = drawerShows || drawerEverShown[0] || drawerPrewarmed
    // While it can't show, the composed drawer builds nothing and nothing in it ticks: shut, or a
    // sidebar that's slid away.
    val drawerHidden = rememberUpdatedState(!drawerShows || (sidebarShown != null && !controller.isSidebarVisible))
    // Android: the phone's drawer is a Material modal drawer. It slides in over the session, which
    // stays put under a scrim.
    val modal = sidebarShown == null
    val scrimColor = if (modal) MaterialTheme.colorScheme.scrim.copy(alpha = MODAL_SCRIM_ALPHA) else DrawerScrim.color(isDark, colors.highContrast)
    val closeDrawerDescription = stringResource(R.string.drawer_close_description)
    // A Catch Up morph poses the drawer itself (its scale is set); it isn't following the motion then.
    val drawerPosed by remember(controller) { derivedStateOf { !controller.drawerScale.floatValue.isNaN() } }
    val chromeBackground = if (isDark) Color(0.04f, 0.04f, 0.04f, 1f) else colors.userBubble
    val borderColor = CatchUpMetrics.borderColor(isDark, colors.border)
    val borderWidthPx = if (isDark) with(density) { 1.dp.toPx() } else 1f
    // Where the card's or drawer's input focus goes when the container takes it away.
    // `clearFocus` also drops the view's own focus; out of touch mode (after any key, TalkBack's
    // Back included) the window gives it straight back, and TalkBack follows that input-focus
    // event to the screen's first element (Catch Up's "N Left", the drawer's scrim) after its own
    // focus had landed. Moving the focus to this target instead leaves the view's focus alone.
    val focusSink = remember { FocusRequester() }
    var holdsFocus by remember { mutableStateOf(false) }

    Box(
        modifier
            .fillMaxSize()
            .background(colors.canvas)
            .onSizeChanged { controller.containerSizePx = it }
            .onFocusChanged { holdsFocus = it.hasFocus }
            .drawerDirectionalPan(
                regions = panRegions,
                isDrawerPresented = { motion.isPresented },
                isContentPushed = { pushed },
                // Beside a sidebar the drawer doesn't move; Catch Up's stack runs its own swipe.
                enabled = { controller.sidebarShown == null && controller.catchUp == null },
                onTouchDown = motion::touchDown,
            ) { event ->
                if (!controller.acceptsPan(event)) return@drawerDirectionalPan
                when (event) {
                    is DrawerPanEvent.Began -> if (event.isRevealingTimes) {
                        currentOnReveal(DrawerRevealPhase.Began)
                        currentOnReveal(DrawerRevealPhase.Changed(event.translation))
                    } else {
                        controller.panBegan()
                        motion.beginDrag()
                        motion.drag(event.fingerTranslation)
                    }
                    is DrawerPanEvent.Changed -> if (event.isRevealingTimes) {
                        currentOnReveal(DrawerRevealPhase.Changed(event.translation))
                    } else {
                        motion.drag(event.fingerTranslation)
                    }
                    is DrawerPanEvent.Ended ->
                        if (event.isRevealingTimes) currentOnReveal(DrawerRevealPhase.Ended) else motion.endDrag(event.velocity)
                    is DrawerPanEvent.Cancelled ->
                        if (event.isRevealingTimes) currentOnReveal(DrawerRevealPhase.Ended) else motion.cancelDrag()
                }
            },
    ) {
        Box(Modifier.focusRequester(focusSink).focusTarget())
        // The modal drawer, over the card; or the sidebar, beside it.
        if (drawerComposed) {
            val hiddenFromAccessibility = !drawerShows || catchUp != null ||
                if (sidebarShown != null) !controller.isSidebarVisible else motion.isDrawerHiddenFromAccessibility.value
            Box(
                Modifier
                    .width(width)
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .zIndex(if (modal && drawerShows && (entering || drawerPosed || motion.isPresented || motion.isCardMoving)) 1f else 0f)
                    .drawerPanDrawer(panRegions)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val placed = drawerShows && (!modal || entering || drawerPosed || motion.isPresented || motion.isCardMoving)
                        if (placed) {
                            layout(constraints.maxWidth, constraints.maxHeight) { placeable.placeRelative(0, 0) }
                        } else {
                            layout(0, 0) {}
                        }
                    }
                    .containerPoseLayer { _, densityPx ->
                        val override = controller.drawerScale.floatValue
                        if (!override.isNaN()) {
                            scaleX = override
                            scaleY = override
                            alpha = if (drawerShows) controller.drawerAlpha.floatValue else 0f
                        } else if (modal) {
                            // The drawer's trailing edge rides the finger, as the card's leading edge did.
                            val shown = min(motion.offset, width.value)
                            translationX = (shown - width.value) * densityPx * if (rtl) -1 else 1
                            alpha = if (drawerShows) controller.drawerAlpha.floatValue else 0f
                        } else {
                            val scale = DrawerDepth.scale(motion.progress, motion.reducedMotion || !motion.recedes)
                            scaleX = scale
                            scaleY = scale
                            alpha = if (drawerShows) controller.drawerAlpha.floatValue else 0f
                        }
                    }
                    .then(if (modal) Modifier.clip(ModalDrawerShape) else Modifier)
                    .then(if (hiddenFromAccessibility) Modifier.clearAndMergeSemantics() else Modifier),
            ) {
                CompositionLocalProvider(LocalDrawerHidden provides drawerHidden) { drawer() }
                if (sidebarShown != null) {
                    // The sidebar's edge against the session.
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .width(with(density) { 1f.toDp() })
                            .background(colors.border),
                    )
                }
            }
        }

        // Catch Up's stage: the drift field, progress, Undo and the dispositions.
        if (chromeMounted) {
            val stageSnapshot = controller.stageSnapshot
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(if (modal) 2f else 0f)
                    .containerPoseLayer { _, _ ->
                        alpha = if (entering) 0f else controller.chromeAlpha.floatValue
                        val scale = controller.chromeScale.floatValue
                        scaleX = scale
                        scaleY = scale
                    }
                    .background(chromeBackground),
            ) {
                if (catchUp == null && stageSnapshot != null) {
                    // Leaving: the stage fades as a picture of itself, out of reach of touches.
                    Image(stageSnapshot, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize().clearAndSetSemantics {})
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .drawWithContent {
                                chromeLayer.record { this@drawWithContent.drawContent() }
                                drawLayer(chromeLayer)
                            }
                            .then(if (catchUp == null) Modifier.clearAndMergeSemantics() else Modifier),
                    ) {
                        chrome()
                    }
                }
            }
        }

        if (stackStage != null) {
            val outgoing = controller.outgoing
            val returningToModalDrawer = modal && ((exiting && isPresented) || morph == CatchUpModeMorph.ReturnToDrawer)
            val leavingSnapshot = if (!cardInStack && returningToModalDrawer) {
                outgoing ?: if (exiting && !reducedMotion) controller.pendingCardSnapshot else null
            } else {
                null
            }
            val stackCornerPx = with(density) { CatchUpMetrics.cornerRadius.toPx() }
            CatchUpStack(
                stage = stackStage,
                controller = controller.catchUpStack,
                proxy = catchUpProxy,
                dismissCard = controller::dismissCard,
                undoCard = controller::undoCard,
                previewRevision = previewRevision,
                revealsPreviewOnBegin = false,
                isExiting = !cardInStack,
                panRegions = panRegions,
                modifier = Modifier
                    .zIndex(if (modal) 3f else 0f)
                    .containerPoseLayer { _, densityPx ->
                        translationX = controller.stackOffset.floatValue * densityPx * if (rtl) -1 else 1
                        translationY = controller.stackTy.floatValue * densityPx
                        alpha = if (entering && modal) 0f else controller.stackAlpha.floatValue
                    },
                leavingCard = if (leavingSnapshot != null) {
                    {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clearAndMergeSemantics()
                                .containerPoseLayer { size, _ ->
                                    alpha = if (exiting) 1f else controller.outgoingAlpha.floatValue
                                    setRoundRectOutline(Offset.Zero, size, stackCornerPx)
                                    clip = true
                                },
                        ) {
                            Image(
                                leavingSnapshot,
                                contentDescription = null,
                                contentScale = ContentScale.FillBounds,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .drawBehind {
                                        drawRoundRect(
                                            color = borderColor,
                                            cornerRadius = CornerRadius(stackCornerPx),
                                            style = Stroke(borderWidthPx),
                                        )
                                    },
                            )
                        }
                    }
                } else {
                    null
                },
                card = {
                    if (cardInStack) {
                        Box(Modifier.fillMaxSize()) {
                            cardContent()
                            if (outgoing != null && controller.outgoingRect == null) {
                                Image(
                                    outgoing,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clearAndSetSemantics {}
                                        .graphicsLayer { alpha = controller.outgoingAlpha.floatValue },
                                )
                            }
                        }
                    }
                },
                preview = preview,
            )
        }

        // The session card: slides over the drawer, or sits beside the sidebar.
        if (!cardInStack) {
            val scrimInteractive = motion.isScrimInteractive.value
            val contentHidden = motion.isContentHiddenFromAccessibility.value
            val outgoing = controller.outgoing
            val outgoingRect = controller.outgoingRect
            val openingOrFinishingIntoSession = morph == CatchUpModeMorph.OpenIntoSession || morph == CatchUpModeMorph.FinishIntoSession
            val returningOnModal = modal && ((exiting && isPresented) || morph == CatchUpModeMorph.ReturnToDrawer)
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(if (modal && openingOrFinishingIntoSession) 3f else 0f)
                    .sidebarLeading(controller)
                    .containerPoseLayer { size, densityPx ->
                        if (controller.cardPosed && !returningOnModal) {
                            translationX = controller.cardTx.floatValue * densityPx * if (rtl) -1 else 1
                            translationY = controller.cardTy.floatValue * densityPx
                            val scale = controller.cardScale.floatValue
                            scaleX = scale
                            scaleY = scale
                            alpha = controller.cardAlpha.floatValue
                            val mask = controller.currentMask() ?: Rect(Offset.Zero, size)
                            setRoundRectOutline(mask.topLeft, mask.size, controller.cardRadius.floatValue * densityPx)
                            clip = true
                        } else if (!modal) {
                            val offset = motion.offset
                            translationX = offset * densityPx * if (rtl) -1 else 1
                            val radius = DrawerCorner.radius(motion.isCardMoving, offset, displayRadius.value)
                            if (radius > 0f) {
                                setRoundRectOutline(Offset.Zero, size, radius * densityPx)
                                clip = true
                            }
                        }
                    }
                    .drawerPanCard(panRegions)
                    .background(colors.canvas),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .containerPoseLayer { _, _ -> alpha = controller.contentAlpha.floatValue }
                        .then(if (contentHidden) Modifier.clearAndMergeSemantics() else Modifier)
                        // Consumes nothing; it's here to be inserted with this slot. Catch Up's
                        // stack consumes the system bars around the moved session, and a session
                        // moved back here kept reading those consumed insets (its header under the
                        // status bar, its composer on the gesture bar) because nothing on its new
                        // path changed. A consumed-insets provider arriving above it makes its
                        // inset paddings read again.
                        .consumeWindowInsets(PaddingValues()),
                ) {
                    cardContent()
                }
                controller.composerSlice?.let { slice ->
                    // The stack card's composer strip sliding into the session, over a cover that
                    // hides the live composer until it lands.
                    val canvas = colors.canvas
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clearAndSetSemantics {}
                            .drawBehind {
                                val p = controller.maskProgress.floatValue
                                val clip = lerp(controller.composerSliceStart, controller.composerSliceEnd, p)
                                drawRect(canvas, topLeft = Offset(0f, clip.top), size = Size(size.width, (size.height - clip.top).coerceAtLeast(0f)))
                                val imageHeight = controller.composerSliceImageStartHeight +
                                    (controller.composerSliceImageEndHeight - controller.composerSliceImageStartHeight) * p
                                clipRect(clip.left, clip.top, clip.right, clip.bottom) {
                                    drawImage(
                                        slice,
                                        dstOffset = IntOffset(clip.left.roundToInt(), (clip.bottom - imageHeight).roundToInt()),
                                        dstSize = IntSize(clip.width.roundToInt().coerceAtLeast(1), imageHeight.roundToInt().coerceAtLeast(1)),
                                    )
                                }
                            },
                    )
                }
                if (!returningOnModal && outgoing != null && outgoingRect != null) {
                    Image(
                        outgoing,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .placedAt(outgoingRect)
                            .clip(RoundedCornerShape(CatchUpMetrics.cornerRadius))
                            .clearAndSetSemantics {}
                            .graphicsLayer { alpha = controller.outgoingAlpha.floatValue },
                    )
                }
                // The stack card's border, fading as its frame grows to the card's bounds.
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val alpha = controller.borderAlpha.floatValue
                            if (!controller.cardPosed || returningOnModal || alpha <= 0f) return@drawBehind
                            val mask = controller.currentMask() ?: Rect(Offset.Zero, size)
                            val radius = controller.cardRadius.floatValue.dp.toPx()
                            drawRoundRect(
                                color = borderColor.copy(alpha = borderColor.alpha * alpha),
                                topLeft = mask.topLeft,
                                size = mask.size,
                                cornerRadius = CornerRadius(radius),
                                style = Stroke(borderWidthPx),
                            )
                        },
                )
                // Mutes the card while the drawer is open; a tap on it closes the drawer.
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawBehind {
                            val override = controller.scrimAlpha.floatValue
                            val progress = if (returningOnModal) 1f else if (!override.isNaN()) override else motion.progress
                            if (progress > 0f) drawRect(scrimColor, alpha = scrimColor.alpha * progress)
                        }
                        .then(
                            if (scrimInteractive) {
                                Modifier.pointerInput(controller) { detectTapGestures { controller.closeDrawer() } }
                            } else {
                                Modifier
                            },
                        )
                        .then(
                            if (scrimInteractive && contentHidden) {
                                Modifier.semantics {
                                    contentDescription = closeDrawerDescription
                                    role = Role.Button
                                    onClick { controller.closeDrawer() }
                                }
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }

        // Back: Catch Up exits; an open drawer closes (a pushed subagent's own back sits beneath),
        // unless Back opened it (above): then Back leaves the app.
        // Under Settings neither does: Back closes Settings, not what waits beneath it.
        BackHandler(enabled = !sheet && catchUp != null) { currentCatchUp?.onExit?.invoke() }
        // Android only: predictive back pulls the card part of the way shut with the gesture
        // (DrawerMotionTunables.BACK_PREVIEW_TRAVEL); the commit closes it from there, a cancel
        // springs it open again. A button or TalkBack back closes it at once, as before.
        PredictiveBackHandler(enabled = !sheet && catchUp == null && motion.isPresented && !openedByBack) { progress ->
            var began = false
            try {
                progress.collect { event ->
                    if (!began) {
                        controller.panBegan()
                        motion.beginDrag()
                        began = true
                    }
                    motion.drag(-event.progress * motion.width * DrawerMotionTunables.BACK_PREVIEW_TRAVEL)
                }
                if (began) motion.endBackDrag() else controller.closeDrawer()
            } catch (cancelled: CancellationException) {
                if (began) motion.cancelDrag()
                throw cancelled
            }
        }
    }

    DisposableEffect(controller) { onDispose { controller.dispose() } }

    SideEffect {
        controller.reducedMotion = reducedMotion
        motion.reducedMotion = reducedMotion
        controller.frameDuration = frameDuration
        motion.frameDurationSeconds = frameDuration
        controller.density = density.density
        controller.safeLeftPx = safeLeft
        controller.safeTopPx = safeTop
        controller.safeRightPx = safeRight
        controller.safeBottomPx = safeBottom
        controller.displayCornerRadius = displayRadius.value
        controller.isDark = isDark
        controller.keyboardHold = keyboardHold
        motion.keyboardHold = keyboardHold
        controller.isSheetPresented = { sheet }
        controller.isContentPushed = { pushed }
        // Nothing focused, nothing to take: a clear would only drop and regain the view's focus.
        controller.clearFocus = { if (holdsFocus && !focusSink.requestFocus()) focusManager.clearFocus(force = true) }
        keyboardHold.focusRelease = { focusSink.requestFocus() }
        controller.cardLayer = cardLayer
        controller.chromeLayer = chromeLayer
        controller.onArrived = onArrived
        controller.onCommitted = onCommitted
        controller.onTouchDown = onTouchDown
        controller.onPresentationChanged = onPresentationChanged
        if (controller.drawerWidth != width.value) {
            controller.finishModeTransition()
            controller.drawerWidth = width.value
            motion.updateWidth(width.value, catchingUp = controller.catchUp != null)
            controller.sidebarWidthChanged()
        }
        controller.contentSessionID = contentSessionID
        // Before `setPresented`: leaving Catch Up for the open drawer is the stack's own exit,
        // not an opening to feel.
        controller.updateCatchUp(catchUp, returnsToDrawer = isPresented, animated = !reducedMotion)
        controller.updatePushed(isContentPushed)
        controller.setSidebar(sidebarShown, animated = !reducedMotion)
        controller.setPresented(isPresented, animated = true)
    }
}

/** A [SessionDrawerController] on this composition's frame clock. */
@Composable
fun rememberSessionDrawerController(initiallyPresented: Boolean = false): SessionDrawerController {
    val scope = rememberCoroutineScope()
    val motion = rememberDrawerMotionState(initiallyPresented)
    val stack = rememberCatchUpStackController()
    return remember(scope, motion, stack) { SessionDrawerController(scope, motion, stack) }
}

/** The card's leading edge at the sidebar's (read in layout only, so the toggle never recomposes the card). */
private fun Modifier.sidebarLeading(controller: SessionDrawerController): Modifier = layout { measurable, constraints ->
    val lead = controller.sidebarLeading.value.dp.roundToPx().coerceIn(0, constraints.maxWidth)
    val width = (constraints.maxWidth - lead).coerceAtLeast(0)
    val placeable = measurable.measure(Constraints.fixed(width, constraints.maxHeight))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.placeRelative(lead, 0) }
}

/** Places this element at [rect] (px) in its parent, at that size. */
private fun Modifier.placedAt(rect: Rect): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(
        Constraints.fixed(rect.width.roundToInt().coerceAtLeast(0), rect.height.roundToInt().coerceAtLeast(0)),
    )
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(rect.left.roundToInt(), rect.top.roundToInt()) }
}

/** The mask between its start and end frames at the morph's progress, or `null` for the whole card. */
internal fun SessionDrawerController.currentMask(): Rect? {
    val start = cardMaskStart ?: return null
    val end = cardMaskEnd ?: return null
    return lerp(start, end, maskProgress.floatValue)
}

/**
 * Applies Catch Up enter/exit/return poses in a pure [DrawModifierNode] backed by a
 * [GraphicsContext] [GraphicsLayer] rather than `Modifier.graphicsLayer`, so per-frame
 * `scaleX/Y`, `translationX/Y` and frame-mask changes neither emit `SemanticsProperties.Shape`
 * invalidations nor re-fire descendant `onGloballyPositioned` observers inside hosted screens.
 */
private fun Modifier.containerPoseLayer(block: GraphicsLayer.(size: Size, density: Float) -> Unit): Modifier =
    this then ContainerPoseLayerElement(block)

private data class ContainerPoseLayerElement(
    val block: GraphicsLayer.(size: Size, density: Float) -> Unit,
) : ModifierNodeElement<ContainerPoseLayerNode>() {
    override fun create() = ContainerPoseLayerNode(block)
    override fun update(node: ContainerPoseLayerNode) {
        node.block = block
    }
}

private class ContainerPoseLayerNode(
    var block: GraphicsLayer.(size: Size, density: Float) -> Unit,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private var layer: GraphicsLayer? = null
    private var layerOwner: GraphicsContext? = null

    override fun ContentDrawScope.draw() {
        if (size.width <= 0f || size.height <= 0f) {
            dropLayer()
            return
        }
        val graphics = currentValueOf(LocalGraphicsContext)
        if (layerOwner !== graphics) dropLayer()
        val activeLayer = layer ?: graphics.createGraphicsLayer().also {
            layer = it
            layerOwner = graphics
        }
        activeLayer.alpha = 1f
        activeLayer.scaleX = 1f
        activeLayer.scaleY = 1f
        activeLayer.translationX = 0f
        activeLayer.translationY = 0f
        activeLayer.clip = false
        activeLayer.block(size, density)
        if (activeLayer.alpha <= 0.001f) {
            return
        }
        val identity = activeLayer.alpha >= 0.999f &&
            activeLayer.scaleX == 1f &&
            activeLayer.scaleY == 1f &&
            activeLayer.translationX == 0f &&
            activeLayer.translationY == 0f &&
            !activeLayer.clip
        if (identity) {
            dropLayer()
            drawContent()
            return
        }
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

/**
 * Clears descendant semantics like `clearAndSetSemantics {}` while also setting
 * `shouldMergeDescendantSemantics = true`, so `sendSubtreeChangeAccessibilityEvents` stops at this
 * hidden container instead of dispatching per-descendant null-node `TYPE_WINDOW_CONTENT_CHANGED`
 * events whenever a hidden drawer, stage, preview card or background session card lays out.
 */
internal fun Modifier.clearAndMergeSemantics(): Modifier = this then ClearAndMergeDrawerSemanticsElement

private object ClearAndMergeDrawerSemanticsElement : ModifierNodeElement<ClearAndMergeDrawerSemanticsNode>() {
    override fun create() = ClearAndMergeDrawerSemanticsNode()
    override fun update(node: ClearAndMergeDrawerSemanticsNode) = Unit
    override fun hashCode(): Int = System.identityHashCode(this)
    override fun equals(other: Any?): Boolean = other === this
}

private class ClearAndMergeDrawerSemanticsNode : Modifier.Node(), SemanticsModifierNode {
    override val shouldClearDescendantSemantics: Boolean get() = true
    override val shouldMergeDescendantSemantics: Boolean get() = true
    override fun SemanticsPropertyReceiver.applySemantics() {
        hideFromAccessibility()
    }
}

/** Material's modal drawer scrim (the `scrim` role at 32%). */
private const val MODAL_SCRIM_ALPHA = 0.32f

/** How long after the container first composes the hidden drawer is built (ms). */
private const val DRAWER_PREWARM_DELAY_MS = 1500L

/** Material's modal drawer: its trailing corners round (the large shape, 16 dp). */
private val ModalDrawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
