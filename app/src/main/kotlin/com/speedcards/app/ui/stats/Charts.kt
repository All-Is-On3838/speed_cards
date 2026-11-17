package com.speedcards.app.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** One rainbow hue per heatmap row, Monday (red) through Sunday (violet). */
private val dayHues = intArrayOf(
    0xFFE0413A.toInt(), 0xFFF08A24.toInt(), 0xFFE8C21E.toInt(), 0xFF2FA85A.toInt(),
    0xFF3B7DDD.toInt(), 0xFF5B5BD6.toInt(), 0xFF9B59D0.toInt(),
)

const val HEAT_LEVELS = 4

/**
 * ARGB for a heatmap cell: the hue comes from the weekday ([dayIndex] 0 = Monday), and [level]
 * (0 until [HEAT_LEVELS]) sets the shade. On light surfaces more reviews are darker; on dark
 * surfaces more reviews are lighter, so busy days always stand out from the background.
 */
fun heatShade(dayIndex: Int, level: Int, dark: Boolean): Int {
    val hue = dayHues[dayIndex.mod(dayHues.size)]
    val t = level.toFloat() / (HEAT_LEVELS - 1)
    val pos = if (dark) 1f - t else t
    val (lightEnd, darkEnd) = if (dark) 0.35f to 0.55f else 0.6f to 0.45f
    return if (pos <= 0.5f) mixArgb(hue, 0xFFFFFFFF.toInt(), lightEnd * (1f - pos * 2))
    else mixArgb(hue, 0xFF000000.toInt(), darkEnd * (pos * 2 - 1))
}

/** Heat level for [n] reviews: quartiles of [max], or -1 for no reviews. */
fun heatLevel(n: Int, max: Int): Int =
    if (n <= 0 || max == 0) -1 else (Math.ceil(n.toDouble() * HEAT_LEVELS / max).toInt() - 1).coerceIn(0, HEAT_LEVELS - 1)

private fun mixArgb(a: Int, b: Int, f: Float): Int {
    fun ch(shift: Int) = ((a shr shift and 0xFF) * (1 - f) + (b shr shift and 0xFF) * f).toInt()
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

@Composable
fun chartBlue(): Color = if (isSystemInDarkTheme()) Color(0xFF5598E7) else Color(0xFF2A78D6)

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Review heatmap: one column per week (Monday first), one cell per day.
 * Each weekday row has its own rainbow hue; the shade steps by quartiles of the busiest day.
 * Tap a day to read its count.
 */
@Composable
fun Heatmap(counts: Map<LocalDate, Int>, today: LocalDate, weeks: Int = 26) {
    val dark = isSystemInDarkTheme()
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val todayRing = MaterialTheme.colorScheme.onSurface
    val start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks((weeks - 1).toLong())
    val max = counts.filterKeys { !it.isBefore(start) }.values.maxOrNull() ?: 0
    fun level(day: LocalDate, n: Int): Color {
        val l = heatLevel(n, max)
        return if (l < 0) empty else Color(heatShade(day.dayOfWeek.value - 1, l, dark))
    }
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    val fmt = remember { DateTimeFormatter.ofPattern("EEE d MMM") }

    Column {
        Text(
            selected?.let { "${it.format(fmt)}: ${counts[it] ?: 0} reviews" } ?: "Tap a day to see its reviews",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            Modifier.fillMaxWidth().height(110.dp).padding(top = 6.dp).pointerInput(start) {
                detectTapGestures { p ->
                    val gap = 2.dp.toPx()
                    val cell = minOf((size.width - gap * (weeks - 1)) / weeks, (size.height - gap * 6) / 7)
                    val col = (p.x / (cell + gap)).toInt()
                    val row = (p.y / (cell + gap)).toInt()
                    val day = start.plusWeeks(col.toLong()).plusDays(row.toLong())
                    selected = if (col in 0 until weeks && row in 0..6 && !day.isAfter(today)) day else null
                }
            },
        ) {
            val gap = 2.dp.toPx()
            val cell = minOf((size.width - gap * (weeks - 1)) / weeks, (size.height - gap * 6) / 7)
            for (w in 0 until weeks) for (d in 0..6) {
                val day = start.plusWeeks(w.toLong()).plusDays(d.toLong())
                if (day.isAfter(today)) continue
                val topLeft = Offset(w * (cell + gap), d * (cell + gap))
                drawRoundRect(level(day, counts[day] ?: 0), topLeft, Size(cell, cell), CornerRadius(2.dp.toPx()))
                if (day == today || day == selected) {
                    drawRoundRect(todayRing, topLeft, Size(cell, cell), CornerRadius(2.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
                }
            }
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Less", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val legendDay = today.dayOfWeek.value - 1
            (listOf(empty) + (0 until HEAT_LEVELS).map { Color(heatShade(legendDay, it, dark)) }).forEach { Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(it)) }
            Text("More", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Vertical bars from a shared baseline, rounded tops, 2dp gaps. Tap a bar to read its value.
 * [labels] are shown under the bars at [labelEvery] intervals.
 */
@Composable
fun BarChart(
    values: List<Int>,
    labels: List<String>,
    describe: (Int) -> String,
    labelEvery: Int = 1,
    height: Int = 140,
) {
    val color = chartBlue()
    val axis = MaterialTheme.colorScheme.outlineVariant
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Column {
        Text(
            selected?.let { describe(it) } ?: "Tallest: $max · tap a bar for details",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Canvas(
            Modifier.fillMaxWidth().height(height.dp).padding(top = 6.dp).pointerInput(values) {
                detectTapGestures { p ->
                    val slot = size.width / values.size
                    selected = (p.x / slot).toInt().takeIf { it in values.indices }
                }
            },
        ) {
            val slot = size.width / values.size
            val gap = 2.dp.toPx()
            val r = minOf(4.dp.toPx(), (slot - gap) / 2)
            values.forEachIndexed { i, v ->
                if (v == 0) return@forEachIndexed
                val h = size.height * v / max
                val left = i * slot + gap / 2
                val w = slot - gap
                val top = size.height - h
                // Rounded data end, square at the baseline.
                val path = Path().apply {
                    moveTo(left, size.height)
                    lineTo(left, top + r)
                    quadraticTo(left, top, left + r, top)
                    lineTo(left + w - r, top)
                    quadraticTo(left + w, top, left + w, top + r)
                    lineTo(left + w, size.height)
                    close()
                }
                drawPath(path, if (selected == null || selected == i) color else color.copy(alpha = 0.45f))
            }
            drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        }
        // Labels sit under their bar's left edge; they may be wider than one bar.
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            val slot = maxWidth / labels.size
            labels.forEachIndexed { i, label ->
                if (i % labelEvery == 0) Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.offset(x = slot * i),
                )
            }
        }
    }
}
