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
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.random.Random

/** Endpoints for a single Ken Burns pan/zoom animation. */
data class KenBurnsPose(val scale: Float, val panX: Float, val panY: Float)

data class KenBurnsMotion(val start: KenBurnsPose, val end: KenBurnsPose)

/** Chains Ken Burns poses so each photo continues from the previous photo's ending X/Y pan with random scale and target pan. */
class KenBurnsTracker {
    private var current = KenBurnsPose(scale = 1.05f, panX = -0.025f, panY = -0.015f)

    fun next(): KenBurnsMotion {
        val startScale = 1.05f + Random.nextFloat() * 0.15f
        var endScale = 1.05f + Random.nextFloat() * 0.15f
        if (abs(endScale - startScale) < 0.06f) {
            endScale = if (startScale < 1.125f) startScale + 0.07f else startScale - 0.07f
        }
        val nextX = (Random.nextFloat() * 2f - 1f) * 0.025f
        val nextY = (Random.nextFloat() * 2f - 1f) * 0.018f
        val start = current.copy(scale = startScale)
        val end = KenBurnsPose(scale = endScale, panX = nextX, panY = nextY)
        current = end
        return KenBurnsMotion(start = start, end = end)
    }
}

/** Ken Burns pan/zoom in the graphics layer phase so animations don't trigger per-frame recompositions. */
fun Modifier.kenBurnsLayer(enabled: Boolean, motion: KenBurnsMotion, progress: () -> Float): Modifier = graphicsLayer {
    if (!enabled) return@graphicsLayer
    val p = progress()
    val scaleVal = lerp(motion.start.scale, motion.end.scale, p)
    val panXVal = lerp(motion.start.panX, motion.end.panX, p)
    val panYVal = lerp(motion.start.panY, motion.end.panY, p)
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
