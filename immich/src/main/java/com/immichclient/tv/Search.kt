package com.immichclient.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Paginated results (search, place): loads the next page when reaching the end. */
class SearchResults(private val scope: CoroutineScope) {
    var assets by mutableStateOf<List<Asset>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var searched by mutableStateOf(false)
    private var next: Int? = null
    private var loader: (suspend (Int) -> Page)? = null
    private var job: Job? = null
    val grid = GridState()

    fun start(l: suspend (Int) -> Page) {
        job?.cancel()
        loader = l; assets = emptyList(); next = 1; error = null; searched = true; loading = false
        scope.launch { grid.list.scrollToItem(0) }
        more()
    }

    /** Reset back to initial state (suggestions). */
    fun clear() {
        job?.cancel()
        loader = null; assets = emptyList(); next = null; error = null; searched = false; loading = false
    }

    fun more() {
        val p = next ?: return
        val l = loader ?: return
        if (loading) return
        loading = true
        job = scope.launch {
            val out = runCatching { l(p) }
            if (loader !== l) return@launch // superseded by a new search or cleared (or cancelled)
            out.onSuccess { r -> assets = assets + r.assets.filter { a -> assets.none { it.id == a.id } }; next = r.next }
                .onFailure { error = it.message; next = null; Log.w("Immich", "search", it) }
            loading = false
        }
    }
}

private const val TOP_PEOPLE = 10

enum class SearchMode(@StringRes val label: Int) { SMART(R.string.mode_smart), TEXT(R.string.mode_text), FILENAME(R.string.mode_filename) }
enum class SearchType(@StringRes val label: Int, val video: Boolean?) { ALL(R.string.type_all, null), PHOTO(R.string.type_photos, false), VIDEO(R.string.type_videos, true) }
enum class SearchOrder(@StringRes val label: Int) { RELEVANCE(R.string.sort_relevance), NEWEST(R.string.sort_newest), OLDEST(R.string.sort_oldest) }

class SearchState(scope: CoroutineScope) {
    var query by mutableStateOf("")
    var mode by mutableStateOf(SearchMode.SMART)
    var type by mutableStateOf(SearchType.ALL)
    var order by mutableStateOf(SearchOrder.RELEVANCE)
    val res = SearchResults(scope)
    /** Focus the first result after a new search. */
    var focusOnResult = false
    var people by mutableStateOf<List<Person>>(emptyList())

    init {
        scope.launch { runCatching { Immich.people() }.onSuccess { people = it } }
        scope.launch { Immich.warmSearch() }
    }

    /** Reset back to initial state (suggestions, default filters/sort). */
    fun reset() {
        query = ""
        mode = SearchMode.SMART
        type = SearchType.ALL
        order = SearchOrder.RELEVANCE
        focusOnResult = false
        res.clear()
        res.grid.focusTarget = null
        res.grid.lastFocus = null
    }

    /** Search without text, type only (all videos / all photos, by date). */
    val typeOnly get() = query.isBlank() && type != SearchType.ALL

    fun search(c: Context) {
        val t = query.trim()
        val ty = type
        if (t.isEmpty()) {
            // No text: Photos/Videos selected = all of that type by date, otherwise nothing to search
            if (ty.video == null) { if (res.searched) res.clear(); return }
            if (order == SearchOrder.RELEVANCE) order = SearchOrder.NEWEST
            focusOnResult = true
            res.start { p -> Immich.searchText(null, "", p, ty.video) }
            return
        }
        RecentSearches.add(c, t)
        focusOnResult = true
        val m = mode
        res.start { p ->
            when (m) {
                SearchMode.SMART -> Immich.searchSmart(t, p, ty.video)
                SearchMode.TEXT -> Immich.searchText(t, "ocr", p, ty.video)
                SearchMode.FILENAME -> Immich.searchText(t, "originalFileName", p, ty.video)
            }
        }
    }
}

object RecentSearches {
    private const val MAX = 10
    fun list(c: Context): List<String> =
        c.getSharedPreferences("zoeken", Context.MODE_PRIVATE).getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() }
    fun add(c: Context, t: String) {
        val l = (listOf(t) + list(c).filter { !it.equals(t, true) }).take(MAX)
        c.getSharedPreferences("zoeken", Context.MODE_PRIVATE).edit().putString("recent", l.joinToString("\n")).apply()
    }
    fun clear(c: Context) {
        c.getSharedPreferences("zoeken", Context.MODE_PRIVATE).edit().remove("recent").apply()
    }
}

