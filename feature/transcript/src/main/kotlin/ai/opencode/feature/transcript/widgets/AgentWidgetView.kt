package ai.opencode.feature.transcript.widgets

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeSecondaryButton
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeHairline
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.widgets.AgentWidgetMachine.Phase
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeWidgetFailure
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * A `widget` tool call's row: the widget it posted once its state is known (a placeholder until
 * then), run by a controller the list's [AgentWidgetStore] keeps.
 */
@Composable
fun AgentWidgetToolRow(item: OpenCodeTranscriptItem.Tool, modifier: Modifier = Modifier) {
    val call = item.call
    val widget = LocalAgentWidgetStates.current[AgentWidgetAdapter.key(item)]
    if (widget == null) {
        AgentWidgetFrame(Phase.Loading(AgentWidgetMachine.Status.Open), AgentWidgetAdapter.title(call) ?: AgentWidgetAdapter.TOOL, modifier)
    } else {
        AgentWidgetRow(widget, modifier)
    }
}

/** A widget's frame, driven by its controller. */
@Composable
fun AgentWidgetRow(widget: AgentWidgetState, modifier: Modifier = Modifier) {
    if (widget.status == AgentWidgetState.Status.Superseded) {
        SupersededLine(modifier)
        return
    }
    val context = LocalTranscriptRowContext.current
    val store = LocalAgentWidgetStore.current
    val server = context.server
    val sessionID = context.sessionID
    if (store == null || server == null || sessionID == null) {
        AgentWidgetFrame(Phase.Loading(AgentWidgetMachine.Status.Open), widget.label, modifier = modifier)
        return
    }
    val controller = remember(store, server, sessionID, widget.key) { store.controller(widget, server, sessionID, context.directory) }
    LaunchedEffect(controller) { controller.start() }
    LaunchedEffect(controller, widget) { controller.update(widget) }
    val listPaused = LocalAgentWidgetPaused.current
    LaunchedEffect(controller, listPaused) { controller.shownChanged(paused = listPaused) }
    DisposableEffect(controller) { onDispose { controller.shownChanged(inline = false) } }
    if (store.fullscreen?.controller === controller) {
        AgentWidgetFullscreenPlaceholder(controller, modifier)
        return
    }
    val title = widget.label
    AgentWidgetFrame(
        controller.phase,
        title,
        controller = controller,
        modifier = modifier
            .onSizeChanged { controller.inlineHeight = it.height }
            .onVisibilityChanged(minFractionVisible = VISIBLE_FRACTION) { controller.shownChanged(inline = it) },
        onExpand = if (controller.offersFullscreen) ({ store.enterFullscreen(controller, title) }) else null,
    )
}

/**
 * The widget in its frame: a placeholder while the widget template loads, the live view with a failed
 * step's error under it, Sending… while the answer goes, the final view read-only with how it
 * ended, or an error card with Try Again. Without a [controller] nothing in it responds.
 */
