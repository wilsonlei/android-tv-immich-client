package com.immichclient.tv

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Right-side scrollbar: a thin track with a thumb at the position within the ENTIRE list, a label next to it (month,
 * or "1,234 / 47,153"), and optional year markers along the track. Appears on scroll and fades out 1.5 s later.
 *
 * Everything is drawn in a single Canvas and the scroll position is only read in the draw phase ([fraction], [label],
 * [sub] inside the draw lambda): per scroll frame only this layer is redrawn, without recomposition or layout.
 * An earlier version with regular Text/offset composables recomposed during the scroll animation and caused 12-21%
 * janky frames instead of 1-5%; with Canvas and throttled text layout the difference from "no bar" is within
 * measurement noise (test: `--ez geen_balk true`).
 *
 * Interactive ([steps], 2.5): ▶ at the right edge focuses the bar (wider, with accent border and a hint);
 * ▲▼ moves the selection by one step (timeline: by month), holding accelerates to big steps (by year); the label
 * shows live where you're jumping to. OK or ◀ jumps there and focuses the first photo; ◀ without changing anything
 * and Back restore focus where it was.
 */
class BarSteps(
    val count: Int,
    /** position 0..1 of step i on the track */
    val fraction: (Int) -> Float,
    val label: (Int) -> String?,
    val sub: (Int) -> String?,
    /** big jump (when holding) from step i in direction d (-1/+1), e.g. to previous/next year */
    val bigStep: (Int, Int) -> Int,
    /** step currently visible (at the top) */
    val current: () -> Int,
    /** jump to step i and focus the first photo there */
    val go: (Int) -> Unit,
    /** nothing chosen: restore focus where it was */
    val back: () -> Unit,
    /** steps are months (hint "▲▼ month · hold: year"), otherwise a general hint */
    val byMonth: Boolean = false,
)

/** Big jump: every [n] steps (lists without years). */
fun bigStepBy(n: Int, count: Int): (Int, Int) -> Int = { i, r -> (i + r * n).coerceIn(0, (count - 1).coerceAtLeast(0)) }

