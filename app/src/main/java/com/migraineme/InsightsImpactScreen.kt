package com.migraineme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
    val symptomStats by vm.symptomStats.collectAsState()
    val auraZoneCounts by vm.auraZoneCounts.collectAsState()
    val auraAttackCount by vm.auraAttackCount.collectAsState()
    val auraDurationStats by vm.auraDurationStats.collectAsState()
    val auraDurationBuckets by vm.auraDurationBuckets.collectAsState()
    val auraInsights by vm.auraInsights.collectAsState()
    val painMigration by vm.painMigration.collectAsState()
    val overallAvgSeverity = remember(migraineSpans) {
        val severities = migraineSpans.mapNotNull { it.severity }
        if (severities.isEmpty()) 5f else severities.average().toFloat()
    }

    val lastCard = when {
        impactItems.isNotEmpty() -> "missed"
        auraZoneCounts.isNotEmpty() && auraAttackCount > 0 -> "aura"
        painLocationCounts.isNotEmpty() && totalMigraineCount > 0 -> "pain"
        symptomStats.isNotEmpty() -> "symptoms"
        else -> "severity"
    }

    val scrollState = rememberScrollState()

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            // ── Card 1: Severity ──
            if (severityCounts.isNotEmpty()) {
                MaybeWatermarkCard(watermark = lastCard == "severity", resId = R.drawable.brainy_recover, flipWatermark = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrainyBlobIcon(R.drawable.brainy_recover_small)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Severity", color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("How hard your migraines hit, across all of them",
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

            // ── Card 1b: Your Symptoms (Phase 2a) ──
            if (symptomStats.isNotEmpty()) {
                var symptomSort by remember { mutableStateOf("Most frequent") }
                val sortedSymptoms = remember(symptomStats, symptomSort) {
                    when (symptomSort) {
                        "Most severe" -> symptomStats.sortedByDescending { it.avgSeverity ?: -1f }
                        "Longest" -> symptomStats.sortedByDescending { it.avgDurationHours ?: -1f }
                        "A to Z" -> symptomStats.sortedBy { prettyLabel(it.symptomLabel).lowercase() }
                        else -> symptomStats.sortedByDescending { it.pctOfAttacks }
                    }
                }
                val tileShape = RoundedCornerShape(18.dp)
                MaybeWatermarkCard(watermark = lastCard == "symptoms", resId = R.drawable.brainy_recover, flipWatermark = true) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Your Symptoms", color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("How often each shows up, and how bad those attacks are",
                                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        }
                        SortChipMenu(symptomSort, listOf("Most frequent", "Most severe", "Longest", "A to Z")) { symptomSort = it }
                    }
                    Spacer(Modifier.height(6.dp))
                    sortedSymptoms.take(10).forEach { s ->
                        val pct = (s.pctOfAttacks * 100f).toInt()
                        val sev = s.avgSeverity
                        val sevColor = if (sev == null) AppTheme.SubtleTextColor
                            else if (sev >= 7f) Color(0xFFE59A9A)
                            else if (sev >= 4f) Color(0xFFD9B27C)
                            else Color(0xFF9CCB9E)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(tileShape)
                                .background(Color.White.copy(alpha = 0.035f))
                                .border(1.dp, Color.White.copy(alpha = 0.05f), tileShape)
                                .padding(horizontal = 16.dp, vertical = 13.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                BrainyRowIcon(s.symptomLabel, size = 20.dp)
                                Text(prettyLabel(s.symptomLabel), color = Color(0xFFF3EAFB),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    overflow = TextOverflow.Ellipsis, maxLines = 1, modifier = Modifier.weight(1f))
                                Text("$pct% · ${s.totalCount} attacks",
                                    color = Color(0xFFCE93D8),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                if (sev != null) {
                                    Text("avg severity ${String.format("%.1f", sev)}", color = sevColor,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                                }
                                val dur = s.avgDurationHours
                                if (dur != null) {
                                    if (sev != null) Text("\u00b7", color = Color(0xFF9C8BB0), style = MaterialTheme.typography.labelSmall)
                                    Text("avg duration ~${String.format("%.0f", dur)}h", color = Color(0xFF9C8BB0),
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            // ── Card 2: Pain Locations ──
            if (painLocationCounts.isNotEmpty() && totalMigraineCount > 0) {
                MaybeWatermarkCard(watermark = lastCard == "pain", resId = R.drawable.brainy_recover, flipWatermark = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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

            // ── Card 2a: How the pain moves — needs timelined attacks ──
            painMigration?.let { pm -> PainMigrationCard(pm) }

            // ── Card 2b: Aura Location ──
            if (auraZoneCounts.isNotEmpty() && auraAttackCount > 0) {
                // watermark lives on the page's last card; that's this one when
                // there are no missed activities below
                MaybeWatermarkCard(watermark = lastCard == "aura", resId = R.drawable.brainy_recover, flipWatermark = true) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Aura Location", color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("Where in your vision it shows up, as seen through your own eyes",
                                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    AuraHeatMap(
                        auraZoneCounts = auraZoneCounts,
                        totalAuraAttacks = auraAttackCount,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(12.dp))
                    auraZoneCounts.forEach { (zoneId, count) ->
                        val pct = (count.toFloat() / auraAttackCount * 100).toInt()
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
                                    .background(AppTheme.AccentPurple.copy(alpha = (pct / 100f).coerceIn(0.2f, 0.9f)))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(AuraZones.label(zoneId), color = Color.White,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("$pct%", color = pctColor,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                        }
                    }

                    auraDurationStats?.let { (avgMin, count) ->
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Average aura duration", color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text(
                                "${formatAuraDuration(avgMin)} · $count timed",
                                color = AppTheme.AccentPurple,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }

                    // Server-computed findings — the patterns, not just the counts.
                    if (auraInsights.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("What we've spotted", color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.height(6.dp))
                        val insightTileShape = RoundedCornerShape(18.dp)
                        auraInsights.forEach { ins ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(insightTileShape)
                                    .background(Color.White.copy(alpha = 0.035f))
                                    .border(1.dp, Color.White.copy(alpha = 0.05f), insightTileShape)
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Text(ins.headline, color = Color(0xFFF3EAFB),
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
                                ins.detail?.let {
                                    Spacer(Modifier.height(3.dp))
                                    Text(it, color = Color(0xFF9C8BB0),
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }

                    // The spread, because an average hides the outlier that
                    // actually matters clinically (a single 90-minute aura).
                    if (auraDurationBuckets.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("How long they last", color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.height(6.dp))
                        val maxBucket = auraDurationBuckets.maxOf { it.second }
                        auraDurationBuckets.forEach { (label, count) ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label, color = AppTheme.BodyTextColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.width(64.dp))
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(Color.White.copy(alpha = 0.06f))
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(count.toFloat() / maxBucket.coerceAtLeast(1))
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(AppTheme.AccentPurple.copy(alpha = 0.65f))
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text("$count", color = AppTheme.SubtleTextColor,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // ── Card 3: Missed Activities ──
            if (impactItems.isNotEmpty()) {
                var missedSort by remember { mutableStateOf("Most missed") }
                val sortedMissed = remember(impactItems, missedSort) {
                    when (missedSort) {
                        "A to Z" -> impactItems.sortedBy { prettyLabel(it.name).lowercase() }
                        else -> impactItems.sortedByDescending { it.totalMissed }
                    }
                }
                val tileShape = RoundedCornerShape(18.dp)
                BrainyWatermarkCard(resId = R.drawable.brainy_recover, flipWatermark = true) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Missed Activities", color = AppTheme.TitleColor,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text("What you couldn't do because of migraines",
                                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        }
                        SortChipMenu(missedSort, listOf("Most missed", "A to Z")) { missedSort = it }
                    }

                    Spacer(Modifier.height(6.dp))

                    sortedMissed.forEach { item ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(tileShape)
                                .background(Color.White.copy(alpha = 0.035f))
                                .border(1.dp, Color.White.copy(alpha = 0.05f), tileShape)
                                .padding(horizontal = 16.dp, vertical = 13.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                BrainyRowIcon(item.name, size = 20.dp)
                                Text(prettyLabel(item.name), color = Color(0xFFF3EAFB),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text("${item.totalMissed}\u00D7 missed", color = Color(0xFFE8A0A0),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("missed during ${item.pctOfMigraines.toInt()}% of migraines",
                                color = Color(0xFF9C8BB0), style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Text("Counts are the total times you logged the activity as missed.",
                        color = AppTheme.SubtleTextColor.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelSmall)
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



// ── Pain migration card (where it starts, where it spreads) ────
/**
 * Renders only what the engine already decided was a real pattern: a location
 * appears here because it recurred in a MAJORITY of timelined attacks, and the
 * row only exists when >= 3 such attacks agreed.
 *
 * Static only — no animation anywhere in this app.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PainMigrationCard(stat: EdgeFunctionsService.PainMigrationStat) {
    fun peakText(mins: Int): String {
        if (mins < 90) return "$mins minutes"
        val hours = mins / 60.0
        if (hours >= 10) return "${Math.round(hours)} hours"
        val oneDecimal = String.format("%.1f", hours)
        return if (oneDecimal.endsWith(".0")) "${oneDecimal.dropLast(2)} hours" else "$oneDecimal hours"
    }

    BaseCard {
        Text(
            "How your pain moves",
            color = AppTheme.TitleColor,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
        )
        Text(
            "Across ${stat.attacksAnalysed} attacks where you logged more than one pain entry",
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))

        // Staged on the body rather than as a list of names: where it starts
        // in one colour, where it spreads in another. Same treatment the PDF
        // prints, so the screen and the report say the same thing.
        val onset = stat.onsetLocations
        val spread = stat.spreadLocations
        if (onset.isNotEmpty() || spread.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PainStageMap(onset, spread, Modifier.weight(1f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    if (onset.isNotEmpty()) {
                        PainStageLegend("1 · Usually starts", onset, AppTheme.AccentPink.copy(alpha = 0.75f))
                        Spacer(Modifier.height(8.dp))
                    }
                    if (spread.isNotEmpty()) {
                        PainStageLegend("2 · Then spreads to", spread, AppTheme.AccentPink)
                    }
                }
            }
        }

        stat.medianMinutesToPeak?.let { mins ->
            Spacer(Modifier.height(10.dp))
            Text(
                "Typically peaks about ${peakText(mins)} in",
                color = AppTheme.BodyTextColor,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PainStageRow(title: String, locations: List<String>, color: Color) {
    Text(title, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
    Spacer(Modifier.height(4.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        locations.forEach { loc ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(color.copy(alpha = 0.20f))
                    .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    ALL_PAIN_POINTS_MAP[loc] ?: loc,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                )
            }
        }
    }
}


/** Front head with the two stages pinned on it, numbered and colour-coded. */
@Composable
private fun PainStageMap(onset: List<String>, spread: List<String>, modifier: Modifier = Modifier) {
    Box(modifier) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.painpoints),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
        )
        val stages = onset.map { it to (AppTheme.AccentPink.copy(alpha = 0.4f) to "1") } +
            spread.map { it to (AppTheme.AccentPink.copy(alpha = 0.95f) to "2") }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.matchParentSize()) {
            val w = maxWidth
            val h = maxHeight
            stages.forEach { (id, style) ->
                val point = FRONT_PAIN_POINTS.firstOrNull { it.id == id } ?: return@forEach
                val (color, label) = style
                Box(
                    Modifier
                        .offset(x = w * point.xPct - 9.dp, y = h * point.yPct - 9.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(1.dp, AppTheme.AccentPink, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = Color.White,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PainStageLegend(title: String, locations: List<String>, color: Color) {
    Text(title, color = color, style = MaterialTheme.typography.labelSmall)
    Spacer(Modifier.height(4.dp))
    Text(locations.joinToString(", ") { ALL_PAIN_POINTS_MAP[it] ?: it },
        color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
}
