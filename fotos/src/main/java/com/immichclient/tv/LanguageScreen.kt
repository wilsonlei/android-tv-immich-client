package com.immichclient.tv

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * First launch: choose language using large tiles (name in its own language), preselected to the system language if
 * supported, otherwise English. The title and explanation follow the focused tile so everyone sees what is asked.
 * OK = choose -> [chosen] (the activity is recreated in that language).
 */
@Composable
fun LanguageScreen(finish: () -> Unit, chosen: () -> Unit) {
    val ctx = LocalContext.current
    val defaultLang = remember { Language.systemLanguage(ctx) }
    var focusedLang by remember { mutableStateOf(defaultLang) }
    val first = remember { FocusRequester() }
    BackHandler { finish() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    // texts in the language of the focused tile (the app itself is still in the provisional language)
    val res = remember(focusedLang) { Language.resources(focusedLang) }

    Column(Modifier.fillMaxSize().background(AppColors.background).padding(horizontal = 64.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.background(AppColors.surface, CircleShape).padding(14.dp)) { Icon(Icons.Filled.Language, null, Modifier.size(30.dp), tint = AppColors.primary) }
        Spacer(Modifier.height(18.dp))
        Text(res.getString(R.string.language_title), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center, maxLines = 1)
        Text(res.getString(R.string.language_sub), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for (code in Language.LANGUAGES) {
                val selected = code == defaultLang
                Surface(
                    onClick = { Language.choose(ctx, code); chosen() },
                    modifier = Modifier.width(150.dp).height(110.dp).then(if (selected) Modifier.focusRequester(first) else Modifier)
                        .onFocusChanged { if (it.isFocused) focusedLang = code },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (selected) AppColors.primary.copy(alpha = 0.22f) else AppColors.surface, contentColor = AppColors.text,
                        focusedContainerColor = AppColors.primary, focusedContentColor = AppColors.onPrimary,
                    ),
                    border = ClickableSurfaceDefaults.border(border = if (selected) Border(androidx.compose.foundation.BorderStroke(2.dp, AppColors.primary), shape = RoundedCornerShape(16.dp)) else Border.None),
                ) {
                    // text styles have their own color (Theme.kt) which overrides the Surface's content color: set explicitly
                    val color = if (focusedLang == code) AppColors.onPrimary else AppColors.text
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(code.uppercase(), style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, letterSpacing = 2.sp), color = color.copy(alpha = 0.7f))
                        Text(Language.name(code), style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp), maxLines = 1, color = color)
                    }
                }
            }
        }
    }
}
