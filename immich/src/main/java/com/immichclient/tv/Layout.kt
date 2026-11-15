package com.immichclient.tv

import androidx.compose.runtime.Immutable

/**
 * Immich-style "justified" layout: photos in their own aspect ratio, rows all the exact same width.
 * Small days (fitting in one row) sit side-by-side each with their own date header; large days get
 * their own rows. Everything in px, calculated before any image is loaded: nothing jumps around.
 */
@Immutable data class Cell(val asset: Asset, val width: Float)
@Immutable data class Chunk(val header: String?, val cells: List<Cell>)
@Immutable data class LayoutRow(val key: String, val chunks: List<Chunk>, val height: Float, val hasHeader: Boolean)

@Immutable data class Group(val header: String?, val assets: List<Asset>)

object Layout {
    /** Extreme aspect ratios for the layout (a panorama gets cropped, not a 3 px tall strip). */
    private fun r(a: Asset) = a.ratio.coerceIn(0.5f, 2.6f)

    fun rows(prefix: String, groups: List<Group>, width: Float, rowHeight: Float, spacing: Float, groupSpacing: Float): List<LayoutRow> {
        val out = ArrayList<LayoutRow>()
        var current = ArrayList<Chunk>()
        var currentWidth = 0f
        var n = 0
        fun flushCurrent() {
            if (current.isEmpty()) return
            out.add(LayoutRow("$prefix-${n++}", current, rowHeight, current.any { it.header != null }))
            current = ArrayList(); currentWidth = 0f
        }
        for (g in groups) {
            if (g.assets.isEmpty()) continue
            val natural = g.assets.sumOf { (r(it) * rowHeight).toDouble() }.toFloat() + spacing * (g.assets.size - 1)
            if (natural <= width) {
                // fits in one row: place next to previous small day(s) if it fits
                val needed = natural + if (current.isEmpty()) 0f else groupSpacing
                if (currentWidth + needed > width) flushCurrent()
                currentWidth += natural + if (current.isEmpty()) 0f else groupSpacing
                current.add(Chunk(g.header, g.assets.map { Cell(it, r(it) * rowHeight) }))
                continue
            }
            flushCurrent()
            // large day: fill rows up to width and then scale to fit exactly
            var row = ArrayList<Asset>()
            var sum = 0f
            var first = true
            for (a in g.assets) {
                row.add(a); sum += r(a)
                if (sum * rowHeight + spacing * (row.size - 1) >= width) {
                    val h = (width - spacing * (row.size - 1)) / sum
                    out.add(LayoutRow("$prefix-${n++}", listOf(Chunk(if (first) g.header else null, row.map { Cell(it, r(it) * h) })), h, first && g.header != null))
                    first = false; row = ArrayList(); sum = 0f
                }
            }
            if (row.isNotEmpty()) {
                out.add(LayoutRow("$prefix-${n++}", listOf(Chunk(if (first) g.header else null, row.map { Cell(it, r(it) * rowHeight) })), rowHeight, first && g.header != null))
            }
        }
        flushCurrent()
        return out
    }

    /** Rough height estimate for a month not yet loaded (only for scrollbar/placeholder). */
    fun estimateHeight(count: Int, width: Float, rowHeight: Float, headerHeight: Float): Float {
        val rows = kotlin.math.ceil(count * 1.25f * rowHeight / width).coerceAtLeast(1f)
        // capped: Compose cannot handle an item >262,000 px (a month with thousands of photos)
        return (rows * rowHeight + rows * 0.5f * headerHeight).coerceAtMost(2400f)
    }

    fun byDay(assets: List<Asset>, withYear: Boolean): List<Group> {
        val out = ArrayList<Group>()
        var day: java.time.LocalDate? = null
        var list = ArrayList<Asset>()
        for (a in assets) {
            val d = a.date.toLocalDate()
            if (d != day) {
                if (list.isNotEmpty()) out.add(Group(dayTitle(day!!, withYear) + placeOf(list), list))
                day = d; list = ArrayList()
            }
            list.add(a)
        }
        if (list.isNotEmpty()) out.add(Group(dayTitle(day!!, withYear) + placeOf(list), list))
        return out
    }

    /** Most common place of that day after the date, like Immich ("Sat 17 Oct · Lisbon"). */
    private fun placeOf(l: List<Asset>): String {
        val city = l.mapNotNull { it.city }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return ""
        return " ·  $city"
    }
}
