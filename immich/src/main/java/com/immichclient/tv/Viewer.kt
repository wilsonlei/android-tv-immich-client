package com.immichclient.tv

import android.content.Context
import android.os.Handler
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * Test (`--ez video_without_placeholder true`): simulates that this device cannot use a placeholder surface, like
 * Media3 decides for many Fire TV models, among others (codecNeedsSetOutputSurfaceWorkaround). In that case, the decoder only starts
 * when there is a real surface.
 */
@Volatile var videoWithoutPlaceholder = false

class ViewerState(val source: ImageSource, start: Int, val title: String?, slideshow: Boolean) {
    var index by mutableIntStateOf(start)
    var direction = 1
    var slideshow by mutableStateOf(slideshow)
    var info by mutableStateOf(false)
    /** Last key press; the bottom bar disappears after a few seconds. */
    var activeAt by mutableLongStateOf(System.currentTimeMillis())
    var playingVideo by mutableStateOf(false)
    var videoError by mutableStateOf(false)
    var player: ExoPlayer? = null
    /** Next photo of the slideshow, already chosen (and preloaded) as soon as the current one is displayed. */
    var slideshowNext = -1

    /** Random slideshow without repetition (see [Shuffler]). */
    private val shuffler = Shuffler()

    /** Next photo for the slideshow: sequential or random, videos skipped if needed; -1 = done. */
    fun slideshowTarget(): Int {
        val n = source.count
        if (Settings.slideshowRandom)
            return shuffler.next(n, index) { Settings.slideshowVideos || source.at(it)?.video != true }
        var i = index + 1
        while (i < n && !Settings.slideshowVideos && source.at(i)?.video == true) i++
        return if (i < n) i else -1
    }

    /** Advance slideshow by one step (or stop at the end). */
    fun slideshowAdvance() {
        val d = slideshowNext.takeIf { it >= 0 && it != index && it < source.count } ?: slideshowTarget()
        slideshowNext = -1
        if (d < 0) { slideshow = false; return }
        direction = if (Settings.slideshowSlide) 1 else 0
        index = d
        if (Settings.slideshowRandom) shuffler.mark(d)
    }
}

