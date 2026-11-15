package com.immichclient.tv

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.service.dreams.DreamService
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.SuccessResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt
import kotlin.random.Random

/*
 * Screensaver (DreamService) with the same atmosphere as the viewer: large photo (crossfade, Ken Burns),
 * clock + date top-left, country outline map with dot bottom-right (CountryMap.kt), a timer ring and
 * date/location bottom-left. Source, interval, and components are configurable (Settings -> Screensaver).
 * Left / Right = previous/next photo; any other key (except volume) closes the screensaver, like a normal screensaver.
 *
 * Memory-frugal (the screensaver runs alongside other apps): screen-sized previews via the same Coil cache as the
 * app, and never more than three images at once (current, previous fading out, next preloaded).
 */

/** What the screensaver shows; one instance per start. */
class DreamState {
    var current by mutableStateOf<Asset?>(null)
    /** Increments on each new photo (restarts the timer ring). */
    var round by mutableIntStateOf(0)
    /** Centered message; only shown while there is no photo yet. */
    var message by mutableStateOf<Int?>(null)
    /** Fetch failed while a photo is already shown: photo stays, with a small cloud icon next to the ring. */
    var errorIcon by mutableStateOf(false)
    /** Remote request: -1 previous, +1 next (only the latest counts). */
    val request = Channel<Int>(Channel.CONFLATED)
    val history = ArrayList<Asset>()
    var pos = -1
    /** For testing only (PreviewActivity, `--es test_fout koppeling|sleutel|net|leeg`): simulates that error. */
    var testError: String? = null
    private var lastKeyAt = 0L

    /** Left/Right key: max 1x per 600 ms, otherwise large previews pile up. */
    fun send(direction: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastKeyAt < 600) return
        lastKeyAt = now
        request.trySend(direction)
    }
}

/**
 * Provides photos according to settings: all (random, via /search/random in batches), an album / favorites /
 * a person (month list, weighted random month, then a random photo; at most 3 months in memory), or today's
 * memories. Videos are skipped; recently shown photos don't repeat immediately. If the chosen source is empty
 * (no album selected, no favorites, no memories today), falls back to random photos.
 */
