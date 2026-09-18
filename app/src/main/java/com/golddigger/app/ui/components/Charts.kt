package com.golddigger.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.rememberUpdatedState
import com.golddigger.app.domain.model.PricePoint
import com.golddigger.app.ui.theme.PortfolioColors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * One category on a chart. Categories are always derived from whatever is in the
 * database at render time — never a fixed list.
 */
data class ChartSlice(
    val label: String,
    val value: Double,
    val color: Color,
)

/**
 * A hand-tuned categorical palette for the first dozen slices, then a
 * deterministic golden-angle hue walk for anything beyond — so a portfolio with
 * 3 or 300 holdings still gets distinct, on-brand colors without a fixed list.
 */
private val CHART_PALETTE = listOf(
    Color(0xFFCFA43B), // gold
    Color(0xFF2E9E6B), // green
    Color(0xFF3B7DD8), // blue
    Color(0xFFE0714E), // coral
    Color(0xFF8C6BD9), // violet
    Color(0xFF2BB3B3), // teal
    Color(0xFFD9A21B), // amber
    Color(0xFF5FA85A), // leaf
    Color(0xFFDB6B9A), // pink
    Color(0xFF4C5C99), // indigo
    Color(0xFFB6893C), // bronze
    Color(0xFF6FB0C9), // sky
)

fun chartColor(index: Int, total: Int): Color {
    if (index < CHART_PALETTE.size) return CHART_PALETTE[index]
    // Golden-angle spacing gives well-separated hues for large N.
    val hue = ((index - CHART_PALETTE.size) * 137.508f) % 360f
    return Color.hsl(hue = hue, saturation = 0.5f, lightness = 0.55f)
}

/**
 * Donut chart. Dragging a finger across the ring highlights the slice under the
 * touch and passes it to [centerContent] (rendered in the hole) so the caller
 * can show the holding / cash name and value; releasing clears the selection.
 */
@Composable
fun PieChart(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier,
    centerContent: @Composable (focused: ChartSlice?) -> Unit = {},
) {
    val total = slices.sumOf { it.value }.toFloat()
    if (total <= 0f) return

    var focusedIndex by remember(slices) { mutableStateOf<Int?>(null) }

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
                .pointerInput(slices) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startIndex = sliceIndexAt(down.position, size, slices, total)
                        // A touch starting outside the ring (the center hole,
                        // or the padding around it) isn't consumed at all, so
                        // an ancestor scrollable (this chart is often a
                        // LazyColumn item) still sees it and can scroll —
                        // only a touch that starts ON a slice commits to the
                        // chart's own omnidirectional drag-to-focus gesture.
                        if (startIndex == null) return@awaitEachGesture
                        focusedIndex = startIndex
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            focusedIndex = sliceIndexAt(change.position, size, slices, total)
                            change.consume()
                        }
                        focusedIndex = null
                    }
                },
        ) {
            val baseStroke = size.minDimension * 0.16f
            val diameter = size.minDimension - baseStroke * 1.4f
            val topLeft = Offset(
                (size.width - diameter) / 2f,
                (size.height - diameter) / 2f,
            )
            var startAngle = -90f
            slices.forEachIndexed { index, slice ->
                val sweep = slice.value.toFloat() / total * 360f
                val isFocused = index == focusedIndex
                val dimmed = focusedIndex != null && !isFocused
                drawArc(
                    color = if (dimmed) slice.color.copy(alpha = 0.35f) else slice.color,
                    startAngle = startAngle,
                    sweepAngle = sweep - 1.5f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = Stroke(width = if (isFocused) baseStroke * 1.35f else baseStroke),
                )
                startAngle += sweep
            }
        }
        centerContent(focusedIndex?.let { slices.getOrNull(it) })
    }
}

/**
 * Which slice sits under [pos] (Canvas pixel space), or null if the touch is
 * outside the ring. Slices start at 12 o'clock and run clockwise.
 */
private fun sliceIndexAt(
    pos: Offset,
    size: IntSize,
    slices: List<ChartSlice>,
    total: Float,
): Int? {
    if (total <= 0f || slices.isEmpty()) return null
    val dx = pos.x - size.width / 2f
    val dy = pos.y - size.height / 2f
    val radius = hypot(dx, dy)
    val outer = min(size.width, size.height) / 2f
    if (radius > outer || radius < outer * 0.32f) return null

    val degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    var rel = (degrees + 90f) % 360f
    if (rel < 0f) rel += 360f

    var acc = 0f
    slices.forEachIndexed { index, slice ->
        val sweep = slice.value.toFloat() / total * 360f
        if (rel >= acc && rel < acc + sweep) return index
        acc += sweep
    }
    return slices.lastIndex
}

@Composable
fun BarChart(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier,
    valueLabel: (Double) -> String = { it.toString() },
) {
    val maxValue = slices.maxOfOrNull { it.value } ?: return
    if (maxValue <= 0.0) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        slices.forEach { slice ->
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        slice.label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(valueLabel(slice.value), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .drawBehind {
                            drawRect(color = slice.color.copy(alpha = 0.18f))
                            drawRect(
                                color = slice.color,
                                size = Size(
                                    width = size.width * (slice.value / maxValue).toFloat(),
                                    height = size.height,
                                ),
                            )
                        },
                )
            }
        }
    }
}

