package com.migraineme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MentalConfigScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var selectedMetrics by remember {
        mutableStateOf(MetricDisplayStore.getDisplayMetrics(context, "mental").toSet())
    }

    // Which metrics are enabled in data settings
    var enabledRegistryKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var settingsLoaded by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            val edge = EdgeFunctionsService()
                            val settings = edge.getMetricSettings(context)
                            enabledRegistryKeys = MetricRegistry.enabledKeys(settings, "mental")
                        } catch (_: Exception) {
                            enabledRegistryKeys = MetricRegistry.byGroup("mental").map { it.key }.toSet()
                        }
                        settingsLoaded = true
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Only show enabled metrics from registry. The registry exposes a single
    // bare "Noise" entry (day_mean_lmean) — replace it with explicit Avg / High
    // / Low variants so the Customize chips match what the Cognitive card
    // actually shows.
    val availableMetrics: List<Pair<String, String>> = remember(settingsLoaded, enabledRegistryKeys) {
        val base = MetricRegistry.byGroup("mental")
            .filter { it.key in enabledRegistryKeys }
            .filterNot { it.table == "ambient_noise_index_daily" }
            .map { it.key to it.label }
        val noiseEnabled = enabledRegistryKeys.any { it.startsWith("ambient_noise_index_daily") }
        val noiseChips = if (noiseEnabled) listOf(
            "ambient_noise_index_daily::day_mean_lmean" to "Noise Avg",
            "ambient_noise_index_daily::day_max_lmax" to "Noise High",
            "ambient_noise_index_daily::day_min_lmean" to "Noise Low",
        ) else emptyList()
        base + noiseChips
    }

    fun saveConfig() {
        MetricDisplayStore.setDisplayMetrics(context, "mental", selectedMetrics.toList())
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {
            HeroCard {
                Text(
                    text = "Customize Cognitive",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Choose which cognitive metrics to display on the Monitor screen.",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (settingsLoaded && availableMetrics.isEmpty()) {
                BaseCard {
                    Text(
                        text = "No cognitive source enabled",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Enable cognitive tracking in Data Settings to customize metrics.",
                        color = AppTheme.SubtleTextColor.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            } else {
                BaseCard {
                    Text(
                        text = "Display Metrics (${selectedMetrics.size}/3)",
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Select up to 3 metrics to show on the Monitor card.",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))
                        for ((metricKey, metricLabel) in availableMetrics) {
                            val isSelected = metricKey in selectedMetrics
                            val label = metricLabel
                            val slotIndex = if (isSelected) selectedMetrics.toList().indexOf(metricKey) else -1
                            val slotColor = if (slotIndex in slotColors.indices) slotColors[slotIndex] else AppTheme.AccentPurple

                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedMetrics = if (isSelected) {
                                        selectedMetrics.minus(metricKey)
                                    } else if (selectedMetrics.size < 3) {
                                        selectedMetrics.plus(metricKey)
                                    } else {
                                        selectedMetrics
                                    }
                                    saveConfig()
                                },
                                enabled = true,
                                label = {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                leadingIcon = if (isSelected) {
                                    {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                } else null,
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.PhoneAndroid,
                                        contentDescription = "Phone",
                                        modifier = Modifier.size(14.dp),
                                        tint = if (isSelected) slotColor else AppTheme.SubtleTextColor
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = slotColor.copy(alpha = 0.3f),
                                    selectedLabelColor = AppTheme.TitleColor,
                                    selectedLeadingIconColor = slotColor,
                                    containerColor = AppTheme.BaseCardContainer,
                                    labelColor = AppTheme.BodyTextColor
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = AppTheme.SubtleTextColor.copy(alpha = 0.5f),
                                    selectedBorderColor = slotColor,
                                    enabled = true,
                                    selected = isSelected
                                )
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(14.dp), tint = AppTheme.SubtleTextColor)
                        Text(text = "Phone", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
