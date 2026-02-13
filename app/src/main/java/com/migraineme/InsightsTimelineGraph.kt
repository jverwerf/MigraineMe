package com.migraineme

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private data class HitTarget(val x: Float, val y: Float, val label: String)
private val GridColor = Color.White.copy(alpha = 0.06f)
private val AxisColor = Color.White.copy(alpha = 0.12f)
private val MigBarColor = Color(0xFFB97BFF)
private val HighlightCol = Color(0xFFFF7BB0)
private val PopupBg = Color(0xFF2A0C3C)
private val AutoDotColor = Color(0xFFFFD54F)

@Composable
fun InsightsTimelineGraph(
    migraines: List<MigraineSpan>, events: List<EventMarker>, metricSeries: List<MetricSeries>,
    windowStart: Instant?, windowEnd: Instant?, highlightMigraineStart: Instant? = null, modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxWidth(), color = Color(0xFF1A0628).copy(alpha = 0.85f), shape = RoundedCornerShape(14.dp)) {
        if (windowStart != null && windowEnd != null) CoreCanvas(migraines, events, metricSeries, windowStart, windowEnd, highlightMigraineStart, Modifier.fillMaxSize().padding(8.dp))
    }
}

@Composable
fun InsightsTimelineGraphInteractive(
    migraines: List<MigraineSpan>, reliefs: List<ReliefSpan>, triggers: List<TriggerPoint>, meds: List<MedicinePoint>,
    hOffsetPx: androidx.compose.runtime.MutableState<Float>, timeSpan: TimeSpan, modifier: Modifier
) {
    val events = remember(triggers, meds, reliefs) {
        val ev = mutableListOf<EventMarker>()
        triggers.forEach { ev += EventMarker(it.at, null, it.name, "Trigger", null, EventCategoryColors["Trigger"]!!) }
        meds.forEach { ev += EventMarker(it.at, null, it.name, "Medicine", it.amount?.let { a -> "Amount: $a" }, EventCategoryColors["Medicine"]!!) }
        reliefs.forEach { ev += EventMarker(it.start, it.end, it.name, "Relief", null, EventCategoryColors["Relief"]!!) }
        ev.sortedBy { it.at }
    }
    val now = Instant.now()
    Surface(modifier = modifier.fillMaxWidth(), color = Color(0xFF1A0628).copy(alpha = 0.85f), shape = RoundedCornerShape(14.dp)) {
        CoreCanvas(migraines, events, emptyList(), now.minusMillis(timeSpan.millis), now, null, Modifier.fillMaxSize().padding(12.dp))
    }
}

