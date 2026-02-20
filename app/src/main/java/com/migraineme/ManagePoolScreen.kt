package com.migraineme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val direction: String? = null,
    val displayGroup: String? = null
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
    val onThresholdChange: (itemId: String, threshold: Double?) -> Unit = { _, _ -> },
    val onSave: (suspend () -> Unit)? = null
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
    val scope = rememberCoroutineScope()

    // ── Local change tracking (prediction + threshold) ──
    var pendingPredictions by remember { mutableStateOf(mapOf<String, PredictionValue>()) }
    var pendingThresholds by remember { mutableStateOf(mapOf<String, Double?>()) }
    val isDirty = pendingPredictions.isNotEmpty() || pendingThresholds.isNotEmpty()
    var isSaving by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    // ── Overlay pending changes onto items for display ──
    val displayItems = remember(config.items, pendingPredictions, pendingThresholds) {
        config.items.map { item ->
            item.copy(
                prediction = pendingPredictions[item.id] ?: item.prediction,
                threshold = if (item.id in pendingThresholds) pendingThresholds[item.id] else item.threshold
            )
        }
    }

    // ── Build effective config: intercepts prediction/threshold locally,
    //    passes all other callbacks through immediately ──
    val effectiveConfig = remember(config, displayItems) {
        config.copy(
            items = displayItems,
            onSetPrediction = { id, pv ->
                pendingPredictions = pendingPredictions + (id to pv)
            },
            onThresholdChange = { id, threshold ->
                pendingThresholds = pendingThresholds + (id to threshold)
            }
        )
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            // Close bar — check for unsaved changes
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(effectiveConfig.title, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                IconButton(onClick = {
                    if (isDirty) showUnsavedDialog = true
                    else navController.popBackStack()
                }) {
                    Icon(Icons.Outlined.Close, "Close", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            // Hero
            HeroCard {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .drawBehind { effectiveConfig.drawHeroIcon(this, effectiveConfig.iconColor) }
                )
                Spacer(Modifier.height(6.dp))
                Text(effectiveConfig.title, color = AppTheme.TitleColor, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(effectiveConfig.subtitle, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
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
                        Icon(Icons.Outlined.Add, "Add", tint = effectiveConfig.iconColor, modifier = Modifier.size(20.dp))
                    }
                }

                if (effectiveConfig.items.isEmpty()) {
                    Text("No items yet — tap + to add", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                } else {
                    // Group items by category, with uncategorized at the end
                    val grouped = effectiveConfig.items.groupBy { it.category ?: "Other" }
                    val sortedCategories = grouped.keys.sortedWith(compareBy { if (it == "Other") "zzz" else it })
                    var expandedCategories by remember { mutableStateOf(setOf<String>()) }
                    var expandedGroups by remember { mutableStateOf(setOf<String>()) }

                    sortedCategories.forEach { category ->
                        val isExpanded = category in expandedCategories
                        val categoryItems = grouped[category] ?: emptyList()
                        val itemCount = categoryItems.size

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
                                "${category.replaceFirstChar { c -> c.uppercase() }} ($itemCount)",
                                color = effectiveConfig.iconColor.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Icon(
                                if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                contentDescription = if (isExpanded) "Collapse" else "Expand",
                                tint = effectiveConfig.iconColor.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        HorizontalDivider(color = effectiveConfig.iconColor.copy(alpha = 0.15f), thickness = 0.5.dp, modifier = Modifier.padding(bottom = 4.dp))

                    AnimatedVisibility(visible = isExpanded) {
                        Column {
                        // Within category, separate grouped vs standalone items
                        val withGroup = categoryItems.filter { it.displayGroup != null }
                        val standalone = categoryItems.filter { it.displayGroup == null }

                        // Render display_groups first
                        val displayGroups = withGroup.groupBy { it.displayGroup!! }
                        displayGroups.forEach { (groupName, members) ->
                            val isGroupExpanded = groupName in expandedGroups
                            // Use first member's icon as group icon
                            val groupIcon = effectiveConfig.iconResolver?.invoke(members.firstOrNull()?.iconKey)
                            // Group-level prediction = highest among members
                            val groupPrediction = members.map { it.prediction }
                                .maxByOrNull { it.ordinal } ?: PredictionValue.NONE
                            val groupIsFavorite = members.any { it.isFavorite }

                            // ── Group header row ──
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(effectiveConfig.iconColor.copy(alpha = 0.06f))
                                    .clickable { expandedGroups = if (isGroupExpanded) expandedGroups - groupName else expandedGroups + groupName }
                                    .padding(vertical = 8.dp, horizontal = 8.dp),
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
                                    if (groupIcon != null) {
                                        Icon(groupIcon, groupName, tint = effectiveConfig.iconColor, modifier = Modifier.size(20.dp))
                                    } else {
                                        Text(groupName.take(2).uppercase(), color = effectiveConfig.iconColor,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                    }
                                }

                                // Group name + member count
                                Column(Modifier.weight(1f)) {
                                    Text(groupName, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                    Text("${members.size} metrics", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                                }

                                // Expand indicator
                                Icon(
                                    if (isGroupExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    contentDescription = if (isGroupExpanded) "Collapse" else "Expand",
                                    tint = AppTheme.SubtleTextColor.copy(alpha = 0.5f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // Group-level prediction chips
                            if (effectiveConfig.showPrediction) {
                                Row(
                                    modifier = Modifier.padding(start = 46.dp, top = 4.dp, bottom = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    PredictionValue.entries.forEach { pv ->
                                        PredictionChip(
                                            value = pv,
                                            isSelected = groupPrediction == pv,
                                            onClick = {
                                                // Set prediction on ALL members of the group — stored locally
                                                members.forEach { member -> effectiveConfig.onSetPrediction(member.id, pv) }
                                            }
                                        )
                                    }
                                }
                            }

                            // ── Expanded: show individual members ──
                            AnimatedVisibility(visible = isGroupExpanded) {
                                Column(Modifier.padding(start = 20.dp, top = 4.dp)) {
                                    members.forEach { item ->
                                        PoolItemRow(item = item, config = effectiveConfig, showDeleteDialog = { showDeleteDialog = it }, indent = 26.dp)
                                        if (item != members.last()) {
                                            HorizontalDivider(color = Color.White.copy(alpha = 0.04f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }

                        // Render standalone items (no displayGroup)
                        standalone.forEach { item ->
                            PoolItemRow(item = item, config = effectiveConfig, showDeleteDialog = { showDeleteDialog = it })
                            if (item != standalone.last()) {
                                HorizontalDivider(color = Color.White.copy(alpha = 0.06f), thickness = 0.5.dp, modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
                        } // end Column
                    } // end AnimatedVisibility
                    } // end categories forEach
                }
            }

            // ── Save & Back buttons ──
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        if (isDirty) showUnsavedDialog = true
                        else navController.popBackStack()
                    },
                    border = BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple)
                ) { Text("Back") }

                if (isDirty) {
                    Button(
                        onClick = {
                            isSaving = true
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    // 1. Write all pending prediction changes to Supabase
                                    for ((id, pv) in pendingPredictions) {
                                        config.onSetPrediction(id, pv)
                                    }
                                    // 2. Write all pending threshold changes to Supabase
                                    for ((id, thresh) in pendingThresholds) {
                                        config.onThresholdChange(id, thresh)
                                    }
                                    // 3. Call recalc edge function
                                    config.onSave?.invoke()
                                }
                                // 4. Clear dirty state
                                pendingPredictions = emptyMap()
                                pendingThresholds = emptyMap()
                                isSaving = false
                            }
                        },
                        enabled = !isSaving,
                        colors = ButtonDefaults.buttonColors(containerColor = effectiveConfig.iconColor)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Saving...", color = Color.White)
                        } else {
                            Text("Save & Recalculate", color = Color.White)
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // ── Unsaved changes dialog ──
    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            containerColor = Color(0xFF1E0A2E),
            titleContentColor = Color.White,
            textContentColor = AppTheme.BodyTextColor,
            title = { Text("Unsaved Changes") },
            text = { Text("You have unsaved changes to prediction values or thresholds. Discard them?") },
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    navController.popBackStack()
                }) { Text("Discard", color = AppTheme.AccentPink) }
            },
            dismissButton = {
                TextButton(onClick = { showUnsavedDialog = false }) { Text("Keep Editing", color = AppTheme.SubtleTextColor) }
            }
        )
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
    val bg = if (isSelected) value.chipColor.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.06f)
    val border = if (isSelected) value.chipColor.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.10f)
    val textColor = if (isSelected) value.chipColor else AppTheme.SubtleTextColor

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(value.display, color = textColor, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
    }
}

/* ────────────────────────────────────────────────
 *  Pool item row
 * ──────────────────────────────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PoolItemRow(
    item: PoolItem,
    config: PoolConfig,
    showDeleteDialog: (PoolItem) -> Unit,
    indent: Dp = 0.dp
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(start = indent)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Icon circle
            val icon = config.iconResolver?.invoke(item.iconKey)
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.07f))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (icon != null) {
                    Icon(icon, item.label, tint = config.iconColor.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                } else {
                    Text(item.label.take(2).uppercase(), color = config.iconColor.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                }
            }

            // Label
            Text(item.label, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))

            // Prediction badge
            if (config.showPrediction && item.prediction != PredictionValue.NONE) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(item.prediction.chipColor.copy(alpha = 0.20f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(item.prediction.display, color = item.prediction.chipColor, style = MaterialTheme.typography.labelSmall)
                }
            }

            // Favorite star
            IconButton(onClick = { config.onToggleFavorite(item.id, !item.isFavorite) }, modifier = Modifier.size(28.dp)) {
                Icon(
                    if (item.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (item.isFavorite) "Unstar" else "Star",
                    tint = if (item.isFavorite) Color(0xFFFDD835) else AppTheme.SubtleTextColor.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp)
                )
            }

            // Delete
            IconButton(onClick = { showDeleteDialog(item) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.Delete, "Delete", tint = AppTheme.SubtleTextColor.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
            }
        }

        // ── Expanded detail section ──
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 42.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Prediction chips
                if (config.showPrediction) {
                    Text("Prediction", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PredictionValue.entries.forEach { pv ->
                            PredictionChip(value = pv, isSelected = item.prediction == pv, onClick = { config.onSetPrediction(item.id, pv) })
                        }
                    }
                }

                // Category selector
                if (config.categories.isNotEmpty()) {
                    Text("Category", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    var catExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = catExpanded, onExpandedChange = { catExpanded = it }) {
                        OutlinedTextField(
                            value = item.category ?: "None",
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            textStyle = MaterialTheme.typography.bodySmall,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catExpanded) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedBorderColor = config.iconColor, unfocusedBorderColor = Color.White.copy(alpha = 0.12f)
                            )
                        )
                        ExposedDropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }, modifier = Modifier.background(Color(0xFF2A0C3C))) {
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

                // Automation toggle
                if (item.isAutomatable) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Auto-detect", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = item.isAutomated,
                            onCheckedChange = { config.onToggleAutomation(item.id, it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = config.iconColor,
                                checkedTrackColor = config.iconColor.copy(alpha = 0.3f),
                                uncheckedThumbColor = AppTheme.SubtleTextColor,
                                uncheckedTrackColor = Color.White.copy(alpha = 0.08f)
                            )
                        )
                    }

                    // Threshold editor
                    if (item.isAutomated && item.direction != null) {
                        val currentThreshold = item.threshold ?: item.defaultThreshold
                        val dimAlpha = if (currentThreshold != null) 1f else 0.4f
                        var textValue by remember(currentThreshold) {
                            mutableStateOf(currentThreshold?.let { formatThresholdForDisplay(it, item.unit) } ?: "")
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (item.direction == "high") "Fires above" else "Fires below",
                                color = AppTheme.SubtleTextColor.copy(alpha = dimAlpha),
                                style = MaterialTheme.typography.labelSmall
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedTextField(
                                    value = textValue, onValueChange = { newText -> textValue = newText; newText.toDoubleOrNull()?.let { config.onThresholdChange(item.id, it) } },
                                    singleLine = true,
                                    modifier = Modifier.width(80.dp),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White, textAlign = TextAlign.End),
                                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = config.iconColor, unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                                        cursorColor = config.iconColor
                                    )
                                )
                                Text(when (item.unit) { "hours" -> "h"; "%" -> "%"; "count" -> ""; "time" -> ""; else -> item.unit ?: "" },
                                    color = AppTheme.SubtleTextColor.copy(alpha = dimAlpha), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
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

