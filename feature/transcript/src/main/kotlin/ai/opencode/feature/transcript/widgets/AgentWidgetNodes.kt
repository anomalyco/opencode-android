package ai.opencode.feature.transcript.widgets

import ai.opencode.core.ui.OpenCodeButtonRole
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeSecondaryButton
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTextField
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.openCodeHairline
import ai.opencode.core.ui.OpenCodeMinTapTarget
import ai.opencode.core.ui.drawerPanHorizontalScroller
import ai.opencode.core.ui.excludedFromDrawerPan
import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.markdown.rememberMarkdownBlocks
import ai.opencode.feature.transcript.markdown.ui.MarkdownBlocksView
import ai.opencode.feature.transcript.markdown.ui.MarkdownTableGrid
import ai.opencode.feature.transcript.markdown.ui.currentMarkdownStyle
import ai.opencode.feature.transcript.widgets.AgentWidgetNode.Align
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt

/** What a node's build reads besides the node: where its events go, and whether it takes input now. */
@Immutable
class AgentWidgetScope(val controller: AgentWidgetController?, val live: Boolean, val title: String) {
    fun send(event: AgentWidgetEvent) {
        if (live) controller?.send(event)
    }
}

/** Points for a gap unit (the contract's 0–3; GUI: 0/4/8/12). Absent, 8. */
internal fun gap(units: Int?): Dp = when (units) {
    0 -> 0.dp
    1 -> 4.dp
    null, 2 -> 8.dp
    else -> 12.dp
}

@Composable
fun AgentWidgetNodeView(node: AgentWidgetNode, scope: AgentWidgetScope, modifier: Modifier = Modifier, path: String = "w") {
    when (node) {
        is AgentWidgetNode.Column -> ColumnNode(node, scope, modifier, path)
        is AgentWidgetNode.Row -> RowNode(node, scope, modifier, path)
        is AgentWidgetNode.Text -> TextNode(node, modifier)
        is AgentWidgetNode.Markdown -> {
            val blocks = rememberMarkdownBlocks("widget-$path-${scope.controller?.key}", node.text)
            MarkdownBlocksView(blocks, modifier.fillMaxWidth())
        }
        is AgentWidgetNode.Image -> AgentWidgetImage(node.src, node.alt, node.aspect, modifier)
        is AgentWidgetNode.Carousel -> CarouselNode(node, scope, modifier, path)
        is AgentWidgetNode.Card -> CardNode(node, scope, modifier, path)
        is AgentWidgetNode.Button -> ButtonNode(node, scope, modifier)
        is AgentWidgetNode.Checkbox -> CheckboxNode(node, scope, modifier)
        is AgentWidgetNode.Slider -> SliderNode(node, scope, modifier)
        is AgentWidgetNode.Select -> SelectNode(node, scope, modifier)
        is AgentWidgetNode.Input -> InputNode(node, scope, modifier)
        AgentWidgetNode.Divider -> HorizontalDivider(modifier, thickness = 1.dp, color = OpenCodeTheme.colors.border)
        is AgentWidgetNode.Table -> TableNode(node, scope, modifier)
        is AgentWidgetNode.Stack -> AgentWidgetStack(node, scope, modifier, path)
        is AgentWidgetNode.Canvas -> when {
            node.shader -> AgentWidgetGPUCanvas(node, scope, modifier)
            node.gpu -> AgentWidgetLiveGPUCanvas(node, scope, modifier)
            else -> AgentWidgetCanvas(node, scope, modifier)
        }
    }
}

