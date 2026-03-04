package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun InsightsExploreScreen(
    navController: NavHostController,
    vm: InsightsViewModel = viewModel()
) {
    val migraines by vm.migraines.collectAsState()
    val linkedItems by vm.selectedLinkedItems.collectAsState()
    val linkedLoading by vm.linkedItemsLoading.collectAsState()

    val symptomSpider by vm.symptomSpider.collectAsState()
    val prodromeSpider by vm.prodromeSpider.collectAsState()
    val triggerSpider by vm.triggerSpider.collectAsState()
    val medicineSpider by vm.medicineSpider.collectAsState()
    val reliefSpider by vm.reliefSpider.collectAsState()
    val locationSpider by vm.locationSpider.collectAsState()
    val activitySpider by vm.activitySpider.collectAsState()
    val missedActivitySpider by vm.missedActivitySpider.collectAsState()
    val medEff by vm.medicineEffectiveness.collectAsState()
    val relEff by vm.reliefEffectiveness.collectAsState()

    val sorted = remember(migraines) { migraines.sortedByDescending { it.start } }
    val selIdx by vm.selectedMigraineIndex.collectAsState()
    val clampedIdx = selIdx.coerceIn(0, (sorted.size - 1).coerceAtLeast(0))
    val sel = sorted.getOrNull(clampedIdx)
    LaunchedEffect(sel?.id) { sel?.id?.let { vm.loadLinkedItems(it) } }

    val allMissed by vm.allMissedActivities.collectAsState()
    val allActs by vm.allActivities.collectAsState()
    val windowEvents = remember(linkedItems, allMissed, allActs, sel?.id) {
        val actsForMigraine = allActs.filter { it.migraineId == sel?.id }
        val missedForMigraine = allMissed.filter { it.migraineId == sel?.id }
        buildEventMarkers(linkedItems, actsForMigraine, missedForMigraine)
    }

    val scrollState = rememberScrollState()

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Outlined.ArrowBack, "Back", tint = AppTheme.BodyTextColor)
                }
            }

            // ── Compact Migraine Timeline (tappable → full timeline) ──
            BaseCard(modifier = Modifier.clickable {
                navController.navigate(Routes.INSIGHTS_TIMELINE)
            }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Migraine Timeline", color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f))
                    Text("\u2192", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
                }

                if (sorted.isEmpty()) {
                    Text("No migraines logged yet", color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall)
                } else {
                    Spacer(Modifier.height(4.dp))
                    CompactMigraineSelector(sorted, clampedIdx, sel,
                        onPrev = { if (clampedIdx < sorted.size - 1) vm.selectMigraine(clampedIdx + 1) },
                        onNext = { if (clampedIdx > 0) vm.selectMigraine(clampedIdx - 1) })

                    if (linkedLoading) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppTheme.AccentPurple)
                        }
                    } else {
                        val catCounts = remember(windowEvents) {
                            windowEvents.groupBy { it.category }.mapValues { it.value.size }
                        }
                        if (catCounts.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                catCounts.forEach { (c, n) ->
                                    CompactChip(n, c, EventCategoryColors[c] ?: AppTheme.AccentPurple)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text("Tap for full timeline with graph & filters",
                        color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                }
            }

            // ── Spider Charts ──

            // Symptoms
            if (symptomSpider != null && symptomSpider!!.totalLogged > 0) {
                SymptomsInsightCard(migraines) {
                    navController.navigate("${Routes.INSIGHTS_BREAKDOWN}/${symptomSpider!!.logType}")
                }
            } else {
                EmptyInsightCard("Symptoms", "Log migraines with symptoms to see patterns here")
            }

            // Prodromes
            if (prodromeSpider != null && prodromeSpider!!.totalLogged > 0) {
                SpiderInsightCard(prodromeSpider!!, {
                    navController.navigate("${Routes.INSIGHTS_BREAKDOWN}/${prodromeSpider!!.logType}")
                })
            } else {
                EmptyInsightCard("Prodromes", "Track early warning signs to spot them sooner")
            }

            // Triggers
            if (triggerSpider != null && triggerSpider!!.totalLogged > 0) {
                SpiderInsightCard(triggerSpider!!, {
                    navController.navigate("${Routes.INSIGHTS_BREAKDOWN}/${triggerSpider!!.logType}")
                })
            } else {
                EmptyInsightCard("Triggers", "Record what triggers your migraines to find patterns")
            }

            // Medicines
            if (medicineSpider != null && medicineSpider!!.totalLogged > 0) {
                    val mEff = if (medEff.isNotEmpty() && medicineSpider!!.axes.size >= 3) {
                        val m = medEff.associate { it.category to it.avgRelief }
                        medicineSpider!!.axes.map { SpiderAxis(it.label, m[it.label] ?: 0f, 3f) }
                    } else null
                    val color = colorForLogType(medicineSpider!!.logType)
                    BaseCard(modifier = Modifier.clickable {
                        navController.navigate("${Routes.INSIGHTS_BREAKDOWN}/${medicineSpider!!.logType}")
                    }) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Canvas(Modifier.size(24.dp)) { HubIcons.run { drawMedicinePill(color) } }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Medicines", color = AppTheme.TitleColor,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                                Text("${medicineSpider!!.totalLogged} logged \u2022 ${medicineSpider!!.breakdown.size} categories",
                                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("\u2192", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        if (medicineSpider!!.axes.size >= 3) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                SpiderChart(axes = medicineSpider!!.axes, accentColor = color, size = 220.dp,
                                    secondAxes = mEff, secondColor = Color.White.copy(alpha = 0.6f))
                            }
                        } else {
                            StackedProportionalBar(axes = medicineSpider!!.axes, accentColor = color)
                        }
                    }
                } else {
                    EmptyInsightCard("Medicines", "Log treatments to track what helps most")
                }

            // Reliefs
            if (reliefSpider != null && reliefSpider!!.totalLogged > 0) {
                    val rEff = if (relEff.isNotEmpty() && reliefSpider!!.axes.size >= 3) {
                        val m = relEff.associate { it.category to it.avgRelief }
                        reliefSpider!!.axes.map { SpiderAxis(it.label, m[it.label] ?: 0f, 3f) }
                    } else null
                    val color = colorForLogType(reliefSpider!!.logType)
                    BaseCard(modifier = Modifier.clickable {
                        navController.navigate("${Routes.INSIGHTS_BREAKDOWN}/${reliefSpider!!.logType}")
                    }) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Canvas(Modifier.size(24.dp)) { HubIcons.run { drawReliefLeaf(color) } }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Reliefs", color = AppTheme.TitleColor,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                                Text("${reliefSpider!!.totalLogged} logged \u2022 ${reliefSpider!!.breakdown.size} categories",
                                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                            }
                            Text("\u2192", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        if (reliefSpider!!.axes.size >= 3) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                SpiderChart(axes = reliefSpider!!.axes, accentColor = color, size = 220.dp,
                                    secondAxes = rEff, secondColor = Color.White.copy(alpha = 0.6f))
                            }
                        } else {
                            StackedProportionalBar(axes = reliefSpider!!.axes, accentColor = color)
                        }
                    }
                } else {
                    EmptyInsightCard("Reliefs", "Track what brings relief to find your best strategies")
                }

            // Locations
            if (locationSpider != null && locationSpider!!.totalLogged > 0) {
                SpiderInsightCard(locationSpider!!, {
                    navController.navigate("${Routes.INSIGHTS_BREAKDOWN}/${locationSpider!!.logType}")
                })
            } else {
                EmptyInsightCard("Locations", "Add locations to your migraines to spot hotspots")
            }

            // Activities
            if (activitySpider != null && activitySpider!!.totalLogged > 0) {
                SpiderInsightCard(activitySpider!!, {
                    navController.navigate("${Routes.INSIGHTS_BREAKDOWN}/${activitySpider!!.logType}")
                })
            } else {
                EmptyInsightCard("Activities", "Log activities to see how they relate to migraines")
            }

            // Missed Activities
            if (missedActivitySpider != null && missedActivitySpider!!.totalLogged > 0) {
                SpiderInsightCard(missedActivitySpider!!, {
                    navController.navigate("${Routes.INSIGHTS_BREAKDOWN}/${missedActivitySpider!!.logType}")
                })
            } else {
                EmptyInsightCard("Missed Activities", "Track skipped routines to uncover hidden patterns")
            }
        }
    }
}

