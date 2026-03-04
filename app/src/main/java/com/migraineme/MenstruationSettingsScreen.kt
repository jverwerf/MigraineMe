package com.migraineme

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TrendingDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Composable
fun MenstruationSettingsScreen(
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // ── State ──
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf<MenstruationSettings?>(null) }
    var saveSuccess by remember { mutableStateOf(false) }

    // Cycle settings
    var lastDateText by remember { mutableStateOf("") }
    var avgCycleText by remember { mutableStateOf("28") }
    var autoUpdateAvg by remember { mutableStateOf(true) }

    // Add period
    var addPeriodDate by remember { mutableStateOf("") }
    var addingPeriod by remember { mutableStateOf(false) }

    // Decay curve (day_m7 … day_0 … day_p7)
    val dayLabels = listOf("-7", "-6", "-5", "-4", "-3", "-2", "-1", "T0", "+1", "+2", "+3", "+4", "+5", "+6", "+7")
    var decayDays by remember { mutableStateOf(listOf("0","0","0","0","0","3","4.5","6","3","1.5","0","0","0","0","0")) }

    // Editing state for tapped bar
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editingValue by remember { mutableStateOf("") }

    // ── Load ──
    LaunchedEffect(Unit) {
        loading = true
        withContext(Dispatchers.IO) {
            try {
                val token = SessionStore.getValidAccessToken(context.applicationContext)
                if (token == null) { errorText = "Please sign in."; loading = false; return@withContext }

                val service = SupabaseMenstruationService(context.applicationContext)
                settings = service.getSettings(token)

                settings?.let { s ->
                    lastDateText = s.lastMenstruationDate?.toString() ?: ""
                    avgCycleText = s.avgCycleLength.toString()
                    autoUpdateAvg = s.autoUpdateAverage
                }

                val edge = EdgeFunctionsService()
                val weights = edge.getMenstruationDecayWeights(context.applicationContext)
                if (weights != null) {
                    decayDays = listOf(
                        fmt(weights.dayM7), fmt(weights.dayM6), fmt(weights.dayM5), fmt(weights.dayM4),
                        fmt(weights.dayM3), fmt(weights.dayM2), fmt(weights.dayM1), fmt(weights.day0),
                        fmt(weights.dayP1), fmt(weights.dayP2), fmt(weights.dayP3), fmt(weights.dayP4),
                        fmt(weights.dayP5), fmt(weights.dayP6), fmt(weights.dayP7)
                    )
                }
            } catch (e: Exception) { errorText = "Failed to load: ${e.message}" }
        }
        loading = false
    }

    // ── Save cycle settings ──
    fun saveCycleSettings() {
        scope.launch {
            saving = true; saveSuccess = false
            val (parsedLast, parsedAvg, err) = validateMenstrInputs(lastDateText, avgCycleText)
            if (err != null) { saving = false; Toast.makeText(context, err, Toast.LENGTH_LONG).show(); return@launch }
            val ok = withContext(Dispatchers.IO) {
                MenstruationTrackingHelper.updateSettingsOnly(context.applicationContext, parsedLast, parsedAvg, autoUpdateAvg)
            }
            saving = false
            if (ok) {
                settings = MenstruationSettings(parsedLast, parsedAvg, autoUpdateAvg)
                saveSuccess = true
                Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
            } else Toast.makeText(context, "Failed to save.", Toast.LENGTH_LONG).show()
        }
    }

    // ── Save decay weights ──
    fun saveDecayWeights() {
        scope.launch {
            saving = true; saveSuccess = false
            val weights = MenstruationDecayWeights(
                dayM7 = decayDays[0].toDoubleOrNull() ?: 0.0, dayM6 = decayDays[1].toDoubleOrNull() ?: 0.0,
                dayM5 = decayDays[2].toDoubleOrNull() ?: 0.0, dayM4 = decayDays[3].toDoubleOrNull() ?: 0.0,
                dayM3 = decayDays[4].toDoubleOrNull() ?: 0.0, dayM2 = decayDays[5].toDoubleOrNull() ?: 0.0,
                dayM1 = decayDays[6].toDoubleOrNull() ?: 0.0, day0 = decayDays[7].toDoubleOrNull() ?: 0.0,
                dayP1 = decayDays[8].toDoubleOrNull() ?: 0.0, dayP2 = decayDays[9].toDoubleOrNull() ?: 0.0,
                dayP3 = decayDays[10].toDoubleOrNull() ?: 0.0, dayP4 = decayDays[11].toDoubleOrNull() ?: 0.0,
                dayP5 = decayDays[12].toDoubleOrNull() ?: 0.0, dayP6 = decayDays[13].toDoubleOrNull() ?: 0.0,
                dayP7 = decayDays[14].toDoubleOrNull() ?: 0.0,
            )
            val ok = withContext(Dispatchers.IO) {
                val edge = EdgeFunctionsService()
                val saved = edge.upsertMenstruationDecayWeights(context.applicationContext, weights)
                if (saved) edge.triggerRecalcRiskScores(context.applicationContext)
                saved
            }
            saving = false
            if (ok) { saveSuccess = true; Toast.makeText(context, "Decay weights saved", Toast.LENGTH_SHORT).show() }
            else Toast.makeText(context, "Failed to save weights.", Toast.LENGTH_LONG).show()
        }
    }

    // ══════════════════════════════════════════════════════
    //  UI
    // ══════════════════════════════════════════════════════
    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            // ═══════════════════════════════════════════
            //  HERO — Current Status at a glance
            // ═══════════════════════════════════════════
            HeroCard {
                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AppTheme.AccentPurple)
                    }
                    return@HeroCard
                }

                errorText?.let {
                    Text(it, color = Color(0xFFFF6B6B), style = MaterialTheme.typography.bodyMedium)
                    return@HeroCard
                }

                if (settings?.lastMenstruationDate == null) {
                    Text("No cycle data yet", color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Text("Configure your cycle settings below to get started.", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                } else {
                    val s = settings!!
                    val nextExpected = s.lastMenstruationDate!!.plusDays(s.avgCycleLength.toLong())
                    val today = LocalDate.now()
                    val daysUntil = ChronoUnit.DAYS.between(today, nextExpected)

                    val countdownText = when {
                        daysUntil < 0 -> "${-daysUntil} days ago"
                        daysUntil == 0L -> "Today"
                        daysUntil == 1L -> "Tomorrow"
                        else -> "In $daysUntil days"
                    }
                    val countdownColor = when {
                        daysUntil in -2..2 -> Color(0xFFE57373)
                        daysUntil in 3..7 -> Color(0xFFFFB74D)
                        else -> AppTheme.AccentPurple
                    }

                    Text("Next Period", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    Text(countdownText, color = countdownColor, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        MStatColumn("Last Period", s.lastMenstruationDate.toString())
                        MStatColumn("Predicted", nextExpected.toString())
                        MStatColumn("Cycle", "${s.avgCycleLength} days")
                    }
                }
            }

            // ═══════════════════════════════════════════
            //  HOW IT WORKS — purple accent card
            // ═══════════════════════════════════════════
            Card(
                colors = CardDefaults.cardColors(containerColor = AppTheme.AccentPurple.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Info, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(20.dp))
                    Text(
                        "Your predicted period date gets a single trigger. The risk curve controls " +
                        "how many points it contributes on each day relative to the predicted date. " +
                        "When the predicted date arrives, it auto-converts to a real period log and " +
                        "the next prediction moves forward by one cycle.",
                        color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // ═══════════════════════════════════════════
            //  BASE CARD — Log a Period
            // ═══════════════════════════════════════════
            BaseCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.CalendarMonth, null, tint = Color(0xFFE57373), modifier = Modifier.size(18.dp))
                    Text("Log a Period", color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MStyledDatePicker(isoDate = addPeriodDate, enabled = !addingPeriod, onDateSelected = { addPeriodDate = it }, modifier = Modifier.weight(1f))
                    Button(
                        onClick = {
                            scope.launch {
                                addingPeriod = true
                                withContext(Dispatchers.IO) {
                                    try {
                                        val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return@withContext
                                        val startDate = LocalDate.parse(addPeriodDate)
                                        val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                                        db.insertTrigger(accessToken = token, migraineId = null, type = "menstruation", startAt = "${startDate}T09:00:00Z", notes = "Logged from app")
                                        val currentSettings = settings
                                        val currentLast = currentSettings?.lastMenstruationDate
                                        if (currentLast == null || startDate.isAfter(currentLast)) {
                                            MenstruationTrackingHelper.updateSettingsOnly(context.applicationContext, startDate, currentSettings?.avgCycleLength ?: 28, currentSettings?.autoUpdateAverage ?: true)
                                            settings = MenstruationSettings(startDate, currentSettings?.avgCycleLength ?: 28, currentSettings?.autoUpdateAverage ?: true)
                                            lastDateText = startDate.toString()
                                        }
                                    } catch (_: Exception) {}
                                }
                                addingPeriod = false
                                addPeriodDate = ""
                                Toast.makeText(context, "Period logged", Toast.LENGTH_SHORT).show()
                            }
                        },
                        enabled = addPeriodDate.isNotBlank() && !addingPeriod,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE57373))
                    ) {
                        if (addingPeriod) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text("Add")
                    }
                }
            }

            // ═══════════════════════════════════════════
            //  BASE CARD — Cycle Settings
            // ═══════════════════════════════════════════
            BaseCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Settings, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(18.dp))
                    Text("Cycle Settings", color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                }

                // Last period date
                Text("Last Period Start Date", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MStyledDatePicker(isoDate = lastDateText, enabled = !saving, onDateSelected = { lastDateText = it }, modifier = Modifier.weight(1f))
                    if (lastDateText.isNotBlank()) {
                        TextButton(onClick = { lastDateText = "" }, enabled = !saving) { Text("Clear", color = AppTheme.AccentPurple) }
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

                // Average cycle length
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Average Cycle Length", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                        Text("Typical range: 21–35 days", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedTextField(
                        value = avgCycleText,
                        onValueChange = { new -> if (new.isEmpty() || new.all { it.isDigit() }) avgCycleText = new },
                        placeholder = { Text("28", color = Color.White.copy(alpha = 0.4f)) },
                        modifier = Modifier.width(80.dp), singleLine = true, enabled = !saving,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center, color = Color.White),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppTheme.AccentPurple, unfocusedBorderColor = Color.White.copy(alpha = 0.3f)
                        )
                    )
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

                // Auto-update toggle
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-update Average", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                        Text("Recalculate from logged periods", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(
                        checked = autoUpdateAvg, onCheckedChange = { autoUpdateAvg = it }, enabled = !saving,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppTheme.AccentPurple)
                    )
                }

                Button(
                    onClick = { saveCycleSettings() },
                    modifier = Modifier.fillMaxWidth(), enabled = !saving,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                ) { Text(if (saving) "Saving…" else "Save Settings") }
            }

            // ═══════════════════════════════════════════
            //  BASE CARD — Menstruation Risk Curve (AI Setup style)
            // ═══════════════════════════════════════════
            BaseCard {
                // Header
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.TrendingDown, null, tint = Color(0xFFE57373), modifier = Modifier.size(18.dp))
                    Text("Menstruation Risk Curve", color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                }
                Text("Tap a bar to edit · Risk points per day relative to predicted period", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)

                // ── Visual bar chart ──
                val values = decayDays.map { it.toDoubleOrNull() ?: 0.0 }
                val maxVal = values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0

                Row(
                    Modifier.fillMaxWidth().height(80.dp),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    values.forEachIndexed { i, v ->
                        val frac = (v / maxVal).toFloat().coerceIn(0f, 1f)
                        val barColor = when {
                            i < 7 -> Color(0xFFFFB74D)
                            i == 7 -> Color(0xFFE57373)
                            else -> Color(0xFF81C784)
                        }
                        val isSelected = editingIndex == i

                        Column(
                            Modifier
                                .weight(1f)
                                .clickable {
                                    if (editingIndex == i) {
                                        editingIndex = null
                                    } else {
                                        editingIndex = i
                                        editingValue = decayDays[i]
                                    }
                                },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                if (v > 0) "${"%.1f".format(v)}" else "",
                                color = if (isSelected) barColor else if (v > 0) Color.White else Color.Transparent,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                            Spacer(Modifier.height(2.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height((frac * 44).dp.coerceAtLeast(3.dp))
                                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                                    .background(
                                        if (isSelected) barColor
                                        else if (v > 0) barColor.copy(alpha = 0.6f)
                                        else barColor.copy(alpha = 0.08f)
                                    )
                                    .then(
                                        if (isSelected) Modifier.border(1.dp, barColor, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                                        else Modifier
                                    )
                            )
                            Text(
                                dayLabels[i],
                                color = if (isSelected) barColor else AppTheme.SubtleTextColor.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1
                            )
                        }
                    }
                }

                // ── Inline editor for selected bar ──
                editingIndex?.let { idx ->
                    val editColor = when {
                        idx < 7 -> Color(0xFFFFB74D)
                        idx == 7 -> Color(0xFFE57373)
                        else -> Color(0xFF81C784)
                    }
                    val phaseLabel = when {
                        idx < 7 -> "Before Period"
                        idx == 7 -> "Period Day"
                        else -> "After Period"
                    }
                    val focusManager = LocalFocusManager.current

                    Spacer(Modifier.height(2.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                    Spacer(Modifier.height(2.dp))

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${dayLabels[idx]} · $phaseLabel",
                                color = editColor,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                "Risk points contributed on this day",
                                color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        OutlinedTextField(
                            value = editingValue,
                            onValueChange = { newVal ->
                                val filtered = newVal.filter { c -> c.isDigit() || c == '.' }
                                editingValue = filtered
                                decayDays = decayDays.toMutableList().also { it[idx] = filtered }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                    editingIndex = null
                                }
                            ),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                textAlign = TextAlign.Center,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.width(80.dp).height(50.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = editColor.copy(alpha = 0.3f),
                                focusedBorderColor = editColor,
                                cursorColor = editColor
                            )
                        )
                    }
                }

                // ── Phase legend ──
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    MPhaseChip("Before", Color(0xFFFFB74D))
                    MPhaseChip("Period", Color(0xFFE57373))
                    MPhaseChip("After", Color(0xFF81C784))
                }

                // ── Save / Reset ──
                if (saveSuccess) {
                    Text("✓ Saved successfully", color = Color(0xFF81C784), style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            decayDays = listOf("0","0","0","0","0","3","4.5","6","3","1.5","0","0","0","0","0")
                            editingIndex = null
                        },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) { Text("Reset Defaults") }
                    Button(
                        onClick = { saveDecayWeights() },
                        modifier = Modifier.weight(1f), enabled = !saving,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                    ) {
                        if (saving) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text("Save Weights")
                    }
                }
            }

        }
    }
}

