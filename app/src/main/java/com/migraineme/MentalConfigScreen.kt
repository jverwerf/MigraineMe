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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MentalConfigScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val initialConfig = remember { MentalCardConfigStore.load(context) }
    var selectedMetrics by remember { mutableStateOf<Set<String>>(initialConfig.mentalDisplayMetrics.toSet()) }

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

    fun saveConfig() {
        MentalCardConfigStore.save(
            context,
            MentalCardConfigData(mentalDisplayMetrics = selectedMetrics.toList())
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
                    text = "Customize Mental Health",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Choose which mental health metrics to display on the Monitor screen.",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }

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
                    for (metric in MentalCardConfig.ALL_MENTAL_METRICS) {
                        val table = MentalCardConfig.metricToTable(metric)
                        val setting = metricSettings[table]

                        if (settingsLoaded && setting != null && !setting.enabled) continue

                        val isSelected = metric in selectedMetrics
                        val label = MentalCardConfig.labelFor(metric)
                        val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))
                        val slotIndex = if (isSelected) selectedMetrics.toList().indexOf(metric) else -1
                        val chipColor = if (slotIndex in slotColors.indices) slotColors[slotIndex] else AppTheme.AccentPurple
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                if (isSelected) {
                                    selectedMetrics = selectedMetrics.minus(metric)
                                } else if (selectedMetrics.size < 3) {
                                    selectedMetrics = selectedMetrics.plus(metric)
                                }
                                saveConfig()
                            },
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
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = chipColor.copy(alpha = 0.3f),
                                selectedLabelColor = chipColor,
                                containerColor = AppTheme.BaseCardContainer,
                                labelColor = AppTheme.BodyTextColor
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = if (isSelected) chipColor else AppTheme.SubtleTextColor.copy(alpha = 0.5f),
                                selectedBorderColor = chipColor,
                                enabled = true,
                                selected = isSelected
                            )
                        )
                    }
                }
            }
        }
    }
}
