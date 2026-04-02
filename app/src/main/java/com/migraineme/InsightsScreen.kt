package com.migraineme

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.interaction.MutableInteractionSource

data class MigraineSpan(val start: Instant, val end: Instant?, val severity: Int? = null, val label: String? = null, val id: String? = null)
data class ReliefSpan(val start: Instant, val end: Instant?, val intensity: Int? = null, val name: String, val sideEffectScale: String? = null)
data class TriggerPoint(val at: Instant, val name: String)
data class MedicinePoint(val at: Instant, val name: String, val amount: String?, val sideEffectScale: String? = null)
enum class TimeSpan(val days: Long, val label: String) { DAY(1, "Day"), WEEK(7, "Week"), MONTH(30, "Month"), YEAR(365, "Year"); val millis: Long get() = days * 24L * 60L * 60L * 1000L }
data class DailyMetricPoint(val date: String, val value: Double)
data class MetricSeries(val key: String, val label: String, val unit: String, val color: Color, val points: List<DailyMetricPoint>)
data class EventMarker(val at: Instant, val endAt: Instant? = null, val name: String, val category: String, val detail: String? = null, val color: Color, val isAutomated: Boolean = false)

val EventCategoryColors = mapOf(
    "Trigger" to Color(0xFFFFB74D), "Prodrome" to Color(0xFF9575CD), "Medicine" to Color(0xFF4FC3F7),
    "Relief" to Color(0xFF81C784), "Activity" to Color(0xFFFF8A65), "Location" to Color(0xFF78909C),
    "Missed Activity" to Color(0xFFFF7043)
)

/**
 * Resolves a trigger/prodrome type string to a metric key for auto-selection.
 * Built from trigger_templates and prodrome_templates source of truth.
 * Handles both template-label format ("Skin temp low") and legacy colon format ("Pressure: Low").
 */
/** All available metric definitions grouped by category. Key must match ViewModel allDailyMetrics keys. */
data class MetricDef(val key: String, val label: String, val unit: String, val color: Color, val group: String)
val AllMetricDefs = listOf(
    // Environment
    MetricDef("pressure", "Pressure", "hPa", Color(0xFF7986CB), "Environment"),
    MetricDef("temp", "Temp", "°C", Color(0xFFFF8A65), "Environment"),
    MetricDef("humidity", "Humidity", "%", Color(0xFF4FC3F7), "Environment"),
    MetricDef("wind", "Wind", "m/s", Color(0xFF81C784), "Environment"),
    MetricDef("uv", "UV Index", "", Color(0xFFFFB74D), "Environment"),
    MetricDef("altitude", "Altitude", "m", Color(0xFFCE93D8), "Environment"),
    MetricDef("alt_change", "Alt. Change", "m", Color(0xFFBA68C8), "Environment"),
    // Physical
    MetricDef("recovery", "Recovery", "%", Color(0xFFFFCC80), "Physical"),
    MetricDef("hrv", "HRV", "ms", Color(0xFFA5D6A7), "Physical"),
    MetricDef("rhr", "Resting HR", "bpm", Color(0xFFEF9A9A), "Physical"),
    MetricDef("spo2", "SpO2", "%", Color(0xFF80DEEA), "Physical"),
    MetricDef("skin_temp", "Skin Temp", "°C", Color(0xFFFFAB91), "Physical"),
    MetricDef("resp_rate", "Resp. Rate", "bpm", Color(0xFFB39DDB), "Physical"),
    MetricDef("stress", "Stress", "", Color(0xFFE57373), "Physical"),
    MetricDef("strain", "Strain", "", Color(0xFFFF8A80), "Physical"),
    MetricDef("high_hr", "High HR Zones", "min", Color(0xFFF48FB1), "Physical"),
    MetricDef("steps", "Steps", "", Color(0xFFDCE775), "Physical"),
    MetricDef("weight", "Weight", "kg", Color(0xFFBCAAA4), "Physical"),
    MetricDef("body_fat", "Body Fat", "%", Color(0xFFFFCC80), "Physical"),
    MetricDef("bp_sys", "BP Systolic", "mmHg", Color(0xFFEF9A9A), "Physical"),
    MetricDef("glucose", "Glucose", "mg/dL", Color(0xFFFFE082), "Physical"),
    // Sleep
    MetricDef("sleep_dur", "Sleep", "hrs", Color(0xFF90CAF9), "Sleep"),
    MetricDef("sleep_score", "Sleep Score", "%", Color(0xFF64B5F6), "Sleep"),
    MetricDef("sleep_eff", "Sleep Eff.", "%", Color(0xFF42A5F5), "Sleep"),
    MetricDef("sleep_dist", "Disturbances", "", Color(0xFF7986CB), "Sleep"),
    MetricDef("sleep_deep", "Deep Sleep", "hrs", Color(0xFF5C6BC0), "Sleep"),
    MetricDef("sleep_rem", "REM Sleep", "hrs", Color(0xFF7E57C2), "Sleep"),
    MetricDef("sleep_light", "Light Sleep", "hrs", Color(0xFF9575CD), "Sleep"),
    MetricDef("bedtime", "Bedtime", "hrs", Color(0xFF3949AB), "Sleep"),
    MetricDef("wake_time", "Wake Time", "hrs", Color(0xFF26A69A), "Sleep"),
    // Mental
    MetricDef("screen_time", "Screen Time", "min", Color(0xFFFF8A65), "Mental"),
    MetricDef("late_screen", "Late Screen", "hrs", Color(0xFFFF7043), "Mental"),
    MetricDef("noise", "Noise", "dB", Color(0xFFFFB74D), "Mental"),
    MetricDef("brightness", "Brightness", "%", Color(0xFFFFF176), "Mental"),
    MetricDef("volume", "Volume", "%", Color(0xFFAED581), "Mental"),
    MetricDef("unlocks", "Unlocks", "", Color(0xFF4DD0E1), "Mental"),
    MetricDef("dark_mode", "Dark Mode", "hrs", Color(0xFF546E7A), "Mental"),
    MetricDef("mindfulness", "Mindfulness", "min", Color(0xFF80CBC4), "Mental"),
    // Diet – macros
    MetricDef("calories", "Calories", "kcal", Color(0xFFFFAB91), "Diet"),
    MetricDef("protein", "Protein", "g", Color(0xFFEF9A9A), "Diet"),
    MetricDef("carbs", "Carbs", "g", Color(0xFFFFCC80), "Diet"),
    MetricDef("fat", "Fat", "g", Color(0xFFFFE082), "Diet"),
    MetricDef("fiber", "Fiber", "g", Color(0xFFA5D6A7), "Diet"),
    MetricDef("sugar", "Sugar", "g", Color(0xFFF48FB1), "Diet"),
    MetricDef("sodium", "Sodium", "mg", Color(0xFFB39DDB), "Diet"),
    MetricDef("caffeine", "Caffeine", "mg", Color(0xFFAED581), "Diet"),
    MetricDef("cholesterol", "Cholesterol", "mg", Color(0xFFFFAB91), "Diet"),
    MetricDef("sat_fat", "Sat. Fat", "g", Color(0xFFEF9A9A), "Diet"),
    MetricDef("unsat_fat", "Unsat. Fat", "g", Color(0xFFFFCC80), "Diet"),
    MetricDef("trans_fat", "Trans Fat", "g", Color(0xFFE57373), "Diet"),
    MetricDef("hydration", "Hydration", "ml", Color(0xFF29B6F6), "Diet"),
    // Diet – minerals
    MetricDef("potassium", "Potassium", "mg", Color(0xFF80CBC4), "Diet"),
    MetricDef("calcium", "Calcium", "mg", Color(0xFFB0BEC5), "Diet"),
    MetricDef("iron", "Iron", "mg", Color(0xFFBCAAA4), "Diet"),
    MetricDef("magnesium", "Magnesium", "mg", Color(0xFF80DEEA), "Diet"),
    MetricDef("zinc", "Zinc", "mg", Color(0xFFCE93D8), "Diet"),
    MetricDef("selenium", "Selenium", "mcg", Color(0xFFFFD54F), "Diet"),
    MetricDef("phosphorus", "Phosphorus", "mg", Color(0xFFA5D6A7), "Diet"),
    MetricDef("copper", "Copper", "mg", Color(0xFFFFAB91), "Diet"),
    MetricDef("manganese", "Manganese", "mg", Color(0xFFB39DDB), "Diet"),
    // Diet – vitamins
    MetricDef("vitamin_a", "Vitamin A", "mcg", Color(0xFFFFCC80), "Diet"),
    MetricDef("vitamin_c", "Vitamin C", "mg", Color(0xFFFFE082), "Diet"),
    MetricDef("vitamin_d", "Vitamin D", "mcg", Color(0xFFFFF176), "Diet"),
    MetricDef("vitamin_e", "Vitamin E", "mg", Color(0xFFA5D6A7), "Diet"),
    MetricDef("vitamin_k", "Vitamin K", "mcg", Color(0xFF81C784), "Diet"),
    MetricDef("vitamin_b6", "Vitamin B6", "mg", Color(0xFF80CBC4), "Diet"),
    MetricDef("vitamin_b12", "Vitamin B12", "mcg", Color(0xFF4FC3F7), "Diet"),
    MetricDef("thiamin", "Thiamin", "mg", Color(0xFF90CAF9), "Diet"),
    MetricDef("riboflavin", "Riboflavin", "mg", Color(0xFF7986CB), "Diet"),
    MetricDef("niacin", "Niacin", "mg", Color(0xFF5C6BC0), "Diet"),
    MetricDef("folate", "Folate", "mcg", Color(0xFF7E57C2), "Diet"),
    MetricDef("biotin", "Biotin", "mcg", Color(0xFF9575CD), "Diet"),
    MetricDef("panto_acid", "Panto. Acid", "mg", Color(0xFFBA68C8), "Diet"),
    // Diet – food risks (0=none, 1=low, 2=medium, 3=high)
    MetricDef("tyramine", "Tyramine", "risk", Color(0xFFFFAB40), "Diet"),
    MetricDef("alcohol", "Alcohol", "risk", Color(0xFFEF5350), "Diet"),
    MetricDef("gluten", "Gluten", "risk", Color(0xFFFFD54F), "Diet"),
)

