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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MissedActivitiesScreen(
    navController: NavController,
    vm: MissedActivityViewModel,
    authVm: AuthViewModel,
    logVm: LogViewModel,
    onClose: () -> Unit = {},
    // Quick-log mode: the same screen reached from Log instead of from inside
    // the wizard, so it saves on its own and can ask why.
    quickLogMode: Boolean = false,
    onSave: ((reasons: Map<String, List<String>>, notes: Map<String, String>, anticipated: Boolean) -> Unit)? = null,
    linkedMigraineId: String? = null,
    onMigraineSelect: ((String?) -> Unit)? = null,
    triggerVm: TriggerViewModel = viewModel(),
    prodromeVm: ProdromeViewModel = viewModel(),
) {
    val pool by vm.pool.collectAsState()
    val frequent by vm.frequent.collectAsState()
    val authState by authVm.state.collectAsState()
    val draft by logVm.draft.collectAsState()
    val scrollState = rememberScrollState()

    // Day key of the migraine start the draft currently claims. The suggestions
    // are relative to it, so a date change has to re-run the load.
    val currentRefDate = draft.migraine?.beganAtIso?.take(10)

    LaunchedEffect(authState.accessToken, currentRefDate) {
        authState.accessToken?.let {
            vm.loadAll(it)
            vm.loadUpcoming(it, referenceDate = currentRefDate)
        }
    }

    // Auto-suggest: any activity scheduled on the migraine's start date through
    // +7 days is something the user likely won't make while sick. Tracked below
    // after the rebuildDraftWithMissed helper is defined.
    val upcoming by vm.upcoming.collectAsState()

    // ── Why, in quick-log mode ────────────────────────────────────────────
    val triggerPool by triggerVm.pool.collectAsState()
    val triggerFreq by triggerVm.frequent.collectAsState()
    val prodromePool by prodromeVm.pool.collectAsState()
    val prodromeFreq by prodromeVm.frequent.collectAsState()
    var hadMigraineToday by remember { mutableStateOf(false) }
    val reasonsByLabel = remember { mutableStateMapOf<String, List<String>>() }
    val notesByLabel = remember { mutableStateMapOf<String, String>() }
    var whyForLabel by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(authState.accessToken, quickLogMode) {
        if (!quickLogMode) return@LaunchedEffect
        val token = authState.accessToken ?: return@LaunchedEffect
        triggerVm.loadAll(token)
        prodromeVm.loadAll(token)
        // Any attack touching today, not just an open one: one that started and
        // ended today would otherwise read as a migraine-free day.
        hadMigraineToday = withContext(Dispatchers.IO) {
            val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
            runCatching { db.hasMigraineOnDay(token, LocalDate.now()) }.getOrDefault(false)
        }
    }

    // Reasons come from what a person actually chooses. A pool row naming a
    // metric table is read off a device or a food log, so it is detected, not
    // a reason someone gives.
    val triggerFavIds = remember(triggerFreq) { triggerFreq.map { it.triggerId }.toSet() }
    val triggerReasonItems = remember(triggerPool, triggerFavIds) {
        triggerPool
            .filter { it.metricTable == null }
            .filterNot { it.label.equals("menstruation_predicted", ignoreCase = true) }
            .map { SelectableItem(it.label, it.iconKey, it.id in triggerFavIds, it.category) }
    }
    val prodromeFavIds = remember(prodromeFreq) { prodromeFreq.map { it.prodromeId }.toSet() }
    val prodromeReasonItems = remember(prodromePool, prodromeFavIds) {
        prodromePool
            .filter { it.metricTable == null }
            .map { SelectableItem(it.label, it.iconKey, it.id in prodromeFavIds, it.category) }
    }

    // Only the missed-activity list changes here. This used to clearDraft() and
    // rebuild the whole draft field by field, which silently dropped
    // editMigraineId (turning an edit into a duplicate insert), every
    // existingId (re-inserting all linked rows as copies), symptom
    // severities/times and aura. Replace just the one list instead.
    fun rebuildDraftWithMissed(missed: List<MissedActivityDraft>) {
        logVm.replaceMissedActivities(missed)
    }

    fun onTap(label: String) {
        val idx = draft.missedActivities.indexOfFirst { it.type == label }
        if (idx >= 0) {
            rebuildDraftWithMissed(draft.missedActivities.toMutableList().apply { removeAt(idx) })
        } else {
            rebuildDraftWithMissed(draft.missedActivities + MissedActivityDraft(type = label))
            // Straight over the picker, for what was just tapped. Only on a day
            // with no attack: with one, the reason is already recorded.
            if (quickLogMode && !hadMigraineToday) whyForLabel = label
        }
    }

    // The suggestions must be stamped with the SAME day as the draft, so a load
    // still in flight or left over from an earlier date is never applied here.
    LaunchedEffect(upcoming, currentRefDate) {
        if (currentRefDate == null) return@LaunchedEffect
        if (upcoming.refDate != currentRefDate) return@LaunchedEffect
        if (logVm.autoSelectedRefDate("missed") == currentRefDate) return@LaunchedEffect

        // Anything auto-added under a previous date belongs to that date's
        // window, so drop it instead of leaving it on this log.
        val previouslyAdded = logVm.autoSelectedTypes("missed")
        val kept = draft.missedActivities.filter { it.type !in previouslyAdded }
        val currentLabels = kept.map { it.type }.toSet()
        val toAdd = upcoming.types.filter { it !in currentLabels }
        if (toAdd.isNotEmpty() || kept.size != draft.missedActivities.size) {
            rebuildDraftWithMissed(kept + toAdd.map { label ->
                MissedActivityDraft(type = label, startAtIso = upcoming.startAts[label])
            })
        }
        logVm.recordAutoSelect("missed", currentRefDate, toAdd.toSet())
    }

    val frequentLabels = remember(frequent) { frequent.mapNotNull { it.missedActivity?.label }.toSet() }
    val selectedLabels = remember(draft.missedActivities) { draft.missedActivities.map { it.type }.toSet() }
    // Wizard search — live-filters the pool grid below
    var wizardSearch by remember { mutableStateOf("") }
    val searchPool = remember(pool, wizardSearch) {
        if (wizardSearch.isBlank()) pool
        else pool.filter { it.label.contains(wizardSearch.trim(), ignoreCase = true) }
    }

    val grouped = remember(searchPool) { searchPool.groupBy { it.category ?: "Other" }.toSortedMap() }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            // Wizard breadcrumb: only when this really is the wizard step.
            if (!quickLogMode) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, t("Back"), tint = Color.White, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t("Activity"), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, t("Close"), tint = Color.White, modifier = Modifier.size(28.dp)) }
                }
            }

            HeroCard {
                Box(Modifier.size(40.dp).drawBehind {
                    HubIcons.run { drawMissedActivity(Color(0xFFFF7043)) }
                })
                Text(t("What did you miss?"), color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(
                    if (draft.missedActivities.isEmpty()) t("Activities you couldn't do") else if (draft.missedActivities.size == 1) t("1 missed activity") else t("%s missed activities", draft.missedActivities.size),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center
                )
                if (draft.missedActivities.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    draft.missedActivities.forEachIndexed { index, ma ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(prettyLabel(ma.type), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
                            Icon(Icons.Outlined.Close, t("Remove"), tint = AppTheme.AccentPink.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp).clickable {
                                    rebuildDraftWithMissed(draft.missedActivities.toMutableList().apply { removeAt(index) })
                                })
                        }
                    }
                }
            }

            if (!quickLogMode) {
                WizardStepNav(onBack = { navController.popBackStack() }, onSkip = { navController.navigate(Routes.NOTES) })
            }

            BaseCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Missed Activities"), color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Text(t("Manage →"), color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clickable { navController.navigate(Routes.MANAGE_MISSED_ACTIVITIES) })
                }
            }

            if (quickLogMode && onMigraineSelect != null) {
                val firstIso = draft.missedActivities.firstOrNull()?.startAtIso
                MigrainePickerCard(itemStartAtIso = firstIso, authVm = authVm, selectedMigraineId = linkedMigraineId, onSelect = onMigraineSelect)
            }

            WizardSearchField(query = wizardSearch, onQueryChange = { wizardSearch = it }, accent = Color(0xFFEF9A9A))

            BaseCard {
                if (frequentLabels.isNotEmpty()) {
                    Text(t("Frequent"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        searchPool.filter { it.label in frequentLabels }.forEach { ma ->
                            MissedCircleButton(ma.label, ma.label in selectedLabels, ma.iconKey) { onTap(ma.label) }
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
                            nonFreq.forEach { ma -> MissedCircleButton(ma.label, ma.label in selectedLabels, ma.iconKey) { onTap(ma.label) } }
                        }
                        if (entries.drop(ci + 1).any { (_, its) -> its.any { it.label !in frequentLabels } })
                            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    }
                }
                if (pool.isEmpty()) Text(t("Loading…"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { navController.popBackStack() },
                    border = BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple)
                ) { Text(if (quickLogMode) t("Cancel") else t("Back")) }
                Button(
                    onClick = {
                        if (quickLogMode) onSave?.invoke(reasonsByLabel.toMap(), notesByLabel.toMap(), !hadMigraineToday)
                        else navController.navigate(Routes.NOTES)
                    },
                    enabled = !quickLogMode || draft.missedActivities.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                ) { Text(if (quickLogMode) t("Save") else t("Next")) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    // Why, over the picker, for whatever was just tapped.
    whyForLabel?.let { label ->
        MissedWhySheet(
            label = label,
            triggerItems = triggerReasonItems,
            prodromeItems = prodromeReasonItems,
            selectedReasons = reasonsByLabel[label] ?: emptyList(),
            note = notesByLabel[label] ?: "",
            onToggleReason = { l ->
                val cur = reasonsByLabel[label] ?: emptyList()
                reasonsByLabel[label] = if (cur.contains(l)) cur - l else cur + l
            },
            onNoteChange = { notesByLabel[label] = it },
            onDismiss = { whyForLabel = null },
        )
    }
}

@Composable
private fun MissedCircleButton(label: String, isSelected: Boolean, iconKey: String? = null, onClick: () -> Unit) {
    val accent = Color(0xFFEF9A9A)
    val icon = MissedActivityIcons.forLabel(label, iconKey)
    val brainyId = MissedActivityIcons.drawableForLabel(label, iconKey)
    val bg = if (isSelected) accent.copy(alpha = 0.40f) else Color.White.copy(alpha = 0.08f)
    val border = if (isSelected) accent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.12f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(bg).border(1.5.dp, border, CircleShape), contentAlignment = Alignment.Center) {
            if (brainyId != null || icon != null) {
                LogIconImage(drawableId = brainyId, fallback = icon, size = if (brainyId != null) 34.dp else 24.dp, tint = if (isSelected) Color.White else AppTheme.SubtleTextColor)
            } else {
                Text(label.take(2).uppercase(), color = if (isSelected) Color.White else AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(t(label), color = if (isSelected) Color.White else AppTheme.BodyTextColor,
            style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun MissedTimeDialog(title: String, initialIso: String? = null, onDismiss: () -> Unit, onSkip: (() -> Unit)?, onConfirm: (iso: String?) -> Unit) {
    var pickedIso by remember { mutableStateOf(initialIso) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E0A2E), titleContentColor = Color.White, textContentColor = AppTheme.BodyTextColor,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t("Time: %s", formatMissedTime(pickedIso)), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
                AppDateTimePicker(label = t("Select time"), onDateTimeSelected = { pickedIso = it })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(pickedIso) }) { Text(if (onSkip != null) t("Add") else t("Save"), color = AppTheme.AccentPurple) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(t("Cancel"), color = AppTheme.SubtleTextColor) }
                if (onSkip != null) TextButton(onClick = onSkip) { Text(t("Skip"), color = Color(0xFFEF9A9A)) }
            }
        }
    )
}

private fun formatMissedTime(iso: String?): String {
    if (iso.isNullOrBlank()) return "Not set"
    return try {
        val odt = runCatching { OffsetDateTime.parse(iso) }.getOrNull()
        val ldt = odt?.toLocalDateTime() ?: runCatching { LocalDateTime.parse(iso) }.getOrNull()
            ?: runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull() ?: return "Not set"
        ldt.format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
    } catch (_: Exception) { "Not set" }
}


