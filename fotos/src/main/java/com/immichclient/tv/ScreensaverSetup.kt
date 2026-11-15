package com.immichclient.tv

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dadb.AdbKeyPair
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread

/**
 * Sets the system screensaver to this app. Android TV usually has no settings screen for this (on Google TV
 * and some boxes the option is hidden); the setting is `Settings.Secure.screensaver_components`, which an app may
 * only write with WRITE_SECURE_SETTINGS. That permission can be granted once via adb (`pm grant`), after which the
 * app toggles itself on and off (and restores the previous screensaver when turned off). Without permission:
 * instructions + the adb commands, or [SelfAdb] (the box connects to its own network adb and does it itself).
 */
object Screensaver {
    private const val COMPONENTS = "screensaver_components"
    private const val ENABLED = "screensaver_enabled"

    fun component(c: Context): ComponentName = ComponentName(c, PhotoDream::class.java)
    fun componentText(c: Context): String = component(c).flattenToShortString()

    fun current(c: Context): String? = runCatching { AndroidSettings.Secure.getString(c.contentResolver, COMPONENTS) }.getOrNull()

    /** Is this app in the list of screensavers? */
    fun isSelected(c: Context): Boolean {
        val own = component(c)
        return current(c)?.split(',')?.any { ComponentName.unflattenFromString(it.trim()) == own } == true
    }

    /** Is this app currently set as the screensaver (and is the screensaver enabled)? */
    fun isActive(c: Context): Boolean = isSelected(c) && runCatching { AndroidSettings.Secure.getInt(c.contentResolver, ENABLED, 1) }.getOrDefault(1) != 0

    fun canWrite(c: Context) = c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    fun developerOptions(c: Context) = runCatching { AndroidSettings.Global.getInt(c.contentResolver, AndroidSettings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) }.getOrDefault(0) == 1
    /** USB debugging (on some boxes network debugging is separate from this, see [isPortOpen]) */
    fun usbDebugging(c: Context) = runCatching { AndroidSettings.Global.getInt(c.contentResolver, AndroidSettings.Global.ADB_ENABLED, 0) }.getOrDefault(0) == 1

    /**
     * Is adb listening on the network (127.0.0.1:5555)? Just open and close a TCP connection: without an adb handshake
     * the TV doesn't prompt. Blocks at most 400 ms (do not call on the main thread).
     */
    fun isPortOpen(): Boolean = runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", ADB_PORT), 400) }; true }.getOrDefault(false)
    const val ADB_PORT = 5555

    /** After how many ms of inactivity the screensaver starts (Android TV: system setting "screen off timeout"). */
    fun startsAfterMs(c: Context): Int? = runCatching { AndroidSettings.System.getInt(c.contentResolver, AndroidSettings.System.SCREEN_OFF_TIMEOUT) }.getOrNull()?.takeIf { it > 0 }

