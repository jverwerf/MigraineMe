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
    fun rebuildDraftWithRels(rels: List<ReliefDraft>) {
        logVm.replaceReliefs(rels)
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
            takenOnly = DoseUnits.isTakenOnlyRelief(pendingLabel),
            onDismiss = { showAddDialog = false },
            onSkip = {
                val updated = draft.rels + ReliefDraft(type = pendingLabel!!, startAtIso = null, endAtIso = null, reliefScale = "NONE")
                rebuildDraftWithRels(updated)
                showAddDialog = false
            },
            onConfirm = { startIso, endIso, minutes, relief, seScale, seNotes ->
                val updated = draft.rels + ReliefDraft(
                    type = pendingLabel!!,
                    startAtIso = startIso,
                    endAtIso = endIso,
                    durationMinutes = minutes,
                    reliefScale = relief,
                    sideEffectScale = seScale,
                    sideEffectNotes = seNotes.ifBlank { null }
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
            takenOnly = DoseUnits.isTakenOnlyRelief(editing.type),
            initialStartIso = editing.startAtIso,
            initialEndIso = editing.endAtIso,
            initialDurationMinutes = editing.durationMinutes,
            initialRelief = editing.reliefScale ?: "NONE",
            initialSideEffectScale = editing.sideEffectScale ?: "NONE",
            initialSideEffectNotes = editing.sideEffectNotes ?: "",
            onDismiss = { showEditDialog = false },
            onConfirm = { startIso, endIso, minutes, relief, seScale, seNotes ->
                val updated = draft.rels.toMutableList().apply {
                    set(editIndex!!, editing.copy(
                        startAtIso = startIso,
                        endAtIso = endIso,
                        durationMinutes = minutes,
                        reliefScale = relief,
                        sideEffectScale = seScale,
                        sideEffectNotes = seNotes.ifBlank { null }
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
    // Wizard search — live-filters the pool grid below
    var wizardSearch by remember { mutableStateOf("") }
    val searchPool = remember(pool, wizardSearch) {
        if (wizardSearch.isBlank()) pool
        else pool.filter { it.label.contains(wizardSearch.trim(), ignoreCase = true) }
    }

    val grouped = remember(searchPool) {
        searchPool.groupBy { it.category ?: "Other" }.toSortedMap()
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            // Top bar: ← Previous | Title | X Close
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (!quickLogMode) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Back"), tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t("Medicines"), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Back"), tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (!quickLogMode) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = t("Close"), tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                } else {
                    Spacer(Modifier.size(28.dp))
                }
            }

            // ── HeroCard ──
            HeroCard {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .drawBehind { HubIcons.run { drawReliefLeaf(Color(0xFF81C784)) } }
                )
                Text(t("Reliefs"), color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(
                    if (draft.rels.isEmpty()) t("Add reliefs that helped")
                    else (if (draft.rels.size == 1) t("1 relief added") else t("%s reliefs added", draft.rels.size)),
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
                                        if (r.startAtIso == null) t("Same as migraine start")
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
                                                t("• %smin", mins),
                                                color = AppTheme.SubtleTextColor.copy(alpha = 0.7f),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    } else if (r.endAtIso == null && r.durationMinutes != null) {
                                        Text(
                                            t("• %smin", r.durationMinutes),
                                            color = AppTheme.SubtleTextColor.copy(alpha = 0.7f),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                                // Relief scale
                                Text(
                                    t("Relief: %s", relief.display),
                                    color = relief.color,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = t("Edit"),
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
                                contentDescription = t("Remove"),
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

            if (!quickLogMode) {
                WizardStepNav(onBack = { navController.popBackStack() }, onSkip = { navController.navigate(Routes.LOCATIONS) })
            }

            // Manage card (own card)
            BaseCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Reliefs"), color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Text(t("Manage →"), color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clickable { navController.navigate(Routes.MANAGE_RELIEFS) })
                }
            }

            if (quickLogMode && onMigraineSelect != null) {
                val firstIso = draft.rels.firstOrNull()?.startAtIso
                MigrainePickerCard(itemStartAtIso = firstIso, authVm = authVm, selectedMigraineId = linkedMigraineId, onSelect = onMigraineSelect)
            }

            WizardSearchField(query = wizardSearch, onQueryChange = { wizardSearch = it }, accent = Color(0xFF81C784))

            // ── Single reliefs card: Frequent → divider → categories ──
            BaseCard {
                if (frequentLabels.isNotEmpty()) {
                    Text(t("Frequent"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        searchPool.filter { it.label in frequentLabels }.forEach { rel ->
                            ReliefButton(rel.label, rel.label in selectedLabels, rel.iconKey) {
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
                                ReliefButton(rel.label, rel.label in selectedLabels, rel.iconKey) {
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
                    Text(t("Loading…"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
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
                ) { Text(if (quickLogMode) t("Cancel") else t("Back")) }
                Button(
                    onClick = { if (quickLogMode) onSave?.invoke() else navController.navigate(Routes.LOCATIONS) },
                    enabled = !quickLogMode || draft.rels.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                ) { Text(if (quickLogMode) t("Save") else t("Next")) }
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
    takenOnly: Boolean,
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
    onConfirm: (startIso: String?, endIso: String?, durationMinutes: Int?, relief: String, sideEffectScale: String, sideEffectNotes: String) -> Unit
) {
    var startIso by remember { mutableStateOf<String?>(null) }
    var endIso by remember { mutableStateOf<String?>(null) }
    var durationText by remember { mutableStateOf("") }
    var selectedRelief by remember { mutableStateOf(ReliefScale.NONE) }
    var sideEffectScale by remember { mutableStateOf("NONE") }
    var sideEffectNotes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E0A2E),
        titleContentColor = Color.White,
        textContentColor = AppTheme.BodyTextColor,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Start time
                Text(t("When did you start?"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                Text(t("Start: %s", formatReliefTime(startIso)), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
                AppDateTimePicker(label = t("Select start time"), onDateTimeSelected = { iso -> startIso = iso })

                // Duration — hidden entirely for taken-only reliefs
                if (!takenOnly) {
                    // Minutes entry (optional)
                    Text(t("How long? (optional)"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    ReliefMinutesField(
                        value = durationText,
                        onValueChange = { new ->
                            durationText = new
                            if (new.isNotEmpty()) endIso = null
                        }
                    )

                    // End time
                    Text(t("When did you stop? (optional)"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    Text(t("End: %s", formatReliefTime(endIso)), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
                    AppDateTimePicker(label = t("Select end time"), onDateTimeSelected = { iso ->
                        endIso = iso
                        if (iso != null) durationText = ""
                    })
                }

                // Relief scale
                Text(t("How much relief?"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReliefScale.entries.forEach { scale ->
                        FilterChip(
                            selected = selectedRelief == scale,
                            onClick = { selectedRelief = scale },
                            label = { Text(t(scale.display), style = MaterialTheme.typography.labelSmall) },
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

                SideEffectChips(
                    sideEffectScale = sideEffectScale,
                    onScaleChange = { sideEffectScale = it },
                    sideEffectNotes = sideEffectNotes,
                    onNotesChange = { sideEffectNotes = it }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val minutes = if (takenOnly) null else durationText.toIntOrNull()?.takeIf { it > 0 }
                onConfirm(startIso, if (takenOnly) null else endIso, minutes, selectedRelief.name, sideEffectScale, sideEffectNotes.trim())
            }) {
                Text(t("Add"), color = AppTheme.AccentPurple)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) {
                    Text(t("Cancel"), color = AppTheme.SubtleTextColor)
                }
                TextButton(onClick = onSkip) {
                    Text(t("Skip"), color = Color(0xFF81C784))
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
    takenOnly: Boolean,
    initialStartIso: String?,
    initialEndIso: String?,
    initialDurationMinutes: Int? = null,
    initialRelief: String,
    initialSideEffectScale: String = "NONE",
    initialSideEffectNotes: String = "",
    onDismiss: () -> Unit,
    onConfirm: (startIso: String?, endIso: String?, durationMinutes: Int?, relief: String, sideEffectScale: String, sideEffectNotes: String) -> Unit
) {
    var startIso by remember { mutableStateOf(initialStartIso) }
    var endIso by remember { mutableStateOf(initialEndIso) }
    var durationText by remember { mutableStateOf(initialDurationMinutes?.toString() ?: "") }
    var selectedRelief by remember { mutableStateOf(ReliefScale.fromString(initialRelief)) }
    var sideEffectScale by remember { mutableStateOf(initialSideEffectScale) }
    var sideEffectNotes by remember { mutableStateOf(initialSideEffectNotes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E0A2E),
        titleContentColor = Color.White,
        textContentColor = AppTheme.BodyTextColor,
        title = { Text(t("Edit %s", title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t("Start: %s", formatReliefTime(startIso)), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
                AppDateTimePicker(label = t("Select start time"), onDateTimeSelected = { iso -> startIso = iso })

                if (!takenOnly) {
                    Text(t("How long? (optional)"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    ReliefMinutesField(
                        value = durationText,
                        onValueChange = { new ->
                            durationText = new
                            if (new.isNotEmpty()) endIso = null
                        }
                    )

                    Text(t("End: %s", formatReliefTime(endIso)), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
                    AppDateTimePicker(label = t("Select end time"), onDateTimeSelected = { iso ->
                        endIso = iso
                        if (iso != null) durationText = ""
                    })
                }

                Text(t("How much relief?"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReliefScale.entries.forEach { scale ->
                        FilterChip(
                            selected = selectedRelief == scale,
                            onClick = { selectedRelief = scale },
                            label = { Text(t(scale.display), style = MaterialTheme.typography.labelSmall) },
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

                SideEffectChips(
                    sideEffectScale = sideEffectScale,
                    onScaleChange = { sideEffectScale = it },
                    sideEffectNotes = sideEffectNotes,
                    onNotesChange = { sideEffectNotes = it }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val minutes = if (takenOnly) null else durationText.toIntOrNull()?.takeIf { it > 0 }
                onConfirm(startIso, if (takenOnly) null else endIso, minutes, selectedRelief.name, sideEffectScale, sideEffectNotes.trim())
            }) {
                Text(t("Save"), color = AppTheme.AccentPurple)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(t("Cancel"), color = AppTheme.SubtleTextColor)
            }
        }
    )
}

/** Digits-only minutes entry with a "min" suffix, shared by the two dialogs. */
@Composable
private fun ReliefMinutesField(value: String, onValueChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { new -> if (new.isEmpty() || new.all { it.isDigit() }) onValueChange(new) },
            label = { Text(t("Minutes")) },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
            ),
            modifier = Modifier.weight(1f),
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
        Text("min", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
    }
}

/* ────────────────────────────────────────────────
 *  Relief circle button
 * ──────────────────────────────────────────────── */

@Composable
private fun ReliefButton(label: String, isSelected: Boolean, iconKey: String? = null, onClick: () -> Unit) {
    val circleColor = if (isSelected) Color(0xFF81C784).copy(alpha = 0.40f) else Color.White.copy(alpha = 0.08f)
    val borderColor = if (isSelected) Color(0xFF81C784).copy(alpha = 0.7f) else Color.White.copy(alpha = 0.12f)
    val iconTint = if (isSelected) Color.White else AppTheme.SubtleTextColor
    val icon = ReliefIcons.forLabel(label, iconKey)
    val brainyId = ReliefIcons.drawableForLabel(label, iconKey)
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
            if (brainyId != null || icon != null) {
                LogIconImage(drawableId = brainyId, fallback = icon, size = if (brainyId != null) 34.dp else 24.dp, tint = iconTint)
            } else {
                Text(label.take(2).uppercase(), color = iconTint, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(t(label),
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


