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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NutritionConfigScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    
    var config by remember { mutableStateOf(MonitorCardConfigStore.load(context)) }
    
    fun updateConfig(newConfig: MonitorCardConfig) {
        config = newConfig
        MonitorCardConfigStore.save(context, newConfig)
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll) {
            // Back navigation
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
            
            // Header
            HeroCard {
                Text(
                    "Customize Nutrition Display",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Choose which 3 metrics appear on the Monitor screen",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            // Metric selection
            BaseCard {
                Text(
                    "Select 3 Metrics",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                
                Spacer(Modifier.height(12.dp))
                
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MonitorCardConfig.ALL_NUTRITION_METRICS.forEach { metric ->
                        val isSelected = metric in config.nutritionDisplayMetrics
                        val canSelect = isSelected || config.nutritionDisplayMetrics.size < 3
                        
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                if (canSelect || isSelected) {
                                    updateConfig(config.toggleNutritionMetric(metric))
                                }
                            },
                            label = {
                                Text(
                                    MonitorCardConfig.NUTRITION_METRIC_LABELS[metric] ?: metric,
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
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AppTheme.AccentPurple.copy(alpha = 0.3f),
                                selectedLabelColor = AppTheme.TitleColor,
                                selectedLeadingIconColor = AppTheme.AccentPurple,
                                containerColor = Color.Transparent,
                                labelColor = AppTheme.SubtleTextColor
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                                selectedBorderColor = AppTheme.AccentPurple,
                                enabled = true,
                                selected = isSelected
                            ),
                            modifier = Modifier.alpha(if (canSelect || isSelected) 1f else 0.4f)
                        )
                    }
                }
                
                Spacer(Modifier.height(12.dp))
                
                Text(
                    "${config.nutritionDisplayMetrics.size}/3 selected",
                    color = if (config.nutritionDisplayMetrics.size == 3) 
                        AppTheme.AccentPurple 
                    else 
                        AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
