package com.immichclient.tv

import android.graphics.Point
import android.util.Log
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * World map like in Google Photos / Immich: all photos with GPS clustered on a map, zooming in splits the
 * clusters into individual photos. Base layer = MapLibre with Immich's dark vector tile style (tiles.immich.cloud,
 * from /server/config). We do clustering ourselves (grid per integer zoom level + merging neighbors, on a
 * background thread) and draw them as Compose layers above the map: this lets them show a thumbnail + count,
 * receive focus, and move per frame with the camera (position = world coordinate * scale, no JNI per frame).
 *
 * Controls: D-pad = focus next cluster in that direction (no cluster left: pan map; nothing left to the left:
 * drawer); OK = zoom into focused cluster, at deepest level (or if it no longer splits) open viewer;
 * Fast-forward / Rewind = zoom level up/down; Back = fly to whole world in one flight, Back again = leave section.
 *
 * Clusters: hierarchical (continent -> region -> country -> place), built once per marker set in the background in
 * MapClusters.kt; per zoom level it is just a tree walk (milliseconds). Long OK on a cluster = all its photos
 * as a grid (MapPhotos.kt), Play/Pause = immediate slideshow.
 *
 * Memory/ANR (21-10-2026): zooming out from a deep level used to cluster during the flight at the old (fine) level
 * across the whole world view: thousands of markers (composables + thumbnails) at once, 1.2M objects, 2-3 s GCs,
 * and on the 2 GB box an ANR. Now: during a move never finer than the current level, at most [MAX_MARKERS] markers,
 * heavy work only on Dispatchers.Default, and on leaving the screen (screensaver, Home) the MapView is destroyed
 * completely (MapLibre otherwise holds ~100 MB native memory).
 *
 * Smoothness: the map renders in a SurfaceView (own layer, no TextureView copy per frame by HWUI), camera moves
 * use easeCamera (linear interpolation; animateCamera = flyTo zooms out first and loads tiles at all intermediate
 * levels), and during a move the previous zoom level's cluster layout stays (markers glide along); only once the
 * camera is idle do we recluster for the new level. This avoids marker churn mid-animation.
 */

/** Map camera: center in web mercator world units (0..1) and scale in px per world unit. */
@Immutable
data class Camera(val cx: Double, val cy: Double, val zoom: Double, val scale: Double)

fun worldX(lon: Double) = (lon + 180) / 360
fun worldY(lat: Double): Double {
    val s = sin(lat.coerceIn(-85.05, 85.05) * PI / 180)
    return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
}
fun lonFrom(x: Double) = x * 360 - 180
fun latFrom(y: Double) = (2 * atan(exp((0.5 - y) * 2 * PI)) - PI / 2) * 180 / PI

/**
 * Deepest zoom level (default, ClusterLevel.NORMAL): at 14, 1 px ≈ 1.5 m (at 52 deg latitude), so a cluster of
 * [RADIUS_DEEP_DP] ≈ 400 m. A holiday resort (pool + cabin) stays one cluster that opens the viewer directly;
 * zooming/splitting deeper makes no sense. The Cluster level setting scales the radius (*0.7 / *1.5) and sets
 * max zoom to 16 / 12.
 */
private val maxZoom get() = Settings.cluster.maxZoom
private const val MARKER_DP = 56f
/**
 * Never show more markers at once (largest first): each marker is a Compose layer with a thumbnail. At an integer
 * zoom level in view there are normally at most a few dozen.
 */
private const val MAX_MARKERS = 90

/** Satellite: Sentinel-2 cloudless 2023 by EOX (CC BY-NC-SA 4.0, free for personal use with attribution, no key). */
private const val SAT_TILES = "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2023_3857/default/g/{z}/{y}/{x}.jpg"
const val SAT_ATTRIBUTION = "EOxCloudless cloudless.eox.at by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2023)"

/** Currently open MapView (when the section is visible): flush its tile cache on low memory (App.onTrimMemory). */
@Volatile var openMap: MapView? = null
/**
 * Test extras (`--ez map_texture true`, `--ei map_lod 0`, `--ei map_fps 30`, `--ez map_all_layers true`): TextureView
 * instead of SurfaceView, tile LOD shift, max map fps, keep all style layers.
 * Measurement (NVIDIA Shield, zoom sequence): LOD shift -1 (tiles one level lower, 4x fewer tiles) improved
 * the map from ~30 to ~55 fps and dropped frames in screen recording from 19% to 6%.
 */
@Volatile var mapTexture = false
@Volatile var mapLod = -1
@Volatile var mapFps = 0
@Volatile var mapAllLayers = false
/** Height of the map View in px, scaled up as a layer to screen (0 = native); 720 gave no measurable gain (GPU is not the bottleneck). */
@Volatile var mapRes = 0
/** Extra LOD shift during the Back flight to the world. */
@Volatile var mapFlight = -2
/**
 * MapLibre in-memory tile cache (parsed off-screen tiles). Measurement 21-10 (3x zoom sequence world -> 14 ->
 * Back): on = native heap 89 MB / PSS 167 MB, off = 62 MB / 143 MB, and map fps equal or slightly better (tiles
 * come from disk cache). Off by default; test: `--ez map_cache true`. Prefetch zoom delta: `--ei map_prefetch N`
 * (-1 = default 4; 1 showed no measurable difference).
 */
@Volatile var mapCache = false
@Volatile var mapPrefetch = -1

/** What OK / long OK / Play-Pause on a cluster does. */
enum class MapAction { VIEW, GRID, SLIDESHOW }

/**
 * Style layers from tiles.immich.cloud (Protomaps dark, 70 layers) that add nothing to a TV photo map but make
 * tiles heavier (buildings, POIs, service roads, small landuse areas, labels that only appear at z15+).
 * Every layer costs main-thread evaluation per frame and GPU drawing shared with the Compose markers.
 */
