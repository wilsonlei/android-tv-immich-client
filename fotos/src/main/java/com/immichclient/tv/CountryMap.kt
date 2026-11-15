package com.immichclient.tv

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Country map: country outline with a dot where the photo was taken (like a photo-frame screensaver).
 * Country shapes from assets/countries.bin (Natural Earth 50m, simplified; built with countries_bin.py).
 */
class Country(val name: String, val minLon: Float, val minLat: Float, val maxLon: Float, val maxLat: Float, val rings: List<FloatArray>)

object Countries {
    @Volatile private var cache: List<Country>? = null
    private val lock = Mutex()

    /** Read once (~250 kB, 60k points), off the main thread. */
    suspend fun list(ctx: Context): List<Country> {
        cache?.let { return it }
        return lock.withLock {
            cache ?: withContext(Dispatchers.IO) { read(ctx.assets.open("countries.bin").use { it.readBytes() }) }.also { cache = it }
        }
    }

    private fun read(b: ByteArray): List<Country> {
        val bb = ByteBuffer.wrap(b) // big-endian
        return List(bb.short.toInt()) {
            val name = ByteArray(bb.short.toInt()).also { bb.get(it) }.toString(Charsets.UTF_8)
            val b0 = bb.short / 100f; val b1 = bb.short / 100f; val b2 = bb.short / 100f; val b3 = bb.short / 100f
            val rings = List(bb.short.toInt()) { FloatArray(2 * bb.short.toInt()) { bb.short / 100f } }
            Country(name, b0, b1, b2, b3, rings)
        }
    }

