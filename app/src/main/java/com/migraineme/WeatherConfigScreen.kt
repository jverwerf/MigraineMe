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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WeatherConfigScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val initialConfig = remember { WeatherCardConfigStore.load(context) }
    var selectedMetrics by remember { mutableStateOf(initialConfig.weatherDisplayMetrics.toSet()) }

    // Check if any weather metric is enabled in data settings
    var anyWeatherEnabled by remember { mutableStateOf(true) } // default true until loaded
    var settingsLoaded by remember { mutableStateOf(false) }

    val weatherTables = setOf(
        "temperature_daily", "pressure_daily", "humidity_daily",
        "wind_daily", "uv_daily", "thunderstorm_daily"
    )

    LaunchedEffect(Unit) {
        scope.launch {
            try {
                val edge = EdgeFunctionsService()
                val settings = edge.getMetricSettings(context)
                val map = settings.associateBy { it.metric }
                // Only hide if there are settings and ALL weather metrics are explicitly disabled
                val hasAnySettings = weatherTables.any { map.containsKey(it) }
                anyWeatherEnabled = !hasAnySettings || weatherTables.any { map[it]?.enabled != false }
            } catch (_: Exception) { }
            settingsLoaded = true
        }
    }

    fun saveConfig() {
        WeatherCardConfigStore.save(
            context,
            WeatherCardConfigData(weatherDisplayMetrics = selectedMetrics.toList())
        )
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll) {
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
                    text = "Customize Environment",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Choose which environment metrics to display on the Monitor screen.",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (settingsLoaded && !anyWeatherEnabled) {
                BaseCard {
                    Text(
                        text = "No environment source enabled",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Enable environment tracking in Data Settings to customize metrics.",
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
                        text = "Select up to 3 environment metrics to show on the Monitor card.",
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
                        for (metric in WeatherCardConfig.ALL_WEATHER_METRICS) {
                            val isSelected = metric in selectedMetrics
                            val label = WeatherCardConfig.WEATHER_METRIC_LABELS[metric] ?: metric
                            val canSelect = isSelected || selectedMetrics.size < 3
                            val slotIndex = if (isSelected) selectedMetrics.toList().indexOf(metric) else -1
                            val slotColor = if (slotIndex in slotColors.indices) slotColors[slotIndex] else AppTheme.AccentPurple

                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedMetrics = if (isSelected) {
                                        selectedMetrics.minus(metric)
                                    } else if (selectedMetrics.size < 3) {
                                        selectedMetrics.plus(metric)
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
                        Text(text = "Phone (API)", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