private val STRIP_LAYERS = listOf(
    "buildings", "pois", "address_label", "roads_oneway", "roads_labels_minor", "water_waterway_label", "water_stream",
    "roads_minor_service", "roads_minor_service_casing", "roads_taxiway", "roads_runway", "landuse_runway", "landuse_aerodrome",
    "landuse_hospital", "landuse_school", "landuse_zoo", "landuse_industrial", "landuse_pedestrian", "landuse_pier", "roads_pier",
    "earth_label_islands",
)

class MapState(private val scope: CoroutineScope) {
    var markers by mutableStateOf<Markers?>(null)
        private set
    /** Cluster tree + levels (MapClusters.kt), built in the background once markers arrive. */
    var index by mutableStateOf<MapIndex?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var styleUrl by mutableStateOf<String?>(null)
    /** Which setting [styleUrl] was fetched for. */
    var styleKind by mutableStateOf<MapStyle?>(null)
    /** Initial view (Europe / main country) already applied (once per session). */
    var started = false
    /** World coordinates of the markers (computed once). */
    var wx = DoubleArray(0); var wy = DoubleArray(0)
    /** Current camera, updated every frame during movement. */
    val camera = mutableStateOf(Camera(0.5, 0.5, 1.0, 1024.0))
    var clusters by mutableStateOf<List<Cluster>>(emptyList())
    var focus by mutableStateOf<String?>(null)
    /** Camera when leaving the section, restored on return. */
    var saved: Camera? = null
    /** "Show on map" from the viewer: fly here once the map is ready. */
    var target by mutableStateOf<Pair<Double, Double>?>(null)
    /** Counter: the map should regain key focus (viewer closed). */
    var requestFocus by mutableIntStateOf(0)
    /** Ongoing computations that may take noticeable time (new zoom level, splitting): > 0 = loading indicator. */
    var computing by mutableIntStateOf(0)
    /** Last map frame was incomplete: tiles are still arriving. */
    var tilesIncomplete by mutableStateOf(false)

    init {
        scope.launch {
            runCatching { Immich.markers() }
                .onSuccess { m ->
                    val dm = Language.ctx.resources.displayMetrics
                    // A group stays one marker as long as it is smaller on screen than ~1/3 of screen height
                    val threshold = 0.32 * min(dm.widthPixels, dm.heightPixels)
                    val (x, y, idx) = withContext(Dispatchers.Default) {
                        val x = DoubleArray(m.count) { worldX(m.lon[it]) }; val y = DoubleArray(m.count) { worldY(m.lat[it]) }
                        val t = System.currentTimeMillis()
                        val idx = MapIndex(m, x, y, dm.density, threshold)
                        Log.i("Immich", "map: ${m.count} markers, ${idx.countryCodes.size} countries, tree in ${System.currentTimeMillis() - t} ms")
                        Triple(x, y, idx)
                    }
                    wx = x; wy = y; markers = m; index = idx
                    // Precompute all zoom levels (once per marker set), so every later step is instant
                    launch(Dispatchers.Default) {
                        val t = System.currentTimeMillis()
                        runCatching { idx.warmUp(Settings.cluster.maxZoom.toInt()) }
                        Log.i("Immich", "map: levels ready in ${System.currentTimeMillis() - t} ms")
                    }
                }
                .onFailure { error = it.message; Log.w("Immich", "markers", it) }
        }
    }

    /** Fetch style URL for configured map style (satellite = dark style with Sentinel-2 underneath). */
    suspend fun loadStyle(s: MapStyle) {
        if (styleKind == s && styleUrl != null) return
        styleUrl = Immich.mapStyleUrl(light = s == MapStyle.LIGHT)
        styleKind = s
    }

    /** Country with the most photos -> bbox (world units) of those photos without outliers (3rd-97th percentile). */
    fun mainCountryBbox(): DoubleArray? {
        val m = markers ?: return null
        val country = m.iso.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return null
        val idx = (0 until m.count).filter { m.iso[it] == country }
        if (idx.size < 2) return null
        val xs = idx.map { wx[it] }.sorted(); val ys = idx.map { wy[it] }.sorted()
        val a = (idx.size * 0.03).toInt(); val b = (idx.size * 0.97).toInt().coerceAtMost(idx.size - 1)
        return doubleArrayOf(xs[a], ys[a], xs[b], ys[b])
    }

    fun show(a: Asset) { if (a.lat != null && a.lon != null) { target = a.lat to a.lon; requestFocus++ } }

    /** Name of a cluster (continent, region, country, or place + country). */
    fun name(c: Cluster): String = index?.name(c) ?: listOfNotNull(markers?.city?.get(c.lead), markers?.country?.get(c.lead)).joinToString(", ")
}

/** Viewer source from the map: only IDs known, the asset is fetched when (almost) visible. */
class MapSource(private val ids: List<String>, private val scope: CoroutineScope) : ImageSource {
    private val cache = mutableStateMapOf<String, Asset>()
    private val inFlight = HashSet<String>()
    override val count get() = ids.size
    override fun at(i: Int): Asset? {
        val id = ids.getOrNull(i) ?: return null
        cache[id]?.let { return it }
        if (inFlight.add(id)) scope.launch {
            runCatching { Immich.asset(id) }.onSuccess { cache[id] = it }.onFailure { inFlight.remove(id); Log.w("Immich", "asset $id", it) }
        }
        return null
    }
}