    /** Readable name of the current screensaver (service label if visible, otherwise component name). */
    fun currentName(c: Context): String? {
        val text = current(c)?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cn = ComponentName.unflattenFromString(text) ?: return text
        return runCatching { c.packageManager.getServiceInfo(cn, 0).loadLabel(c.packageManager).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: text
    }

    /** Remember the current screensaver (to restore when turned "off"), unless it is already this app. */
    fun rememberPrevious(c: Context) {
        val now = current(c)
        if (!now.isNullOrBlank() && !isSelected(c)) Settings.dreamPrevious = now
    }

    /** On: this app as screensaver (previous is remembered); off: restore previous (or default). */
    fun set(c: Context, on: Boolean): Boolean = runCatching {
        val cr = c.contentResolver
        if (on) {
            rememberPrevious(c)
            AndroidSettings.Secure.putString(cr, COMPONENTS, componentText(c))
            AndroidSettings.Secure.putInt(cr, ENABLED, 1)
        } else {
            val prev = Settings.dreamPrevious.takeIf { it.isNotBlank() && ComponentName.unflattenFromString(it) != component(c) } ?: default(c)
            AndroidSettings.Secure.putString(cr, COMPONENTS, prev)
        }
        Log.i("Photos", "screensaver ${if (on) "on" else "off"}: now ${current(c)}")
        true
    }.onFailure { Log.w("Photos", "set screensaver", it) }.getOrDefault(false)

    /** The system's own screensaver (e.g. Backdrop on Android TV). */
    private fun default(c: Context): String? =
        runCatching { AndroidSettings.Secure.getString(c.contentResolver, "screensaver_default_component") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { Resources.getSystem().let { r -> r.getIdentifier("config_dreamsDefaultComponent", "string", "android").takeIf { it != 0 }?.let { r.getString(it) } } }.getOrNull()

    fun canOpen(c: Context, action: String) = runCatching { Intent(action).resolveActivity(c.packageManager) != null }.getOrDefault(false)
    fun open(c: Context, action: String): Boolean = runCatching { c.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)

    const val ACTION_DREAM = "android.settings.DREAM_SETTINGS"
    const val ACTION_ABOUT = AndroidSettings.ACTION_DEVICE_INFO_SETTINGS
    const val ACTION_DEVELOPER = AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS

    /** The adb commands for the on-screen guide. */
    fun commands(c: Context): List<String> = listOf(
        "adb connect ${Pairing.localIp() ?: "<ip>"}",
        "adb shell pm grant ${c.packageName} android.permission.WRITE_SECURE_SETTINGS",
        "adb shell settings put secure screensaver_components ${componentText(c)}",
        "adb shell settings put secure screensaver_enabled 1",
    )
}

/**
 * The box drives its own adb (network debugging, 127.0.0.1:5555): grants the app WRITE_SECURE_SETTINGS and sets the
 * screensaver. Only works when network debugging (or "ADB over network") is enabled in Developer options; the TV asks
 * for permission the first time (key in filesDir). Afterwards adb is no longer needed.
 */
object SelfAdb {
    var status by mutableStateOf<Int?>(null)
        private set
    var detail by mutableStateOf<String?>(null)
        private set
    @Volatile private var busy = false

    fun setup(c: Context) {
        if (busy) return
        busy = true
        val app = c.applicationContext
        status = R.string.adb_connecting; detail = null
        thread(name = "SelfAdb") {
            try {
                // port closed = network debugging off: tell user right away (instead of after timeout)
                if (!Screensaver.isPortOpen()) { status = R.string.adb_no_connection; return@thread }
                Screensaver.rememberPrevious(app)
                val priv = File(app.filesDir, "adbkey"); val pub = File(app.filesDir, "adbkey.pub")
                if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
                // waiting for the permission prompt on the TV can take a bit: generous read timeout
                Dadb.create("127.0.0.1", Screensaver.ADB_PORT, AdbKeyPair.read(priv, pub), 4000, 120_000).use { d ->
                    val errors = StringBuilder()
                    fun sh(cmd: String) {
                        val r = d.shell(cmd)
                        if (r.exitCode != 0) errors.append("$cmd -> ${r.allOutput.trim()}\n")
                    }
                    if (!Screensaver.canWrite(app)) sh("pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS")
                    // with the permission the app sets it itself (and can turn it off later); if that fails, do it via adb
                    if (!Screensaver.set(app, true)) {
                        sh("settings put secure screensaver_components ${Screensaver.componentText(app)}")
                        sh("settings put secure screensaver_enabled 1")
                    }
                    Log.i("Photos", "adb: done, perm=${Screensaver.canWrite(app)} active=${Screensaver.isActive(app)} ${errors.toString().trim()}")
                    status = if (Screensaver.isActive(app)) R.string.adb_done else R.string.adb_failed
                    detail = errors.toString().trim().ifEmpty { null }
                }
            } catch (e: java.net.ConnectException) {
                Log.w("Photos", "adb: no connection", e); status = R.string.adb_no_connection; detail = null
            } catch (e: java.net.SocketTimeoutException) {
                Log.w("Photos", "adb: timeout", e); status = R.string.adb_no_permission; detail = null
            } catch (e: Exception) {
                Log.w("Photos", "adb failed", e); status = R.string.adb_failed; detail = e.message?.take(200)
            } finally { busy = false }
        }
    }
}

/** Settings -> Screensaver -> Set up: status, buttons, and step-by-step instructions. Back closes. */
class ScreensaverHelpActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Language.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PhotosTheme { ScreensaverHelp { finish() } } }
    }
}

