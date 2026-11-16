package com.immichclient.tv

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * App language, independent of the system language. The choice is stored in prefs ("settings"/"language", per device)
 * and applied via a Context with its own Configuration ([wrap], in MainActivity.attachBaseContext); after a switch,
 * `recreate()`. For text outside Compose (pairing page, error messages on the server thread, row headers) there is [ctx]:
 * the application context in the chosen language. Date/time patterns and number formatting follow the chosen [locale].
 *
 * Adding a language: `values-xx/strings.xml` (all strings + plurals + date patterns), code in [LANGUAGES], and the name
 * in [name] (in its own language); nothing else.
 */
object Language {
    /** Supported languages (ISO 639-1), in the order of the picker screen. */
    val LANGUAGES = listOf("en", "nl", "de", "fr", "es")
    private const val PREFS = "settings"
    private const val KEY = "language"

    /** Name of the language in that language itself (for the picker screen and settings). */
    fun name(code: String) = when (code) {
        "nl" -> "Nederlands"; "de" -> "Deutsch"; "fr" -> "Français"; "es" -> "Español"; else -> "English"
    }

    @Volatile var code: String = "en"
        private set
    /** Has a language ever been chosen (otherwise show the picker screen first)? */
    var chosen by mutableStateOf(false)
        private set
    /** Section in which the activity reopens after a language switch (recreate), once (Settings). */
    @Volatile var restartSection: Section? = null
    /** After that restart, put focus on the Language row (the list stays in the same position, see SettingsScreen). */
    @Volatile var focusLanguage = false
    val locale: Locale get() = Locale.forLanguageTag(code)

    /** Application context in the chosen language, for strings outside Compose. */
    lateinit var ctx: Context
        private set
    private lateinit var baseCtx: Context

    /** Language suggested by the system setting (if supported), otherwise English. */
    fun systemLanguage(c: Context): String {
        val l = c.resources.configuration.locales.let { if (it.isEmpty) Locale.getDefault() else it[0] }
        return l.language.takeIf { it in LANGUAGES } ?: "en"
    }

    /** Once at process start (App.onCreate). */
    fun start(app: Context) {
        baseCtx = app.applicationContext
        val p = baseCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = p.getString(KEY, null)?.takeIf { it in LANGUAGES }
        chosen = saved != null
        code = saved ?: systemLanguage(baseCtx)
        apply()
    }

    /** Save choice and apply immediately; the activity must call `recreate()` afterwards. */
    fun choose(c: Context, next: String) {
        if (next !in LANGUAGES) return
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, next).apply()
        code = next
        chosen = true
        apply()
    }

    private fun apply() {
        Locale.setDefault(locale)
        ctx = wrap(baseCtx)
        DateFormats.refresh()
    }

    /** Context with the chosen language (for Activity.attachBaseContext). */
    fun wrap(base: Context): Context = base.createConfigurationContext(configuration(base, locale))

    private fun configuration(base: Context, l: Locale) = Configuration(base.resources.configuration).apply { setLocale(l); setLayoutDirection(l) }

    /** Resources in another supported language (pairing page in the phone's language). */
    fun resources(language: String): Resources =
        if (language == code) ctx.resources else baseCtx.createConfigurationContext(configuration(baseCtx, Locale.forLanguageTag(language))).resources

    /** First supported language from an Accept-Language header ("nl-NL,nl;q=0.9,en;q=0.8"), otherwise the app language. */
    fun fromAcceptLanguage(header: String?): String {
        if (header.isNullOrBlank()) return code
        for (part in header.split(',')) {
            val tag = part.substringBefore(';').trim().lowercase(Locale.ROOT)
            val lang = tag.substringBefore('-')
            if (lang in LANGUAGES) return lang
        }
        return code
    }

    fun s(@StringRes id: Int, vararg args: Any): String = ctx.getString(id, *args)
    fun plural(@PluralsRes id: Int, n: Int, vararg args: Any): String = ctx.resources.getQuantityString(id, n, *args)
    /** Number with thousands separator according to the language ("12.345" / "12,345" / "12 345"). */
    fun number(n: Int): String = DateFormats.number.format(n.toLong())
    fun number(n: Long): String = DateFormats.number.format(n)
}

/** Date/time formatting in the chosen language; patterns are in strings (per language) so translations can adjust them. */
object DateFormats {
    lateinit var monthYear: DateTimeFormatter; private set
    lateinit var monthShort: DateTimeFormatter; private set
    lateinit var dayShort: DateTimeFormatter; private set
    lateinit var dayYear: DateTimeFormatter; private set
    lateinit var dayLong: DateTimeFormatter; private set
    lateinit var time: DateTimeFormatter; private set
    lateinit var number: NumberFormat; private set

