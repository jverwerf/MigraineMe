package com.migraineme

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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

/** Brainy header icon inside a soft organic blob. */
@Composable
internal fun BrainyBlobIcon(resId: Int = R.drawable.brainy_detective_small, flip: Boolean = false) {
    Box(
        modifier = Modifier
            .size(width = 58.dp, height = 54.dp)
            .background(
                brush = Brush.linearGradient(
                    listOf(Color(0x57CE93D8), Color(0x24B388FF))
                ),
                shape = RoundedCornerShape(
                    topStartPercent = 46, topEndPercent = 54,
                    bottomEndPercent = 42, bottomStartPercent = 58
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(resId),
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .graphicsLayer(scaleX = if (flip) -1f else 1f)
        )
    }
}

/**
 * Static navigation card for the Insights hub: Brainy + title + a one-line
 * description of what lives on the detail page. Never shows data itself.
 */
@Composable
internal fun BrainyNavCard(
    title: String,
    description: String,
    resId: Int,
    onClick: () -> Unit,
    flipBlob: Boolean = false,
    flipWatermark: Boolean = false,
    preview: (@Composable ColumnScope.() -> Unit)? = null,
) {
    BrainyWatermarkCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        resId = resId,
        flipWatermark = flipWatermark
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BrainyBlobIcon(resId = smallVariantOf(resId), flip = flipBlob)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(description, color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(8.dp))
            Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
        }
        if (preview != null) {
            Spacer(Modifier.height(6.dp))
            preview()
        }
    }
}

/** Inline-text row icon on the same soft blob as BrainyRowIcon, sized for 18sp art. */
@Composable
internal fun InlineBlobIcon(res: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(listOf(Color(0x57CE93D8), Color(0x24B388FF))),
                shape = RoundedCornerShape(
                    topStartPercent = 46, topEndPercent = 54,
                    bottomEndPercent = 42, bottomStartPercent = 58
                )
            )
            .padding(2.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(painterResource(res), contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

/** Stat tile matching the Accuracy detail's first card, reused in its nav-card preview. */
@Composable
internal fun AccuracyStatTile(value: String, color: Color, label: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, color = color,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(2.dp))
            Text(label, color = AppTheme.SubtleTextColor,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium))
        }
    }
}

/** One line of a nav-card preview: label + compact stat, same row style as RecommendationsCard. */
/**
 * [sub] is a second line under the label, for cards whose row needs a plain
 * sentence as well as a short right-hand verdict. What Worked uses it so the hub
 * preview says exactly what the page says, rather than a number the page no
 * longer shows.
 */
internal data class CardPreviewEntry(val label: String, val stat: String, val category: String? = null,
                                     val iconKey: String? = null, val sub: String? = null)

