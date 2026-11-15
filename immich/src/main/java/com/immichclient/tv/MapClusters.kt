package com.immichclient.tv

import androidx.annotation.StringRes
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

/*
 * World map clusters, hierarchical: continent -> region (UN subregion) -> country -> place (distance clusters).
 *
 * Everything is built once per marker set on a background thread ([MapIndex]): per marker, the country code
 * (from Immich's country name), the tree with members per group, bounding box, and marker anchor (the densest
 * cell of the group, not the average: that landed in the middle of the sea for Europe or Asia), and per integer
 * zoom level (lazy, cached) the distance clusters within each country. What is visible at a zoom level
 * ([MapIndex.visible]) is then just a tree walk: a group stays one marker as long as it is small on screen
 * (spread below [MapIndex.thresholdPx]) and zoom is below its max; otherwise its children. So at world scale
 * you see continents, zoomed in you see regions and countries, and below that places, taking at most
 * milliseconds per step instead of re-clustering tens of thousands of photos.
 */

enum class Continent(@StringRes val label: Int) {
    EUROPE(R.string.continent_europe), ASIA(R.string.continent_asia), AFRICA(R.string.continent_africa), NORTH_AMERICA(R.string.continent_north_america),
    SOUTH_AMERICA(R.string.continent_south_america), OCEANIA(R.string.continent_oceania), ANTARCTICA(R.string.continent_antarctica), OTHER(R.string.continent_other),
}

/** UN M49 subregions with their countries (ISO 3166). */
enum class Region(val continent: Continent, @StringRes val label: Int, countries: String) {
    NORTHERN_EUROPE(Continent.EUROPE, R.string.region_northern_europe, "AX DK EE FO FI GG IS IE IM JE LV LT NO SJ SE GB"),
    WESTERN_EUROPE(Continent.EUROPE, R.string.region_western_europe, "AT BE FR DE LI LU MC NL CH"),
    SOUTHERN_EUROPE(Continent.EUROPE, R.string.region_southern_europe, "AL AD BA HR GI GR VA IT MT ME MK PT SM RS SI ES XK"),
    EASTERN_EUROPE(Continent.EUROPE, R.string.region_eastern_europe, "BY BG CZ HU PL MD RO RU SK UA"),
    NORTHERN_AFRICA(Continent.AFRICA, R.string.region_northern_africa, "DZ EG LY MA SD TN EH"),
    WESTERN_AFRICA(Continent.AFRICA, R.string.region_western_africa, "BJ BF CV CI GM GH GN GW LR ML MR NE NG SH SN SL TG"),
    CENTRAL_AFRICA(Continent.AFRICA, R.string.region_central_africa, "AO CM CF TD CG CD GQ GA ST"),
    EASTERN_AFRICA(Continent.AFRICA, R.string.region_eastern_africa, "BI KM DJ ER ET KE MG MW MU YT MZ RE RW SC SO SS TZ UG ZM ZW IO TF"),
    SOUTHERN_AFRICA(Continent.AFRICA, R.string.region_southern_africa, "BW SZ LS NA ZA"),
    NORTHERN_AMERICA(Continent.NORTH_AMERICA, R.string.region_northern_america, "BM CA GL PM US"),
    CENTRAL_AMERICA(Continent.NORTH_AMERICA, R.string.region_central_america, "BZ CR SV GT HN MX NI PA"),
    CARIBBEAN(Continent.NORTH_AMERICA, R.string.region_caribbean, "AI AG AW BS BB BQ VG KY CU CW DM DO GD GP HT JM MQ MS PR BL KN LC MF VC SX TT TC VI"),
    SOUTH_AMERICA(Continent.SOUTH_AMERICA, R.string.continent_south_america, "AR BO BV BR CL CO EC FK GF GY PY PE GS SR UY VE"),
    CENTRAL_ASIA(Continent.ASIA, R.string.region_central_asia, "KZ KG TJ TM UZ"),
    EASTERN_ASIA(Continent.ASIA, R.string.region_eastern_asia, "CN HK MO KP JP MN KR TW"),
    SOUTHEAST_ASIA(Continent.ASIA, R.string.region_southeast_asia, "BN KH ID LA MY MM PH SG TH TL VN"),
    SOUTH_ASIA(Continent.ASIA, R.string.region_south_asia, "AF BD BT IN IR MV NP PK LK"),
    WEST_ASIA(Continent.ASIA, R.string.region_middle_east, "AM AZ BH CY GE IQ IL JO KW LB OM QA SA PS SY TR AE YE"),
    AUSTRALIA(Continent.OCEANIA, R.string.region_australia_nz, "AU CX CC HM NZ NF"),
    MELANESIA(Continent.OCEANIA, R.string.region_melanesia, "FJ NC PG SB VU"),
    MICRONESIA(Continent.OCEANIA, R.string.region_micronesia, "GU KI MH FM NR MP PW UM"),
    POLYNESIA(Continent.OCEANIA, R.string.region_polynesia, "AS CK PF NU PN WS TK TO TV WF"),
    ANTARCTICA(Continent.ANTARCTICA, R.string.continent_antarctica, "AQ"),
    ;