@Composable
private fun ColumnNode(node: AgentWidgetNode.Column, scope: AgentWidgetScope, modifier: Modifier, path: String) {
    val alignment = when (node.align) {
        Align.Center -> Alignment.CenterHorizontally
        Align.End -> Alignment.End
        else -> Alignment.Start
    }
    val child = if (node.align == Align.Stretch) Modifier.fillMaxWidth() else Modifier
    // Full screen, the branch holding a `fill` canvas takes the height left.
    val fills = LocalAgentWidgetFullscreen.current && LocalAgentWidgetGrows.current && AgentWidgetNode.fills(node)
    Column(if (fills) modifier.fillMaxHeight() else modifier, verticalArrangement = Arrangement.spacedBy(gap(node.gap)), horizontalAlignment = alignment) {
        node.children.forEachIndexed { index, it ->
            AgentWidgetNodeView(it, scope, if (fills && AgentWidgetNode.fills(it)) child.weight(1f) else child, "$path.$index")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RowNode(node: AgentWidgetNode.Row, scope: AgentWidgetScope, modifier: Modifier, path: String) {
    val spacing = gap(node.gap)
    val vertical = when (node.align) {
        Align.Center -> Alignment.CenterVertically
        Align.End -> Alignment.Bottom
        else -> Alignment.Top
    }
    // Cards side by side don't fit a phone: they scroll sideways at a readable width.
    if (node.children.any { it is AgentWidgetNode.Card }) {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val width = minOf(260.dp, maxWidth * 0.8f)
            val scroll = rememberScrollState()
            Row(
                Modifier.fillMaxWidth().drawerPanHorizontalScroller(scroll).horizontalScroll(scroll),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalAlignment = vertical,
            ) {
                node.children.forEachIndexed { index, it ->
                    AgentWidgetNodeView(it, scope, if (it is AgentWidgetNode.Card) Modifier.width(width) else Modifier, "$path.$index")
                }
            }
        }
        return
    }
    if (node.wrap || node.children.count { it is AgentWidgetNode.Button } > 1) {
        FlowRow(
            modifier,
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
            itemVerticalAlignment = vertical,
        ) {
            node.children.forEachIndexed { index, it -> AgentWidgetNodeView(it, scope, Modifier, "$path.$index") }
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = vertical) {
            node.children.forEachIndexed { index, it ->
                val grows = it is AgentWidgetNode.Input || it is AgentWidgetNode.Slider || it is AgentWidgetNode.Select ||
                    it is AgentWidgetNode.Column || it is AgentWidgetNode.Canvas || it is AgentWidgetNode.Stack ||
                    it is AgentWidgetNode.Table
                AgentWidgetNodeView(it, scope, if (grows) Modifier.weight(1f) else Modifier, "$path.$index")
            }
        }
    }
}

@Composable
internal fun toneColor(tone: AgentWidgetNode.Tone): Color {
    val colors = OpenCodeTheme.colors
    return when (tone) {
        AgentWidgetNode.Tone.Default -> colors.text
        AgentWidgetNode.Tone.Muted -> colors.secondaryText
        AgentWidgetNode.Tone.Accent -> colors.accent
        AgentWidgetNode.Tone.Success -> colors.diffAddedText
        AgentWidgetNode.Tone.Warning -> colors.warning
        AgentWidgetNode.Tone.Error -> colors.danger
    }
}

@Composable
private fun TextNode(node: AgentWidgetNode.Text, modifier: Modifier) {
    val colors = OpenCodeTheme.colors
    val style = when (node.style) {
        AgentWidgetNode.TextStyle.Title -> OpenCodeTextStyles.headline
        AgentWidgetNode.TextStyle.Body -> OpenCodeTextStyles.callout
        AgentWidgetNode.TextStyle.Caption -> OpenCodeTextStyles.footnote
        AgentWidgetNode.TextStyle.Code -> OpenCodeTextStyles.subheadline.copy(fontFamily = FontFamily.Monospace)
    }
    val color = if (node.tone == AgentWidgetNode.Tone.Default && node.style == AgentWidgetNode.TextStyle.Title) colors.strongText
    else if (node.tone == AgentWidgetNode.Tone.Default && node.style == AgentWidgetNode.TextStyle.Caption) colors.secondaryText
    else toneColor(node.tone)
    Text(node.text, modifier, style = style, color = color)
}

@Composable
private fun CarouselNode(node: AgentWidgetNode.Carousel, scope: AgentWidgetScope, modifier: Modifier, path: String) {
    val count = node.children.size
    if (count == 0) return
    val pager = rememberPagerState(initialPage = (node.index ?: 0).coerceIn(0, count - 1)) { count }
    // The widget template's index moves the pager; a swipe tells the widget template (with an id).
    LaunchedEffect(node.index) {
        val index = node.index ?: return@LaunchedEffect
        if (index in 0 until count && index != pager.currentPage) pager.scrollToPage(index)
    }
    val id = node.id
    if (id != null) {
        LaunchedEffect(pager, id) {
            snapshotFlow { pager.settledPage }.drop(1).collect { page -> if (page != node.index) scope.send(AgentWidgetEvent.change(id, page)) }
        }
    }
    val colors = OpenCodeTheme.colors
    val pageLabel = stringResource(R.string.transcript_widget_page, pager.currentPage + 1, count)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(
            pager,
            Modifier
                .fillMaxWidth()
                .drawerPanHorizontalScroller({ pager.canScrollBackward }, { pager.canScrollForward })
                .clip(OpenCodeShape.card)
                .semantics { stateDescription = pageLabel },
            pageSpacing = 8.dp,
            beyondViewportPageCount = 0,
        ) { page -> AgentWidgetNodeView(node.children[page], scope, Modifier.fillMaxWidth(), "$path.$page") }
        if (count > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(count) { index ->
                    val on = index == pager.currentPage
                    Box(Modifier.size(if (on) 7.dp else 6.dp).background(if (on) colors.text else colors.tertiaryText.copy(alpha = 0.5f), CircleShape))
                }
            }
        }
    }
}