/** Drives the map (camera, clusters, focus, keys); lives as long as the section is visible. */
private class MapEngine(
    val state: MapState, val scope: CoroutineScope, val w: Int, val h: Int, val pr: Float, val worldZoom: Double,
    val open: (Cluster, MapAction) -> Unit,
) {
    /** State: style is reapplied when the map is recreated (after returning to view). */
    var map by mutableStateOf<MapLibreMap?>(null)
    private var busy = false
    private var rerun = false
    /** If an idle request arrived during a running round, pick focus afterwards. */
    private var rerunIdle = false
    private var prevBbox: DoubleArray? = null
    private var prevZ = -1
    /** Marker to focus once the camera is idle (after zoom in/out: the cluster containing that photo). */
    private var desiredFocus = -1
    /** After panning: pick next cluster in that direction, relative to the old focus position (panned along). */
    private var afterPan: Triple<Int, Int, Offset>? = null
    private var lastFocusPos = Offset(w / 2f, h / 2f)
    /** Target zoom of the active animation: subsequent Fast-forward/Rewind builds on it (retarget, don't wait). */
    private var targetZoom: Double? = null

    // Measurement: map frames per move (MapLibre reports render time per rendered frame)
    private var measStart = 0L
    private var measFrames = 0
    private var measRenderMs = 0.0
    private var measMaxMs = 0.0

    fun screenPos(c: Cluster, cam: Camera = state.camera.value) =
        Offset(((c.wx - cam.cx) * cam.scale + w / 2).toFloat(), ((c.wy - cam.cy) * cam.scale + h / 2).toFloat())

    fun focusedCluster() = state.clusters.firstOrNull { it.key == state.focus }

    fun frameFinished(complete: Boolean, renderS: Double) {
        // Tiles still arriving? Only write on change (runs per map frame)
        if (state.tilesIncomplete == complete) state.tilesIncomplete = !complete
        if (measStart == 0L) return
        measFrames++; val ms = renderS * 1000; measRenderMs += ms; if (ms > measMaxMs) measMaxMs = ms
    }

    fun readCamera(map: MapLibreMap, idle: Boolean) {
        val p = map.cameraPosition
        val t = p.target ?: return
        state.camera.value = Camera(worldX(t.longitude), worldY(t.latitude), p.zoom, 512.0 * 2.0.pow(p.zoom) * pr)
        if (idle) {
            targetZoom = null
            if (map.tileLodZoomShift != mapLod.toDouble()) map.tileLodZoomShift = mapLod.toDouble()
            if (measStart != 0L && measFrames > 0) {
                val dur = System.currentTimeMillis() - measStart
                Log.i("Immich", "map move: $dur ms, $measFrames map frames (%.0f fps), render avg %.1f ms max %.1f ms, zoom %.2f".format(
                    measFrames * 1000.0 / dur, measRenderMs / measFrames, measMaxMs, p.zoom))
            }
            measStart = 0L
        } else if (measStart == 0L) { measStart = System.currentTimeMillis(); measFrames = 0; measRenderMs = 0.0; measMaxMs = 0.0 }
        recluster(idle)
    }

    /**
     * Recluster when needed. During a move (not [idle]) the previous zoom level's layout stays (no marker churn
     * mid-animation). If the view moves outside the previous bbox (zooming out, panning), recluster, but never finer
     * than the current level: previously zooming out from level 14 clustered the entire world at level 14 (thousands
     * of markers with thumbnails -> ANR). When idle, the actual level applies. Computation (a tree walk, or once per
     * level for places) runs on Dispatchers.Default.
     */
    fun recluster(idle: Boolean, force: Boolean = false) {
        val idx = state.index ?: return
        val cam = state.camera.value
        val realZ = floor(cam.zoom + 1e-6).toInt().coerceIn(0, 22)
        val hw = w / 2.0 / cam.scale; val hh = h / 2.0 / cam.scale
        val b = prevBbox
        val outside = b == null || cam.cx - hw < b[0] || cam.cy - hh < b[1] || cam.cx + hw > b[2] || cam.cy + hh > b[3]
        val z = when { idle || prevZ < 0 -> realZ; outside -> min(prevZ, realZ); else -> prevZ }
        val needed = force || z != prevZ || outside
        if (!needed) { if (idle) pickFocus(); return }
        if (busy) { rerun = true; rerunIdle = rerunIdle || idle; return }
        busy = true
        val margin = 2 * radiusDp(z) * pr / cam.scale
        val box = doubleArrayOf(cam.cx - hw - margin, cam.cy - hh - margin, cam.cx + hw + margin, cam.cy + hh + margin)
        val slow = !idx.ready(z)
        if (slow) state.computing++
        scope.launch {
            val list = try { withContext(Dispatchers.Default) { idx.visible(z, box, MAX_MARKERS) } } finally { if (slow) state.computing-- }
            prevBbox = box; prevZ = z
            state.clusters = list
            busy = false
            if (rerun) { val s2 = idle || rerunIdle; rerun = false; rerunIdle = false; recluster(s2) } else if (idle) pickFocus()
        }
    }

    /** Determine focus when camera is idle: desired photo -> cluster with it; else existing; else nearest. */
    private fun pickFocus() {
        val l = state.clusters
        if (l.isEmpty()) { state.focus = null; return }
        val desired = desiredFocus
        if (desired >= 0) {
            desiredFocus = -1
            l.firstOrNull { it.members.binarySearch(desired) >= 0 }?.let { setFocus(it); return }
        }
        afterPan?.let { (dx, dy, from) ->
            afterPan = null
            inDirection(l, from, dx, dy)?.let { setFocus(it); return }
        }
        if (l.any { it.key == state.focus }) return
        setFocus(l.minByOrNull { (screenPos(it) - lastFocusPos).getDistance() }!!)
    }

    private fun setFocus(c: Cluster) { state.focus = c.key; lastFocusPos = screenPos(c) }

    /** Best cluster in direction (dx, dy) from [from]: prefers clusters close to the axis (like Android focus search). */
    private fun inDirection(l: List<Cluster>, from: Offset, dx: Int, dy: Int): Cluster? {
        var best: Cluster? = null; var bestScore = Float.MAX_VALUE
        for (c in l) {
            val p = screenPos(c)
            if (p.x < 0 || p.y < 0 || p.x > w || p.y > h) continue
            val along = (p.x - from.x) * dx + (p.y - from.y) * dy
            if (along < 6f) continue
            val cross = abs((p.x - from.x) * dy) + abs((p.y - from.y) * dx)
            val score = along + 2.5f * cross
            if (score < bestScore) { bestScore = score; best = c }
        }
        return best
    }

    /** Arrow key: next cluster in that direction, or pan the map. Returns false if the key isn't for the map (drawer). */
    fun direction(dx: Int, dy: Int): Boolean {
        val m = map ?: return false
        val f = focusedCluster()
        val from = f?.let { screenPos(it) } ?: lastFocusPos
        inDirection(state.clusters, from, dx, dy)?.let { setFocus(it); return true }
        val cam = state.camera.value
        // No cluster left in that direction: pan the map. Only at world level (everything visible, panning makes
        // no sense) does Left go to the drawer, like everywhere else on the left edge; otherwise MENU opens the drawer.
        if (dx < 0 && cam.zoom <= worldZoom + 0.2) return false
        val step = 0.45
        val nx = cam.cx + dx * step * w / cam.scale
        val ny = (cam.cy + dy * step * h / cam.scale).coerceIn(0.02, 0.98)
        afterPan = Triple(dx, dy, Offset(from.x - (dx * step * w).toFloat(), from.y - (dy * step * h).toFloat()))
        m.easeCamera(CameraUpdateFactory.newLatLng(LatLng(latFrom(ny), lonFrom(nx))), 380)
        return true
    }

    /** Is an OK already running (splitting is computed in the background)? Then ignore a second OK. */
    private var picking = false

    /**
     * OK: zoom into the cluster until it splits; if it doesn't split (even at deepest level) or is one photo:
     * open viewer. Whether it splits is computed by the cluster index on Default (a new level can take a moment).
     */
    fun pick() {
        val m = map ?: return
        val idx = state.index ?: return
        val c = focusedCluster() ?: return
        if (c.count == 1) { open(c, MapAction.VIEW); return }
        if (picking) return
        val cam = state.camera.value
        val v = idx.bounds(c)
        val spanX = (v[2] - v[0]) * cam.scale; val spanY = (v[3] - v[1]) * cam.scale
        val fits = cam.zoom + log2(min(0.55 * w / max(spanX, 1e-6), 0.55 * h / max(spanY, 1e-6)))
        val goalZoom = min(fits, maxZoom)
        // No zoom gain left: open viewer
        if (goalZoom - cam.zoom < 0.5) { open(c, MapAction.VIEW); return }
        val dz = floor(goalZoom + 1e-6).toInt()
        picking = true
        val slow = !idx.ready(dz)
        if (slow) state.computing++
        scope.launch {
            val splits = try { withContext(Dispatchers.Default) { idx.splits(c, dz) } } finally { picking = false; if (slow) state.computing-- }
            // Still one whole at target zoom (holiday resort): open viewer
            if (!splits) { open(c, MapAction.VIEW); return@launch }
            desiredFocus = c.lead
            targetZoom = goalZoom
            m.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latFrom((v[1] + v[3]) / 2), lonFrom((v[0] + v[2]) / 2)), goalZoom), 650)
        }
    }

    /** Long OK: all photos of the focused cluster as a grid; Play/Pause: immediate slideshow. */
    fun photos(action: MapAction) { focusedCluster()?.let { open(it, action) } }

    /** Fast-forward / Rewind: zoom one level in or out around the focused cluster (stays in place); during animation, accumulates on target. */
    fun zoom(d: Double) {
        val m = map ?: return
        val cam = state.camera.value
        val base = targetZoom ?: cam.zoom
        val next = (base + d).coerceIn(worldZoom, maxZoom)
        if (abs(next - cam.zoom) < 0.01) return
        val f = focusedCluster()
        desiredFocus = f?.lead ?: -1
        val p = f?.let { screenPos(it) } ?: Offset(w / 2f, h / 2f)
        targetZoom = next
        m.easeCamera(CameraUpdateFactory.zoomBy(next - cam.zoom, Point(p.x.roundToInt(), p.y.roundToInt())), 400)
    }

    /** Camera framing [bbox] (world units), with margin. */
    fun fitBounds(b: DoubleArray, margin: Double = 0.78): CameraPosition {
        val dx = (b[2] - b[0]).coerceAtLeast(1e-6); val dy = (b[3] - b[1]).coerceAtLeast(1e-6)
        val z = log2(min(margin * w / (dx * 512 * pr), margin * h / (dy * 512 * pr))).coerceIn(worldZoom, maxZoom - 1)
        return CameraPosition.Builder().target(LatLng(latFrom((b[1] + b[3]) / 2), lonFrom((b[0] + b[2]) / 2))).zoom(z).build()
    }

    /** Back: fly to whole world in one flight. Returns false if already there (then Back leaves the section). */
    fun back(): Boolean {
        val m = map ?: return false
        val cam = state.camera.value
        if (cam.zoom <= worldZoom + 0.2) return false
        desiredFocus = focusedCluster()?.lead ?: -1
        targetZoom = worldZoom
        // During flight through 12 zoom levels use coarser tiles (otherwise loading tiles per level -> 15 fps); restored on idle
        if (mapFlight != 0) m.tileLodZoomShift = (mapLod + mapFlight).toDouble()
        m.easeCamera(world(worldZoom), 800)
        return true
    }

    fun flyTo(lat: Double, lon: Double) {
        val m = map ?: return
        desiredFocus = -1
        lastFocusPos = Offset(w / 2f, h / 2f)
        targetZoom = maxZoom
        m.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), maxZoom), 1200)
    }
}