    val codes: List<String> = countries.split(' ')

    companion object {
        private val byIso: Map<String, Region> by lazy { HashMap<String, Region>().also { m -> for (r in entries) for (c in r.codes) m[c] = r } }
        fun of(iso: String): Region? = byIso[iso]
    }
}

enum class MapLevel { CONTINENT, REGION, COUNTRY, PLACE }

/**
 * Cluster of markers at one location. [key] is stable (same group = same key, even after recalculating), [members] =
 * marker indices in ascending order (= newest first). [name] only for continent/region/country; [country] = index in
 * [MapIndex.countryCodes] (-1 for continent/region).
 */
class Cluster(
    val key: String, val wx: Double, val wy: Double, val members: IntArray,
    val level: MapLevel = MapLevel.PLACE, val name: String? = null, val country: Int = -1,
    /** Merged because markers overlapped at this level (Italy + Croatia): always splits apart on zoom-in. */
    val merged: Boolean = false,
) {
    val count get() = members.size
    /** Representative for thumbnail: the middle one (newest is often a dark/accidental photo). */
    val lead get() = members[members.size / 2]
}

/** Continent, region, or country: the marker ([cluster]), bounding box around all members, and child groups. */
class MapGroup(val cluster: Cluster, val x0: Double, val y0: Double, val x1: Double, val y1: Double, val children: List<MapGroup>, val zMax: Int)

/** Up to which zoom level (exclusive) a group at that level may stay one marker, even if small. */
private const val Z_CONTINENT = 3
private const val Z_REGION = 5
private const val Z_COUNTRY = 7

/** Marker size and radius within which places merge (dp): small at world/country level, coarser deeper in. */
const val RADIUS_DP = 66f
const val RADIUS_DEEP_DP = 130f
/** Cluster radius (dp) per integer zoom level: 66 dp up to level 9, linear to 130 dp at level 14 (times setting). */
fun radiusDp(z: Int) = (RADIUS_DP + (RADIUS_DEEP_DP - RADIUS_DP) * ((z - 9) / 5f).coerceIn(0f, 1f)) * Settings.cluster.radius

private class CellBucket(val country: Int, val kx: Long, val ky: Long) {
    var n = 0; var sx = 0.0; var sy = 0.0; var members = IntArray(8); var gone = false
    fun add(i: Int, x: Double, y: Double) { if (n == members.size) members = members.copyOf(n * 2); members[n++] = i; sx += x; sy += y }
    fun absorb(b: CellBucket) {
        if (n + b.n > members.size) members = members.copyOf(max(n + b.n, 2 * members.size))
        System.arraycopy(b.members, 0, members, n, b.n); n += b.n; sx += b.sx; sy += b.sy
    }
}