@Composable
private fun CardNode(node: AgentWidgetNode.Card, scope: AgentWidgetScope, modifier: Modifier, path: String) {
    val colors = OpenCodeTheme.colors
    val shape = OpenCodeShape.card
    val id = node.id
    val pressable = id != null && scope.live
    Column(
        modifier
            .clip(shape)
            .then(if (node.selected) Modifier.background(colors.accent.copy(alpha = 0.08f), shape).border(2.dp, colors.accent, shape) else Modifier.openCodeHairline(shape))
            .then(
                if (id != null) {
                    Modifier.selectable(selected = node.selected, enabled = pressable, role = Role.Button) { scope.send(AgentWidgetEvent.press(id)) }
                } else Modifier,
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        node.children.forEachIndexed { index, it -> AgentWidgetNodeView(it, scope, Modifier, "$path.$index") }
    }
}

@Composable
private fun ButtonNode(node: AgentWidgetNode.Button, scope: AgentWidgetScope, modifier: Modifier) {
    val enabled = scope.live && !node.disabled
    val press = { scope.send(AgentWidgetEvent.press(node.id)) }
    when (node.variant) {
        AgentWidgetNode.Variant.Primary -> OpenCodePrimaryButton(node.label, press, modifier, enabled)
        AgentWidgetNode.Variant.Danger -> OpenCodePrimaryButton(node.label, press, modifier, enabled, role = OpenCodeButtonRole.Destructive)
        AgentWidgetNode.Variant.Secondary -> OpenCodeSecondaryButton(node.label, press, modifier, enabled)
    }
}

@Composable
private fun CheckboxNode(node: AgentWidgetNode.Checkbox, scope: AgentWidgetScope, modifier: Modifier) {
    val enabled = scope.live && !node.disabled
    Row(
        modifier
            .clip(OpenCodeShape.control)
            .toggleable(node.checked, enabled = enabled, role = Role.Checkbox) { scope.send(AgentWidgetEvent.change(node.id, it)) }
            .heightIn(min = OpenCodeMinTapTarget)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(node.checked, onCheckedChange = null, enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = OpenCodeTheme.colors.accent))
        Spacer(Modifier.width(8.dp))
        Text(node.label, style = OpenCodeTextStyles.callout, color = OpenCodeTheme.colors.text)
    }
}

