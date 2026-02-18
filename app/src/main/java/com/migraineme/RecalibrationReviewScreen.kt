package com.migraineme

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun RecalibrationReviewScreen(
    onBack: () -> Unit = {},
    vm: RecalibrationViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val ctx = LocalContext.current
    val scrollState = rememberScrollState()

    // Load proposals on first render
    LaunchedEffect(Unit) {
        vm.loadProposals(ctx)
    }

    // Navigate back on success
    LaunchedEffect(state.applied) {
        if (state.applied) {
            // Clear the SharedPrefs flag
            ctx.getSharedPreferences("recalibration", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("has_proposals", false).apply()
        }
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 60.dp) {

            // ── Header ──
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Text(
                    "AI Recalibration",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }

            // ── Loading ──
            if (state.loading) {
                BaseCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = AppTheme.AccentPurple
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Analysing your data...", color = AppTheme.BodyTextColor)
                    }
                }
                return@ScrollableScreenContent
            }

            // ── Error ──
            if (state.error != null) {
                BaseCard {
                    Text("Something went wrong", color = AppTheme.AccentPink, fontWeight = FontWeight.SemiBold)
                    Text(state.error ?: "", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
                }
                return@ScrollableScreenContent
            }

            // ── Applied success ──
            if (state.applied) {
                BaseCard {
                    Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFF81C784), modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Recalibration applied!", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Your trigger settings, gauge thresholds, and favorites have been updated.",
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Back to Home")
                    }
                }
                return@ScrollableScreenContent
            }

            // ── No proposals ──
            if (state.proposals.isEmpty()) {
                BaseCard {
                    Text("No learning this month", color = AppTheme.TitleColor, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "We don't have enough data yet to suggest changes. Keep logging your migraines, triggers, and symptoms — once we spot patterns, we'll have personalised suggestions for you here.",
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Got it")
                    }
                }
                return@ScrollableScreenContent
            }

            // ── Clinical Assessment ──
            if (state.clinicalAssessment.isNotBlank()) {
                BaseCard {
                    Text(
                        "What we found",
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.clinicalAssessment,
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // ── Calibration Notes ──
            if (state.calibrationNotes.isNotBlank()) {
                BaseCard {
                    Text(
                        "Gauge performance",
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.calibrationNotes,
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // ── Accept All / Reject All ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { vm.acceptAll() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Accept all", color = Color.White)
                }
                OutlinedButton(
                    onClick = { vm.rejectAll() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Reject all", color = Color.White)
                }
            }

            // ── Proposals grouped by type, fixed display order ──
            val grouped = state.proposals.groupBy { it.type }
            val displayOrder = listOf(
                "clinical_assessment" to "Updated clinical profile",
                "profile" to "Profile updates",
                "trigger" to "Trigger adjustments",
                "prodrome" to "Prodrome adjustments",
                "medicine" to "Medicine favorites",
                "relief" to "Relief favorites",
                "symptom" to "Symptom favorites",
                "activity" to "Activity favorites",
                "missed_activity" to "Missed activity favorites",
                "gauge_threshold" to "Gauge thresholds",
                "gauge_decay" to "Decay curves",
                "data_warning" to "Data warnings",
            )

            for ((type, sectionTitle) in displayOrder) {
                val proposals = grouped[type] ?: continue

                BaseCard {
                    Text(
                        sectionTitle,
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Spacer(Modifier.height(4.dp))

                    // Gauge thresholds: combined table at the top
                    if (type == "gauge_threshold" && proposals.size > 1) {
                        GaugeThresholdTable(proposals)
                        Spacer(Modifier.height(8.dp))
                    }

                    for (proposal in proposals) {
                        ProposalRow(
                            proposal = proposal,
                            onToggle = { vm.toggleProposal(proposal.id) },
                        )
                        if (proposal != proposals.last()) {
                            Divider(
                                color = AppTheme.SubtleTextColor.copy(alpha = 0.2f),
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            // ── Apply Button ──
            val acceptedCount = state.proposals.count { it.accepted }
            val rejectedCount = state.proposals.count { !it.accepted }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = { vm.applyDecisions(ctx) },
                enabled = !state.applying,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (state.applying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Applying...")
                } else {
                    Text("Apply $acceptedCount change${if (acceptedCount != 1) "s" else ""}" +
                            if (rejectedCount > 0) " ($rejectedCount rejected)" else "")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProposalRow(
    proposal: RecalibrationViewModel.Proposal,
    onToggle: () -> Unit,
) {
    val bgColor by animateColorAsState(
        if (proposal.accepted) Color(0xFF2D2D3D) else Color(0xFF1A1A24),
        label = "proposalBg",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable { onToggle() }
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Accept/reject checkbox
        Checkbox(
            checked = proposal.accepted,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = AppTheme.AccentPurple,
                uncheckedColor = AppTheme.SubtleTextColor,
            ),
        )

        Spacer(Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Label + change
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    proposal.label,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                if (proposal.fromValue != null && proposal.toValue != null &&
                    proposal.type !in listOf("data_warning", "gauge_decay", "gauge_threshold", "clinical_assessment")) {
                    Spacer(Modifier.width(8.dp))
                    SeverityBadge(proposal.fromValue)
                    Text(" → ", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    SeverityBadge(proposal.toValue)
                }
            }

            // Decay curve visualization
            if (proposal.type == "gauge_decay" && proposal.fromValue != null && proposal.toValue != null) {
                Spacer(Modifier.height(4.dp))
                DecayCurveComparison(proposal.fromValue, proposal.toValue, proposal.accepted)
            }

            // Favorite indicator
            if (proposal.shouldFavorite && proposal.toValue == "favorite") {
                Text(
                    "★ Add to favorites",
                    color = Color(0xFFFFD54F),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (!proposal.shouldFavorite && proposal.fromValue == "favorite") {
                Text(
                    "Remove from favorites",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Reasoning
            if (!proposal.reasoning.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    proposal.reasoning,
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Clinical assessment — expandable full text
            if (proposal.type == "clinical_assessment" && !proposal.toValue.isNullOrBlank()) {
                var expanded by remember { mutableStateOf(false) }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (expanded) proposal.toValue else proposal.toValue.take(200) + "…",
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (expanded) "Show less" else "Read full assessment",
                    color = AppTheme.AccentPurple,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .clickable { expanded = !expanded }
                        .padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SeverityBadge(value: String) {
    val color = when (value.uppercase()) {
        "HIGH" -> Color(0xFFE57373)
        "MILD" -> Color(0xFFFFB74D)
        "LOW" -> Color(0xFF81C784)
        "NONE" -> AppTheme.SubtleTextColor
        "FAVORITE", "NOT_FAVORITE" -> Color(0xFFFFD54F)
        else -> AppTheme.SubtleTextColor
    }

    Text(
        value.uppercase(),
        color = color,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
    )
}

@Composable
private fun GaugeThresholdTable(proposals: List<RecalibrationViewModel.Proposal>) {
    val zoneOrder = listOf("LOW", "MILD", "HIGH")
    val sorted = zoneOrder.mapNotNull { zone -> proposals.find { it.label.uppercase() == zone } }

    if (sorted.isEmpty()) return

    Column {
        // Header row: Zone labels
        Row {
            Text("", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(36.dp))
            for (p in sorted) {
                val zoneColor = when (p.label.uppercase()) {
                    "HIGH" -> Color(0xFFE57373)
                    "MILD" -> Color(0xFFFFB74D)
                    "LOW" -> Color(0xFF81C784)
                    else -> AppTheme.SubtleTextColor
                }
                Text(p.label.uppercase(), color = zoneColor,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.width(56.dp), textAlign = TextAlign.Center)
            }
        }

        // Was row
        Row {
            Text("Was", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(36.dp))
            for (p in sorted) {
                Text(p.fromValue ?: "–", color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(56.dp), textAlign = TextAlign.Center)
            }
        }

        // New row
        Row {
            Text("New", color = Color(0xFF81C784),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.width(36.dp))
            for (p in sorted) {
                val effectiveNew = if (p.accepted) p.toValue else p.fromValue
                val changed = effectiveNew != p.fromValue
                Text(effectiveNew ?: "–",
                    color = if (changed) Color(0xFFFFB74D) else AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (changed) FontWeight.Bold else FontWeight.Normal
                    ),
                    modifier = Modifier.width(56.dp), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun DecayCurveComparison(fromJson: String, toJson: String, accepted: Boolean) {
    val fromDays = parseDecayDays(fromJson)
    val toDays = parseDecayDays(toJson)

    if (toDays.isEmpty()) return

    Column {
        // Header
        Row {
            Text("Day", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(36.dp))
            for (i in 0..6) {
                Text("$i", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
            }
        }

        // "Was" row
        Row {
            Text("Was", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(36.dp))
            for (i in 0..6) {
                val v = fromDays.getOrNull(i)
                Text(
                    if (v != null) formatDecayVal(v) else "–",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(36.dp), textAlign = TextAlign.Center,
                )
            }
        }

        // "New" row
        Row {
            Text("New", color = Color(0xFF81C784), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.width(36.dp))
            for (i in 0..6) {
                val newV = toDays.getOrElse(i) { 0.0 }
                val oldV = fromDays.getOrNull(i)
                val effectiveV = if (accepted) newV else (oldV ?: newV)
                val changed = accepted && (oldV == null || oldV != newV)
                Text(
                    formatDecayVal(effectiveV),
                    color = if (changed) Color(0xFFFFB74D) else AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = if (changed) FontWeight.Bold else FontWeight.Normal
                    ),
                    modifier = Modifier.width(36.dp), textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun parseDecayDays(json: String): List<Double> {
    return try {
        val obj = org.json.JSONObject(json)
        (0..6).map { obj.optDouble("day$it", 0.0) }
    } catch (_: Exception) { emptyList() }
}

private fun formatDecayVal(v: Double): String {
    return if (v == v.toLong().toDouble()) v.toLong().toString()
    else String.format("%.1f", v)
}
