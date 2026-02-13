package com.migraineme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

/* ────────────────────────────────────────────────
 *  Data models
 * ──────────────────────────────────────────────── */

enum class PredictionValue(val display: String, val chipColor: Color) {
    NONE("None",  Color(0xFF666666)),
    LOW("Low",    Color(0xFF81C784)),
    MILD("Mild",  Color(0xFFFFB74D)),
    HIGH("High",  Color(0xFFE57373));

    companion object {
        fun fromString(s: String?): PredictionValue =
            entries.find { it.name.equals(s, ignoreCase = true) } ?: NONE
    }
}

data class PoolItem(
    val id: String,
    val label: String,
    val iconKey: String? = null,
    val category: String? = null,
    val isFavorite: Boolean = false,
    val prediction: PredictionValue = PredictionValue.NONE,
    val isAutomatable: Boolean = false,
    val isAutomated: Boolean = false,
    val threshold: Double? = null,
    val defaultThreshold: Double? = null,
    val unit: String? = null,
    val direction: String? = null
)

/** Icon entry for the add-dialog picker grid */
data class PickerIconEntry(val key: String, val label: String, val icon: ImageVector)

data class PoolConfig(
    val title: String,
    val subtitle: String,
    val iconColor: Color,
    val drawHeroIcon: DrawScope.(Color) -> Unit,
    val items: List<PoolItem>,
    val categories: List<String> = emptyList(),
    val showPrediction: Boolean = false,
    val iconResolver: ((String?) -> ImageVector?)? = null,
    val pickerIcons: List<PickerIconEntry> = emptyList(),
    val onAdd: (label: String, category: String?, prediction: PredictionValue) -> Unit,
    val onDelete: (itemId: String) -> Unit,
    val onToggleFavorite: (itemId: String, starred: Boolean) -> Unit,
    val onSetPrediction: (itemId: String, prediction: PredictionValue) -> Unit = { _, _ -> },
    val onToggleAutomation: (itemId: String, enabled: Boolean) -> Unit = { _, _ -> },
    val onSetCategory: (itemId: String, category: String?) -> Unit = { _, _ -> },
    val onThresholdChange: (itemId: String, threshold: Double?) -> Unit = { _, _ -> }
)

