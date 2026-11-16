package com.immichclient.tv

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import kotlin.reflect.KProperty

/**
 * All app settings as a single state object: each setting is Compose state (anyone reading it recomposes on change)
 * and is immediately saved to prefs. Stored per account, just like server/key: `key` or `key_<VIEWER>` (see [load],
 * called from Immich.selectAccount).
 *
 * Default values = behavior of version 1.1, so an update doesn't change anything for existing users.
 */
object Settings {
    private const val PREFS = "settings"
    private var prefs: SharedPreferences? = null
    private var suffix = ""
    private val all = ArrayList<Setting<*>>()

    class Setting<T>(
        private val baseKey: String, val default: T, private val perDevice: Boolean,
        private val read: (SharedPreferences, String, T) -> T, private val write: (SharedPreferences.Editor, String, T) -> Unit,
    ) {
        private val state = mutableStateOf(default)
        private val key get() = if (perDevice) baseKey else baseKey + suffix
        var value: T
            get() = state.value
            set(v) { state.value = v; prefs?.edit()?.also { write(it, key, v) }?.apply() }
        fun load(p: SharedPreferences) { state.value = runCatching { read(p, key, default) }.getOrDefault(default) }
        operator fun getValue(t: Any?, p: KProperty<*>): T = value
        operator fun setValue(t: Any?, p: KProperty<*>, v: T) { value = v }
    }

    private fun bool(k: String, d: Boolean, perDevice: Boolean = false) = Setting(k, d, perDevice, { p, s, x -> p.getBoolean(s, x) }, { e, s, v -> e.putBoolean(s, v) }).also { all += it }
    private fun int(k: String, d: Int) = Setting(k, d, false, { p, s, x -> p.getInt(s, x) }, { e, s, v -> e.putInt(s, v) }).also { all += it }
    private fun text(k: String, d: String, perDevice: Boolean = false) =
        Setting(k, d, perDevice, { p, s, x -> p.getString(s, x) ?: x }, { e, s, v -> e.putString(s, v) }).also { all += it }
    private inline fun <reified E : Enum<E>> choice(k: String, d: E, perDevice: Boolean = false) = Setting(
        k, d, perDevice,
        { p, s, x -> p.getString(s, null)?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: x },
        { e, s, v -> e.putString(s, v.name) },
    ).also { all += it }

    // ---- Slideshow ----
    /** Slowly zoom in during the slideshow. */
    var kenBurns by bool("ken_burns", true)
    var slideshowSeconds by int("slideshow_s", 6)
    val SLIDESHOW_TIMES = (3..30).toList()
    var slideshowRandom by bool("slideshow_random", false)
    /** Play videos in the slideshow (otherwise skip). */
    var slideshowVideos by bool("slideshow_videos", true)
    var slideshowSlide by bool("slideshow_slide", false)
    /** Progress ring in the top right during the slideshow. */
    var slideshowRing by bool("slideshow_ring", true)

    // ---- Viewer ----
    var viewerMap by choice("viewer_map", MapSize.LARGE)
    /** Hide the date/place bar after ... ms (0 = always show, -1 = off). */
    var barMs by int("bar_ms", 4000)
    val BAR_TIMES = listOf(-1, 0) + (2..20).map { it * 1000 }
    var counter by bool("counter", true)
    /** album(s) of the photo in the bar and info panel (useful when coming from a person or the timeline) */
    var albumName by bool("album_name", true)
    /** blurred thumbhash background around photos/videos that don't fill the screen (viewer + screensaver); off = black */
    var blurredBackground by bool("blurred_background", true)

    // ---- Timeline ----
    var rowSize by choice("row_size", RowSize.NORMAL)
    var newestFirst by bool("newest_first", true)
    var partners by bool("partners", true)
    var memories by bool("memories", true)
    var start by choice("start", Section.PHOTOS)
    var greeting by choice("greeting", Greeting.OFF)
    var greetingText by text("greeting_text", "")

