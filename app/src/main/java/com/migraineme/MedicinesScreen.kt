package com.migraineme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/* ────────────────────────────────────────────────
 *  Relief scale enum (mirrors DB CHECK constraint)
 * ──────────────────────────────────────────────── */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MedicinesScreen(
    navController: NavController,
    vm: MedicineViewModel,
    authVm: AuthViewModel,
    logVm: LogViewModel,
    onClose: () -> Unit = {},
    quickLogMode: Boolean = false,
    onSave: (() -> Unit)? = null,
    linkedMigraineId: String? = null,
    onMigraineSelect: ((String?) -> Unit)? = null
) {
    val pool by vm.pool.collectAsState()
    val frequent by vm.frequent.collectAsState()
    val authState by authVm.state.collectAsState()
    val draft by logVm.draft.collectAsState()
    val scrollState = rememberScrollState()

    LaunchedEffect(authState.accessToken) {
        authState.accessToken?.let { vm.loadAll(it) }
    }

    // ── Rebuild helpers ──
    fun rebuildDraftWithMeds(meds: List<MedicineDraft>) {
        logVm.replaceMedicines(meds)
    }

    // ── Add dialog state ──
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingLabel by remember { mutableStateOf<String?>(null) }

    // ── Edit dialog state ──
    var showEditDialog by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf<Int?>(null) }

    fun onMedicineTap(label: String) {
        val existingIdx = draft.meds.indexOfFirst { it.name == label }
        if (existingIdx >= 0) {
            // Deselect
            val updated = draft.meds.toMutableList().apply { removeAt(existingIdx) }
            rebuildDraftWithMeds(updated)
        } else {
            pendingLabel = label
            showAddDialog = true
        }
    }

    // Add dialog
    if (showAddDialog && pendingLabel != null) {
        MedicineAddDialog(
            title = pendingLabel!!,
            onDismiss = { showAddDialog = false },
            onSkip = {
                val updated = draft.meds + MedicineDraft(name = pendingLabel!!, startAtIso = null, reliefScale = "NONE")
                rebuildDraftWithMeds(updated)
                showAddDialog = false
            },
            onConfirm = { amount, iso, relief ->
                val updated = draft.meds + MedicineDraft(name = pendingLabel!!, amount = amount.ifBlank { null }, startAtIso = iso, reliefScale = relief)
                rebuildDraftWithMeds(updated)
                showAddDialog = false
            }
        )
    }

    // Edit dialog
    if (showEditDialog && editIndex != null && editIndex!! in draft.meds.indices) {
        val editing = draft.meds[editIndex!!]
        MedicineEditDialog(
            title = editing.name ?: "",
            initialAmount = editing.amount ?: "",
            initialIso = editing.startAtIso,
            initialRelief = editing.reliefScale ?: "NONE",
            onDismiss = { showEditDialog = false },
            onConfirm = { amount, iso, relief ->
                val updated = draft.meds.toMutableList().apply {
                    set(editIndex!!, editing.copy(amount = amount.ifBlank { null }, startAtIso = iso, reliefScale = relief))
                }
                rebuildDraftWithMeds(updated)
                showEditDialog = false
            }
        )
    }

    // Frequent labels
    val frequentLabels = remember(frequent) { frequent.mapNotNull { it.medicine?.label }.toSet() }
    val selectedLabels = remember(draft.meds) { draft.meds.mapNotNull { it.name }.toSet() }

    // Group pool by category
    val grouped = remember(pool) {
        pool.groupBy { it.category ?: "Other" }.toSortedMap()
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 60.dp) {

            // Top bar: ← Previous | Title | X Close
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (!quickLogMode) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Triggers", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                Text("Medicines", color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                if (!quickLogMode) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                } else {
                    Spacer(Modifier.size(28.dp))
                }
            }

            // ── HeroCard: icon + title + subtitle + selected list ──
            HeroCard {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .drawBehind { HubIcons.run { drawMedicinePill(Color(0xFF4FC3F7)) } }
                )
                Text("Medicines", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(
                    if (draft.meds.isEmpty()) "Add medicines you've taken"
                    else "${draft.meds.size} medicine${if (draft.meds.size > 1) "s" else ""} added",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                if (draft.meds.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    draft.meds.forEachIndexed { index, m ->
                        val relief = ReliefScale.fromString(m.reliefScale)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    m.name ?: "",
                                    color = AppTheme.BodyTextColor,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        if (m.startAtIso == null) "Same as migraine start"
                                        else formatMedTime(m.startAtIso),
                                        color = AppTheme.SubtleTextColor.copy(alpha = 0.7f),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    if (!m.amount.isNullOrBlank()) {
                                        Text(
                                            "• ${m.amount}",
                                            color = AppTheme.SubtleTextColor.copy(alpha = 0.7f),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                                // Relief scale chip
                                Text(
                                    "Relief: ${relief.display}",
                                    color = relief.color,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = "Edit",
                                tint = AppTheme.AccentPurple.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable {
                                        editIndex = index
                                        showEditDialog = true
                                    }
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Remove",
                                tint = AppTheme.AccentPink.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable {
                                        val updated = draft.meds.toMutableList().apply { removeAt(index) }
                                        rebuildDraftWithMeds(updated)
                                    }
                            )
                        }
                    }
                }
            }

            // Manage card (own card)
            BaseCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Medicines", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Text("Manage →", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clickable { navController.navigate(Routes.MANAGE_MEDICINES) })
                }
            }

            if (quickLogMode && onMigraineSelect != null) {
                val firstIso = draft.meds.firstOrNull()?.startAtIso
                MigrainePickerCard(itemStartAtIso = firstIso, authVm = authVm, selectedMigraineId = linkedMigraineId, onSelect = onMigraineSelect)
            }

            // ── Single medicines card: Frequent → divider → categories ──
            BaseCard {
                // Frequent section
                if (frequentLabels.isNotEmpty()) {
                    Text("Frequent", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        pool.filter { it.label in frequentLabels }.forEach { med ->
                            MedicineButton(med.label, med.label in selectedLabels) {
                                onMedicineTap(med.label)
                            }
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                }

                // Category sections with dividers
                val categoryEntries = grouped.entries.toList()
                categoryEntries.forEachIndexed { catIndex, (category, items) ->
                    val nonFreqItems = items.filter { it.label !in frequentLabels }
                    if (nonFreqItems.isNotEmpty()) {
                        Text(category, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            nonFreqItems.forEach { med ->
                                MedicineButton(med.label, med.label in selectedLabels) {
                                    onMedicineTap(med.label)
                                }
                            }
                        }
                        val hasMore = categoryEntries.drop(catIndex + 1).any { (_, its) -> its.any { it.label !in frequentLabels } }
                        if (hasMore) {
                            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                        }
                    }
                }

                if (pool.isEmpty()) {
                    Text("Loading…", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Navigation
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = { navController.popBackStack() },
                    border = BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple)
                ) { Text(if (quickLogMode) "Cancel" else "Back") }
                Button(
                    onClick = { if (quickLogMode) onSave?.invoke() else navController.navigate(Routes.RELIEFS) },
                    enabled = !quickLogMode || draft.meds.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                ) { Text(if (quickLogMode) "Save" else "Next") }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/* ────────────────────────────────────────────────
 *  Add dialog: amount + time + relief scale
 * ──────────────────────────────────────────────── */

@Composable
private fun MedicineAddDialog(
    title: String,
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
    onConfirm: (amount: String, iso: String?, relief: String) -> Unit
) {
    var amount by remember { mutableStateOf("") }
    var pickedIso by remember { mutableStateOf<String?>(null) }
    var selectedRelief by remember { mutableStateOf(ReliefScale.NONE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E0A2E),
        titleContentColor = Color.White,
        textContentColor = AppTheme.BodyTextColor,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Amount
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount (e.g. 50mg, 2 tablets)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.AccentPurple,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedLabelColor = AppTheme.AccentPurple,
                        unfocusedLabelColor = AppTheme.SubtleTextColor,
                        cursorColor = Color.White,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                // Time
                Text("When did you take it?", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                Text("Time: ${formatMedTime(pickedIso)}", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
                AppDateTimePicker(label = "Select time", onDateTimeSelected = { iso -> pickedIso = iso })

                // Relief scale
                Text("How much relief?", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReliefScale.entries.forEach { scale ->
                        FilterChip(
                            selected = selectedRelief == scale,
                            onClick = { selectedRelief = scale },
                            label = { Text(scale.display, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = scale.color.copy(alpha = 0.3f),
                                selectedLabelColor = Color.White,
                                containerColor = Color.White.copy(alpha = 0.06f),
                                labelColor = AppTheme.SubtleTextColor
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selectedRelief == scale,
                                borderColor = Color.White.copy(alpha = 0.12f),
                                selectedBorderColor = scale.color.copy(alpha = 0.6f)
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(amount.trim(), pickedIso, selectedRelief.name) }) {
                Text("Add", color = AppTheme.AccentPurple)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = AppTheme.SubtleTextColor)
                }
                TextButton(onClick = onSkip) {
                    Text("Skip", color = Color(0xFF4FC3F7))
                }
            }
        }
    )
}

/* ────────────────────────────────────────────────
 *  Edit dialog: amount + time + relief scale
 * ──────────────────────────────────────────────── */

@Composable
private fun MedicineEditDialog(
    title: String,
    initialAmount: String,
    initialIso: String?,
    initialRelief: String,
    onDismiss: () -> Unit,
    onConfirm: (amount: String, iso: String?, relief: String) -> Unit
) {
    var amount by remember { mutableStateOf(initialAmount) }
    var pickedIso by remember { mutableStateOf(initialIso) }
    var selectedRelief by remember { mutableStateOf(ReliefScale.fromString(initialRelief)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E0A2E),
        titleContentColor = Color.White,
        textContentColor = AppTheme.BodyTextColor,
        title = { Text("Edit $title") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.AccentPurple,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedLabelColor = AppTheme.AccentPurple,
                        unfocusedLabelColor = AppTheme.SubtleTextColor,
                        cursorColor = Color.White,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                Text("Time: ${formatMedTime(pickedIso)}", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
                AppDateTimePicker(label = "Select time", onDateTimeSelected = { iso -> pickedIso = iso })

                Text("How much relief?", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReliefScale.entries.forEach { scale ->
                        FilterChip(
                            selected = selectedRelief == scale,
                            onClick = { selectedRelief = scale },
                            label = { Text(scale.display, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = scale.color.copy(alpha = 0.3f),
                                selectedLabelColor = Color.White,
                                containerColor = Color.White.copy(alpha = 0.06f),
                                labelColor = AppTheme.SubtleTextColor
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selectedRelief == scale,
                                borderColor = Color.White.copy(alpha = 0.12f),
                                selectedBorderColor = scale.color.copy(alpha = 0.6f)
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(amount.trim(), pickedIso, selectedRelief.name) }) {
                Text("Save", color = AppTheme.AccentPurple)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = AppTheme.SubtleTextColor)
            }
        }
    )
}

/* ────────────────────────────────────────────────
 *  Medicine circle button
 * ──────────────────────────────────────────────── */

@Composable
private fun MedicineButton(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val circleColor = if (isSelected) Color(0xFF4FC3F7).copy(alpha = 0.40f) else Color.White.copy(alpha = 0.08f)
    val borderColor = if (isSelected) Color(0xFF4FC3F7).copy(alpha = 0.7f) else Color.White.copy(alpha = 0.12f)
    val iconTint = if (isSelected) Color.White else AppTheme.SubtleTextColor
    val textColor = if (isSelected) Color.White else AppTheme.BodyTextColor

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(circleColor)
                .border(width = 1.5.dp, color = borderColor, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label.take(2).uppercase(),
                color = iconTint,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = textColor,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/* ────────────────────────────────────────────────
 *  Format helper
 * ──────────────────────────────────────────────── */

private fun formatMedTime(iso: String?): String {
    if (iso.isNullOrBlank()) return "Not set"
    return try {
        val odt = runCatching { OffsetDateTime.parse(iso) }.getOrNull()
        val ldt = odt?.toLocalDateTime()
            ?: runCatching { LocalDateTime.parse(iso) }.getOrNull()
            ?: runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull()
            ?: return "Not set"
        ldt.format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
    } catch (_: Exception) {
        "Not set"
    }
}

