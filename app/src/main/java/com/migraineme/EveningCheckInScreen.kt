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

private enum class CheckInPage { TRIGGERS, PRODROMES, MEDICINES, RELIEFS, NOTE, REVIEW }

private data class SelectableItem(
    val label: String,
    val iconKey: String? = null,
    val isFavourite: Boolean = false,
)

data class AiMatchItem(val label: String, val category: String)

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
        triggerPool.filter { it.predictionValue?.uppercase() != "NONE" }
            .map { SelectableItem(it.label, it.iconKey, it.id in triggerFavIds) }
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

    val selectedTriggers = remember { mutableStateListOf<String>() }
    val selectedProdromes = remember { mutableStateListOf<String>() }
    val selectedMedicines = remember { mutableStateListOf<String>() }
    val selectedReliefs = remember { mutableStateListOf<String>() }

    var noteText by remember { mutableStateOf("") }
    var aiMatches by remember { mutableStateOf<List<AiMatchItem>>(emptyList()) }
    var aiParsed by remember { mutableStateOf(false) }
    var aiLoading by remember { mutableStateOf(false) }

    var currentPage by remember { mutableStateOf(CheckInPage.TRIGGERS) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    fun runAiParse() {
        if (noteText.isBlank()) { aiParsed = true; return }
        val token = authState.accessToken ?: return
        aiLoading = true
        scope.launch {
            try {
                val matches = withContext(Dispatchers.IO) {
                    callGptForMatches(
                        token,
                        noteText,
                        triggerPool.map { it.label },
                        prodromePool.map { it.label },
                        medicinePool.map { it.label },
                        reliefPool.map { it.label }
                    )
                }
                aiMatches = matches
                matches.forEach { m ->
                    when (m.category) {
                        "trigger" -> if (m.label !in selectedTriggers) selectedTriggers.add(m.label)
                        "prodrome" -> if (m.label !in selectedProdromes) selectedProdromes.add(m.label)
                        "medicine" -> if (m.label !in selectedMedicines) selectedMedicines.add(m.label)
                        "relief" -> if (m.label !in selectedReliefs) selectedReliefs.add(m.label)
                    }
                }
            } catch (e: Exception) {
                Log.e("EveningCheckIn", "AI parse failed, falling back to local", e)
                // Fallback to local matching
                val matches = matchNoteTextLocal(noteText, triggerPool.map { it.label }, prodromePool.map { it.label }, medicinePool.map { it.label }, reliefPool.map { it.label })
                aiMatches = matches
                matches.forEach { m ->
                    when (m.category) {
                        "trigger" -> if (m.label !in selectedTriggers) selectedTriggers.add(m.label)
                        "prodrome" -> if (m.label !in selectedProdromes) selectedProdromes.add(m.label)
                        "medicine" -> if (m.label !in selectedMedicines) selectedMedicines.add(m.label)
                        "relief" -> if (m.label !in selectedReliefs) selectedReliefs.add(m.label)
                    }
                }
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
                    selectedTriggers.forEach { runCatching { db.insertTrigger(token, null, it, now, "evening check-in") } }
                    selectedProdromes.forEach { runCatching { db.insertProdrome(token, null, it, now, "evening check-in") } }
                    selectedMedicines.forEach { runCatching { db.insertMedicine(token, null, it, null, now, "evening check-in") } }
                    selectedReliefs.forEach { runCatching { db.insertRelief(token, null, it, now, "evening check-in") } }
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

    fun goNext() {
        if (currentPage == CheckInPage.NOTE && noteText.isNotBlank() && !aiParsed) runAiParse()
        pages.getOrNull(pageIndex + 1)?.let { currentPage = it }
    }
    fun goBack() { pages.getOrNull(pageIndex - 1)?.let { currentPage = it } }

    // ── UI ──

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
            ) { page -> when (page) {
                CheckInPage.TRIGGERS -> FavouritesPage("Any triggers today?", "Tap anything that happened", triggerItems, selectedTriggers, Color(0xFFFFB74D), { TriggerIcons.forKey(it) }) { l -> if (l in selectedTriggers) selectedTriggers.remove(l) else selectedTriggers.add(l) }
                CheckInPage.PRODROMES -> FavouritesPage("Any warning signs?", "Body signals you noticed", prodromeItems, selectedProdromes, Color(0xFF9575CD), { ProdromeIcons.forKey(it) }) { l -> if (l in selectedProdromes) selectedProdromes.remove(l) else selectedProdromes.add(l) }
                CheckInPage.MEDICINES -> FavouritesPage("Take any medicine?", "What did you take today", medicineItems, selectedMedicines, Color(0xFF4FC3F7), { MedicineIcons.forKey(it) }) { l -> if (l in selectedMedicines) selectedMedicines.remove(l) else selectedMedicines.add(l) }
                CheckInPage.RELIEFS -> FavouritesPage("Use any relief methods?", "What helped today", reliefItems, selectedReliefs, Color(0xFF81C784), { ReliefIcons.forKey(it) }) { l -> if (l in selectedReliefs) selectedReliefs.remove(l) else selectedReliefs.add(l) }
                CheckInPage.NOTE -> NotePage(noteText, { noteText = it; if (aiParsed) { aiParsed = false; aiMatches = emptyList() } }, aiLoading, aiParsed, aiMatches) { runAiParse() }
                CheckInPage.REVIEW -> ReviewPage(selectedTriggers, selectedProdromes, selectedMedicines, selectedReliefs, aiMatches.map { it.label }.toSet(), saving, saved, { selectedTriggers.remove(it) }, { selectedProdromes.remove(it) }, { selectedMedicines.remove(it) }, { selectedReliefs.remove(it) }) { save() }
            } }
        }

        if (!saved) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (currentPage == CheckInPage.TRIGGERS) {
                    TextButton(onClick = { navController.popBackStack() }, modifier = Modifier.height(36.dp)) { Text("Cancel", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall) }
                } else {
                    TextButton(onClick = { goBack() }, modifier = Modifier.height(36.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(14.dp), tint = AppTheme.SubtleTextColor); Spacer(Modifier.width(2.dp)); Text("Back", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall) }
                }
                when (currentPage) {
                    CheckInPage.REVIEW -> Button(onClick = { save() }, enabled = !saving, modifier = Modifier.height(36.dp), colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)) {
                        if (saving) { CircularProgressIndicator(Modifier.size(14.dp), Color.White, strokeWidth = 2.dp) } else { Icon(Icons.Outlined.Check, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("Save", style = MaterialTheme.typography.bodySmall) }
                    }
                    CheckInPage.NOTE -> Button(onClick = { if (noteText.isNotBlank() && !aiParsed) runAiParse(); goNext() }, enabled = !aiLoading, modifier = Modifier.height(36.dp), colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)) {
                        Text(if (noteText.isNotBlank() && !aiParsed) "Match & review" else "Review", style = MaterialTheme.typography.bodySmall); Spacer(Modifier.width(2.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(14.dp))
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
private fun FavouritesPage(title: String, subtitle: String, items: List<SelectableItem>, selected: List<String>, accentColor: Color, iconResolver: (String?) -> ImageVector?, onToggle: (String) -> Unit) {
    val scrollState = rememberScrollState()
    var showAll by remember { mutableStateOf(false) }
    val favourites = remember(items) { items.filter { it.isFavourite } }
    val others = remember(items) { items.filter { !it.isFavourite } }

    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))

        if (favourites.isNotEmpty()) {
            Text("Favourites", color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                favourites.forEach { item ->
                    CheckInCircle(item.label, iconResolver(item.iconKey) ?: iconResolver(item.label.lowercase()), item.label in selected, accentColor) { onToggle(item.label) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (others.isNotEmpty()) {
            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
            Row(Modifier.fillMaxWidth().clickable { showAll = !showAll }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (showAll) "Show less" else "Show all (${others.size} more)", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                Icon(if (showAll) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(20.dp))
            }
            AnimatedVisibility(visible = showAll) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    others.forEach { item ->
                        CheckInCircle(item.label, iconResolver(item.iconKey) ?: iconResolver(item.label.lowercase()), item.label in selected, accentColor) { onToggle(item.label) }
                    }
                }
            }
        }

        val count = selected.size
        if (count > 0) {
            Spacer(Modifier.height(8.dp))
            Text("$count selected", color = accentColor, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(80.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Note Page
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun NotePage(noteText: String, onNoteChange: (String) -> Unit, aiLoading: Boolean, aiParsed: Boolean, aiMatches: List<AiMatchItem>, onParse: () -> Unit) {
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
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Describe your day…")
        }
        try { speechLauncher.launch(intent) } catch (_: Exception) {
            android.widget.Toast.makeText(context, "Voice input not available", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("Tell our AI about your day", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(4.dp))
        Text("Type or speak freely — we'll match it to your triggers, prodromes, medicines and reliefs as best we can", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = noteText, onValueChange = onNoteChange,
            placeholder = { Text("e.g. \"had red wine, neck felt stiff, took ibuprofen\"", color = AppTheme.SubtleTextColor.copy(alpha = 0.5f)) },
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

        if (aiParsed && aiMatches.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Found ${aiMatches.size} match${if (aiMatches.size > 1) "es" else ""} — added to review:", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            aiMatches.forEach { m ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(catColor(m.category).copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.label, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
                    Text(m.category.replaceFirstChar { it.uppercase() }, color = catColor(m.category), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (aiParsed && aiMatches.isEmpty() && noteText.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text("No extra matches found", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
        }
        if (noteText.isBlank()) {
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(80.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Review Page
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun ReviewPage(triggers: List<String>, prodromes: List<String>, medicines: List<String>, reliefs: List<String>, aiLabels: Set<String>, saving: Boolean, saved: Boolean, rmTrigger: (String) -> Unit, rmProdrome: (String) -> Unit, rmMedicine: (String) -> Unit, rmRelief: (String) -> Unit, onSave: () -> Unit) {
    val scrollState = rememberScrollState()
    val total = triggers.size + prodromes.size + medicines.size + reliefs.size

    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text("Review", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(4.dp))
        Text(LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM")), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
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

        if (triggers.isNotEmpty()) ReviewSection("Triggers", Color(0xFFFFB74D), triggers, aiLabels, rmTrigger)
        if (prodromes.isNotEmpty()) ReviewSection("Prodromes", Color(0xFF9575CD), prodromes, aiLabels, rmProdrome)
        if (medicines.isNotEmpty()) ReviewSection("Medicines", Color(0xFF4FC3F7), medicines, aiLabels, rmMedicine)
        if (reliefs.isNotEmpty()) ReviewSection("Reliefs", Color(0xFF81C784), reliefs, aiLabels, rmRelief)

        if (total > 0 && !saved) {
            Spacer(Modifier.height(12.dp))
            Text("Logging $total item${if (total > 1) "s" else ""}", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun ReviewSection(title: String, color: Color, items: List<String>, aiLabels: Set<String>, onRemove: (String) -> Unit) {
    Spacer(Modifier.height(12.dp))
    Text("$title (${items.size})", color = color, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
    Spacer(Modifier.height(8.dp))
    items.forEach { label ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp).background(color.copy(alpha = 0.10f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(label, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                if (label in aiLabels) Text("matched from note", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = { onRemove(label) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Close, "Remove", tint = color.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Circle Button
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun CheckInCircle(label: String, icon: ImageVector?, isSelected: Boolean, color: Color, onClick: () -> Unit) {
    val bg = if (isSelected) color.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.08f)
    val bdr = if (isSelected) color.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.12f)
    val tint = if (isSelected) Color.White else AppTheme.SubtleTextColor
    val txt = if (isSelected) Color.White else AppTheme.BodyTextColor

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onClick)) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(bg).border(1.5.dp, bdr, CircleShape), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
            else Text(label.take(2).uppercase(), color = tint, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = txt, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth())
    }
}

// ═══════════════════════════════════════════════════════════════════
//  GPT Matching via Edge Function
// ═══════════════════════════════════════════════════════════════════

private suspend fun callGptForMatches(
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
//  Local Fallback Matching (no API cost)
// ═══════════════════════════════════════════════════════════════════

private fun matchNoteTextLocal(text: String, triggers: List<String>, prodromes: List<String>, medicines: List<String>, reliefs: List<String>): List<AiMatchItem> {
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
