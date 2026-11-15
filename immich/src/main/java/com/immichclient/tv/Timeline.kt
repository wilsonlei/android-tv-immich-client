package com.immichclient.tv

import android.util.Log
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Timeline per month: month list immediately, month contents only when (almost) visible. */
class TimelineState(val filter: Filter, newestFirst: Boolean, private val scope: CoroutineScope) : ImageSource {
    var newestFirst by mutableStateOf(newestFirst)
        private set
    var months by mutableStateOf<List<Month>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    val loaded = mutableStateMapOf<String, List<Asset>>()
    private val inFlight = HashSet<String>()
    private var starts = IntArray(0)
    private var total = 0
    val grid = GridState()
    private val layoutCache = HashMap<String, List<LayoutRow>>()
    /** Pre-built rows (header + photos) per month so a newly loaded month doesn't rebuild everything. */
    private val rowCache = HashMap<String, List<GridItem>>()

    /** Partner setting with which the list was loaded (setting changed -> reload). */
    private var withPartners = Settings.partners

    init { start() }

    private fun start() {
        loading = true
        withPartners = Settings.partners
        scope.launch {
            runCatching { Immich.months(filter, newestFirst) }
                .onSuccess { months = it; sumUp(); error = null }
                .onFailure { error = it.message; Log.w("Immich", "months", it) }
            loading = false
        }
    }

    fun flipOrder() = setOrder(!newestFirst)

    fun setOrder(newest: Boolean) {
        newestFirst = newest
        reload()
    }

    /** Fetch everything again (order or partner setting changed). */
    fun reload() {
        loaded.clear(); layoutCache.clear(); rowCache.clear(); inFlight.clear(); months = emptyList()
        grid.lastFocus = null
        scope.launch { grid.list.scrollToItem(0) }
        start()
    }

    /** Apply settings to an existing timeline (main timeline/favorites): only when something differs. */
    fun applySettings() {
        if (filter.albumId == null && filter.personId == null && filter.bbox == null && newestFirst != Settings.newestFirst) { setOrder(Settings.newestFirst); return }
        if (filter.albumId == null && filter.personId == null && filter.bbox == null && !filter.favorites && withPartners != Settings.partners) reload()
    }

    private fun sumUp() {
        starts = IntArray(months.size)
        var s = 0
        months.forEachIndexed { i, m -> starts[i] = s; s += m.count }
        total = s
    }

    fun load(m: String) {
        if (loaded.containsKey(m) || !inFlight.add(m)) return
        scope.launch {
            runCatching { Immich.month(filter, newestFirst, m) }
                .onSuccess { a ->
                    // Pre-decode thumbhashes in the background so scrolling doesn't have to
                    withContext(Dispatchers.Default) { a.take(600).forEach { x -> x.thumbhash?.let(ThumbHash::bitmap) } }
                    val i = months.indexOfFirst { it.key == m }
                    if (i >= 0 && months[i].count != a.size) {
                        months = months.toMutableList().also { l -> l[i] = Month(m, a.size) }; sumUp()
                    }
                    loaded[m] = a
                }
                .onFailure { Log.w("Immich", "month $m", it) }
            inFlight.remove(m)
        }
    }

    fun layoutRows(m: Month, width: Float, height: Float): List<LayoutRow>? {
        val a = loaded[m.key] ?: return null
        return layoutCache.getOrPut("${m.key}|$width|$height") {
            Layout.rows(m.key, Layout.byDay(a, withYear = false), width, height, SPACING, GROUP_SPACING)
        }
    }

    /** Rows for all months; [height] = row height (small/normal/large setting). */
    fun rows(width: Float, height: Float): Pair<List<GridItem>, IntArray> {
        val out = ArrayList<GridItem>()
        val monthOf = ArrayList<Int>()
        months.forEachIndexed { i, m ->
            val isLoaded = loaded.containsKey(m.key)
            val r = rowCache.getOrPut("${m.key}|$width|$height|$isLoaded|${m.count}") {
                val header = HeaderRow("k-${m.key}", monthTitle(m.key), Language.plural(R.plurals.items, m.count, m.count))
                val rows = layoutRows(m, width, height)
                if (rows == null) listOf(header, PlaceholderRow("w-${m.key}", m.key, Layout.estimateHeight(m.count, width, height, HEADER_HEIGHT)))
                else listOf(header) + rows.map { PhotoRow(it) }
            }
            out.addAll(r)
            repeat(r.size) { monthOf.add(i) }
        }
        return out to monthOf.toIntArray()
    }

