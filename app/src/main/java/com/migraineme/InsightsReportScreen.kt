// FILE: InsightsReportScreen.kt
package com.migraineme

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ═══════ Filter category colors (matching the card / spider colors) ═══════

private val FilterCatColors = mapOf(
    "Severity" to Color(0xFFFF7043),
    "Symptom" to Color(0xFFCE93D8),
    "Pain Location" to Color(0xFFFF8A80),
    "Trigger" to Color(0xFFFF8A65),
    "Prodrome" to Color(0xFFFFD54F),
    "Medicine" to Color(0xFF4FC3F7),
    "Relief" to Color(0xFF81C784),
    "Activity" to Color(0xFFBA68C8),
    "Location" to Color(0xFF4DD0E1),
    "Missed Activity" to Color(0xFFEF9A9A)
)

// ═══════ Ordered categories to match the migraine log flow ═══════

private val FilterCatOrder = listOf(
    "Severity", "Symptom", "Pain Location", "Trigger", "Prodrome",
    "Medicine", "Relief", "Activity", "Location", "Missed Activity"
)

@Composable
fun InsightsReportScreen(
    navController: NavController,
    vm: InsightsViewModel = viewModel()
) {
    val owner = LocalContext.current as ViewModelStoreOwner
    val ctx: Context = LocalContext.current.applicationContext
    val activityCtx = LocalContext.current as? android.app.Activity
    val authVm: AuthViewModel = viewModel(owner)
    val auth by authVm.state.collectAsState()
    LaunchedEffect(auth.accessToken) {
        auth.accessToken?.takeIf { it.isNotBlank() }?.let { vm.load(ctx, it) }
    }

    val migraines by vm.migraines.collectAsState()
    val allDailyMetrics by vm.allDailyMetrics.collectAsState()
    val linkedItems by vm.selectedLinkedItems.collectAsState()
    val linkedLoading by vm.linkedItemsLoading.collectAsState()
    val scrollState = rememberScrollState()
    val zone = ZoneId.systemDefault()

    // ── Filter state ──
    // Reset to NONE on first open so no metrics are pre-selected
    LaunchedEffect(Unit) { vm.setTimeFrame(InsightsViewModel.TimeFrame.NONE) }
    val tagIndex by vm.migraineTagIndex.collectAsState()
    val activeFilters by vm.activeFilters.collectAsState()
    val timeFrame by vm.timeFrame.collectAsState()
    val customRange by vm.customRange.collectAsState()

    // ── Filtered migraines ──
    val sorted = remember(migraines) { migraines.sortedByDescending { it.start } }
    val filteredSorted = remember(sorted, activeFilters, tagIndex, timeFrame, customRange) {
        if (timeFrame == InsightsViewModel.TimeFrame.NONE) return@remember emptyList()
        val cutoff = when {
            timeFrame == InsightsViewModel.TimeFrame.CUSTOM && customRange != null -> {
                customRange!!.from.atStartOfDay(zone).toInstant()
            }
            timeFrame.days != null -> Instant.now().minus(Duration.ofDays(timeFrame.days!!.toLong()))
            else -> null
        }
        val upperBound = if (timeFrame == InsightsViewModel.TimeFrame.CUSTOM && customRange != null) {
            customRange!!.to.plusDays(1).atStartOfDay(zone).toInstant()
        } else null

        sorted.filter { m ->
            if (cutoff != null && m.start.isBefore(cutoff)) return@filter false
            if (upperBound != null && m.start.isAfter(upperBound)) return@filter false
            if (activeFilters.isEmpty()) true
            else {
                val tags = tagIndex[m.id] ?: emptySet()
                activeFilters.all { it in tags }
            }
        }
    }

    val selIdx by vm.selectedMigraineIndex.collectAsState()
    // Clamp to filtered list
    val clampedIdx = selIdx.coerceIn(0, (filteredSorted.size - 1).coerceAtLeast(0))
    LaunchedEffect(filteredSorted.size, selIdx) {
        if (filteredSorted.isNotEmpty() && selIdx >= filteredSorted.size) vm.selectMigraine(0)
    }
    val sel = filteredSorted.getOrNull(clampedIdx)
    LaunchedEffect(sel?.id) { sel?.id?.let { vm.loadLinkedItems(it) } }

    // Window days (shared with detail screen)
    val wBefore by vm.windowDaysBefore.collectAsState()
    val wAfter by vm.windowDaysAfter.collectAsState()
    val wStart = sel?.start?.minus(Duration.ofDays(wBefore))
    val wEnd = (sel?.end ?: sel?.start)?.plus(Duration.ofDays(wAfter))
    val windowDates = remember(wStart, wEnd) {
        if (wStart == null || wEnd == null) emptySet()
        else {
            val f = LocalDate.ofInstant(wStart, zone)
            val t = LocalDate.ofInstant(wEnd, zone)
            generateSequence(f) { it.plusDays(1) }
                .takeWhile { !it.isAfter(t) }
                .map { it.toString() }.toSet()
        }
    }

    val allMissed by vm.allMissedActivities.collectAsState()
    val allActs by vm.allActivities.collectAsState()

    val windowEvents = remember(linkedItems, allMissed, allActs, sel?.id) {
        val actsForMigraine = allActs.filter { it.migraineId == sel?.id }
        val missedForMigraine = allMissed.filter { it.migraineId == sel?.id }
        buildEventMarkers(linkedItems, actsForMigraine, missedForMigraine)
    }

    val autoSelectedKeys = remember(windowEvents) {
        windowEvents.filter { it.isAutomated }
            .mapNotNull { ev: EventMarker -> vm.labelToMetricKey(ev.name) }
            .toSet()
    }

    // Auto-selected keys across ALL filtered migraines (for the full report)
    val filteredIds = remember(filteredSorted) { filteredSorted.mapNotNull { it.id }.toSet() }

    // Available tags scoped to filtered migraines only
    val filteredAvailableTags = remember(filteredIds, tagIndex) {
        val tags = mutableSetOf<InsightsViewModel.FilterTag>()
        filteredIds.forEach { id -> tagIndex[id]?.let { tags.addAll(it) } }
        tags.groupBy({ it.category }, { it.label })
            .mapValues { it.value.distinct().sorted() }
    }

    val templateMap by vm.labelToMetricMap.collectAsState()

    val allAutoSelectedKeys = remember(filteredIds, allDailyMetrics, templateMap) {
        vm.autoMetricKeysForMigraines(filteredIds)
    }

    val available = remember(allDailyMetrics, timeFrame) {
        if (timeFrame == InsightsViewModel.TimeFrame.NONE) emptyList()
        else AllMetricDefs.filter { d -> allDailyMetrics.containsKey(d.key) }
    }

    val userToggledKeys by vm.userToggledMetrics.collectAsState()
    val enabledKeys = allAutoSelectedKeys + userToggledKeys

    // ── Report generation gate ──
    var reportGenerated by remember { mutableStateOf(false) }

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

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll) {

            // ── Back arrow ──
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back", tint = Color.White)
                }
            }

            // ══════════ 1. FILTER CARD (collapsible) ══════════
            FilterCard(
                availableTags = filteredAvailableTags,
                activeFilters = activeFilters,
                timeFrame = timeFrame,
                customRange = customRange,
                totalCount = sorted.size,
                filteredCount = filteredSorted.size,
                onToggle = { vm.toggleFilter(it) },
                onTimeFrame = { vm.setTimeFrame(it) },
                onCustomRange = { from, to -> vm.setCustomRange(from, to) },
                onClear = { vm.clearFilters() },
                availableMetrics = available,
                enabledKeys = enabledKeys,
                autoSelectedKeys = allAutoSelectedKeys,
                onToggleMetric = { key ->
                    if (key !in allAutoSelectedKeys) {
                        vm.toggleMetric(key)
                    }
                }
            )

            // ══════════ Spiders (computed regardless, needed for PDF) ══════════
            val spiderLoading by vm.spiderLoading.collectAsState()
            val spiders = remember(filteredIds, spiderLoading) {
                if (spiderLoading) InsightsViewModel.FilteredSpiders()
                else vm.buildFilteredSpiders(filteredIds)
            }

            // ══════════ GENERATE / DOWNLOAD REPORT BUTTON ══════════
            if (!reportGenerated) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { reportGenerated = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppTheme.AccentPurple
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "Generate Report",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }

            // ══════════ 2. REPORT CONTENT (shown after Generate) ══════════
            if (reportGenerated) {

            // ══════════ DOWNLOAD REPORT BUTTON ══════════
            Spacer(Modifier.height(12.dp))
            var isGeneratingPdf by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()

            Button(
                onClick = {
                    isGeneratingPdf = true
                    val timeLabel = when (timeFrame) {
                        InsightsViewModel.TimeFrame.NONE -> "All"
                        InsightsViewModel.TimeFrame.WEEK_1 -> "Last 7 Days"
                        InsightsViewModel.TimeFrame.WEEK_2 -> "Last 14 Days"
                        InsightsViewModel.TimeFrame.MONTH_1 -> "Last 30 Days"
                        InsightsViewModel.TimeFrame.MONTH_3 -> "Last 3 Months"
                        InsightsViewModel.TimeFrame.MONTH_6 -> "Last 6 Months"
                        InsightsViewModel.TimeFrame.YEAR_1 -> "Last Year"
                        InsightsViewModel.TimeFrame.ALL -> "All Time"
                        InsightsViewModel.TimeFrame.CUSTOM -> {
                            val cr = customRange
                            if (cr != null) "${cr.from} – ${cr.to}" else "Custom"
                        }
                    }
                    scope.launch {
                        try {
                            data class MgGraphData(
                                val mg: MigraineSpan, val events: List<EventMarker>,
                                val windowMigs: List<MigraineSpan>, val windowDates: Set<String>,
                                val wStart: java.time.Instant, val wEnd: java.time.Instant,
                                val autoKeys: Set<String>
                            )
                            val allGd = mutableListOf<MgGraphData>()
                            val globalAutoKeys = mutableSetOf<String>()

                            for (mg in filteredSorted) {
                                val mgId = mg.id ?: continue
                                val linked = vm.getLinkedItemsFor(mgId)
                                val actsFor = allActs.filter { it.migraineId == mgId }
                                val missedFor = allMissed.filter { it.migraineId == mgId }
                                val events = buildEventMarkers(linked, actsFor, missedFor)
                                val mWStart = mg.start.minus(Duration.ofDays(wBefore))
                                val mWEnd = (mg.end ?: mg.start).plus(Duration.ofDays(wAfter))
                                val mWindowDates = run {
                                    val f = LocalDate.ofInstant(mWStart, zone)
                                    val t = LocalDate.ofInstant(mWEnd, zone)
                                    generateSequence(f) { it.plusDays(1) }
                                        .takeWhile { !it.isAfter(t) }
                                        .map { it.toString() }.toSet()
                                }
                                val mWindowMigs = migraines.filter { m ->
                                    val e = m.end ?: m.start
                                    !m.start.isAfter(mWEnd) && !e.isBefore(mWStart)
                                }
                                val mAutoKeys = events.filter { it.isAutomated }
                                    .mapNotNull { ev -> vm.labelToMetricKey(ev.name) }.toSet()
                                globalAutoKeys.addAll(mAutoKeys)
                                allGd.add(MgGraphData(mg, events, mWindowMigs, mWindowDates, mWStart, mWEnd, mAutoKeys))
                            }

                            val overlayKeys = globalAutoKeys + userToggledKeys
                            val captures = mutableListOf<ReportPdfGenerator.TimelineCapture>()
                            for (gd in allGd) {
                                val mAvail = AllMetricDefs.filter { d ->
                                    allDailyMetrics[d.key]?.any { it.date in gd.windowDates } == true
                                }
                                fun series(keys: Set<String>) = mAvail.filter { it.key in keys }.map { d ->
                                    MetricSeries(d.key, d.label, d.unit, d.color,
                                        allDailyMetrics[d.key]!!.filter { it.date in gd.windowDates }
                                            .map { DailyMetricPoint(it.date, it.value) })
                                }
                                val autoSeries = series(gd.autoKeys)
                                val fullSeries = series(overlayKeys)

                                if (activityCtx != null) {
                                    val autoBmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        captureTimelineGraph(activityCtx, gd.windowMigs, gd.events, autoSeries, gd.wStart, gd.wEnd, gd.mg.start)
                                    }
                                    val fullBmp = if (fullSeries.size > autoSeries.size) {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            captureTimelineGraph(activityCtx, gd.windowMigs, gd.events, fullSeries, gd.wStart, gd.wEnd, gd.mg.start)
                                        }
                                    } else autoBmp

                                    val sortedEvents = gd.events.sortedBy { it.at }
                                    val legendEvents = sortedEvents.mapIndexed { i, ev ->
                                        ReportPdfGenerator.LegendEvent(i + 1, ev.name, ev.category,
                                            ev.color.let { android.graphics.Color.argb((it.alpha * 255).toInt(), (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()) },
                                            ev.isAutomated)
                                    }
                                    fun metricLegend(s: List<MetricSeries>) = s.map { ms ->
                                        ReportPdfGenerator.LegendMetric(ms.label, ms.unit,
                                            ms.color.let { android.graphics.Color.argb((it.alpha * 255).toInt(), (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()) })
                                    }
                                    if (autoBmp != null && fullBmp != null) {
                                        captures.add(ReportPdfGenerator.TimelineCapture(
                                            gd.mg, autoBmp, fullBmp, legendEvents, metricLegend(autoSeries), metricLegend(fullSeries)))
                                    }
                                }
                            }

                            val reportData = ReportPdfGenerator.ReportData(
                                filteredMigraines = filteredSorted, timeFrameLabel = timeLabel,
                                spiders = spiders, enabledMetrics = enabledSeries,
                                autoMetricKeys = allAutoSelectedKeys, allDailyMetrics = allDailyMetrics,
                                timelineCaptures = captures)
                            val generator = ReportPdfGenerator(ctx)
                            val file = generator.generate(reportData)
                            isGeneratingPdf = false
                            if (file != null) { generator.share(file) }
                            else { android.widget.Toast.makeText(ctx, "Failed to generate PDF", android.widget.Toast.LENGTH_SHORT).show() }
                        } catch (e: Exception) {
                            isGeneratingPdf = false
                            android.util.Log.e("ReportPDF", "PDF error", e)
                            android.widget.Toast.makeText(ctx, "PDF error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = !isGeneratingPdf && !spiderLoading,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isGeneratingPdf) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Generating PDF…", color = Color.White, style = MaterialTheme.typography.titleSmall)
                } else if (spiderLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White.copy(alpha = 0.5f))
                    Spacer(Modifier.width(8.dp))
                    Text("Loading…", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.titleSmall)
                } else {
                    Text("Download Report", color = Color.White,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                }
            }

            Spacer(Modifier.height(12.dp))

            HeroCard {
                Text("Migraine Timeline", color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))

                if (filteredSorted.isEmpty()) {
                    Text(
                        if (sorted.isEmpty()) "No migraines logged yet"
                        else "No migraines match filters",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    return@HeroCard
                }

                // Migraine selector (navigates filtered list)
                DetailMigraineSelector(filteredSorted, clampedIdx, sel,
                    onPrev = { if (clampedIdx < filteredSorted.size - 1) vm.selectMigraine(clampedIdx + 1) },
                    onNext = { if (clampedIdx > 0) vm.selectMigraine(clampedIdx - 1) })

                if (linkedLoading) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(16.dp),
                            strokeWidth = 2.dp, color = AppTheme.AccentPurple)
                    }
                } else {
                    val catCounts = remember(windowEvents) {
                        windowEvents.groupBy { it.category }.mapValues { it.value.size }
                    }
                    if (catCounts.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            catCounts.forEach { (c, n) ->
                                DetailChip(n, c, EventCategoryColors[c] ?: AppTheme.AccentPurple)
                            }
                        }
                    }
                }

                // Graph
                InsightsTimelineGraph(
                    windowMigs, windowEvents, enabledSeries,
                    wStart, wEnd, sel?.start,
                    Modifier.fillMaxWidth().height(360.dp))

                Text("Window around migraine", color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

                // Window adjustment
                WindowDaysControl(wBefore, wAfter, onChanged = { b, a -> vm.setWindowDays(b, a) })
            }

            // ══════════ 3. FILTERED INSIGHT CARDS (same as main page, filtered aggregation) ══════════

            if (filteredSorted.isNotEmpty()) {
                // Symptoms card (same as SymptomsInsightCard on main page)
                spiders.symptoms?.takeIf { it.totalLogged > 0 }?.let {
                    FilteredSymptomsCard(
                        migCount = filteredSorted.size,
                        painChar = spiders.painChar,
                        accompanying = spiders.accompanying,
                        onClick = { navController.navigate("${Routes.INSIGHTS_BREAKDOWN}/Symptoms") }
                    )
                }

                // All other spiders (same as SpiderInsightCard on main page)
                listOf(
                    spiders.prodromes, spiders.triggers,
                    spiders.medicines, spiders.reliefs,
                    spiders.locations, spiders.activities,
                    spiders.missedActivities
                ).forEach { sp ->
                    if (sp != null && sp.totalLogged > 0) {
                        val effAxes = when (sp.logType) {
                            "Medicines" -> if (spiders.medicineEffectiveness.isNotEmpty() && sp.axes.size >= 3) {
                                val m = spiders.medicineEffectiveness.associate { it.category to it.avgRelief }
                                sp.axes.map { SpiderAxis(it.label, m[it.label] ?: 0f, 3f) }
                            } else null
                            "Reliefs" -> if (spiders.reliefEffectiveness.isNotEmpty() && sp.axes.size >= 3) {
                                val m = spiders.reliefEffectiveness.associate { it.category to it.avgRelief }
                                sp.axes.map { SpiderAxis(it.label, m[it.label] ?: 0f, 3f) }
                            } else null
                            else -> null
                        }
                        FilteredSpiderCard(sp, effAxes) {
                            navController.navigate("${Routes.INSIGHTS_BREAKDOWN}/${sp.logType}")
                        }
                    }
                }
            }

            // ══════════ HEALTH METRICS SPARKLINES ══════════
            if (enabledSeries.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Health Metrics", color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(horizontal = 4.dp))
                Spacer(Modifier.height(8.dp))

                // Date range from filtered migraines ±7 days
                val metricMigDates = remember(filteredSorted) {
                    filteredSorted.map { it.start.atZone(zone).toLocalDate() }
                }
                val metricRangeStart = remember(metricMigDates) {
                    metricMigDates.minOrNull()?.minusDays(7)
                }
                val metricRangeEnd = remember(metricMigDates, filteredSorted) {
                    metricMigDates.maxOrNull()?.let { last ->
                        filteredSorted.find { it.start.atZone(zone).toLocalDate() == last }?.end
                            ?.let { it.atZone(zone).toLocalDate().plusDays(7) }
                            ?: last.plusDays(7)
                    }
                }

                enabledSeries.forEach { series ->
                    val filteredPoints = remember(series, metricRangeStart, metricRangeEnd) {
                        if (metricRangeStart != null && metricRangeEnd != null) {
                            series.points.filter {
                                val d = LocalDate.parse(it.date)
                                !d.isBefore(metricRangeStart) && !d.isAfter(metricRangeEnd)
                            }
                        } else series.points
                    }
                    if (filteredPoints.size >= 2) {
                        val isAuto = series.key in allAutoSelectedKeys
                        BaseCard(modifier = Modifier.padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${series.label} (${series.unit})",
                                    color = series.color,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                                if (isAuto) {
                                    Spacer(Modifier.width(8.dp))
                                    Text("AUTO",
                                        color = AppTheme.AccentPink,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        modifier = Modifier
                                            .background(AppTheme.AccentPink.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                            Spacer(Modifier.height(8.dp))

                            // Sparkline with migraine markers
                            val sorted = filteredPoints.sortedBy { it.date }
                            val minV = sorted.minOf { it.value }
                            val maxV = sorted.maxOf { it.value }
                            val avgV = sorted.map { it.value }.average()

                            Canvas(Modifier.fillMaxWidth().height(100.dp)) {
                                val w = size.width; val h = size.height
                                val padL = 40f; val padR = 8f; val padT = 12f; val padB = 20f
                                val cw = w - padL - padR; val ch = h - padT - padB
                                val rng = if (maxV - minV < 0.001) 1.0 else maxV - minV
                                val firstDate = LocalDate.parse(sorted.first().date)
                                val lastDate = LocalDate.parse(sorted.last().date)
                                val daySpan = java.time.temporal.ChronoUnit.DAYS.between(firstDate, lastDate).toFloat().coerceAtLeast(1f)

                                fun dateX(ds: String): Float {
                                    val days = java.time.temporal.ChronoUnit.DAYS.between(firstDate, LocalDate.parse(ds)).toFloat()
                                    return padL + (days / daySpan) * cw
                                }
                                fun valY(v: Double): Float = padT + ch - ((v - minV) / rng).toFloat() * ch

                                // Grid lines (3 horizontal)
                                val gridColor = Color.White.copy(alpha = 0.08f)
                                listOf(maxV, (minV + maxV) / 2.0, minV).forEach { v ->
                                    val gy = valY(v)
                                    drawLine(gridColor, Offset(padL, gy), Offset(w - padR, gy), 1f)
                                }

                                // Y-axis labels
                                val yPaint = android.graphics.Paint().apply {
                                    color = Color.White.copy(alpha = 0.4f).toArgb()
                                    textSize = 20f; isAntiAlias = true; textAlign = android.graphics.Paint.Align.RIGHT
                                }
                                drawContext.canvas.nativeCanvas.drawText("%.1f".format(maxV), padL - 4f, valY(maxV) + 5f, yPaint)
                                drawContext.canvas.nativeCanvas.drawText("%.1f".format(minV), padL - 4f, valY(minV) + 5f, yPaint)

                                // Avg dashed line
                                val avgY = valY(avgV)
                                drawLine(series.color.copy(alpha = 0.2f), Offset(padL, avgY), Offset(w - padR, avgY), 1f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))

                                // Migraine markers
                                filteredSorted.forEach { mg ->
                                    val mDate = mg.start.atZone(zone).toLocalDate().toString()
                                    val days = java.time.temporal.ChronoUnit.DAYS.between(firstDate, LocalDate.parse(mDate)).toFloat()
                                    if (days < 0 || days > daySpan) return@forEach
                                    val mx = padL + (days / daySpan) * cw
                                    drawLine(AppTheme.AccentPink.copy(alpha = 0.4f),
                                        Offset(mx, padT), Offset(mx, h - padB), 1.5f,
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)))
                                    val sev = mg.severity ?: 0
                                    if (sev > 0) {
                                        drawRoundRect(AppTheme.AccentPink.copy(alpha = 0.3f),
                                            Offset(mx - 10f, padT - 2f), androidx.compose.ui.geometry.Size(20f, 14f),
                                            cornerRadius = CornerRadius(4f))
                                        drawContext.canvas.nativeCanvas.drawText("$sev", mx,
                                            padT + 9f, android.graphics.Paint().apply {
                                                color = AppTheme.AccentPink.toArgb(); textSize = 18f
                                                isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER
                                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                                            })
                                    }
                                }

                                // Sparkline
                                val path = Path()
                                sorted.forEachIndexed { i, pt ->
                                    val x = dateX(pt.date); val py = valY(pt.value)
                                    if (i == 0) path.moveTo(x, py) else path.lineTo(x, py)
                                }
                                drawPath(path, series.color.copy(alpha = 0.15f), style = Stroke(width = 5f, cap = StrokeCap.Round))
                                drawPath(path, series.color.copy(alpha = 0.8f), style = Stroke(width = 2f, cap = StrokeCap.Round))
                                sorted.forEach { pt ->
                                    drawCircle(series.color.copy(alpha = 0.3f), 4f, Offset(dateX(pt.date), valY(pt.value)))
                                    drawCircle(series.color, 2f, Offset(dateX(pt.date), valY(pt.value)))
                                }

                                // X-axis dates
                                val xPaint = android.graphics.Paint().apply {
                                    color = Color.White.copy(alpha = 0.4f).toArgb()
                                    textSize = 18f; isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER
                                }
                                val xFmt = java.time.format.DateTimeFormatter.ofPattern("dd MMM")
                                val labelCount = 5.coerceAtMost(daySpan.toInt() + 1)
                                for (i in 0 until labelCount) {
                                    val frac = if (labelCount <= 1) 0f else i.toFloat() / (labelCount - 1)
                                    val ld = firstDate.plusDays((frac * daySpan).toLong())
                                    val dx = padL + frac * cw
                                    drawContext.canvas.nativeCanvas.drawText(ld.format(xFmt), dx, h - 2f, xPaint)
                                }
                            }

                            // Stats row
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Min: ${"%.1f".format(minV)}", color = AppTheme.SubtleTextColor,
                                    style = MaterialTheme.typography.labelSmall)
                                Text("Avg: ${"%.1f".format(avgV)}", color = AppTheme.SubtleTextColor,
                                    style = MaterialTheme.typography.labelSmall)
                                Text("Max: ${"%.1f".format(maxV)}", color = AppTheme.SubtleTextColor,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            } // end if (reportGenerated)
        }
    }
}