@Composable
fun InsightsScreen(navController: NavHostController, vm: InsightsViewModel = viewModel()) {
    val owner = LocalContext.current as ViewModelStoreOwner
    val ctx: Context = LocalContext.current.applicationContext
    val authVm: AuthViewModel = viewModel(owner)
    val auth by authVm.state.collectAsState()
    LaunchedEffect(auth.accessToken) { auth.accessToken?.takeIf { it.isNotBlank() }?.let { vm.load(ctx, it) } }

    val migraines by vm.migraines.collectAsState()
    val spiderLoading by vm.spiderLoading.collectAsState()
    val allDailyMetrics by vm.allDailyMetrics.collectAsState()
    val linkedItems by vm.selectedLinkedItems.collectAsState()
    val linkedLoading by vm.linkedItemsLoading.collectAsState()
    val scrollState = rememberScrollState()
    val zone = ZoneId.systemDefault()
    val sorted = remember(migraines) { migraines.sortedByDescending { it.start } }
    val selIdx by vm.selectedMigraineIndex.collectAsState()
    LaunchedEffect(sorted.size) { if (selIdx >= sorted.size && sorted.isNotEmpty()) vm.selectMigraine(0) }
    val sel = sorted.getOrNull(selIdx)
    LaunchedEffect(sel?.id) { sel?.id?.let { vm.loadLinkedItems(it) } }

    // Window days (shared across all insight screens)
    val wBefore by vm.windowDaysBefore.collectAsState()
    val wAfter by vm.windowDaysAfter.collectAsState()
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

    val allMissed by vm.allMissedActivities.collectAsState()
    val allActs by vm.allActivities.collectAsState()

    // Events from linked items + activities/missed filtered by migraine
    val windowEvents = remember(linkedItems, allMissed, allActs, sel?.id) {
        val actsForMigraine = allActs.filter { it.migraineId == sel?.id }
        val missedForMigraine = allMissed.filter { it.migraineId == sel?.id }
        buildEventMarkers(linkedItems, actsForMigraine, missedForMigraine)
    }

    // Auto-select metrics based on ALL automated items linked to this migraine
    val templateMap by vm.labelToMetricMap.collectAsState()
    val autoSelectedKeys = remember(windowEvents, templateMap) {
        windowEvents
            .filter { it.isAutomated }
            .mapNotNull { ev -> vm.labelToMetricKey(ev.name) }
            .toSet()
    }

    // Available metrics (only those with data in window)
    val available = remember(allDailyMetrics, windowDates) {
        AllMetricDefs.filter { d ->
            allDailyMetrics[d.key]?.any { it.date in windowDates } == true
        }
    }

    // Enabled metrics: auto-selected from triggers + user toggles (stored in VM)
    val userToggledKeys by vm.userToggledMetrics.collectAsState()
    val userDisabledKeys by vm.userDisabledMetrics.collectAsState()

    val enabledKeys = (autoSelectedKeys - userDisabledKeys) + userToggledKeys

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

    // Spiders
    val triggerSpider by vm.triggerSpider.collectAsState()
    val prodromeSpider by vm.prodromeSpider.collectAsState()
    val symptomSpider by vm.symptomSpider.collectAsState()
    val medicineSpider by vm.medicineSpider.collectAsState()
    val reliefSpider by vm.reliefSpider.collectAsState()
    val activitySpider by vm.activitySpider.collectAsState()
    val missedActivitySpider by vm.missedActivitySpider.collectAsState()
    val locationSpider by vm.locationSpider.collectAsState()

    val premiumStateTop by PremiumManager.state.collectAsState()

    // ======= Correlation & Gauge data =======
    val correlations by vm.correlationStats.collectAsState()
    val gaugeAccuracy by vm.gaugeAccuracy.collectAsState()
    val gaugeProposals by vm.gaugeProposals.collectAsState()
    val gaugeProposalApplying by vm.gaugeProposalApplying.collectAsState()
    val correlationsLoading by vm.correlationsLoading.collectAsState()
    val adjustingThreshold by vm.adjustingThreshold.collectAsState()
    val adjustingIds by vm.adjustingIds.collectAsState()
    val weeklySummary by vm.weeklySummary.collectAsState()
    val insightHistory by vm.insightHistory.collectAsState()
    val medicineItems by vm.medicineItems.collectAsState()
    val reliefItems by vm.reliefItems.collectAsState()
    val dayOfWeekPattern by vm.dayOfWeekPattern.collectAsState()
    val contextItems by vm.contextItems.collectAsState()
    val impactItems by vm.impactItems.collectAsState()
    val migraineSpans by vm.migraines.collectAsState()
    val overallAvgSeverity = remember(migraineSpans) {
        val severities = migraineSpans.mapNotNull { it.severity }
        if (severities.isEmpty()) 5f else severities.average().toFloat()
    }

    // Split correlations by type (all dynamic, no hardcoded labels)
    val significantCorrelations = remember(correlations) {
        correlations.filter { it.isSignificant() }
    }
    val triggerCorrelations = remember(significantCorrelations) {
        significantCorrelations.filter { it.factorType == "trigger" }
            .sortedByDescending { it.liftRatio }
    }
    val metricCorrelations = remember(significantCorrelations) {
        significantCorrelations.filter { it.factorType == "metric" }
            .sortedByDescending { it.liftRatio }
    }
    val interactionCorrelations = remember(significantCorrelations) {
        significantCorrelations.filter { it.factorType == "interaction" }
            .sortedByDescending { it.liftRatio }
    }
    // Treatments use self-reported relief — relax p-value filter, only require lift > 1.2
    val treatmentCorrelations = remember(correlations) {
        correlations.filter { it.factorType == "treatment" && it.liftRatio > 1.2f }
            .sortedByDescending { it.liftRatio }
    }
    val treatmentInteractionCorrelations = remember(correlations) {
        correlations.filter { it.factorType == "treatment_interaction" && it.liftRatio > 1.2f }
            .sortedByDescending { it.liftRatio }
    }
    val thresholdNudges = remember(significantCorrelations) {
        significantCorrelations.filter {
            it.factorType == "metric" && it.suggestedThreshold != null && it.currentThreshold != null &&
                kotlin.math.abs(it.suggestedThreshold - it.currentThreshold) > it.currentThreshold * 0.05f
        }.sortedByDescending { it.liftRatio }
    }

    // ── Full-screen loading state (like Journal) ──
    if (spiderLoading || correlationsLoading) {
        Box(
            Modifier
                .fillMaxSize()
                .background(AppTheme.FadeColor),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = AppTheme.AccentPurple)
                Spacer(Modifier.height(8.dp))
                Text("Loading insights\u2026", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            // ── 1. WEEKLY SUMMARY (Hero) ──
            if (weeklySummary != null) {
                WeeklySummaryCard(weeklySummary!!, onClick = { navController.navigate(Routes.FREQUENCY_TRENDS) })
            } else {
                BaseCard {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(24.dp)) { HubIcons.run { drawCalendarWeek(AppTheme.AccentPurple) } }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Weekly Summary", color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("Your weekly overview will appear after your first week of tracking",
                                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // ── Content (data loaded) ──
            val ml by vm.migraines.collectAsState()
                val premiumState by PremiumManager.state.collectAsState()
                val medEff by vm.medicineEffectiveness.collectAsState()
                val relEff by vm.reliefEffectiveness.collectAsState()
                val recomputeStatus by vm.recomputeStatus.collectAsState()

                // ── 2. FULL REPORT ──
                BaseCard(modifier = Modifier.clickable {
                    if (premiumStateTop.isPremium) navController.navigate(Routes.INSIGHTS_REPORT)
                    else navController.navigate(Routes.PAYWALL)
                }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(24.dp)) { HubIcons.run { drawBriefcase(Color(0xFF4FC3F7)) } }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Full Report", color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("Generate a doctor-ready PDF with all your migraine data",
                                color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (!premiumStateTop.isPremium) {
                            Icon(Icons.Outlined.Lock, contentDescription = "Premium",
                                tint = AppTheme.AccentPurple, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("\u2192", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
                    }
                }

                // ── 3. EXPLORE MIGRAINES (tap-through, free) ──
                BaseCard(modifier = Modifier.clickable {
                    navController.navigate(Routes.INSIGHTS_DETAIL)
                }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(24.dp)) { HubIcons.run { drawMigraineStarburst(AppTheme.AccentPink) } }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Explore Migraines", color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("Timeline, breakdowns & detailed charts",
                                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("\u2192", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
                    }
                }

                // ── 4. ACCURACY ──
                PremiumGate(
                    message = "Unlock Accuracy",
                    subtitle = "See how well your gauge predicts migraines",
                    onUpgrade = { navController.navigate(Routes.PAYWALL) }
                ) {
                    AccuracyPreviewCard(
                        gaugeAccuracy = gaugeAccuracy,
                        onClick = { navController.navigate(Routes.INSIGHTS_THRESHOLDS) },
                    )
                }

                // ── 5. WHAT HAPPENED ──
                PremiumGate(
                    message = "Unlock Pattern Analysis",
                    subtitle = "See correlations, combinations & thresholds",
                    onUpgrade = { navController.navigate(Routes.PAYWALL) }
                ) {
                    if (significantCorrelations.isEmpty()) {
                        BaseCard(modifier = Modifier.clickable { navController.navigate(Routes.INSIGHTS_PATTERNS) }) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Canvas(Modifier.size(24.dp)) { HubIcons.run { drawPatternsVenn(Color(0xFFCE93D8)) } }
                                Spacer(Modifier.width(10.dp))
                                Text("What Happened", color = AppTheme.TitleColor,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    modifier = Modifier.weight(1f))
                                Text("\u2192", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("Discover correlations and dangerous trigger combinations from your data.",
                                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        val previewPatterns = remember(triggerCorrelations, metricCorrelations) {
                            (triggerCorrelations + metricCorrelations).sortedByDescending { it.liftRatio }.take(2)
                        }
                        val previewInteractions = remember(interactionCorrelations) {
                            interactionCorrelations.take(2)
                        }
                        PatternsPreviewCard(
                            patterns = previewPatterns,
                            interactions = previewInteractions,
                            onShowAll = { navController.navigate(Routes.INSIGHTS_PATTERNS) }
                        )
                    }
                }

                // ── 6. WHAT WORKED ──
                PremiumGate(
                    message = "Unlock Treatment Analysis",
                    subtitle = "See which medicines and reliefs work best",
                    onUpgrade = { navController.navigate(Routes.PAYWALL) }
                ) {
                    val previewTreatments = remember(treatmentCorrelations) {
                        treatmentCorrelations.take(2)
                    }
                    val previewTreatmentInteractions = remember(treatmentInteractionCorrelations) {
                        treatmentInteractionCorrelations.take(2)
                    }
                    TreatmentPreviewCard(
                        treatments = previewTreatments,
                        treatmentInteractions = previewTreatmentInteractions,
                        onShowAll = { navController.navigate(Routes.INSIGHTS_TREATMENTS) }
                    )
                }

                // ── 7. WHAT WERE YOU DOING ──
                PremiumGate(
                    message = "Unlock Context Analysis",
                    subtitle = "See what you were doing when migraines hit",
                    onUpgrade = { navController.navigate(Routes.PAYWALL) }
                ) {
                    ContextCard(contextItems.take(2), overallAvgSeverity,
                        onClick = { navController.navigate(Routes.INSIGHTS_CONTEXT) })
                }

                // ── 8. HOW DID IT IMPACT YOU ──
                val painLocCounts by vm.painLocationCounts.collectAsState()
                val sevCounts by vm.severityCounts.collectAsState()
                val totalMigraineCount by vm.totalMigraineCount.collectAsState()
                PremiumGate(
                    message = "Unlock Impact Analysis",
                    subtitle = "See severity, pain locations & missed activities",
                    onUpgrade = { navController.navigate(Routes.PAYWALL) }
                ) {
                    ImpactCard(
                        impactItems = impactItems.take(2),
                        painLocationCounts = painLocCounts,
                        severityCounts = sevCounts,
                        totalMigraines = totalMigraineCount,
                        overallAvgSeverity = overallAvgSeverity,
                        onClick = { navController.navigate(Routes.INSIGHTS_IMPACT) },
                    )
                }


        }
    }
}


// ======= Composable helpers =======

@Composable
private fun MigraineSelector(
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
                    Text("$hStr${d.minusHours(d.toHours()).toMinutes()}m • Severity: ${sel.severity ?: "—"}/10",
                        color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                } else {
                    Text("Severity: ${sel.severity ?: "—"}/10",
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
private fun Chip(n: Int, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text("$n", color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.width(2.dp))
        Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
    }
}

// ======= Spider/Symptom cards =======

@Composable
internal fun EmptyInsightCard(
    logType: String,
    emptyMessage: String,
    onClick: (() -> Unit)? = null
) {
    val color = colorForLogType(logType)
    BaseCard(modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(24.dp)) {
                HubIcons.run {
                    when (logType) {
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
                Text(logType, color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                Text(emptyMessage, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun SpiderInsightCard(data: SpiderData, onClick: () -> Unit, secondAxes: List<SpiderAxis>? = null) {
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
                    secondAxes = secondAxes, secondColor = Color.White.copy(alpha = 0.6f),
                    seBadgeColors = data.seBadgeColors)
            }
        } else {
            StackedProportionalBar(axes = data.axes, accentColor = color)
        }

        // SE badge legend (always show for medicines/reliefs so users know the ring meaning)
        if (data.logType == "Medicines" || data.logType == "Reliefs") {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Side effects: ", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
                listOf(
                    Color(0xFF81C784) to "none",
                    Color(0xFFFFB74D) to "soft",
                    Color(0xFFFF8A65) to "moderate",
                    Color(0xFFE57373) to "severe",
                ).forEach { (c, label) ->
                    Spacer(Modifier.width(6.dp))
                    Canvas(Modifier.size(8.dp)) {
                        drawCircle(c.copy(alpha = 0.7f), size.minDimension / 2f, style = Stroke(width = 2f))
                    }
                    Spacer(Modifier.width(3.dp))
                    Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
                }
            }
        }
    }
}

@Composable
internal fun SymptomsInsightCard(ms: List<MigraineSpan>, onClick: () -> Unit) {
    val vm: InsightsViewModel = viewModel(LocalContext.current as ViewModelStoreOwner)
    val ss by vm.symptomSpider.collectAsState()
    val pcs by vm.painCharSpider.collectAsState()
    val acs by vm.accompSpider.collectAsState()
    BaseCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(24.dp)) { HubIcons.run { drawMigraineStarburst(AppTheme.AccentPink) } }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Symptoms", color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                Text("${ms.size} migraines logged",
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
            Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodyMedium)
        }
        // Overall symptom spider (matches iOS — shows migraine types as spider chart)
        ss?.takeIf { it.axes.isNotEmpty() }?.let { data ->
            Spacer(Modifier.height(12.dp))
            if (data.axes.size >= 3) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SpiderChart(axes = data.axes, accentColor = AppTheme.AccentPink, size = 200.dp)
                }
            } else StackedProportionalBar(axes = data.axes, accentColor = AppTheme.AccentPink)
        }
        pcs?.takeIf { it.axes.isNotEmpty() }?.let { data ->
            Spacer(Modifier.height(16.dp))
            Text("Pain Character", color = Color(0xFFEF5350),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(4.dp))
            if (data.axes.size >= 3) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SpiderChart(axes = data.axes, accentColor = Color(0xFFEF5350), size = 200.dp)
                }
            } else StackedProportionalBar(axes = data.axes, accentColor = Color(0xFFEF5350))
        }
        acs?.takeIf { it.axes.isNotEmpty() }?.let { data ->
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

// ══════════════════════════════════════════════════════════════════
// INSIGHT CARDS — All data-driven, no hardcoded labels
// ══════════════════════════════════════════════════════════════════

/** Lift ratio as a colored bar + plain-language text */
@Composable
internal fun LiftBadge(lift: Float) {
    val label = when {
        lift >= 2f -> "${lift.toInt()}\u00D7 more likely"
        else -> "${String.format("%.1f", lift)}\u00D7 more likely"
    }
    val fraction = (lift / 5f).coerceIn(0f, 1f)
    Column(horizontalAlignment = Alignment.End) {
        Text(label, color = AppTheme.AccentPurple,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp))
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .width(80.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(3.dp))
                    .background(AppTheme.AccentPurple)
            )
        }
    }
}

