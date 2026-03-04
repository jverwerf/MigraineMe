package com.migraineme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

private data class RiskGraphDay(val date: String, val values: Map<String, Float>)
private data class MetricTemplate(
    val chipKey: String,       // e.g. "sleep:duration", "nutrition:caffeine"
    val label: String,         // Direction-stripped label: "Sleep Duration", "Caffeine"
    val category: String,      // "Sleep", "Weather", "Physical", "Mental", "Nutrition"
    val triggerLabel: String?,  // Original trigger label: "Sleep duration high", "Caffeine low"
    val displayGroup: String?, // e.g. "Poor sleep", "Irregular sleep"
)
private data class RiskGraphResult(
    val days: List<RiskGraphDay>,
    val allMin: Map<String, Float>,
    val allMax: Map<String, Float>,
    val templates: List<MetricTemplate>,
    val labelToChipKeys: Map<String, Set<String>>, // lowercase label/group → set of chip keys
)

private val RISK_COLOR = Color(0xFFEF5350)
private const val highT = 10f
private const val mildT = 5f
private const val lowT = 3f

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RiskHistoryGraph(
    days: Int = 14,
    endDate: LocalDate = LocalDate.now(),
    onClick: (() -> Unit)? = null,
    highlightContributors: List<String> = emptyList()
) {
    val context = LocalContext.current

    var graphDays by remember { mutableStateOf<List<RiskGraphDay>>(emptyList()) }
    var migraineDates by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var symptomDates by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var triggerDates by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var medicineDates by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var reliefDates by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var prodromeDates by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var activityDates by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var allMin by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var allMax by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var templates by remember { mutableStateOf<List<MetricTemplate>>(emptyList()) }
    var labelToChipKeys by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedMetrics by remember { mutableStateOf<Set<String>>(setOf("risk:score")) }
    var visibleHubTypes by remember { mutableStateOf(setOf(0, 1, 2, 3, 4, 5, 6)) }
    // Tapped icon popup: shows which items on that date
    var tappedPopup by remember { mutableStateOf<Pair<String, List<String>>?>(null) } // "Triggers" to ["Stress", "Caffeine"]
    // Tooltip for tapped hub icon
    data class IconHitBox(val x: Float, val y: Float, val typeIdx: Int, val date: String)
    var iconHitBoxes by remember { mutableStateOf<List<IconHitBox>>(emptyList()) }
    var tappedIcon by remember { mutableStateOf<IconHitBox?>(null) }

    LaunchedEffect(days, endDate) {
        isLoading = true
        tappedIcon = null
        val result = loadRiskGraphData(context, days, endDate)
        graphDays = result.days; allMin = result.allMin; allMax = result.allMax
        templates = result.templates; labelToChipKeys = result.labelToChipKeys
        // Load hub item names per date
        withContext(Dispatchers.IO) {
                val token = SessionStore.getValidAccessToken(context) ?: return@withContext
                val userId = SessionStore.readUserId(context) ?: return@withContext
                val client = okhttp3.OkHttpClient()
                val cutoff = endDate.minusDays(days.toLong()).toString()
                fun fetchLogItems(table: String, nameCol: String, dateCol: String = "start_at"): Map<String, List<String>> {
                    return try {
                        val url = "${BuildConfig.SUPABASE_URL}/rest/v1/$table?user_id=eq.$userId&select=$dateCol,$nameCol&$dateCol=gte.$cutoff&order=$dateCol.desc&limit=500"
                        val req = okhttp3.Request.Builder().url(url).get()
                            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                            .addHeader("Authorization", "Bearer $token").build()
                        val body = client.newCall(req).execute().body?.string() ?: "[]"
                        val arr = org.json.JSONArray(body)
                        val map = mutableMapOf<String, MutableList<String>>()
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val d = obj.optString(dateCol, "").take(10)
                            val name = obj.optString(nameCol, "").takeIf { it.isNotBlank() } ?: "Unknown"
                            if (d.length == 10) map.getOrPut(d) { mutableListOf() }.add(name)
                        }
                        map
                    } catch (_: Exception) { emptyMap() }
                }
                // Migraines — get type + severity per date; also extract symptoms from type field
                try {
                    val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                    val allMigraines = db.getMigraines(token)
                    val zone = java.time.ZoneId.systemDefault()
                    val mMap = mutableMapOf<String, MutableList<String>>()
                    val sMap = mutableMapOf<String, MutableList<String>>()
                    for (m in allMigraines) {
                        val d = try { java.time.OffsetDateTime.parse(m.startAt).atZoneSameInstant(zone).toLocalDate().toString() } catch (_: Exception) { m.startAt.take(10) }
                        val label = listOfNotNull(m.type, m.severity?.let { "severity $it" }).joinToString(", ").ifBlank { "Migraine" }
                        mMap.getOrPut(d) { mutableListOf() }.add(label)
                        // Symptoms are stored comma-separated in the type field
                        val symptoms = m.type?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() && it != "Migraine" } ?: emptyList()
                        if (symptoms.isNotEmpty()) {
                            sMap.getOrPut(d) { mutableListOf() }.addAll(symptoms)
                        }
                    }
                    migraineDates = mMap
                    symptomDates = sMap
                } catch (_: Exception) {}
                triggerDates = fetchLogItems("triggers", "type", "start_at")
                medicineDates = fetchLogItems("medicines", "name", "start_at")
                reliefDates = fetchLogItems("reliefs", "type", "start_at")
                prodromeDates = fetchLogItems("prodromes", "type", "start_at")
                activityDates = fetchLogItems("time_in_high_hr_zones_daily", "value_minutes", "date")
            }
            isLoading = false
    }

    val favPool = remember { buildFavoritesPool(context) }
    val effectiveFavs = remember { getEffectiveFavOfFavs(context) }

    // Load zone thresholds from DB
    var lowT by remember { mutableStateOf(3f) }
    var mildT by remember { mutableStateOf(5f) }
    var highT by remember { mutableStateOf(10f) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val thresholds = EdgeFunctionsService().getRiskGaugeThresholds(context)
                thresholds.find { it.zone.uppercase() == "LOW" }?.let { lowT = it.minValue.toFloat() }
                thresholds.find { it.zone.uppercase() == "MILD" }?.let { mildT = it.minValue.toFloat() }
                thresholds.find { it.zone.uppercase() == "HIGH" }?.let { highT = it.minValue.toFloat() }
            } catch (_: Exception) {}
        }
    }

    data class ChipDef(val key: String, val label: String, val color: Color)
    val allChips = remember(favPool, effectiveFavs, templates) {
        val list = mutableListOf<ChipDef>()
        val added = mutableSetOf<String>()
        fun add(k: String, l: String, c: Color) { if (added.add(k)) list.add(ChipDef(k, l, c)) }
        // 1. Risk score
        add("risk:score", "Risk Score", RISK_COLOR)
        // 2. Favorites first
        effectiveFavs.forEach { add(it.key, it.label, catColor(it.category)) }
        // 3. Rest of fav pool
        favPool.forEach { add(it.key, it.label, catColor(it.category)) }
        // 4. ALL metrics from templates (deduplicated by chip key)
        templates.forEach { add(it.chipKey, it.label, catColor(it.category)) }
        list.toList()
    }

    // Pre-select chips matching contributor names via label/displayGroup lookup
    LaunchedEffect(labelToChipKeys, highlightContributors) {
        if (highlightContributors.isNotEmpty() && labelToChipKeys.isNotEmpty()) {
            val matched = mutableSetOf("risk:score")
            for (name in highlightContributors) {
                labelToChipKeys[name.lowercase()]?.let { matched.addAll(it) }
            }
            if (matched.size > 1) selectedMetrics = matched
        }
    }

    val daysWithData = graphDays.filter { it.values.isNotEmpty() }
    val isNormalized = selectedMetrics.size >= 2

    BaseCard(modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("$days-Day Risk History", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            if (onClick != null) Text("View Full \u2192", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))

        if (isLoading) {
            Row(Modifier.fillMaxWidth().height(180.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), AppTheme.AccentPurple, strokeWidth = 2.dp)
            }
        } else if (daysWithData.isEmpty()) {
            Text("No data available", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().height(180.dp).padding(vertical = 70.dp), textAlign = TextAlign.Center)
        } else if (selectedMetrics.isEmpty()) {
            Text("Select a metric below", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().height(180.dp).padding(vertical = 70.dp), textAlign = TextAlign.Center)
        } else {
            // Legend
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                selectedMetrics.forEach { key ->
                    val chip = allChips.find { it.key == key } ?: return@forEach
                    val values = daysWithData.mapNotNull { it.values[key] }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(8.dp)) { drawCircle(chip.color) }
                        Spacer(Modifier.width(4.dp))
                        if (isNormalized) {
                            val mn = allMin[key] ?: 0f; val mx = allMax[key] ?: 1f
                            Text("${chip.label} [${fmtV(mn)}\u2013${fmtV(mx)}]", color = chip.color, style = MaterialTheme.typography.labelSmall)
                        } else {
                            val avg = if (values.isNotEmpty()) values.average().toFloat() else 0f
                            Text("${chip.label} (avg ${fmtV(avg)})", color = chip.color, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
            }
            // Hub icons legend row — tap to toggle visibility
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                data class HubLegend(val idx: Int, val label: String, val color: Color, val dates: Map<String, List<String>>, val draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit)
                val hubItems = listOf(
                    HubLegend(0, "Migraine", Color(0xFFE57373), migraineDates) {
                        // Draw a mini band indicator
                        drawRect(Color(0xFFE57373).copy(alpha = 0.15f), Offset.Zero, size)
                        drawLine(Color(0xFFE57373).copy(alpha = 0.5f), Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 2f)
                        drawLine(Color(0xFFE57373).copy(alpha = 0.5f), Offset(size.width, 0f), Offset(size.width, size.height), strokeWidth = 2f)
                    },
                    HubLegend(1, "Symptoms", Color(0xFFE57373), symptomDates) { HubIcons.run { drawMigraineStarburst(Color(0xFFE57373)) } },
                    HubLegend(2, "Triggers", Color(0xFFFFB74D), triggerDates) { HubIcons.run { drawTriggerBolt(Color(0xFFFFB74D)) } },
                    HubLegend(3, "Medicines", Color(0xFF4FC3F7), medicineDates) { HubIcons.run { drawMedicinePill(Color(0xFF4FC3F7)) } },
                    HubLegend(4, "Reliefs", Color(0xFF81C784), reliefDates) { HubIcons.run { drawReliefLeaf(Color(0xFF81C784)) } },
                    HubLegend(5, "Prodromes", Color(0xFF9575CD), prodromeDates) { HubIcons.run { drawProdromeEye(Color(0xFF9575CD)) } },
                    HubLegend(6, "Activities", Color(0xFFFF8A65), activityDates) { HubIcons.run { drawActivityPulse(Color(0xFFFF8A65)) } }
                )
                hubItems.forEach { item ->
                    if (item.dates.isNotEmpty()) {
                        val visible = item.idx in visibleHubTypes
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier
                                .clickable { visibleHubTypes = if (visible) visibleHubTypes - item.idx else visibleHubTypes + item.idx }
                                .padding(vertical = 2.dp)
                        ) {
                            Canvas(Modifier.size(10.dp)) { item.draw(this) }
                            Text(
                                item.label,
                                color = if (visible) AppTheme.SubtleTextColor else AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                                style = MaterialTheme.typography.labelSmall,
                                textDecoration = if (!visible) TextDecoration.LineThrough else TextDecoration.None
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))

            // Canvas with tap-to-tooltip
            val n = daysWithData.size
            val hubTypeLabels = listOf("Migraine", "Symptoms", "Triggers", "Medicines", "Reliefs", "Prodromes", "Activities")
            Box(Modifier.fillMaxWidth().height(180.dp)) {
                val density = LocalDensity.current
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(daysWithData, visibleHubTypes) {
                            detectTapGestures { tap ->
                                val hitRadius = 16f
                                val hit = iconHitBoxes.minByOrNull {
                                    kotlin.math.hypot((it.x - tap.x).toDouble(), (it.y - tap.y).toDouble())
                                }
                                if (hit != null && kotlin.math.hypot((hit.x - tap.x).toDouble(), (hit.y - tap.y).toDouble()) < hitRadius * 2) {
                                    tappedIcon = if (tappedIcon == hit) null else hit
                                } else {
                                    tappedIcon = null
                                }
                            }
                        }
                ) {
                val w = size.width; val h = size.height
                if (n == 0) return@Canvas
                val chartH = h - 16f
                // Y-axis max: accommodate both the high threshold and actual score values
                val maxDataVal = daysWithData.maxOfOrNull { day ->
                    selectedMetrics.mapNotNull { day.values[it] }.maxOrNull() ?: 0f
                } ?: 0f
                val maxR = (maxOf(highT, maxDataVal) * 1.15f).coerceAtLeast(12f)
                fun yZ(v: Float) = chartH * (1f - v / maxR)
                fun xFor(i: Int) = if (n <= 1) w / 2f else (i.toFloat() / (n - 1)) * w

                // Zone bands — only when risk score uses its own Y-axis (not normalized)
                val showZones = !isNormalized && "risk:score" in selectedMetrics
                if (showZones) {
                    drawRect(Color(0xFF81C784).copy(alpha = 0.07f), Offset(0f, yZ(mildT)), androidx.compose.ui.geometry.Size(w, yZ(lowT) - yZ(mildT)))
                    drawRect(Color(0xFFFFB74D).copy(alpha = 0.07f), Offset(0f, yZ(highT)), androidx.compose.ui.geometry.Size(w, yZ(mildT) - yZ(highT)))
                    drawRect(Color(0xFFEF5350).copy(alpha = 0.07f), Offset(0f, 0f), androidx.compose.ui.geometry.Size(w, yZ(highT)))

                    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
                    for ((thr, c) in listOf(lowT to Color(0xFF81C784), mildT to Color(0xFFFFB74D), highT to Color(0xFFEF5350))) {
                        drawLine(c.copy(alpha = 0.25f), Offset(0f, yZ(thr)), Offset(w, yZ(thr)), strokeWidth = 1f, pathEffect = dash)
                    }
                }

                // Migraine overlay bands (if visible)
                if (0 in visibleHubTypes && migraineDates.isNotEmpty()) {
                    val dateList = daysWithData.map { it.date }
                    with(MigraineOverlayHelper) {
                        drawMigraineBands(
                            dateList = dateList,
                            migraineDates = migraineDates.keys,
                            padding = 0f,
                            graphWidth = w,
                            graphHeight = chartH
                        )
                    }
                }

                // Lines per metric
                for (metricKey in selectedMetrics) {
                    val chip = allChips.find { it.key == metricKey } ?: continue
                    val points = mutableListOf<Triple<Float, Float, Float>>() // x, y, raw value
                    for (i in daysWithData.indices) {
                        val v = daysWithData[i].values[metricKey] ?: continue
                        val x = xFor(i)
                        val y = if (isNormalized) {
                            val mn = allMin[metricKey] ?: 0f; val mx = allMax[metricKey] ?: 1f
                            val range = if (mx - mn < 0.001f) 1f else mx - mn
                            chartH * (1f - (v - mn) / range)
                        } else if (metricKey == "risk:score") {
                            yZ(v)
                        } else {
                            val allVals = daysWithData.mapNotNull { it.values[metricKey] }
                            val mn = allVals.minOrNull() ?: 0f; val mx = allVals.maxOrNull() ?: 1f
                            val range = if (mx - mn < 0.001f) 1f else mx - mn
                            val pad = range * 0.1f
                            chartH * (1f - (v - mn + pad) / (range + 2 * pad))
                        }
                        points.add(Triple(x, y, v))
                    }

                    // Color function for risk score based on thresholds
                    fun riskZoneColor(v: Float): Color = when {
                        v >= highT -> Color(0xFFEF5350)
                        v >= mildT -> Color(0xFFFFB74D)
                        v >= lowT -> Color(0xFF81C784)
                        else -> Color(0xFF81C784)
                    }
                    val isRiskScore = metricKey == "risk:score"

                    // Draw lines between consecutive points
                    if (points.size >= 2) {
                        for (i in 0 until points.size - 1) {
                            val lineColor = if (isRiskScore) {
                                val avgV = (points[i].third + points[i + 1].third) / 2f
                                riskZoneColor(avgV).copy(alpha = 0.9f)
                            } else chip.color.copy(alpha = 0.8f)
                            drawLine(lineColor, Offset(points[i].first, points[i].second), Offset(points[i + 1].first, points[i + 1].second), strokeWidth = 2.5f, cap = StrokeCap.Round)
                        }
                    }
                    // Draw dots for all points (including single day)
                    for ((x, y, v) in points) {
                        val dotColor = if (isRiskScore) riskZoneColor(v) else chip.color
                        drawCircle(dotColor, radius = 3.5f, center = Offset(x, y))
                        // For single day or few points, draw a horizontal dashed guide line
                        if (n <= 3) {
                            val guideDash = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f)
                            drawLine(dotColor.copy(alpha = 0.2f), Offset(0f, y), Offset(w, y), strokeWidth = 1f, pathEffect = guideDash)
                        }
                    }
                }

                // Hub item icons IN the graph — vertically centered, stacked vertically
                val slotW = if (n <= 1) w else w / (n - 1).toFloat()
                val iconSz = (slotW * 0.45f).coerceIn(9f, 22f) // grow with space, min 9, max 22
                val newHitBoxes = mutableListOf<IconHitBox>()

                data class IconEntry(val dayIdx: Int, val typeIdx: Int, val color: Long)
                val iconEntries = mutableListOf<IconEntry>()
                val dateSets = listOf(
                    symptomDates to 0xFF_E5_73_73L,   // Symptoms — red
                    triggerDates to 0xFF_FF_B7_4DL,    // Triggers — orange
                    medicineDates to 0xFF_4F_C3_F7L,   // Medicines — blue
                    reliefDates to 0xFF_81_C7_84L,     // Reliefs — green
                    prodromeDates to 0xFF_95_75_CDL,   // Prodromes — purple
                    activityDates to 0xFF_FF_8A_65L    // Activities — deep orange
                )
                daysWithData.forEachIndexed { i, day ->
                    dateSets.forEachIndexed { typeIdx, (dates, colorL) ->
                        // typeIdx+1 because 0=migraine (bands), 1=symptoms, 2=triggers etc.
                        if (day.date in dates && (typeIdx + 1) in visibleHubTypes) {
                            iconEntries.add(IconEntry(i, typeIdx + 1, colorL))
                        }
                    }
                }

                val byDay = iconEntries.groupBy { it.dayIdx }
                byDay.forEach { (dayIdx, entries) ->
                    val cx = xFor(dayIdx)
                    val count = entries.size
                    // Position icons at the risk score point for this day
                    val riskVal = daysWithData.getOrNull(dayIdx)?.values?.get("risk:score")
                    val anchorY = if (riskVal != null) {
                        yZ(riskVal) // at the risk score dot
                    } else {
                        chartH * 0.5f // fallback: mid-graph
                    }
                    // Stack vertically above the anchor point
                    val stackH = count * (iconSz + 1.5f) - 1.5f
                    val startY = (anchorY - stackH - iconSz * 0.5f).coerceIn(0f, chartH - stackH)

                    entries.forEachIndexed { idx, entry ->
                        val iy = startY + idx * (iconSz + 1.5f)
                        val c = Color(entry.color)
                        val dayDate = daysWithData[entry.dayIdx].date
                        newHitBoxes.add(IconHitBox(cx, iy + iconSz / 2f, entry.typeIdx, dayDate))

                        translate(left = cx - iconSz / 2f, top = iy) {
                            val savedSize = this.size
                            clipRect(0f, 0f, iconSz, iconSz) {
                                scale(iconSz / savedSize.width, iconSz / savedSize.height, pivot = Offset.Zero) {
                                    HubIcons.run {
                                        when (entry.typeIdx) {
                                            1 -> drawMigraineStarburst(c)
                                            2 -> drawTriggerBolt(c)
                                            3 -> drawMedicinePill(c)
                                            4 -> drawReliefLeaf(c)
                                            5 -> drawProdromeEye(c)
                                            6 -> drawActivityPulse(c)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                iconHitBoxes = newHitBoxes.toList()
            }

                // Tooltip overlay — show actual logged items
                tappedIcon?.let { icon ->
                    val typeLabel = hubTypeLabels.getOrElse(icon.typeIdx) { "" }
                    val dateLabel = try { LocalDate.parse(icon.date).format(java.time.format.DateTimeFormatter.ofPattern("MMM d")) } catch (_: Exception) { icon.date }
                    // Get actual item names from the maps
                    val itemNames: List<String> = when (icon.typeIdx) {
                        0 -> migraineDates[icon.date] ?: emptyList()
                        1 -> symptomDates[icon.date] ?: emptyList()
                        2 -> triggerDates[icon.date] ?: emptyList()
                        3 -> medicineDates[icon.date] ?: emptyList()
                        4 -> reliefDates[icon.date] ?: emptyList()
                        5 -> prodromeDates[icon.date] ?: emptyList()
                        6 -> activityDates[icon.date] ?: emptyList()
                        else -> emptyList()
                    }
                    val displayItems = itemNames.distinct().take(6)
                    val pxX = with(density) { (icon.x / 1f).toDp() }
                    val pxY = with(density) { (icon.y / 1f).toDp() }
                    Box(
                        modifier = Modifier
                            .offset(x = (pxX - 50.dp).coerceAtLeast(0.dp), y = pxY - 24.dp - (displayItems.size * 14).dp)
                            .background(Color(0xDD1E0A2E), shape = RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Column {
                            Text("$typeLabel · $dateLabel", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                            displayItems.forEach { name ->
                                Text("• $name", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                            if (itemNames.size > 6) {
                                Text("+ ${itemNames.size - 6} more", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            } // end Box

            if (n >= 1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (n == 1) Arrangement.Center else Arrangement.SpaceBetween) {
                    if (n == 1) {
                        Text(daysWithData.first().date.takeLast(5).replace("-", "/"), color = AppTheme.SubtleTextColor.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
                    } else {
                        listOf(daysWithData.first(), daysWithData[n / 2], daysWithData.last()).forEach { day ->
                            Text(day.date.takeLast(5).replace("-", "/"), color = AppTheme.SubtleTextColor.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        // Chips — Favs first, divider, then grouped by category
        Spacer(Modifier.height(12.dp))
        Text("Select Metrics" + if (selectedMetrics.size > 1) " (${selectedMetrics.size} selected)" else "", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))

        // Favourites section
        val favChipKeys = remember { mutableSetOf("risk:score").apply { effectiveFavs.forEach { add(it.key) }; favPool.forEach { add(it.key) } } }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            allChips.filter { it.key in favChipKeys }.forEach { chip ->
                val sel = chip.key in selectedMetrics
                FilterChip(
                    selected = sel,
                    onClick = { selectedMetrics = if (sel) selectedMetrics - chip.key else selectedMetrics + chip.key },
                    label = { Text(chip.label, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = chip.color.copy(alpha = 0.3f), selectedLabelColor = chip.color, containerColor = AppTheme.BaseCardContainer, labelColor = AppTheme.SubtleTextColor),
                    border = FilterChipDefaults.filterChipBorder(borderColor = if (sel) chip.color else AppTheme.SubtleTextColor.copy(alpha = 0.3f), selectedBorderColor = chip.color, enabled = true, selected = sel)
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = AppTheme.SubtleTextColor.copy(alpha = 0.15f))
        Spacer(Modifier.height(6.dp))

        // Grouped by category (dynamic from templates)
        data class CatGroup(val name: String, val color: Color, val keys: List<String>)
        val groups = remember(templates) {
            val catOrder = listOf("Sleep", "Weather", "Physical", "Mental", "Nutrition")
            templates
                .groupBy { it.category }
                .mapNotNull { (cat, tpls) ->
                    val keys = tpls.map { it.chipKey }.distinct()
                    if (keys.isNotEmpty()) CatGroup(cat, catColor(cat), keys) else null
                }
                .sortedBy { catOrder.indexOf(it.name).let { i -> if (i < 0) 99 else i } }
        }
        groups.forEach { group ->
            Text(group.name, color = group.color, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(4.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                group.keys.forEach { key ->
                    val chip = allChips.find { it.key == key } ?: return@forEach
                    val sel = key in selectedMetrics
                    FilterChip(
                        selected = sel,
                        onClick = { selectedMetrics = if (sel) selectedMetrics - key else selectedMetrics + key },
                        label = { Text(chip.label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = group.color.copy(alpha = 0.3f), selectedLabelColor = group.color, containerColor = AppTheme.BaseCardContainer, labelColor = AppTheme.SubtleTextColor),
                        border = FilterChipDefaults.filterChipBorder(borderColor = if (sel) group.color else AppTheme.SubtleTextColor.copy(alpha = 0.3f), selectedBorderColor = group.color, enabled = true, selected = sel)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
private suspend fun loadRiskGraphData(ctx: android.content.Context, days: Int, endDate: LocalDate): RiskGraphResult = withContext(Dispatchers.IO) {
    val empty = RiskGraphResult(emptyList(), emptyMap(), emptyMap(), emptyList(), emptyMap())
    try {
        val token = SessionStore.getValidAccessToken(ctx) ?: return@withContext empty
        val startDate = endDate.minusDays(days.toLong() - 1)
        val startStr = startDate.toString(); val endStr = endDate.toString()
        val fetchLimit = days + 14
        val dateMap = mutableMapOf<String, MutableMap<String, Float>>()
        fun put(date: String, key: String, value: Float) { if (date in startStr..endStr) dateMap.getOrPut(date) { mutableMapOf() }[key] = value }

        val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)

        // 1. Risk score (always present)
        try { db.getRiskScoreDaily(token, fetchLimit).forEach { put(it.date, "risk:score", it.score.toFloat()) } } catch (_: Exception) {}

        // 2. Fetch all metric templates from user_triggers + user_prodromes
        val allTriggers = try { db.getAllTriggerPool(token) } catch (_: Exception) { emptyList() }
        val allProdromes = try { db.getAllProdromePool(token) } catch (_: Exception) { emptyList() }

        // Build MetricTemplate list and collect unique (table, column) → chipKey pairs
        val dirSuffixes = listOf(" high", " low", " early", " late")
        fun stripDirection(label: String): String {
            var base = label
            for (s in dirSuffixes) {
                if (base.endsWith(s, ignoreCase = true)) { base = base.dropLast(s.length); break }
            }
            return base.trim()
        }

        // Deduplicate by chipKey — collect all template metadata
        val chipKeyToTemplate = linkedMapOf<String, MetricTemplate>()
        fun addTemplate(label: String, table: String?, column: String?, displayGroup: String?) {
            if (table == null) return
            val chipKey = SupabaseDbService.tableColToChipKey(table, column) ?: return
            if (chipKey !in chipKeyToTemplate) {
                val cat = SupabaseDbService.chipCategory(chipKey)
                chipKeyToTemplate[chipKey] = MetricTemplate(
                    chipKey = chipKey,
                    label = stripDirection(label),
                    category = cat,
                    triggerLabel = label,
                    displayGroup = displayGroup,
                )
            } else {
                // Add additional trigger label / display group references
                val existing = chipKeyToTemplate[chipKey]!!
                if (existing.triggerLabel != label || existing.displayGroup != displayGroup) {
                    // Keep the existing — it already has a label. We just need matching to work.
                    // The templates list will have one entry per chipKey but matching checks all triggers.
                }
            }
        }
        allTriggers.forEach { addTemplate(it.label, it.metricTable, it.metricColumn, it.displayGroup) }
        allProdromes.forEach { addTemplate(it.label, it.metricTable, it.metricColumn, it.displayGroup) }
        val templates = chipKeyToTemplate.values.toList()

        // Also build full label/group → chipKey lookup for contributor matching
        // (multiple labels can map to same chipKey, and display_groups map to multiple chipKeys)
        val labelToChipKeys = mutableMapOf<String, MutableSet<String>>()
        fun indexLabel(label: String?, chipKey: String) {
            if (label.isNullOrBlank()) return
            labelToChipKeys.getOrPut(label.lowercase()) { mutableSetOf() }.add(chipKey)
        }
        allTriggers.forEach { t ->
            val ck = SupabaseDbService.tableColToChipKey(t.metricTable ?: return@forEach, t.metricColumn) ?: return@forEach
            indexLabel(t.label, ck)
            indexLabel(t.displayGroup, ck)
        }
        allProdromes.forEach { p ->
            val ck = SupabaseDbService.tableColToChipKey(p.metricTable ?: return@forEach, p.metricColumn) ?: return@forEach
            indexLabel(p.label, ck)
            indexLabel(p.displayGroup, ck)
        }

        // 3. Fetch data dynamically — group by table, batch columns
        val tableColumns = mutableMapOf<String, MutableSet<String>>() // table → set of columns
        val tableColChipKey = mutableMapOf<String, String>()          // "table|col" → chipKey
        for (t in templates) {
            val entry = SupabaseDbService.TABLE_COL_TO_CHIP_KEY.entries.find { it.value == t.chipKey } ?: continue
            val (table, col) = entry.key.split("|", limit = 2)
            tableColumns.getOrPut(table) { mutableSetOf() }.add(col)
            tableColChipKey["$table|$col"] = t.chipKey
        }

        val userId = SessionStore.readUserId(ctx) ?: ""
        val restClient = okhttp3.OkHttpClient()

        for ((table, columns) in tableColumns) {
            try {
                val selectCols = (listOf("date") + columns).joinToString(",")
                val url = "${BuildConfig.SUPABASE_URL}/rest/v1/$table?user_id=eq.$userId&select=$selectCols&order=date.desc&limit=$fetchLimit"
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
                                put(d, chipKey, v.toFloat())
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val graphDays = dateMap.entries.sortedBy { it.key }.map { (date, vals) -> RiskGraphDay(date, vals) }
        val mn = mutableMapOf<String, Float>(); val mx = mutableMapOf<String, Float>()
        graphDays.forEach { day -> day.values.forEach { (k, v) -> mn[k] = minOf(mn[k] ?: v, v); mx[k] = maxOf(mx[k] ?: v, v) } }

        // Enrich templates with label→chipKey mapping for contributor matching
        // Return templates augmented: we store the full lookup in a wrapper
        RiskGraphResult(graphDays, mn, mx, templates, labelToChipKeys)
    } catch (_: Exception) { RiskGraphResult(emptyList(), emptyMap(), emptyMap(), emptyList(), emptyMap()) }
}

private fun catColor(category: String): Color = when (category) {
    "Sleep" -> Color(0xFF7E57C2); "Weather" -> Color(0xFF4FC3F7); "Physical" -> Color(0xFF81C784)
    "Mental" -> Color(0xFFBA68C8); "Nutrition" -> Color(0xFFFFB74D); else -> Color(0xFF999999)
}

private fun fmtV(v: Float): String = if (v == v.toLong().toFloat()) "${v.toLong()}" else "%.1f".format(v)
