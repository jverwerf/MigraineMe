package com.migraineme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Composable
fun PaintThePictureScreen(
    navController: NavController,
    authVm: AuthViewModel,
    vm: LogViewModel,
    symptomVm: SymptomViewModel,
    triggerVm: TriggerViewModel = viewModel(),
    prodromeVm: ProdromeViewModel = viewModel(),
    medicineVm: MedicineViewModel = viewModel(),
    reliefVm: ReliefViewModel = viewModel(),
    activityVm: ActivityViewModel = viewModel(),
    locationVm: LocationViewModel = viewModel(),
    missedActivityVm: MissedActivityViewModel = viewModel(),
    onClose: () -> Unit = {}
) {
    val authState by authVm.state.collectAsState()
    val draft by vm.draft.collectAsState()
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Load all pools
    val triggerPool by triggerVm.pool.collectAsState()
    val prodromePool by prodromeVm.pool.collectAsState()
    val medicinePool by medicineVm.pool.collectAsState()
    val reliefPool by reliefVm.pool.collectAsState()
    val activityPool by activityVm.pool.collectAsState()
    val locationPool by locationVm.pool.collectAsState()
    val missedActivityPool by missedActivityVm.pool.collectAsState()
    val symptomPool by symptomVm.pool.collectAsState()

    LaunchedEffect(authState.accessToken) {
        authState.accessToken?.let {
            triggerVm.loadAll(it)
            prodromeVm.loadAll(it)
            medicineVm.loadAll(it)
            reliefVm.loadAll(it)
            activityVm.loadAll(it)
            locationVm.loadAll(it)
            missedActivityVm.loadAll(it)
            symptomVm.loadAll(it)
        }
    }

    // Pain location options
    val painLocationOptions = remember {
        listOf("Top of Head", "Forehead Center", "Forehead Left", "Forehead Right",
            "Left Brow", "Right Brow", "Left Temple", "Right Temple",
            "Left Eye", "Right Eye", "Left Ear", "Right Ear",
            "Nose Bridge", "Left Sinus", "Right Sinus",
            "Left Jaw / TMJ", "Right Jaw / TMJ", "Teeth Left", "Teeth Right",
            "Base of Skull Left", "Base of Skull Right", "Base of Skull Center",
            "Occipital Center", "Back Upper Left", "Back Upper Right",
            "Neck Left", "Neck Right", "Left Shoulder", "Right Shoulder",
            "Behind Left Ear", "Behind Right Ear",
            "Upper Back Center", "Upper Back Left", "Upper Back Right",
            "Center / Lower Back")
    }

    // ── AI state ──
    var dayNote by rememberSaveable { mutableStateOf("") }
    var aiResult by remember { mutableStateOf<AiLogParseResultV2?>(null) }
    var aiParsed by remember { mutableStateOf(false) }
    var aiLoading by remember { mutableStateOf(false) }
    val premiumState by PremiumManager.state.collectAsState()

    // ── Editable result state (user can remove/edit items) ──
    var editSeverity by remember { mutableStateOf<AiParsedField<Int>?>(null) }
    val editPainLocations = remember { mutableStateListOf<AiParsedField<String>>() }
    val editSymptoms = remember { mutableStateListOf<AiParsedField<String>>() }
    val editMatches = remember { mutableStateListOf<AiMatchItemV2>() }

    // Dialog state for editing severity
    var showSeverityEditor by remember { mutableStateOf(false) }
    var expandedItemKey by remember { mutableStateOf<String?>(null) }

    // Helper to sync all item drafts
    fun syncDraftItems() {
        vm.replaceTriggers(editMatches.filter { it.category == "trigger" }.map {
            TriggerDraft(type = it.label, startAtIso = it.startAtIso)
        })
        vm.replaceProdromes(editMatches.filter { it.category == "prodrome" }.map {
            ProdromeDraft(type = it.label, startAtIso = it.startAtIso)
        })
        vm.replaceMedicines(editMatches.filter { it.category == "medicine" }.map {
            MedicineDraft(name = it.label, amount = it.amount, notes = null,
                startAtIso = it.startAtIso,
                reliefScale = it.reliefScale ?: "NONE", sideEffectScale = it.sideEffectScale ?: "NONE",
                sideEffectNotes = it.sideEffectNotes)
        })
        vm.replaceReliefs(editMatches.filter { it.category == "relief" }.map {
            ReliefDraft(type = it.label, startAtIso = it.startAtIso, endAtIso = it.endAtIso,
                reliefScale = it.reliefScale ?: "NONE",
                sideEffectScale = it.sideEffectScale ?: "NONE", sideEffectNotes = it.sideEffectNotes)
        })
        vm.replaceActivities(editMatches.filter { it.category == "activity" }.map {
            ActivityDraft(type = it.label, startAtIso = it.startAtIso, endAtIso = it.endAtIso)
        })
        vm.replaceLocations(editMatches.filter { it.category == "location" }.map {
            LocationDraft(type = it.label, startAtIso = it.startAtIso)
        })
        vm.replaceMissedActivities(editMatches.filter { it.category == "missed_activity" }.map {
            MissedActivityDraft(type = it.label, startAtIso = it.startAtIso)
        })
    }

    // Sync AI result → editable state
    fun syncFromResult(result: AiLogParseResultV2) {
        editSeverity = result.severity
        editPainLocations.clear(); editPainLocations.addAll(result.painLocations)
        editSymptoms.clear(); editSymptoms.addAll(result.symptoms)
        editMatches.clear(); editMatches.addAll(result.matches)
    }

    // Inject current editable state into draft
    fun injectIntoDraft() {
        editSeverity?.let { vm.setMigraineDraft(severity = it.value) }
        if (editPainLocations.isNotEmpty()) {
            val labelToId = ALL_PAIN_POINTS_MAP.entries.associate { it.value to it.key }
            val ids = editPainLocations.mapNotNull { labelToId[it.value] }
            if (ids.isNotEmpty()) vm.setPainLocationsDraft(ids)
        }
        if (editSymptoms.isNotEmpty()) vm.setSymptomsDraft(editSymptoms.map { it.value })
        // Replace item lists entirely so removals are reflected
        vm.replaceTriggers(editMatches.filter { it.category == "trigger" }.map {
            TriggerDraft(type = it.label, startAtIso = it.startAtIso)
        })
        vm.replaceProdromes(editMatches.filter { it.category == "prodrome" }.map {
            ProdromeDraft(type = it.label, startAtIso = it.startAtIso)
        })
        vm.replaceMedicines(editMatches.filter { it.category == "medicine" }.map {
            MedicineDraft(name = it.label, amount = it.amount, notes = null,
                startAtIso = it.startAtIso,
                reliefScale = it.reliefScale ?: "NONE", sideEffectScale = it.sideEffectScale ?: "NONE",
                sideEffectNotes = it.sideEffectNotes)
        })
        vm.replaceReliefs(editMatches.filter { it.category == "relief" }.map {
            ReliefDraft(type = it.label, startAtIso = it.startAtIso, endAtIso = it.endAtIso,
                reliefScale = it.reliefScale ?: "NONE",
                sideEffectScale = it.sideEffectScale ?: "NONE", sideEffectNotes = it.sideEffectNotes)
        })
        vm.replaceActivities(editMatches.filter { it.category == "activity" }.map {
            ActivityDraft(type = it.label, startAtIso = it.startAtIso, endAtIso = it.endAtIso)
        })
        vm.replaceLocations(editMatches.filter { it.category == "location" }.map {
            LocationDraft(type = it.label, startAtIso = it.startAtIso)
        })
        vm.replaceMissedActivities(editMatches.filter { it.category == "missed_activity" }.map {
            MissedActivityDraft(type = it.label, startAtIso = it.startAtIso)
        })
    }

    // Speech recogniser
    val speechLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                dayNote = if (dayNote.isBlank()) spoken else "$dayNote, $spoken"
                if (aiParsed) {
                    aiParsed = false; aiResult = null
                    editPainLocations.clear(); editSymptoms.clear(); editMatches.clear()
                    editSeverity = null
                    showSeverityEditor = false
                    expandedItemKey = null
                }
            }
        }
    }

    fun launchVoice() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Paint the picture — tell us about your migraine…")
        }
        try { speechLauncher.launch(intent) } catch (_: Exception) {
            android.widget.Toast.makeText(context, "Voice input not available", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun runAiParse() {
        if (dayNote.isBlank()) { aiParsed = true; return }
        aiLoading = true
        scope.launch {
            try {
                // Step 1: Deterministic pre-parse (instant, free)
                val deterResult = withContext(Dispatchers.IO) {
                    deterministicParse(
                        dayNote,
                        triggerPool.map { it.label },
                        prodromePool.map { it.label },
                        medicinePool.map { it.label },
                        reliefPool.map { it.label },
                        activityPool.map { it.label },
                        locationPool.map { it.label },
                        missedActivityPool.map { it.label },
                        painLocationOptions,
                        symptomPool.map { it.label }
                    )
                }

                // Step 2: GPT enhancement (premium only)
                val token = authState.accessToken
                val gptResult = if (premiumState.isPremium && token != null) {
                    try {
                        withContext(Dispatchers.IO) {
                            callGptForFullLogParseV2(
                                token, dayNote,
                                triggerPool.map { it.label },
                                prodromePool.map { it.label },
                                medicinePool.map { it.label },
                                reliefPool.map { it.label },
                                activityPool.map { it.label },
                                locationPool.map { it.label },
                                missedActivityPool.map { it.label },
                                painLocationOptions,
                                symptomPool.map { it.label },
                                deterResult
                            )
                        }
                    } catch (_: Exception) { null }
                } else null

                // Step 3: Merge results
                val merged = mergeResults(deterResult, gptResult)
                aiResult = merged
                syncFromResult(merged)
                injectIntoDraft()

            } catch (_: Exception) {
                // Full fallback to deterministic only
                val deterResult = deterministicParse(
                    dayNote,
                    triggerPool.map { it.label },
                    prodromePool.map { it.label },
                    medicinePool.map { it.label },
                    reliefPool.map { it.label },
                    activityPool.map { it.label },
                    locationPool.map { it.label },
                    missedActivityPool.map { it.label },
                    painLocationOptions,
                    symptomPool.map { it.label }
                )
                aiResult = deterResult
                syncFromResult(deterResult)
                injectIntoDraft()
            }
            aiParsed = true; aiLoading = false
        }
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            // Top bar
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Timing", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
                Text("Paint the Picture", color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            // Hero
            HeroCard {
                Icon(
                    imageVector = Icons.Outlined.Notes,
                    contentDescription = "Paint the picture",
                    tint = AppTheme.AccentPink,
                    modifier = Modifier.size(40.dp)
                )
                Text(
                    "Paint the picture",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )
                Text(
                    "Type or speak freely — we'll pre-fill your entire log",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            // Input card
            BaseCard {
                OutlinedTextField(
                    value = dayNote,
                    onValueChange = {
                        dayNote = it
                        if (aiParsed) {
                            aiParsed = false; aiResult = null
                            editPainLocations.clear(); editSymptoms.clear(); editMatches.clear()
                            editSeverity = null
                        }
                    },
                    placeholder = { Text("e.g. \"woke up with pounding left temple, felt nauseous, took sumatriptan and lay in dark room…\"", color = AppTheme.SubtleTextColor.copy(alpha = 0.5f)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = AppTheme.BodyTextColor,
                        cursorColor = AppTheme.AccentPurple, focusedBorderColor = AppTheme.AccentPurple,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f)
                    ),
                    minLines = 4, maxLines = 8
                )
                Spacer(Modifier.height(10.dp))

                // Voice + Find matches row
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { launchVoice() },
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple),
                        border = BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Outlined.Mic, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Voice", style = MaterialTheme.typography.bodySmall)
                    }

                    if (dayNote.isNotBlank() && !aiParsed) {
                        if (premiumState.isPremium) {
                            Button(
                                onClick = { runAiParse() },
                                enabled = !aiLoading,
                                modifier = Modifier.weight(1f).height(40.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple.copy(alpha = 0.8f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                if (aiLoading) {
                                    CircularProgressIndicator(Modifier.size(16.dp), Color.White, strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Analysing…", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Text("Find matches", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        } else {
                            // Free users still get deterministic matching
                            Button(
                                onClick = { runAiParse() },
                                enabled = !aiLoading,
                                modifier = Modifier.weight(1f).height(40.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple.copy(alpha = 0.8f)),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                if (aiLoading) {
                                    CircularProgressIndicator(Modifier.size(16.dp), Color.White, strokeWidth = 2.dp)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Analysing…", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Text("Find matches", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }

                // ── Show editable parse results ──
                if (aiParsed) {
                    val totalFound = editMatches.size + editPainLocations.size + editSymptoms.size +
                            (if (editSeverity != null) 1 else 0)

                    if (totalFound > 0) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Found $totalFound match${if (totalFound > 1) "es" else ""} — added to your log:",
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(6.dp))

                        // Severity — editable slider
                        editSeverity?.let { sev ->
                            if (showSeverityEditor) {
                                SeverityEditorPill(
                                    value = sev.value,
                                    inferred = sev.inferred,
                                    onValueChange = { newVal ->
                                        editSeverity = AiParsedField(newVal, inferred = false)
                                        vm.setMigraineDraft(severity = newVal)
                                    },
                                    onDone = { showSeverityEditor = false },
                                    onRemove = {
                                        editSeverity = null
                                        showSeverityEditor = false
                                    }
                                )
                            } else {
                                EditableMatchPill(
                                    label = "Severity: ${sev.value}/10",
                                    category = "severity",
                                    color = Color(0xFFEF5350),
                                    inferred = sev.inferred,
                                    onClick = { showSeverityEditor = true },
                                    onRemove = { editSeverity = null }
                                )
                            }
                        }

                        // Pain locations — remove only
                        editPainLocations.toList().forEach { loc ->
                            EditableMatchPill(
                                label = loc.value,
                                category = "pain location",
                                color = Color(0xFFEF5350),
                                inferred = loc.inferred,
                                onRemove = {
                                    editPainLocations.removeAll { it.value == loc.value }
                                    val labelToId = ALL_PAIN_POINTS_MAP.entries.associate { it.value to it.key }
                                    vm.setPainLocationsDraft(editPainLocations.mapNotNull { labelToId[it.value] })
                                }
                            )
                        }

                        // Symptoms — remove only
                        editSymptoms.toList().forEach { sym ->
                            EditableMatchPill(
                                label = sym.value,
                                category = "symptom",
                                color = AppTheme.AccentPink,
                                inferred = sym.inferred,
                                onRemove = {
                                    editSymptoms.removeAll { it.value == sym.value }
                                    vm.setSymptomsDraft(editSymptoms.map { it.value })
                                }
                            )
                        }

                        // Item matches — all expandable for time, medicine/relief also have extra fields
                        editMatches.toList().forEach { m ->
                            val catColor = when (m.category) {
                                "trigger" -> Color(0xFFFFB74D)
                                "prodrome" -> Color(0xFF9575CD)
                                "medicine" -> Color(0xFF4FC3F7)
                                "relief" -> Color(0xFF81C784)
                                "activity" -> Color(0xFFFF8A65)
                                "location" -> Color(0xFF78909C)
                                "missed_activity" -> Color(0xFFFF7043)
                                else -> AppTheme.SubtleTextColor
                            }
                            val key = "${m.label}|${m.category}"
                            val isExpanded = expandedItemKey == key

                            if (isExpanded) {
                                ItemEditorPill(
                                    match = m,
                                    color = catColor,
                                    onUpdate = { updated ->
                                        val idx = editMatches.indexOfFirst { it.label == m.label && it.category == m.category }
                                        if (idx >= 0) editMatches[idx] = updated
                                    },
                                    onDone = { expandedItemKey = null; syncDraftItems() },
                                    onRemove = {
                                        editMatches.removeAll { it.label == m.label && it.category == m.category }
                                        expandedItemKey = null
                                        syncDraftItems()
                                    }
                                )
                            } else {
                                val timeSub = m.startAtIso?.let { iso ->
                                    try {
                                        val odt = OffsetDateTime.parse(iso)
                                        val isToday = odt.toLocalDate() == java.time.LocalDate.now()
                                        if (isToday) odt.format(DateTimeFormatter.ofPattern("HH:mm"))
                                        else odt.format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm"))
                                    } catch (_: Exception) { null }
                                }
                                val detailParts = mutableListOf<String>()
                                timeSub?.let { detailParts.add(it) }
                                m.amount?.let { detailParts.add(it) }
                                if (m.reliefScale != null && m.reliefScale != "NONE") detailParts.add("relief: ${m.reliefScale!!.lowercase()}")
                                val sub = detailParts.joinToString(" · ").ifBlank { null }

                                EditableMatchPill(
                                    label = m.label,
                                    category = m.category.replace("_", " "),
                                    color = catColor,
                                    inferred = m.inferred,
                                    subtitle = sub,
                                    onClick = { expandedItemKey = key },
                                    onRemove = {
                                        editMatches.removeAll { it.label == m.label && it.category == m.category }
                                        syncDraftItems()
                                    }
                                )
                            }
                        }

                        // Legend for inferred items
                        val hasInferred = (editSeverity?.inferred == true) ||
                                editPainLocations.any { it.inferred } ||
                                editSymptoms.any { it.inferred } ||
                                editMatches.any { it.inferred }
                        if (hasInferred) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Italic items are suggestions — remove any that don't apply",
                                    color = AppTheme.SubtleTextColor.copy(alpha = 0.6f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }

                    } else if (dayNote.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("No matches found — you can add items manually in the next steps", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Hint — skip is fine
            if (!aiParsed && dayNote.isBlank()) {
                BaseCard {
                    Text(
                        "This is optional — you can skip straight to logging manually",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Navigation
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = { navController.popBackStack() },
                    border = BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple)
                ) { Text("Back") }
                Button(
                    onClick = {
                        // Auto-parse if text entered but not yet parsed
                        if (dayNote.isNotBlank() && !aiParsed) runAiParse()
                        // Re-inject edited state before navigating
                        if (aiParsed) injectIntoDraft()
                        navController.navigate(Routes.LOG_MIGRAINE)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                ) { Text("Next") }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ═════════════════════════════════════════════════════════════════════
//  Editable Match Pill — removable, shows inferred state
// ═════════════════════════════════════════════════════════════════════

@Composable
private fun EditableMatchPill(
    label: String,
    category: String,
    color: Color,
    inferred: Boolean,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    onRemove: () -> Unit
) {
    val borderMod = if (inferred) {
        Modifier.border(
            width = 1.dp,
            color = color.copy(alpha = 0.4f),
            shape = RoundedCornerShape(10.dp)
        )
    } else Modifier

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .then(borderMod)
            .background(
                if (inferred) color.copy(alpha = 0.06f) else color.copy(alpha = 0.12f),
                RoundedCornerShape(10.dp)
            )
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = if (inferred) Color.White.copy(alpha = 0.7f) else Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (inferred) FontWeight.Normal else FontWeight.Medium,
                    fontStyle = if (inferred) FontStyle.Italic else FontStyle.Normal
                )
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = AppTheme.SubtleTextColor.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        if (inferred) {
            Text(
                "suggested",
                color = color.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                modifier = Modifier.padding(end = 2.dp)
            )
        }
        Text(
            category.replaceFirstChar { it.uppercase() },
            color = color,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(end = 2.dp)
        )
        // Remove button
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable { onRemove() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Remove",
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

// ═════════════════════════════════════════════════════════════════════
//  Severity Editor Pill — inline slider
// ═════════════════════════════════════════════════════════════════════

@Composable
private fun SeverityEditorPill(
    value: Int,
    inferred: Boolean,
    onValueChange: (Int) -> Unit,
    onDone: () -> Unit,
    onRemove: () -> Unit
) {
    var sliderValue by remember(value) { mutableStateOf(value.toFloat()) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(Color(0xFFEF5350).copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Severity: ${sliderValue.toInt()}/10",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
            )
            Row {
                Text(
                    "Done",
                    color = AppTheme.AccentPurple,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            onValueChange(sliderValue.toInt())
                            onDone()
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { onRemove() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Close, "Remove", tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
                }
            }
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            valueRange = 1f..10f,
            steps = 8,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFEF5350),
                activeTrackColor = Color(0xFFEF5350),
                inactiveTrackColor = Color(0xFFEF5350).copy(alpha = 0.2f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Mild", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            Text("Severe", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ═════════════════════════════════════════════════════════════════════
//  Timing Editor Pill — inline hour/minute adjustment
// ═════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimingEditorPill(
    label: String,
    currentIso: String,
    color: Color,
    onDateTimeSelected: (String) -> Unit,
    onDone: () -> Unit,
    onRemove: () -> Unit
) {
    val parsed = try { OffsetDateTime.parse(currentIso) } catch (_: Exception) { OffsetDateTime.now() }
    var hour by remember { mutableStateOf(parsed.hour) }
    var minute by remember { mutableStateOf(parsed.minute) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$label: %02d:%02d".format(hour, minute),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
            )
            Row {
                Text(
                    "Done",
                    color = AppTheme.AccentPurple,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            val updated = parsed.withHour(hour).withMinute(minute)
                            onDateTimeSelected(updated.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Box(
                    Modifier.size(28.dp).clip(CircleShape).clickable { onRemove() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Close, "Remove", tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Hour slider
            Column(Modifier.weight(1f)) {
                Text("Hour", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = hour.toFloat(),
                    onValueChange = { hour = it.toInt() },
                    valueRange = 0f..23f,
                    steps = 22,
                    colors = SliderDefaults.colors(
                        thumbColor = color, activeTrackColor = color,
                        inactiveTrackColor = color.copy(alpha = 0.2f)
                    )
                )
            }
            // Minute slider
            Column(Modifier.weight(1f)) {
                Text("Min", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = minute.toFloat(),
                    onValueChange = { minute = (it.toInt() / 5) * 5 },
                    valueRange = 0f..55f,
                    steps = 10,
                    colors = SliderDefaults.colors(
                        thumbColor = color, activeTrackColor = color,
                        inactiveTrackColor = color.copy(alpha = 0.2f)
                    )
                )
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════
//  Item Editor Pill — expandable for medicine/relief fields
// ═════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemEditorPill(
    match: AiMatchItemV2,
    color: Color,
    onUpdate: (AiMatchItemV2) -> Unit,
    onDone: () -> Unit,
    onRemove: () -> Unit
) {
    val scaleOptions = listOf("NONE", "LOW", "MODERATE", "HIGH")
    val scaleLabels = listOf("None", "Low", "Moderate", "High")

    val parsedTime = remember(match.startAtIso) {
        match.startAtIso?.let { try { OffsetDateTime.parse(it) } catch (_: Exception) { null } }
    }
    var selectedDate by remember(match.label) { mutableStateOf(parsedTime?.toLocalDate() ?: java.time.LocalDate.now()) }
    var hour by remember(match.label) { mutableStateOf(parsedTime?.hour ?: OffsetDateTime.now().hour) }
    var minute by remember(match.label) { mutableStateOf(parsedTime?.minute ?: 0) }
    var showDatePicker by remember { mutableStateOf(false) }
    var amount by remember(match.label) { mutableStateOf(match.amount ?: "") }
    var reliefScale by remember(match.label) { mutableStateOf(match.reliefScale ?: "NONE") }
    var sideEffectScale by remember(match.label) { mutableStateOf(match.sideEffectScale ?: "NONE") }
    var sideEffectNotes by remember(match.label) { mutableStateOf(match.sideEffectNotes ?: "") }

    fun commitChanges() {
        val zone = java.time.ZoneId.systemDefault()
        val timeIso = selectedDate.atTime(hour, minute).atZone(zone).toOffsetDateTime()
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        onUpdate(match.copy(
            startAtIso = timeIso,
            amount = amount.ifBlank { null },
            reliefScale = reliefScale,
            sideEffectScale = sideEffectScale,
            sideEffectNotes = sideEffectNotes.ifBlank { null }
        ))
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // Header row
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                match.label,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
            )
            Row {
                Text(
                    match.category.replace("_", " ").replaceFirstChar { it.uppercase() },
                    color = color,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text(
                    "Done",
                    color = AppTheme.AccentPurple,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { commitChanges(); onDone() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Box(
                    Modifier.size(28.dp).clip(CircleShape).clickable { onRemove() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Close, "Remove", tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Date + Time editor
        Text("When?", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                selectedDate.format(DateTimeFormatter.ofPattern("EEE d MMM")),
                color = AppTheme.AccentPurple,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(AppTheme.AccentPurple.copy(alpha = 0.1f))
                    .clickable { showDatePicker = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
            Text(
                "%02d:%02d".format(hour, minute),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
            )
        }

        // Date picker dialog
        if (showDatePicker) {
            val datePickerState = rememberDatePickerState(
                initialSelectedDateMillis = selectedDate
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
            )
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            datePickerState.selectedDateMillis?.let { millis ->
                                selectedDate = java.time.Instant.ofEpochMilli(millis)
                                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                            }
                            showDatePicker = false
                        },
                        enabled = datePickerState.selectedDateMillis != null
                    ) { Text("OK", color = if (datePickerState.selectedDateMillis != null) AppTheme.AccentPurple else AppTheme.SubtleTextColor) }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) {
                        Text("Cancel", color = AppTheme.SubtleTextColor)
                    }
                },
                colors = DatePickerDefaults.colors(containerColor = Color(0xFF1E0A2E))
            ) {
                DatePicker(
                    state = datePickerState,
                    colors = appDatePickerColors(),
                    title = { Text("Select date", color = Color.White, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
                    headline = null,
                    showModeToggle = false
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Hour", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = hour.toFloat(),
                    onValueChange = { hour = it.toInt() },
                    valueRange = 0f..23f,
                    steps = 22,
                    colors = SliderDefaults.colors(
                        thumbColor = color, activeTrackColor = color,
                        inactiveTrackColor = color.copy(alpha = 0.2f)
                    )
                )
            }
            Column(Modifier.weight(1f)) {
                Text("Min", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = minute.toFloat(),
                    onValueChange = { minute = (it.toInt() / 5) * 5 },
                    valueRange = 0f..55f,
                    steps = 10,
                    colors = SliderDefaults.colors(
                        thumbColor = color, activeTrackColor = color,
                        inactiveTrackColor = color.copy(alpha = 0.2f)
                    )
                )
            }
        }

        // Amount (medicine only)
        if (match.category == "medicine") {
            Text("Amount / dosage", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                placeholder = { Text("e.g. 2 tablets, 400mg", color = AppTheme.SubtleTextColor.copy(alpha = 0.4f)) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = AppTheme.BodyTextColor,
                    cursorColor = color, focusedBorderColor = color.copy(alpha = 0.5f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                ),
                singleLine = true
            )
            Spacer(Modifier.height(10.dp))
        }

        // Relief scale + side effects (medicine and relief only)
        if (match.category == "medicine" || match.category == "relief") {
        // Relief scale
        Text("How much did it help?", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            scaleOptions.forEachIndexed { idx, scale ->
                val selected = reliefScale == scale
                val chipColor = when (scale) {
                    "HIGH" -> Color(0xFF81C784)
                    "MODERATE" -> Color(0xFFFFB74D)
                    "LOW" -> Color(0xFFEF5350)
                    else -> Color.White.copy(alpha = 0.2f)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) chipColor.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.05f))
                        .border(1.dp, if (selected) chipColor else Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .clickable { reliefScale = scale }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        scaleLabels[idx],
                        color = if (selected) Color.White else AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Side effect scale
        Text("Any side effects?", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            scaleOptions.forEachIndexed { idx, scale ->
                val selected = sideEffectScale == scale
                val chipColor = when (scale) {
                    "HIGH" -> Color(0xFFEF5350)
                    "MODERATE" -> Color(0xFFFFB74D)
                    "LOW" -> Color(0xFF81C784)
                    else -> Color.White.copy(alpha = 0.2f)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) chipColor.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.05f))
                        .border(1.dp, if (selected) chipColor else Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .clickable { sideEffectScale = scale }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        scaleLabels[idx],
                        color = if (selected) Color.White else AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                }
            }
        }

        // Side effect notes (only if side effects aren't NONE)
        if (sideEffectScale != "NONE") {
            Spacer(Modifier.height(10.dp))
            Text("Side effect details", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = sideEffectNotes,
                onValueChange = { sideEffectNotes = it },
                placeholder = { Text("e.g. drowsy, nauseous", color = AppTheme.SubtleTextColor.copy(alpha = 0.4f)) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = AppTheme.BodyTextColor,
                    cursorColor = color, focusedBorderColor = color.copy(alpha = 0.5f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f)
                ),
                singleLine = true
            )
        }
        } // end medicine/relief fields
    }
}