    override val count get() = total
    override fun at(i: Int): Asset? {
        if (i < 0 || i >= total) return null
        var lo = 0; var hi = starts.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (starts[mid] <= i) lo = mid else hi = mid - 1 }
        val m = months[lo]
        val l = loaded[m.key] ?: run { load(m.key); return null }
        // Prefetch neighboring month so the viewer advances smoothly
        months.getOrNull(lo + 1)?.let { if (i - starts[lo] > m.count - 6) load(it.key) }
        return l.getOrNull(i - starts[lo])
    }

    fun indexOf(a: Asset): Int {
        months.forEachIndexed { i, m -> loaded[m.key]?.indexOfFirst { it.id == a.id }?.takeIf { it >= 0 }?.let { return starts[i] + it } }
        return -1
    }

    fun startOf(monthIndex: Int) = starts.getOrElse(monthIndex) { 0 }
}

@Composable
fun TimelineScreen(screen: Screen.Timeline, nav: Nav) {
    val state = screen.state
    val scope = rememberCoroutineScope()
    val title = screen.title
    var pickerOpen by remember { mutableStateOf(false) }
    var jumpLabel by remember { mutableStateOf<String?>(null) }
    val firstButton = remember { FocusRequester() }
    val dateButton = remember { FocusRequester() }
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(pickerOpen) { if (wasOpen && !pickerOpen && state.grid.focusTarget == null) { delay(16); runCatching { dateButton.requestFocus() } }; wasOpen = pickerOpen }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = widthFor(maxWidth.value)
        // Row height = setting; only read here so a change re-layouts all rows once
        val rowHeight = Settings.rowSize.height
        val (rows, monthOf) = remember(state.months, state.loaded.size, width, rowHeight) { state.rows(width, rowHeight) }
        val topCount = 1 // header item (title + buttons + memories)

        // Load whatever is (almost) visible
        LaunchedEffect(rows) {
            snapshotFlow { state.grid.list.layoutInfo.visibleItemsInfo.map { it.index } }
                .distinctUntilChanged()
                .collectLatest { visible ->
                    if (visible.isEmpty()) return@collectLatest
                    val from = (visible.first() - topCount).coerceAtLeast(0)
                    val to = (visible.last() - topCount).coerceAtMost(monthOf.size - 1)
                    if (to < 0) { state.months.firstOrNull()?.let { state.load(it.key) }; return@collectLatest }
                    val mFrom = monthOf.getOrElse(from) { 0 }
                    val mTo = monthOf.getOrElse(to) { 0 }
                    for (mi in (mFrom - 1)..(mTo + 2)) state.months.getOrNull(mi)?.let { state.load(it.key) }
                }
        }
        // First time: focus the first photo; returning from another section: focus the last selected photo (otherwise drawer opens)
        LaunchedEffect(state.months.isNotEmpty(), state.loaded.isNotEmpty()) {
            // Wait briefly: otherwise Android grabs the first drawer item when opening the window
            delay(400)
            if (state.grid.focusTarget == null) {
                val last = state.grid.lastFocus
                if (last != null) { state.grid.focusTarget = last.id; return@LaunchedEffect }
                val m = state.months.firstOrNull() ?: return@LaunchedEffect
                state.loaded[m.key]?.firstOrNull()?.let { state.grid.focusTarget = it.id }
            }
        }

        // Always use current rows (jump is also captured by the scrollbar; a stale list gave the wrong row)
        val currentRows = rememberUpdatedState(rows)
        fun jump(monthIndex: Int) {
            val m = state.months.getOrNull(monthIndex) ?: return
            jumpLabel = monthTitle(m.key)
            state.load(m.key)
            scope.launch {
                val i = currentRows.value.indexOfFirst { it.key == "k-${m.key}" }
                Log.i("Immich", "jump to ${m.key}: row $i")
                if (i >= 0) state.grid.list.scrollToItem(i + topCount)
                // Wait until the month arrives and focus the first photo
                repeat(100) {
                    state.loaded[m.key]?.firstOrNull()?.let { state.grid.focusTarget = it.id; return@launch }
                    delay(30)
                }
            }
        }
        val currentMonth by remember(monthOf) {
            derivedStateOf { monthOf.getOrElse((state.grid.list.firstVisibleItemIndex - topCount + 1).coerceAtLeast(0)) { 0 } }
        }

        Box(
            Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key.nativeKeyCode) {
                    // Fast-forward / rewind on remote: next / previous month
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { jump(currentMonth + 1); true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { jump((currentMonth - 1).coerceAtLeast(0)); true }
                    // Play/Pause: slideshow from the focused photo
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        val a = state.grid.lastFocus ?: state.loaded[state.months.firstOrNull()?.key ?: ""]?.firstOrNull()
                        a?.let { x -> state.indexOf(x).takeIf { it >= 0 }?.let { nav.view(state, it, title, true, state.grid) } }
                        true
                    }
                    else -> false
                }
            },
        ) {
            when {
                state.error != null && state.months.isEmpty() -> StatusMessage(stringResource(R.string.immich_unreachable), state.error, Icons.Filled.CloudOff)
                !state.loading && state.months.isEmpty() -> StatusMessage(
                    stringResource(if (screen.state.filter.favorites) R.string.no_favorites_yet else R.string.no_photos_yet),
                    if (screen.state.filter.favorites) stringResource(R.string.favorites_tip) else null,
                    if (screen.state.filter.favorites) Icons.Filled.FavoriteBorder else Icons.Filled.PhotoLibrary,
                )
            }
            PhotoGrid(
                rows = rows,
                state = state.grid,
                onClick = { a -> state.indexOf(a).takeIf { it >= 0 }?.let { nav.view(state, it, title, false, state.grid) } },
                onPlaceholder = state::load,
                headerCount = topCount,
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "top", contentType = "top") {
                    Column {
                        // Greeting (setting) above the main timeline: automatic ("Good afternoon, <first name>") or custom text
                        val greeting = if (screen.withMemories) greetingText() else null
                        ScreenHeader(greeting ?: title, if (greeting != null) title else screen.sub) {
                            ChoiceChip(
                                selected = false, onClick = { state.flipOrder() }, modifier = Modifier.focusRequester(firstButton),
                                leadingIcon = { Icon(Icons.Filled.SwapVert, null, Modifier.width(18.dp)) },
                                text = stringResource(if (state.newestFirst) R.string.newest_first else R.string.oldest_first),
                            )
                            ChoiceChip(
                                selected = false, onClick = { pickerOpen = true }, modifier = Modifier.focusRequester(dateButton),
                                leadingIcon = { Icon(Icons.Filled.CalendarMonth, null, Modifier.width(18.dp)) },
                                text = stringResource(R.string.go_to_date),
                            )
                        }
                        if (screen.withMemories && Settings.memories) MemoriesRow(nav)
                    }
                }
            }
            TimelineScrollbar(state, monthOf, topCount, currentMonth, Modifier.align(Alignment.CenterEnd), go = ::jump) {
                // Nothing selected on the scrollbar: return focus to where it was (or the first photo of the visible month)
                val back = state.grid.lastFocus?.id ?: state.months.getOrNull(currentMonth)?.let { state.loaded[it.key]?.firstOrNull()?.id }
                state.grid.focusTarget = back
            }
            AnimatedVisibility(jumpLabel != null, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
                Text(
                    jumpLabel.orEmpty(), style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.background(Color(0xCC000000), RoundedCornerShape(16.dp)).padding(horizontal = 28.dp, vertical = 14.dp),
                )
            }
            LaunchedEffect(jumpLabel) { if (jumpLabel != null) { delay(900); jumpLabel = null } }
        }
        if (pickerOpen) DatePicker(state, close = { pickerOpen = false }, pick = { pickerOpen = false; jump(it) })
    }
}

