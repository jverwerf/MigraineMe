// FILE: C:\Users\verwe\Projects\MigraineMe\app\src\main\java\com\migraineme\InsightsScreen.kt
package com.migraineme

import android.content.Context
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class MigraineSpan(
    val start: Instant,
    val end: Instant?,
    val severity: Int? = null,
    val label: String? = null
)

data class ReliefSpan(
    val start: Instant,
    val end: Instant?,
    val intensity: Int? = null,
    val name: String
)

data class TriggerPoint(
    val at: Instant,
    val name: String
)

data class MedicinePoint(
    val at: Instant,
    val name: String,
    val amount: String?
)

private data class HitPoint(val x: Float, val y: Float, val label: String)

private enum class TimeSpan(val days: Long, val label: String) {
    DAY(1, "Day"),
    WEEK(7, "Week"),
    MONTH(30, "Month"),
    YEAR(365, "Year");

    val millis: Long get() = days * 24L * 60L * 60L * 1000L
}

@Composable
fun InsightsScreen(
    vm: InsightsViewModel = viewModel()
) {
    val owner = LocalContext.current as ViewModelStoreOwner
    val ctx: Context = LocalContext.current.applicationContext
    val authVm: AuthViewModel = viewModel(owner)
    val auth by authVm.state.collectAsState()

    // Load only (no workers here)
    LaunchedEffect(auth.accessToken) {
        val token = auth.accessToken
        if (!token.isNullOrBlank()) vm.load(ctx, token)
    }

    val migraines by vm.migraines.collectAsState()
    val reliefs by vm.reliefs.collectAsState()
    val triggers by vm.triggers.collectAsState()
    val meds by vm.medicines.collectAsState()

    val vScroll = rememberScrollState()
    val hOffsetPx = remember { mutableFloatStateOf(0f) }
    var timeSpan by remember { mutableStateOf(TimeSpan.WEEK) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(vScroll)
            .padding(12.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Insights", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))

        Text("graph", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TimeSpan.values().forEach { span ->
                val selected = span == timeSpan
                if (selected) {
                    Button(
                        onClick = { timeSpan = span },
                        modifier = Modifier.padding(end = 8.dp),
                        colors = ButtonDefaults.buttonColors()
                    ) { Text(span.label) }
                } else {
                    OutlinedButton(
                        onClick = { timeSpan = span },
                        modifier = Modifier.padding(end = 8.dp)
                    ) { Text(span.label) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        TimelineCanvas(
            migraines = migraines,
            reliefs = reliefs,
            triggers = triggers,
            meds = meds,
            hOffsetPx = hOffsetPx,
            timeSpan = timeSpan,
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
        )
    }
}

@Composable
private fun TimelineCanvas(
    migraines: List<MigraineSpan>,
    reliefs: List<ReliefSpan>,
    triggers: List<TriggerPoint>,
    meds: List<MedicinePoint>,
    hOffsetPx: MutableState<Float>,
    timeSpan: TimeSpan,
    modifier: Modifier,
    zoneId: ZoneId = ZoneId.systemDefault(),
    minBarHeight: Dp = 10.dp,
    maxBarHeight: Dp = 18.dp,
    showDayTicks: Boolean = true
) {
    val density = LocalDensity.current
    var canvasWidthPx by remember { mutableStateOf(0) }
    var canvasHeightPx by remember { mutableStateOf(0) }
    var popup by remember { mutableStateOf<HitPoint?>(null) }

    val axisTextPaint = remember { Paint().apply { color = Color.Gray.toArgb(); textSize = 28f; isAntiAlias = true } }
    val axisLabelPaint = remember { Paint().apply { color = Color.DarkGray.toArgb(); textSize = 30f; isAntiAlias = true } }
    val brainPaint = remember { Paint().apply { color = Color.DarkGray.toArgb(); textSize = 36f; isAntiAlias = true } }
    val leafPaint = remember { Paint().apply { color = Color.DarkGray.toArgb(); textSize = 36f; isAntiAlias = true } }
    val pillPaint = remember { Paint().apply { color = Color.DarkGray.toArgb(); textSize = 36f; isAntiAlias = true } }
    val lightningPaint = remember { Paint().apply { color = Color.DarkGray.toArgb(); textSize = 36f; isAntiAlias = true } }

    val hitRadiusPx = with(density) { 24.dp.toPx() }
    val barHitHalfHeight = with(density) { 16.dp.toPx() }

    fun humanDuration(start: Instant, end: Instant): String {
        val d = Duration.between(start, end).abs()
        val hours = d.toHours()
        val minutes = d.minusHours(hours).toMinutes()
        val parts = buildList {
            if (hours > 0) add("${hours}h")
            if (minutes > 0 || hours == 0L) add("${minutes}m")
        }
        return parts.joinToString(" ")
    }

    val Y_MIG = 0.9f
    val Y_MED = 0.65f
    val Y_REL = 0.4f
    val Y_TRI = 0.15f

    Box(modifier = modifier.onSizeChanged { size ->
        canvasWidthPx = size.width
        canvasHeightPx = size.height
    }) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        hOffsetPx.value += delta
                        if (hOffsetPx.value > 0f) hOffsetPx.value = 0f
                    }
                )
                .pointerInput(timeSpan) {
                    detectTapGestures { pos ->
                        val now = Instant.now()
                        val leftPad = 56f
                        val rightPad = 16f
                        val topPad = 16f
                        val bottomPad = 40f
                        val x0 = leftPad
                        val x1 = canvasWidthPx.toFloat() - rightPad
                        val y0 = topPad
                        val y1 = canvasHeightPx.toFloat() - bottomPad

                        fun yOf(v: Float) = y1 - (v.coerceIn(0f, 1f)) * (y1 - y0)

                        val visibleMillis = timeSpan.millis
                        val millisPerPx = visibleMillis / (x1 - x0).coerceAtLeast(1f)
                        val effectiveOffset = min(hOffsetPx.value, 0f)
                        val leftInstant = now.minusMillis(visibleMillis)
                            .minusMillis((effectiveOffset * -1f * millisPerPx).toLong())
                        val rightInstant = leftInstant.plusMillis(visibleMillis)

                        fun xOf(t: Instant): Float {
                            val c = when {
                                t.isBefore(leftInstant) -> leftInstant
                                t.isAfter(rightInstant) -> rightInstant
                                else -> t
                            }
                            val r = (c.toEpochMilli() - leftInstant.toEpochMilli()).toFloat() / visibleMillis
                            return x0 + r * (x1 - x0)
                        }

                        val hits = mutableListOf<HitPoint>()

                        val yMig = yOf(Y_MIG)
                        migraines.sortedBy { it.start }.forEach { m ->
                            val s = m.start
                            val e = m.end ?: now
                            val xs = xOf(s)
                            val xe = xOf(e)
                            val mid = xs + (xe - xs) / 2f
                            val lbl = "${m.label ?: "Migraine"}\nDuration: ${humanDuration(s, e)}"
                            hits += HitPoint(mid, yMig, lbl)
                            if (pos.x in min(xs, xe)..max(xs, xe) && abs(pos.y - yMig) <= barHitHalfHeight) {
                                popup = HitPoint(pos.x, yMig, lbl)
                                return@detectTapGestures
                            }
                        }

                        val yRel = yOf(Y_REL)
                        reliefs.sortedBy { it.start }.forEach { r ->
                            val s = r.start
                            val e = r.end ?: now
                            val xs = xOf(s)
                            val xe = xOf(e)
                            val mid = xs + (xe - xs) / 2f
                            val lbl = "${r.name}\nDuration: ${humanDuration(s, e)}"
                            hits += HitPoint(mid, yRel, lbl)
                            if (pos.x in min(xs, xe)..max(xs, xe) && abs(pos.y - yRel) <= barHitHalfHeight) {
                                popup = HitPoint(pos.x, yRel, lbl)
                                return@detectTapGestures
                            }
                        }

                        val yMed = yOf(Y_MED)
                        meds.sortedBy { it.at }.forEach { med ->
                            val x = xOf(med.at)
                            val line2 = med.amount?.let { amt -> "Amount: $amt" } ?: ""
                            val lbl = if (line2.isNotEmpty()) "${med.name}\n$line2" else med.name
                            hits += HitPoint(x, yMed, lbl)
                        }

                        val yTrig = yOf(Y_TRI)
                        triggers.sortedBy { it.at }.forEach { t ->
                            val x = xOf(t.at)
                            hits += HitPoint(x, yTrig, t.name)
                        }

                        val nearest = hits.minByOrNull { h -> hypot(h.x - pos.x, h.y - pos.y) }
                        popup = if (nearest != null && hypot(nearest.x - pos.x, nearest.y - pos.y) <= hitRadiusPx) nearest else null
                    }
                }
        ) {
            val now = Instant.now()

            val leftPadPx = 56f
            val rightPadPx = 16f
            val topPadPx = 16f
            val bottomPadPx = 40f
            val axisStroke = 2f

            val x0 = leftPadPx
            val x1 = size.width - rightPadPx
            val y0 = topPadPx
            val y1 = size.height - bottomPadPx

            fun yOf(v: Float) = y1 - (v.coerceIn(0f, 1f)) * (y1 - y0)

            val visibleMillis = timeSpan.millis
            val millisPerPx = visibleMillis / (x1 - x0).coerceAtLeast(1f)
            val effectiveOffset = min(hOffsetPx.value, 0f)
            val leftInstant = now.minusMillis(visibleMillis)
                .minusMillis((effectiveOffset * -1f * millisPerPx).toLong())
            val rightInstant = leftInstant.plusMillis(visibleMillis)

            fun xOf(t: Instant): Float {
                val c = when {
                    t.isBefore(leftInstant) -> leftInstant
                    t.isAfter(rightInstant) -> rightInstant
                    else -> t
                }
                val r = (c.toEpochMilli() - leftInstant.toEpochMilli()).toFloat() / visibleMillis
                return x0 + r * (x1 - x0)
            }

            // axes
            drawLine(Color.Gray, Offset(x0, y1), Offset(x1, y1), axisStroke)
            drawLine(Color.Gray, Offset(x0, y0), Offset(x0, y1), axisStroke)

            val marks = listOf(1f, 0.75f, 0.5f, 0.25f, 0f)
            marks.forEach { value ->
                val y = yOf(value)
                drawLine(Color.DarkGray, Offset(x0 - 6f, y), Offset(x0 + 6f, y), 2f)
                drawContext.canvas.nativeCanvas.drawText(
                    value.toString(), x0 - 48f, y + 10f, axisLabelPaint
                )
            }

            val minStroke = with(density) { minBarHeight.toPx() }
            val maxStroke = with(density) { maxBarHeight.toPx() }

            val yMig = yOf(0.9f)
            migraines.sortedBy { it.start }.forEach { m ->
                val xs = xOf(m.start)
                val xe = xOf(m.end ?: now)
                val sev = (m.severity ?: 0).coerceIn(0, 10)
                val t = minStroke + (maxStroke - minStroke) * (sev / 10f)
                drawLine(Color(0xFF607D8B), Offset(xs, yMig), Offset(xe, yMig), t)
                val mid = xs + (xe - xs) / 2f
                drawContext.canvas.nativeCanvas.drawText("🧠", mid - 12f, yMig - t / 2f - 6f, brainPaint)
            }

            val yRel = yOf(0.4f)
            reliefs.sortedBy { it.start }.forEach { r ->
                val xs = xOf(r.start)
                val xe = xOf(r.end ?: now)
                val inten = (r.intensity ?: 0).coerceIn(0, 10)
                val t = minStroke + (maxStroke - minStroke) * (inten / 10f)
                drawLine(Color(0xFF4CAF50), Offset(xs, yRel), Offset(xe, yRel), t)
                val mid = xs + (xe - xs) / 2f
                drawContext.canvas.nativeCanvas.drawText("🍃", mid - 12f, yRel - t / 2f - 6f, leafPaint)
            }

            val yMed = yOf(0.65f)
            meds.sortedBy { it.at }.forEach { med ->
                val x = xOf(med.at)
                drawContext.canvas.nativeCanvas.drawText("💊", x - 12f, yMed - 6f, pillPaint)
            }

            val yTrig = yOf(0.15f)
            triggers.sortedBy { it.at }.forEach { t ->
                val x = xOf(t.at)
                drawContext.canvas.nativeCanvas.drawText("⚡", x - 12f, yTrig - 6f, lightningPaint)
            }

            if (showDayTicks) {
                var d = ZonedDateTime.ofInstant(leftInstant, zoneId).truncatedTo(ChronoUnit.DAYS)
                val endDay = ZonedDateTime.ofInstant(rightInstant, zoneId).truncatedTo(ChronoUnit.DAYS).plusDays(1)
                while (!d.isAfter(endDay)) {
                    val xi = xOf(d.toInstant())
                    drawLine(Color.LightGray, Offset(xi, y1), Offset(xi, y1 - 10f))
                    drawContext.canvas.nativeCanvas.drawText(
                        DateTimeFormatter.ofPattern("MMM d").withZone(zoneId).format(d),
                        xi - 40f,
                        y1 + 28f,
                        axisTextPaint
                    )
                    d = d.plusDays(1)
                }
            }
        }

        popup?.let { hit ->
            val offsetX = (hit.x - 140f).roundToInt()
            val offsetY = (hit.y - 80f).roundToInt()
            Card(
                modifier = Modifier.offset { IntOffset(offsetX, offsetY) },
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA)),
                shape = RoundedCornerShape(8.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Text(
                    text = hit.label,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Black
                )
            }
        }
    }
}
