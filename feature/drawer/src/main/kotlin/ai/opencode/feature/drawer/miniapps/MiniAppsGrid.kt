package ai.opencode.feature.drawer.miniapps

import kotlinx.serialization.Serializable
import kotlin.math.max

/** A widget on the dashboard: its key (`sessionID:messageID:callID`) and its rect in grid cells. */
@Serializable
data class MiniAppsTile(val key: String, val x: Int, val y: Int, val w: Int, val h: Int) {
    val bottom: Int get() = y + h

    fun overlaps(other: MiniAppsTile): Boolean =
        key != other.key && x < other.x + other.w && other.x < x + w && y < other.y + other.h && other.y < y + h
}

/**
 * The dashboard's tiling: a grid of [columns] with gravity, where tiles never overlap. Moving or resizing a tile
 * places it at its target, pushes what it overlaps down below it (or up above it, when there's room), and lets
 * everything else fall back up.
 */
object MiniAppsGrid {
    const val COLUMNS = 4
    const val MIN_W = 2
    const val MIN_H = 2
    const val DEFAULT_H = 4

    /** [tile] kept inside the columns, at least the minimum size. */
    fun clamped(tile: MiniAppsTile, columns: Int = COLUMNS): MiniAppsTile {
        val w = tile.w.coerceIn(minOf(MIN_W, columns), columns)
        val h = max(tile.h, MIN_H)
        return tile.copy(x = tile.x.coerceIn(0, columns - w), y = max(tile.y, 0), w = w, h = h)
    }

    /** [key] added at the first free spot, scanning rows top to bottom, each left to right. */
    fun adding(tiles: List<MiniAppsTile>, key: String, w: Int = COLUMNS, h: Int = DEFAULT_H, columns: Int = COLUMNS): List<MiniAppsTile> {
        if (tiles.any { it.key == key }) return tiles
        val size = clamped(MiniAppsTile(key, 0, 0, w, h), columns)
        var y = 0
        while (true) {
            for (x in 0..columns - size.w) {
                val candidate = size.copy(x = x, y = y)
                if (tiles.none(candidate::overlaps)) return tiles + candidate
            }
            y++
        }
    }

    /** [key] removed, the rest fallen back up. */
    fun removing(tiles: List<MiniAppsTile>, key: String): List<MiniAppsTile> = compacted(tiles.filter { it.key != key })

    /**
     * The layout while [key] is dragged or resized to [target]: the tile held at its (clamped) target, the tiles it
     * overlaps moved aside, every other tile fallen up as far as it can.
     */
    fun moving(tiles: List<MiniAppsTile>, key: String, target: MiniAppsTile, columns: Int = COLUMNS): List<MiniAppsTile> {
        if (tiles.none { it.key == key }) return tiles
        val moved = clamped(target.copy(key = key), columns)
        val layout = tiles.map { if (it.key == key) moved else it }.toMutableList()
        pushAside(layout, moved, byUser = true)
        return compacted(layout, held = key)
    }

    /** Every tile fallen up as far as it can, in reading order; [held] stays where it is. */
    fun compacted(tiles: List<MiniAppsTile>, held: String? = null): List<MiniAppsTile> {
        val placed = ArrayList<MiniAppsTile>(tiles.size)
        tiles.firstOrNull { it.key == held }?.let(placed::add)
        val order = tiles.filter { it.key != held }.sortedWith(compareBy({ it.y }, { it.x }))
        for (tile in order) {
            var t = tile
            while (t.y > 0 && placed.none(t.copy(y = t.y - 1)::overlaps)) t = t.copy(y = t.y - 1)
            while (placed.any(t::overlaps)) t = t.copy(y = t.y + 1)
            placed += t
        }
        val byKey = placed.associateBy { it.key }
        return tiles.map { byKey.getValue(it.key) }
    }

    /** Whether no two tiles overlap and all are inside the columns. */
    fun isValid(tiles: List<MiniAppsTile>, columns: Int = COLUMNS): Boolean =
        tiles.all { it.x >= 0 && it.y >= 0 && it.x + it.w <= columns && it.w > 0 && it.h > 0 } &&
            tiles.indices.all { i -> (i + 1 until tiles.size).none { j -> tiles[i].overlaps(tiles[j]) } }

    /** The rows the layout takes. */
    fun height(tiles: List<MiniAppsTile>): Int = tiles.maxOfOrNull { it.bottom } ?: 0

    /**
     * Moves what [moved] overlaps out of its way: for the user's own tile, a tile that fits right above it goes
     * there (so dragging onto a tile swaps them), anything else goes below it, and pushes on in turn.
     */
    private fun pushAside(layout: MutableList<MiniAppsTile>, moved: MiniAppsTile, byUser: Boolean) {
        val hits = layout.filter(moved::overlaps).sortedWith(compareBy({ it.y }, { it.x }))
        for (hit in hits) {
            val current = layout.first { it.key == hit.key }
            if (!moved.overlaps(current)) continue
            if (byUser) {
                val above = current.copy(y = max(moved.y - current.h, 0))
                if (above.y < current.y && layout.none(above::overlaps)) {
                    layout[layout.indexOfFirst { it.key == hit.key }] = above
                    continue
                }
            }
            val below = current.copy(y = moved.bottom)
            layout[layout.indexOfFirst { it.key == hit.key }] = below
            pushAside(layout, below, byUser = false)
        }
    }
}