private const val WORLD_LAT = 20.0
private const val WORLD_LON = 10.0
private fun world(zoom: Double) = CameraUpdateFactory.newLatLngZoom(LatLng(WORLD_LAT, WORLD_LON), zoom)
/** Europe (initial view setting): from Ireland to the Black Sea, from Sicily to northern Norway. */
private val EUROPE = doubleArrayOf(worldX(-12.0), worldY(66.0), worldX(34.0), worldY(35.0))

/** "lat,lon,zoom" <-> camera (last viewed place). */
private fun lastPlace(): CameraPosition? {
    val p = Settings.mapLast.split(',').mapNotNull { it.toDoubleOrNull() }
    if (p.size != 3) return null
    return CameraPosition.Builder().target(LatLng(p[0], p[1])).zoom(p[2]).build()
}

/**
 * Map section. If the app isn't visible (screensaver, Home, another app), the MapView is completely removed:
 * MapLibre otherwise retains tiles, GL context, and buffers (~100 MB native) while another app or screensaver
 * needs that memory. On return, it restores the same position (state.saved).
 */
@Composable
fun MapScreen(state: MapState, nav: Nav) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var visible by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> visible = true
                Lifecycle.Event.ON_STOP -> { visible = false; Log.i("Immich", "map: app hidden, MapView removed") }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Only the MapView disappears; the rest (focus, markers) stays, otherwise focus would jump to the drawer
    MapContent(state, nav, visible)
}