@Composable
fun ScreensaverHelp(close: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler { close() }
    var tick by remember { mutableIntStateOf(0) }
    // re-check every 2 s: the user might change something via adb or system settings in the meantime
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    val active = remember(tick, SelfAdb.status) { Screensaver.isActive(ctx) }
    val canWrite = remember(tick, SelfAdb.status) { Screensaver.canWrite(ctx) }
    val dev = remember(tick) { Screensaver.developerOptions(ctx) }
    val usb = remember(tick) { Screensaver.usbDebugging(ctx) }
    val network by produceState(false, tick) { value = withContext(Dispatchers.IO) { Screensaver.isPortOpen() } }
    val currentName = remember(tick, SelfAdb.status) { Screensaver.currentName(ctx) }
    val startsAfter = remember(tick) { Screensaver.startsAfterMs(ctx) }
    val canDream = remember { Screensaver.canOpen(ctx, Screensaver.ACTION_DREAM) }
    val canAbout = remember { Screensaver.canOpen(ctx, Screensaver.ACTION_ABOUT) }
    val canDev = remember { Screensaver.canOpen(ctx, Screensaver.ACTION_DEVELOPER) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(40); runCatching { first.requestFocus() } }

    // header + status stay pinned at the top; below them the buttons and instructions scroll (otherwise the status
    // scrolls off screen as soon as a button gets focus)
    Column(Modifier.fillMaxSize().background(Color(0xF60A0A0A)).trapFocus().padding(top = 30.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 64.dp)) {
            Text(stringResource(R.string.dream_setup), style = MaterialTheme.typography.headlineMedium, color = AppColors.text)
            Text(stringResource(R.string.dream_setup_sub), style = MaterialTheme.typography.bodyMedium, color = AppColors.muted, modifier = Modifier.padding(bottom = 10.dp))
            Column(Modifier.fillMaxWidth().background(AppColors.surface, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        StatusCheck(active, stringResource(if (active) R.string.dream_status_on else R.string.dream_status_off))
                        StatusCheck(canWrite, stringResource(if (canWrite) R.string.dream_perm_yes else R.string.dream_perm_no))
                    }
                    Column(Modifier.weight(0.7f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        StatusCheck(dev, stringResource(if (dev) R.string.dream_dev_yes else R.string.dream_dev_no))
                        StatusCheck(usb, stringResource(if (usb) R.string.dream_usb_yes else R.string.dream_usb_no))
                        StatusCheck(network, stringResource(if (network) R.string.dream_net_yes else R.string.dream_net_no))
                    }
                }
                val time = startsAfter?.let { ms -> if (ms >= 60_000) stringResource(R.string.minutes, ms / 60_000) else stringResource(R.string.seconds, ms / 1000) }
                val lines = listOfNotNull(
                    currentName?.takeIf { !active }?.let { stringResource(R.string.dream_current, it) },
                    time?.takeIf { startsAfter!! < 24 * 3_600_000 }?.let { stringResource(R.string.dream_starts_after, it) },
                )
                if (lines.isNotEmpty()) Text(lines.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = AppColors.muted, modifier = Modifier.padding(start = 32.dp), maxLines = 1)
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            // Keep horizontal inset in contentPadding so 1.1x focused buttons are not clipped by LazyColumn bounds.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 64.dp, end = 64.dp, top = 12.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // direct buttons
            if (canWrite) item {
                ActionButton(stringResource(if (active) R.string.dream_turn_off else R.string.dream_turn_on), stringResource(R.string.dream_toggle_desc), Modifier.fillMaxWidth().focusRequester(first)) {
                    Screensaver.set(ctx, !active); tick++
                }
            }
            if (canDream) item { ActionButton(stringResource(R.string.dream_open_system), stringResource(R.string.dream_open_system_desc), Modifier.fillMaxWidth().then(if (!canWrite) Modifier.focusRequester(first) else Modifier)) { Screensaver.open(ctx, Screensaver.ACTION_DREAM) } }
            // automatic via self-adb
            item {
                Text(stringResource(R.string.dream_auto_heading), style = MaterialTheme.typography.titleLarge, color = AppColors.primary, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
                Text(stringResource(R.string.dream_auto_desc), style = MaterialTheme.typography.bodyMedium, color = AppColors.muted)
            }
            item {
                val s = SelfAdb.status
                ActionButton(stringResource(R.string.dream_auto_button), s?.let { stringResource(it) + (SelfAdb.detail?.let { d -> "  ·  $d" } ?: "") } ?: stringResource(R.string.dream_auto_button_desc),
                    Modifier.fillMaxWidth().then(if (!canWrite && !canDream) Modifier.focusRequester(first) else Modifier)) { SelfAdb.setup(ctx) }
            }
            // manual
            item {
                Text(stringResource(R.string.dream_manual_heading), style = MaterialTheme.typography.titleLarge, color = AppColors.primary, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
                InstructionStep("1", stringResource(R.string.dream_step1))
                InstructionStep("2", stringResource(R.string.dream_step2))
                InstructionStep("3", stringResource(R.string.dream_step3))
            }
            item {
                Column(Modifier.fillMaxWidth().background(AppColors.surface, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    for (cmd in Screensaver.commands(ctx)) Text(cmd, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp), color = AppColors.text)
                }
                Text(stringResource(R.string.dream_step4), style = MaterialTheme.typography.bodyMedium, color = AppColors.muted, modifier = Modifier.padding(top = 8.dp))
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.padding(top = 6.dp)) {
                    if (canAbout) ActionButton(stringResource(R.string.dream_open_about), stringResource(R.string.dream_open_about_desc), Modifier.weight(1f)) { Screensaver.open(ctx, Screensaver.ACTION_ABOUT) }
                    if (canDev) ActionButton(stringResource(R.string.dream_open_dev), stringResource(R.string.dream_open_dev_desc), Modifier.weight(1f)) { Screensaver.open(ctx, Screensaver.ACTION_DEVELOPER) }
                }
            }
            item { Text(stringResource(R.string.press_back_to_close), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp)) }
        }
    }
}

