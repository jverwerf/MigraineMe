// app/src/main/java/com/migraineme/GraphAttachmentPicker.kt
package com.migraineme

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun GraphAttachmentPicker(
    accessToken: String,
    insightsVm: InsightsViewModel,
    onAttach: (JsonObject) -> Unit,
    onDismiss: () -> Unit
) {
    // ── Premium check ──
    val premiumState by PremiumManager.state.collectAsState()

    // ── Tab state: 0 = Insights, 1 = Risk ──
    var selectedTab by remember { mutableStateOf(0) }

    // ── UI ──
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer),
        border = AppTheme.BaseCardBorder
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Attach graph", color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = AppTheme.SubtleTextColor, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(8.dp))

            if (!premiumState.isPremium) {
                PremiumGate(
                    message = "Share your migraine graphs",
                    subtitle = "Attach detailed graphs to community posts",
                    onUpgrade = onDismiss
                ) {}
            } else {
                // ── Graph type toggle ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("Insights", "Risk").forEachIndexed { idx, label ->
                        val isSel = selectedTab == idx
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) AppTheme.AccentPurple.copy(alpha = 0.25f) else Color.Transparent)
                                .clickable { selectedTab = idx }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                color = if (isSel) AppTheme.AccentPurple else AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                when (selectedTab) {
                    0 -> InsightsGraphTab(accessToken, insightsVm, onAttach)
                    1 -> RiskGraphTab(accessToken, onAttach)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// TAB 0: Insights Timeline Graph (existing functionality)
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun InsightsGraphTab(
    accessToken: String,
    insightsVm: InsightsViewModel,
    onAttach: (JsonObject) -> Unit
) {
    val ctx: Context = LocalContext.current.applicationContext
    LaunchedEffect(accessToken) {
        if (accessToken.isNotBlank()) insightsVm.load(ctx, accessToken)
    }

    val migraines by insightsVm.migraines.collectAsState()
    val allDailyMetrics by insightsVm.allDailyMetrics.collectAsState()
    val linkedItems by insightsVm.selectedLinkedItems.collectAsState()
    val linkedLoading by insightsVm.linkedItemsLoading.collectAsState()
    val zone = ZoneId.systemDefault()
    val sorted = remember(migraines) { migraines.sortedByDescending { it.start } }
    val selIdx by insightsVm.selectedMigraineIndex.collectAsState()
    LaunchedEffect(sorted.size) { if (selIdx >= sorted.size && sorted.isNotEmpty()) insightsVm.selectMigraine(0) }
    val sel = sorted.getOrNull(selIdx)
    LaunchedEffect(sel?.id) { sel?.id?.let { insightsVm.loadLinkedItems(it) } }

    // Window days
    val wBefore by insightsVm.windowDaysBefore.collectAsState()
    val wAfter by insightsVm.windowDaysAfter.collectAsState()
    val wStart = sel?.start?.minus(Duration.ofDays(wBefore))
    val wEnd = (sel?.end ?: sel?.start)?.plus(Duration.ofDays(wAfter))
    val windowDates = remember(wStart, wEnd) {
        if (wStart == null || wEnd == null) emptySet()
        else {
            val f = LocalDate.ofInstant(wStart, zone)
            val t = LocalDate.ofInstant(wEnd, zone)
            generateSequence(f) { it.plusDays(1) }.takeWhile { !it.isAfter(t) }.map { it.toString() }.toSet()
        }
    }

    val allMissed by insightsVm.allMissedActivities.collectAsState()
    val allActs by insightsVm.allActivities.collectAsState()

    // Events from linked items
    val windowEvents = remember(linkedItems, allMissed, allActs, sel?.id) {
        val actsForMigraine = allActs.filter { it.migraineId == sel?.id }
        val missedForMigraine = allMissed.filter { it.migraineId == sel?.id }
        buildEventMarkers(linkedItems, actsForMigraine, missedForMigraine)
    }

    // Auto-select metrics
    val templateMap by insightsVm.labelToMetricMap.collectAsState()
    val autoSelectedKeys = remember(windowEvents, templateMap) {
        windowEvents
            .filter { it.isAutomated }
            .mapNotNull { ev -> insightsVm.labelToMetricKey(ev.name) }
            .toSet()
    }

    val available = remember(allDailyMetrics, windowDates) {
        AllMetricDefs.filter { d ->
            allDailyMetrics[d.key]?.any { it.date in windowDates } == true
        }
    }

    val userToggledKeys by insightsVm.userToggledMetrics.collectAsState()
    val userDisabledKeysTop by insightsVm.userDisabledMetrics.collectAsState()
    val enabledKeys = (autoSelectedKeys - userDisabledKeysTop) + userToggledKeys

    val enabledSeries = remember(available, enabledKeys, allDailyMetrics, windowDates) {
        available.filter { it.key in enabledKeys }.map { d ->
            MetricSeries(d.key, d.label, d.unit, d.color,
                allDailyMetrics[d.key]!!
                    .filter { it.date in windowDates }
                    .map { DailyMetricPoint(it.date, it.value) })
        }
    }

    val windowMigs = remember(migraines, wStart, wEnd) {
        if (wStart == null || wEnd == null) listOfNotNull(sel)
        else migraines.filter { m ->
            val e = m.end ?: m.start
            !m.start.isAfter(wEnd) && !e.isBefore(wStart)
        }
    }

    if (sorted.isEmpty()) {
        Text("No migraines logged yet", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
    } else {
        // ── Migraine selector ──
        PickerMigraineSelector(sorted, selIdx, sel,
            onPrev = { if (selIdx < sorted.size - 1) insightsVm.selectMigraine(selIdx + 1) },
            onNext = { if (selIdx > 0) insightsVm.selectMigraine(selIdx - 1) }
        )

        Spacer(Modifier.height(8.dp))

        if (linkedLoading) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppTheme.AccentPurple)
            }
        } else {
            // Event category chips
            val catCounts = remember(windowEvents) {
                windowEvents.groupBy { it.category }.mapValues { it.value.size }
            }
            if (catCounts.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    catCounts.forEach { (c, n) ->
                        PickerChip(n, c, EventCategoryColors[c] ?: AppTheme.AccentPurple)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Graph ──
        InsightsTimelineGraph(
            windowMigs, windowEvents, enabledSeries,
            wStart, wEnd, sel?.start,
            Modifier.fillMaxWidth().height(180.dp)
        )

        Spacer(Modifier.height(4.dp))

        // Window days control
        WindowDaysControl(wBefore, wAfter, onChanged = { b, a -> insightsVm.setWindowDays(b, a) })

        // Metric picker chips
        if (available.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Overlay Metrics", color = AppTheme.TitleColor,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(2.dp))
            Text("Tap to toggle. \u26A1 = auto-detected.",
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(6.dp))

            val userDisabledKeys by insightsVm.userDisabledMetrics.collectAsState()
            val effectiveEnabled = (autoSelectedKeys - userDisabledKeys) + userToggledKeys

            val groups = available.groupBy { it.group }
            groups.forEach { (group, defs) ->
                Text(group, color = AppTheme.SubtleTextColor.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    defs.forEach { d ->
                        val isAuto = d.key in autoSelectedKeys
                        val isEnabled = d.key in effectiveEnabled
                        PickerMetricToggle(
                            label = d.label,
                            color = d.color,
                            active = isEnabled,
                            isAutoSelected = isAuto,
                            onClick = { insightsVm.toggleMetric(d.key, isEnabled) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Attach button ──
        Button(
            onClick = {
                if (wStart != null && wEnd != null) {
                    val json = serializeInsightsGraphToJson(wStart, wEnd, windowMigs, windowEvents, enabledSeries)
                    onAttach(json)
                }
            },
            enabled = wStart != null && wEnd != null && (windowMigs.isNotEmpty() || windowEvents.isNotEmpty() || enabledSeries.isNotEmpty()),
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
        ) {
            Text("Attach to comment", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// TAB 1: Risk Graph
// ═══════════════════════════════════════════════════════════════════

private data class RiskPickerDay(val date: String, val score: Float, val zone: String)
private data class RiskMetricChip(val key: String, val label: String, val category: String, val color: Color)
private data class RiskMetricLine(val key: String, val label: String, val color: Color, val points: Map<String, Float>)

private fun pickerCatColor(category: String): Color = when (category) {
    "Sleep" -> Color(0xFF7E57C2); "Weather" -> Color(0xFF4FC3F7); "Physical" -> Color(0xFF81C784)
    "Mental" -> Color(0xFFBA68C8); "Nutrition" -> Color(0xFFFFB74D); else -> Color(0xFF999999)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RiskGraphTab(
    accessToken: String,
    onAttach: (JsonObject) -> Unit
) {
    val ctx = LocalContext.current.applicationContext
    var riskDays by remember { mutableStateOf<List<RiskPickerDay>>(emptyList()) }
    var metricLines by remember { mutableStateOf<List<RiskMetricLine>>(emptyList()) }
    var chips by remember { mutableStateOf<List<RiskMetricChip>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedDays by remember { mutableStateOf(14) }
    var selectedMetrics by remember { mutableStateOf<Set<String>>(setOf("risk:score")) }

    LaunchedEffect(accessToken) {
        isLoading = true
        withContext(Dispatchers.IO) {
            try {
                val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                val rows = db.getRiskScoreDaily(accessToken, 30)
                riskDays = rows.map { RiskPickerDay(it.date, it.score.toFloat(), it.zone) }
                    .sortedBy { it.date }

                // Load favourite metrics for overlay chips
                val favPool = buildFavoritesPool(ctx)
                val effectiveFavs = getEffectiveFavOfFavs(ctx)
                val allEntries = (effectiveFavs + favPool).distinctBy { it.key }

                // Build chips
                val chipList = mutableListOf(RiskMetricChip("risk:score", "Risk Score", "Risk", Color(0xFFEF5350)))
                allEntries.forEach { e ->
                    chipList.add(RiskMetricChip(e.key, e.label, e.category, pickerCatColor(e.category)))
                }
                chips = chipList

                // Fetch metric data for favourite keys
                val userId = SessionStore.readUserId(ctx) ?: ""
                val token = SessionStore.getValidAccessToken(ctx) ?: accessToken
                val tableColumns = mutableMapOf<String, MutableSet<String>>()
                val tableColChipKey = mutableMapOf<String, String>()
                for (e in allEntries) {
                    val entry = SupabaseDbService.TABLE_COL_TO_CHIP_KEY.entries.find { it.value == e.key } ?: continue
                    val (table, col) = entry.key.split("|", limit = 2)
                    tableColumns.getOrPut(table) { mutableSetOf() }.add(col)
                    tableColChipKey["$table|$col"] = e.key
                }

                val restClient = okhttp3.OkHttpClient()
                val metricData = mutableMapOf<String, MutableMap<String, Float>>() // chipKey → (date → value)

                for ((table, columns) in tableColumns) {
                    try {
                        val selectCols = (listOf("date") + columns).joinToString(",")
                        val url = "${BuildConfig.SUPABASE_URL}/rest/v1/$table?user_id=eq.$userId&select=$selectCols&order=date.desc&limit=44"
                        val req = okhttp3.Request.Builder().url(url).get()
                            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                            .addHeader("Authorization", "Bearer $token").build()
                        val resp = restClient.newCall(req).execute()
                        val body = resp.body?.string()
                        if (resp.isSuccessful && !body.isNullOrBlank()) {
                            val arr = org.json.JSONArray(body)
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                val d = obj.optString("date", "")
                                if (d.isBlank()) continue
                                for (col in columns) {
                                    val v = obj.optDouble(col)
                                    if (!v.isNaN()) {
                                        val chipKey = tableColChipKey["$table|$col"] ?: continue
                                        metricData.getOrPut(chipKey) { mutableMapOf() }[d] = v.toFloat()
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                metricLines = metricData.map { (key, pts) ->
                    val chip = chipList.find { it.key == key }
                    RiskMetricLine(key, chip?.label ?: key, chip?.color ?: Color.Gray, pts)
                }
            } catch (_: Exception) {}
        }
        isLoading = false
    }

    val displayDays = remember(riskDays, selectedDays) {
        riskDays.takeLast(selectedDays)
    }

    // Filter metric lines to selected + visible date range
    val displayMetricLines = remember(metricLines, selectedMetrics, displayDays) {
        if (displayDays.isEmpty()) emptyList()
        else {
            val dateRange = displayDays.map { it.date }.toSet()
            metricLines.filter { it.key in selectedMetrics }
                .map { line -> line.copy(points = line.points.filterKeys { it in dateRange }) }
                .filter { it.points.isNotEmpty() }
        }
    }

    if (isLoading) {
        Row(Modifier.fillMaxWidth().height(180.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), AppTheme.AccentPurple, strokeWidth = 2.dp)
        }
    } else if (riskDays.isEmpty()) {
        Text("No risk data yet", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
    } else {
        // ── Day range selector ──
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Range:", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            listOf(7, 14, 30).forEach { d ->
                val isSel = selectedDays == d
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSel) AppTheme.AccentPurple.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.04f))
                        .clickable { selectedDays = d }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text("${d}d", color = if (isSel) AppTheme.AccentPurple else AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium))
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Date range label ──
        if (displayDays.isNotEmpty()) {
            val fmt = DateTimeFormatter.ofPattern("d MMM")
            val from = LocalDate.parse(displayDays.first().date).format(fmt)
            val to = LocalDate.parse(displayDays.last().date).format(fmt)
            Text("$from \u2013 $to", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
        }

        // ── Risk graph preview with metric overlays ──
        RiskGraphCanvasMulti(
            days = displayDays.map { RiskDayPoint(it.date, it.score, it.zone) },
            metricLines = displayMetricLines,
            showRiskLine = "risk:score" in selectedMetrics,
            modifier = Modifier.fillMaxWidth().height(180.dp)
        )

        Spacer(Modifier.height(8.dp))

        // ── Metric chips ──
        if (chips.size > 1) {
            Text("Overlay Metrics", color = AppTheme.TitleColor,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(6.dp))

            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                chips.forEach { chip ->
                    val sel = chip.key in selectedMetrics
                    FilterChip(
                        selected = sel,
                        onClick = {
                            selectedMetrics = if (sel) selectedMetrics - chip.key else selectedMetrics + chip.key
                        },
                        label = { Text(chip.label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = chip.color.copy(alpha = 0.3f),
                            selectedLabelColor = chip.color,
                            containerColor = AppTheme.BaseCardContainer,
                            labelColor = AppTheme.SubtleTextColor
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = if (sel) chip.color else AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                            selectedBorderColor = chip.color,
                            enabled = true,
                            selected = sel
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Attach button ──
        Button(
            onClick = {
                val json = serializeRiskGraphToJson(displayDays, displayMetricLines)
                onAttach(json)
            },
            enabled = displayDays.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
        ) {
            Text("Attach to comment", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

// ── Risk graph preview canvas (reused for both picker and attached card rendering) ──

val RISK_GRAPH_HIGH_T = 10f
val RISK_GRAPH_MILD_T = 5f
val RISK_GRAPH_LOW_T = 3f

data class RiskDayPoint(val date: String, val score: Float, val zone: String)

fun riskZoneColor(v: Float): Color = when {
    v >= RISK_GRAPH_HIGH_T -> Color(0xFFEF5350)
    v >= RISK_GRAPH_MILD_T -> Color(0xFFFFB74D)
    v >= RISK_GRAPH_LOW_T -> Color(0xFF81C784)
    else -> Color(0xFF81C784)
}

@Composable
private fun RiskGraphPreview(days: List<RiskPickerDay>, modifier: Modifier = Modifier) {
    RiskGraphCanvas(days.map { RiskDayPoint(it.date, it.score, it.zone) }, modifier)
}

/** Multi-metric risk graph for the picker — draws risk line + normalized overlay metrics. */
@Composable
private fun RiskGraphCanvasMulti(
    days: List<RiskDayPoint>,
    metricLines: List<RiskMetricLine>,
    showRiskLine: Boolean,
    modifier: Modifier = Modifier
) {
    if (days.isEmpty()) return
    val actualMax = days.maxOf { it.score }
    val maxR = maxOf(RISK_GRAPH_HIGH_T * 1.2f, actualMax * 1.1f, 12f)
    val isNormalized = metricLines.isNotEmpty()

    Canvas(modifier.clipToBounds()) {
        val w = size.width; val h = size.height
        val n = days.size
        if (n == 0) return@Canvas
        val chartH = h - 16f
        val dateList = days.map { it.date }
        fun yZ(v: Float) = chartH * (1f - v / maxR)
        fun xFor(i: Int) = if (n <= 1) w / 2f else (i.toFloat() / (n - 1)) * w

        // Zone bands (only when risk score shown and is sole metric)
        if (showRiskLine) {
            drawRect(Color(0xFF81C784).copy(alpha = 0.07f), Offset(0f, yZ(RISK_GRAPH_MILD_T)), Size(w, yZ(RISK_GRAPH_LOW_T) - yZ(RISK_GRAPH_MILD_T)))
            drawRect(Color(0xFFFFB74D).copy(alpha = 0.07f), Offset(0f, yZ(RISK_GRAPH_HIGH_T)), Size(w, yZ(RISK_GRAPH_MILD_T) - yZ(RISK_GRAPH_HIGH_T)))
            drawRect(Color(0xFFEF5350).copy(alpha = 0.07f), Offset(0f, 0f), Size(w, yZ(RISK_GRAPH_HIGH_T)))

            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
            for ((thr, c) in listOf(RISK_GRAPH_LOW_T to Color(0xFF81C784), RISK_GRAPH_MILD_T to Color(0xFFFFB74D), RISK_GRAPH_HIGH_T to Color(0xFFEF5350))) {
                drawLine(c.copy(alpha = 0.25f), Offset(0f, yZ(thr)), Offset(w, yZ(thr)), strokeWidth = 1f, pathEffect = dash)
            }

            // Risk score line
            val points = days.mapIndexed { i, d ->
                val y = if (isNormalized) {
                    val mn = days.minOf { it.score }; val mx = days.maxOf { it.score }
                    val range = if (mx - mn < 0.001f) 1f else mx - mn
                    chartH * (1f - (d.score - mn) / range)
                } else yZ(d.score)
                Triple(xFor(i), y, d.score)
            }
            for (i in 0 until points.size - 1) {
                val avg = (points[i].third + points[i + 1].third) / 2f
                drawLine(riskZoneColor(avg).copy(alpha = 0.9f), Offset(points[i].first, points[i].second), Offset(points[i + 1].first, points[i + 1].second), strokeWidth = 2.5f, cap = StrokeCap.Round)
            }
            for ((x, y, v) in points) {
                drawCircle(riskZoneColor(v), radius = 3.5f, center = Offset(x, y))
            }
        }

        // Overlay metric lines (normalized 0-1 to chart height)
        for (line in metricLines) {
            val vals = dateList.mapIndexedNotNull { i, date ->
                line.points[date]?.let { Triple(i, xFor(i), it) }
            }
            if (vals.size < 2) continue
            val mn = vals.minOf { it.third }; val mx = vals.maxOf { it.third }
            val range = if (mx - mn < 0.001f) 1f else mx - mn
            val normalized = vals.map { (_, x, v) ->
                val y = chartH * (1f - (v - mn) / range)
                Pair(x, y)
            }
            for (i in 0 until normalized.size - 1) {
                drawLine(line.color.copy(alpha = 0.8f), Offset(normalized[i].first, normalized[i].second), Offset(normalized[i + 1].first, normalized[i + 1].second), strokeWidth = 2f, cap = StrokeCap.Round)
            }
            for ((x, y) in normalized) {
                drawCircle(line.color, radius = 2.5f, center = Offset(x, y))
            }
        }
    }
}

/** Simple risk-only graph canvas for rendering attachments in comments. */
@Composable
fun RiskGraphCanvas(days: List<RiskDayPoint>, modifier: Modifier = Modifier) {
    if (days.isEmpty()) return
    val actualMax = days.maxOf { it.score }
    val maxR = maxOf(RISK_GRAPH_HIGH_T * 1.2f, actualMax * 1.1f, 12f)

    Canvas(modifier.clipToBounds()) {
        val w = size.width; val h = size.height
        val n = days.size
        if (n == 0) return@Canvas
        val chartH = h - 16f
        fun yZ(v: Float) = chartH * (1f - v / maxR)
        fun xFor(i: Int) = if (n <= 1) w / 2f else (i.toFloat() / (n - 1)) * w

        // Zone bands
        drawRect(Color(0xFF81C784).copy(alpha = 0.07f), Offset(0f, yZ(RISK_GRAPH_MILD_T)), Size(w, yZ(RISK_GRAPH_LOW_T) - yZ(RISK_GRAPH_MILD_T)))
        drawRect(Color(0xFFFFB74D).copy(alpha = 0.07f), Offset(0f, yZ(RISK_GRAPH_HIGH_T)), Size(w, yZ(RISK_GRAPH_MILD_T) - yZ(RISK_GRAPH_HIGH_T)))
        drawRect(Color(0xFFEF5350).copy(alpha = 0.07f), Offset(0f, 0f), Size(w, yZ(RISK_GRAPH_HIGH_T)))

        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
        for ((thr, c) in listOf(RISK_GRAPH_LOW_T to Color(0xFF81C784), RISK_GRAPH_MILD_T to Color(0xFFFFB74D), RISK_GRAPH_HIGH_T to Color(0xFFEF5350))) {
            drawLine(c.copy(alpha = 0.25f), Offset(0f, yZ(thr)), Offset(w, yZ(thr)), strokeWidth = 1f, pathEffect = dash)
        }

        // Risk score line
        val points = days.mapIndexed { i, d -> Triple(xFor(i), yZ(d.score), d.score) }

        // Lines
        for (i in 0 until points.size - 1) {
            val avg = (points[i].third + points[i + 1].third) / 2f
            drawLine(riskZoneColor(avg).copy(alpha = 0.9f), Offset(points[i].first, points[i].second), Offset(points[i + 1].first, points[i + 1].second), strokeWidth = 2.5f, cap = StrokeCap.Round)
        }

        // Dots
        for ((x, y, v) in points) {
            drawCircle(riskZoneColor(v), radius = 3.5f, center = Offset(x, y))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// SERIALISATION
// ═══════════════════════════════════════════════════════════════════

private fun colorToHex(color: Color): String {
    val argb = color.hashCode()
    return "#${(argb and 0xFFFFFF).toString(16).padStart(6, '0')}"
}

/** Serialize insights timeline graph (backward compatible — no "type" field). */
private fun serializeInsightsGraphToJson(
    windowStart: java.time.Instant,
    windowEnd: java.time.Instant,
    migraines: List<MigraineSpan>,
    events: List<EventMarker>,
    metricSeries: List<MetricSeries>
): JsonObject = buildJsonObject {
    put("start", windowStart.toString())
    put("end", windowEnd.toString())

    put("migraines", buildJsonArray {
        for (m in migraines) {
            add(buildJsonObject {
                put("start", m.start.toString())
                m.end?.let { put("end", it.toString()) }
                m.severity?.let { put("severity", it) }
                m.label?.let { put("label", it) }
            })
        }
    })

    put("events", buildJsonArray {
        for (ev in events) {
            add(buildJsonObject {
                put("at", ev.at.toString())
                ev.endAt?.let { put("end", it.toString()) }
                put("name", ev.name)
                put("category", ev.category)
                put("color", colorToHex(ev.color))
            })
        }
    })

    put("metrics", buildJsonArray {
        for (s in metricSeries) {
            add(buildJsonObject {
                put("label", s.label)
                put("unit", s.unit)
                put("color", colorToHex(s.color))
                put("points", buildJsonArray {
                    for (pt in s.points) {
                        add(buildJsonObject {
                            put("date", pt.date)
                            put("value", pt.value)
                        })
                    }
                })
            })
        }
    })
}

/** Serialize risk graph data (type = "risk") with optional metric overlays. */
private fun serializeRiskGraphToJson(days: List<RiskPickerDay>, metricLines: List<RiskMetricLine> = emptyList()): JsonObject = buildJsonObject {
    put("type", "risk")
    put("days", buildJsonArray {
        for (d in days) {
            add(buildJsonObject {
                put("date", d.date)
                put("score", d.score.toDouble())
                put("zone", d.zone)
            })
        }
    })
    if (metricLines.isNotEmpty()) {
        put("metrics", buildJsonArray {
            for (line in metricLines) {
                add(buildJsonObject {
                    put("key", line.key)
                    put("label", line.label)
                    put("color", colorToHex(line.color))
                    put("points", buildJsonArray {
                        for ((date, value) in line.points) {
                            add(buildJsonObject {
                                put("date", date)
                                put("value", value.toDouble())
                            })
                        }
                    })
                })
            }
        })
    }
}

// ═══════════════════════════════════════════════════════════════════
// SHARED PICKER COMPONENTS
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun PickerMigraineSelector(
    sorted: List<MigraineSpan>, idx: Int, sel: MigraineSpan?,
    onPrev: () -> Unit, onNext: () -> Unit
) {
    val z = ZoneId.systemDefault()
    val df = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(z)
    val tf = DateTimeFormatter.ofPattern("h:mm a").withZone(z)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev, enabled = idx < sorted.size - 1) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Older",
                tint = if (idx < sorted.size - 1) AppTheme.AccentPurple
                else AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                modifier = Modifier.size(22.dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
            Text(sel?.label ?: "Migraine", color = Color.White,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sel != null) {
                Text(df.format(sel.start) + " \u2022 " + tf.format(sel.start),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                val e = sel.end
                if (e != null) {
                    val d = Duration.between(sel.start, e)
                    val hStr = if (d.toHours() > 0) "${d.toHours()}h " else ""
                    Text("$hStr${d.minusHours(d.toHours()).toMinutes()}m \u2022 Severity: ${sel.severity ?: "\u2014"}/10",
                        color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                } else {
                    Text("Severity: ${sel.severity ?: "\u2014"}/10",
                        color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text("${idx + 1} of ${sorted.size}", color = AppTheme.AccentPurple,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
        }
        IconButton(onClick = onNext, enabled = idx > 0) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, "Newer",
                tint = if (idx > 0) AppTheme.AccentPurple
                else AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun PickerChip(n: Int, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text("$n", color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.width(2.dp))
        Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PickerMetricToggle(
    label: String, color: Color, active: Boolean,
    isAutoSelected: Boolean, onClick: () -> Unit
) {
    val bg = if (active) color.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.04f)
    val tc = if (active) color else AppTheme.SubtleTextColor.copy(alpha = 0.5f)
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isAutoSelected) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFFFD54F)))
        } else {
            Box(Modifier.size(6.dp).clip(CircleShape)
                .background(if (active) color else color.copy(alpha = 0.3f)))
        }
        Spacer(Modifier.width(4.dp))
        Text(label, color = tc,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium))
    }
}