// ═══════ Filter card ═══════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterCard(
    availableTags: Map<String, List<String>>,
    activeFilters: Set<InsightsViewModel.FilterTag>,
    timeFrame: InsightsViewModel.TimeFrame,
    customRange: InsightsViewModel.CustomRange?,
    totalCount: Int,
    filteredCount: Int,
    onToggle: (InsightsViewModel.FilterTag) -> Unit,
    onTimeFrame: (InsightsViewModel.TimeFrame) -> Unit,
    onCustomRange: (java.time.LocalDate, java.time.LocalDate) -> Unit,
    onClear: () -> Unit,
    availableMetrics: List<MetricDef> = emptyList(),
    enabledKeys: Set<String> = emptySet(),
    autoSelectedKeys: Set<String> = emptySet(),
    onToggleMetric: (String) -> Unit = {}
) {
    var expanded by remember { mutableStateOf(true) }
    val hasFilters = activeFilters.isNotEmpty() || (timeFrame != InsightsViewModel.TimeFrame.ALL && timeFrame != InsightsViewModel.TimeFrame.NONE)
    val activeCount = activeFilters.size + if (timeFrame != InsightsViewModel.TimeFrame.ALL && timeFrame != InsightsViewModel.TimeFrame.NONE) 1 else 0
    val enabledMetricCount = enabledKeys.size

    // Custom date picker state
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }
    var customFrom by remember(customRange) {
        mutableStateOf(customRange?.from ?: java.time.LocalDate.now().minusDays(30))
    }
    var customTo by remember(customRange) {
        mutableStateOf(customRange?.to ?: java.time.LocalDate.now())
    }

    BaseCard {
        // Header row — always visible, tappable
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.FilterList,
                contentDescription = "Filter",
                tint = if (hasFilters) AppTheme.AccentPurple else AppTheme.SubtleTextColor,
                modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                val totalActive = activeCount + enabledMetricCount
                Text(
                    if (hasFilters || enabledMetricCount > 0) "Select Metrics ($totalActive)"
                    else "Select Metrics",
                    color = if (hasFilters || enabledMetricCount > 0) Color.White else AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                if (hasFilters) {
                    Text("$filteredCount of $totalCount migraines",
                        color = AppTheme.AccentPurple,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            if (hasFilters) {
                Text("Clear",
                    color = AppTheme.AccentPurple.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onClear() }
                        .padding(horizontal = 8.dp, vertical = 4.dp))
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp
                else Icons.Filled.KeyboardArrowDown,
                contentDescription = "Expand",
                tint = AppTheme.SubtleTextColor,
                modifier = Modifier.size(20.dp))
        }

        // Active filter chips (shown when collapsed + has filters)
        if (!expanded && hasFilters) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (timeFrame != InsightsViewModel.TimeFrame.ALL && timeFrame != InsightsViewModel.TimeFrame.NONE) {
                    val timeLabel = if (timeFrame == InsightsViewModel.TimeFrame.CUSTOM && customRange != null) {
                        val df = java.time.format.DateTimeFormatter.ofPattern("MMM d")
                        "${df.format(customRange.from)} – ${df.format(customRange.to)}"
                    } else timeFrame.label
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(AppTheme.AccentPurple.copy(alpha = 0.2f))
                            .clickable { onTimeFrame(InsightsViewModel.TimeFrame.ALL) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(timeLabel, color = AppTheme.AccentPurple,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium))
                        Spacer(Modifier.width(4.dp))
                        Text("✕", color = AppTheme.AccentPurple.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                activeFilters.sortedBy { it.key }.forEach { tag ->
                    val color = FilterCatColors[tag.category] ?: AppTheme.AccentPurple
                    ActiveFilterChip(tag, color, onToggle)
                }
            }
        }

        // Expanded: timeframe + all categories
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(Modifier.padding(top = 8.dp)) {
                // Timeframe selector
                Text("Time Range", color = AppTheme.AccentPurple.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(bottom = 2.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InsightsViewModel.TimeFrame.entries
                        .filter { it != InsightsViewModel.TimeFrame.NONE }
                        .forEach { tf ->
                        val isActive = tf == timeFrame
                        FilterOptionChip(
                            label = tf.label,
                            color = AppTheme.AccentPurple,
                            active = isActive,
                            onClick = {
                                if (tf == InsightsViewModel.TimeFrame.CUSTOM) {
                                    showFromPicker = true
                                } else {
                                    onTimeFrame(tf)
                                }
                            }
                        )
                    }
                }

                // Custom date range row (shown when custom is active or being set)
                AnimatedVisibility(visible = timeFrame == InsightsViewModel.TimeFrame.CUSTOM) {
                    val df = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // From button
                        Column(Modifier.weight(1f)) {
                            Text("From", color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.labelSmall)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .clickable { showFromPicker = true }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(df.format(customFrom), color = Color.White,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text("–", color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 12.dp))
                        // To button
                        Column(Modifier.weight(1f)) {
                            Text("To", color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.labelSmall)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .clickable { showToPicker = true }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(df.format(customTo), color = Color.White,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                // Tag categories + Overlay Metrics — only shown after a time range is selected
                if (timeFrame != InsightsViewModel.TimeFrame.NONE) {

                // Tag categories
                val orderedCats = FilterCatOrder.filter { it in availableTags }
                orderedCats.forEach { cat ->
                    val labels = availableTags[cat] ?: return@forEach
                    val color = FilterCatColors[cat] ?: AppTheme.AccentPurple
                    Text(cat, color = color.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        labels.forEach { label ->
                            val tag = InsightsViewModel.FilterTag(cat, label)
                            val isActive = tag in activeFilters
                            FilterOptionChip(label, color, isActive) { onToggle(tag) }
                        }
                    }
                }

                // ── Overlay Metrics ──
                if (availableMetrics.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Overlay Metrics", color = AppTheme.AccentPurple.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(bottom = 2.dp))
                    Text("Toggle metric lines on the graph. ⚡ = auto-detected.",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(4.dp))
                    val metricGroups = availableMetrics.groupBy { it.group }
                    metricGroups.forEach { (group, defs) ->
                        Text(group, color = AppTheme.SubtleTextColor.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            defs.forEach { d ->
                                val isAuto = d.key in autoSelectedKeys
                                val isEnabled = d.key in enabledKeys
                                DetailToggle(
                                    label = d.label,
                                    color = d.color,
                                    active = isEnabled,
                                    isAutoSelected = isAuto,
                                    onClick = { onToggleMetric(d.key) }
                                )
                            }
                        }
                    }
                }

                } // end if timeFrame != NONE
            }
        }
    }

    // ── Date picker dialogs ──
    if (showFromPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = customFrom.atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showFromPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        customFrom = java.time.Instant.ofEpochMilli(ms)
                            .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        if (customFrom.isAfter(customTo)) customTo = customFrom
                        onCustomRange(customFrom, customTo)
                    }
                    showFromPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showFromPicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = state)
        }
    }

    if (showToPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = customTo.atStartOfDay(java.time.ZoneOffset.UTC)
                .toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showToPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        customTo = java.time.Instant.ofEpochMilli(ms)
                            .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        if (customTo.isBefore(customFrom)) customFrom = customTo
                        onCustomRange(customFrom, customTo)
                    }
                    showToPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showToPicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
private fun ActiveFilterChip(
    tag: InsightsViewModel.FilterTag, color: Color,
    onRemove: (InsightsViewModel.FilterTag) -> Unit
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.2f))
            .clickable { onRemove(tag) }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(tag.label, color = color,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(4.dp))
        Text("✕", color = color.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun FilterOptionChip(
    label: String, color: Color, active: Boolean, onClick: () -> Unit
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
        Box(Modifier.size(6.dp).clip(CircleShape)
            .background(if (active) color else color.copy(alpha = 0.3f)))
        Spacer(Modifier.width(4.dp))
        Text(label, color = tc,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ═══════ Filtered spider cards (same layout as main page) ═══════

@Composable
private fun FilteredSpiderCard(data: SpiderData, secondAxes: List<SpiderAxis>? = null, onClick: () -> Unit = {}) {
    val color = colorForLogType(data.logType)
    BaseCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(24.dp)) {
                HubIcons.run {
                    when (data.logType) {
                        "Triggers" -> drawTriggerBolt(color)
                        "Prodromes" -> drawProdromeEye(color)
                        "Symptoms" -> drawMigraineStarburst(color)
                        "Medicines" -> drawMedicinePill(color)
                        "Reliefs" -> drawReliefLeaf(color)
                        "Activities" -> drawActivityPulse(color)
                        "Missed Activities" -> drawMissedActivity(color)
                        "Locations" -> drawLocationPin(color)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(data.logType, color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                Text("${data.totalLogged} logged • ${data.breakdown.size} categories",
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
            Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(8.dp))
        if (data.axes.size >= 3) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                SpiderChart(axes = data.axes, accentColor = color, size = 220.dp,
                    secondAxes = secondAxes, secondColor = Color.White.copy(alpha = 0.6f))
            }
        } else {
            StackedProportionalBar(axes = data.axes, accentColor = color)
        }
    }
}

@Composable
private fun FilteredSymptomsCard(
    migCount: Int,
    painChar: SpiderData?,
    accompanying: SpiderData?,
    onClick: () -> Unit = {}
) {
    BaseCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(24.dp)) { HubIcons.run { drawMigraineStarburst(AppTheme.AccentPink) } }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Symptoms", color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                Text("$migCount migraines",
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
            Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodyMedium)
        }
        painChar?.takeIf { it.axes.isNotEmpty() }?.let { data ->
            Spacer(Modifier.height(12.dp))
            Text("Pain Character", color = Color(0xFFEF5350),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(4.dp))
            if (data.axes.size >= 3) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SpiderChart(axes = data.axes, accentColor = Color(0xFFEF5350), size = 200.dp)
                }
            } else StackedProportionalBar(axes = data.axes, accentColor = Color(0xFFEF5350))
        }
        accompanying?.takeIf { it.axes.isNotEmpty() }?.let { data ->
            Spacer(Modifier.height(16.dp))
            Text("Accompanying Experience", color = Color(0xFFBA68C8),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(4.dp))
            if (data.axes.size >= 3) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SpiderChart(axes = data.axes, accentColor = Color(0xFFBA68C8), size = 200.dp)
                }
            } else StackedProportionalBar(axes = data.axes, accentColor = Color(0xFFBA68C8))
        }
    }
}