class DreamQueue {
    private val queue = ArrayDeque<Asset>()
    private val recent = ArrayDeque<String>()
    /** Month lists per source filter (multiple albums = multiple filters). */
    private var months: Map<Filter, List<Month>>? = null
    private var monthsSig: String? = null
    /** Photo ID -> name of the chosen album/person it came from (for the source label). */
    private val fromName = HashMap<String, String>()
    // Fixed order: month keys in order, current position, and photos of the current month
    private var sequence: List<String>? = null
    private var seqByFilter: Map<Filter, Set<String>> = emptyMap()
    private var seqSig: String? = null
    private var seqIdx = 0
    private var resumeAfter: LocalDateTime? = null
    private val seqBuf = ArrayDeque<Asset>()
    private val monthCache = object : LinkedHashMap<String, List<Asset>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Asset>>?) = size > 3
    }
    private var memories: List<Asset>? = null
    private var memoryDay: LocalDate? = null
    private var memoryIndex = 0
    /** Photos that truly came from the chosen source (not the fallback): only those get the source label. */
    private val sourceIds = LinkedHashSet<String>()
    /** Prefetched (and preloaded) next photo, see [lookAhead]. */
    private var ahead: Asset? = null
    /** Never run next() and lookAhead() concurrently (otherwise both fetch the same batch). */
    private val lock = kotlinx.coroutines.sync.Mutex()

    fun isFromSource(id: String) = id in sourceIds
    fun nameOf(id: String): String? = fromName[id]

    suspend fun next(): Asset? = lock.withLock {
        ahead?.let { ahead = null; return@withLock it }
        fetch()
    }

    /** Pick and preload the next photo in advance while the current one is visible. */
    suspend fun lookAhead(ctx: Context) = lock.withLock {
        if (ahead != null) return@withLock
        val a = runCatching { fetch() }.onFailure { Log.w("Immich", "screensaver lookAhead", it) }.getOrNull() ?: return@withLock
        ahead = a
        SingletonImageLoader.get(ctx).enqueue(previewRequest(ctx, a.id))
    }

    /** Source as filters (timeline endpoint), each with its label name; empty = nothing selected. */
    private fun sourceFilters(): List<Pair<Filter, String?>> {
        val s = Settings
        val albums = s.pairs(s.dreamAlbums, s.dreamAlbumNames).map { (id, n) -> Filter(albumId = id) to n.ifBlank { null } }
        val people = s.pairs(s.dreamPeople, s.dreamPersonNames).map { (id, n) -> Filter(personId = id) to n.ifBlank { null } }
        return when (s.dreamSource) {
            DreamSource.ALL -> listOf(Filter() to null)
            DreamSource.MEMORIES -> emptyList()
            DreamSource.ALBUM -> albums
            DreamSource.FAVORITES -> listOf(Filter(favorites = true) to null)
            DreamSource.PERSON -> people
            DreamSource.COMBO -> albums + people
        }
    }

    private suspend fun fetch(): Asset? {
        if (Settings.dreamSource == DreamSource.MEMORIES) return mark(fromMemories()) ?: fromBatch()
        val fs = sourceFilters()
        if (fs.isEmpty()) return fromBatch() // nothing chosen yet: random photos
        if (Settings.dreamOrder != DreamOrder.RANDOM) return mark(fromSequence(fs)) ?: fromBatch()
        if (Settings.dreamSource == DreamSource.ALL) return fromBatch()
        return mark(fromMonths(fs)) ?: fromBatch()
    }

    private fun yearOf(m: Month) = m.key.take(4).toIntOrNull() ?: 0

    /** Month lists of all filters (from the chosen year); skip a failing filter (deleted album) while another works. */
    private suspend fun monthsOf(fs: List<Filter>): Map<Filter, List<Month>> {
        val from = Settings.dreamFrom
        val per = LinkedHashMap<Filter, List<Month>>()
        var error: Throwable? = null
        for (f in fs) runCatching { per[f] = Immich.months(f, true).filter { it.count > 0 && yearOf(it) >= from } }.onFailure { error = it }
        if (per.isEmpty()) error?.let { throw it }
        return per
    }

    /**
     * Fixed order (oldest/newest first) across all filters combined: month by month, photos merged and sorted by date.
     * Starts over after the last one. Position is saved so the screensaver resumes there next time.
     */
    private suspend fun fromSequence(fs: List<Pair<Filter, String?>>): Asset? {
        val oldest = Settings.dreamOrder == DreamOrder.OLDEST
        val sig = "${Settings.dreamSource}|${fs.map { it.first }}|${Settings.dreamFrom}|$oldest".hashCode().toString()
        if (seqSig != sig || sequence == null) {
            val per = monthsOf(fs.map { it.first })
            seqByFilter = per.mapValues { (_, ms) -> ms.map { it.key }.toSet() }
            val keys = per.values.flatten().map { it.key }.distinct().sorted().let { if (oldest) it else it.reversed() }
            sequence = keys; seqSig = sig; seqBuf.clear(); seqIdx = 0; resumeAfter = null
            val pos = Settings.dreamPosition.split('\t')
            if (pos.getOrNull(0) == sig) pos.getOrNull(1)?.let { d ->
                runCatching { LocalDateTime.parse(d) }.getOrNull()?.let { r ->
                    resumeAfter = r
                    val ym = d.take(7)
                    seqIdx = keys.indexOfFirst { if (oldest) it.take(7) >= ym else it.take(7) <= ym }.coerceAtLeast(0)
                }
            }
            Log.i("Immich", "screensaver: fixed order, ${keys.size} months, starting at ${keys.getOrNull(seqIdx)} (after $resumeAfter)")
        }
        val ms = sequence.orEmpty()
        if (ms.isEmpty()) return null
        var wraps = 0
        while (seqBuf.isEmpty()) {
            if (seqIdx >= ms.size) { seqIdx = 0; resumeAfter = null; if (++wraps > 1) return null }
            val key = ms[seqIdx++]
            val merged = LinkedHashMap<String, Pair<Asset, String?>>()
            for ((f, name) in fs) if (key in seqByFilter[f].orEmpty())
                Immich.month(f, true, key).filter { !it.video }.forEach { merged.putIfAbsent(it.id, it to name) }
            var photos = merged.values.sortedBy { it.first.date }.let { if (oldest) it else it.reversed() }
            resumeAfter?.let { r -> photos = photos.filter { if (oldest) it.first.date > r else it.first.date < r } }
            resumeAfter = null
            for ((a, name) in photos) { name?.let { fromName[a.id] = it }; seqBuf.addLast(a) }
        }
        val a = seqBuf.removeFirst()
        Settings.dreamPosition = "$sig\t${a.date}"
        return a
    }

    private fun mark(a: Asset?): Asset? {
        if (a != null) { sourceIds.add(a.id); if (sourceIds.size > 120) sourceIds.remove(sourceIds.first()).also { if (fromName.size > 400) fromName.clear() } }
        return a
    }

    private suspend fun fromBatch(): Asset? {
        if (queue.size < 3) queue.addAll(Immich.random(40, Settings.dreamFrom).filter { !it.video && it.id !in recent && queue.none { q -> q.id == it.id } })
        return queue.removeFirstOrNull()?.let { rememberRecent(it) }
    }

    /** Random photo from one or more sources, weighted by photo count (a large album appears more often). */
    private suspend fun fromMonths(sources: List<Pair<Filter, String?>>): Asset? {
        val fs = sources.map { it.first }
        val sig = "$fs|${Settings.dreamFrom}"
        if (monthsSig != sig || months == null) {
            months = monthsOf(fs); monthsSig = sig; monthCache.clear(); recent.clear()
        }
        val all = months.orEmpty().flatMap { (f, ms) -> ms.map { f to it } }
        val total = all.sumOf { it.second.count }
        if (total == 0) return null
        var last: Asset? = null
        repeat(10) {
            var r = Random.nextInt(total)
            val (f, m) = all.first { r -= it.second.count; r < 0 }
            val list = monthCache.getOrPut("$f|${m.key}") { Immich.month(f, true, m.key) }
            val a = list.filter { !it.video }.randomOrNull() ?: return@repeat
            last = a
            sources.firstOrNull { it.first == f }?.second?.let { fromName[a.id] = it }
            if (a.id !in recent) return rememberRecent(a, total)
        }
        return last?.let { rememberRecent(it, total) }
    }

    private suspend fun fromMemories(): Asset? {
        val today = LocalDate.now()
        if (memories == null || memoryDay != today) {
            memories = Immich.memories(today).flatMap { it.assets }.filter { !it.video }.shuffled()
            memoryDay = today; memoryIndex = 0
        }
        val l = memories ?: return null
        if (l.isEmpty()) return null
        // "from": skip memories before that year
        val from = Settings.dreamFrom
        repeat(l.size) { val a = l[memoryIndex++ % l.size]; if (a.date.year >= from) return a }
        return null
    }

    private fun rememberRecent(a: Asset, total: Int = Int.MAX_VALUE): Asset {
        recent.addLast(a.id)
        val max = (total / 2).coerceIn(2, 80)
        while (recent.size > max) recent.removeFirst()
        return a
    }
}