// ── Compact migraine selector (no graph, just prev/next + date) ──

@Composable
private fun CompactMigraineSelector(
    sorted: List<MigraineSpan>, idx: Int, sel: MigraineSpan?,
    onPrev: () -> Unit, onNext: () -> Unit
) {
    val z = ZoneId.systemDefault()
    val df = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(z)
    val tf = DateTimeFormatter.ofPattern("h:mm a").withZone(z)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev, enabled = idx < sorted.size - 1, modifier = Modifier.size(32.dp)) {
            Text("\u2190", color = if (idx < sorted.size - 1) AppTheme.AccentPurple
            else AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                style = MaterialTheme.typography.titleMedium)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
            Text(sel?.label ?: "Migraine", color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sel != null) {
                Text(df.format(sel.start) + " \u2022 " + tf.format(sel.start),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                val e = sel.end
                if (e != null) {
                    val d = Duration.between(sel.start, e)
                    val hStr = if (d.toHours() > 0) "${d.toHours()}h " else ""
                    Text("${hStr}${d.minusHours(d.toHours()).toMinutes()}m \u2022 Severity: ${sel.severity ?: "\u2014"}/10",
                        color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text("${idx + 1} of ${sorted.size}", color = AppTheme.AccentPurple,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
        }
        IconButton(onClick = onNext, enabled = idx > 0, modifier = Modifier.size(32.dp)) {
            Text("\u2192", color = if (idx > 0) AppTheme.AccentPurple
            else AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun CompactChip(n: Int, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text("$n", color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.width(2.dp))
        Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
    }
}
