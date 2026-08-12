package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
fun LogHomeScreen(
    navController: NavController,
    authVm: AuthViewModel,
    vm: LogViewModel,
    symptomVm: SymptomViewModel,
    onClose: () -> Unit = {}
) {
    val draft by vm.draft.collectAsState()
    val authState by authVm.state.collectAsState()
    val scrollState = rememberScrollState()

    // Load symptoms from Supabase
    val painCharacter by symptomVm.painCharacter.collectAsState()
    val accompanying by symptomVm.accompanying.collectAsState()
    val favorites by symptomVm.favorites.collectAsState()
    val favoriteIds by symptomVm.favoriteIds.collectAsState()

    LaunchedEffect(authState.accessToken) {
        authState.accessToken?.let { symptomVm.loadAll(it) }
    }

    // UI state — initialize from draft if available
    val selectedSymptoms = remember {
        mutableStateListOf<String>().apply {
            draft.migraine?.symptoms?.let { addAll(it) }
        }
    }
    var notes by rememberSaveable { mutableStateOf(draft.migraine?.note ?: "") }

    // Sync from existing draft when it changes
    LaunchedEffect(draft.migraine?.symptoms, draft.migraine?.note) {
        draft.migraine?.let { m ->
            if (selectedSymptoms.isEmpty() && m.symptoms.isNotEmpty()) {
                selectedSymptoms.addAll(m.symptoms)
            }
            if (notes.isEmpty() && !m.note.isNullOrBlank()) {
                notes = m.note ?: ""
            }
        }
    }

    // Auto-init draft
    LaunchedEffect(Unit) {
        if (draft.migraine == null) {
            vm.setMigraineDraft(type = "Migraine", severity = 5, beganAtIso = null, endedAtIso = null, note = null)
        }
    }

    fun syncDraft() {
        val typeLabel = if (selectedSymptoms.isEmpty()) "Migraine" else selectedSymptoms.joinToString(", ")
        vm.setMigraineDraft(
            type = typeLabel,
            note = notes.ifBlank { null },
            symptoms = selectedSymptoms.toList()
        )
    }

    // Aura detail sheet: opens whenever the Aura symptom is tapped.
    var showAuraSheet by remember { mutableStateOf(false) }
    // Non-null while the intensity sheet for that symptom is open.
    var severitySheetLabel by remember { mutableStateOf<String?>(null) }

    fun onSymptomTap(label: String) {
        val isPainChar = painCharacter.any { it.label == label }
        if (isAuraSymptomLabel(label)) {
            if (label !in selectedSymptoms) selectedSymptoms.add(label)
            syncDraft()
            showAuraSheet = true
        } else if (isPainChar) {
            // Migraine types and pain quality have nothing to rate.
            if (label in selectedSymptoms) selectedSymptoms.remove(label) else selectedSymptoms.add(label)
            syncDraft()
        } else {
            if (label !in selectedSymptoms) selectedSymptoms.add(label)
            syncDraft()
            severitySheetLabel = label
        }
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            // Top bar: ← Previous | Title | X Close
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Back"), tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(t("Back"), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = t("Close"), tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            // Hero
            HeroCard {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .drawBehind { HubIcons.run { drawMigraineStarburst(AppTheme.AccentPink) } }
                )
                Text(
                    t("What are you experiencing?"),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )
                Text(
                    if (selectedSymptoms.isEmpty()) t("Select all that apply")
                    else (if (selectedSymptoms.size == 1) t("1 symptom selected") else t("%s symptoms selected", selectedSymptoms.size)),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                if (selectedSymptoms.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    selectedSymptoms.toList().forEach { sym ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                sym,
                                color = AppTheme.BodyTextColor,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                            )
                            // Intensity is captured in the tap-through sheet; shown
                            // here so the hero reflects what was rated.
                            draft.symptomSeverities[sym]?.let { sev ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(AppTheme.AccentPurple.copy(alpha = 0.18f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        sev.lowercase().replaceFirstChar { c -> c.uppercase() },
                                        color = AppTheme.AccentPurple,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = t("Remove"),
                                tint = AppTheme.AccentPink.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable {
                                        selectedSymptoms.remove(sym)
                                        if (isAuraSymptomLabel(sym)) vm.setAuraDraft(emptyList(), null)
                                        syncDraft()
                                    }
                            )
                        }
                    }
                }
            }

            // Manage card (always on top)
            BaseCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(t("Symptoms"), color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Text(t("Manage →"), color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clickable { navController.navigate(Routes.MANAGE_SYMPTOMS) })
                }
            }

            // Split frequent by category
            val freqPainIds = favorites.filter { it.symptom?.category == "pain_character" }.mapNotNull { it.symptom?.label }.toSet()
            val freqAccompIds = favorites.filter { it.symptom?.category != null && it.symptom?.category != "pain_character" }.mapNotNull { it.symptom?.label }.toSet()

            // Pain character card
            BaseCard {
                Text(t("Pain character"), color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))

                // Frequent pain symptoms first
                if (freqPainIds.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        painCharacter.filter { it.label in freqPainIds }.forEach { symptom ->
                            SymptomButton(symptom.label, symptom.label in selectedSymptoms, iconKey = symptom.iconKey) {
                                onSymptomTap(symptom.label)
                            }
                        }
                    }
                    // Divider between frequent and rest
                    val remainingPain = painCharacter.filter { it.label !in freqPainIds }
                    if (remainingPain.isNotEmpty()) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    }
                }

                // Remaining pain symptoms
                val remainingPain = painCharacter.filter { it.label !in freqPainIds }
                if (remainingPain.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        remainingPain.forEach { symptom ->
                            SymptomButton(symptom.label, symptom.label in selectedSymptoms, iconKey = symptom.iconKey) {
                                onSymptomTap(symptom.label)
                            }
                        }
                    }
                } else if (freqPainIds.isEmpty()) {
                    Text(t("Loading…"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Accompanying experience card
            BaseCard {
                Text(t("Accompanying experience"), color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))

                // Frequent accompanying symptoms first
                if (freqAccompIds.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        accompanying.filter { it.label in freqAccompIds }.forEach { symptom ->
                            SymptomButton(symptom.label, symptom.label in selectedSymptoms, iconKey = symptom.iconKey) {
                                onSymptomTap(symptom.label)
                            }
                        }
                    }
                    // Divider between frequent and rest
                    val remainingAccomp = accompanying.filter { it.label !in freqAccompIds }
                    if (remainingAccomp.isNotEmpty()) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    }
                }

                // Remaining accompanying symptoms
                val remainingAccomp = accompanying.filter { it.label !in freqAccompIds }
                if (remainingAccomp.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        remainingAccomp.forEach { symptom ->
                            SymptomButton(symptom.label, symptom.label in selectedSymptoms, iconKey = symptom.iconKey) {
                                onSymptomTap(symptom.label)
                            }
                        }
                    }
                } else if (freqAccompIds.isEmpty()) {
                    Text(t("Loading…"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Navigation
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = { navController.popBackStack() },
                    border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple)
                ) { Text(t("Back")) }
                Button(
                    onClick = { syncDraft(); navController.navigate(Routes.PAIN_LOCATION) },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                ) { Text(t("Next")) }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showAuraSheet) {
        // Staged entries: each is the zones the aura covered at one moment.
        // The union is still written to migraines.aura_locations, so every
        // existing consumer keeps working while the report gains a sequence.
        val staged = draft.auraEntries
        AuraDetailSheet(
            initialZones = if (staged.isEmpty()) draft.auraLocations else emptyList(),
            // Once moments are staged the chips describe the NEXT stage, so a
            // reopen must not prefill them with the attack-total sum.
            initialDurationMinutes = if (staged.isEmpty()) draft.auraDurationMinutes else null,
            previousEntries = staged.map { AuraPrevEntry(it.startAtIso, it.durationMinutes, it.zones) },
            onAddMoment = { zones, dur, at ->
                val base = staged.ifEmpty {
                    if (draft.auraLocations.isEmpty()) emptyList()
                    else listOf(AuraEntryDraft(zones = draft.auraLocations))
                }
                vm.setAuraEntries(
                    base + AuraEntryDraft(
                        startAtIso = at ?: java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")),
                        zones = zones,
                        durationMinutes = dur,
                    ),
                    dur,
                )
            },
            onSave = { zones, dur, at ->
                if (staged.isEmpty()) {
                    if (at == null) {
                        vm.setAuraDraft(zones, dur)
                    } else {
                        // A single moment with a user-chosen time is still a
                        // sequence of one: stage it so the time is kept.
                        vm.setAuraEntries(listOf(AuraEntryDraft(startAtIso = at, zones = zones, durationMinutes = dur)), dur)
                    }
                } else {
                    val last = if (zones.isEmpty()) staged
                    else staged + AuraEntryDraft(
                        startAtIso = at ?: java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")),
                        zones = zones,
                        durationMinutes = dur,
                    )
                    vm.setAuraEntries(last, dur)
                }
                showAuraSheet = false
            },
            onRemove = {
                selectedSymptoms.removeAll { isAuraSymptomLabel(it) }
                vm.setAuraEntries(emptyList(), null)
                syncDraft()
                showAuraSheet = false
            },
            onDismiss = { showAuraSheet = false }
        )
    }

    severitySheetLabel?.let { label ->
        SymptomSeveritySheet(
            label = label,
            selected = draft.symptomSeverities[label],
            time = draft.symptomTimes[label],
            onSelect = { sev ->
                vm.setSymptomSeverity(label, sev)
                severitySheetLabel = null
            },
            onSetTime = { vm.setSymptomTime(label, it) },
            onRemove = {
                selectedSymptoms.remove(label)
                syncDraft()
                severitySheetLabel = null
            },
            onDismiss = { severitySheetLabel = null }
        )
    }
}