/** Grid cell + country in one Long: country (14 bits) | kx (25 bits) | ky (25 bits). */
private fun cellKey(country: Int, kx: Long, ky: Long) = ((country + 1).toLong() shl 50) or ((kx and 0x1FFFFFF) shl 25) or (ky and 0x1FFFFFF)

/**
 * Everything the map knows about the markers, built once (on a background thread) per marker set.
 * [pr] = pixel ratio (px per dp), [thresholdPx] = spread in px below which a group stays one marker.
 */
class MapIndex(
    val markers: Markers, private val wx: DoubleArray, private val wy: DoubleArray, private val pr: Float, private val thresholdPx: Double,
    /** Markers closer than this (px) merge into one marker (otherwise one hides under the other). */
    private val overlapPx: Double = 0.85 * 56 * pr,
) {
    private val n = wx.size
    /** Country codes (ISO) present; "??" = photos with unknown country. */
    val countryCodes: List<String>
    private val countryOf: IntArray
    val roots: List<MapGroup>
    private val groups = HashMap<String, MapGroup>()
    private val parent = HashMap<String, MapGroup>()
    private val countryNames: Array<String>

    init {
        val codes = ArrayList<String>()
        val numMap = HashMap<String, Int>()
        countryOf = IntArray(n) { i -> markers.iso[i]?.let { c -> numMap.getOrPut(c) { codes.add(c); codes.size - 1 } } ?: -1 }
        fillUnknown(codes)
        countryCodes = codes
        countryNames = Array(codes.size) { i -> if (codes[i] == UNKNOWN) Language.s(R.string.continent_other) else CountryName.fromIso(codes[i]) }
        roots = build()
    }

    /** Photos without a (recognized) country: country of the nearest photo with a country (~1 deg grid), else "unknown". */
    private fun fillUnknown(codes: ArrayList<String>) {
        val loose = (0 until n).filter { countryOf[it] < 0 }
        if (loose.isEmpty()) return
        val cell = 1.0 / 360
        val grid = HashMap<Long, Int>()
        for (i in 0 until n) if (countryOf[i] >= 0) grid.putIfAbsent(cellKey(0, floor(wx[i] / cell).toLong(), floor(wy[i] / cell).toLong()), i)
        var unknown = -1
        for (i in loose) {
            val kx = floor(wx[i] / cell).toLong(); val ky = floor(wy[i] / cell).toLong()
            var best = -1; var bestD = Double.MAX_VALUE
            for (r in 0..3) {
                for (dx in -r..r) for (dy in -r..r) {
                    if (max(kotlin.math.abs(dx), kotlin.math.abs(dy)) != r) continue
                    val j = grid[cellKey(0, kx + dx, ky + dy)] ?: continue
                    val d = hypot(wx[j] - wx[i], wy[j] - wy[i])
                    if (d < bestD) { bestD = d; best = j }
                }
                if (best >= 0) break
            }
            countryOf[i] = if (best >= 0) countryOf[best] else {
                if (unknown < 0) { codes.add(UNKNOWN); unknown = codes.size - 1 }
                unknown
            }
        }
    }

    private fun build(): List<MapGroup> {
        // Members per country (ascending, since i increases)
        val count = IntArray(countryCodes.size)
        for (c in countryOf) count[c]++
        val byCountry = Array(countryCodes.size) { IntArray(count[it]) }
        val fill = IntArray(countryCodes.size)
        for (i in 0 until n) { val c = countryOf[i]; byCountry[c][fill[c]++] = i }
        val countries = countryCodes.indices.map { c -> make("l:${countryCodes[c]}", countryNames[c], MapLevel.COUNTRY, byCountry[c], emptyList(), Z_COUNTRY, c) }

        val roots = ArrayList<MapGroup>()
        for (continent in Continent.entries) {
            val inContinent = countries.filter { g -> continentOf(countryCodes[g.cluster.country]) == continent }
            if (inContinent.isEmpty()) continue
            // Regions with photos; a region with one country is just that country (avoids two markers on the same spot)
            val regions = inContinent.groupBy { Region.of(countryCodes[it.cluster.country]) }.map { (r, ls) ->
                if (r == null || ls.size == 1) ls else listOf(make("r:${r.name}", Language.s(r.label), MapLevel.REGION, merge(ls), ls, Z_REGION, -1))
            }.flatten()
            // Continent with only one region (South America, only US + Canada, ...): put countries directly underneath
            val children = if (regions.size == 1 && regions[0].cluster.level == MapLevel.REGION) regions[0].children else regions
            roots += make("w:${continent.name}", Language.s(continent.label), MapLevel.CONTINENT, merge(inContinent), children, Z_CONTINENT, -1)
        }
        return roots
    }

    private fun continentOf(iso: String) = Region.of(iso)?.continent ?: Continent.OTHER

    private fun merge(groups: List<MapGroup>): IntArray {
        val out = IntArray(groups.sumOf { it.cluster.count })
        var p = 0
        for (g in groups) { System.arraycopy(g.cluster.members, 0, out, p, g.cluster.count); p += g.cluster.count }
        out.sort()
        return out
    }

    /** MapGroup with bounding box and anchor: centroid of the densest ~40 km cell (where most photos are). */
    private fun make(key: String, name: String, level: MapLevel, members: IntArray, children: List<MapGroup>, zMax: Int, country: Int): MapGroup {
        var x0 = 1.0; var x1 = 0.0; var y0 = 1.0; var y1 = 0.0
        val cell = 1.0 / 1024
        val cells = HashMap<Long, DoubleArray>()
        var best: DoubleArray? = null
        for (i in members) {
            val x = wx[i]; val y = wy[i]
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
            val v = cells.getOrPut(cellKey(0, floor(x / cell).toLong(), floor(y / cell).toLong())) { DoubleArray(3) }
            v[0]++; v[1] += x; v[2] += y
            if (best == null || v[0] > best[0]) best = v
        }
        val b = best!!
        val g = MapGroup(Cluster(key, b[1] / b[0], b[2] / b[0], members, level, name, country), x0, y0, x1, y1, children, zMax)
        groups[key] = g
        for (k in children) parent[k.cluster.key] = g
        return g
    }

    // ---- Distance clusters per integer zoom level, within a country ----

    private class Level(val byCountry: Array<Array<Cluster>>)
    private val levels = HashMap<Int, Level>()

    /** Distance clusters at integer zoom level [z] (lazily computed, cached; also keyed by cluster setting). */
    private fun level(z: Int): Level {
        val key = z * 16 + Settings.cluster.ordinal
        synchronized(levels) { levels[key]?.let { return it } }
        val next = compute(z)
        synchronized(levels) { levels[key] = next }
        return next
    }

    /** Whether level [z] is already computed (so [visible] is instant and no loading indicator is needed). */
    fun ready(z: Int) = synchronized(levels) { levels.containsKey(z * 16 + Settings.cluster.ordinal) }

    /**
     * Grid cells of [radiusDp] px at integer zoom level [z], per country; then neighboring cells (same country)
     * whose centroids are closer than that radius merge (largest first). That way clusters don't jump while zooming
     * within one level, and a place never crosses a country border.
     */
    private fun compute(z: Int): Level {
        val scaleZ = 512.0 * (1L shl z) * pr
        val radius = radiusDp(z) * pr
        val cell = radius / scaleZ
        val cells = HashMap<Long, CellBucket>()
        for (i in 0 until n) {
            val x = wx[i]; val y = wy[i]; val c = countryOf[i]
            val kx = floor(x / cell).toLong(); val ky = floor(y / cell).toLong()
            cells.getOrPut(cellKey(c, kx, ky)) { CellBucket(c, kx, ky) }.add(i, x, y)
        }
        val list = cells.values.sortedByDescending { it.n }
        for (a in list) {
            if (a.gone) continue
            for (dx in -1..1) for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val b = cells[cellKey(a.country, a.kx + dx, a.ky + dy)] ?: continue
                if (b.gone || b.n > a.n) continue
                val d = hypot((a.sx / a.n - b.sx / b.n) * scaleZ, (a.sy / a.n - b.sy / b.n) * scaleZ)
                if (d < radius) { a.absorb(b); b.gone = true }
            }
        }
        val byCountry = Array(countryCodes.size) { ArrayList<Cluster>() }
        for (v in list) if (!v.gone) byCountry[v.country] += Cluster("p$z:${v.country}:${v.kx}:${v.ky}", v.sx / v.n, v.sy / v.n, v.members.copyOf(v.n).also { it.sort() }, country = v.country)
        return Level(Array(byCountry.size) { byCountry[it].toTypedArray() })
    }

    // ---- What is visible ----

    private fun scale(z: Int) = 512.0 * 2.0.pow(z) * pr

    /** Does [g] stay a single marker at level [z]? Small enough on screen, and not past its max level. */
    private fun isSingle(g: MapGroup, z: Int) = z < g.zMax && max(g.x1 - g.x0, g.y1 - g.y0) * scale(z) <= thresholdPx * Settings.cluster.radius

    /**
     * Clusters at integer zoom level [z] inside box [b] (x0, y0, x1, y1 in world units). If more than [max]:
     * the largest ones (bounded marker count = bounded Compose work and bounded concurrent thumbnails).
     */
    fun visible(z: Int, b: DoubleArray, max: Int): List<Cluster> {
        val out = ArrayList<Cluster>()
        for (g in roots) collect(g, z, b, out)
        val nonOverlapping = mergeOverlaps(out, z)
        return if (nonOverlapping.size <= max) nonOverlapping else nonOverlapping.sortedByDescending { it.count }.take(max)
    }

    /**
     * Merge markers that (almost) cover each other into one: largest stays and absorbs the rest. Name =
     * smallest common group (Italy + Croatia + Slovenia -> Southern Europe); if all are places, the place name.
     * Such a merged marker always splits apart again when zooming in.
     */
    private fun mergeOverlaps(list: List<Cluster>, z: Int): List<Cluster> {
        if (list.size < 2) return list
        val s = scale(z)
        val limit = overlapPx / s
        val sorted = list.sortedByDescending { it.count }
        val out = ArrayList<Cluster>()
        val buckets = ArrayList<ArrayList<Cluster>>()
        for (c in sorted) {
            val i = out.indexOfFirst { hypot(it.wx - c.wx, it.wy - c.wy) < limit }
            if (i < 0) { out += c; buckets += arrayListOf(c) } else buckets[i] += c
        }
        if (buckets.all { it.size == 1 }) return list
        return buckets.map { group ->
            if (group.size == 1) return@map group[0]
            val lead = group[0]
            val members = IntArray(group.sumOf { it.count }).also { a -> var p = 0; for (c in group) { System.arraycopy(c.members, 0, a, p, c.count); p += c.count }; a.sort() }
            val allPlaces = group.all { it.level == MapLevel.PLACE }
            val common = if (allPlaces && group.all { it.country == lead.country }) null else commonAncestor(group)
            Cluster(
                "m:" + group.joinToString("+") { it.key }, lead.wx, lead.wy, members,
                level = common?.cluster?.level ?: MapLevel.PLACE, name = common?.cluster?.name,
                country = if (common == null) lead.country else -1, merged = true,
            )
        }
    }

    /** Smallest group (country, region, continent) containing all clusters; null if none. */
    private fun commonAncestor(list: List<Cluster>): MapGroup? {
        fun chain(c: Cluster): List<MapGroup> {
            var g: MapGroup? = groups[c.key] ?: if (c.country >= 0) groups["l:${countryCodes[c.country]}"] else null
            val out = ArrayList<MapGroup>()
            while (g != null) { out += g; g = parent[g.cluster.key] }
            return out
        }
        val first = chain(list[0])
        val rest = list.drop(1).map { chain(it).toSet() }
        return first.firstOrNull { g -> rest.all { g in it } }
    }

    /** What is visible of group [g] at level [z] (only within box [b], or all if [b] is null). */
    private fun collect(g: MapGroup, z: Int, b: DoubleArray?, out: ArrayList<Cluster>) {
        fun inside(c: Cluster) = b == null || (c.wx >= b[0] && c.wx <= b[2] && c.wy >= b[1] && c.wy <= b[3])
        if (b != null && (g.x1 < b[0] || g.x0 > b[2] || g.y1 < b[1] || g.y0 > b[3])) return
        if (isSingle(g, z)) { if (inside(g.cluster)) out += g.cluster; return }
        if (g.children.isNotEmpty()) { for (k in g.children) collect(k, z, b, out); return }
        for (c in level(z).byCountry[g.cluster.country]) if (inside(c)) out += c
    }

    /** Does [c] still split into multiple markers at integer zoom level [z]? */
    fun splits(c: Cluster, z: Int): Boolean {
        if (c.merged) return true
        if (c.level != MapLevel.PLACE) {
            val g = groups[c.key] ?: return false
            return ArrayList<Cluster>().also { collect(g, z, null, it) }.size > 1
        }
        if (c.country < 0) return false
        var x0 = 1.0; var x1 = 0.0; var y0 = 1.0; var y1 = 0.0
        for (i in c.members) { val x = wx[i]; val y = wy[i]; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        val margin = 3 * radiusDp(z) * pr / scale(z)
        var found = 0
        for (k in level(z).byCountry[c.country]) {
            if (k.wx < x0 - margin || k.wx > x1 + margin || k.wy < y0 - margin || k.wy > y1 + margin) continue
            if (overlaps(k.members, c.members) && ++found > 1) return true
        }
        return false
    }

    /** Do two ascending sorted arrays share at least one element? */
    private fun overlaps(a: IntArray, b: IntArray): Boolean {
        var i = 0; var j = 0
        while (i < a.size && j < b.size) { val x = a[i]; val y = b[j]; if (x == y) return true; if (x < y) i++ else j++ }
        return false
    }

    private val places = HashMap<String, String>()

    /** Name under a marker: continent/region/country, or for a place the most frequent city + country. */
    fun name(c: Cluster): String {
        c.name?.let { return it }
        synchronized(places) { places[c.key]?.let { return it } }
        val count = HashMap<String, Int>()
        val step = max(1, c.count / 2000)
        var i = 0
        while (i < c.count) { markers.city[c.members[i]]?.let { count[it] = (count[it] ?: 0) + 1 }; i += step }
        val city = count.maxByOrNull { it.value }?.key
        val country = if (c.country >= 0) countryNames[c.country] else markers.country[c.lead]
        val out = listOfNotNull(city, country?.takeIf { countryCodes.getOrNull(c.country) != UNKNOWN }).joinToString(", ")
        synchronized(places) { places[c.key] = out }
        return out
    }

    /** Member bounding box (world units) of a cluster, for zooming in. */
    fun bounds(c: Cluster): DoubleArray {
        groups[c.key]?.let { return doubleArrayOf(it.x0, it.y0, it.x1, it.y1) }
        var x0 = 1.0; var x1 = 0.0; var y0 = 1.0; var y1 = 0.0
        for (i in c.members) { val x = wx[i]; val y = wy[i]; if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        return doubleArrayOf(x0, y0, x1, y1)
    }

    /** Precompute all levels up to and including [to] (in the background, after building). */
    fun warmUp(to: Int) { for (z in 0..to) level(z) }

    companion object {
        const val UNKNOWN = "??"
    }
}