@Composable
fun SearchScreen(state: SearchState, nav: Nav) {
    val ctx = LocalContext.current
    var inputOpen by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && !t.isNullOrBlank()) { state.query = t; state.search(ctx) }
    }
    val voicePrompt = stringResource(R.string.voice_prompt)
    val canVoice = remember {
        ctx.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = widthFor(maxWidth.value)
        val sorted = remember(state.res.assets, state.order) {
            when (state.order) {
                SearchOrder.RELEVANCE -> state.res.assets
                SearchOrder.NEWEST -> state.res.assets.sortedByDescending { it.date }
                SearchOrder.OLDEST -> state.res.assets.sortedBy { it.date }
            }
        }
        val rowHeight = Settings.rowSize.height
        val rows = remember(sorted, width, rowHeight) { rowsFor(sorted, width, byDay = state.order != SearchOrder.RELEVANCE, height = rowHeight) }
        val source = remember(sorted) { ListSource(sorted) }
        LaunchedEffect(rows) {
            if (state.focusOnResult && sorted.isNotEmpty()) { state.focusOnResult = false; state.res.grid.focusTarget = sorted.first().id }
        }
        // Load next page when approaching the end
        LaunchedEffect(rows) {
            snapshotFlow { state.res.grid.list.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) >= it.totalItemsCount - 3 } }
                .distinctUntilChanged().collect { if (it && rows.isNotEmpty()) state.res.more() }
        }
        PhotoGrid(
            rows = rows, state = state.res.grid, headerCount = 1,
            onClick = { a -> nav.view(source, sorted.indexOf(a), if (state.typeOnly) ctx.getString(state.type.label) else ctx.getString(R.string.search_title, state.query), false, state.res.grid) },
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "top") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ScreenHeader(stringResource(R.string.search), null)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(
                            onClick = { inputOpen = true },
                            modifier = Modifier.weight(1f).height(56.dp).focusRequester(fieldFocus),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(28.dp)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                            colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.surfaceHigh, focusedContainerColor = Color(0xFF34343A)),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, AppColors.primary), shape = RoundedCornerShape(28.dp)),
                            ),
                        ) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Search, null, tint = AppColors.muted)
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    state.query.ifEmpty { stringResource(R.string.search_hint) },
                                    style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                                    color = if (state.query.isEmpty()) AppColors.muted else AppColors.text,
                                )
                            }
                        }
                        if (state.query.isNotEmpty()) Surface(
                            onClick = {
                                state.reset()
                                runCatching { fieldFocus.requestFocus() }
                            },
                            modifier = Modifier.size(56.dp),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(28.dp)),
                            colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.surfaceHigh, focusedContainerColor = AppColors.primary, focusedContentColor = AppColors.onPrimary),
                        ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Close, stringResource(R.string.clear_search)) } }
                        if (canVoice) Surface(
                            onClick = {
                                runCatching {
                                    voiceLauncher.launch(
                                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Language.locale.toLanguageTag())
                                            .putExtra(RecognizerIntent.EXTRA_PROMPT, voicePrompt),
                                    )
                                }
                            },
                            modifier = Modifier.size(56.dp),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(28.dp)),
                            colors = ClickableSurfaceDefaults.colors(containerColor = AppColors.surfaceHigh, focusedContainerColor = AppColors.primary, focusedContentColor = AppColors.onPrimary),
                        ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Mic, stringResource(R.string.voice_search)) } }
                    }
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (m in SearchMode.entries) ChoiceChip(selected = state.mode == m, onClick = { state.mode = m; state.search(ctx) }, text = stringResource(m.label))
                        Spacer(Modifier.width(18.dp))
                        for (t in SearchType.entries) ChoiceChip(selected = state.type == t, onClick = { state.type = t; state.search(ctx) }, text = stringResource(t.label))
                    }
                    if (state.res.assets.isNotEmpty()) Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (o in SearchOrder.entries) ChoiceChip(selected = state.order == o, onClick = { state.order = o }, text = stringResource(o.label))
                    }
                    when {
                        state.res.loading && state.res.assets.isEmpty() -> Text(stringResource(R.string.searching), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted)
                        state.res.error != null && state.res.assets.isEmpty() -> Text(stringResource(R.string.search_failed, state.res.error.orEmpty()), color = AppColors.muted)
                        state.res.searched && state.res.assets.isEmpty() -> Text(stringResource(R.string.nothing_found), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted)
                        state.res.searched -> Text(pluralStringResource(R.plurals.results, sorted.size, Language.number(sorted.size) + if (state.res.loading) "+" else ""), style = MaterialTheme.typography.bodySmall)
                        else -> Suggestions(state, nav) { state.query = it; state.search(ctx) }
                    }
                }
            }
        }
        ListScrollbar(state.res.grid, rows, sorted.size, state.res.loading, Modifier.align(Alignment.CenterEnd))
    }
    LaunchedEffect(Unit) { delay(30); if (!state.res.searched) runCatching { fieldFocus.requestFocus() } }
    // Overlay closed: return focus to search field (otherwise Android jumps to the drawer); results will take over afterward
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(inputOpen) { if (wasOpen && !inputOpen) { delay(16); runCatching { fieldFocus.requestFocus() } }; wasOpen = inputOpen }
    if (inputOpen) TextInputOverlay(stringResource(R.string.search), state.query, close = { inputOpen = false }) { state.query = it; inputOpen = false; state.search(ctx) }
}

