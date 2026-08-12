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

    // Treatments use self-reported relief — relax p-value filter, only require lift > 1.2
    val treatmentCorrelations = remember(correlationStats) {
        correlationStats.filter { it.factorType == "treatment" && it.liftRatio > 1.2f }
            .sortedByDescending { it.liftRatio }
    }
    val treatmentInteractionCorrelations = remember(correlationStats) {
        correlationStats.filter { it.factorType == "treatment_interaction" && it.liftRatio > 1.2f }
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

            if (treatmentCorrelations.isNotEmpty() || treatmentInteractionCorrelations.isNotEmpty()) {
                TreatmentEffectivenessCard(treatmentCorrelations, treatmentInteractionCorrelations,
                    medicineCategories = medicineCategories, reliefIconKeys = reliefIconKeys,
                    watermarkOnLast = symptomSegments.isEmpty())
            }

            // Per-treatment symptom segment comparison (Phase 2c)
            // Timing: does treating earlier lower the peak?
            if (treatmentTiming.isNotEmpty()) {
                TreatmentTimingCard(treatmentTiming)
            }

            if (symptomSegments.isNotEmpty()) {
                TreatmentSymptomSegmentCard(symptomSegments)
            }

            if (!correlationsLoading && treatmentCorrelations.isEmpty() && treatmentInteractionCorrelations.isEmpty()) {
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
private fun TreatmentSymptomSegmentCard(rows: List<EdgeFunctionsService.CorrelationStat>) {
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
    BrainyWatermarkCard(resId = R.drawable.brainy_shield, flipWatermark = true) {
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
