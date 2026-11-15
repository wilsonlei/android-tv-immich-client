package com.immichclient.tv

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * All people (from Search -> "All people"): grid of faces, sorted by frequency by default (Immich's order:
 * favorites first, then photo count), or by name; typing filters live.
 */
class PeopleState(val all: List<Person>) {
    var query by mutableStateOf("")
    var byName by mutableStateOf(false)
    val grid = LazyGridState()
    var lastFocus: String? = null

    fun visible(): List<Person> {
        val z = stripAccents(query.trim())
        val l = if (z.isEmpty()) all else all.filter { p -> stripAccents(p.name).split(' ').any { it.startsWith(z, ignoreCase = true) } || stripAccents(p.name).contains(z, ignoreCase = true) }
        return if (byName) l.sortedWith(compareBy(java.text.Collator.getInstance(Language.locale)) { it.name }) else l
    }
}

/** Strip diacritics so e.g. "Malaisie" / "Chloé" matches "chloe". */
fun stripAccents(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

@Composable
fun PeopleScreen(state: PeopleState, nav: Nav) {
    val list = remember(state.all, state.query, state.byName) { state.visible() }
    val personLabel = stringResource(R.string.person)
    var searchOpen by remember { mutableStateOf(false) }
    val searchBtn = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(searchOpen) { if (wasOpen && !searchOpen) { delay(16); runCatching { searchBtn.requestFocus() } }; wasOpen = searchOpen }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            state = state.grid,
            contentPadding = PaddingValues(start = 24.dp, end = 40.dp, top = 20.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Column {
                    ScreenHeader(stringResource(R.string.people), Language.number(list.size) + if (state.query.isNotBlank()) "  ·  “${state.query}”" else "")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        ChoiceChip(
                            selected = state.query.isNotBlank(), onClick = { searchOpen = true }, modifier = Modifier.focusRequester(searchBtn),
                            leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.width(18.dp)) },
                            text = state.query.ifBlank { stringResource(R.string.find_person) },
                        )
                        ChoiceChip(selected = !state.byName, onClick = { state.byName = false }, text = stringResource(R.string.sort_most_photos))
                        ChoiceChip(selected = state.byName, onClick = { state.byName = true }, text = stringResource(R.string.sort_name))
                    }
                }
            }
            items(list, key = { it.id }) { p ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Card(
                        onClick = { nav.open(Screen.Timeline(p.name, personLabel, Filter(personId = p.id), true, false, nav.scope)) },
                        modifier = (if (p.id == state.lastFocus) Modifier.focusRequester(backFocus) else Modifier)
                            .fillMaxWidth().aspectRatio(1f).onFocusChanged { if (it.isFocused) state.lastFocus = p.id },
                        shape = CardDefaults.shape(CircleShape),
                        scale = CardDefaults.scale(focusedScale = 1.08f),
                    ) { AsyncImage(imageRequest(Immich.face(p.id)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    Text(p.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        Scrollbar(
            fraction = { fractionOf(state.grid, 1) },
            movement = { state.grid.firstVisibleItemIndex * 7919 + state.grid.firstVisibleItemScrollOffset / 200 },
            scrollable = { state.grid.canScrollForward || state.grid.canScrollBackward },
            label = { list.getOrNull((state.grid.firstVisibleItemIndex - 1).coerceAtLeast(0))?.name?.take(1)?.uppercase()?.takeIf { state.byName } },
            sub = { "${Language.number((state.grid.firstVisibleItemIndex - 1).coerceAtLeast(0) + 1)} / ${Language.number(list.size)}" },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
    LaunchedEffect(Unit) {
        delay(30)
        if (state.lastFocus == null || runCatching { backFocus.requestFocus() }.isFailure) runCatching { searchBtn.requestFocus() }
    }
    if (searchOpen) TextInputOverlay(stringResource(R.string.find_person), state.query, live = { state.query = it }, action = stringResource(R.string.action_done), close = { searchOpen = false }) {
        state.query = it; searchOpen = false
    }
}