@Composable
private fun StatusCheck(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null, Modifier.size(20.dp), tint = if (ok) Color(0xFF86EFAC) else AppColors.muted)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun InstructionStep(n: String, text: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(n, style = MaterialTheme.typography.labelLarge, color = AppColors.primary, modifier = Modifier.width(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** Row in a picker list: name, subtitle, and (optional) image (album cover, face = round). */
class PickerItem(val id: String, val name: String, val sub: String?, val image: String?, val round: Boolean = false)

/**
 * Picker list (album or person for the screensaver), usable with hundreds of items: search button at top (filters
 * as you type, accents ignored), then the current selection, then the rest (albums: most recently updated first; people:
 * most frequent first). Focus starts on the current selection. Back closes without picking.
 * [multi] = multi-select: [selected] is a comma-separated list, OK toggles a row on/off (saved immediately) and Back closes;
 * order stays as when opened, so a row doesn't jump away when toggled.
 */
@Composable
fun PickerList(title: String, items: List<PickerItem>?, selected: String, close: () -> Unit, multi: Boolean = false, onSelect: (String, String) -> Unit) {
    Overlays.Open()
    val active = remember(selected) { selected.split(',').filter { it.isNotBlank() }.toSet() }
    val onOpen = remember { active }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = !searchOpen) { close() }
    val fr = remember { FocusRequester() }
    val searchBtn = remember { FocusRequester() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val visible = remember(items, query) {
        val l = items.orEmpty()
        val z = stripAccents(query.trim())
        if (z.isNotEmpty()) l.filter { stripAccents(it.name).contains(z, ignoreCase = true) }
        else l.filter { it.id in onOpen } + l.filter { it.id !in onOpen }
    }
    // focus on the current selection (at the top) or the first row; after searching, on the first match
    LaunchedEffect(items, query) {
        if (visible.isEmpty()) { if (items != null) { delay(40); runCatching { searchBtn.requestFocus() } }; return@LaunchedEffect }
        listState.scrollToItem(0)
        delay(40)
        repeat(5) { if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect; delay(16) }
    }
    var wasOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color(0xF60A0A0A)).trapFocus().padding(horizontal = 64.dp, vertical = 36.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.displaySmall)
                    if (items != null) Text(Language.number(visible.size) + (if (query.isNotBlank()) "  ·  “$query”" else "") + (if (multi) "  ·  " + stringResource(R.string.dream_albums_hint, active.size) else ""), style = MaterialTheme.typography.bodyMedium, color = AppColors.muted)
                }
                if (!items.isNullOrEmpty()) ChoiceChip(
                    selected = query.isNotBlank(), onClick = { searchOpen = true }, modifier = Modifier.focusRequester(searchBtn),
                    leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.width(18.dp)) },
                    text = query.ifBlank { stringResource(R.string.search) },
                )
            }
            Spacer(Modifier.height(16.dp))
            when {
                items == null -> LoadingDots(true)
                visible.isEmpty() -> Text(stringResource(R.string.nothing_found), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted)
                else -> LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp)) {
                    items(visible, key = { it.id }) { item ->
                        PickerRow(item, item.id in active, if (item === visible.first()) Modifier.focusRequester(fr) else Modifier) { onSelect(item.id, item.name) }
                    }
                }
            }
        }
    }
    LaunchedEffect(searchOpen) { if (wasOpen && !searchOpen && visible.isEmpty()) { delay(16); runCatching { searchBtn.requestFocus() } }; wasOpen = searchOpen }
    if (searchOpen) TextInputOverlay(title, query, live = { query = it }, action = stringResource(R.string.action_done), close = { searchOpen = false }) { query = it; searchOpen = false }
}

