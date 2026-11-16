package com.immichclient.tv

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ExploreState(scope: CoroutineScope) {
    var people by mutableStateOf<List<Person>>(emptyList())
    var places by mutableStateOf<List<Place>>(emptyList())
    var error by mutableStateOf<String?>(null)
    val grid = LazyGridState()
    var lastFocus: String? = null

    init {
        scope.launch { runCatching { Immich.people() }.onSuccess { people = it }.onFailure { error = it.message; Log.w("Photos", "people", it) } }
        scope.launch {
            runCatching { Immich.places() }
                .onSuccess { l ->
                    // country with the most places (main country) first, then by country and city
                    val mainCountry = l.groupingBy { it.country }.eachCount().maxByOrNull { it.value }?.key
                    val collator = java.text.Collator.getInstance(Language.locale)
                    places = l.sortedWith(compareBy<Place> { it.country != mainCountry }.thenBy(collator) { it.country }.thenBy(collator) { it.city })
                }
                .onFailure { Log.w("Photos", "places", it) }
        }
    }
}

/** People (faces) and places, like "Explore" in Immich. */
@Composable
fun ExploreScreen(state: ExploreState, nav: Nav) {
    val first = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    val personLabel = stringResource(R.string.person)
    val peopleTitle = stringResource(R.string.people); val placesTitle = stringResource(R.string.places)
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(30),
            state = state.grid,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") { ScreenHeader(stringResource(R.string.explore), null) }
            if (state.error != null && state.people.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { StatusMessage(stringResource(R.string.load_failed), state.error) }
            if (state.people.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "kp") {
                Text(stringResource(R.string.people), style = MaterialTheme.typography.titleLarge)
            }
            items(state.people, key = { "p" + it.id }, span = { GridItemSpan(3) }) { p ->
                val mod = when {
                    ("p" + p.id) == state.lastFocus -> Modifier.focusRequester(backFocus)
                    p == state.people.first() -> Modifier.focusRequester(first)
                    else -> Modifier
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Card(
                        onClick = { nav.open(Screen.Timeline(p.name, personLabel, Filter(personId = p.id), withMemories = false, startNewest = true, nav.scope)) },
                        modifier = mod.fillMaxWidth().aspectRatio(1f).onFocusChanged { if (it.isFocused) state.lastFocus = "p" + p.id },
                        shape = CardDefaults.shape(CircleShape),
                        scale = CardDefaults.scale(focusedScale = 1.08f),
                    ) { AsyncImage(imageRequest(Immich.face(p.id)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    Text(p.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                }
            }
            if (state.places.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "kpl") {
                Text(stringResource(R.string.places), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 18.dp))
            }
            items(state.places, key = { "s" + it.city + it.country }, span = { GridItemSpan(5) }) { pl ->
                Card(
                    onClick = {
                        nav.open(Screen.AssetList(pl.city, pl.country, true, nav.scope) { page -> Immich.searchPlace(pl.city, page) })
                    },
                    modifier = (if (("s" + pl.city + pl.country) == state.lastFocus) Modifier.focusRequester(backFocus) else Modifier)
                        .fillMaxWidth().aspectRatio(1.3f).onFocusChanged { if (it.isFocused) state.lastFocus = "s" + pl.city + pl.country },
                    shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
                    scale = CardDefaults.scale(focusedScale = 1.05f),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        AsyncImage(imageRequest(Immich.thumb(pl.cover)), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(bottomGradient))
                        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                            Text(pl.city, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
                            Text(pl.country, style = MaterialTheme.typography.labelSmall, color = Color(0xCCFFFFFF), maxLines = 1)
                        }
                    }
                }
            }
        }
        Scrollbar(
            fraction = { fractionOf(state.grid, 1) },
            movement = { state.grid.firstVisibleItemIndex * 7919 + state.grid.firstVisibleItemScrollOffset / 200 },
            scrollable = { state.grid.canScrollForward || state.grid.canScrollBackward },
            label = { (state.grid.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String)?.let { if (it.startsWith("s")) placesTitle else if (it.startsWith("p")) peopleTitle else null } },
            sub = { null },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
    LaunchedEffect(state.people.isNotEmpty()) {
        delay(30)
        if (state.lastFocus == null || runCatching { backFocus.requestFocus() }.isFailure) runCatching { first.requestFocus() }
    }
}
