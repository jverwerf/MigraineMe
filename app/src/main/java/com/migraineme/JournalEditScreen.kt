package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Generic edit screen for a single journal item.
 *
 * Supports: trigger, medicine, relief, prodrome, activity, location
 * Shows: item type label (read-only), date/time pickers, notes, save/delete.
 *
 * NOT used for migraines — those open the full wizard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalEditScreen(
    itemType: String, // "trigger", "medicine", "relief", "prodrome", "activity", "location"
    itemId: String,
    authVm: AuthViewModel,
    logVm: LogViewModel? = null,
    onBack: () -> Unit,
    onDeleted: () -> Unit = onBack,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth by authVm.state.collectAsState()
    val db = remember { SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) }

    // Editable state
    var label by remember { mutableStateOf("") }
    // One-unit system: medicine amount is a number; the unit is fixed
    // (log's stamped unit wins, then legacy text, then the pool medicine's).
    var doseText by remember { mutableStateOf("") }
    var doseUnit by remember { mutableStateOf(DoseUnits.MG) }
    var inputUnit by remember { mutableStateOf(DoseUnits.MG) }
    var startAt by remember { mutableStateOf(Instant.now()) }
    var endAt by remember { mutableStateOf<Instant?>(null) } // end time for relief
    // Relief: which pool item the row is — the raw stored label, re-pickable
    // from the user's own pool (Frequent, then All), matching EditReliefScreen.
    var reliefType by remember { mutableStateOf("") }
    var reliefFrequent by remember { mutableStateOf<List<String>>(emptyList()) }
    var reliefAll by remember { mutableStateOf<List<String>>(emptyList()) }
    // Label -> pool icon_key, so the picker can show each relief's Brainy art.
    var reliefIconKeys by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var reliefTypeMenuOpen by remember { mutableStateOf(false) }
    var hasEnd by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reliefScale by remember { mutableStateOf(ReliefScale.NONE) }
    var sideEffectScale by remember { mutableStateOf("NONE") }
    var sideEffectNotes by remember { mutableStateOf("") }

    val context = LocalContext.current
    val seSpeechLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                sideEffectNotes = if (sideEffectNotes.isBlank()) spoken else "$sideEffectNotes, $spoken"
            }
        }
    }

    // Voice input for the general Notes field, same contract as the
    // side-effect one: appends the spoken text to what is already there.
    val notesSpeechLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                notes = if (notes.isBlank()) spoken else "$notes, $spoken"
            }
        }
    }

    // Trigger label map for display
    var triggerLabelMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // Load the item
    LaunchedEffect(itemId) {
        val token = auth.accessToken ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                // Load trigger labels for display
                val edge = EdgeFunctionsService()
                val defs = edge.getTriggerDefinitions(ctx)
                triggerLabelMap = defs.associate { it.triggerType to it.label }

                when (itemType) {
                    "trigger" -> {
                        val rows = db.getAllTriggers(token)
                        val row = rows.find { it.id == itemId }
                        if (row != null) {
                            label = row.type?.let { t -> triggerLabelMap[t] ?: t.replace("_", " ").replaceFirstChar { c -> c.uppercase() } } ?: ""
                            startAt = Instant.parse(row.startAt)
                            notes = row.notes ?: ""
                        }
                    }
                    "medicine" -> {
                        val rows = db.getAllMedicines(token)
                        val row = rows.find { it.id == itemId }
                        if (row != null) {
                            label = row.name ?: ""
                            val poolUnit = runCatching { db.getAllMedicinePool(token) }.getOrNull()
                                ?.find { it.label == row.name }?.doseUnit
                            doseUnit = DoseUnits.unitForEdit(row.doseUnit, row.amount, poolUnit)
                            inputUnit = DoseUnits.inputOptions(doseUnit).first()
                            doseText = row.doseValue?.let { DoseUnits.formatValue(it) }
                                ?: DoseUnits.parseLegacy(row.amount)?.first?.let { DoseUnits.formatValue(it) }
                                ?: ""
                            startAt = Instant.parse(row.startAt)
                            notes = row.notes ?: ""
                            reliefScale = ReliefScale.fromString(row.reliefScale)
                            sideEffectScale = row.sideEffectScale ?: "NONE"
                            sideEffectNotes = row.sideEffectNotes ?: ""
                        }
                    }
                    "relief" -> {
                        val rows = db.getAllReliefs(token)
                        val row = rows.find { it.id == itemId }
                        if (row != null) {
                            label = row.type ?: ""
                            reliefType = row.type ?: ""
                            startAt = Instant.parse(row.startAt)
                            endAt = row.endAt?.takeIf { it.isNotBlank() && it != row.startAt }?.let { Instant.parse(it) }
                            hasEnd = endAt != null
                            notes = row.notes ?: ""
                            reliefScale = ReliefScale.fromString(row.reliefScale)
                            sideEffectScale = row.sideEffectScale ?: "NONE"
                            sideEffectNotes = row.sideEffectNotes ?: ""
                        }
                        // Pool options for the type picker — frequent prefs in
                        // position order first, the rest alphabetised. Best
                        // effort: an empty pool leaves the name read-only.
                        val prefs = runCatching { db.getReliefPrefs(token) }.getOrNull().orEmpty()
                        val pool = runCatching { db.getAllReliefPool(token) }.getOrNull().orEmpty()
                        val freq = prefs
                            .filter { it.status == "frequent" }
                            .sortedBy { it.position }
                            .mapNotNull { it.relief?.label?.trim() }
                            .filter { it.isNotEmpty() }
                        reliefFrequent = freq
                        reliefAll = pool.map { it.label.trim() }
                            .filter { it.isNotEmpty() && it !in freq }
                            .sorted()
                        reliefIconKeys = buildMap {
                            pool.forEach { put(it.label.trim(), it.iconKey) }
                            prefs.forEach { p -> p.relief?.let { r -> put(r.label.trim(), r.iconKey) } }
                        }
                    }
                    "prodrome" -> {
                        val rows = db.getAllProdromeLog(token)
                        val row = rows.find { it.id == itemId }
                        if (row != null) {
                            label = row.type?.replace("_", " ")?.replaceFirstChar { c -> c.uppercase() } ?: ""
                            startAt = row.startAt?.let { Instant.parse(it) } ?: Instant.now()
                            notes = row.notes ?: ""
                        }
                    }
                    "activity" -> {
                        val rows = db.getAllActivityLog(token)
                        val row = rows.find { it.id == itemId }
                        if (row != null) {
                            label = row.type?.replace("_", " ")?.replaceFirstChar { c -> c.uppercase() } ?: ""
                            startAt = row.startAt?.let { Instant.parse(it) } ?: Instant.now()
                            notes = row.notes ?: ""
                        }
                    }
                    "location" -> {
                        val rows = db.getAllLocationLog(token)
                        val row = rows.find { it.id == itemId }
                        if (row != null) {
                            label = row.type?.replace("_", " ")?.replaceFirstChar { c -> c.uppercase() } ?: ""
                            startAt = Instant.parse(row.startAt)
                            notes = row.notes ?: ""
                        }
                    }
                }
            } catch (e: Exception) {
                error = "Failed to load: ${e.message}"
            }
        }
        loaded = true
    }

    // Date/time derived from startAt
    val zoned = startAt.atZone(ZoneId.systemDefault())
    var selectedDate by remember(loaded) { mutableStateOf(zoned.toLocalDate()) }
    var selectedTime by remember(loaded) { mutableStateOf(zoned.toLocalTime()) }

    val typeTitle = when (itemType) {
        "trigger" -> "Trigger"
        "medicine" -> "Medicine"
        "relief" -> "Relief"
        "prodrome" -> "Prodrome"
        "activity" -> "Activity"
        "location" -> "Location"
        else -> "Item"
    }

    Scaffold(
        containerColor = AppTheme.FadeColor,
    ) { padding ->

        if (!loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppTheme.AccentPurple)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Item name. Reliefs are re-pickable from the user's own pool
            // (Frequent, then All) — same dropdown as EditReliefScreen;
            // everything else stays read-only. ──
            if (itemType == "relief" && (reliefFrequent.isNotEmpty() || reliefAll.isNotEmpty())) {
                BaseCard {
                    Text(typeTitle, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            BrainyRowIcon(reliefType, iconKey = reliefIconKeys[reliefType], category = "relief", size = 24.dp)
                            Text(
                                reliefType.ifBlank { "Unknown" },
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { reliefTypeMenuOpen = true }) {
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = t("Choose type"), tint = AppTheme.SubtleTextColor)
                            }
                        }
                        DropdownMenu(
                            expanded = reliefTypeMenuOpen,
                            onDismissRequest = { reliefTypeMenuOpen = false },
                        ) {
                            if (reliefFrequent.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(t("Frequent"), fontWeight = FontWeight.Bold) },
                                    onClick = {},
                                    enabled = false,
                                )
                                reliefFrequent.forEach { opt ->
                                    DropdownMenuItem(
                                        text = { Text(opt) },
                                        leadingIcon = { BrainyRowIcon(opt, iconKey = reliefIconKeys[opt], category = "relief", size = 24.dp, gap = 0.dp) },
                                        onClick = {
                                            reliefType = opt
                                            reliefTypeMenuOpen = false
                                        },
                                    )
                                }
                                if (reliefAll.isNotEmpty()) Divider()
                            }
                            if (reliefAll.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(t("All"), fontWeight = FontWeight.Bold) },
                                    onClick = {},
                                    enabled = false,
                                )
                                reliefAll.forEach { opt ->
                                    DropdownMenuItem(
                                        text = { Text(opt) },
                                        leadingIcon = { BrainyRowIcon(opt, iconKey = reliefIconKeys[opt], category = "relief", size = 24.dp, gap = 0.dp) },
                                        onClick = {
                                            reliefType = opt
                                            reliefTypeMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                BaseCard {
                    Text(typeTitle, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        label.ifBlank { "Unknown" },
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }

            // ── Amount (medicine only) — number only, unit fixed by the medicine ──
            if (itemType == "medicine") {
                BaseCard {
                    Text(t("Amount"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    DoseAmountInput(
                        doseUnit = doseUnit,
                        valueText = doseText,
                        onValueTextChange = { doseText = it },
                        inputUnit = inputUnit,
                        onInputUnitChange = { inputUnit = it },
                        accent = AppTheme.AccentPurple,
                        label = t("Amount"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AppTheme.AccentPurple,
                            unfocusedBorderColor = AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                            cursorColor = AppTheme.AccentPurple,
                            focusedLabelColor = AppTheme.SubtleTextColor,
                            unfocusedLabelColor = AppTheme.SubtleTextColor,
                        ),
                    )
                }
            }

            // ── Date & Time ──
            BaseCard {
                Text(t("When"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Date picker button — log entries never sit in the future
                    val datePickerState = rememberDatePickerState(
                        initialSelectedDateMillis = localDateToDatePickerMillis(selectedDate),
                        selectableDates = PastOnlyDates
                    )
                    var showDatePicker by remember { mutableStateOf(false) }

                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) {
                        Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(selectedDate.format(DateTimeFormatter.ofPattern("dd MMM yyyy", appLocale())))
                    }

                    if (showDatePicker) {
                        DatePickerDialog(
                            onDismissRequest = { showDatePicker = false },
                            confirmButton = {
                                TextButton(onClick = {
                                    datePickerState.selectedDateMillis?.let {
                                        selectedDate = datePickerMillisToLocalDate(it)
                                    }
                                    // Moving onto today can strand the kept time ahead of now — clamp
                                    if (selectedDate == LocalDate.now() && selectedTime.isAfter(LocalTime.now())) {
                                        selectedTime = LocalTime.now()
                                    }
                                    showDatePicker = false
                                }) { Text(t("OK")) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDatePicker = false }) { Text(t("Cancel")) }
                            },
                        ) {
                            DatePicker(state = datePickerState)
                        }
                    }

                    // Time picker button
                    val timePickerState = rememberTimePickerState(
                        initialHour = selectedTime.hour,
                        initialMinute = selectedTime.minute,
                    )
                    var showTimePicker by remember { mutableStateOf(false) }

                    OutlinedButton(
                        onClick = { showTimePicker = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) {
                        Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(selectedTime.format(DateTimeFormatter.ofPattern("HH:mm")))
                    }

                    if (showTimePicker) {
                        AlertDialog(
                            onDismissRequest = { showTimePicker = false },
                            confirmButton = {
                                TextButton(onClick = {
                                    val picked = LocalTime.of(timePickerState.hour, timePickerState.minute)
                                    // Today + a later clock time would be a future log — clamp to now
                                    selectedTime = if (selectedDate == LocalDate.now() && picked.isAfter(LocalTime.now())) {
                                        LocalTime.now()
                                    } else picked
                                    showTimePicker = false
                                }) { Text(t("OK")) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showTimePicker = false }) { Text(t("Cancel")) }
                            },
                            text = { TimePicker(state = timePickerState) },
                        )
                    }
                }
            }

            // ── End time (relief only) — a plain End time card under When,
            // no minutes field, no toggle. It stays optional: unset rows show
            // "Not set" buttons (tapping one starts an end at now) and the x
            // clears back to no end, because a bare relief log is always
            // valid. The old version synced endAt from the pickers
            // unconditionally, so every save fabricated end_at = start_at on
            // reliefs that had no end. Taken-only reliefs (Water, Caffeine…)
            // get no end-time UI at all. ──
            if (itemType == "relief" && !DoseUnits.isTakenOnlyRelief(reliefType)) {
                val endZoned = endAt?.atZone(ZoneId.systemDefault())
                // No stored end → pickers default to NOW, not the start time
                // (iOS parity): toggling "Set end time" on and saving untouched
                // must not fabricate a zero-minute end_at = start_at.
                var selectedEndDate by remember(loaded) { mutableStateOf(endZoned?.toLocalDate() ?: LocalDate.now()) }
                var selectedEndTime by remember(loaded) { mutableStateOf(endZoned?.toLocalTime() ?: LocalTime.now()) }

                // Sync endAt from the pickers only while an end is wanted
                LaunchedEffect(selectedEndDate, selectedEndTime, hasEnd) {
                    endAt = if (hasEnd) {
                        ZonedDateTime.of(selectedEndDate, selectedEndTime, ZoneId.systemDefault()).toInstant()
                    } else null
                }

                BaseCard {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(t("End time"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        if (hasEnd) {
                            IconButton(onClick = { hasEnd = false }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Outlined.Close, contentDescription = t("Clear"), tint = AppTheme.SubtleTextColor, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    if (!hasEnd) {
                        // Placeholder buttons — tapping either starts an end
                        // at now; the real pickers replace them right away.
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    selectedEndDate = LocalDate.now()
                                    selectedEndTime = LocalTime.now()
                                    hasEnd = true
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.SubtleTextColor),
                            ) {
                                Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(t("Not set"))
                            }
                            OutlinedButton(
                                onClick = {
                                    selectedEndDate = LocalDate.now()
                                    selectedEndTime = LocalTime.now()
                                    hasEnd = true
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.SubtleTextColor),
                            ) {
                                Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(t("Not set"))
                            }
                        }
                    }

                    if (hasEnd) {

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val endDatePickerState = rememberDatePickerState(
                            initialSelectedDateMillis = localDateToDatePickerMillis(selectedEndDate),
                            selectableDates = PastOnlyDates
                        )
                        var showEndDatePicker by remember { mutableStateOf(false) }

                        OutlinedButton(
                            onClick = { showEndDatePicker = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        ) {
                            Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(selectedEndDate.format(DateTimeFormatter.ofPattern("dd MMM yyyy", appLocale())))
                        }

                        if (showEndDatePicker) {
                            DatePickerDialog(
                                onDismissRequest = { showEndDatePicker = false },
                                confirmButton = {
                                    TextButton(onClick = {
                                        endDatePickerState.selectedDateMillis?.let {
                                            selectedEndDate = datePickerMillisToLocalDate(it)
                                        }
                                        // Moving onto today can strand the kept time ahead of now — clamp
                                        if (selectedEndDate == LocalDate.now() && selectedEndTime.isAfter(LocalTime.now())) {
                                            selectedEndTime = LocalTime.now()
                                        }
                                        showEndDatePicker = false
                                    }) { Text(t("OK")) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showEndDatePicker = false }) { Text(t("Cancel")) }
                                },
                            ) {
                                DatePicker(state = endDatePickerState)
                            }
                        }

                        val endTimePickerState = rememberTimePickerState(
                            initialHour = selectedEndTime.hour,
                            initialMinute = selectedEndTime.minute,
                        )
                        var showEndTimePicker by remember { mutableStateOf(false) }

                        OutlinedButton(
                            onClick = { showEndTimePicker = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        ) {
                            Icon(Icons.Outlined.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(selectedEndTime.format(DateTimeFormatter.ofPattern("HH:mm")))
                        }

                        if (showEndTimePicker) {
                            AlertDialog(
                                onDismissRequest = { showEndTimePicker = false },
                                confirmButton = {
                                    TextButton(onClick = {
                                        val picked = LocalTime.of(endTimePickerState.hour, endTimePickerState.minute)
                                        // Today + a later clock time would be a future log — clamp to now
                                        selectedEndTime = if (selectedEndDate == LocalDate.now() && picked.isAfter(LocalTime.now())) {
                                            LocalTime.now()
                                        } else picked
                                        showEndTimePicker = false
                                    }) { Text(t("OK")) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showEndTimePicker = false }) { Text(t("Cancel")) }
                                },
                                text = { TimePicker(state = endTimePickerState) },
                            )
                        }
                    }
                    }
                }
            }

            // ── Notes (all types, matching iOS; side effect notes stay separate) ──
            BaseCard {
                Text(t("Notes"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(t("Add notes…"), color = AppTheme.SubtleTextColor) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AppTheme.AccentPurple,
                        unfocusedBorderColor = AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                        cursorColor = AppTheme.AccentPurple,
                    ),
                    trailingIcon = {
                        // Same voice input as the side-effect notes field; the
                        // screen opens on a note that already exists, so
                        // dictation appends to it.
                        IconButton(onClick = {
                            val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            }
                            try { notesSpeechLauncher.launch(intent) } catch (_: Exception) {
                                android.widget.Toast.makeText(context, tSync("Voice input not available"), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Outlined.Mic, contentDescription = t("Voice input"), tint = AppTheme.AccentPurple, modifier = Modifier.size(20.dp))
                        }
                    },
                    minLines = 2,
                    maxLines = 5,
                )
            }

            // ── Relief scale + Side effects (medicine & relief only) ──
            if (itemType == "medicine" || itemType == "relief") {
                BaseCard {
                    Text(t("How much relief?"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReliefScale.entries.forEach { scale ->
                            FilterChip(
                                selected = reliefScale == scale,
                                onClick = { reliefScale = scale },
                                label = { Text(t(scale.display), style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = scale.color.copy(alpha = 0.3f),
                                    selectedLabelColor = Color.White,
                                    containerColor = Color.White.copy(alpha = 0.06f),
                                    labelColor = AppTheme.SubtleTextColor
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = reliefScale == scale,
                                    borderColor = Color.White.copy(alpha = 0.12f),
                                    selectedBorderColor = scale.color.copy(alpha = 0.6f)
                                )
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(t("Any side effects?"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("NONE" to "None", "SOFT" to "Soft", "MODERATE" to "Moderate", "SEVERE" to "Severe").forEach { (key, display) ->
                            val seColor = when (key) { "NONE" -> Color(0xFF81C784); "SOFT" -> Color(0xFFFFB74D); "MODERATE" -> Color(0xFFFF8A65); else -> Color(0xFFE57373) }
                            FilterChip(
                                selected = sideEffectScale == key,
                                onClick = { sideEffectScale = key },
                                label = { Text(t(display), style = MaterialTheme.typography.labelSmall) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = seColor.copy(alpha = 0.3f),
                                    selectedLabelColor = Color.White,
                                    containerColor = Color.White.copy(alpha = 0.06f),
                                    labelColor = AppTheme.SubtleTextColor
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = sideEffectScale == key,
                                    borderColor = Color.White.copy(alpha = 0.12f),
                                    selectedBorderColor = seColor.copy(alpha = 0.6f)
                                )
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sideEffectNotes,
                        onValueChange = { sideEffectNotes = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(t("Side effect notes"), color = AppTheme.SubtleTextColor) },
                        placeholder = { Text(t("e.g. drowsiness, nausea…"), color = AppTheme.SubtleTextColor) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AppTheme.AccentPurple,
                            unfocusedBorderColor = AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                            cursorColor = AppTheme.AccentPurple,
                        ),
                        trailingIcon = {
                            IconButton(onClick = {
                                val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Describe side effects…")
                                }
                                try { seSpeechLauncher.launch(intent) } catch (_: Exception) {
                                    android.widget.Toast.makeText(context, tSync("Voice input not available"), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Outlined.Mic, contentDescription = t("Voice input"), tint = AppTheme.AccentPurple, modifier = Modifier.size(20.dp))
                            }
                        },
                        minLines = 1,
                        maxLines = 3,
                    )
                }
            }

            // ── Error ──
            if (error != null) {
                Text(error!!, color = Color(0xFFE57373), style = MaterialTheme.typography.bodySmall)
            }

            // ── Save button ──
            Button(
                onClick = {
                    saving = true
                    error = null
                    scope.launch {
                        val token = auth.accessToken
                        if (token.isNullOrBlank()) {
                            error = "Not signed in"
                            saving = false
                            return@launch
                        }
                        withContext(Dispatchers.IO) {
                            try {
                                val newStartAt = ZonedDateTime.of(selectedDate, selectedTime, ZoneId.systemDefault())
                                    .toInstant().toString()
                                when (itemType) {
                                    "trigger" -> db.updateTrigger(token, itemId, startAt = newStartAt, notes = notes)
                                    "medicine" -> {
                                        val doseValue = DoseUnits.parseNumber(doseText)
                                            ?.let { DoseUnits.toStored(it, doseUnit, inputUnit) }
                                        db.updateMedicine(token, itemId, startAt = newStartAt, amount = null, notes = notes, reliefScale = reliefScale.name, sideEffectScale = sideEffectScale, sideEffectNotes = sideEffectNotes.ifBlank { null }, doseValue = doseValue, doseUnit = if (doseValue != null) doseUnit else null)
                                    }
                                    "relief" -> {
                                        // End card set → end_at, cleared → the stored end
                                        // time is wiped (a bare relief log is always valid).
                                        val resolvedEnd: Instant? =
                                            if (DoseUnits.isTakenOnlyRelief(reliefType) || !hasEnd) null else endAt
                                        db.updateRelief(
                                            token, itemId,
                                            type = reliefType.ifBlank { null },
                                            startAt = newStartAt,
                                            endAt = resolvedEnd?.toString(),
                                            clearEndAt = resolvedEnd == null,
                                            notes = notes,
                                            reliefScale = reliefScale.name,
                                            sideEffectScale = sideEffectScale,
                                            sideEffectNotes = sideEffectNotes.ifBlank { null }
                                        )
                                    }
                                    "prodrome" -> db.updateProdromeLog(token, itemId, type = null, startAt = newStartAt, notes = notes)
                                    "activity" -> db.updateActivityLog(token, itemId, type = null, startAt = newStartAt, notes = notes)
                                    "location" -> db.updateLocationLog(token, itemId, type = null, startAt = newStartAt, notes = notes)
                                    else -> {}
                                }
                            } catch (e: Exception) {
                                error = "Save failed: ${e.message}"
                            }
                        }
                        saving = false
                        if (error == null) {
                            // Just this row, back into the window the feed already
                            // holds — not a reload of the whole journal.
                            auth.accessToken?.let { logVm?.refreshJournalEntry(it, itemType, itemId) }
                            onBack()
                        }
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(8.dp))
                }
                Text(t("Save changes"), color = Color.White)
            }

            // ── Delete button ──
            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE57373)),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, tint = Color(0xFFE57373), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(t("Delete"))
            }

            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text(t("Delete %s?", typeTitle)) },
                    text = { Text(t("This can't be undone.")) },
                    confirmButton = {
                        TextButton(onClick = {
                            scope.launch {
                                val token = auth.accessToken ?: return@launch
                                withContext(Dispatchers.IO) {
                                    try {
                                        when (itemType) {
                                            "trigger" -> db.deleteTrigger(token, itemId)
                                            "medicine" -> db.deleteMedicine(token, itemId)
                                            "relief" -> {
                                                db.deleteRelief(token, itemId)
                                                DeviceReliefOutcomeWorker.cancel(ctx, itemId)
                                            }
                                            "prodrome" -> db.deleteProdromeLog(token, itemId)
                                            "activity" -> db.deleteActivityLog(token, itemId)
                                            "location" -> db.deleteLocationLog(token, itemId)
                                            else -> {}
                                        }
                                    } catch (_: Exception) {}
                                }
                                confirmDelete = false
                                // The row is gone on the server; drop it from the
                                // loaded feed rather than rebuilding the feed.
                                logVm?.removeFromJournal(itemId)
                                onDeleted()
                            }
                        }) { Text(t("Delete"), color = Color(0xFFE57373)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDelete = false }) { Text(t("Cancel")) }
                    },
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
