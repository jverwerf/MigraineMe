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

    // Customize: section order + visibility (InsightsSectionConfig). The Brainy
    // header blob lands on the first visible section and the bottom watermark
    // on the last, whatever the order.
    val sectionConfig by rememberInsightsSectionConfig(InsightsSections.PAGE_ACCURACY)
    val presentSections = if (gaugeAccuracy != null)
        setOf(InsightsSections.ACCURACY_GAUGE, InsightsSections.ACCURACY_MATRIX) else emptySet()
    val visibleSections = sectionConfig.orderedVisible().filter { it in presentSections }
    val firstSection = visibleSections.firstOrNull()
    val lastSection = visibleSections.lastOrNull()

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            InsightsCustomizeRow(navController, InsightsSections.PAGE_ACCURACY)

            if (correlationsLoading) {
                BaseCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppTheme.AccentPurple)
                        Spacer(Modifier.width(12.dp))
                        Text(t("Loading…"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (gaugeAccuracy != null) {
                val ga = gaugeAccuracy!!

                for (sectionId in visibleSections) {
                key(sectionId) {
                when (sectionId) {

                // ── Gauge Accuracy card ──
                InsightsSections.ACCURACY_GAUGE -> {
                MaybeWatermarkCard(watermark = sectionId == lastSection, resId = R.drawable.brainy_archer, flipWatermark = true) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (sectionId == firstSection) {
                            BrainyBlobIcon(R.drawable.brainy_archer_small)
                            Spacer(Modifier.width(10.dp))
                        }
                        Column {
                            Text(t("Gauge Accuracy"), color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text(t("How well your risk gauge predicts migraines"),
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
                                Text(t("Caught — migraines that followed a warning"), color = AppTheme.SubtleTextColor,
                                    textAlign = TextAlign.Center,
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
                                Text(t("False alarms — warnings with no migraine"), color = AppTheme.SubtleTextColor,
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium))
                            }
                        }
                    }
                }

                if (sectionId != lastSection) Spacer(Modifier.height(12.dp))
                } // end gauge

                // ── Confusion matrix ──
                InsightsSections.ACCURACY_MATRIX -> {
                MaybeWatermarkCard(watermark = sectionId == lastSection, resId = R.drawable.brainy_archer, flipWatermark = true) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (sectionId == firstSection) {
                            BrainyBlobIcon(R.drawable.brainy_archer_small)
                            Spacer(Modifier.width(10.dp))
                        }
                        Column {
                            Text(t("Detailed Breakdown"), color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                            Spacer(Modifier.height(4.dp))
                            Text(t("Every tracked day, sorted by warning vs outcome"),
                                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Spacer(Modifier.width(64.dp))
                        MatrixAxisLabel(t("Migraine\nhappened"), Modifier.weight(1f))
                        MatrixAxisLabel(t("No\nmigraine"), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        MatrixAxisLabel(t("Gauge\nwarned"), Modifier.width(64.dp))
                        ConfusionCell(
                            modifier = Modifier.weight(1f),
                            value = "${ga.truePositives}",
                            label = t("Caught"),
                            sublabel = t("Warned & it happened"),
                            color = Color(0xFF81C784),
                        )
                        ConfusionCell(
                            modifier = Modifier.weight(1f),
                            value = "${ga.falsePositives}",
                            label = t("False alarm"),
                            sublabel = t("Warned, nothing came"),
                            color = Color(0xFFE57373),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        MatrixAxisLabel(t("No\nwarning"), Modifier.width(64.dp))
                        ConfusionCell(
                            modifier = Modifier.weight(1f),
                            value = "${ga.falseNegatives}",
                            label = t("Missed"),
                            sublabel = t("It happened unwarned"),
                            color = Color(0xFFFFB74D),
                        )
                        ConfusionCell(
                            modifier = Modifier.weight(1f),
                            value = "${ga.trueNegatives}",
                            label = t("Quiet days"),
                            sublabel = t("No warning, no migraine"),
                            color = AppTheme.SubtleTextColor,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        t("How to read it: green and grey are the gauge being right; amber and red are the two ways it can be wrong. Together the four cells cover all %s tracked days.", ga.totalDays),
                        color = AppTheme.SubtleTextColor.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (sectionId != lastSection) Spacer(Modifier.height(12.dp))
                } // end matrix

                else -> {}
                } // end when
                } // end key
                } // end for
            }

            // ── Empty state ──
            if (!correlationsLoading && gaugeAccuracy == null) {
                BaseCard {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Canvas(Modifier.size(36.dp)) { HubIcons.run { drawThresholdTarget(Color(0xFFFFB74D)) } }
                        Spacer(Modifier.height(8.dp))
                        Text(t("Not enough data yet"), color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.height(4.dp))
                        Text(t("Keep logging migraines and using the gauge. After 7+ days of data, accuracy metrics will appear here."),
                            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun MatrixAxisLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        color = Color(0xFFCE93D8),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
    )
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
            Text(t(label), color = Color.White,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(2.dp))
            Text(sublabel, color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center)
        }
    }
}
