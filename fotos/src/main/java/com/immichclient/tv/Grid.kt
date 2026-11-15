package com.immichclient.tv

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** An item in a photo grid (LazyColumn). */
sealed interface GridItem { val key: String }
data class HeaderRow(override val key: String, val title: String, val sub: String?) : GridItem
data class PhotoRow(val row: LayoutRow) : GridItem { override val key get() = row.key }
/** Month that is still loading; focusable so holding Down never gets stuck. */
data class PlaceholderRow(override val key: String, val month: String, val height: Float) : GridItem

const val ROW_HEIGHT = 138f
const val SPACING = 6f
const val GROUP_SPACING = 26f
const val HEADER_HEIGHT = 30f

class GridState {
    val list = LazyListState()
    /** Asset that should receive focus as soon as it is visible (returning from viewer / returning to this screen). */
    var focusTarget by mutableStateOf<String?>(null)
    var lastFocus: Asset? = null
    /** Viewer closed on this photo: scroll there and give it focus. */
    var returnTo by mutableStateOf<String?>(null)

    suspend fun goTo(id: String, rows: List<GridItem>, headerCount: Int = 0) {
        val i = rows.indexOfFirst { r -> r is PhotoRow && r.row.chunks.any { s -> s.cells.any { it.asset.id == id } } }
        if (i < 0) return
        val visible = list.layoutInfo.visibleItemsInfo
        val already = visible.any { it.index == i + headerCount && it.offset >= 0 && it.offset + it.size <= list.layoutInfo.viewportEndOffset }
        if (!already) list.scrollToItem(i + headerCount, -(list.layoutInfo.viewportSize.height * 0.3f).toInt())
        focusTarget = id
    }
}

/** Source for the viewer: can have more items than currently loaded (timeline loads month by month). */
interface ImageSource {
    val count: Int
    fun at(i: Int): Asset?
}

class ListSource(val list: List<Asset>) : ImageSource {
    override val count get() = list.size
    override fun at(i: Int) = list.getOrNull(i)
}

/**
 * Selected photos stay at ~30% of the viewport height (like Android TV rows), with a smooth scroll;
 * by default Compose only scrolls just enough, leaving you always against the edge. Applies to all lists and grids
 * (via the theme).
 *
 * The spec is a spring, not a tween: Compose re-applies the spec on "bring into view" every frame to the remaining
 * distance (UpdatableAnimationState, playTime = one frame). A tween(260, FastOutSlowIn) only moved ~1% closer per
 * frame (the easing starts flat): a crawling scroll that felt like an intentional delay. A spring integrates properly
 * per frame (with velocity) and follows holding a key smoothly without falling behind.
 */
@OptIn(ExperimentalFoundationApi::class)
val focusAt30 = object : BringIntoViewSpec {
    @Deprecated("") override val scrollAnimationSpec: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 700f)
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val target = containerSize * 0.3f
        return if (size > containerSize * 0.7f) offset else offset - target
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoGrid(
    rows: List<GridItem>,
    state: GridState,
    onClick: (Asset) -> Unit,
    modifier: Modifier = Modifier,
    headerCount: Int = 1,
    onPlaceholder: (String) -> Unit = {},
    header: LazyListScope.() -> Unit = {},
) {
    androidx.compose.runtime.LaunchedEffect(state.returnTo, rows) {
        val id = state.returnTo ?: return@LaunchedEffect
        state.goTo(id, rows, headerCount)
        state.returnTo = null
    }
    LazyColumn(
        state = state.list,
        modifier = modifier,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(SPACING.dp),
    ) {
        header()
        items(rows, key = { it.key }, contentType = { it::class }) { r ->
            when (r) {
                is HeaderRow -> MonthHeader(r)
                is PhotoRow -> PhotosRow(r.row, state, onClick)
                is PlaceholderRow -> PlaceholderBox(r, onPlaceholder)
            }
        }
    }
}

@Composable
private fun MonthHeader(r: HeaderRow) {
    Column(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(r.title, style = MaterialTheme.typography.headlineSmall)
            r.sub?.let { Text("   $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 3.dp)) }
        }
        // subtle accent line: short segment in primary color, then a hairline
        Row(Modifier.padding(top = 5.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(34.dp).height(3.dp).background(AppColors.primary, RoundedCornerShape(2.dp)))
            Box(Modifier.padding(start = 8.dp).weight(1f).height(1.dp).background(AppColors.text.copy(alpha = 0.08f)))
        }
    }
}

@Composable
private fun PhotosRow(row: LayoutRow, state: GridState, onClick: (Asset) -> Unit) {
    var focus by remember { mutableStateOf(false) }
    // draw enlarged focused photo above the next row
    Row(
        Modifier.zIndex(if (focus) 1f else 0f).onFocusChanged { focus = it.hasFocus },
        horizontalArrangement = Arrangement.spacedBy(GROUP_SPACING.dp),
    ) {
        for (chunk in row.chunks) {
            var chunkFocus by remember { mutableStateOf(false) }
            Column(Modifier.zIndex(if (chunkFocus) 1f else 0f).onFocusChanged { chunkFocus = it.hasFocus }) {
                if (row.hasHeader) Text(
                    chunk.header.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    modifier = Modifier.height(HEADER_HEIGHT.dp).padding(top = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(SPACING.dp)) {
                    for (c in chunk.cells) {
                        PhotoTile(
                            c.asset, c.width.dp, row.height.dp,
                            onClick = { onClick(c.asset) },
                            focusTarget = state.focusTarget == c.asset.id,
                            onFocusTargetDone = { state.focusTarget = null },
                            onFocus = { state.lastFocus = c.asset },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceholderBox(r: PlaceholderRow, onPlaceholder: (String) -> Unit) {
    androidx.compose.runtime.LaunchedEffect(r.month) { onPlaceholder(r.month) }
    // gently breathing placeholder (layer alpha only: no redrawing)
    val pulse = rememberInfiniteTransition(label = "pulse")
    val a by pulse.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    Surface(
        onClick = {},
        modifier = Modifier.fillMaxWidth().height(r.height.dp).graphicsLayer { alpha = a }.onFocusChanged { if (it.isFocused) onPlaceholder(r.month) },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(5.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.surface.copy(alpha = 0.5f), focusedContainerColor = AppColors.surfaceHigh),
    ) { Box(Modifier) {} }
}

fun widthFor(total: Float) = total - 48f

/** Flat list (search results, memory, place) as rows: by day, or as a single block by relevance. */
fun rowsFor(assets: List<Asset>, width: Float, byDay: Boolean, withYear: Boolean = true, height: Float = ROW_HEIGHT): List<GridItem> {
    val groups = if (byDay) Layout.byDay(assets, withYear) else listOf(Group(null, assets))
    return Layout.rows("l", groups, width, height, SPACING, GROUP_SPACING).map { PhotoRow(it) }
}
