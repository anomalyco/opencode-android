package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.markdown.InlineText
import ai.opencode.feature.transcript.selection.TranscriptSelectionLeafKey
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/**
 * A table in the transcript's inset block. Columns size to their content up to 220. When that's
 * wider than the block, the columns shrink to fit it, as the desktop's (browser) table layout does,
 * but never below a readable floor (112, or the column's natural width if smaller, and never below
 * its longest word). A table that still doesn't fit scrolls, with the next column peeking in under
 * the edge fade. Rules under the header and between rows run the block's full width and stay put
 * while the columns scroll.
 *
 * @param selection The block's key in a reply: each cell joins the reply's one selection (its part
 *     is [TranscriptSelectionLeafKey.tablePart]). Elsewhere cells aren't selectable.
 */
@Composable
fun MarkdownTable(
    header: List<InlineText>,
    rows: List<List<InlineText>>,
    selection: TranscriptSelectionLeafKey? = null,
    modifier: Modifier = Modifier,
) {
    val style = currentMarkdownStyle()
    val colors = style.colors
    val density = LocalDensity.current
    val targets = LocalSessionLinks.current.targets
    val listener = rememberLinkListener()
    // No `tnum`: Inter's tabular set also widens the hyphen, which opened a gap in words like
    // "mid-sentence", and left-aligned cells don't line digits up anyway.
    val cellStyle = OpenCodeTextStyles.subheadline
    val headerStyle = cellStyle.copy(fontWeight = FontWeight.SemiBold)
    fun present(cell: InlineText, isHeader: Boolean) = MarkdownPresentation.present(
        cell,
        PresentationOptions(
            foreground = if (isHeader) colors.secondaryText else colors.text,
            textSize = cellStyle.fontSize,
            tonesBody = false,
        ),
        style, targets, density, listener, siteIcon = null,
    )
    val presentedHeader = remember(header, targets, colors, density) { header.map { present(it, true) } }
    val presentedRows = remember(rows, targets, colors, density) { rows.map { row -> row.map { present(it, false) } } }
    ReportsSessionLinks((presentedHeader + presentedRows.flatten()).flatMap { it.sessionIDs }.distinct())
    val columns = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)
    val presented = listOf(presentedHeader) + presentedRows
    MarkdownTableGrid(columns, presented.size, modifier) { rowIndex, column ->
        val cell = presented[rowIndex].getOrNull(column)
        val isHeader = rowIndex == 0
        if (cell == null) {
            Box(Modifier)
        } else {
            val key = selection?.copy(part = TranscriptSelectionLeafKey.tablePart(rowIndex, column))
            ArrivingText(
                cell, emptyList(), if (isHeader) headerStyle else cellStyle, if (isHeader) colors.secondaryText else colors.text,
                selectionKey = key,
            )
        }
    }
}

/**
 * [MarkdownTable]'s grid and band for any cells: [rows] rows (the header first) of [columns] cells, each drawn by
 * [cell]. A column for which [end] is true lines its cells up on the trailing edge. With [rowBackground], each row also gets
 * that content behind it, sized from rule to rule and across the band's full width (a highlight, a press target); with
 * [headerBackground], each header cell gets it behind its share of the header row.
 */
