package com.migraineme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

@Composable
fun QuickLogMedicineScreen(
    navController: NavController,
    authVm: AuthViewModel,
    medicineVm: MedicineViewModel = viewModel()
) {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    val authState by authVm.state.collectAsState()
    val pool by medicineVm.pool.collectAsState()
    val frequent by medicineVm.frequent.collectAsState()
    
    // Load medicine options
    LaunchedEffect(authState.accessToken) {
        authState.accessToken?.let { medicineVm.loadAll(it) }
    }
    
    // Form state
    var selectedMedicine by rememberSaveable { mutableStateOf<String?>(null) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var amount by rememberSaveable { mutableStateOf("") }
    var startAtIso by rememberSaveable { mutableStateOf<String?>(null) }
    var notes by rememberSaveable { mutableStateOf("") }
    var reliefScale by rememberSaveable { mutableStateOf("NONE") }
    var sideEffectScale by rememberSaveable { mutableStateOf("NONE") }
    var sideEffectNotes by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    // Voice input for the Notes field, same contract as JournalEditScreen:
    // appends the spoken text to what is already there.
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

    // Same voice input for the side-effect notes field.
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

    val scrollState = rememberScrollState()
    
    // Get labels for display
    val frequentLabels = remember(frequent, pool) {
        frequent.mapNotNull { pref -> pool.find { it.id == pref.medicineId }?.label }
    }
    val allLabels = remember(pool) { pool.map { it.label } }

    // Icon lookup: label → category (medicines use category for icon resolution)
    val categoryByLabel = remember(pool) { pool.associate { it.label to it.category } }

    // One-unit system: the unit belongs to the pool medicine, never the log.
    val unitByLabel = remember(pool) { pool.associate { it.label to (it.doseUnit ?: DoseUnits.MG) } }
    val doseUnit = selectedMedicine?.let { unitByLabel[it] } ?: DoseUnits.MG
    var inputUnit by rememberSaveable(selectedMedicine) { mutableStateOf(DoseUnits.inputOptions(doseUnit).first()) }

    Box {
        ScrollFadeContainer(scrollState = scrollState) { scroll ->
            ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {
                // Hero Card - Medicine Selection
                HeroCard {
                    Text(
                        t("Quick Log Medicine"),
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    
                    Text(
                        t("Log a medicine without a migraine"),
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    // Medicine dropdown
                    Box(Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = selectedMedicine ?: "Select medicine...",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(t("Medicine"), color = AppTheme.SubtleTextColor) },
                            trailingIcon = {
                                IconButton(onClick = { menuOpen = true }) {
                                    Text("▼", color = Color.White)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = AppTheme.AccentPurple,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                            )
                        )
                        
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            if (frequentLabels.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(t("Frequent"), fontWeight = FontWeight.Bold) },
                                    onClick = {},
                                    enabled = false
                                )
                                frequentLabels.forEach { label ->
                                    val icon = MedicineIcons.forKey(categoryByLabel[label])
                                    val brainyId = MedicineIcons.drawableForKey(categoryByLabel[label])
                                    DropdownMenuItem(
                                        text = { Text(t(label)) },
                                        leadingIcon = if (brainyId != null || icon != null) {{ LogIconImage(drawableId = brainyId, fallback = icon, size = 20.dp, tint = LocalContentColor.current) }} else null,
                                        onClick = {
                                            selectedMedicine = label
                                            menuOpen = false
                                        }
                                    )
                                }
                                Divider()
                            }
                            
                            if (allLabels.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = { Text(t("All"), fontWeight = FontWeight.Bold) },
                                    onClick = {},
                                    enabled = false
                                )
                                allLabels.forEach { label ->
                                    val icon = MedicineIcons.forKey(categoryByLabel[label])
                                    val brainyId = MedicineIcons.drawableForKey(categoryByLabel[label])
                                    DropdownMenuItem(
                                        text = { Text(t(label)) },
                                        leadingIcon = if (brainyId != null || icon != null) {{ LogIconImage(drawableId = brainyId, fallback = icon, size = 20.dp, tint = LocalContentColor.current) }} else null,
                                        onClick = {
                                            selectedMedicine = label
                                            menuOpen = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { navController.navigate(Routes.ADJUST_MEDICINES) }) {
                            Text(t("Manage Medicines"), color = AppTheme.AccentPurple)
                        }
                    }
                }
                
                // Details Card
                BaseCard {
                    Text(
                        t("Details"),
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    
                    // Amount — number only, unit is fixed by the medicine
                    DoseAmountInput(
                        doseUnit = doseUnit,
                        valueText = amount,
                        onValueTextChange = { amount = it },
                        inputUnit = inputUnit,
                        onInputUnitChange = { inputUnit = it },
                        accent = AppTheme.AccentPurple,
                        label = t("Amount"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AppTheme.AccentPurple,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                            focusedLabelColor = AppTheme.SubtleTextColor,
                            unfocusedLabelColor = AppTheme.SubtleTextColor
                        )
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    // Time picker
                    Column(Modifier.fillMaxWidth()) {
                        Text(t("When did you take this?"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        AppDateTimePicker(
                            label = startAtIso?.let { formatIsoForDisplay(it) } ?: t("Select time...")
                        ) { iso ->
                            startAtIso = iso
                        }
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    
                    // Notes
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text(t("Notes (optional)"), color = AppTheme.SubtleTextColor) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AppTheme.AccentPurple,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                        ),
                        trailingIcon = {
                            IconButton(onClick = {
                                val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                }
                                try { notesSpeechLauncher.launch(intent) } catch (_: Exception) {
                                    android.widget.Toast.makeText(ctx, tSync("Voice input not available"), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Outlined.Mic, contentDescription = t("Voice input"), tint = AppTheme.AccentPurple, modifier = Modifier.size(20.dp))
                            }
                        },
                        minLines = 2
                    )

                    Spacer(Modifier.height(12.dp))

                    // Relief scale
                    Text(t("How much relief?"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReliefScale.entries.forEach { scale ->
                            androidx.compose.material3.FilterChip(
                                selected = reliefScale == scale.name,
                                onClick = { reliefScale = scale.name },
                                label = { Text(t(scale.display), style = MaterialTheme.typography.labelSmall) },
                                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = scale.color.copy(alpha = 0.3f),
                                    selectedLabelColor = Color.White,
                                    containerColor = Color.White.copy(alpha = 0.06f),
                                    labelColor = AppTheme.SubtleTextColor
                                ),
                                border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = reliefScale == scale.name,
                                    borderColor = Color.White.copy(alpha = 0.12f),
                                    selectedBorderColor = scale.color.copy(alpha = 0.6f)
                                )
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Side effects
                    Text(t("Any side effects?"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("NONE" to "None", "SOFT" to "Soft", "MODERATE" to "Moderate", "SEVERE" to "Severe").forEach { (key, display) ->
                            val seColor = when (key) { "NONE" -> Color(0xFF81C784); "SOFT" -> Color(0xFFFFB74D); "MODERATE" -> Color(0xFFFF8A65); else -> Color(0xFFE57373) }
                            androidx.compose.material3.FilterChip(
                                selected = sideEffectScale == key,
                                onClick = { sideEffectScale = key },
                                label = { Text(t(display), style = MaterialTheme.typography.labelSmall) },
                                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = seColor.copy(alpha = 0.3f),
                                    selectedLabelColor = Color.White,
                                    containerColor = Color.White.copy(alpha = 0.06f),
                                    labelColor = AppTheme.SubtleTextColor
                                ),
                                border = androidx.compose.material3.FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = sideEffectScale == key,
                                    borderColor = Color.White.copy(alpha = 0.12f),
                                    selectedBorderColor = seColor.copy(alpha = 0.6f)
                                )
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = sideEffectNotes,
                        onValueChange = { sideEffectNotes = it },
                        label = { Text(t("Side effect notes"), color = AppTheme.SubtleTextColor) },
                        placeholder = { Text(t("e.g. drowsiness, nausea…"), color = AppTheme.SubtleTextColor.copy(alpha = 0.5f)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AppTheme.AccentPurple,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                        ),
                        trailingIcon = {
                            IconButton(onClick = {
                                val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Describe side effects…")
                                }
                                try { seSpeechLauncher.launch(intent) } catch (_: Exception) {
                                    android.widget.Toast.makeText(ctx, tSync("Voice input not available"), android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Outlined.Mic, contentDescription = t("Voice input"), tint = AppTheme.AccentPurple, modifier = Modifier.size(20.dp))
                            }
                        },
                        minLines = 1, maxLines = 3
                    )
                }
                
                // Action Buttons
                BaseCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        OutlinedButton(
                            onClick = { navController.popBackStack() },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White
                            )
                        ) {
                            Text(t("Cancel"))
                        }
                        
                        Button(
                            onClick = {
                                val token = authState.accessToken
                                val medicine = selectedMedicine
                                if (token.isNullOrBlank() || medicine.isNullOrBlank()) return@Button
                                
                                saving = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            val db = SupabaseDbService(
                                                BuildConfig.SUPABASE_URL,
                                                BuildConfig.SUPABASE_ANON_KEY
                                            )
                                            val doseValue = DoseUnits.parseNumber(amount)
                                                ?.let { DoseUnits.toStored(it, doseUnit, inputUnit) }
                                            db.insertMedicine(
                                                accessToken = token,
                                                migraineId = null, // Standalone medicine
                                                name = medicine,
                                                amount = null,
                                                startAt = startAtIso ?: Instant.now().toString(),
                                                notes = notes.ifBlank { null },
                                                reliefScale = reliefScale,
                                                sideEffectScale = sideEffectScale,
                                                sideEffectNotes = sideEffectNotes.ifBlank { null },
                                                doseValue = doseValue,
                                                doseUnit = if (doseValue != null) doseUnit else null
                                            )
                                        }
                                        snackbarHostState.showSnackbar(tSync("Medicine logged!"))
                                        navController.popBackStack()
                                    } catch (e: Exception) {
                                        snackbarHostState.showSnackbar(tSync("Error: %1\$s", e.message ?: ""))
                                    } finally {
                                        saving = false
                                    }
                                }
                            },
                            enabled = !saving && selectedMedicine != null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppTheme.AccentPurple
                            )
                        ) {
                            Text(if (saving) t("Saving...") else t("Log Medicine"))
                        }
                    }
                }
            }
        }
        
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.padding(16.dp)
        )
    }
}

private fun formatIsoForDisplay(iso: String): String {
    return try {
        val instant = java.time.OffsetDateTime.parse(iso)
        instant.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy HH:mm"))
    } catch (_: Exception) {
        try {
            val ldt = java.time.LocalDateTime.parse(iso.removeSuffix("Z"))
            ldt.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy HH:mm"))
        } catch (_: Exception) {
            iso
        }
    }
}