@Composable
private fun Suggestions(state: SearchState, nav: Nav, onSearch: (String) -> Unit) {
    val ctx = LocalContext.current
    var recent by remember { mutableStateOf(RecentSearches.list(ctx)) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 10.dp)) {
        val rowPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        if (recent.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.recent_searches), style = MaterialTheme.typography.titleMedium)
                ChoiceChip(
                    selected = false,
                    onClick = { RecentSearches.clear(ctx); recent = emptyList() },
                    leadingIcon = { Icon(Icons.Filled.Close, null, Modifier.width(18.dp)) },
                    text = stringResource(R.string.key_clear),
                )
            }
            LazyRow(contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(recent) { t ->
                    ChoiceChip(selected = false, onClick = { onSearch(t) }, leadingIcon = { Icon(Icons.Filled.History, null, Modifier.width(18.dp)) }, text = t)
                }
            }
        }
        Text(stringResource(R.string.try_searching), style = MaterialTheme.typography.titleMedium)
        val suggestions = stringArrayResource(R.array.suggestions).toList()
        LazyRow(contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(suggestions) { t ->
                ChoiceChip(selected = false, onClick = { onSearch(t) }, text = t)
            }
        }
        if (state.people.isNotEmpty()) {
            Text(stringResource(R.string.people), style = MaterialTheme.typography.titleMedium)
            val personLabel = stringResource(R.string.person)
            // Top 10 most frequent people (Immich order: favorites, then photo count) + "All people"
            // (grid with faces, search, and sort); previously 30 chips in one row = lots of scrolling
            LazyRow(contentPadding = rowPadding, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.people.take(TOP_PEOPLE), key = { it.id }) { p ->
                    ChoiceChip(
                        selected = false,
                        onClick = { nav.open(Screen.Timeline(p.name, personLabel, Filter(personId = p.id), withMemories = false, startNewest = true, nav.scope)) },
                        leadingIcon = { FaceAvatar(p, 26.dp) }, text = p.name,
                    )
                }
                if (state.people.size > TOP_PEOPLE) item(key = "all") {
                    ChoiceChip(
                        selected = false, onClick = { nav.open(Screen.People(state.people)) },
                        leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.width(18.dp)) },
                        text = stringResource(R.string.all_people, state.people.size),
                    )
                }
            }
        }
    }
}

/** Flat paginated list (e.g. all photos from a place), grouped by day with year. */
@Composable
fun AssetListScreen(screen: Screen.AssetList, nav: Nav) {
    val res = screen.res
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = widthFor(maxWidth.value)
        val rowHeight = Settings.rowSize.height
        val rows = remember(res.assets, width, rowHeight) { rowsFor(res.assets, width, byDay = screen.byDay, height = rowHeight) }
        val source = remember(res.assets) { ListSource(res.assets) }
        LaunchedEffect(rows) {
            snapshotFlow { res.grid.list.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) >= it.totalItemsCount - 3 } }
                .distinctUntilChanged().collect { if (it && rows.isNotEmpty()) res.more() }
        }
        LaunchedEffect(rows.isNotEmpty()) {
            if (rows.isNotEmpty() && res.grid.lastFocus == null) res.grid.focusTarget = res.assets.firstOrNull()?.id
        }
        PhotoGrid(
            rows = rows, state = res.grid, headerCount = 1,
            onClick = { a -> nav.view(source, res.assets.indexOf(a), screen.title, false, res.grid) },
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "top") {
                ScreenHeader(screen.title, listOfNotNull(screen.sub, if (res.assets.isNotEmpty()) pluralStringResource(R.plurals.items, res.assets.size, res.assets.size) + (if (res.loading) "+" else "") else null).joinToString("  ·  "))
                if (res.error != null && res.assets.isEmpty()) Text(stringResource(R.string.loading_failed, res.error.orEmpty()), color = AppColors.muted)
            }
        }
        ListScrollbar(res.grid, rows, res.assets.size, res.loading, Modifier.align(Alignment.CenterEnd))
    }
}

