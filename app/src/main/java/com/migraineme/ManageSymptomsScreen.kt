package com.migraineme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ManageSymptomsScreen(
    navController: NavController,
    vm: SymptomViewModel,
    authVm: AuthViewModel
) {
    val authState by authVm.state.collectAsState()
    val painCharacter by vm.painCharacter.collectAsState()
    val accompanying by vm.accompanying.collectAsState()
    val favorites by vm.favorites.collectAsState()
    val favoriteIds by vm.favoriteIds.collectAsState()
    val scrollState = rememberScrollState()

    LaunchedEffect(authState.accessToken) {
        authState.accessToken?.let { vm.loadAll(it) }
    }

    // Add dialog
    var showAddDialog by remember { mutableStateOf(false) }
    var addCategory by remember { mutableStateOf("pain_character") }
    var addSubCategory by remember { mutableStateOf("pain_character") }
    var addLabel by remember { mutableStateOf("") }
    var addIconKey by remember { mutableStateOf<String?>(null) }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false; addLabel = ""; addIconKey = null; addSubCategory = addCategory },
            containerColor = Color(0xFF1E0A2E),
            titleContentColor = Color.White,
            textContentColor = AppTheme.BodyTextColor,
            confirmButton = {
                TextButton(
                    onClick = {
                        val token = authState.accessToken ?: return@TextButton
                        if (addLabel.isNotBlank()) {
                            vm.addNewToPool(token, addLabel.trim(), addSubCategory, addIconKey)
                            addLabel = ""
                            addIconKey = null
                            showAddDialog = false
                        }
                    },
                    enabled = addLabel.isNotBlank()
                ) { Text("Add", color = if (addLabel.isNotBlank()) AppTheme.AccentPurple else AppTheme.SubtleTextColor) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false; addLabel = ""; addIconKey = null }) {
                    Text("Cancel", color = AppTheme.SubtleTextColor)
                }
            },
            title = { Text(if (addCategory == "pain_character") "Add Pain Character" else "Add Accompanying Experience") },
            text = {
                Column {
                    OutlinedTextField(
                        value = addLabel,
                        onValueChange = { addLabel = it },
                        label = { Text("Symptom name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = AppTheme.AccentPurple,
                            focusedBorderColor = AppTheme.AccentPurple,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            focusedLabelColor = AppTheme.AccentPurple,
                            unfocusedLabelColor = AppTheme.SubtleTextColor
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Category", style = MaterialTheme.typography.bodySmall, color = AppTheme.SubtleTextColor)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val categories = if (addCategory == "pain_character")
                            listOf("pain_character")
                        else
                            listOf("accompanying", "Cognitive", "Digestive", "Emotional", "Motor", "Sensory", "Visual", "Other")
                        categories.forEach { cat ->
                            val displayName = when (cat) {
                                "pain_character" -> "Pain character"
                                "accompanying" -> "Accompanying"
                                else -> cat
                            }
                            val selected = addSubCategory == cat
                            AssistChip(
                                onClick = { addSubCategory = cat },
                                label = { Text(displayName, style = MaterialTheme.typography.labelSmall) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = if (selected) AppTheme.AccentPurple.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.06f),
                                    labelColor = if (selected) Color.White else AppTheme.SubtleTextColor
                                ),
                                border = AssistChipDefaults.assistChipBorder(
                                    enabled = true,
                                    borderColor = if (selected) AppTheme.AccentPurple.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.12f)
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Pick an icon", style = MaterialTheme.typography.bodySmall, color = AppTheme.SubtleTextColor)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SymptomIcons.PICKER_ICONS.forEach { picker ->
                            val isChosen = addIconKey == picker.key
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isChosen) AppTheme.AccentPurple.copy(alpha = 0.40f)
                                        else Color.White.copy(alpha = 0.08f)
                                    )
                                    .border(
                                        1.5.dp,
                                        if (isChosen) AppTheme.AccentPurple.copy(alpha = 0.7f)
                                        else Color.White.copy(alpha = 0.12f),
                                        CircleShape
                                    )
                                    .clickable { addIconKey = if (isChosen) null else picker.key },
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
        )
    }

    // Delete confirmation
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SupabaseDbService.UserSymptomRow?>(null) }

    if (showDeleteDialog && deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = Color(0xFF1E0A2E),
            titleContentColor = Color.White,
            textContentColor = AppTheme.BodyTextColor,
            confirmButton = {
                TextButton(onClick = {
                    val token = authState.accessToken ?: return@TextButton
                    vm.removeFromPool(token, deleteTarget!!.id)
                    showDeleteDialog = false; deleteTarget = null
                }) { Text("Delete", color = AppTheme.AccentPink) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = AppTheme.SubtleTextColor)
                }
            },
            title = { Text("Remove symptom?") },
            text = { Text("Remove \"${deleteTarget?.label}\"? This can't be undone.") }
        )
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            // Close bar
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Manage Symptoms", color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            // Hero
            HeroCard {
                Icon(Icons.Outlined.Psychology, contentDescription = null, tint = AppTheme.AccentPink, modifier = Modifier.size(40.dp))
                Text("Symptoms", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(
                    "Add, remove, or star frequent symptoms",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            // Pain Character section
            BaseCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Pain character", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    IconButton(onClick = { addCategory = "pain_character"; addSubCategory = "pain_character"; showAddDialog = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add", tint = AppTheme.AccentPurple, modifier = Modifier.size(20.dp))
                    }
                }
                if (painCharacter.isEmpty()) {
                    Text("No symptoms yet", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                } else {
                    painCharacter.forEach { symptom ->
                        val isFav = symptom.id in favoriteIds
                        val prefId = favorites.find { it.symptomId == symptom.id }?.id
                        SymptomRow(
                            label = symptom.label,
                            iconKey = symptom.iconKey,
                            isFavorite = isFav,
                            onToggleFavorite = {
                                val token = authState.accessToken ?: return@SymptomRow
                                if (isFav && prefId != null) vm.removeFromFavorites(token, prefId)
                                else vm.addToFavorites(token, symptom.id)
                            },
                            onDelete = { deleteTarget = symptom; showDeleteDialog = true }
                        )
                    }
                }
            }

            // Accompanying Experience section
            BaseCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Accompanying experience", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    IconButton(onClick = { addCategory = "accompanying"; addSubCategory = "accompanying"; showAddDialog = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add", tint = AppTheme.AccentPurple, modifier = Modifier.size(20.dp))
                    }
                }
                if (accompanying.isEmpty()) {
                    Text("No symptoms yet", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                } else {
                    val grouped = accompanying.groupBy { it.category ?: "Other" }.toSortedMap()
                    grouped.forEach { (cat, symptoms) ->
                        val displayCat = when (cat) {
                            "accompanying" -> "General"
                            else -> cat
                        }
                        Text(displayCat, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 8.dp))
                        symptoms.forEach { symptom ->
                            val isFav = symptom.id in favoriteIds
                            val prefId = favorites.find { it.symptomId == symptom.id }?.id
                            SymptomRow(
                                label = symptom.label,
                                iconKey = symptom.iconKey,
                                isFavorite = isFav,
                                onToggleFavorite = {
                                    val token = authState.accessToken ?: return@SymptomRow
                                    if (isFav && prefId != null) vm.removeFromFavorites(token, prefId)
                                    else vm.addToFavorites(token, symptom.id)
                                },
                                onDelete = { deleteTarget = symptom; showDeleteDialog = true }
                            )
                        }
                    }
                }
            }

            // Back
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                OutlinedButton(
                    onClick = { navController.popBackStack() },
                    border = BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple)
                ) { Text("Back") }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SymptomRow(
    label: String,
    iconKey: String? = null,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    val icon = SymptomIcons.forLabel(label, iconKey)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Round icon circle
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = AppTheme.SubtleTextColor, modifier = Modifier.size(20.dp))
            } else {
                Text(
                    label.take(2).uppercase(),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
        // Label
        Text(label, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        // Star
        IconButton(onClick = onToggleFavorite, modifier = Modifier.size(32.dp)) {
            Icon(
                if (isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                contentDescription = if (isFavorite) "Remove from frequent" else "Add to frequent",
                tint = if (isFavorite) Color(0xFFFFD54F) else AppTheme.SubtleTextColor,
                modifier = Modifier.size(18.dp)
            )
        }
        // Delete
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = AppTheme.AccentPink.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
        }
    }
}


