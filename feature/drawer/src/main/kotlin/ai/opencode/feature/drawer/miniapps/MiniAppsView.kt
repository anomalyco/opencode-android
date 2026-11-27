package ai.opencode.feature.drawer.miniapps

import ai.opencode.core.R as CoreR
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticEffect
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeContentUnavailable
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.OpenCodeTopBar
import ai.opencode.core.ui.OpenCodeTopBarBackButton
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.drawer.R
import ai.opencode.feature.drawer.miniapps.MiniAppsFeature.Action
import ai.opencode.feature.drawer.miniapps.MiniAppsFeature.Page
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.feature.transcript.TranscriptDisclosureState
import ai.opencode.feature.transcript.TranscriptRowContext
import ai.opencode.feature.transcript.widgets.AgentWidgetAdapter
import ai.opencode.feature.transcript.widgets.AgentWidgetFullscreenHost
import ai.opencode.feature.transcript.widgets.AgentWidgetInstances
import ai.opencode.feature.transcript.widgets.AgentWidgetRow
import ai.opencode.feature.transcript.widgets.AgentWidgetStore
import ai.opencode.feature.transcript.widgets.LocalAgentWidgetPaused
import ai.opencode.feature.transcript.widgets.LocalAgentWidgetStore
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeWidgetInstance
import ai.opencode.server.model.OpenCodeWidgetStatus
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.animateIntSizeAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.roundToInt

/**
 * Mini Apps: the dashboard of widgets kept out of their sessions, and All Widgets to add them from. Widgets run as
 * in their transcript (the same rows and controller), each with its own session and directory, so what they send
 * goes to that session.
 */
@Composable
fun MiniAppsView(store: StoreOf<MiniAppsFeature.State, Action>, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state by store.observe { it }
    LaunchedEffect(store) { store.send(Action.Task) }
    BackHandler(enabled = state.isEditing) { store.send(Action.DoneTapped) }
    val widgets = remember { AgentWidgetStore() }
    DisposableEffect(widgets) { onDispose { widgets.close() } }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val colors = OpenCodeTheme.colors

    CompositionLocalProvider(
        LocalAgentWidgetStore provides widgets,
        LocalAgentWidgetPaused provides !lifecycle.isAtLeast(Lifecycle.State.RESUMED),
    ) {
        AgentWidgetFullscreenHost(widgets)
        Column(modifier.fillMaxSize().background(colors.canvas)) {
            OpenCodeTopBar(
                title = { Text(stringResource(R.string.drawer_mini_apps_title)) },
                navigation = { OpenCodeTopBarBackButton(onClick = onClose) },
                actions = {
                    if (state.page == Page.Dashboard && state.tiles.isNotEmpty()) {
                        if (state.isEditing) {
                            TextButton(onClick = { store.send(Action.DoneTapped) }) { Text(stringResource(CoreR.string.common_done)) }
                        } else {
                            TextButton(onClick = { store.send(Action.EditTapped) }) { Text(stringResource(CoreR.string.common_edit)) }
                        }
                    }
                },
            )
            if (!state.isEditing) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Page.entries.forEachIndexed { index, page ->
                        SegmentedButton(
                            selected = state.page == page,
                            onClick = { store.send(Action.PageSelected(page)) },
                            shape = SegmentedButtonDefaults.itemShape(index, Page.entries.size),
                            icon = {},
                        ) {
                            Text(stringResource(if (page == Page.Dashboard) R.string.drawer_mini_apps_dashboard else R.string.drawer_mini_apps_all_widgets), maxLines = 1)
                        }
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    !state.hasLoaded && state.loadFailed -> OpenCodeContentUnavailable(
                        SFSymbol.SquareGrid2x2,
                        stringResource(R.string.drawer_mini_apps_load_failed),
                        Modifier.fillMaxSize(),
                        action = stringResource(CoreR.string.common_try_again),
                        onAction = { store.send(Action.RetryTapped) },
                    )
                    !state.hasLoaded -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), strokeWidth = 2.dp)
                    state.page == Page.AllWidgets -> AllWidgets(state, store::send)
                    state.tiles.isEmpty() -> OpenCodeContentUnavailable(
                        SFSymbol.SquareGrid2x2,
                        stringResource(R.string.drawer_mini_apps_empty_title),
                        Modifier.fillMaxSize(),
                        description = stringResource(R.string.drawer_mini_apps_empty_message),
                        action = stringResource(R.string.drawer_mini_apps_all_widgets),
                        onAction = { store.send(Action.PageSelected(Page.AllWidgets)) },
                    )
                    else -> Dashboard(state.server, state.tiles, state.instances, state.isEditing, store::send)
                }
            }
        }
    }
}