@Composable
private fun SliderNode(node: AgentWidgetNode.Slider, scope: AgentWidgetScope, modifier: Modifier) {
    val colors = OpenCodeTheme.colors
    // While dragged, the slider shows its own value; it goes to the widget template on release.
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(node.value) }
    if (!dragging && value != node.value) value = node.value
    val step = node.step
    val steps = if (step != null && node.max > node.min) (((node.max - node.min) / step).roundToInt() - 1).coerceIn(0, 200) else 0
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            node.label?.let { Text(it, Modifier.weight(1f), style = OpenCodeTextStyles.callout, color = colors.text) }
            Text(formatNumber(value, step), style = OpenCodeTextStyles.footnote.copy(fontFeatureSettings = "tnum"), color = colors.secondaryText)
        }
        Slider(
            value = value,
            onValueChange = {
                dragging = true
                value = it
            },
            onValueChangeFinished = {
                dragging = false
                if (value != node.value) scope.send(AgentWidgetEvent.change(node.id, value.toDouble().let { v -> if (step != null) snap(v, node.min.toDouble(), step.toDouble()) else v }))
            },
            enabled = scope.live,
            valueRange = node.min..maxOf(node.max, node.min + 0.0001f),
            steps = if (steps in 1..100) steps else 0,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.excludedFromDrawerPan(scope.live).semantics { contentDescription = node.label ?: "" },
        )
    }
}

private fun snap(value: Double, min: Double, step: Double): Double {
    val snapped = min + Math.round((value - min) / step) * step
    return Math.round(snapped * 1e9) / 1e9
}

internal fun formatNumber(value: Float, step: Float?): String {
    val decimals = when {
        step == null -> if (value == Math.round(value).toFloat()) 0 else 2
        step >= 1f -> 0
        else -> minOf(4, (-Math.floor(Math.log10(step.toDouble()))).toInt())
    }
    return if (decimals == 0) Math.round(value).toString() else "%.${decimals}f".format(value)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectNode(node: AgentWidgetNode.Select, scope: AgentWidgetScope, modifier: Modifier) {
    val colors = OpenCodeTheme.colors
    fun pick(value: String) {
        if (node.multiple) {
            val next = if (value in node.value) node.value - value else node.value + value
            scope.send(AgentWidgetEvent.change(node.id, node.options.map { it.value }.filter { it in next }))
        } else if (node.value.firstOrNull() != value) {
            scope.send(AgentWidgetEvent.change(node.id, value))
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        node.label?.let { Text(it, style = OpenCodeTextStyles.callout, color = colors.text) }
        val chips = node.options.size <= 8 && node.options.none { it.description != null } && node.options.all { it.label.length <= 24 }
        if (chips) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (option in node.options) {
                    FilterChip(
                        selected = option.value in node.value,
                        onClick = { pick(option.value) },
                        enabled = scope.live,
                        label = { Text(option.label) },
                        shape = OpenCodeShape.control,
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.accent.copy(alpha = 0.14f)),
                    )
                }
            }
        } else {
            for (option in node.options) {
                val selected = option.value in node.value
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(OpenCodeShape.control)
                        .selectable(selected, enabled = scope.live, role = if (node.multiple) Role.Checkbox else Role.RadioButton) { pick(option.value) }
                        .heightIn(min = OpenCodeMinTapTarget)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (node.multiple) {
                        Checkbox(selected, onCheckedChange = null, enabled = scope.live, colors = CheckboxDefaults.colors(checkedColor = colors.accent))
                    } else {
                        RadioButton(selected, onClick = null, enabled = scope.live, colors = RadioButtonDefaults.colors(selectedColor = colors.accent))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(option.label, style = OpenCodeTextStyles.callout, color = colors.text)
                        option.description?.let { Text(it, style = OpenCodeTextStyles.footnote, color = colors.secondaryText) }
                    }
                }
            }
        }
    }
}

