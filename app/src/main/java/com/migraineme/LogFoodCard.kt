package com.migraineme

// Food on the Log tab.
//
// Food could only ever be entered from Monitor -> Diet, so people looking for
// it under Log found Migraine, Prodrome, Trigger, Medicine, Relief and Activity
// and concluded the app could not do it (Stephanie Konkol, 2026-08-21). This
// card is the missing door, not a second food logger: tapping it opens the same
// MONITOR_NUTRITION screen, so a food added from Log is identical to one added
// from Monitor and there is nothing to keep in sync.
//
// It carries today's three pinned metrics for the same reason the Monitor Diet
// card does, and reads them the same way (MetricDisplayStore -> metricTotal), so
// the two surfaces can never disagree.

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LogFoodCard(navController: NavController) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<NutritionLogItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // Same three the user pinned for the Monitor Diet card — read once, since
    // the picker lives on Monitor and cannot change while this screen is up.
    val displayMetrics = remember {
        MetricDisplayStore.getDisplayMetrics(ctx, "nutrition")
            .map { MetricRegistry.nutritionLegacyKey(it) }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            items = runCatching { USDAFoodSearchService(ctx).getTodayNutritionItems() }
                .getOrDefault(emptyList())
        }
        loading = false
    }

    BaseCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { navController.navigate(Routes.MONITOR_NUTRITION) }
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BrainyBlobIcon(R.drawable.brainy_diet_small)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    t("Food"),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    when {
                        loading -> t("Loading…")
                        items.isEmpty() -> t("Search or scan what you ate")
                        else -> t("%s logged today · tap to add", items.size)
                    },
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
        }

        // Numbers only once something is logged. Three zeroes on an empty day
        // read as a broken card rather than an empty one.
        if (!loading && items.isNotEmpty() && displayMetrics.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                displayMetrics.forEachIndexed { index, metric ->
                    // Formatting is lifted verbatim from the Monitor Diet card
                    // (MonitorScreen.kt) — exposures print their severity word,
                    // nutrients their number and unit. If these two ever drift
                    // the same day reads differently on two screens.
                    val total = items.metricTotal(metric)
                    val registryKey = MetricRegistry.nutritionRegistryKey(metric)
                    val label = MetricRegistry.label(registryKey)
                    val unit = MetricRegistry.unit(registryKey)
                    val formatted = if (ExposureScale.isExposureMetric(metric)) {
                        RiskColors.formatRiskLevel(metric, total.toInt()).first
                    } else if (total >= 10) "${total.toInt()}$unit" else String.format("%.1f$unit", total)

                    MetricTile(
                        formatted,
                        label,
                        slotColors.getOrElse(index) { slotColors.last() },
                        Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
