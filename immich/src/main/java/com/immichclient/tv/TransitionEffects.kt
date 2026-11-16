package com.immichclient.tv

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** Ken Burns pan/zoom in the graphics layer phase so animations don't trigger per-frame recompositions. */
fun Modifier.kenBurnsLayer(enabled: Boolean, mode: Int, progress: () -> Float): Modifier = graphicsLayer {
    if (!enabled) return@graphicsLayer
    val p = progress()
    val scaleVal = when (mode) {
        0, 1 -> 1.05f + 0.12f * p     // Zoom In (1.05x -> 1.17x)
        else -> 1.17f - 0.12f * p     // Zoom Out (1.17x -> 1.05x)
    }
    val panXVal = when (mode) {
        0 -> -0.025f + 0.05f * p      // Pan Left to Right
        1 -> 0.025f - 0.05f * p       // Pan Right to Left
        2 -> -0.02f + 0.04f * p       // Pan Left to Right
        else -> 0.02f - 0.04f * p      // Pan Right to Left
    }
    val panYVal = when (mode) {
        0 -> -0.015f + 0.03f * p      // Pan Top to Bottom
        1 -> 0.015f - 0.03f * p       // Pan Bottom to Top
        2 -> 0.015f - 0.03f * p       // Pan Bottom to Top
        else -> -0.015f + 0.03f * p   // Pan Top to Bottom
    }
    scaleX = scaleVal
    scaleY = scaleVal
    translationX = panXVal * size.width
    translationY = panYVal * size.height
}

/** Crossfade transition transform for slideshow and screensaver (fades incoming in, then fades outgoing out). */
fun slideshowFadeTransform(durationMs: Int = 1000): ContentTransform =
    fadeIn(tween(durationMs)) togetherWith fadeOut(tween(durationMs, delayMillis = durationMs / 2))

/** Horizontal slide transition transform for photo/video navigation and slideshow slide mode. */
fun slideshowSlideTransform(direction: Int, durationMs: Int = 250): ContentTransform {
    val dir = direction.coerceIn(-1, 1)
    return slideInHorizontally(tween(durationMs)) { it * dir } togetherWith
        slideOutHorizontally(tween(durationMs)) { -it * dir }
}