@Composable
internal fun ColumnScope.CardPreviewRows(entries: List<CardPreviewEntry>, totalCount: Int = entries.size) {
    val shown = entries.take(2)
    shown.forEachIndexed { i, e ->
        if (i > 0) Spacer(Modifier.height(6.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.04f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrainyRowIcon(e.label, iconKey = e.iconKey, category = e.category, size = 18.dp)
            Column(Modifier.weight(1f)) {
                Text(prettyLabel(e.label), color = Color.White,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                e.sub?.let {
                    Text(it, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            // Bounded share. An unweighted stat measures first and takes whatever
            // width it wants, which starved the label column to a single character
            // on long lift strings ("about 5 times as likely · 46% of attacks").
            // 0.8 against the label's 1f caps the stat at ~44% of the row.
            Text(e.stat, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.8f))
        }
    }
    val extra = totalCount - shown.size
    if (extra > 0) {
        Text(t("+%s more", extra), color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.End))
    }
}

private fun smallVariantOf(resId: Int): Int = when (resId) {
    R.drawable.brainy_detective -> R.drawable.brainy_detective_small
    R.drawable.brainy_archer -> R.drawable.brainy_archer_small
    R.drawable.brainy_shield -> R.drawable.brainy_shield_small
    R.drawable.brainy_runner -> R.drawable.brainy_runner_small
    R.drawable.brainy_briefcase -> R.drawable.brainy_briefcase_small
    R.drawable.brainy_recover -> R.drawable.brainy_recover_small
    R.drawable.brainy_gardener -> R.drawable.brainy_gardener_small
    R.drawable.brainy_risk -> R.drawable.brainy_risk_small
    else -> resId
}

/** BaseCard variant with a faint oversized Brainy bleeding off the bottom-right corner. */
@Composable
internal fun BrainyWatermarkCard(
    modifier: Modifier = Modifier,
    resId: Int = R.drawable.brainy_detective,
    // watermark Brainys should look left, into the card; flip right-facing poses
    flipWatermark: Boolean = false,
    contentPadding: Dp = 12.dp,
    innerSpacing: Dp = 6.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppTheme.BaseCardShape,
        colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer),
        elevation = CardDefaults.cardElevation(0.dp),
        border = AppTheme.BaseCardBorder
    ) {
        Box(Modifier.fillMaxWidth()) {
            // matchParentSize keeps the oversized watermark from stretching short cards
            Box(Modifier.matchParentSize()) {
                Image(
                    painter = painterResource(resId),
                    contentDescription = null,
                    modifier = Modifier
                        .size(120.dp)
                        .align(Alignment.BottomEnd)
                        .offset(x = 18.dp, y = 24.dp)
                        .alpha(0.14f)
                        .graphicsLayer(scaleX = if (flipWatermark) -1f else 1f)
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(innerSpacing),
                content = content
            )
        }
    }
}

data class MigraineSpan(
    val start: Instant,
    val end: Instant?,
    val severity: Int? = null,
    val label: String? = null,
    val id: String? = null,
    val painLocations: List<String> = emptyList(),
    val auraLocations: List<String> = emptyList(),
    val auraDurationMinutes: Int? = null
)
data class ReliefSpan(val start: Instant, val end: Instant?, val intensity: Int? = null, val name: String, val sideEffectScale: String? = null)
data class TriggerPoint(val at: Instant, val name: String)
data class MedicinePoint(val at: Instant, val name: String, val amount: String?, val sideEffectScale: String? = null)
enum class TimeSpan(val days: Long, val label: String) { DAY(1, "Day"), WEEK(7, "Week"), MONTH(30, "Month"), YEAR(365, "Year"); val millis: Long get() = days * 24L * 60L * 60L * 1000L }
data class DailyMetricPoint(val date: String, val value: Double)
data class MetricSeries(val key: String, val label: String, val unit: String, val color: Color, val points: List<DailyMetricPoint>)
data class EventMarker(val at: Instant, val endAt: Instant? = null, val name: String, val category: String, val detail: String? = null, val color: Color, val isAutomated: Boolean = false)

val EventCategoryColors = mapOf(
    "Trigger" to Color(0xFFFFB74D), "Prodrome" to Color(0xFF9575CD),
    "Pain Character" to Color(0xFFCE93D8), "Accompanying" to Color(0xFFBA68C8),
    "Postdrome" to Color(0xFFAB47BC),
    "Medicine" to Color(0xFF4FC3F7),
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
    MetricDef("thunderstorm", "Thunderstorm", "", Color(0xFFFFD54F), "Environment"),
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
    MetricDef("screen_time", "Screen Time", "min", Color(0xFFFF8A65), "Cognitive"),
    MetricDef("late_screen", "Late Screen", "hrs", Color(0xFFFF7043), "Cognitive"),
    MetricDef("noise", "Noise", "", Color(0xFFFFB74D), "Cognitive"),
    MetricDef("brightness", "Brightness", "%", Color(0xFFFFF176), "Cognitive"),
    MetricDef("volume", "Volume", "%", Color(0xFFAED581), "Cognitive"),
    MetricDef("unlocks", "Unlocks", "", Color(0xFF4DD0E1), "Cognitive"),
    MetricDef("dark_mode", "Dark Mode", "hrs", Color(0xFF546E7A), "Cognitive"),
    MetricDef("mindfulness", "Mindfulness", "min", Color(0xFF80CBC4), "Cognitive"),
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
    MetricDef("histamine", "Histamine", "risk", Color(0xFFAB47BC), "Diet"),
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
            .flatMap { ev -> vm.metricKeysForLabel(ev.name) }
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
    val hubTreatmentTiming by vm.treatmentTiming.collectAsState()
    val dayOfWeekPattern by vm.dayOfWeekPattern.collectAsState()
    val contextItems by vm.contextItems.collectAsState()
    val impactItems by vm.impactItems.collectAsState()
    val migraineSpans by vm.migraines.collectAsState()
    val overallAvgSeverity = remember(migraineSpans) {
        val severities = migraineSpans.mapNotNull { it.severity }
        if (severities.isEmpty()) 5f else severities.average().toFloat()
    }

    // Split correlations by type (all dynamic, no hardcoded labels).
    // Engine-gated rows carry a mode (comparison/prevalence) and are shown directly; fall back to
    // isSignificant for older untagged rows.
    val significantCorrelations = remember(correlations) {
        correlations.filter { it.hasGateMode || it.isSignificant() }
    }
    val triggerCorrelations = remember(significantCorrelations) {
        significantCorrelations.filter { it.factorType == "trigger" && it.symptomOutcome == null }
            .sortedByDescending { it.liftRatio }
    }
    val metricCorrelations = remember(significantCorrelations) {
        significantCorrelations.filter { it.factorType == "metric" }
            .sortedByDescending { it.liftRatio }
    }
    val interactionCorrelations = remember(significantCorrelations) {
        significantCorrelations.filter { it.factorType == "interaction" && it.isRealCombo }
            .sortedByDescending { it.liftRatio }
    }
    val thresholdNudges = remember(significantCorrelations) {
        significantCorrelations.filter {
            it.factorType == "metric" && it.suggestedThreshold != null && it.currentThreshold != null &&
                kotlin.math.abs(it.suggestedThreshold - it.currentThreshold) > it.currentThreshold * 0.05f
        }.sortedByDescending { it.liftRatio }
    }

    // ── Nav-card previews: each card surfaces its top two entries inline ──
    val accuracyPreview: (@Composable ColumnScope.() -> Unit)? =
        gaugeAccuracy?.takeIf { it.totalDays > 0 }?.let { ga ->
            {
                // Same two stat tiles as the Accuracy detail's first card.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccuracyStatTile(
                        value = "${ga.sensitivityPct}%",
                        color = Color(0xFF81C784),
                        label = t("Caught — migraines that followed a warning"),
                        modifier = Modifier.weight(1f),
                    )
                    AccuracyStatTile(
                        value = "${ga.falseAlarmRatePct}%",
                        color = if (ga.falseAlarmRatePct > 30) Color(0xFFE57373) else Color(0xFFFFB74D),
                        label = t("False alarms — warnings with no migraine"),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    val patternPool = triggerCorrelations + metricCorrelations
    val whatHappenedPreview: (@Composable ColumnScope.() -> Unit)? =
        patternPool.takeIf { it.isNotEmpty() }?.let { pool ->
            {
                CardPreviewRows(
                    pool.take(2).map {
                        // Risk, not treatment: how much more often this turns up
                        // around an attack than on a normal day. Prevalence-mode
                        // rows have no fair comparison, so they only say how often.
                        // A chronic row (long attacks expanded into flare days)
                        // counts days, not attacks, and says so.
                        val stat = if (it.mode == "prevalence")
                            (if (it.isChronic) tSync("in %s%% of flare days", it.pctMigraineWindows.toInt())
                             else tSync("in %s%% of attacks", it.pctMigraineWindows.toInt()))
                        else
                            (if (it.isChronic) tSync("%1\$s · %2\$s%% of flare days",
                                    liftTimesText(it.liftRatio), it.pctMigraineWindows.toInt())
                             else tSync("%1\$s · %2\$s%% of attacks",
                                    liftTimesText(it.liftRatio), it.pctMigraineWindows.toInt()))
                        CardPreviewEntry(it.factorName, stat)
                    },
                    totalCount = pool.size + interactionCorrelations.size
                )
            }
        }
    // The preview is built by the same builder as the What Worked page and shows
    // the same verdict and the same line. A preview that promises a row the page
    // then denies was the original bug on this card.
    val whatWorkedRowsHub = buildWhatWorkedRows(
        pool = medicineItems.map { it to "medicine" } + reliefItems.map { it to "relief" },
        stats = correlations,
        timing = hubTreatmentTiming,
    )
    val whatWorkedPreview: (@Composable ColumnScope.() -> Unit)? =
        whatWorkedRowsHub.takeIf { it.isNotEmpty() }?.let { pool ->
            {
                CardPreviewRows(
                    pool.take(2).map { row ->
                        CardPreviewEntry(row.name, verdictText(row.verdict), row.category,
                            sub = evidenceText(row.evidence))
                    },
                    totalCount = pool.size
                )
            }
        }
    val helpingPool = correlations.filter { it.factorType == "well_done" }.sortedByDescending { it.liftRatio }
    val whatsHelpingPreview: (@Composable ColumnScope.() -> Unit)? =
        helpingPool.takeIf { it.isNotEmpty() }?.let { pool ->
            {
                CardPreviewRows(
                    pool.take(2).map {
                        // Attack-free days, a third question again: how much more
                        // often you did this on the days no attack came.
                        CardPreviewEntry(it.factorName,
                            tSync("on %s%% of migraine-free days", it.pctControlWindows.toInt()))
                    },
                    totalCount = pool.size
                )
            }
        }
    val contextPool = contextItems.sortedByDescending { it.count }
    val contextPreview: (@Composable ColumnScope.() -> Unit)? =
        contextPool.takeIf { it.isNotEmpty() }?.let { pool ->
            {
                CardPreviewRows(
                    pool.take(2).map {
                        CardPreviewEntry(it.name, tSync("%1\$s attacks · %2\$s%%", it.count, it.pctOfMigraines.toInt()))
                    },
                    totalCount = pool.size
                )
            }
        }
    val impactPool = impactItems.sortedByDescending { it.totalMissed }
    val painLocsHub by vm.painLocationCounts.collectAsState()
    val totalMigsHub by vm.totalMigraineCount.collectAsState()
    val auraAttacksHub by vm.auraAttackCount.collectAsState()
    // Expose pain + aura first, then missed activities fill the remainder.
    val impactEntries = buildList {
        painLocsHub.firstOrNull()?.let { (locId, count) ->
            if (totalMigsHub > 0) add(CardPreviewEntry(ALL_PAIN_POINTS_MAP[locId] ?: locId,
                tSync("in %1\$s attacks · %2\$s%%", count, count * 100 / totalMigsHub),
                iconKey = "migraine_starburst"))
        }
        if (auraAttacksHub > 0) add(CardPreviewEntry(tSync("Aura"), tSync("in %s attacks", auraAttacksHub)))
        impactPool.forEach {
            add(CardPreviewEntry(it.name, tSync("missed %1\$s times · %2\$s%%", it.totalMissed, it.pctOfMigraines.toInt())))
        }
    }
    val auraZonesHub by vm.auraZoneCounts.collectAsState()
    val impactPreview: (@Composable ColumnScope.() -> Unit)? =
        impactEntries.takeIf { it.isNotEmpty() }?.let { pool ->
            {
                CardPreviewRows(
                    pool.take(2),
                    totalCount = painLocsHub.size + (if (auraAttacksHub > 0) 1 else 0) + impactPool.size
                )
                // The man and the eyes: mini pain heat map + aura zones inline.
                if ((painLocsHub.isNotEmpty() && totalMigsHub > 0) || (auraZonesHub.isNotEmpty() && auraAttacksHub > 0)) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (painLocsHub.isNotEmpty() && totalMigsHub > 0) {
                            PainHeatMap(
                                painLocationCounts = painLocsHub,
                                totalMigraines = totalMigsHub,
                                points = FRONT_PAIN_POINTS,
                                imageRes = R.drawable.painpoints,
                                modifier = Modifier.weight(1f).aspectRatio(0.75f),
                            )
                        }
                        if (auraZonesHub.isNotEmpty() && auraAttacksHub > 0) {
                            AuraHeatMap(
                                auraZoneCounts = auraZonesHub,
                                totalAuraAttacks = auraAttacksHub,
                                modifier = Modifier.weight(1.4f),
                            )
                        }
                    }
                }
            }
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
                Text(t("Loading insights\u2026"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp, spacing = 8.dp) {

            // ── 1. WEEKLY SUMMARY + Explore Migraines moved to Monitor → Migraines ──

            // ── Content (data loaded) ──
            val ml by vm.migraines.collectAsState()
                val premiumState by PremiumManager.state.collectAsState()
                val medEff by vm.medicineEffectiveness.collectAsState()
                val relEff by vm.reliefEffectiveness.collectAsState()
                val recomputeStatus by vm.recomputeStatus.collectAsState()

                // ── 0. CUSTOMIZE INSIGHTS (entry card, same as Monitor's Configure Monitor) ──
                val insightsCtx = LocalContext.current
                var insightsConfig by remember { mutableStateOf(InsightsCardConfigStore.load(insightsCtx)) }
                LaunchedEffect(Unit) {
                    insightsConfig = InsightsCardConfigStore.load(insightsCtx)
                }
                HeroCard(
                    modifier = Modifier.clickable { navController.navigate(Routes.INSIGHTS_CONFIG) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = t("Configure"),
                            tint = AppTheme.AccentPurple,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                t("Customize Insights"),
                                color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                t("Show, hide, and reorder cards"),
                                color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            "\u2192",
                            color = AppTheme.AccentPurple,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }

                // ── 2. FULL REPORT (pinned, not configurable) ──
                var showFullReportInfo by remember { mutableStateOf(false) }
                Box(modifier = Modifier.fillMaxWidth()) {
                    BrainyWatermarkCard(modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            premiumGatedClickable(
                                access = premiumStateTop.access,
                                onOpen = { navController.navigate(Routes.INSIGHTS_REPORT) },
                                onUpgrade = { navController.navigate(Routes.PAYWALL) }
                            )
                        ),
                        resId = R.drawable.brainy_briefcase, flipWatermark = true
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            BrainyBlobIcon(R.drawable.brainy_briefcase_small)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t("Full Report"), color = AppTheme.TitleColor,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                                Text(t("Generate a doctor-ready PDF with all your migraine data"),
                                    color = AppTheme.SubtleTextColor,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            // Only once we know they are on the free tier — a
                            // padlock drawn while entitlement is still resolving
                            // is shown to subscribers too.
                            if (premiumStateTop.access == PremiumAccess.NOT_ENTITLED) {
                                Icon(Icons.Outlined.Lock, contentDescription = t("Premium"),
                                    tint = AppTheme.AccentPurple, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("\u2192", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    IconButton(
                        onClick = { showFullReportInfo = true },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 10.dp, y = (-14).dp)
                            .size(34.dp)
                    ) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = t("About Full Report"),
                                tint = AppTheme.SubtleTextColor,
                                modifier = Modifier.size(20.dp)
                            )
                    }
                }
                if (showFullReportInfo) {
                    AlertDialog(
                        onDismissRequest = { showFullReportInfo = false },
                        confirmButton = {
                            TextButton(onClick = { showFullReportInfo = false }) {
                                Text(t("Got it"), color = AppTheme.AccentPurple)
                            }
                        },
                        title = {
                            Text(t("About Full Report"), color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        },
                        text = {
                            Text(FullReportInfoCopy.text, modifier = Modifier.verticalScroll(rememberScrollState()), color = AppTheme.BodyTextColor,
                                style = MaterialTheme.typography.bodyMedium)
                        },
                        containerColor = AppTheme.BaseCardContainer
                    )
                }

                // ── 3. Explore Migraines moved to Monitor → Migraines (file kept as Routes.INSIGHTS_DETAIL target) ──
                if (false) {
                BaseCard(modifier = Modifier.clickable {
                    navController.navigate(Routes.INSIGHTS_DETAIL)
                }) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(24.dp)) { HubIcons.run { drawMigraineStarburst(AppTheme.AccentPink) } }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t("Explore Migraines"), color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text(t("Timeline, breakdowns & detailed charts"),
                                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("\u2192", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
                    }
                }
                } // end if(false) wrapper hiding the moved Explore Migraines card
                // ── Configurable cards, in the user's order (Customize Insights) ──
                // Each card keeps its own code; key(cardId) keeps remember{} state
                // attached to the card when the order changes.
                for (insightsCardId in insightsConfig.getOrderedVisibleCards()) {
                key(insightsCardId) {
                when (insightsCardId) {
                InsightsCardConfig.CARD_RECOMMENDATIONS -> {
                // ── 3b. AI RECOMMENDATIONS (premium) ──
                val aiRecs by vm.aiRecommendations.collectAsState()
                val dismissedRecKeys by vm.dismissedRecommendationKeys.collectAsState()
                var showRecsInfo by remember { mutableStateOf(false) }
                if (buildRecommendationSections(aiRecs, dismissedRecKeys).isNotEmpty()) {
                    PremiumGate(
                        message = t("Unlock AI Recommendations"),
                        subtitle = t("Per-category guidance from your data"),
                        onUpgrade = { navController.navigate(Routes.PAYWALL) }
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            val recsContext = LocalContext.current
                            RecommendationsCard(
                                aiRecs, dismissedRecKeys,
                                onDismiss = { category, name, evidence ->
                                    vm.dismissRecommendation(recsContext, category, name, evidence)
                                }
                            ) {
                                navController.navigate(Routes.INSIGHTS_RECOMMENDATIONS)
                            }
                            IconButton(
                                onClick = { showRecsInfo = true },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 10.dp, y = (-14).dp)
                                    .size(34.dp)
                            ) {
                                    Icon(
                                        Icons.Outlined.Info,
                                        contentDescription = t("About AI Recommendations"),
                                        tint = AppTheme.SubtleTextColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                            }
                        }
                    }
                }
                if (showRecsInfo) {
                    AlertDialog(
                        onDismissRequest = { showRecsInfo = false },
                        confirmButton = {
                            TextButton(onClick = { showRecsInfo = false }) {
                                Text(t("Got it"), color = AppTheme.AccentPurple)
                            }
                        },
                        title = {
                            Text(t("About AI Recommendations"), color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        },
                        text = {
                            Text(AiRecommendationsInfoCopy.text, modifier = Modifier.verticalScroll(rememberScrollState()), color = AppTheme.BodyTextColor,
                                style = MaterialTheme.typography.bodyMedium)
                        },
                        containerColor = AppTheme.BaseCardContainer
                    )
                }


                } // end card

                InsightsCardConfig.CARD_ACCURACY -> {
                // ── 4. ACCURACY ──
                var showAccuracyInfo by remember { mutableStateOf(false) }
                PremiumGate(
                    message = t("Unlock Accuracy"),
                    subtitle = t("See how well your risk score lined up with your logged attacks"),
                    onUpgrade = { navController.navigate(Routes.PAYWALL) }
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        BrainyNavCard(
                            title = t("Accuracy"),
                            description = t("How well your risk score lined up with your attacks"),
                            resId = R.drawable.brainy_archer,
                            flipWatermark = true,
                            onClick = { navController.navigate(Routes.INSIGHTS_THRESHOLDS) },
                            preview = accuracyPreview,
                        )
                        IconButton(
                            onClick = { showAccuracyInfo = true },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 10.dp, y = (-14).dp)
                                .size(34.dp)
                        ) {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = t("About Accuracy"),
                                    tint = AppTheme.SubtleTextColor,
                                    modifier = Modifier.size(20.dp)
                                )
                        }
                    }
                }
                if (showAccuracyInfo) {
                    AlertDialog(
                        onDismissRequest = { showAccuracyInfo = false },
                        confirmButton = {
                            TextButton(onClick = { showAccuracyInfo = false }) {
                                Text(t("Got it"), color = AppTheme.AccentPurple)
                            }
                        },
                        title = {
                            Text(t("About Accuracy"), color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        },
                        text = {
                            Text(AccuracyInfoCopy.text, modifier = Modifier.verticalScroll(rememberScrollState()), color = AppTheme.BodyTextColor,
                                style = MaterialTheme.typography.bodyMedium)
                        },
                        containerColor = AppTheme.BaseCardContainer
                    )
                }

                } // end card

                InsightsCardConfig.CARD_PATTERNS -> {
                // ── 5. WHAT HAPPENED ──
                var showWhatHappenedInfo by remember { mutableStateOf(false) }
                PremiumGate(
                    message = t("Unlock Pattern Analysis"),
                    subtitle = t("See correlations, combinations & thresholds"),
                    onUpgrade = { navController.navigate(Routes.PAYWALL) }
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        BrainyNavCard(
                            title = t("What Happened"),
                            description = t("Triggers, patterns & dangerous combinations behind your migraines"),
                            resId = R.drawable.brainy_detective,
                            flipBlob = true,
                            onClick = { navController.navigate(Routes.INSIGHTS_PATTERNS) },
                            preview = whatHappenedPreview,
                        )
                        IconButton(
                            onClick = { showWhatHappenedInfo = true },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 10.dp, y = (-14).dp)
                                .size(34.dp)
                        ) {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = t("About What Happened"),
                                    tint = AppTheme.SubtleTextColor,
                                    modifier = Modifier.size(20.dp)
                                )
                        }
                    }
                }
                if (showWhatHappenedInfo) {
                    AlertDialog(
                        onDismissRequest = { showWhatHappenedInfo = false },
                        confirmButton = {
                            TextButton(onClick = { showWhatHappenedInfo = false }) {
                                Text(t("Got it"), color = AppTheme.AccentPurple)
                            }
                        },
                        title = {
                            Text(t("About What Happened"), color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        },
                        text = {
                            Text(WhatHappenedInfoCopy.text, modifier = Modifier.verticalScroll(rememberScrollState()), color = AppTheme.BodyTextColor,
                                style = MaterialTheme.typography.bodyMedium)
                        },
                        containerColor = AppTheme.BaseCardContainer
                    )
                }

                } // end card

                InsightsCardConfig.CARD_TREATMENTS -> {
                // ── 6. WHAT WORKED ──
                var showWhatWorkedInfo by remember { mutableStateOf(false) }
                PremiumGate(
                    message = t("Unlock Treatment Analysis"),
                    subtitle = t("See which medicines and reliefs work best"),
                    onUpgrade = { navController.navigate(Routes.PAYWALL) }
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        BrainyNavCard(
                            title = t("What Worked"),
                            description = t("How your attacks went when you used each medicine and relief"),
                            resId = R.drawable.brainy_shield,
                            flipWatermark = true,
                            onClick = { navController.navigate(Routes.INSIGHTS_TREATMENTS) },
                            preview = whatWorkedPreview,
                        )
                        IconButton(
                            onClick = { showWhatWorkedInfo = true },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 10.dp, y = (-14).dp)
                                .size(34.dp)
                        ) {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = t("About What Worked"),
                                    tint = AppTheme.SubtleTextColor,
                                    modifier = Modifier.size(20.dp)
                                )
                        }
                    }
                }
                if (showWhatWorkedInfo) {
                    AlertDialog(
                        onDismissRequest = { showWhatWorkedInfo = false },
                        confirmButton = {
                            TextButton(onClick = { showWhatWorkedInfo = false }) {
                                Text(t("Got it"), color = AppTheme.AccentPurple)
                            }
                        },
                        title = {
                            Text(t("About What Worked"), color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        },
                        text = {
                            Text(WhatWorkedInfoCopy.text, modifier = Modifier.verticalScroll(rememberScrollState()), color = AppTheme.BodyTextColor,
                                style = MaterialTheme.typography.bodyMedium)
                        },
                        containerColor = AppTheme.BaseCardContainer
                    )
                }

                } // end card

                InsightsCardConfig.CARD_HELPING -> {
                // ── 6b. WHAT'S HELPING (Well Done positive layer) ──
                // docs/well-done-layer-spec.md (migraineme-ios repo). Habits
                // present on migraine-free days + what drives them.
                var showWhatsHelpingInfo by remember { mutableStateOf(false) }
                PremiumGate(
                    message = t("Unlock What Strengthens You"),
                    subtitle = t("See the habits behind your migraine-free days"),
                    onUpgrade = { navController.navigate(Routes.PAYWALL) }
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        BrainyNavCard(
                            title = t("What Strengthens You"),
                            description = t("Habits that show up on your migraine-free days"),
                            resId = R.drawable.brainy_gardener,
                            flipWatermark = true,
                            onClick = { navController.navigate(Routes.INSIGHTS_WHATS_HELPING) },
                            preview = whatsHelpingPreview,
                        )
                        IconButton(
                            onClick = { showWhatsHelpingInfo = true },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 10.dp, y = (-14).dp)
                                .size(34.dp)
                        ) {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = t("About What Strengthens You"),
                                    tint = AppTheme.SubtleTextColor,
                                    modifier = Modifier.size(20.dp)
                                )
                        }
                    }
                }
                if (showWhatsHelpingInfo) {
                    AlertDialog(
                        onDismissRequest = { showWhatsHelpingInfo = false },
                        confirmButton = {
                            TextButton(onClick = { showWhatsHelpingInfo = false }) {
                                Text(t("Got it"), color = AppTheme.AccentPurple)
                            }
                        },
                        title = {
                            Text(t("About What Strengthens You"), color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        },
                        text = {
                            Text(WhatsHelpingInfoCopy.text, modifier = Modifier.verticalScroll(rememberScrollState()), color = AppTheme.BodyTextColor,
                                style = MaterialTheme.typography.bodyMedium)
                        },
                        containerColor = AppTheme.BaseCardContainer
                    )
                }

                } // end card

                InsightsCardConfig.CARD_CHANGES -> {
                // ── 6c. WHAT CHANGED (last 30 days vs the 30 before) ──
                // Mirrors the PDF report's What changed page; hidden entirely
                // when no item's count moved between the two windows.
                val itemTrends by vm.itemTrends.collectAsState()
                val changedTrends = remember(itemTrends) {
                    itemTrends.filter { it.current != it.prior }
                }
                var showWhatChangedInfo by remember { mutableStateOf(false) }
                if (changedTrends.isNotEmpty()) {
                    PremiumGate(
                        message = t("Unlock What Changed"),
                        subtitle = t("See which logged items moved over the last month"),
                        onUpgrade = { navController.navigate(Routes.PAYWALL) }
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            WhatChangedCard(changedTrends) {
                                navController.navigate(Routes.INSIGHTS_WHAT_CHANGED)
                            }
                            IconButton(
                                onClick = { showWhatChangedInfo = true },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 10.dp, y = (-14).dp)
                                    .size(34.dp)
                            ) {
                                    Icon(
                                        Icons.Outlined.Info,
                                        contentDescription = t("About What Changed"),
                                        tint = AppTheme.SubtleTextColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                            }
                        }
                    }
                }
                if (showWhatChangedInfo) {
                    AlertDialog(
                        onDismissRequest = { showWhatChangedInfo = false },
                        confirmButton = {
                            TextButton(onClick = { showWhatChangedInfo = false }) {
                                Text(t("Got it"), color = AppTheme.AccentPurple)
                            }
                        },
                        title = {
                            Text(t("About What Changed"), color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        },
                        text = {
                            Text(WhatChangedInfoCopy.text, modifier = Modifier.verticalScroll(rememberScrollState()), color = AppTheme.BodyTextColor,
                                style = MaterialTheme.typography.bodyMedium)
                        },
                        containerColor = AppTheme.BaseCardContainer
                    )
                }

                } // end card

                InsightsCardConfig.CARD_CONTEXT -> {
                // ── 7. WHAT WERE YOU DOING ──
                var showContextInfo by remember { mutableStateOf(false) }
                PremiumGate(
                    message = t("Unlock Context Analysis"),
                    subtitle = t("See what you were doing when migraines hit"),
                    onUpgrade = { navController.navigate(Routes.PAYWALL) }
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        BrainyNavCard(
                            title = t("What Were You Doing"),
                            description = t("Activities & locations during your migraines"),
                            resId = R.drawable.brainy_runner,
                            flipWatermark = true,
                            onClick = { navController.navigate(Routes.INSIGHTS_CONTEXT) },
                            preview = contextPreview,
                        )
                        IconButton(
                            onClick = { showContextInfo = true },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 10.dp, y = (-14).dp)
                                .size(34.dp)
                        ) {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = t("About What Were You Doing"),
                                    tint = AppTheme.SubtleTextColor,
                                    modifier = Modifier.size(20.dp)
                                )
                        }
                    }
                }
                if (showContextInfo) {
                    AlertDialog(
                        onDismissRequest = { showContextInfo = false },
                        confirmButton = {
                            TextButton(onClick = { showContextInfo = false }) {
                                Text(t("Got it"), color = AppTheme.AccentPurple)
                            }
                        },
                        title = {
                            Text(t("About What Were You Doing"), color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        },
                        text = {
                            Text(ContextInfoCopy.text, modifier = Modifier.verticalScroll(rememberScrollState()), color = AppTheme.BodyTextColor,
                                style = MaterialTheme.typography.bodyMedium)
                        },
                        containerColor = AppTheme.BaseCardContainer
                    )
                }

                } // end card

                InsightsCardConfig.CARD_IMPACT -> {
                // ── 8. HOW DID IT IMPACT YOU ──
                val painLocCounts by vm.painLocationCounts.collectAsState()
                val sevCounts by vm.severityCounts.collectAsState()
                val totalMigraineCount by vm.totalMigraineCount.collectAsState()
                val topSymptoms by vm.symptomStats.collectAsState()
                val auraZoneCounts by vm.auraZoneCounts.collectAsState()
                val auraAttackCount by vm.auraAttackCount.collectAsState()
                val auraDurationStats by vm.auraDurationStats.collectAsState()
                val auraInsights by vm.auraInsights.collectAsState()
                var showImpactInfo by remember { mutableStateOf(false) }
                PremiumGate(
                    message = t("Unlock Impact Analysis"),
                    subtitle = t("See severity, pain locations & missed activities"),
                    onUpgrade = { navController.navigate(Routes.PAYWALL) }
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        BrainyNavCard(
                            title = t("How Did It Impact You"),
                            description = t("Severity, pain locations, symptoms & missed activities"),
                            resId = R.drawable.brainy_recover,
                            flipWatermark = true,
                            onClick = { navController.navigate(Routes.INSIGHTS_IMPACT) },
                            preview = impactPreview,
                        )
                        IconButton(
                            onClick = { showImpactInfo = true },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 10.dp, y = (-14).dp)
                                .size(34.dp)
                        ) {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = t("About How Did It Impact You"),
                                    tint = AppTheme.SubtleTextColor,
                                    modifier = Modifier.size(20.dp)
                                )
                        }
                    }
                }
                if (showImpactInfo) {
                    AlertDialog(
                        onDismissRequest = { showImpactInfo = false },
                        confirmButton = {
                            TextButton(onClick = { showImpactInfo = false }) {
                                Text(t("Got it"), color = AppTheme.AccentPurple)
                            }
                        },
                        title = {
                            Text(t("About How Did It Impact You"), color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        },
                        text = {
                            Text(ImpactInfoCopy.text, modifier = Modifier.verticalScroll(rememberScrollState()), color = AppTheme.BodyTextColor,
                                style = MaterialTheme.typography.bodyMedium)
                        },
                        containerColor = AppTheme.BaseCardContainer
                    )
                }

                } // end card
                } // end when
                } // end key
                } // end for

                // ── Medical disclaimer (dismissible, Google Play Health Content policy) ──
                MedicalDisclaimerCard(prefKey = "insights_dismissed")


        }
    }
}

