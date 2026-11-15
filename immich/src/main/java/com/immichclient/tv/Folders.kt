package com.immichclient.tv

import android.util.Log
import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Folders: browse photos as they are stored on disk (upload folder or external library), via Immich's folder view
 * (`/view/folder/unique-paths` + `/view/folder?path=`, permission folder.read). Each folder is its own screen on
 * the stack, so Back = one folder up.
 */
class FolderNode(var name: String, val path: String, var parent: FolderNode?) {
    val children = ArrayList<FolderNode>()
    /** Immich knows this folder has its own photos/videos */
    var hasPhotos = false

    /** path from the start of the tree, for the subtitle */
    fun relativeTo(start: FolderNode): String {
        val parts = ArrayList<String>()
        var k: FolderNode? = this
        while (k != null && k !== start) { parts += k.name; k = k.parent }
        return parts.asReversed().joinToString(" / ")
    }

    companion object {
        /** Build tree from path list; returns the first point where there is a choice (common prefix skipped). */
        fun build(paths: List<String>): FolderNode {
            val root = FolderNode("", "", null)
            val absolute = paths.any { it.startsWith("/") }
            for (p in paths) {
                var k = root
                var path = if (absolute) "" else null
                for (part in p.split('/').filter { it.isNotEmpty() }) {
                    path = if (path == null) part else "$path/$part"
                    k = k.children.firstOrNull { it.name == part } ?: FolderNode(part, path, k).also { k.children += it }
                }
                if (k !== root) k.hasPhotos = true
            }
            val collator = java.text.Collator.getInstance(Language.locale).apply { strength = java.text.Collator.SECONDARY }
            fun sort(k: FolderNode) { k.children.sortWith { a, b -> collator.compare(a.name, b.name) }; k.children.forEach(::sort) }
            sort(root)
            // "/data/upload/<id>/..." or "/mnt/media/photos/...": skip folders with no choice
            var start = root
            while (start.children.size == 1 && !start.hasPhotos) start = start.children[0]
            // Collapse single-child chains ("2024 / Camera" instead of a folder with just one subfolder). With multiple
            // libraries (e.g. /mnt/photos and /usr/src/app/upload/...) the top level shows one entry per library.
            fun collapse(k: FolderNode) {
                for (i in k.children.indices) {
                    var c = k.children[i]
                    while (c.children.size == 1 && !c.hasPhotos) {
                        val g = c.children[0]
                        g.name = c.name + " / " + g.name; g.parent = k
                        c = g
                    }
                    k.children[i] = c
                    collapse(c)
                }
            }
            collapse(start)
            // Immich's default upload folder (in the container) is simply shown as "upload / ..."
            for (c in start.children) for (pre in listOf("usr / src / app / ", "data / ")) if (c.name.startsWith(pre + "upload")) c.name = c.name.removePrefix(pre)
            return start
        }
    }
}

/** The folder tree, loaded once per session and shared across all folder screens. */
class FolderTree(scope: CoroutineScope) {
    var start by mutableStateOf<FolderNode?>(null)
    var error by mutableStateOf<String?>(null)
    /** folders Z-A (e.g. "26-10-17 ..." at the top for date-prefixed folders); applies to all folder screens */
    var reversed by mutableStateOf(false)

    init {
        scope.launch {
            runCatching { FolderNode.build(Immich.folders()) }
                .onSuccess { start = it }
                .onFailure { error = it.message ?: it.toString(); Log.w("Photos", "folders", it) }
        }
    }
}

class FolderState(val tree: FolderTree, val node: FolderNode?, scope: CoroutineScope) {
    var photos by mutableStateOf<List<Asset>?>(null)
    var error by mutableStateOf<String?>(null)
    val grid = LazyGridState()
    /** index (folders first, then photos) that should receive focus when visible; -1 = none */
    var focusTarget by mutableIntStateOf(0)
    var lastFocus = 0
    private var loaded: FolderNode? = null

    init { scope.launch { load() } }

    /** root screen: the tree arrives later; only then do we know which folder this is */
    val current: FolderNode? get() = node ?: tree.start

    suspend fun load() {
        val k = current ?: return
        if (loaded === k) return
        loaded = k
        if (!k.hasPhotos) { photos = emptyList(); return }
        runCatching { Immich.folderContents(k.path) }.onSuccess { photos = it }.onFailure { error = it.message; photos = emptyList(); Log.w("Photos", "folder ${k.path}", it) }
    }
}

private const val COLUMNS = 6

