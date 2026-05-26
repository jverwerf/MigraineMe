package com.migraineme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val correlationsLoading by vm.correlationsLoading.collectAsState()
    val symptomSegments by vm.symptomSegments.collectAsState()

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
                        Text("Loading treatment data…", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (treatmentCorrelations.isNotEmpty() || treatmentInteractionCorrelations.isNotEmpty()) {
                TreatmentEffectivenessCard(treatmentCorrelations, treatmentInteractionCorrelations)
            }

            // Per-treatment symptom segment comparison (Phase 2c)
            if (symptomSegments.isNotEmpty()) {
                TreatmentSymptomSegmentCard(symptomSegments)
            }

            if (!correlationsLoading && treatmentCorrelations.isEmpty() && treatmentInteractionCorrelations.isEmpty()) {
                BaseCard {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Canvas(Modifier.size(36.dp)) { HubIcons.run { drawShieldCheck(Color(0xFF81C784)) } }
                        Spacer(Modifier.height(8.dp))
                        Text("No treatment data yet", color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.height(4.dp))
                        Text("Log medicines and reliefs with your migraines to see what works best.",
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
    BaseCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(24.dp)) { HubIcons.run { drawShieldCheck(Color(0xFF4FC3F7)) } }
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Works Best When…", color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text("How relief changes depending on which symptoms are present",
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(8.dp))
        grouped.take(6).forEach { (med, list) ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(med, color = Color.White,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                list.take(4).forEach { stat ->
                    val withRelief = stat.pctMigraineWindows
                    val withoutRelief = stat.pctControlWindows
                    val lift = stat.liftRatio
                    val direction = if (lift > 1.1f) "better" else if (lift < 0.9f) "worse" else "similar"
                    val color = if (lift > 1.1f) Color(0xFF81C784) else if (lift < 0.9f) Color(0xFFE57373) else Color(0xFFCFCFCF)
                    Column(Modifier.padding(top = 2.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(prettyLabel(stat.symptomSegment), color = Color.White,
                                style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f))
                            Text(direction, color = color,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        }
                        Text("With ${prettyLabel(stat.symptomSegment)}: ${reliefLabel(withRelief)} · Without: ${reliefLabel(withoutRelief)}",
                            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
