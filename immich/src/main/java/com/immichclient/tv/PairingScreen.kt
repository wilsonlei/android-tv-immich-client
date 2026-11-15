package com.immichclient.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/**
 * First start (or "Reconnect"): QR code to the phone page of Pairing.kt, with instructions and progress on the left.
 * [back] is null when there is no account yet (Back then closes the app); [done] is called after pairing succeeds.
 */
@Composable
fun PairingScreen(test: Boolean, back: (() -> Unit)?, done: () -> Unit, finish: () -> Unit) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        Pairing.start(ctx, test)
        onDispose { Pairing.stop() }
    }
    BackHandler { back?.invoke() ?: finish() }
    val status = Pairing.status
    val url = Pairing.url
    if (status is Pairing.Status.Done) LaunchedEffect(status) { delay(2800); done() }

    // The screen is 960x540 dp: left instructions + progress, right QR (320 dp); everything must fit without scrolling.
    Row(Modifier.fillMaxSize().background(AppColors.background).padding(horizontal = 64.dp, vertical = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 40.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(AppColors.surface, CircleShape).padding(12.dp)) { Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(26.dp), tint = AppColors.primary) }
                Column(Modifier.padding(start = 16.dp)) {
                    Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.displaySmall.copy(fontSize = 28.sp), maxLines = 1)
                    Text(stringResource(if (test) R.string.pair_test else R.string.pair_one_time), style = MaterialTheme.typography.bodyMedium, color = AppColors.muted, maxLines = 2)
                    Text(stringResource(R.string.unofficial_short), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
            Spacer(Modifier.height(26.dp))
            StepRow(1, stringResource(R.string.step1), stringResource(R.string.step1_sub))
            StepRow(2, stringResource(R.string.step2), stringResource(R.string.step2_sub))
            StepRow(3, stringResource(R.string.step3), stringResource(R.string.step3_sub))
            Spacer(Modifier.height(10.dp))
            // progress / result
            AnimatedContent(status::class, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "status") { _ ->
                when (val s = Pairing.status) {
                    is Pairing.Status.Waiting -> Row(verticalAlignment = Alignment.CenterVertically) {
                        PulseDot(); Text(stringResource(R.string.waiting_for_phone), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted, modifier = Modifier.padding(start = 12.dp))
                    }
                    is Pairing.Status.Busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                        SpinnerRing(); Text(s.step.toString(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp), maxLines = 2)
                    }
                    is Pairing.Status.Done -> Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.CheckCircle, null, Modifier.size(28.dp), tint = Color(0xFF86EFAC))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(stringResource(R.string.pair_done_title), style = MaterialTheme.typography.titleLarge, color = Color(0xFF86EFAC))
                            Text(stringResource(R.string.connected_as, s.name, s.server), style = MaterialTheme.typography.bodyMedium, color = AppColors.muted)
                            s.warning?.let { Text(it.toString(), style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFCD34D), modifier = Modifier.padding(top = 6.dp)) }
                        }
                    }
                    is Pairing.Status.Error -> Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.ErrorOutline, null, Modifier.size(28.dp), tint = Color(0xFFFCA5A5))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(stringResource(R.string.pair_failed), style = MaterialTheme.typography.titleLarge, color = Color(0xFFFCA5A5))
                            Text(s.message.toString(), style = MaterialTheme.typography.bodyMedium, color = AppColors.text)
                            if (url != null) Text(stringResource(R.string.try_again_phone), style = MaterialTheme.typography.bodyMedium, color = AppColors.muted, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
        // QR on the right
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(320.dp).background(Color.White, RoundedCornerShape(20.dp)).padding(20.dp), contentAlignment = Alignment.Center) {
                when {
                    status is Pairing.Status.Done -> Icon(Icons.Filled.CheckCircle, null, Modifier.size(140.dp), tint = Color(0xFF16A34A))
                    url != null -> {
                        val qr = remember(url) { Pairing.qr(url).asImageBitmap() }
                        Image(qr, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
                    }
                    else -> Text(stringResource(R.string.no_network), style = MaterialTheme.typography.titleLarge, color = Color(0xFF52525B))
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.or_type_address), style = MaterialTheme.typography.bodySmall)
            Text(url ?: "–", style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 17.sp, letterSpacing = 0.5.sp), color = AppColors.primary, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun StepRow(n: Int, title: String, sub: String) {
    Row(Modifier.padding(bottom = 14.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(30.dp).background(AppColors.primary, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelLarge, color = AppColors.onPrimary)
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = AppColors.muted)
        }
    }
}

/** Softly pulsing dot: nothing happening yet. */
@Composable
private fun PulseDot() {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(12.dp).background(AppColors.primary.copy(alpha = a), CircleShape)) }
}

/** Spinning ring while pairing. */
@Composable
private fun SpinnerRing() {
    val t = rememberInfiniteTransition(label = "ring")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "angle")
    Canvas(Modifier.size(28.dp).padding(3.dp)) {
        val strokeWidth = 3.dp.toPx()
        drawCircle(Color(0x33FFFFFF), style = Stroke(strokeWidth))
        drawArc(AppColors.primary, angle, 100f, false, style = Stroke(strokeWidth, cap = StrokeCap.Round))
    }
}
