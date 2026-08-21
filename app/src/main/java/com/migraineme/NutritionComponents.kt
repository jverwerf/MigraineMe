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
import androidx.compose.runtime.remember
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
                        t("%s cal", it.toInt()),
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
            contentDescription = t("Add"),
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
                    Text(t(" • %s cal", it.toInt()), color = Color(0xFFFFB74D), style = MaterialTheme.typography.bodySmall)
                }
                if (item.source == "manual_usda") {
                    Text(t(" • Manual"), color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
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
        Text(t(label), color = AppTheme.TitleColor, style = MaterialTheme.typography.bodySmall)
        Text(
            if (value != null && value > 0) {
                if (value < 1) String.format("%.2f%s", value, unit) else String.format("%.1f%s", value, unit)
            } else "-",
            color = AppTheme.AccentPurple,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
        )
    }
}


/**
 * The four exposure verdicts for a day — tyramine, alcohol, gluten, histamine —
 * as one compact block: geometric icon, name, severity word, vertical bar.
 *
 * These are the only nutrition numbers that mean anything on their own to
 * someone tracking migraine, so they sit at the TOP of Today's Log rather than
 * buried at the bottom of All Nutrients where they used to be the last thing on
 * the screen. Colour comes from RiskColors, so the hue names the nutrient and
 * the tint names the level, matching the All Nutrients rows exactly.
 */
@Composable
fun NutritionExposureRows(todayItems: List<NutritionLogItem>) {
    val exposureKeys = remember {
        MetricRegistry.byGroup("nutrition")
            .map { it.key }
            .filter { ExposureScale.isExposureMetric(MetricRegistry.nutritionLegacyKey(it)) }
    }
    if (exposureKeys.isEmpty()) return

    Column(Modifier.fillMaxWidth()) {
        exposureKeys.forEach { registryKey ->
            val legacyKey = MetricRegistry.nutritionLegacyKey(registryKey)
            val total = todayItems.metricTotal(legacyKey)
            val (levelText, valueColor) = RiskColors.formatRiskLevel(legacyKey, total.toInt())
            val level = when (total.toInt()) { 3 -> "high"; 2 -> "medium"; 1 -> "low"; else -> "none" }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (legacyKey) {
                        "tyramine_exposure" -> CheeseIcon(valueColor, 12.dp)
                        "alcohol_exposure" -> WineGlassIcon(valueColor, 12.dp)
                        "gluten_exposure" -> WheatIcon(valueColor, 12.dp)
                        "histamine_exposure" -> FlaskIcon(valueColor, 12.dp)
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(t(MetricRegistry.label(registryKey)), color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(levelText, color = valueColor, style = MaterialTheme.typography.bodySmall)
                    if (level != "none") {
                        Spacer(Modifier.width(4.dp))
                        RiskBar(valueColor, level, maxHeight = 12.dp)
                    }
                }
            }
        }
    }
}
