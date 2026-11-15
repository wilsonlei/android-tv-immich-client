package com.immichclient.tv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import java.io.File
import kotlin.math.roundToInt

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Language.start(this)
        Settings.load(this, null)
        // Coil: single ImageLoader for the app + screensaver, sharing OkHttp client (auth header + timeout) and caches
        SingletonImageLoader.setSafe { ctx ->
            ImageLoader.Builder(ctx)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { Immich.http })) }
                .memoryCache { MemoryCache.Builder().maxSizePercent(ctx, 0.25).build() }
                .diskCache { DiskCache.Builder().directory(File(ctx.cacheDir, "coil").toOkioPath()).maxSizeBytes(512L * 1024 * 1024).build() }
                .bitmapConfig(Bitmap.Config.RGB_565)
                .allowHardware(true)
                .build()
        }
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        Log.i("Immich", "app started: memClass=${am.memoryClass}MB, large=${am.largeMemoryClass}MB, lowRam=${am.isLowRamDevice}")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) releaseMemory("trim:$level")
        else if (level >= TRIM_MEMORY_RUNNING_LOW) {
            SingletonImageLoader.get(this).memoryCache?.trimToSize(16L * 1024 * 1024)
            ThumbHash.clear()
        }
    }

    /** Release caches when hidden or before starting the screensaver. */
    fun releaseMemory(reason: String) {
        Log.i("Immich", "releaseMemory ($reason)")
        SingletonImageLoader.get(this).memoryCache?.clear()
        ThumbHash.clear()
    }
}

/** Screens pushed onto the navigation stack. */
sealed class Screen {
    class Timeline(
        val title: String,
        val sub: String?,
        val filter: Filter,
        val withMemories: Boolean,
        startNewest: Boolean,
        scope: CoroutineScope,
    ) : Screen() {
        val state = TimelineState(filter, startNewest, scope)
    }
    class Albums(scope: CoroutineScope) : Screen() { val state = AlbumsState(scope) }
    class Explore(scope: CoroutineScope) : Screen() { val state = ExploreState(scope) }
    class Search(scope: CoroutineScope) : Screen() { val state = SearchState(scope) }
    class Memories(scope: CoroutineScope) : Screen() { val state = MemoriesState(scope) }
    class Map(scope: CoroutineScope) : Screen() { val state = MapState(scope) }
    class MapPhotos(val title: String, ids: List<String>, scope: CoroutineScope) : Screen() {
        val state = MapPhotosState(ids, scope)
    }
    class People(val people: List<Person>) : Screen() { val state = PeopleState(people) }
    class Folders(tree: FolderTree, node: FolderNode?, scope: CoroutineScope) : Screen() {
        val state = FolderState(tree, node, scope)
    }
    data object SettingsScreen : Screen()
    class AssetList(
        val title: String,
        val sub: String?,
        val byDay: Boolean = true,
        scope: CoroutineScope,
        load: suspend (Int) -> Page,
    ) : Screen() {
        val res = SearchResults(scope).apply { start(load) }
    }
}

class Nav(
    val scope: CoroutineScope,
    val open: (Screen) -> Unit,
    val view: (ImageSource, Int, String?, Boolean, GridState?, (() -> Unit)?) -> Unit,
) {
    fun view(source: ImageSource, start: Int, title: String?, slideshow: Boolean, grid: GridState?) =
        view(source, start, title, slideshow, grid, null)
}

/** Key behavior overrides coordinated between screens and [MainActivity.dispatchKeyEvent]. */
object Keys {
    /** Long Back press starts the screensaver directly. */
    var longBack = false
    var toScreensaver = false
    /** Map screen handles key repeats on OK itself (long OK = photo grid). */
    var okRepeat = false
    var okConsumed = false
}

enum class Section(@StringRes val label: Int, val icon: ImageVector) {
    PHOTOS(R.string.section_photos, Icons.Filled.Photo),
    MEMORIES(R.string.section_memories, Icons.Filled.AutoAwesome),
    SEARCH(R.string.section_search, Icons.Filled.Search),
    EXPLORE(R.string.section_explore, Icons.Filled.Explore),
    MAP(R.string.section_map, Icons.Filled.Map),
    ALBUMS(R.string.section_albums, Icons.Filled.PhotoLibrary),
    FOLDERS(R.string.section_folders, Icons.Filled.Folder),
    FAVORITES(R.string.section_favorites, Icons.Filled.Favorite),
    SETTINGS(R.string.section_settings, Icons.Filled.Settings),
}

/** Active overlays so Back closes them before navigating. */
object Overlays {
    var openCount by mutableIntStateOf(0)
        private set

    @Composable
    fun Open() {
        DisposableEffect(Unit) { openCount++; onDispose { openCount-- } }
    }
}

