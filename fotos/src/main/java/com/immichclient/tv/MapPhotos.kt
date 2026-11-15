package com.immichclient.tv

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * All photos of a map cluster (long OK on the map) as a grid: newest first (the order from /map/markers),
 * with an interactive scrollbar. OK = viewer from that photo, Play/Pause (or Slideshow button) = slideshow.
 * Only photo IDs are known initially; date etc. are fetched by [MapSource] only when a photo (or bar label) asks for it.
 */
class MapPhotosState(val ids: List<String>, scope: CoroutineScope) {
    val source = MapSource(ids, scope)
    val grid = LazyGridState()
    /** Index that should receive focus once visible (-1 = none). */
    var focusTarget by mutableIntStateOf(0)
    var lastFocus = 0
}

private const val COLUMNS = 6

@Composable
fun MapPhotosScreen(screen: Screen.MapPhotos, nav: Nav) {
    val state = screen.state
    val scope = rememberCoroutineScope()
    val n = state.ids.size
    fun view(i: Int, slideshow: Boolean) = nav.view(state.source, i, screen.title, slideshow, null) { state.focusTarget = state.lastFocus }
    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key.nativeKeyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> { view(state.lastFocus, true); true }
                else -> false
            }
        },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            state = state.grid,
            contentPadding = PaddingValues(start = 24.dp, end = 40.dp, top = 20.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                ScreenHeader(screen.title, pluralStringResource(R.plurals.photos, n, Language.number(n))) {
                    ChoiceChip(
                        selected = false, onClick = { view(0, true) },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, null, Modifier.width(18.dp)) },
                        text = stringResource(R.string.slideshow),
                    )
                }
            }
            items(n, key = { state.ids[it] }) { i ->
                IdTile(
                    state.ids[i], focusTarget = state.focusTarget == i, onFocusTargetDone = { state.focusTarget = -1 },
                    onFocus = { state.lastFocus = i },
                ) { view(i, false) }
            }
        }
        // Scrollbar: date of the first visible photo (once known) + "N / M"; interactive in ~5% steps
        fun firstNow() = (state.grid.firstVisibleItemIndex - 1).coerceIn(0, (n - 1).coerceAtLeast(0))
        fun date(i: Int) = state.source.at(i)?.let { monthTitle(it.date.toLocalDate().toString()) }
        val steps = remember(n) {
            val anchors = (0 until 20).map { it * n / 20 / COLUMNS * COLUMNS }.distinct()
            BarSteps(
                count = anchors.size,
                fraction = { i -> anchors[i].toFloat() / (n - 1).coerceAtLeast(1) },
                label = { i -> date(anchors[i]) },
                sub = { i -> "${Language.number(anchors[i] + 1)} / ${Language.number(n)}" },
                bigStep = bigStepBy(5, anchors.size),
                current = { anchors.indexOfLast { it <= firstNow() }.coerceAtLeast(0) },
                go = { i -> scope.launch { state.grid.scrollToItem(anchors[i] + 1); state.focusTarget = anchors[i] } },
                back = { state.focusTarget = state.lastFocus },
            )
        }
        if (n > 0) Scrollbar(
            fraction = { fractionOf(state.grid, 1) },
            movement = { state.grid.firstVisibleItemIndex * 7919 + state.grid.firstVisibleItemScrollOffset / 200 },
            scrollable = { state.grid.canScrollForward || state.grid.canScrollBackward },
            label = { date(firstNow()) },
            sub = { "${Language.number(firstNow() + 1)} / ${Language.number(n)}" },
            modifier = Modifier.align(Alignment.CenterEnd),
            steps = steps,
        )
    }
    // Returning to this screen (or first time): focus the last selected photo
    LaunchedEffect(Unit) { delay(30); if (state.focusTarget < 0) state.focusTarget = state.lastFocus }
}

/** Square thumbnail from just an ID (focus: slightly larger, white border, glow in accent color). */
@Composable
internal fun IdTile(id: String, focusTarget: Boolean, onFocusTargetDone: () -> Unit, onFocus: () -> Unit, onClick: () -> Unit) {
    val fr = remember { FocusRequester() }
    if (focusTarget) LaunchedEffect(id) {
        repeat(5) { if (runCatching { fr.requestFocus() }.isSuccess) { onFocusTargetDone(); return@LaunchedEffect }; delay(16) }
        onFocusTargetDone()
    }
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "scale")
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.aspectRatio(1f)
            .graphicsLayer {
                scaleX = scale; scaleY = scale; this.shape = shape; clip = true
                shadowElevation = if (focused) 16.dp.toPx() else 0f
                spotShadowColor = AppColors.primary; ambientShadowColor = AppColors.primary
            }
            .background(AppColors.surface)
            .focusRequester(fr)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .clickable(interactionSource = null, indication = null, onClick = onClick),
    ) {
        AsyncImage(imageRequest(Immich.thumb(id)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (focused) Box(Modifier.matchParentSize().border(3.dp, Color.White, shape))
    }
}