    private fun inRing(x: Float, y: Float, r: FloatArray): Boolean {
        var inside = false
        var j = r.size - 2
        var i = 0
        while (i < r.size) {
            val xi = r[i]; val yi = r[i + 1]; val xj = r[j]; val yj = r[j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i; i += 2
        }
        return inside
    }

    /** The country containing the point; if just off the (simplified) coast: nearest country within ~1 deg. */
    fun find(countries: List<Country>, lon: Float, lat: Float): Country? {
        var best: Country? = null
        var bestDist = 1e9f
        for (c in countries) {
            if (lon < c.minLon - 1 || lon > c.maxLon + 1 || lat < c.minLat - 1 || lat > c.maxLat + 1) continue
            for (r in c.rings) if (inRing(lon, lat, r)) return c
            for (r in c.rings) {
                var i = 0
                while (i < r.size) {
                    val d = abs(r[i] - lon) + abs(r[i + 1] - lat)
                    if (d < bestDist) { bestDist = d; best = c }
                    i += 2
                }
            }
        }
        return if (bestDist < 1f) best else null
    }
}

/**
 * Computed map in px (origin = top-left of the map): rings as paths, dimensions, and the dot.
 * [ringIdx] = which rings of the country were drawn; using the projection (k, minX, minY, scale) the dot of
 * another photo in the same country can be placed in the same drawing (outline stays, only the dot moves).
 */
class MapShape(
    val country: String, val ringIdx: List<Int>, val paths: List<Path>, val width: Float, val height: Float, val dot: Offset,
    private val k: Float, private val minX: Float, private val minY: Float, private val scale: Float,
) {
    fun dotFor(lon: Float, lat: Float) = Offset((lon * k - minX) * scale, (-lat - minY) * scale)
}

/**
 * Simple projection: x = lon*cos(lat), y = -lat. Only parts of the country near the photo
 * (no Alaska near New York). The country fills [tall]; a wide country may expand up to [maxOverflow]*[wide]
 * (extending off-screen to the right), while the dot stays at least [dotMargin] inside the area.
 */
fun mapShape(countries: List<Country>, lon: Float, lat: Float, wide: Float, tall: Float, dotMargin: Float, maxOverflow: Float): MapShape? {
    val country = Countries.find(countries, lon, lat) ?: return null
    val k = cos(lat * Math.PI / 180).toFloat()
    val ringIdx = country.rings.indices.filter { ri ->
        val r = country.rings[ri]
        var x0 = 1e9f; var x1 = -1e9f; var y0 = 1e9f; var y1 = -1e9f
        var i = 0
        while (i < r.size) { x0 = min(x0, r[i]); x1 = max(x1, r[i]); y0 = min(y0, r[i + 1]); y1 = max(y1, r[i + 1]); i += 2 }
        (lon in x0..x1 && lat in y0..y1) || (abs((x0 + x1) / 2 - lon) * k < 8 && abs((y0 + y1) / 2 - lat) < 8)
    }
    if (ringIdx.isEmpty()) return null
    val rings = ringIdx.map { country.rings[it] }
    var minX = lon * k; var maxX = minX; var minY = -lat; var maxY = minY
    for (r in rings) {
        var i = 0
        while (i < r.size) {
            val x = r[i] * k; val y = -r[i + 1]
            if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
            i += 2
        }
    }
    val w = (maxX - minX).takeIf { it > 0 } ?: 1f
    val h = (maxY - minY).takeIf { it > 0 } ?: 1f
    val dotX = lon * k - minX
    val scale = minOf(tall / h, maxOverflow * wide / w, if (dotX > 0) (wide - dotMargin) / dotX else 1e9f)
    val paths = rings.map { r ->
        Path().apply {
            var i = 0
            while (i < r.size) {
                val x = (r[i] * k - minX) * scale; val y = (-r[i + 1] - minY) * scale
                if (i == 0) moveTo(x, y) else lineTo(x, y)
                i += 2
            }
            close()
        }
    }
    return MapShape(country.name, ringIdx, paths, w * scale, h * scale, Offset(dotX * scale, (-lat - minY) * scale), k, minX, minY, scale)
}

private val LAND_FILL = Color(0x42FFFFFF)
private val LAND_LINE = Color(0xF2FFFFFF)
private val SHADOW = Color(0x8C000000)
private val GLOW = Color(0x47FFFFFF)

/** Outline (fraction [line] 0..1 drawn), fill ([fill] 0..1) and dot ([pop] 0..1), all relative to map origin. */
private fun DrawScope.drawCountry(shape: MapShape, measures: List<PathMeasure>?, seg: Path, line: Float, fill: Float, pop: Float, strokeWidth: Float, dot: Offset = shape.dot) {
    val stroke = Stroke(strokeWidth, join = StrokeJoin.Round)
    val shadow = Stroke(strokeWidth * 2.5f, join = StrokeJoin.Round)
    shape.paths.forEachIndexed { i, p ->
        if (fill > 0f) drawPath(p, LAND_FILL.copy(alpha = LAND_FILL.alpha * fill))
        val path = when {
            line >= 1f || measures == null -> p
            line <= 0f -> null
            else -> { seg.rewind(); measures[i].getSegment(0f, line * measures[i].length, seg, true); seg }
        }
        if (path != null) {
            translate(0f, strokeWidth * 0.6f) { drawPath(path, SHADOW, style = shadow) }
            drawPath(path, LAND_LINE, style = stroke)
        }
    }
    if (pop > 0f) {
        drawCircle(SHADOW, 7.dp.toPx() * pop, dot + Offset(0f, strokeWidth * 0.6f))
        drawCircle(GLOW, 14.dp.toPx() * pop, dot)
        drawCircle(Color.White, 5.5.dp.toPx() * pop, dot)
    }
}

// Area for the map in the viewer: right 1/3 of width, country up to 80% of height, 6% above bottom edge.
private const val WIDE = 1f / 3
private const val TALL = 0.8f
private const val BOTTOM = 0.06f
private const val MAX_OVERFLOW = 2.2f

/** Animation: outline draws (0.2-1.8 s), fills (1.6-2.4 s), dot pops (1.5-2.0 s), label slides in (1.8-2.5 s). */
private fun part(t: Float, from: Float, duration: Float) = ((t - from) / duration).coerceIn(0f, 1f)
private val DRAW_EASING = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/** Only show map after transition finishes and user lingers on a photo (unobtrusive during fast browsing). */
private const val MAP_DELAY_MS = 500L
private const val EXIT_MS = 450

/**
 * Map in the viewer: single layer that persists while browsing. [target] is the photo to show on the map
 * (null = no map: video, info panel, no GPS). If the new photo is in the same country (same rings, dot fits
 * inside the drawing), the outline remains and only the dot glides to the new spot (label crossfades);
 * otherwise the old map unrolls first (exit) and the new one draws itself.
 */
@Composable
fun CountryMapOverlay(target: Asset?, widthPx: Int, heightPx: Int, sizeScale: Float = 1f, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var shape by remember { mutableStateOf<MapShape?>(null) }
    var label by remember { mutableStateOf<Pair<String, String?>?>(null) }
    /** Dot target (for label side); the dot itself animates toward it. */
    var dotTarget by remember { mutableStateOf(Offset.Zero) }
    val dot = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val clock = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    var pulsing by remember { mutableStateOf(false) }
    val dotMargin = with(density) { 40.dp.toPx() }
    val rightMargin = with(density) { 64.dp.toPx() }
    // [sizeScale] = small/large setting: scales the area (width and height) the country fits into
    val wide = widthPx * WIDE * sizeScale
    val tall = heightPx * TALL * sizeScale

    // Position of map on screen: if country fits, right-align; otherwise start on the left of the area and overflow right
    fun position(s: MapShape): Offset {
        val right = if (s.width <= wide - rightMargin) rightMargin else wide - s.width
        return Offset(widthPx - right - s.width, heightPx * (1 - BOTTOM) - s.height)
    }
    suspend fun compute(a: Asset): MapShape? {
        val countries = Countries.list(ctx)
        return withContext(Dispatchers.Default) {
            with(density) { mapShape(countries, a.lon!!.toFloat(), a.lat!!.toFloat(), wide, tall, dotMargin, MAX_OVERFLOW) }
        }
    }
    // Exit: outline unrolls, dot shrinks, label slides up and away
    suspend fun hide() {
        exit.animateTo(1f, tween(EXIT_MS, easing = EaseIn))
        shape = null; label = null; pulsing = false
        exit.snapTo(0f)
    }

    val currentTarget = rememberUpdatedState(target)
    LaunchedEffect(widthPx, heightPx, sizeScale) {
        // Sequential: an exit always finishes before the next target is processed (snapshotFlow delivers latest)
        snapshotFlow { currentTarget.value }.collect { a ->
            val old = shape
            if (a == null || a.lat == null || a.lon == null) { if (old != null) hide(); return@collect }
            val next = runCatching { compute(a) }.onFailure { android.util.Log.w("Fotos", "country map", it) }.getOrNull()
            if (old != null && next != null && old.country == next.country && old.ringIdx == next.ringIdx) {
                val d = old.dotFor(a.lon.toFloat(), a.lat.toFloat())
                val p = position(old)
                // 1 px tolerance: a dot right on the margin (projection often places it there) shouldn't fail on rounding
                if (d.x in -1f..old.width + 1f && d.y in -1f..old.height + 1f && p.x + d.x <= widthPx - dotMargin + 1f) {
                    label = monthYear(a.date) to a.city
                    dotTarget = d
                    scope.launch { dot.animateTo(d, tween(700, easing = FastOutSlowInEasing)) }
                    return@collect
                }
            }
            val t0 = System.currentTimeMillis()
            if (old != null) hide()
            kotlinx.coroutines.delay((MAP_DELAY_MS - (System.currentTimeMillis() - t0)).coerceAtLeast(0))
            if (next == null || currentTarget.value?.id != a.id) return@collect
            clock.snapTo(0f); exit.snapTo(0f); dot.snapTo(next.dot); dotTarget = next.dot; pulsing = false
            label = monthYear(a.date) to a.city
            shape = next
        }
    }
    val s = shape ?: return
    val origin = position(s)
    val left = origin.x
    val top = origin.y
    val measures = remember(s) { s.paths.map { PathMeasure().apply { setPath(it, false) } } }
    val seg = remember { Path() }
    LaunchedEffect(s) { clock.animateTo(2600f, tween(2600, easing = LinearEasing)) }
    LaunchedEffect(s) { kotlinx.coroutines.delay(2000); pulsing = true }

    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val t = clock.value
            val u = exit.value
            translate(left, top) {
                drawCountry(
                    s, measures, seg,
                    line = min(DRAW_EASING.transform(part(t, 200f, 1600f)), 1f - u),
                    fill = EaseOut.transform(part(t, 1600f, 800f)) * (1f - u),
                    pop = EaseOutBack.transform(part(t, 1500f, 500f)) * (1f - min(1f, u * 1.3f)),
                    strokeWidth = 2.dp.toPx(),
                    dot = dot.value,
                )
            }
        }
        // Pulsing ring around the dot: only layer properties change, the rest isn't redrawn
        if (pulsing) {
            val pulse = rememberInfiniteTransition(label = "pulse")
            val p by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = EaseOut), RepeatMode.Restart), label = "p")
            Canvas(
                Modifier.offset { IntOffset((left + dot.value.x).roundToInt() - 14.dp.roundToPx(), (top + dot.value.y).roundToInt() - 14.dp.roundToPx()) }
                    .size(28.dp)
                    .graphicsLayer { val sc = 1 + 1.2f * p; scaleX = sc; scaleY = sc; alpha = (1 - p / 0.7f).coerceIn(0f, 1f) * (1 - exit.value) },
            ) { drawCircle(GLOW, 14.dp.toPx()) }
        }
        // Label next to the dot: to the right, or left if it would overflow; follows the dot, text crossfades
        val l = label ?: return@Box
        val tm = rememberTextMeasurer()
        val monthStyle = TextStyle(fontFamily = Overpass, fontSize = 14.sp, color = Color(0xD9FFFFFF), shadow = Shadow(Color(0xD9000000), Offset(0f, 1f), 8f))
        val placeStyle = TextStyle(fontFamily = Overpass, fontSize = 20.sp, color = Color.White, shadow = Shadow(Color(0xD9000000), Offset(0f, 1f), 8f))
        val textSize = remember(l) {
            val a = tm.measure(l.first, monthStyle).size
            val b = l.second?.let { tm.measure(it, placeStyle).size }
            IntOffset(max(a.width, b?.width ?: 0), a.height + (b?.height ?: 0))
        }
        val gap = with(density) { 22.dp.toPx() }
        val toLeft = left + dotTarget.x + gap + textSize.x > widthPx - with(density) { 24.dp.toPx() }
        val align = if (toLeft) TextAlign.End else TextAlign.Start
        Box(
            Modifier.offset {
                val sx = left + dot.value.x
                val x = if (toLeft) sx - gap - textSize.x else sx + gap
                IntOffset(x.roundToInt(), (top + dot.value.y - textSize.y / 2f).roundToInt())
            }.graphicsLayer {
                val f = EaseOut.transform(part(clock.value, 1800f, 700f))
                val u = exit.value
                alpha = f * (1 - u); translationX = (1 - f) * 10.dp.toPx() * if (toLeft) 1 else -1; translationY = -u * 0.3f * size.height
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        ) {
            AnimatedContent(
                targetState = l, label = "label",
                transitionSpec = { fadeIn(tween(350, delayMillis = 150)) togetherWith fadeOut(tween(200)) },
                contentAlignment = if (toLeft) Alignment.TopEnd else Alignment.TopStart,
            ) { (month, place) ->
                Column(horizontalAlignment = if (toLeft) Alignment.End else Alignment.Start) {
                    Text(month, style = monthStyle, maxLines = 1, textAlign = align)
                    if (place != null) Text(place, style = placeStyle, maxLines = 1, textAlign = align)
                }
            }
        }
    }
}

/** Small static map (info panel): the whole country fits inside the box. */
@Composable
fun SmallCountryMap(lat: Double, lon: Double, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val shape by produceState<MapShape?>(null, lat, lon, w, h) {
            value = runCatching {
                val countries = Countries.list(ctx)
                withContext(Dispatchers.Default) { with(density) { mapShape(countries, lon.toFloat(), lat.toFloat(), w - 24.dp.toPx(), h - 24.dp.toPx(), 12.dp.toPx(), 1f) } }
            }.getOrNull()
        }
        val s = shape ?: return@BoxWithConstraints
        val seg = remember { Path() }
        Canvas(Modifier.fillMaxSize()) {
            translate((size.width - s.width) / 2, (size.height - s.height) / 2) { drawCountry(s, null, seg, 1f, 1f, 1f, 1.5.dp.toPx()) }
        }
    }
}
