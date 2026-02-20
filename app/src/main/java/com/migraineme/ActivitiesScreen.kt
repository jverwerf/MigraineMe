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
fun ActivitiesScreen(
    navController: NavController,
    vm: ActivityViewModel,
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

    LaunchedEffect(authState.accessToken, draft.migraine?.beganAtIso) {
        authState.accessToken?.let {
            vm.loadAll(it)
            vm.loadRecent(it, draft.migraine?.beganAtIso)
        }
    }

    // Recent activities: type (lowercase) → days ago / start_at
    val recentDaysAgo by vm.recentDaysAgo.collectAsState()
    val recentStartAts by vm.recentStartAts.collectAsState()
    var hasAutoSelected by remember { mutableStateOf(false) }

    // Reset auto-select when migraine date changes
    LaunchedEffect(draft.migraine?.beganAtIso) {
        hasAutoSelected = false
    }

    fun rebuildDraftWithActivities(acts: List<ActivityDraft>) {
        logVm.replaceActivities(acts)
    }

    // ── Auto-select recent activities (once, on first load — wizard only) ──
    // Recent data uses lowercase activity_type; pool uses title case labels
    // Only auto-select when user has explicitly set a migraine date
    LaunchedEffect(recentDaysAgo, pool) {
        if (!quickLogMode && !hasAutoSelected && recentDaysAgo.isNotEmpty() && pool.isNotEmpty() && draft.migraine?.beganAtIso != null) {
            val currentLabels = draft.activities.map { it.type.lowercase() }.toSet()
            // Build lowercase → title case mapping from pool
            val poolLabelMap = pool.associate { it.label.lowercase() to it.label }
            val toAdd = recentDaysAgo.keys
                .filter { it in poolLabelMap && it !in currentLabels }
            if (toAdd.isNotEmpty()) {
                val newActs = draft.activities + toAdd.map { key ->
                    ActivityDraft(
                        type = poolLabelMap[key] ?: key,
                        startAtIso = recentStartAts[key]
                    )
                }
                rebuildDraftWithActivities(newActs)
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

    fun onTap(label: String) {
        val idx = draft.activities.indexOfFirst { it.type == label }
        if (idx >= 0) {
            rebuildDraftWithActivities(draft.activities.toMutableList().apply { removeAt(idx) })
        } else {
            pendingLabel = label
            showAddTimeDialog = true
        }
    }

    // Add dialog
    if (showAddTimeDialog && pendingLabel != null) {
        ActTimeDialog(
            title = pendingLabel!!,
            initialIso = null,
            initialEndIso = null,
            onDismiss = { showAddTimeDialog = false },
            onSkip = {
                val updated = draft.activities + ActivityDraft(pendingLabel!!, startAtIso = null, endAtIso = null)
                rebuildDraftWithActivities(updated)
                showAddTimeDialog = false
            },
            onConfirm = { startIso, endIso ->
                val updated = draft.activities + ActivityDraft(pendingLabel!!, startAtIso = startIso, endAtIso = endIso)
                rebuildDraftWithActivities(updated)
                showAddTimeDialog = false
            }
        )
    }

    // Edit dialog
    if (showEditTimeDialog && editIndex != null && editIndex!! in draft.activities.indices) {
        val editing = draft.activities[editIndex!!]
        ActTimeDialog(
            title = editing.type,
            initialIso = editing.startAtIso,
            initialEndIso = editing.endAtIso,
            onDismiss = { showEditTimeDialog = false },
            onSkip = {
                val updated = draft.activities.toMutableList().apply {
                    set(editIndex!!, editing.copy(startAtIso = null, endAtIso = null))
                }
                rebuildDraftWithActivities(updated)
                showEditTimeDialog = false
            },
            onConfirm = { startIso, endIso ->
                val updated = draft.activities.toMutableList().apply {
                    set(editIndex!!, editing.copy(startAtIso = startIso, endAtIso = endIso))
                }
                rebuildDraftWithActivities(updated)
                showEditTimeDialog = false
            }
        )
    }

    val frequentLabels = remember(frequent) { frequent.mapNotNull { it.activity?.label }.toSet() }
    val selectedLabels = remember(draft.activities) { draft.activities.map { it.type }.toSet() }
    val grouped = remember(pool) { pool.groupBy { it.category ?: "Other" }.toSortedMap() }

    // Build daysAgo lookup keyed by title-case label (matching pool)
    val daysAgoByLabel = remember(recentDaysAgo, pool) {
        val poolLabelMap = pool.associate { it.label.lowercase() to it.label }
        recentDaysAgo.mapKeys { (k, _) -> poolLabelMap[k] ?: k }
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (!quickLogMode) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Location", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
                Text("Activity", color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                if (!quickLogMode) {
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close", tint = Color.White, modifier = Modifier.size(28.dp)) }
                } else {
                    Spacer(Modifier.size(28.dp))
                }
            }

            HeroCard {
                Box(Modifier.size(40.dp).drawBehind {
                    HubIcons.run { drawActivityPulse(Color(0xFFFF8A65)) }
                })
                Text("What were you doing?", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(
                    if (draft.activities.isEmpty()) "Log your activity" else "${draft.activities.size} activit${if (draft.activities.size > 1) "ies" else "y"} added",
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center
                )
                if (draft.activities.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    draft.activities.forEachIndexed { index, act ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(act.type, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                                Text(
                                    if (act.startAtIso == null) "Same as migraine start" else formatActTime(act.startAtIso),
                                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall
                                )
                                if (act.endAtIso != null) {
                                    Text(
                                        "→ ${formatActTime(act.endAtIso)}",
                                        color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                if (act.startAtIso != null && act.endAtIso != null) {
                                    deriveDurationMinutes(act.startAtIso, act.endAtIso)?.let { mins ->
                                        Text(
                                            if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m",
                                            color = AppTheme.AccentPurple.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.Edit, "Edit time", tint = AppTheme.AccentPurple.copy(alpha = 0.6f),
                                    modifier = Modifier.size(18.dp).clickable { editIndex = index; showEditTimeDialog = true })
                                Icon(Icons.Outlined.Close, "Remove", tint = AppTheme.AccentPink.copy(alpha = 0.6f),
                                    modifier = Modifier.size(18.dp).clickable {
                                        rebuildDraftWithActivities(draft.activities.toMutableList().apply { removeAt(index) })
                                    })
                            }
                        }
                    }
                }
            }

            BaseCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Activities", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Text("Manage →", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clickable { navController.navigate(Routes.MANAGE_ACTIVITIES) })
                }
            }

            if (quickLogMode && onMigraineSelect != null) {
                val firstIso = draft.activities.firstOrNull()?.startAtIso
                MigrainePickerCard(itemStartAtIso = firstIso, authVm = authVm, selectedMigraineId = linkedMigraineId, onSelect = onMigraineSelect)
            }

            BaseCard {
                if (frequentLabels.isNotEmpty()) {
                    Text("Frequent", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        pool.filter { it.label in frequentLabels }.forEach { act ->
                            ActCircleButton(act.label, act.label in selectedLabels, act.iconKey, daysAgo = daysAgoByLabel[act.label]) { onTap(act.label) }
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                }
                val entries = grouped.entries.toList()
                entries.forEachIndexed { ci, (cat, items) ->
                    val nonFreq = items.filter { it.label !in frequentLabels }
                    if (nonFreq.isNotEmpty()) {
                        Text(cat, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            nonFreq.forEach { act -> ActCircleButton(act.label, act.label in selectedLabels, act.iconKey, daysAgo = daysAgoByLabel[act.label]) { onTap(act.label) } }
                        }
                        if (entries.drop(ci + 1).any { (_, its) -> its.any { it.label !in frequentLabels } })
                            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    }
                }
                if (pool.isEmpty()) Text("Loading…", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { navController.popBackStack() },
                    border = BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple)
                ) { Text(if (quickLogMode) "Cancel" else "Back") }
                Button(onClick = { if (quickLogMode) onSave?.invoke() else navController.navigate(Routes.MISSED_ACTIVITIES) },
                    enabled = !quickLogMode || draft.activities.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                ) { Text(if (quickLogMode) "Save" else "Next") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ActCircleButton(label: String, isSelected: Boolean, iconKey: String? = null, daysAgo: Int? = null, onClick: () -> Unit) {
    val accent = Color(0xFFFF8A65)
    val icon = ActivityIcons.forKey(iconKey)

    val (bg, border) = when {
        isSelected && daysAgo == null -> accent.copy(alpha = 0.40f) to accent.copy(alpha = 0.7f)
        isSelected && daysAgo == 0   -> accent.copy(alpha = 0.50f) to accent.copy(alpha = 0.85f)
        isSelected && daysAgo == 1   -> Color(0xFFE57373).copy(alpha = 0.35f) to Color(0xFFE57373).copy(alpha = 0.65f)
        isSelected && daysAgo == 2   -> Color(0xFFEF5350).copy(alpha = 0.30f) to Color(0xFFEF5350).copy(alpha = 0.55f)
        isSelected && daysAgo == 3   -> Color(0xFFEF9A9A).copy(alpha = 0.25f) to Color(0xFFEF9A9A).copy(alpha = 0.45f)
        else -> Color.White.copy(alpha = 0.08f) to Color.White.copy(alpha = 0.12f)
    }
    val iconTint = if (isSelected) Color.White else AppTheme.SubtleTextColor
    val textColor = if (isSelected) Color.White else AppTheme.BodyTextColor

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(bg).border(1.5.dp, border, CircleShape), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(24.dp))
            } else {
                Text(label.take(2).uppercase(), color = iconTint,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = textColor,
            style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth())
        if (isSelected && daysAgo != null && daysAgo > 0) {
            Text(
                when (daysAgo) { 1 -> "yesterday"; 2 -> "2d ago"; 3 -> "3d ago"; else -> "" },
                color = Color.White.copy(alpha = 0.5f),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ActTimeDialog(title: String, initialIso: String? = null, initialEndIso: String? = null, onDismiss: () -> Unit, onSkip: (() -> Unit)?, onConfirm: (startIso: String?, endIso: String?) -> Unit) {
    var pickedIso by remember { mutableStateOf(initialIso) }
    var pickedEndIso by remember { mutableStateOf(initialEndIso) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E0A2E), titleContentColor = Color.White, textContentColor = AppTheme.BodyTextColor,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Start: ${formatActTime(pickedIso)}", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
                AppDateTimePicker(label = "Set start time", onDateTimeSelected = { pickedIso = it })
                Spacer(Modifier.height(4.dp))
                Text("End: ${formatActTime(pickedEndIso)}", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
                AppDateTimePicker(label = "Set end time (optional)", onDateTimeSelected = { pickedEndIso = it })
                if (pickedIso != null && pickedEndIso != null) {
                    deriveDurationMinutes(pickedIso!!, pickedEndIso!!)?.let { mins ->
                        Text("Duration: ${if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"}", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(pickedIso, pickedEndIso) }) { Text(if (onSkip != null) "Add" else "Save", color = AppTheme.AccentPurple) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel", color = AppTheme.SubtleTextColor) }
                if (onSkip != null) TextButton(onClick = onSkip) { Text("Skip", color = Color(0xFFFF8A65)) }
            }
        }
    )
}

private fun formatActTime(iso: String?): String {
    if (iso.isNullOrBlank()) return "Not set"
    return try {
        val odt = runCatching { OffsetDateTime.parse(iso) }.getOrNull()
        val ldt = odt?.toLocalDateTime() ?: runCatching { LocalDateTime.parse(iso) }.getOrNull()
            ?: runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull() ?: return "Not set"
        ldt.format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
    } catch (_: Exception) { "Not set" }
}

private fun deriveDurationMinutes(startIso: String, endIso: String): Int? {
    return try {
        val s = runCatching { OffsetDateTime.parse(startIso).toLocalDateTime() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(startIso) }.getOrNull()
            ?: runCatching { Instant.parse(startIso).atZone(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull() ?: return null
        val e = runCatching { OffsetDateTime.parse(endIso).toLocalDateTime() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(endIso) }.getOrNull()
            ?: runCatching { Instant.parse(endIso).atZone(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull() ?: return null
        val dur = java.time.Duration.between(s, e)
        if (dur.isNegative) null else dur.toMinutes().toInt()
    } catch (_: Exception) { null }
}


