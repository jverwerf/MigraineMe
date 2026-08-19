package com.migraineme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController

@Composable
fun InsightsTreatmentsScreen(
    navController: NavHostController,
    vm: InsightsViewModel = viewModel()
) {
    val correlationStats by vm.correlationStats.collectAsState()
    val medicineCategories by vm.medicineCategories.collectAsState()
    val reliefIconKeys by vm.reliefIconKeys.collectAsState()
    val correlationsLoading by vm.correlationsLoading.collectAsState()
    val symptomSegments by vm.symptomSegments.collectAsState()
    val treatmentTiming by vm.treatmentTiming.collectAsState()
    val intradayResponse by vm.intradayResponse.collectAsState()
    val medicineItems by vm.medicineItems.collectAsState()
    val reliefItems by vm.reliefItems.collectAsState()
    val intradayEasers = remember(intradayResponse) {
        intradayResponse.filter { it.eventKind == "easer" }
    }

    // Everything the user actually logged, ungated. The hub card previews this
    // exact pool (InsightsScreen.kt whatWorkedPreview), so the page has to show
    // it or the card promises rows the page then denies. Same build, same sort.
    val treatmentPool = remember(medicineItems, reliefItems) {
        (medicineItems.map { it to "medicine" } + reliefItems.map { it to "relief" })
            .sortedByDescending { it.first.avgRelief }
    }

    // Treatments use self-reported relief — relax p-value filter, only require lift > 1.2
    val treatmentCorrelations = remember(correlationStats) {
        correlationStats.filter { it.factorType == "treatment" && it.liftRatio > 1.2f }
            .sortedByDescending { it.liftRatio }
    }
    val treatmentInteractionCorrelations = remember(correlationStats) {
        correlationStats.filter { it.factorType == "treatment_interaction" && it.liftRatio > 1.2f && it.isRealCombo }
            .sortedByDescending { it.liftRatio }
    }

    val scrollState = rememberScrollState()

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            if (correlationsLoading) {
                BaseCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppTheme.AccentPurple)
                        Spacer(Modifier.width(12.dp))
                        Text(t("Loading treatment data…"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // The logged-pool card always renders last when it has anything, so
            // it owns the watermark and every card above it gives it up. The
            // blob stays on whichever card is first.
            val statsCardShown = treatmentCorrelations.isNotEmpty() || treatmentInteractionCorrelations.isNotEmpty()
            val poolCardShown = treatmentPool.isNotEmpty()
            val anyStatsCardShown = statsCardShown || intradayEasers.isNotEmpty() ||
                treatmentTiming.isNotEmpty() || symptomSegments.isNotEmpty()

            if (statsCardShown) {
                TreatmentEffectivenessCard(treatmentCorrelations, treatmentInteractionCorrelations,
                    medicineCategories = medicineCategories, reliefIconKeys = reliefIconKeys,
                    watermarkOnLast = symptomSegments.isEmpty() && !poolCardShown)
            }

            // Pain response: how pain moved after these
            // treatments. Cautions (pain consistently ROSE) render in amber.
            // Hidden entirely when the engine wrote no easer rows.
            if (intradayEasers.isNotEmpty()) {
                IntradayResponseCard(intradayEasers, easers = true)
            }

            // Per-treatment symptom segment comparison (Phase 2c)
            // Timing: does treating earlier lower the peak?
            if (treatmentTiming.isNotEmpty()) {
                TreatmentTimingCard(treatmentTiming)
            }

            if (symptomSegments.isNotEmpty()) {
                TreatmentSymptomSegmentCard(symptomSegments, watermark = !poolCardShown)
            }

            // No statistical gate: if it was logged, it is on the page.
            if (poolCardShown) {
                LoggedTreatmentsCard(
                    pool = treatmentPool,
                    medicineCategories = medicineCategories,
                    reliefIconKeys = reliefIconKeys,
                    showBlob = !anyStatsCardShown,
                )
            }

            if (!correlationsLoading && !anyStatsCardShown && !poolCardShown) {
                BaseCard {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Canvas(Modifier.size(36.dp)) { HubIcons.run { drawShieldCheck(Color(0xFF81C784)) } }
                        Spacer(Modifier.height(8.dp))
                        Text(t("No treatment data yet"), color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.height(4.dp))
                        Text(t("Log medicines and reliefs with your migraines to see what works best."),
                            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun TreatmentSymptomSegmentCard(
    rows: List<EdgeFunctionsService.CorrelationStat>,
    watermark: Boolean = true,
) {
    val grouped = remember(rows) {
        rows.groupBy { it.factorName }
            .map { (name, list) -> name to list.sortedByDescending { kotlin.math.abs(it.liftRatio - 1f) } }
            .sortedByDescending { it.second.firstOrNull()?.let { kotlin.math.abs(it.liftRatio - 1f) } ?: 0f }
    }
    fun reliefLabel(v: Float): String = when {
        v < 0.5f -> "no relief"
        v < 1.5f -> "low"
        v < 2.5f -> "mild"
        else      -> "high"
    }
    val tileShape = RoundedCornerShape(18.dp)
    var sortMode by remember { mutableStateOf("Strongest effect") }
    val sortedGroups = remember(grouped, sortMode) {
        when (sortMode) {
            "A to Z" -> grouped.sortedBy { it.first.lowercase() }
            "Newest" -> grouped.sortedByDescending { it.second.maxOfOrNull { s -> s.updatedAt } ?: "" }
            "Oldest" -> grouped.sortedBy { it.second.minOfOrNull { s -> s.updatedAt } ?: "" }
            else -> grouped
        }
    }
    MaybeWatermarkCard(watermark = watermark, resId = R.drawable.brainy_shield, flipWatermark = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t("Works Best When…"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(t("How relief changes depending on which symptoms are present"),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
            SortChipMenu(sortMode, listOf("Strongest effect", "A to Z", "Newest", "Oldest")) { sortMode = it }
        }
        Spacer(Modifier.height(6.dp))
        sortedGroups.take(6).forEach { (med, list) ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(tileShape)
                    .background(Color.White.copy(alpha = 0.035f))
                    .border(1.dp, Color.White.copy(alpha = 0.05f), tileShape)
                    .padding(horizontal = 16.dp, vertical = 13.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrainyRowIcon(med, size = 20.dp)
                    Text(med, color = Color(0xFFF3EAFB),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                }
                list.take(4).forEach { stat ->
                    val withRelief = stat.pctMigraineWindows
                    val withoutRelief = stat.pctControlWindows
                    val lift = stat.liftRatio
                    val direction = if (lift > 1.1f) "better" else if (lift < 0.9f) "worse" else "similar"
                    val color = if (lift > 1.1f) Color(0xFF9CCB9E) else if (lift < 0.9f) Color(0xFFE8A0A0) else Color(0xFF9D8BB3)
                    Column(Modifier.padding(top = 7.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            BrainyRowIcon(stat.symptomSegment, size = 16.dp, gap = 5.dp)
                            Text(prettyLabel(stat.symptomSegment), color = Color(0xFFDDD2EA),
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f))
                            Text(direction, color = color,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        }
                        Text(t("With %1\$s: %2\$s · Without: %3\$s", prettyLabel(stat.symptomSegment), reliefLabel(withRelief), reliefLabel(withoutRelief)),
                            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Text(t("Relief levels come from what you logged after using the treatment."),
            color = AppTheme.SubtleTextColor.copy(alpha = 0.75f),
            style = MaterialTheme.typography.labelSmall)
    }
}


// ── Logged treatments card (ungated) ───────────────────────────
/**
 * Every medicine and relief the user has linked to an attack, with the average
 * relief they reported and how often they used it. No statistical gate: the
 * engine's correlation cards stack above this one, so statistics are an extra
 * layer rather than the entry fee for seeing your own log back.
 *
 * The rows and the legend moved here from InsightsScreen.kt, where
 * TreatmentEffectivenessContent had been written for exactly this and then
 * never called from anywhere.
 */
@Composable
private fun LoggedTreatmentsCard(
    pool: List<Pair<InsightsViewModel.TreatmentItem, String>>,
    medicineCategories: Map<String, String>,
    reliefIconKeys: Map<String, String>,
    showBlob: Boolean,
) {
    BrainyWatermarkCard(resId = R.drawable.brainy_shield, flipWatermark = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (showBlob) {
                BrainyBlobIcon(R.drawable.brainy_shield_small)
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(t("Effectiveness Ranking"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(t("Every medicine and relief you logged, ranked by the relief you reported"),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(6.dp))

        pool.forEach { (item, category) ->
            val key = item.name.lowercase()
            LoggedTreatmentRow(
                item = item,
                category = category,
                iconKey = reliefIconKeys[key],
                iconCategory = medicineCategories[key],
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ReliefLegendSwatch(Color(0xFF81C784), t("High relief"))
            ReliefLegendSwatch(Color(0xFFFFB74D), t("Some relief"))
            ReliefLegendSwatch(Color(0xFFE57373), t("Low/none"))
        }
    }
}

@Composable
private fun ReliefLegendSwatch(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LoggedTreatmentRow(
    item: InsightsViewModel.TreatmentItem,
    category: String,
    iconKey: String?,
    iconCategory: String?,
) {
    val barColor = when {
        item.avgRelief >= 2.2f -> Color(0xFF81C784)
        item.avgRelief >= 1.2f -> Color(0xFFFFB74D)
        else -> Color(0xFFE57373)
    }
    val pct = (item.avgRelief / 3f).coerceIn(0f, 1f)
    val label = when {
        item.avgRelief >= 2.5f -> t("High")
        item.avgRelief >= 1.5f -> t("Mild")
        item.avgRelief >= 0.5f -> t("Low")
        else -> t("None")
    }
    // Kind stays on the row in words: the Brainy art distinguishes most rows,
    // but a name with no art resolves to no icon at all, and then a medicine
    // and a relief would read identically.
    val kindLabel = if (category == "medicine") t("Medicine") else t("Relief")

    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrainyRowIcon(item.name, iconKey = iconKey, category = iconCategory, size = 18.dp)

        Column(Modifier.weight(1f)) {
            Text(prettyLabel(item.name), color = Color.White,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$kindLabel · ${t("%s× used", item.count)}", color = AppTheme.SubtleTextColor,
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

// ── Treatment timing card (early vs late) ──────────────────────
/**
 * Every row shown has already passed the engine's gate (>=3 attacks a side,
 * >=1.5 point gap), so this view never decides what is worth saying.
 *
 * Copy stays "in your logged attacks", never causal: people treat bad attacks
 * differently, so this is a correlation, not proof that treating early helps.
 */
@Composable
fun TreatmentTimingCard(rows: List<EdgeFunctionsService.TreatmentTimingStat>) {
    fun cutoffText(minutes: Int): String {
        if (minutes < 90) return "$minutes min"
        val hours = minutes / 60.0
        if (hours >= 10) return "${Math.round(hours)}h"
        val oneDecimal = String.format("%.1f", hours)
        return if (oneDecimal.endsWith(".0")) "${oneDecimal.dropLast(2)}h" else "${oneDecimal}h"
    }

    BaseCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                t("Timing"),
                color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
        }
        Spacer(Modifier.height(4.dp))

        rows.forEach { row ->
            val earlierIsBetter = row.earlyAvgPeak < row.lateAvgPeak
            Text(
                row.treatmentName,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TimingBucket(t("Within %s", cutoffText(row.cutoffMinutes)), row.earlyAvgPeak, row.earlyCount, earlierIsBetter, Modifier.weight(1f))
                TimingBucket(t("Later"), row.lateAvgPeak, row.lateCount, !earlierIsBetter, Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (earlierIsBetter)
                    t("In your logged attacks, taking %s sooner went with a lower peak.", row.treatmentName)
                else
                    t("In your logged attacks, the earlier doses went with a higher peak — often a sign the worst attacks get treated fastest."),
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(10.dp))
        }

        Text(
            t("Based on attacks where you set a time for the dose."),
            color = AppTheme.SubtleTextColor.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun TimingBucket(title: String, peak: Float, count: Int, highlight: Boolean, modifier: Modifier = Modifier) {
    val good = Color(0xFF81C784)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (highlight) good.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
        Text(
            String.format("%.1f", peak),
            color = if (highlight) good else Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        )
        Text(t("avg peak"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
        Text(
            (if (count == 1) t("1 attack") else t("%s attacks", count)),
            color = AppTheme.SubtleTextColor.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}
