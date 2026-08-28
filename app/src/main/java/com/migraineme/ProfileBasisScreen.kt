package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Profile → "What this is based on".
 *
 * Every answer the user gave in the AI-setup questionnaire, section by
 * section in the order they answered it, each section with its own edit
 * button (opens that one page of the setup flow, pre-filled, see
 * [AiSetupScreen] edit mode). Above the answers: the setup-day summary, the
 * clinical assessment (which weekly check-ins DO rewrite), and what has
 * changed since setup — the answers themselves never move on their own, only
 * an edit or a redo rewrites them, and the page says so.
 *
 * Reads the same ai_setup_profiles row the Profile card already fetches, in
 * either platform's key spelling, plus the live tables the weekly check-ins
 * write to (user_triggers / user_prodromes / risk_gauge_thresholds /
 * recalibration_proposals) so setup-day and today can be shown side by side.
 */

/** One-shot notice for this screen, set by the edit flow before navigating back. */
object ProfileBasisNotice {
    var message: String? = null
}

private data class BasisData(
    val row: JsonObject?,
    val answers: JsonObject?,
    val acceptedProposals: List<JsonObject>,
    val runCount: Int,
    val liveTriggers: Map<String, String>,   // label → prediction_value
    val liveProdromes: Map<String, String>,
    val thresholds: Map<String, Double>,      // zone → min_value
    val enabledMetrics: Int,
    val totalMetrics: Int,
    val sources: Set<String>,
)