/** Every widget of the server, last updated first, each live with its add/remove toggle. */
@Composable
private fun AllWidgets(state: MiniAppsFeature.State, send: (Action) -> Unit) {
    val all = state.instances.all
    if (all.isEmpty()) {
        OpenCodeContentUnavailable(
            SFSymbol.SquareGrid2x2,
            stringResource(R.string.drawer_mini_apps_no_widgets_title),
            Modifier.fillMaxSize(),
            description = stringResource(R.string.drawer_mini_apps_no_widgets_message),
        )
        return
    }
    val onDashboard = state.dashboardKeys
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        items(all, key = { it.key }) { instance ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InstanceHeader(instance, instance.key in onDashboard, onToggle = { send(Action.DashboardToggled(instance.key)) })
                MiniAppWidget(state.server, instance)
            }
        }
        item { Box(Modifier.navigationBarsPadding().height(16.dp)) }
    }
}

@Composable
private fun InstanceHeader(instance: OpenCodeWidgetInstance, added: Boolean, onToggle: () -> Unit) {
    val colors = OpenCodeTheme.colors
    val state = instance.state
    val status = stringResource(
        when {
            state.failure != null -> R.string.drawer_mini_apps_status_failed
            state.status == OpenCodeWidgetStatus.Submitted -> R.string.drawer_mini_apps_status_submitted
            state.status == OpenCodeWidgetStatus.Dismissed -> R.string.drawer_mini_apps_status_dismissed
            else -> R.string.drawer_mini_apps_status_open
        },
    )
    val updated = DateUtils.getRelativeTimeSpanString(instance.updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                state.title?.trim()?.ifEmpty { null } ?: state.widgetTemplate.name,
                style = OpenCodeTextStyles.headline,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.drawer_mini_apps_details, "${state.widgetTemplate.name}@${state.widgetTemplate.version.take(7)}", updated, status),
                style = OpenCodeTextStyles.footnote,
                color = if (state.failure != null) colors.danger else colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            instance.sessionTitle?.let {
                Text(it, style = OpenCodeTextStyles.footnote, color = colors.tertiaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(onClick = onToggle, shape = OpenCodeShape.control) {
            if (added) OpenCodeIcon(SFSymbol.Checkmark, contentDescription = null, size = 14.dp)
            Text(
                stringResource(if (added) R.string.drawer_mini_apps_on_dashboard else R.string.drawer_mini_apps_add),
                modifier = Modifier.padding(start = if (added) 4.dp else 0.dp),
            )
        }
    }
}

/** The widget as its transcript row shows it, run for its own session and directory. */
@Composable
private fun MiniAppWidget(server: OpenCodeServer, instance: OpenCodeWidgetInstance, modifier: Modifier = Modifier) {
    val disclosure = remember { TranscriptDisclosureState() }
    val context = remember(server, instance.state.sessionID, instance.directory) {
        TranscriptRowContext(server = server, sessionID = instance.state.sessionID, directory = instance.directory, disclosure = disclosure, send = {})
    }
    val widget = remember(instance.state, instance.data) { AgentWidgetAdapter.state(instance.state, instance.data) }
    CompositionLocalProvider(LocalTranscriptRowContext provides context) {
        AgentWidgetRow(widget, modifier)
    }
}

/** The grid's measures in px: a column's step, a row's step and the gap between cells. */
private class GridMetrics(val stepX: Float, val stepY: Float, val gap: Float) {
    fun offset(tile: MiniAppsTile) = IntOffset((tile.x * stepX).roundToInt(), (tile.y * stepY).roundToInt())
    fun size(tile: MiniAppsTile) = IntSize((tile.w * stepX - gap).roundToInt(), (tile.h * stepY - gap).roundToInt())
}

/**
 * A tile being moved or resized: the layout when it started, how far the finger went, and the layout the grid makes
 * of where it is now ([MiniAppsGrid.moving]). Per-frame; only the dropped layout reaches the reducer.
 */
@Stable
private class TileDrag {
    enum class Mode { Move, Resize }

    var key: String? by mutableStateOf(null)
        private set
    var mode by mutableStateOf(Mode.Move)
        private set
    var delta by mutableStateOf(Offset.Zero)
        private set
    var preview: List<MiniAppsTile>? by mutableStateOf(null)
        private set
    private var base: List<MiniAppsTile> = emptyList()
    private var start: MiniAppsTile? = null

    fun begin(key: String, mode: Mode, tiles: List<MiniAppsTile>) {
        base = tiles
        start = tiles.firstOrNull { it.key == key } ?: return
        this.key = key
        this.mode = mode
        delta = Offset.Zero
        preview = tiles
    }

    fun drag(by: Offset, metrics: GridMetrics) {
        val start = start ?: return
        delta += by
        val target = when (mode) {
            Mode.Move -> start.copy(x = (start.x + delta.x / metrics.stepX).roundToInt(), y = (start.y + delta.y / metrics.stepY).roundToInt())
            Mode.Resize -> start.copy(w = (start.w + delta.x / metrics.stepX).roundToInt(), h = (start.h + delta.y / metrics.stepY).roundToInt())
        }
        val next = MiniAppsGrid.moving(base, start.key, target)
        if (next != preview) preview = next
    }

    /** The layout to keep, fallen into place; null when nothing moved. */
    fun end(): List<MiniAppsTile>? {
        val result = preview?.let { MiniAppsGrid.compacted(it) }?.takeIf { it != base }
        key = null
        start = null
        preview = null
        delta = Offset.Zero
        return result
    }
}

@Composable
private fun Dashboard(
    server: OpenCodeServer,
    tiles: List<MiniAppsTile>,
    instances: AgentWidgetInstances,
    editing: Boolean,
    send: (Action) -> Unit,
) {
    val density = LocalDensity.current
    val colors = OpenCodeTheme.colors
    val drag = remember { TileDrag() }
    val padding = 12.dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = 8.dp
        val rowHeight = 72.dp
        val cellWidth = (maxWidth - padding * 2 - gap * (MiniAppsGrid.COLUMNS - 1)) / MiniAppsGrid.COLUMNS
        val metrics = with(density) { GridMetrics((cellWidth + gap).toPx(), (rowHeight + gap).toPx(), gap.toPx()) }
        val layout = drag.preview ?: tiles
        // Room below the tiles to drag into.
        val rows = MiniAppsGrid.height(layout) + if (editing) MiniAppsGrid.DEFAULT_H else 0
        HapticEffect(Haptic.snap, drag.preview) { old, new -> old != null && new != null }
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState(), enabled = drag.key == null)) {
            Box(
                Modifier
                    .padding(padding)
                    .navigationBarsPadding()
                    .fillMaxWidth()
                    .height(rowHeight * rows + gap * (rows - 1).coerceAtLeast(0)),
            ) {
                // Where the dragged tile lands.
                drag.key?.let { key -> layout.firstOrNull { it.key == key } }?.let { slot ->
                    val size = metrics.size(slot)
                    Box(
                        Modifier
                            .offset { metrics.offset(slot) }
                            .size(with(density) { size.width.toDp() }, with(density) { size.height.toDp() })
                            .background(colors.focusRing.copy(alpha = 0.12f), OpenCodeShape.panel)
                            .border(1.dp, colors.focusRing.copy(alpha = 0.5f), OpenCodeShape.panel),
                    )
                }
                for (tile in layout) key(tile.key) {
                    DashboardTile(server, tile, instances[tile.key], editing, drag, metrics, layout, send)
                }
            }
        }
    }
}