@Composable
private fun PickerRow(item: PickerItem, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focus by remember { mutableStateOf(false) }
    androidx.tv.material3.Surface(
        onClick = onClick, modifier = modifier.fillMaxWidth().height(64.dp).onFocusChanged { focus = it.isFocused },
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = AppColors.surface, focusedContainerColor = AppColors.primary, focusedContentColor = AppColors.onPrimary),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val shape = if (item.round) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(8.dp)
            Box(Modifier.size(46.dp).clip(shape).background(AppColors.surfaceHigh)) {
                if (item.image != null) coil3.compose.AsyncImage(imageRequest(item.image), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, color = if (focus) AppColors.onPrimary else AppColors.text, maxLines = 1)
                item.sub?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (focus) AppColors.onPrimary.copy(alpha = 0.8f) else AppColors.muted, maxLines = 1) }
            }
            if (selected) Icon(Icons.Filled.CheckCircle, null, Modifier.padding(end = 8.dp), tint = if (focus) AppColors.onPrimary else AppColors.primary)
        }
    }
}

/** Albums for the picker list: cover, name + count/period; most recently updated first. */
@Composable
fun albumChoices(): List<PickerItem>? {
    val l by produceState<List<PickerItem>?>(null) {
        value = runCatching { Immich.albums().filter { it.count > 0 }.sortedByDescending { it.updatedAt }.map { PickerItem(it.id, it.name, albumSubtitle(it), it.cover?.let(Immich::thumb)) } }.getOrDefault(emptyList())
    }
    return l
}

/** People: face + name; in Immich's order (favorites, then most frequent). */
@Composable
fun personChoices(): List<PickerItem>? {
    val l by produceState<List<PickerItem>?>(null) {
        value = runCatching { Immich.people().map { PickerItem(it.id, it.name, null, Immich.face(it.id), round = true) } }.getOrDefault(emptyList())
    }
    return l
}