/* ────────────────────────────────────────────────
 *  Screen
 * ──────────────────────────────────────────────── */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ManagePoolScreen(
    navController: NavController,
    config: PoolConfig
) {
    val scrollState = rememberScrollState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf<PoolItem?>(null) }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 60.dp) {

            // Close bar
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(config.title, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Outlined.Close, "Close", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            // Hero
            HeroCard {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .drawBehind { config.drawHeroIcon(this, config.iconColor) }
                )
                Spacer(Modifier.height(6.dp))
                Text(config.title, color = AppTheme.TitleColor, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(config.subtitle, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }

            // Pool card
            BaseCard {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Pool", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Outlined.Add, "Add", tint = config.iconColor, modifier = Modifier.size(20.dp))
                    }
                }

                if (config.items.isEmpty()) {
                    Text("No items yet — tap + to add", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                } else {
                    // Group items by category, with uncategorized at the end
                    val grouped = config.items.groupBy { it.category ?: "Other" }
                    val sortedCategories = grouped.keys.sortedWith(compareBy { if (it == "Other") "zzz" else it })
                    var expandedCategories by remember { mutableStateOf(setOf<String>()) }

                    sortedCategories.forEach { category ->
                        val isExpanded = category in expandedCategories
                        val itemCount = grouped[category]?.size ?: 0

                        // Section header — clickable to expand/collapse
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandedCategories = if (isExpanded) expandedCategories - category else expandedCategories + category }
                                .padding(top = 12.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${category.replaceFirstChar { it.uppercase() }} ($itemCount)",
                                color = config.iconColor.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Icon(
                                if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription = if (isExpanded) "Collapse" else "Expand",
                                tint = config.iconColor.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        HorizontalDivider(color = config.iconColor.copy(alpha = 0.15f), thickness = 0.5.dp, modifier = Modifier.padding(bottom = 4.dp))

                    AnimatedVisibility(visible = isExpanded) {
                        Column {
                    grouped[category]?.forEach { item ->
                        // Track category dropdown state per item
                        var catExpanded by remember { mutableStateOf(false) }

                        // ── Row 1: icon · label · star · delete ──
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Icon circle
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                val resolvedIcon = config.iconResolver?.invoke(item.iconKey)
                                if (resolvedIcon != null) {
                                    Icon(
                                        imageVector = resolvedIcon,
                                        contentDescription = item.label,
                                        tint = config.iconColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    Text(
                                        (item.iconKey ?: item.label.take(2)).uppercase(),
                                        color = config.iconColor,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                            }

                            // Label
                            Text(
                                item.label,
                                color = AppTheme.BodyTextColor,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )

                            // Star
                            IconButton(onClick = { config.onToggleFavorite(item.id, !item.isFavorite) }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    if (item.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                    if (item.isFavorite) "Unstar" else "Star",
                                    tint = if (item.isFavorite) Color(0xFFFFD54F) else AppTheme.SubtleTextColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Delete — hidden for automatable (system) triggers
                            if (!item.isAutomatable) {
                                IconButton(onClick = { showDeleteDialog = item }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Outlined.Delete, "Delete", tint = AppTheme.AccentPink.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        // ── Row 2: prediction value chips ──
                        if (config.showPrediction) {
                        Row(
                            modifier = Modifier.padding(start = 46.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            PredictionValue.entries.forEach { pv ->
                                PredictionChip(
                                    value = pv,
                                    isSelected = item.prediction == pv,
                                    onClick = { config.onSetPrediction(item.id, pv) }
                                )
                            }
                        }
                        }

                        // ── Row 3: editable category ──
                        if (config.categories.isNotEmpty()) {
                            Row(
                                modifier = Modifier.padding(start = 46.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color.White.copy(alpha = 0.06f))
                                            .clickable { catExpanded = true }
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            item.category ?: "Category",
                                            color = if (item.category != null) AppTheme.SubtleTextColor else AppTheme.SubtleTextColor.copy(alpha = 0.4f),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                        Icon(
                                            Icons.Outlined.ExpandMore,
                                            contentDescription = "Change category",
                                            tint = AppTheme.SubtleTextColor.copy(alpha = 0.5f),
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = catExpanded,
                                        onDismissRequest = { catExpanded = false },
                                        modifier = Modifier.background(Color(0xFF2A0C3C))
                                    ) {
                                        config.categories.forEach { cat ->
                                            DropdownMenuItem(
                                                text = { Text(cat, color = Color.White, style = MaterialTheme.typography.bodySmall) },
                                                onClick = { config.onSetCategory(item.id, cat); catExpanded = false },
                                                modifier = if (item.category == cat) Modifier.background(config.iconColor.copy(alpha = 0.15f)) else Modifier
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // ── Row 4: automation toggle (only if automatable) ──
                        if (item.isAutomatable) {
                            Row(
                                modifier = Modifier
                                    .padding(start = 46.dp, bottom = 4.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .clickable { config.onToggleAutomation(item.id, !item.isAutomated) }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Mini toggle track
                                Box(
                                    modifier = Modifier
                                        .width(26.dp)
                                        .height(14.dp)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(
                                            if (item.isAutomated) Color(0xFF4A1A6B).copy(alpha = 0.7f)
                                            else Color.White.copy(alpha = 0.10f)
                                        )
                                ) {
                                    // Thumb
                                    Box(
                                        modifier = Modifier
                                            .padding(2.dp)
                                            .size(10.dp)
                                            .align(if (item.isAutomated) Alignment.CenterEnd else Alignment.CenterStart)
                                            .clip(CircleShape)
                                            .background(
                                                if (item.isAutomated) Color.White.copy(alpha = 0.85f)
                                                else AppTheme.SubtleTextColor.copy(alpha = 0.4f)
                                            )
                                    )
                                }
                                Text(
                                    "Auto-detect",
                                    color = if (item.isAutomated) AppTheme.SubtleTextColor else AppTheme.SubtleTextColor.copy(alpha = 0.5f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }

                            // ── Row 5: threshold ──
                            val hasThreshold = item.direction == "low" || item.direction == "high"
                            if (hasThreshold) {
                                val effectiveThreshold = item.threshold ?: item.defaultThreshold
                                val displayValue = effectiveThreshold?.let { formatThresholdForDisplay(it, item.unit) } ?: ""
                                var textValue by remember(item.id, effectiveThreshold) {
                                    mutableStateOf(displayValue)
                                }
                                val dimAlpha = if (item.isAutomated) 1f else 0.4f
                                Row(
                                    modifier = Modifier
                                        .padding(start = 46.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        "Threshold:",
                                        color = AppTheme.SubtleTextColor.copy(alpha = dimAlpha),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    Box(
                                        modifier = Modifier
                                            .width(72.dp)
                                            .height(28.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(
                                                if (item.isAutomated) Color.White.copy(alpha = 0.08f)
                                                else Color.White.copy(alpha = 0.04f)
                                            )
                                            .border(
                                                0.5.dp,
                                                if (item.isAutomated) Color.White.copy(alpha = 0.15f)
                                                else Color.White.copy(alpha = 0.06f),
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 8.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        androidx.compose.foundation.text.BasicTextField(
                                            value = textValue,
                                            onValueChange = { newText ->
                                                textValue = newText
                                                val parsed = newText.toDoubleOrNull()
                                                if (parsed != null) {
                                                    config.onThresholdChange(item.id, parsed)
                                                }
                                            },
                                            enabled = item.isAutomated,
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.labelSmall.copy(
                                                color = AppTheme.BodyTextColor.copy(alpha = dimAlpha)
                                            ),
                                            cursorBrush = androidx.compose.ui.graphics.SolidColor(AppTheme.AccentPurple)
                                        )
                                        if (textValue.isEmpty()) {
                                            Text(
                                                "—",
                                                color = AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                    Text(
                                        when (item.unit) {
                                            "hours" -> "h"
                                            "%" -> "%"
                                            "count" -> ""
                                            "time" -> ""
                                            else -> item.unit ?: ""
                                        },
                                        color = AppTheme.SubtleTextColor.copy(alpha = dimAlpha),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }

                        // Divider between items within same category
                        if (item != grouped[category]?.last()) {
                            HorizontalDivider(color = Color.White.copy(alpha = 0.06f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    } // end items forEach
                        } // end Column
                    } // end AnimatedVisibility
                    } // end categories forEach
                }
            }

            // Back
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                OutlinedButton(
                    onClick = { navController.popBackStack() },
                    border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple)
                ) { Text("Back") }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // ── Add dialog ──
    if (showAddDialog) {
        var newLabel by remember { mutableStateOf("") }
        var newCategory by remember { mutableStateOf<String?>(null) }
        var newPrediction by remember { mutableStateOf(PredictionValue.NONE) }
        var newIconKey by remember { mutableStateOf<String?>(null) }
        var categoryExpanded by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = Color(0xFF1E0A2E),
            titleContentColor = Color.White,
            textContentColor = AppTheme.BodyTextColor,
            title = { Text("Add ${config.title.lowercase().removeSuffix("s")}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Name
                    OutlinedTextField(
                        value = newLabel,
                        onValueChange = { newLabel = it },
                        placeholder = { Text("Name", color = AppTheme.SubtleTextColor) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = config.iconColor,
                            focusedBorderColor = config.iconColor,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.15f)
                        )
                    )

                    // Category dropdown
                    if (config.categories.isNotEmpty()) {
                        Text("Category", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        ExposedDropdownMenuBox(
                            expanded = categoryExpanded,
                            onExpandedChange = { categoryExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = newCategory ?: "Select...",
                                onValueChange = {},
                                readOnly = true,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = if (newCategory != null) Color.White else AppTheme.SubtleTextColor,
                                    cursorColor = config.iconColor,
                                    focusedBorderColor = config.iconColor,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.15f)
                                )
                            )
                            ExposedDropdownMenu(
                                expanded = categoryExpanded,
                                onDismissRequest = { categoryExpanded = false },
                                modifier = Modifier.background(Color(0xFF2A0C3C))
                            ) {
                                config.categories.forEach { cat ->
                                    DropdownMenuItem(
                                        text = { Text(cat, color = Color.White) },
                                        onClick = { newCategory = cat; categoryExpanded = false },
                                        colors = MenuDefaults.itemColors(
                                            textColor = Color.White
                                        ),
                                        modifier = if (newCategory == cat) Modifier.background(config.iconColor.copy(alpha = 0.15f)) else Modifier
                                    )
                                }
                            }
                        }
                    }

                    // Prediction value
                    if (config.showPrediction) {
                        Text("Prediction value", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PredictionValue.entries.forEach { pv ->
                                PredictionChip(
                                    value = pv,
                                    isSelected = newPrediction == pv,
                                    onClick = { newPrediction = pv }
                                )
                            }
                        }
                    }

                    // ── Icon picker (only shown when pickerIcons is provided) ──
                    if (config.pickerIcons.isNotEmpty()) {
                        Text("Pick an icon", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            config.pickerIcons.forEach { picker ->
                                val isChosen = newIconKey == picker.key
                                Box(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (isChosen) config.iconColor.copy(alpha = 0.40f)
                                            else Color.White.copy(alpha = 0.08f)
                                        )
                                        .border(
                                            1.5.dp,
                                            if (isChosen) config.iconColor.copy(alpha = 0.7f)
                                            else Color.White.copy(alpha = 0.12f),
                                            CircleShape
                                        )
                                        .clickable { newIconKey = if (isChosen) null else picker.key },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        picker.icon, contentDescription = picker.label,
                                        tint = if (isChosen) Color.White else AppTheme.SubtleTextColor,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newLabel.isNotBlank()) {
                            config.onAdd(newLabel.trim(), newCategory, newPrediction)
                            showAddDialog = false
                        }
                    },
                    enabled = newLabel.isNotBlank()
                ) { Text("Add", color = if (newLabel.isNotBlank()) config.iconColor else AppTheme.SubtleTextColor) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel", color = AppTheme.SubtleTextColor) }
            }
        )
    }

    // ── Delete dialog ──
    showDeleteDialog?.let { item ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            containerColor = Color(0xFF1E0A2E),
            titleContentColor = Color.White,
            textContentColor = AppTheme.BodyTextColor,
            title = { Text("Remove \"${item.label}\"?") },
            text = { Text("This will remove it from your pool. Past logged entries won't be affected.") },
            confirmButton = {
                TextButton(onClick = { config.onDelete(item.id); showDeleteDialog = null }) { Text("Delete", color = AppTheme.AccentPink) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) { Text("Cancel", color = AppTheme.SubtleTextColor) }
            }
        )
    }
}

/* ────────────────────────────────────────────────
 *  Prediction chip
 * ──────────────────────────────────────────────── */

@Composable
private fun PredictionChip(
    value: PredictionValue,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) value.chipColor.copy(alpha = 0.25f)
                else Color.White.copy(alpha = 0.06f)
            )
            .border(
                1.dp,
                if (isSelected) value.chipColor.copy(alpha = 0.6f)
                else Color.White.copy(alpha = 0.10f),
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            value.display,
            color = if (isSelected) value.chipColor else AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
        )
    }
}

private fun formatThresholdForDisplay(value: Double, unit: String?): String {
    return when (unit) {
        "hours" -> String.format("%.1f", value)
        "%" -> String.format("%.0f", value)
        "count" -> String.format("%.0f", value)
        "time" -> {
            val h = value.toInt()
            String.format("%d:%02d", h, 0)
        }
        else -> String.format("%.1f", value)
    }
}
