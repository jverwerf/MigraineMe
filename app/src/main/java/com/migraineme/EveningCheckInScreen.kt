package com.migraineme

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private enum class CheckInPage { NOTE, TRIGGERS, PRODROMES, MEDICINES, RELIEFS, REVIEW }

private data class SelectableItem(
    val label: String,
    val iconKey: String? = null,
    val isFavourite: Boolean = false,
)

// Keep legacy types for backward compat with other files that import them
data class AiMatchItem(val label: String, val category: String)

/**
 * Full migraine log AI parse result — used by PaintThePictureScreen (legacy, kept for compat).
 */
data class AiLogParseResult(
    val severity: Int? = null,
    val painLocations: List<String> = emptyList(),
    val symptoms: List<String> = emptyList(),
    val matches: List<AiMatchItem> = emptyList()
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EveningCheckInScreen(
    navController: NavController,
    authVm: AuthViewModel,
    triggerVm: TriggerViewModel = viewModel(),
    prodromeVm: ProdromeViewModel = viewModel(),
    medicineVm: MedicineViewModel = viewModel(),
    reliefVm: ReliefViewModel = viewModel(),
) {
    val scope = rememberCoroutineScope()
    val authState by authVm.state.collectAsState()

    LaunchedEffect(authState.accessToken) {
        authState.accessToken?.let { token ->
            triggerVm.loadAll(token)
            prodromeVm.loadAll(token)
            medicineVm.loadAll(token)
            reliefVm.loadAll(token)
        }
    }

    val triggerPool by triggerVm.pool.collectAsState()
    val triggerFreq by triggerVm.frequent.collectAsState()
    val prodromePool by prodromeVm.pool.collectAsState()
    val prodromeFreq by prodromeVm.frequent.collectAsState()
    val medicinePool by medicineVm.pool.collectAsState()
    val medicineFreq by medicineVm.frequent.collectAsState()
    val reliefPool by reliefVm.pool.collectAsState()
    val reliefFreq by reliefVm.frequent.collectAsState()

    val triggerFavIds = remember(triggerFreq) { triggerFreq.map { it.triggerId }.toSet() }
    val triggerItems = remember(triggerPool, triggerFavIds) {
        triggerPool.map { SelectableItem(it.label, it.iconKey, it.id in triggerFavIds) }
    }
    val prodromeFavIds = remember(prodromeFreq) { prodromeFreq.map { it.prodromeId }.toSet() }
    val prodromeItems = remember(prodromePool, prodromeFavIds) {
        prodromePool.map { SelectableItem(it.label, it.iconKey, it.id in prodromeFavIds) }
    }
    val medicineFavIds = remember(medicineFreq) { medicineFreq.map { it.medicineId }.toSet() }
    val medicineItems = remember(medicinePool, medicineFavIds) {
        medicinePool.map { SelectableItem(it.label, it.category, it.id in medicineFavIds) }
    }
    val reliefFavIds = remember(reliefFreq) { reliefFreq.map { it.reliefId }.toSet() }
    val reliefItems = remember(reliefPool, reliefFavIds) {
        reliefPool.map { SelectableItem(it.label, it.iconKey, it.id in reliefFavIds) }
    }

    // ── Rich item state (carries all fields) ──
    val selectedTriggers = remember { mutableStateListOf<CheckInTriggerItem>() }
    val selectedProdromes = remember { mutableStateListOf<CheckInProdromeItem>() }
    val selectedMedicines = remember { mutableStateListOf<CheckInMedicineItem>() }
    val selectedReliefs = remember { mutableStateListOf<CheckInReliefItem>() }

    var noteText by remember { mutableStateOf("") }
    var aiParseResult by remember { mutableStateOf<CheckInParseResult?>(null) }
    var aiParsed by remember { mutableStateOf(false) }
    var aiLoading by remember { mutableStateOf(false) }

    var currentPage by remember { mutableStateOf(CheckInPage.NOTE) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    // Helper: check if a label is selected in any list
    fun isTriggerSelected(label: String) = selectedTriggers.any { it.label == label }
    fun isProdromeSelected(label: String) = selectedProdromes.any { it.label == label }
    fun isMedicineSelected(label: String) = selectedMedicines.any { it.label == label }
    fun isReliefSelected(label: String) = selectedReliefs.any { it.label == label }

    fun runAiParse() {
        if (noteText.isBlank()) { aiParsed = true; return }
        aiLoading = true
        scope.launch {
            try {
                // Step 1: Deterministic parse (free, instant)
                val deterResult = withContext(Dispatchers.IO) {
                    deterministicParseCheckIn(
                        noteText,
                        triggerPool.map { it.label },
                        prodromePool.map { it.label },
                        medicinePool.map { it.label },
                        reliefPool.map { it.label }
                    )
                }

                // Step 2: GPT enhancement (premium only)
                val token = authState.accessToken
                val gptResult = if (PremiumManager.state.value.isPremium && token != null) {
                    try {
                        withContext(Dispatchers.IO) {
                            callGptForCheckInParse(
                                token, noteText,
                                triggerPool.map { it.label },
                                prodromePool.map { it.label },
                                medicinePool.map { it.label },
                                reliefPool.map { it.label },
                                deterResult
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("EveningCheckIn", "GPT parse failed", e)
                        null
                    }
                } else null

                // Step 3: Merge
                val merged = mergeCheckInResults(deterResult, gptResult)
                aiParseResult = merged

                // Inject into selected lists (don't duplicate)
                merged.triggers.forEach { t ->
                    if (!isTriggerSelected(t.label)) selectedTriggers.add(t)
                }
                merged.prodromes.forEach { p ->
                    if (!isProdromeSelected(p.label)) selectedProdromes.add(p)
                }
                merged.medicines.forEach { m ->
                    if (!isMedicineSelected(m.label)) selectedMedicines.add(m)
                }
                merged.reliefs.forEach { r ->
                    if (!isReliefSelected(r.label)) selectedReliefs.add(r)
                }

            } catch (e: Exception) {
                Log.e("EveningCheckIn", "AI parse failed, falling back to deterministic", e)
                val deterResult = deterministicParseCheckIn(
                    noteText,
                    triggerPool.map { it.label },
                    prodromePool.map { it.label },
                    medicinePool.map { it.label },
                    reliefPool.map { it.label }
                )
                aiParseResult = deterResult
                deterResult.triggers.forEach { t -> if (!isTriggerSelected(t.label)) selectedTriggers.add(t) }
                deterResult.prodromes.forEach { p -> if (!isProdromeSelected(p.label)) selectedProdromes.add(p) }
                deterResult.medicines.forEach { m -> if (!isMedicineSelected(m.label)) selectedMedicines.add(m) }
                deterResult.reliefs.forEach { r -> if (!isReliefSelected(r.label)) selectedReliefs.add(r) }
            }
            aiParsed = true; aiLoading = false
        }
    }

    fun save() {
        val token = authState.accessToken ?: return
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                    val now = Instant.now().toString()
                    selectedTriggers.forEach { t ->
                        runCatching {
                            db.insertTrigger(token, null, t.label, t.startAtIso ?: now, t.note ?: "evening check-in")
                        }
                    }
                    selectedProdromes.forEach { p ->
                        runCatching {
                            db.insertProdrome(token, null, p.label, p.startAtIso ?: now, p.note ?: "evening check-in")
                        }
                    }
                    selectedMedicines.forEach { m ->
                        runCatching {
                            db.insertMedicine(
                                token, null, m.label, m.amount, m.startAtIso ?: now, m.note ?: "evening check-in",
                                reliefScale = m.reliefScale ?: "NONE",
                                sideEffectScale = m.sideEffectScale ?: "NONE",
                                sideEffectNotes = m.sideEffectNotes
                            )
                        }
                    }
                    selectedReliefs.forEach { r ->
                        runCatching {
                            db.insertRelief(
                                token, null, r.label, r.startAtIso ?: now, r.note ?: "evening check-in",
                                endAt = r.endAtIso,
                                reliefScale = r.reliefScale ?: "NONE",
                                sideEffectScale = r.sideEffectScale ?: "NONE",
                                sideEffectNotes = r.sideEffectNotes
                            )
                        }
                    }
                }
                saved = true
                kotlinx.coroutines.delay(1200)
                navController.popBackStack()
            } catch (e: Exception) { Log.e("EveningCheckIn", "Save failed", e) }
            finally { saving = false }
        }
    }

    val pages = CheckInPage.entries
    val pageIndex = pages.indexOf(currentPage)

    // Auto-advance from NOTE to TRIGGERS once parsing completes
    var pendingAdvance by remember { mutableStateOf(false) }
    LaunchedEffect(aiParsed, pendingAdvance) {
        if (aiParsed && pendingAdvance && currentPage == CheckInPage.NOTE) {
            pendingAdvance = false
            currentPage = CheckInPage.TRIGGERS
        }
    }

    fun goNext() {
        if (currentPage == CheckInPage.NOTE) {
            if (noteText.isBlank()) {
                currentPage = CheckInPage.TRIGGERS
                return
            }
            if (!aiParsed) {
                runAiParse()
                pendingAdvance = true
                return
            }
        }
        pages.getOrNull(pageIndex + 1)?.let { currentPage = it }
    }

    fun goBack() { pages.getOrNull(pageIndex - 1)?.let { currentPage = it } }

    // ── UI ──

    fun nowIso(): String = java.time.OffsetDateTime.now().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    val bgBrush = remember { androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF1A0029), Color(0xFF2A003D), Color(0xFF1A0029))) }

    Column(Modifier.fillMaxSize().background(bgBrush).statusBarsPadding().navigationBarsPadding()) {
        LinearProgressIndicator(
            progress = { (pageIndex + 1).toFloat() / pages.size },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = AppTheme.AccentPink, trackColor = AppTheme.TrackColor
        )
        Text(
            "Evening Check-in — ${pageIndex + 1} of ${pages.size}",
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 28.dp)
        )
        Spacer(Modifier.height(8.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                targetState = currentPage,
                transitionSpec = {
                    if (targetState.ordinal > initialState.ordinal)
                        slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
                    else
                        slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
                }, label = "checkinPage"
            ) { page ->
                // Collect AI-matched labels per category
                val aiTriggerLabels = remember(aiParseResult) { aiParseResult?.triggers?.map { it.label }?.toSet() ?: emptySet() }
                val aiProdromeLabels = remember(aiParseResult) { aiParseResult?.prodromes?.map { it.label }?.toSet() ?: emptySet() }
                val aiMedicineLabels = remember(aiParseResult) { aiParseResult?.medicines?.map { it.label }?.toSet() ?: emptySet() }
                val aiReliefLabels = remember(aiParseResult) { aiParseResult?.reliefs?.map { it.label }?.toSet() ?: emptySet() }

                when (page) {
                CheckInPage.NOTE -> NotePage(noteText, { noteText = it; if (aiParsed) { aiParsed = false; aiParseResult = null } }, aiLoading, aiParsed, aiParseResult, navController,
                    onRemoveMatch = { label, category ->
                        when (category) {
                            "trigger" -> selectedTriggers.removeAll { it.label == label }
                            "prodrome" -> selectedProdromes.removeAll { it.label == label }
                            "medicine" -> selectedMedicines.removeAll { it.label == label }
                            "relief" -> selectedReliefs.removeAll { it.label == label }
                        }
                    }
                ) { runAiParse() }
                CheckInPage.TRIGGERS -> FavouritesPage("Any triggers today?", if (aiTriggerLabels.isNotEmpty()) "We matched some from your note — confirm or adjust" else "Tap anything that happened", triggerItems, selectedTriggers.map { it.label }, Color(0xFFFFB74D), { TriggerIcons.forKey(it) }, aiTriggerLabels) { l ->
                    if (isTriggerSelected(l)) selectedTriggers.removeAll { it.label == l } else selectedTriggers.add(CheckInTriggerItem(label = l, startAtIso = nowIso(), note = "evening check-in"))
                }
                CheckInPage.PRODROMES -> FavouritesPage("Any warning signs?", if (aiProdromeLabels.isNotEmpty()) "We matched some from your note — confirm or adjust" else "Body signals you noticed", prodromeItems, selectedProdromes.map { it.label }, Color(0xFF9575CD), { ProdromeIcons.forKey(it) }, aiProdromeLabels) { l ->
                    if (isProdromeSelected(l)) selectedProdromes.removeAll { it.label == l } else selectedProdromes.add(CheckInProdromeItem(label = l, startAtIso = nowIso(), note = "evening check-in"))
                }
                CheckInPage.MEDICINES -> FavouritesPage("Take any medicine?", if (aiMedicineLabels.isNotEmpty()) "We matched some from your note — confirm or adjust" else "What did you take today", medicineItems, selectedMedicines.map { it.label }, Color(0xFF4FC3F7), { MedicineIcons.forKey(it) }, aiMedicineLabels) { l ->
                    if (isMedicineSelected(l)) selectedMedicines.removeAll { it.label == l } else selectedMedicines.add(CheckInMedicineItem(label = l, startAtIso = nowIso(), note = "evening check-in", reliefScale = "NONE", sideEffectScale = "NONE"))
                }
                CheckInPage.RELIEFS -> FavouritesPage("Use any relief methods?", if (aiReliefLabels.isNotEmpty()) "We matched some from your note — confirm or adjust" else "What helped today", reliefItems, selectedReliefs.map { it.label }, Color(0xFF81C784), { ReliefIcons.forKey(it) }, aiReliefLabels) { l ->
                    if (isReliefSelected(l)) selectedReliefs.removeAll { it.label == l } else selectedReliefs.add(CheckInReliefItem(label = l, startAtIso = nowIso(), note = "evening check-in", reliefScale = "NONE", sideEffectScale = "NONE"))
                }
                CheckInPage.REVIEW -> ReviewPage(
                    selectedTriggers, selectedProdromes, selectedMedicines, selectedReliefs,
                    aiParseResult, saving, saved,
                    rmTrigger = { label -> selectedTriggers.removeAll { it.label == label } },
                    rmProdrome = { label -> selectedProdromes.removeAll { it.label == label } },
                    rmMedicine = { label -> selectedMedicines.removeAll { it.label == label } },
                    rmRelief = { label -> selectedReliefs.removeAll { it.label == label } },
                    onUpdateMedicine = { updated ->
                        val idx = selectedMedicines.indexOfFirst { it.label == updated.label }
                        if (idx >= 0) selectedMedicines[idx] = updated
                    },
                    onUpdateRelief = { updated ->
                        val idx = selectedReliefs.indexOfFirst { it.label == updated.label }
                        if (idx >= 0) selectedReliefs[idx] = updated
                    },
                    onSave = { save() }
                )
            } }
        }

        if (!saved) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (currentPage == CheckInPage.NOTE) {
                    TextButton(onClick = { navController.popBackStack() }, modifier = Modifier.height(36.dp)) { Text("Cancel", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall) }
                } else {
                    TextButton(onClick = { goBack() }, modifier = Modifier.height(36.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(14.dp), tint = AppTheme.SubtleTextColor); Spacer(Modifier.width(2.dp)); Text("Back", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall) }
                }
                when (currentPage) {
                    CheckInPage.REVIEW -> Button(onClick = { save() }, enabled = !saving, modifier = Modifier.height(36.dp), colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)) {
                        if (saving) { CircularProgressIndicator(Modifier.size(14.dp), Color.White, strokeWidth = 2.dp) } else { Icon(Icons.Outlined.Check, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Save", style = MaterialTheme.typography.bodySmall) }
                    }
                    CheckInPage.NOTE -> Button(onClick = { goNext() }, enabled = !aiLoading, modifier = Modifier.height(36.dp), colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)) {
                        Text(if (noteText.isNotBlank() && !aiParsed) "Match & continue" else if (noteText.isBlank()) "Skip" else "Next", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.width(2.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(14.dp))
                    }
                    else -> Button(onClick = { goNext() }, modifier = Modifier.height(36.dp), colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)) {
                        Text("Next", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.width(2.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Favourites Page
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FavouritesPage(title: String, subtitle: String, items: List<SelectableItem>, selected: List<String>, accentColor: Color, iconResolver: (String?) -> ImageVector?, aiMatched: Set<String> = emptySet(), onToggle: (String) -> Unit) {
    val scrollState = rememberScrollState()
    val favourites = remember(items) { items.filter { it.isFavourite } }
    val others = remember(items) { items.filter { !it.isFavourite } }

    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)

        // Selected items summary at top
        if (selected.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.fillMaxWidth()
                    .background(accentColor.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                    .border(1.dp, accentColor.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                selected.forEach { label ->
                    val isAi = label in aiMatched
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isAi) {
                            Text("✦ ", color = Color(0xFFFFD54F), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            label,
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Remove",
                            tint = accentColor.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp).clickable { onToggle(label) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (favourites.isNotEmpty()) {
            Text("Favourites", color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                favourites.forEach { item ->
                    CheckInCircle(item.label, iconResolver(item.iconKey) ?: iconResolver(item.label.lowercase()), item.label in selected, accentColor, item.label in aiMatched) { onToggle(item.label) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (others.isNotEmpty()) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Spacer(Modifier.height(12.dp))
            Text("All", color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                others.forEach { item ->
                    CheckInCircle(item.label, iconResolver(item.iconKey) ?: iconResolver(item.label.lowercase()), item.label in selected, accentColor, item.label in aiMatched) { onToggle(item.label) }
                }
            }
        }

        Spacer(Modifier.height(80.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Note Page
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun NotePage(noteText: String, onNoteChange: (String) -> Unit, aiLoading: Boolean, aiParsed: Boolean, aiResult: CheckInParseResult?, navController: NavController, onRemoveMatch: (String, String) -> Unit = { _, _ -> }, onParse: () -> Unit) {
    val scrollState = rememberScrollState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Speech recogniser launcher
    val speechLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                val updated = if (noteText.isBlank()) spoken else "$noteText, $spoken"
                onNoteChange(updated)
            }
        }
    }

    fun launchVoice() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "One thing at a time — what happened today?")
        }
        try { speechLauncher.launch(intent) } catch (_: Exception) {
            android.widget.Toast.makeText(context, "Voice input not available", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("Tell us about your day", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(4.dp))
        Text("Type or speak freely — keep each thing separate so we can match it accurately", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = noteText, onValueChange = onNoteChange,
            placeholder = { Text("e.g. \"had red wine at dinner. neck felt stiff. took 2 ibuprofen. it helped a bit.\"", color = AppTheme.SubtleTextColor.copy(alpha = 0.5f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = AppTheme.BodyTextColor, cursorColor = AppTheme.AccentPurple, focusedBorderColor = AppTheme.AccentPurple, unfocusedBorderColor = Color.White.copy(alpha = 0.15f)),
            minLines = 3, maxLines = 6
        )
        Spacer(Modifier.height(10.dp))

        // Voice + Find matches row
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { launchVoice() },
                modifier = Modifier.height(40.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Outlined.Mic, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Voice", style = MaterialTheme.typography.bodySmall)
            }

            if (noteText.isNotBlank() && !aiParsed) {
                Button(
                    onClick = onParse, enabled = !aiLoading,
                    modifier = Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple.copy(alpha = 0.8f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (aiLoading) { CircularProgressIndicator(Modifier.size(16.dp), Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)); Text("Finding…", style = MaterialTheme.typography.bodySmall) }
                    else Text("Find matches", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (aiParsed && aiResult != null) {
            val total = aiResult.triggers.size + aiResult.prodromes.size + aiResult.medicines.size + aiResult.reliefs.size
            if (total > 0) {
                Spacer(Modifier.height(16.dp))
                Text("Found $total match${if (total > 1) "es" else ""} — added to review:", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))

                aiResult.triggers.forEach { t ->
                    NoteMatchPill(t.label, "trigger", Color(0xFFFFB74D), t.inferred, t.startAtIso, onRemove = { onRemoveMatch(t.label, "trigger") })
                }
                aiResult.prodromes.forEach { p ->
                    NoteMatchPill(p.label, "prodrome", Color(0xFF9575CD), p.inferred, p.startAtIso, onRemove = { onRemoveMatch(p.label, "prodrome") })
                }
                aiResult.medicines.forEach { m ->
                    val detail = buildString {
                        m.amount?.let { append("${it} · ") }
                        if (m.reliefScale != null && m.reliefScale != "NONE") append("relief: ${m.reliefScale.lowercase()}")
                    }.trimEnd(' ', '·')
                    NoteMatchPill(m.label, "medicine", Color(0xFF4FC3F7), m.inferred, m.startAtIso, detail.ifBlank { null }, onRemove = { onRemoveMatch(m.label, "medicine") })
                }
                aiResult.reliefs.forEach { r ->
                    val detail = if (r.reliefScale != null && r.reliefScale != "NONE") "relief: ${r.reliefScale.lowercase()}" else null
                    NoteMatchPill(r.label, "relief", Color(0xFF81C784), r.inferred, r.startAtIso, detail, onRemove = { onRemoveMatch(r.label, "relief") })
                }
            }
        }
        if (aiParsed && (aiResult == null || (aiResult.triggers.isEmpty() && aiResult.prodromes.isEmpty() && aiResult.medicines.isEmpty() && aiResult.reliefs.isEmpty())) && noteText.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text("No matches found", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
        }
        if (noteText.isBlank()) {
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun NoteMatchPill(label: String, category: String, color: Color, inferred: Boolean, startAtIso: String?, extraDetail: String? = null, onRemove: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .then(if (inferred) Modifier.border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(10.dp)) else Modifier)
            .background(if (inferred) color.copy(alpha = 0.06f) else color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label, color = if (inferred) Color.White.copy(alpha = 0.7f) else Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (inferred) FontWeight.Normal else FontWeight.Medium,
                    fontStyle = if (inferred) FontStyle.Italic else FontStyle.Normal
                )
            )
            val subParts = mutableListOf<String>()
            startAtIso?.let {
                try {
                    val odt = OffsetDateTime.parse(it)
                    subParts.add(odt.format(DateTimeFormatter.ofPattern("HH:mm")))
                } catch (_: Exception) {}
            }
            extraDetail?.let { subParts.add(it) }
            if (subParts.isNotEmpty()) {
                Text(subParts.joinToString(" · "), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (inferred) {
            Text("suggested", color = color.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), modifier = Modifier.padding(end = 4.dp))
        }
        Text(category.replaceFirstChar { it.uppercase() }, color = color, style = MaterialTheme.typography.labelSmall)
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Outlined.Close, "Remove", tint = color.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
        }
    }
}


// ═══════════════════════════════════════════════════════════════════
//  Review Page — shows all fields, tap to expand and edit
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewPage(
    triggers: List<CheckInTriggerItem>,
    prodromes: List<CheckInProdromeItem>,
    medicines: List<CheckInMedicineItem>,
    reliefs: List<CheckInReliefItem>,
    aiResult: CheckInParseResult?,
    saving: Boolean, saved: Boolean,
    rmTrigger: (String) -> Unit,
    rmProdrome: (String) -> Unit,
    rmMedicine: (String) -> Unit,
    rmRelief: (String) -> Unit,
    onUpdateMedicine: (CheckInMedicineItem) -> Unit,
    onUpdateRelief: (CheckInReliefItem) -> Unit,
    onSave: () -> Unit
) {
    val scrollState = rememberScrollState()
    val total = triggers.size + prodromes.size + medicines.size + reliefs.size
    val aiLabels = aiResult?.let {
        (it.triggers.map { t -> t.label } + it.prodromes.map { p -> p.label } + it.medicines.map { m -> m.label } + it.reliefs.map { r -> r.label }).toSet()
    } ?: emptySet()

    var expandedKey by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("Review", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(4.dp))
        Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM")), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))

        AnimatedVisibility(visible = saved) {
            Row(Modifier.fillMaxWidth().background(Color(0xFF2E7D32).copy(alpha = 0.85f), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Check-in saved!", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }

        if (total == 0 && !saved) {
            Spacer(Modifier.height(40.dp))
            Text("Nothing selected — go back to tap items, or save an empty check-in", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }

        if (triggers.isNotEmpty()) {
            ReviewSectionHeader("Triggers", Color(0xFFFFB74D), triggers.size)
            triggers.forEach { t ->
                val key = "trigger_${t.label}"
                if (expandedKey == key) {
                    TriggerEditorPill(t, Color(0xFFFFB74D),
                        onDone = { expandedKey = null },
                        onRemove = { rmTrigger(t.label); expandedKey = null }
                    )
                } else {
                    ReviewItemRow(t.label, Color(0xFFFFB74D), t.label in aiLabels, t.inferred,
                        subtitle = formatTimeSubtitle(t.startAtIso),
                        onRemove = { rmTrigger(t.label) },
                        onClick = { expandedKey = key }
                    )
                }
            }
        }
        if (prodromes.isNotEmpty()) {
            ReviewSectionHeader("Prodromes", Color(0xFF9575CD), prodromes.size)
            prodromes.forEach { p ->
                val key = "prodrome_${p.label}"
                if (expandedKey == key) {
                    ProdromeEditorPill(p, Color(0xFF9575CD),
                        onDone = { expandedKey = null },
                        onRemove = { rmProdrome(p.label); expandedKey = null }
                    )
                } else {
                    ReviewItemRow(p.label, Color(0xFF9575CD), p.label in aiLabels, p.inferred,
                        subtitle = formatTimeSubtitle(p.startAtIso),
                        onRemove = { rmProdrome(p.label) },
                        onClick = { expandedKey = key }
                    )
                }
            }
        }
        if (medicines.isNotEmpty()) {
            ReviewSectionHeader("Medicines", Color(0xFF4FC3F7), medicines.size)
            medicines.forEach { m ->
                val key = "medicine_${m.label}"
                if (expandedKey == key) {
                    MedicineEditorPill(m, Color(0xFF4FC3F7),
                        onUpdate = onUpdateMedicine,
                        onDone = { expandedKey = null },
                        onRemove = { rmMedicine(m.label); expandedKey = null }
                    )
                } else {
                    val sub = buildString {
                        m.amount?.let { append("Amount: $it") }
                        formatTimeSubtitle(m.startAtIso)?.let { if (isNotEmpty()) append(" · "); append(it) }
                        if (m.reliefScale != null && m.reliefScale != "NONE") { if (isNotEmpty()) append(" · "); append("Relief: ${m.reliefScale.lowercase()}") }
                        if (m.sideEffectScale != null && m.sideEffectScale != "NONE") { if (isNotEmpty()) append(" · "); append("Side effects: ${m.sideEffectScale.lowercase()}") }
                        m.sideEffectNotes?.let { if (isNotEmpty()) append(" · "); append(it) }
                    }.ifBlank { null }
                    ReviewItemRow(m.label, Color(0xFF4FC3F7), m.label in aiLabels, m.inferred,
                        subtitle = sub, onRemove = { rmMedicine(m.label) },
                        onClick = { expandedKey = key }
                    )
                }
            }
        }
        if (reliefs.isNotEmpty()) {
            ReviewSectionHeader("Reliefs", Color(0xFF81C784), reliefs.size)
            reliefs.forEach { r ->
                val key = "relief_${r.label}"
                if (expandedKey == key) {
                    ReliefEditorPill(r, Color(0xFF81C784),
                        onUpdate = onUpdateRelief,
                        onDone = { expandedKey = null },
                        onRemove = { rmRelief(r.label); expandedKey = null }
                    )
                } else {
                    val sub = buildString {
                        formatTimeSubtitle(r.startAtIso)?.let { append(it) }
                        if (r.reliefScale != null && r.reliefScale != "NONE") { if (isNotEmpty()) append(" · "); append("Relief: ${r.reliefScale.lowercase()}") }
                        if (r.sideEffectScale != null && r.sideEffectScale != "NONE") { if (isNotEmpty()) append(" · "); append("Side effects: ${r.sideEffectScale.lowercase()}") }
                        r.sideEffectNotes?.let { if (isNotEmpty()) append(" · "); append(it) }
                    }.ifBlank { null }
                    ReviewItemRow(r.label, Color(0xFF81C784), r.label in aiLabels, r.inferred,
                        subtitle = sub, onRemove = { rmRelief(r.label) },
                        onClick = { expandedKey = key }
                    )
                }
            }
        }

        if (total > 0 && !saved) {
            Spacer(Modifier.height(12.dp))
            Text("Logging $total item${if (total > 1) "s" else ""}", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun ReviewSectionHeader(title: String, color: Color, count: Int) {
    Spacer(Modifier.height(12.dp))
    Text("$title ($count)", color = color, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ReviewItemRow(label: String, color: Color, isAiMatched: Boolean, inferred: Boolean, subtitle: String?, onRemove: () -> Unit, onClick: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .then(if (inferred) Modifier.border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(10.dp)) else Modifier)
            .background(if (inferred) color.copy(alpha = 0.06f) else color.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label, color = if (inferred) Color.White.copy(alpha = 0.7f) else Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (inferred) FontWeight.Normal else FontWeight.Medium,
                    fontStyle = if (inferred) FontStyle.Italic else FontStyle.Normal
                )
            )
            val tagParts = mutableListOf<String>()
            if (isAiMatched) tagParts.add("matched from note")
            if (inferred) tagParts.add("suggested")
            subtitle?.let { tagParts.add(it) }
            if (tagParts.isNotEmpty()) {
                Text(tagParts.joinToString(" · "), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            } else {
                Text("Tap to edit", color = AppTheme.SubtleTextColor.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
            }
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.Close, "Remove", tint = color.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Time Editor — shared by all editor pills
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeEditor(
    initialIso: String?,
    color: Color,
    onTimeChanged: (String) -> Unit
) {
    val parsedTime = remember(initialIso) {
        initialIso?.let { try { OffsetDateTime.parse(it) } catch (_: Exception) { null } }
    }
    var selectedDate by remember { mutableStateOf(parsedTime?.toLocalDate() ?: java.time.LocalDate.now()) }
    var hour by remember { mutableStateOf(parsedTime?.hour ?: OffsetDateTime.now().hour) }
    var minute by remember { mutableStateOf(parsedTime?.minute ?: 0) }
    var showDatePicker by remember { mutableStateOf(false) }

    fun commit() {
        val zone = java.time.ZoneId.systemDefault()
        val iso = selectedDate.atTime(hour, minute).atZone(zone).toOffsetDateTime()
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        onTimeChanged(iso)
    }

    Text("When?", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
    Spacer(Modifier.height(2.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            selectedDate.format(DateTimeFormatter.ofPattern("EEE d MMM")),
            color = AppTheme.AccentPurple,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(AppTheme.AccentPurple.copy(alpha = 0.1f)).clickable { showDatePicker = true }.padding(horizontal = 8.dp, vertical = 4.dp)
        )
        Text("%02d:%02d".format(hour, minute), color = Color.White, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        selectedDate = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    }
                    showDatePicker = false; commit()
                }, enabled = datePickerState.selectedDateMillis != null) { Text("OK", color = if (datePickerState.selectedDateMillis != null) AppTheme.AccentPurple else AppTheme.SubtleTextColor) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel", color = AppTheme.SubtleTextColor) } },
            colors = DatePickerDefaults.colors(containerColor = Color(0xFF1E0A2E))
        ) { DatePicker(state = datePickerState, colors = appDatePickerColors(), title = { Text("Select date", color = Color.White, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) }, headline = null, showModeToggle = false) }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Hour", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            Slider(value = hour.toFloat(), onValueChange = { hour = it.toInt(); commit() }, valueRange = 0f..23f, steps = 22,
                colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color, inactiveTrackColor = color.copy(alpha = 0.2f)))
        }
        Column(Modifier.weight(1f)) {
            Text("Min", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            Slider(value = minute.toFloat(), onValueChange = { minute = (it.toInt() / 5) * 5; commit() }, valueRange = 0f..55f, steps = 10,
                colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color, inactiveTrackColor = color.copy(alpha = 0.2f)))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Relief Scale Picker — shared by medicine/relief editors
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun ScalePicker(label: String, value: String, onChanged: (String) -> Unit, colorMap: Map<String, Color>) {
    val options = listOf("NONE", "LOW", "MODERATE", "HIGH")
    val labels = listOf("None", "Low", "Moderate", "High")
    Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { idx, scale ->
            val selected = value == scale
            val chipColor = colorMap[scale] ?: Color.White.copy(alpha = 0.2f)
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .background(if (selected) chipColor.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.05f))
                    .border(1.dp, if (selected) chipColor else Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    .clickable { onChanged(scale) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(labels[idx], color = if (selected) Color.White else AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal))
            }
        }
    }
}

private val reliefColorMap = mapOf("HIGH" to Color(0xFF81C784), "MODERATE" to Color(0xFFFFB74D), "LOW" to Color(0xFFEF5350), "NONE" to Color.White.copy(alpha = 0.2f))
private val sideEffectColorMap = mapOf("HIGH" to Color(0xFFEF5350), "MODERATE" to Color(0xFFFFB74D), "LOW" to Color(0xFF81C784), "NONE" to Color.White.copy(alpha = 0.2f))

// ═══════════════════════════════════════════════════════════════════
//  Editor Header — shared by all editor pills
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun EditorHeader(label: String, color: Color, onDone: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
        Row {
            Text("Done", color = AppTheme.AccentPurple, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onDone() }.padding(horizontal = 8.dp, vertical = 4.dp))
            Box(Modifier.size(28.dp).clip(CircleShape).clickable { onRemove() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Close, "Remove", tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Trigger Editor Pill — time only
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun TriggerEditorPill(item: CheckInTriggerItem, color: Color, onDone: () -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp).background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        EditorHeader(item.label, color, onDone, onRemove)
        Spacer(Modifier.height(10.dp))
        TimeEditor(item.startAtIso, color) { /* triggers don't update time in current model */ }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Prodrome Editor Pill — time only
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun ProdromeEditorPill(item: CheckInProdromeItem, color: Color, onDone: () -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp).background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        EditorHeader(item.label, color, onDone, onRemove)
        Spacer(Modifier.height(10.dp))
        TimeEditor(item.startAtIso, color) { /* prodromes don't update time in current model */ }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Medicine Editor Pill — time + amount + relief + side effects
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun MedicineEditorPill(item: CheckInMedicineItem, color: Color, onUpdate: (CheckInMedicineItem) -> Unit, onDone: () -> Unit, onRemove: () -> Unit) {
    var timeIso by remember(item.label) { mutableStateOf(item.startAtIso) }
    var amount by remember(item.label) { mutableStateOf(item.amount ?: "") }
    var reliefScale by remember(item.label) { mutableStateOf(item.reliefScale ?: "NONE") }
    var sideEffectScale by remember(item.label) { mutableStateOf(item.sideEffectScale ?: "NONE") }
    var sideEffectNotes by remember(item.label) { mutableStateOf(item.sideEffectNotes ?: "") }

    fun commit() {
        onUpdate(item.copy(startAtIso = timeIso, amount = amount.ifBlank { null }, reliefScale = reliefScale, sideEffectScale = sideEffectScale, sideEffectNotes = sideEffectNotes.ifBlank { null }))
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp).background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        EditorHeader(item.label, color, { commit(); onDone() }, onRemove)
        Spacer(Modifier.height(10.dp))

        TimeEditor(timeIso, color) { timeIso = it }
        Spacer(Modifier.height(10.dp))

        Text("Amount / dosage", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = amount, onValueChange = { amount = it },
            placeholder = { Text("e.g. 2 tablets, 400mg", color = AppTheme.SubtleTextColor.copy(alpha = 0.4f)) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = AppTheme.BodyTextColor, cursorColor = color, focusedBorderColor = color.copy(alpha = 0.5f), unfocusedBorderColor = Color.White.copy(alpha = 0.1f)),
            singleLine = true
        )
        Spacer(Modifier.height(10.dp))

        ScalePicker("How much did it help?", reliefScale, { reliefScale = it }, reliefColorMap)
        Spacer(Modifier.height(10.dp))
        ScalePicker("Any side effects?", sideEffectScale, { sideEffectScale = it }, sideEffectColorMap)

        if (sideEffectScale != "NONE") {
            Spacer(Modifier.height(10.dp))
            Text("Side effect details", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = sideEffectNotes, onValueChange = { sideEffectNotes = it },
                placeholder = { Text("e.g. drowsy, nauseous", color = AppTheme.SubtleTextColor.copy(alpha = 0.4f)) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = AppTheme.BodyTextColor, cursorColor = color, focusedBorderColor = color.copy(alpha = 0.5f), unfocusedBorderColor = Color.White.copy(alpha = 0.1f)),
                singleLine = true
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Relief Editor Pill — time + relief scale + side effects
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun ReliefEditorPill(item: CheckInReliefItem, color: Color, onUpdate: (CheckInReliefItem) -> Unit, onDone: () -> Unit, onRemove: () -> Unit) {
    var timeIso by remember(item.label) { mutableStateOf(item.startAtIso) }
    var reliefScale by remember(item.label) { mutableStateOf(item.reliefScale ?: "NONE") }
    var sideEffectScale by remember(item.label) { mutableStateOf(item.sideEffectScale ?: "NONE") }
    var sideEffectNotes by remember(item.label) { mutableStateOf(item.sideEffectNotes ?: "") }

    fun commit() {
        onUpdate(item.copy(startAtIso = timeIso, reliefScale = reliefScale, sideEffectScale = sideEffectScale, sideEffectNotes = sideEffectNotes.ifBlank { null }))
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp).background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        EditorHeader(item.label, color, { commit(); onDone() }, onRemove)
        Spacer(Modifier.height(10.dp))

        TimeEditor(timeIso, color) { timeIso = it }
        Spacer(Modifier.height(10.dp))

        ScalePicker("How much did it help?", reliefScale, { reliefScale = it }, reliefColorMap)
        Spacer(Modifier.height(10.dp))
        ScalePicker("Any side effects?", sideEffectScale, { sideEffectScale = it }, sideEffectColorMap)

        if (sideEffectScale != "NONE") {
            Spacer(Modifier.height(10.dp))
            Text("Side effect details", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = sideEffectNotes, onValueChange = { sideEffectNotes = it },
                placeholder = { Text("e.g. drowsy, nauseous", color = AppTheme.SubtleTextColor.copy(alpha = 0.4f)) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = AppTheme.BodyTextColor, cursorColor = color, focusedBorderColor = color.copy(alpha = 0.5f), unfocusedBorderColor = Color.White.copy(alpha = 0.1f)),
                singleLine = true
            )
        }
    }
}

private fun formatTimeSubtitle(iso: String?): String? {
    if (iso == null) return null
    return try {
        val odt = OffsetDateTime.parse(iso)
        odt.format(DateTimeFormatter.ofPattern("HH:mm"))
    } catch (_: Exception) { null }
}

// ═══════════════════════════════════════════════════════════════════
//  Circle Button
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun CheckInCircle(label: String, icon: ImageVector?, isSelected: Boolean, color: Color, isAiMatched: Boolean = false, onClick: () -> Unit) {
    val bg = if (isSelected) color.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.08f)
    val bdr = if (isAiMatched && isSelected) Color(0xFFFFD54F) else if (isSelected) color.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.12f)
    val tint = if (isSelected) Color.White else AppTheme.SubtleTextColor
    val txt = if (isSelected) Color.White else AppTheme.BodyTextColor

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onClick)) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(bg).border(if (isAiMatched && isSelected) 2.dp else 1.5.dp, bdr, CircleShape), contentAlignment = Alignment.Center) {
                if (icon != null) Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
                else Text(label.take(2).uppercase(), color = tint, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
            }
            if (isAiMatched && isSelected) {
                Box(Modifier.align(Alignment.TopEnd).size(16.dp).clip(CircleShape).background(Color(0xFFFFD54F)), contentAlignment = Alignment.Center) {
                    Text("✦", color = Color(0xFF1A0029), style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = txt, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth())
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Legacy GPT Matching — kept for backward compat
// ═══════════════════════════════════════════════════════════════════

internal suspend fun callGptForMatches(
    accessToken: String,
    noteText: String,
    triggers: List<String>,
    prodromes: List<String>,
    medicines: List<String>,
    reliefs: List<String>
): List<AiMatchItem> {
    val systemPrompt = """
You are a migraine specialist AI. The user describes their day in natural language. Your job is to figure out which items from their personal pools are LIKELY relevant — even if not explicitly mentioned.

Think like a neurologist and infer what the situation implies:

EXAMPLES:
- "went to a festival" → Loud noise, Bright light, Alcohol, Dehydration, Poor sleep, Stress, Overexertion
- "busy day at work" → Stress, Excessive screen time, Poor posture, Skipped meal, Dehydration
- "flew to Spain" → Travel, Altitude change, Dehydration, Irregular sleep, Jet lag, Poor diet
- "had pizza and beer with mates" → Alcohol, Processed food, Cheese, Late meal
- "kids kept me up all night" → Poor sleep, Sleep deprivation, Stress, Fatigue
- "spent all day painting the house" → Overexertion, Strong smell, Neck tension, Dehydration, Skipped meal
- "had a migraine this morning, took tablets and lay down" → look for medicines (tablets, pills) and reliefs (rest, dark room) in their pools
- "feeling off, bit dizzy and nauseous" → look for prodromes like Dizziness, Nausea, Fatigue
- "period started today" → Menstruation, Hormonal change
- "skipped breakfast, only had coffee" → Skipped meal, Caffeine, Dehydration
- "hungover" → Alcohol, Dehydration, Poor sleep, Nausea
- "stared at screens all day on deadline" → Excessive screen time, Stress, Poor posture, Skipped meal, Eye strain
- "went for a long run" → Overexertion, Dehydration
- "argument with partner" → Emotional stress, Stress, Anxiety
- "weather changed suddenly, got really hot" → Weather change, Dehydration, Bright light

RULES:
- ONLY return items whose EXACT label exists in the provided pools. Never invent labels.
- Be thorough — flag anything that is likely or plausible, not just certain.
- Return a JSON array only. No markdown, no explanation.
- Each item: {"label": "exact pool label", "category": "trigger|prodrome|medicine|relief"}
- If genuinely nothing matches, return: []
""".trimIndent()

    val userMessage = """
User said: "$noteText"

TRIGGER pool: ${triggers.joinToString(", ")}
PRODROME pool: ${prodromes.joinToString(", ")}
MEDICINE pool: ${medicines.joinToString(", ")}
RELIEF pool: ${reliefs.joinToString(", ")}

Which items from these pools are likely relevant to what the user described? Think broadly about implications. JSON array only.
""".trimIndent()

    val requestBody = org.json.JSONObject().apply {
        put("system_prompt", systemPrompt)
        put("user_message", userMessage)
    }

    val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/ai-setup"

    val client = okhttp3.OkHttpClient()
    val request = okhttp3.Request.Builder()
        .url(url)
        .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
        .header("Authorization", "Bearer $accessToken")
        .header("Content-Type", "application/json")
        .post(okhttp3.RequestBody.create(
            "application/json".toMediaTypeOrNull(),
            requestBody.toString()
        ))
        .build()

    val response = client.newCall(request).execute()
    val text = response.body?.string() ?: ""
    if (!response.isSuccessful) throw Exception("AI failed: ${response.code}")

    val clean = text.replace("```json", "").replace("```", "").trim()
        val arr = org.json.JSONArray(clean)
        val matches = mutableListOf<AiMatchItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val label = obj.getString("label")
            val cat = obj.getString("category")
            // Only accept labels actually in the pools
            val valid = when (cat) {
                "trigger" -> label in triggers
                "prodrome" -> label in prodromes
                "medicine" -> label in medicines
                "relief" -> label in reliefs
                else -> false
            }
            if (valid) matches.add(AiMatchItem(label, cat))
        }
    return matches
}

// ═══════════════════════════════════════════════════════════════════
//  Full Migraine Log AI Parse — covers entire wizard (legacy)
// ═══════════════════════════════════════════════════════════════════

internal suspend fun callGptForFullLogParse(
    accessToken: String,
    noteText: String,
    triggers: List<String>,
    prodromes: List<String>,
    medicines: List<String>,
    reliefs: List<String>,
    activities: List<String>,
    locations: List<String>,
    missedActivities: List<String>,
    painLocationOptions: List<String>,
    symptomOptions: List<String>
): AiLogParseResult {
    // Delegate to V2 and convert back for legacy callers
    val deterResult = deterministicParse(noteText, triggers, prodromes, medicines, reliefs, activities, locations, missedActivities, painLocationOptions, symptomOptions)
    val gptResult = try {
        callGptForFullLogParseV2(accessToken, noteText, triggers, prodromes, medicines, reliefs, activities, locations, missedActivities, painLocationOptions, symptomOptions, deterResult)
    } catch (_: Exception) { null }
    val merged = mergeResults(deterResult, gptResult)
    return AiLogParseResult(
        severity = merged.severity?.value,
        painLocations = merged.painLocations.map { it.value },
        symptoms = merged.symptoms.map { it.value },
        matches = merged.matches.map { AiMatchItem(it.label, it.category) }
    )
}

// ═══════════════════════════════════════════════════════════════════
//  Local Fallback Matching (no API cost) — legacy
// ═══════════════════════════════════════════════════════════════════

internal fun matchNoteTextLocal(text: String, triggers: List<String>, prodromes: List<String>, medicines: List<String>, reliefs: List<String>): List<AiMatchItem> {
    val lower = text.lowercase()
    val matches = mutableListOf<AiMatchItem>()
    triggers.forEach { if (hit(lower, it)) matches.add(AiMatchItem(it, "trigger")) }
    prodromes.forEach { if (hit(lower, it)) matches.add(AiMatchItem(it, "prodrome")) }
    medicines.forEach { if (hit(lower, it)) matches.add(AiMatchItem(it, "medicine")) }
    reliefs.forEach { if (hit(lower, it)) matches.add(AiMatchItem(it, "relief")) }
    return matches
}

private fun hit(text: String, label: String): Boolean {
    val l = label.lowercase()
    if (text.contains(l)) return true
    val words = l.split(" ", "_", "-").filter { it.length > 2 }
    if (words.size > 1 && words.all { text.contains(it) }) return true
    return SYNONYMS[l]?.any { text.contains(it) } == true
}

private fun catColor(cat: String): Color = when (cat) { "trigger" -> Color(0xFFFFB74D); "prodrome" -> Color(0xFF9575CD); "medicine" -> Color(0xFF4FC3F7); "relief" -> Color(0xFF81C784); else -> Color.White }

private val SYNONYMS: Map<String, List<String>> = mapOf(
    "alcohol" to listOf("wine", "beer", "drink", "drinking", "booze", "cocktail"),
    "poor sleep" to listOf("slept badly", "bad sleep", "insomnia", "couldn't sleep"),
    "skipped meal" to listOf("skipped lunch", "skipped dinner", "didn't eat", "forgot to eat"),
    "dehydration" to listOf("dehydrated", "not enough water", "thirsty"),
    "stress" to listOf("stressed", "stressful", "anxious", "overwhelmed"),
    "bright light" to listOf("bright lights", "glare", "fluorescent"),
    "loud noise" to listOf("loud", "noisy"),
    "weather change" to listOf("weather", "barometric", "storm"),
    "neck stiffness" to listOf("stiff neck", "neck pain", "tight neck"),
    "nausea" to listOf("nauseous", "queasy", "felt sick"),
    "fatigue" to listOf("exhausted", "tired", "worn out"),
    "brain fog" to listOf("foggy", "can't concentrate"),
    "light sensitivity" to listOf("sensitive to light", "photophobia"),
    "sound sensitivity" to listOf("sensitive to sound", "phonophobia"),
    "aura" to listOf("visual aura", "zigzag", "flashing"),
    "caffeine" to listOf("coffee", "espresso", "energy drink"),
    "menstruation" to listOf("period", "menstrual", "cycle"),
    "dizziness" to listOf("dizzy", "vertigo", "lightheaded"),
    "ibuprofen" to listOf("advil", "motrin", "nurofen"),
    "paracetamol" to listOf("acetaminophen", "tylenol", "panadol"),
    "sumatriptan" to listOf("imigran", "imitrex"),
    "meditation" to listOf("meditated", "mindfulness"),
    "ice" to listOf("ice pack", "cold pack", "cold compress"),
    "rest" to listOf("rested", "nap", "napped", "lay down"),
    "dark room" to listOf("darkness", "lay in dark"),
)