@Composable
internal fun MarkdownTableGrid(
    columns: Int,
    rows: Int,
    modifier: Modifier = Modifier,
    end: (column: Int) -> Boolean = { false },
    rowBackground: (@Composable (row: Int) -> Unit)? = null,
    headerBackground: (@Composable (column: Int) -> Unit)? = null,
    cell: @Composable (row: Int, column: Int) -> Unit,
) {
    val rules = remember { TableRules() }
    // Backgrounds reach the band's border, so the grid holds the band's vertical padding itself.
    val behind = rowBackground != null || headerBackground != null
    BoxWithConstraints(modifier) {
        val viewport = maxWidth - TranscriptBandStyle.horizontalPadding * 2
        MarkdownBand(rules = rules, verticalPadding = if (behind) 0.dp else TranscriptBandStyle.verticalPadding) {
            Layout(
                content = {
                    if (rowBackground != null) for (index in 0 until rows) rowBackground(index)
                    if (headerBackground != null) for (column in 0 until columns) headerBackground(column)
                    for (rowIndex in 0 until rows) {
                        for (column in 0 until columns) cell(rowIndex, column)
                    }
                },
            ) { all, _ ->
                val behindRows = if (rowBackground != null) rows else 0
                val behindHeaders = if (headerBackground != null) columns else 0
                val backgrounds = all.subList(0, behindRows)
                val headerBackgrounds = all.subList(behindRows, behindRows + behindHeaders)
                val measurables = all.subList(behindRows + behindHeaders, all.size)
                val cap = 220.dp.roundToPx()
                val floor = 112.dp.roundToPx()
                val hSpacing = TranscriptMetrics.turn.roundToPx()
                val vSpacing = TranscriptMetrics.bubbleVertical.roundToPx()
                val rule = (0.5.dp).roundToPx().coerceAtLeast(1)
                val rowCount = rows
                // Each column's natural width (its widest cell, capped) and its narrowest: its longest
                // word, and at least the readable floor, so a squeezed column never wraps word by word.
                val widest = IntArray(columns)
                val narrowest = IntArray(columns)
                for ((index, measurable) in measurables.withIndex()) {
                    val column = index % maxOf(columns, 1)
                    val natural = minOf(measurable.maxIntrinsicWidth(Constraints.Infinity), cap)
                    val word = minOf(measurable.minIntrinsicWidth(Constraints.Infinity), natural)
                    val readable = maxOf(word, minOf(natural, floor))
                    widest[column] = maxOf(widest[column], natural)
                    narrowest[column] = maxOf(narrowest[column], readable)
                }
                val room = viewport.roundToPx() - hSpacing * (columns - 1).coerceAtLeast(0)
                val widestTotal = widest.sum()
                val narrowestTotal = narrowest.sum()
                val columnWidths = IntArray(columns)
                for (column in 0 until columns) {
                    columnWidths[column] = when {
                        widestTotal <= room -> widest[column]
                        narrowestTotal >= room -> narrowest[column]
                        else -> {
                            // Share the room past every column's longest word in proportion to how much more each wants.
                            val share = (room - narrowestTotal).toFloat() / (widestTotal - narrowestTotal)
                            narrowest[column] + ((widest[column] - narrowest[column]) * share).toInt()
                        }
                    }
                }
                val placeables = measurables.mapIndexed { index, measurable ->
                    val width = columnWidths[index % maxOf(columns, 1)]
                    measurable.measure(Constraints(minWidth = 0, maxWidth = width.coerceAtLeast(0)))
                }
                val widths = IntArray(columns) { column -> (0 until rowCount).maxOfOrNull { placeables[it * columns + column].width } ?: 0 }
                val heights = IntArray(rowCount) { row -> (0 until columns).maxOfOrNull { placeables[row * columns + it].height } ?: 0 }
                val tops = IntArray(rowCount)
                val ruleTops = mutableListOf<Float>()
                var y = 0
                for (row in 0 until rowCount) {
                    if (row > 0) {
                        y += vSpacing
                        ruleTops += y.toFloat()
                        y += rule + vSpacing
                    }
                    tops[row] = y
                    y += heights[row]
                }
                val totalWidth = widths.sum() + hSpacing * (columns - 1).coerceAtLeast(0)
                val bandTop = TranscriptBandStyle.verticalPadding.roundToPx()
                val shift = if (behind) bandTop else 0
                rules.offsets = ruleTops.map { it + shift }
                val bandWidth = maxOf(totalWidth, viewport.roundToPx())
                val inset = TranscriptBandStyle.horizontalPadding.roundToPx()
                val rowPlaceables = backgrounds.mapIndexed { index, measurable ->
                    val top = if (index == 0) -bandTop else tops[index] - vSpacing
                    val bottom = if (index == rowCount - 1) y + bandTop else tops[index] + heights[index] + vSpacing
                    measurable.measure(Constraints.fixed(bandWidth + inset * 2, (bottom - top).coerceAtLeast(0))) to top
                }
                val headerBottom = if (rowCount > 1) heights[0] + vSpacing else y + bandTop
                var headerX = 0
                val headerPlaceables = headerBackgrounds.mapIndexed { column, measurable ->
                    val left = if (column == 0) -inset else headerX - hSpacing / 2
                    val right = if (column == columns - 1) bandWidth + inset else headerX + widths[column] + hSpacing - hSpacing / 2
                    headerX += widths[column] + hSpacing
                    measurable.measure(Constraints.fixed((right - left).coerceAtLeast(0), headerBottom + bandTop)) to left
                }
                layout(if (behind) bandWidth else totalWidth, y + shift * 2) {
                    for ((placeable, top) in rowPlaceables) placeable.place(-inset, top + shift)
                    for ((placeable, left) in headerPlaceables) placeable.place(left, -bandTop + shift)
                    for (row in 0 until rowCount) {
                        var x = 0
                        for (column in 0 until columns) {
                            val placeable = placeables[row * columns + column]
                            placeable.place(if (end(column)) x + widths[column] - placeable.width else x, tops[row] + shift)
                            x += widths[column] + hSpacing
                        }
                    }
                }
            }
        }
    }
}
