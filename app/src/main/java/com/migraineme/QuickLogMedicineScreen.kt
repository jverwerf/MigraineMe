package com.migraineme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
    var saving by remember { mutableStateOf(false) }
    
    val scrollState = rememberScrollState()
    
    // Get labels for display
    val frequentLabels = remember(frequent, pool) {
        frequent.mapNotNull { pref -> pool.find { it.id == pref.medicineId }?.label }
    }
    val allLabels = remember(pool) { pool.map { it.label } }

    Box {
        ScrollFadeContainer(scrollState = scrollState) { scroll ->
            ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {
                // Back navigation
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                }
                
                // Hero Card - Medicine Selection
                HeroCard {
                    Text(
                        "Quick Log Medicine",
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    
                    Text(
                        "Log a medicine without a migraine",
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
                            label = { Text("Medicine", color = AppTheme.SubtleTextColor) },
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
                                    text = { Text("Frequent", fontWeight = FontWeight.Bold) },
                                    onClick = {},
                                    enabled = false
                                )
                                frequentLabels.forEach { label ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
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
                                    text = { Text("All", fontWeight = FontWeight.Bold) },
                                    onClick = {},
                                    enabled = false
                                )
                                allLabels.forEach { label ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
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
                            Text("Manage Medicines", color = AppTheme.AccentPurple)
                        }
                    }
                }
                
                // Details Card
                BaseCard {
                    Text(
                        "Details",
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    
                    // Amount
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { Text("Amount (e.g., 500mg, 2 tablets)", color = AppTheme.SubtleTextColor) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AppTheme.AccentPurple,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                        )
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    // Time picker
                    Column(Modifier.fillMaxWidth()) {
                        Text("When did you take this?", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        AppDateTimePicker(
                            label = startAtIso?.let { formatIsoForDisplay(it) } ?: "Select time..."
                        ) { iso ->
                            startAtIso = iso
                        }
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    
                    // Notes
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notes (optional)", color = AppTheme.SubtleTextColor) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AppTheme.AccentPurple,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                        ),
                        minLines = 2
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
                            Text("Cancel")
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
                                            db.insertMedicine(
                                                accessToken = token,
                                                migraineId = null, // Standalone medicine
                                                name = medicine,
                                                amount = amount.ifBlank { null },
                                                startAt = startAtIso ?: Instant.now().toString(),
                                                notes = notes.ifBlank { null }
                                            )
                                        }
                                        snackbarHostState.showSnackbar("Medicine logged!")
                                        navController.popBackStack()
                                    } catch (e: Exception) {
                                        snackbarHostState.showSnackbar("Error: ${e.message}")
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
                            Text(if (saving) "Saving..." else "Log Medicine")
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

