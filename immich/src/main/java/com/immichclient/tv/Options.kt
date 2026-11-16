package com.immichclient.tv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

/*
 * Settings (bottom section in the drawer): one list with groups. Each row is a tv-Surface: ▲▼ selects a row,
 * ◀ ▶ adjusts the value (step back/forward), OK picks the next value (wrapping around) or runs an action.
 * Everything reads/writes the Settings object directly; the rest of the app follows via Compose state.
 */

@Composable
fun SettingsScreen(reconnect: () -> Unit, disconnected: () -> Unit) {
    val ctx = LocalContext.current
    val me by produceState(Immich.me, Immich.version) { value = Immich.loadMe() }
    val immichVersion by produceState<String?>(null, Immich.version) { value = Immich.serverVersion() }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    var confirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var greetingInput by remember { mutableStateOf(false) }
    // cache size: disk + memory, re-measured after clearing
    var cacheRound by remember { mutableIntStateOf(0) }
    val cacheSizeFmt = stringResource(R.string.cache_size)
    val cache by produceState<String?>(null, cacheRound) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val l = SingletonImageLoader.get(ctx)
                val disk = l.diskCache?.size ?: 0L; val memory = l.memoryCache?.size ?: 0L
                cacheSizeFmt.format(Language.locale, Language.number((disk / 1048576.0).roundToLong()), Language.number((memory / 1048576.0).roundToLong()))
            }.getOrDefault("?")
        }
    }
    // screensaver: state (re-read on change and on resume)
    var dreamRound by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) dreamRound++ }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val dreamActive = remember(dreamRound) { Screensaver.isActive(ctx) }
    val dreamCanWrite = remember(dreamRound) { Screensaver.canWrite(ctx) }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val dreamRow = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    val languageRow = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(30)
        // after a language switch (recreate) the list is still in the same position: focus back on the Language row, otherwise the first row
        if (Language.focusLanguage) { Language.focusLanguage = false; repeat(5) { if (runCatching { languageRow.requestFocus() }.isSuccess) return@LaunchedEffect; delay(16) } }
        // via the gear icon next to the screensaver (system settings): jump straight to that group
        if (Keys.toScreensaver) { Keys.toScreensaver = false; if (SCREENSAVER_ENABLED) { list.scrollToItem(dreamItemIndex(Settings.allSettings)); delay(50); if (runCatching { dreamRow.requestFocus() }.isSuccess) return@LaunchedEffect } }
        runCatching { first.requestFocus() }
    }
    LaunchedEffect(confirm) { if (confirm) { delay(6000); confirm = false } }
    val offOn = listOf(stringResource(R.string.off), stringResource(R.string.on))
    // basic = the settings most people look for; "all" shows the rest too (remembered per device)
    val all = Settings.allSettings

    LazyColumn(
        Modifier.fillMaxSize(),
        state = list,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { ScreenHeader(stringResource(R.string.settings), stringResource(R.string.settings_sub)) }
        item {
            ChoicesRow(stringResource(R.string.show_settings), stringResource(if (all) R.string.settings_all_desc else R.string.settings_basic_desc),
                listOf(stringResource(R.string.settings_basic), stringResource(R.string.settings_all)), if (all) 1 else 0, Modifier.focusRequester(first)) { Settings.allSettings = it == 1 }
        }

        // row counts per mode are also used in dreamItemIndex()
        groupHeader(R.string.group_slideshow)
        item {
            ChoicesRow(stringResource(R.string.time_per_photo), stringResource(R.string.time_per_photo_desc), Settings.SLIDESHOW_TIMES.map { stringResource(R.string.seconds, it) },
                Settings.SLIDESHOW_TIMES.indexOf(Settings.slideshowSeconds).coerceAtLeast(0)) { Settings.slideshowSeconds = Settings.SLIDESHOW_TIMES[it] }
        }
        if (all) toggleRow(R.string.ken_burns, R.string.ken_burns_desc, offOn, Settings.kenBurns) { Settings.kenBurns = it }
        item { ChoicesRow(stringResource(R.string.order), stringResource(R.string.slideshow_order_desc), listOf(stringResource(R.string.by_date), stringResource(R.string.random)), if (Settings.slideshowRandom) 1 else 0) { Settings.slideshowRandom = it == 1 } }
        item { ChoicesRow(stringResource(R.string.videos), stringResource(R.string.slideshow_videos_desc), listOf(stringResource(R.string.play), stringResource(R.string.skip)), if (Settings.slideshowVideos) 0 else 1) { Settings.slideshowVideos = it == 0 } }
        item { ChoicesRow(stringResource(R.string.transition), stringResource(R.string.transition_desc), listOf(stringResource(R.string.fade), stringResource(R.string.slide)), if (Settings.slideshowSlide) 1 else 0) { Settings.slideshowSlide = it == 1 } }
        toggleRow(R.string.slideshow_ring, R.string.slideshow_ring_desc, offOn, Settings.slideshowRing) { Settings.slideshowRing = it }

        // ---- Screensaver (item index dreamItemIndex() = the group header; do not change the order above without updating it) ----
        if (SCREENSAVER_ENABLED) {
            groupHeader(R.string.group_screensaver)
            item {
                // with write permission: on/off sets it immediately; without: OK opens the guide (adb, system settings)
                if (dreamCanWrite) ChoicesRow(stringResource(R.string.screensaver), stringResource(if (dreamActive) R.string.dream_status_on else R.string.dream_on_desc), offOn, if (dreamActive) 1 else 0, Modifier.focusRequester(dreamRow)) {
                    Screensaver.set(ctx, it == 1); dreamRound++
                } else SettingRow(stringResource(R.string.screensaver), stringResource(if (dreamActive) R.string.dream_status_on else R.string.dream_status_off_ok), Modifier.focusRequester(dreamRow), onClick = { runCatching { ctx.startActivity(android.content.Intent(ctx, ScreensaverHelpActivity::class.java)) } }) { focus ->
                    Text(stringResource(if (dreamActive) R.string.on else R.string.off), style = MaterialTheme.typography.titleMedium, color = if (focus) AppColors.onPrimary else AppColors.text, modifier = Modifier.padding(end = 10.dp))
                }
            }
            item { ActionRow(stringResource(R.string.dream_setup), stringResource(R.string.dream_setup_desc)) { runCatching { ctx.startActivity(android.content.Intent(ctx, ScreensaverHelpActivity::class.java)) } } }
            item { ActionRow(stringResource(R.string.dream_preview), stringResource(R.string.dream_preview_desc)) { runCatching { ctx.startActivity(android.content.Intent(ctx, PreviewActivity::class.java)) } } }
            item { ChoicesRow(stringResource(R.string.dream_source), stringResource(R.string.dream_source_desc), DreamSource.entries.map { stringResource(it.label) }, Settings.dreamSource.ordinal) { Settings.dreamSource = DreamSource.entries[it] } }
            item {
                val years = Settings.DREAM_YEARS
                ChoicesRow(stringResource(R.string.dream_from), stringResource(R.string.dream_from_desc), years.map { if (it == 0) stringResource(R.string.all_years) else it.toString() },
                    years.indexOf(Settings.dreamFrom).coerceAtLeast(0)) { Settings.dreamFrom = years[it] }
            }
            item {
                ChoicesRow(stringResource(R.string.order), stringResource(R.string.dream_order_desc), DreamOrder.entries.map { stringResource(it.label) }, Settings.dreamOrder.ordinal) { Settings.dreamOrder = DreamOrder.entries[it] }
            }
            item {
                val labels = Settings.DREAM_TIMES.map { stringResource(R.string.seconds, it) }
                ChoicesRow(stringResource(R.string.time_per_photo), stringResource(R.string.dream_time_desc), labels, Settings.DREAM_TIMES.indexOf(Settings.dreamSeconds).coerceAtLeast(0)) { Settings.dreamSeconds = Settings.DREAM_TIMES[it] }
            }
            toggleRow(R.string.dream_clock, R.string.dream_clock_desc, offOn, Settings.dreamClock) { Settings.dreamClock = it }
            item { ChoicesRow(stringResource(R.string.map_on_photo), stringResource(R.string.dream_map_desc), MapSize.entries.map { stringResource(it.label) }, Settings.dreamMap.ordinal) { Settings.dreamMap = MapSize.entries[it] } }
            if (all) toggleRow(R.string.ken_burns, R.string.dream_ken_burns_desc, offOn, Settings.dreamKenBurns) { Settings.dreamKenBurns = it }
            if (all) toggleRow(R.string.dream_info, R.string.dream_info_desc, offOn, Settings.dreamInfo) { Settings.dreamInfo = it }
            if (all) toggleRow(R.string.dream_ring, R.string.dream_ring_desc, offOn, Settings.dreamRing) { Settings.dreamRing = it }
        }

        groupHeader(R.string.group_viewing)
        item { ChoicesRow(stringResource(R.string.map_on_photo), stringResource(R.string.map_on_photo_desc), MapSize.entries.map { stringResource(it.label) }, Settings.viewerMap.ordinal) { Settings.viewerMap = MapSize.entries[it] } }
        item {
            val off = stringResource(R.string.off)
            val always = stringResource(R.string.always_show)
            val labels = Settings.BAR_TIMES.map {
                when (it) {
                    -1 -> off
                    0 -> always
                    else -> stringResource(R.string.seconds, it / 1000)
                }
            }
            ChoicesRow(stringResource(R.string.date_and_place), stringResource(R.string.date_and_place_desc), labels, Settings.BAR_TIMES.indexOf(Settings.barMs).coerceAtLeast(0)) { Settings.barMs = Settings.BAR_TIMES[it] }
        }
        if (all) item { ChoicesRow(stringResource(R.string.counter), stringResource(R.string.counter_desc, Language.number(12345)), offOn, if (Settings.counter) 1 else 0) { Settings.counter = it == 1 } }
        if (all) toggleRow(R.string.album_name, R.string.album_name_desc, offOn, Settings.albumName) { Settings.albumName = it }
        if (all) toggleRow(R.string.blurred_background, R.string.blurred_background_desc, offOn, Settings.blurredBackground) { Settings.blurredBackground = it }

        groupHeader(R.string.group_timeline)
        item { ChoicesRow(stringResource(R.string.photo_size), stringResource(R.string.photo_size_desc), RowSize.entries.map { stringResource(it.label) }, Settings.rowSize.ordinal) { Settings.rowSize = RowSize.entries[it] } }
        if (all) item { ChoicesRow(stringResource(R.string.order), stringResource(R.string.timeline_order_desc), listOf(stringResource(R.string.newest_first), stringResource(R.string.oldest_first)), if (Settings.newestFirst) 0 else 1) { Settings.newestFirst = it == 0 } }
        if (all) toggleRow(R.string.partner_photos, R.string.partner_photos_desc, offOn, Settings.partners) { Settings.partners = it }
        if (all) toggleRow(R.string.memories_at_top, R.string.memories_at_top_desc, offOn, Settings.memories) { Settings.memories = it }
        item {
            val choices = listOf(Section.PHOTOS, Section.MEMORIES, Section.MAP, Section.ALBUMS, Section.FOLDERS, Section.FAVORITES)
            ChoicesRow(stringResource(R.string.start_screen), stringResource(R.string.start_screen_desc), choices.map { stringResource(it.label) },
                choices.indexOf(Settings.start).coerceAtLeast(0)) { Settings.start = choices[it] }
        }
        if (all) item {
            val example = stringResource(R.string.greeting_with_name, stringResource(R.string.greeting_afternoon), me?.name?.substringBefore(' ') ?: "…")
            ChoicesRow(stringResource(R.string.greeting), stringResource(R.string.greeting_desc, example), Greeting.entries.map { stringResource(it.label) }, Settings.greeting.ordinal) { Settings.greeting = Greeting.entries[it] }
        }
        if (all && Settings.greeting == Greeting.CUSTOM) item {
            ActionRow(stringResource(R.string.custom_text), Settings.greetingText.ifBlank { stringResource(R.string.nothing_entered_yet) }) { greetingInput = true }
        }

        groupHeader(R.string.group_map)
        if (all) item { ChoicesRow(stringResource(R.string.start_view), stringResource(R.string.start_view_desc), MapStart.entries.map { stringResource(it.label) }, Settings.mapStart.ordinal) { Settings.mapStart = MapStart.entries[it] } }
        if (all) item { ChoicesRow(stringResource(R.string.cluster_level), stringResource(R.string.cluster_level_desc), ClusterLevel.entries.map { stringResource(it.label) }, Settings.cluster.ordinal) { Settings.cluster = ClusterLevel.entries[it] } }
        item { ChoicesRow(stringResource(R.string.map_style), stringResource(R.string.map_style_desc), MapStyle.entries.map { stringResource(it.label) }, Settings.mapStyle.ordinal) { Settings.mapStyle = MapStyle.entries[it] } }

        groupHeader(R.string.group_appearance)
        item {
            // language: apply immediately = recreate activity (all texts, dates, and country names follow)
            ChoicesRow(stringResource(R.string.language), stringResource(R.string.language_desc), Language.LANGUAGES.map { Language.name(it) }, Language.LANGUAGES.indexOf(Language.code).coerceAtLeast(0), Modifier.focusRequester(languageRow)) {
                val next = Language.LANGUAGES[it]
                if (next != Language.code) { Language.choose(ctx, next); Language.restartSection = Section.SETTINGS; Language.focusLanguage = true; (ctx as? android.app.Activity)?.recreate() }
            }
        }
        item {
            ChoicesRow(stringResource(R.string.accent_color), stringResource(R.string.accent_color_desc), Accent.entries.map { stringResource(it.label) }, Settings.accent.ordinal, color = Settings.accent.primary) { Settings.accent = Accent.entries[it] }
        }

        if (all) {
            groupHeader(R.string.group_storage)
            item {
                ActionRow(stringResource(R.string.clear_image_cache), cache ?: "…") {
                    val l = SingletonImageLoader.get(ctx)
                    l.memoryCache?.clear(); ThumbHash.clear()
                    Thread { runCatching { l.diskCache?.clear() }; cacheRound++ }.start()
                }
            }
        }

        groupHeader(R.string.group_account)
        item { InfoRow(stringResource(R.string.immich_server), Immich.server.ifBlank { "–" }) }
        item { InfoRow(stringResource(R.string.signed_in_as), me?.let { "${it.name}  ·  ${it.email}" } ?: "…") }
        if (all) item {
            val miss = Permissions.missing
            val status = when {
                miss == null -> stringResource(R.string.perms_checking)
                miss.isEmpty() -> stringResource(R.string.perms_ok)
                else -> stringResource(R.string.perms_missing_short, miss.joinToString(", "))
            }
            ActionRow(stringResource(R.string.perms_title), status) { scope.launch { Permissions.check() }; Permissions.show(ctx) }
        }
        item { ActionRow(stringResource(R.string.reconnect), stringResource(R.string.reconnect_desc)) { reconnect() } }
        item {
            ActionRow(stringResource(if (confirm) R.string.are_you_sure else R.string.disconnect), stringResource(if (confirm) R.string.are_you_sure_desc else R.string.disconnect_desc)) {
                if (!confirm) confirm = true else { Immich.disconnect(ctx, Immich.viewer); disconnected() }
            }
        }

        groupHeader(R.string.group_about)
        item { InfoRow(stringResource(R.string.app_line, stringResource(R.string.app_name)), stringResource(R.string.version, version)) }
        item { InfoRow(stringResource(R.string.immich_server), immichVersion?.let { stringResource(R.string.version, it) } ?: "…") }
        if (all) item { InfoRow(stringResource(R.string.map), stringResource(R.string.map_attribution, SAT_ATTRIBUTION)) }
        item { Text(stringResource(R.string.unofficial_long), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) }
    }
    if (greetingInput) TextInputOverlay(stringResource(R.string.greeting), Settings.greetingText, action = stringResource(R.string.action_done), close = { greetingInput = false }) {
        Settings.greetingText = it; greetingInput = false
    }
}