@Composable
fun ChartLegend(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier,
    trailing: (ChartSlice) -> String = { "" },
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        slices.forEach { slice ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .drawBehind { drawRect(slice.color) },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    slice.label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(trailing(slice), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * The Holding Detail price-history chart. Unlike a plain sparkline, x-position
 * is proportional to elapsed time (not point index) so a range with uneven
 * sync gaps — a weekend, a stretch with background sync off — doesn't distort
 * into looking evenly spaced, and the line is tinted green/red by whether the
 * range's last point is above or below its first, matching the app's
 * gain/loss color language everywhere else.
 *
 * Dragging a finger across the chart scrubs a crosshair to the nearest point
 * by time (not touch-x-to-index, since points aren't evenly spaced) and
 * reports it via [onScrub] so the caller can show its price/time somewhere
 * off-canvas; releasing reports null, same touch-lifecycle shape as [PieChart].
 *
 * Only claims the gesture once it's moved past touch slop *and* the movement
 * is mostly horizontal — scrubbing is inherently a left-right interaction
 * (the y-axis is price, not a selectable dimension), so a mostly-vertical
 * drag is left unconsumed for the enclosing scrollable/PullToRefreshBox
 * (this chart normally sits inside both) to handle instead.
 */
@Composable
fun PriceHistoryChart(
    points: List<PricePoint>,
    modifier: Modifier = Modifier,
    onScrub: (PricePoint?) -> Unit = {},
) {
    if (points.size < 2) {
        Box(modifier)
        return
    }
    val lineColor = if (points.last().price >= points.first().price) {
        PortfolioColors.gain
    } else {
        PortfolioColors.loss
    }
    val minValue = points.minOf { it.price }
    val maxValue = points.maxOf { it.price }
    val valueRange = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
    val minTimestamp = points.first().timestamp
    val timeRange = (points.last().timestamp - minTimestamp).takeIf { it > 0L } ?: 1L

    // pointerInput is keyed on points, so it restarts (and this stays fresh)
    // whenever the range's data changes; rememberUpdatedState is still needed
    // because onScrub itself can get a new identity on recomposition without
    // points changing.
    val currentOnScrub by rememberUpdatedState(onScrub)
    var scrubIndex by remember(points) { mutableStateOf<Int?>(null) }

    Canvas(
        modifier.pointerInput(points) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var committed = false
                var totalDx = 0f
                var totalDy = 0f
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) break
                    if (!committed) {
                        val delta = change.positionChange()
                        totalDx += delta.x
                        totalDy += delta.y
                        val pastSlop = abs(totalDx) > viewConfiguration.touchSlop ||
                            abs(totalDy) > viewConfiguration.touchSlop
                        if (!pastSlop) continue
                        if (abs(totalDx) <= abs(totalDy)) {
                            // Mostly-vertical: a scroll/pull-to-refresh
                            // gesture, not a scrub. Leave events unconsumed
                            // and stop tracking for the rest of this touch.
                            break
                        }
                        committed = true
                    }
                    val index = indexForTouchX(change.position.x, size.width.toFloat(), points, minTimestamp, timeRange)
                    scrubIndex = index
                    currentOnScrub(points[index])
                    change.consume()
                }
                scrubIndex = null
                currentOnScrub(null)
            }
        },
    ) {
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = (point.timestamp - minTimestamp).toFloat() / timeRange * size.width
            val y = size.height - ((point.price - minValue) / valueRange).toFloat() * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 3f, cap = StrokeCap.Round),
        )

        scrubIndex?.let { idx ->
            val point = points[idx]
            val x = (point.timestamp - minTimestamp).toFloat() / timeRange * size.width
            val y = size.height - ((point.price - minValue) / valueRange).toFloat() * size.height
            drawLine(
                color = lineColor.copy(alpha = 0.4f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )
            drawCircle(color = lineColor, radius = 7f, center = Offset(x, y))
            drawCircle(color = Color.White, radius = 3f, center = Offset(x, y))
        }
    }
}

/**
 * Index of the point nearest [touchX] (canvas pixel space) by elapsed time —
 * not by proportionally mapping touch-x to a list index, since points aren't
 * evenly spaced in time (sync gaps mean a run of closely-timed points can sit
 * next to a wide gap). Binary search on timestamp, same complexity a linear
 * scan of touch-move events would otherwise pay per frame.
 */
private fun indexForTouchX(
    touchX: Float,
    canvasWidth: Float,
    points: List<PricePoint>,
    minTimestamp: Long,
    timeRange: Long,
): Int {
    val targetTime = minTimestamp + (touchX / canvasWidth).coerceIn(0f, 1f) * timeRange
    var lo = 0
    var hi = points.lastIndex
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (points[mid].timestamp < targetTime) lo = mid + 1 else hi = mid
    }
    if (lo > 0) {
        val prevDelta = targetTime - points[lo - 1].timestamp
        val currDelta = points[lo].timestamp - targetTime
        if (prevDelta <= currDelta) return lo - 1
    }
    return lo
}
