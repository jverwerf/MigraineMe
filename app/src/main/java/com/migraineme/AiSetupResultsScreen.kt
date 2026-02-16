package com.migraineme

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
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
    onApply: () -> Unit,
    onSkip: () -> Unit,
    isApplying: Boolean = false,
    applyProgress: AiSetupApplier.ApplyProgress? = null,
) {
    val scrollState = rememberScrollState()

    // Build icon lookup maps from pool items
    val triggerIconMap = remember(availableItems) {
        availableItems?.triggers?.associate { it.label.lowercase() to it.iconKey } ?: emptyMap()
    }
    val prodromeIconMap = remember(availableItems) {
        availableItems?.prodromes?.associate { it.label.lowercase() to it.iconKey } ?: emptyMap()
    }

    fun triggerIcon(label: String): ImageVector? = triggerIconMap[label.lowercase()]?.let { TriggerIcons.forKey(it) }
    fun prodromeIcon(label: String): ImageVector? = prodromeIconMap[label.lowercase()]?.let { ProdromeIcons.forKey(it) }

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
            Box(
                Modifier.size(48.dp).background(
                    Brush.linearGradient(listOf(AppTheme.AccentPink.copy(alpha = 0.3f), AppTheme.AccentPurple.copy(alpha = 0.2f))),
                    RoundedCornerShape(14.dp)
                ), contentAlignment = Alignment.Center
            ) { Icon(Icons.Outlined.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
            Spacer(Modifier.height(12.dp))
            Text("Your Personalised Setup", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text("Review what AI recommends. You can always adjust these later in Manage Items and Settings.", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }

        // ── Summary ──
        if (config.summary.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = AppTheme.AccentPurple.copy(alpha = 0.15f)), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = AppTheme.AccentPink, modifier = Modifier.size(20.dp))
                    Text(config.summary, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
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
        val highT = config.triggers.filter { it.severity == "HIGH" }
        val mildT = config.triggers.filter { it.severity == "MILD" }
        val lowT = config.triggers.filter { it.severity == "LOW" }

        if (highT.isNotEmpty() || mildT.isNotEmpty() || lowT.isNotEmpty()) {
            CollapsibleSection("Triggers", Icons.Outlined.Bolt, Color(0xFFFFB74D), "${highT.size} high, ${mildT.size} mild, ${lowT.size} low") {
                if (highT.isNotEmpty()) SeverityGroup("HIGH", Color(0xFFEF5350), highT.map { TDI(it.label, it.favorite, it.reasoning, it.defaultThreshold) }) { label -> triggerIcon(label) }
                if (mildT.isNotEmpty()) { Spacer(Modifier.height(8.dp)); SeverityGroup("MILD", Color(0xFFFFB74D), mildT.map { TDI(it.label, it.favorite, it.reasoning, it.defaultThreshold) }) { label -> triggerIcon(label) } }
                if (lowT.isNotEmpty()) { Spacer(Modifier.height(8.dp)); SeverityGroup("LOW", Color(0xFF81C784), lowT.map { TDI(it.label, it.favorite, it.reasoning, it.defaultThreshold) }) { label -> triggerIcon(label) } }
            }
        }

        // ── Prodromes ──
        val activeP = config.prodromes.filter { it.severity != "NONE" }
        val highP = activeP.filter { it.severity == "HIGH" }
        val mildP = activeP.filter { it.severity == "MILD" }
        val lowP = activeP.filter { it.severity == "LOW" }

        if (activeP.isNotEmpty()) {
            CollapsibleSection("Warning Signs", Icons.Outlined.Sensors, Color(0xFFCE93D8), "${highP.size} high, ${mildP.size} mild, ${lowP.size} low") {
                if (highP.isNotEmpty()) SeverityGroup("HIGH", Color(0xFFEF5350), highP.map { TDI(it.label, it.favorite, it.reasoning, it.defaultThreshold) }) { label -> prodromeIcon(label) }
                if (mildP.isNotEmpty()) { Spacer(Modifier.height(8.dp)); SeverityGroup("MILD", Color(0xFFFFB74D), mildP.map { TDI(it.label, it.favorite, it.reasoning, it.defaultThreshold) }) { label -> prodromeIcon(label) } }
                if (lowP.isNotEmpty()) { Spacer(Modifier.height(8.dp)); SeverityGroup("LOW", Color(0xFF81C784), lowP.map { TDI(it.label, it.favorite, it.reasoning, it.defaultThreshold) }) { label -> prodromeIcon(label) } }
            }
        }

        // ── Medicines ──
        if (config.medicines.isNotEmpty()) {
            CollapsibleSection("Medicines", Icons.Outlined.Medication, Color(0xFF4FC3F7), "${config.medicines.size} in quick-log") {
                config.medicines.forEach { FavoriteChip(it.label, it.reasoning) }
            }
        }

        // ── Symptoms ──
        if (config.symptoms.isNotEmpty()) {
            CollapsibleSection("Symptoms", Icons.Outlined.Healing, AppTheme.AccentPink, "${config.symptoms.size} in quick-log") {
                config.symptoms.forEach { FavoriteChip(it.label, it.reasoning) }
            }
        }

        // ── Reliefs ──
        if (config.reliefs.isNotEmpty()) {
            CollapsibleSection("Reliefs", Icons.Outlined.Spa, Color(0xFF81C784), "${config.reliefs.size} in quick-log") {
                config.reliefs.forEach { FavoriteChip(it.label, it.reasoning) }
            }
        }

        // ── Activities ──
        if (config.activities.isNotEmpty()) {
            CollapsibleSection("Activities", Icons.Outlined.DirectionsRun, Color(0xFFFF8A65), "${config.activities.size} in quick-log") {
                config.activities.forEach { FavoriteChip(it.label, it.reasoning) }
            }
        }

        // ── Missed Activities ──
        if (config.missedActivities.isNotEmpty()) {
            CollapsibleSection("Missed Activities", Icons.Outlined.EventBusy, Color(0xFFEF9A9A), "${config.missedActivities.size} in quick-log") {
                config.missedActivities.forEach { FavoriteChip(it.label, it.reasoning) }
            }
        }

        // ── Gauge Thresholds ──
        Card(colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Speed, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(18.dp))
                    Text("Risk Gauge Thresholds", color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    ThresholdBadge("Low", config.gaugeThresholds.low, Color(0xFF81C784))
                    ThresholdBadge("Mild", config.gaugeThresholds.mild, Color(0xFFFFB74D))
                    ThresholdBadge("High", config.gaugeThresholds.high, Color(0xFFEF5350))
                }
                if (config.gaugeThresholds.reasoning.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(config.gaugeThresholds.reasoning, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // ── Decay Weights ──
        if (config.decayWeights.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.TrendingDown, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(18.dp))
                        Text("Decay Curves", color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("How quickly trigger risk fades over days", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(12.dp))
                    config.decayWeights.forEach { dw ->
                        val color = when (dw.severity) { "HIGH" -> Color(0xFFEF5350); "MILD" -> Color(0xFFFFB74D); else -> Color(0xFF81C784) }
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.08f)).padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(dw.severity, color = color, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.width(40.dp))
                            listOf(dw.day0, dw.day1, dw.day2, dw.day3, dw.day4, dw.day5, dw.day6).forEachIndexed { i, v ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                    Text("${"%.1f".format(v)}", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                    Text("d$i", color = AppTheme.SubtleTextColor.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        if (dw.reasoning.isNotBlank()) {
                            Text(dw.reasoning, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 6.dp))
                        }
                        Spacer(Modifier.height(4.dp))
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
            Button(onClick = onApply, modifier = Modifier.fillMaxWidth().height(52.dp),
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
                Text("Everything here can be changed later. Go to Manage Items to adjust triggers, medicines, and reliefs. Go to Settings → Risk Model to tweak gauge thresholds.",
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
private fun ThresholdBadge(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).background(color.copy(alpha = 0.15f), CircleShape).border(2.dp, color.copy(alpha = 0.4f), CircleShape), contentAlignment = Alignment.Center) {
            Text("$value", color = color, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
    }
}