// ══════════════════════════════════════════════════════════════
//  Private helpers
// ══════════════════════════════════════════════════════════════

@Composable
private fun MStatColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(2.dp))
        Text(value, color = Color.White, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun MPhaseChip(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).background(color.copy(alpha = 0.6f), RoundedCornerShape(2.dp)))
        Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun MStyledDatePicker(
    isoDate: String, enabled: Boolean, onDateSelected: (String) -> Unit, modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val parsed = isoDate.trim().takeIf { it.isNotBlank() }?.let {
        runCatching { LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
    }
    val initial = parsed ?: LocalDate.now()
    OutlinedButton(
        onClick = {
            DatePickerDialog(ctx, { _, y, m, d ->
                onDateSelected("%04d-%02d-%02d".format(y, m + 1, d))
            }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
        },
        enabled = enabled, modifier = modifier, shape = RoundedCornerShape(12.dp)
    ) { Text(if (isoDate.isBlank()) "Select date" else isoDate, color = Color.White) }
}

private fun validateMenstrInputs(lastDateText: String, avgCycleText: String): Triple<LocalDate?, Int, String?> {
    val lastDate = lastDateText.trim().takeIf { it.isNotBlank() }?.let { raw ->
        try { LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE) }
        catch (_: Exception) { return Triple(null, 0, "Invalid date format. Use YYYY-MM-DD.") }
    }
    val avg = avgCycleText.trim().toIntOrNull() ?: return Triple(null, 0, "Average cycle length must be a number.")
    if (avg < 15 || avg > 60) return Triple(null, 0, "Cycle length should be between 15 and 60 days.")
    return Triple(lastDate, avg, null)
}

private fun fmt(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString()
    else v.toBigDecimal().stripTrailingZeros().toPlainString()