@Composable
fun Scrollbar(
    /** 0..1: thumb position; read during drawing */
    fraction: () -> Float,
    /** snapshot value that changes on each scroll step (controls when the bar is shown) */
    movement: () -> Any?,
    /** is there anything to scroll at all? */
    scrollable: () -> Boolean,
    label: () -> String?,
    sub: () -> String?,
    /** years (or other markers) along the track: position 0..1 -> text */
    markers: List<Pair<Float, String>> = emptyList(),
    modifier: Modifier = Modifier,
    /** remote control interaction (focus on the bar); null = indicator only */
    steps: BarSteps? = null,
) {
    if (scrollbarDisabled) return
    var visible by remember { mutableStateOf(false) }
    // interaction: does the bar have focus, and which step is selected (only read in the draw phase)
    var focus by remember { mutableStateOf(false) }
    val choice = remember { mutableIntStateOf(0) }
    var start by remember { mutableIntStateOf(0) }
    // one extra draw pass after each scroll step (170 ms later): text is laid out at most every 150 ms and would
    // otherwise lag behind by a few numbers after the last step
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        snapshotFlow { movement() }.distinctUntilChanged().collectLatest {
            if (!scrollable()) { visible = false; return@collectLatest }
            visible = true; delay(170); tick++; delay(1330); visible = false
        }
    }
    // text layout of the label is the expensive part (1-3 ms per measure with variable font): at most ~6x per second
    val cache = remember { LabelCache() }
    // alpha only read in the graphicsLayer (no recomposition per animation frame)
    val alpha = animateFloatAsState(if (visible || focus) 1f else 0f, tween(if (visible || focus) 160 else 450), label = "scrollbar")
    val tm = rememberTextMeasurer(cacheSize = 16)
    val labelStyle = MaterialTheme.typography.labelLarge.copy(color = Color.White)
    val subStyle = MaterialTheme.typography.labelSmall.copy(color = Color(0xB3FFFFFF))
    val yearStyle = MaterialTheme.typography.labelSmall.copy(color = Color(0x99FFFFFF))
    val years = remember(markers, yearStyle) { markers.map { (f, t) -> f to tm.measure(t, yearStyle) } }
    val accent = AppColors.primary
    val hintText = androidx.compose.ui.res.stringResource(if (steps?.byMonth == true) R.string.scrollbar_hint else R.string.scrollbar_hint_general)
    val hint = remember(hintText, subStyle) { tm.measure(hintText, subStyle) }

    androidx.compose.foundation.layout.Box(modifier.fillMaxHeight().width(300.dp)) {
        Canvas(Modifier.matchParentSize().graphicsLayer { this.alpha = alpha.value; compositingStrategy = CompositingStrategy.ModulateAlpha }) {
            if (alpha.value <= 0f) return@Canvas
            val w = size.width
            val margin = 28.dp.toPx()
            val controlled = focus && steps != null
            val thumbH = (if (controlled) 40 else 30).dp.toPx()
            val travel = (size.height - 2 * margin - thumbH).coerceAtLeast(0f)
            // track (wider and brighter when focused)
            val trackW = (if (controlled) 5 else 3).dp.toPx()
            drawRoundRect(if (controlled) Color(0x66FFFFFF) else Color(0x33FFFFFF), Offset(w - 11.5.dp.toPx() - trackW / 2, margin), Size(trackW, size.height - 2 * margin), CornerRadius(trackW / 2))
            // years: left of the track, only if they don't overlap (at least 20 dp apart)
            val minGap = 20.dp.toPx()
            var lastY = -1e9f
            for ((f, lay) in years) {
                val y = f * travel
                if (y - lastY < minGap) continue
                lastY = y
                drawText(lay, topLeft = Offset(w - 22.dp.toPx() - lay.size.width, margin + y + thumbH / 2 - lay.size.height / 2))
            }
            // thumb (when controlled: at the chosen step, larger, with a glow)
            val st = steps
            val k = choice.intValue
            val f = if (controlled && st != null && st.count > 0) st.fraction(k.coerceIn(0, st.count - 1)) else fraction()
            val thumbY = margin + f.coerceIn(0f, 1f) * travel
            val thumbW = (if (controlled) 11 else 7).dp.toPx()
            if (controlled) drawRoundRect(accent.copy(alpha = 0.35f), Offset(w - 11.5.dp.toPx() - thumbW / 2 - 4.dp.toPx(), thumbY - 4.dp.toPx()), Size(thumbW + 8.dp.toPx(), thumbH + 8.dp.toPx()), CornerRadius(9.dp.toPx()))
            drawRoundRect(accent, Offset(w - 11.5.dp.toPx() - thumbW / 2, thumbY), Size(thumbW, thumbH), CornerRadius(thumbW / 2))
            // label next to the thumb (dark surface so it remains readable over photos)
            @Suppress("UNUSED_EXPRESSION") tick
            val now = android.os.SystemClock.uptimeMillis()
            if (controlled || now - cache.time >= 150) {
                cache.time = now
                run {
                    val lt = if (controlled && st != null) st.label(k) else label()
                    if (lt != cache.labelText) { cache.labelText = lt; cache.label = lt?.let { tm.measure(it, labelStyle) } }
                    val sbt = if (controlled && st != null) st.sub(k) else sub()
                    if (sbt != cache.subText) { cache.subText = sbt; cache.sub = sbt?.let { tm.measure(it, subStyle) } }
                }
            }
            val l = cache.label
            val s = cache.sub
            val hl = if (controlled) hint else null
            if (l == null && s == null) return@Canvas
            val textW = maxOf(l?.size?.width ?: 0, s?.size?.width ?: 0, hl?.size?.width ?: 0).toFloat()
            val textH = ((l?.size?.height ?: 0) + (s?.size?.height ?: 0) + (hl?.size?.height?.plus(4.dp.roundToPx()) ?: 0)).toFloat()
            val px = 14.dp.toPx(); val py = 6.dp.toPx()
            val boxW = textW + 2 * px; val boxH = textH + 2 * py
            val right = w - 26.dp.toPx()
            val top = (thumbY + thumbH / 2 - boxH / 2).coerceIn(0f, (size.height - boxH).coerceAtLeast(0f))
            drawRoundRect(Color(0xE6000000), Offset(right - boxW, top), Size(boxW, boxH), CornerRadius(12.dp.toPx()))
            if (controlled) drawRoundRect(accent, Offset(right - boxW, top), Size(boxW, boxH), CornerRadius(12.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            var y = top + py
            if (l != null) { drawText(l, topLeft = Offset(right - px - l.size.width, y)); y += l.size.height }
            if (s != null) { drawText(s, topLeft = Offset(right - px - s.size.width, y)); y += s.size.height }
            if (hl != null) drawText(hl, topLeft = Offset(right - px - hl.size.width, y + 4.dp.toPx()), color = accent)
        }
        // the bar itself as a focus target (narrow, far right): ▶ at the right edge of the grid lands here
        if (steps != null && steps.count > 0) androidx.compose.foundation.layout.Box(
            Modifier.align(androidx.compose.ui.Alignment.CenterEnd).fillMaxHeight().width(28.dp)
                .focusProperties { canFocus = scrollable() }
                .onFocusChanged {
                    if (it.isFocused && !focus) { choice.intValue = steps.current().coerceIn(0, steps.count - 1); start = choice.intValue; android.util.Log.i("Photos", "bar: focus (step $start of ${steps.count})") }
                    focus = it.isFocused
                }
                .onPreviewKeyEvent { e ->
                    val n = e.nativeKeyEvent
                    val arrow = n.keyCode == KeyEvent.KEYCODE_DPAD_UP || n.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                    val ok = n.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || n.keyCode == KeyEvent.KEYCODE_ENTER
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent arrow || ok || n.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || n.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    when {
                        arrow -> {
                            val d = if (n.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                            val r = n.repeatCount
                            // tap = one step; holding first ~10 steps/s, after ~1 s big jumps (year) ~6/s
                            val next = when {
                                r >= 14 -> if (r % 3 == 0) steps.bigStep(choice.intValue, d) else choice.intValue
                                r > 0 && r % 2 == 1 -> choice.intValue
                                else -> choice.intValue + d
                            }
                            choice.intValue = next.coerceIn(0, steps.count - 1)
                            true
                        }
                        ok -> { android.util.Log.i("Photos", "bar: OK -> step ${choice.intValue}"); steps.go(choice.intValue); true }
                        n.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { if (choice.intValue != start) steps.go(choice.intValue) else steps.back(); true }
                        n.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> true
                        n.keyCode == KeyEvent.KEYCODE_BACK -> { steps.back(); true }
                        else -> false
                    }
                }
                .focusable(),
        )
    }
}

/** test: `--ez geen_balk true` disables the scrollbar (to compare smoothness with and without) */
@Volatile var scrollbarDisabled = false

private class LabelCache {
    var time = 0L
    var labelText: String? = null
    var subText: String? = null
    var label: androidx.compose.ui.text.TextLayoutResult? = null
    var sub: androidx.compose.ui.text.TextLayoutResult? = null
}

/** Position 0..1 of a LazyColumn: first visible item + partial offset into it, relative to total scrollable range. */
fun fractionOf(list: LazyListState, headerCount: Int = 0): Float {
    val info = list.layoutInfo
    val visible = info.visibleItemsInfo
    val total = info.totalItemsCount - headerCount
    if (total <= 0 || visible.isEmpty()) return 0f
    val first = visible.first()
    val part = if (first.size > 0) (-first.offset.toFloat() / first.size).coerceIn(0f, 1f) else 0f
    val i = (first.index - headerCount + part).coerceAtLeast(0f)
    val n = (total - visible.size + 1).coerceAtLeast(1)
    return if (!list.canScrollForward) 1f else (i / n).coerceIn(0f, 1f)
}

fun fractionOf(grid: LazyGridState, headerCount: Int = 0): Float {
    val info = grid.layoutInfo
    val visible = info.visibleItemsInfo
    val total = info.totalItemsCount - headerCount
    if (total <= 0 || visible.isEmpty()) return 0f
    val i = (visible.first().index - headerCount).coerceAtLeast(0)
    val n = (total - visible.size + 1).coerceAtLeast(1)
    return if (!grid.canScrollForward) 1f else (i.toFloat() / n).coerceIn(0f, 1f)
}

/** Per row, the 0-based index of the first photo in it, for "N / M" in flat lists. */
fun firstPhotoPerRow(rows: List<GridItem>): IntArray {
    val out = IntArray(rows.size)
    var n = 0
    rows.forEachIndexed { i, r -> out[i] = n; if (r is PhotoRow) n += r.row.chunks.sumOf { it.cells.size } }
    return out
}
