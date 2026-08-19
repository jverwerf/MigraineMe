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

    // Everything the user actually logged, ungated, left-joined to whatever the
    // engine measured. The hub card previews this exact list (InsightsScreen.kt
    // whatWorkedPreview), so the page has to show it or the card promises rows
    // the page then denies. Same build, same sort.
    val whatWorkedRows = remember(medicineItems, reliefItems, correlationStats, treatmentTiming) {
        buildWhatWorkedRows(
            pool = medicineItems.map { it to "medicine" } + reliefItems.map { it to "relief" },
            stats = correlationStats,
            timing = treatmentTiming,
        )
    }

    // Combination rows carry no usable multiplier any more (lift_ratio is pinned
    // to 1 on every treatment row), so they are selected by being real pairs and
    // reported as how often the pair was used, nothing more.
    val treatmentInteractionCorrelations = remember(correlationStats) {
        correlationStats.filter { it.factorType == "treatment_interaction" && it.isRealCombo }
            .sortedByDescending { it.sampleSize }
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

            val anythingShown = whatWorkedRows.isNotEmpty() || intradayEasers.isNotEmpty() ||
                symptomSegments.isNotEmpty() || treatmentInteractionCorrelations.isNotEmpty()

            // ── Card 1: across your attacks ──
            // No statistical gate: if it was logged, it is on the page, with its
            // verdict, the line the verdict came from, its timing line where the
            // engine measured one, and its dots.
            if (whatWorkedRows.isNotEmpty()) {
                WhatWorkedCard(
                    rows = whatWorkedRows,
                    medicineCategories = medicineCategories,
                    reliefIconKeys = reliefIconKeys,
                    watermark = intradayEasers.isEmpty() && symptomSegments.isEmpty() &&
                        treatmentInteractionCorrelations.isEmpty(),
                    showBlob = true,
                )
            }

            // ── Card 2: within the attack ──
            // Pain response: how pain moved after these treatments. Cautions
            // (pain consistently ROSE) render in amber. Hidden entirely when the
            // engine wrote no easer rows — 15 of 198 users log pain points, so
            // card 1 has to stand alone for everyone else.
            if (intradayEasers.isNotEmpty()) {
                IntradayResponseCard(intradayEasers, easers = true)
            }

            // The standalone Timing card is gone: early-vs-late now sits on the
            // treatment's own row in card 1, where it belongs, instead of being
            // repeated as a separate list of the same treatment names.

            if (treatmentInteractionCorrelations.isNotEmpty()) {
                TreatmentCombinationsCard(treatmentInteractionCorrelations,
                    watermark = symptomSegments.isEmpty())
            }

            if (symptomSegments.isNotEmpty()) {
                TreatmentSymptomSegmentCard(symptomSegments, watermark = true)
            }

            if (!correlationsLoading && !anythingShown) {
                BaseCard {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Canvas(Modifier.size(36.dp)) { HubIcons.run { drawShieldCheck(Color(0xFF81C784)) } }
                        Spacer(Modifier.height(8.dp))
                        Text(t("Nothing linked to an attack yet"), color = AppTheme.TitleColor,
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
    // Segment rows compare two RATING averages: pct_migraine_windows carries
    // avg_relief_with and pct_control_windows avg_relief_without. lift_ratio is
    // pinned to 1 on every treatment row now, so ranking and direction come off
    // the two averages directly — the old abs(lift - 1) sort saw a constant and
    // called everything "similar".
    fun gap(s: EdgeFunctionsService.CorrelationStat) = s.pctMigraineWindows - s.pctControlWindows
    val grouped = remember(rows) {
        rows.groupBy { it.factorName }
            .map { (name, list) -> name to list.sortedByDescending { kotlin.math.abs(gap(it)) } }
            .sortedByDescending { it.second.firstOrNull()?.let { s -> kotlin.math.abs(gap(s)) } ?: 0f }
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
                    // Half a point apart on the 0-3 relief scale is the smallest
                    // gap worth calling a difference.
                    val d = withRelief - withoutRelief
                    val direction = if (d > 0.5f) t("better") else if (d < -0.5f) t("worse") else t("similar")
                    val color = if (d > 0.5f) Color(0xFF9CCB9E) else if (d < -0.5f) Color(0xFFE8A0A0) else Color(0xFF9D8BB3)
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

// ── Treatment combinations ─────────────────────────────────────
/**
 * Pairs the user reaches for together. These rows still carry the engine's old
 * lift_ratio, computed the pre-v2 way, and it is not rendered: a combination
 * gives every treatment in an attack full credit for that attack's outcome, so
 * a multiplier off it would be the same borrowed-credit number the page just
 * stopped publishing. How often the pair was actually used is what is left that
 * is true, and dots carry the rest.
 */
@Composable
private fun TreatmentCombinationsCard(
    rows: List<EdgeFunctionsService.CorrelationStat>,
    watermark: Boolean = true,
) {
    val tileShape = RoundedCornerShape(18.dp)
    val metaColor = Color(0xFF9C8BB0)
    MaybeWatermarkCard(watermark = watermark, resId = R.drawable.brainy_shield, flipWatermark = true) {
        Column(Modifier.fillMaxWidth()) {
            Text(t("Used Together"), color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(t("Pairs you reached for in the same attack"),
                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        rows.take(6).forEach { stat ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(tileShape)
                    .background(Color.White.copy(alpha = 0.035f))
                    .border(1.dp, Color.White.copy(alpha = 0.05f), tileShape)
                    .padding(horizontal = 16.dp, vertical = 13.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrainyRowIcon(stat.factorName, size = 20.dp)
                    Text("${prettyLabel(stat.factorName)}  +  ${prettyLabel(stat.factorB)}",
                        color = Color(0xFFF3EAFB),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Text(t("Used together in %s of your attacks", stat.sampleSize),
                    color = AppTheme.BodyTextColor, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(t("confidence"), color = metaColor, style = MaterialTheme.typography.labelSmall)
                    ConfidenceDotsCount(stat.confidenceDots, Color(0xFF81C784))
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