@OptIn(UnstableApi::class)
@Composable
fun Viewer(state: ViewerState, onMap: ((Asset) -> Unit)? = null, close: (Asset?) -> Unit) {
    val ctx = LocalContext.current
    val fr = remember { FocusRequester() }
    val current = state.source.at(state.index)
    var bar by remember { mutableStateOf(Settings.barMs >= 0) }
    var manualBar by remember { mutableStateOf(false) }
    BackHandler {
        if (state.info || manualBar) {
            state.info = false
            manualBar = false
        } else {
            close(state.source.at(state.index))
        }
    }
    LaunchedEffect(Unit) { fr.requestFocus() }
    // Keep screen on during a slideshow: otherwise the system screensaver (which cannot be turned off on
    // devices like the Google TV Streamer) starts in the middle of the slideshow. When paused or stopped, the normal timeout applies again.
    val view = LocalView.current
    DisposableEffect(state.slideshow) {
        view.keepScreenOn = state.slideshow
        onDispose { view.keepScreenOn = false }
    }

    // Hoist a single ExoPlayer instance for the entire viewer session to avoid codec collisions during transitions
    val player = remember(ctx) {
        val buffers = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 2_000,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000,
            )
            .setTargetBufferBytes(32 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(0, false)
            .build()
        val renderers = if (!videoWithoutPlaceholder) DefaultRenderersFactory(ctx) else object : DefaultRenderersFactory(ctx) {
            override fun buildVideoRenderers(
                context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
                eventHandler: Handler, eventListener: VideoRendererEventListener, allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>,
            ) {
                out.add(object : MediaCodecVideoRenderer(context, codecAdapterFactory, mediaCodecSelector, allowedVideoJoiningTimeMs,
                    enableDecoderFallback, eventHandler, eventListener, 50) {
                    override fun codecNeedsSetOutputSurfaceWorkaround(name: String) = true
                })
            }
        }
        ExoPlayer.Builder(ctx, renderers)
            .setLoadControl(buffers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Immich.http)))
            .build()
    }

    DisposableEffect(player) {
        state.player = player
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                state.playingVideo = isPlaying
            }
            override fun onPlaybackStateChanged(s: Int) {
                if (s == Player.STATE_ENDED) {
                    state.playingVideo = false
                    if (state.slideshow) state.slideshowAdvance()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val cur = state.source.at(state.index)
                Log.w("Immich", "video error for ${cur?.id}", error)
                state.playingVideo = false
                state.videoError = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.stop()
            player.release()
            state.player = null
            state.playingVideo = false
        }
    }

    // Play active video item or stop when viewing photo
    LaunchedEffect(state.index) {
        state.videoError = false
        val item = state.source.at(state.index)
        if (item?.video == true) {
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(MediaItem.fromUri(Immich.video(item.id)))
            player.prepare()
            player.playWhenReady = true
        } else {
            player.stop()
            player.clearMediaItems()
            state.playingVideo = false
        }
    }

    // Slideshow: if a video fails to play, proceed after 3 seconds instead of hanging
    LaunchedEffect(state.videoError, state.slideshow) {
        if (state.videoError && state.slideshow) {
            delay(3000)
            if (state.slideshow && state.videoError) state.slideshowAdvance()
        }
    }

    fun go(d: Int) {
        val n = (state.index + d)
        if (n < 0 || n >= state.source.count) return
        if (current?.video == true) { player.stop(); state.playingVideo = false }
        state.direction = d
        state.index = n
        state.activeAt = System.currentTimeMillis()
    }

    // Preload neighbors, frugally: first the next one, then the previous one, and only if you stay, one further.
    // Suspended during video to leave bandwidth and connections entirely for video streaming.
    LaunchedEffect(state.index, state.playingVideo, state.slideshow) {
        if (state.playingVideo || current?.video == true) return@LaunchedEffect
        // Random slideshow: neighbors are not the next one; the slideshow loop preloads that itself
        if (state.slideshow && Settings.slideshowRandom) return@LaunchedEffect
        val loader = SingletonImageLoader.get(ctx)
        fun load(d: Int) { state.source.at(state.index + d)?.takeIf { !it.video }?.let { loader.enqueue(previewRequest(ctx, it.id)) } }
        load(1)
        delay(300); load(-1)
        delay(700); load(2)
    }
    // Slideshow: choose and preload the next one in advance (also for random or skipped videos), then proceed
    LaunchedEffect(state.slideshow, state.index, state.playingVideo) {
        if (!state.slideshow || state.playingVideo || current?.video == true) return@LaunchedEffect
        val t0 = System.currentTimeMillis()
        val target = state.slideshowTarget()
        state.slideshowNext = target
        if (target >= 0) {
            val loader = SingletonImageLoader.get(ctx)
            // The month of a random photo might not be loaded yet (at() triggers that): retry a bit later
            for (attempt in 0 until 3) {
                val a = state.source.at(target)
                if (a != null) { if (!a.video) loader.enqueue(previewRequest(ctx, a.id)); break }
                delay(500)
            }
        }
        delay((Settings.slideshowMs - (System.currentTimeMillis() - t0)).coerceAtLeast(300))
        state.slideshowAdvance()
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(fr).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val k = e.key.nativeKeyCode
            when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { state.slideshow = false; go(-1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { state.slideshow = false; go(1); true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_INFO -> {
                    state.info = !state.info; true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (manualBar || (bar && !state.playingVideo)) {
                        bar = false
                        manualBar = false
                    } else {
                        manualBar = true
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    val sp = state.player
                    // Info panel open on a photo with a location: OK jumps to the world map
                    if (state.info && current?.video != true && onMap != null && current != null && current.lat != null) onMap(current)
                    else if (state.info) {
                        state.slideshow = !state.slideshow
                        if (state.slideshow) state.info = false
                        if (current?.video == true && sp != null) sp.playWhenReady = state.slideshow
                    }
                    else if (current?.video == true && sp != null && !state.slideshow) sp.playWhenReady = !sp.playWhenReady
                    else {
                        state.slideshow = !state.slideshow
                        if (current?.video == true && sp != null) sp.playWhenReady = state.slideshow
                    }
                    state.activeAt = System.currentTimeMillis(); true
                }
                // Fast-forward / Rewind: 10 s in a video, otherwise 10 photos
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { state.player?.takeIf { current?.video == true }?.let { it.seekTo(it.currentPosition + 10_000) } ?: go(10); true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { state.player?.takeIf { current?.video == true }?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) } ?: go(-10); true }
                else -> false
            }
        },
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }

        val kenBurnsTracker = remember { KenBurnsTracker() }
        AnimatedContent(
            targetState = state.index,
            transitionSpec = {
                val spec = if (state.slideshow && !Settings.slideshowSlide) {
                    slideshowFadeTransform()
                } else {
                    slideshowSlideTransform(state.direction)
                }
                spec using null
            },
            label = "media",
        ) { i ->
            val a = state.source.at(i)
            when {
                a == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted) }
                a.video -> VideoView(a, state, player)
                else -> PhotoFrame(
                    a = a,
                    active = i == state.index,
                    kenBurns = state.slideshow && Settings.kenBurns,
                    durationMs = Settings.slideshowMs.toInt(),
                    kenBurnsTracker = kenBurnsTracker,
                )
            }
        }

        // Map (like in a photo frame screensaver): only for photos with GPS, not for video or info. The layer itself waits briefly
        // on a new country and, within the same country, only slides the dot. Setting: off / small / large.
        val mapScale = Settings.viewerMap.scale
        CountryMapOverlay(
            target = current?.takeIf { mapScale > 0f && !it.video && it.lat != null && it.lon != null && !state.info },
            widthPx = widthPx, heightPx = heightPx, sizeScale = mapScale,
        )

        // Bottom bar: date and location, disappears automatically (configurable, 0 = always, -1 = off)
        LaunchedEffect(state.activeAt, state.index) {
            val ms = Settings.barMs
            if (ms < 0) bar = false
            else { bar = true; if (ms > 0) { delay(ms.toLong()); bar = false } }
        }
        AnimatedVisibility(
            (manualBar || (bar && (!state.playingVideo || Settings.barMs == 0))) && current != null, Modifier.align(Alignment.BottomStart),
            enter = fadeIn(), exit = fadeOut(),
        ) {
            if (current != null) BottomBar(current, state, withMap = current.lat != null && !current.video && mapScale > 0f)
        }
        AnimatedVisibility(
            state.info && current != null, Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            if (current != null) InfoPanel(current, onMap != null && current.lat != null)
        }
        // Top right slideshow: a badge (play icon + "Slideshow") when starting, and briefly "Slideshow paused" when
        // stopping (OK, or manual browsing); not on every automatic transition. It fades out after a few seconds.
        // The progress ring keeps its fixed place: the badge only fades (alpha) and retains its space.
        var badge by remember { mutableStateOf<Boolean?>(null) }
        var badgeRound by remember { mutableIntStateOf(0) }
        var firstTime by remember { mutableStateOf(true) }
        LaunchedEffect(state.slideshow) {
            // Viewer opened without slideshow: no "paused"
            if (firstTime) { firstTime = false; if (!state.slideshow) return@LaunchedEffect }
            badge = state.slideshow; badgeRound++
        }
        LaunchedEffect(badgeRound) { if (badge != null) { delay(if (badge == true) 5000 else 3000); badge = null } }
        Row(Modifier.align(Alignment.TopEnd).padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            val b = badge
            val badgeAlpha by animateFloatAsState(if (b != null) 1f else 0f, tween(450), label = "badge")
            var lastBadge by remember { mutableStateOf(true) } // last content, so it fades out with that text
            if (b != null) lastBadge = b
            Row(
                Modifier.padding(end = 10.dp).graphicsLayer { alpha = badgeAlpha }
                    .background(Color(0xA6000000), RoundedCornerShape(14.dp)).padding(start = 9.dp, end = 13.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (lastBadge) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, Modifier.size(17.dp), tint = Color.White)
                Spacer(Modifier.width(5.dp))
                Text(stringResource(if (lastBadge) R.string.slideshow else R.string.slideshow_paused), style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
            }
        }
    }
}

