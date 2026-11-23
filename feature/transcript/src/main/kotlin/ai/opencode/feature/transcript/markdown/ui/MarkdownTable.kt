package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.openCodeHairline
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.markdown.InlineText
import ai.opencode.feature.transcript.selection.TranscriptSelectionLeafKey
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/**
 * A table in the transcript's inset block. Columns size to their content up to 220. When that's
 * wider than the block, the columns shrink to fit it, as the desktop's (browser) table layout does,
 * but never below a readable floor (112, or the column's natural width if smaller, and never below
 * its longest word). A table that still doesn't fit scrolls, with the next column peeking in under
 * the edge fade. Rules under the header and between rows run the block's full width and stay put
 * while the columns scroll.
 *
 * On a phone ([showsTableCards]) each body row is a card instead: its first cell is the title, and
 * every other cell sits under its column heading as a small label. The heading row isn't shown.
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
    val rules = remember { TableRules() }
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
    if (showsTableCards) {
        TableCards(header, presentedRows, columns, cellStyle, selection, modifier)
        return
    }
    BoxWithConstraints(modifier) {
        val viewport = maxWidth - TranscriptBandStyle.horizontalPadding * 2
        MarkdownBand(rules = rules) {
            Layout(
                content = {
                    for ((rowIndex, row) in (listOf(presentedHeader) + presentedRows).withIndex()) {
                        for (column in 0 until columns) {
                            val cell = row.getOrNull(column)
                            val isHeader = row === presentedHeader
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
                },
            ) { measurables, _ ->
                val cap = 220.dp.roundToPx()
                val floor = 112.dp.roundToPx()
                val hSpacing = TranscriptMetrics.turn.roundToPx()
                val vSpacing = TranscriptMetrics.bubbleVertical.roundToPx()
                val rule = (0.5.dp).roundToPx().coerceAtLeast(1)
                val rowCount = measurables.size / maxOf(columns, 1)
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
                rules.offsets = ruleTops
                layout(totalWidth, y) {
                    for (row in 0 until rowCount) {
                        var x = 0
                        for (column in 0 until columns) {
                            placeables[row * columns + column].place(x, tops[row])
                            x += widths[column] + hSpacing
                        }
                    }
                }
            }
        }
    }
}

/** Phones show a table's rows as cards; tablets and wider windows keep the grid. */
internal val showsTableCards: Boolean
    @Composable @ReadOnlyComposable get() = LocalConfiguration.current.smallestScreenWidthDp < 600

@Composable
private fun TableCards(
    header: List<InlineText>,
    rows: List<List<PresentedText>>,
    columns: Int,
    cellStyle: TextStyle,
    selection: TranscriptSelectionLeafKey?,
    modifier: Modifier,
) {
    val colors = currentMarkdownStyle().colors
    val shape = RoundedCornerShape(OpenCodeRadius.card)
    val headings = remember(header) { header.map { it.text.trim() } }
    val titleStyle = cellStyle.copy(fontWeight = FontWeight.SemiBold)
    val labelStyle = OpenCodeTextStyles.caption2.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.04.em)
    // A cell still streaming in holds its line, so the card doesn't grow when it arrives.
    val pendingHeight = with(LocalDensity.current) { cellStyle.lineHeight.toDp() }
    Column(
        modifier.fillMaxWidth().semantics { collectionInfo = CollectionInfo(rowCount = rows.size, columnCount = 1) },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for ((rowIndex, row) in rows.withIndex()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .semantics { collectionItemInfo = CollectionItemInfo(rowIndex = rowIndex, rowSpan = 1, columnIndex = 0, columnSpan = 1) }
                    .background(OpenCodeSurfaces.color, shape)
                    .openCodeHairline(shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                for (column in 0 until columns) {
                    val cell = row.getOrNull(column)
                    val key = selection?.copy(part = TranscriptSelectionLeafKey.tablePart(rowIndex + 1, column))
                    if (column == 0) {
                        if (cell == null) Spacer(Modifier.height(pendingHeight)) else ArrivingText(cell, emptyList(), titleStyle, colors.text, selectionKey = key)
                        if (columns > 1) {
                            Spacer(Modifier.height(10.dp))
                            Box(Modifier.fillMaxWidth().height(OpenCodeSurfaces.hairline).background(colors.border))
                        }
                        continue
                    }
                    Column(Modifier.padding(top = 10.dp)) {
                        val heading = headings.getOrNull(column).orEmpty()
                        if (heading.isNotEmpty()) {
                            Text(
                                heading.uppercase(), style = labelStyle, color = colors.accent,
                                modifier = Modifier.padding(bottom = 2.dp).clearAndSetSemantics { contentDescription = heading },
                            )
                        }
                        if (cell == null) Spacer(Modifier.height(pendingHeight)) else ArrivingText(cell, emptyList(), cellStyle, colors.text, selectionKey = key)
                    }
                }
            }
        }
    }
}