    // ---- Map ----
    var mapStart by choice("map_start", MapStart.WORLD)
    var cluster by choice("cluster", ClusterLevel.NORMAL)
    var mapStyle by choice("map_style", MapStyle.DARK)
    /** last viewed place: "lat,lon,zoom" (for MapStart.LAST) */
    var mapLast by text("map_last", "")

    // ---- Settings screen ----
    /** show all settings (otherwise only basic); per device */
    var allSettings by bool("all_settings", false, perDevice = true)

    // ---- Appearance ----
    var accent by choice("accent", Accent.BLUE)

    // ---- Screensaver (DreamService, see Dream.kt) ----
    var dreamSource by choice("dream_source", DreamSource.ALL)
    /** chosen album IDs, comma-separated (up to 2.7.2 always one) */
    var dreamAlbum by text("dream_album", "")
    /** names matching [dreamAlbum], newline-separated (same order) */
    var dreamAlbumName by text("dream_album_name", "")
    /** for a source album/favorites: if the photo is also in another album, show that name instead of the source */
    var dreamOtherAlbum by bool("dream_other_album", true)
    /** chosen people (comma-separated; up to 2.7.2 always one) and their names (newlines), like albums */
    var dreamPerson by text("dream_person", "")
    var dreamPersonName by text("dream_person_name", "")
    /** only photos from this year onward (0 = all years) */
    var dreamFrom by int("dream_from", 0)
    var dreamOrder by choice("dream_order", DreamOrder.RANDOM)
    /** for a fixed order: where the screensaver left off ("source-key<TAB>date"), so it resumes there */
    var dreamPosition by text("dream_position", "")
    var dreamSeconds by int("dream_s", 15)
    val DREAM_TIMES = (3..20).toList()
    var dreamClock by bool("dream_clock", true)
    /** country map bottom right: off / small / large (up to 2.7.2 a toggle "dream_map", see [load]) */
    var dreamMap by choice("dream_map_size", MapSize.LARGE)
    var dreamKenBurns by bool("dream_ken_burns", true)
    /** date/place (and album name) bottom left */
    var dreamInfo by bool("dream_info", true)
    /** small progress ring bottom left */
    var dreamRing by bool("dream_ring", true)
    /** screensaver that was active before this app was set (to restore when turned "off"); per device */
    var dreamPrevious by text("dream_previous", "", perDevice = true)

    /** Read prefs for this account; [viewer] = profile with its own account, otherwise the general account. */
    fun load(c: Context, viewer: String?) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        suffix = if (viewer.isNullOrBlank()) "" else "_$viewer"
        for (i in all) i.load(p)
        // up to 1.2 the start view "country with most photos" had a different name; that's the only saved value not
        // in MapStart anymore, and it would otherwise silently fall back to the default (whole world)
        p.getString("map_start$suffix", null)?.let { old -> if (MapStart.entries.none { it.name == old }) mapStart = MapStart.MAIN_COUNTRY }
        // up to 2.7.2 the screensaver map was only on/off
        if (!p.contains("dream_map_size$suffix") && !p.getBoolean("dream_map$suffix", true)) dreamMap = MapSize.OFF
        slideshowSeconds = slideshowSeconds.coerceIn(SLIDESHOW_TIMES.first(), SLIDESHOW_TIMES.last())
        dreamSeconds = dreamSeconds.coerceIn(DREAM_TIMES.first(), DREAM_TIMES.last())
        if (barMs !in BAR_TIMES) barMs = 4000
    }

    val dreamAlbums get() = listFrom(dreamAlbum)
    val dreamAlbumNames get() = namesFrom(dreamAlbumName)
    val dreamPeople get() = listFrom(dreamPerson)
    val dreamPersonNames get() = namesFrom(dreamPersonName)
    /** choices for "from": 0 = all years, then 2000 .. this year */
    val DREAM_YEARS get() = listOf(0) + (2000..java.time.LocalDate.now().year)
    private fun listFrom(s: String) = s.split(',').filter { it.isNotBlank() }
    private fun namesFrom(s: String) = s.split('\n').filter { it.isNotBlank() }
    /** (id, name) pairs of a selection, padding missing names with "" */
    fun pairs(ids: List<String>, names: List<String>) = ids.mapIndexed { i, id -> id to names.getOrElse(i) { "" } }
    private fun toggle(ids: List<String>, nm: List<String>, id: String, name: String): Pair<String, String> {
        val p = pairs(ids, nm).toMutableList()
        if (!p.removeAll { it.first == id }) p += id to name
        return p.joinToString(",") { it.first } to p.joinToString("\n") { it.second }
    }
    /** Toggle an album in the screensaver selection. */
    fun toggleDreamAlbum(id: String, name: String) { toggle(dreamAlbums, dreamAlbumNames, id, name).let { dreamAlbum = it.first; dreamAlbumName = it.second } }
    /** Toggle a person in the screensaver selection. */
    fun toggleDreamPerson(id: String, name: String) { toggle(dreamPeople, dreamPersonNames, id, name).let { dreamPerson = it.first; dreamPersonName = it.second } }

    /** Slideshow time in ms. */
    val slideshowMs get() = slideshowSeconds * 1000L
}

