package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private val BAND_LABELS = mapOf(
    "working_well" to "Working well",
    "showing_progress" to "Showing progress",
    "some_effect" to "Some effect",
    "not_noticeable" to "Not noticeable yet",
    "not_enough_data" to "Not enough data"
)

private fun bandColor(band: String): Color = when (band) {
    "working_well" -> Color(0xFF6ED69E)
    "showing_progress" -> Color(0xFFFFB454)
    "some_effect" -> Color.White.copy(alpha = 0.62f)
    "not_noticeable" -> Color(0xFFE0492B)
    else -> Color.White.copy(alpha = 0.62f)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorTreatmentDetailScreen(navController: NavController, regimenId: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var regimen by remember { mutableStateOf<SupabaseDbService.TreatmentRegimenRow?>(null) }
    var efficacy by remember { mutableStateOf<SupabaseDbService.TreatmentEfficacyRow?>(null) }
    var confounders by remember { mutableStateOf<List<SupabaseDbService.TreatmentConfounderRow>>(emptyList()) }
    var triggerShift by remember { mutableStateOf<List<SupabaseDbService.TreatmentTriggerShiftRow>>(emptyList()) }
    var mmdSeries by remember { mutableStateOf<List<SupabaseDbService.TreatmentMmdSeriesPoint>>(emptyList()) }
    var leaderboard by remember { mutableStateOf<List<SupabaseDbService.TreatmentLeaderboardRow>>(emptyList()) }
    var sideEffects by remember { mutableStateOf<List<SupabaseDbService.TreatmentSideEffectLogRow>>(emptyList()) }
    var narrative by remember { mutableStateOf<String?>(null) }
    var narrativeLoading by remember { mutableStateOf(false) }
    var showAddSideEffect by remember { mutableStateOf(false) }
    var showStopConfirm by remember { mutableStateOf(false) }
    var showLinkSheet by remember { mutableStateOf(false) }
    var showConfounderConfig by remember { mutableStateOf(false) }
    var enabledConfounders by remember { mutableStateOf(loadEnabledConfounders(context)) }

    suspend fun reload() {
        withContext(Dispatchers.IO) {
            val token = SessionStore.getValidAccessToken(context) ?: return@withContext
            val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
            try {
                val allRegimens = db.getTreatmentRegimens(token)
                regimen = allRegimens.firstOrNull { it.id == regimenId }
            } catch (_: Throwable) { }
            efficacy = runCatching { db.getTreatmentEfficacy(token, regimenId) }.getOrNull()
            confounders = runCatching { db.getTreatmentConfounders(token, regimenId) }.getOrDefault(emptyList())
            triggerShift = runCatching { db.getTreatmentTriggerShift(token, regimenId) }.getOrDefault(emptyList())
            mmdSeries = runCatching { db.getTreatmentMmdSeries(token, regimenId) }.getOrDefault(emptyList())
            leaderboard = runCatching { db.getTreatmentLeaderboard(token) }.getOrDefault(emptyList())
            val r = regimen
            if (r != null) {
                val endDate = r.stopDate ?: LocalDate.now().toString()
                sideEffects = runCatching { db.getTreatmentSideEffectLogs(token, r.startDate, endDate) }.getOrDefault(emptyList())
            }
        }
    }

    suspend fun generateNarrative(force: Boolean = false) {
        narrativeLoading = true
        withContext(Dispatchers.IO) {
            val token = SessionStore.getValidAccessToken(context) ?: return@withContext
            val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
            val resp = runCatching { db.getTreatmentNarrative(token, regimenId, force) }.getOrNull()
            narrative = resp?.narrative ?: narrative
        }
        narrativeLoading = false
    }

    LaunchedEffect(regimenId) {
        reload()
        if (narrative == null) generateNarrative()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
            val r = regimen
            if (r != null) {
                val period = r.stopDate?.let { "${r.startDate} → $it" } ?: "since ${r.startDate}"
                val sub = listOfNotNull(
                    r.kind.replaceFirstChar { it.uppercase() },
                    r.amount, r.frequency, period
                ).joinToString(" · ")
                Text(sub, color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }

            HeadlineCard(efficacy)

            if (mmdSeries.isNotEmpty()) MmdChartCard(mmdSeries, efficacy?.baselineMmd)

            if (efficacy != null) SeverityDurationRow(efficacy!!)

            if (confounders.isNotEmpty()) ConfoundersCard(
                confounders = confounders.filter { it.metric in enabledConfounders },
                onCustomize = { showConfounderConfig = true },
                hasFilteredAll = confounders.isNotEmpty() && confounders.none { it.metric in enabledConfounders }
            )

            if (triggerShift.isNotEmpty()) TriggerShiftCard(triggerShift)

            SideEffectsCard(
                sideEffects = sideEffects,
                onAdd = { showAddSideEffect = true }
            )

            NarrativeCard(
                narrative = narrative,
                loading = narrativeLoading,
                onRegenerate = { scope.launch { generateNarrative(force = true) } }
            )

            if (leaderboard.size >= 2) LeaderboardCard(leaderboard, currentId = regimenId)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ActionButton(
                    if (regimen?.groupId == null) "Link →" else "Linked ✓",
                    modifier = Modifier.weight(1f),
                ) { showLinkSheet = true }
                ActionButton("Stop", modifier = Modifier.weight(1f), enabled = regimen?.stopDate == null) {
                    showStopConfirm = true
                }
                ActionButton("Delete", modifier = Modifier.weight(1f), color = Color(0xFFE0492B)) {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val token = SessionStore.getValidAccessToken(context)
                            if (token != null) {
                                val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                                runCatching { db.deleteTreatmentRegimen(token, regimenId) }
                            }
                        }
                        navController.popBackStack()
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        if (showAddSideEffect) {
            AddSideEffectDialog(
                onDismiss = { showAddSideEffect = false },
                onSaved = {
                    showAddSideEffect = false
                    scope.launch { reload() }
                }
            )
        }

        val r = regimen
        if (showLinkSheet && r != null) {
            LinkRegimenDialog(
                current = r,
                onDismiss = { showLinkSheet = false },
                onSaved = {
                    showLinkSheet = false
                    scope.launch { reload() }
                }
            )
        }

        if (showConfounderConfig) {
            ConfounderConfigDialog(
                enabled = enabledConfounders,
                onToggle = { key ->
                    val next = if (enabledConfounders.contains(key)) enabledConfounders - key else enabledConfounders + key
                    enabledConfounders = next
                    saveEnabledConfounders(context, next)
                },
                onDismiss = { showConfounderConfig = false }
            )
        }

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text("Stop this treatment?") },
            text = { Text("It will move to Past. You can still see the data.") },
            confirmButton = {
                TextButton(onClick = {
                    showStopConfirm = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val token = SessionStore.getValidAccessToken(context)
                            if (token != null) {
                                val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                                runCatching {
                                    db.updateTreatmentRegimen(token, regimenId, stopDate = LocalDate.now().toString())
                                }
                            }
                        }
                        navController.popBackStack()
                    }
                }) { Text("Stop now", color = Color(0xFFE0492B)) }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun HeadlineCard(e: SupabaseDbService.TreatmentEfficacyRow?) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF2A0C3C).copy(alpha = 0.78f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
            .padding(20.dp)
    ) {
        Text("MIGRAINE DAYS PER MONTH", color = Color.White.copy(alpha = 0.62f), letterSpacing = androidx.compose.ui.unit.TextUnit(0.5f, androidx.compose.ui.unit.TextUnitType.Sp), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(6.dp))
        if (e == null) {
            Text("Loading...", color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        val pct = e.pctChangeMmd
        val pctText = if (pct != null) String.format("%+.0f%%", pct) else "-"
        Text(pctText, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(2.dp))
        Text(BAND_LABELS[e.band] ?: "-", color = bandColor(e.band), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        val b = e.baselineMmd; val r = e.rollingMmd
        if (b != null && r != null && b > 0) {
            Text("You went from ${b.toInt()} to ${r.toInt()} migraine days a month, averaged since you started.",
                color = Color.White.copy(alpha = 0.86f), style = MaterialTheme.typography.bodyMedium)
        }
        val t = e.trailing4wMmd
        if (t != null) {
            Spacer(Modifier.height(2.dp))
            Text("Last 4 weeks: ${t.toInt()} days",
                color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.height(16.dp))
        ClinicalBandScale(pct = e.pctChangeMmd, hasData = e.band != "not_enough_data")
        Spacer(Modifier.height(12.dp))

        val trustNote = if (e.rampComplete) " · enough data to trust this" else " · still in 8-week ramp"
        Text("${e.weeksActive} weeks in · ${e.nAttacksRolling} attacks tracked$trustNote",
            color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ClinicalBandScale(pct: Double?, hasData: Boolean) {
    val markerIndex: Int? = when {
        !hasData || pct == null -> null
        pct > -10 -> 0
        pct > -30 -> 1
        pct > -50 -> 2
        else -> 3
    }
    val segColors = listOf(
        Color(0xFFE0492B).copy(alpha = 0.30f),
        Color.White.copy(alpha = 0.14f),
        Color(0xFFFFB454).copy(alpha = 0.40f),
        Color(0xFF6ED69E).copy(alpha = 0.45f),
    )
    val labels = listOf("Not noticeable", "Some effect", "Showing progress", "Working well")
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            segColors.forEachIndexed { i, c ->
                Box(
                    modifier = Modifier.weight(1f).height(5.dp)
                        .background(c, RoundedCornerShape(
                            topStart = if (i == 0) 3.dp else 0.dp,
                            bottomStart = if (i == 0) 3.dp else 0.dp,
                            topEnd = if (i == 3) 3.dp else 0.dp,
                            bottomEnd = if (i == 3) 3.dp else 0.dp,
                        ))
                ) {
                    if (markerIndex == i) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = (-5).dp)
                                .width(2.dp).height(15.dp)
                                .background(Color(0xFFDCCEFF))
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.forEach { l ->
                Text(l, color = Color.White.copy(alpha = 0.60f),
                    style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable
private fun MmdChartCard(series: List<SupabaseDbService.TreatmentMmdSeriesPoint>, baseline: Double?) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF2A0C3C).copy(alpha = 0.65f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Monthly migraine days", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text("last ${series.size} wks", color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(10.dp))
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxWidth().height(170.dp)) {
            val w = size.width
            val h = size.height
            val topPad = 22f; val bottomPad = 22f
            val plotH = h - topPad - bottomPad

            val maxMmd = maxOf(2.0, (series.maxOfOrNull { it.mmd } ?: 2).toDouble(), baseline ?: 0.0) * 1.15
            fun yFor(v: Double): Float = topPad + plotH - (v / maxMmd).toFloat() * plotH
            fun xFor(i: Int): Float = if (series.size <= 1) 0f
                else 6f + (w - 12f) * i / (series.size - 1).toFloat()

            // Subtle grid (3 horizontal gridlines)
            for (g in 1..3) {
                val y = topPad + plotH * g / 4f
                drawLine(Color.White.copy(alpha = 0.05f),
                    start = androidx.compose.ui.geometry.Offset(0f, y),
                    end = androidx.compose.ui.geometry.Offset(w, y),
                    strokeWidth = 1f)
            }

            if (baseline != null) {
                drawLine(Color.White.copy(alpha = 0.40f),
                    start = androidx.compose.ui.geometry.Offset(0f, yFor(baseline)),
                    end = androidx.compose.ui.geometry.Offset(w, yFor(baseline)),
                    strokeWidth = 1.5f,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
                drawLine(Color(0xFF6ED69E).copy(alpha = 0.60f),
                    start = androidx.compose.ui.geometry.Offset(0f, yFor(baseline / 2)),
                    end = androidx.compose.ui.geometry.Offset(w, yFor(baseline / 2)),
                    strokeWidth = 1.5f,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
            }

            val startIdx = series.indexOfFirst { it.weeksFromStart == 0 }
            if (startIdx >= 0) {
                drawLine(Color(0xFFB97BFF).copy(alpha = 0.55f),
                    start = androidx.compose.ui.geometry.Offset(xFor(startIdx), topPad - 4f),
                    end = androidx.compose.ui.geometry.Offset(xFor(startIdx), h - bottomPad + 4f),
                    strokeWidth = 1.5f,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3f, 4f)))
            }

            if (series.size >= 2) {
                // Smooth path with cubic interpolation for visual softness
                val linePath = androidx.compose.ui.graphics.Path().apply {
                    moveTo(xFor(0), yFor(series[0].mmd.toDouble()))
                    for (i in 1 until series.size) {
                        val px = xFor(i - 1); val py = yFor(series[i - 1].mmd.toDouble())
                        val cx = xFor(i); val cy = yFor(series[i].mmd.toDouble())
                        val mx = (px + cx) / 2f
                        cubicTo(mx, py, mx, cy, cx, cy)
                    }
                }
                // Area fill under the line (subtle vertical gradient)
                val areaPath = androidx.compose.ui.graphics.Path().apply {
                    addPath(linePath)
                    lineTo(xFor(series.size - 1), h - bottomPad)
                    lineTo(xFor(0), h - bottomPad)
                    close()
                }
                drawPath(areaPath, brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(Color(0xFFB97BFF).copy(alpha = 0.30f), Color(0xFFB97BFF).copy(alpha = 0.0f)),
                    startY = topPad, endY = h - bottomPad
                ))
                drawPath(linePath, color = Color(0xFFB97BFF),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 3f, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round))
                // Dots: outer glow + solid
                series.forEachIndexed { i, p ->
                    val c = androidx.compose.ui.geometry.Offset(xFor(i), yFor(p.mmd.toDouble()))
                    drawCircle(Color(0xFFB97BFF).copy(alpha = 0.30f), radius = 6f, center = c)
                    drawCircle(Color(0xFFB97BFF), radius = 3.5f, center = c)
                    drawCircle(Color(0xFF1A0028), radius = 1.5f, center = c)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendDot(Color(0xFFB97BFF), "migraine days/week")
            LegendDot(Color.White.copy(alpha = 0.4f), "baseline")
            LegendDot(Color(0xFF6ED69E), "working-well line")
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(7.dp)) { drawCircle(color) }
        Spacer(Modifier.width(4.dp))
        Text(label, color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SeverityDurationRow(e: SupabaseDbService.TreatmentEfficacyRow) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MiniMetric(label = "mean severity", rolling = e.rollingSeverity, baseline = e.baselineSeverity, unit = " / 10", modifier = Modifier.weight(1f))
        MiniMetric(label = "mean duration", rolling = e.rollingDurationH, baseline = e.baselineDurationH, unit = "h", modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MiniMetric(label: String, rolling: Double?, baseline: Double?, unit: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF2A0C3C).copy(alpha = 0.65f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(12.dp)
    ) {
        Text(label, color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.labelSmall)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(rolling?.let { String.format("%.1f%s", it, unit) } ?: "-",
                color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            if (rolling != null && baseline != null && baseline > 0) {
                val pct = (rolling - baseline) / baseline * 100
                Spacer(Modifier.width(6.dp))
                Text(String.format("%+.0f%%", pct),
                    color = if (pct < 0) Color(0xFF6ED69E) else Color(0xFFE0492B),
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            }
        }
        baseline?.let {
            Text("was ${String.format("%.1f%s", it, unit)}", color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TriggerShiftCard(shifts: List<SupabaseDbService.TreatmentTriggerShiftRow>) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF2A0C3C).copy(alpha = 0.65f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Text("Trigger profile shift", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(4.dp))
        shifts.forEach { t ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t.triggerType.replaceFirstChar { it.uppercase() },
                    color = Color.White.copy(alpha = 0.86f), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                val (label, color) = triggerChangeLabel(t)
                Text(label, color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            }
            Divider(color = Color.White.copy(alpha = 0.08f))
        }
    }
}

private fun triggerChangeLabel(t: SupabaseDbService.TreatmentTriggerShiftRow): Pair<String, Color> {
    val change = t.rankChange
    if (change == null) {
        return when {
            t.rollingRank != null && t.baselineRank == null -> "new" to Color(0xFFE0492B)
            t.rollingRank == null && t.baselineRank != null -> "gone" to Color(0xFF6ED69E)
            else -> "-" to Color.White.copy(alpha = 0.62f)
        }
    }
    return when {
        change == 0 -> "no change" to Color.White.copy(alpha = 0.62f)
        change > 0 -> "down $change rank${if (change == 1) "" else "s"}" to Color(0xFF6ED69E)
        else -> "up ${-change} rank${if (-change == 1) "" else "s"}" to Color(0xFFE0492B)
    }
}

@Composable
private fun LeaderboardCard(rows: List<SupabaseDbService.TreatmentLeaderboardRow>, currentId: String) {
    val sorted = rows.sortedBy { it.pctChangeMmd ?: Double.POSITIVE_INFINITY }
    val maxPct = maxOf(50.0, sorted.mapNotNull { it.pctChangeMmd }.map { Math.abs(it) }.maxOrNull() ?: 50.0)
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF2A0C3C).copy(alpha = 0.65f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Compared to other treatments", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text("% MMD change", color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(4.dp))
        sorted.forEach { row ->
            LeaderboardRow(row, maxPct, isCurrent = row.regimenId == currentId)
            if (row.regimenId != sorted.last().regimenId) Divider(color = Color.White.copy(alpha = 0.08f))
        }
    }
}

@Composable
private fun LeaderboardRow(row: SupabaseDbService.TreatmentLeaderboardRow, maxPct: Double, isCurrent: Boolean) {
    val pct = row.pctChangeMmd
    val widthRatio = pct?.let { minOf(1.0, Math.abs(it) / maxPct) } ?: 0.0
    val color = when (row.band) {
        "working_well" -> Color(0xFF6ED69E)
        "showing_progress" -> Color(0xFFFFB454)
        "some_effect" -> Color.White.copy(alpha = 0.62f)
        "not_noticeable" -> Color(0xFFE0492B).copy(alpha = 0.7f)
        else -> Color.White.copy(alpha = 0.62f)
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                if (isCurrent) {
                    Spacer(Modifier.width(4.dp))
                    Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFFB97BFF).copy(alpha = 0.20f)) {
                        Text("active", color = Color(0xFFB97BFF), modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            val sub = listOfNotNull(row.kind, row.amount, row.frequency).joinToString(" · ")
            Text(sub, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
        }
        Box(modifier = Modifier.width(60.dp).height(6.dp).background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(3.dp))) {
            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(widthRatio.toFloat()).background(color, RoundedCornerShape(3.dp)))
        }
        Spacer(Modifier.width(8.dp))
        Text(pct?.let { String.format("%+.0f%%", it) } ?: "N/A",
            color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(48.dp))
    }
}

@Composable
private fun ConfoundersCard(
    confounders: List<SupabaseDbService.TreatmentConfounderRow>,
    onCustomize: () -> Unit,
    hasFilteredAll: Boolean = false
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF2A0C3C).copy(alpha = 0.65f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("What else changed", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onCustomize, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.Tune, contentDescription = "Customize", tint = Color(0xFFB97BFF))
            }
        }
        Spacer(Modifier.height(4.dp))
        if (confounders.isEmpty()) {
            Text(
                if (hasFilteredAll) "Tap the slider to pick what you want to see."
                else "No data yet for the metrics you picked.",
                color = Color.White.copy(alpha = 0.62f),
                style = MaterialTheme.typography.bodySmall
            )
            return@Column
        }
        // 2-column grid matching iOS LazyVGrid layout
        val pairs = confounders.chunked(2)
        pairs.forEach { rowPair ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowPair.forEach { c ->
                    val label = when (c.direction) {
                        "up"   -> c.pctChange?.let { String.format("+%.0f%%", it) } ?: "up"
                        "down" -> c.pctChange?.let { String.format("%.0f%%", it) } ?: "down"
                        "stable" -> "stable"
                        "concurrent" -> {
                            val n = (c.rollingValue ?: 0.0).toInt()
                            if (n == 1) "1 added" else "$n added"
                        }
                        else -> "-"
                    }
                    val color = when (c.direction) {
                        "up" -> Color(0xFF6ED69E)
                        "down" -> Color(0xFFFFB454)
                        "concurrent" -> Color(0xFFFFB454)
                        else -> Color.White.copy(alpha = 0.62f)
                    }
                    Row(
                        modifier = Modifier.weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(prettyMetric(c.metric), color = Color.White.copy(alpha = 0.86f),
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        Text(label, color = color, fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                // If odd count, pad the missing cell so the last row aligns with the grid
                if (rowPair.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Correlations during the window, not effects of the drug.",
            color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
    }
}

private fun prettyMetric(m: String): String = when (m) {
    "sleep_hours" -> "Sleep"
    "hrv" -> "HRV"
    "resting_hr" -> "Resting HR"
    else -> m
}

private val CONFOUNDER_METRICS = listOf(
    "sleep_hours" to "Sleep",
    "hrv" to "HRV",
    "resting_hr" to "Resting HR"
)
private const val CONFOUNDER_PREFS = "treatment_confounder_prefs"
private const val CONFOUNDER_KEY = "enabled_v1"

private fun loadEnabledConfounders(context: android.content.Context): Set<String> {
    val sp = context.getSharedPreferences(CONFOUNDER_PREFS, android.content.Context.MODE_PRIVATE)
    return sp.getStringSet(CONFOUNDER_KEY, null) ?: CONFOUNDER_METRICS.map { it.first }.toSet()
}
private fun saveEnabledConfounders(context: android.content.Context, keys: Set<String>) {
    val sp = context.getSharedPreferences(CONFOUNDER_PREFS, android.content.Context.MODE_PRIVATE)
    sp.edit().putStringSet(CONFOUNDER_KEY, keys).apply()
}

@Composable
private fun SideEffectsCard(
    sideEffects: List<SupabaseDbService.TreatmentSideEffectLogRow>,
    onAdd: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF2A0C3C).copy(alpha = 0.65f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Side effects", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = Color(0xFFB97BFF).copy(alpha = 0.15f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFB97BFF).copy(alpha = 0.35f)),
                onClick = onAdd
            ) {
                Text("+ note", color = Color(0xFFB97BFF), fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(6.dp))
        if (sideEffects.isEmpty()) {
            Text("None logged yet for this regimen.",
                color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodySmall)
        } else {
            sideEffects.forEach { s ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    if (s.selectedSymptoms.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            s.selectedSymptoms.take(6).forEach { p ->
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = Color(0xFFB97BFF).copy(alpha = 0.22f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFB97BFF))
                                ) {
                                    Text(p, color = Color(0xFFDCCEFF), fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    if (!s.notes.isNullOrBlank()) {
                        Text("\"${s.notes}\"", color = Color.White.copy(alpha = 0.86f),
                            fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(s.logDate, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
                }
                Divider(color = Color.White.copy(alpha = 0.08f))
            }
        }
    }
}

@Composable
private fun NarrativeCard(narrative: String?, loading: Boolean, onRegenerate: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF2A0C3C).copy(alpha = 0.65f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Summary", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            if (loading) CircularProgressIndicator(color = Color(0xFFB97BFF), modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(8.dp))
        Text(narrative ?: if (loading) "Generating..." else "Tap regenerate to build your summary.",
            color = Color.White.copy(alpha = 0.86f), style = MaterialTheme.typography.bodyMedium, lineHeight = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp))
        Spacer(Modifier.height(10.dp))
        Surface(
            shape = RoundedCornerShape(9.dp),
            color = Color.White.copy(alpha = 0.05f),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            onClick = { if (!loading) onRegenerate() }
        ) {
            Text("Regenerate", color = Color.White,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ActionButton(label: String, modifier: Modifier = Modifier, color: Color = Color.White, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.05f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
    ) {
        Text(label, color = if (enabled) color else color.copy(alpha = 0.4f), fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(vertical = 11.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LinkRegimenDialog(
    current: SupabaseDbService.TreatmentRegimenRow,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var allRows by remember { mutableStateOf<List<SupabaseDbService.TreatmentRegimenRow>>(emptyList()) }
    val linked = remember { mutableStateListOf<String>() }
    val selected = remember { mutableStateListOf<String>() }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        loading = true
        val rows = withContext(Dispatchers.IO) {
            try {
                val token = SessionStore.getValidAccessToken(context) ?: return@withContext emptyList()
                val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                db.getTreatmentRegimens(token)
            } catch (_: Throwable) { emptyList() }
        }
        allRows = rows
        val cg = current.groupId
        val pre = if (cg != null) rows.filter { it.groupId == cg && it.id != current.id }.map { it.id } else emptyList()
        linked.clear(); linked.addAll(pre)
        selected.clear(); selected.addAll(pre)
        loading = false
    }

    fun toggle(id: String) {
        if (selected.contains(id)) selected.remove(id) else selected.add(id)
    }

    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            withContext(Dispatchers.IO) {
                val token = SessionStore.getValidAccessToken(context) ?: return@withContext
                val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                val newGroup: String? = if (selected.isEmpty()) null
                    else current.groupId ?: java.util.UUID.randomUUID().toString()
                // removed
                linked.subtract(selected.toSet()).forEach { rid ->
                    runCatching { db.setTreatmentRegimenGroupId(token, rid, null) }
                }
                // added
                selected.subtract(linked.toSet()).forEach { rid ->
                    runCatching { db.setTreatmentRegimenGroupId(token, rid, newGroup) }
                }
                // current
                runCatching { db.setTreatmentRegimenGroupId(token, current.id, newGroup) }
            }
            saving = false
            onSaved()
        }
    }

    val candidates = allRows.filter { it.id != current.id }
    val suggested = candidates.filter { it.name.equals(current.name, ignoreCase = true) }
    val others = candidates.filter { !it.name.equals(current.name, ignoreCase = true) }
    val confirmLabel = if (selected.isEmpty()) "Unlink"
        else "Link ${selected.size} treatment${if (selected.size == 1) "" else "s"}"

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = Color(0xFF2A0C3C),
        title = { Text("Link treatments", color = Color(0xFFDCCEFF)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())
            ) {
                Text("Group regimens together (e.g. titration of the same drug) so the chart spans the journey.",
                    color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFFB97BFF))
                    }
                } else if (candidates.isEmpty()) {
                    Text("No other treatments to link to.", color = Color.White.copy(alpha = 0.62f))
                } else {
                    if (suggested.isNotEmpty()) {
                        Text("Suggested · same name", color = Color(0xFFDCCEFF), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                        suggested.forEach { r ->
                            LinkRow(r = r, isSelected = selected.contains(r.id), onClick = { toggle(r.id) })
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (others.isNotEmpty()) {
                        Text("Other treatments", color = Color(0xFFDCCEFF), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                        others.forEach { r ->
                            LinkRow(r = r, isSelected = selected.contains(r.id), onClick = { toggle(r.id) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }, enabled = !saving) {
                Text(if (saving) "Saving..." else confirmLabel, color = Color(0xFFB97BFF))
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!saving) onDismiss() }) {
                Text("Cancel", color = Color.White.copy(alpha = 0.82f))
            }
        }
    )
}

@Composable
private fun ConfounderConfigDialog(
    enabled: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF2A0C3C),
        title = { Text("Customize 'What else changed'", color = Color(0xFFDCCEFF)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Each metric is compared between the 4 weeks before the regimen started and the most recent 4 weeks. Pick the ones that matter to you.",
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                CONFOUNDER_METRICS.forEach { (key, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { onToggle(key) }.padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (enabled.contains(key)) "●" else "○",
                            color = if (enabled.contains(key)) Color(0xFFB97BFF) else Color.White.copy(alpha = 0.40f),
                            fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp)
                        )
                        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = Color(0xFFB97BFF)) } }
    )
}

@Composable
private fun LinkRow(r: SupabaseDbService.TreatmentRegimenRow, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF2A0C3C).copy(alpha = 0.65f))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (isSelected) Icons.Outlined.MedicalServices else Icons.Outlined.MedicalServices,
            contentDescription = null,
            tint = if (isSelected) Color(0xFFB97BFF) else Color.White.copy(alpha = 0.40f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(r.name, color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            val dose = listOfNotNull(r.amount, r.frequency).joinToString(" · ")
            val period = r.stopDate?.let { "${r.startDate} → $it" } ?: "since ${r.startDate}"
            Text(listOfNotNull(dose.ifBlank { null }, period).joinToString(" · "),
                color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodySmall)
        }
        Text(if (isSelected) "✓" else " ",
            color = if (isSelected) Color(0xFFB97BFF) else Color.Transparent,
            fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(4.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddSideEffectDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selected = remember { mutableStateListOf<String>() }
    var notes by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    val speechLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                notes = if (notes.isBlank()) spoken else "$notes $spoken"
            }
        }
    }
    fun launchVoice() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Describe the side effect")
        }
        try { speechLauncher.launch(intent) } catch (_: Exception) {
            android.widget.Toast.makeText(context, "Voice input not available", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun save() {
        if (saving) return
        if (selected.isEmpty() && notes.isBlank()) return
        saving = true
        scope.launch {
            withContext(Dispatchers.IO) {
                val token = SessionStore.getValidAccessToken(context) ?: return@withContext
                val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                runCatching {
                    db.insertTreatmentSideEffectLog(
                        token,
                        logDate = LocalDate.now().toString(),
                        selectedSymptoms = selected.toList(),
                        notes = if (notes.isBlank()) null else notes,
                        source = "manual"
                    )
                }
            }
            saving = false
            onSaved()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = Color(0xFF2A0C3C),
        title = { Text("Note a side effect", color = Color(0xFFDCCEFF)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (TREATMENT_SIDE_EFFECT_POOL + "+ other").forEach { label ->
                        val isSel = selected.contains(label)
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (isSel) Color(0xFFB97BFF).copy(alpha = 0.22f) else Color.White.copy(alpha = 0.04f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isSel) Color(0xFFB97BFF) else Color.White.copy(alpha = 0.10f)),
                            onClick = {
                                if (isSel) selected.remove(label) else selected.add(label)
                            }
                        ) {
                            Text(label,
                                color = if (isSel) Color(0xFFDCCEFF) else Color.White.copy(alpha = 0.86f),
                                fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Extra notes (optional)") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp)
                )
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFB97BFF).copy(alpha = 0.20f),
                    onClick = { launchVoice() }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Icon(Icons.Outlined.Mic, contentDescription = null,
                            tint = Color(0xFFB97BFF), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Voice", color = Color(0xFFB97BFF), fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }, enabled = !saving && (selected.isNotEmpty() || notes.isNotBlank())) {
                Text(if (saving) "Saving..." else "Save", color = Color(0xFFB97BFF))
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!saving) onDismiss() }) { Text("Cancel", color = Color.White.copy(alpha = 0.82f)) }
        }
    )
}
