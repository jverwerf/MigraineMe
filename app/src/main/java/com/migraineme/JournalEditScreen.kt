package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
    var extraLabel by remember { mutableStateOf<String?>(null) } // amount for medicine
    var startAt by remember { mutableStateOf(Instant.now()) }
    var endAt by remember { mutableStateOf<Instant?>(null) } // end time for relief
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
                            extraLabel = row.amount
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
                            startAt = Instant.parse(row.startAt)
                            endAt = row.endAt?.takeIf { it.isNotBlank() && it != row.startAt }?.let { Instant.parse(it) }
                            notes = row.notes ?: ""
                            reliefScale = ReliefScale.fromString(row.reliefScale)
                            sideEffectScale = row.sideEffectScale ?: "NONE"
                            sideEffectNotes = row.sideEffectNotes ?: ""
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
        topBar = {
            TopAppBar(
                title = { Text("Edit $typeTitle", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppTheme.BaseCardContainer),
            )
        },
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
            // ── Item name (read-only) ──
            BaseCard {
                Text(typeTitle, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    label.ifBlank { "Unknown" },
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }

            // ── Amount (medicine only) ──
            if (itemType == "medicine") {
                BaseCard {
                    Text("Amount", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = extraLabel ?: "",
                        onValueChange = { extraLabel = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. 500mg, 2 tablets…", color = AppTheme.SubtleTextColor) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AppTheme.AccentPurple,
                            unfocusedBorderColor = AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                            cursorColor = AppTheme.AccentPurple,
                        ),
                    )
                }
            }

            // ── Date & Time ──
            BaseCard {
                Text("When", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Date picker button
                    val datePickerState = rememberDatePickerState(
                        initialSelectedDateMillis = selectedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    )
                    var showDatePicker by remember { mutableStateOf(false) }

                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) {
                        Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(selectedDate.format(DateTimeFormatter.ofPattern("dd MMM yyyy")))
                    }

                    if (showDatePicker) {
                        DatePickerDialog(
                            onDismissRequest = { showDatePicker = false },
                            confirmButton = {
                                TextButton(onClick = {
                                    datePickerState.selectedDateMillis?.let {
                                        selectedDate = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                                    }
                                    showDatePicker = false
                                }) { Text("OK") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
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
                                    selectedTime = LocalTime.of(timePickerState.hour, timePickerState.minute)
                                    showTimePicker = false
                                }) { Text("OK") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
                            },
                            text = { TimePicker(state = timePickerState) },
                        )
                    }
                }
            }

            // ── End time (relief only) ──
            if (itemType == "relief") {
                val endZoned = endAt?.atZone(ZoneId.systemDefault())
                var selectedEndDate by remember(loaded) { mutableStateOf(endZoned?.toLocalDate() ?: selectedDate) }
                var selectedEndTime by remember(loaded) { mutableStateOf(endZoned?.toLocalTime() ?: selectedTime) }

                // Sync endAt whenever end date/time changes
                LaunchedEffect(selectedEndDate, selectedEndTime) {
                    endAt = ZonedDateTime.of(selectedEndDate, selectedEndTime, ZoneId.systemDefault()).toInstant()
                }

                BaseCard {
                    Text("End time", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val endDatePickerState = rememberDatePickerState(
                            initialSelectedDateMillis = selectedEndDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        )
                        var showEndDatePicker by remember { mutableStateOf(false) }

                        OutlinedButton(
                            onClick = { showEndDatePicker = true },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        ) {
                            Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(selectedEndDate.format(DateTimeFormatter.ofPattern("dd MMM yyyy")))
                        }

                        if (showEndDatePicker) {
                            DatePickerDialog(
                                onDismissRequest = { showEndDatePicker = false },
                                confirmButton = {
                                    TextButton(onClick = {
                                        endDatePickerState.selectedDateMillis?.let {
                                            selectedEndDate = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                                        }
                                        showEndDatePicker = false
                                    }) { Text("OK") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showEndDatePicker = false }) { Text("Cancel") }
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
                                        selectedEndTime = LocalTime.of(endTimePickerState.hour, endTimePickerState.minute)
                                        showEndTimePicker = false
                                    }) { Text("OK") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showEndTimePicker = false }) { Text("Cancel") }
                                },
                                text = { TimePicker(state = endTimePickerState) },
                            )
                        }
                    }
                }
            }

            // ── Notes (migraine only — medicines & reliefs use side effect notes) ──
            if (itemType == "migraine") {
            BaseCard {
                Text("Notes", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Add notes…", color = AppTheme.SubtleTextColor) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = AppTheme.AccentPurple,
                        unfocusedBorderColor = AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                        cursorColor = AppTheme.AccentPurple,
                    ),
                    minLines = 2,
                    maxLines = 5,
                )
            }
            }

            // ── Relief scale + Side effects (medicine & relief only) ──
            if (itemType == "medicine" || itemType == "relief") {
                BaseCard {
                    Text("How much relief?", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReliefScale.entries.forEach { scale ->
                            FilterChip(
                                selected = reliefScale == scale,
                                onClick = { reliefScale = scale },
                                label = { Text(scale.display, style = MaterialTheme.typography.labelSmall) },
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

                    Text("Any side effects?", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("NONE" to "None", "SOFT" to "Soft", "MODERATE" to "Moderate", "SEVERE" to "Severe").forEach { (key, display) ->
                            val seColor = when (key) { "NONE" -> Color(0xFF81C784); "SOFT" -> Color(0xFFFFB74D); "MODERATE" -> Color(0xFFFF8A65); else -> Color(0xFFE57373) }
                            FilterChip(
                                selected = sideEffectScale == key,
                                onClick = { sideEffectScale = key },
                                label = { Text(display, style = MaterialTheme.typography.labelSmall) },
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
                        label = { Text("Side effect notes", color = AppTheme.SubtleTextColor) },
                        placeholder = { Text("e.g. drowsiness, nausea…", color = AppTheme.SubtleTextColor) },
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
                                    android.widget.Toast.makeText(context, "Voice input not available", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Outlined.Mic, contentDescription = "Voice input", tint = AppTheme.AccentPurple, modifier = Modifier.size(20.dp))
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
                                    "medicine" -> db.updateMedicine(token, itemId, startAt = newStartAt, amount = extraLabel?.ifBlank { null }, notes = notes, reliefScale = reliefScale.name, sideEffectScale = sideEffectScale, sideEffectNotes = sideEffectNotes.ifBlank { null })
                                    "relief" -> db.updateRelief(token, itemId, startAt = newStartAt, endAt = endAt?.toString(), notes = notes, reliefScale = reliefScale.name, sideEffectScale = sideEffectScale, sideEffectNotes = sideEffectNotes.ifBlank { null })
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
                            auth.accessToken?.let { logVm?.loadJournal(it) }
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
                Text("Save changes", color = Color.White)
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
                Text("Delete")
            }

            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("Delete $typeTitle?") },
                    text = { Text("This can't be undone.") },
                    confirmButton = {
                        TextButton(onClick = {
                            scope.launch {
                                val token = auth.accessToken ?: return@launch
                                withContext(Dispatchers.IO) {
                                    try {
                                        when (itemType) {
                                            "trigger" -> db.deleteTrigger(token, itemId)
                                            "medicine" -> db.deleteMedicine(token, itemId)
                                            "relief" -> db.deleteRelief(token, itemId)
                                            "prodrome" -> db.deleteProdromeLog(token, itemId)
                                            "activity" -> db.deleteActivityLog(token, itemId)
                                            "location" -> db.deleteLocationLog(token, itemId)
                                            else -> {}
                                        }
                                    } catch (_: Exception) {}
                                }
                                confirmDelete = false
                                auth.accessToken?.let { logVm?.loadJournal(it) }
                                onDeleted()
                            }
                        }) { Text("Delete", color = Color(0xFFE57373)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                    },
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}
