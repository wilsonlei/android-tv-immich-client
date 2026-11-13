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
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Place
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.VideoSize
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * Test (`--ez video_zonder_placeholder true`): simulates that this device cannot use a placeholder surface, like
 * Media3 decides for many Fire TV models, among others (codecNeedsSetOutputSurfaceWorkaround). In that case, the decoder only starts
 * when there is a real surface.
 */
@Volatile var videoZonderPlaceholder = false

class KijkerStaat(val bron: BeeldBron, start: Int, val titel: String?, diashow: Boolean) {
    var index by mutableIntStateOf(start)
    var richting = 1
    var diashow by mutableStateOf(diashow)
    var info by mutableStateOf(false)
    /** last key press; the bottom bar disappears after a few seconds */
    var actief by mutableLongStateOf(System.currentTimeMillis())
    var speeltVideo by mutableStateOf(false)
    var videoFout by mutableStateOf(false)
    var speler: ExoPlayer? = null
    /** next photo of the slideshow, already chosen (and preloaded) as soon as the current one is displayed */
    var diaVolgende = -1

    /** random slideshow without repetition (see [Schudder]) */
    private val schudder = Schudder()

    /** Next photo for the slideshow: sequential or random, videos skipped if needed; -1 = done. */
    fun diaDoel(): Int {
        val n = bron.aantal
        if (Instellingen.diaWillekeurig)
            return schudder.volgende(n, index) { Instellingen.diaVideos || bron.op(it)?.video != true }
        var i = index + 1
        while (i < n && !Instellingen.diaVideos && bron.op(i)?.video == true) i++
        return if (i < n) i else -1
    }

    /** Advance slideshow by one step (or stop at the end). */
    fun diaVerder() {
        val d = diaVolgende.takeIf { it >= 0 && it != index && it < bron.aantal } ?: diaDoel()
        diaVolgende = -1
        if (d < 0) { diashow = false; return }
        richting = if (Instellingen.diaSchuiven) 1 else 0
        index = d
        if (Instellingen.diaWillekeurig) schudder.markeer(d)
    }
}