@Composable
private fun CoreCanvas(
    migraines: List<MigraineSpan>, events: List<EventMarker>, metricSeries: List<MetricSeries>,
    windowStart: Instant, windowEnd: Instant, highlightMigraineStart: Instant?, modifier: Modifier
) {
    val density = LocalDensity.current
    var cW by remember { mutableStateOf(0) }; var cH by remember { mutableStateOf(0) }
    var popup by remember { mutableStateOf<HitTarget?>(null) }
    val zone = ZoneId.systemDefault(); val visMs = Duration.between(windowStart, windowEnd).toMillis().coerceAtLeast(1)
    val eventCats = remember(events) { events.map { it.category }.distinct() }
    val sortedEvents = remember(events) { events.sortedBy { it.at } }
    val datePaint = remember { Paint().apply { color = Color.White.copy(alpha = 0.5f).toArgb(); textSize = 24f; isAntiAlias = true } }
    val catPaint = remember { Paint().apply { color = Color.White.copy(alpha = 0.35f).toArgb(); textSize = 20f; isAntiAlias = true; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) } }
    val numPaint = remember { Paint().apply { color = Color.White.copy(alpha = 0.85f).toArgb(); textSize = 14f; isAntiAlias = true; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER } }

    Box(modifier = modifier.onSizeChanged { cW = it.width; cH = it.height }) {
        Canvas(Modifier.matchParentSize().pointerInput(windowStart, windowEnd) {
            detectTapGestures { pos ->
                val w = cW.toFloat(); val h = cH.toFloat(); val x0 = 8f; val x1 = w - 8f
                fun xOf(t: Instant): Float { val cl = t.clamp(windowStart, windowEnd); return x0 + (cl.toEpochMilli() - windowStart.toEpochMilli()).toFloat() / visMs * (x1 - x0) }
                val migBot = h * 0.16f; val evTop = migBot + 4f; val evBot = if (eventCats.isNotEmpty()) h * 0.48f else evTop; val now = Instant.now()
                val hits = mutableListOf<HitTarget>()
                migraines.forEach { m -> val xs = xOf(m.start); val xe = xOf(m.end ?: now); hits += HitTarget((xs + xe) / 2f, migBot / 2f, "${m.label ?: "Migraine"}\nSeverity: ${m.severity ?: "–"}/10\n${hDur(m.start, m.end ?: now)}") }
                val cc = eventCats.size.coerceAtLeast(1); val rh = (evBot - evTop) / cc; val hitMin = 14f
                val hitOffsets = mutableListOf<Pair<Float, Float>>() // (cx, cy) of placed dots
                sortedEvents.forEachIndexed { idx, ev -> val ci = eventCats.indexOf(ev.category).coerceAtLeast(0); val auto = if (ev.isAutomated) " ⚡auto" else ""
                    val rcy = evTop + ci * rh + rh / 2f; val ex = xOf(ev.at)
                    val nearby = hitOffsets.filter { kotlin.math.abs(it.first - ex) < hitMin && kotlin.math.abs(it.second - rcy) < 1f }
                    val ox = if (nearby.isEmpty()) 0f else { val n = nearby.size; val s = if (n % 2 == 0) -1f else 1f; s * ((n + 1) / 2) * hitMin }
                    hitOffsets.add(Pair(ex + ox, rcy))
                    hits += HitTarget(ex + ox, rcy, "#${idx + 1} ${ev.name}$auto${ev.detail?.let { "\n$it" } ?: ""}") }
                val near = hits.minByOrNull { hypot(it.x - pos.x, it.y - pos.y) }
                popup = if (near != null && hypot(near.x - pos.x, near.y - pos.y) <= 56f) near else null
            }
        }) {
            val now = Instant.now(); val w = size.width; val h = size.height; val x0 = 8f; val x1 = w - 8f; val bPad = 32f
            fun xOf(t: Instant): Float { val cl = t.clamp(windowStart, windowEnd); return x0 + (cl.toEpochMilli() - windowStart.toEpochMilli()).toFloat() / visMs * (x1 - x0) }
            val migBot = h * 0.16f; val evTop = migBot + 4f; val catCnt = eventCats.size; val evBot = if (catCnt > 0) h * 0.48f else evTop; val metTop = evBot + 8f; val metBot = h - bPad

            // X-axis date labels — skip days to prevent overlap
            val totalDays = ChronoUnit.DAYS.between(
                ZonedDateTime.ofInstant(windowStart, zone).truncatedTo(ChronoUnit.DAYS),
                ZonedDateTime.ofInstant(windowEnd, zone).truncatedTo(ChronoUnit.DAYS)
            ).toInt() + 1
            // Show at most ~6 labels so they never overlap
            val labelEveryN = when {
                totalDays <= 7 -> 1
                totalDays <= 14 -> 2
                totalDays <= 21 -> 3
                totalDays <= 42 -> 5
                else -> 7
            }
            val dateFmt = if (totalDays <= 14)
                DateTimeFormatter.ofPattern("d").withZone(zone)
            else
                DateTimeFormatter.ofPattern("d").withZone(zone)
            val monthFmt = DateTimeFormatter.ofPattern("MMM").withZone(zone)

            var day = ZonedDateTime.ofInstant(windowStart, zone).truncatedTo(ChronoUnit.DAYS)
            val endDay = ZonedDateTime.ofInstant(windowEnd, zone).truncatedTo(ChronoUnit.DAYS).plusDays(1)
            var dayIdx = 0
            while (!day.isAfter(endDay)) {
                val xi = xOf(day.toInstant())
                // Grid line for every day
                drawLine(GridColor, Offset(xi, 0f), Offset(xi, h - bPad), 1f)
                // Label only every Nth day
                if (dayIdx % labelEveryN == 0) {
                    val dayStr = dateFmt.format(day)
                    val monthStr = monthFmt.format(day)
                    // Show month on first label or when month changes
                    val showMonth = dayIdx == 0 || day.dayOfMonth <= labelEveryN
                    val label = if (showMonth) "$monthStr $dayStr" else dayStr
                    val tw = datePaint.measureText(label)
                    drawContext.canvas.nativeCanvas.drawText(
                        label, xi - tw / 2f, h - 6f, datePaint
                    )
                }
                day = day.plusDays(1)
                dayIdx++
            }
            drawLine(AxisColor, Offset(x0, migBot), Offset(x1, migBot), 1f); if (catCnt > 0) drawLine(AxisColor, Offset(x0, evBot), Offset(x1, evBot), 1f)

            if (highlightMigraineStart != null) migraines.find { it.start == highlightMigraineStart }?.let { sel ->
                val xs = xOf(sel.start); val xe = xOf(sel.end ?: now); val bL = min(xs, xe); val bR = max(xs, xe)
                drawRect(brush = Brush.verticalGradient(listOf(HighlightCol.copy(alpha = 0.10f), HighlightCol.copy(alpha = 0.02f))), topLeft = Offset(bL, 0f), size = Size((bR - bL).coerceAtLeast(4f), h - bPad))
                val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)); drawLine(HighlightCol.copy(alpha = 0.4f), Offset(xs, 0f), Offset(xs, h - bPad), 1.5f, pathEffect = dash)
                if (sel.end != null) drawLine(HighlightCol.copy(alpha = 0.3f), Offset(xe, 0f), Offset(xe, h - bPad), 1.5f, pathEffect = dash)
            }

            val barY = migBot / 2f; val minStk = with(density) { 7.dp.toPx() }; val maxStk = with(density) { 14.dp.toPx() }
            migraines.sortedBy { it.start }.forEach { m -> val xs = xOf(m.start); val xe = xOf(m.end ?: now); val sev = (m.severity ?: 0).coerceIn(0, 10); val thick = minStk + (maxStk - minStk) * (sev / 10f)
                val isHl = highlightMigraineStart != null && m.start == highlightMigraineStart; val col = if (isHl) HighlightCol else MigBarColor
                drawLine(col.copy(alpha = 0.15f), Offset(xs, barY), Offset(xe, barY), thick + 8f, StrokeCap.Round); drawLine(col.copy(alpha = 0.8f), Offset(xs, barY), Offset(xe, barY), thick, StrokeCap.Round)
                if (sev > 0) drawContext.canvas.nativeCanvas.drawText("$sev", (xs + xe) / 2f - 6f, barY + 4f, Paint().apply { color = Color.White.toArgb(); textSize = 20f; isAntiAlias = true; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }) }

            if (catCnt > 0) { val rowH = (evBot - evTop) / catCnt
                eventCats.forEachIndexed { ci, catName -> val rcy = evTop + ci * rowH + rowH / 2f; drawContext.canvas.nativeCanvas.drawText(catName, x0 + 2f, rcy + 5f, catPaint); if (ci > 0) drawLine(GridColor, Offset(x0, evTop + ci * rowH), Offset(x1, evTop + ci * rowH), 0.5f) }

                // Pre-compute positions with overlap offset
                data class DotPos(val idx: Int, val ev: EventMarker, val cx: Float, val cy: Float, val ox: Float, val oy: Float)
                val dotPositions = mutableListOf<DotPos>()
                val minSpacing = 14f // min px between dot centers before we spread

                sortedEvents.forEachIndexed { idx, ev ->
                    val ci = eventCats.indexOf(ev.category).coerceAtLeast(0)
                    val rcy = evTop + ci * rowH + rowH / 2f
                    val ex = xOf(ev.at)

                    // Find how many prior dots in same row are too close
                    val nearby = dotPositions.filter { kotlin.math.abs(it.cx - ex) < minSpacing && kotlin.math.abs(it.cy - rcy) < 1f }
                    val offsetX = if (nearby.isEmpty()) 0f else {
                        val n = nearby.size
                        // Alternate left/right: 0, +14, -14, +28, -28...
                        val sign = if (n % 2 == 0) -1f else 1f
                        sign * ((n + 1) / 2) * minSpacing
                    }
                    dotPositions.add(DotPos(idx, ev, ex, rcy, ex + offsetX, rcy))
                }

                // Draw dots at offset positions
                dotPositions.forEach { dp ->
                    val ev = dp.ev; val dx = dp.ox; val dy = dp.oy
                    if (ev.endAt != null) { val ex2 = xOf(ev.endAt); drawLine(ev.color.copy(alpha = 0.6f), Offset(dp.cx, dy), Offset(ex2, dy), 6f, StrokeCap.Round) }
                    if (ev.isAutomated) { drawCircle(AutoDotColor.copy(alpha = 0.2f), 12f, Offset(dx, dy)); drawCircle(AutoDotColor, 6f, Offset(dx, dy)) }
                    else { drawCircle(ev.color.copy(alpha = 0.15f), 10f, Offset(dx, dy)); drawCircle(ev.color, 5f, Offset(dx, dy)) }
                    // Number label above dot
                    drawContext.canvas.nativeCanvas.drawText("${dp.idx + 1}", dx, dy - 10f, numPaint)
                }
            }

            if (metricSeries.isNotEmpty() && metTop < metBot) { val chartH = metBot - metTop
                metricSeries.forEach { series -> if (series.points.size < 2) return@forEach; val sorted = series.points.sortedBy { it.date }; val minV = sorted.minOf { it.value }; val maxV = sorted.maxOf { it.value }; val range = (maxV - minV).coerceAtLeast(0.01)
                    fun dateX(ds: String): Float { val ld = java.time.LocalDate.parse(ds); return xOf(ld.atStartOfDay(zone).toInstant().plusSeconds(43200)) }
                    fun valY(v: Double): Float { return metBot - ((v - minV) / range).toFloat().coerceIn(0f, 1f) * chartH * 0.85f }
                    val path = Path(); sorted.forEachIndexed { i, pt -> if (i == 0) path.moveTo(dateX(pt.date), valY(pt.value)) else path.lineTo(dateX(pt.date), valY(pt.value)) }
                    drawPath(path, series.color.copy(alpha = 0.1f), style = Stroke(width = 6f, cap = StrokeCap.Round)); drawPath(path, series.color.copy(alpha = 0.7f), style = Stroke(width = 2.5f, cap = StrokeCap.Round))
                    sorted.forEach { pt -> drawCircle(series.color.copy(alpha = 0.3f), 5f, Offset(dateX(pt.date), valY(pt.value))); drawCircle(series.color, 2.5f, Offset(dateX(pt.date), valY(pt.value))) }
                    val first = sorted.first(); drawContext.canvas.nativeCanvas.drawText("${series.label}: ${"%.1f".format(first.value)}${series.unit}", dateX(first.date) + 4f, valY(first.value) - 6f, Paint().apply { color = series.color.copy(alpha = 0.5f).toArgb(); textSize = 17f; isAntiAlias = true })
                    if (sorted.size > 2) { val last = sorted.last(); drawContext.canvas.nativeCanvas.drawText("${"%.1f".format(last.value)}", dateX(last.date) - 12f, valY(last.value) - 6f, Paint().apply { color = series.color.copy(alpha = 0.5f).toArgb(); textSize = 17f; isAntiAlias = true }) }
                }
            }
        }
        popup?.let { hit -> val offX = (hit.x - 120f).roundToInt().coerceAtLeast(0); val offY = (hit.y - 70f).roundToInt().coerceAtLeast(0)
            Card(Modifier.offset { IntOffset(offX, offY) }, colors = CardDefaults.cardColors(containerColor = PopupBg), shape = RoundedCornerShape(10.dp), elevation = CardDefaults.cardElevation(8.dp)) {
                Text(hit.label, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f)) } }
    }
}

private fun Instant.clamp(a: Instant, b: Instant): Instant = when { this.isBefore(a) -> a; this.isAfter(b) -> b; else -> this }
private fun hDur(s: Instant, e: Instant): String { val d = Duration.between(s, e).abs(); val h = d.toHours(); val m = d.minusHours(h).toMinutes(); return buildString { if (h > 0) append("${h}h "); append("${m}m") } }

