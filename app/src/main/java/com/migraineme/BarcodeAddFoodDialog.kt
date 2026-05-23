package com.migraineme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarcodeAddFoodDialog(
    product: OFFProduct,
    grams: Double,
    onGramsChange: (Double) -> Unit,
    mealType: String,
    onMealTypeChange: (String) -> Unit,
    isAdding: Boolean,
    monitorMetrics: List<String>,
    tyramineRisk: String? = null,
    alcoholRisk: String? = null,
    glutenRisk: String? = null,
    histamineRisk: String? = null,
    isClassifyingRisks: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val mealTypes = listOf("breakfast", "lunch", "dinner", "snack")
    val scrollState = rememberScrollState()
    var gramsText by remember(grams) {
        mutableStateOf(if (grams == grams.toLong().toDouble()) grams.toLong().toString() else String.format("%.1f", grams))
    }

    val scale = (grams.takeIf { it > 0 } ?: 100.0) / 100.0
    fun scaled(column: String): Double? = product.nutrientsPer100g[column]?.times(scale)

    AlertDialog(
        onDismissRequest = { if (!isAdding) onDismiss() },
        title = { Text("Add Scanned Food", color = AppTheme.TitleColor) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(scrollState)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (product.imageUrl != null) {
                        AsyncImage(
                            model = product.imageUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            product.name,
                            color = AppTheme.BodyTextColor,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                        )
                        product.brand?.let {
                            Text(it, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            product.barcode,
                            color = AppTheme.SubtleTextColor.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Portion in grams
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Portion", color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium)
                    product.servingQuantityGrams?.let {
                        Text(
                            "1 serving ≈ ${it.toInt()} g",
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.clickable {
                                onGramsChange(it)
                                gramsText = it.toLong().toString()
                            }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = gramsText,
                    onValueChange = { txt ->
                        gramsText = txt
                        txt.replace(",", ".").toDoubleOrNull()?.takeIf { it >= 0 }?.let(onGramsChange)
                    },
                    placeholder = { Text("Grams") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    trailingIcon = { Text("g", color = AppTheme.SubtleTextColor, modifier = Modifier.padding(end = 12.dp)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.AccentPurple,
                        unfocusedBorderColor = AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                        focusedTextColor = AppTheme.TitleColor,
                        unfocusedTextColor = AppTheme.TitleColor,
                        cursorColor = AppTheme.AccentPurple
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                // Favorite metrics
                Text(
                    "Nutrition (${grams.toInt()} g)",
                    color = AppTheme.AccentPurple,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(4.dp))

                monitorMetrics.forEach { metric ->
                    if (!MonitorCardConfig.isRiskMetric(metric)) {
                        val label = MonitorCardConfig.NUTRITION_METRIC_LABELS[metric] ?: metric
                        val unit = MonitorCardConfig.NUTRITION_METRIC_UNITS[metric] ?: ""
                        NutrientRow(label, scaled(metric), unit)
                    }
                }

                Spacer(Modifier.height(4.dp))
                if (isClassifyingRisks) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Food Risks", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(12.dp), AppTheme.AccentPurple, strokeWidth = 1.5.dp)
                            Spacer(Modifier.width(4.dp))
                            Text("Classifying…", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    BarcodeRiskRow("Tyramine", tyramineRisk, RiskColors.TyramineHigh, RiskColors.TyramineMedium, RiskColors.TyramineLow) { c, s -> CheeseIcon(c, s) }
                    BarcodeRiskRow("Alcohol", alcoholRisk, RiskColors.AlcoholHigh, RiskColors.AlcoholMedium, RiskColors.AlcoholLow) { c, s -> WineGlassIcon(c, s) }
                    BarcodeRiskRow("Gluten", glutenRisk, RiskColors.GlutenHigh, RiskColors.GlutenMedium, RiskColors.GlutenLow) { c, s -> WheatIcon(c, s) }
                    BarcodeRiskRow("Histamine", histamineRisk, RiskColors.HistamineHigh, RiskColors.HistamineMedium, RiskColors.HistamineLow) { c, s -> FlaskIcon(c, s) }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = AppTheme.SubtleTextColor.copy(alpha = 0.2f))
                Spacer(Modifier.height(6.dp))
                Text("All Nutrients", color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(4.dp))

                val shown = monitorMetrics.toSet()
                MonitorCardConfig.ALL_NUTRITION_METRICS.forEach { metric ->
                    if (!MonitorCardConfig.isRiskMetric(metric) && metric !in shown) {
                        val label = MonitorCardConfig.NUTRITION_METRIC_LABELS[metric] ?: metric
                        val unit = MonitorCardConfig.NUTRITION_METRIC_UNITS[metric] ?: ""
                        NutrientRow(label, scaled(metric), unit)
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
            TextButton(onClick = onConfirm, enabled = !isAdding && grams > 0) {
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

@Composable
private fun BarcodeRiskRow(
    label: String,
    risk: String?,
    highColor: Color,
    mediumColor: Color,
    lowColor: Color,
    icon: @Composable (Color, Dp) -> Unit
) {
    val level = risk ?: "none"
    val display = when (level) { "high" -> "High"; "medium" -> "Medium"; "low" -> "Low"; else -> "None" }
    val color = when (level) {
        "high" -> highColor; "medium" -> mediumColor; "low" -> lowColor
        else -> AppTheme.SubtleTextColor
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon(color, 14.dp)
            Spacer(Modifier.width(6.dp))
            Text(label, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(display, color = color, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
            if (level != "none") {
                Spacer(Modifier.width(4.dp))
                RiskBar(color, level, maxHeight = 14.dp)
            }
        }
    }
}