@Composable
internal fun SymptomButton(label: String, isSelected: Boolean, iconKey: String? = null, onClick: () -> Unit) {
    val icon = SymptomIcons.forLabel(label, iconKey)
    val brainyId = SymptomIcons.drawableForLabel(label, iconKey)
    val circleColor = if (isSelected) AppTheme.AccentPurple.copy(alpha = 0.40f) else Color.White.copy(alpha = 0.08f)
    val borderColor = if (isSelected) AppTheme.AccentPurple.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.12f)
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
                .then(
                    Modifier.border(width = 1.5.dp, color = borderColor, shape = CircleShape)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (brainyId != null || icon != null) {
                LogIconImage(drawableId = brainyId, fallback = icon, size = if (brainyId != null) 34.dp else 26.dp, tint = iconTint)
            } else {
                Text(
                    label.take(2).uppercase(),
                    color = iconTint,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                )
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



// ── Symptom severity sheet (mild / moderate / severe) ──────────
/**
 * Opens when a rateable symptom is tapped, mirroring the aura sheet so the
 * interaction is the same everywhere. Three levels rather than a 1-10 slider:
 * that's what people can actually judge for nausea or dizziness. Picking a
 * level closes the sheet immediately, so rating stays one extra tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SymptomSeveritySheet(
    label: String,
    selected: String?,
    time: String?,
    onSelect: (String?) -> Unit,
    onSetTime: (String?) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Timing is opt-in so the fast path stays one extra tap: pick a level and
    // the sheet closes. Expanding this never stamps a default time.
    var showTimePicker by remember { mutableStateOf(false) }
    val levels = listOf(
        Triple("MILD", "Mild", "Noticeable but I could carry on"),
        Triple("MODERATE", "Moderate", "Hard to ignore, it got in the way"),
        Triple("SEVERE", "Severe", "Overwhelming, I couldn't function"),
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppTheme.BaseCardContainer.copy(alpha = 0.96f),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(t(label),
                color = Color.White,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                t("How bad was it?"),
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(4.dp))

            levels.forEach { (value, title, blurb) ->
                val isOn = selected == value
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isOn) AppTheme.AccentPurple else Color.White.copy(alpha = 0.06f))
                        .border(
                            1.dp,
                            if (isOn) Color.Transparent else AppTheme.AccentPurple.copy(alpha = 0.25f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelect(if (isOn) null else value) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        t(title),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        t(blurb),
                        color = if (isOn) Color.White.copy(alpha = 0.85f) else AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // Timing — collapsed by default, so it costs nothing to ignore
            Text(
                if (time == null) t("+ Add a time") else t("Started %s", formatSymptomTime(time)),
                color = AppTheme.AccentPurple,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.fillMaxWidth().clickable { showTimePicker = !showTimePicker },
                textAlign = TextAlign.Center
            )
            if (showTimePicker) {
                AppDateTimePicker(
                    label = if (time == null) t("Set time") else formatSymptomTime(time)
                ) { onSetTime(it) }
                if (time != null) {
                    Text(
                        t("Clear time"),
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().clickable { onSetTime(null) },
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                t("Skip — I'd rather not rate it"),
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().clickable { onDismiss() },
                textAlign = TextAlign.Center
            )
            Text(
                t("Remove %s from this log", label),
                color = AppTheme.AccentPink.copy(alpha = 0.9f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().clickable { onRemove() },
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Local formatter — the shared formatIsoDdMmYyHm helpers are file-private. */
private fun formatSymptomTime(iso: String?): String {
    if (iso.isNullOrBlank()) return "At attack start"
    return runCatching {
        java.time.OffsetDateTime.parse(iso)
            .atZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm"))
    }.getOrElse { iso }
}
