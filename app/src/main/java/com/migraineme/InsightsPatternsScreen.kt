package com.migraineme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController

@Composable
fun InsightsPatternsScreen(
    navController: NavHostController,
    vm: InsightsViewModel = viewModel()
) {
    val correlationStats by vm.correlationStats.collectAsState()
    val triggerIconKeys by vm.triggerIconKeys.collectAsState()
    val correlationsLoading by vm.correlationsLoading.collectAsState()
    val symptomOutcomes by vm.symptomOutcomes.collectAsState()
    val intradayResponse by vm.intradayResponse.collectAsState()
    val intradayAggravators = remember(intradayResponse) {
        intradayResponse.filter { it.eventKind == "aggravator" }
    }

    val significantCorrelations = remember(correlationStats) {
        correlationStats.filter { it.isSignificant() }
    }
    val triggerCorrelations = remember(significantCorrelations) {
        significantCorrelations.filter { it.factorType == "trigger" }.sortedByDescending { it.liftRatio }
    }
    val metricCorrelations = remember(significantCorrelations) {
        significantCorrelations.filter { it.factorType == "metric" }.sortedByDescending { it.liftRatio }
    }
    val interactionCorrelations = remember(significantCorrelations) {
        significantCorrelations.filter { it.factorType == "interaction" && it.isRealCombo }.sortedByDescending { it.liftRatio }
    }
    val allPatterns = remember(triggerCorrelations, metricCorrelations) {
        (triggerCorrelations + metricCorrelations).sortedByDescending { it.liftRatio }
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
                        Text(t("Loading patterns…"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (allPatterns.isNotEmpty() || interactionCorrelations.isNotEmpty()) {
                TopPatternsCard(triggerCorrelations, metricCorrelations, interactionCorrelations, triggerIconKeys,
                    watermarkOnLast = symptomOutcomes.isEmpty())
            }

            // Pain response: how pain moved after these events.
            // Hidden entirely when the engine wrote no aggravator rows.
            if (intradayAggravators.isNotEmpty()) {
                IntradayResponseCard(intradayAggravators, easers = false)
            }

            // Per-trigger symptom profile (Phase 2b)
            if (symptomOutcomes.isNotEmpty()) {
                TriggerSymptomProfileCard(symptomOutcomes)
            }

            if (!correlationsLoading && allPatterns.isEmpty() && interactionCorrelations.isEmpty()) {
                BaseCard {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Canvas(Modifier.size(36.dp)) { HubIcons.run { drawPatternsVenn(Color(0xFFCE93D8)) } }
                        Spacer(Modifier.height(8.dp))
                        Text(t("No patterns yet"), color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.height(4.dp))
                        Text(t("Log more migraines and your patterns will appear here."),
                            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun TriggerSymptomProfileCard(rows: List<EdgeFunctionsService.CorrelationStat>) {
    val grouped = remember(rows) {
        rows.groupBy { it.factorName }
            .map { (name, list) -> name to list.sortedByDescending { it.liftRatio } }
            .sortedByDescending { it.second.firstOrNull()?.liftRatio ?: 0f }
    }
    val tileShape = RoundedCornerShape(18.dp)
    var sortMode by remember { mutableStateOf("Strongest effect") }
    val sortedGroups = remember(grouped, sortMode) {
        when (sortMode) {
            "Most affected days" -> grouped.sortedByDescending { it.second.maxOfOrNull { s -> s.pctMigraineWindows } ?: 0f }
            "A to Z" -> grouped.sortedBy { it.first.lowercase() }
            "Newest" -> grouped.sortedByDescending { it.second.maxOfOrNull { s -> s.updatedAt } ?: "" }
            "Oldest" -> grouped.sortedBy { it.second.minOfOrNull { s -> s.updatedAt } ?: "" }
            else -> grouped
        }
    }
    BrainyWatermarkCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t("What These Triggers Do to You"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(t("The symptoms that tend to follow each trigger"),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
            SortChipMenu(sortMode,
                listOf("Strongest effect", "Most affected days", "A to Z", "Newest", "Oldest")) { sortMode = it }
        }
        Spacer(Modifier.height(6.dp))
        sortedGroups.take(6).forEach { (trigger, list) ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(tileShape)
                    .background(Color.White.copy(alpha = 0.035f))
                    .border(1.dp, Color.White.copy(alpha = 0.05f), tileShape)
                    .padding(horizontal = 16.dp, vertical = 13.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrainyRowIcon(trigger, size = 20.dp)
                    Text(trigger, color = Color(0xFFF3EAFB),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                }
                list.take(4).forEach { stat ->
                    val condPct = stat.pctMigraineWindows
                    val baselinePct = stat.pctControlWindows
                    Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        BrainyRowIcon(stat.symptomOutcome, size = 16.dp, gap = 5.dp)
                        Text(prettyLabel(stat.symptomOutcome), color = Color(0xFFDDD2EA),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            modifier = Modifier.weight(1f))
                        // The two rates, undivided. Dividing them gave a bare
                        // "2.4×" that read as an amount rather than a comparison,
                        // and the rates say the same thing without the arithmetic.
                        Text(t("%1\$s%% of days vs %2\$s%% usually", condPct.toInt(), baselinePct.toInt()),
                            color = if (condPct >= baselinePct * 2f) Color(0xFFE8A0A0) else Color(0xFFC9A9E8),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Text(t("Percentages compare days after the trigger with your ordinary days."),
            color = AppTheme.SubtleTextColor.copy(alpha = 0.75f),
            style = MaterialTheme.typography.labelSmall)
    }
}
