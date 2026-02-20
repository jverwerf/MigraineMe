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
    onBack: () -> Unit,
    onDeleted: () -> Unit = onBack,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth by authVm.state.collectAsState()
    val db = remember { SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) }

    // Editable state
    var label by remember { mutableStateOf("") }
    var extraLabel by remember { mutableStateOf<String?>(null) } // amount for medicine, duration for relief
    var startAt by remember { mutableStateOf(Instant.now()) }
    var notes by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

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
                        }
                    }
                    "relief" -> {
                        val rows = db.getAllReliefs(token)
                        val row = rows.find { it.id == itemId }
                        if (row != null) {
                            label = row.type ?: ""
                            extraLabel = row.durationMinutes?.let { "${it} min" }
                            startAt = Instant.parse(row.startAt)
                            notes = row.notes ?: ""
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
                if (!extraLabel.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(extraLabel!!, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
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

            // ── Notes ──
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
                                    "medicine" -> db.updateMedicine(token, itemId, startAt = newStartAt, notes = notes)
                                    "relief" -> db.updateRelief(token, itemId, startAt = newStartAt, notes = notes)
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
                        if (error == null) onBack()
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