@Composable
private fun DashboardTile(
    server: OpenCodeServer,
    tile: MiniAppsTile,
    instance: OpenCodeWidgetInstance?,
    editing: Boolean,
    drag: TileDrag,
    metrics: GridMetrics,
    layout: List<MiniAppsTile>,
    send: (Action) -> Unit,
) {
    val density = LocalDensity.current
    val colors = OpenCodeTheme.colors
    val reduceMotion = rememberReduceMotion()
    val dragged = drag.key == tile.key
    val slotOffset = metrics.offset(tile)
    val slotSize = metrics.size(tile)
    val start = remember(dragged) { slotOffset to slotSize }
    // The dragged tile follows the finger; the others move to their new places.
    val targetOffset = if (dragged && drag.mode == TileDrag.Mode.Move) {
        start.first + IntOffset(drag.delta.x.roundToInt(), drag.delta.y.roundToInt())
    } else {
        slotOffset
    }
    val minSize = metrics.size(MiniAppsTile(tile.key, 0, 0, MiniAppsGrid.MIN_W, MiniAppsGrid.MIN_H))
    val targetSize = if (dragged && drag.mode == TileDrag.Mode.Resize) {
        IntSize(
            (start.second.width + drag.delta.x.roundToInt()).coerceIn(minSize.width, metrics.size(tile.copy(w = MiniAppsGrid.COLUMNS - tile.x)).width),
            (start.second.height + drag.delta.y.roundToInt()).coerceAtLeast(minSize.height),
        )
    } else {
        slotSize
    }
    val offset by animateIntOffsetAsState(
        targetOffset,
        if (dragged) snap() else MotionRole.Layout.animation(reduceMotion = reduceMotion, visibilityThreshold = IntOffset(1, 1)),
        label = "tile-offset",
    )
    val size by animateIntSizeAsState(
        targetSize,
        if (dragged) snap() else MotionRole.Layout.animation(reduceMotion = reduceMotion, visibilityThreshold = IntSize(1, 1)),
        label = "tile-size",
    )
    val title = instance?.state?.let { it.title?.trim()?.ifEmpty { null } ?: it.widgetTemplate.name } ?: ""
    Box(
        Modifier
            .offset { offset }
            .size(with(density) { size.width.toDp() }, with(density) { size.height.toDp() })
            .zIndex(if (dragged) 1f else 0f)
            .clip(OpenCodeShape.panel)
            .background(colors.canvas),
    ) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (instance != null) {
                MiniAppWidget(server, instance)
            } else {
                Text(
                    stringResource(R.string.drawer_mini_apps_missing),
                    Modifier.padding(16.dp),
                    style = OpenCodeTextStyles.footnote,
                    color = colors.secondaryText,
                )
            }
        }
        if (editing) {
            val currentLayout by androidx.compose.runtime.rememberUpdatedState(layout)
            val currentMetrics by androidx.compose.runtime.rememberUpdatedState(metrics)
            fun gestures(mode: TileDrag.Mode) = Modifier.pointerInput(tile.key, mode) {
                detectDragGestures(
                    onDragStart = { drag.begin(tile.key, mode, currentLayout) },
                    onDrag = { change, amount ->
                        change.consume()
                        drag.drag(amount, currentMetrics)
                    },
                    onDragEnd = { drag.end()?.let { send(Action.LayoutDropped(it)) } },
                    onDragCancel = { drag.end() },
                )
            }
            // In edit mode the whole tile is the handle; the widget takes no input.
            Box(
                Modifier
                    .matchParentSize()
                    .background(colors.text.copy(alpha = if (dragged) 0.06f else 0.03f))
                    .border(1.dp, colors.focusRing.copy(alpha = if (dragged) 1f else 0.6f), OpenCodeShape.panel)
                    .then(gestures(TileDrag.Mode.Move)),
            )
            OpenCodeIconButton(
                SFSymbol.Xmark,
                stringResource(R.string.drawer_mini_apps_remove, title),
                onClick = { send(Action.RemoveTapped(tile.key)) },
                modifier = Modifier.align(Alignment.TopStart).padding(2.dp),
                size = 32.dp,
                iconSize = 14.dp,
            )
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(ResizeHandle)
                    .then(gestures(TileDrag.Mode.Resize)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(28.dp).background(colors.focusRing, OpenCodeShape.control),
                    contentAlignment = Alignment.Center,
                ) {
                    OpenCodeIcon(SFSymbol.ArrowUpLeftAndArrowDownRight, stringResource(R.string.drawer_mini_apps_resize, title), size = 16.dp, tint = colors.canvas)
                }
            }
        }
    }
}

private val ResizeHandle: Dp = 48.dp
