package com.immichclient.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Permission check for the API key: which of [Pairing.PERMISSIONS] does it have (`/api-keys/me`, a key can always read
 * its own permissions; "all" = everything)? If that fails (older server), probe the corresponding endpoint per permission
 * (403 = missing). On a newly missing permission after startup, show the overview ([PermissionsScreen]) once;
 * afterwards only via Settings -> Account -> Permissions or from a screen missing the permission (Folders).
 */
object Permissions {
    /** What each permission is needed for; [core] = the app cannot work without this. */
    class Permission(val name: String, @StringRes val desc: Int, val core: Boolean = false)

    val ALL = listOf(
        Permission("asset.read", R.string.perm_asset_read, core = true),
        Permission("asset.view", R.string.perm_asset_view, core = true),
        Permission("user.read", R.string.perm_user_read),
        Permission("album.read", R.string.perm_album_read),
        Permission("memory.read", R.string.perm_memory_read),
        Permission("person.read", R.string.perm_person_read),
        Permission("map.read", R.string.perm_map_read),
        Permission("folder.read", R.string.perm_folder_read),
    )

    /** null = not yet checked (or indeterminable); otherwise the missing permissions */
    var missing by mutableStateOf<List<String>?>(null)
        private set
    /** permissions that could not be determined (no /api-keys/me and no conclusive response) */
    var unknown by mutableStateOf<Set<String>>(emptySet())
        private set
    var keyName by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    /** User chose Reconnect in PermissionsActivity (MainActivity opens the pairing screen). */
    var reconnectRequested by mutableStateOf(false)

    fun show(context: Context) {
        val intent = Intent(context, PermissionsActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    suspend fun check() {
        if (Immich.key.isBlank()) return
        busy = true
        try {
            val own = Immich.keyPermissions()
            if (own != null) {
                keyName = own.first
                val has = own.second
                missing = if ("all" in has) emptyList() else ALL.map { it.name }.filter { it !in has }
                unknown = emptySet()
            } else {
                keyName = null
                val result = ALL.associate { it.name to Immich.testPermission(it.name) }
                missing = result.filterValues { it == false }.keys.toList()
                unknown = result.filterValues { it == null }.keys
            }
            Log.i("Photos", "permissions: missing=$missing unknown=$unknown")
        } finally { busy = false }
    }

    fun isMissing(permission: String) = missing?.contains(permission) == true

    /** After startup: open overview if a permission is missing that hasn't been reported yet (per account). */
    fun notifyNew(c: Context) {
        val miss = missing ?: return
        val p = c.getSharedPreferences("instellingen", Context.MODE_PRIVATE)
        val key = "rechten_gemeld" + (Immich.viewer?.let { "_$it" } ?: "")
        val now = miss.sorted().joinToString(",")
        val prev = p.getString(key, "").orEmpty()
        if (now != prev) p.edit().putString(key, now).apply()
        if (miss.isNotEmpty() && now != prev) show(c)
    }
}

class PermissionsActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Language.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhotosTheme {
                PermissionsScreen(
                    reconnect = { Permissions.reconnectRequested = true; finish() },
                    close = { finish() },
                )
            }
        }
    }
}

/** Overview ("wizard"): each permission with check/cross and what it's for, plus Reconnect / Check again. */
@Composable
fun PermissionsScreen(reconnect: () -> Unit, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    BackHandler { close() }
    LaunchedEffect(Unit) { delay(30); runCatching { first.requestFocus() } }
    val miss = Permissions.missing
    val core = miss.orEmpty().any { m -> Permissions.ALL.any { it.name == m && it.core } }
    Box(
        Modifier.fillMaxSize().background(Color(0xF20A0A0A)).trapFocus()
            .onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && e.key.nativeKeyCode == KeyEvent.KEYCODE_MENU }
            .padding(horizontal = 56.dp, vertical = 36.dp),
    ) {
        Column(Modifier.widthIn(max = 900.dp)) {
            Text(stringResource(R.string.perms_title), style = MaterialTheme.typography.displaySmall)
            val sub = when {
                Permissions.busy || miss == null -> stringResource(R.string.perms_checking)
                miss.isEmpty() && Permissions.unknown.isEmpty() -> stringResource(R.string.perms_ok)
                miss.isEmpty() -> stringResource(R.string.perms_partly_unknown)
                core -> stringResource(R.string.perms_core_missing)
                else -> stringResource(R.string.perms_some_missing)
            }
            Text(listOfNotNull(Permissions.keyName?.let { "“$it”" }, sub).joinToString("  ·  "), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted)
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (r in Permissions.ALL) {
                    val ok = when { miss == null || r.name in Permissions.unknown -> null; r.name in miss -> false; else -> true }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when (ok) { true -> Icons.Filled.CheckCircle; false -> Icons.Filled.Error; null -> Icons.Filled.HelpOutline },
                            null, Modifier.size(24.dp),
                            tint = when (ok) { true -> Color(0xFF86EFAC); false -> Color(0xFFFCA5A5); null -> AppColors.muted },
                        )
                        Text(r.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 14.dp).width(150.dp))
                        Text(stringResource(r.desc), style = MaterialTheme.typography.bodyLarge, color = if (ok == false) Color.White else AppColors.muted)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            if (!miss.isNullOrEmpty()) {
                Text(stringResource(R.string.perms_fix_desc), style = MaterialTheme.typography.bodyMedium, color = AppColors.muted)
                Spacer(Modifier.height(14.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (!miss.isNullOrEmpty()) {
                    ActionButton(stringResource(R.string.reconnect), stringResource(R.string.perms_reconnect_sub), Modifier.width(260.dp).focusRequester(first)) { reconnect() }
                }
                ActionButton(stringResource(R.string.perms_recheck), null, Modifier.width(220.dp).then(if (miss.isNullOrEmpty()) Modifier.focusRequester(first) else Modifier)) {
                    scope.launch { Permissions.check() }
                }
                ActionButton(stringResource(R.string.perms_close), null, Modifier.width(160.dp)) { close() }
            }
        }
    }
}
