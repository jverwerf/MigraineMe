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

    // Customize: section order + visibility (InsightsSectionConfig). A section
    // only counts when it has data, so the Brainy header blob lands on the first
    // visible section and the bottom watermark on the last, whatever the order.
    val sectionConfig by rememberInsightsSectionConfig(InsightsSections.PAGE_TREATMENTS)
    val presentSections = buildSet {
        if (whatWorkedRows.isNotEmpty()) add(InsightsSections.TREATMENTS_WHAT_WORKED)
        if (intradayEasers.isNotEmpty()) add(InsightsSections.TREATMENTS_PAIN_RESPONSE)
        if (treatmentInteractionCorrelations.isNotEmpty()) add(InsightsSections.TREATMENTS_USED_TOGETHER)
        if (symptomSegments.isNotEmpty()) add(InsightsSections.TREATMENTS_WORKS_BEST_WHEN)
    }
    val visibleSections = sectionConfig.orderedVisible().filter { it in presentSections }
    val firstSection = visibleSections.firstOrNull()
    val lastSection = visibleSections.lastOrNull()

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            InsightsCustomizeRow(navController, InsightsSections.PAGE_TREATMENTS)

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

            for (sectionId in visibleSections) {
                key(sectionId) {
                    when (sectionId) {
                        // ── Card 1: across your attacks ──
                        // No statistical gate: if it was logged, it is on the page, with its
                        // verdict, the line the verdict came from, its timing line where the
                        // engine measured one, and its dots.
                        InsightsSections.TREATMENTS_WHAT_WORKED -> {
                            WhatWorkedCard(
                                rows = whatWorkedRows,
                                medicineCategories = medicineCategories,
                                reliefIconKeys = reliefIconKeys,
                                watermark = sectionId == lastSection,
                                showBlob = sectionId == firstSection,
                            )
                        }
                        // ── Card 2: within the attack ──
                        // Pain response: how pain moved after these treatments, in pain
                        // points with the sign kept. A treatment after which pain ROSE
                        // renders in the warning colour, flagged, never hidden. Hidden
                        // entirely when the engine wrote no easer rows — 15 of 198 users log
                        // pain points, so card 1 has to stand alone for everyone else.
                        InsightsSections.TREATMENTS_PAIN_RESPONSE -> {
                            PainResponseCard(intradayEasers,
                                watermark = sectionId == lastSection,
                                showBlob = sectionId == firstSection)
                        }
                        // The standalone Timing card is gone: early-vs-late now sits on the
                        // treatment's own row in card 1, where it belongs, instead of being
                        // repeated as a separate list of the same treatment names.

                        // ── Card 3: pairs ──
                        InsightsSections.TREATMENTS_USED_TOGETHER -> {
                            UsedTogetherCard(treatmentInteractionCorrelations,
                                watermark = sectionId == lastSection,
                                showBlob = sectionId == firstSection)
                        }
                        // ── Card 4: symptom segments ──
                        InsightsSections.TREATMENTS_WORKS_BEST_WHEN -> {
                            WorksBestWhenCard(symptomSegments,
                                watermark = sectionId == lastSection,
                                showBlob = sectionId == firstSection)
                        }
                        else -> {}
                    }
                }
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