/** Lag indicator */
@Composable
internal fun LagChip(lagDays: Int) {
    val text = when (lagDays) {
        0 -> "same day"
        1 -> "1 day before"
        else -> "$lagDays days before"
    }
    Text(
        text,
        color = AppTheme.SubtleTextColor,
        style = MaterialTheme.typography.labelSmall,
    )
}

/** Confidence dot based on p-value */
@Composable
internal fun ConfidenceDots(pValue: Float, color: Color = AppTheme.AccentPurple) {
    val dots = when {
        pValue < 0.01f -> 3
        pValue < 0.05f -> 2
        else -> 1
    }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(if (i < dots) color else AppTheme.SubtleTextColor.copy(alpha = 0.2f))
            )
        }
    }
}

/** Single correlation row — used by multiple cards */
@Composable
internal fun CorrelationRow(stat: EdgeFunctionsService.CorrelationStat) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (stat.factorB != null) {
                    Text(
                        stat.factorName,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stat.factorB,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        stat.factorName,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LagChip(stat.bestLagDays)
                    ConfidenceDots(stat.pValue)
                }
                // Duration & severity descriptors for triggers
                if (stat.factorType == "trigger" && (stat.avgDurationHrs != null || stat.avgSeverity != null)) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        stat.avgSeverity?.let { sev ->
                            val sevColor = when {
                                sev >= 7f -> Color(0xFFE57373)
                                sev >= 5f -> Color(0xFFFFB74D)
                                else -> Color(0xFF81C784)
                            }
                            Box(Modifier.size(8.dp).clip(CircleShape).background(sevColor))
                            Text("${String.format("%.0f", sev)}/10",
                                color = sevColor,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                        }
                        if (stat.avgSeverity != null && stat.avgDurationHrs != null) {
                            Text("\u00B7", color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.labelSmall)
                        }
                        stat.avgDurationHrs?.let { hrs ->
                            val durText = if (hrs >= 24f) "${String.format("%.0f", hrs / 24)}d avg duration"
                                else "${String.format("%.0f", hrs)}h avg duration"
                            Text(durText, color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            LiftBadge(stat.liftRatio)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stat.toInsightText(),
            color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Compact correlation row — name + lag + lift only, no insight text */
@Composable
private fun CorrelationRowCompact(stat: EdgeFunctionsService.CorrelationStat) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (stat.factorB != null) {
                Text(
                    stat.factorName,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stat.factorB,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    stat.factorName,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LagChip(stat.bestLagDays)
                ConfidenceDots(stat.pValue)
            }
            // Duration & severity descriptors for triggers
            if (stat.factorType == "trigger" && (stat.avgDurationHrs != null || stat.avgSeverity != null)) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    stat.avgSeverity?.let { sev ->
                        val sevColor = when {
                            sev >= 7f -> Color(0xFFE57373)
                            sev >= 5f -> Color(0xFFFFB74D)
                            else -> Color(0xFF81C784)
                        }
                        Box(Modifier.size(8.dp).clip(CircleShape).background(sevColor))
                        Text("${String.format("%.0f", sev)}/10",
                            color = sevColor,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                    }
                    if (stat.avgSeverity != null && stat.avgDurationHrs != null) {
                        Text("\u00B7", color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    stat.avgDurationHrs?.let { hrs ->
                        val durText = if (hrs >= 24f) "${String.format("%.0f", hrs / 24f)}d avg duration"
                            else "${String.format("%.0f", hrs)}h avg duration"
                        Text(durText, color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        LiftBadge(stat.liftRatio)
    }
}

// ── Top Patterns Card ──

@Composable
internal fun TopPatternsCard(
    triggers: List<EdgeFunctionsService.CorrelationStat>,
    metrics: List<EdgeFunctionsService.CorrelationStat>,
    interactions: List<EdgeFunctionsService.CorrelationStat> = emptyList(),
) {
    // Merge and sort by lift, show top 5
    val combined = remember(triggers, metrics) {
        (triggers + metrics).sortedByDescending { it.liftRatio }.take(5)
    }
    val topInteractions = remember(interactions) {
        interactions.sortedByDescending { it.liftRatio }.take(5)
    }
    if (combined.isEmpty() && topInteractions.isEmpty()) return

    BaseCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(24.dp)) { HubIcons.run { drawPatternsVenn(Color(0xFFCE93D8)) } }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("What Happened", color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text("Triggers, prodromes & metrics linked to your migraines",
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (combined.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Top Patterns", color = Color(0xFFCE93D8),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))

            combined.forEach { stat ->
                CorrelationRow(stat)
            }
        }

        if (topInteractions.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Dangerous Combinations", color = Color(0xFFE57373),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(4.dp))

            topInteractions.forEach { stat ->
                CorrelationRow(stat)
            }
        }
    }
}

// ── Patterns & Combinations Preview Card ──

@Composable
internal fun PatternsPreviewCard(
    patterns: List<EdgeFunctionsService.CorrelationStat>,
    interactions: List<EdgeFunctionsService.CorrelationStat>,
    onShowAll: () -> Unit
) {
    BaseCard(modifier = Modifier.clickable { onShowAll() }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(24.dp)) { HubIcons.run { drawPatternsVenn(Color(0xFFCE93D8)) } }
            Spacer(Modifier.width(8.dp))
            Text("What Happened", color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f))
            Text("\u2192", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
        }

        if (patterns.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Top Patterns", color = Color(0xFFCE93D8),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            patterns.forEach { stat ->
                CorrelationRowCompact(stat)
            }
        }

        if (interactions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Dangerous Combinations", color = Color(0xFFE57373),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            interactions.forEach { stat ->
                CorrelationRowCompact(stat)
            }
        }


    }
}

// ── Treatment Preview Card (main screen — max 2 + max 2 combos, tap to show all) ──

@Composable
internal fun TreatmentPreviewCard(
    treatments: List<EdgeFunctionsService.CorrelationStat>,
    treatmentInteractions: List<EdgeFunctionsService.CorrelationStat>,
    onShowAll: () -> Unit
) {
    BaseCard(modifier = Modifier.clickable { onShowAll() }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(24.dp)) { HubIcons.run { drawShieldCheck(Color(0xFF81C784)) } }
            Spacer(Modifier.width(8.dp))
            Text("What Worked", color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f))
            Text("\u2192", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
        }

        if (treatments.isEmpty() && treatmentInteractions.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Log medicines and reliefs with your migraines to see what works best.",
                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
        }

        if (treatments.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Top Treatments", color = Color(0xFF81C784),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            treatments.forEach { stat ->
                TreatmentRowCompact(stat)
            }
        }

        if (treatmentInteractions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Effective Combinations", color = Color(0xFF81C784),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            treatmentInteractions.forEach { stat ->
                TreatmentRowCompact(stat)
            }
        }
    }
}