@OptIn(UnstableApi::class)
@Composable
fun Kijker(staat: KijkerStaat, opKaart: ((Asset) -> Unit)? = null, sluit: (Asset?) -> Unit) {
    val ctx = LocalContext.current
    val fr = remember { FocusRequester() }
    val huidig = staat.bron.op(staat.index)
    var balk by remember { mutableStateOf(Instellingen.balkMs >= 0) }
    var handBalk by remember { mutableStateOf(false) }
    BackHandler {
        if (staat.info || handBalk) {
            staat.info = false
            handBalk = false
        } else {
            sluit(staat.bron.op(staat.index))
        }
    }
    LaunchedEffect(Unit) { fr.requestFocus() }
    // Keep screen on during a slideshow: otherwise the system screensaver (which cannot be turned off on
    // devices like the Google TV Streamer) starts in the middle of the slideshow. When paused or stopped, the normal timeout applies again.
    val view = LocalView.current
    DisposableEffect(staat.diashow) {
        view.keepScreenOn = staat.diashow
        onDispose { view.keepScreenOn = false }
    }

    // Hoist a single ExoPlayer instance for the entire viewer session to avoid codec collisions during transitions
    val speler = remember(ctx) {
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
        val renderers = if (!videoZonderPlaceholder) DefaultRenderersFactory(ctx) else object : DefaultRenderersFactory(ctx) {
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

    DisposableEffect(speler) {
        staat.speler = speler
        val luister = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                staat.speeltVideo = isPlaying
            }
            override fun onPlaybackStateChanged(s: Int) {
                if (s == Player.STATE_ENDED) {
                    staat.speeltVideo = false
                    if (staat.diashow) staat.diaVerder()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                val cur = staat.bron.op(staat.index)
                Log.w("Fotos", "video error for ${cur?.id}", error)
                staat.speeltVideo = false
                staat.videoFout = true
            }
        }
        speler.addListener(luister)
        onDispose {
            speler.removeListener(luister)
            speler.stop()
            speler.release()
            staat.speler = null
            staat.speeltVideo = false
        }
    }

    // Play active video item or stop when viewing photo
    LaunchedEffect(staat.index) {
        staat.videoFout = false
        val item = staat.bron.op(staat.index)
        if (item?.video == true) {
            speler.stop()
            speler.clearMediaItems()
            speler.setMediaItem(MediaItem.fromUri(Immich.video(item.id)))
            speler.prepare()
            speler.playWhenReady = true
        } else {
            speler.stop()
            speler.clearMediaItems()
            staat.speeltVideo = false
        }
    }

    // Slideshow: if a video fails to play, proceed after 3 seconds instead of hanging
    LaunchedEffect(staat.videoFout, staat.diashow) {
        if (staat.videoFout && staat.diashow) {
            delay(3000)
            if (staat.diashow && staat.videoFout) staat.diaVerder()
        }
    }

    fun ga(d: Int) {
        val n = (staat.index + d)
        if (n < 0 || n >= staat.bron.aantal) return
        if (huidig?.video == true) { speler.stop(); staat.speeltVideo = false }
        staat.richting = d
        staat.index = n
        staat.actief = System.currentTimeMillis()
    }

    // Preload neighbors, frugally: first the next one, then the previous one, and only if you stay, one further.
    // Suspended during video to leave bandwidth and connections entirely for video streaming.
    LaunchedEffect(staat.index, staat.speeltVideo, staat.diashow) {
        if (staat.speeltVideo || huidig?.video == true) return@LaunchedEffect
        // random slideshow: neighbors are not the next one; the slideshow loop preloads that itself
        if (staat.diashow && Instellingen.diaWillekeurig) return@LaunchedEffect
        val loader = SingletonImageLoader.get(ctx)
        fun laad(d: Int) { staat.bron.op(staat.index + d)?.takeIf { !it.video }?.let { loader.enqueue(previewVerzoek(ctx, it.id)) } }
        laad(1)
        delay(300); laad(-1)
        delay(700); laad(2)
    }
    // slideshow: choose and preload the next one in advance (also for random or skipped videos), then proceed
    LaunchedEffect(staat.diashow, staat.index, staat.speeltVideo) {
        if (!staat.diashow || staat.speeltVideo || huidig?.video == true) return@LaunchedEffect
        val t0 = System.currentTimeMillis()
        val doel = staat.diaDoel()
        staat.diaVolgende = doel
        if (doel >= 0) {
            val loader = SingletonImageLoader.get(ctx)
            // the month of a random photo might not be loaded yet (op() triggers that): retry a bit later
            for (poging in 0 until 3) {
                val a = staat.bron.op(doel)
                if (a != null) { if (!a.video) loader.enqueue(previewVerzoek(ctx, a.id)); break }
                delay(500)
            }
        }
        delay((Instellingen.diaMs - (System.currentTimeMillis() - t0)).coerceAtLeast(300))
        staat.diaVerder()
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(fr).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val k = e.key.nativeKeyCode
            when (k) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { staat.diashow = false; ga(-1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { staat.diashow = false; ga(1); true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_INFO -> {
                    staat.info = !staat.info; true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (handBalk || (balk && !staat.speeltVideo)) {
                        balk = false
                        handBalk = false
                    } else {
                        handBalk = true
                    }
                    true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    val sp = staat.speler
                    // info panel open on a photo with a location: OK jumps to the world map
                    if (staat.info && huidig?.video != true && opKaart != null && huidig != null && huidig.lat != null) opKaart(huidig)
                    else if (staat.info) {
                        staat.diashow = !staat.diashow
                        if (staat.diashow) staat.info = false
                        if (huidig?.video == true && sp != null) sp.playWhenReady = staat.diashow
                    }
                    else if (huidig?.video == true && sp != null && !staat.diashow) sp.playWhenReady = !sp.playWhenReady
                    else {
                        staat.diashow = !staat.diashow
                        if (huidig?.video == true && sp != null) sp.playWhenReady = staat.diashow
                    }
                    staat.actief = System.currentTimeMillis(); true
                }
                // ⏩ / ⏪: 10 s in a video, otherwise 10 photos
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { staat.speler?.takeIf { huidig?.video == true }?.let { it.seekTo(it.currentPosition + 10_000) } ?: ga(10); true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { staat.speler?.takeIf { huidig?.video == true }?.let { it.seekTo((it.currentPosition - 10_000).coerceAtLeast(0)) } ?: ga(-10); true }
                else -> false
            }
        },
    ) {
        val dicht = LocalDensity.current
        val breedtePx = with(dicht) { maxWidth.roundToPx() }
        val hoogtePx = with(dicht) { maxHeight.roundToPx() }

        // Deliberately no fadeIn/fadeOut here: they set up a full-screen offscreen layer (8 MB) per page and HWUI retains
        // those layers in its cache. The crossfade is in Beeld (ModulateAlpha = alpha per draw call, without a layer);
        // the slide/zoom only determines how long the old page continues to exist.
        AnimatedContent(
            targetState = staat.index,
            transitionSpec = {
                if (staat.diashow) {
                    if (Instellingen.diaSchuiven) {
                        slideInHorizontally(tween(400)) { it / 6 * staat.richting.coerceIn(-1, 1) } togetherWith
                            slideOutHorizontally(tween(400)) { -it / 6 * staat.richting.coerceIn(-1, 1) }
                    } else {
                        fadeIn(tween(900)) togetherWith fadeOut(tween(900))
                    }
                } else {
                    slideInHorizontally(tween(260)) { it / 6 * staat.richting.coerceIn(-1, 1) } togetherWith
                        slideOutHorizontally(tween(260)) { -it / 6 * staat.richting.coerceIn(-1, 1) }
                }
            },
            label = "foto",
        ) { i ->
            val a = staat.bron.op(i)
            val zicht by transition.animateFloat(transitionSpec = { tween(if (staat.richting == 0) 900 else 200) }, label = "zicht") {
                if (it == EnterExitState.Visible) 1f else 0f
            }
            if (a != null) Beeld(a, staat, actief = i == staat.index, zicht = { zicht })
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.laden), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht) }
        }

        // Animated video transition: follows Settings (slide vs fade) during slideshow, slides on manual navigation
        AnimatedContent(
            targetState = if (huidig?.video == true) staat.index else null,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val spec = if (initialState == null || targetState == null) {
                    fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                } else if (staat.diashow) {
                    if (Instellingen.diaSchuiven) {
                        slideInHorizontally(tween(400)) { it * staat.richting.coerceIn(-1, 1) } togetherWith
                            slideOutHorizontally(tween(400)) { -it * staat.richting.coerceIn(-1, 1) }
                    } else {
                        fadeIn(tween(600)) togetherWith fadeOut(tween(600))
                    }
                } else {
                    slideInHorizontally(tween(260)) { it * staat.richting.coerceIn(-1, 1) } togetherWith
                        slideOutHorizontally(tween(260)) { -it * staat.richting.coerceIn(-1, 1) }
                }
                spec using null
            },
            label = "video_transitie",
        ) { targetIdx ->
            val a = targetIdx?.let { staat.bron.op(it) }
            if (a != null && a.video) {
                VideoScherm(a, staat, speler)
            } else {
                Spacer(Modifier.fillMaxSize())
            }
        }

        // Map (like in a photo frame screensaver): only for photos with GPS, not for video or info. The layer itself waits briefly
        // on a new country and, within the same country, only slides the dot. Setting: off / small / large.
        val kaartMaat = Instellingen.viewerKaart.schaal
        KaartLaag(
            doel = huidig?.takeIf { kaartMaat > 0f && !it.video && it.lat != null && it.lon != null && !staat.info },
            breedtePx = breedtePx, hoogtePx = hoogtePx, maat = kaartMaat,
        )

        // bottom bar: date and location, disappears automatically (configurable, 0 = always, -1 = off)
        LaunchedEffect(staat.actief, staat.index) {
            val ms = Instellingen.balkMs
            if (ms < 0) balk = false
            else { balk = true; if (ms > 0) { delay(ms.toLong()); balk = false } }
        }
        AnimatedVisibility(
            (handBalk || (balk && !staat.speeltVideo)) && huidig != null, Modifier.align(Alignment.BottomStart),
            enter = fadeIn(), exit = fadeOut(),
        ) {
            if (huidig != null) OnderBalk(huidig, staat, metKaart = huidig.lat != null && !huidig.video && kaartMaat > 0f)
        }
        AnimatedVisibility(
            staat.info && huidig != null, Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            if (huidig != null) InfoPaneel(huidig, opKaart != null && huidig.lat != null)
        }
        // Top right slideshow: a badge (play icon + "Slideshow") when starting, and briefly "Slideshow paused" when
        // stopping (OK, or manual browsing); not on every automatic transition. It fades out after a few seconds.
        // The progress ring keeps its fixed place: the badge only fades (alpha) and retains its space.
        var badge by remember { mutableStateOf<Boolean?>(null) }
        var badgeRonde by remember { mutableIntStateOf(0) }
        var eersteKeer by remember { mutableStateOf(true) }
        LaunchedEffect(staat.diashow) {
            // viewer opened without slideshow: no "paused"
            if (eersteKeer) { eersteKeer = false; if (!staat.diashow) return@LaunchedEffect }
            badge = staat.diashow; badgeRonde++
        }
        LaunchedEffect(badgeRonde) { if (badge != null) { delay(if (badge == true) 5000 else 3000); badge = null } }
        Row(Modifier.align(Alignment.TopEnd).padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            val b = badge
            val badgeAlpha by animateFloatAsState(if (b != null) 1f else 0f, tween(450), label = "badge")
            var laatste by remember { mutableStateOf(true) } // last content, so it fades out with that text
            if (b != null) laatste = b
            Row(
                Modifier.padding(end = 10.dp).graphicsLayer { alpha = badgeAlpha }
                    .background(Color(0xA6000000), RoundedCornerShape(14.dp)).padding(start = 9.dp, end = 13.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (laatste) Icons.Filled.PlayArrow else Icons.Filled.Pause, null, Modifier.size(17.dp), tint = Color.White)
                Spacer(Modifier.width(5.dp))
                Text(stringResource(if (laatste) R.string.diashow else R.string.diashow_pauze), style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
            }
            AnimatedVisibility(staat.diashow && Instellingen.diaRing, enter = fadeIn(), exit = fadeOut()) { DiaRing(staat) }
        }
    }
}

