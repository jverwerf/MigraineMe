// FILE: InsightsReportScreen.kt
package com.migraineme

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
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

// ======= Filter category colors (matching the card / spider colors) =======

private val FilterCatColors = mapOf(
    "Severity" to Color(0xFFFF7043),
    // Symptom split into 3 pool-category buckets — matches iOS
    // ReportFilterCard.filterCategoryColors after the pain_character /
    // accompanying / Postdrome split. The Kotlin viewmodel emits these
    // bucket names directly (symptomBucket() in InsightsViewModel), so
    // FilterCatOrder must list them or the chips never render.
    "Pain Character" to Color(0xFFCE93D8),
    "Accompanying" to Color(0xFFBA68C8),
    "Postdrome" to Color(0xFFAB47BC),
    "Pain Location" to Color(0xFFFF8A80),
    "Trigger" to Color(0xFFFFB74D),
    "Prodrome" to Color(0xFF9575CD),
    "Medicine" to Color(0xFF4FC3F7),
    "Relief" to Color(0xFF81C784),
    "Activity" to Color(0xFFFF8A65),
    "Location" to Color(0xFF78909C),
    "Missed Activity" to Color(0xFFFF7043)
)

// ======= Ordered categories to match the migraine log flow =======

private val FilterCatOrder = listOf(
    "Severity", "Pain Character", "Accompanying", "Postdrome",
    "Pain Location", "Trigger", "Prodrome",
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

    //  Filter state 
    // Reset to NONE on first open so no metrics are pre-selected. The
    // generated flag has to reset with it: leaving it set while the filter
    // empties rendered a whole report claiming "No migraines logged yet" for
    // an account with a hundred of them.
    LaunchedEffect(Unit) {
        vm.setTimeFrame(InsightsViewModel.TimeFrame.NONE)
        vm.setReportGenerated(false)
    }
    val tagIndex by vm.migraineTagIndex.collectAsState()
    val activeFilters by vm.activeFilters.collectAsState()
    val timeFrame by vm.timeFrame.collectAsState()
    val customRange by vm.customRange.collectAsState()

    //  Filtered migraines 
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
    val allSymptoms by vm.allSymptoms.collectAsState()

    // Data for comprehensive doctor report
    val correlationStats by vm.correlationStats.collectAsState()
    val gaugeAccuracy by vm.gaugeAccuracy.collectAsState()
    val medEffectiveness by vm.medicineEffectiveness.collectAsState()
    val reliefEffectiveness by vm.reliefEffectiveness.collectAsState()
    val contextItems by vm.contextItems.collectAsState()
    val impactItems by vm.impactItems.collectAsState()
    val painLocationCounts by vm.painLocationCounts.collectAsState()
    val severityCounts by vm.severityCounts.collectAsState()
    // Newer clinical layers: pain timeline entries, where the pain migrates,
    // and whether treating earlier changed the peak. All three are shown on
    // their own Insights pages already; the report is the doctor-facing copy.
    val painPointsByMigraine by vm.painPointsByMigraine.collectAsState()
    val auraZonesByMigraine by vm.auraZonesByMigraine.collectAsState()
    val painMigration by vm.painMigration.collectAsState()
    val treatmentTiming by vm.treatmentTiming.collectAsState()
    val symptomStats by vm.symptomStats.collectAsState()
    val auraInsights by vm.auraInsights.collectAsState()
    val contextIconKeys by vm.contextIconKeys.collectAsState()
    val totalMigraineCount by vm.totalMigraineCount.collectAsState()
    val overallAvgSeverity = remember(migraines) {
        val severities = migraines.mapNotNull { it.severity }
        if (severities.isEmpty()) 5f else severities.average().toFloat()
    }

    // Profile data — fetched from ai_setup_profiles
    var clinicalAssessment by remember { mutableStateOf<String?>(null) }
    var profileSummary by remember { mutableStateOf<String?>(null) }
    var profileFrequency by remember { mutableStateOf<String?>(null) }
    var profileDuration by remember { mutableStateOf<String?>(null) }
    var profileExperience by remember { mutableStateOf<String?>(null) }
    var profileTrajectory by remember { mutableStateOf<String?>(null) }
    var profileGender by remember { mutableStateOf<String?>(null) }
    var profileAgeRange by remember { mutableStateOf<String?>(null) }
    var profileSeasonalPattern by remember { mutableStateOf<String?>(null) }
    var profileTracksCycle by remember { mutableStateOf(false) }
    var profileTriggerAreas by remember { mutableStateOf<List<String>>(emptyList()) }
    var profileFreeText by remember { mutableStateOf<String?>(null) }

    // Regimen-based treatments (matches Monitor → Treatments)
    var regimenTreatments by remember { mutableStateOf<List<SupabaseDbService.TreatmentLeaderboardRow>>(emptyList()) }
    var regimenNarratives by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var regimenSideEffects by remember { mutableStateOf<Map<String, List<SupabaseDbService.TreatmentSideEffectLogRow>>>(emptyMap()) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val token = SessionStore.getValidAccessToken(ctx) ?: return@withContext
                val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                val lb = db.getTreatmentLeaderboard(token)
                regimenTreatments = lb
                val narr = mutableMapOf<String, String>()
                val se = mutableMapOf<String, List<SupabaseDbService.TreatmentSideEffectLogRow>>()
                val today = java.time.LocalDate.now().toString()
                for (row in lb) {
                    runCatching { db.getTreatmentNarrative(token, row.regimenId)?.narrative }
                        .getOrNull()?.let { narr[row.regimenId] = it }
                    runCatching { db.getTreatmentSideEffectLogs(token, row.startDate, row.stopDate ?: today) }
                        .getOrNull()?.let { if (it.isNotEmpty()) se[row.regimenId] = it }
                }
                regimenNarratives = narr
                regimenSideEffects = se
            } catch (e: Exception) {
                android.util.Log.w("ReportScreen", "Failed to load regimens: ${e.message}")
            }
        }
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val token = SessionStore.getValidAccessToken(ctx) ?: return@withContext
                val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/ai_setup_profiles" +
                    "?select=clinical_assessment,summary,frequency,duration,experience,trajectory,gender,age_range,seasonal_pattern,tracks_cycle,trigger_areas,answers&limit=1"
                val client = okhttp3.OkHttpClient()
                val req = okhttp3.Request.Builder().url(url).get()
                    .header("Authorization", "Bearer $token")
                    .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .header("Accept", "application/vnd.pgrst.object+json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: "{}"
                        val obj = org.json.JSONObject(body)
                        val ca = obj.optString("clinical_assessment", "")
                        if (ca.isNotBlank()) clinicalAssessment = ca
                        val s = obj.optString("summary", "")
                        if (s.isNotBlank()) profileSummary = s
                        val f = obj.optString("frequency", "")
                        if (f.isNotBlank()) profileFrequency = f
                        val d = obj.optString("duration", "")
                        if (d.isNotBlank()) profileDuration = d
                        val e = obj.optString("experience", "")
                        if (e.isNotBlank()) profileExperience = e
                        val t = obj.optString("trajectory", "")
                        if (t.isNotBlank()) profileTrajectory = t
                        val g = obj.optString("gender", "")
                        if (g.isNotBlank()) profileGender = g
                        val a = obj.optString("age_range", "")
                        if (a.isNotBlank()) profileAgeRange = a
                        val sp = obj.optString("seasonal_pattern", "")
                        if (sp.isNotBlank()) profileSeasonalPattern = sp
                        profileTracksCycle = obj.optBoolean("tracks_cycle", false)
                        val ta = obj.optJSONArray("trigger_areas")
                        if (ta != null) {
                            profileTriggerAreas = (0 until ta.length()).mapNotNull { ta.optString(it).takeIf { s2 -> s2.isNotBlank() } }
                        }
                        val answers = obj.optJSONObject("answers")
                        val ft = answers?.optString("free_text", "") ?: ""
                        if (ft.isNotBlank()) profileFreeText = ft
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("ReportScreen", "Failed to load profile: ${e.message}")
            }
        }
    }

    val windowEvents = remember(linkedItems, allMissed, allActs, sel?.id) {
        val actsForMigraine = allActs.filter { it.migraineId == sel?.id }
        val missedForMigraine = allMissed.filter { it.migraineId == sel?.id }
        buildEventMarkers(linkedItems, actsForMigraine, missedForMigraine)
    }

    val autoSelectedKeys = remember(windowEvents) {
        windowEvents.filter { it.isAutomated }
            .flatMap { ev -> vm.metricKeysForLabel(ev.name) }
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
    val userDisabledKeys by vm.userDisabledMetrics.collectAsState()
    val enabledKeys = (allAutoSelectedKeys - userDisabledKeys) + userToggledKeys

    //  Report generation gate 
    val reportGenerated by vm.reportGenerated.collectAsState()

    val enabledSeries = remember(available, enabledKeys, allDailyMetrics, windowDates) {
        available.filter { it.key in enabledKeys }.map { d ->
            MetricSeries(d.key, d.label, d.unit, d.color,
                allDailyMetrics[d.key]!!
                    .filter { it.date in windowDates }
                    .map { DailyMetricPoint(it.date, it.value) })
        }
    }
    // Second pass — every other metric that has data in the window. Used
    // by the Health Metrics section to render "Overlay Metrics" first and
    // "All Available Metrics" below (matches iOS InsightsScreen split).
    val availableNotEnabledSeries = remember(available, enabledKeys, allDailyMetrics, windowDates) {
        available.filter { it.key !in enabledKeys }.map { d ->
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
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            // ========== 1. FILTER CARD (collapsible) ==========
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
                    vm.toggleMetric(key, key in enabledKeys)
                },
                metricSources = vm.metricSources.collectAsState().value
            )

            // ========== Spiders (computed regardless, needed for PDF) ==========
            val spiderLoading by vm.spiderLoading.collectAsState()
            val spiders = remember(filteredIds, spiderLoading) {
                if (spiderLoading) InsightsViewModel.FilteredSpiders()
                else vm.buildFilteredSpiders(filteredIds)
            }
            val filteredImpact = remember(filteredIds, spiderLoading) {
                if (spiderLoading) InsightsViewModel.FilteredImpactData()
                else vm.buildFilteredImpactData(filteredIds)
            }

            // ========== GENERATE / DOWNLOAD REPORT BUTTON ==========
            // Hidden until the user picks a time range (matches iOS — making
            // the chip selection the explicit gate so the user can't generate
            // an empty / un-scoped report).
            if (!reportGenerated && timeFrame != InsightsViewModel.TimeFrame.NONE) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { vm.setReportGenerated(true) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppTheme.AccentPurple
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        t("Generate Report"),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }

            // ========== REPORT CONTENT (shown after Generate) ==========
            if (reportGenerated) {

            // ========== DOWNLOAD REPORT BUTTON ==========
            Spacer(Modifier.height(12.dp))
            var isGeneratingPdf by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()

            Button(
                onClick = {
                    isGeneratingPdf = true
                    android.util.Log.d("ReportPDF", "Starting PDF generation")
                    val timeLabel = try {
                        when (timeFrame) {
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
                            if (cr != null) "${cr.from} -- ${cr.to}" else "Custom"
                        }
                    }
                    } catch (e: Exception) {
                        android.util.Log.e("ReportPDF", "timeLabel error", e)
                        "Unknown"
                    }
                    android.util.Log.d("ReportPDF", "timeLabel=$timeLabel, filteredSorted=${filteredSorted.size}")
                    // Same bounds as filteredSorted so the PDF matches the screen:
                    // custom ranges are start-of-day inclusive / day-after exclusive,
                    // preset ranges are a rolling instant lower bound with no upper.
                    val isoFmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
                    val fromIso: String?
                    val toIso: String?
                    when {
                        timeFrame == InsightsViewModel.TimeFrame.CUSTOM && customRange != null -> {
                            fromIso = customRange!!.from.atStartOfDay(zone).format(isoFmt)
                            toIso = customRange!!.to.plusDays(1).atStartOfDay(zone).format(isoFmt)
                        }
                        timeFrame.days != null -> {
                            fromIso = Instant.now().minus(Duration.ofDays(timeFrame.days!!.toLong()))
                                .atZone(zone).format(isoFmt)
                            toIso = null
                        }
                        else -> { fromIso = null; toIso = null }
                    }
                    val filterSummary = if (activeFilters.isNotEmpty()) {
                        "$timeLabel · ${activeFilters.joinToString(", ") { it.label }}"
                    } else null
                    scope.launch {
                        try {
                            // The document is built by the `build-report-html`
                            // edge function and printed here, so every surface
                            // ships the same report without four drawing
                            // engines drifting apart.
                            val html = EdgeFunctionsService().getReportHtml(
                                context = ctx,
                                timeframeLabel = timeLabel,
                                from = fromIso,
                                to = toIso,
                                metricKeys = enabledKeys.toList(),
                                episodeIds = filteredSorted.mapNotNull { it.id },
                                disabledMetricKeys = userDisabledKeys.toList(),
                                filterSummary = filterSummary,
                            )
                            if (html == null) {
                                isGeneratingPdf = false
                                android.widget.Toast.makeText(
                                    ctx, "Couldn't build the report — check your connection",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                                return@launch
                            }
                            val file = ReportHtmlPrinter.renderToPdf(ctx, html)
                            isGeneratingPdf = false
                            if (file != null) ReportHtmlPrinter.share(ctx, file)
                            else android.widget.Toast.makeText(
                                ctx, "Failed to generate PDF", android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        } catch (e: Exception) {
                            isGeneratingPdf = false
                            android.util.Log.e("ReportPDF", "PDF error", e)
                            android.widget.Toast.makeText(
                                ctx, "PDF error: ${e.message}", android.widget.Toast.LENGTH_LONG,
                            ).show()
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
                    Text(t("Generating PDF…"), color = Color.White, style = MaterialTheme.typography.titleSmall)
                } else if (spiderLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White.copy(alpha = 0.5f))
                    Spacer(Modifier.width(8.dp))
                    Text(t("Loading…"), color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.titleSmall)
                } else {
                    Text(t("Download Report"), color = Color.White,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                }
            }

            Spacer(Modifier.height(12.dp))

            // ========== 1. FREQUENCY TRENDS (filtered) ==========
            //   Order: Day of Week -> Weekly -> Monthly Frequency -> Monthly Duration -> Seasonal
            if (filteredSorted.size >= 2) {
                val filteredByMonth = remember(filteredSorted) {
                    filteredSorted.groupBy {
                        it.start.atZone(zone).toLocalDate().withDayOfMonth(1)
                    }.toSortedMap()
                }
                val filteredByWeek = remember(filteredSorted) {
                    filteredSorted.groupBy {
                        val d = it.start.atZone(zone).toLocalDate()
                        d.minusDays(d.dayOfWeek.value.toLong() - 1)
                    }.toSortedMap()
                }
                val filteredDayOfWeek = remember(filteredSorted) {
                    val grouped = filteredSorted.groupBy {
                        it.start.atZone(zone).toLocalDate().dayOfWeek.value
                    }
                    val total = filteredSorted.size.coerceAtLeast(1)
                    (1..7).map { day ->
                        val count = grouped[day]?.size ?: 0
                        InsightsViewModel.DayOfWeekStat(
                            dayName = java.time.DayOfWeek.of(day).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()),
                            dayIndex = day,
                            count = count,
                            pct = count * 100f / total
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BrainyBlobIcon(resId = R.drawable.brainy_migraines_small, flip = true)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t("Frequency Trends"), color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        Text(t("When the attacks fall and how that is moving"),
                            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))

                // 1. Day of Week
                if (filteredDayOfWeek.any { it.count > 0 }) {
                    BaseCard {
                        Text(t("Day of Week"), color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.height(8.dp))
                        val maxPct = filteredDayOfWeek.maxOf { it.pct }.coerceAtLeast(1f)
                        Row(
                            Modifier.fillMaxWidth().height(120.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            filteredDayOfWeek.sortedBy { it.dayIndex }.forEach { stat ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("${stat.count}", color = Color.White,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                    Spacer(Modifier.height(2.dp))
                                    val barH = (stat.pct / maxPct * 80f).coerceAtLeast(4f)
                                    val barColor = frequencyBarColor(stat.pct, maxPct)
                                    Canvas(Modifier.fillMaxWidth(0.6f).height(barH.dp)) {
                                        drawRoundRect(barColor, cornerRadius = CornerRadius(4f, 4f))
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(stat.dayName, color = AppTheme.SubtleTextColor,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

                // 2. Weekly
                if (filteredByWeek.size >= 3) {
                    BaseCard {
                        Text(t("Weekly Frequency"), color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.height(8.dp))
                        val weeks = filteredByWeek.keys.toList()
                        val weekCounts = weeks.map { filteredByWeek[it]?.size ?: 0 }
                        val maxWk = weekCounts.max().coerceAtLeast(1)
                        val avgWk = weekCounts.average().toFloat()
                        WeeklyBarChart(weeks, weekCounts, maxWk, avgWk,
                            Modifier.fillMaxWidth().height(160.dp))
                    }
                }

                // 3. Monthly Frequency
                if (filteredByMonth.size >= 2) {
                    BaseCard {
                        Text(t("Monthly Frequency"), color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.height(8.dp))
                        val months = filteredByMonth.keys.toList()
                        val counts = months.map { filteredByMonth[it]?.size ?: 0 }
                        val maxCount = counts.max().coerceAtLeast(1)
                        val avgCount = counts.average().toFloat()
                        MonthlyBarChart(months, counts, maxCount, avgCount,
                            Modifier.fillMaxWidth().height(200.dp))
                    }
                }

                // 4. Monthly Duration (avg attack length per month, in hours)
                val monthsWithDur = remember(filteredSorted) {
                    filteredByMonth.mapNotNull { (monthStart, items) ->
                        val durs = items.mapNotNull { m ->
                            val end = m.end ?: return@mapNotNull null
                            val hours = java.time.Duration.between(m.start, end).toMinutes() / 60.0
                            if (hours <= 0 || hours >= 168) null else hours
                        }
                        if (durs.isEmpty()) null
                        else Triple(monthStart, durs.average().toFloat(), durs.size)
                    }
                }
                if (monthsWithDur.isNotEmpty()) {
                    BaseCard {
                        Text(t("Monthly Duration"), color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.height(8.dp))
                        val maxAvg = monthsWithDur.maxOf { it.second }.coerceAtLeast(1f)
                        val durFmt = DateTimeFormatter.ofPattern("MMM")
                        Row(
                            Modifier.fillMaxWidth().height(120.dp).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            monthsWithDur.forEach { (monthStart, avg, _) ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(String.format("%.1fh", avg), color = AppTheme.AccentPink,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                    Spacer(Modifier.height(2.dp))
                                    val barH = (avg / maxAvg * 80f).coerceAtLeast(4f)
                                    Canvas(Modifier.width(24.dp).height(barH.dp)) {
                                        drawRoundRect(frequencyBarColor(avg, maxAvg).copy(alpha = 0.7f), cornerRadius = CornerRadius(4f, 4f))
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(monthStart.format(durFmt), color = AppTheme.SubtleTextColor,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

                // 5. Seasonal (NH meteorological buckets)
                val seasonalCounts = remember(filteredSorted) {
                    val c = IntArray(4)
                    filteredSorted.forEach {
                        val month = it.start.atZone(zone).toLocalDate().monthValue
                        c[(month % 12) / 3]++
                    }
                    c.toList()
                }
                if (seasonalCounts.sum() > 0) {
                    val labels = listOf("Winter", "Spring", "Summer", "Autumn")
                    val maxSeason = seasonalCounts.max().coerceAtLeast(1)
                    val total = seasonalCounts.sum()
                    BaseCard {
                        Text(t("Seasonal"), color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth().height(120.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            seasonalCounts.forEachIndexed { i, count ->
                                val barColor = frequencyBarColor(count.toFloat(), maxSeason.toFloat(), FrequencyPinkRamp)
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("$count", color = Color.White,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                    Spacer(Modifier.height(2.dp))
                                    val barH = (count.toFloat() / maxSeason * 80f).coerceAtLeast(4f)
                                    Canvas(Modifier.width(24.dp).height(barH.dp)) {
                                        drawRoundRect(barColor.copy(alpha = 0.8f), cornerRadius = CornerRadius(4f, 4f))
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(t(labels[i]), color = AppTheme.SubtleTextColor,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        val worstIdx = seasonalCounts.indices.maxByOrNull { seasonalCounts[it] }
                        if (worstIdx != null && seasonalCounts[worstIdx] > 0 && total > 0) {
                            val pct = seasonalCounts[worstIdx].toFloat() / total * 100f
                            Spacer(Modifier.height(8.dp))
                            Text(t("Most frequent: %1\$s (%2\$s migraines, %3\$s%%)", labels[worstIdx], seasonalCounts[worstIdx], String.format("%.0f", pct)),
                                color = AppTheme.BodyTextColor,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            // ========== 2. WHAT HAPPENED (all time) ==========
            // Include both `trigger` and `metric` factor types so the Full
            // Report matches iOS (InsightsScreen.swift previewTriggers). The
            // metric bucket carries the sleep / weather / HRV patterns that
            // were silently dropped before — same account showed different
            // top items between iOS and Android because of this filter.
            val previewTriggers = remember(correlationStats) {
                correlationStats.filter { (it.hasGateMode || it.isSignificant()) && it.symptomOutcome == null && (it.factorType == "trigger" || it.factorType == "metric") }
                    .sortedByDescending { it.liftRatio }.take(3)
            }
            val previewInteractions = remember(correlationStats) {
                correlationStats.filter { (it.hasGateMode || it.isSignificant()) && it.factorType == "interaction" }
                    .sortedByDescending { it.liftRatio }.take(3)
            }
            if (previewTriggers.isNotEmpty() || previewInteractions.isNotEmpty()) {
                Column {
                    PatternsPreviewCard(
                        patterns = previewTriggers,
                        interactions = previewInteractions,
                        onShowAll = { navController.navigate(Routes.INSIGHTS_PATTERNS) }
                    )
                    Text(t("  Based on all time data"), color = AppTheme.SubtleTextColor.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelSmall,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                }
            }

            // ========== 3a. TREATMENTS (regimen-based, matches Monitor → Treatments) ==========
            val filteredRegimens = remember(regimenTreatments, filteredSorted) {
                if (filteredSorted.isEmpty()) regimenTreatments
                else {
                    val dates = filteredSorted.map { it.start.atZone(zone).toLocalDate() }
                    val winStart = dates.min()
                    val winEnd = (dates.max()).plusDays(1)
                    regimenTreatments.filter { r ->
                        val s = runCatching { java.time.LocalDate.parse(r.startDate) }.getOrNull() ?: java.time.LocalDate.MIN
                        val e = r.stopDate?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } ?: java.time.LocalDate.MAX
                        !s.isAfter(winEnd) && !e.isBefore(winStart)
                    }
                }
            }
            if (filteredRegimens.isNotEmpty()) {
                ReportRegimenTreatmentsCard(
                    rows = filteredRegimens,
                    narratives = regimenNarratives,
                    sideEffects = regimenSideEffects,
                )
            }

            // ========== 3a-ii. TREATMENT TIMING (early vs late, all-time) ==========
            // The engine only writes a row once both buckets clear >= 3 attacks
            // and the gap is >= 1.5 points, so anything here is safe to print
            // as-is. Deliberately all-time and not filtered: the split is the
            // user's own median delay across their whole history.
            if (treatmentTiming.isNotEmpty()) {
                Column {
                    TreatmentTimingCard(treatmentTiming)
                    Text(t("  Based on all time data"), color = AppTheme.SubtleTextColor.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelSmall,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                }
            }

            // ========== 3. WHAT WORKED (all time) ==========
            val previewTreatments = remember(correlationStats) {
                correlationStats.filter { it.factorType == "treatment" && it.liftRatio > 1.2f }
                    .sortedByDescending { it.liftRatio }.take(3)
            }
            val previewTreatmentInteractions = remember(correlationStats) {
                correlationStats.filter { it.factorType == "treatment_interaction" && it.liftRatio > 1.2f }
                    .sortedByDescending { it.liftRatio }.take(3)
            }
            if (previewTreatments.isNotEmpty() || previewTreatmentInteractions.isNotEmpty()) {
                Column {
                    TreatmentPreviewCard(
                        treatments = previewTreatments,
                        treatmentInteractions = previewTreatmentInteractions,
                        onShowAll = { navController.navigate(Routes.INSIGHTS_TREATMENTS) }
                    )
                    Text(t("  Based on all time data"), color = AppTheme.SubtleTextColor.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelSmall,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                }
            }

            // ========== 3b. WHAT'S HELPING (Well Done layer, all-time) ==========
            val previewWellDone = remember(correlationStats) {
                correlationStats.filter { it.factorType == "well_done" }
                    .sortedByDescending { it.liftRatio }.take(3)
            }
            val previewWellDoneChains = remember(correlationStats) {
                correlationStats.filter { it.factorType == "well_done_chain" }
                    .sortedByDescending { it.liftRatio }.take(3)
            }
            if (previewWellDone.isNotEmpty() || previewWellDoneChains.isNotEmpty()) {
                Column {
                    // Gardener is What's Helping's costume on the Insights hub;
                    // the report section wears the same one so the two read as
                    // the same thing.
                    BrainyWatermarkCard(resId = R.drawable.brainy_gardener, flipWatermark = true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BrainyBlobIcon(resId = R.drawable.brainy_gardener_small)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t("What's Helping"), color = AppTheme.TitleColor,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                                Text(t("Habits that show up on your migraine-free days"),
                                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { navController.navigate(Routes.INSIGHTS_WHATS_HELPING) }) {
                                Text(t("All →"), color = AppTheme.AccentPurple)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        previewWellDone.forEach { stat -> WellDoneDirectRow(stat) }
                        previewWellDoneChains.forEach { stat -> WellDoneChainRow(stat) }
                    }
                    Text(t("  Based on all time data"), color = AppTheme.SubtleTextColor.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelSmall,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                }
            }

            // ========== 4. WHAT WERE YOU DOING (filtered) ==========
            if (filteredImpact.contextItems.isNotEmpty()) {
                // contextIconKeys gives the rows their Brainy log art, the same
                // icons the user sees when logging the activity or location.
                ContextCard(filteredImpact.contextItems.take(5), filteredImpact.overallAvgSeverity,
                    onClick = { navController.navigate(Routes.INSIGHTS_CONTEXT) },
                    contextIconKeys = contextIconKeys)
            }

            // ========== 5. HOW DID IT IMPACT YOU (filtered) ==========
            // Aura zones / duration and the top symptoms were already computed
            // for the PDF but never rendered on screen. ImpactCard has taken
            // both since the aura-detail build, so this is a wiring gap, not a
            // new card.
            val hasAura = filteredImpact.auraZoneCounts.isNotEmpty() && filteredImpact.auraAttackCount > 0
            if (filteredImpact.severityCounts.isNotEmpty() || filteredImpact.painLocationCounts.isNotEmpty() ||
                filteredImpact.impactItems.isNotEmpty() || hasAura) {
                ImpactCard(
                    impactItems = filteredImpact.impactItems.take(3),
                    painLocationCounts = filteredImpact.painLocationCounts,
                    severityCounts = filteredImpact.severityCounts,
                    totalMigraines = filteredImpact.totalMigraineCount,
                    overallAvgSeverity = filteredImpact.overallAvgSeverity,
                    topSymptoms = symptomStats.take(5),
                    auraZoneCounts = filteredImpact.auraZoneCounts,
                    auraAttacks = filteredImpact.auraAttackCount,
                    auraDurationStats = filteredImpact.auraDurationStats,
                    auraInsights = auraInsights,
                    onClick = { navController.navigate(Routes.INSIGHTS_IMPACT) },
                )
            }

            // ========== 5a. HOW YOUR PAIN MOVES (all-time) ==========
            // Null unless >= 3 attacks with a real multi-entry timeline agreed
            // on a pattern, so there is no empty-state branch to write here.
            painMigration?.let { pm ->
                Column {
                    PainMigrationCard(pm)
                    Text(t("  Based on all time data"), color = AppTheme.SubtleTextColor.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.labelSmall,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                }
            }

            // ========== 6. ATTACK LOG ==========
            // One card per attack: the person, the aura eyes, the window of
            // metrics and events, and the sequence — the same composition the
            // PDF prints, so the screen and the report cannot disagree.
            if (filteredSorted.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                ReportSectionHeader(
                    title = t("Attack log"),
                    subtitle = t("Every attack in range, in the order things happened"),
                    resId = R.drawable.brainy_briefcase_small,
                )
                Spacer(Modifier.height(8.dp))

                // Window stepper: every attack card honours it, so the reader
                // can widen the context without leaving the report.
                WindowDaysControl(wBefore, wAfter, onChanged = { b, a -> vm.setWindowDays(b, a) })

                val metricSources by vm.metricSources.collectAsState()
                if (metricSources.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    SourceBadgeRow(metricSources.sorted())
                }
                Spacer(Modifier.height(8.dp))

                val symptomCats = remember { vm.catSymptomMap() }
                filteredSorted.take(ATTACK_CARDS_SHOWN).forEach { attack ->
                    val id = attack.id
                    val detail = remember(attack, painPointsByMigraine, allSymptoms) {
                        buildReportAttackDetail(attack, painPointsByMigraine, allSymptoms)
                    }
                    val aStart = attack.start.minus(Duration.ofDays(wBefore))
                    val aEnd = (attack.end ?: attack.start).plus(Duration.ofDays(wAfter))
                    val aDates = remember(aStart, aEnd) {
                        val f = LocalDate.ofInstant(aStart, zone)
                        val t = LocalDate.ofInstant(aEnd, zone)
                        generateSequence(f) { it.plusDays(1) }.takeWhile { !it.isAfter(t) }
                            .map { it.toString() }.toSet()
                    }
                    val aSeries = remember(aDates, enabledKeys, allDailyMetrics) {
                        AllMetricDefs.filter { it.key in enabledKeys }.mapNotNull { d ->
                            val pts = allDailyMetrics[d.key]?.filter { it.date in aDates }.orEmpty()
                            if (pts.size < 2) null
                            else MetricSeries(d.key, d.label, d.unit, d.color,
                                pts.map { DailyMetricPoint(it.date, it.value) })
                        }
                    }
                    AttackCard(
                        mg = attack,
                        detail = detail,
                        events = if (id == sel?.id) windowEvents else emptyList(),
                        metrics = aSeries,
                        windowStart = aStart,
                        windowEnd = aEnd,
                        linked = if (id == sel?.id) linkedItems else null,
                        symptoms = allSymptoms.filter { it.migraineId == id },
                        symptomCategories = symptomCats,
                        auraZones = auraZonesByMigraine[id].orEmpty(),
                    )
                }
                if (filteredSorted.size > ATTACK_CARDS_SHOWN) {
                    Text(
                        t("Showing %1\$s of %2\$s attacks · the PDF includes all %3\$s", ATTACK_CARDS_SHOWN, filteredSorted.size, filteredSorted.size),
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    )
                }
            }

            // ========== 7. SPIDERS (filtered) ==========

            if (filteredSorted.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BrainyBlobIcon(resId = R.drawable.brainy_migraines_small)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t("What You Logged"), color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        Text(t("Every log type across the attacks in range"),
                            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                val hasMigraineSyms = (spiders.painChar?.totalLogged ?: 0) > 0
                    || (spiders.accompanying?.totalLogged ?: 0) > 0
                    || (spiders.postdrome?.totalLogged ?: 0) > 0
                if (hasMigraineSyms) {
                    FilteredSymptomsCard(
                        migCount = filteredSorted.size,
                        painChar = spiders.painChar,
                        accompanying = spiders.accompanying,
                        postdrome = spiders.postdrome,
                        onClick = {
                            vm.setReportBreakdownFilter(spiders)
                            navController.navigate("${Routes.INSIGHTS_BREAKDOWN}/Migraines")
                        }
                    )
                }

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
                            vm.setReportBreakdownFilter(spiders)
                            navController.navigate("${Routes.INSIGHTS_BREAKDOWN}/${sp.logType}")
                        }
                    }
                }
            }

            // ========== 8. HEALTH METRICS (filtered) ==========
            if (enabledSeries.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BrainyBlobIcon(resId = R.drawable.brainy_physical_small)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t("Health Metrics"), color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        Text(t("Everything tracked around the attacks in range"),
                            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                }
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
                                    Text(t("AUTO"),
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
                                Text(t("Min: %s", "%.1f".format(minV)), color = AppTheme.SubtleTextColor,
                                    style = MaterialTheme.typography.labelSmall)
                                Text(t("Avg: %s", "%.1f".format(avgV)), color = AppTheme.SubtleTextColor,
                                    style = MaterialTheme.typography.labelSmall)
                                Text(t("Max: %s", "%.1f".format(maxV)), color = AppTheme.SubtleTextColor,
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

// ======= Filter card =======

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
    onToggleMetric: (String) -> Unit = {},
    metricSources: Set<String> = emptySet()
) {
    var expanded by remember { mutableStateOf(true) }
    var showFilterByInfo by remember { mutableStateOf(false) }
    var showOverlayMetricsInfo by remember { mutableStateOf(false) }
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
        // Header row – always visible, tappable
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.FilterList,
                contentDescription = t("Filter"),
                tint = if (hasFilters) AppTheme.AccentPurple else AppTheme.SubtleTextColor,
                modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                val totalActive = activeCount + enabledMetricCount
                Text(
                    if (hasFilters || enabledMetricCount > 0) t("Select Metrics (%s)", totalActive)
                    else t("Select Metrics"),
                    color = if (hasFilters || enabledMetricCount > 0) Color.White else AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                if (hasFilters) {
                    Text(t("%1\$s of %2\$s migraines", filteredCount, totalCount),
                        color = AppTheme.AccentPurple,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            if (hasFilters) {
                Text(t("Clear"),
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
                contentDescription = t("Expand"),
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
                        "${df.format(customRange.from)} — ${df.format(customRange.to)}"
                    } else t(timeFrame.label)
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
                Text(t("Time Range"), color = AppTheme.AccentPurple.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(bottom = 2.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Time range chips — reduced set: All Time, 1 Year,
                    // 6 Months, 3 Months, 1 Month, Custom. Day-grained
                    // chips (7d / 14d) hidden to keep the row simple.
                    listOf(
                        InsightsViewModel.TimeFrame.ALL,
                        InsightsViewModel.TimeFrame.YEAR_1,
                        InsightsViewModel.TimeFrame.MONTH_6,
                        InsightsViewModel.TimeFrame.MONTH_3,
                        InsightsViewModel.TimeFrame.MONTH_1,
                        InsightsViewModel.TimeFrame.CUSTOM,
                    ).forEach { tf ->
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
                            Text(t("From"), color = AppTheme.SubtleTextColor,
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
                        Text("-", color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 12.dp))
                        // To button
                        Column(Modifier.weight(1f)) {
                            Text(t("To"), color = AppTheme.SubtleTextColor,
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

                // Tag categories + Overlay Metrics – only shown after a time range is selected
                if (timeFrame != InsightsViewModel.TimeFrame.NONE) {

                // "Filter by" tinted section — light purple box matches iOS
                // so the chip filters read as a distinct interactive group
                // under the time-range row. Header + helper colors match the
                // Overlay Metrics box below for visual consistency.
                Spacer(Modifier.height(18.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppTheme.AccentPurple.copy(alpha = 0.10f))
                        .border(1.dp, AppTheme.AccentPurple.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t("Filter by"),
                            color = AppTheme.AccentPurple,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        IconButton(
                            onClick = { showFilterByInfo = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = t("About Filter by"),
                                tint = AppTheme.AccentPurple.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(t("Tap any chip below to limit the report to attacks matching it."),
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodyMedium)

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
                                FilterOptionChip(prettyLabel(label), color, isActive) { onToggle(tag) }
                            }
                        }
                    }
                }

                //  Overlay Metrics — matching tinted section.
                if (availableMetrics.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(AppTheme.AccentPurple.copy(alpha = 0.10f))
                            .border(1.dp, AppTheme.AccentPurple.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t("Overlay Metrics"),
                                color = AppTheme.AccentPurple,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            IconButton(
                                onClick = { showOverlayMetricsInfo = true },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = t("About Overlay Metrics"),
                                    tint = AppTheme.AccentPurple.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(t("Tap any metric to add it to the graph. Highlighted ones have been detected in your migraine data."),
                            color = AppTheme.BodyTextColor,
                            style = MaterialTheme.typography.bodyMedium)
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
                }

                // Source badges removed per product decision (matches iOS).

                } // end if timeFrame != NONE
            }
        }

        // "Filter by" / "Overlay Metrics" info dialogs — same copy as iOS so
        // the explanation about chips only showing what data exists is
        // surfaced on both platforms.
        if (showFilterByInfo) {
            AlertDialog(
                onDismissRequest = { showFilterByInfo = false },
                confirmButton = {
                    TextButton(onClick = { showFilterByInfo = false }) {
                        Text(t("Got it"), color = AppTheme.AccentPurple)
                    }
                },
                title = {
                    Text(t("About Filter by"), color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                },
                text = {
                    Text(
                        t("Tap any chip below to narrow the report down to just the migraine attacks that have that severity / symptom / trigger / medicine / etc. Multiple chips stack — picking a Trigger plus a Symptom shows only attacks with both. Chips are grouped by category and only the values that appear in your logs show up here."),
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodyMedium)
                },
                containerColor = AppTheme.BaseCardContainer
            )
        }
        if (showOverlayMetricsInfo) {
            AlertDialog(
                onDismissRequest = { showOverlayMetricsInfo = false },
                confirmButton = {
                    TextButton(onClick = { showOverlayMetricsInfo = false }) {
                        Text(t("Got it"), color = AppTheme.AccentPurple)
                    }
                },
                title = {
                    Text(t("About Overlay Metrics"), color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                },
                text = {
                    Text(
                        t("Toggle metrics on or off to overlay them on the timeline graph below. We only show metrics you actually have data for — if a metric isn't here, that table is empty for your account (e.g. no sleep stages logged means no Deep / REM / Light chips). Highlighted ones were auto-detected from triggers / prodromes you've logged."),
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodyMedium)
                },
                containerColor = AppTheme.BaseCardContainer
            )
        }
    }

    //  Date picker dialogs
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
                }) { Text(t("OK")) }
            },
            dismissButton = {
                TextButton(onClick = { showFromPicker = false }) { Text(t("Cancel")) }
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
                }) { Text(t("OK")) }
            },
            dismissButton = {
                TextButton(onClick = { showToPicker = false }) { Text(t("Cancel")) }
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
        Text(t(tag.label), color = color,
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
        Text(t(label), color = tc,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ======= Filtered spider cards (same layout as main page) =======

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
                Text(t("%1\$s logged • %2\$s categories", data.totalLogged, data.breakdown.size),
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
    postdrome: SpiderData?,
    onClick: () -> Unit = {}
) {
    BaseCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(24.dp)) { HubIcons.run { drawMigraineStarburst(AppTheme.AccentPink) } }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t("Migraines"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                Text(t("%s migraines", migCount),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
            Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodyMedium)
        }
        painChar?.takeIf { it.axes.isNotEmpty() }?.let { data ->
            Spacer(Modifier.height(12.dp))
            Text(t("Pain Character"), color = Color(0xFFEF5350),
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
            Text(t("Accompanying Experience"), color = Color(0xFFBA68C8),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(4.dp))
            if (data.axes.size >= 3) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SpiderChart(axes = data.axes, accentColor = Color(0xFFBA68C8), size = 200.dp)
                }
            } else StackedProportionalBar(axes = data.axes, accentColor = Color(0xFFBA68C8))
        }
        postdrome?.takeIf { it.axes.isNotEmpty() }?.let { data ->
            Spacer(Modifier.height(16.dp))
            Text(t("Postdrome"), color = Color(0xFF4DB6AC),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(4.dp))
            if (data.axes.size >= 3) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SpiderChart(axes = data.axes, accentColor = Color(0xFF4DB6AC), size = 200.dp)
                }
            } else StackedProportionalBar(axes = data.axes, accentColor = Color(0xFF4DB6AC))
        }
    }
}

// ======= Private composables =======

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
            Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Older"),
                tint = if (idx < sorted.size - 1) AppTheme.AccentPurple
                else AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                modifier = Modifier.size(22.dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
            Text(sel?.label ?: t("Migraine"), color = Color.White,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sel != null) {
                Text(df.format(sel.start) + " • " + tf.format(sel.start),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                val e = sel.end
                if (e != null) {
                    val d = Duration.between(sel.start, e)
                    val hStr = if (d.toHours() > 0) "${d.toHours()}h " else ""
                    Text(t("%1\$s%2\$sm • Severity: %3\$s/10", hStr, d.minusHours(d.toHours()).toMinutes(), sel.severity ?: "-"),
                        color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                } else {
                    Text(t("Severity: %s/10", sel.severity ?: "-"),
                        color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(t("%1\$s of %2\$s", idx + 1, sorted.size), color = AppTheme.AccentPurple,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
        }
        IconButton(onClick = onNext, enabled = idx > 0) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, t("Newer"),
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
        Text(t(label), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
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
        Text(t(label), color = tc,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium))
    }
}

// ======= Shared: Window days control =======

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
        Text(t("Before"), color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(4.dp))
        SmallStepButton("−") { if (before > 1) onChanged(before - 1, after) }
        Text("${before}d", color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 6.dp))
        SmallStepButton("+") { if (before < 30) onChanged(before + 1, after) }

        Spacer(Modifier.width(16.dp))

        // After control
        Text(t("After"), color = AppTheme.SubtleTextColor,
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
        Text(t(label), color = AppTheme.AccentPurple,
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

// ─────────────────────────────────────────────────────────────────────
// Regimen-based treatments card (matches Monitor → Treatments)
// ─────────────────────────────────────────────────────────────────────

@Composable
private fun ReportRegimenTreatmentsCard(
    rows: List<SupabaseDbService.TreatmentLeaderboardRow>,
    narratives: Map<String, String>,
    sideEffects: Map<String, List<SupabaseDbService.TreatmentSideEffectLogRow>>,
) {
    val sorted = remember(rows) {
        rows.sortedBy { it.pctChangeMmd ?: Double.POSITIVE_INFINITY }
    }
    val pink = androidx.compose.ui.graphics.Color(0xFFFF7BB0)

    BrainyWatermarkCard(resId = R.drawable.brainy_treatments, flipWatermark = true) {
        ReportSectionHeader(
            title = t("Treatments"),
            subtitle = t("How each regimen has changed your migraine days"),
            resId = R.drawable.brainy_treatments_small,
        )
        Spacer(Modifier.height(8.dp))
        sorted.forEachIndexed { idx, r ->
            RegimenRow(r, narratives[r.regimenId], sideEffects[r.regimenId] ?: emptyList(), pink)
            if (idx < sorted.size - 1) {
                Divider(color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f),
                    modifier = Modifier.padding(vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun RegimenRow(
    r: SupabaseDbService.TreatmentLeaderboardRow,
    narrative: String?,
    logs: List<SupabaseDbService.TreatmentSideEffectLogRow>,
    pink: androidx.compose.ui.graphics.Color,
) {
    val band = r.band
    val bandColor = regimenBandColor(band)
    val bandLabel = regimenBandLabel(band)
    val pctText = r.pctChangeMmd?.let { String.format("%+.0f%%", it) } ?: "—"
    val dose = listOfNotNull(r.amount, r.frequency).joinToString(" · ")
    val weeks = runCatching {
        val s = java.time.LocalDate.parse(r.startDate)
        java.time.temporal.ChronoUnit.WEEKS.between(s, java.time.LocalDate.now()).coerceAtLeast(0L).toInt()
    }.getOrNull() ?: 0
    val sub = listOfNotNull(r.kind, dose.ifBlank { null }, "$weeks wks").joinToString(" · ")

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrainyRowIcon(r.name, category = r.kind, size = 20.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(r.name, color = androidx.compose.ui.graphics.Color.White,
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text(sub, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.labelSmall)
            }
            if (band == "not_enough_data") {
                Text(t("not enough data"), color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.labelSmall)
            } else {
                val mag = r.pctChangeMmd?.let { minOf(Math.abs(it), 100.0) / 100.0 } ?: 0.0
                Box(modifier = Modifier.width(54.dp).height(5.dp)
                    .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f),
                        androidx.compose.foundation.shape.RoundedCornerShape(3.dp))) {
                    Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(mag.toFloat())
                        .background(bandColor, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)))
                }
                Spacer(Modifier.width(8.dp))
                Text(pctText, color = bandColor, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.End)
            }
        }
        if (band != "not_enough_data") {
            Text(bandLabel, color = bandColor, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelSmall)
        }
        if (!narrative.isNullOrBlank()) {
            Text(narrative, color = AppTheme.BodyTextColor,
                style = MaterialTheme.typography.bodySmall, maxLines = 4,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (logs.isNotEmpty()) {
            Text(t("Side effects"), color = pink, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelSmall)
            logs.take(5).forEach { log ->
                val pills = log.selectedSymptoms.joinToString(", ")
                val line = log.notes?.takeIf { it.isNotBlank() } ?: pills
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("• ${log.logDate}",
                        color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelSmall)
                    Text(line, color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun regimenBandLabel(b: String): String = when (b) {
    "working_well" -> "Working well"
    "showing_progress" -> "Showing progress"
    "some_effect" -> "Some effect"
    "not_noticeable" -> "Not noticeable yet"
    else -> "Not enough data"
}

private fun regimenBandColor(b: String): androidx.compose.ui.graphics.Color = when (b) {
    "working_well" -> androidx.compose.ui.graphics.Color(0xFF6ED69E)
    "showing_progress" -> androidx.compose.ui.graphics.Color(0xFFFFB454)
    "some_effect" -> AppTheme.BodyTextColor
    "not_noticeable" -> androidx.compose.ui.graphics.Color(0xFFE0492B)
    else -> androidx.compose.ui.graphics.Color.White.copy(alpha = 0.55f)
}


// ======= Attack log (pain timeline + rated symptoms per attack) =======

/**
 * How many pain entries are printed per attack before the rest are summarised.
 * Someone logging every twinge can produce dozens of entries for one attack;
 * past ten the doctor is reading noise, not a pattern. The count of what was
 * dropped is always shown so the report never silently truncates.
 */
private const val PAIN_ENTRIES_SHOWN = 10

/** Attack cards rendered on screen. This list is scrollable, not lazy, so a
 *  hundred cards would stall the report; the PDF carries the full set. */
private const val ATTACK_CARDS_SHOWN = 12

/** One moment in an attack: rows sharing a `start_at` are a single entry. */
internal data class ReportPainEntry(
    val at: Instant?,
    val severity: Int?,
    val locations: List<String>,
)

internal data class ReportAttackDetail(
    val migraine: MigraineSpan,
    /** Capped at [PAIN_ENTRIES_SHOWN]. */
    val painEntries: List<ReportPainEntry>,
    val totalPainEntries: Int,
    /** Only symptoms carrying an intensity or a time — the rest are already
     *  listed as the attack's type. */
    val symptoms: List<SupabaseDbService.SymptomLogRow>,
)

/**
 * Null when the attack has nothing timed to say, which keeps pre-feature
 * attacks out of the section instead of giving them an empty row.
 */
internal fun buildReportAttackDetail(
    mg: MigraineSpan,
    painPointsByMigraine: Map<String, List<SupabaseDbService.PainPointRow>>,
    allSymptoms: List<SupabaseDbService.SymptomLogRow>,
): ReportAttackDetail? {
    val id = mg.id ?: return null
    val grouped = painPointsByMigraine[id].orEmpty()
        .groupBy { it.startAt }
        .toSortedMap()
        .map { (startAt, rows) ->
            ReportPainEntry(
                at = TimeOfDay.parseInstant(startAt),
                severity = rows.mapNotNull { it.severity }.maxOrNull(),
                // prettyLabel, not the raw id: a location the map doesn't know
                // (older logs, ids added since) should still read as English.
                locations = rows.map { ALL_PAIN_POINTS_MAP[it.locationId] ?: prettyLabel(it.locationId) },
            )
        }
    val symptoms = allSymptoms.filter {
        it.migraineId == id && (it.severity != null || it.startAt != null)
    }
    if (grouped.isEmpty() && symptoms.isEmpty()) return null
    return ReportAttackDetail(
        migraine = mg,
        painEntries = grouped.take(PAIN_ENTRIES_SHOWN),
        totalPainEntries = grouped.size,
        symptoms = symptoms,
    )
}

/**
 * How far an event sits from the attack's onset: "+2h 10m", "-2d 4h",
 * "at onset". Triggers and prodromes are routinely logged hours or days
 * BEFORE the attack, so the sign carries real clinical meaning and must
 * never be flattened to "at onset".
 */
internal fun offsetFromStart(start: Instant, at: Instant?): String {
    if (at == null) return ""
    val mins = Duration.between(start, at).toMinutes()
    if (mins == 0L) return "at onset"
    val sign = if (mins < 0) "-" else "+"
    val abs = kotlin.math.abs(mins)
    val d = abs / 1440
    val h = (abs % 1440) / 60
    val m = abs % 60
    return when {
        d > 0L && h > 0L -> "$sign${d}d ${h}h"
        d > 0L -> "$sign${d}d"
        h > 0L && m > 0L -> "$sign${h}h ${m}m"
        h > 0L -> "$sign${h}h"
        else -> "$sign${m}m"
    }
}

@Composable
private fun AttackLogCard(details: List<ReportAttackDetail>) {
    val zone = ZoneId.systemDefault()
    val dateFmt = remember { DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault()) }

    BrainyWatermarkCard(resId = R.drawable.brainy_briefcase, flipWatermark = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BrainyBlobIcon(resId = R.drawable.brainy_briefcase_small)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t("Attack Log"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(t("How the pain and symptoms moved through each attack"),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
        }

        details.forEach { d ->
            Spacer(Modifier.height(12.dp))
            val mg = d.migraine
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(dateFmt.format(mg.start), color = Color.White,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.weight(1f))
                mg.severity?.let { s ->
                    Text(t("Peak %s/10", s), color = severityColor(s),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                }
            }

            d.painEntries.forEachIndexed { i, e ->
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        if (i == 0) t("Pain") else t("Then"),
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(44.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            buildString {
                                append(offsetFromStart(mg.start, e.at).ifBlank { "time not logged" })
                                e.severity?.let { append(" · $it/10") }
                            },
                            color = e.severity?.let { severityColor(it) } ?: AppTheme.BodyTextColor,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                        )
                        Text(e.locations.joinToString(", "),
                            color = AppTheme.BodyTextColor,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (d.totalPainEntries > d.painEntries.size) {
                Spacer(Modifier.height(2.dp))
                Text(
                    t("Showing %1\$s of %2\$s pain entries", d.painEntries.size, d.totalPainEntries),
                    color = AppTheme.SubtleTextColor.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }

            d.symptoms.forEach { s ->
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // Same Brainy art the user saw when logging the symptom;
                    // label-first so pool items they added themselves resolve too.
                    val symIcon = SymptomIcons.forKey(s.type)
                    val symBrainy = brainyForLogKey(s.type, s.type) ?: brainyForLogVector(symIcon)
                    if (symBrainy != null || symIcon != null) {
                        LogIconImage(drawableId = symBrainy, fallback = symIcon,
                            size = if (symBrainy != null) 26.dp else 18.dp, tint = Color(0xFF9575CD))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        (s.type ?: t("Symptom")).replace("_", " ").replaceFirstChar { it.uppercase() },
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    val detail = listOfNotNull(
                        s.severity?.lowercase(),
                        s.startAt?.let { TimeOfDay.parseInstant(it) }
                            ?.let { offsetFromStart(mg.start, it) }?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    if (detail.isNotEmpty()) {
                        Text(detail, color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private fun severityColor(s: Int): Color = when {
    s <= 3 -> Color(0xFF81C784)
    s <= 6 -> Color(0xFFFFB74D)
    else -> Color(0xFFE57373)
}

/**
 * Section header in the Brainy style used across Insights and Monitor: blob
 * icon, title, one line of plain-language context. Used for the report's own
 * sections; the shared preview cards (Patterns, Treatments, Impact) already
 * carry their own.
 */
@Composable
internal fun ReportSectionHeader(
    title: String,
    subtitle: String,
    resId: Int,
    flip: Boolean = false,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BrainyBlobIcon(resId = resId, flip = flip)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(subtitle, color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ======= Attack card: the PDF's composition, on screen =======

/**
 * One attack, shown the way the printed report shows it: where the pain sat,
 * what the aura looked like, the health metrics and events around it, and the
 * sequence spelled out underneath.
 *
 * This replaces the separate "Migraine Timeline" card — the two were the same
 * attack described twice, and keeping one composition means the screen and the
 * PDF can't drift.
 */
@Composable
internal fun AttackCard(
    mg: MigraineSpan,
    detail: ReportAttackDetail?,
    events: List<EventMarker>,
    metrics: List<MetricSeries>,
    windowStart: Instant,
    windowEnd: Instant,
    linked: SupabaseDbService.MigraineLinkedItems?,
    symptoms: List<SupabaseDbService.SymptomLogRow>,
    symptomCategories: Map<String, String>,
    /** Timestamped aura zones for this attack, when it has them. */
    auraZones: List<SupabaseDbService.AuraZoneRow> = emptyList(),
) {
    val zone = ZoneId.systemDefault()
    val dateFmt = remember { DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm").withZone(zone) }
    val hrs = mg.end?.let { (it.toEpochMilli() - mg.start.toEpochMilli()) / 3_600_000.0 }

    BaseCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(dateFmt.format(mg.start), color = Color.White,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.weight(1f))
            Text(
                buildString {
                    append(mg.severity?.let { "Severity $it" } ?: "Severity —")
                    append(hrs?.let { " · ${String.format("%.1f", it)}h" } ?: " · ongoing")
                },
                color = mg.severity?.let { severityColor(it) } ?: AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }

        Spacer(Modifier.height(8.dp))

        val painIds = remember(mg, detail) {
            (mg.painLocations + detail?.painEntries.orEmpty().flatMap { it.locations }).distinct()
        }

        // Visuals across the top, chart full width underneath — the chart is
        // the part that needs the room.
        val hasVisuals = painIds.isNotEmpty() || mg.auraLocations.isNotEmpty()
        if (hasVisuals) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (painIds.isNotEmpty()) {
                    AttackPainMap(mg, detail, Modifier.width(112.dp))
                }
                if (mg.auraLocations.isNotEmpty()) {
                    // Staged when the attack has timed zones: the later a zone
                    // appeared, the deeper it reads — same rule as the pain
                    // dots. Untimed attacks get one flat shade.
                    val moments = auraZones.filter { it.startAt != null }
                        .groupBy { it.startAt!! }
                        .toSortedMap()
                    val counts = if (moments.isEmpty()) {
                        mg.auraLocations.map { it to 100 }
                    } else {
                        val steps = (moments.size - 1).coerceAtLeast(1)
                        moments.entries.flatMapIndexed { i, entry ->
                            entry.value.map { it.zone to (30 + (i * 70 / steps)) }
                        }
                    }
                    AuraHeatMap(
                        auraZoneCounts = counts,
                        totalAuraAttacks = 100,
                        modifier = Modifier.weight(1f),
                        showPercentages = false,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        AttackChart(
            mg = mg, detail = detail, events = events, metrics = metrics,
            windowStart = windowStart, windowEnd = windowEnd,
            modifier = Modifier.fillMaxWidth().height(if (metrics.isEmpty()) 160.dp else 250.dp),
        )

        Spacer(Modifier.height(8.dp))
        AttackSequence(mg, detail, linked, symptoms, symptomCategories, metrics)
    }
}

/** The attack's sequence, then the same items grouped by category. */
@Composable
private fun AttackSequence(
    mg: MigraineSpan,
    detail: ReportAttackDetail?,
    linked: SupabaseDbService.MigraineLinkedItems?,
    symptoms: List<SupabaseDbService.SymptomLogRow>,
    symptomCategories: Map<String, String>,
    metrics: List<MetricSeries> = emptyList(),
) {
    data class Row3(val label: String, val at: Instant?, val text: String, val pain: Boolean = false)

    val rows = remember(mg, detail, linked, symptoms) {
        val out = mutableListOf<Row3>()
        val catOf = { l: String -> symptomCategories[l.trim().lowercase()]?.lowercase() ?: "" }
        val ts = { raw: String? -> raw?.let { TimeOfDay.parseInstant(it) } }
        val rowFor = symptoms.filter { !it.type.isNullOrBlank() }
            .associateBy { it.type!!.trim().lowercase() }

        (mg.label ?: "").split(",").map { it.trim() }
            .filter { it.isNotEmpty() && it != "Migraine" && catOf(it) != "postdrome" }
            .forEach { label ->
                val src = rowFor[label.lowercase()]
                val note = src?.severity?.let { " · ${it.lowercase()}" } ?: ""
                out += Row3("Symptom", src?.startAt?.let { TimeOfDay.parseInstant(it) },
                    prettyLabel(label) + note)
            }
        symptoms.forEach { s ->
            val t = s.type ?: return@forEach
            if (catOf(t) != "postdrome") return@forEach
            out += Row3("Postdrome", s.startAt?.let { TimeOfDay.parseInstant(it) },
                prettyLabel(t) + (s.severity?.let { " · ${it.lowercase()}" } ?: ""))
        }
        detail?.painEntries?.forEach { e ->
            out += Row3("Pain", e.at,
                (e.severity?.let { "$it/10 — " } ?: "") + e.locations.joinToString(", "), pain = true)
        }
        if (detail == null && mg.painLocations.isNotEmpty()) {
            out += Row3("Pain location", null,
                mg.painLocations.joinToString(", ") { ALL_PAIN_POINTS_MAP[it] ?: prettyLabel(it) },
                pain = true)
        }
        linked?.triggers?.forEach { r ->
            r.type?.let { out += Row3("Trigger", ts(r.startAt), prettyLabel(it)) }
        }
        linked?.prodromes?.forEach { r ->
            r.type?.let { out += Row3("Prodrome", ts(r.startAt), prettyLabel(it)) }
        }
        linked?.medicines?.forEach { r ->
            val n = r.name ?: return@forEach
            val amt = r.amount?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            out += Row3("Medicine", ts(r.startAt), prettyLabel(n) + amt)
        }
        linked?.reliefs?.forEach { r ->
            r.type?.let { out += Row3("Relief", ts(r.startAt), prettyLabel(it)) }
        }
        linked?.locations?.forEach { r ->
            r.type?.let { out += Row3("Location", ts(r.startAt), prettyLabel(it)) }
        }
        linked?.activities?.forEach { r ->
            r.type?.let { out += Row3("Activity", ts(r.startAt), prettyLabel(it)) }
        }
        if (mg.auraLocations.isNotEmpty()) {
            out += Row3("Aura", null,
                mg.auraLocations.joinToString(", ") { AuraZones.label(it) } +
                    (mg.auraDurationMinutes?.let { " · ${formatAuraDuration(it)}" } ?: ""))
        }
        val order = listOf("Symptom", "Prodrome", "Pain", "Pain location", "Postdrome",
            "Aura", "Trigger", "Medicine", "Relief", "Activity", "Location")
        out.filter { it.at != null }.sortedBy { it.at } +
            out.filter { it.at == null }.sortedBy { order.indexOf(it.label).let { i -> if (i < 0) 99 else i } }
    }

    if (rows.isEmpty()) return

    Text(t("BY CATEGORY"), color = AppTheme.SubtleTextColor.copy(alpha = 0.7f),
        style = MaterialTheme.typography.labelSmall)
    rows.filter { it.label != "Aura" }
        .groupBy { it.label }
        .forEach { (cat, items) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(cat.uppercase(),
                    color = if (items.first().pain) AppTheme.AccentPink else AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(74.dp))
                Text(items.joinToString(", ") { it.text }, color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            }
        }

    // The chart scales each line to its own range, which is only honest if the
    // real numbers are printed somewhere the reader can find them.
    val drawn = metrics.filter { it.points.size >= 2 }
    if (drawn.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(t("TRACKED DATA"), color = AppTheme.SubtleTextColor.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall)
        drawn.forEach { series ->
            val values = series.points.map { it.value }
            val min = values.min()
            val max = values.max()
            fun fmt(v: Double) = if (kotlin.math.abs(v) >= 100) String.format("%.0f", v) else String.format("%.1f", v)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(t(series.label) + (series.unit.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""),
                    color = series.color, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f))
                Text("${fmt(min)}–${fmt(max)}", color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(t("TIMELINE"), color = AppTheme.SubtleTextColor.copy(alpha = 0.7f),
        style = MaterialTheme.typography.labelSmall)
    rows.forEach { r ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Text(t(r.label), color = if (r.pain) AppTheme.AccentPink else AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(74.dp))
            Text(offsetFromStart(mg.start, r.at), color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(58.dp))
            Text(r.text, color = AppTheme.BodyTextColor,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
    }
}


/**
 * The attack's pain on the person: one dot per entry, numbered in the order
 * they happened and deepening with time. Nothing is drawn when the attack has
 * no pain locations — an empty head is not information.
 */
@Composable
internal fun AttackPainMap(
    mg: MigraineSpan,
    detail: ReportAttackDetail?,
    modifier: Modifier = Modifier,
) {
    data class Pin(val id: String, val n: Int, val alpha: Float)

    val pins = remember(mg, detail) {
        val entries = detail?.painEntries.orEmpty()
        if (entries.isNotEmpty()) {
            val steps = (entries.size - 1).coerceAtLeast(1)
            entries.flatMapIndexed { i, entry ->
                entry.locations.map { label ->
                    val id = ALL_PAIN_POINTS_MAP.entries.firstOrNull { it.value == label }?.key ?: label
                    Pin(id, i + 1, 0.35f + (i.toFloat() / steps) * 0.6f)
                }
            }
        } else {
            mg.painLocations.map { Pin(it, 0, 0.9f) }
        }
    }.filter { pin -> FRONT_PAIN_POINTS.any { it.id == pin.id } }

    if (pins.isEmpty()) return

    Box(modifier) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.painpoints),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
        )
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.matchParentSize()) {
            val w = maxWidth
            val h = maxHeight
            pins.forEach { pin ->
                val point = FRONT_PAIN_POINTS.firstOrNull { it.id == pin.id } ?: return@forEach
                Box(
                    Modifier
                        .offset(x = w * point.xPct - 8.dp, y = h * point.yPct - 8.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(AppTheme.AccentPink.copy(alpha = pin.alpha))
                        .border(1.dp, AppTheme.AccentPink, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (pin.n > 0) {
                        Text("${pin.n}", color = Color.White,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }
}


/** Catmull-Rom through the points as cubic beziers — a data line that curves
 *  rather than kinks reads as a trend instead of a zigzag. Same curve the
 *  printed report draws. */
private fun smoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    if (points.size == 2) {
        path.lineTo(points[1].x, points[1].y)
        return path
    }
    for (i in 0 until points.size - 1) {
        val p0 = points.getOrElse(i - 1) { points[i] }
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = points.getOrElse(i + 2) { p2 }
        path.cubicTo(
            p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f,
            p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f,
            p2.x, p2.y,
        )
    }
    return path
}

/**
 * The attack window, drawn the way the report prints it: one uniform time axis,
 * a top panel for the attack itself (pain curve plus every discrete event
 * labelled at 45°) and a bottom panel for continuously tracked data, each line
 * named and scaled to its own range. The onset line runs through both.
 */
@Composable
internal fun AttackChart(
    mg: MigraineSpan,
    detail: ReportAttackDetail?,
    events: List<EventMarker>,
    metrics: List<MetricSeries>,
    windowStart: Instant,
    windowEnd: Instant,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    val dayFmt = remember { DateTimeFormatter.ofPattern("dd MMM").withZone(zone) }
    val hasMetrics = metrics.any { it.points.size >= 2 }
    val density = LocalDensity.current

    Canvas(modifier) {
        val spanMs = (windowEnd.toEpochMilli() - windowStart.toEpochMilli()).coerceAtLeast(3_600_000L)
        val padL = 26.dp.toPx()
        val padR = 10.dp.toPx()
        val plotW = size.width - padL - padR
        fun xOf(t: Instant): Float {
            val ms = t.toEpochMilli().coerceIn(windowStart.toEpochMilli(), windowEnd.toEpochMilli())
            return padL + (ms - windowStart.toEpochMilli()).toFloat() / spanMs * plotW
        }

        val p1Top = 6.dp.toPx()
        val painTop = p1Top + 10.dp.toPx()
        val painH = 62.dp.toPx()
        val p1Bottom = painTop + painH + 40.dp.toPx()   // room for the 45° labels
        val p2Top = p1Bottom + 8.dp.toPx()
        val metricTop = p2Top + 10.dp.toPx()
        val metricH = if (hasMetrics) 62.dp.toPx() else 0f
        val p2Bottom = if (hasMetrics) metricTop + metricH + 4.dp.toPx() else p1Bottom

        fun yPain(sev: Float) = painTop + (1f - sev / 10f) * painH

        // Panels
        val panelFill = Color.White.copy(alpha = 0.022f)
        drawRoundRect(panelFill, Offset(padL - 6.dp.toPx(), p1Top),
            androidx.compose.ui.geometry.Size(size.width - padL, p1Bottom - p1Top),
            CornerRadius(8.dp.toPx()))
        if (hasMetrics) {
            drawRoundRect(panelFill, Offset(padL - 6.dp.toPx(), p2Top),
                androidx.compose.ui.geometry.Size(size.width - padL, p2Bottom - p2Top),
                CornerRadius(8.dp.toPx()))
        }

        // Day stripes and date ticks
        val dayMs = 86_400_000L
        var day = windowStart.toEpochMilli()
        var idx = 0
        val nativeCanvas = drawContext.canvas.nativeCanvas
        val tickPaint = android.graphics.Paint().apply {
            color = AppTheme.SubtleTextColor.copy(alpha = 0.75f).toArgb()
            textSize = with(density) { 7.sp.toPx() }
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        while (day <= windowEnd.toEpochMilli()) {
            val x = xOf(Instant.ofEpochMilli(day))
            val next = xOf(Instant.ofEpochMilli(minOf(day + dayMs, windowEnd.toEpochMilli())))
            if (idx % 2 == 0) {
                drawRect(Color.White.copy(alpha = 0.018f), Offset(x, p1Top + 10.dp.toPx()),
                    androidx.compose.ui.geometry.Size((next - x).coerceAtLeast(0f), p2Bottom - p1Top - 10.dp.toPx()))
            }
            drawLine(Color.White.copy(alpha = 0.06f), Offset(x, p1Top + 10.dp.toPx()),
                Offset(x, p2Bottom), strokeWidth = 1f)
            // Days from onset rather than calendar dates: on a per-attack
            // window the useful question is "how long before".
            val fromOnset = ((day - mg.start.toEpochMilli()) / dayMs.toDouble()).let { Math.round(it) }
            val label = when {
                fromOnset == 0L -> tSync("onset")
                fromOnset > 0 -> "+${fromOnset}d"
                else -> "${fromOnset}d"
            }
            nativeCanvas.drawText(tSync(label), x, size.height - 2.dp.toPx(), tickPaint)
            day += dayMs
            idx++
        }

        // Severity gridlines
        val axisPaint = android.graphics.Paint().apply {
            color = AppTheme.SubtleTextColor.copy(alpha = 0.75f).toArgb()
            textSize = with(density) { 7.sp.toPx() }
            isAntiAlias = true
        }
        listOf(0f, 5f, 10f).forEach { sv ->
            drawLine(Color.White.copy(alpha = 0.05f), Offset(padL, yPain(sv)),
                Offset(size.width - padR, yPain(sv)), strokeWidth = 1f)
            nativeCanvas.drawText(sv.toInt().toString(), 4.dp.toPx(), yPain(sv) + 3.dp.toPx(), axisPaint)
        }

        // The attack itself, shaded through both panels
        val attackEnd = mg.end ?: mg.start
        drawRect(AppTheme.AccentPink.copy(alpha = 0.10f), Offset(xOf(mg.start), p1Top + 10.dp.toPx()),
            androidx.compose.ui.geometry.Size((xOf(attackEnd) - xOf(mg.start)).coerceAtLeast(2f),
                p2Bottom - p1Top - 10.dp.toPx()))

        // Pain curve, carried out to the end of the attack
        val pain = detail?.painEntries.orEmpty().mapNotNull { e ->
            e.at?.let { it to (e.severity?.toFloat() ?: return@mapNotNull null) }
        }
        if (pain.size >= 2) {
            val pts = pain + listOf(attackEnd to pain.last().second)
            val path = smoothPath(pts.map { (t, sev) -> Offset(xOf(t), yPain(sev)) })
            drawPath(path, AppTheme.AccentPink.copy(alpha = 0.25f), style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round))
            drawPath(path, AppTheme.AccentPink, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            pain.forEach { (t, sev) ->
                drawCircle(AppTheme.AccentPink, radius = 3.dp.toPx(), center = Offset(xOf(t), yPain(sev)))
            }
        }

        // Events: a pin at the axis with its name set at 45°
        val labelPaint = android.graphics.Paint().apply {
            color = AppTheme.BodyTextColor.copy(alpha = 0.85f).toArgb()
            textSize = with(density) { 7.sp.toPx() }
            isAntiAlias = true
        }
        val used = mutableListOf<Float>()
        events.sortedBy { it.at }.forEach { ev ->
            var x = xOf(ev.at)
            while (used.any { kotlin.math.abs(it - x) < 7.dp.toPx() }) x += 7.dp.toPx()
            used += x
            val baseY = painTop + painH
            drawLine(ev.color.copy(alpha = 0.3f), Offset(x, painTop), Offset(x, p2Bottom),
                strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
            drawCircle(ev.color, radius = 3.dp.toPx(), center = Offset(x, baseY))
            nativeCanvas.save()
            nativeCanvas.rotate(45f, x + 2.dp.toPx(), baseY + 6.dp.toPx())
            nativeCanvas.drawText(ev.name, x + 2.dp.toPx(), baseY + 6.dp.toPx(), labelPaint)
            nativeCanvas.restore()
        }

        // Onset
        drawLine(AppTheme.AccentPink.copy(alpha = 0.55f), Offset(xOf(mg.start), p1Top),
            Offset(xOf(mg.start), p2Bottom), strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))

        // Tracked data: each line scaled to its own range, named on the line
        if (hasMetrics) {
            metrics.filter { it.points.size >= 2 }.forEachIndexed { i, series ->
                val pts = series.points.mapNotNull { pt ->
                    runCatching { LocalDate.parse(pt.date).atStartOfDay(zone).toInstant() }
                        .getOrNull()?.let { it to pt.value.toFloat() }
                }.filter { it.first >= windowStart && it.first <= windowEnd }
                if (pts.size < 2) return@forEachIndexed
                val min = pts.minOf { it.second }
                val max = pts.maxOf { it.second }
                val range = (max - min).takeIf { it != 0f } ?: 1f
                fun yOf(v: Float) = metricTop + (1f - (v - min) / range) * metricH
                val path = smoothPath(pts.map { (t, v) -> Offset(xOf(t), yOf(v)) })
                drawPath(path, series.color.copy(alpha = 0.18f), style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
                drawPath(path, series.color, style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round))

                val lx = padL + plotW * (0.06f + (i % 4) * 0.22f)
                val near = pts.minByOrNull { kotlin.math.abs(xOf(it.first) - lx) } ?: pts.first()
                val ly = (yOf(near.second) - 5.dp.toPx()).coerceAtLeast(metricTop + 7.dp.toPx())
                val namePaint = android.graphics.Paint().apply {
                    color = series.color.toArgb()
                    textSize = with(density) { 7.sp.toPx() }
                    isAntiAlias = true
                }
                nativeCanvas.drawText(tSync(series.label), lx, ly, namePaint)
            }
        }
    }
}