@Composable
fun AgentWidgetFrame(
    phase: Phase,
    title: String,
    modifier: Modifier = Modifier,
    controller: AgentWidgetController? = null,
    onExpand: (() -> Unit)? = null,
) {
    val colors = OpenCodeTheme.colors
    val fullscreen = LocalAgentWidgetFullscreen.current
    val keyboard = rememberAgentWidgetKeyboard(controller, enabled = phase is Phase.Ready || phase is Phase.Stepping)
    Box(
        (if (fullscreen) modifier.fillMaxSize() else modifier.fillMaxWidth().openCodeHairline(OpenCodeShape.panel))
            .then(if (keyboard.focused) Modifier.border(1.dp, colors.focusRing, OpenCodeShape.panel) else Modifier)
            .then(keyboard.modifier),
    ) {
        Column(
            Modifier
                .then(if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .padding(if (fullscreen) PaddingValues(FULLSCREEN_PADDING) else PaddingValues(16.dp))
                .semantics { paneTitle = title },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (phase) {
                is Phase.Loading, is Phase.Starting -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = colors.secondaryText)
                        Text(stringResource(R.string.transcript_widget_loading), style = OpenCodeTextStyles.footnote, color = colors.secondaryText)
                    }
                    val starting = phase is Phase.Starting && controller != null && controller.isLiveMode
                    val density = LocalDensity.current.density
                    Box(
                        Modifier.fillMaxWidth().height(96.dp).background(colors.userBubble, OpenCodeShape.card).then(
                            if (starting) {
                                Modifier.onSizeChanged {
                                    controller!!.liveViewWidth = it.width / density
                                    controller.liveViewHeight = it.height / density
                                }
                            } else Modifier,
                        ),
                    )
                    // A live program may wait for a frame before it renders anything.
                    if (starting) LiveFrames(controller!!, density, fullscreen)
                }
                is Phase.Failed -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusLine(SFSymbol.XmarkCircleFill, colors.danger, stringResource(R.string.transcript_widget_failed))
                    SelectionContainer {
                        Text(phase.message, style = OpenCodeTextStyles.footnote.copy(fontFamily = FontFamily.Monospace), color = colors.secondaryText)
                    }
                    if (controller != null && phase.retryable) {
                        Row {
                            val label = if (controller.isLiveMode) R.string.transcript_widget_restart else CoreR.string.common_try_again
                            OpenCodeSecondaryButton(stringResource(label), onClick = controller::retry)
                        }
                    }
                }
                is Phase.Ready, is Phase.Stepping, is Phase.Responding -> {
                    val live = controller != null && (phase is Phase.Ready || phase is Phase.Stepping)
                    val view = phase.view!!
                    val density = LocalDensity.current.density
                    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                    // Full screen the view takes the height: a `fill` canvas grows into it, anything else scrolls.
                    val body = when {
                        !fullscreen -> Modifier
                        AgentWidgetNode.fills(view) -> Modifier.weight(1f)
                        else -> Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    }.then(
                        if (fullscreen && controller != null) Modifier.onGloballyPositioned { controller.fullscreenRoot(it.boundsInWindow(), density, rtl) } else Modifier,
                    )
                AgentWidgetNodeView(
                    view,
                    AgentWidgetScope(controller, live, title),
                    if (controller?.isLiveMode == true) {
                        body.onSizeChanged {
                            controller.liveViewWidth = it.width / density
                            controller.liveViewHeight = it.height / density
                        }
                    } else body,
                )
                if (controller != null && controller.isLiveMode) LiveFrames(controller, density, fullscreen)
                controller?.httpsHosts?.let { hosts ->
                    Text(
                        stringResource(R.string.transcript_widget_network, hosts.joinToString(", ")),
                        style = OpenCodeTextStyles.caption1,
                        color = colors.tertiaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                    val error = (phase as? Phase.Ready)?.error
                    if (error != null) StatusLine(SFSymbol.XmarkCircleFill, colors.danger, error, live = true)
                    if (phase is Phase.Responding) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = colors.secondaryText)
                            Text(stringResource(R.string.transcript_widget_sending), style = OpenCodeTextStyles.footnote, color = colors.secondaryText)
                        }
                    }
                }
                is Phase.Closed -> {
                    phase.view?.let { AgentWidgetNodeView(it, AgentWidgetScope(controller, live = false, title)) }
                    when (val how = phase.how) {
                        is AgentWidgetMachine.Closed.Submitted -> {
                            val summary = remember(how.json) { AgentWidgetStep.summary(how.json) }
                            StatusLine(SFSymbol.Checkmark, colors.diffAddedText, stringResource(R.string.transcript_widget_submitted), summary.ifEmpty { null })
                        }
                        AgentWidgetMachine.Closed.Dismissed ->
                            StatusLine(SFSymbol.Xmark, colors.tertiaryText, stringResource(R.string.transcript_widget_dismissed))
                    }
                }
            }
            val failure = controller?.failure
            if (controller != null && failure != null && phase !is Phase.Closed) FailureNote(failure, told = failure.sent || controller.askedAgent, onAsk = controller::askAgentToFix)
            controller?.devNotice?.let { notice ->
                // Dev mode only: the last client error.
                Text("Dev: $notice", style = OpenCodeTextStyles.caption1.copy(fontFamily = FontFamily.Monospace), color = colors.tertiaryText, maxLines = 3)
            }
        }
        if (keyboard.focused) KeyboardHint(Modifier.align(Alignment.BottomEnd).padding(6.dp))
        if (onExpand != null) {
            OpenCodeIconButton(
                SFSymbol.ArrowUpLeftAndArrowDownRight,
                stringResource(R.string.transcript_widget_fullscreen),
                onExpand,
                Modifier.align(Alignment.TopEnd).padding(4.dp),
                style = OpenCodeIconButtonStyle.Circle,
                size = 32.dp,
                iconSize = 16.dp,
            )
        }
    }
}

