package com.immichclient.tv

import androidx.compose.runtime.Immutable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Immutable
data class Asset(
    val id: String,
    /** width / height, already rotated as displayed */
    val ratio: Float,
    val video: Boolean,
    val thumbhash: String?,
    val date: LocalDateTime,
    /** "0:01:23.456000" */
    val duration: String?,
    val city: String?,
    val country: String?,
    val favorite: Boolean,
    /** GPS (null without location); for the country map in the viewer */
    val lat: Double? = null,
    val lon: Double? = null,
)

@Immutable data class Month(val key: String, val count: Int)
@Immutable data class Album(
    val id: String, val name: String, val cover: String?, val count: Int,
    val from: LocalDate?, val to: LocalDate?, val updatedAt: String, val shared: Boolean, val oldestFirst: Boolean,
)
@Immutable data class Person(val id: String, val name: String, val favorite: Boolean)
@Immutable data class Place(val city: String, val country: String, val cover: String)
@Immutable data class Memory(val id: String, val year: Int, val day: LocalDate, val assets: List<Asset>) {
    val yearsAgo get() = day.year - year
}
data class Page(val assets: List<Asset>, val next: Int?)
@Immutable data class User(val name: String, val email: String)
/** All photos with GPS for the world map, as columns (compact: can be tens of thousands). Order = newest first. */
class Markers(
    val id: Array<String>, val lat: DoubleArray, val lon: DoubleArray, val city: Array<String?>,
    /** country name in the app language */
    val country: Array<String?>,
    /** ISO country code (null if Immich returned none or an unknown country) */
    val iso: Array<String?>,
) {
    val count get() = id.size
}
data class Details(
    val filename: String, val camera: String?, val lens: String?, val settings: String?, val place: String?,
    val people: List<String>, val description: String?, val dimensions: String?,
)

/** Which photos the timeline shows: everything, an album, a person, or favorites. */
@Immutable
data class Filter(val albumId: String? = null, val personId: String? = null, val favorites: Boolean = false) {
    fun query(newestFirst: Boolean) = buildString {
        append("order=").append(if (newestFirst) "desc" else "asc")
        // in an album: no partner photos and no "timeline" visibility filter (archived album photos belong in it);
        // partner photos only in the main timeline, and only if that setting is enabled
        if (albumId != null) append("&albumId=").append(albumId)
        else append("&visibility=timeline&withPartners=").append(personId == null && !favorites && Settings.partners)
        if (personId != null) append("&personId=").append(personId)
        if (favorites) append("&isFavorite=true")
        append("&withStacked=true")
    }
}

/** "0:01:23.456000" -> "1:23" */
fun shortDuration(d: String?): String? {
    if (d == null) return null
    // Immich 3 returns milliseconds in the timeline, elsewhere "0:01:23.456000"
    d.toLongOrNull()?.let { ms -> val s = ms / 1000; return if (s <= 0) null else if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
    val p = d.split(":")
    if (p.size != 3) return null
    val h = p[0].toIntOrNull() ?: 0
    val m = p[1].toIntOrNull() ?: 0
    val s = p[2].substringBefore('.').toIntOrNull() ?: 0
    if (h == 0 && m == 0 && s == 0) return null
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun localTime(s: String, offsetHours: Double): LocalDateTime {
    val t = runCatching { LocalDateTime.parse(s.take(19)) }.getOrElse { LocalDateTime.of(1970, 1, 1, 0, 0) }
    // timeline/bucket gives fileCreatedAt in UTC + localOffsetHours; zero = already local
    return if (offsetHours == 0.0) t else t.plus((offsetHours * 60).toLong(), ChronoUnit.MINUTES)
}
