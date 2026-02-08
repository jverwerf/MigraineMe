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
 *  Relief scale enum (per-log value, not a pool characteristic)
 * ──────────────────────────────────────────────── */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReliefsScreen(
    navController: NavController,
    vm: ReliefViewModel,
    authVm: AuthViewModel,
    logVm: LogViewModel,
    onClose: () -> Unit = {}
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
    fun rebuildDraftWithRels(rels: List<ReliefDraft>) {
        val d = draft
        logVm.clearDraft()
        d.migraine?.let {
            logVm.setMigraineDraft(it.type, it.severity, it.beganAtIso, it.endedAtIso, it.note, symptoms = it.symptoms)
        }
        if (d.painLocations.isNotEmpty()) logVm.setPainLocationsDraft(d.painLocations)
        d.prodromes.forEach { logVm.addProdromeDraft(it.type, it.startAtIso, it.note) }
        d.triggers.forEach { logVm.addTriggerDraft(it.type, it.startAtIso, it.note) }
        d.meds.forEach { m -> logVm.addMedicineDraft(m.name ?: "", m.amount, m.notes, m.startAtIso, m.reliefScale) }
        rels.forEach { logVm.addReliefDraft(it.type, it.notes, it.startAtIso, it.endAtIso, it.reliefScale) }
        d.locations.forEach { logVm.addLocationDraft(it.type, it.startAtIso, it.note) }
        d.activities.forEach { logVm.addActivityDraft(it.type, it.startAtIso, it.note) }
        d.missedActivities.forEach { logVm.addMissedActivityDraft(it.type, it.startAtIso, it.note) }
    }

    // ── Add dialog state ──
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingLabel by remember { mutableStateOf<String?>(null) }

    // ── Edit dialog state ──
    var showEditDialog by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf<Int?>(null) }

    fun onReliefTap(label: String) {
        val existingIdx = draft.rels.indexOfFirst { it.type == label }
        if (existingIdx >= 0) {
            val updated = draft.rels.toMutableList().apply { removeAt(existingIdx) }
            rebuildDraftWithRels(updated)
        } else {
            pendingLabel = label
            showAddDialog = true
        }
    }

    // Add dialog
    if (showAddDialog && pendingLabel != null) {
        ReliefAddDialog(
            title = pendingLabel!!,
            onDismiss = { showAddDialog = false },
            onSkip = {
                val updated = draft.rels + ReliefDraft(type = pendingLabel!!, startAtIso = null, endAtIso = null, reliefScale = "NONE")
                rebuildDraftWithRels(updated)
                showAddDialog = false
            },
            onConfirm = { startIso, endIso, relief ->
                val updated = draft.rels + ReliefDraft(
                    type = pendingLabel!!,
                    startAtIso = startIso,
                    endAtIso = endIso,
                    reliefScale = relief
                )
                rebuildDraftWithRels(updated)
                showAddDialog = false
            }
        )
    }

    // Edit dialog
    if (showEditDialog && editIndex != null && editIndex!! in draft.rels.indices) {
        val editing = draft.rels[editIndex!!]
        ReliefEditDialog(
            title = editing.type,
            initialStartIso = editing.startAtIso,
            initialEndIso = editing.endAtIso,
            initialRelief = editing.reliefScale ?: "NONE",
            onDismiss = { showEditDialog = false },
            onConfirm = { startIso, endIso, relief ->
                val updated = draft.rels.toMutableList().apply {
                    set(editIndex!!, editing.copy(
                        startAtIso = startIso,
                        endAtIso = endIso,
                        reliefScale = relief
                    ))
                }
                rebuildDraftWithRels(updated)
                showEditDialog = false
            }
        )
    }

    // Frequent labels
    val frequentLabels = remember(frequent) { frequent.mapNotNull { it.relief?.label }.toSet() }
    val selectedLabels = remember(draft.rels) { draft.rels.map { it.type }.toSet() }

    // Group pool by category
    val grouped = remember(pool) {
        pool.groupBy { it.category ?: "Other" }.toSortedMap()
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 60.dp) {

            // Top bar: ← Previous | Title | X Close
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Medicines", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
                Text("Reliefs", color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            // ── HeroCard ──
            HeroCard {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .drawBehind { HubIcons.run { drawReliefLeaf(Color(0xFF81C784)) } }
                )
                Text("Reliefs", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(
                    if (draft.rels.isEmpty()) "Add reliefs that helped"
                    else "${draft.rels.size} relief${if (draft.rels.size > 1) "s" else ""} added",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                if (draft.rels.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    draft.rels.forEachIndexed { index, r ->
                        val relief = ReliefScale.fromString(r.reliefScale)
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
                                    r.type,
                                    color = AppTheme.BodyTextColor,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        if (r.startAtIso == null) "Same as migraine start"
                                        else formatReliefTime(r.startAtIso),
                                        color = AppTheme.SubtleTextColor.copy(alpha = 0.7f),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    if (r.endAtIso != null) {
                                        Text(
                                            "→ ${formatReliefTime(r.endAtIso)}",
                                            color = AppTheme.SubtleTextColor.copy(alpha = 0.7f),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                    if (r.startAtIso != null && r.endAtIso != null) {
                                        deriveDurationMinutes(r.startAtIso, r.endAtIso)?.let { mins ->
                                            Text(
                                                "• ${mins}min",
                                                color = AppTheme.SubtleTextColor.copy(alpha = 0.7f),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                                // Relief scale
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
                                        val updated = draft.rels.toMutableList().apply { removeAt(index) }
                                        rebuildDraftWithRels(updated)
                                    }
                            )
                        }
                    }
                }
            }

            // Manage card (own card)
            BaseCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Reliefs", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Text("Manage →", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clickable { navController.navigate(Routes.MANAGE_RELIEFS) })
                }
            }

            // ── Single reliefs card: Frequent → divider → categories ──
            BaseCard {
                if (frequentLabels.isNotEmpty()) {
                    Text("Frequent", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        pool.filter { it.label in frequentLabels }.forEach { rel ->
                            ReliefButton(rel.label, rel.label in selectedLabels) {
                                onReliefTap(rel.label)
                            }
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                }

                val categoryEntries = grouped.entries.toList()
                categoryEntries.forEachIndexed { catIndex, (category, items) ->
                    val nonFreqItems = items.filter { it.label !in frequentLabels }
                    if (nonFreqItems.isNotEmpty()) {
                        Text(category, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            nonFreqItems.forEach { rel ->
                                ReliefButton(rel.label, rel.label in selectedLabels) {
                                    onReliefTap(rel.label)
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
                ) { Text("Back") }
                Button(
                    onClick = { navController.navigate(Routes.LOCATIONS) },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                ) { Text("Next") }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/* ────────────────────────────────────────────────
 *  Add dialog: duration + start time + end time
 * ──────────────────────────────────────────────── */

@Composable
private fun ReliefAddDialog(
    title: String,
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
    onConfirm: (startIso: String?, endIso: String?, relief: String) -> Unit
) {
    var startIso by remember { mutableStateOf<String?>(null) }
    var endIso by remember { mutableStateOf<String?>(null) }
    var selectedRelief by remember { mutableStateOf(ReliefScale.NONE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E0A2E),
        titleContentColor = Color.White,
        textContentColor = AppTheme.BodyTextColor,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Start time
                Text("When did you start?", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                Text("Start: ${formatReliefTime(startIso)}", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
                AppDateTimePicker(label = "Select start time", onDateTimeSelected = { iso -> startIso = iso })

                // End time
                Text("When did you stop? (optional)", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                Text("End: ${formatReliefTime(endIso)}", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
                AppDateTimePicker(label = "Select end time", onDateTimeSelected = { iso -> endIso = iso })

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
            TextButton(onClick = { onConfirm(startIso, endIso, selectedRelief.name) }) {
                Text("Add", color = AppTheme.AccentPurple)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = AppTheme.SubtleTextColor)
                }
                TextButton(onClick = onSkip) {
                    Text("Skip", color = Color(0xFF81C784))
                }
            }
        }
    )
}

/* ────────────────────────────────────────────────
 *  Edit dialog: duration + start + end
 * ──────────────────────────────────────────────── */

@Composable
private fun ReliefEditDialog(
    title: String,
    initialStartIso: String?,
    initialEndIso: String?,
    initialRelief: String,
    onDismiss: () -> Unit,
    onConfirm: (startIso: String?, endIso: String?, relief: String) -> Unit
) {
    var startIso by remember { mutableStateOf(initialStartIso) }
    var endIso by remember { mutableStateOf(initialEndIso) }
    var selectedRelief by remember { mutableStateOf(ReliefScale.fromString(initialRelief)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E0A2E),
        titleContentColor = Color.White,
        textContentColor = AppTheme.BodyTextColor,
        title = { Text("Edit $title") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Start: ${formatReliefTime(startIso)}", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
                AppDateTimePicker(label = "Select start time", onDateTimeSelected = { iso -> startIso = iso })

                Text("End: ${formatReliefTime(endIso)}", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
                AppDateTimePicker(label = "Select end time", onDateTimeSelected = { iso -> endIso = iso })

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
            TextButton(onClick = { onConfirm(startIso, endIso, selectedRelief.name) }) {
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
 *  Relief circle button
 * ──────────────────────────────────────────────── */

@Composable
private fun ReliefButton(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val circleColor = if (isSelected) Color(0xFF81C784).copy(alpha = 0.40f) else Color.White.copy(alpha = 0.08f)
    val borderColor = if (isSelected) Color(0xFF81C784).copy(alpha = 0.7f) else Color.White.copy(alpha = 0.12f)
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

private fun formatReliefTime(iso: String?): String {
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

/** Derive duration in minutes from two ISO timestamps (client-side preview). */
private fun deriveDurationMinutes(startIso: String, endIso: String): Int? {
    return try {
        fun parseInstant(s: String): Instant? =
            runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()
                ?: runCatching { Instant.parse(s) }.getOrNull()
                ?: runCatching { LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull()

        val s = parseInstant(startIso) ?: return null
        val e = parseInstant(endIso) ?: return null
        val mins = java.time.Duration.between(s, e).toMinutes().toInt()
        if (mins >= 0) mins else null
    } catch (_: Exception) { null }
}