@Composable
private fun MapContent(state: MapState, nav: Nav, visible: Boolean) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val fr = remember { FocusRequester() }
    var hintUntil by remember { mutableLongStateOf(System.currentTimeMillis() + 6000) }
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(hintUntil) { hint = true; delay((hintUntil - System.currentTimeMillis()).coerceAtLeast(0)); hint = false }
    var styleError by remember { mutableStateOf(false) }
    var styleReady by remember { mutableStateOf(false) }
    var mapReady by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize().background(AppColors.background)) {
        val w = constraints.maxWidth; val h = constraints.maxHeight
        val pr = density.density
        // Whole world across width (poles cropped); MapLibre: world = 512 * 2^zoom logical px
        val worldZoom = log2(w / (512.0 * pr))
        val engine = remember(w, h) {
            MapEngine(state, scope, w, h, pr, worldZoom) { c, action ->
                val m = state.markers ?: return@MapEngine
                val ids = c.members.map { m.id[it] }
                val title = state.name(c).ifEmpty { Language.s(R.string.map) }
                when (action) {
                    MapAction.GRID -> {
                        var minLat = Double.POSITIVE_INFINITY; var maxLat = Double.NEGATIVE_INFINITY
                        var minLon = Double.POSITIVE_INFINITY; var maxLon = Double.NEGATIVE_INFINITY
                        for (i in c.members) {
                            val la = m.lat[i]; val lo = m.lon[i]
                            if (la < minLat) minLat = la; if (la > maxLat) maxLat = la
                            if (lo < minLon) minLon = lo; if (lo > maxLon) maxLon = lo
                        }
                        val pad = 1e-5
                        val bbox = "${minLon - pad},${minLat - pad},${maxLon + pad},${maxLat + pad}"
                        val sub = Language.plural(R.plurals.photos, ids.size, Language.number(ids.size))
                        nav.open(Screen.Timeline(title, sub, Filter(bbox = bbox), withMemories = false, startNewest = true, scope = nav.scope))
                    }
                    else -> nav.view(MapSource(ids, nav.scope), 0, title, action == MapAction.SLIDESHOW, null) { state.requestFocus++ }
                }
            }
        }
        val ctx = LocalContext.current
        val scale = if (mapRes in 1 until h) mapRes.toFloat() / h else 1f
        val mapView: MapView? = if (!visible) null else remember(w, h) {
            MapLibre.getInstance(ctx)
            val start = state.saved
            // Initial view: returning to section = where you left off; else setting (world / Europe / last place;
            // main country (country with most photos) once markers arrive, see below)
            val camera = when {
                start != null -> CameraPosition.Builder().target(LatLng(latFrom(start.cy), lonFrom(start.cx))).zoom(start.zoom).build()
                Settings.mapStart == MapStart.EUROPE -> engine.fitBounds(EUROPE)
                Settings.mapStart == MapStart.LAST -> lastPlace() ?: CameraPosition.Builder().target(LatLng(WORLD_LAT, WORLD_LON)).zoom(worldZoom).build()
                else -> CameraPosition.Builder().target(LatLng(WORLD_LAT, WORLD_LON)).zoom(worldZoom).build()
            }
            // SurfaceView (textureMode false): own layer under the window, composited by SurfaceFlinger; Compose layers
            // above (markers, drawer, viewer with black background) draw normally in the window. TextureView cost an
            // extra HWUI copy per map frame on a Tegra box.
            // Optionally (mapRes) a smaller map View scaled up as a layer (SurfaceView follows the transform):
            // less fill work for the GPU. pixelRatio adjusted so logical size matches screen (dp): text stays the
            // same size, and our Camera math (512*2^zoom*density px per world) holds. Default native.
            val options = MapLibreMapOptions.createFromAttributes(ctx).camera(camera)
                .foregroundLoadColor(AppColors.background.toArgb())
                .textureMode(mapTexture).pixelRatio(pr * scale)
                .compassEnabled(false).logoEnabled(false).attributionEnabled(false)
            MapView(ctx, options).apply { isFocusable = false; isFocusableInTouchMode = false }
        }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        if (mapView != null) DisposableEffect(mapView) {
            mapView.onCreate(null)
            val observer = LifecycleEventObserver { _, e ->
                when (e) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    else -> {}
                }
            }
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
            lifecycle.addObserver(observer)
            openMap = mapView
            mapView.addOnDidFailLoadingMapListener { Log.w("Immich", "map style: $it"); styleError = true }
            mapView.addOnDidFinishRenderingFrameListener(MapView.OnDidFinishRenderingFrameListener { complete, _, renderS ->
                engine.frameFinished(complete, renderS)
                if (complete && styleReady && !mapReady) mapReady = true
            })
            mapView.getMapAsync { map ->
                map.uiSettings.apply { setAllGesturesEnabled(false); isCompassEnabled = false; isLogoEnabled = false; isAttributionEnabled = false }
                map.setMinZoomPreference(worldZoom)
                map.setMaxZoomPreference(maxZoom)
                if (mapLod != 0) map.tileLodZoomShift = mapLod.toDouble()
                if (mapFps > 0) mapView.setMaximumFps(mapFps)
                if (!mapCache) map.setTileCacheEnabled(false)
                if (mapPrefetch >= 0) map.prefetchZoomDelta = mapPrefetch
                Log.i("Immich", "map: lod $mapLod, flight $mapFlight, res $mapRes, texture $mapTexture, fps ${mapFps.takeIf { it > 0 } ?: "free"}, allLayers $mapAllLayers, cache $mapCache, prefetch ${map.prefetchZoomDelta}, pixelRatio ${mapView.pixelRatio}")
                map.addOnCameraMoveListener { engine.readCamera(map, idle = false) }
                map.addOnCameraIdleListener { engine.readCamera(map, idle = true) }
                engine.map = map
                engine.readCamera(map, idle = true)
                // Projection check (once): our formula alongside MapLibre's
                val p = map.projection.toScreenLocation(LatLng(52.0, 5.0))
                val cam = state.camera.value
                Log.i("Immich", "map projection: maplibre=(${p.x},${p.y}) ours=(${(worldX(5.0) - cam.cx) * cam.scale + w / 2}, ${(worldY(52.0) - cam.cy) * cam.scale + h / 2}) pr=$pr/${mapView.pixelRatio}")
            }
            onDispose {
                state.saved = state.camera.value
                if (Settings.mapStart == MapStart.LAST) state.camera.value.let { c ->
                    Settings.mapLast = "%.5f,%.5f,%.2f".format(java.util.Locale.ROOT, latFrom(c.cy), lonFrom(c.cx), c.zoom)
                }
                lifecycle.removeObserver(observer)
                if (openMap === mapView) openMap = null
                engine.map = null
                mapReady = false
                mapView.onPause(); mapView.onStop(); mapView.onDestroy()
            }
        }
        // Fetch and apply style (setting) once both map and URL are ready
        val styleChoice = Settings.mapStyle
        LaunchedEffect(styleChoice) { state.loadStyle(styleChoice) }
        LaunchedEffect(state.styleUrl, state.styleKind, engine.map) {
            val url = state.styleUrl ?: return@LaunchedEffect
            val kind = state.styleKind ?: return@LaunchedEffect
            if (engine.map == null) return@LaunchedEffect
            engine.map?.setStyle(Style.Builder().fromUri(url)) { style ->
                styleReady = true
                if (!mapAllLayers) {
                    val n = STRIP_LAYERS.count { style.removeLayer(it) }
                    Log.i("Immich", "map style ${kind.name}: $n layers removed, ${style.layers.size} left")
                }
                // Satellite = hybrid: remove all fills (land, water, landuse) and put Sentinel-2 at the bottom;
                // roads, borders, and labels from Immich's style remain on top
                if (kind == MapStyle.SATELLITE) {
                    for (l in style.layers.toList()) if (l is org.maplibre.android.style.layers.FillLayer || l is org.maplibre.android.style.layers.BackgroundLayer) style.removeLayer(l)
                    val tiles = org.maplibre.android.style.sources.TileSet("2.2.0", SAT_TILES).apply { minZoom = 0f; maxZoom = 15f; attribution = SAT_ATTRIBUTION }
                    style.addSource(org.maplibre.android.style.sources.RasterSource("sat", tiles, 256))
                    style.addLayerAt(org.maplibre.android.style.layers.RasterLayer("sat", "sat"), 0)
                }
            }
        }
        // Cluster index ready: cluster
        LaunchedEffect(state.index) { if (state.index != null) engine.recluster(idle = true, force = true) }
        // Cluster level (setting) changed while map is open: update max zoom and layout
        val clusterChoice = Settings.cluster
        LaunchedEffect(clusterChoice) {
            while (engine.map == null) delay(50)
            engine.map?.setMaxZoomPreference(clusterChoice.maxZoom)
            if (state.index != null) engine.recluster(idle = true, force = true)
        }
        // Initial view "country with most photos": once markers arrive (once, not on return to section)
        LaunchedEffect(state.markers) {
            if (state.markers == null || state.started || state.saved != null || Settings.mapStart != MapStart.MAIN_COUNTRY) return@LaunchedEffect
            state.started = true
            val b = withContext(Dispatchers.Default) { state.mainCountryBbox() } ?: return@LaunchedEffect
            while (engine.map == null) delay(50)
            engine.map?.easeCamera(CameraUpdateFactory.newCameraPosition(engine.fitBounds(b)), 900)
        }
        // "Show on map"
        LaunchedEffect(state.target) {
            val (lat, lon) = state.target ?: return@LaunchedEffect
            while (engine.map == null) delay(50)
            state.target = null
            engine.flyTo(lat, lon)
        }
        LaunchedEffect(state.requestFocus) { delay(60); runCatching { fr.requestFocus() } }
        var okLong by remember { mutableStateOf(false) }

        // derivedStateOf: camera changes every frame, only the boolean (can go back) should recompose
        val canBack by remember { androidx.compose.runtime.derivedStateOf { state.camera.value.zoom > worldZoom + 0.2 } }
        DisposableEffect(Unit) { Keys.okRepeat = true; onDispose { Keys.okRepeat = false } }
        BackHandler(enabled = canBack) { hintUntil = System.currentTimeMillis() + 3000; engine.back() }

        Box(
            Modifier.fillMaxSize().focusRequester(fr).focusable().onPreviewKeyEvent { e ->
                val code = e.key.nativeKeyCode
                // OK on release: short = zoom/open, long (repeat ≈ 0.4 s) = all photos of cluster as grid.
                // After long-OK, MainActivity swallows the rest of the same press (Keys.okConsumed), including key-up.
                if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                    if (e.type == KeyEventType.KeyDown) {
                        val repeat = e.nativeKeyEvent.repeatCount
                        if (repeat == 0) okLong = false
                        else if (!okLong) {
                            okLong = true; Keys.okConsumed = true
                            Log.i("Immich", "map: long-OK opens grid")
                            engine.photos(MapAction.GRID)
                        }
                    } else if (e.type == KeyEventType.KeyUp) {
                        if (!okLong) { Log.i("Immich", "map: OK press (short)"); engine.pick() }
                        okLong = false
                    }
                    hintUntil = System.currentTimeMillis() + 3000
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val handled = when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> engine.direction(-1, 0)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> engine.direction(1, 0)
                    KeyEvent.KEYCODE_DPAD_UP -> engine.direction(0, -1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> engine.direction(0, 1)
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { engine.zoom(1.0); true }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> { engine.zoom(-1.0); true }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> { engine.photos(MapAction.SLIDESHOW); true }
                    else -> false
                }
                if (handled) hintUntil = System.currentTimeMillis() + 3000
                handled
            },
        ) {
            if (mapView != null) AndroidView(
                factory = { mapView },
                modifier = Modifier.graphicsLayer { scaleX = 1 / scale; scaleY = 1 / scale; transformOrigin = TransformOrigin(0f, 0f) }
                    .layout { m, _ -> val p = m.measure(Constraints.fixed((w * scale).roundToInt(), (h * scale).roundToInt())); layout(w, h) { p.place(0, 0) } },
            )
            if (!mapReady) Box(Modifier.fillMaxSize().background(AppColors.background))
            // No base layer (offline / tiles.immich.cloud unreachable): country outlines from countries.bin
            if (styleError && !styleReady) CountriesLayer(state.camera, w, h)

            val m = state.markers
            if (m != null) for (c in state.clusters) key(c.key) {
                MapMarker(c, m, focused = c.key == state.focus, cam = state.camera, w = w, h = h)
            }
            // Name and count below the focused cluster
            engine.focusedCluster()?.let { c ->
                key(c.key) { FocusLabel(c, state.name(c), state.camera, w, h) }
            }

            // Header top-left (on light or satellite map with dark scrim behind it, otherwise unreadable)
            val darkMap = styleChoice == MapStyle.DARK
            Column(
                Modifier.padding(start = 24.dp, top = 20.dp)
                    .then(if (darkMap) Modifier else Modifier.background(Color(0xB3000000), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 8.dp)),
            ) {
                Text(stringResource(R.string.map), style = MaterialTheme.typography.displaySmall)
                val n = m?.count
                Text(
                    when { state.error != null -> stringResource(R.string.map_load_failed, state.error.orEmpty()); n == null -> stringResource(R.string.loading); else -> pluralStringResource(R.plurals.photos_with_location, n, Language.number(n)) },
                    style = MaterialTheme.typography.bodyMedium, color = AppColors.muted,
                )
            }
            if (styleError && !styleReady) Text(
                stringResource(R.string.map_no_background), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.TopEnd).padding(24.dp),
            )
            // Loading indicator: markers/tree not ready yet, new level computing (> 150 ms), or tiles arriving
            // for a while (> 0.7 s). That way the map never appears stuck.
            var computingLong by remember { mutableStateOf(false) }
            LaunchedEffect(state.computing > 0) { if (state.computing > 0) { delay(150); computingLong = true } else computingLong = false }
            var tilesLong by remember { mutableStateOf(false) }
            LaunchedEffect(state.tilesIncomplete, styleError) {
                if (state.tilesIncomplete && !styleError) { delay(700); tilesLong = true; delay(20_000); tilesLong = false } else tilesLong = false
            }
            val loading = (state.error == null && (state.markers == null || state.index == null)) || computingLong || tilesLong
            LoadingDots(loading, Modifier.align(Alignment.TopEnd).padding(top = if (styleError && !styleReady) 56.dp else 22.dp, end = 24.dp))
            // Controls hint at bottom (fades out automatically) and attribution
            val hintAlpha by animateFloatAsState(if (hint) 1f else 0f, tween(400), label = "hint")
            Text(
                stringResource(R.string.map_hint),
                style = MaterialTheme.typography.labelLarge, color = Color(0xDDFFFFFF),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp).graphicsLayer { alpha = hintAlpha }
                    .background(Color(0xB3000000), RoundedCornerShape(16.dp)).padding(horizontal = 18.dp, vertical = 8.dp),
            )
            Text(
                if (styleChoice == MapStyle.SATELLITE) "$SAT_ATTRIBUTION · © OpenStreetMap · Protomaps" else "© OpenStreetMap · Protomaps",
                style = MaterialTheme.typography.labelSmall, color = Color(0xCCFFFFFF), maxLines = 1,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 3.dp)
                    .background(Color(0x80000000), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/** Places content with its anchor ([anchorX], [anchorY] in 0..1) on a world point; only placement follows the camera (no remeasure). */
private fun Modifier.atWorldPoint(cam: State<Camera>, w: Int, h: Int, wx: Double, wy: Double, anchorX: Float = 0.5f, anchorY: Float = 0.5f) = layout { measurable, c ->
    val p = measurable.measure(c.copy(minWidth = 0, minHeight = 0))
    layout(0, 0) {
        val k = cam.value
        p.place(((wx - k.cx) * k.scale + w / 2 - p.width * anchorX).roundToInt(), ((wy - k.cy) * k.scale + h / 2 - p.height * anchorY).roundToInt())
    }
}

@Composable
private fun MapMarker(c: Cluster, m: Markers, focused: Boolean, cam: State<Camera>, w: Int, h: Int) {
    // Pops in slightly; focused one grows
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, tween(260, easing = EaseOutBack)) }
    val fs by animateFloatAsState(if (focused) 1.3f else 1f, tween(160), label = "focus")
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.atWorldPoint(cam, w, h, c.wx, c.wy).zIndex(if (focused) 1f else 0f).size(MARKER_DP.dp)
            .graphicsLayer { val s = pop.value * fs; scaleX = s; scaleY = s; alpha = pop.value.coerceIn(0f, 1f) }
            // Dark border instead of layer shadow: HWUI tessellates shadows per node per frame, which adds up with
            // dozens of moving markers; only the focused marker keeps a (primary-colored) shadow
            .drawBehind { drawRoundRect(Color(0x80000000), Offset(-2f, 0f), Size(size.width + 4f, size.height + 3f), CornerRadius(13.dp.toPx())) },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                this.shape = shape; clip = true; shadowElevation = if (focused) 14.dp.toPx() else 0f
                spotShadowColor = AppColors.primary; ambientShadowColor = AppColors.primary
            }.background(AppColors.surfaceHigh),
        ) {
            AsyncImage(markerImage(m.id[c.lead]), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().border(if (focused) 3.dp else 2.dp, if (focused) AppColors.primary else Color.White, shape))
        }
        if (c.count > 1) Text(
            Language.number(c.count), style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-8).dp)
                .background(if (focused) AppColors.primaryDark else Color(0xE6000000), RoundedCornerShape(10.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** Thumbnail decoded at marker size (~146 px): 90 markers * 85 kB instead of * 330 kB for the full thumbnail. */
@Composable
private fun markerImage(id: String): coil3.request.ImageRequest {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { (MARKER_DP * 1.3f).dp.roundToPx() }
    return remember(id) {
        coil3.request.ImageRequest.Builder(ctx).data(Immich.thumb(id)).size(px, px).precision(coil3.size.Precision.INEXACT).build()
    }
}

@Composable
private fun FocusLabel(c: Cluster, name: String, cam: State<Camera>, w: Int, h: Int) {
    Column(
        Modifier.atWorldPoint(cam, w, h, c.wx, c.wy, anchorY = 0f).zIndex(2f).padding(top = (MARKER_DP * 0.65f + 12).dp)
            .background(Color(0xD9000000), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (name.isNotEmpty()) Text(name, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, textAlign = TextAlign.Center)
        Text(pluralStringResource(R.plurals.photos, c.count, Language.number(c.count)), style = MaterialTheme.typography.labelSmall, color = Color(0xCCFFFFFF))
    }
}

/** "Loading..." with a spinning arc; fades in and out. Only spins while visible. */
@Composable
fun LoadingDots(visible: Boolean, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 200 else 400), label = "loading")
    if (alpha <= 0f) return
    val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "angle")
    val accent = AppColors.primary
    androidx.compose.foundation.layout.Row(
        modifier.graphicsLayer { this.alpha = alpha }.background(Color(0xCC000000), RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(18.dp).graphicsLayer { rotationZ = angle }) {
            drawCircle(Color(0x40FFFFFF), style = Stroke(2.5.dp.toPx()))
            drawArc(accent, 0f, 100f, false, style = Stroke(2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Text(stringResource(R.string.loading), style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.padding(start = 10.dp))
    }
}

/** Country outlines (countries.bin) in web mercator as fallback background without tiles. */
@Composable
private fun CountriesLayer(cam: State<Camera>, w: Int, h: Int) {
    val ctx = LocalContext.current
    var path by remember { mutableStateOf<Path?>(null) }
    LaunchedEffect(Unit) {
        val countries = Countries.list(ctx)
        path = withContext(Dispatchers.Default) {
            Path().apply {
                for (c in countries) for (r in c.rings) {
                    var i = 0
                    while (i < r.size) {
                        val x = worldX(r[i].toDouble()).toFloat(); val y = worldY(r[i + 1].toDouble()).toFloat()
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                        i += 2
                    }
                    close()
                }
            }
        }
    }
    val p = path ?: return
    Canvas(Modifier.fillMaxSize()) {
        val k = cam.value
        withTransform({
            translate((w / 2 - k.cx * k.scale).toFloat(), (h / 2 - k.cy * k.scale).toFloat())
            scale(k.scale.toFloat(), k.scale.toFloat(), Offset.Zero)
        }) {
            drawPath(p, Color(0x33FFFFFF))
            drawPath(p, Color(0x99FFFFFF), style = Stroke((1.5.dp.toPx() / k.scale).toFloat()))
        }
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x33000000)))))
}