// ── Dismissible medical disclaimer box (health policy) ──
// One X dismisses it app-wide on this device ("all_dismissed"); the legacy
// per-surface prefKey is still read so pre-existing dismissals keep their
// surface hidden. Per-device by design: never synced to the backend, so a
// fresh install (and any store reviewer) always sees it once.
@Composable
fun MedicalDisclaimerCard(prefKey: String) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("medical_disclaimer", Context.MODE_PRIVATE) }
    var dismissed by remember {
        mutableStateOf(prefs.getBoolean("all_dismissed", false) || prefs.getBoolean(prefKey, false))
    }
    if (dismissed) return
    BaseCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                t("MigraineMe is not a medical device and does not diagnose, treat, cure, or prevent any condition. Risk estimates and insights are based on your own logged data and are not medical advice; always consult a qualified healthcare professional."),
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    prefs.edit().putBoolean("all_dismissed", true).apply()
                    dismissed = true
                },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = t("Dismiss disclaimer"),
                    tint = AppTheme.SubtleTextColor,
                    modifier = Modifier.size(16.dp)
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
    val df = DateTimeFormatter.ofPattern("MMM d, yyyy", rememberAppLocale()).withZone(z)
    val tf = DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(rememberAppLocale()).withZone(z)
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
private fun Chip(n: Int, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text("$n", color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.width(2.dp))
        Text(t(label), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
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
                        "Symptoms", "Migraines" -> drawMigraineStarburst(color)
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
                // logType stays the English domain key (drives colors/icons);
                // translated only at render.
                Text(t(logType), color = AppTheme.TitleColor,
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
                        "Symptoms", "Migraines" -> drawMigraineStarburst(color)
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
                Text(t(data.logType), color = AppTheme.TitleColor,
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
                Text(t("Side effects: "), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
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
                    Text(t(label), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
                }
            }
        }
    }
}

@Composable
internal fun SymptomsInsightCard(ms: List<MigraineSpan>, onClick: () -> Unit) {
    val vm: InsightsViewModel = viewModel(LocalContext.current as ViewModelStoreOwner)
    val pcs by vm.painCharSpider.collectAsState()
    val acs by vm.accompSpider.collectAsState()
    val pds by vm.postdromeSpider.collectAsState()
    BaseCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(24.dp)) { HubIcons.run { drawMigraineStarburst(AppTheme.AccentPink) } }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t("Migraines"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                Text(t("%s migraines logged", ms.size),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
            Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodyMedium)
        }
        pcs?.takeIf { it.axes.isNotEmpty() }?.let { data ->
            Spacer(Modifier.height(16.dp))
            Text(t("Pain Character"), color = Color(0xFFEF5350),
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
            Text(t("Accompanying Experience"), color = Color(0xFFBA68C8),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(4.dp))
            if (data.axes.size >= 3) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    SpiderChart(axes = data.axes, accentColor = Color(0xFFBA68C8), size = 200.dp)
                }
            } else StackedProportionalBar(axes = data.axes, accentColor = Color(0xFFBA68C8))
        }
        pds?.takeIf { it.axes.isNotEmpty() }?.let { data ->
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

// ══════════════════════════════════════════════════════════════════
// INSIGHT CARDS — All data-driven, no hardcoded labels
// ══════════════════════════════════════════════════════════════════

/**
 * A risk lift in plain English. The old copy printed a bare "×1.7" and, above
 * 2, truncated it with .toInt(), so a 2.9× read "2× more likely" — the wrong
 * number and a glyph nobody reads the same way twice. Spelled out, at any size,
 * with no rounding lie. Same wording as iOS.
 *
 * This is deliberately NOT the What Worked vocabulary: this card answers "how
 * much does this raise the chance of an attack starting", which is a different
 * question from "did this treatment help once one had".
 */
internal fun liftTimesText(lift: Float): String = tSync("about %s times as likely", trimLift(lift))

/** Lift ratio as a colored bar + plain-language text */
@Composable
internal fun LiftBadge(lift: Float) {
    val label = liftTimesText(lift)
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

/** Right-side badge for a correlation row. ALWAYS shows the attack count + %; comparison-mode
 *  rows additionally show the "X× more likely" multiplier. (The "how often vs raises risk"
 *  explanation lives in the card's (i) info sheet, not on every row.) */
@Composable
internal fun PatternBadge(stat: EdgeFunctionsService.CorrelationStat) {
    val pct = stat.pctMigraineWindows.toInt()
    // Chronic rows count flare days inside long attacks, so the unit is named;
    // plain rows keep the bare "in N of M" the badge has always shown.
    val countLabel = if (stat.isChronic)
        t("in %1\$s of %2\$s flare days (%3\$s%%)", stat.attackHits, stat.sampleSize, pct)
    else "in ${stat.attackHits} of ${stat.sampleSize} ($pct%)"
    if (stat.mode == "prevalence") {
        Text(countLabel, color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp))
    } else {
        Column(horizontalAlignment = Alignment.End) {
            LiftBadge(stat.liftRatio)
            Spacer(Modifier.height(2.dp))
            Text(countLabel, color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp))
        }
    }
}