// ═══════ Private composables ═══════

@Composable
private fun DetailMigraineSelector(
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
                Text(df.format(sel.start) + " • " + tf.format(sel.start),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                val e = sel.end
                if (e != null) {
                    val d = Duration.between(sel.start, e)
                    val hStr = if (d.toHours() > 0) "${d.toHours()}h " else ""
                    Text("$hStr${d.minusHours(d.toHours()).toMinutes()}m • Severity: ${sel.severity ?: "–"}/10",
                        color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                } else {
                    Text("Severity: ${sel.severity ?: "–"}/10",
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
private fun DetailChip(n: Int, label: String, color: Color) {
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
private fun DetailToggle(
    label: String, color: Color, active: Boolean,
    isAutoSelected: Boolean, onClick: () -> Unit
) {
    val bg = if (active) color.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.04f)
    val tc = if (active) color else AppTheme.SubtleTextColor.copy(alpha = 0.5f)
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .then(
                if (isAutoSelected) Modifier // not clickable
                else Modifier.clickable(onClick = onClick)
            )
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

// ═══════ Shared: Window days control ═══════

@Composable
internal fun WindowDaysControl(
    before: Long,
    after: Long,
    onChanged: (before: Long, after: Long) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Before control
        Text("Before", color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(4.dp))
        SmallStepButton("−") { if (before > 1) onChanged(before - 1, after) }
        Text("${before}d", color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 6.dp))
        SmallStepButton("+") { if (before < 30) onChanged(before + 1, after) }

        Spacer(Modifier.width(16.dp))

        // After control
        Text("After", color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(4.dp))
        SmallStepButton("−") { if (after > 1) onChanged(before, after - 1) }
        Text("${after}d", color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 6.dp))
        SmallStepButton("+") { if (after < 30) onChanged(before, after + 1) }
    }
}

@Composable
private fun SmallStepButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(AppTheme.AccentPurple.copy(alpha = 0.2f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = AppTheme.AccentPurple,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
    }
}

/** Render InsightsTimelineGraph composable offscreen and capture as Bitmap */
private suspend fun captureTimelineGraph(
    activity: android.app.Activity,
    migraines: List<MigraineSpan>,
    events: List<EventMarker>,
    metricSeries: List<MetricSeries>,
    windowStart: java.time.Instant,
    windowEnd: java.time.Instant,
    highlightStart: java.time.Instant
): android.graphics.Bitmap? {
    return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val widthPx = (activity.resources.displayMetrics.density * 520).toInt()
        val heightPx = (activity.resources.displayMetrics.density * 220).toInt()

        val composeView = androidx.compose.ui.platform.ComposeView(activity).apply {
            setContent {
                InsightsTimelineGraph(
                    migraines = migraines,
                    events = events,
                    metricSeries = metricSeries,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    highlightMigraineStart = highlightStart,
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                )
            }
        }

        val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        composeView.layoutParams = android.view.ViewGroup.LayoutParams(widthPx, heightPx)
        root.addView(composeView)

        composeView.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    composeView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    composeView.postDelayed({
                        try {
                            val bitmap = android.graphics.Bitmap.createBitmap(
                                composeView.width.coerceAtLeast(1),
                                composeView.height.coerceAtLeast(1),
                                android.graphics.Bitmap.Config.ARGB_8888
                            )
                            val canvas = android.graphics.Canvas(bitmap)
                            composeView.draw(canvas)
                            root.removeView(composeView)
                            cont.resume(bitmap) {}
                        } catch (e: Exception) {
                            root.removeView(composeView)
                            cont.resume(null) {}
                        }
                    }, 150)
                }
            }
        )
    }
}