/** Greeting according to settings; the name comes from /users/me (first word), time of day from the clock. */
@Composable
fun greetingText(): String? {
    val g = Settings.greeting
    if (g == Greeting.OFF) return null
    if (g == Greeting.CUSTOM) return Settings.greetingText.trim().ifEmpty { null }
    LaunchedEffect(Immich.server) { Immich.loadMe() }
    val name = Immich.me?.name?.trim()?.substringBefore(' ')?.ifEmpty { null }
    val hour = java.time.LocalTime.now().hour
    val part = stringResource(when (hour) { in 6..11 -> R.string.greeting_morning; in 12..17 -> R.string.greeting_afternoon; in 18..23 -> R.string.greeting_evening; else -> R.string.greeting_night })
    return if (name != null) stringResource(R.string.greeting_with_name, part, name) else part
}

/**
 * Timeline scrollbar: position based on month counts (months load lazily, so rows alone don't reflect
 * the whole): first visible row -> month -> fraction within that month -> photo number in total.
 */
@Composable
private fun TimelineScrollbar(state: TimelineState, monthOf: IntArray, topCount: Int, monthIndex: Int, modifier: Modifier, go: (Int) -> Unit, back: () -> Unit) {
    val list = state.grid.list
    val months = state.months
    if (state.count == 0 || months.isEmpty()) return
    // First row index per month
    val monthStart = remember(monthOf) {
        val out = IntArray(months.size) { monthOf.size }
        for (r in monthOf.indices.reversed()) out[monthOf[r]] = r
        for (m in out.indices.reversed()) if (out[m] == monthOf.size && m + 1 < out.size) out[m] = out[m + 1]
        out
    }
    fun photoNumber(): Int {
        val visible = list.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull() ?: return 0
        if (!list.canScrollForward) return state.count - 1
        val part = if (first.size > 0) (-first.offset.toFloat() / first.size).coerceIn(0f, 1f) else 0f
        val r = (first.index - topCount + part).coerceAtLeast(0f)
        val m = monthOf.getOrElse(r.toInt()) { 0 }.coerceIn(0, months.lastIndex)
        val start = monthStart[m]
        val end = if (m + 1 < monthStart.size) monthStart[m + 1] else monthOf.size
        val inside = if (end > start) ((r - start) / (end - start)).coerceIn(0f, 1f) else 0f
        return (state.startOf(m) + inside * months[m].count).toInt().coerceIn(0, state.count - 1)
    }
    // Year markers along the track (newest first or oldest first: order of the months)
    val years = remember(months) {
        val out = ArrayList<Pair<Float, String>>()
        var prev = ""
        months.forEachIndexed { i, m ->
            val y = m.key.take(4)
            if (y != prev) { prev = y; if (y.toIntOrNull()?.let { it > 1970 } == true) out.add(state.startOf(i).toFloat() / state.count to y) }
        }
        out
    }
    // Remote control navigation: one step = one month, hold = by year
    val current = rememberUpdatedState(monthIndex)
    val goNow = rememberUpdatedState(go); val backNow = rememberUpdatedState(back)
    val steps = remember(months) {
        fun year(i: Int) = months[i].key.take(4)
        BarSteps(
            count = months.size,
            fraction = { i -> state.startOf(i).toFloat() / (state.count - 1).coerceAtLeast(1) },
            label = { i -> monthTitle(months[i].key) },
            sub = { i -> Language.plural(R.plurals.items, months[i].count, months[i].count) },
            bigStep = { i, r ->
                // To the first month (in list order) of the previous/next year
                var j = i
                if (r > 0) { while (j < months.lastIndex && year(j) == year(i)) j++ }
                else { while (j > 0 && year(j) == year(i)) j--; while (j > 0 && year(j - 1) == year(j)) j-- }
                j
            },
            current = { current.value },
            go = { goNow.value(it) },
            back = { backNow.value() },
            byMonth = true,
        )
    }
    Scrollbar(
        fraction = { photoNumber().toFloat() / (state.count - 1).coerceAtLeast(1) },
        movement = { list.firstVisibleItemIndex * 7919 + list.firstVisibleItemScrollOffset / 200 },
        scrollable = { list.canScrollForward || list.canScrollBackward },
        label = { months.getOrNull(monthIndex)?.let { monthTitle(it.key) } },
        sub = { "${Language.number(photoNumber() + 1)} / ${Language.number(state.count)}" },
        markers = years,
        modifier = modifier,
        steps = steps,
    )
}

