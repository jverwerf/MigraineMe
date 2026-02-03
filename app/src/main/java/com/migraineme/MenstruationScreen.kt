package com.migraineme

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Menstruation tracking screen accessible from Monitor or DataSettings.
 * Shows current data and allows editing via expandable form.
 * 
 * When opened from DataSettings toggle (first time setup), will automatically
 * enable the menstruation metric after user saves settings.
 * 
 * Background: purple_sky_bg_menstruation.png (set in MainActivity)
 */
@Composable
fun MenstruationScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var loggingPeriod by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf<MenstruationSettings?>(null) }
    var menstruationEnabled by remember { mutableStateOf(false) }

    // Edit mode
    var showEditForm by remember { mutableStateOf(false) }
    
    // Log period dialog
    var showLogPeriodDialog by remember { mutableStateOf(false) }
    var logPeriodDate by remember { mutableStateOf(LocalDate.now().toString()) }

    // Editable fields
    var lastDateText by remember { mutableStateOf("") }
    var avgCycleText by remember { mutableStateOf("28") }
    var autoUpdateAvg by remember { mutableStateOf(true) }
    
    // Refresh trigger
    var refreshTrigger by remember { mutableStateOf(0) }

    // Load settings and check if enabled
    LaunchedEffect(refreshTrigger) {
        loading = true
        errorText = null

        val result = withContext(Dispatchers.IO) {
            runCatching {
                val token = SessionStore.getValidAccessToken(context.applicationContext)
                    ?: return@runCatching Triple<MenstruationSettings?, Boolean, String?>(
                        null,
                        false,
                        "Please sign in to view menstruation settings."
                    )

                val service = SupabaseMenstruationService(context.applicationContext)
                val s = service.getSettings(token)
                
                // Check if menstruation metric is enabled
                val edge = EdgeFunctionsService()
                val metricSettings = edge.getMetricSettings(context.applicationContext)
                val enabled = metricSettings.any { it.metric == "menstruation" && it.enabled }
                
                Triple(s, enabled, null)
            }.getOrElse { e ->
                Triple<MenstruationSettings?, Boolean, String?>(
                    null,
                    false,
                    "Failed to load: ${e.message ?: "Unknown error"}"
                )
            }
        }

        settings = result.first
        menstruationEnabled = result.second
        errorText = result.third

        result.first?.let { s ->
            lastDateText = s.lastMenstruationDate?.toString() ?: ""
            avgCycleText = s.avgCycleLength.toString()
            autoUpdateAvg = s.autoUpdateAverage
        }
        
        // If not enabled and no settings, show edit form for first time setup
        if (!menstruationEnabled && (result.first == null || result.first?.lastMenstruationDate == null)) {
            showEditForm = true
        }

        loading = false
    }

    fun saveSettings() {
        scope.launch {
            saving = true

            val (parsedLast, parsedAvg, validationError) = validateMenstruationInputs(
                lastDateText = lastDateText,
                avgCycleText = avgCycleText
            )

            if (validationError != null) {
                withContext(Dispatchers.Main) {
                    saving = false
                    Toast.makeText(context, validationError, Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            val ok = withContext(Dispatchers.IO) {
                // Just update settings - the toggle in DataSettings handles enable/disable
                MenstruationTrackingHelper.updateSettingsOnly(
                    context = context.applicationContext,
                    lastDate = parsedLast,
                    avgCycle = parsedAvg,
                    autoUpdate = autoUpdateAvg
                )
            }

            withContext(Dispatchers.Main) {
                saving = false
                if (ok) {
                    settings = MenstruationSettings(
                        lastMenstruationDate = parsedLast,
                        avgCycleLength = parsedAvg,
                        autoUpdateAverage = autoUpdateAvg
                    )
                    showEditForm = false
                    Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to save. Please try again.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    fun logPeriod(dateStr: String) {
        scope.launch {
            loggingPeriod = true
            
            val date = try {
                LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loggingPeriod = false
                    Toast.makeText(context, "Invalid date format", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            
            val ok = withContext(Dispatchers.IO) {
                try {
                    val token = SessionStore.getValidAccessToken(context.applicationContext)
                        ?: return@withContext false
                    
                    // Insert trigger directly - DB trigger handles settings + predicted update
                    val db = SupabaseDbService(
                        BuildConfig.SUPABASE_URL,
                        BuildConfig.SUPABASE_ANON_KEY
                    )
                    
                    db.insertTrigger(
                        accessToken = token,
                        migraineId = null,
                        type = "menstruation",
                        startAt = "${date}T09:00:00Z",
                        notes = "Logged from app"
                    )
                    true
                } catch (e: Exception) {
                    android.util.Log.e("MenstruationScreen", "Failed to log period: ${e.message}", e)
                    false
                }
            }
            
            withContext(Dispatchers.Main) {
                loggingPeriod = false
                showLogPeriodDialog = false
                if (ok) {
                    Toast.makeText(context, "Period logged!", Toast.LENGTH_SHORT).show()
                    // Refresh to show updated data
                    refreshTrigger++
                } else {
                    Toast.makeText(context, "Failed to log period", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Log Period Dialog
    if (showLogPeriodDialog) {
        AlertDialog(
            onDismissRequest = { showLogPeriodDialog = false },
            title = { Text("Log Period") },
            text = {
                Column {
                    Text("When did your period start?")
                    Spacer(Modifier.height(16.dp))
                    MenstruationDatePicker(
                        isoDate = logPeriodDate,
                        enabled = !loggingPeriod,
                        onDateSelected = { logPeriodDate = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { logPeriod(logPeriodDate) },
                    enabled = !loggingPeriod
                ) {
                    Text(if (loggingPeriod) "Logging..." else "Log Period")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLogPeriodDialog = false },
                    enabled = !loggingPeriod
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll) {
            // Main card
            HeroCard {
                Text(
                    "Menstruation Tracking",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )

                Spacer(Modifier.height(20.dp))

                if (loading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = AppTheme.AccentPurple)
                    }
                } else if (errorText != null) {
                    Text(
                        errorText!!,
                        color = Color(0xFFFF6B6B),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else if (!menstruationEnabled || settings?.lastMenstruationDate == null) {
                    // First time setup
                    Text(
                        "Set up cycle tracking",
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Track your menstrual cycle to identify migraine patterns.",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                    
                    Spacer(Modifier.height(16.dp))
                    
                    // Show edit form expanded for first time setup
                    EditFormContent(
                        lastDateText = lastDateText,
                        onLastDateChange = { lastDateText = it },
                        avgCycleText = avgCycleText,
                        onAvgCycleChange = { avgCycleText = it },
                        autoUpdateAvg = autoUpdateAvg,
                        onAutoUpdateChange = { autoUpdateAvg = it },
                        saving = saving,
                        showCancel = false,
                        onCancel = {},
                        onSave = { saveSettings() },
                        saveButtonText = "Enable Tracking"
                    )
                } else {
                    val s = settings!!

                    // Current values display
                    CurrentValuesSection(
                        settings = s,
                        onLogPeriod = { 
                            logPeriodDate = LocalDate.now().toString()
                            showLogPeriodDialog = true 
                        }
                    )

                    Spacer(Modifier.height(20.dp))

                    // Configure button
                    OutlinedButton(
                        onClick = { showEditForm = !showEditForm },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            if (showEditForm) "Hide Settings" else "Configure Settings",
                            color = Color.White
                        )
                    }

                    // Expandable edit form
                    AnimatedVisibility(
                        visible = showEditForm,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Column(modifier = Modifier.padding(top = 20.dp)) {
                            EditFormContent(
                                lastDateText = lastDateText,
                                onLastDateChange = { lastDateText = it },
                                avgCycleText = avgCycleText,
                                onAvgCycleChange = { avgCycleText = it },
                                autoUpdateAvg = autoUpdateAvg,
                                onAutoUpdateChange = { autoUpdateAvg = it },
                                saving = saving,
                                showCancel = true,
                                onCancel = {
                                    // Reset to current values
                                    settings?.let { s ->
                                        lastDateText = s.lastMenstruationDate?.toString() ?: ""
                                        avgCycleText = s.avgCycleLength.toString()
                                        autoUpdateAvg = s.autoUpdateAverage
                                    }
                                    showEditForm = false
                                },
                                onSave = { saveSettings() },
                                saveButtonText = "Save"
                            )
                        }
                    }
                }
            }

            // Info card
            BaseCard {
                Text(
                    "About Cycle Tracking",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Menstrual cycles can affect migraine patterns. Many women experience migraines around their period due to hormonal fluctuations, particularly drops in estrogen.",
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Tracking your cycle helps identify patterns and predict high-risk days for migraines.",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun CurrentValuesSection(
    settings: MenstruationSettings,
    onLogPeriod: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Last Period
        DataRow(
            label = "Last Period",
            value = settings.lastMenstruationDate?.toString() ?: "Not set"
        )

        // Average Cycle
        DataRow(
            label = "Average Cycle",
            value = "${settings.avgCycleLength} days"
        )

        // Auto-update
        DataRow(
            label = "Auto-update",
            value = if (settings.autoUpdateAverage) "On" else "Off"
        )

        // Next expected (if we have last date)
        settings.lastMenstruationDate?.let { lastDate ->
            val nextExpected = lastDate.plusDays(settings.avgCycleLength.toLong())
            val today = LocalDate.now()
            val daysUntil = ChronoUnit.DAYS.between(today, nextExpected)

            Spacer(Modifier.height(4.dp))
            Divider(color = Color.White.copy(alpha = 0.2f))
            Spacer(Modifier.height(12.dp))

            DataRow(
                label = "Next Expected",
                value = nextExpected.toString()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when {
                        daysUntil < 0 -> "${-daysUntil} days ago"
                        daysUntil == 0L -> "Today"
                        daysUntil == 1L -> "Tomorrow"
                        else -> "In $daysUntil days"
                    },
                    color = when {
                        daysUntil in -2..2 -> Color(0xFFFF6B6B) // Red when close
                        daysUntil in 3..7 -> Color(0xFFFFB74D) // Orange when approaching
                        else -> AppTheme.SubtleTextColor
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                
                // Show Log Period button prominently when expected/overdue
                if (daysUntil <= 2) {
                    Button(
                        onClick = onLogPeriod,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE57373)
                        )
                    ) {
                        Text("Log Period", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        
        // Always show a way to log period (if not already showing the red button)
        val showOutlinedButton = settings.lastMenstruationDate == null || 
            ChronoUnit.DAYS.between(
                LocalDate.now(), 
                settings.lastMenstruationDate!!.plusDays(settings.avgCycleLength.toLong())
            ) > 2
            
        if (showOutlinedButton) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onLogPeriod,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Log Period", color = Color.White)
            }
        }
    }
}

@Composable
private fun EditFormContent(
    lastDateText: String,
    onLastDateChange: (String) -> Unit,
    avgCycleText: String,
    onAvgCycleChange: (String) -> Unit,
    autoUpdateAvg: Boolean,
    onAutoUpdateChange: (Boolean) -> Unit,
    saving: Boolean,
    showCancel: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    saveButtonText: String
) {
    Column {
        if (showCancel) {
            Divider(color = Color.White.copy(alpha = 0.2f))
            Spacer(Modifier.height(20.dp))

            Text(
                "Edit Settings",
                color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )

            Spacer(Modifier.height(16.dp))
        }

        // Last period date picker
        Text(
            "Last Period Date",
            color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MenstruationDatePicker(
                isoDate = lastDateText,
                enabled = !saving,
                onDateSelected = onLastDateChange,
                modifier = Modifier.weight(1f)
            )
            if (lastDateText.isNotBlank()) {
                TextButton(
                    onClick = { onLastDateChange("") },
                    enabled = !saving
                ) {
                    Text("Clear", color = AppTheme.BodyTextColor)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Average cycle length
        Text(
            "Average Cycle Length (days)",
            color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.labelLarge
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = avgCycleText,
            onValueChange = { new ->
                if (new.isEmpty() || new.all { it.isDigit() }) {
                    onAvgCycleChange(new)
                }
            },
            placeholder = { Text("28", color = Color.White.copy(alpha = 0.5f)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !saving,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = AppTheme.AccentPurple,
                unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
            )
        )
        Text(
            "Typical range: 21–35 days",
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(16.dp))

        // Auto-update toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Auto-update Average",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    "Recalculate from logged periods",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = autoUpdateAvg,
                onCheckedChange = onAutoUpdateChange,
                enabled = !saving,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AppTheme.AccentPurple
                )
            )
        }

        Spacer(Modifier.height(24.dp))

        // Save / Cancel buttons
        if (showCancel) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    enabled = !saving,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel", color = Color.White)
                }

                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f),
                    enabled = !saving,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppTheme.AccentPurple
                    )
                ) {
                    Text(if (saving) "Saving..." else saveButtonText)
                }
            }
        } else {
            // First time setup - just save button
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
                enabled = !saving,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppTheme.AccentPurple
                )
            ) {
                Text(if (saving) "Saving..." else saveButtonText)
            }
        }
    }
}

@Composable
private fun DataRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            value,
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
        )
    }
}

@Composable
private fun MenstruationDatePicker(
    isoDate: String,
    enabled: Boolean,
    onDateSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current

    val parsed = isoDate.trim().takeIf { it.isNotBlank() }?.let { raw ->
        runCatching { LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
    }

    val initial = parsed ?: LocalDate.now()
    val displayText = if (isoDate.isBlank()) "Select date" else isoDate

    OutlinedButton(
        onClick = {
            DatePickerDialog(
                ctx,
                { _, y, m, d ->
                    val picked = "%04d-%02d-%02d".format(y, m + 1, d)
                    onDateSelected(picked)
                },
                initial.year,
                initial.monthValue - 1,
                initial.dayOfMonth
            ).show()
        },
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(displayText, color = Color.White)
    }
}

private fun validateMenstruationInputs(
    lastDateText: String,
    avgCycleText: String
): Triple<LocalDate?, Int, String?> {
    val lastDate = lastDateText.trim().takeIf { it.isNotBlank() }?.let { raw ->
        try {
            LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: Exception) {
            return Triple(null, 0, "Invalid date format. Use YYYY-MM-DD.")
        }
    }

    val avg = avgCycleText.trim().toIntOrNull()
        ?: return Triple(null, 0, "Average cycle length must be a number.")

    if (avg < 15 || avg > 60) {
        return Triple(null, 0, "Cycle length should be between 15 and 60 days.")
    }

    return Triple(lastDate, avg, null)
}
