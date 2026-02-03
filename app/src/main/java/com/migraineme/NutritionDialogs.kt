package com.migraineme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFoodDialog(
    food: USDAFoodSearchResult,
    foodDetails: USDAFoodDetailsFull?,
    isLoadingDetails: Boolean,
    mealType: String,
    onMealTypeChange: (String) -> Unit,
    servings: Double,
    onServingsChange: (Double) -> Unit,
    isAdding: Boolean,
    monitorMetrics: List<String>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val mealTypes = listOf("breakfast", "lunch", "dinner", "snack")
    val scrollState = rememberScrollState()
    
    AlertDialog(
        onDismissRequest = { if (!isAdding && !isLoadingDetails) onDismiss() },
        title = { Text("Add Food", color = AppTheme.TitleColor) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    food.description,
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                )
                
                food.brandName?.let {
                    Text(it, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
                
                Spacer(Modifier.height(12.dp))
                
                // Servings selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Servings", color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium)
                    if (food.servingSize != null && food.servingSizeUnit != null) {
                        Text(
                            "1 serving = ${food.servingSize.toInt()} ${food.servingSizeUnit}",
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                
                Spacer(Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "−",
                        color = if (servings > 0.5) AppTheme.AccentPurple else AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier
                            .clickable(enabled = servings > 0.5) { onServingsChange((servings - 0.5).coerceAtLeast(0.5)) }
                            .padding(horizontal = 12.dp)
                    )
                    
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (servings == servings.toLong().toDouble()) "${servings.toLong()}" else String.format("%.1f", servings),
                            color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        if (food.servingSize != null && food.servingSizeUnit != null) {
                            Text(
                                "= ${(food.servingSize * servings).toInt()} ${food.servingSizeUnit}",
                                color = AppTheme.AccentPurple,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    
                    Text(
                        "+",
                        color = AppTheme.AccentPurple,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier
                            .clickable { onServingsChange(servings + 0.5) }
                            .padding(horizontal = 12.dp)
                    )
                }
                
                Spacer(Modifier.height(12.dp))
                
                if (isLoadingDetails) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), AppTheme.AccentPurple, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Loading nutrients...", color = AppTheme.SubtleTextColor)
                    }
                } else if (foodDetails != null) {
                    val nutrients = foodDetails.foodNutrients.associate { it.nutrient.id to (it.amount ?: 0.0) }
                    
                    val nutrientIdMap = mapOf(
                        MonitorCardConfig.METRIC_CALORIES to 1008,
                        MonitorCardConfig.METRIC_PROTEIN to 1003,
                        MonitorCardConfig.METRIC_CARBS to 1005,
                        MonitorCardConfig.METRIC_FAT to 1004,
                        MonitorCardConfig.METRIC_FIBER to 1079,
                        MonitorCardConfig.METRIC_SUGAR to 2000,
                        MonitorCardConfig.METRIC_SODIUM to 1093,
                        MonitorCardConfig.METRIC_CAFFEINE to 1057
                    )
                    
                    fun getValue(metric: String): Double? {
                        return nutrientIdMap[metric]?.let { nutrients[it] }?.times(servings)
                    }
                    
                    Text(
                        "Nutrition (per serving × $servings)",
                        color = AppTheme.AccentPurple,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(Modifier.height(4.dp))
                    
                    monitorMetrics.forEach { metric ->
                        val value = getValue(metric)
                        val label = MonitorCardConfig.NUTRITION_METRIC_LABELS[metric] ?: metric
                        val unit = MonitorCardConfig.NUTRITION_METRIC_UNITS[metric] ?: ""
                        NutrientRow(label, value, unit)
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                Text("Meal Type", color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = mealType.replaceFirstChar { it.uppercase() },
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppTheme.AccentPurple,
                            unfocusedBorderColor = AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                            focusedTextColor = AppTheme.TitleColor,
                            unfocusedTextColor = AppTheme.TitleColor
                        ),
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        mealTypes.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.replaceFirstChar { it.uppercase() }) },
                                onClick = {
                                    onMealTypeChange(type)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isAdding && !isLoadingDetails && foodDetails != null) {
                if (isAdding) {
                    CircularProgressIndicator(Modifier.size(16.dp), AppTheme.AccentPurple, strokeWidth = 2.dp)
                } else {
                    Text("Add", color = AppTheme.AccentPurple)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isAdding) {
                Text("Cancel", color = AppTheme.SubtleTextColor)
            }
        },
        containerColor = Color(0xFF1E0A2E)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditFoodDialog(
    item: NutritionLogItem,
    mealType: String,
    onMealTypeChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val mealTypes = listOf("breakfast", "lunch", "dinner", "snack")
    var servingsMultiplier by remember { mutableStateOf(1.0) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Food", color = AppTheme.TitleColor) },
        text = {
            Column {
                Text(item.foodName, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                
                item.calories?.let {
                    val adjustedCalories = (it * servingsMultiplier).toInt()
                    Text(
                        "$adjustedCalories calories${if (servingsMultiplier != 1.0) " (was ${it.toInt()})" else ""}",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                
                Spacer(Modifier.height(16.dp))
                
                Text("Adjust Servings", color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "−",
                        color = if (servingsMultiplier > 0.5) AppTheme.AccentPurple else AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier
                            .clickable(enabled = servingsMultiplier > 0.5) { servingsMultiplier = (servingsMultiplier - 0.5).coerceAtLeast(0.5) }
                            .padding(horizontal = 12.dp)
                    )
                    
                    Text(
                        "${if (servingsMultiplier == servingsMultiplier.toLong().toDouble()) servingsMultiplier.toLong() else String.format("%.1f", servingsMultiplier)}x",
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    
                    Text(
                        "+",
                        color = AppTheme.AccentPurple,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.clickable { servingsMultiplier += 0.5 }.padding(horizontal = 12.dp)
                    )
                }
                
                Spacer(Modifier.height(16.dp))
                
                Text("Meal Type", color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = mealType.replaceFirstChar { it.uppercase() },
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppTheme.AccentPurple,
                            unfocusedBorderColor = AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                            focusedTextColor = AppTheme.TitleColor,
                            unfocusedTextColor = AppTheme.TitleColor
                        ),
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        mealTypes.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.replaceFirstChar { it.uppercase() }) },
                                onClick = {
                                    onMealTypeChange(type)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                Text(
                    "Delete this entry",
                    color = Color(0xFFE57373),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable { onDelete() }.padding(vertical = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(servingsMultiplier) }) {
                Text("Save", color = AppTheme.AccentPurple)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = AppTheme.SubtleTextColor)
            }
        },
        containerColor = Color(0xFF1E0A2E)
    )
}