/** Compact treatment row for main screen — green bars, shorter/milder split */
@Composable
private fun TreatmentRowCompact(stat: EdgeFunctionsService.CorrelationStat) {
    val green = Color(0xFF81C784)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                stat.factorName,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (stat.factorB != null) {
                Text(
                    stat.factorB,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "used in ${stat.pctMigraineWindows.toInt()}% of migraines",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall,
                )
                ConfidenceDots(stat.pValue, green)
            }
        }
        // Two separate bars: shorter + milder
        Column(horizontalAlignment = Alignment.End) {
            if (stat.durationLift > 1f) {
                EffectivenessBadge(stat.durationLift, "shorter")
            }
            if (stat.severityLift > 1f) {
                if (stat.durationLift > 1f) Spacer(Modifier.height(4.dp))
                EffectivenessBadge(stat.severityLift, "milder")
            }
            if (stat.durationLift <= 1f && stat.severityLift <= 1f) {
                EffectivenessBadge(stat.liftRatio, "effective")
            }
        }
    }
}

// ── Accuracy Preview Card ──

@Composable
internal fun AccuracyPreviewCard(
    gaugeAccuracy: EdgeFunctionsService.GaugeAccuracy?,
    onClick: () -> Unit,
) {
    val hasData = gaugeAccuracy != null && gaugeAccuracy.totalDays >= 7

    BaseCard(modifier = Modifier.clickable { onClick() }) {
        // ── Header ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(24.dp)) { HubIcons.run { drawThresholdTarget(Color(0xFFFFB74D)) } }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Accuracy", color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text("How well your gauge predicts migraines", color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("\u2192", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
        }

        if (hasData) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Caught block
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("${gaugeAccuracy!!.sensitivityPct}%", color = Color(0xFF81C784),
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                        Spacer(Modifier.height(4.dp))
                        Text("Caught", color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium))
                    }
                }
                // False alarms block
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("${gaugeAccuracy!!.falseAlarmRatePct}%",
                            color = if (gaugeAccuracy.falseAlarmRatePct > 30) Color(0xFFE57373) else Color(0xFFFFB74D),
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                        Spacer(Modifier.height(4.dp))
                        Text("False alarms", color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium))
                    }
                }
                // Total days block
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("${gaugeAccuracy!!.totalDays}", color = Color.White,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                        Spacer(Modifier.height(4.dp))
                        Text("Days tracked", color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium))
                    }
                }
            }
        } else {
            Spacer(Modifier.height(14.dp))
            Text(
                "Need at least 7 days of gauge data to show accuracy.",
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ── Threshold Nudges Card ──

// ── Threshold Preview Card (main screen — compact, max 3, no text, no adjust) ──

@Composable
private fun ThresholdPreviewCard(
    nudges: List<EdgeFunctionsService.CorrelationStat>,
    onShowAll: () -> Unit,
    onAdjust: (EdgeFunctionsService.CorrelationStat) -> Unit = {},
    isAdjusting: Boolean = false,
    adjustingIds: Set<String> = emptySet(),
) {
    if (nudges.isEmpty()) return

    BaseCard {
        Row(
            Modifier.fillMaxWidth().clickable { onShowAll() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Canvas(Modifier.size(24.dp)) { HubIcons.run { drawThresholdTarget(Color(0xFFFFB74D)) } }
            Spacer(Modifier.width(8.dp))
            Text("Threshold Suggestions", color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f))
            if (isAdjusting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = AppTheme.AccentPurple,
                    strokeWidth = 2.dp,
                )
            } else {
                Text("\u2192", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
            }
        }

        Spacer(Modifier.height(8.dp))

        nudges.take(3).forEach { stat ->
            val suggested = stat.suggestedThreshold ?: return@forEach
            val current = stat.currentThreshold ?: return@forEach
            val fmtSuggested = EdgeFunctionsService.CorrelationStat.fmtThreshold(suggested, stat.factorName)
            val fmtCurrent = EdgeFunctionsService.CorrelationStat.fmtThreshold(current, stat.factorName)

            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stat.factorName, color = Color.White,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    ConfidenceDots(stat.pValue)
                }
                Text(fmtCurrent, color = Color(0xFFFFB74D), style = MaterialTheme.typography.bodySmall)
                Text(" \u2192 ", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                Text(fmtSuggested, color = Color(0xFF81C784), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                val statAdjusting = stat.id in adjustingIds
                Text(
                    "\u2713",
                    color = if (statAdjusting) AppTheme.SubtleTextColor else AppTheme.AccentPurple,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (statAdjusting) AppTheme.SubtleTextColor.copy(alpha = 0.1f)
                            else AppTheme.AccentPurple.copy(alpha = 0.15f)
                        )
                        .then(
                            if (statAdjusting) Modifier
                            else Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onAdjust(stat) }
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// ── Threshold Detail Card (full screen — with text, adjust button) ──

@Composable
internal fun ThresholdDetailCard(
    nudges: List<EdgeFunctionsService.CorrelationStat>,
    onAdjust: (EdgeFunctionsService.CorrelationStat) -> Unit = {},
    isAdjusting: Boolean = false,
    adjustingIds: Set<String> = emptySet(),
) {
    if (nudges.isEmpty()) return

    BaseCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(24.dp)) { HubIcons.run { drawThresholdTarget(Color(0xFFFFB74D)) } }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Threshold Suggestions", color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text("Your data suggests adjusting these triggers",
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
            if (isAdjusting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = AppTheme.AccentPurple,
                    strokeWidth = 2.dp,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        nudges.forEach { stat ->
            val suggested = stat.suggestedThreshold ?: return@forEach
            val current = stat.currentThreshold ?: return@forEach
            val fmtSuggested = EdgeFunctionsService.CorrelationStat.fmtThreshold(suggested, stat.factorName)
            val fmtCurrent = EdgeFunctionsService.CorrelationStat.fmtThreshold(current, stat.factorName)

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1528)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        stat.factorName,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Spacer(Modifier.height(6.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("Current", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                            Text(fmtCurrent, color = Color(0xFFFFB74D),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                        }
                        Text("\u2192", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.titleMedium)
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Suggested", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                            Text(fmtSuggested, color = Color(0xFF81C784),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        stat.toInsightText(),
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodySmall,
                    )

                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LiftBadge(stat.liftRatio)
                            ConfidenceDots(stat.pValue)
                        }

                        val statAdjusting = stat.id in adjustingIds
                        Text(
                            if (statAdjusting) "Adjusting…" else "Adjust",
                            color = if (statAdjusting) AppTheme.SubtleTextColor else AppTheme.AccentPurple,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (statAdjusting) AppTheme.SubtleTextColor.copy(alpha = 0.1f)
                                    else AppTheme.AccentPurple.copy(alpha = 0.15f)
                                )
                                .then(
                                    if (statAdjusting) Modifier
                                    else Modifier.clickable { onAdjust(stat) }
                                )
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── Interaction Insights Card ──

@Composable
internal fun InteractionInsightsCard(interactions: List<EdgeFunctionsService.CorrelationStat>) {
    BaseCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Dangerous Combinations", color = Color(0xFFE57373),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }
        }

        Spacer(Modifier.height(8.dp))

        interactions.take(5).forEach { stat ->
            CorrelationRow(stat)
        }
    }
}

// ── Treatment Effectiveness Card ──

@Composable
internal fun TreatmentEffectivenessCard(
    treatments: List<EdgeFunctionsService.CorrelationStat>,
    treatmentInteractions: List<EdgeFunctionsService.CorrelationStat> = emptyList(),
    showLegend: Boolean = false,
) {
    val top = remember(treatments) {
        treatments.sortedByDescending { it.liftRatio }.take(5)
    }
    val topInteractions = remember(treatmentInteractions) {
        treatmentInteractions.sortedByDescending { it.liftRatio }.take(5)
    }
    BaseCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(24.dp)) { HubIcons.run { drawShieldCheck(Color(0xFF81C784)) } }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("What Worked", color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text("Medicines & reliefs that shorten your migraines",
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(8.dp))

        if (top.isEmpty() && topInteractions.isEmpty()) {
            Text("Log medicines and reliefs with your migraines to see what works best.",
                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
        }

        top.forEach { stat ->
            TreatmentRow(stat)
        }

        if (topInteractions.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Effective Combinations", color = Color(0xFF81C784),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(4.dp))

            topInteractions.forEach { stat ->
                TreatmentRow(stat)
            }
        }
    }
}

/** Single treatment row — medicine or relief with effectiveness badge */
@Composable
internal fun TreatmentRow(stat: EdgeFunctionsService.CorrelationStat) {
    val green = Color(0xFF81C784)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stat.factorName,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (stat.factorB != null) {
                    Text(
                        stat.factorB,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "used in ${stat.pctMigraineWindows.toInt()}% of migraines",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    ConfidenceDots(stat.pValue, green)
                }
            }
            // Two separate bars: shorter + milder
            Column(horizontalAlignment = Alignment.End) {
                if (stat.durationLift > 1f) {
                    EffectivenessBadge(stat.durationLift, "shorter")
                }
                if (stat.severityLift > 1f) {
                    if (stat.durationLift > 1f) Spacer(Modifier.height(4.dp))
                    EffectivenessBadge(stat.severityLift, "milder")
                }
                // Fallback if neither has data yet (uses overall lift)
                if (stat.durationLift <= 1f && stat.severityLift <= 1f) {
                    EffectivenessBadge(stat.liftRatio, "effective")
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stat.toInsightText(),
            color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Effectiveness badge — green tones (opposite of LiftBadge red tones) */
@Composable
private fun EffectivenessBadge(lift: Float, type: String = "effective") {
    val green = Color(0xFF81C784)
    val label = when {
        lift >= 2f -> "${lift.toInt()}\u00D7 $type"
        else -> "${String.format("%.1f", lift)}\u00D7 $type"
    }
    val fraction = (lift / 5f).coerceIn(0f, 1f)
    Column(horizontalAlignment = Alignment.End) {
        Text(label, color = green,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp))
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .width(80.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(3.dp))
                    .background(green)
            )
        }
    }
}

// ── Treatment Interaction Card ──

@Composable
internal fun TreatmentInteractionCard(interactions: List<EdgeFunctionsService.CorrelationStat>) {
    BaseCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Effective Combinations", color = Color(0xFF81C784),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }
        }

        Spacer(Modifier.height(8.dp))

        interactions.take(5).forEach { stat ->
            TreatmentRow(stat)
        }
    }
}

// ── Gauge Performance Card ──

// ── "What Were You Doing?" Card ──

@Composable
internal fun ContextCard(items: List<InsightsViewModel.ContextItem>, overallAvgSeverity: Float, onClick: (() -> Unit)? = null) {
    BaseCard(modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(24.dp)) { HubIcons.run { drawActivityPulse(Color(0xFF4DD0E1)) } }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("What Were You Doing", color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text("Activities & locations during your migraines",
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
            if (onClick != null) {
                Text("\u2192", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
            }
        }

        Spacer(Modifier.height(8.dp))

        if (items.isEmpty()) {
            Text("Log activities and locations with your migraines to see what you were doing when they hit.",
                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
        } else {
            items.take(7).forEach { item ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.name,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${item.count} migraines (${item.pctOfMigraines.toInt()}%)",
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        val sevColor = when {
                            item.avgSeverity >= 7f -> Color(0xFFE57373)
                            item.avgSeverity >= 5f -> Color(0xFFFFB74D)
                            else -> Color(0xFF81C784)
                        }
                        Box(Modifier.size(8.dp).clip(CircleShape).background(sevColor))
                        Text("${String.format("%.0f", item.avgSeverity)}/10",
                            color = sevColor,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                        item.avgDurationHrs?.let { hrs ->
                            Text("\u00B7", color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.labelSmall)
                            val durText = if (hrs >= 24f) "${String.format("%.0f", hrs / 24f)}d avg duration"
                                else "${String.format("%.0f", hrs)}h avg duration"
                            Text(durText, color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

// ── "How Did It Impact You" Card ──

@Composable
internal fun ImpactCard(
    impactItems: List<InsightsViewModel.ImpactItem>,
    painLocationCounts: List<Pair<String, Int>> = emptyList(),
    severityCounts: List<Pair<Int, Int>> = emptyList(),
    totalMigraines: Int = 0,
    overallAvgSeverity: Float = 5f,
    onClick: (() -> Unit)? = null,
) {
    val hasData = impactItems.isNotEmpty() || painLocationCounts.isNotEmpty() || severityCounts.isNotEmpty()

    BaseCard(modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(24.dp)) { HubIcons.run { drawRipple(Color(0xFFE57373)) } }
            Spacer(Modifier.width(8.dp))
            Text("How Did It Impact You", color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f))
            if (onClick != null) {
                Text("\u2192", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
            }
        }

        if (!hasData) {
            Spacer(Modifier.height(8.dp))
            Text("Log pain locations, severity, and missed activities to see your impact summary.",
                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            return@BaseCard
        }

        // ── 1. Severity ──
        if (severityCounts.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val sevColor = androidx.compose.ui.graphics.lerp(
                        AppTheme.AccentPurple, Color(0xFFE57373), ((overallAvgSeverity - 1f) / 9f).coerceIn(0f, 1f)
                    )
                    Text(
                        String.format("%.1f", overallAvgSeverity),
                        color = sevColor,
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                    )
                    Text("avg /10", color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.width(16.dp))
                SeverityMiniChart(severityCounts, modifier = Modifier.weight(1f))
            }
        }

        // ── 2. Pain maps (front + back side by side) ──
        if (painLocationCounts.isNotEmpty() && totalMigraines > 0) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PainHeatMap(
                    painLocationCounts = painLocationCounts,
                    totalMigraines = totalMigraines,
                    points = FRONT_PAIN_POINTS,
                    imageRes = R.drawable.painpoints,
                    modifier = Modifier.weight(1f).aspectRatio(0.75f),
                )
                PainHeatMap(
                    painLocationCounts = painLocationCounts,
                    totalMigraines = totalMigraines,
                    points = BACK_PAIN_POINTS,
                    imageRes = R.drawable.painpointsback,
                    modifier = Modifier.weight(1f).aspectRatio(0.75f),
                )
            }
        }

        // ── 3. Missed Activities ──
        if (impactItems.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Missed Activities", color = Color(0xFFE57373),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))

            impactItems.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, color = Color.White,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("missed during ${item.pctOfMigraines.toInt()}% of migraines",
                            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    }
                    Text("${item.totalMissed}\u00D7", color = Color(0xFFE57373),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

// ── Pain Heat Map (reusable for front/back) ──

@Composable
internal fun PainHeatMap(
    painLocationCounts: List<Pair<String, Int>>,
    totalMigraines: Int,
    points: List<PainPoint> = FRONT_PAIN_POINTS,
    imageRes: Int = R.drawable.painpoints,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var imageSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // Resolve both direct IDs and legacy label-based entries (matches iOS PainHeatMapView)
    val countsMap = remember(painLocationCounts) {
        val labelToIds = mapOf(
            "forehead" to listOf("forehead_center", "forehead_left", "forehead_right"),
            "both temples" to listOf("temple_left", "temple_right"),
            "behind left eye" to listOf("eye_left"),
            "behind right eye" to listOf("eye_right"),
            "behind both eyes" to listOf("eye_left", "eye_right"),
            "back of head" to listOf("occipital_center"),
            "base of skull" to listOf("base_skull_center", "base_skull_left", "base_skull_right"),
            "jaw" to listOf("jaw_left", "jaw_right"),
            "neck" to listOf("neck_left", "neck_right"),
            "sinus" to listOf("sinus_left", "sinus_right"),
            "ear" to listOf("ear_left", "ear_right"),
            "brow" to listOf("brow_left", "brow_right"),
            "teeth" to listOf("teeth_left", "teeth_right"),
            "upper back" to listOf("upper_back_center", "upper_back_left", "upper_back_right"),
            "shoulder" to listOf("shoulder_left", "shoulder_right"),
            "top of head" to listOf("vertex"),
            "top_of_head" to listOf("vertex"),
            "right_temple" to listOf("temple_right"),
            "left_temple" to listOf("temple_left"),
            "behind_right_eye" to listOf("eye_right"),
            "behind_left_eye" to listOf("eye_left"),
        )
        val result = mutableMapOf<String, Int>()
        for ((loc, count) in painLocationCounts) {
            result[loc] = count
            labelToIds[loc.lowercase()]?.forEach { id ->
                result[id] = maxOf(result[id] ?: 0, count)
            }
        }
        result.toMap()
    }

    Box(modifier.clip(RoundedCornerShape(12.dp))) {
        Image(
            painter = painterResource(imageRes),
            contentDescription = "Pain location heatmap",
            contentScale = ContentScale.FillWidth,
            alpha = 0.3f,
            modifier = Modifier.fillMaxSize()
                .onGloballyPositioned { imageSize = it.size },
        )

        if (imageSize.width > 0 && imageSize.height > 0) {
            points.forEach { point ->
                val count = countsMap[point.id] ?: return@forEach
                val pct = (count.toFloat() / totalMigraines.coerceAtLeast(1) * 100).toInt()
                if (pct <= 0) return@forEach

                val alpha = (pct / 100f).coerceIn(0.2f, 0.9f)
                val radius = (7 + (pct / 100f * 7)).dp

                val xPx = point.xPct * imageSize.width
                val yPx = point.yPct * imageSize.height
                val xDp = with(density) { xPx.toDp() }
                val yDp = with(density) { yPx.toDp() }

                Box(
                    modifier = Modifier
                        .offset(x = xDp - radius, y = yDp - radius)
                        .size(radius * 2)
                        .clip(CircleShape)
                        .background(Color(0xFFE57373).copy(alpha = alpha)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$pct",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = if (pct >= 100) 7.sp else 8.sp,
                        ),
                    )
                }
            }
        }
    }
}

// ── Severity Mini Chart (1-10 bar distribution) ──

@Composable
internal fun SeverityMiniChart(
    severityCounts: List<Pair<Int, Int>>,
    modifier: Modifier = Modifier,
    barHeight: Dp = 36.dp,
) {
    val countsMap = remember(severityCounts) { severityCounts.toMap() }
    val maxCount = remember(severityCounts) { severityCounts.maxOfOrNull { it.second } ?: 1 }

    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        (1..10).forEach { sev ->
            val count = countsMap[sev] ?: 0
            val fraction = if (maxCount > 0) count.toFloat() / maxCount else 0f
            val barColor = androidx.compose.ui.graphics.lerp(
                AppTheme.AccentPurple, Color(0xFFE57373), ((sev - 1) / 9f)
            )

            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((barHeight.value * fraction.coerceAtLeast(0.05f)).dp)
                        .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                        .background(barColor.copy(alpha = if (count > 0) 0.7f else 0.15f))
                )
                Text("$sev", color = AppTheme.SubtleTextColor.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp))
            }
        }
    }
}

@Composable
internal fun GaugePerformanceCard(
    ga: EdgeFunctionsService.GaugeAccuracy,
    proposals: List<InsightsViewModel.GaugeProposal>,
    applyingIds: Set<String>,
    onAccept: (String) -> Unit,
    onReject: (String) -> Unit,
    onAcceptAll: () -> Unit,
) {
    // ── Card 1: Gauge Accuracy ──
    BaseCard {

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("${ga.sensitivityPct}%", color = Color(0xFF81C784),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                    Spacer(Modifier.height(4.dp))
                    Text("Caught", color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium))
                }
            }
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("${ga.falseAlarmRatePct}%",
                        color = if (ga.falseAlarmRatePct > 30) Color(0xFFE57373) else Color(0xFFFFB74D),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                    Spacer(Modifier.height(4.dp))
                    Text("False alarms", color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium))
                }
            }
        }
    }
}

@Composable
internal fun GaugeProposalRow(
    proposal: InsightsViewModel.GaugeProposal,
    applying: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val zoneColor = when (proposal.label.uppercase()) {
        "HIGH" -> Color(0xFFE57373)
        "MILD" -> Color(0xFFFFB74D)
        "LOW" -> Color(0xFF81C784)
        else -> AppTheme.BodyTextColor
    }
    val typeLabel = if (proposal.type == "gauge_threshold") "Threshold" else "Decay curve"

    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(proposal.label.uppercase(), color = zoneColor,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                Spacer(Modifier.width(6.dp))
                Text(typeLabel, color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall)
            }
            if (proposal.fromValue != null && proposal.toValue != null && proposal.type == "gauge_threshold") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(proposal.fromValue, color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall)
                    Text(" → ", color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall)
                    Text(proposal.toValue, color = zoneColor,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                }
            }
            if (!proposal.reasoning.isNullOrBlank()) {
                Text(proposal.reasoning, color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }

        if (applying) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppTheme.AccentPurple)
        } else {
            IconButton(onClick = onReject, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "Reject",
                    tint = AppTheme.SubtleTextColor, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(2.dp))
            IconButton(onClick = onAccept, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Check, contentDescription = "Accept",
                    tint = Color(0xFF81C784), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
internal fun GaugeStat(value: String, label: String, sublabel: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Text(label, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.labelSmall)
        if (sublabel.isNotBlank()) {
            Text(sublabel, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ── Weekly Summary Card ──

@Composable
private fun WeeklySummaryCard(ws: InsightsViewModel.WeeklySummary, onClick: () -> Unit) {
    HeroCard(modifier = Modifier.clickable { onClick() }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(24.dp)) { HubIcons.run { drawCalendarWeek(AppTheme.AccentPurple) } }
            Spacer(Modifier.width(8.dp))
            Text("This Week", color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f))
            Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${ws.thisWeekCount}", color = Color.White,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                    if (ws.trend != "stable") {
                        Spacer(Modifier.width(4.dp))
                        val trendColor = if (ws.trend == "up") Color(0xFFE57373) else Color(0xFF81C784)
                        val arrow = if (ws.trend == "up") "↑" else "↓"
                        Text(arrow, color = trendColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    }
                }
                Text("migraines", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.labelSmall)
                Text("vs ${ws.lastWeekCount} last week", color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall)
            }

            ws.thisWeekAvgSeverity?.let { avg ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val sevColor = when {
                        avg >= 7f -> Color(0xFFE57373)
                        avg >= 4f -> Color(0xFFFFB74D)
                        else -> Color(0xFF81C784)
                    }
                    Text(String.format("%.1f", avg), color = sevColor,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                    Text("avg severity", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.labelSmall)
                    ws.lastWeekAvgSeverity?.let { lastAvg ->
                        Text("vs ${String.format("%.1f", lastAvg)} last week", color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${ws.totalLogged}", color = AppTheme.AccentPurple,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                Text("total", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.labelSmall)
                Text("all time", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ── Insight History Card ──

@Composable
private fun InsightHistoryCard(history: List<InsightsViewModel.DailyInsightRow>) {
    var expanded by remember { mutableStateOf(false) }
    val visible = if (expanded) history else history.take(3)

    BaseCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("💡", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Daily Insights", color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text("Your recent personalised advice",
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(8.dp))

        visible.forEachIndexed { i, row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.width(52.dp)) {
                    val dateParts = row.date.split("-")
                    val displayDate = if (dateParts.size == 3) "${dateParts[2]}/${dateParts[1]}" else row.date
                    Text(displayDate, color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                    row.riskZone?.let { zone ->
                        val zoneColor = when (zone.uppercase()) {
                            "HIGH" -> Color(0xFFE57373)
                            "MILD" -> Color(0xFFFFB74D)
                            "LOW" -> Color(0xFF81C784)
                            else -> AppTheme.SubtleTextColor
                        }
                        Box(
                            Modifier
                                .padding(top = 2.dp)
                                .height(4.dp)
                                .width(32.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(zoneColor)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    row.insight,
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (i < visible.lastIndex) {
                Divider(color = AppTheme.SubtleTextColor.copy(alpha = 0.1f))
            }
        }

        if (history.size > 3) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (expanded) "Show less" else "Show all ${history.size} insights",
                color = AppTheme.AccentPurple,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
            )
        }
    }
}

// ── Treatment Effectiveness Card ──

@Composable
private fun TreatmentEffectivenessContent(
    items: List<InsightsViewModel.TreatmentItem>,
) {
    Spacer(Modifier.height(12.dp))
    Divider(color = AppTheme.SubtleTextColor.copy(alpha = 0.15f))
    Spacer(Modifier.height(12.dp))

    Text("Effectiveness Ranking", color = AppTheme.SubtleTextColor,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
    Spacer(Modifier.height(8.dp))

    items.take(8).forEach { item -> TreatmentRow(item) }

    // Legend
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF81C784)))
            Text("High relief", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFFFB74D)))
            Text("Some relief", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFE57373)))
            Text("Low/none", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TreatmentRow(item: InsightsViewModel.TreatmentItem) {
    val barColor = when {
        item.avgRelief >= 2.2f -> Color(0xFF81C784)
        item.avgRelief >= 1.2f -> Color(0xFFFFB74D)
        else -> Color(0xFFE57373)
    }
    val pct = (item.avgRelief / 3f).coerceIn(0f, 1f)
    val label = when {
        item.avgRelief >= 2.5f -> "High"
        item.avgRelief >= 1.5f -> "Mild"
        item.avgRelief >= 0.5f -> "Low"
        else -> "None"
    }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(item.name, color = Color.White,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${item.count}\u00D7 used", color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelSmall)
        }

        Spacer(Modifier.width(12.dp))

        Box(
            Modifier.width(80.dp).height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(AppTheme.SubtleTextColor.copy(alpha = 0.15f))
        ) {
            Box(
                Modifier.fillMaxHeight()
                    .fillMaxWidth(pct)
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor)
            )
        }

        Spacer(Modifier.width(8.dp))

        Text(label, color = barColor,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.width(36.dp))
    }
}

// ── Day-of-Week Pattern Card ──

@Composable
private fun DayOfWeekCard(pattern: List<InsightsViewModel.DayOfWeekStat>) {
    if (pattern.isEmpty()) return
    val maxCount = pattern.maxOf { it.count }.coerceAtLeast(1)
    val total = pattern.sumOf { it.count }
    if (total < 3) return

    val worst = pattern.maxByOrNull { it.count }

    BaseCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("\uD83D\uDCC6", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Day of Week", color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text("When your migraines hit most",
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth().height(100.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            pattern.forEach { day ->
                val fraction = day.count.toFloat() / maxCount
                val isWorst = day == worst && day.count > 0
                val barColor = when {
                    isWorst -> Color(0xFFE57373)
                    fraction > 0.7f -> Color(0xFFFFB74D)
                    fraction > 0f -> AppTheme.AccentPurple
                    else -> AppTheme.SubtleTextColor.copy(alpha = 0.2f)
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f),
                ) {
                    if (day.count > 0) {
                        Text("${day.count}", color = barColor,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                    }

                    Spacer(Modifier.height(4.dp))

                    Box(
                        Modifier
                            .width(24.dp)
                            .height(maxOf(4.dp, (fraction * 60).dp))
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(barColor)
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(day.dayName, color = if (isWorst) Color.White else AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (isWorst) FontWeight.Bold else FontWeight.Normal
                        ))
                }
            }
        }

        worst?.takeIf { it.count > 0 }?.let { w ->
            Spacer(Modifier.height(8.dp))
            val avgPerDay = total.toFloat() / 7f
            val ratio = if (avgPerDay > 0) w.count / avgPerDay else 0f
            if (ratio > 1.3f) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AppTheme.AccentPurple.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        "${w.dayName}s account for ${String.format("%.0f", w.pct)}% of your migraines \u2014 " +
                            "${String.format("%.1f", ratio)}\u00D7 the average day.",
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        }
    }
}


