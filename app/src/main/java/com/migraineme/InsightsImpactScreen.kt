package com.migraineme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController

@Composable
fun InsightsImpactScreen(
    navController: NavHostController,
    vm: InsightsViewModel = viewModel()
) {
    val impactItems by vm.impactItems.collectAsState()
    val painLocationCounts by vm.painLocationCounts.collectAsState()
    val severityCounts by vm.severityCounts.collectAsState()
    val totalMigraineCount by vm.totalMigraineCount.collectAsState()
    val migraineSpans by vm.migraines.collectAsState()
    val overallAvgSeverity = remember(migraineSpans) {
        val severities = migraineSpans.mapNotNull { it.severity }
        if (severities.isEmpty()) 5f else severities.average().toFloat()
    }

    val scrollState = rememberScrollState()

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Outlined.ArrowBack, "Back", tint = AppTheme.BodyTextColor)
                }
            }

            // ── Card 1: Severity ──
            if (severityCounts.isNotEmpty()) {
                BaseCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(24.dp)) { HubIcons.run { drawMigraineStarburst(Color(0xFFE57373)) } }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Severity", color = Color(0xFFE57373),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Text("Distribution across your migraines",
                                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val sevColor = androidx.compose.ui.graphics.lerp(
                                AppTheme.AccentPurple, Color(0xFFE57373),
                                ((overallAvgSeverity - 1f) / 9f).coerceIn(0f, 1f)
                            )
                            Text(
                                String.format("%.1f", overallAvgSeverity),
                                color = sevColor,
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontWeight = FontWeight.Bold, fontSize = 48.sp),
                            )
                            Text("avg /10", color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.labelSmall)
                        }

                        Spacer(Modifier.width(20.dp))

                        SeverityMiniChart(
                            severityCounts = severityCounts,
                            modifier = Modifier.weight(1f),
                            barHeight = 60.dp,
                        )
                    }
                }
            }

            // ── Card 2: Pain Locations ──
            if (painLocationCounts.isNotEmpty() && totalMigraineCount > 0) {
                BaseCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(24.dp)) { HubIcons.run { drawLocationPin(Color(0xFFFF8A65)) } }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Pain Locations", color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("Where your migraines hurt most",
                                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Front + Back side by side
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PainHeatMap(
                            painLocationCounts = painLocationCounts,
                            totalMigraines = totalMigraineCount,
                            points = FRONT_PAIN_POINTS,
                            imageRes = R.drawable.painpoints,
                            modifier = Modifier.weight(1f).aspectRatio(0.75f),
                        )
                        PainHeatMap(
                            painLocationCounts = painLocationCounts,
                            totalMigraines = totalMigraineCount,
                            points = BACK_PAIN_POINTS,
                            imageRes = R.drawable.painpointsback,
                            modifier = Modifier.weight(1f).aspectRatio(0.75f),
                        )
                    }

                    // Location list with percentages
                    Spacer(Modifier.height(12.dp))
                    painLocationCounts.forEach { (locId, count) ->
                        val label = ALL_PAIN_POINTS_MAP[locId] ?: locId
                        val pct = (count.toFloat() / totalMigraineCount * 100).toInt()
                        val pctColor = when {
                            pct >= 60 -> Color(0xFFE57373)
                            pct >= 30 -> Color(0xFFFFB74D)
                            else -> AppTheme.SubtleTextColor
                        }

                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFE57373).copy(alpha = (pct / 100f).coerceIn(0.2f, 0.9f)))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(label, color = Color.White,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("$pct%", color = pctColor,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }

            // ── Card 3: Missed Activities ──
            if (impactItems.isNotEmpty()) {
                BaseCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(24.dp)) { HubIcons.run { drawMissedActivity(Color(0xFFE57373)) } }
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Missed Activities", color = Color(0xFFE57373),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Text("What you couldn't do because of migraines",
                                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

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

            // Empty state
            if (painLocationCounts.isEmpty() && severityCounts.isEmpty() && impactItems.isEmpty()) {
                BaseCard {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Canvas(Modifier.size(36.dp)) { HubIcons.run { drawRipple(Color(0xFFE57373)) } }
                        Spacer(Modifier.height(8.dp))
                        Text("No impact data yet", color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.height(4.dp))
                        Text("Log pain locations, severity, and missed activities to see your impact summary.",
                            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
