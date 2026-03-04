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

/**
 * Determines the source icon type based on preferred_source from metric_settings.
 */
private fun sourceTypeFor(preferredSource: String?): String? = when (preferredSource) {
    "whoop", "garmin", "oura", "fitbit", "health_connect" -> "external"
    "phone" -> "phone"
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SleepConfigScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var selectedMetrics by remember {
        mutableStateOf(MetricDisplayStore.getDisplayMetrics(context, "sleep").toSet())
    }

    // Which metrics are enabled + their settings (for source icons)
    var enabledRegistryKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var metricSettingsMap by remember {
        mutableStateOf<Map<String, EdgeFunctionsService.MetricSettingResponse>>(emptyMap())
    }
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
                            metricSettingsMap = settings.associateBy { it.metric }
                            enabledRegistryKeys = MetricRegistry.enabledKeys(settings, "sleep")
                        } catch (_: Exception) {
                            enabledRegistryKeys = MetricRegistry.byGroup("sleep").map { it.key }.toSet()
                        }
                        settingsLoaded = true
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Only show enabled metrics from registry
    val availableMetrics = remember(settingsLoaded, enabledRegistryKeys) {
        MetricRegistry.byGroup("sleep").filter { it.key in enabledRegistryKeys }
    }

    fun saveConfig() {
        MetricDisplayStore.setDisplayMetrics(context, "sleep", selectedMetrics.toList())
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {
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
                    text = "Customize Sleep",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Choose which sleep metrics to display on the Monitor screen.",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (settingsLoaded && availableMetrics.isEmpty()) {
                BaseCard {
                    Text(
                        text = "No sleep source enabled",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Enable sleep tracking in Data Settings to customize metrics.",
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
                        text = "Select up to 3 sleep metrics to show on the Monitor card.",
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
                        for (metric in availableMetrics) {
                            val isSelected = metric.key in selectedMetrics
                            val label = metric.label
                            val slotIndex = if (isSelected) selectedMetrics.toList().indexOf(metric.key) else -1
                            val slotColor = if (slotIndex in slotColors.indices) slotColors[slotIndex] else AppTheme.AccentPurple

                            // Determine source icon from metric_settings
                            val settingsKey = MetricRegistry.dataSettingsKey(metric.key)
                            val setting = metricSettingsMap[settingsKey]
                            val source = sourceTypeFor(setting?.preferredSource)

                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedMetrics = if (isSelected) {
                                        selectedMetrics.minus(metric.key)
                                    } else if (selectedMetrics.size < 3) {
                                        selectedMetrics.plus(metric.key)
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
                                trailingIcon = when (source) {
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
                                    "phone" -> {
                                        {
                                            Icon(
                                                Icons.Default.PhoneAndroid,
                                                contentDescription = "Phone",
                                                modifier = Modifier.size(14.dp),
                                                tint = if (isSelected) slotColor else AppTheme.SubtleTextColor
                                            )
                                        }
                                    }
                                    else -> null
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
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
