package com.immichclient.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Immich (read-only). Server address and API key are stored in prefs: set via the pairing screen (Pairing.kt, with
 * the phone) or via adb with `am broadcast -a com.immichclient.tv.SET_KEY --es key ... [--es server ...]` (see KeyReceiver).
 * A custom launcher can pass a profile (extra "viewer", both when setting and when launching the app): then each
 * profile gets its own account.
 */
object Immich {
    private const val PREFS = "immich"

    @Volatile var key = ""
        private set
    /** Base address without /api, e.g. http://192.168.1.10:2283 or https://photos.example.com */
    @Volatile var server = ""
        private set
    /** Profile whose account is currently active (null = the general account). */
    @Volatile var viewer: String? = null
        private set
    val api get() = "$server/api"
    /** Increments on pair/disconnect so Compose notices (key/server themselves are plain fields). */
    var version by mutableIntStateOf(0)
        private set

    val http: OkHttpClient = OkHttpClient.Builder()
        // send the key only to our own server (not to e.g. tiles.immich.cloud)
        .addInterceptor { chain ->
            val req = chain.request()
            chain.proceed(if (req.url.toString().startsWith(server)) req.newBuilder().header("x-api-key", key).build() else req)
        }
        .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 24 })
        .connectionPool(ConnectionPool(24, 5, TimeUnit.MINUTES))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // smart search can take a bit after idle (ML model loads)
        .build()

    /** Select the account for this profile (falls back to the general account). */
    fun selectAccount(c: Context, viewer: String?) {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = viewer?.takeIf { it.isNotBlank() && p.contains("sleutel_$it") }
        this.viewer = k
        key = p.getString(if (k != null) "sleutel_$k" else "sleutel", "") ?: ""
        server = p.getString(if (k != null) "server_$k" else "server", "") ?: ""
        me = null
        Settings.load(c, k)
        version++
    }

    fun setKey(c: Context, next: String, viewer: String?, serverAddress: String? = null) {
        val suffix = if (viewer.isNullOrBlank()) "" else "_$viewer"
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString("sleutel$suffix", next.trim())
            serverAddress?.let { putString("server$suffix", Pairing.normalize(it)) }
        }.apply()
    }

    /** Clear key (and address) of this account; the key itself stays in Immich until deleted there. */
    fun disconnect(c: Context, viewer: String?) {
        val suffix = if (viewer.isNullOrBlank()) "" else "_$viewer"
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("sleutel$suffix").remove("server$suffix").apply()
        selectAccount(c, viewer)
    }

    fun thumb(id: String) = "$api/assets/$id/thumbnail?size=thumbnail"
    fun preview(id: String) = "$api/assets/$id/thumbnail?size=preview"
    fun video(id: String) = "$api/assets/$id/video/playback"
    fun face(id: String) = "$api/people/$id/thumbnail"

    private suspend fun fetch(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("Immich ${r.code}: ${req.url.encodedPath} ${body.take(200)}")
            body
        }
    }

    private suspend fun get(path: String) = fetch(Request.Builder().url(api + path).build())
    private suspend fun post(path: String, json: JSONObject) =
        fetch(Request.Builder().url(api + path).post(json.toString().toRequestBody("application/json".toMediaType())).build())

    /** Signed-in user (name, e-mail) for Settings and the greeting; fetched once per account. */
    var me by mutableStateOf<User?>(null)
        private set
    suspend fun loadMe(): User? = me ?: runCatching { JSONObject(get("/users/me")).let { User(it.optString("name"), it.optString("email")) } }
        .onFailure { Log.w("Photos", "users/me", it) }.getOrNull()?.also { me = it }

    /** Immich server version ("3.3.3"), for "About". */
    suspend fun serverVersion(): String? = runCatching {
        JSONObject(get("/server/version")).let { "${it.optInt("major")}.${it.optInt("minor")}.${it.optInt("patch")}" }
    }.getOrNull()

    // ---- Timeline (also for albums, people, and favorites: same endpoint with a filter) ----

    suspend fun months(filter: Filter, newestFirst: Boolean): List<Month> {
        val a = JSONArray(get("/timeline/buckets?" + filter.query(newestFirst)))
        return List(a.length()) { i -> a.getJSONObject(i).let { Month(it.getString("timeBucket"), it.getInt("count")) } }
    }

    suspend fun month(filter: Filter, newestFirst: Boolean, month: String): List<Asset> = withContext(Dispatchers.Default) {
        val o = JSONObject(get("/timeline/bucket?" + filter.query(newestFirst) + "&withCoordinates=true&timeBucket=$month"))
        val ids = o.getJSONArray("id")
        val ratio = o.optJSONArray("ratio")
        val isImage = o.getJSONArray("isImage")
        val hash = o.optJSONArray("thumbhash")
        val date = o.optJSONArray("fileCreatedAt")
        val offset = o.optJSONArray("localOffsetHours")
        val duration = o.optJSONArray("duration")
        val city = o.optJSONArray("city")
        val country = o.optJSONArray("country")
        val fav = o.optJSONArray("isFavorite")
        val lat = o.optJSONArray("latitude")
        val lon = o.optJSONArray("longitude")
        fun coord(a: JSONArray?, i: Int) = a?.optDouble(i)?.takeIf { !it.isNaN() }
        List(ids.length()) { i ->
            Asset(
                id = ids.getString(i),
                ratio = ratio?.optDouble(i, 1.0)?.toFloat()?.takeIf { it > 0 && !it.isNaN() } ?: 1f,
                video = !isImage.getBoolean(i),
                thumbhash = hash?.optString(i)?.takeIf { it.isNotEmpty() && it != "null" },
                date = localTime(date.getString(i), offset?.optDouble(i, 0.0) ?: 0.0),
                duration = duration?.optString(i)?.takeIf { it.isNotEmpty() && it != "null" },
                city = city?.optString(i)?.takeIf { it.isNotEmpty() && it != "null" },
                country = country?.optString(i)?.takeIf { it.isNotEmpty() && it != "null" }?.let(CountryName::translate),
                favorite = fav?.optBoolean(i) ?: false,
                lat = coord(lat, i),
                lon = coord(lon, i),
            )
        }
    }

    // ---- Search ----

    /** Smart search (CLIP): "beach", "birthday with cake", "dog in the snow". Order = relevance; language = app language. */
    suspend fun searchSmart(text: String, page: Int, videoOnly: Boolean?): Page {
        val body = JSONObject().put("query", text).put("language", Language.code).put("size", 120).put("page", page).put("withExif", true)
        if (videoOnly != null) body.put("type", if (videoOnly) "VIDEO" else "IMAGE")
        return page(JSONObject(post("/search/smart", body)))
    }

    /** Immich's ML model unloads from memory after a few minutes of idle; the first search then takes ~9 s. */
    suspend fun warmSearch() {
        runCatching { post("/search/smart", JSONObject().put("query", "photo").put("language", Language.code).put("size", 1)) }
    }

    suspend fun searchPlace(city: String, page: Int): Page =
        page(JSONObject(post("/search/metadata", JSONObject().put("city", city).put("size", 250).put("page", page).put("order", "desc").put("withExif", true))))

    /**
     * Filename / description / OCR (text in photo), optionally photos or videos only. Without text = all
     * photos or videos from the timeline, newest first (like the type filter in the web app).
     */
    suspend fun searchText(text: String?, field: String, page: Int, videoOnly: Boolean? = null): Page {
        val body = JSONObject().put("size", 250).put("page", page).put("order", "desc").put("withExif", true)
        if (text != null) body.put(field, text) else body.put("visibility", "timeline")
        if (videoOnly != null) body.put("type", if (videoOnly) "VIDEO" else "IMAGE")
        return page(JSONObject(post("/search/metadata", body)))
    }

    private suspend fun page(o: JSONObject): Page = withContext(Dispatchers.Default) {
        val a = o.getJSONObject("assets")
        val items = a.getJSONArray("items")
        Page(List(items.length()) { asset(items.getJSONObject(it)) }, a.optString("nextPage").toIntOrNull())
    }

    suspend fun places(): List<Place> = withContext(Dispatchers.Default) {
        val a = JSONArray(get("/search/cities"))
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val exif = o.optJSONObject("exifInfo")
            Place(exif?.optString("city").orEmpty(), CountryName.translate(exif?.optString("country").orEmpty()), o.getString("id"))
        }.filter { it.city.isNotBlank() }
    }

    suspend fun people(): List<Person> = withContext(Dispatchers.Default) {
        val a = JSONObject(get("/people?size=1000&withHidden=false")).getJSONArray("people")
        List(a.length()) { i -> a.getJSONObject(i).let { Person(it.getString("id"), it.optString("name"), it.optBoolean("isFavorite")) } }
            .filter { it.name.isNotBlank() }
    }

    // ---- Albums ----

    suspend fun albums(): List<Album> = withContext(Dispatchers.Default) {
        val a = JSONArray(get("/albums"))
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Album(
                id = o.getString("id"),
                name = o.optString("albumName"),
                cover = o.optString("albumThumbnailAssetId").takeIf { it.isNotEmpty() && it != "null" },
                count = o.optInt("assetCount"),
                from = o.optString("startDate").take(10).let { runCatching { LocalDate.parse(it) }.getOrNull() },
                to = o.optString("endDate").take(10).let { runCatching { LocalDate.parse(it) }.getOrNull() },
                updatedAt = o.optString("lastModifiedAssetTimestamp").ifEmpty { o.optString("updatedAt") },
                shared = o.optBoolean("shared"),
                oldestFirst = o.optString("order") == "asc",
            )
        }
    }

    // ---- Memories ("on this day X years ago") ----

    suspend fun memories(day: LocalDate): List<Memory> = withContext(Dispatchers.Default) {
        val a = JSONArray(get("/memories?for=$day"))
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val assets = o.getJSONArray("assets")
            Memory(
                id = o.getString("id"),
                year = o.optJSONObject("data")?.optInt("year") ?: 0,
                day = day,
                assets = List(assets.length()) { asset(assets.getJSONObject(it)) }.sortedBy { it.date },
            )
        }.filter { it.assets.isNotEmpty() }.sortedByDescending { it.year }
    }

    /** Random photos (screensaver "all photos"): /search/random returns [n] assets with exif in one call. */
    suspend fun random(n: Int, fromYear: Int = 0): List<Asset> = withContext(Dispatchers.Default) {
        val q = JSONObject().put("size", n).put("type", "IMAGE").put("visibility", "timeline").put("withExif", true)
        if (fromYear > 0) q.put("takenAfter", "$fromYear-01-01T00:00:00.000Z")
        val a = JSONArray(post("/search/random", q))
        List(a.length()) { asset(a.getJSONObject(it)) }
    }

    // ---- Albums of a single photo (viewer: "in album ...") ----

    private val albumsOf = object : LinkedHashMap<String, List<String>>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, List<String>>?) = size > 300
    }

    /** Names of the albums this photo is in (cached; error or missing permission = empty list). */
    suspend fun albumsOf(id: String): List<String> {
        synchronized(albumsOf) { albumsOf[id] }?.let { return it }
        val names = runCatching {
            val a = JSONArray(get("/albums?assetId=$id"))
            List(a.length()) { a.getJSONObject(it).optString("albumName") }.filter { it.isNotBlank() }.distinct()
        }.onFailure { Log.w("Photos", "albums of $id: ${it.message}") }.getOrNull() ?: return emptyList()
        synchronized(albumsOf) { albumsOf[id] = names }
        return names
    }

    // ---- Key permissions (see Permissions.kt) ----

    /** Name + permissions of our own key; null if the server doesn't support this (then [testPermission]). */
    suspend fun keyPermissions(): Pair<String, Set<String>>? = runCatching {
        val o = JSONObject(get("/api-keys/me"))
        val p = o.getJSONArray("permissions")
        o.optString("name") to (0 until p.length()).map { p.getString(it) }.toSet()
    }.onFailure { Log.w("Photos", "api-keys/me: ${it.message}") }.getOrNull()

    /** Test permission via its endpoint: true = works, false = 403, null = indeterminable. */
    suspend fun testPermission(permission: String): Boolean? {
        val path = when (permission) {
            "user.read" -> "/users/me"
            "asset.read" -> "/timeline/buckets?visibility=timeline&order=desc"
            "album.read" -> "/albums"
            "memory.read" -> "/memories?for=${LocalDate.now()}"
            "person.read" -> "/people?size=1"
            "map.read" -> "/map/markers?fileCreatedAfter=2999-01-01T00:00:00.000Z"
            "folder.read" -> "/view/folder/unique-paths"
            else -> return null
        }
        return try { get(path); true } catch (e: IOException) {
            if (e.message.orEmpty().startsWith("Immich 403")) false else null
        }
    }

    // ---- Folders (as files are arranged on disk or in an external library; permission folder.read) ----

    /** All folders with photos (full paths as Immich knows them). */
    suspend fun folders(): List<String> = withContext(Dispatchers.Default) {
        val a = JSONArray(get("/view/folder/unique-paths"))
        List(a.length()) { a.getString(it) }
    }

    /** Photos and videos directly in this folder (not in subfolders), by date. */
    suspend fun folderContents(path: String): List<Asset> = withContext(Dispatchers.Default) {
        val url = ("$api/view/folder".toHttpUrlOrNull() ?: throw IOException("invalid server address"))
            .newBuilder().addQueryParameter("path", path).build()
        val a = JSONArray(fetch(Request.Builder().url(url).build()))
        List(a.length()) { asset(a.getJSONObject(it)) }.sortedBy { it.date }
    }

    /** Single asset (for the viewer from the map: only IDs are known there). */
    suspend fun asset(id: String): Asset = withContext(Dispatchers.Default) { asset(JSONObject(get("/assets/$id"))) }

    // ---- Map ----

    /**
     * All photos with GPS (id, coords, city): one call (can be tens of thousands: 47k = 6.6 MB JSON), newest first.
     * Streamed with JsonReader instead of one JSONArray: that cost ~40 MB of temporary objects on the Java heap
     * (47k JSONObjects each with its own map), right when the map also needs memory.
     * City and country names are interned (same String instance for the same name).
     */
    suspend fun markers(): Markers = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$api/map/markers").build()
        val ids = ArrayList<String>(4096); var lat = DoubleArray(4096); var lon = DoubleArray(4096)
        val city = ArrayList<String?>(4096); val english = ArrayList<String?>(4096)
        val pool = HashMap<String, String>()
        fun interned(s: String?) = s?.takeIf { it.isNotEmpty() && it != "null" }?.let { pool.getOrPut(it) { it } }
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Immich ${r.code}: /api/map/markers ${r.body?.string().orEmpty().take(200)}")
            android.util.JsonReader(r.body!!.charStream()).use { j ->
                fun text(): String? = if (j.peek() == android.util.JsonToken.NULL) { j.nextNull(); null } else j.nextString()
                j.beginArray()
                while (j.hasNext()) {
                    var id = ""; var la = Double.NaN; var lo = Double.NaN; var st: String? = null; var la2: String? = null
                    j.beginObject()
                    while (j.hasNext()) when (j.nextName()) {
                        "id" -> id = j.nextString()
                        "lat" -> la = if (j.peek() == android.util.JsonToken.NULL) { j.nextNull(); Double.NaN } else j.nextDouble()
                        "lon" -> lo = if (j.peek() == android.util.JsonToken.NULL) { j.nextNull(); Double.NaN } else j.nextDouble()
                        "city" -> st = interned(text())
                        "country" -> la2 = interned(text())
                        else -> j.skipValue()
                    }
                    j.endObject()
                    if (id.isEmpty() || la.isNaN() || lo.isNaN()) continue
                    val n = ids.size
                    if (n == lat.size) { lat = lat.copyOf(n * 2); lon = lon.copyOf(n * 2) }
                    ids.add(id); lat[n] = la; lon[n] = lo; city.add(st); english.add(la2)
                }
                j.endArray()
            }
        }
        val n = ids.size
        // country name and ISO code once per distinct country
        val nameMap = HashMap<String, String>(); val codeMap = HashMap<String, String?>()
        val country = arrayOfNulls<String>(n); val iso = arrayOfNulls<String>(n)
        for (i in 0 until n) english[i]?.let { e -> country[i] = nameMap.getOrPut(e) { CountryName.translate(e) }; iso[i] = codeMap.getOrPut(e) { CountryName.iso(e) } }
        Markers(ids.toTypedArray(), lat.copyOf(n), lon.copyOf(n), city.toTypedArray(), country, iso)
    }

    /** Map style from the Immich server (light or dark; satellite = dark style with Sentinel-2 underneath, see WorldMap.kt). */
    suspend fun mapStyleUrl(light: Boolean): String {
        val field = if (light) "mapLightStyleUrl" else "mapDarkStyleUrl"
        return runCatching { JSONObject(get("/server/config")).optString(field).takeIf { it.startsWith("http") } }.getOrNull()
            ?: "https://tiles.immich.cloud/v1/style/${if (light) "light" else "dark"}.json"
    }

    /** Details for the info panel in the viewer. */
    suspend fun details(id: String): Details = withContext(Dispatchers.Default) {
        val o = JSONObject(get("/assets/$id"))
        val e = o.optJSONObject("exifInfo")
        fun s(k: String) = e?.optString(k)?.takeIf { it.isNotEmpty() && it != "null" }
        val people = o.optJSONArray("people")
        Details(
            filename = o.optString("originalFileName"),
            camera = listOfNotNull(s("make"), s("model")).joinToString(" ").ifEmpty { null },
            lens = s("lensModel"),
            settings = listOfNotNull(
                e?.optDouble("focalLength")?.takeIf { !it.isNaN() && it > 0 }?.let { "${it.toInt()} mm" },
                e?.optDouble("fNumber")?.takeIf { !it.isNaN() && it > 0 }?.let { "ƒ/" + ("%.1f".format(it)).removeSuffix(",0").removeSuffix(".0") },
                s("exposureTime")?.let { "$it s" },
                e?.optInt("iso")?.takeIf { it > 0 }?.let { "ISO $it" },
            ).joinToString("  ·  ").ifEmpty { null },
            place = listOfNotNull(s("city"), s("state"), s("country")?.let(CountryName::translate)).distinct().joinToString(", ").ifEmpty { null },
            people = if (people == null) emptyList() else
                List(people.length()) { people.getJSONObject(it).optString("name") }.filter { it.isNotBlank() },
            description = s("description"),
            dimensions = listOfNotNull(o.optInt("width").takeIf { it > 0 }, o.optInt("height").takeIf { it > 0 })
                .takeIf { it.size == 2 }?.let { (w, h) -> "$w × $h" },
        )
    }

    private fun asset(o: JSONObject): Asset {
        var w = o.optInt("width")
        var h = o.optInt("height")
        val e = o.optJSONObject("exifInfo")
        if (w <= 0 || h <= 0) {
            w = e?.optInt("exifImageWidth") ?: 0
            h = e?.optInt("exifImageHeight") ?: 0
            if (e?.optString("orientation") in setOf("5", "6", "7", "8")) w = h.also { h = w }
        }
        return Asset(
            id = o.getString("id"),
            ratio = if (w > 0 && h > 0) w.toFloat() / h else 1f,
            video = o.optString("type") == "VIDEO",
            thumbhash = o.optString("thumbhash").takeIf { it.isNotEmpty() && it != "null" },
            // localDateTime is local time, even when it has a trailing Z
            date = localTime(o.optString("localDateTime").ifEmpty { o.optString("fileCreatedAt") }, 0.0),
            duration = o.optString("duration").takeIf { it.isNotEmpty() && it != "null" },
            city = e?.optString("city")?.takeIf { it.isNotEmpty() && it != "null" },
            country = e?.optString("country")?.takeIf { it.isNotEmpty() && it != "null" }?.let(CountryName::translate),
            favorite = o.optBoolean("isFavorite"),
            lat = e?.optDouble("latitude")?.takeIf { !it.isNaN() },
            lon = e?.optDouble("longitude")?.takeIf { !it.isNaN() },
        )
    }
}

/**
 * Set the key via adb (only the shell is allowed, see DUMP permission in the manifest):
 * `am broadcast -a com.immichclient.tv.SET_KEY -n com.immichclient.tv/.KeyReceiver --es key ... --es server http://...:2283 [--es language nl]`.
 * Without `server`, the saved address stays intact; `language` (en/nl/de/fr/es) skips the first-start language picker.
 */
class KeyReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val s = i.getStringExtra("key") ?: i.getStringExtra("sleutel") ?: return
        (i.getStringExtra("language") ?: i.getStringExtra("taal"))?.let { Language.choose(c, it) }
        val viewer = i.getStringExtra("viewer") ?: i.getStringExtra("kijker")
        Immich.setKey(c, s, viewer, i.getStringExtra("server"))
        Immich.selectAccount(c, null)
        Log.i("Photos", "key set" + (viewer?.let { " for $it" } ?: "") + " for ${Immich.server}")
    }
}
