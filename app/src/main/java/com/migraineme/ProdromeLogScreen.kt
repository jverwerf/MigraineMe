package com.migraineme

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProdromeLogScreen(
    navController: NavController,
    vm: ProdromeViewModel,
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
    // Rebuilds the entire draft preserving everything except prodromes,
    // then re-adds the provided prodrome list
    fun rebuildDraftWithProdromes(prodromes: List<ProdromeDraft>) {
        val d = draft
        logVm.clearDraft()
        d.migraine?.let {
            logVm.setMigraineDraft(it.type, it.severity, it.beganAtIso, it.endedAtIso, it.note, symptoms = it.symptoms)
        }
        if (d.painLocations.isNotEmpty()) logVm.setPainLocationsDraft(d.painLocations)
        d.triggers.forEach { logVm.addTriggerDraft(it.type, it.startAtIso, it.note) }
        d.meds.forEach { m -> m.name?.let { logVm.addMedicineDraft(it, m.amount, m.notes, m.startAtIso) } }
        d.rels.forEach { logVm.addReliefDraft(it.type, it.notes, it.startAtIso, it.endAtIso, it.reliefScale) }
        d.locations.forEach { logVm.addLocationDraft(it.type, it.startAtIso, it.note) }
        d.activities.forEach { logVm.addActivityDraft(it.type, it.startAtIso, it.note) }
        d.missedActivities.forEach { logVm.addMissedActivityDraft(it.type, it.startAtIso, it.note) }
        prodromes.forEach { logVm.addProdromeDraft(it.type, it.startAtIso, it.note) }
    }

    // ── Time dialog: add new ──
    var showAddTimeDialog by remember { mutableStateOf(false) }
    var pendingLabel by remember { mutableStateOf<String?>(null) }

    // ── Time dialog: edit existing ──
    var showEditTimeDialog by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf<Int?>(null) }

    fun onProdromeTap(label: String) {
        val existingIdx = draft.prodromes.indexOfFirst { it.type == label }
        if (existingIdx >= 0) {
            // Deselect — remove from draft
            val updated = draft.prodromes.toMutableList().apply { removeAt(existingIdx) }
            rebuildDraftWithProdromes(updated)
        } else {
            // Show time picker to add
            pendingLabel = label
            showAddTimeDialog = true
        }
    }

    // Add dialog
    if (showAddTimeDialog && pendingLabel != null) {
        ProdromeTimeDialog(
            title = pendingLabel!!,
            initialIso = null,
            onDismiss = { showAddTimeDialog = false },
            onSkip = {
                val updated = draft.prodromes + ProdromeDraft(pendingLabel!!, startAtIso = null)
                rebuildDraftWithProdromes(updated)
                showAddTimeDialog = false
            },
            onConfirm = { iso ->
                val updated = draft.prodromes + ProdromeDraft(pendingLabel!!, startAtIso = iso)
                rebuildDraftWithProdromes(updated)
                showAddTimeDialog = false
            }
        )
    }

    // Edit dialog
    if (showEditTimeDialog && editIndex != null && editIndex!! in draft.prodromes.indices) {
        val editing = draft.prodromes[editIndex!!]
        ProdromeTimeDialog(
            title = editing.type,
            initialIso = editing.startAtIso,
            onDismiss = { showEditTimeDialog = false },
            onSkip = {
                val updated = draft.prodromes.toMutableList().apply {
                    set(editIndex!!, editing.copy(startAtIso = null))
                }
                rebuildDraftWithProdromes(updated)
                showEditTimeDialog = false
            },
            onConfirm = { iso ->
                val updated = draft.prodromes.toMutableList().apply {
                    set(editIndex!!, editing.copy(startAtIso = iso))
                }
                rebuildDraftWithProdromes(updated)
                showEditTimeDialog = false
            }
        )
    }

    // Frequent labels
    val frequentLabels = remember(frequent) { frequent.mapNotNull { it.prodrome?.label }.toSet() }
    val selectedLabels = remember(draft.prodromes) { draft.prodromes.map { it.type }.toSet() }

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
                    Text("Pain", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
                Text("Prodromes", color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, "Close", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            // ── Selected prodromes card (hero area) ──
            HeroCard {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .drawBehind { HubIcons.run { drawProdromeEye(Color(0xFFCE93D8)) } }
                )
                Text("Prodromes", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(
                    if (draft.prodromes.isEmpty()) "Select early warning signs"
                    else "${draft.prodromes.size} prodrome${if (draft.prodromes.size > 1) "s" else ""} selected",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                if (draft.prodromes.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    draft.prodromes.forEachIndexed { index, p ->
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
                                    p.type,
                                    color = AppTheme.BodyTextColor,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                                )
                                Text(
                                    if (p.startAtIso == null) "Same as migraine start"
                                    else formatProdromeTime(p.startAtIso),
                                    color = AppTheme.SubtleTextColor.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            // Edit time
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = "Edit time",
                                tint = AppTheme.AccentPurple.copy(alpha = 0.7f),
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable {
                                        editIndex = index
                                        showEditTimeDialog = true
                                    }
                            )
                            Spacer(Modifier.width(8.dp))
                            // Remove
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Remove",
                                tint = AppTheme.AccentPink.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable {
                                        val updated = draft.prodromes.toMutableList().apply { removeAt(index) }
                                        rebuildDraftWithProdromes(updated)
                                    }
                            )
                        }
                    }
                }
            }

            // Manage card
            BaseCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Prodromes", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Text("Manage →", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clickable { navController.navigate(Routes.MANAGE_PRODROMES) })
                }
            }

            // ── Single prodromes card: Frequent, then categories with dividers ──
            BaseCard {
                // Frequent section (at top of card)
                if (frequentLabels.isNotEmpty()) {
                    Text("Frequent", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        pool.filter { it.label in frequentLabels }.forEach { prod ->
                            ProdromeButton(prod.label, prod.label in selectedLabels, prod.iconKey) {
                                onProdromeTap(prod.label)
                            }
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                }

                // Category sections with dividers between
                val categoryEntries = grouped.entries.toList()
                categoryEntries.forEachIndexed { catIndex, (category, items) ->
                    val nonFreqItems = items.filter { it.label !in frequentLabels }
                    if (nonFreqItems.isNotEmpty()) {
                        Text(category, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            nonFreqItems.forEach { prod ->
                                ProdromeButton(prod.label, prod.label in selectedLabels, prod.iconKey) {
                                    onProdromeTap(prod.label)
                                }
                            }
                        }
                        // Divider between categories (not after last)
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

            // Nav buttons
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = { navController.popBackStack() },
                    border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple)
                ) { Text("Back") }
                Button(
                    onClick = { navController.navigate(Routes.TRIGGERS) },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                ) { Text("Next") }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/* ────────────────────────────────────────────────
 *  Time dialog — Skip (use migraine start) or pick
 * ──────────────────────────────────────────────── */

@Composable
private fun ProdromeTimeDialog(
    title: String,
    initialIso: String?,
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
    onConfirm: (String?) -> Unit
) {
    var pickedIso by remember { mutableStateOf(initialIso) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E0A2E),
        titleContentColor = Color.White,
        textContentColor = AppTheme.BodyTextColor,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "When did this start?",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Time: ${formatProdromeTime(pickedIso)}",
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )
                AppDateTimePicker(
                    label = "Select time",
                    onDateTimeSelected = { iso -> pickedIso = iso }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pickedIso) }) {
                Text("Set time", color = AppTheme.AccentPurple)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = AppTheme.SubtleTextColor)
                }
                TextButton(onClick = onSkip) {
                    Text("Skip", color = Color(0xFFCE93D8))
                }
            }
        }
    )
}

/* ────────────────────────────────────────────────
 *  Prodrome circle button
 * ──────────────────────────────────────────────── */

@Composable
private fun ProdromeButton(label: String, isSelected: Boolean, iconKey: String? = null, onClick: () -> Unit) {
    val circleColor = if (isSelected) Color(0xFFCE93D8).copy(alpha = 0.40f) else Color.White.copy(alpha = 0.08f)
    val borderColor = if (isSelected) Color(0xFFCE93D8).copy(alpha = 0.7f) else Color.White.copy(alpha = 0.12f)
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
                (iconKey ?: label.take(2)).uppercase(),
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

private fun formatProdromeTime(iso: String?): String {
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