/** Ring that fills up over the slideshow duration and restarts for each photo; pauses while a video is playing. */
@Composable
private fun DiaRing(staat: KijkerStaat) {
    val deel = remember { Animatable(0f) }
    LaunchedEffect(staat.index, staat.speeltVideo) {
        deel.snapTo(0f)
        if (!staat.speeltVideo) deel.animateTo(1f, tween(Instellingen.diaMs.toInt(), easing = LinearEasing))
    }
    VoortgangRing({ deel.value })
}

@Composable
private fun Beeld(a: Asset, staat: KijkerStaat, actief: Boolean, zicht: () -> Float) {
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = zicht(); compositingStrategy = CompositingStrategy.ModulateAlpha }, contentAlignment = Alignment.Center) {
        val hash = remember(a.thumbhash) { a.thumbhash?.let { ThumbHash.bitmap(it) } }
        if (a.video) {
            // video background: thumbhash stretched full screen darkened
            if (hash != null && Instellingen.wazig) {
                Image(hash, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.7f)
                Box(Modifier.fillMaxSize().background(Color(0x8C000000)))
            }
        } else {
            val isKenBurns = staat.diashow && Instellingen.kenBurns
            val vullen = isKenBurns && a.ratio in 1.3f..2.1f
            val schaal = if (vullen) ContentScale.Crop else ContentScale.Fit

            // 1) thumbhash (blur layer)
            if (hash != null && (!vullen && Instellingen.wazig)) {
                // background instead of black bars: thumbhash (32 px) stretched full screen = free 'blur', darkened
                Image(hash, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.7f)
                Box(Modifier.fillMaxSize().background(Color(0x8C000000)))
                if (!isKenBurns) {
                    val vorm = Modifier.aspectRatio(a.ratio.coerceIn(0.3f, 4f), matchHeightConstraintsFirst = true)
                    Image(hash, null, vorm, contentScale = ContentScale.FillBounds)
                }
            }

            // macOS Photos-style Ken Burns: simultaneous pan and zoom with varied motion paths
            val voortgang = remember(a.id) { Animatable(0f) }
            val mode = remember(a.id) { kotlin.math.abs(a.id.hashCode()) % 4 }
            LaunchedEffect(a.id, staat.diashow, Instellingen.kenBurns) {
                if (staat.diashow && Instellingen.kenBurns) {
                    if (actief) {
                        voortgang.snapTo(0f)
                        voortgang.animateTo(1f, tween((Instellingen.diaMs + 2000).toInt(), easing = LinearEasing))
                    }
                } else if (voortgang.value != 0f) {
                    voortgang.animateTo(0f, tween(500, easing = FastOutSlowInEasing))
                }
            }

            val p = voortgang.value
            val scaleVal = if (!isKenBurns) 1f else when (mode) {
                0, 1 -> 1.05f + 0.12f * p     // Zoom In (1.05x -> 1.17x)
                else -> 1.17f - 0.12f * p     // Zoom Out (1.17x -> 1.05x)
            }
            val panXVal = if (!isKenBurns) 0f else when (mode) {
                0 -> -0.025f + 0.05f * p      // Pan Left to Right
                1 -> 0.025f - 0.05f * p       // Pan Right to Left
                2 -> -0.02f + 0.04f * p       // Pan Left to Right
                else -> 0.02f - 0.04f * p      // Pan Right to Left
            }
            val panYVal = if (!isKenBurns) 0f else when (mode) {
                0 -> -0.015f + 0.03f * p      // Pan Top to Bottom
                1 -> 0.015f - 0.03f * p       // Pan Bottom to Top
                2 -> 0.015f - 0.03f * p       // Pan Bottom to Top
                else -> -0.015f + 0.03f * p   // Pan Top to Bottom
            }

            val mod = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scaleVal
                scaleY = scaleVal
                translationX = panXVal * size.width
                translationY = panYVal * size.height
            }
            if (!isKenBurns) AsyncImage(beeld(Immich.thumb(a.id)), null, mod, contentScale = schaal)
            AsyncImage(preview(a.id), null, mod, contentScale = schaal)
        }
    }
}