/** A program error the server recorded for this widget, and whether the agent heard of it or the user can ask. */
@Composable
private fun FailureNote(failure: OpenCodeWidgetFailure, told: Boolean, onAsk: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    val error = failure.error
    val at = error.line?.let { line -> error.column?.let { "$line:$it" } ?: "$line" }
    val text = if (at != null) {
        stringResource(R.string.transcript_widget_failed_on_at, failure.client, error.phase, error.message, at)
    } else {
        stringResource(R.string.transcript_widget_failed_on, failure.client, error.phase, error.message)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectionContainer {
            Text(text, style = OpenCodeTextStyles.footnote.copy(fontFamily = FontFamily.Monospace), color = colors.danger)
        }
        if (told) {
            Text(stringResource(R.string.transcript_widget_agent_told), style = OpenCodeTextStyles.footnote, color = colors.secondaryText)
        } else {
            Row { OpenCodeSecondaryButton(stringResource(R.string.transcript_widget_ask_agent), onClick = onAsk) }
        }
    }
}

/** A widget a newer one replaced (it follows in the timeline). */
@Composable
private fun SupersededLine(modifier: Modifier) {
    val colors = OpenCodeTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OpenCodeIcon(SFSymbol.ArrowDown, contentDescription = null, size = 14.dp, tint = colors.tertiaryText)
        Text(stringResource(R.string.transcript_widget_superseded), style = OpenCodeTextStyles.footnote, color = colors.secondaryText)
    }
}

/** The widget has the keyboard: a keyboard and "Esc", which gives it back. */
@Composable
private fun KeyboardHint(modifier: Modifier) {
    val colors = OpenCodeTheme.colors
    val description = stringResource(R.string.transcript_widget_keyboard_description)
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        OpenCodeIcon(SFSymbol.Keyboard, null, size = 12.dp, tint = colors.tertiaryText)
        Text(stringResource(R.string.transcript_widget_keyboard_hint), style = OpenCodeTextStyles.caption2, color = colors.tertiaryText)
    }
}

/**
 * Hands a live program each display frame it asks for, at the next vsync while the widget is shown: a Choreographer
 * callback per request, outside Compose's frame clock, so a frame wakes no recomposer and writes no state.
 */
@Composable
private fun LiveFrames(controller: AgentWidgetController, density: Float, fullscreen: Boolean) {
    val dark = OpenCodeTheme.colors.canvas.luminance() < 0.5f
    DisposableEffect(controller, density, fullscreen, dark) {
        val choreographer = Choreographer.getInstance()
        val handler = Handler(Looper.getMainLooper())
        var posted = false
        var frameNanos = 0L
        // Delivered once the frame is drawn: at the vsync's start the delivery waits on the run (about 3x longer).
        val deliver = Runnable { controller.deliverLiveFrame(frameNanos, density, dark, fullscreen) }
        val callback = Choreographer.FrameCallback { nanos ->
            posted = false
            frameNanos = nanos
            handler.post(deliver)
        }
        val wanted = {
            if (!posted) {
                posted = true
                choreographer.postFrameCallback(callback)
            }
        }
        controller.onLiveFrameWanted = wanted
        if (controller.liveWantsFrame) wanted()
        onDispose {
            choreographer.removeFrameCallback(callback)
            handler.removeCallbacks(deliver)
            if (controller.onLiveFrameWanted === wanted) controller.onLiveFrameWanted = null
        }
    }
}

@Composable
private fun StatusLine(symbol: SFSymbol, tint: Color, words: String, detail: String? = null, live: Boolean = false, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    Row(
        modifier.fillMaxWidth().then(if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OpenCodeIcon(symbol, contentDescription = null, size = 14.dp, tint = tint)
        Text(words, style = OpenCodeTextStyles.footnote, color = colors.text, maxLines = if (detail == null) 4 else 1)
        if (detail != null) {
            Text(detail, Modifier.weight(1f), style = OpenCodeTextStyles.footnote, color = colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The quiet line a widget's answer leaves in the transcript: `◈ Responded to "…"`. */
@Composable
fun AgentWidgetResponseLine(text: String, modifier: Modifier = Modifier) {
    val colors = OpenCodeTheme.colors
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("◈", style = OpenCodeTextStyles.footnote, color = colors.tertiaryText)
        Text(text, style = OpenCodeTextStyles.footnote, color = colors.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

internal val WidgetCardRadius = OpenCodeRadius.card
