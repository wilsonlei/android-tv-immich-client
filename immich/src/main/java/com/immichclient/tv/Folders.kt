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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
    val grid = GridState()
    /** Subfolder index that should receive focus when visible; -1 = none. */
    var folderFocus by mutableIntStateOf(0)
    var lastFolderFocus = 0
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
        nav.view(ListSource(photos), i.coerceIn(0, photos.lastIndex), title, slideshow, state.grid)
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key.nativeKeyCode) {
                // ⏯ = slideshow of this folder, starting from the selected photo (or the first)
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    val idx = state.grid.lastFocus?.let { photos.indexOf(it) }?.takeIf { it >= 0 } ?: 0
                    view(idx, true)
                    photos.isNotEmpty()
                }
                else -> false
            }
        },
    ) {
        val width = widthFor(maxWidth.value)
        val rowHeight = Settings.rowSize.height
        val rows = remember(photos, width, rowHeight) { rowsFor(photos, width, byDay = true, height = rowHeight) }
        val folderRows = remember(folders) { folders.chunked(COLUMNS) }
        val showLoading = state.photos == null && k.hasPhotos
        val showEmpty = state.photos != null && folders.isEmpty() && photos.isEmpty()
        val headerCount = 1 + folderRows.size + (if (showLoading || showEmpty) 1 else 0)

        PhotoGrid(
            rows = rows,
            state = state.grid,
            headerCount = headerCount,
            onClick = { a -> view(photos.indexOf(a), false) },
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header", contentType = "header") {
                val parts = listOfNotNull(
                    folders.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.folders_count, it, it) },
                    photos.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.items, it, it) },
                )
                val path = tree.start?.let { k.relativeTo(it) }.orEmpty()
                ScreenHeader(title, listOfNotNull(path.takeIf { it.isNotEmpty() && it != title }, parts.joinToString("  ·  ").ifEmpty { null }).joinToString("   —   ").ifEmpty { null }) {
                    if (folders.size > 1) ChoiceChip(
                        selected = false, onClick = { tree.reversed = !tree.reversed; state.folderFocus = 0 },
                        text = if (tree.reversed) "Z–A" else "A–Z",
                    )
                    if (photos.isNotEmpty()) ChoiceChip(
                        selected = false, onClick = { view(0, true) },
                        leadingIcon = { Icon(Icons.Filled.PlayArrow, null, Modifier.width(18.dp)) },
                        text = stringResource(R.string.slideshow),
                    )
                }
            }
            items(folderRows.size, key = { "fr:" + folderRows[it].first().path }, contentType = { "folderRow" }) { rIdx ->
                val row = folderRows[rIdx]
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEachIndexed { cIdx, m ->
                        val idx = rIdx * COLUMNS + cIdx
                        FolderTile(
                            m,
                            modifier = Modifier.weight(1f),
                            focusTarget = state.folderFocus == idx,
                            onFocusTargetDone = { state.folderFocus = -1 },
                            onFocus = { state.lastFolderFocus = idx },
                        ) {
                            nav.open(Screen.Folders(state.tree, m, nav.scope))
                        }
                    }
                    repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (showLoading) item(key = "loading") {
                Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted, modifier = Modifier.padding(top = 24.dp))
            }
            if (showEmpty) item(key = "empty") {
                StatusMessage(stringResource(if (state.error != null) R.string.folders_load_failed else R.string.folder_empty), state.error, Icons.Filled.Folder)
            }
        }
        if (photos.isNotEmpty()) {
            ListScrollbar(state.grid, rows, photos.size, false, Modifier.align(Alignment.CenterEnd), topCount = headerCount)
        }
    }
    // Returning from a subfolder or first load: focus the last selected subfolder, or first photo if no subfolders
    LaunchedEffect(folders.isEmpty(), photos.isNotEmpty()) {
        delay(30)
        if (state.grid.returnTo != null || state.grid.focusTarget != null) return@LaunchedEffect
        if (folders.isNotEmpty()) {
            if (state.folderFocus < 0) state.folderFocus = state.lastFolderFocus.coerceIn(0, folders.lastIndex)
        } else if (photos.isNotEmpty() && state.grid.lastFocus == null) {
            state.grid.focusTarget = photos.first().id
        }
    }
}

/** Folder: large folder icon with name underneath (focus: slightly larger, white border, accent glow). */
@Composable
private fun FolderTile(m: FolderNode, modifier: Modifier = Modifier, focusTarget: Boolean, onFocusTargetDone: () -> Unit, onFocus: () -> Unit, onClick: () -> Unit) {
    val fr = remember { FocusRequester() }
    if (focusTarget) LaunchedEffect(m.path) {
        repeat(5) { if (runCatching { fr.requestFocus() }.isSuccess) { onFocusTargetDone(); return@LaunchedEffect }; delay(16) }
        onFocusTargetDone()
    }
    var focus by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focus) 1.06f else 1f, tween(140), label = "scale")
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.aspectRatio(1f)
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
