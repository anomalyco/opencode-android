package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeMinTapTarget
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeHairline
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.markdown.InlineText
import ai.opencode.feature.transcript.selection.TranscriptSelectionLeafKey
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
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
 * A table that would scroll ([MarkdownTableFit.scrolls]) draws each body row as a card instead: its
 * first cell is the title, and every other cell sits under its column heading as a small label. The
 * heading row isn't shown. The cells are measured before either is composed, so the table never
 * draws as the other first; while a reply streams it turns into cards once, when a row makes it too
 * wide, and stays cards. A quiet switch at the block's top end turns such a table into the grid and
 * back; the pick is the reader's, per table, and stays while the row scrolls away and back.
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
    val latch = remember { TableCardsLatch() }
    // The reader's pick for a table that went to cards: kept while the row scrolls away and back.
    var showsTable by rememberSaveable { mutableStateOf(false) }
    SubcomposeLayout(modifier) { constraints ->
        val viewport = constraints.maxWidth - (TranscriptBandStyle.horizontalPadding * 2).roundToPx()
        // The grid's cells as plain text, measured only: what the grid would size its columns from.
        val probes = subcompose(TableSlot.Probe) {
            for (row in listOf(presentedHeader) + presentedRows) {
                for (column in 0 until columns) {
                    val cell = row.getOrNull(column)
                    if (cell == null) {
                        Box(Modifier)
                    } else {
                        Text(
                            cell.text, Modifier.clearAndSetSemantics {}, style = if (row === presentedHeader) headerStyle else cellStyle,
                            inlineContent = cell.inlineContent,
                        )
                    }
                }
            }
        }
        val fit = markdownTableFit(
            IntArray(probes.size) { probes[it].maxIntrinsicWidth(Constraints.Infinity) },
            IntArray(probes.size) { probes[it].minIntrinsicWidth(Constraints.Infinity) },
            columns, viewport,
        )
        val switchable = constraints.hasBoundedWidth && latch.cards(viewport, fit.scrolls)
        val cards = switchable && !showsTable
        val switch = if (switchable) {
            subcompose(TableSlot.Switch) { TableViewSwitch(showsTable) { showsTable = !showsTable } }
                .map { it.measure(Constraints()) }
        } else {
            emptyList()
        }
        val top = if (switchable) TableViewSwitch.height.roundToPx() else 0
        val content = subcompose(if (cards) TableSlot.Cards else TableSlot.Grid) {
            if (cards) {
                TableCards(header, presentedRows, columns, cellStyle, selection, Modifier)
            } else {
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
                        val hSpacing = TranscriptMetrics.turn.roundToPx()
                        val vSpacing = TranscriptMetrics.bubbleVertical.roundToPx()
                        val rule = (0.5.dp).roundToPx().coerceAtLeast(1)
                        val rowCount = measurables.size / maxOf(columns, 1)
                        val columnWidths = markdownTableFit(
                            IntArray(measurables.size) { measurables[it].maxIntrinsicWidth(Constraints.Infinity) },
                            IntArray(measurables.size) { measurables[it].minIntrinsicWidth(Constraints.Infinity) },
                            columns, viewport,
                        ).columnWidths()
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
        }.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = maxOf(constraints.minWidth, content.maxOfOrNull { it.width } ?: 0)
        val height = maxOf(constraints.minHeight, top + (content.maxOfOrNull { it.height } ?: 0))
        layout(width, height) {
            content.forEach { it.place(0, top) }
            // The target is centred on the face, which sits flush with the block's top end; the rest of
            // the target reaches into the margins around it.
            val reach = ((OpenCodeMinTapTarget - TableViewSwitch.face) / 2).roundToPx()
            switch.forEach { it.place(width - it.width + reach, -reach) }
        }
    }
}

private enum class TableSlot { Probe, Switch, Grid, Cards }

/**
 * Switches a table that went to cards to the grid and back: a quiet glyph like the code block's
 * Copy button, the table glyph over cards and the cards glyph over the grid.
 */
@Composable
private fun TableViewSwitch(showsTable: Boolean, onSwitch: () -> Unit) {
    val colors = OpenCodeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val label = stringResource(if (showsTable) R.string.transcript_table_show_cards_description else R.string.transcript_table_show_table_description)
    val shape = RoundedCornerShape(OpenCodeRadius.xs)
    var face = Modifier.size(TableViewSwitch.face)
    if (pressed) face = face.background(colors.text.copy(alpha = 0.08f), shape)
    Box(
        Modifier
            .size(OpenCodeMinTapTarget)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onSwitch)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
                onClick { onSwitch(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(face, contentAlignment = Alignment.Center) {
            OpenCodeIcon(
                if (showsTable) SFSymbol.RectangleSplit1x2 else SFSymbol.Tablecells, contentDescription = null, size = 16.dp,
                tint = if (pressed) colors.text else colors.secondaryText,
            )
        }
    }
}

private object TableViewSwitch {
    val face = 24.dp

    /** What the switch adds above the table: its face and a little air. */
    val height = face + 4.dp
}

/**
 * How a table's columns fit its block. Each column's natural width is its widest cell, capped at
 * 220; its narrowest is its longest word and at least the readable floor (112, or its natural width
 * if smaller), so a squeezed column never wraps word by word.
 */
internal class MarkdownTableFit(private val widest: IntArray, private val narrowest: IntArray, private val room: Int) {
    /** Even at their narrowest the columns don't fit: the grid would scroll sideways. */
    val scrolls: Boolean get() = narrowest.sum() > room

    /** Natural widths when they fit; otherwise shrunk toward the narrowest, never below it. */
    fun columnWidths(): IntArray {
        val widestTotal = widest.sum()
        val narrowestTotal = narrowest.sum()
        return IntArray(widest.size) { column ->
            when {
                widestTotal <= room -> widest[column]
                narrowestTotal >= room -> narrowest[column]
                else -> {
                    // Share the room past every column's longest word in proportion to how much more each wants.
                    val share = (room - narrowestTotal).toFloat() / (widestTotal - narrowestTotal)
                    narrowest[column] + ((widest[column] - narrowest[column]) * share).toInt()
                }
            }
        }
    }
}

/**
 * The fit of cells whose widest and narrowest (longest word) widths are [maxWidths] and [minWidths],
 * row by row with the header first, in a block [viewport] wide.
 */
internal fun Density.markdownTableFit(maxWidths: IntArray, minWidths: IntArray, columns: Int, viewport: Int): MarkdownTableFit {
    val cap = 220.dp.roundToPx()
    val floor = 112.dp.roundToPx()
    val widest = IntArray(columns)
    val narrowest = IntArray(columns)
    for (index in maxWidths.indices) {
        val column = index % maxOf(columns, 1)
        val natural = minOf(maxWidths[index], cap)
        val word = minOf(minWidths[index], natural)
        widest[column] = maxOf(widest[column], natural)
        narrowest[column] = maxOf(narrowest[column], maxOf(word, minOf(natural, floor)))
    }
    val room = viewport - TranscriptMetrics.turn.roundToPx() * (columns - 1).coerceAtLeast(0)
    return MarkdownTableFit(widest, narrowest, room)
}

/** Once cards at a width, a table stays cards, so a cell that shrinks as its Markdown closes doesn't flip it back. */
private class TableCardsLatch {
    private var viewport = -1
    private var cards = false

    fun cards(viewport: Int, scrolls: Boolean): Boolean {
        if (viewport != this.viewport) {
            this.viewport = viewport
            cards = false
        }
        cards = cards || scrolls
        return cards
    }
}

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
