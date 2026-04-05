package com.migraineme

import android.util.Log
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Risk Model Settings Screen
 *
 * 1. Gauge Thresholds — circle badges (tap to edit)
 * 2. Decay Weights — visual bar charts with editable values
 */
@Composable
fun RiskWeightsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val edge = remember { EdgeFunctionsService() }
    val scrollState = rememberScrollState()

    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saveSuccess by remember { mutableStateOf(false) }

    // ── Gauge thresholds ──
    var thresholdNone by remember { mutableStateOf(0.0) }
    var thresholdLow by remember { mutableStateOf(3.0) }
    var thresholdMild by remember { mutableStateOf(5.0) }
    var thresholdHigh by remember { mutableStateOf(10.0) }

    // ── Decay weights ──
    val dayLabels = listOf("T0", "+1d", "+2d", "+3d", "+4d", "+5d", "+6d")
    var highDays by remember { mutableStateOf(listOf(10.0, 5.0, 2.5, 0.0, 0.0, 0.0, 0.0)) }
    var mildDays by remember { mutableStateOf(listOf(6.0, 3.0, 1.5, 0.0, 0.0, 0.0, 0.0)) }
    var lowDays by remember { mutableStateOf(listOf(3.0, 1.5, 0.0, 0.0, 0.0, 0.0, 0.0)) }

    fun getDays(sev: String): List<Double> = when (sev) { "HIGH" -> highDays; "MILD" -> mildDays; "LOW" -> lowDays; else -> List(7) { 0.0 } }
    fun setDays(sev: String, days: List<Double>) = when (sev) { "HIGH" -> highDays = days; "MILD" -> mildDays = days; "LOW" -> lowDays = days; else -> {} }

    // ── Load ──
    LaunchedEffect(Unit) {
        val thresholdResult = withContext(Dispatchers.IO) { runCatching { edge.getRiskGaugeThresholds(context) }.getOrNull() }
        val weightResult = withContext(Dispatchers.IO) { runCatching { edge.getRiskDecayWeights(context) }.getOrNull() }

        if (thresholdResult != null) {
            for (row in thresholdResult) {
                when (row.zone.uppercase()) {
                    "NONE" -> thresholdNone = row.minValue
                    "LOW" -> thresholdLow = row.minValue
                    "MILD" -> thresholdMild = row.minValue
                    "HIGH" -> thresholdHigh = row.minValue
                }
            }
        } else { error = "Failed to load thresholds" }

        if (weightResult != null) {
            for (row in weightResult) {
                val days = listOf(row.day0, row.day1, row.day2, row.day3, row.day4, row.day5, row.day6)
                when (row.severity.uppercase()) { "HIGH" -> highDays = days; "MILD" -> mildDays = days; "LOW" -> lowDays = days }
            }
        } else { error = (error?.let { "$it\n" } ?: "") + "Failed to load weights" }

        loading = false
    }

    // ── Save ──
    fun save() {
        scope.launch {
            saving = true; error = null; saveSuccess = false
            withContext(Dispatchers.IO) {
                try {
                    var allOk = true
                    for ((zone, minVal) in listOf("LOW" to thresholdLow, "MILD" to thresholdMild, "HIGH" to thresholdHigh)) {
                        if (!edge.upsertRiskGaugeThreshold(context, zone, minVal)) allOk = false
                    }
                    for (sev in listOf("HIGH", "MILD", "LOW")) {
                        val d = getDays(sev)
                        if (!edge.upsertRiskDecayWeight(context, sev, d[0], d[1], d[2], d[3], d[4], d[5], d[6])) allOk = false
                    }
                    if (allOk) { edge.triggerRecalcRiskScores(context); saveSuccess = true }
                    else error = "Some rows failed to save"
                } catch (e: Exception) { error = "Save failed: ${e.message}" }
            }
            saving = false
        }
    }

    fun resetDefaults() {
        thresholdNone = 0.0; thresholdLow = 3.0; thresholdMild = 5.0; thresholdHigh = 10.0
        highDays = listOf(10.0, 5.0, 2.5, 0.0, 0.0, 0.0, 0.0)
        mildDays = listOf(6.0, 3.0, 1.5, 0.0, 0.0, 0.0, 0.0)
        lowDays = listOf(3.0, 1.5, 0.0, 0.0, 0.0, 0.0, 0.0)
    }

    // ── UI ──
    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            if (loading) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppTheme.AccentPurple)
                }
            } else {

                // ═══════════════════════════════════════════════════════════
                // HERO — Gauge Thresholds
                // ═══════════════════════════════════════════════════════════
                HeroCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Speed, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(18.dp))
                        Text("Gauge Thresholds", color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    }
                    Text("Minimum score to enter each risk zone. Tap to edit.", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        // NONE is always 0 — not editable
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(52.dp).background(Color(0xFF666666).copy(alpha = 0.15f), CircleShape).border(2.dp, Color(0xFF666666).copy(alpha = 0.4f), CircleShape), contentAlignment = Alignment.Center) {
                                Text("0", color = Color(0xFF666666), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("None", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        }
                        GaugeThresholdCircle("Low", thresholdLow, Color(0xFF81C784)) { thresholdLow = it }
                        GaugeThresholdCircle("Mild", thresholdMild, Color(0xFFFFB74D)) { thresholdMild = it }
                        GaugeThresholdCircle("High", thresholdHigh, Color(0xFFEF5350)) { thresholdHigh = it }
                    }
                }

                // ═══════════════════════════════════════════════════════════
                // BASE CARD — Decay Curves header
                // ═══════════════════════════════════════════════════════════
                BaseCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.TrendingDown, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(18.dp))
                        Text("Decay Curves", color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    }
                    Text("How quickly trigger risk fades over days. Tap a bar to edit.", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                }

                // One card per severity
                for (sev in listOf("HIGH", "MILD", "LOW")) {
                    val sevColor = severityColor(sev)
                    val days = getDays(sev)
                    DecayCurveCard(sev, sevColor, days, dayLabels, thresholdLow, thresholdMild, thresholdHigh) { idx, newVal ->
                        val updated = days.toMutableList(); updated[idx] = newVal; setDays(sev, updated)
                    }
                }

                // ── Error / success ──
                error?.let {
                    Text(it, color = Color(0xFFE57373), style = MaterialTheme.typography.bodySmall)
                }
                if (saveSuccess) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2A1A)), shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFF81C784), modifier = Modifier.size(18.dp))
                            Text("Saved — risk scores recalculated", color = Color(0xFF81C784), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // ── Buttons ──
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { resetDefaults() }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) { Text("Reset Defaults") }
                    Button(
                        onClick = { save() }, modifier = Modifier.weight(1f), enabled = !saving,
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                    ) {
                        if (saving) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text("Save")
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Gauge Threshold Circle — tap to edit
// ═══════════════════════════════════════════════════════════════════════════
@Composable
private fun GaugeThresholdCircle(label: String, value: Double, color: Color, onChange: (Double) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(fmt(value)) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (editing) {
            OutlinedTextField(
                value = text,
                onValueChange = { new ->
                    if (new.isEmpty() || new.all { it.isDigit() || it == '.' }) {
                        text = new
                        new.toDoubleOrNull()?.let { onChange(it) }
                    }
                },
                modifier = Modifier.width(56.dp).height(52.dp),
                textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = color, textAlign = TextAlign.Center),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = color, unfocusedBorderColor = color.copy(alpha = 0.4f)),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { editing = false }),
            )
        } else {
            Box(
                Modifier.size(52.dp).background(color.copy(alpha = 0.15f), CircleShape).border(2.dp, color.copy(alpha = 0.4f), CircleShape).clickable { editing = true },
                contentAlignment = Alignment.Center
            ) {
                Text(fmt(value), color = color, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Decay Curve Card — visual bars + tap to edit
// ═══════════════════════════════════════════════════════════════════════════
@Composable
private fun DecayCurveCard(severity: String, color: Color, values: List<Double>, labels: List<String>, thresholdLow: Double = 3.0, thresholdMild: Double = 5.0, thresholdHigh: Double = 10.0, onValueChange: (Int, Double) -> Unit) {
    var editingIndex by remember { mutableStateOf(-1) }
    var editText by remember { mutableStateOf("") }
    val maxVal = values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0

    fun barColor(v: Double): Color = when {
        v <= 0.0 -> color.copy(alpha = 0.08f)
        else -> color
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(severity, color = color, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(12.dp))

            // Bar chart
            Row(Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                values.forEachIndexed { i, v ->
                    val frac = (v / maxVal).toFloat().coerceIn(0f, 1f)
                    val barHeight = (frac * 56).dp.coerceAtLeast(4.dp)
                    val isEditing = editingIndex == i

                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        // Value label
                        if (isEditing) {
                            OutlinedTextField(
                                value = editText,
                                onValueChange = { new ->
                                    if (new.isEmpty() || new.all { it.isDigit() || it == '.' }) {
                                        editText = new
                                        new.toDoubleOrNull()?.let { onValueChange(i, it) }
                                    }
                                },
                                modifier = Modifier.width(40.dp).height(28.dp),
                                textStyle = MaterialTheme.typography.labelSmall.copy(color = color, textAlign = TextAlign.Center),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { editingIndex = -1 }),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = color, unfocusedBorderColor = color.copy(alpha = 0.3f)),
                            )
                        } else {
                            Text(
                                fmt(v), color = if (v > 0) Color.White else AppTheme.SubtleTextColor.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.clickable {
                                    editingIndex = i; editText = fmt(v)
                                }
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        // Bar — colored by gauge zone
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(barHeight)
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                .background(barColor(v).copy(alpha = 0.7f))
                                .clickable { editingIndex = i; editText = fmt(v) }
                        )
                        // Day label
                        Text(labels[i], color = AppTheme.SubtleTextColor.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }

        }
    }
}

private fun severityColor(severity: String): Color = when (severity) {
    "HIGH" -> Color(0xFFE57373); "MILD" -> Color(0xFFFFB74D); "LOW" -> Color(0xFF81C784); else -> Color.White
}

private fun fmt(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toBigDecimal().stripTrailingZeros().toPlainString()
