package com.migraineme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController

@Composable
fun InsightsThresholdsScreen(
    navController: NavHostController,
    vm: InsightsViewModel = viewModel()
) {
    val gaugeAccuracy by vm.gaugeAccuracy.collectAsState()
    val correlationsLoading by vm.correlationsLoading.collectAsState()

    val scrollState = rememberScrollState()

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Outlined.ArrowBack, "Back", tint = AppTheme.BodyTextColor)
                }
            }

            if (correlationsLoading) {
                BaseCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppTheme.AccentPurple)
                        Spacer(Modifier.width(12.dp))
                        Text("Loading…", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (gaugeAccuracy != null) {
                val ga = gaugeAccuracy!!

                // ── Gauge Accuracy card ──
                BaseCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Canvas(Modifier.size(28.dp)) {
                            HubIcons.run { drawGaugePerformance(Color(0xFF81C784)) }
                        }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Gauge Accuracy", color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("How well your risk gauge predicts migraines",
                                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Spacer(Modifier.height(14.dp))

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

                Spacer(Modifier.height(12.dp))

                // ── Confusion matrix ──
                BaseCard {
                    Text("Detailed Breakdown", color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(4.dp))
                    Text("Full confusion matrix over ${ga.totalDays} days",
                        color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)

                    Spacer(Modifier.height(14.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConfusionCell(
                            modifier = Modifier.weight(1f),
                            value = "${ga.truePositives}",
                            label = "True Positives",
                            sublabel = "Gauge warned, migraine happened",
                            color = Color(0xFF81C784),
                        )
                        ConfusionCell(
                            modifier = Modifier.weight(1f),
                            value = "${ga.falsePositives}",
                            label = "False Positives",
                            sublabel = "Gauge warned, no migraine",
                            color = Color(0xFFE57373),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ConfusionCell(
                            modifier = Modifier.weight(1f),
                            value = "${ga.falseNegatives}",
                            label = "Missed",
                            sublabel = "No warning, migraine happened",
                            color = Color(0xFFFFB74D),
                        )
                        ConfusionCell(
                            modifier = Modifier.weight(1f),
                            value = "${ga.trueNegatives}",
                            label = "True Negatives",
                            sublabel = "No warning, no migraine",
                            color = AppTheme.SubtleTextColor,
                        )
                    }
                }
            }

            // ── Empty state ──
            if (!correlationsLoading && gaugeAccuracy == null) {
                BaseCard {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Canvas(Modifier.size(36.dp)) { HubIcons.run { drawThresholdTarget(Color(0xFFFFB74D)) } }
                        Spacer(Modifier.height(8.dp))
                        Text("Not enough data yet", color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.height(4.dp))
                        Text("Keep logging migraines and using the gauge. After 7+ days of data, accuracy metrics will appear here.",
                            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfusionCell(
    modifier: Modifier = Modifier,
    value: String,
    label: String,
    sublabel: String,
    color: Color,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.06f)),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.15f)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, color = color,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(2.dp))
            Text(label, color = Color.White,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(2.dp))
            Text(sublabel, color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center)
        }
    }
}
