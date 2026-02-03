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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeatherConfigScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Load current config
    val initialConfig = remember { WeatherCardConfigStore.load(context) }
    var selectedMetrics by remember { mutableStateOf(initialConfig.weatherDisplayMetrics.toSet()) }

    fun saveConfig() {
        WeatherCardConfigStore.save(
            context,
            WeatherCardConfigData(weatherDisplayMetrics = selectedMetrics.toList())
        )
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll) {
            // Back navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                IconButton(onClick = {
                    saveConfig()
                    onBack()
                }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            }

            HeroCard {
                Text(
                    "Customize Weather",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Choose which weather metrics to display on the Monitor screen.",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            BaseCard {
                Text(
                    "Display Metrics (${selectedMetrics.size}/3)",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Select up to 3 weather metrics to show on the Monitor card.",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WeatherCardConfig.ALL_WEATHER_METRICS.forEach { metric ->
                        val isSelected = metric in selectedMetrics
                        val label = WeatherCardConfig.WEATHER_METRIC_LABELS[metric] ?: metric
                        val canSelect = isSelected || selectedMetrics.size < 3

                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                selectedMetrics = if (isSelected) {
                                    selectedMetrics - metric
                                } else if (selectedMetrics.size < 3) {
                                    selectedMetrics + metric
                                } else {
                                    selectedMetrics // Don't add if already at max
                                }
                                saveConfig()
                            },
                            enabled = canSelect,
                            label = { Text(label) },
                            leadingIcon = if (isSelected) {
                                {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AppTheme.AccentPurple.copy(alpha = 0.3f),
                                selectedLabelColor = AppTheme.AccentPurple,
                                selectedLeadingIconColor = AppTheme.AccentPurple,
                                containerColor = AppTheme.BaseCardContainer,
                                labelColor = AppTheme.SubtleTextColor,
                                disabledContainerColor = AppTheme.BaseCardContainer.copy(alpha = 0.5f),
                                disabledLabelColor = AppTheme.SubtleTextColor.copy(alpha = 0.4f)
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                                selectedBorderColor = AppTheme.AccentPurple,
                                disabledBorderColor = AppTheme.SubtleTextColor.copy(alpha = 0.2f),
                                enabled = canSelect,
                                selected = isSelected
                            )
                        )
                    }
                }
            }

        }
    }
}