    fun refresh() {
        val l = Language.locale
        fun f(id: Int) = DateTimeFormatter.ofPattern(Language.s(id), l)
        monthYear = f(R.string.pattern_month_year); monthShort = f(R.string.pattern_month_short)
        dayShort = f(R.string.pattern_day_short); dayYear = f(R.string.pattern_day_year); dayLong = f(R.string.pattern_day_long)
        time = f(R.string.pattern_time)
        number = NumberFormat.getIntegerInstance(l)
    }
}

private fun String.capitalizeFirst() = replaceFirstChar { if (it.isLowerCase()) it.titlecase(Language.locale) else it.toString() }

fun monthTitle(key: String): String =
    runCatching { LocalDate.parse(key.take(10)).format(DateFormats.monthYear).capitalizeFirst() }.getOrDefault(key)

fun dayTitle(d: LocalDate, withYear: Boolean): String {
    val today = LocalDate.now()
    return when (d) {
        today -> Language.s(R.string.today)
        today.minusDays(1) -> Language.s(R.string.yesterday)
        else -> d.format(if (withYear || d.year != today.year) DateFormats.dayYear else DateFormats.dayShort).capitalizeFirst()
    }
}

fun monthYear(t: LocalDateTime): String = t.format(DateFormats.monthYear)

fun dayLong(t: LocalDateTime): String = t.format(DateFormats.dayLong).capitalizeFirst()

fun dateLong(t: LocalDateTime): String = dayLong(t) + " ·  " + t.format(DateFormats.time)

fun yearsAgo(n: Int) = Language.plural(R.plurals.years_ago, n, n)

/**
 * Country names: Immich returns them in English. Map via ISO code (Locale.getISOCountries + English name) to the name
 * in the app language; unknown names remain as-is.
 */
object CountryName {
    private val isoFromEnglish: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for (iso in Locale.getISOCountries()) {
            val n = Locale("", iso).getDisplayCountry(Locale.ENGLISH).lowercase(Locale.ROOT)
            m[n] = iso
            // ICU writes "Bosnia & Herzegovina", "St. Lucia"; Immich's geocoder writes "Bosnia and Herzegovina", "Saint Lucia"
            m[n.replace(" & ", " and ")] = iso
            if (n.startsWith("st. ")) m["saint " + n.removePrefix("st. ")] = iso
        }
        // names Immich (geocoder) writes differently from ICU display names
        m["the netherlands"] = "NL"; m["netherlands"] = "NL"; m["united states of america"] = "US"; m["united states"] = "US"
        m["czech republic"] = "CZ"; m["czechia"] = "CZ"; m["russia"] = "RU"; m["south korea"] = "KR"; m["north korea"] = "KP"
        m["vietnam"] = "VN"; m["macedonia"] = "MK"; m["north macedonia"] = "MK"; m["ivory coast"] = "CI"; m["côte d'ivoire"] = "CI"
        m["swaziland"] = "SZ"; m["eswatini"] = "SZ"; m["turkey"] = "TR"; m["türkiye"] = "TR"; m["uk"] = "GB"; m["great britain"] = "GB"
        m["cape verde"] = "CV"; m["myanmar (burma)"] = "MM"; m["burma"] = "MM"; m["the bahamas"] = "BS"; m["the gambia"] = "GM"
        m["bolivia"] = "BO"; m["tanzania"] = "TZ"; m["laos"] = "LA"; m["syria"] = "SY"; m["iran"] = "IR"; m["moldova"] = "MD"
        m["brunei"] = "BN"; m["micronesia"] = "FM"; m["palestine"] = "PS"; m["vatican city"] = "VA"; m["hong kong"] = "HK"; m["macau"] = "MO"
        m
    }
    private val cache = HashMap<String, String>()
    private var cacheLanguage = ""

    /** English name (as Immich returns it) -> ISO 3166 code, or null if unknown. */
    fun iso(english: String): String? = isoFromEnglish[english.trim().lowercase(Locale.ROOT)]

    /** Name of a country (ISO code) in the app language. */
    fun fromIso(iso: String): String = Locale("", iso).getDisplayCountry(Language.locale).ifBlank { iso }

    /** English name -> name in the app language (cached per language). */
    @Synchronized fun translate(english: String): String {
        if (cacheLanguage != Language.code) { cache.clear(); cacheLanguage = Language.code }
        return cache.getOrPut(english) {
            val iso = isoFromEnglish[english.trim().lowercase(Locale.ROOT)] ?: return@getOrPut english
            Locale("", iso).getDisplayCountry(Language.locale).ifBlank { english }
        }
    }
}