/**
 * Scrollbar for a flat list (search results, place): day header of the first visible row + "N / M". Interactive:
 * one step = one day (row with header), or 5% of the list when there are no day headers.
 */
@Composable
fun ListScrollbar(grid: GridState, rows: List<GridItem>, total: Int, loading: Boolean, modifier: Modifier, topCount: Int = 1) {
    if (rows.isEmpty()) return
    val list = grid.list
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val firsts = remember(rows) { firstPhotoPerRow(rows) }
    fun currentRow() = (list.firstVisibleItemIndex - topCount).coerceIn(0, rows.lastIndex)
    fun headerOf(row: Int): String? {
        var r = row
        while (r > 0 && (rows[r] as? PhotoRow)?.row?.hasHeader != true) r--
        return (rows[r] as? PhotoRow)?.row?.chunks?.firstOrNull()?.header
    }
    val steps = remember(rows, total) {
        val headers = rows.indices.filter { (rows[it] as? PhotoRow)?.row?.hasHeader == true }
        val anchors = if (headers.size >= 3) headers else (0 until 20).map { it * rows.size / 20 }.distinct()
        BarSteps(
            count = anchors.size,
            fraction = { i -> firsts[anchors[i]].toFloat() / (total - 1).coerceAtLeast(1) },
            label = { i -> headerOf(anchors[i]) },
            sub = { i -> "${Language.number(firsts[anchors[i]] + 1)} / ${Language.number(total)}" },
            bigStep = bigStepBy(10, anchors.size),
            current = { anchors.indexOfLast { it <= currentRow() }.coerceAtLeast(0) },
            go = { i ->
                val r = anchors[i]
                scope.launch {
                    list.scrollToItem(r + topCount)
                    grid.focusTarget = (rows[r] as? PhotoRow)?.row?.chunks?.firstOrNull()?.cells?.firstOrNull()?.asset?.id
                }
            },
            back = { grid.focusTarget = grid.lastFocus?.id },
        )
    }
    Scrollbar(
        fraction = { fractionOf(list, topCount) },
        movement = { list.firstVisibleItemIndex * 7919 + list.firstVisibleItemScrollOffset / 200 },
        scrollable = { list.canScrollForward || list.canScrollBackward },
        label = { headerOf(currentRow()) },
        sub = { "${Language.number(firsts[currentRow()] + 1)} / ${Language.number(total)}" + if (loading) "+" else "" },
        modifier = modifier,
        steps = steps,
    )
}

/** Memories from the past week: years per day, like "On this day" in Immich. */
@Composable
fun MemoriesScreen(state: MemoriesState, nav: Nav) {
    val first = remember { FocusRequester() }
    androidx.compose.foundation.lazy.LazyColumn(
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { ScreenHeader(stringResource(R.string.section_memories), stringResource(R.string.memories_sub)) }
        if (state.done && state.days.all { it.second.isEmpty() }) item { StatusMessage(stringResource(R.string.no_memories), null) }
        state.days.forEachIndexed { di, (day, list) ->
            if (list.isEmpty()) return@forEachIndexed
            item(key = day.toString()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(dayTitle(day, false), style = MaterialTheme.typography.titleLarge)
                    LazyRow(
                        Modifier.offset(x = (-10).dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    ) {
                        items(list, key = { it.id }) { m ->
                            Box(if (di == state.days.indexOfFirst { it.second.isNotEmpty() } && m == list.first()) Modifier.focusRequester(first) else Modifier) {
                                MemoryCard(m, nav, width = 190f)
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(state.done) { delay(50); runCatching { first.requestFocus() } }
}

class MemoriesState(scope: CoroutineScope) {
    var days by mutableStateOf<List<Pair<java.time.LocalDate, List<Memory>>>>(emptyList())
    var done by mutableStateOf(false)

    init {
        scope.launch {
            val today = java.time.LocalDate.now()
            val l = (0..6).map { today.minusDays(it.toLong()) }
            days = l.map { it to emptyList() }
            l.forEachIndexed { i, d ->
                runCatching { Immich.memories(d) }.onSuccess { m -> days = days.toMutableList().also { it[i] = d to m } }
            }
            done = true
        }
    }
}