@Composable
private fun VideoView(a: Asset, state: ViewerState, player: ExoPlayer) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        key(a.id) {
            AndroidView(
                factory = { c ->
                    SurfaceView(c).apply {
                        player.setVideoSurfaceView(this)
                    }
                },
                onRelease = { sv ->
                    player.clearVideoSurfaceView(sv)
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .aspectRatio(a.ratio.coerceIn(0.2f, 5f), matchHeightConstraintsFirst = true),
            )
        }
        if (state.videoError) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.VideocamOff, null, Modifier.size(44.dp), tint = AppColors.muted)
            Text(stringResource(R.string.video_error), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted, modifier = Modifier.padding(top = 8.dp))
        }
        // Thin progress bar at the bottom
        val fraction by produceState(0f, a.id, player) {
            while (true) {
                val d = player.duration
                value = if (d > 0) player.currentPosition.toFloat() / d else 0f
                delay(100)
            }
        }
        val smoothFraction by animateFloatAsState(
            targetValue = fraction,
            animationSpec = tween(durationMillis = 100, easing = LinearEasing),
            label = "progress",
        )
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x33FFFFFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(smoothFraction.coerceIn(0f, 1f)).background(AppColors.primary))
        }
    }
}

@Composable
private fun BottomBar(a: Asset, state: ViewerState, withMap: Boolean) {
    Box(Modifier.padding(start = 40.dp, bottom = 28.dp)) {
        PhotoInfoBar(
            a = a,
            withMap = withMap,
            title = state.title,
            counter = if (Settings.counter) "${state.index + 1} / ${Language.number(state.source.count)}" else null,
        )
    }
}