@Composable
private fun VideoScherm(a: Asset, staat: KijkerStaat, speler: ExoPlayer) {
    var verhouding by remember(a.id) { mutableFloatStateOf(a.ratio) }
    DisposableEffect(a.id, speler) {
        val luister = object : Player.Listener {
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.width > 0 && v.height > 0) {
                    verhouding = v.width * v.pixelWidthHeightRatio / v.height
                }
            }
        }
        speler.addListener(luister)
        onDispose { speler.removeListener(luister) }
    }
    Box(Modifier.fillMaxSize()) {
        key(a.id) {
            AndroidView(
                factory = { c ->
                    SurfaceView(c).apply {
                        speler.setVideoSurfaceView(this)
                    }
                },
                onRelease = { sv ->
                    speler.clearVideoSurfaceView(sv)
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .aspectRatio(verhouding.coerceIn(0.2f, 5f), matchHeightConstraintsFirst = true),
            )
        }
        if (staat.videoFout) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.VideocamOff, null, Modifier.size(44.dp), tint = Kleur.zacht)
            Text(stringResource(R.string.video_fout), style = MaterialTheme.typography.bodyLarge, color = Kleur.zacht, modifier = Modifier.padding(top = 8.dp))
        }
        // thin progress bar at the bottom
        val deel by produceState(0f, a.id, speler) {
            while (true) {
                val d = speler.duration
                value = if (d > 0) speler.currentPosition.toFloat() / d else 0f
                delay(100)
            }
        }
        val vloeiendDeel by animateFloatAsState(
            targetValue = deel,
            animationSpec = tween(durationMillis = 100, easing = LinearEasing),
            label = "voortgang",
        )
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x33FFFFFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(vloeiendDeel.coerceIn(0f, 1f)).background(Kleur.primair))
        }
    }
}