// Labels are string resources (translated); the UI resolves them via stringResource(x.label).
enum class MapSize(@StringRes val label: Int, val scale: Float) { OFF(R.string.off, 0f), SMALL(R.string.small, 0.62f), LARGE(R.string.large, 1f) }
enum class RowSize(@StringRes val label: Int, val height: Float) { SMALL(R.string.small, 104f), NORMAL(R.string.normal, 138f), LARGE(R.string.large, 186f) }
enum class Greeting(@StringRes val label: Int) { OFF(R.string.off), AUTO(R.string.automatic), CUSTOM(R.string.custom_text) }
enum class MapStart(@StringRes val label: Int) { WORLD(R.string.whole_world), EUROPE(R.string.europe), MAIN_COUNTRY(R.string.country_most_photos), LAST(R.string.last_viewed_place) }
/** Scale on the cluster radius and deepest zoom level (see WorldMap.kt: 14 ~ 400 m per cluster at 52 deg latitude). */
enum class ClusterLevel(@StringRes val label: Int, val radius: Float, val maxZoom: Double) { FINE(R.string.fine, 0.7f, 16.0), NORMAL(R.string.normal_cluster, 1f, 14.0), COARSE(R.string.coarse, 1.5f, 12.0) }
enum class MapStyle(@StringRes val label: Int) { DARK(R.string.dark), LIGHT(R.string.light), SATELLITE(R.string.satellite) }
/** Where the screensaver gets its photos from. */
enum class DreamSource(@StringRes val label: Int) {
    ALL(R.string.dream_all_photos), ALBUM(R.string.dream_albums), FAVORITES(R.string.section_favorites),
    MEMORIES(R.string.dream_memories), PERSON(R.string.dream_people),
    /** the selected albums and selected people combined */
    COMBO(R.string.dream_combo),
}
/** Order of the screensaver photos; for a fixed order it resumes where it left off ([Settings.dreamPosition]). */
enum class DreamOrder(@StringRes val label: Int) { RANDOM(R.string.random), OLDEST(R.string.oldest_first), NEWEST(R.string.newest_first) }

/** Accent color throughout the app (AppColors.primary etc. read this). */
enum class Accent(@StringRes val label: Int, val primary: Color, val dark: Color, val onPrimary: Color) {
    BLUE(R.string.color_blue, Color(0xFFACCBFA), Color(0xFF4250AF), Color(0xFF0B1A3A)),
    GREEN(R.string.color_green, Color(0xFFA6E3B9), Color(0xFF2F7D4F), Color(0xFF07261A)),
    ORANGE(R.string.color_orange, Color(0xFFFFC89A), Color(0xFFC2520C), Color(0xFF3A1A05)),
    PINK(R.string.color_pink, Color(0xFFF9B8D4), Color(0xFFBE185D), Color(0xFF3B0A1F)),
    PURPLE(R.string.color_purple, Color(0xFFD4BCFA), Color(0xFF6D3FC9), Color(0xFF22103F)),
    GRAY(R.string.color_gray, Color(0xFFD9D9DE), Color(0xFF5B5B66), Color(0xFF1A1A1E)),
}
