package com.immichclient.tv

import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class AlbumOrder(@StringRes val label: Int) { RECENT(R.string.sort_recent), NEWEST(R.string.sort_newest_photos), NAME(R.string.sort_name), COUNT(R.string.sort_count) }

class AlbumsState(scope: CoroutineScope) {
    var albums by mutableStateOf<List<Album>>(emptyList())
    var error by mutableStateOf<String?>(null)
    var order by mutableStateOf(AlbumOrder.RECENT)
    var sharedOnly by mutableStateOf(false)
    var query by mutableStateOf("")
    val grid = LazyGridState()
    var lastFocus: String? = null

    init {
        scope.launch {
            runCatching { Immich.albums() }.onSuccess { albums = it }.onFailure { error = it.message; Log.w("Photos", "albums", it) }
        }
    }

    fun visible(): List<Album> {
        val l = albums.filter { (!sharedOnly || it.shared) && (query.isBlank() || stripAccents(it.name).contains(stripAccents(query.trim()), ignoreCase = true)) && it.count > 0 }
        return when (order) {
            AlbumOrder.RECENT -> l.sortedByDescending { it.updatedAt }
            AlbumOrder.NEWEST -> l.sortedByDescending { it.to }
            AlbumOrder.NAME -> l.sortedWith(compareBy(java.text.Collator.getInstance(Language.locale)) { it.name })
            AlbumOrder.COUNT -> l.sortedByDescending { it.count }
        }
    }
}

@Composable
fun AlbumsScreen(state: AlbumsState, nav: Nav) {
    val list = remember(state.albums, state.order, state.sharedOnly, state.query) { state.visible() }
    val first = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    var searchOpen by remember { mutableStateOf(false) }
    val searchBtn = remember { FocusRequester() }
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(searchOpen) { if (wasOpen && !searchOpen) { delay(16); runCatching { searchBtn.requestFocus() } }; wasOpen = searchOpen }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            state = state.grid,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Column {
                    ScreenHeader(stringResource(R.string.albums), pluralStringResource(R.plurals.albums_count, list.size, list.size) + if (state.query.isNotBlank()) " " + stringResource(R.string.albums_with, state.query) else "")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        for (v in AlbumOrder.entries) {
                            ChoiceChip(
                                selected = state.order == v, onClick = { state.order = v },
                                modifier = if (v == AlbumOrder.RECENT) Modifier.focusRequester(first) else Modifier,
                                text = stringResource(v.label),
                            )
                        }
                        ChoiceChip(selected = state.sharedOnly, onClick = { state.sharedOnly = !state.sharedOnly }, text = stringResource(R.string.shared))
                        ChoiceChip(
                            selected = state.query.isNotBlank(), onClick = { searchOpen = true }, modifier = Modifier.focusRequester(searchBtn),
                            leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.width(18.dp)) },
                            text = state.query.ifBlank { stringResource(R.string.find_album) },
                        )
                    }
                }
            }
            if (state.error != null && state.albums.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { StatusMessage(stringResource(R.string.albums_load_failed), state.error) }
            items(list, key = { it.id }) { a ->
                AlbumCard(
                    a, if (a.id == state.lastFocus) Modifier.focusRequester(backFocus) else Modifier,
                    onFocus = { state.lastFocus = a.id },
                ) {
                    nav.open(Screen.Timeline(a.name, albumSubtitle(a), Filter(albumId = a.id), withMemories = false, startNewest = !a.oldestFirst, nav.scope))
                }
            }
        }
        Scrollbar(
            fraction = { fractionOf(state.grid, 1) },
            movement = { state.grid.firstVisibleItemIndex * 7919 + state.grid.firstVisibleItemScrollOffset / 200 },
            scrollable = { state.grid.canScrollForward || state.grid.canScrollBackward },
            label = { null },
            sub = { "${Language.number((state.grid.firstVisibleItemIndex - 1).coerceAtLeast(0) + 1)} / ${Language.number(list.size)}" },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
    LaunchedEffect(Unit) {
        delay(30)
        if (state.lastFocus == null || runCatching { backFocus.requestFocus() }.isFailure) runCatching { first.requestFocus() }
    }
    if (searchOpen) TextInputOverlay(stringResource(R.string.find_album), state.query, live = { state.query = it }, action = stringResource(R.string.action_done), close = { searchOpen = false }) {
        state.query = it; searchOpen = false
    }
}

fun albumSubtitle(a: Album): String {
    val period = when {
        a.from == null -> null
        a.to == null || a.from.withDayOfMonth(1) == a.to.withDayOfMonth(1) -> a.from.format(DateFormats.monthShort)
        else -> a.from.format(DateFormats.monthShort) + " – " + a.to.format(DateFormats.monthShort)
    }
    return listOfNotNull(Language.plural(R.plurals.items, a.count, a.count), period, if (a.shared) Language.s(R.string.shared_lower) else null).joinToString("  ·  ")
}

@Composable
private fun AlbumCard(a: Album, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    Column {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth().aspectRatio(1f).onFocusChanged { if (it.isFocused) onFocus() },
            shape = CardDefaults.shape(RoundedCornerShape(14.dp)),
            scale = CardDefaults.scale(focusedScale = 1.05f),
        ) {
            Box(Modifier.fillMaxSize().background(AppColors.surfaceHigh)) {
                if (a.cover != null) AsyncImage(imageRequest(Immich.thumb(a.cover)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(Icons.Filled.PhotoLibrary, null, Modifier.align(Alignment.Center), tint = AppColors.muted)
            }
        }
        Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, modifier = Modifier.padding(top = 8.dp))
        Text(albumSubtitle(a), style = MaterialTheme.typography.bodySmall, maxLines = 1, color = Color(0xFFA1A1AA))
    }
}
