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
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TriggersScreen(
    navController: NavController,
    vm: TriggerViewModel,
    authVm: AuthViewModel,
    logVm: LogViewModel,
    onClose: () -> Unit = {},
    quickLogMode: Boolean = false,
    onSave: (() -> Unit)? = null,
    linkedMigraineId: String? = null,
    onMigraineSelect: ((String?) -> Unit)? = null
) {
    val rawPool by vm.pool.collectAsState()
    val frequent by vm.frequent.collectAsState()

    // Show all triggers in the logging wizard (including NONE prediction)
    // so system-detected anomalies are surfaced. Collapse display_group items
    // into a single entry so user sees "Poor sleep" instead of 8 individual sleep metrics
    val pool = remember(rawPool) {
        val visible = rawPool
        val standalone = visible.filter { it.displayGroup == null }
        val grouped = visible.filter { it.displayGroup != null }
            .groupBy { it.displayGroup!! }
            .map { (groupName, members) ->
                val first = members.first()
                // Use highest severity among members as group severity
                val severityOrder = listOf("HIGH", "MILD", "LOW", "NONE")
                val bestSeverity = members.map { it.predictionValue?.uppercase() ?: "NONE" }
                    .minByOrNull { sev -> severityOrder.indexOf(sev) } ?: "NONE"
                SupabaseDbService.UserTriggerRow(
                    id = "group_${groupName}",
                    label = groupName,
                    iconKey = first.iconKey,
                    category = first.category,
                    predictionValue = bestSeverity,
                    displayGroup = groupName
                )
            }
        standalone + grouped
    }
    val authState by authVm.state.collectAsState()
    val draft by logVm.draft.collectAsState()
    val scrollState = rememberScrollState()

    LaunchedEffect(authState.accessToken, draft.migraine?.beganAtIso) {
        authState.accessToken?.let { token ->
            vm.loadAll(token)
            vm.loadRecent(token, draft.migraine?.beganAtIso)
        }
    }

    // Recent triggers: type → days ago
    val recentDaysAgo by vm.recentDaysAgo.collectAsState()
    val recentStartAts by vm.recentStartAts.collectAsState()
    var hasAutoSelected by remember { mutableStateOf(false) }

    // Reset auto-select when migraine date changes
    LaunchedEffect(draft.migraine?.beganAtIso) {
        hasAutoSelected = false
    }

    // ── Rebuild helpers ──
    fun rebuildDraftWithTriggers(triggers: List<TriggerDraft>) {
        logVm.replaceTriggers(triggers)
    }

    // ── Auto-select recent triggers (once, on first load — wizard only) ──
    // Only auto-select when user has explicitly set a migraine date
    LaunchedEffect(recentDaysAgo, pool) {
        if (!quickLogMode && !hasAutoSelected && recentDaysAgo.isNotEmpty() && pool.isNotEmpty() && draft.migraine?.beganAtIso != null) {
            val currentLabels = draft.triggers.map { it.type }.toSet()
            val poolLabelsSet = pool.map { it.label }.toSet()
            val toAdd = recentDaysAgo.keys
                .filter { it in poolLabelsSet && it !in currentLabels }
            if (toAdd.isNotEmpty()) {
                val newTriggers = draft.triggers + toAdd.map { TriggerDraft(it, startAtIso = recentStartAts[it]) }
                rebuildDraftWithTriggers(newTriggers)
            }
            hasAutoSelected = true
        }
    }

    // ── Time dialog: add new ──
    var showAddTimeDialog by remember { mutableStateOf(false) }
    var pendingLabel by remember { mutableStateOf<String?>(null) }

    // ── Time dialog: edit existing ──
    var showEditTimeDialog by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf<Int?>(null) }

    fun onTriggerTap(label: String) {
        val existingIdx = draft.triggers.indexOfFirst { it.type == label }
        if (existingIdx >= 0) {
            // Deselect
            val updated = draft.triggers.toMutableList().apply { removeAt(existingIdx) }
            rebuildDraftWithTriggers(updated)
        } else {
            pendingLabel = label
            showAddTimeDialog = true
        }
    }

    // Add dialog
    if (showAddTimeDialog && pendingLabel != null) {
        TriggerTimeDialog(
            title = pendingLabel!!,
            initialIso = null,
            onDismiss = { showAddTimeDialog = false },
            onSkip = {
                val updated = draft.triggers + TriggerDraft(pendingLabel!!, startAtIso = null)
                rebuildDraftWithTriggers(updated)
                showAddTimeDialog = false
            },
            onConfirm = { iso ->
                val updated = draft.triggers + TriggerDraft(pendingLabel!!, startAtIso = iso)
                rebuildDraftWithTriggers(updated)
                showAddTimeDialog = false
            }
        )
    }

    // Edit dialog
    if (showEditTimeDialog && editIndex != null && editIndex!! in draft.triggers.indices) {
        val editing = draft.triggers[editIndex!!]
        TriggerTimeDialog(
            title = editing.type,
            initialIso = editing.startAtIso,
            onDismiss = { showEditTimeDialog = false },
            onSkip = {
                val updated = draft.triggers.toMutableList().apply {
                    set(editIndex!!, editing.copy(startAtIso = null))
                }
                rebuildDraftWithTriggers(updated)
                showEditTimeDialog = false
            },
            onConfirm = { iso ->
                val updated = draft.triggers.toMutableList().apply {
                    set(editIndex!!, editing.copy(startAtIso = iso))
                }
                rebuildDraftWithTriggers(updated)
                showEditTimeDialog = false
            }
        )
    }

    // Frequent labels
    val poolLabels = remember(pool) { pool.map { it.label }.toSet() }
    val frequentLabels = remember(frequent, poolLabels) {
        frequent.mapNotNull { it.trigger?.label }.filter { it in poolLabels }.toSet()
    }
    val selectedLabels = remember(draft.triggers) { draft.triggers.map { it.type }.toSet() }

    // Group pool by category
    val grouped = remember(pool) {
        pool.groupBy { it.category ?: "Other" }.toSortedMap()
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            // Top bar: ← Previous | Title | X Close
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (!quickLogMode) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Prodromes", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                Text("Triggers", color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
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
                        .drawBehind { HubIcons.run { drawTriggerBolt(Color(0xFFFFB74D)) } }
                )
                Text("Triggers", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(
                    if (draft.triggers.isEmpty()) "Select triggers that may have contributed"
                    else "${draft.triggers.size} trigger${if (draft.triggers.size > 1) "s" else ""} selected",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                if (draft.triggers.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    draft.triggers.forEachIndexed { index, t ->
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
                                    t.type,
                                    color = AppTheme.BodyTextColor,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                                )
                                Text(
                                    if (t.startAtIso == null) "Same as migraine start"
                                    else formatTriggerTime(t.startAtIso),
                                    color = AppTheme.SubtleTextColor.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
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
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Remove",
                                tint = AppTheme.AccentPink.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable {
                                        val updated = draft.triggers.toMutableList().apply { removeAt(index) }
                                        rebuildDraftWithTriggers(updated)
                                    }
                            )
                        }
                    }
                }
            }

            // Manage card (own card)
            BaseCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Triggers", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Text("Manage →", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clickable { navController.navigate(Routes.MANAGE_TRIGGERS) })
                }
            }

            // ── Migraine picker (quick log only) ──
            if (quickLogMode && onMigraineSelect != null) {
                val firstIso = draft.triggers.firstOrNull()?.startAtIso
                MigrainePickerCard(itemStartAtIso = firstIso, authVm = authVm, selectedMigraineId = linkedMigraineId, onSelect = onMigraineSelect)
            }

            // ── Single triggers card: Frequent → divider → categories with dividers ──
            BaseCard {
                // Frequent section
                if (frequentLabels.isNotEmpty()) {
                    Text("Frequent", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        pool.filter { it.label in frequentLabels }.forEach { trig ->
                            TriggerButton(trig.label, trig.iconKey, trig.label in selectedLabels, daysAgo = recentDaysAgo[trig.label]) {
                                onTriggerTap(trig.label)
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
                            nonFreqItems.forEach { trig ->
                                TriggerButton(trig.label, trig.iconKey, trig.label in selectedLabels, daysAgo = recentDaysAgo[trig.label]) {
                                    onTriggerTap(trig.label)
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
                    onClick = { if (quickLogMode) onSave?.invoke() else navController.navigate(Routes.MEDICINES) },
                    enabled = !quickLogMode || draft.triggers.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                ) { Text(if (quickLogMode) "Save" else "Next") }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/* ────────────────────────────────────────────────
 *  Time dialog — Skip (use migraine start) or pick
 * ──────────────────────────────────────────────── */

@Composable
private fun TriggerTimeDialog(
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
                    "Time: ${formatTriggerTime(pickedIso)}",
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
                    Text("Skip", color = Color(0xFFFFB74D))
                }
            }
        }
    )
}

/* ────────────────────────────────────────────────
 *  Trigger circle button
 * ──────────────────────────────────────────────── */

@Composable
private fun TriggerButton(label: String, iconKey: String? = null, isSelected: Boolean, daysAgo: Int? = null, onClick: () -> Unit) {
    val accent = Color(0xFFFFB74D)

    val (circleColor, borderColor) = when {
        isSelected && daysAgo == null -> accent.copy(alpha = 0.40f) to accent.copy(alpha = 0.7f)
        isSelected && daysAgo == 0   -> accent.copy(alpha = 0.50f) to accent.copy(alpha = 0.85f)
        isSelected && daysAgo == 1   -> Color(0xFFFF8A65).copy(alpha = 0.35f) to Color(0xFFFF8A65).copy(alpha = 0.65f) // deeper orange
        isSelected && daysAgo == 2   -> Color(0xFFE57373).copy(alpha = 0.30f) to Color(0xFFE57373).copy(alpha = 0.55f) // salmon
        isSelected && daysAgo == 3   -> Color(0xFFEF9A9A).copy(alpha = 0.25f) to Color(0xFFEF9A9A).copy(alpha = 0.45f) // faded red
        else -> Color.White.copy(alpha = 0.08f) to Color.White.copy(alpha = 0.12f)
    }
    val iconTint = if (isSelected) Color.White else AppTheme.SubtleTextColor
    val textColor = if (isSelected) Color.White else AppTheme.BodyTextColor
    val icon = TriggerIcons.forKey(iconKey)

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
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Text(
                    label.take(2).uppercase(),
                    color = iconTint,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                )
            }
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
        if (isSelected && daysAgo != null && daysAgo > 0) {
            Text(
                when (daysAgo) {
                    1 -> "yesterday"
                    2 -> "2d ago"
                    3 -> "3d ago"
                    else -> ""
                },
                color = Color.White.copy(alpha = 0.5f),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/* ────────────────────────────────────────────────
 *  Format helper
 * ──────────────────────────────────────────────── */

private fun formatTriggerTime(iso: String?): String {
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