@Composable
fun FoldersScreen(screen: Screen.Folders, nav: Nav) {
    val state = screen.state
    val tree = state.tree
    val k = state.current
    LaunchedEffect(k) { state.load() }

    if (k == null) {
        val f = tree.error
        if (f == null) StatusMessage(stringResource(R.string.loading), null, Icons.Filled.Folder)
        else if (f.contains("folder.read")) Box(Modifier.fillMaxSize()) {
            StatusMessage(stringResource(R.string.folders_no_access), stringResource(R.string.folders_no_access_desc), Icons.Filled.Folder)
            // button below the message (which is centered)
            val fr = remember { FocusRequester() }
            val ctx = androidx.compose.ui.platform.LocalContext.current
            ActionButton(stringResource(R.string.perms_title), stringResource(R.string.perms_view_and_fix), Modifier.align(Alignment.Center).offset(y = 140.dp).width(320.dp).focusRequester(fr)) { Permissions.show(ctx) }
            LaunchedEffect(Unit) { delay(50); runCatching { fr.requestFocus() } }
        }
        else StatusMessage(stringResource(R.string.folders_load_failed), f, Icons.Filled.Folder)
        return
    }
    val folders = remember(k, tree.reversed) { if (tree.reversed) k.children.asReversed().toList() else k.children.toList() }
    val photos = state.photos.orEmpty()
    val title = if (k === tree.start) stringResource(R.string.section_folders) else k.name
    fun view(i: Int, slideshow: Boolean) {
        if (photos.isEmpty()) return
        nav.view(ListSource(photos), i.coerceIn(0, photos.lastIndex), title, slideshow, null) { state.focusTarget = state.lastFocus }
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            state = state.grid,
            contentPadding = PaddingValues(start = 24.dp, end = 40.dp, top = 20.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key.nativeKeyCode) {
                    // ⏯ = slideshow of this folder, starting from the selected photo (or the first)
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> { view(state.lastFocus - folders.size, true); photos.isNotEmpty() }
                    else -> false
                }
            },
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                val parts = listOfNotNull(
                    folders.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.folders_count, it, it) },
                    photos.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.items, it, it) },
                )
                val path = tree.start?.let { k.relativeTo(it) }.orEmpty()
                ScreenHeader(title, listOfNotNull(path.takeIf { it.isNotEmpty() && it != title }, parts.joinToString("  ·  ").ifEmpty { null }).joinToString("   —   ").ifEmpty { null }) {
                    if (folders.size > 1) ChoiceChip(
                        selected = false, onClick = { tree.reversed = !tree.reversed; state.focusTarget = 0 },
                        text = if (tree.reversed) "Z–A" else "A–Z",
                    )
                    if (photos.isNotEmpty()) ChoiceChip(
                        selected = false, onClick = { view(0, true) },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, null, Modifier.width(18.dp)) },
                        text = stringResource(R.string.slideshow),
                    )
                }
            }
            items(folders.size, key = { "m:" + folders[it].path }) { i ->
                val m = folders[i]
                FolderTile(m, focusTarget = state.focusTarget == i, onFocusTargetDone = { state.focusTarget = -1 }, onFocus = { state.lastFocus = i }) {
                    nav.open(Screen.Folders(state.tree, m, nav.scope))
                }
            }
            items(photos.size, key = { "f:" + photos[it].id }) { i ->
                val idx = folders.size + i
                IdTile(
                    photos[i].id, focusTarget = state.focusTarget == idx, onFocusTargetDone = { state.focusTarget = -1 },
                    onFocus = { state.lastFocus = idx },
                ) { view(i, false) }
            }
            if (state.photos == null && k.hasPhotos) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted, modifier = Modifier.padding(top = 24.dp))
            }
            if (state.photos != null && folders.isEmpty() && photos.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                StatusMessage(stringResource(if (state.error != null) R.string.folders_load_failed else R.string.folder_empty), state.error, Icons.Filled.Folder)
            }
        }
        val n = folders.size + photos.size
        if (n > 0) Scrollbar(
            fraction = { fractionOf(state.grid, 1) },
            movement = { state.grid.firstVisibleItemIndex * 7919 + state.grid.firstVisibleItemScrollOffset / 200 },
            scrollable = { state.grid.canScrollForward || state.grid.canScrollBackward },
            label = { folders.getOrNull((state.grid.firstVisibleItemIndex - 1).coerceAtLeast(0))?.name },
            sub = { "${Language.number((state.grid.firstVisibleItemIndex - 1).coerceIn(0, n - 1) + 1)} / ${Language.number(n)}" },
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
    // back on this screen (or first time): focus on the last selected tile
    LaunchedEffect(Unit) { delay(30); if (state.focusTarget < 0) state.focusTarget = state.lastFocus }
}

/** Folder: large folder icon with name underneath (focus: slightly larger, white border, accent glow). */
@Composable
private fun FolderTile(m: FolderNode, focusTarget: Boolean, onFocusTargetDone: () -> Unit, onFocus: () -> Unit, onClick: () -> Unit) {
    val fr = remember { FocusRequester() }
    if (focusTarget) LaunchedEffect(m.path) {
        repeat(5) { if (runCatching { fr.requestFocus() }.isSuccess) { onFocusTargetDone(); return@LaunchedEffect }; delay(16) }
        onFocusTargetDone()
    }
    var focus by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focus) 1.06f else 1f, tween(140), label = "scale")
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.aspectRatio(1f)
            .graphicsLayer {
                scaleX = scale; scaleY = scale; this.shape = shape; clip = true
                shadowElevation = if (focus) 16.dp.toPx() else 0f
                spotShadowColor = AppColors.primary; ambientShadowColor = AppColors.primary
            }
            .background(if (focus) AppColors.primary else AppColors.surface)
            .then(if (focus) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .focusRequester(fr)
            .onFocusChanged { focus = it.isFocused; if (it.isFocused) onFocus() }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Folder, null, Modifier.size(40.dp), tint = if (focus) AppColors.onPrimary else AppColors.primary)
        Text(
            m.name, style = MaterialTheme.typography.titleSmall, color = if (focus) AppColors.onPrimary else AppColors.text,
            maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}
