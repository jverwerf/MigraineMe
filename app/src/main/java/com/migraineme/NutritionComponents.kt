package com.migraineme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun FoodSearchResultItem(
    food: USDAFoodSearchResult,
    foodRisks: FoodRiskResult? = null,
    isClassifyingRisks: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                food.description,
                color = AppTheme.TitleColor,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 2
            )
            if (food.brandName != null) {
                Text(
                    food.brandName,
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                food.calories?.let {
                    Text(
                        "${it.toInt()} cal",
                        color = Color(0xFFFFB74D),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (food.servingSize != null && food.servingSizeUnit != null) {
                    Text(
                        " • ${food.servingSize.toInt()} ${food.servingSizeUnit}",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                // Risk badges — geometric icon + vertical bar
                if (foodRisks != null) {
                    // Colour by severity level (green/amber/red), matching iOS.
                    // Each badge renders nothing when its level is "none".
                    TyramineRiskBadge(riskLevelColor(foodRisks.tyramine), foodRisks.tyramine)
                    AlcoholRiskBadge(riskLevelColor(foodRisks.alcohol), foodRisks.alcohol)
                    GlutenRiskBadge(riskLevelColor(foodRisks.gluten), foodRisks.gluten)
                    HistamineRiskBadge(riskLevelColor(foodRisks.histamine), foodRisks.histamine)
                } else if (isClassifyingRisks) {
                    Spacer(Modifier.width(6.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        color = AppTheme.AccentPurple,
                        strokeWidth = 1.5.dp
                    )
                }
            }
        }
        
        Icon(
            Icons.Default.Add,
            contentDescription = "Add",
            tint = AppTheme.AccentPurple,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun RiskBadge(
    letter: String,
    level: String,
    highColor: Color,
    mediumColor: Color,
    lowColor: Color
) {
    if (level == "none") return
    val color = when (level) {
        "high" -> highColor; "medium" -> mediumColor; "low" -> lowColor
        else -> return
    }
    Spacer(Modifier.width(4.dp))
    Text(
        letter,
        color = color,
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
    )
}

@Composable
fun TodayLogItem(
    item: NutritionLogItem,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.foodName, color = AppTheme.TitleColor, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Row {
                Text(item.mealType.replaceFirstChar { it.uppercase() }, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                item.calories?.let {
                    Text(" • ${it.toInt()} cal", color = Color(0xFFFFB74D), style = MaterialTheme.typography.bodySmall)
                }
                if (item.source == "manual_usda") {
                    Text(" • Manual", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        
        if (onEdit != null) {
            Text("✎", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clickable { onEdit() }.padding(8.dp))
        }
        
        if (onDelete != null) {
            Text("✕", color = Color(0xFFE57373), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clickable { onDelete() }.padding(8.dp))
        }
    }
}

@Composable
fun NutrientRow(label: String, value: Double?, unit: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = AppTheme.TitleColor, style = MaterialTheme.typography.bodySmall)
        Text(
            if (value != null && value > 0) {
                if (value < 1) String.format("%.2f%s", value, unit) else String.format("%.1f%s", value, unit)
            } else "-",
            color = AppTheme.AccentPurple,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
        )
    }
}