/**
 * A table drawn as the transcript's markdown tables are ([MarkdownTableGrid]): the same band, rules, cell style and
 * column sizing, with end-aligned columns on the trailing edge, the sort indicator on the sorted header, and the selected
 * row tinted. Rows take presses only when selectable, headers only when sortable.
 */
@Composable
private fun TableNode(node: AgentWidgetNode.Table, scope: AgentWidgetScope, modifier: Modifier) {
    val colors = currentMarkdownStyle().colors
    val cellStyle = OpenCodeTextStyles.subheadline
    val headerStyle = cellStyle.copy(fontWeight = FontWeight.SemiBold)
    val id = node.id
    val rowsPress = id != null && node.selectable
    val headersPress = id != null && node.sortable
    val tint = colors.accent.copy(alpha = 0.14f)
    MarkdownTableGrid(
        node.columns.size,
        node.rows.size + 1,
        modifier.fillMaxWidth(),
        end = { node.columns[it].end },
        rowBackground = { row ->
            val body = row - 1
            val selected = body >= 0 && node.selected == body
            Box(
                Modifier
                    .then(if (selected) Modifier.background(tint) else Modifier)
                    .then(
                        if (rowsPress && body >= 0) {
                            Modifier.selectable(selected, enabled = scope.live, role = Role.Button) { scope.send(AgentWidgetEvent.press(id!!, body)) }
                        } else Modifier,
                    ),
            )
        },
        headerBackground = if (headersPress) {
            { column -> Box(Modifier.clickable(enabled = scope.live, role = Role.Button) { scope.send(AgentWidgetEvent.change(id!!, column)) }) }
        } else null,
    ) { row, column ->
        if (row == 0) {
            val sort = node.sort?.takeIf { it.column == column }
            Row {
                Text(node.columns[column].label, Modifier.weight(1f, fill = false), style = headerStyle, color = colors.secondaryText)
                if (sort != null) {
                    Text(if (sort.descending) " ▼" else " ▲", Modifier.clearAndSetSemantics {}, style = headerStyle, color = colors.secondaryText)
                }
            }
        } else {
            Text(node.rows[row - 1][column], style = cellStyle, color = colors.text)
        }
    }
}

/** Typing stays here and goes to the widget template once it rests 300 ms, on Done, on losing focus, or before any other event. */
@Composable
private fun InputNode(node: AgentWidgetNode.Input, scope: AgentWidgetScope, modifier: Modifier) {
    val controller = scope.controller
    var text by remember(node.id) { mutableStateOf(node.value) }
    var typed by remember(node.id) { mutableStateOf(false) }
    var seen by remember(node.id) { mutableStateOf(node.value) }
    // A new value from the widget template replaces the text unless typing is still waiting to go.
    if (node.value != seen) {
        seen = node.value
        if (controller?.hasDraft(node.id) != true) {
            text = node.value
            typed = false
        }
    }
    LaunchedEffect(text, typed) {
        if (!typed) return@LaunchedEffect
        delay(DEBOUNCE_MILLIS)
        controller?.commit(node.id)
    }
    fun commit() {
        if (typed) controller?.commit(node.id)
    }
    OpenCodeTextField(
        value = text,
        onValueChange = {
            text = it
            typed = true
            if (scope.live) controller?.draft(node.id, it)
        },
        modifier = modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) commit() },
        label = node.label,
        placeholder = node.placeholder,
        enabled = scope.live,
        singleLine = !node.multiline,
        minLines = if (node.multiline) 3 else 1,
        keyboardOptions = KeyboardOptions(imeAction = if (node.multiline) ImeAction.Default else ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
    )
}

private const val DEBOUNCE_MILLIS = 300L