/** Pick year, then month. */
@Composable
private fun DatePicker(state: TimelineState, close: () -> Unit, pick: (Int) -> Unit) {
    var year by remember { mutableStateOf<Int?>(null) }
    val byYear = remember(state.months) {
        state.months.withIndex().groupBy { it.value.key.take(4).toIntOrNull() ?: 0 }
    }
    androidx.activity.compose.BackHandler { if (year != null) year = null else close() }
    val fr = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color(0xF00A0A0A)).trapFocus().padding(48.dp)) {
        Column {
            Text(year?.toString() ?: stringResource(R.string.go_to_year), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(20.dp))
            val ySel = year
            if (ySel == null) {
                LazyVerticalGrid(GridCells.Fixed(8), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(byYear.keys.toList(), key = { it }) { y ->
                        val n = byYear[y].orEmpty().sumOf { it.value.count }
                        ActionButton(if (y <= 1970) stringResource(R.string.no_date) else "$y", Language.number(n), if (y == byYear.keys.first()) Modifier.focusRequester(fr) else Modifier) {
                            if (byYear[y].orEmpty().size == 1) pick(byYear[y]!!.first().index) else year = y
                        }
                    }
                }
            } else {
                LazyVerticalGrid(GridCells.Fixed(6), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(byYear[ySel].orEmpty(), key = { it.value.key }) { (i, m) ->
                        ActionButton(monthTitle(m.key).substringBefore(' '), Language.number(m.count), if (i == byYear[ySel]!!.first().index) Modifier.focusRequester(fr) else Modifier) { pick(i) }
                    }
                }
            }
        }
    }
    LaunchedEffect(year) { delay(50); runCatching { fr.requestFocus() } }
}

