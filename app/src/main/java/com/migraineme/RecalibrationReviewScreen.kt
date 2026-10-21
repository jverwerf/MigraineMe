package com.migraineme

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
                        if (state.proposals.isEmpty()) t("Your goals have been updated.")
                        else t("Your trigger settings, gauge thresholds, and favorites have been updated."),
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

            // ── No proposals (and no goal ideas) ──
            if (state.proposals.isEmpty() && state.goalIdeas.isEmpty()) {
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

            // ── Header: same build as the Home nav cards and the Goals review
            // (blob icon, title, one line), with the two bulk actions as the
            // app's pill buttons. Static, no motion. ──
            BaseCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrainyBlobIcon(resId = R.drawable.brainy_gardener_small)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            t("Your recalibration"),
                            color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                        Text(
                            t("Tick what you want to keep, then apply."),
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GoalPillButton(label = t("Accept all"), enabled = true) { vm.acceptAll() }
                    GoalPillButton(label = t("Reject all"), enabled = true) { vm.rejectAll() }
                }
            }

            // ── What we found: ONE card, the explanations behind buttons (Jordy
            // 10-21: "all in one card with clickable buttons"). The buttons are the
            // AI narrative's own sections, plus the gauge notes, the cycle notes and
            // this week's goal review when there is one. ──
            val goalReport by PractitionerGoalsStore.report.collectAsState()
            LaunchedEffect(state.goalIdeas.isNotEmpty()) {
                if (MonitorCardConfig.GOALS_ENABLED && state.goalIdeas.isNotEmpty()) PractitionerGoalsStore.refreshReview(ctx)
            }
            val tabs = remember(state.clinicalAssessment, state.calibrationNotes, state.menstrualNotes, goalReport, state.goalIdeas.size) {
                buildList {
                    for ((title, body) in narrativeSections(state.clinicalAssessment)) {
                        add(ExplanationTab(chipLabelFor(title), body))
                    }
                    if (state.calibrationNotes.isNotBlank()) add(ExplanationTab("Gauge", state.calibrationNotes))
                    if (state.menstrualNotes.isNotBlank()) add(ExplanationTab("Cycle", state.menstrualNotes))
                    val review = goalReport?.report?.trim().orEmpty()
                    if (state.goalIdeas.isNotEmpty() && review.isNotBlank()) add(ExplanationTab("Goals", review))
                }
            }
            if (tabs.isNotEmpty()) ExplanationCard(tabs)

            // ── The line between reading and deciding ──
            SectionDividerLabel(t("Tick what to keep"))

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
                "ovulation_decay" to "Ovulation decay",
                "data_warning" to "Data warnings",
            )

            for ((type, sectionTitle) in displayOrder) {
                val proposals = grouped[type] ?: continue

                BaseCard {
                    Text(
                        t(sectionTitle),
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

            // ── New goals (goal ideas from this run; empty unless Goals is on) ──
            if (state.goalIdeas.isNotEmpty()) {
                BaseCard {
                    Text(
                        t("New goals"),
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        t("Goal ideas from your own data."),
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    for ((i, idea) in state.goalIdeas.withIndex()) {
                        GoalIdeaRow(idea = idea, onToggle = { vm.toggleGoalIdea(idea.row.id) })
                        if (i < state.goalIdeas.lastIndex) {
                            Divider(
                                color = AppTheme.SubtleTextColor.copy(alpha = 0.2f),
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        }
                    }
                }
            }

            // ── Apply Button ──
            val acceptedCount = state.proposals.count { it.accepted } + state.goalIdeas.count { it.accepted }
            val rejectedCount = state.proposals.count { !it.accepted } + state.goalIdeas.count { !it.accepted }

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

/** One goal idea: same look and checkbox as a proposal row; title, target, reason. Static. */
@Composable
private fun GoalIdeaRow(
    idea: RecalibrationViewModel.GoalIdea,
    onToggle: () -> Unit,
) {
    val s = idea.row
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(proposalRowColor(idea.accepted))
            .clickable { onToggle() }
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = idea.accepted,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = AppTheme.AccentPurple,
                uncheckedColor = AppTheme.SubtleTextColor,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                t(s.title),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                goalAskText(s.asGoal()),
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodySmall,
            )
            if (s.reason.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    s.reason.trim(),
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ProposalRow(
    proposal: RecalibrationViewModel.Proposal,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(proposalRowColor(proposal.accepted))
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
                    proposal.type !in listOf("data_warning", "clinical_assessment", "gauge_decay", "menstruation_decay", "ovulation_decay")) {
                    Spacer(Modifier.width(8.dp))
                    if (proposal.type == "gauge_threshold") {
                        Text(t("Was "), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        Text(prettyValue(proposal.fromValue), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                        Text(" → ", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        Text(prettyValue(proposal.toValue), color = Color(0xFF81C784), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
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

            // Menstrual cycle / ovulation decay visualization (15 days m7..0..p7)
            if ((proposal.type == "menstruation_decay" || proposal.type == "ovulation_decay") && proposal.fromValue != null && proposal.toValue != null
                // The ovulation curve arrives with UPPERCASE day keys and an empty "was" ({}), so test the new value, any case
                && proposal.toValue.contains("day_", ignoreCase = true)) {
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
                Spacer(Modifier.height(4.dp))
                NarrativeText(proposal.toValue, collapsible = true, small = true)
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
    val bgColor = proposalRowColor(proposal.accepted)
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
                Text(p.fromValue?.let { prettyValue(it) } ?: "–", color = AppTheme.SubtleTextColor,
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
                Text(effectiveNew?.let { prettyValue(it) } ?: "–",
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
        // Keys come as day_m7 (menstrual) or DAY_M7 (ovulation): read them case-insensitively
        val byLower = obj.keys().asSequence().associate { it.lowercase() to obj.optDouble(it, 0.0) }
        MENSTRUAL_KEYS.map { byLower[it] ?: 0.0 }
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
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.04f))
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


/** Proposal values are stored as enum/level codes; show them in the user's language. */
@Composable
private fun prettyValue(v: String): String = when (v.uppercase()) {
    "FAVORITE" -> t("Favourite")
    "NOT_FAVORITE" -> t("Not a favourite")
    "HIGH" -> t("HIGH")
    "MILD" -> t("MILD")
    "LOW" -> t("LOW")
    "NONE" -> t("NONE")
    else -> v
}

/** Row surface: a wash of the accent when ticked, nearly nothing when not. Static. */
private fun proposalRowColor(accepted: Boolean): Color =
    if (accepted) AppTheme.AccentPurple.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.04f)

/**
 * The AI narratives arrive with "=== Migraine Profile ===" markers between
 * sections (the server parses them too, so they stay in the text). Shown as
 * headings over their paragraphs, the marker itself never on screen. With
 * [collapsible], only the first section shows until "Read more".
 */
@Composable
private fun NarrativeText(text: String, collapsible: Boolean = false, small: Boolean = false) {
    val sections = remember(text) { narrativeSections(text) }
    var expanded by remember(text) { mutableStateOf(!collapsible) }
    // A single long block (no markers) folds at its second sentence instead.
    val foldable = collapsible && (sections.size > 1 || (sections.firstOrNull()?.second?.length ?: 0) > 320)
    val shown = when {
        expanded || !foldable -> sections
        sections.size > 1 -> sections.take(1)
        else -> listOf(sections[0].first to firstSentences(sections[0].second, 2))
    }
    val bodyStyle = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        shown.forEachIndexed { i, (title, body) ->
            if (title != null) {
                if (i > 0) Spacer(Modifier.height(6.dp))
                Text(
                    t(title),
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            Text(body, color = AppTheme.BodyTextColor, style = bodyStyle)
        }
        if (foldable) {
            Text(
                if (expanded) t("Show less") else t("Read more"),
                color = AppTheme.AccentPurple,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.clickable { expanded = !expanded }.padding(top = 4.dp),
            )
        }
    }
}

private val NARRATIVE_HEADER = Regex("""^\s*=+\s*(.+?)\s*=+\s*$""")

/** Split "=== Title ===\nbody…" text into (title, body) pairs; text before the first marker has a null title. */
internal fun narrativeSections(text: String): List<Pair<String?, String>> {
    val out = mutableListOf<Pair<String?, String>>()
    var title: String? = null
    val body = StringBuilder()
    fun flush() {
        val b = body.toString().trim()
        if (b.isNotEmpty() || title != null) out += title to b
        body.clear()
    }
    for (line in text.lines()) {
        val m = NARRATIVE_HEADER.matchEntire(line)
        if (m != null) { flush(); title = m.groupValues[1].trim() } else body.appendLine(line)
    }
    flush()
    return out.filter { it.second.isNotEmpty() }
}

/** The first [n] sentences of a paragraph, for a folded preview. */
private fun firstSentences(text: String, n: Int): String {
    val m = Regex("""(.+?[.!?])(\s|$)""").findAll(text).take(n).toList()
    return if (m.isEmpty()) text else m.joinToString(" ") { it.groupValues[1] }
}

/** One button on the explanation card and the text it opens. [label] is an English key. */
private data class ExplanationTab(val label: String, val text: String)

/** The server's narrative headers as short button labels (English keys, translated at render). */
private fun chipLabelFor(header: String?): String = when (header?.trim()?.lowercase()) {
    null, "" -> "Profile"
    "migraine profile" -> "Profile"
    "trigger profile" -> "Triggers"
    "burden profile" -> "Burden"
    "coping profile" -> "Coping"
    else -> header.trim()
}

/**
 * "What we found" as one card: a row of buttons, one per explanation, and the
 * chosen explanation under them (folded, Read more). Static, no motion.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ExplanationCard(tabs: List<ExplanationTab>) {
    var selected by remember(tabs.size) { mutableStateOf(0) }
    val sel = tabs.getOrNull(selected) ?: tabs.first()
    BaseCard {
        Text(
            t("What we found"),
            color = AppTheme.TitleColor,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tabs.forEachIndexed { i, tab ->
                val on = i == selected
                Box(
                    modifier = Modifier
                        .background(
                            if (on) AppTheme.AccentPurple.copy(alpha = 0.45f) else AppTheme.AccentPurple.copy(alpha = 0.16f),
                            RoundedCornerShape(12.dp),
                        )
                        .border(1.dp, AppTheme.AccentPurple.copy(alpha = if (on) 0.9f else 0.40f), RoundedCornerShape(12.dp))
                        .clickable { selected = i }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        t(tab.label),
                        color = if (on) Color.White else AppTheme.AccentPurple,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        key(sel.label) { NarrativeText(sel.text, collapsible = true) }
    }
}

/** A rule with a label in it, between the explanations and the sections you tick. */
@Composable
private fun SectionDividerLabel(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = AppTheme.AccentPurple.copy(alpha = 0.45f))
        Text(
            label,
            color = AppTheme.AccentPurple,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = AppTheme.AccentPurple.copy(alpha = 0.45f))
    }
}