/** Fetch error -> message: key rejected, source no longer exists (album deleted), or no connection. */
private fun messageFor(e: Throwable): Int {
    val m = e.message.orEmpty()
    return when {
        m.startsWith("Immich 401") || m.startsWith("Immich 403") -> R.string.dream_key_error
        m.startsWith("Immich 4") -> R.string.dream_no_photos
        else -> R.string.dream_no_connection
    }
}

/** Source label for the bottom-left line (album name, "3 years ago", ...); null for "all photos" or fallback. */
@Composable
private fun sourceName(a: Asset, queue: DreamQueue): String? {
    if (Settings.dreamSource == DreamSource.ALL || !queue.isFromSource(a.id)) return null
    return when (Settings.dreamSource) {
        DreamSource.ALL -> null
        DreamSource.ALBUM -> queue.nameOf(a.id) ?: Settings.dreamAlbumNames.singleOrNull()
        DreamSource.PERSON -> queue.nameOf(a.id) ?: Settings.dreamPersonNames.singleOrNull()
        DreamSource.COMBO -> queue.nameOf(a.id)
        DreamSource.FAVORITES -> stringResource(R.string.section_favorites)
        DreamSource.MEMORIES -> (LocalDate.now().year - a.date.year).takeIf { it > 0 }?.let { pluralStringResource(R.plurals.years_ago, it, it) }
    }
}

private val SHADOW = Shadow(Color(0xD9000000), Offset(0f, 2f), 10f)

/** Gentle shift of clock/text (a few dp, every 4 minutes): nothing stays on the exact same pixels for hours. */
private val DRIFT = listOf(Offset(0f, 0f), Offset(10f, 6f), Offset(-6f, 12f), Offset(12f, -4f), Offset(-10f, -8f), Offset(4f, 10f))