/** Lag indicator */
@Composable
internal fun LagChip(lagDays: Int) {
    val text = when (lagDays) {
        0 -> t("same day")
        1 -> t("1 day before")
        else -> t("%s days before", lagDays)
    }
    Text(
        text,
        color = AppTheme.SubtleTextColor,
        style = MaterialTheme.typography.labelSmall,
    )
}

/**
 * Confidence dot based on p-value. p is nullable since the v2 treatment schema:
 * a rating-only row ran no test. It still gets one lit dot — a weak result shown
 * weakly is honest, an empty dots row reads as broken.
 */
@Composable
internal fun ConfidenceDots(pValue: Float?, color: Color = AppTheme.AccentPurple) {
    val p = pValue ?: 1f
    val dots = when {
        p < 0.01f -> 3
        p < 0.05f -> 2
        else -> 1
    }
    ConfidenceDotsCount(dots, color)
}

/** Same dots, drawn from a count the engine already decided. Never zero. */
@Composable
internal fun ConfidenceDotsCount(dots: Int, color: Color = AppTheme.AccentPurple) {
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
            PatternBadge(stat)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stat.toInsightText(),
            color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ── Pain response card (intraday engine) ──────────────────────
/**
 * Every row shown has already passed the engine's gate (>= 5 occurrences with
 * a pain reading on both sides, |median effect| >= 1.0 pain points, >= 60%
 * sign consistency), so this view never decides what is worth saying.
 * Aggravator rows show how pain ROSE in the hours after the event; easer rows
 * how it FELL. An easer whose pain consistently rose arrives flagged
 * caution = true and renders in amber — flagged, never hidden.
 */
@Composable
internal fun IntradayResponseCard(
    rows: List<EdgeFunctionsService.IntradayResponseStat>,
    easers: Boolean,
    showBlob: Boolean = false,
    watermark: Boolean = false,
) {
    if (rows.isEmpty()) return
    fun horizonText(minutes: Int): String = when {
        minutes % 60 == 0 -> "~${minutes / 60}h"
        else -> "~${minutes}m"
    }
    val amber = Color(0xFFFFB74D)
    val red = Color(0xFFE57373)
    val green = Color(0xFF81C784)
    val sorted = remember(rows) { rows.sortedByDescending { kotlin.math.abs(it.medianEffect) } }
    val top = sorted.take(4)
    MaybeWatermarkCard(watermark = watermark, resId = R.drawable.brainy_detective) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (showBlob) {
                BrainyBlobIcon(flip = true)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(t("Pain response"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                Text(
                    if (easers) t("How your pain moved after these treatments")
                    else t("How your pain moved after these were logged"),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(6.dp))
        top.forEach { row ->
            val rising = row.medianEffect > 0f
            val color = when {
                row.caution -> amber
                rising -> red
                else -> green
            }
            val effectText = (if (rising) "+" else "−") +
                String.format("%.1f", kotlin.math.abs(row.medianEffect))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BrainyRowIcon(row.label, size = 18.dp)
                Column(Modifier.weight(1f)) {
                    Text(prettyLabel(row.label), color = Color.White,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (row.caution) {
                        Text(t("pain rose after this"), color = amber,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(t("%1\$s within %2\$s", effectText, horizonText(row.horizonMinutes)),
                        color = color,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                    Spacer(Modifier.height(2.dp))
                    ConfidenceDots(row.pValue, color)
                }
            }
        }
        if (sorted.size > top.size) {
            Text(t("+%s more", sorted.size - top.size), color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(4.dp))
        Text(t("Compared with your pain entries in the 3 hours before each log."),
            color = AppTheme.SubtleTextColor.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * "Usually starts: Left Temple (5 of 5)" for a trigger row, or null when no
 * location_trigger row exists for that trigger. Several locations for one
 * trigger → the one with the lowest p. Label comes from the head map's own
 * table so the line names the same place the silhouette plots.
 */
@Composable
internal fun usuallyStartsLine(
    stat: EdgeFunctionsService.CorrelationStat,
    locationRows: List<EdgeFunctionsService.CorrelationStat>,
): String? {
    if (stat.factorType != "trigger" || locationRows.isEmpty()) return null
    val name = stat.factorName.trim().lowercase()
    val best = locationRows
        .filter { it.isLocationTrigger && it.factorName.trim().lowercase() == name && it.factorB != null }
        .minByOrNull { it.pValue ?: 1f } ?: return null
    val id = canonicalPainLocationId(best.factorB!!)
    val label = ALL_PAIN_POINTS_MAP[id] ?: prettyLabel(id)
    return t("Usually starts: %1\$s (%2\$s of %3\$s)", t(label), best.withHits, best.withTotal)
}

/** Compact correlation row — name + lag + lift only, no insight text */
@Composable
private fun CorrelationRowCompact(
    stat: EdgeFunctionsService.CorrelationStat,
    locationRows: List<EdgeFunctionsService.CorrelationStat> = emptyList(),
) {
    // Tightened vertical padding so rows are denser (was 6.dp + 2.dp/4.dp
    // internal gaps — looked airy compared to iOS). Labels also run through
    // prettyLabel so snake_case from older logs renders cleanly.
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        BrainyRowIcon(stat.factorName, size = 18.dp)
        Column(Modifier.weight(1f)) {
            if (stat.factorB != null) {
                Text(
                    prettyLabel(stat.factorName),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    prettyLabel(stat.factorB),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    prettyLabel(stat.factorName),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(1.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LagChip(stat.bestLagDays)
                ConfidenceDots(stat.pValue)
            }
            // Duration & severity descriptors for triggers
            if (stat.factorType == "trigger" && (stat.avgDurationHrs != null || stat.avgSeverity != null)) {
                Spacer(Modifier.height(1.dp))
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
            // Where attacks with this trigger tend to start (location_trigger row).
            usuallyStartsLine(stat, locationRows)?.let { line ->
                Spacer(Modifier.height(1.dp))
                Text(line, color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        PatternBadge(stat)
    }
}

// ── Top Patterns Card ──

@Composable
internal fun TopPatternsCard(
    triggers: List<EdgeFunctionsService.CorrelationStat>,
    metrics: List<EdgeFunctionsService.CorrelationStat>,
    interactions: List<EdgeFunctionsService.CorrelationStat> = emptyList(),
    iconKeys: Map<String, String> = emptyMap(),
    locationTriggers: List<EdgeFunctionsService.CorrelationStat> = emptyList(),
    watermarkOnLast: Boolean = false,
    showBlob: Boolean = true,
) {
    // Source of truth — matches the edge function's "topRiskFactors"
    // definition (compute-correlation-stats:1491): trigger || metric.
    // The detail screen (sole caller) shows ALL significant findings;
    // home / Full Report previews use the dedicated *Preview* cards.
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("insights_patterns", Context.MODE_PRIVATE) }
    var hiddenKeys by remember { mutableStateOf(prefs.getStringSet("hidden", emptySet())!!.toSet()) }
    var showHidden by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf("Highest risk") }
    var comboSortMode by remember { mutableStateOf("Highest risk") }

    fun keyOf(stat: EdgeFunctionsService.CorrelationStat) = "${stat.factorName}|${stat.factorB ?: ""}"
    fun toggleHidden(stat: EdgeFunctionsService.CorrelationStat) {
        val k = keyOf(stat)
        hiddenKeys = if (k in hiddenKeys) hiddenKeys - k else hiddenKeys + k
        prefs.edit().putStringSet("hidden", hiddenKeys).apply()
    }
    fun sorted(list: List<EdgeFunctionsService.CorrelationStat>, mode: String) = when (mode) {
        "Most frequent" -> list.sortedByDescending { it.pctMigraineWindows }
        "Most severe" -> list.sortedByDescending { it.avgSeverity ?: -1f }
        "Days before" -> list.sortedBy { it.bestLagDays }
        "Newest" -> list.sortedByDescending { it.updatedAt }
        "Oldest" -> list.sortedBy { it.updatedAt }
        else -> list.sortedByDescending { it.liftRatio }
    }
    // Patterns mixes triggers, metrics and interactions, so a factor name belongs
    // to no one pool: "Fatigue" and "Yawning" live in the prodrome pool and
    // "Weather change" in none at all. Looking only in the trigger pool, by key
    // only, left every metric row and most trigger rows with no icon. The pool key
    // stays the preferred path when there is one; the manifest resolves the rest
    // by label across all kinds.
    fun iconFor(name: String?): Int? = name?.let { n ->
        brainyForLogKey(iconKeys[n.lowercase()], n)
    }

    val combined = remember(triggers, metrics) { triggers + metrics }
    val topInteractions = remember(interactions) { interactions }
    if (combined.isEmpty() && topInteractions.isEmpty()) return

    val visiblePatterns = sorted(combined, sortMode).filter { showHidden || keyOf(it) !in hiddenKeys }
    val visibleCombos = sorted(topInteractions, comboSortMode).filter { showHidden || keyOf(it) !in hiddenKeys }
    val hiddenCount = (combined + topInteractions).count { keyOf(it) in hiddenKeys }

    val combosCardShown = visibleCombos.isNotEmpty() || (topInteractions.isNotEmpty() && showHidden)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (combined.isNotEmpty()) {
            MaybeWatermarkCard(watermark = watermarkOnLast && !combosCardShown, resId = R.drawable.brainy_detective) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showBlob) {
                        BrainyBlobIcon(flip = true)
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(t("Patterns"), color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        Text(t("Each factor made a migraine this many times likelier"),
                            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                    SortChipMenu(sortMode,
                        listOf("Highest risk", "Most frequent", "Most severe", "Days before", "Newest", "Oldest")) { sortMode = it }
                }
                Spacer(Modifier.height(6.dp))
                visiblePatterns.forEach { stat ->
                    PatternTile(stat, icon = iconFor(stat.factorName), iconB = iconFor(stat.factorB),
                        dimmed = keyOf(stat) in hiddenKeys, locationRows = locationTriggers) { toggleHidden(stat) }
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Confidence: three dots is the strongest statistical signal."),
                        color = AppTheme.SubtleTextColor.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f))
                    if (hiddenCount > 0) {
                        Text(
                            if (showHidden) t("hide %s again", hiddenCount) else t("%s hidden \u00b7 show", hiddenCount),
                            color = AppTheme.AccentPurple,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.clickable { showHidden = !showHidden }.padding(4.dp)
                        )
                    }
                }
            }
        }

        if (combosCardShown) {
            MaybeWatermarkCard(watermark = watermarkOnLast, resId = R.drawable.brainy_detective) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t("Combinations"), color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        Text(t("Together, these pairs multiplied your risk the most"),
                            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                    SortChipMenu(comboSortMode,
                        listOf("Highest risk", "Most frequent", "Days before", "Newest", "Oldest")) { comboSortMode = it }
                }
                Spacer(Modifier.height(6.dp))
                visibleCombos.forEach { stat ->
                    PatternTile(stat, icon = iconFor(stat.factorName), iconB = iconFor(stat.factorB),
                        dimmed = keyOf(stat) in hiddenKeys) { toggleHidden(stat) }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/** Small sort selector chip with a purple dropdown, shared by Insights detail cards. */
@Composable
internal fun SortChipMenu(current: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            t("%s \u25be", t(current)),
            color = AppTheme.AccentPurple,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { open = true }
                .background(Color.White.copy(alpha = 0.05f))
                .padding(horizontal = 9.dp, vertical = 5.dp)
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.background(Color(0xFF1E0A2E))
        ) {
            options.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(t(mode), color = if (mode == current) AppTheme.AccentPurple else Color.White,
                        style = MaterialTheme.typography.bodySmall) },
                    onClick = { onSelect(mode); open = false }
                )
            }
        }
    }
}

/** BaseCard that becomes a BrainyWatermarkCard when it is the page's last visible card. */
@Composable
internal fun MaybeWatermarkCard(
    watermark: Boolean,
    resId: Int,
    flipWatermark: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (watermark) BrainyWatermarkCard(resId = resId, flipWatermark = flipWatermark, content = content)
    else BaseCard(content = content)
}

/** Quiet rounded tile for one pattern/combination finding. */
@Composable
private fun PatternTile(
    stat: EdgeFunctionsService.CorrelationStat,
    icon: Int? = null,
    iconB: Int? = null,
    dimmed: Boolean = false,
    locationRows: List<EdgeFunctionsService.CorrelationStat> = emptyList(),
    onToggleHide: (() -> Unit)? = null,
) {
    val lagText = when (stat.bestLagDays) {
        0 -> "same day"
        1 -> "1 day before"
        else -> "${stat.bestLagDays} days before"
    }
    // Chronic rows count flare days inside long attacks, not attack rows.
    val occText = if (stat.isChronic)
        t("%1\$s of %2\$s flare days (%3\$s%%)", stat.attackHits, stat.sampleSize, stat.pctMigraineWindows.toInt())
    else
        t("%1\$s of %2\$s attacks (%3\$s%%)", stat.attackHits, stat.sampleSize, stat.pctMigraineWindows.toInt())
    val isCombo = stat.factorType == "interaction"
    val metaColor = Color(0xFF9C8BB0)
    val tileShape = RoundedCornerShape(18.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.55f else 1f)
            .clip(tileShape)
            .background(Color.White.copy(alpha = 0.035f))
            .border(1.dp, Color.White.copy(alpha = 0.05f), tileShape)
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        val titleIsPair = stat.factorB != null
        val headlineStat = if (stat.mode != "prevalence") {
            liftTimesText(stat.liftRatio)
        } else if (stat.isChronic) {
            tSync("in %s%% of flare days", stat.pctMigraineWindows.toInt())
        } else {
            tSync("in %s%% of attacks", stat.pctMigraineWindows.toInt())
        }
        val headlineColor = if (isCombo) Color(0xFFE8A0A0) else Color(0xFFC9A9E8)

        Row(verticalAlignment = Alignment.CenterVertically) {
            val title = androidx.compose.ui.text.buildAnnotatedString {
                if (icon != null) { appendInlineContent("iconA", "\u2b1c"); append(" ") }
                append(stat.factorName)
                if (stat.factorB != null) {
                    append("  +  ")
                    if (iconB != null) { appendInlineContent("iconB", "\u2b1c"); append(" ") }
                    append(stat.factorB)
                }
            }
            val inlineIcons = buildMap {
                icon?.let { res ->
                    put("iconA", androidx.compose.foundation.text.InlineTextContent(
                        androidx.compose.ui.text.Placeholder(24.sp, 22.sp,
                            androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter)
                    ) { InlineBlobIcon(res) })
                }
                iconB?.let { res ->
                    put("iconB", androidx.compose.foundation.text.InlineTextContent(
                        androidx.compose.ui.text.Placeholder(24.sp, 22.sp,
                            androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter)
                    ) { InlineBlobIcon(res) })
                }
            }
            Text(
                title,
                inlineContent = inlineIcons,
                color = Color(0xFFF3EAFB),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // A pair title ("A  +  B") needs the whole row: sharing it with the
            // headline stat truncated the second factor away, which is the half
            // that makes it a combination. Pairs move the stat to the meta line.
            if (!titleIsPair) {
                Spacer(Modifier.width(10.dp))
                Text(
                    headlineStat,
                    color = headlineColor,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(0.8f)
                )
            }
            onToggleHide?.let { toggle ->
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (dimmed) Icons.Outlined.Check else Icons.Outlined.Close,
                    contentDescription = if (dimmed) t("Unhide") else t("Hide"),
                    tint = AppTheme.SubtleTextColor.copy(alpha = if (dimmed) 0.9f else 0.45f),
                    modifier = Modifier.size(15.dp).clickable { toggle() }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$lagText  \u00b7  $occText", color = metaColor,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f))
            if (titleIsPair) {
                Spacer(Modifier.width(10.dp))
                Text(
                    headlineStat,
                    color = headlineColor,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.End
                )
            }
        }
        // Where attacks with this trigger tend to start (location_trigger row, best p).
        usuallyStartsLine(stat, locationRows)?.let { line ->
            Spacer(Modifier.height(2.dp))
            Text(line, color = metaColor, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(t("confidence"), color = metaColor, style = MaterialTheme.typography.labelSmall)
            ConfidenceDots(stat.pValue, Color(0xFFB388FF))
            if (stat.factorType == "trigger" && (stat.avgSeverity != null || stat.avgDurationHrs != null)) {
                Text("\u00b7", color = metaColor, style = MaterialTheme.typography.labelSmall)
                stat.avgSeverity?.let { sev ->
                    Text(t("%s/10 avg severity", String.format("%.0f", sev)), color = metaColor,
                        style = MaterialTheme.typography.labelSmall)
                }
                stat.avgDurationHrs?.let { hrs ->
                    if (stat.avgSeverity != null) Text("\u00b7", color = metaColor, style = MaterialTheme.typography.labelSmall)
                    val durText = if (hrs >= 24f) "~${String.format("%.0f", hrs / 24)}d duration"
                        else "~${String.format("%.0f", hrs)}h duration"
                    Text(durText, color = metaColor, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

// ── Patterns & Combinations Preview Card ──

@Composable
internal fun PatternsPreviewCard(
    patterns: List<EdgeFunctionsService.CorrelationStat>,
    interactions: List<EdgeFunctionsService.CorrelationStat>,
    symptomOutcomes: List<EdgeFunctionsService.CorrelationStat> = emptyList(),
    locationTriggers: List<EdgeFunctionsService.CorrelationStat> = emptyList(),
    onShowAll: () -> Unit
) {
    BrainyWatermarkCard(modifier = Modifier.clickable { onShowAll() }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BrainyBlobIcon(flip = true)
            Spacer(Modifier.width(8.dp))
            Text(t("What Happened"), color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f))
            Text("\u2192", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
        }

        if (patterns.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(t("Patterns"), color = Color(0xFFCE93D8),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            patterns.forEach { stat ->
                CorrelationRowCompact(stat, locationRows = locationTriggers)
            }
        }

        if (interactions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(t("Combinations"), color = Color(0xFFE57373),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            interactions.forEach { stat ->
                CorrelationRowCompact(stat)
            }
        }

        if (symptomOutcomes.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(t("Trigger \u2192 Symptom"), color = Color(0xFFFFB74D),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            symptomOutcomes.take(2).forEach { stat ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${prettyLabel(stat.factorName)} \u2192 ${prettyLabel(stat.symptomOutcome)}", color = Color.White,
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f))
                    // The two rates, undivided — same as the Patterns detail card.
                    Text(t("%1\$s%% of days vs %2\$s%% usually",
                        stat.pctMigraineWindows.toInt(), stat.pctControlWindows.toInt()),
                        color = if (stat.pctMigraineWindows >= stat.pctControlWindows * 2f) Color(0xFFE57373) else AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(0.8f))
                }
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

    BrainyWatermarkCard(modifier = Modifier.clickable { onClick() }, resId = R.drawable.brainy_archer, flipWatermark = true) {
        // ── Header ──
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BrainyBlobIcon(R.drawable.brainy_archer_small)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(t("Accuracy"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(t("How well your risk score lined up with your attacks"), color = AppTheme.SubtleTextColor,
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
                        Text(t("Caught"), color = AppTheme.SubtleTextColor,
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
                        Text(t("False alarms"), color = AppTheme.SubtleTextColor,
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
                        Text(t("Days tracked"), color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium))
                    }
                }
            }
        } else {
            Spacer(Modifier.height(14.dp))
            Text(
                t("Need at least 7 days of gauge data to show accuracy."),
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
            Text(t("Threshold Suggestions"), color = AppTheme.TitleColor,
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
                Row(Modifier.weight(0.8f), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(fmtCurrent, color = Color(0xFFFFB74D), style = MaterialTheme.typography.bodySmall)
                    Text(" \u2192 ", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    Text(fmtSuggested, color = Color(0xFF81C784), style = MaterialTheme.typography.bodySmall)
                }
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
                Text(t("Threshold Suggestions"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(t("Your data suggests adjusting these triggers"),
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
                            Text(t("Current"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                            Text(fmtCurrent, color = Color(0xFFFFB74D),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                        }
                        Text("\u2192", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.titleMedium)
                        Column(horizontalAlignment = Alignment.End) {
                            Text(t("Suggested"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
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
                            PatternBadge(stat)
                            ConfidenceDots(stat.pValue)
                        }

                        val statAdjusting = stat.id in adjustingIds
                        Text(
                            if (statAdjusting) t("Adjusting…") else t("Adjust"),
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
                Text(t("Combinations"), color = Color(0xFFE57373),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }
        }

        Spacer(Modifier.height(8.dp))

        // Detail card — show every interaction (was capped at 5).
        interactions.forEach { stat ->
            CorrelationRow(stat)
        }
    }
}

// The treatment multiplier cards lived here: TreatmentPreviewCard,
// TreatmentRowCompact, TreatmentEffectivenessCard, TreatmentTile,
// TreatmentRow, EffectivenessBadge and TreatmentInteractionCard. They
// rendered "N× shorter / milder / effective" off lag_details keys the v2
// engine no longer writes. What Worked is now built by
// TreatmentEffectiveness.kt and shared by the hub, the page and the
// Full Report, so there is one vocabulary and one set of rows.

// ── Gauge Performance Card ──

// ── "What Were You Doing?" Card ──

@Composable
internal fun ContextCard(
    items: List<InsightsViewModel.ContextItem>,
    overallAvgSeverity: Float,
    onClick: (() -> Unit)? = null,
    contextIconKeys: Map<String, Pair<String, Boolean>> = emptyMap(),
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("insights_context", Context.MODE_PRIVATE) }
    var hiddenKeys by remember { mutableStateOf(prefs.getStringSet("hidden", emptySet())!!.toSet()) }
    var showHidden by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf("Most frequent") }

    fun toggleHidden(name: String) {
        hiddenKeys = if (name in hiddenKeys) hiddenKeys - name else hiddenKeys + name
        prefs.edit().putStringSet("hidden", hiddenKeys).apply()
    }
    val sortedItems = remember(items, sortMode) {
        when (sortMode) {
            "Most severe" -> items.sortedByDescending { it.avgSeverity }
            "Longest" -> items.sortedByDescending { it.avgDurationHrs ?: 0f }
            "A to Z" -> items.sortedBy { it.name.lowercase() }
            else -> items.sortedByDescending { it.count }
        }
    }
    // The old label fallbacks called forKey(name.lowercase()), feeding a human
    // label into a snake_case-key lookup: "Eating out" became "eating out" and
    // never matched the key "eating_out", so only accidental single-word keys
    // ("gym", "home", "park") ever resolved. The manifest normalises properly.
    fun iconFor(name: String): Int? =
        brainyForLogKey(contextIconKeys[name.lowercase()]?.first, name)

    val visible = sortedItems.take(12).filter { showHidden || it.name !in hiddenKeys }
    val hiddenCount = items.count { it.name in hiddenKeys }
    val cyan = Color(0xFF8FD4DA)
    val metaColor = Color(0xFF9C8BB0)
    val tileShape = RoundedCornerShape(18.dp)

    BrainyWatermarkCard(modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier, resId = R.drawable.brainy_runner, flipWatermark = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrainyBlobIcon(R.drawable.brainy_runner_small)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t("Activities & Locations"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(t("What you were doing when your migraines hit"),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
            if (onClick != null) {
                Text("\u2192", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
            } else {
                SortChipMenu(sortMode, listOf("Most frequent", "Most severe", "Longest", "A to Z")) { sortMode = it }
            }
        }

        Spacer(Modifier.height(6.dp))

        if (items.isEmpty()) {
            Text(t("Log activities and locations with your migraines to see what you were doing when they hit."),
                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
        } else {
            visible.forEach { item ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .alpha(if (item.name in hiddenKeys) 0.55f else 1f)
                        .clip(tileShape)
                        .background(Color.White.copy(alpha = 0.035f))
                        .border(1.dp, Color.White.copy(alpha = 0.05f), tileShape)
                        .padding(horizontal = 16.dp, vertical = 13.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val title = androidx.compose.ui.text.buildAnnotatedString {
                            if (iconFor(item.name) != null) { appendInlineContent("icon", "\u2b1c"); append(" ") }
                            append(item.name)
                        }
                        val inlineIcons = buildMap {
                            iconFor(item.name)?.let { res ->
                                put("icon", androidx.compose.foundation.text.InlineTextContent(
                                    androidx.compose.ui.text.Placeholder(24.sp, 22.sp,
                                        androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter)
                                ) { InlineBlobIcon(res) })
                            }
                        }
                        Text(
                            title,
                            inlineContent = inlineIcons,
                            color = Color(0xFFF3EAFB),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(t("%1\$s migraines (%2\$s%%)", item.count, item.pctOfMigraines.toInt()),
                            color = cyan,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(0.8f))
                        if (onClick == null) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                if (item.name in hiddenKeys) Icons.Outlined.Check else Icons.Outlined.Close,
                                contentDescription = if (item.name in hiddenKeys) t("Unhide") else t("Hide"),
                                tint = AppTheme.SubtleTextColor.copy(alpha = if (item.name in hiddenKeys) 0.9f else 0.45f),
                                modifier = Modifier.size(15.dp).clickable { toggleHidden(item.name) }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        val sevColor = when {
                            item.avgSeverity >= 7f -> Color(0xFFE59A9A)
                            item.avgSeverity >= 5f -> Color(0xFFD9B27C)
                            else -> Color(0xFF9CCB9E)
                        }
                        Text(t("%s/10 avg severity", String.format("%.0f", item.avgSeverity)),
                            color = sevColor,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                        item.avgDurationHrs?.let { hrs ->
                            Text("\u00b7", color = metaColor, style = MaterialTheme.typography.labelSmall)
                            val durText = if (hrs >= 24f) "~${String.format("%.0f", hrs / 24f)}d avg duration"
                                else "~${String.format("%.0f", hrs)}h avg duration"
                            Text(durText, color = metaColor, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(t("Severity colour: green mild, amber medium, rose severe."),
                    color = AppTheme.SubtleTextColor.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f))
                if (hiddenCount > 0 && onClick == null) {
                    Text(
                        if (showHidden) t("hide %s again", hiddenCount) else t("%s hidden \u00b7 show", hiddenCount),
                        color = AppTheme.AccentPurple,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clickable { showHidden = !showHidden }.padding(4.dp)
                    )
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
    topSymptoms: List<EdgeFunctionsService.SymptomStat> = emptyList(),
    auraZoneCounts: List<Pair<String, Int>> = emptyList(),
    auraAttacks: Int = 0,
    auraDurationStats: Pair<Int, Int>? = null,
    auraInsights: List<EdgeFunctionsService.AuraInsight> = emptyList(),
    onClick: (() -> Unit)? = null,
) {
    val hasData = impactItems.isNotEmpty() || painLocationCounts.isNotEmpty() || severityCounts.isNotEmpty() || topSymptoms.isNotEmpty() || auraZoneCounts.isNotEmpty()

    BrainyWatermarkCard(modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier, resId = R.drawable.brainy_recover) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrainyBlobIcon(R.drawable.brainy_recover_small)
            Spacer(Modifier.width(8.dp))
            Text(t("How Did It Impact You"), color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f))
            if (onClick != null) {
                Text("\u2192", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
            }
        }

        if (!hasData) {
            Spacer(Modifier.height(8.dp))
            Text(t("Log pain locations, severity, and missed activities to see your impact summary."),
                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            return@BrainyWatermarkCard
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
                    Text(t("avg /10"), color = AppTheme.SubtleTextColor,
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

        // ── 2b. Aura map (where in the visual field, % of aura attacks) ──
        if (auraZoneCounts.isNotEmpty() && auraAttacks > 0) {
            Spacer(Modifier.height(14.dp))
            Text(t("Aura Location"), color = AppTheme.AccentPurple,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(2.dp))
            Text((if (auraAttacks == 1) t("Across 1 aura attack, as seen through your own eyes") else t("Across %s aura attacks, as seen through your own eyes", auraAttacks)),
                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(8.dp))
            AuraHeatMap(
                auraZoneCounts = auraZoneCounts,
                totalAuraAttacks = auraAttacks,
                modifier = Modifier.fillMaxWidth()
            )
            // Duration reads at a glance next to the severity average — it's the
            // number a clinician asks about first.
            auraDurationStats?.let { (avgMin, timed) ->
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Average aura duration"), color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(t("%1\$s · %2\$s timed", formatAuraDuration(avgMin), timed),
                        color = AppTheme.AccentPurple,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(0.8f))
                }
            }
            // Server-computed findings — the patterns, not just the counts.
            auraInsights.take(2).forEach { ins ->
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("•", color = AppTheme.AccentPurple,
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(6.dp))
                    Text(ins.headline, color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ── 3. Missed Activities ──
        if (impactItems.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(t("Missed Activities"), color = Color(0xFFE57373),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))

            impactItems.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrainyRowIcon(item.name, size = 20.dp)
                    Column(Modifier.weight(1f)) {
                        Text(prettyLabel(item.name), color = Color.White,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t("missed during %s%% of migraines", item.pctOfMigraines.toInt()),
                            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(t("%s times", item.totalMissed), color = Color(0xFFE57373),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(0.8f))
                }
            }
        }

        TopSymptomsSection(topSymptoms)
    }
}

@Composable
private fun TopSymptomsSection(topSymptoms: List<EdgeFunctionsService.SymptomStat>) {
    if (topSymptoms.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    Text(t("Top Symptoms"), color = Color(0xFFCE93D8),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
    topSymptoms.forEach { s ->
        val pct = s.displayPct
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            BrainyRowIcon(s.symptomLabel, size = 18.dp)
            Text(prettyLabel(s.symptomLabel), color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val sev = s.avgSeverity
            if (sev != null) {
                Text(t("sev %s", String.format("%.1f", sev)),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(end = 6.dp))
            }
            Text("$pct%", color = Color(0xFFCE93D8),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
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
    // The wizard writes canonical PainPoint.id values directly. Older rows can
    // still carry a legacy spelling, so keys go through the exact-match
    // canonicaliser and counts for the same point are summed. Still no fuzzy
    // matching — that was what misplotted markers before.
    val countsMap = remember(painLocationCounts) {
        val result = mutableMapOf<String, Int>()
        for ((loc, count) in painLocationCounts) {
            val id = canonicalPainLocationId(loc)
            result[id] = (result[id] ?: 0) + count
        }
        result.toMap()
    }

    Box(modifier.clip(RoundedCornerShape(12.dp))) {
        Image(
            painter = painterResource(imageRes),
            contentDescription = t("Pain location heatmap"),
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
                    Text(t("Caught"), color = AppTheme.SubtleTextColor,
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
                    Text(t("False alarms"), color = AppTheme.SubtleTextColor,
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
                Text(t(proposal.label).uppercase(), color = zoneColor,
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
                Icon(Icons.Outlined.Close, contentDescription = t("Reject"),
                    tint = AppTheme.SubtleTextColor, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(2.dp))
            IconButton(onClick = onAccept, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Check, contentDescription = t("Accept"),
                    tint = Color(0xFF81C784), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
internal fun GaugeStat(value: String, label: String, sublabel: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Text(t(label), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.labelSmall)
        if (sublabel.isNotBlank()) {
            Text(t(sublabel), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
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
            Text(t("This Week"), color = AppTheme.TitleColor,
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
                Text(t("migraines"), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.labelSmall)
                Text(t("vs %s last week", ws.lastWeekCount), color = AppTheme.SubtleTextColor,
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
                    Text(t("avg severity"), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.labelSmall)
                    ws.lastWeekAvgSeverity?.let { lastAvg ->
                        Text(t("vs %s last week", String.format("%.1f", lastAvg)), color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${ws.totalLogged}", color = AppTheme.AccentPurple,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                Text(t("total"), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.labelSmall)
                Text(t("all time"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
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
                Text(t("Daily Insights"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(t("Your recent personalised advice"),
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
                if (expanded) t("Show less") else t("Show all %s insights", history.size),
                color = AppTheme.AccentPurple,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp),
            )
        }
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
                Text(t("Day of Week"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(t("When your migraines hit most"),
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
                        t("%1\$ss account for %2\$s%% of your migraines \u2014 about %3\$s times an average day.", w.dayName, String.format("%.0f", w.pct), trimLift(ratio)),
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        }
    }
}



// ======= What changed (last 30 days vs the 30 before) =======

internal val TrendRed = Color(0xFFE57373)
internal val TrendGreen = Color(0xFF81C784)
internal val TrendAmber = Color(0xFFFFB74D)

/**
 * Row color for an item trend. Unwanted items (triggers, prodromes, medicines,
 * symptoms) going up read red and going down green; reliefs going up read
 * green and going down muted — fewer reliefs is not a bad sign, just quieter.
 */
internal fun trendColor(kind: String, delta: Int): Color =
    if (kind == "relief") {
        if (delta > 0) TrendGreen else AppTheme.SubtleTextColor
    } else {
        if (delta > 0) TrendRed else TrendGreen
    }

/** True when acute medication use is rising: count reached 3+ and grew 50%+ (or from zero). */
internal fun medicationRising(changed: List<InsightsViewModel.ItemTrend>): Boolean =
    changed.any {
        it.kind == "medicine" && it.current >= 3 &&
            (it.prior == 0 || it.current.toFloat() / it.prior >= 1.5f)
    }

@Composable
internal fun WhatChangedCard(changed: List<InsightsViewModel.ItemTrend>, onClick: () -> Unit) {
    val top = remember(changed) {
        changed.sortedByDescending { kotlin.math.abs(it.delta) }.take(2)
    }
    val medRising = remember(changed) { medicationRising(changed) }
    BrainyWatermarkCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        resId = R.drawable.brainy_risk,
        flipWatermark = true
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrainyBlobIcon(R.drawable.brainy_risk_small)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t("What changed"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(t("Last 30 days vs the 30 before"), color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(8.dp))
            Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(4.dp))
        top.forEachIndexed { i, tr ->
            val color = trendColor(tr.kind, tr.delta)
            if (i > 0) Spacer(Modifier.height(6.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BrainyRowIcon(tr.name, size = 18.dp)
                Text(prettyLabel(tr.name), color = Color.White,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text("${tr.prior}→${tr.current}", color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp))
                Text(if (tr.delta > 0) "↑" else "↓", color = color,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
            }
        }
        if (changed.size > top.size) {
            Text(t("+%s more", changed.size - top.size), color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.End))
        }
        if (medRising) {
            Spacer(Modifier.height(2.dp))
            Text(t("Acute medication use rising — worth an overuse check."),
                color = TrendAmber, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private val MEDICAL_NOTE: String get() = "\n\n" + tSync("MigraineMe is not a medical device and does not diagnose, treat, cure, or prevent any condition. This is not medical advice, always consult a qualified healthcare professional.")

object FullReportInfoCopy {
    val text: String get() = tSync("The big one. A single scrollable report that pulls every angle we have on your data into one place: frequency over time, top patterns and trigger interactions, which treatments and lifestyle changes are working, the activities and locations that surround your attacks, severity, pain location and aura breakdowns, missed-activity counts, plus the full migraine timeline with spider charts of what was happening around each attack.\n\nThe raw inputs are tweakable. You can edit any logged migraine, change a trigger or prodrome's severity in Manage Items, and filter the whole report by time range or tags. The AI-derived parts (Recommendations and the What Happened pattern findings) aren't directly editable; they recompute live from whatever data you've kept in.\n\nUseful for sharing with your neurologist or just getting a quarterly read of how things are trending. Everything you see in the individual cards below is in here too, but stitched together so patterns across categories become obvious.") + MEDICAL_NOTE
}

object AiRecommendationsInfoCopy {
    val text: String get() = tSync("A more useful read on top of the patterns we've already found in your data. The AI takes what's showing up (the items that turn up around your attacks, the combinations that look risky together, the treatments that seem to be helping) and adds a personal next step for each one, across six categories.\n\nWhat it looks at per category:\n• Triggers: the ones that keep showing up before attacks, and the pairs that look especially risky together. Gives you a preventive action tied to your pattern.\n• Prodromes: your personal early warning signs, with interventions that may abort or shorten an attack when they appear.\n• Medicines: rebound and overuse flags (triptans, NSAIDs, paracetamol, opioids), alternatives when something isn't relieving well, side-effect mitigation, same-class swaps, doctor-discussion prompts. All tied to your own usage.\n• Reliefs: same angles for non-drug methods (alternatives, technique tweaks, timing).\n• Activities: links your timing (exercise, sleep, stress) to context like cortisol and dehydration.\n• Symptoms: the ones running high or severe in your attacks, with targeted prep (anti-emetic on hand, dim-room kit, postdrome plan).\n\nEach recommendation comes with a short evidence line so you can see why it's being suggested. Dismiss anything that doesn't match your reality and it won't come back. These are starting points to discuss with your neurologist, not diagnoses.") + MEDICAL_NOTE
}

object AccuracyInfoCopy {
    val text: String get() = tSync("A reality check on your risk gauge. We look back at every day you've used the app and ask two questions: when the gauge said HIGH or MILD, did an attack actually happen? And when an attack happened, was the gauge warning the day before?\n\nThat gives you two numbers:\n• Hit rate: of all your attack days, what share the gauge correctly flagged as high-risk beforehand.\n• False alarm rate: of all your non-attack days, what share the gauge flagged anyway.\n\nTap in for the full breakdown (true positives, missed attacks, etc.) and to review any pending recalibration proposals. To tune the LOW / MILD / HIGH thresholds and the day-by-day decay curve yourself, open the menu and tap Risk Model. Lowering the HIGH threshold catches more attacks (higher hit rate) but also more false alarms; raising it does the opposite.\n\nThe weekly recalibration uses these same numbers to nudge your thresholds in the right direction: too many false alarms means thresholds get raised; missed attacks means they get lowered. Changes are incremental (around 20-30% per pass) so the gauge gradually homes in on what works for you.\n\nNeeds at least 7 days of logged data to start showing.") + MEDICAL_NOTE
}

object WhatHappenedInfoCopy {
    val text: String get() = tSync("The patterns we've found in your data, stacked in three layers:\n• Single factors: the triggers, prodromes, and daily metrics (sleep, weather, HRV, etc.) that show up most around your attacks.\n• Combinations: pairs of factors that look risky together even if each one isn't a big deal alone. The classic example is under-6h sleep on its own being fine, but under-6h sleep paired with a stressful day stacking into a high-risk window.\n• Symptom outcomes: given the trigger you just hit, which symptoms tend to show up.\n\nYou'll see one of two numbers on each finding:\n• \"about 2 times as likely\" — how much more often it happens on the days around your attacks compared with your normal days. We can only show this for things we can fairly compare day to day: signals tracked constantly (sleep, weather, HRV) and manual triggers you log very consistently. This is the stronger read — it means the factor genuinely makes an attack more likely.\n• \"% of your attacks\" — for things you only flag manually and not regularly, where there's no fair day-to-day comparison. It simply shows how often the factor turned up in your attacks. It tells you what your attacks have in common, not that the factor raises your risk.\n\nWhen a finding says \"flare days\", a long attack was counted day by day, so the numbers are attack days rather than attacks.\n\nThe dots show how sure we are: more data behind a finding, more dots. Findings sharpen as you log more — sparse logging means weaker findings, consistent logging means cleaner patterns.\n\nThe preview here shows the top 2 from each layer. Tap in for the full ranked list.") + MEDICAL_NOTE
}

object WhatWorkedInfoCopy {
    val text: String get() = tSync("How your attacks went when you used each medicine and relief. This is your own log read back to you, not a measure of the drug.\n\nEvery treatment you logged gets a row, and every row gets one of the same four verdicts, so you can compare them at a glance:\n• Works well — measured improvement, or you rated it high relief.\n• Some help — a smaller effect, or a mild rating.\n• No clear effect — measured and found nothing, or you rated it low or none.\n• Not enough yet — logged, but nothing to judge on.\n\nUnderneath, one line says where the verdict came from, in the unit it was measured in:\n• Pain points, when you log pain during an attack. We compare the three hours before a dose with the reading around two hours after, inside the same attack.\n• Pain points against your untreated attacks, when you don't. This one is deliberately cautious: you reach for treatment on your worse attacks, so it understates the benefit. That's why it reads \"at least\".\n• Hours, when we have enough ended-at times to compare an attack's length against your untreated attacks of the same severity.\n• Your own relief rating, in the words you picked, when there isn't enough to measure.\n\nWhere we have dose times, a second line splits your uses at your own median delay for that treatment and shows the peak pain either side. It is your habit, not a clinical window.\n\nThe dots show how much is behind the finding. One dot is a weak result honestly shown, not an error.\n\nFindings sharpen with more logging, especially relief ratings, dose times and ended-at times on each attack. \"Took something but didn't note what\" can't feed into this.") + MEDICAL_NOTE
}

object WhatsHelpingInfoCopy {
    val text: String get() = tSync("The positive side of your data: what you're doing right, tied to your migraine-free days.\n\u2022 Well done: habits and steady health signals (consistent sleep, calm stress, steady caffeine, trigger-free eating days) that show up on the days you stay migraine-free. Consistency is the point, staying near your own normal, not hitting anyone else's targets.\n\u2022 What drives it: the things that make those habits happen, an active day leading to steadier recovery, alcohol-free days leading to steadier sleep, yoga leading to calmer stress.\n\nEverything here is measured against your own data. Nothing in this card warns or judges; it only shows what is already working so you can keep doing it.\n\nFindings sharpen with more logging. If the card is quiet, it just needs more days of data, not different behaviour.") + MEDICAL_NOTE
}

object WhatChangedInfoCopy {
    val text: String get() = tSync("A straight before-and-after of what you've been logging: for every trigger, prodrome, medicine and relief, how often it appeared on the attacks of the last 30 days compared with the 30 days before that.\n\nThis is a tally, not a correlation analysis. It only counts items linked to an attack, and it only compares the two date windows — nothing is filtered or weighted. An item shows up here the moment its count moved between the two periods.\n\nColours give you the read at a glance: an unwanted item (trigger, prodrome, medicine) climbing shows red, easing off shows green. Reliefs work the other way round: using them more shows green.\n\nIf your acute medication count is climbing fast, a small note flags it. Medication-overuse headache is a real thing, and catching the trend early is exactly what this card is for.\n\nThe preview shows the four biggest movers. Tap in for the full list, matching the What changed page of the PDF report.") + MEDICAL_NOTE
}

object ContextInfoCopy {
    val text: String get() = tSync("A simple picture of the activities and locations you've tagged on your migraine logs. For each one we show how often it showed up alongside an attack (count and percentage of your attacks) and the average severity of those attacks compared to your overall average. Higher than usual gets flagged red; lower gets flagged green.\n\nThe preview shows the top 2 by frequency. Tap in for the full list.\n\nImportant: this only counts activities and locations you've actually linked to a specific migraine (via the Activities and Locations steps in the full wizard, or in the Daily Check-In). It's not a correlation analysis like What Happened; it's a straight tally of what was around when you logged each attack. If you don't tag activities or locations, nothing shows up here.") + MEDICAL_NOTE
}

object ImpactInfoCopy {
    val text: String get() = tSync("The downstream story: not what caused your attacks, but what they looked like and what you missed because of them.\n• Severity distribution: how your attacks break down across the 1-10 scale. Useful for spotting whether you mostly get moderate ones or whether you're getting a creeping number of severe ones.\n• Pain locations: which sides and zones of your head get hit most. Pulled from the Pain step in the wizard.\n• Aura location: if you log an aura, where in your field of vision it appears and how long it lasts, drawn as you see it through your own eyes. Only shows once you've logged at least one aura.\n• Top symptoms: which symptoms show up most often, with the average severity and duration of the attacks they appear in. Comes from your linked symptom logs.\n• Missed activities: the things you've explicitly tagged as missed because of an attack, ranked by how often they came up.\n\nTap in for the full breakdown.\n\nLike the other cards, this only counts data you've logged. If you don't rate severity, pick pain locations, or tag missed activities in your wizard, those sections stay empty.") + MEDICAL_NOTE
}
