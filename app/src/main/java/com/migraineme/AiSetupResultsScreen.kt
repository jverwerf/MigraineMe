package com.migraineme

import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ═══════════════════════════════════════════════════════════════════════════
// Results Review Screen
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun AiSetupResultsScreen(
    config: AiSetupService.AiConfig,
    availableItems: AiSetupService.AvailableItems?,
    onApply: (AiSetupService.AiConfig) -> Unit,
    onSkip: () -> Unit,
    isApplying: Boolean = false,
    applyProgress: AiSetupApplier.ApplyProgress? = null,
) {
    val scrollState = rememberScrollState()

    // ── Mutable state for editing ──
    var triggers by remember(config) { mutableStateOf(config.triggers) }
    var prodromes by remember(config) { mutableStateOf(config.prodromes) }
    var medicines by remember(config) { mutableStateOf(config.medicines) }
    var symptoms by remember(config) { mutableStateOf(config.symptoms) }
    var reliefs by remember(config) { mutableStateOf(config.reliefs) }
    var activities by remember(config) { mutableStateOf(config.activities) }
    var missedActivities by remember(config) { mutableStateOf(config.missedActivities) }
    var gaugeThresholds by remember(config) { mutableStateOf(config.gaugeThresholds) }
    var decayWeights by remember(config) { mutableStateOf(config.decayWeights) }
    var menstruationConfig by remember(config) { mutableStateOf(config.menstruationConfig) }

    fun currentConfig() = config.copy(
        triggers = triggers, prodromes = prodromes, medicines = medicines,
        symptoms = symptoms, reliefs = reliefs, activities = activities,
        missedActivities = missedActivities, gaugeThresholds = gaugeThresholds,
        decayWeights = decayWeights, menstruationConfig = menstruationConfig,
    )

    // Build icon lookup maps from pool items
    val triggerIconMap = remember(availableItems) {
        availableItems?.triggers?.associate { it.label.lowercase() to it.iconKey } ?: emptyMap()
    }
    val prodromeIconMap = remember(availableItems) {
        availableItems?.prodromes?.associate { it.label.lowercase() to it.iconKey } ?: emptyMap()
    }
    val medicineIconMap = remember(availableItems) {
        availableItems?.medicines?.associate { it.label.lowercase() to it.iconKey } ?: emptyMap()
    }
    val symptomIconMap = remember(availableItems) {
        availableItems?.symptoms?.associate { it.label.lowercase() to it.iconKey } ?: emptyMap()
    }
    val reliefIconMap = remember(availableItems) {
        availableItems?.reliefs?.associate { it.label.lowercase() to it.iconKey } ?: emptyMap()
    }
    val activityIconMap = remember(availableItems) {
        availableItems?.activities?.associate { it.label.lowercase() to it.iconKey } ?: emptyMap()
    }
    val missedActivityIconMap = remember(availableItems) {
        availableItems?.missedActivities?.associate { it.label.lowercase() to it.iconKey } ?: emptyMap()
    }

    fun triggerIcon(label: String): ImageVector? = triggerIconMap[label.lowercase()]?.let { TriggerIcons.forKey(it) }
    fun prodromeIcon(label: String): ImageVector? = prodromeIconMap[label.lowercase()]?.let { ProdromeIcons.forKey(it) }
    fun medicineIcon(label: String): ImageVector? = medicineIconMap[label.lowercase()]?.let { MedicineIcons.forKey(it) }
    fun symptomIcon(label: String): ImageVector? = symptomIconMap[label.lowercase()]?.let { SymptomIcons.forKey(it) }
    fun reliefIcon(label: String): ImageVector? = reliefIconMap[label.lowercase()]?.let { ReliefIcons.forKey(it) }
    fun activityIcon(label: String): ImageVector? = activityIconMap[label.lowercase()]?.let { ActivityIcons.forKey(it) }
    fun missedActivityIcon(label: String): ImageVector? = missedActivityIconMap[label.lowercase()]?.let { ActivityIcons.forKey(it) }

    LaunchedEffect(scrollState.value) {
        if (TourManager.isActive() && TourManager.currentPhase() == CoachPhase.SETUP) {
            SetupScrollState.scrollPosition = scrollState.value
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Header ──
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "MigraineMe",
                modifier = Modifier.size(72.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text("Your Personalised Setup", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
        }

        // ── AI Analysis Hint ──
        Card(
            colors = CardDefaults.cardColors(containerColor = AppTheme.AccentPurple.copy(alpha = 0.1f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("MigraineMe AI has analysed your profile", color = AppTheme.AccentPurple, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                    Text("Below are personalised triggers, medicines, reliefs, gauge thresholds, and decay curves — all tailored to you. Tap any value to adjust it, or press ✕ to remove items. Everything can be changed later in Settings.",
                        color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ── Clinical Assessment — "What Our AI Found" ──
        if (config.clinicalAssessment.isNotBlank()) {
            var assessmentExpanded by remember { mutableStateOf(true) }
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E).copy(alpha = 0.9f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.border(1.dp, Brush.linearGradient(listOf(AppTheme.AccentPink.copy(alpha = 0.3f), AppTheme.AccentPurple.copy(alpha = 0.3f))), RoundedCornerShape(16.dp))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { assessmentExpanded = !assessmentExpanded }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(32.dp).background(AppTheme.AccentPink.copy(alpha = 0.15f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Psychology, null, tint = AppTheme.AccentPink, modifier = Modifier.size(18.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("What Our AI Found", color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                            Text("Clinical assessment of your profile", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        }
                        Icon(if (assessmentExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = AppTheme.SubtleTextColor, modifier = Modifier.size(20.dp))
                    }
                    AnimatedVisibility(visible = assessmentExpanded) {
                        Text(config.clinicalAssessment, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }

        // ── Gauge Calibration Notes — "How We Calibrated Your Gauge" ──
        if (config.calibrationNotes.isNotBlank()) {
            var calibrationExpanded by remember { mutableStateOf(true) }
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E).copy(alpha = 0.9f)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.border(1.dp, Brush.linearGradient(listOf(AppTheme.AccentPurple.copy(alpha = 0.3f), Color(0xFF4FC3F7).copy(alpha = 0.3f))), RoundedCornerShape(16.dp))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth().clickable { calibrationExpanded = !calibrationExpanded }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(32.dp).background(AppTheme.AccentPurple.copy(alpha = 0.15f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Analytics, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(18.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("How We Calibrated Your Gauge", color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                            Text("Why your thresholds are set where they are", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        }
                        Icon(if (calibrationExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = AppTheme.SubtleTextColor, modifier = Modifier.size(20.dp))
                    }
                    AnimatedVisibility(visible = calibrationExpanded) {
                        Text(config.calibrationNotes, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }

        // ── Data Warnings ──
        config.dataWarnings.forEach { warning ->
            val (bgColor, borderColor, icon) = when (warning.severity) {
                "high" -> Triple(Color(0xFF4A1010), Color(0xFFEF5350), Icons.Outlined.Warning)
                "medium" -> Triple(Color(0xFF4A3A10), Color(0xFFFFB74D), Icons.Outlined.Info)
                else -> Triple(Color(0xFF1A2A1A), Color(0xFF81C784), Icons.Outlined.Lightbulb)
            }
            Card(colors = CardDefaults.cardColors(containerColor = bgColor), shape = RoundedCornerShape(12.dp),
                modifier = Modifier.border(1.dp, borderColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp))) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(icon, null, tint = borderColor, modifier = Modifier.size(18.dp))
                    Text(warning.message, color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ── Triggers ──
        val highT = triggers.filter { it.severity == "HIGH" }
        val mildT = triggers.filter { it.severity == "MILD" }
        val lowT = triggers.filter { it.severity == "LOW" }

        if (highT.isNotEmpty() || mildT.isNotEmpty() || lowT.isNotEmpty()) {
            CollapsibleSection("Triggers", Icons.Outlined.Bolt, Color(0xFFFFB74D), "${highT.size} high, ${mildT.size} mild, ${lowT.size} low") {
                if (highT.isNotEmpty()) EditableSeverityGroup("HIGH", Color(0xFFEF5350), highT.map { TDI(it.label, it.favorite, it.reasoning, it.defaultThreshold) },
                    onDelete = { label -> triggers = triggers.filter { it.label != label } },
                    onChangeSeverity = { label, newSev -> triggers = triggers.map { if (it.label == label) it.copy(severity = newSev) else it } },
                    onChangeThreshold = { label, newVal -> triggers = triggers.map { if (it.label == label) it.copy(defaultThreshold = newVal) else it } },
                    iconResolver = { label -> triggerIcon(label) })
                if (mildT.isNotEmpty()) { Spacer(Modifier.height(8.dp)); EditableSeverityGroup("MILD", Color(0xFFFFB74D), mildT.map { TDI(it.label, it.favorite, it.reasoning, it.defaultThreshold) },
                    onDelete = { label -> triggers = triggers.filter { it.label != label } },
                    onChangeSeverity = { label, newSev -> triggers = triggers.map { if (it.label == label) it.copy(severity = newSev) else it } },
                    onChangeThreshold = { label, newVal -> triggers = triggers.map { if (it.label == label) it.copy(defaultThreshold = newVal) else it } },
                    iconResolver = { label -> triggerIcon(label) }) }
                if (lowT.isNotEmpty()) { Spacer(Modifier.height(8.dp)); EditableSeverityGroup("LOW", Color(0xFF81C784), lowT.map { TDI(it.label, it.favorite, it.reasoning, it.defaultThreshold) },
                    onDelete = { label -> triggers = triggers.filter { it.label != label } },
                    onChangeSeverity = { label, newSev -> triggers = triggers.map { if (it.label == label) it.copy(severity = newSev) else it } },
                    onChangeThreshold = { label, newVal -> triggers = triggers.map { if (it.label == label) it.copy(defaultThreshold = newVal) else it } },
                    iconResolver = { label -> triggerIcon(label) }) }
            }
        }

        // ── Prodromes ──
        val activeP = prodromes.filter { it.severity != "NONE" }
        val highP = activeP.filter { it.severity == "HIGH" }
        val mildP = activeP.filter { it.severity == "MILD" }
        val lowP = activeP.filter { it.severity == "LOW" }

        if (activeP.isNotEmpty()) {
            CollapsibleSection("Warning Signs", Icons.Outlined.Sensors, Color(0xFFCE93D8), "${highP.size} high, ${mildP.size} mild, ${lowP.size} low") {
                if (highP.isNotEmpty()) EditableSeverityGroup("HIGH", Color(0xFFEF5350), highP.map { TDI(it.label, it.favorite, it.reasoning, it.defaultThreshold) },
                    onDelete = { label -> prodromes = prodromes.filter { it.label != label } },
                    onChangeSeverity = { label, newSev -> prodromes = prodromes.map { if (it.label == label) it.copy(severity = newSev) else it } },
                    iconResolver = { label -> prodromeIcon(label) })
                if (mildP.isNotEmpty()) { Spacer(Modifier.height(8.dp)); EditableSeverityGroup("MILD", Color(0xFFFFB74D), mildP.map { TDI(it.label, it.favorite, it.reasoning, it.defaultThreshold) },
                    onDelete = { label -> prodromes = prodromes.filter { it.label != label } },
                    onChangeSeverity = { label, newSev -> prodromes = prodromes.map { if (it.label == label) it.copy(severity = newSev) else it } },
                    iconResolver = { label -> prodromeIcon(label) }) }
                if (lowP.isNotEmpty()) { Spacer(Modifier.height(8.dp)); EditableSeverityGroup("LOW", Color(0xFF81C784), lowP.map { TDI(it.label, it.favorite, it.reasoning, it.defaultThreshold) },
                    onDelete = { label -> prodromes = prodromes.filter { it.label != label } },
                    onChangeSeverity = { label, newSev -> prodromes = prodromes.map { if (it.label == label) it.copy(severity = newSev) else it } },
                    iconResolver = { label -> prodromeIcon(label) }) }
            }
        }

        // ── Medicines ──
        if (medicines.isNotEmpty()) {
            CollapsibleSection("Medicines", Icons.Outlined.Medication, Color(0xFF4FC3F7), "${medicines.size} in quick-log") {
                medicines.forEach { item -> DeletableFavoriteChip(item.label, item.reasoning, icon = medicineIcon(item.label), iconTint = Color(0xFF4FC3F7)) { medicines = medicines.filter { it.label != item.label } } }
            }
        }

        // ── Symptoms ──
        if (symptoms.isNotEmpty()) {
            CollapsibleSection("Symptoms", Icons.Outlined.Healing, AppTheme.AccentPink, "${symptoms.size} in quick-log") {
                symptoms.forEach { item -> DeletableFavoriteChip(item.label, item.reasoning, icon = symptomIcon(item.label), iconTint = AppTheme.AccentPink) { symptoms = symptoms.filter { it.label != item.label } } }
            }
        }

        // ── Reliefs ──
        if (reliefs.isNotEmpty()) {
            CollapsibleSection("Reliefs", Icons.Outlined.Spa, Color(0xFF81C784), "${reliefs.size} in quick-log") {
                reliefs.forEach { item -> DeletableFavoriteChip(item.label, item.reasoning, icon = reliefIcon(item.label), iconTint = Color(0xFF81C784)) { reliefs = reliefs.filter { it.label != item.label } } }
            }
        }

        // ── Activities ──
        if (activities.isNotEmpty()) {
            CollapsibleSection("Activities", Icons.Outlined.DirectionsRun, Color(0xFFFF8A65), "${activities.size} in quick-log") {
                activities.forEach { item -> DeletableFavoriteChip(item.label, item.reasoning, icon = activityIcon(item.label), iconTint = Color(0xFFFF8A65)) { activities = activities.filter { it.label != item.label } } }
            }
        }

        // ── Missed Activities ──
        if (missedActivities.isNotEmpty()) {
            CollapsibleSection("Missed Activities", Icons.Outlined.EventBusy, Color(0xFFFF7043), "${missedActivities.size} in quick-log") {
                missedActivities.forEach { item -> DeletableFavoriteChip(item.label, item.reasoning, icon = missedActivityIcon(item.label), iconTint = Color(0xFFFF7043)) { missedActivities = missedActivities.filter { it.label != item.label } } }
            }
        }

        // ── Risk Gauge Configuration (thresholds + decay — single card) ──
        Card(colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Speed, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(18.dp))
                    Text("Risk Gauge Configuration", color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                }
                Spacer(Modifier.height(4.dp))
                Text("Tap any value below to adjust it. You can always fine-tune these later in Settings → Risk Model.",
                    color = AppTheme.AccentPurple.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)

                // ── Thresholds ──
                Spacer(Modifier.height(14.dp))
                Text("Zone Thresholds", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(4.dp))
                Text("Set where the gauge transitions between None → Low → Mild → High", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    EditableThresholdBadge("Low", gaugeThresholds.low, Color(0xFF81C784)) { gaugeThresholds = gaugeThresholds.copy(low = it) }
                    EditableThresholdBadge("Mild", gaugeThresholds.mild, Color(0xFFFFB74D)) { gaugeThresholds = gaugeThresholds.copy(mild = it) }
                    EditableThresholdBadge("High", gaugeThresholds.high, Color(0xFFEF5350)) { gaugeThresholds = gaugeThresholds.copy(high = it) }
                }
                if (gaugeThresholds.reasoning.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(gaugeThresholds.reasoning, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                }

                // ── Decay Curves ──
                if (decayWeights.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = AppTheme.TrackColor)
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.TrendingDown, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(16.dp))
                        Text("Decay Curves", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("How quickly trigger risk fades over days — one template per severity", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)

                    decayWeights.forEachIndexed { idx, dw ->
                        val color = when (dw.severity) { "HIGH" -> Color(0xFFEF5350); "MILD" -> Color(0xFFFFB74D); else -> Color(0xFF81C784) }
                        val values = listOf(dw.day0, dw.day1, dw.day2, dw.day3, dw.day4, dw.day5, dw.day6)
                        val maxVal = values.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
                        val labels = listOf("T0", "+1d", "+2d", "+3d", "+4d", "+5d", "+6d")

                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                            Spacer(Modifier.width(8.dp))
                            Text(dw.severity, color = color, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                            IconButton(onClick = { decayWeights = decayWeights.toMutableList().also { it.removeAt(idx) } }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Outlined.Close, "Remove", tint = AppTheme.SubtleTextColor, modifier = Modifier.size(14.dp))
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                            values.forEachIndexed { i, v ->
                                val frac = (v / maxVal).toFloat().coerceIn(0f, 1f)
                                val barHeight = (frac * 30).dp.coerceAtLeast(3.dp)
                                val zoneColor = if (v <= 0.0) color.copy(alpha = 0.08f) else color
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("${"%.1f".format(v)}", color = if (v > 0) Color.White else AppTheme.SubtleTextColor.copy(alpha = 0.4f), style = MaterialTheme.typography.labelSmall)
                                    Spacer(Modifier.height(2.dp))
                                    Box(Modifier.fillMaxWidth().height(barHeight).clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)).background(zoneColor.copy(alpha = 0.7f)))
                                    Text(labels[i], color = AppTheme.SubtleTextColor.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        if (dw.reasoning.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(dw.reasoning, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        // ── Menstruation Setup ──
        menstruationConfig?.let { mc ->
            Card(
                colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.border(1.dp, Brush.linearGradient(listOf(Color(0xFFE57373).copy(alpha = 0.3f), Color(0xFFCE93D8).copy(alpha = 0.3f))), RoundedCornerShape(16.dp))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.FavoriteBorder, null, tint = Color(0xFFE57373), modifier = Modifier.size(18.dp))
                        Text("Menstruation Tracking", color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Outlined.Close, "Remove", tint = AppTheme.SubtleTextColor, modifier = Modifier.size(16.dp).clickable { menstruationConfig = null })
                    }
                    Spacer(Modifier.height(12.dp))

                    // Cycle info
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("Cycle Length", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                            Text("${mc.avgCycleLength} days", color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Severity", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                            val sevColor = when (mc.severity) { "HIGH" -> Color(0xFFEF5350); "MILD" -> Color(0xFFFFB74D); else -> Color(0xFF81C784) }
                            Box(Modifier.background(sevColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text(mc.severity, color = sevColor, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                            }
                        }
                    }

                    // Decay curve visualization
                    if (mc.decayCurve.size == 15) {
                        Spacer(Modifier.height(12.dp))
                        Text("Risk Curve (days relative to period)", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(6.dp))
                        val labels = listOf("-7", "-6", "-5", "-4", "-3", "-2", "-1", "T0", "+1", "+2", "+3", "+4", "+5", "+6", "+7")
                        val maxVal = mc.decayCurve.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
                        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.Bottom) {
                            mc.decayCurve.forEachIndexed { i, v ->
                                val frac = (v / maxVal).toFloat().coerceIn(0f, 1f)
                                val barColor = when { i < 7 -> Color(0xFFFFB74D); i == 7 -> Color(0xFFE57373); else -> Color(0xFF81C784) }
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.fillMaxWidth().height((frac * 36).dp.coerceAtLeast(2.dp)).background(barColor.copy(alpha = 0.6f), RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)))
                                    Text(labels[i], color = AppTheme.SubtleTextColor.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            }
                        }
                    }

                    if (mc.reasoning.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(mc.reasoning, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // ── Apply / Progress ──
        Spacer(Modifier.height(8.dp))
        if (isApplying && applyProgress != null) {
            Card(colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Applying configuration...", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { applyProgress.current.toFloat() / applyProgress.total.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = AppTheme.AccentPink, trackColor = AppTheme.TrackColor
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("${applyProgress.current}/${applyProgress.total} — ${applyProgress.label}", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                }
            }
        } else {
            Button(onClick = { onApply(currentConfig()) }, modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Filled.Check, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Apply Configuration", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            }
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text("Skip — I'll configure manually in Settings", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium)
            }
        }

        // Reassurance card
        Card(colors = CardDefaults.cardColors(containerColor = AppTheme.AccentPurple.copy(alpha = 0.1f)), shape = RoundedCornerShape(12.dp)) {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Tune, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(18.dp))
                Text("Everything here is fully adjustable. Go to Manage Items to change triggers, medicines, and reliefs. Go to Settings → Risk Model to adjust gauge thresholds and decay curves at any time. The AI will also suggest refinements on Insights as it learns from your data.",
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Components
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun CollapsibleSection(title: String, icon: ImageVector, iconColor: Color, badge: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(true) }
    Card(colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(18.dp))
                Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                Text(badge, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = AppTheme.SubtleTextColor, modifier = Modifier.size(20.dp))
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
            }
        }
    }
}

private data class TDI(val label: String, val favorite: Boolean, val reasoning: String, val threshold: Double? = null)

@Composable
private fun SeverityGroup(severity: String, color: Color, items: List<TDI>, iconResolver: ((String) -> ImageVector?)? = null) {
    Text(severity, color = color, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
    Spacer(Modifier.height(4.dp))
    items.forEach { item ->
        val icon = iconResolver?.invoke(item.label)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.08f)).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) {
                Icon(icon, null, tint = color.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
            }
            if (item.favorite) Text("★", color = Color(0xFFFFD54F), style = MaterialTheme.typography.labelMedium)
            Column(Modifier.weight(1f)) {
                Text(item.label, color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.reasoning.isNotBlank()) Text(item.reasoning, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (item.threshold != null) {
                Text("⚡${String.format("%.1f", item.threshold)}", color = color.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ItemChip(label: String, severity: String, isFavorite: Boolean, reasoning: String, icon: ImageVector? = null) {
    val color = when (severity) { "HIGH" -> Color(0xFFEF5350); "MILD" -> Color(0xFFFFB74D); else -> Color(0xFF81C784) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.08f)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) {
            Icon(icon, null, tint = color.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
        }
        if (isFavorite) Text("★", color = Color(0xFFFFD54F), style = MaterialTheme.typography.labelMedium)
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, style = MaterialTheme.typography.bodySmall)
            if (reasoning.isNotBlank()) Text(reasoning, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        }
        Box(Modifier.background(color.copy(alpha = 0.2f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(severity, color = color, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun FavoriteChip(label: String, reasoning: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AppTheme.AccentPurple.copy(alpha = 0.08f)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("★", color = Color(0xFFFFD54F), style = MaterialTheme.typography.labelMedium)
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, style = MaterialTheme.typography.bodySmall)
            if (reasoning.isNotBlank()) Text(reasoning, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        }
    }
}

@Composable
private fun EditableSeverityGroup(severity: String, color: Color, items: List<TDI>, onDelete: (String) -> Unit, onChangeSeverity: (String, String) -> Unit, onChangeThreshold: (String, Double) -> Unit = { _, _ -> }, iconResolver: ((String) -> ImageVector?)? = null) {
    Text(severity, color = color, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
    Spacer(Modifier.height(4.dp))
    items.forEach { item ->
        val icon = iconResolver?.invoke(item.label)
        var showSeverityMenu by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.08f)).padding(start = 10.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) Icon(icon, null, tint = color.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
            if (item.favorite) Text("★", color = Color(0xFFFFD54F), style = MaterialTheme.typography.labelMedium)
            Column(Modifier.weight(1f)) {
                Text(item.label, color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.reasoning.isNotBlank()) Text(item.reasoning, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (item.threshold != null) {
                var editingThreshold by remember { mutableStateOf(false) }
                var thresholdText by remember(item.threshold) { mutableStateOf(String.format("%.1f", item.threshold)) }
                if (editingThreshold) {
                    OutlinedTextField(
                        value = thresholdText,
                        onValueChange = { new -> if (new.isEmpty() || new.all { it.isDigit() || it == '.' }) thresholdText = new },
                        modifier = Modifier.width(52.dp).height(36.dp),
                        textStyle = MaterialTheme.typography.labelSmall.copy(color = color, textAlign = TextAlign.Center),
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = color, unfocusedBorderColor = color.copy(alpha = 0.3f)),
                    )
                    IconButton(onClick = { thresholdText.toDoubleOrNull()?.let { onChangeThreshold(item.label, it) }; editingThreshold = false }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Filled.Check, null, tint = color, modifier = Modifier.size(12.dp))
                    }
                } else {
                    Text("⚡${String.format("%.1f", item.threshold)}", color = color.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { editingThreshold = true })
                }
            }
            // Severity chip — tap to change
            Box {
                Box(Modifier.clickable { showSeverityMenu = true }.background(color.copy(alpha = 0.2f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(severity, color = color, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                }
                DropdownMenu(expanded = showSeverityMenu, onDismissRequest = { showSeverityMenu = false }) {
                    listOf("HIGH", "MILD", "LOW").forEach { sev ->
                        DropdownMenuItem(text = { Text(sev) }, onClick = { onChangeSeverity(item.label, sev); showSeverityMenu = false })
                    }
                }
            }
            // Delete
            IconButton(onClick = { onDelete(item.label) }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Outlined.Close, "Remove", tint = AppTheme.SubtleTextColor, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun DeletableFavoriteChip(label: String, reasoning: String, icon: ImageVector? = null, iconTint: Color = Color.White, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AppTheme.AccentPurple.copy(alpha = 0.08f)).padding(start = 10.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) {
            Icon(icon, null, tint = iconTint.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
        } else {
            Text("★", color = Color(0xFFFFD54F), style = MaterialTheme.typography.labelMedium)
        }
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, style = MaterialTheme.typography.bodySmall)
            if (reasoning.isNotBlank()) Text(reasoning, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Outlined.Close, "Remove", tint = AppTheme.SubtleTextColor, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun EditableThresholdBadge(label: String, value: Int, color: Color, onChange: (Int) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var text by remember(value) { mutableStateOf(value.toString()) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (editing) {
            OutlinedTextField(
                value = text,
                onValueChange = { new -> if (new.isEmpty() || new.all { it.isDigit() }) text = new },
                modifier = Modifier.width(56.dp).height(48.dp),
                textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = color, textAlign = TextAlign.Center),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = color, unfocusedBorderColor = color.copy(alpha = 0.4f)),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            TextButton(onClick = { text.toIntOrNull()?.let { onChange(it) }; editing = false }, modifier = Modifier.height(24.dp), contentPadding = PaddingValues(0.dp)) {
                Text("Done", color = color, style = MaterialTheme.typography.labelSmall)
            }
        } else {
            Box(Modifier.size(48.dp).background(color.copy(alpha = 0.15f), CircleShape).border(2.dp, color.copy(alpha = 0.4f), CircleShape).clickable { editing = true }, contentAlignment = Alignment.Center) {
                Text("$value", color = color, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ThresholdBadge(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).background(color.copy(alpha = 0.15f), CircleShape).border(2.dp, color.copy(alpha = 0.4f), CircleShape), contentAlignment = Alignment.Center) {
            Text("$value", color = color, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
    }
}