/** Trim memory cache after each transition to ~3 previews (current, previous, next); the rest stays on disk. */
private const val MEMORY_LIMIT = 24L * 1024 * 1024

@Composable
fun DreamScreen(state: DreamState) {
    val ctx = LocalContext.current
    val queue = remember { DreamQueue() }
    val seconds = Settings.dreamSeconds

    // Loop: pick photo -> load preview -> show -> wait for timer or key press
    LaunchedEffect(Unit) {
        if (Immich.key.isBlank() || Immich.server.isBlank() || state.testError == "koppeling") { state.message = R.string.dream_not_connected; return@LaunchedEffect }
        val loader = SingletonImageLoader.get(ctx)
        suspend fun nextPhoto(): Asset? = when (state.testError) {
            "leeg" -> null
            "net" -> throw java.net.ConnectException("test")
            "sleutel" -> throw java.io.IOException("Immich 401: test")
            else -> queue.next()
        }
        /** Failure: without a photo show centered message, with a photo only the cloud icon (photo stays). */
        fun fail(m: Int) { if (state.current == null) state.message = m else state.errorIcon = true }
        var direction = 1
        while (isActive) {
            val h = state.history
            var waitMs = 20_000L
            val a: Asset? = when {
                direction < 0 && state.pos > 0 -> { state.pos--; h[state.pos] }
                direction < 0 -> state.current // already at oldest: stay
                direction > 0 && state.pos < h.lastIndex -> { state.pos++; h[state.pos] }
                else -> runCatching { nextPhoto() }.fold(
                    onSuccess = { n -> n?.also { h.add(it); if (h.size > 30) h.removeAt(0); state.pos = h.lastIndex } },
                    onFailure = { e ->
                        Log.w("Immich", "screensaver: ${e.message}")
                        val m = messageFor(e)
                        if (m != R.string.dream_no_connection) waitMs = 60_000L
                        fail(m); null
                    },
                )
            }
            if (a == null) {
                if (state.current == null && state.message == null) state.message = R.string.dream_no_photos
                direction = withTimeoutOrNull(waitMs) { state.request.receive() } ?: 1
                continue
            }
            if (a.id != state.current?.id) {
                // Fetch the preview first (max 8 s), then switch: no half-loaded or blank photo
                val ok = withTimeoutOrNull(8_000) { runCatching { loader.execute(previewRequest(ctx, a.id)) is SuccessResult }.getOrDefault(false) } == true
                if (!ok) {
                    Log.w("Immich", "screensaver: preview ${a.id} not loaded")
                    fail(R.string.dream_no_connection)
                    direction = withTimeoutOrNull(waitMs) { state.request.receive() } ?: 1
                    continue
                }
                state.message = null
                state.errorIcon = false
                state.current = a
                // After transition: keep cache small and prepare the next one
                launch { delay(1800); loader.memoryCache?.trimToSize(MEMORY_LIMIT); queue.lookAhead(ctx) }
            }
            state.round++
            direction = withTimeoutOrNull(Settings.dreamSeconds * 1000L) { state.request.receive() } ?: 1
        }
    }

    // Prevent burn-in: everything static shifts a few dp occasionally
    val driftTarget by produceState(DRIFT[0]) { var i = 0; while (true) { delay(4 * 60_000L); i++; value = DRIFT[i % DRIFT.size] } }
    val drift by animateOffsetAsState(driftTarget, tween(4000), label = "drift")
    val shift = Modifier.offset { IntOffset((drift.x * density).roundToInt(), (drift.y * density).roundToInt()) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }
        val current = state.current
        val kenBurns = Settings.dreamKenBurns

        AnimatedContent(
            targetState = current, contentKey = { it?.id },
            transitionSpec = { fadeIn(tween(900)) togetherWith fadeOut(tween(900)) },
            label = "dream",
        ) { a ->
            val vis by transition.animateFloat(transitionSpec = { tween(900) }, label = "vis") {
                if (it == EnterExitState.Visible) 1f else 0f
            }
            if (a != null) DreamPhoto(a, active = a.id == current?.id, kenBurns = kenBurns, durationMs = seconds * 1000L + 2500, alpha = { vis })
        }

        val dreamMap = Settings.dreamMap.scale
        if (dreamMap > 0f) CountryMapOverlay(target = current?.takeIf { it.lat != null && it.lon != null }, widthPx = widthPx, heightPx = heightPx, sizeScale = dreamMap)

        if (Settings.dreamClock) Clock(Modifier.align(Alignment.TopStart).then(shift).padding(start = 48.dp, top = 36.dp))

        // Bottom-left: ring (time until next photo) + date and location/source
        if (current != null) Row(Modifier.align(Alignment.BottomStart).then(shift).padding(start = 48.dp, bottom = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            val ring = remember { Animatable(0f) }
            LaunchedEffect(state.round) { ring.snapTo(0f); if (state.round > 0) ring.animateTo(1f, tween(seconds * 1000, easing = LinearEasing)) }
            if (Settings.dreamRing) ProgressRing({ ring.value }, Modifier.size(22.dp))
            if (state.errorIcon) Icon(Icons.Filled.CloudOff, null, Modifier.padding(start = if (Settings.dreamRing) 10.dp else 0.dp).size(20.dp), tint = Color(0xB3FFFFFF))
            if (Settings.dreamInfo) Column(Modifier.padding(start = if (Settings.dreamRing || state.errorIcon) 16.dp else 0.dp)) {
                Text(current.date.format(DateFormats.dayLong).replaceFirstChar { it.titlecase(Language.locale) }, style = TextStyle(fontFamily = Overpass, fontSize = 18.sp, color = Color.White, shadow = SHADOW), maxLines = 1)
                // With map, the place is shown near the dot; otherwise here
                val place = listOfNotNull(current.city, current.country).joinToString(", ").takeIf { it.isNotEmpty() && !(Settings.dreamMap != MapSize.OFF && current.lat != null) }
                // Albums containing the photo (excluding the chosen source album, already shown as source name)
                val source = sourceName(current, queue)
                // Albums other than the source(s); with "show other album" those replace the source name ("Birthday" instead of "Screensaver")
                val sources = if (Settings.dreamSource == DreamSource.ALBUM || Settings.dreamSource == DreamSource.COMBO) Settings.dreamAlbumNames.toSet() + listOfNotNull(source) else setOfNotNull(source)
                val other = albumNames(current.id).filter { it !in sources }
                val albums = other.takeIf { it.isNotEmpty() }?.let(::albumLine)
                val showSource = source.takeUnless { albums != null && Settings.dreamOtherAlbum }
                val line = listOfNotNull(place, showSource, albums).joinToString("  ·  ")
                if (line.isNotEmpty()) Text(line, style = TextStyle(fontFamily = Overpass, fontSize = 15.sp, color = Color(0xD9FFFFFF), shadow = SHADOW), maxLines = 1)
            }
        }

        if (current == null) state.message?.let { m ->
            Column(Modifier.align(Alignment.Center).then(shift).padding(horizontal = 64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (m == R.string.dream_no_connection) Icons.Filled.CloudOff else Icons.Filled.PhotoLibrary, null, Modifier.size(44.dp), tint = AppColors.muted)
                Text(stringResource(m), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

/** Clock + date in the app's language; text only updates when the minute changes. */
@Composable
private fun Clock(modifier: Modifier) {
    val now by produceState(LocalDateTime.now()) { while (true) { value = LocalDateTime.now().withSecond(0).withNano(0); delay(1000) } }
    Column(modifier) {
        Text(now.format(DateFormats.time), style = TextStyle(fontFamily = Overpass, fontSize = 64.sp, fontWeight = FontWeight(600), color = Color.White, shadow = SHADOW), maxLines = 1)
        Text(now.format(DateFormats.dayLong).replaceFirstChar { it.titlecase(Language.locale) }, style = TextStyle(fontFamily = Overpass, fontSize = 22.sp, color = Color(0xE6FFFFFF), shadow = SHADOW), maxLines = 1)
    }
}

/**
 * Single photo: landscape photos around 3:2 fill the screen (cropped), the rest fits inside with thumbhash as
 * blurred background. Ken Burns: slow zoom with a gentle pan, only on the active photo (the outgoing one keeps
 * its zoom, otherwise it snaps back during the crossfade).
 */
@Composable
private fun DreamPhoto(a: Asset, active: Boolean, kenBurns: Boolean, durationMs: Long, alpha: () -> Float) {
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha(); compositingStrategy = CompositingStrategy.ModulateAlpha }, contentAlignment = Alignment.Center) {
        val fill = a.ratio in 1.45f..2.0f
        // macOS Photos-style Ken Burns: simultaneous pan and zoom with varied motion paths
        val progress = remember(a.id) { Animatable(0f) }
        val mode = remember(a.id) { kotlin.math.abs(a.id.hashCode()) % 4 }
        LaunchedEffect(a.id, kenBurns) {
            if (kenBurns && active) {
                progress.snapTo(0f)
                progress.animateTo(1f, tween(durationMs.toInt(), easing = LinearEasing))
            }
        }
        val mod = Modifier.fillMaxSize().kenBurnsLayer(kenBurns, mode) { progress.value }
        val hash = remember(a.thumbhash) { a.thumbhash?.let { ThumbHash.bitmap(it) } }
        if (hash != null && !fill && Settings.blurredBackground) {
            Image(hash, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.7f)
            Box(Modifier.fillMaxSize().background(Color(0x8C000000)))
            if (!kenBurns) {
                Image(hash, null, Modifier.aspectRatio(a.ratio.coerceIn(0.3f, 4f), matchHeightConstraintsFirst = true), contentScale = ContentScale.FillBounds)
            }
        }
        AsyncImage(previewImage(a.id), null, mod, contentScale = if (fill) ContentScale.Crop else ContentScale.Fit)
    }
}

/**
 * The screensaver itself. Compose inside a DreamService: ComposeView needs a LifecycleOwner, SavedStateRegistryOwner,
 * and ViewModelStoreOwner in the view tree; the service acts as all three (lifecycle: attached = CREATED,
 * dreaming = RESUMED). Keys go through dispatchKeyEvent (interactive), not through Compose.
 */
class PhotoDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    private val state = DreamState()

    override fun onCreate() {
        super.onCreate()
        // Same process as the app (required, see manifest). If the app is still in memory (e.g. with the world map),
        // release everything possible first: the system treats the whole process as foreground while the screensaver
        // runs, and otherwise killed things on wake (black screen, 21-10). The map itself is removed when the
        // activity stops (MapScreen).
        (application as? App)?.releaseMemory("screensaver")
        savedState.performAttach()
        savedState.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        isScreenBright = true
        val view = ComposeView(Language.wrap(this))
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setContent { PhotosTheme { DreamScreen(state) } }
        setContentView(view)
        isAttached = true
        Log.i("Immich", "screensaver: started (${Settings.dreamSource}, ${Settings.dreamSeconds} s)")
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) state.send(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                return true
            }
            // Let volume keys work normally without closing the screensaver
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> return super.dispatchKeyEvent(event)
        }
        // Any other key: wake on RELEASE (key-up must not reach the app underneath: in a launcher that clicks
        // the focused tile). If held down, or the system ignores wakeUp(), force finish after 1.5 s: the screensaver
        // must never get stuck.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) wakeLatest(1500)
        if (event.action == KeyEvent.ACTION_UP) wake()
        return true
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var woken = false

    private fun wake() {
        if (woken) return
        woken = true
        Log.i("Immich", "screensaver: key press, waking up")
        runCatching { wakeUp() }.onFailure { Log.w("Immich", "screensaver wakeUp", it); finish() }
        // Safety net: still not gone after 2 s -> hard stop
        handler.postDelayed({ if (isAttached) { Log.w("Immich", "screensaver: wakeUp didn't work, finish()"); runCatching { finish() } } }, 2000)
    }

    private fun wakeLatest(ms: Long) = handler.postDelayed({ if (!woken) wake() }, ms)

    private var isAttached = false

    override fun onDreamingStopped() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        isAttached = false
        handler.removeCallbacksAndMessages(null)
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        viewModelStore.clear()
        super.onDetachedFromWindow()
        Log.i("Immich", "screensaver: stopped")
    }
}

/** Settings -> Screensaver -> Preview: same view as a regular activity (also for testing without the system setting). */
class PreviewActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Language.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Immich.selectAccount(this, null)
        val state = DreamState().apply { testError = intent.getStringExtra("test_fout") }
        setContent {
            PhotosTheme {
                Box(
                    Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key.nativeKeyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> { state.send(-1); true }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> { state.send(1); true }
                            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> false
                            else -> { finish(); true }
                        }
                    },
                ) { DreamScreen(state) }
            }
        }
    }
}