/** Item index of the "Screensaver" group header in the list: header + Basic/All row + Slideshow group (1 + 3 or 6 rows). */
private fun dreamItemIndex(all: Boolean) = 3 + if (all) 6 else 5
/** Show the Screensaver group in settings (since 2.2; false hides the group again, the DreamService remains). */
const val SCREENSAVER_ENABLED = true

private fun LazyListScope.groupHeader(@StringRes name: Int) = item {
    Text(stringResource(name), style = MaterialTheme.typography.titleLarge, color = AppColors.primary, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp, start = 4.dp))
}

private fun LazyListScope.toggleRow(@StringRes title: Int, @StringRes desc: Int, offOn: List<String>, value: Boolean, set: (Boolean) -> Unit) = item {
    ChoicesRow(stringResource(title), stringResource(desc), offOn, if (value) 1 else 0) { set(it == 1) }
}

/** Row with a choice: ◀ ▶ = previous/next value (no wrap), OK = next (wraps around). */
@Composable
private fun ChoicesRow(title: String, desc: String, options: List<String>, index: Int, modifier: Modifier = Modifier, color: Color? = null, onSelect: (Int) -> Unit) {
    SettingRow(
        title, desc, modifier.onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key.nativeKeyCode) {
                // always handled (even at the edges), otherwise focus jumps to the drawer at the lowest value
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (index > 0) onSelect(index - 1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (index < options.lastIndex) onSelect(index + 1); true }
                else -> false
            }
        },
        onClick = { onSelect((index + 1) % options.size) },
    ) { focus ->
        val dim = if (focus) AppColors.onPrimary.copy(alpha = 0.45f) else AppColors.muted.copy(alpha = 0.5f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", style = MaterialTheme.typography.titleLarge, color = if (index > 0) (if (focus) AppColors.onPrimary else AppColors.text) else dim)
            if (color != null) Box(Modifier.padding(start = 14.dp).size(16.dp).background(color, CircleShape))
            // 240 dp: the longest value ("Country with the most photos", "Land mit den meisten Fotos") is ~220 dp
            Text(options[index], style = MaterialTheme.typography.titleMedium, color = if (focus) AppColors.onPrimary else AppColors.text, modifier = Modifier.padding(horizontal = 14.dp).width(240.dp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("›", style = MaterialTheme.typography.titleLarge, color = if (index < options.lastIndex) (if (focus) AppColors.onPrimary else AppColors.text) else dim)
        }
    }
}

@Composable
private fun ActionRow(title: String, desc: String, modifier: Modifier = Modifier, onClick: () -> Unit) = SettingRow(title, desc, modifier, onClick = onClick) { focus ->
    Text("OK", style = MaterialTheme.typography.labelLarge, color = if (focus) AppColors.onPrimary else AppColors.muted, modifier = Modifier.padding(end = 10.dp))
}

@Composable
private fun InfoRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = AppColors.muted, modifier = Modifier.width(220.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SettingRow(title: String, desc: String, modifier: Modifier = Modifier, onClick: () -> Unit, trailing: @Composable (focus: Boolean) -> Unit) {
    var focus by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick, modifier = modifier.fillMaxWidth().onFocusChanged { focus = it.isFocused },
        shape = ButtonStyle.shape,
        scale = ButtonStyle.scale(focusedScale = 1f),
        colors = ButtonStyle.colors,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                // text styles have their own color (Theme.kt), which overrides the Surface's content color: set explicitly
                Text(title, style = MaterialTheme.typography.titleMedium, color = if (focus) AppColors.onPrimary else AppColors.text, maxLines = 1)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = if (focus) AppColors.onPrimary.copy(alpha = 0.8f) else AppColors.muted, maxLines = 2)
            }
            trailing(focus)
        }
    }
}