@Composable
fun ProfileBasisScreen(
    onEditSection: (entryKey: String) -> Unit,
    onRedoSetup: () -> Unit,
    onReviewProposals: () -> Unit,
) {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<BasisData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf(ProfileBasisNotice.message.also { ProfileBasisNotice.message = null }) }

    LaunchedEffect(Unit) {
        loading = true
        data = withContext(Dispatchers.IO) { loadBasis(ctx) }
        loading = false
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        notice?.let { msg ->
            BaseCard {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(18.dp))
                    Text(msg, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { notice = null }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Outlined.Close, t("Dismiss"), tint = AppTheme.SubtleTextColor, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        val d = data
        when {
            loading -> BaseCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = AppTheme.AccentPurple, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(t("Loading your answers…"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
            }
            d == null || d.answers == null -> NotAnsweredYet(d?.row, onRedoSetup)
            else -> BasisContent(d, onEditSection, onRedoSetup, onReviewProposals)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Empty state — a row can exist with no answers (created by an accepted
// weekly proposal), or not exist at all. Either way: say so, offer the way in.
// ═══════════════════════════════════════════════════════════════════════
@Composable
private fun NotAnsweredYet(row: JsonObject?, onRedoSetup: () -> Unit) {
    HeroCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionGlyph(Icons.Outlined.Psychology)
            Column {
                Text(t("Not answered yet"), color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                Text(t("You have not been through the setup questions"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            t("The AI learns from what you log either way. Answering the setup questions gives it your own account of your migraines to start from — sleep, stress, food, weather, hormones and warning signs — so its first read of you is closer than a blank page."),
            color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall
        )
        val ca = row?.str("clinical_assessment")
        if (!ca.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            AssessmentBlock(ca, row.str("updated_at"))
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRedoSetup, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink), shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(t("Answer the setup questions"))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Main content
// ═══════════════════════════════════════════════════════════════════════
@Composable
private fun BasisContent(
    d: BasisData,
    onEditSection: (String) -> Unit,
    onRedoSetup: () -> Unit,
    onReviewProposals: () -> Unit,
) {
    val row = d.row!!
    val a = d.answers!!
    val setupDate = fmtDate(row.str("created_at"))
    val updatedDate = fmtDate(row.str("updated_at"))

    // ── Summary + assessment ──
    HeroCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionGlyph(Icons.Outlined.AutoAwesome)
            Column {
                Text(t("Your summary"), color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                Text(t("Written from everything below"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            }
        }
        val summary = row.str("summary")
        if (!summary.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().background(AppTheme.AccentPurple.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = AppTheme.AccentPink, modifier = Modifier.size(16.dp))
                Text(summary, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(t("Written at setup"), frozen = true)
                Text(if (setupDate != null) t("%1\$s. It does not rewrite itself.", setupDate) else t("It does not rewrite itself."), color = AppTheme.SubtleTextColor.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }
        }
        val ca = row.str("clinical_assessment")
        if (!ca.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            AssessmentBlock(ca, row.str("updated_at"))
        }
    }

    // ── What has changed since ──
    ChangesCard(d, onReviewProposals)

    // ── Divider: the answers ──
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
        Text(t("What you told us").uppercase(), color = AppTheme.SubtleTextColor.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        HorizontalDivider(Modifier.weight(1f), color = Color.White.copy(alpha = 0.12f))
    }
    Text(
        if (setupDate != null) t("Your answers from %1\$s. Weekly check-ins never touch them — only you do, with the edit button on each section, or by redoing setup.", setupDate)
        else t("Your answers. Weekly check-ins never touch them — only you do, with the edit button on each section, or by redoing setup."),
        color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp)
    )

    // ── 1. About you ──
    AnswerSection(Icons.Outlined.Person, t("About you"), 1, AiSetupEntry.ABOUT, onEditSection, listOf(
        AnswerRow(t("Gender"), a.pick("gender", "gender")),
        AnswerRow(t("Age"), a.pick("age_range", "ageRange")),
        AnswerRow(t("How often you get them"), a.pick("frequency", "frequency")),
        AnswerRow(t("How long they last"), a.pick("duration", "duration")),
        AnswerRow(t("Living with migraine"), a.pick("experience", "experience")),
        AnswerRow(t("Where it is heading"), a.pick("trajectory", "trajectory")),
        AnswerRow(t("Warning signs first"), a.pick("warning_signs_before", "warningBefore")),
        AnswerRow(t("Trigger to attack"), a.pick("trigger_delay", "triggerDelay")),
        AnswerRow(t("Daily routine"), a.pick("daily_routine", "dailyRoutine")),
        AnswerRow(t("Seasonal pattern"), a.pick("seasonal_pattern", "seasonalPattern")),
    ))

    // ── 2. Sleep ──
    AnswerSection(Icons.Outlined.Bedtime, t("Sleep"), 2, AiSetupEntry.SLEEP, onEditSection, listOf(
        AnswerRow(t("Hours a night"), a.pick("sleep_hours", "sleepHours")),
        AnswerRow(t("Quality"), a.pick("sleep_quality", "sleepQuality")),
        AnswerRow(t("Bad sleep sets one off"), a.pickCert("poor_sleep_quality_triggers", "poorQualityTriggers")),
        AnswerRow(t("Too little sleep"), a.pickCert("too_little_sleep_triggers", "tooLittleSleepTriggers")),
        AnswerRow(t("Sleeping in"), a.pickCert("oversleep_triggers", "oversleepTriggers")),
        AnswerRow(t("Sleep problems"), a.pickSet("sleep_issues", "sleepIssues")),
    ))

    // ── 3. Stress & screens ──
    AnswerSection(Icons.Outlined.Psychology, t("Stress & screens"), 3, AiSetupEntry.STRESS, onEditSection, listOf(
        AnswerRow(t("Stress level"), a.pick("stress_level", "stressLevel")),
        AnswerRow(t("Stress changes set one off"), a.pickCert("stress_change_triggers", "stressChangeTriggers")),
        AnswerRow(t("Emotional patterns"), a.pickCertMap("emotional_patterns", "emotionalPatterns")),
        AnswerRow(t("Screen time a day"), a.pick("screen_time_daily", "screenTimeDaily")),
        AnswerRow(t("Screens set one off"), a.pickCert("screen_time_triggers", "screenTimeTriggers")),
        AnswerRow(t("Screens late at night"), a.pickCert("late_screen_triggers", "lateScreenTriggers")),
    ))

    // ── 4. Food & drink ──
    AnswerSection(Icons.Outlined.Restaurant, t("Food & drink"), 4, AiSetupEntry.DIET, onEditSection, listOf(
        AnswerRow(t("Caffeine"), a.pick("caffeine_intake", "caffeineIntake")),
        AnswerRow(t("Caffeine pattern"), a.pick("caffeine_direction", "caffeineDirection")),
        AnswerRow(t("Caffeine sets one off"), a.pickCert("caffeine_certainty", "caffeineCertainty")),
        AnswerRow(t("Alcohol"), a.pick("alcohol_frequency", "alcoholFrequency")),
        AnswerRow(t("Alcohol sets one off"), a.pickCert("alcohol_triggers", "alcoholTriggers")),
        AnswerRow(t("Drinks that do it"), a.pickSet("specific_drinks", "specificDrinks")),
        AnswerRow(t("Aged foods"), a.pickCertMap("tyramine_foods", "tyramineFoods")),
        AnswerRow(t("Fermented foods"), a.pickCertMap("histamine_foods", "histamineFoods")),
        AnswerRow(t("Gluten"), a.pick("gluten_sensitivity", "glutenSensitivity")),
        AnswerRow(t("Gluten sets one off"), a.pickCert("gluten_triggers", "glutenTriggers")),
        AnswerRow(t("Eating patterns"), a.pickCertMap("eating_patterns", "eatingPatterns")),
        AnswerRow(t("Water a day"), a.pick("water_intake", "waterIntake")),
        AnswerRow(t("Tracks nutrition"), a.pick("tracks_nutrition", "tracksNutrition")),
    ))

    // ── 5. Weather & surroundings ──
    AnswerSection(Icons.Outlined.Cloud, t("Weather & surroundings"), 5, AiSetupEntry.WEATHER, onEditSection, listOf(
        AnswerRow(t("Weather sets one off"), a.pickCert("weather_triggers", "weatherTriggers")),
        AnswerRow(t("Which weather"), a.pickCertMap("specific_weather", "specificWeather")),
        AnswerRow(t("Surroundings"), a.pickCertMap("environment_sensitivities", "environmentSensitivities")),
        AnswerRow(t("Physical"), a.pickCertMap("physical_factors", "physicalFactors")),
    ))

    // ── 6. Exercise & cycle ──
    val tracksCycle = a.pick("tracks_cycle", "tracksCycle")
    AnswerSection(Icons.Outlined.FitnessCenter, t("Exercise & cycle"), 6, AiSetupEntry.EXERCISE, onEditSection, buildList {
        add(AnswerRow(t("Exercise"), a.pick("exercise_frequency", "exerciseFrequency")))
        add(AnswerRow(t("Exercise sets one off"), a.pickCert("exercise_triggers", "exerciseTriggers")))
        add(AnswerRow(t("How you train"), a.pickSet("exercise_pattern", "exercisePattern")))
        add(AnswerRow(t("Tracking your cycle"), tracksCycle))
        if (tracksCycle?.value == AiSetupOptions.TRACKS_CYCLE_YES) {
            add(AnswerRow(t("Cycle patterns"), a.pickCertMap("cycle_patterns", "cyclePatterns")))
            add(AnswerRow(t("Cycle length"), a.pick("cycle_length", "cycleLength")))
            add(AnswerRow(t("Last period"), a.pick("last_period_date", "lastPeriodDate")))
            add(AnswerRow(t("When in the cycle"), a.pickSet("cycle_migraine_timing", "cycleMigraineTiming")))
            add(AnswerRow(t("Contraception"), a.pick("uses_contraception", "usesContraception")))
            add(AnswerRow(t("Effect on migraines"), a.pick("contraception_effect", "contraceptionEffect")))
        }
    })

    // ── 7. Warning signs ──
    AnswerSection(Icons.Outlined.Sensors, t("Warning signs you get"), 7, AiSetupEntry.WARNING_SIGNS, onEditSection, listOf(
        AnswerRow(t("In your body"), a.pickCertMap("physical_prodromes", "physicalProdromes")),
        AnswerRow(t("In your mood"), a.pickCertMap("mood_prodromes", "moodProdromes")),
        AnswerRow(t("In your senses"), a.pickCertMap("sensory_prodromes", "sensoryProdromes")),
    ))

    // ── 8. Lists — one row per pool, each with its own edit target ──
    BaseCard {
        SectionHeader(Icons.Outlined.Checklist, t("What you added to your lists"), t("Page 8 of setup"), null)
        Spacer(Modifier.height(4.dp))
        ListRow(t("Triggers"), a.pickSet("selected_triggers", "selectedTriggers"), AiSetupEntry.TRIGGERS, onEditSection)
        ListRow(t("Warning signs"), a.pickSet("selected_prodromes", "selectedProdromes"), AiSetupEntry.PRODROMES, onEditSection)
        ListRow(t("Symptoms"), a.pickSet("selected_symptoms", "selectedSymptoms"), AiSetupEntry.SYMPTOMS, onEditSection)
        ListRow(t("After-effects"), a.pickSet("selected_postdromes", "selectedPostdromes"), AiSetupEntry.POSTDROMES, onEditSection)
        ListRow(t("Places"), a.pickSet("selected_locations", "selectedLocations"), AiSetupEntry.LOCATIONS, onEditSection)
        ListRow(t("Medicines"), a.pickSet("selected_medicines", "selectedMedicines"), AiSetupEntry.MEDICINES, onEditSection)
        ListRow(t("Reliefs"), a.pickSet("selected_reliefs", "selectedReliefs"), AiSetupEntry.RELIEFS, onEditSection)
        ListRow(t("Activities"), a.pickSet("selected_activities", "selectedActivities"), AiSetupEntry.ACTIVITIES, onEditSection)
        ListRow(t("Activities you miss"), a.pickSet("selected_missed_activities", "selectedMissedActivities"), AiSetupEntry.MISSED_ACTIVITIES, onEditSection)
    }

    // ── Notes ──
    val freeText = AiSetupProfileStore.freeText(a)
    BaseCard {
        SectionHeader(Icons.Outlined.FormatQuote, t("Your notes"), t("What you typed in your own words"), null, onEdit = { onEditSection(AiSetupEntry.NOTES) })
        Spacer(Modifier.height(6.dp))
        if (freeText.isNullOrBlank()) {
            Text(t("You skipped this"), color = AppTheme.SubtleTextColor.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic))
        } else {
            Text("“$freeText”", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().background(AppTheme.TrackColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).padding(12.dp))
        }
    }

    // ── What the AI made of it ──
    MadeOfItCard(d)

    // ── Data it could see ──
    BaseCard {
        SectionHeader(Icons.Outlined.Watch, t("Data it can see"), t("Connected right now"), null)
        Spacer(Modifier.height(4.dp))
        val names = d.sources.map { src -> WearableSource.entries.firstOrNull { it.key == src }?.label ?: src }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(t("Wearables and health apps"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
        }
        if (names.isEmpty()) {
            Text(t("Nothing connected"), color = AppTheme.SubtleTextColor.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic))
        } else {
            PillRow(names.map { it to PillTone.ON })
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(t("Measurements switched on"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            Text(t("%1\$s of %2\$s", d.enabledMetrics, d.totalMetrics), color = Color.White, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
        }
    }

    Text(
        t("These answers are the starting point. What the app actually does each day comes from Manage Items and Risk Model, and the weekly check-in nudges those as it learns from what you log. Editing a section here re-runs the setup for that page and asks the AI to re-check your profile straight away."),
        color = AppTheme.SubtleTextColor.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 4.dp)
    )

    // ── Redo — same card, same action as on Profile ──
    BaseCard {
        Row(Modifier.fillMaxWidth().clickable { onRedoSetup() }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.RestartAlt, null, tint = Color(0xFF4FC3F7), modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(t("Redo Onboarding"), color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(t("Answer everything again and recalibrate your profile"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = AppTheme.SubtleTextColor, modifier = Modifier.size(16.dp))
        }
    }
    Spacer(Modifier.height(8.dp))
    @Suppress("UNUSED_VARIABLE") val unused = updatedDate
}

// ═══════════════════════════════════════════════════════════════════════
// Cards
// ═══════════════════════════════════════════════════════════════════════
@Composable
private fun AssessmentBlock(text: String, updatedAt: String?) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1A1A2E).copy(alpha = 0.9f))
            .border(1.dp, AppTheme.AccentPink.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }.padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.MedicalInformation, null, tint = AppTheme.AccentPink, modifier = Modifier.size(16.dp))
            Text(t("MigraineMe Clinical Assessment"), color = Color.White, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = AppTheme.SubtleTextColor, modifier = Modifier.size(16.dp))
        }
        if (expanded) {
            Text(text, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
            Tag(t("Kept up to date"), frozen = false)
            val d = fmtDate(updatedAt)
            Text(if (d != null) t("Last rewritten %1\$s by a weekly check-in", d) else t("Rewritten by weekly check-ins"),
                color = AppTheme.SubtleTextColor.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ChangesCard(d: BasisData, onReviewProposals: () -> Unit) {
    val accepted = d.acceptedProposals
    BaseCard {
        SectionHeader(
            Icons.Outlined.History, t("What has changed since"),
            if (d.runCount > 0) t("%1\$s check-ins so far", d.runCount) else t("No check-ins yet"),
            accepted.size.takeIf { it > 0 }?.toString()
        )
        Spacer(Modifier.height(4.dp))
        if (accepted.isEmpty()) {
            Text(t("Nothing yet. Every change the weekly check-in proposes shows up here once you accept it."),
                color = AppTheme.SubtleTextColor.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic))
        } else {
            accepted.take(8).forEach { p -> ChangeRow(p) }
            if (accepted.size > 8) {
                Text(t("and %1\$s more", accepted.size - 8), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
            }
            val latest = fmtDate(accepted.firstOrNull()?.str("reviewed_at") ?: accepted.firstOrNull()?.str("created_at"))
            Text(
                (if (latest != null) t("Latest %1\$s. ", latest) else "") + t("Everything here is a change you accepted — nothing moves without you."),
                color = AppTheme.SubtleTextColor.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp)
            )
        }
        TextButton(onClick = onReviewProposals, contentPadding = PaddingValues(0.dp)) {
            Text(t("Open the review screen"), color = AppTheme.AccentPurple, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ChangeRow(p: JsonObject) {
    val type = p.str("type") ?: ""
    val label = p.str("label") ?: ""
    val from = p.str("from_value")
    val to = p.str("to_value")
    val (title, fromShown, toShown) = when (type) {
        "trigger", "prodrome" -> Triple(t(label), from?.let { sevWord(it) }, to?.let { sevWord(it) })
        "gauge_threshold" -> Triple(t("%1\$s starts at", sevWord(label)), from, to)
        "gauge_decay" -> Triple(t("%1\$s day curve", sevWord(label)), null, t("changed"))
        "menstruation_decay" -> Triple(t("Cycle curve"), null, t("changed"))
        "clinical_assessment" -> Triple(t("Your assessment"), null, t("rewritten"))
        "medicine", "relief", "symptom", "activity", "missed_activity" ->
            Triple(t(label), null, if (to == "favorite") t("quick-log") else t("removed from quick-log"))
        else -> Triple(t(label), from, to)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (fromShown != null) {
                Text(fromShown, color = AppTheme.SubtleTextColor.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough))
                Text("→", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            }
            if (toShown != null) Pill(toShown, toneForSeverity(to))
        }
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
}

@Composable
private fun MadeOfItCard(d: BasisData) {
    val row = d.row!!
    val areas = (row["trigger_areas"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    val setupTriggers = (row["ai_config"] as? JsonObject)?.get("triggers") as? JsonArray
    val setupSeverity: Map<String, String> = setupTriggers?.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val l = o.str("label") ?: return@mapNotNull null
        l.lowercase() to (o.str("severity") ?: "NONE").uppercase()
    }?.toMap() ?: emptyMap()
    val high = d.liveTriggers.filterValues { it == "HIGH" }.keys.sorted()
    val mild = d.liveTriggers.filterValues { it == "MILD" }.keys.sorted()
    fun moved(label: String, now: String) = setupSeverity[label.lowercase()]?.let { it != now } ?: false
    val setBy = setupSeverity.values.count { it != "NONE" }

    BaseCard {
        SectionHeader(Icons.Outlined.Insights, t("What the AI made of it"), t("Worked out for you, not answered"), null)
        Spacer(Modifier.height(4.dp))
        if (areas.isNotEmpty()) {
            LabelWithTag(t("Your trigger areas"), t("at setup"), frozen = true)
            PillRow(areas.map { t(it) to PillTone.PLAIN })
            Spacer(Modifier.height(8.dp))
        }
        LabelWithTag(t("Watched closely"), t("now"), frozen = false)
        if (high.isEmpty()) NoneText() else PillRow(high.map { t(it) to (if (moved(it, "HIGH")) PillTone.DEF_MOVED else PillTone.DEF) })
        Spacer(Modifier.height(8.dp))
        LabelWithTag(t("Watched loosely"), t("now"), frozen = false)
        if (mild.isEmpty()) NoneText() else PillRow(mild.map { t(it) to (if (moved(it, "MILD")) PillTone.MAYBE_MOVED else PillTone.MAYBE) })
        if (setBy > 0) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(t("Set at setup, from your answers"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                Text(setBy.toString(), color = Color.White, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
            }
        }
        if (d.thresholds.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(t("Gauge starts"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                Text(
                    listOf("LOW", "MILD", "HIGH").mapNotNull { z -> d.thresholds[z]?.let { "${sevWord(z)} ${it.toInt()}" } }.joinToString(" / "),
                    color = Color.White, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                )
            }
        }
        Text(t("Outlined = moved since setup. Change any of these in Manage Items."),
            color = AppTheme.SubtleTextColor.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Answer sections
// ═══════════════════════════════════════════════════════════════════════
private class Shown(val value: String?, val pills: List<Pair<String, PillTone>>) {
    val isEmpty get() = value.isNullOrBlank() && pills.isEmpty()
}
private data class AnswerRow(val label: String, val shown: Shown?)

@Composable
private fun AnswerSection(
    icon: ImageVector, title: String, page: Int, entryKey: String,
    onEditSection: (String) -> Unit, rows: List<AnswerRow>,
) {
    val answered = rows.count { it.shown != null && !it.shown.isEmpty }
    BaseCard {
        SectionHeader(icon, title, t("Page %1\$s of setup", page), "$answered / ${rows.size}", warn = answered < rows.size, onEdit = { onEditSection(entryKey) })
        Spacer(Modifier.height(4.dp))
        rows.forEachIndexed { i, r ->
            val s = r.shown
            if (s == null || s.isEmpty) {
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(r.label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    Text(t("You skipped this"), color = AppTheme.SubtleTextColor.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic))
                }
            } else if (s.pills.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(r.label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    PillRow(s.pills)
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                    Text(r.label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(s.value!!, color = Color.White, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
            if (i < rows.lastIndex) HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
        }
    }
}

@Composable
private fun ListRow(label: String, shown: Shown?, entryKey: String, onEditSection: (String) -> Unit) {
    val n = shown?.pills?.size ?: 0
    Row(Modifier.fillMaxWidth().clickable { onEditSection(entryKey) }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(if (n == 0) t("none") else t("%1\$s added", n), color = if (n == 0) AppTheme.SubtleTextColor.copy(alpha = 0.55f) else Color.White,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (n == 0) FontWeight.Normal else FontWeight.Medium, fontStyle = if (n == 0) FontStyle.Italic else FontStyle.Normal))
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Outlined.Edit, t("Edit"), tint = AppTheme.AccentPurple, modifier = Modifier.size(14.dp))
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
}

// ═══════════════════════════════════════════════════════════════════════
// Small pieces
// ═══════════════════════════════════════════════════════════════════════
@Composable
private fun SectionGlyph(icon: ImageVector) {
    Box(
        Modifier.size(34.dp).background(Brush.linearGradient(listOf(AppTheme.AccentPink.copy(alpha = 0.3f), AppTheme.AccentPurple.copy(alpha = 0.2f))), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String, subtitle: String, count: String?, warn: Boolean = false, onEdit: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionGlyph(icon)
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            Text(subtitle, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
        }
        if (count != null) {
            Text(count, color = if (warn) Color(0xFFFFC06B) else AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.background(if (warn) Color(0xFFFFC06B).copy(alpha = 0.12f) else Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp)).padding(horizontal = 9.dp, vertical = 3.dp))
        }
        if (onEdit != null) {
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Edit, t("Edit"), tint = AppTheme.AccentPurple, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private enum class PillTone { DEF, MAYBE, PLAIN, ON, OFF, DEF_MOVED, MAYBE_MOVED }

@Composable
private fun Pill(text: String, tone: PillTone) {
    val (bg, fg, border) = when (tone) {
        PillTone.DEF -> Triple(AppTheme.AccentPink.copy(alpha = 0.18f), Color(0xFFFFB6D2), AppTheme.AccentPink.copy(alpha = 0.3f))
        PillTone.DEF_MOVED -> Triple(AppTheme.AccentPink.copy(alpha = 0.18f), Color(0xFFFFB6D2), Color(0xFF7BE0A8))
        PillTone.MAYBE -> Triple(AppTheme.AccentPurple.copy(alpha = 0.14f), Color(0xFFD3B4FF), AppTheme.AccentPurple.copy(alpha = 0.24f))
        PillTone.MAYBE_MOVED -> Triple(AppTheme.AccentPurple.copy(alpha = 0.14f), Color(0xFFD3B4FF), Color(0xFF7BE0A8))
        PillTone.PLAIN -> Triple(Color.White.copy(alpha = 0.09f), AppTheme.BodyTextColor, Color.Transparent)
        PillTone.ON -> Triple(Color(0xFF7BE0A8).copy(alpha = 0.14f), Color(0xFF7BE0A8), Color(0xFF7BE0A8).copy(alpha = 0.26f))
        PillTone.OFF -> Triple(Color.White.copy(alpha = 0.06f), AppTheme.SubtleTextColor.copy(alpha = 0.6f), Color.Transparent)
    }
    Text(text, color = fg, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.background(bg, RoundedCornerShape(7.dp)).border(1.dp, border, RoundedCornerShape(7.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PillRow(pills: List<Pair<String, PillTone>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        pills.forEach { (text, tone) -> Pill(text, tone) }
    }
}

@Composable
private fun Tag(text: String, frozen: Boolean) {
    Text(text.uppercase(), color = if (frozen) AppTheme.SubtleTextColor.copy(alpha = 0.7f) else Color(0xFF7BE0A8),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = androidx.compose.ui.unit.TextUnit(0.06f, androidx.compose.ui.unit.TextUnitType.Em)),
        modifier = Modifier.background(if (frozen) Color.White.copy(alpha = 0.10f) else Color(0xFF7BE0A8).copy(alpha = 0.15f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
}

@Composable
private fun LabelWithTag(label: String, tag: String, frozen: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 5.dp)) {
        Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
        Tag(tag, frozen)
    }
}

@Composable
private fun NoneText() {
    Text(t("none"), color = AppTheme.SubtleTextColor.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic))
}

// ═══════════════════════════════════════════════════════════════════════
// Answer readers — both key spellings, see AiSetupProfileStore.preFillFromAnswers
// ═══════════════════════════════════════════════════════════════════════
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

private fun JsonObject.el(snake: String, camel: String): JsonElement? = (this[snake] ?: this[camel])?.takeIf { it !is JsonNull }

private fun JsonObject.pick(snake: String, camel: String): Shown? {
    val v = (el(snake, camel) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" } ?: return null
    return Shown(Strings.tSync(v), emptyList())
}

private fun certOf(raw: String?): DeterministicMapper.Certainty? {
    val v = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { DeterministicMapper.Certainty.valueOf(v.uppercase()) }.getOrNull() ?: CertaintyWords.fromLocalisedWord(v)
}

private fun certWord(c: DeterministicMapper.Certainty): String = when (c) {
    DeterministicMapper.Certainty.EVERY_TIME -> Strings.tSync("Every time")
    DeterministicMapper.Certainty.OFTEN -> Strings.tSync("Often")
    DeterministicMapper.Certainty.SOMETIMES -> Strings.tSync("Sometimes")
    DeterministicMapper.Certainty.RARELY -> Strings.tSync("Rarely")
    DeterministicMapper.Certainty.NO -> Strings.tSync("No")
}

private fun certTone(c: DeterministicMapper.Certainty): PillTone = when (c) {
    DeterministicMapper.Certainty.EVERY_TIME, DeterministicMapper.Certainty.OFTEN -> PillTone.DEF
    DeterministicMapper.Certainty.SOMETIMES, DeterministicMapper.Certainty.RARELY -> PillTone.MAYBE
    DeterministicMapper.Certainty.NO -> PillTone.OFF
}

private fun JsonObject.pickCert(snake: String, camel: String): Shown? {
    val c = certOf((el(snake, camel) as? JsonPrimitive)?.contentOrNull) ?: return null
    return Shown(null, listOf(certWord(c) to certTone(c)))
}

private fun JsonObject.pickSet(snake: String, camel: String): Shown? {
    val arr = el(snake, camel) as? JsonArray ?: return null
    val items = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
    if (items.isEmpty()) return null
    return Shown(null, items.map { Strings.tSync(it) to PillTone.PLAIN })
}

private fun JsonObject.pickCertMap(snake: String, camel: String): Shown? {
    val obj = el(snake, camel) as? JsonObject ?: return null
    val items = obj.entries.mapNotNull { (k, v) -> certOf((v as? JsonPrimitive)?.contentOrNull)?.takeIf { it != DeterministicMapper.Certainty.NO }?.let { k to it } }
    if (items.isEmpty()) return null
    return Shown(null, items.map { (k, c) -> Strings.tSync(k) to certTone(c) })
}

private fun sevWord(v: String): String = when (v.uppercase()) {
    "HIGH" -> Strings.tSync("Definitely"); "MILD" -> Strings.tSync("Maybe"); "LOW" -> Strings.tSync("A little"); "NONE" -> Strings.tSync("Off"); else -> v
}
private fun toneForSeverity(v: String?): PillTone = when (v?.uppercase()) {
    "HIGH" -> PillTone.DEF; "MILD" -> PillTone.MAYBE; "LOW" -> PillTone.MAYBE; "NONE" -> PillTone.OFF; else -> PillTone.PLAIN
}

private fun fmtDate(iso: String?): String? = try {
    iso?.let { Instant.parse(it.replace(" ", "T").let { s -> if (s.endsWith("Z") || s.contains("+")) s else "${s}Z" }).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("d MMMM yyyy")) }
} catch (_: Exception) { null }

// ═══════════════════════════════════════════════════════════════════════
// Loading — one GET per table, all in parallel
// ═══════════════════════════════════════════════════════════════════════
private suspend fun loadBasis(ctx: android.content.Context): BasisData? {
    val appCtx = ctx.applicationContext
    val token = SessionStore.getValidAccessToken(appCtx) ?: return null
    val userId = SessionStore.readUserId(appCtx) ?: JwtUtils.extractUserIdFromAccessToken(token) ?: return null
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val client = okhttp3.OkHttpClient()
    val json = Json { ignoreUnknownKeys = true }

    fun get(path: String): JsonArray = try {
        val req = okhttp3.Request.Builder().url("$base/rest/v1/$path").get()
            .header("Authorization", "Bearer $token").header("apikey", BuildConfig.SUPABASE_ANON_KEY).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) JsonArray(emptyList())
            else (json.parseToJsonElement(r.body?.string() ?: "[]") as? JsonArray) ?: JsonArray(emptyList())
        }
    } catch (e: Exception) {
        android.util.Log.w("ProfileBasis", "GET $path failed: ${e.message}"); JsonArray(emptyList())
    }

    return coroutineScope {
        val profile = async { get("ai_setup_profiles?user_id=eq.$userId&select=*&limit=1") }
        val accepted = async { get("recalibration_proposals?user_id=eq.$userId&status=eq.accepted&select=type,label,from_value,to_value,reviewed_at,created_at&order=reviewed_at.desc.nullslast,created_at.desc") }
        val history = async { get("recalibration_history?user_id=eq.$userId&select=id") }
        val triggers = async { get("user_triggers?user_id=eq.$userId&select=label,prediction_value") }
        val prodromes = async { get("user_prodromes?user_id=eq.$userId&select=label,prediction_value") }
        val thresholds = async { get("risk_gauge_thresholds?user_id=eq.$userId&select=zone,min_value") }
        val metrics = async { get("metric_settings?user_id=eq.$userId&select=metric,enabled,preferred_source") }

        val row = profile.await().firstOrNull() as? JsonObject
        fun sevMap(arr: JsonArray): Map<String, String> = arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val l = o.str("label") ?: return@mapNotNull null
            l to (o.str("prediction_value") ?: "NONE").uppercase()
        }.toMap()
        val ms = metrics.await().mapNotNull { it as? JsonObject }
        BasisData(
            row = row,
            answers = row?.get("answers") as? JsonObject,
            acceptedProposals = accepted.await().mapNotNull { it as? JsonObject },
            runCount = history.await().size,
            liveTriggers = sevMap(triggers.await()),
            liveProdromes = sevMap(prodromes.await()),
            thresholds = thresholds.await().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val z = o.str("zone")?.uppercase() ?: return@mapNotNull null
                z to ((o["min_value"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null)
            }.toMap(),
            enabledMetrics = ms.count { (it["enabled"] as? JsonPrimitive)?.booleanOrNull == true },
            totalMetrics = ms.size,
            sources = ms.filter { (it["enabled"] as? JsonPrimitive)?.booleanOrNull == true }
                .mapNotNull { it.str("preferred_source") }.filter { it != "demo" }.toSet(),
        )
    }
}
