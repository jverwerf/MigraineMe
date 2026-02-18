package com.migraineme

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Risk Model Settings Screen
 *
 * Two sections:
 *
 * 1. Gauge Thresholds — the score boundaries that define gauge zones:
 *      None=0, Low=3, Mild=5, High=10
 *    "If my total score is ≥ X, I'm in zone Y"
 *
 * 2. Decay Weights — how much each trigger/prodrome severity contributes
 *    per day (today through +6 days):
 *      HIGH  | 10 | 5 | 2.5 | 0 | 0 | 0 | 0
 *      MILD  |  6 | 3 | 1.5 | 0 | 0 | 0 | 0
 *      LOW   |  3 | 1.5 | 0 | 0 | 0 | 0 | 0
 */
@Composable
fun RiskWeightsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val edge = remember { EdgeFunctionsService() }
    val scrollState = rememberScrollState()

    // ── Loading / saving state ──
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saveSuccess by remember { mutableStateOf(false) }

    // ── Gauge thresholds state ──
    val zones = listOf("NONE", "LOW", "MILD", "HIGH")
    var thresholdNone by remember { mutableStateOf("0") }
    var thresholdLow by remember { mutableStateOf("3") }
    var thresholdMild by remember { mutableStateOf("5") }
    var thresholdHigh by remember { mutableStateOf("10") }

    // ── Decay weights state — separate state per severity for reliable recomposition ──
    val severities = listOf("HIGH", "MILD", "LOW")
    val dayLabels = listOf("Today", "+1d", "+2d", "+3d", "+4d", "+5d", "+6d")

    var highDays by remember { mutableStateOf(listOf("10", "5", "2.5", "0", "0", "0", "0")) }
    var mildDays by remember { mutableStateOf(listOf("6", "3", "1.5", "0", "0", "0", "0")) }
    var lowDays by remember { mutableStateOf(listOf("3", "1.5", "0", "0", "0", "0", "0")) }

    fun getDays(sev: String): List<String> = when (sev) {
        "HIGH" -> highDays
        "MILD" -> mildDays
        "LOW" -> lowDays
        else -> listOf("0","0","0","0","0","0","0")
    }

    fun setDays(sev: String, days: List<String>) {
        when (sev) {
            "HIGH" -> highDays = days
            "MILD" -> mildDays = days
            "LOW" -> lowDays = days
        }
    }

    fun getThreshold(zone: String): String = when (zone) {
        "NONE" -> thresholdNone
        "LOW" -> thresholdLow
        "MILD" -> thresholdMild
        "HIGH" -> thresholdHigh
        else -> "0"
    }

    fun setThreshold(zone: String, value: String) {
        when (zone) {
            "NONE" -> thresholdNone = value
            "LOW" -> thresholdLow = value
            "MILD" -> thresholdMild = value
            "HIGH" -> thresholdHigh = value
        }
    }

    // ── Load from Supabase ──
    LaunchedEffect(Unit) {
        // Fetch on IO thread
        val thresholdResult = withContext(Dispatchers.IO) {
            try {
                edge.getRiskGaugeThresholds(context)
            } catch (e: Exception) {
                Log.e("RiskWeightsScreen", "Failed to load thresholds: ${e.message}", e)
                null
            }
        }

        val weightResult = withContext(Dispatchers.IO) {
            try {
                edge.getRiskDecayWeights(context)
            } catch (e: Exception) {
                Log.e("RiskWeightsScreen", "Failed to load weights: ${e.message}", e)
                null
            }
        }

        // Apply state on Main thread
        if (thresholdResult != null) {
            Log.d("RiskWeightsScreen", "Loaded ${thresholdResult.size} threshold rows")
            for (row in thresholdResult) {
                when (row.zone.uppercase()) {
                    "NONE" -> thresholdNone = fmt(row.minValue)
                    "LOW" -> thresholdLow = fmt(row.minValue)
                    "MILD" -> thresholdMild = fmt(row.minValue)
                    "HIGH" -> thresholdHigh = fmt(row.minValue)
                }
            }
        } else {
            error = "Failed to load thresholds"
        }

        if (weightResult != null) {
            Log.d("RiskWeightsScreen", "Loaded ${weightResult.size} weight rows")
            for (row in weightResult) {
                val days = listOf(
                    fmt(row.day0), fmt(row.day1), fmt(row.day2),
                    fmt(row.day3), fmt(row.day4), fmt(row.day5), fmt(row.day6)
                )
                Log.d("RiskWeightsScreen", "  ${row.severity}: $days")
                when (row.severity.uppercase()) {
                    "HIGH" -> highDays = days
                    "MILD" -> mildDays = days
                    "LOW" -> lowDays = days
                }
            }
        } else {
            error = (error?.let { "$it\n" } ?: "") + "Failed to load weights"
        }

        loading = false
    }

    // ── Save handler ──
    fun save() {
        scope.launch {
            saving = true
            error = null
            saveSuccess = false

            withContext(Dispatchers.IO) {
                try {
                    var allOk = true

                    // Save gauge thresholds
                    for (zone in zones) {
                        val minVal = getThreshold(zone).toDoubleOrNull() ?: 0.0
                        val ok = edge.upsertRiskGaugeThreshold(
                            context = context,
                            zone = zone,
                            minValue = minVal
                        )
                        if (!ok) allOk = false
                    }

                    // Save decay weights
                    for (sev in severities) {
                        val vals = getDays(sev)
                        val ok = edge.upsertRiskDecayWeight(
                            context = context,
                            severity = sev,
                            day0 = vals.getOrNull(0)?.toDoubleOrNull() ?: 0.0,
                            day1 = vals.getOrNull(1)?.toDoubleOrNull() ?: 0.0,
                            day2 = vals.getOrNull(2)?.toDoubleOrNull() ?: 0.0,
                            day3 = vals.getOrNull(3)?.toDoubleOrNull() ?: 0.0,
                            day4 = vals.getOrNull(4)?.toDoubleOrNull() ?: 0.0,
                            day5 = vals.getOrNull(5)?.toDoubleOrNull() ?: 0.0,
                            day6 = vals.getOrNull(6)?.toDoubleOrNull() ?: 0.0
                        )
                        if (!ok) allOk = false
                    }

                    if (allOk) {
                        // Recalculate risk scores with the new thresholds/weights
                        edge.triggerRecalcRiskScores(context)
                        saveSuccess = true
                    }
                    else error = "Some rows failed to save"
                } catch (e: Exception) {
                    error = "Save failed: ${e.message}"
                }
            }
            saving = false
        }
    }

    // ── Reset to defaults ──
    fun resetDefaults() {
        thresholdNone = "0"
        thresholdLow = "3"
        thresholdMild = "5"
        thresholdHigh = "10"

        highDays = listOf("10", "5", "2.5", "0", "0", "0", "0")
        mildDays = listOf("6", "3", "1.5", "0", "0", "0", "0")
        lowDays  = listOf("3", "1.5", "0", "0", "0", "0", "0")
    }

    // ── UI ──
    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 60.dp) {

            // Top bar
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Text(
                    "Risk Model",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.size(48.dp))
            }

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppTheme.AccentPurple)
                }
            } else {

                // ─────────────────────────────────────────────────────────
                // Section 1: Gauge Thresholds
                // ─────────────────────────────────────────────────────────
                BaseCard {
                    Text(
                        "Gauge Thresholds",
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        "The minimum score needed to enter each risk zone on the gauge. " +
                        "When your total trigger + prodrome score reaches a threshold, the gauge moves into that zone.",
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        zones.forEach { zone ->
                            val zoneCol = zoneColor(zone)
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    zone,
                                    color = zoneCol,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    textAlign = TextAlign.Center
                                )
                                OutlinedTextField(
                                    value = getThreshold(zone),
                                    onValueChange = { newVal ->
                                        setThreshold(zone, newVal.filter { it.isDigit() || it == '.' })
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                        textAlign = TextAlign.Center,
                                        color = Color.White
                                    ),
                                    modifier = Modifier.fillMaxWidth().height(50.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                        focusedBorderColor = AppTheme.AccentPurple,
                                        cursorColor = AppTheme.AccentPurple
                                    )
                                )
                            }
                        }
                    }
                }

                // ─────────────────────────────────────────────────────────
                // Section 2: Decay Weights
                // ─────────────────────────────────────────────────────────
                BaseCard {
                    Text(
                        "Decay Weights",
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        "How much each trigger or prodrome contributes to your risk score based on its severity and how many days ago it occurred.",
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // One card per severity — fits properly on phone
                severities.forEach { sev ->
                    val sevColor = severityColor(sev)
                    val days = getDays(sev)

                    BaseCard {
                        Text(
                            sev,
                            color = sevColor,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )

                        // Row 1: Today, +1d, +2d, +3d
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (dayIdx in 0 until 4) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        dayLabels[dayIdx],
                                        color = AppTheme.SubtleTextColor,
                                        style = MaterialTheme.typography.labelSmall,
                                        textAlign = TextAlign.Center
                                    )
                                    OutlinedTextField(
                                        value = days.getOrElse(dayIdx) { "0" },
                                        onValueChange = { newVal ->
                                            val filtered = newVal.filter { it.isDigit() || it == '.' }
                                            val updated = days.toMutableList()
                                            if (dayIdx < updated.size) {
                                                updated[dayIdx] = filtered
                                            }
                                            setDays(sev, updated)
                                        },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                                            textAlign = TextAlign.Center,
                                            color = Color.White
                                        ),
                                        modifier = Modifier.fillMaxWidth().height(50.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                            focusedBorderColor = sevColor,
                                            cursorColor = sevColor
                                        )
                                    )
                                }
                            }
                        }

                        // Row 2: +4d, +5d, +6d
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (dayIdx in 4 until 7) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        dayLabels[dayIdx],
                                        color = AppTheme.SubtleTextColor,
                                        style = MaterialTheme.typography.labelSmall,
                                        textAlign = TextAlign.Center
                                    )
                                    OutlinedTextField(
                                        value = days.getOrElse(dayIdx) { "0" },
                                        onValueChange = { newVal ->
                                            val filtered = newVal.filter { it.isDigit() || it == '.' }
                                            val updated = days.toMutableList()
                                            if (dayIdx < updated.size) {
                                                updated[dayIdx] = filtered
                                            }
                                            setDays(sev, updated)
                                        },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        singleLine = true,
                                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                                            textAlign = TextAlign.Center,
                                            color = Color.White
                                        ),
                                        modifier = Modifier.fillMaxWidth().height(50.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                                            focusedBorderColor = sevColor,
                                            cursorColor = sevColor
                                        )
                                    )
                                }
                            }
                            // Empty spacer to balance the row
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }

                // ── Error / success ──
                error?.let {
                    Text(it, color = Color(0xFFE57373), style = MaterialTheme.typography.bodySmall)
                }
                if (saveSuccess) {
                    Text("Saved successfully", color = Color(0xFF81C784), style = MaterialTheme.typography.bodySmall)
                }

                // ── Buttons ──
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { resetDefaults() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text("Reset Defaults")
                    }

                    Button(
                        onClick = { save() },
                        modifier = Modifier.weight(1f),
                        enabled = !saving,
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Save")
                        }
                    }
                }

                // ── How it works ──
                BaseCard {
                    Text(
                        "How it works",
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        "Each trigger and prodrome you log has a severity (High, Mild, Low, None). " +
                        "The decay weight for that severity and how many days ago it was logged " +
                        "determines its point contribution to your total risk score.\n\n" +
                        "For example with defaults: a High-severity trigger logged today " +
                        "contributes 10 points. Tomorrow it contributes 5, the day after 2.5.\n\n" +
                        "All active contributions are summed into a total score. " +
                        "The gauge thresholds then map that total to a zone:\n" +
                        "• Score ≥ 10 → High\n" +
                        "• Score ≥ 5 → Mild\n" +
                        "• Score ≥ 3 → Low\n" +
                        "• Score < 3 → None",
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

private fun zoneColor(zone: String): Color = when (zone) {
    "HIGH" -> Color(0xFFE57373)
    "MILD" -> Color(0xFFFFB74D)
    "LOW"  -> Color(0xFF81C784)
    "NONE" -> Color(0xFF666666)
    else   -> Color.White
}

private fun severityColor(severity: String): Color = when (severity) {
    "HIGH" -> Color(0xFFE57373)
    "MILD" -> Color(0xFFFFB74D)
    "LOW"  -> Color(0xFF81C784)
    else   -> Color.White
}

/** Format a Double for display: strip trailing zeros (e.g. 5.0 → "5", 2.5 → "2.5") */
private fun fmt(v: Double): String {
    return if (v == v.toLong().toDouble()) v.toLong().toString()
    else v.toBigDecimal().stripTrailingZeros().toPlainString()
}