@Composable
private fun OnderBalk(a: Asset, staat: KijkerStaat, metKaart: Boolean) {
    Box(Modifier.fillMaxWidth().height(170.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))) {
        Column(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 28.dp)) {
            // counter near the title, on the left: map is at bottom-right (it overlapped the counter)
            Row(verticalAlignment = Alignment.Bottom) {
                staat.titel?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = Kleur.primair, modifier = Modifier.padding(end = 12.dp)) }
                if (Instellingen.teller) Text("${staat.index + 1} / ${Taal.getal(staat.bron.aantal)}", style = MaterialTheme.typography.labelMedium, color = Color(0xAAFFFFFF), modifier = Modifier.padding(bottom = 1.dp))
            }
            Text(datumLang(a.datum), style = MaterialTheme.typography.titleLarge, color = Color.White)
            val plaats = listOfNotNull(a.stad, a.land).joinToString(", ")
            // with map (photo with GPS), the location is shown near the dot
            if (plaats.isNotEmpty() && !metKaart) Text(plaats, style = MaterialTheme.typography.bodyLarge, color = Color(0xDDFFFFFF))
            // album(s) containing the photo; exclude the album currently being viewed
            if (Instellingen.albumNaam) {
                val namen = albumNamen(a.id).filter { it != staat.titel }
                if (namen.isNotEmpty()) Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(16.dp), tint = Color(0xBBFFFFFF))
                    Text("  " + albumRegel(namen), style = MaterialTheme.typography.bodyMedium, color = Color(0xBBFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun InfoPaneel(a: Asset, metKaartSprong: Boolean) {
    val details by produceState<Details?>(null, a.id) { value = runCatching { Immich.details(a.id) }.getOrNull() }
    Column(Modifier.fillMaxHeight().width(380.dp).background(Color(0xF0121214)).padding(28.dp)) {
        // The hint row is fixed at the bottom; content above it may exceed screen height (many people, long
        // description) and scrolls instead of pushing the hint off screen. Long lists are truncated.
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(stringResource(R.string.info), style = MaterialTheme.typography.headlineSmall)
            InfoRegel(Icons.Filled.Schedule, datumLang(a.datum), null)
            val d = details
            if (d != null) {
                d.omschrijving?.let { Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                if (d.mensen.isNotEmpty()) InfoRegel(Icons.Filled.People, d.mensen.joinToString(", "), null, maxRegels = 3)
                val albums = albumNamen(a.id)
                if (albums.isNotEmpty()) InfoRegel(Icons.Filled.PhotoLibrary, albums.joinToString(", "), null, maxRegels = 3)
                d.plaats?.let { InfoRegel(Icons.Filled.Place, it, null) }
                if (a.lat != null && a.lon != null) {
                    KaartKlein(a.lat, a.lon, Modifier.fillMaxWidth().height(150.dp).background(Kleur.vlak, RoundedCornerShape(12.dp)))
                }
                InfoRegel(Icons.Filled.Image, d.bestand, d.afmeting)
                d.camera?.let { InfoRegel(Icons.Filled.CameraAlt, it, listOfNotNull(d.lens, d.instellingen).joinToString("\n").ifEmpty { null }) }
            } else {
                Text(stringResource(R.string.laden), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, null, Modifier.size(16.dp), tint = Kleur.zacht)
            Text("  " + stringResource(if (metKaartSprong) R.string.hint_info_kaart else R.string.hint_info), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InfoRegel(icoon: ImageVector, titel: String, sub: String?, maxRegels: Int = Int.MAX_VALUE) {
    Row {
        Icon(icoon, null, Modifier.size(22.dp).padding(top = 2.dp), tint = Kleur.zacht)
        Column(Modifier.padding(start = 14.dp)) {
            Text(titel, style = MaterialTheme.typography.bodyLarge, maxLines = maxRegels, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Albums containing this photo (empty while loading or if there are none). */
@Composable
fun albumNamen(id: String): List<String> =
    produceState(emptyList(), id) { delay(150); value = Immich.albumsVan(id) }.value

/** "Vacation 2023", or "Vacation 2023 + 2" when in multiple albums. */
fun albumRegel(namen: List<String>) = if (namen.size <= 1) namen.firstOrNull().orEmpty() else namen[0] + "  + " + (namen.size - 1)
