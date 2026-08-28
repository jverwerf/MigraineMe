package com.migraineme

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

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
                        Text(t("Analysing your data..."), color = AppTheme.BodyTextColor)
                    }
                }
                return@ScrollableScreenContent
            }

            // ── Error ──
            if (state.error != null) {
                BaseCard {
                    Text(t("Something went wrong"), color = AppTheme.AccentPink, fontWeight = FontWeight.SemiBold)
                    Text(state.error ?: "", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
                }
                return@ScrollableScreenContent
            }

            // ── Applied success ──
            if (state.applied) {
                BaseCard {
                    Icon(Icons.Outlined.CheckCircle, null, tint = Color(0xFF81C784), modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(t("Recalibration applied!"), color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        t("Your trigger settings, gauge thresholds, and favorites have been updated."),
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(t("Back to Home"))
                    }
                }
                return@ScrollableScreenContent
            }

            // ── No proposals ──
            if (state.proposals.isEmpty()) {
                BaseCard {
                    Text(t("No learning this week"), color = AppTheme.TitleColor, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t("We don't have enough data yet to suggest changes. Keep logging your migraines, triggers, and symptoms — once we spot patterns, we'll have personalised suggestions for you here."),
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(t("Got it"))
                    }
                }
                return@ScrollableScreenContent
            }

            // ── Intro card — pinned to top ──
            BaseCard {
                Text(
                    t("Your recalibration"),
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    t("We've reviewed your recent migraines and logs and prepared the suggestions below. Use the buttons to accept or reject everything in one tap, or scroll down and pick the ones you want yourself."),
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // ── Accept All / Reject All — pinned to top ──
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
                    Text(t("Accept all"), color = Color.White)
                }
                OutlinedButton(
                    onClick = { vm.rejectAll() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(t("Reject all"), color = Color.White)
                }
            }

            // ── Clinical Assessment ──
            if (state.clinicalAssessment.isNotBlank()) {
                BaseCard {
                    Text(
                        t("What we found"),
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
                        t("Gauge performance"),
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

            // ── Proposals grouped by type, fixed display order ──
            val grouped = state.proposals.groupBy { it.type }
            val displayOrder = listOf(
                "clinical_assessment" to "Updated clinical profile",
                "answer" to "Your answers",
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
                "menstruation_decay" to "Menstrual cycle decay",
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
                    if (type == "answer") {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            t("From what you have logged, some of your setup answers look different now. Keep the suggestion, pick another value, or untick to leave your answer as it is."),
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else if (type != "data_warning") {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            t("Keep checked to accept, untap to reject."),
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Spacer(Modifier.height(4.dp))

                    for (proposal in proposals) {
                        if (type == "data_warning") {
                            WarningRow(proposal)
                        } else if (type == "answer") {
                            AnswerProposalRow(
                                proposal = proposal,
                                onToggle = { vm.toggleProposal(proposal.id) },
                                onChoose = { value -> vm.chooseAnswer(proposal.id, value) },
                            )
                        } else {
                            ProposalRow(
                                proposal = proposal,
                                onToggle = { vm.toggleProposal(proposal.id) },
                            )
                        }
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
                    Text(t("Applying..."))
                } else {
                    Text((if (acceptedCount == 1) t("Apply 1 change") else t("Apply %s changes", acceptedCount)) +
                            if (rejectedCount > 0) t(" (%s rejected)", rejectedCount) else "")
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
                Text(t(proposal.label),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )

                if (proposal.fromValue != null && proposal.toValue != null &&
                    proposal.type !in listOf("data_warning", "clinical_assessment", "gauge_decay", "menstruation_decay")) {
                    Spacer(Modifier.width(8.dp))
                    if (proposal.type == "gauge_threshold") {
                        Text(t("Was "), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        Text(proposal.fromValue, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                        Text(" → ", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        Text(proposal.toValue, color = Color(0xFF81C784), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                    } else {
                        SeverityBadge(proposal.fromValue)
                        Text(" → ", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        SeverityBadge(proposal.toValue)
                    }
                }
            }

            // Decay curve visualization (only if values are JSON day maps)
            if (proposal.type == "gauge_decay" && proposal.fromValue != null && proposal.toValue != null
                && proposal.fromValue.contains("day")) {
                Spacer(Modifier.height(4.dp))
                DecayCurveComparison(proposal.fromValue, proposal.toValue, proposal.accepted)
            }

            // Menstrual cycle decay visualization (15 days m7..0..p7)
            if (proposal.type == "menstruation_decay" && proposal.fromValue != null && proposal.toValue != null
                && proposal.fromValue.contains("day_")) {
                Spacer(Modifier.height(4.dp))
                MenstrualDecayComparison(proposal.fromValue, proposal.toValue, proposal.accepted)
            }

            // Favorite indicator
            if (proposal.shouldFavorite && proposal.toValue == "favorite") {
                Text(
                    t("★ Add to favorites"),
                    color = Color(0xFFFFD54F),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (!proposal.shouldFavorite && proposal.fromValue == "favorite") {
                Text(
                    t("Remove from favorites"),
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
                    if (expanded) t("Show less") else t("Read full assessment"),
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

/**
 * A proposed correction to one of the setup answers (type "answer"): the
 * question as asked, what the user said, what the data suggests, and every
 * allowed value as a chip so a third value can be picked. Tapping a chip
 * also ticks the row; untick to keep the original answer.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AnswerProposalRow(
    proposal: RecalibrationViewModel.Proposal,
    onToggle: () -> Unit,
    onChoose: (String) -> Unit,
) {
    val bgColor by animateColorAsState(
        if (proposal.accepted) Color(0xFF2D2D3D) else Color(0xFF1A1A24),
        label = "answerBg",
    )
    val isCertainty = proposal.kind == "certainty"
    fun show(v: String?): String = when {
        v == null -> Strings.tSync("Not answered")
        isCertainty -> certaintyWord(v)
        else -> Strings.tSync(v)
    }
    val effective = proposal.effectiveValue

    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(bgColor).padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = proposal.accepted,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = AppTheme.AccentPurple, uncheckedColor = AppTheme.SubtleTextColor),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                proposal.question?.let { t(it) } ?: t(proposal.label),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("You said"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(6.dp))
                Text(show(proposal.fromValue), color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough))
                Text("  →  ", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                Text(show(effective), color = Color(0xFF81C784), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                if (proposal.chosenValue != null && proposal.chosenValue != proposal.toValue) {
                    Spacer(Modifier.width(6.dp))
                    Text(t("(your pick)"), color = AppTheme.AccentPurple, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (!proposal.reasoning.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(proposal.reasoning, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
            }
            if (proposal.options.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    proposal.options.forEach { opt ->
                        val selected = proposal.accepted && opt == effective
                        val suggested = opt == proposal.toValue
                        Text(
                            show(opt),
                            color = if (selected) Color.White else AppTheme.BodyTextColor,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) AppTheme.AccentPurple.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.07f))
                                .border(1.dp, if (suggested) AppTheme.AccentPurple.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                                .clickable { onChoose(opt) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun certaintyWord(v: String): String = when (v.uppercase()) {
    "EVERY_TIME" -> Strings.tSync("Every time")
    "OFTEN" -> Strings.tSync("Often")
    "SOMETIMES" -> Strings.tSync("Sometimes")
    "RARELY" -> Strings.tSync("Rarely")
    "NO" -> Strings.tSync("No")
    else -> v
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
                Text(t(p.label).uppercase(), color = zoneColor,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.width(56.dp), textAlign = TextAlign.Center)
            }
        }

        // Was row
        Row {
            Text(t("Was"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(36.dp))
            for (p in sorted) {
                Text(p.fromValue ?: "–", color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(56.dp), textAlign = TextAlign.Center)
            }
        }

        // New row
        Row {
            Text(t("New"), color = Color(0xFF81C784),
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
            Text(t("Day"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(36.dp))
            for (i in 0..6) {
                Text("$i", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
            }
        }

        // "Was" row
        Row {
            Text(t("Was"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall,
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
            Text(t("New"), color = Color(0xFF81C784), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
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

private val MENSTRUAL_KEYS = listOf(
    "day_m7","day_m6","day_m5","day_m4","day_m3","day_m2","day_m1",
    "day_0",
    "day_p1","day_p2","day_p3","day_p4","day_p5","day_p6","day_p7",
)
private val MENSTRUAL_LABELS = listOf(
    "-7","-6","-5","-4","-3","-2","-1","0","+1","+2","+3","+4","+5","+6","+7",
)

private fun parseMenstrualDays(json: String): List<Double> {
    return try {
        val obj = org.json.JSONObject(json)
        MENSTRUAL_KEYS.map { obj.optDouble(it, 0.0) }
    } catch (_: Exception) { emptyList() }
}

@Composable
private fun MenstrualDecayComparison(fromJson: String, toJson: String, accepted: Boolean) {
    val fromVals = parseMenstrualDays(fromJson)
    val toVals = parseMenstrualDays(toJson)
    if (toVals.isEmpty()) return

    val scroll = rememberScrollState()
    Column(modifier = Modifier.horizontalScroll(scroll)) {
        Row {
            Text(t("Day"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(32.dp))
            MENSTRUAL_LABELS.forEach { lbl ->
                Text(lbl, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(28.dp))
            }
        }
        Row {
            Text(t("Was"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(32.dp))
            fromVals.forEach { v ->
                Text(formatDecayVal(v), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(28.dp))
            }
        }
        Row {
            Text(t("New"), color = Color(0xFF81C784), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), modifier = Modifier.width(32.dp))
            toVals.forEachIndexed { i, newV ->
                val oldV = fromVals.getOrNull(i)
                val effective = if (accepted) newV else (oldV ?: newV)
                val changed = accepted && (oldV == null || oldV != newV)
                Text(
                    formatDecayVal(effective),
                    color = if (changed) Color(0xFFFFB74D) else AppTheme.SubtleTextColor,
                    style = if (changed) MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(28.dp),
                )
            }
        }
    }
}

@Composable
private fun WarningRow(proposal: RecalibrationViewModel.Proposal) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1A1A24))
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
            tint = Color(0xFFFFB74D),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(t(proposal.label),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            if (!proposal.reasoning.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    proposal.reasoning,
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
