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
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private fun sourceTypeForNutrition(preferredSource: String?): String? = when (preferredSource) {
    "phone" -> "phone"
    "health_connect" -> "external"
    null -> null
    else -> "phone"
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NutritionConfigScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var config by remember { mutableStateOf(MonitorCardConfigStore.load(context)) }

    var metricSettings by remember {
        mutableStateOf<Map<String, EdgeFunctionsService.MetricSettingResponse>>(emptyMap())
    }
    var settingsLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                val edge = EdgeFunctionsService()
                val settings = edge.getMetricSettings(context)
                metricSettings = settings.associateBy { it.metric }
            } catch (_: Exception) { }
            settingsLoaded = true
        }
    }

    // Nutrition is a single metric in metric_settings
    val nutritionSetting = metricSettings["nutrition"]
    val nutritionSource = sourceTypeForNutrition(nutritionSetting?.preferredSource)
    // Only hide all metrics if nutrition is explicitly disabled
    val hideNutrition = settingsLoaded && nutritionSetting != null && !nutritionSetting.enabled

    fun updateConfig(newConfig: MonitorCardConfig) {
        config = newConfig
        MonitorCardConfigStore.save(context, newConfig)
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            }

            HeroCard {
                Text(
                    text = "Customize Nutrition Display",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Choose which 3 metrics appear on the Monitor screen",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (hideNutrition) {
                BaseCard {
                    Text(
                        text = "No nutrition source enabled",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Enable nutrition tracking in Data Settings to customize metrics.",
                        color = AppTheme.SubtleTextColor.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            } else {
                BaseCard {
                    Text(
                        text = "Select 3 Metrics",
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )

                    Spacer(Modifier.height(12.dp))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))
                        for (metric in MonitorCardConfig.ALL_NUTRITION_METRICS) {
                            val isSelected = metric in config.nutritionDisplayMetrics
                            val canSelect = isSelected || config.nutritionDisplayMetrics.size < 3
                            val slotIndex = if (isSelected) config.nutritionDisplayMetrics.indexOf(metric) else -1
                            val slotColor = if (slotIndex in slotColors.indices) slotColors[slotIndex] else AppTheme.AccentPurple

                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    if (canSelect || isSelected) {
                                        updateConfig(config.toggleNutritionMetric(metric))
                                    }
                                },
                                label = {
                                    Text(
                                        text = MonitorCardConfig.NUTRITION_METRIC_LABELS[metric] ?: metric,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                },
                                leadingIcon = if (isSelected) {
                                    {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else null,
                                trailingIcon = when (nutritionSource) {
                                    "external" -> {
                                        {
                                            Icon(
                                                Icons.Default.Watch,
                                                contentDescription = "External",
                                                modifier = Modifier.size(14.dp),
                                                tint = if (isSelected) slotColor else AppTheme.SubtleTextColor
                                            )
                                        }
                                    }
                                    else -> {
                                        {
                                            Icon(
                                                Icons.Default.PhoneAndroid,
                                                contentDescription = "Phone",
                                                modifier = Modifier.size(14.dp),
                                                tint = if (isSelected) slotColor else AppTheme.SubtleTextColor
                                            )
                                        }
                                    }
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = slotColor.copy(alpha = 0.3f),
                                    selectedLabelColor = AppTheme.TitleColor,
                                    selectedLeadingIconColor = slotColor,
                                    containerColor = Color.Transparent,
                                    labelColor = AppTheme.BodyTextColor
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = AppTheme.SubtleTextColor.copy(alpha = 0.5f),
                                    selectedBorderColor = slotColor,
                                    enabled = true,
                                    selected = isSelected
                                ),
                                enabled = true
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = "${config.nutritionDisplayMetrics.size}/3 selected",
                        color = if (config.nutritionDisplayMetrics.size == 3)
                            AppTheme.AccentPurple
                        else
                            AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall
                    )

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Watch, contentDescription = null, modifier = Modifier.size(14.dp), tint = AppTheme.SubtleTextColor)
                            Text(text = "External", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(14.dp), tint = AppTheme.SubtleTextColor)
                            Text(text = "Phone", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

