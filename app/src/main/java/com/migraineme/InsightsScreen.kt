package com.migraineme

import android.content.Context
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import androidx.navigation.NavHostController
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class MigraineSpan(val start: Instant, val end: Instant?, val severity: Int? = null, val label: String? = null, val id: String? = null)
data class ReliefSpan(val start: Instant, val end: Instant?, val intensity: Int? = null, val name: String)
data class TriggerPoint(val at: Instant, val name: String)
data class MedicinePoint(val at: Instant, val name: String, val amount: String?)
enum class TimeSpan(val days: Long, val label: String) { DAY(1, "Day"), WEEK(7, "Week"), MONTH(30, "Month"), YEAR(365, "Year"); val millis: Long get() = days * 24L * 60L * 60L * 1000L }
data class DailyMetricPoint(val date: String, val value: Double)
data class MetricSeries(val key: String, val label: String, val unit: String, val color: Color, val points: List<DailyMetricPoint>)
data class EventMarker(val at: Instant, val endAt: Instant? = null, val name: String, val category: String, val detail: String? = null, val color: Color, val isAutomated: Boolean = false)

val EventCategoryColors = mapOf(
    "Trigger" to Color(0xFFFF8A65), "Prodrome" to Color(0xFFFFD54F), "Medicine" to Color(0xFF4FC3F7),
    "Relief" to Color(0xFF81C784), "Activity" to Color(0xFFBA68C8), "Location" to Color(0xFF4DD0E1),
    "Missed Activity" to Color(0xFFEF9A9A)
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

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            //  Full Report card — taps to paywall for free users
            HeroCard(modifier = Modifier.clickable {
                if (premiumStateTop.isPremium) navController.navigate(Routes.INSIGHTS_REPORT)
                else navController.navigate(Routes.PAYWALL)
            }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Full Report", color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        Text("Filter, compare & explore all your migraine data",
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (!premiumStateTop.isPremium) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = "Premium",
                            tint = AppTheme.AccentPurple,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
                }
            }

            //  Timeline card — taps to paywall for free users
            HeroCard(modifier = Modifier.clickable {
                if (premiumStateTop.isPremium) navController.navigate(Routes.INSIGHTS_DETAIL)
                else navController.navigate(Routes.PAYWALL)
            }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Migraine Timeline", color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f))
                    if (!premiumStateTop.isPremium) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = "Premium",
                            tint = AppTheme.AccentPurple,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
                }

                if (sorted.isEmpty()) {
                    Text("No migraines logged yet", color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    return@HeroCard
                }

                MigraineSelector(sorted, selIdx, sel,
                    { if (selIdx < sorted.size - 1) vm.selectMigraine(selIdx + 1) },
                    { if (selIdx > 0) vm.selectMigraine(selIdx - 1) })

                if (linkedLoading) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppTheme.AccentPurple)
                    }
                } else {
                    // Event count chips (categories only – no "Auto" chip)
                    val catCounts = remember(windowEvents) {
                        windowEvents.groupBy { it.category }.mapValues { it.value.size }
                    }
                    if (catCounts.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            catCounts.forEach { (c, n) ->
                                Chip(n, c, EventCategoryColors[c] ?: AppTheme.AccentPurple)
                            }
                        }
                    }
                }

                // Metric picker chips (grouped by category)
                //  REMOVED: metric picker is on the detail screen 

                // Graph (compact)
                InsightsTimelineGraph(
                    windowMigs, windowEvents, enabledSeries,
                    wStart, wEnd, sel?.start,
                    Modifier.fillMaxWidth().height(220.dp))

                Text("Window around migraine", color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

                WindowDaysControl(wBefore, wAfter, onChanged = { b, a -> vm.setWindowDays(b, a) })
            }

            Spacer(Modifier.height(4.dp))

            // Spider cards — premium only (except trigger spider as teaser)
            if (spiderLoading) {
                BaseCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = AppTheme.AccentPurple)
                        Spacer(Modifier.width(12.dp))
                        Text("Loading insights...", color = AppTheme.SubtleTextColor)
                    }
                }
            } else {
                val ml by vm.migraines.collectAsState()
                val premiumState by PremiumManager.state.collectAsState()

                // Trigger spider — free teaser
                if (triggerSpider != null && triggerSpider!!.totalLogged > 0) {
                    val medEff by vm.medicineEffectiveness.collectAsState()
                    val relEff by vm.reliefEffectiveness.collectAsState()
                    SpiderInsightCard(triggerSpider!!, {
                        if (premiumState.isPremium) {
                            navController.navigate("${Routes.INSIGHTS_BREAKDOWN}/${triggerSpider!!.logType}")
                        } else {
                            navController.navigate(Routes.PAYWALL)
                        }
                    })
                }

                // All other spiders — premium only
                PremiumGate(
                    message = "Unlock All Insights",
                    subtitle = "Spider charts, symptom patterns, treatment effectiveness",
                    onUpgrade = { navController.navigate(Routes.PAYWALL) }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (symptomSpider != null && symptomSpider!!.totalLogged > 0) {
                            SymptomsInsightCard(ml) {
                                navController.navigate("${Routes.INSIGHTS_BREAKDOWN}/${symptomSpider!!.logType}")
                            }
                        }
                        val medEff by vm.medicineEffectiveness.collectAsState()
                        val relEff by vm.reliefEffectiveness.collectAsState()
                        listOf(prodromeSpider, medicineSpider, reliefSpider,
                            locationSpider, activitySpider, missedActivitySpider).forEach { sp ->
                            if (sp != null && sp.totalLogged > 0) {
                                val eff = when (sp.logType) {
                                    "Medicines" -> if (medEff.isNotEmpty() && sp.axes.size >= 3) {
                                        val m = medEff.associate { it.category to it.avgRelief }
                                        sp.axes.map { SpiderAxis(it.label, m[it.label] ?: 0f, 3f) }
                                    } else null
                                    "Reliefs" -> if (relEff.isNotEmpty() && sp.axes.size >= 3) {
                                        val m = relEff.associate { it.category to it.avgRelief }
                                        sp.axes.map { SpiderAxis(it.label, m[it.label] ?: 0f, 3f) }
                                    } else null
                                    else -> null
                                }
                                SpiderInsightCard(sp, {
                                    navController.navigate("${Routes.INSIGHTS_BREAKDOWN}/${sp.logType}")
                                }, eff)
                            }
                        }
                    }
                }
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
private fun SpiderInsightCard(data: SpiderData, onClick: () -> Unit, secondAxes: List<SpiderAxis>? = null) {
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
private fun SymptomsInsightCard(ms: List<MigraineSpan>, onClick: () -> Unit) {
    val vm: InsightsViewModel = viewModel(LocalContext.current as ViewModelStoreOwner)
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
        pcs?.takeIf { it.axes.isNotEmpty() }?.let { data ->
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