/** Overview of all shortcuts per screen (INFO button, or via Settings -> Keys). Any key closes it. */
@Composable
fun KeysOverview(close: () -> Unit) {
    Overlays.Open()
    val fr = remember { FocusRequester() }
    BackHandler { close() }
    LaunchedEffect(Unit) { delay(30); runCatching { fr.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Color(0xF20A0A0A)).trapFocus().focusRequester(fr).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key.nativeKeyCode) { KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> false; else -> { close(); true } }
            }
            .padding(horizontal = 56.dp, vertical = 36.dp),
    ) {
        Column {
            Text(stringResource(R.string.keys), style = MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.keys_close), style = MaterialTheme.typography.bodyMedium, color = AppColors.muted)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    KeyGroup(R.string.keys_group_everywhere, listOf(
                        R.string.key_hold_back to R.string.key_hold_back_desc,
                        R.string.key_info to R.string.key_info_desc,
                        R.string.key_left_edge to R.string.key_left_edge_desc,
                        R.string.key_back to R.string.key_back_desc,
                    ))
                    KeyGroup(R.string.keys_group_timeline, listOf(
                        R.string.key_arrows to R.string.key_arrows_desc,
                        R.string.key_ok to R.string.key_ok_open,
                        R.string.key_play_pause to R.string.key_play_pause_desc,
                        R.string.key_rw_ff to R.string.key_rw_ff_month,
                        R.string.key_scrollbar to R.string.key_scrollbar_desc,
                        R.string.key_back to R.string.key_back_top,
                    ))
                    KeyGroup(R.string.keys_group_settings, listOf(
                        R.string.key_left_right to R.string.key_value_desc,
                        R.string.key_ok to R.string.key_ok_value_desc,
                    ))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    KeyGroup(R.string.keys_group_viewer, listOf(
                        R.string.key_left_right to R.string.key_prev_next,
                        R.string.key_ok_play to R.string.key_slideshow_desc,
                        R.string.key_info_button to R.string.key_info_panel_desc,
                        R.string.key_ok_info to R.string.key_ok_info_desc,
                        R.string.key_rw_ff to R.string.key_rw_ff_viewer,
                        R.string.key_back to R.string.key_close,
                    ))
                    KeyGroup(R.string.keys_group_map, listOf(
                        R.string.key_arrows to R.string.key_map_arrows,
                        R.string.key_ok to R.string.key_map_ok,
                        R.string.key_hold_ok to R.string.key_map_hold_ok_desc,
                        R.string.key_play_pause to R.string.key_map_play_desc,
                        R.string.key_rw_ff to R.string.key_map_zoom,
                        R.string.key_back to R.string.key_map_back,
                        R.string.key_map_left to R.string.key_left_edge_desc,
                    ))
                }
            }
        }
    }
}

@Composable
private fun KeyGroup(@StringRes name: Int, rows: List<Pair<Int, Int>>) {
    Column {
        Text(stringResource(name), style = MaterialTheme.typography.titleMedium, color = AppColors.primary, modifier = Modifier.padding(bottom = 4.dp))
        for ((key, what) in rows) Row(Modifier.padding(vertical = 1.dp)) {
            Text(stringResource(key), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(150.dp).padding(end = 8.dp), maxLines = 2)
            Text(stringResource(what), style = MaterialTheme.typography.bodyMedium, color = AppColors.muted, maxLines = 2, modifier = Modifier.weight(1f))
        }
    }
}