class MainActivity : ComponentActivity() {
    private var intentState by mutableStateOf<Intent?>(null)
    private var backDownAt = 0L

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Language.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Immich.selectAccount(this, null)
        intentState = intent
        applyTestExtras(intent)
        setContent {
            PhotosTheme {
                PhotosApp(intent = intentState ?: intent, onExit = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentState = intent
        applyTestExtras(intent)
    }

    private fun applyTestExtras(intent: Intent) {
        if (intent.hasExtra("map_texture")) mapTexture = intent.getBooleanExtra("map_texture", false)
        if (intent.hasExtra("map_lod")) mapLod = intent.getIntExtra("map_lod", -1)
        if (intent.hasExtra("map_fps")) mapFps = intent.getIntExtra("map_fps", 0)
        if (intent.hasExtra("map_all_layers")) mapAllLayers = intent.getBooleanExtra("map_all_layers", false)
        if (intent.hasExtra("map_res")) mapRes = intent.getIntExtra("map_res", 0)
        if (intent.hasExtra("map_flight")) mapFlight = intent.getIntExtra("map_flight", -2)
        if (intent.hasExtra("map_cache")) mapCache = intent.getBooleanExtra("map_cache", false)
        if (intent.hasExtra("map_prefetch")) mapPrefetch = intent.getIntExtra("map_prefetch", -1)
        if (intent.hasExtra("video_without_placeholder")) videoWithoutPlaceholder = intent.getBooleanExtra("video_without_placeholder", false)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // Swallow remainder of a long-OK press once MapScreen handled it
        if (Keys.okConsumed && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            if (event.action == KeyEvent.ACTION_UP) Keys.okConsumed = false
            return true
        }
        // Long Back -> launch PreviewActivity (screensaver)
        if (code == KeyEvent.KEYCODE_BACK && Keys.longBack) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) { backDownAt = SystemClock.uptimeMillis(); Keys.toScreensaver = false }
                else if (!Keys.toScreensaver && SystemClock.uptimeMillis() - backDownAt >= 600) {
                    Keys.toScreensaver = true
                    startActivity(Intent(this, PreviewActivity::class.java))
                    return true
                }
            } else if (event.action == KeyEvent.ACTION_UP && Keys.toScreensaver) {
                Keys.toScreensaver = false
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
fun PhotosApp(intent: Intent, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var languageChosen by remember { mutableStateOf(Language.chosen) }
    var connected by remember { mutableStateOf(Immich.server.isNotBlank() && Immich.key.isNotBlank()) }
    var testPairing by remember { mutableStateOf(false) }

    // Language picker before anything else on first launch
    if (!languageChosen) {
        LanguageScreen(
            finish = onExit,
            chosen = {
                languageChosen = true
                (ctx as? MainActivity)?.recreate()
            },
        )
        return
    }

    // Pairing screen when not connected yet (or testing pairing)
    if (!connected || testPairing) {
        PairingScreen(
            test = testPairing,
            back = { testPairing = false },
            done = { connected = true; testPairing = false },
            finish = onExit,
        )
        return
    }

    // Main app state: cached sections + navigation stack + active viewer
    val stack = remember { mutableStateListOf<Screen>() }
    val sectionCache = remember { HashMap<Section, Screen>() }
    var activeSection by remember { mutableStateOf(Language.restartSection ?: Settings.start) }
    var viewer by remember { mutableStateOf<Pair<ViewerState, (() -> Unit)?>?>(null) }
    var returnGrid by remember { mutableStateOf<GridState?>(null) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    fun screenFor(s: Section): Screen = sectionCache.getOrPut(s) {
        when (s) {
            Section.PHOTOS -> Screen.Timeline(ctx.getString(R.string.section_photos), null, Filter(), true, Settings.newestFirst, scope)
            Section.MEMORIES -> Screen.Memories(scope)
            Section.SEARCH -> Screen.Search(scope)
            Section.EXPLORE -> Screen.Explore(scope)
            Section.MAP -> Screen.Map(scope)
            Section.ALBUMS -> Screen.Albums(scope)
            Section.FOLDERS -> Screen.Folders(FolderTree(scope), null, scope)
            Section.FAVORITES -> Screen.Timeline(ctx.getString(R.string.section_favorites), null, Filter(favorites = true), false, Settings.newestFirst, scope)
            Section.SETTINGS -> Screen.SettingsScreen
        }
    }

    // Initialize first screen
    LaunchedEffect(Unit) {
        if (stack.isEmpty()) {
            Language.restartSection = null
            stack.add(screenFor(activeSection))
        }
        Permissions.check()
        Permissions.notifyNew(ctx)
    }

    // Handle deep-link intent extras (e.g. from launcher shortcuts or test extras)
    LaunchedEffect(intent) {
        val sec = intent.getStringExtra("section")?.let { name -> Section.entries.firstOrNull { it.name.equals(name, true) } }
        if (sec != null) {
            activeSection = sec
            stack.clear()
            stack.add(screenFor(sec))
        }
    }

    // Apply timeline settings when returning from Settings
    LaunchedEffect(stack.lastOrNull()) {
        val cur = stack.lastOrNull()
        if (cur is Screen.Timeline) cur.state.applySettings()
    }

    val nav = remember {
        Nav(
            scope = scope,
            open = { s -> stack.add(s) },
            view = { source, start, title, slideshow, grid, onClose ->
                returnGrid = grid
                viewer = ViewerState(source, start, title, slideshow) to onClose
            },
        )
    }

    // Long Back launches screensaver when on a top-level screen without viewer
    val onTop = stack.size <= 1 && viewer == null
    DisposableEffect(onTop) { Keys.longBack = onTop; onDispose { Keys.longBack = false } }

    // Refresh permissions status when returning from system settings
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) scope.launch { Permissions.check() }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // Back handling: close overlays -> close viewer -> pop stack -> open drawer -> exit
    BackHandler(enabled = viewer != null || stack.size > 1 || drawerState.currentValue == DrawerValue.Closed) {
        when {
            viewer != null -> {
                val (vs, onClose) = viewer!!
                val last = vs.source.at(vs.index)
                viewer = null
                last?.let { a -> returnGrid?.focusTarget = a.id }
                returnGrid = null
                onClose?.invoke()
            }
            stack.size > 1 -> stack.removeAt(stack.lastIndex)
            drawerState.currentValue == DrawerValue.Closed -> scope.launch { drawerState.setValue(DrawerValue.Open) }
        }
    }

    // Drawer focus items (to focus language after restart)
    val drawerFocus = remember { Section.entries.associateWith { FocusRequester() } }
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Open) {
            delay(50)
            runCatching { drawerFocus[activeSection]?.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize().background(AppColors.background)) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = { value ->
                Column(
                    Modifier
                        .fillMaxHeight()
                        .background(if (value == DrawerValue.Open) AppColors.surface else AppColors.background)
                        .padding(12.dp)
                        .selectableGroup(),
                ) {
                    Section.entries.forEach { sec ->
                        if (sec == Section.SETTINGS) Spacer(Modifier.weight(1f))
                        val label = stringResource(sec.label)
                        NavigationDrawerItem(
                            selected = activeSection == sec && stack.size == 1,
                            onClick = {
                                activeSection = sec
                                stack.clear()
                                stack.add(screenFor(sec))
                                scope.launch { drawerState.setValue(DrawerValue.Closed) }
                            },
                            leadingContent = { Icon(sec.icon, null, Modifier.size(20.dp)) },
                            modifier = Modifier.focusRequester(drawerFocus[sec]!!),
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = AppColors.primary.copy(alpha = 0.22f),
                                selectedContentColor = AppColors.primary,
                                focusedContainerColor = AppColors.primary,
                                focusedContentColor = AppColors.onPrimary,
                            ),
                        ) {
                            Text(label, maxLines = 1, color = LocalContentColor.current)
                        }
                    }
                }
            },
        ) {
            Box(Modifier.fillMaxSize().padding(start = 80.dp)) {
                when (val cur = stack.lastOrNull()) {
                    is Screen.Timeline -> TimelineScreen(cur, nav)
                    is Screen.Albums -> AlbumsScreen(cur.state, nav)
                    is Screen.Explore -> ExploreScreen(cur.state, nav)
                    is Screen.Search -> SearchScreen(cur.state, nav)
                    is Screen.Memories -> MemoriesScreen(cur.state, nav)
                    is Screen.Map -> MapScreen(cur.state, nav)
                    is Screen.MapPhotos -> MapPhotosScreen(cur, nav)
                    is Screen.People -> PeopleScreen(cur.state, nav)
                    is Screen.Folders -> FoldersScreen(cur, nav)
                    is Screen.SettingsScreen -> SettingsScreen(
                        reconnect = { testPairing = true },
                        disconnected = {
                            sectionCache.clear()
                            stack.clear()
                            connected = false
                        },
                    )
                    is Screen.AssetList -> AssetListScreen(cur, nav)
                    null -> {}
                }
            }
        }

        // Viewer overlay
        val activeViewer = viewer
        AnimatedVisibility(activeViewer != null, enter = fadeIn(), exit = fadeOut()) {
            if (activeViewer != null) {
                val (vs, onClose) = activeViewer
                Viewer(
                    state = vs,
                    onMap = { asset ->
                        viewer = null
                        returnGrid = null
                        onClose?.invoke()
                        activeSection = Section.MAP
                        val mapScreen = screenFor(Section.MAP) as Screen.Map
                        stack.clear()
                        stack.add(mapScreen)
                        mapScreen.state.show(asset)
                    },
                    close = { last ->
                        viewer = null
                        last?.let { a -> returnGrid?.focusTarget = a.id }
                        returnGrid = null
                        onClose?.invoke()
                    },
                )
            }
        }
    }
}