@Composable
fun ActionButton(title: String, sub: String?, modifier: Modifier = Modifier, focusedScale: Float = 1.1f, onClick: () -> Unit) {
    // Set text color explicitly: typography color otherwise beats contentColor (light text on light blue)
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick, modifier = modifier.height(64.dp).onFocusChanged { focused = it.isFocused },
        shape = ButtonStyle.shape,
        scale = ButtonStyle.scale(focusedScale),
        colors = ButtonStyle.colors,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (focused) AppColors.onPrimary else AppColors.text, maxLines = 1)
            sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (focused) AppColors.onPrimary.copy(alpha = 0.8f) else AppColors.muted, maxLines = 1) }
        }
    }
}

@Composable
fun ScreenHeader(title: String, sub: String?, buttons: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.displaySmall, maxLines = 1)
            sub?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.muted, maxLines = 1) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { buttons() }
    }
}

/** Empty state / error message, centered with a large soft icon. */
@Composable
fun StatusMessage(title: String, text: String?, icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.PhotoLibrary) {
    Column(Modifier.fillMaxSize().padding(64.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.padding(bottom = 18.dp).background(AppColors.surface, androidx.compose.foundation.shape.CircleShape).padding(22.dp)) {
            Icon(icon, null, Modifier.width(40.dp).height(40.dp), tint = AppColors.muted)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        text?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = AppColors.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp)) }
    }
}

/** "On this day": cards at the top of the timeline, like in Immich. */
@Composable
fun MemoriesRow(nav: Nav) {
    val list = MemoriesCache.today
    LaunchedEffect(Unit) { MemoriesCache.loadToday() }
    if (list.isEmpty()) return
    // Shift slightly left + inner padding so the enlarged focus border of the first card isn't clipped
    LazyRow(
        Modifier.fillMaxWidth().offset(x = (-10).dp).padding(top = 2.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(list, key = { it.id }) { m -> MemoryCard(m, nav) }
    }
}

@Composable
fun MemoryCard(m: Memory, nav: Nav, width: Float = 168f) {
    val cover = m.assets[m.assets.size / 2]
    Card(
        onClick = { nav.view(ListSource(m.assets), 0, yearsAgo(m.yearsAgo), true, null) },
        modifier = Modifier.width(width.dp).height((width * 1.25f).dp),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        scale = CardDefaults.scale(focusedScale = 1.05f),
    ) {
        Box(Modifier.fillMaxSize().background(AppColors.surfaceHigh)) {
            AsyncImage(cardImage(cover.id), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(bottomGradient))
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text("${m.year}", style = MaterialTheme.typography.displaySmall, color = Color.White)
                Text(yearsAgo(m.yearsAgo), style = MaterialTheme.typography.labelLarge, color = Color.White)
                Text(pluralStringResource(R.plurals.photos, m.assets.size, Language.number(m.assets.size)), style = MaterialTheme.typography.labelSmall, color = Color(0xCCFFFFFF))
            }
        }
    }
}

/** Today's memories (and recent days for the Memories screen), fetched once per day. */
object MemoriesCache {
    var today by mutableStateOf<List<Memory>>(emptyList())
        private set
    private var fetchedOn: LocalDate? = null

    suspend fun loadToday() {
        val d = LocalDate.now()
        if (fetchedOn == d) return
        runCatching { Immich.memories(d) }.onSuccess { today = it; fetchedOn = d }.onFailure { Log.w("Immich", "memories", it) }
    }
}