@Composable
private fun InfoPanel(a: Asset, withMapJump: Boolean) {
    val details by produceState<Details?>(null, a.id) { value = runCatching { Immich.details(a.id) }.getOrNull() }
    Column(Modifier.fillMaxHeight().width(380.dp).background(Color(0xF0121214)).padding(28.dp)) {
        // The hint row is fixed at the bottom; content above it may exceed screen height (many people, long
        // description) and scrolls instead of pushing the hint off screen. Long lists are truncated.
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(stringResource(R.string.info), style = MaterialTheme.typography.headlineSmall)
            InfoRow(Icons.Filled.Schedule, dayLong(a.date), null)
            val d = details
            if (d != null) {
                d.description?.let { Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                if (d.people.isNotEmpty()) InfoRow(Icons.Filled.People, d.people.joinToString(", "), null, maxLines = 3)
                val albums = albumNames(a.id)
                if (albums.isNotEmpty()) InfoRow(Icons.Filled.PhotoLibrary, albums.joinToString(", "), null, maxLines = 3)
                d.place?.let { InfoRow(Icons.Filled.Place, it, null) }
                if (a.lat != null && a.lon != null) {
                    SmallCountryMap(a.lat, a.lon, Modifier.fillMaxWidth().height(150.dp).background(AppColors.surface, RoundedCornerShape(12.dp)))
                }
                InfoRow(Icons.Filled.Image, d.filename, d.dimensions)
                d.camera?.let { InfoRow(Icons.Filled.CameraAlt, it, listOfNotNull(d.lens, d.settings).joinToString("\n").ifEmpty { null }) }
            } else {
                Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, null, Modifier.size(16.dp), tint = AppColors.muted)
            Text("  " + stringResource(if (withMapJump) R.string.hint_info_map else R.string.hint_info), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, sub: String?, maxLines: Int = Int.MAX_VALUE) {
    Row {
        Icon(icon, null, Modifier.size(22.dp).padding(top = 2.dp), tint = AppColors.muted)
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Albums containing this photo (empty while loading or if there are none). */
@Composable
fun albumNames(id: String): List<String> =
    produceState(emptyList(), id) { delay(150); value = Immich.albumsOf(id) }.value

/** "Vacation 2023", or "Vacation 2023 + 2" when in multiple albums. */
fun albumLine(names: List<String>) = if (names.size <= 1) names.firstOrNull().orEmpty() else names[0] + "  + " + (names.size - 1)
