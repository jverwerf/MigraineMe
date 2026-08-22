package com.migraineme

// What's Helping — Well Done positive layer
// (docs/well-done-layer-spec.md in the migraineme-ios repo).
// Two sections: consistent habits present on migraine-free days (direct
// rows, factor_type well_done) and the improvers that make them steady
// (chains, factor_type well_done_chain, factorB = target). Positive
// framing only: leads with "% of your migraine-free days", never a
// multiplier, no warnings, no risk language.

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController

private val WD_GREEN = Color(0xFF81C784)

@Composable
fun InsightsWhatsHelpingScreen(
    navController: NavHostController,
    vm: InsightsViewModel = viewModel()
) {
    val correlationStats by vm.correlationStats.collectAsState()
    val correlationsLoading by vm.correlationsLoading.collectAsState()

    val wellDone = remember(correlationStats) {
        correlationStats.filter { it.factorType == "well_done" }
            .sortedByDescending { it.liftRatio }
    }
    val chains = remember(correlationStats) {
        correlationStats.filter { it.factorType == "well_done_chain" }
            .sortedByDescending { it.liftRatio }
    }

    val scrollState = rememberScrollState()

    // Customize: section order + visibility (InsightsSectionConfig). A section
    // only counts when it has data, so the Brainy header blob lands on the first
    // visible section and the bottom watermark on the last, whatever the order.
    val sectionConfig by rememberInsightsSectionConfig(InsightsSections.PAGE_HELPING)
    val presentSections = buildSet {
        if (wellDone.isNotEmpty()) add(InsightsSections.HELPING_DIRECT)
        if (chains.isNotEmpty()) add(InsightsSections.HELPING_HABITS_WHY)
    }
    val visibleSections = sectionConfig.orderedVisible().filter { it in presentSections }
    val firstSection = visibleSections.firstOrNull()
    val lastSection = visibleSections.lastOrNull()

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            InsightsCustomizeRow(navController, InsightsSections.PAGE_HELPING)

            if (correlationsLoading) {
                BaseCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppTheme.AccentPurple)
                        Spacer(Modifier.width(12.dp))
                        Text(t("Loading…"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            for (sectionId in visibleSections) {
                key(sectionId) {
                    when (sectionId) {
                        InsightsSections.HELPING_DIRECT -> {
                            MaybeWatermarkCard(watermark = sectionId == lastSection, resId = R.drawable.brainy_gardener, flipWatermark = true) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (sectionId == firstSection) {
                                        BrainyBlobIcon(R.drawable.brainy_gardener_small)
                                        Spacer(Modifier.width(10.dp))
                                    }
                                    Column {
                                        Text(t("Well Done"), color = AppTheme.TitleColor,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                                        Text(t("Habits that show up on your migraine-free days"),
                                            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                wellDone.take(10).forEach { stat -> WellDoneDirectRow(stat) }
                            }
                        }
                        InsightsSections.HELPING_HABITS_WHY -> {
                            MaybeWatermarkCard(watermark = sectionId == lastSection, resId = R.drawable.brainy_gardener, flipWatermark = true) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (sectionId == firstSection) {
                                        BrainyBlobIcon(R.drawable.brainy_gardener_small)
                                        Spacer(Modifier.width(10.dp))
                                    }
                                    Column {
                                        Text(t("What Drives It"), color = AppTheme.TitleColor,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                                        Text(t("What makes those habits happen"),
                                            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                chains.take(10).forEach { stat -> WellDoneChainRow(stat) }
                            }
                        }
                        else -> {}
                    }
                }
            }

            if (!correlationsLoading && wellDone.isEmpty() && chains.isEmpty()) {
                BaseCard {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Canvas(Modifier.size(36.dp)) { HubIcons.run { drawShieldCheck(WD_GREEN) } }
                        Spacer(Modifier.height(8.dp))
                        Text(t("Nothing to show yet"), color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.height(4.dp))
                        Text(t("Keep logging — as your sleep, stress and habit data builds up, the things you're doing right show up here."),
                            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfidenceDotsGreen(pValue: Float?) {
    // p is nullable since the v2 treatment schema. Every row still shows dots:
    // one lit dot is the honest floor, an empty row reads as broken.
    val p = pValue ?: 1f
    val filled = when {
        p < 0.01f -> 3
        p < 0.05f -> 2
        else -> 1
    }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            Box(
                Modifier.size(5.dp).clip(CircleShape)
                    .background(if (i < filled) WD_GREEN else Color.White.copy(alpha = 0.18f))
            )
        }
    }
}

@Composable
internal fun WellDoneDirectRow(stat: EdgeFunctionsService.CorrelationStat) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BrainyRowIcon(stat.factorName)
            Text(prettyLabel(stat.factorName), color = Color.White,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            ConfidenceDotsGreen(stat.pValue)
        }
        Text(t("On %s%% of your migraine-free days.", stat.pctControlWindows.toInt()),
            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun WellDoneChainRow(stat: EdgeFunctionsService.CorrelationStat) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        // A chain is two things with two icons. Built as one wrapping inline
        // string so long labels flow onto a second line instead of truncating.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            val resA = brainyForLogKey(null, stat.factorName)
            val resB = stat.factorB?.let { brainyForLogKey(null, it) }
            val title = androidx.compose.ui.text.buildAnnotatedString {
                if (resA != null) { appendInlineContent("a", "\u2b1c"); append(" ") }
                append(prettyLabel(stat.factorName))
                append("  \u2192  ")
                if (resB != null) { appendInlineContent("b", "\u2b1c"); append(" ") }
                append(tSync("steady %s", prettyLabel(stat.factorB ?: "").lowercase()))
            }
            val inline = buildMap {
                resA?.let { res ->
                    put("a", androidx.compose.foundation.text.InlineTextContent(
                        androidx.compose.ui.text.Placeholder(24.sp, 22.sp,
                            androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter)
                    ) { InlineBlobIcon(res) })
                }
                resB?.let { res ->
                    put("b", androidx.compose.foundation.text.InlineTextContent(
                        androidx.compose.ui.text.Placeholder(24.sp, 22.sp,
                            androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter)
                    ) { InlineBlobIcon(res) })
                }
            }
            Text(title, inlineContent = inline, color = Color.White,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            ConfidenceDotsGreen(stat.pValue)
        }
        Text(t("Steady %1\$s%% of the time with it, %2\$s%% without.", stat.pctControlWindows.toInt(), stat.pctMigraineWindows.toInt()),
            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
    }
}
