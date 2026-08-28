package com.migraineme

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AiPage {
    STORY,
    Q1, Q2, Q3, Q4, Q5, Q6, Q7,
    TRIGGERS, PRODROMES,
    SYMPTOMS, POSTDROMES,
    LOCATIONS,
    MEDICINES, RELIEFS,
    ACTIVITIES, MISSED_ACTIVITIES,
    NOTES,
    PROCESSING, RESULTS, COMPANIONS
}

/**
 * Pages the setup flow can be opened AT, by key. This is the contract shared
 * with the Profile "What this is based on" page (per-section edit) and the
 * start screen's head-start card (`ABOUT`, the first questionnaire page).
 * Keys are stable strings because they travel through the nav route
 * (`Routes.aiSetup(start, single)`), and iOS / RN use the same keys.
 */
object AiSetupEntry {
    const val ABOUT = "about"
    const val SLEEP = "sleep"
    const val STRESS = "stress"
    const val DIET = "diet"
    const val WEATHER = "weather"
    const val EXERCISE = "exercise"
    const val WARNING_SIGNS = "warning_signs"
    const val TRIGGERS = "triggers"
    const val PRODROMES = "prodromes"
    const val SYMPTOMS = "symptoms"
    const val POSTDROMES = "postdromes"
    const val LOCATIONS = "locations"
    const val MEDICINES = "medicines"
    const val RELIEFS = "reliefs"
    const val ACTIVITIES = "activities"
    const val MISSED_ACTIVITIES = "missed_activities"
    const val NOTES = "notes"

    /** Display title per key, for the header in single-page mode. */
    fun title(key: String): String = when (key) {
        ABOUT -> Strings.tSync("About you")
        SLEEP -> Strings.tSync("Sleep")
        STRESS -> Strings.tSync("Stress & screens")
        DIET -> Strings.tSync("Food & drink")
        WEATHER -> Strings.tSync("Weather & surroundings")
        EXERCISE -> Strings.tSync("Exercise & cycle")
        WARNING_SIGNS -> Strings.tSync("Warning signs")
        TRIGGERS -> Strings.tSync("Your triggers")
        PRODROMES -> Strings.tSync("Warning signs you log")
        SYMPTOMS -> Strings.tSync("Symptoms")
        POSTDROMES -> Strings.tSync("After the attack")
        LOCATIONS -> Strings.tSync("Places")
        MEDICINES -> Strings.tSync("Medicines")
        RELIEFS -> Strings.tSync("Reliefs")
        ACTIVITIES -> Strings.tSync("Activities")
        MISSED_ACTIVITIES -> Strings.tSync("Activities you miss")
        NOTES -> Strings.tSync("Your notes")
        else -> Strings.tSync("Setup")
    }
}

private fun pageForEntry(key: String?): AiPage? = when (key) {
    AiSetupEntry.ABOUT -> AiPage.Q1
    AiSetupEntry.SLEEP -> AiPage.Q2
    AiSetupEntry.STRESS -> AiPage.Q3
    AiSetupEntry.DIET -> AiPage.Q4
    AiSetupEntry.WEATHER -> AiPage.Q5
    AiSetupEntry.EXERCISE -> AiPage.Q6
    AiSetupEntry.WARNING_SIGNS -> AiPage.Q7
    AiSetupEntry.TRIGGERS -> AiPage.TRIGGERS
    AiSetupEntry.PRODROMES -> AiPage.PRODROMES
    AiSetupEntry.SYMPTOMS -> AiPage.SYMPTOMS
    AiSetupEntry.POSTDROMES -> AiPage.POSTDROMES
    AiSetupEntry.LOCATIONS -> AiPage.LOCATIONS
    AiSetupEntry.MEDICINES -> AiPage.MEDICINES
    AiSetupEntry.RELIEFS -> AiPage.RELIEFS
    AiSetupEntry.ACTIVITIES -> AiPage.ACTIVITIES
    AiSetupEntry.MISSED_ACTIVITIES -> AiPage.MISSED_ACTIVITIES
    AiSetupEntry.NOTES -> AiPage.NOTES
    else -> null
}

/** What the single-page save did after the answers were applied, for the caller to route on. */
sealed class AiSetupEditOutcome {
    /** recalibrate returned proposals — send the user to the review screen. */
    object ProposalsReady : AiSetupEditOutcome()
    /** Saved, but no immediate re-check happened; `message` says why (cooldown, free plan, too few logs). */
    data class SavedOnly(val message: String) : AiSetupEditOutcome()
}

/**
 * @param startPage  an [AiSetupEntry] key to open at, skipping the story page;
 *                   null = the full flow from the story page (first setup / Redo).
 * @param singlePage true = show only [startPage]; Next becomes "Save & re-check",
 *                   which applies the config, re-runs recalibrate and calls
 *                   [onEditDone] instead of going on to the companions page.
 *                   Back on the single page cancels via [onSkip].
 *
 * On open, whatever the user saved before (ai_setup_profiles.answers, either
 * platform's spelling) is pre-filled, so a redo or an edit starts from their
 * own answers rather than a blank form.
 */
@Composable
fun AiSetupScreen(
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    startPage: String? = null,
    singlePage: Boolean = false,
    onEditDone: (AiSetupEditOutcome) -> Unit = {},
) {
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext
    val scope = rememberCoroutineScope()

    val entryPage = remember(startPage) { pageForEntry(startPage) }
    var currentPage by remember { mutableStateOf(entryPage ?: AiPage.STORY) }
    // Edit mode: only this one page, then the AI pass, then back to the caller.
    val editMode = singlePage && entryPage != null

    // ── Load pool items + data context on launch ──
    var availableItems by remember { mutableStateOf<AiSetupService.AvailableItems?>(null) }
    var dataContext by remember { mutableStateOf<AiSetupService.DataContext?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                availableItems = AiSetupService.buildAvailableItems(appCtx)
                dataContext = AiSetupService.buildDataContext(appCtx)
            } catch (e: Exception) {
                Log.e("AiSetup", "Failed to load pools", e)
            }
        }
    }

    // ── Story page state ──
    var storyText by remember { mutableStateOf("") }
    var storyParsed by remember { mutableStateOf(false) }
    var storyLoading by remember { mutableStateOf(false) }
    var preFill by remember { mutableStateOf<OnboardingPreFill?>(null) }
    // Track which fields were pre-filled by story (so UI can show hints)
    var preFilledFields by remember { mutableStateOf(setOf<String>()) }
    var showParseSummary by remember { mutableStateOf(false) }

    // ── Trigger / prodrome / location / postdrome pool selections ──
    var selectedTriggers by remember { mutableStateOf(setOf<String>()) }
    var selectedProdromes by remember { mutableStateOf(setOf<String>()) }
    var selectedLocations by remember { mutableStateOf(setOf<String>()) }
    var selectedPostdromes by remember { mutableStateOf(setOf<String>()) }

    // ═══════════════════════════════════════════════════════════════
    // Page 1 — Demographics & Migraine Profile
    // ═══════════════════════════════════════════════════════════════
    var gender by remember { mutableStateOf<String?>(null) }
    var ageRange by remember { mutableStateOf<String?>(null) }
    var frequency by remember { mutableStateOf<String?>(null) }
    var duration by remember { mutableStateOf<String?>(null) }
    var experience by remember { mutableStateOf<String?>(null) }
    var trajectory by remember { mutableStateOf<String?>(null) }
    var warningBefore by remember { mutableStateOf<String?>(null) }
    var triggerDelay by remember { mutableStateOf<String?>(null) }
    var dailyRoutine by remember { mutableStateOf<String?>(null) }
    var seasonalPattern by remember { mutableStateOf<String?>(null) }

    // Page 2 — Sleep
    var sleepHours by remember { mutableStateOf<String?>(null) }
    var sleepQuality by remember { mutableStateOf<String?>(null) }
    var poorQualityTriggers by remember { mutableStateOf<DeterministicMapper.Certainty?>(null) }
    var tooLittleSleepTriggers by remember { mutableStateOf<DeterministicMapper.Certainty?>(null) }
    var oversleepTriggers by remember { mutableStateOf<DeterministicMapper.Certainty?>(null) }
    var sleepIssues by remember { mutableStateOf(setOf<String>()) }

    // Page 3 — Stress & Screen
    var stressLevel by remember { mutableStateOf<String?>(null) }
    var stressChangeTriggers by remember { mutableStateOf<DeterministicMapper.Certainty?>(null) }
    var emotionalPatterns by remember { mutableStateOf(mapOf<String, DeterministicMapper.Certainty>()) }
    var screenTimeDaily by remember { mutableStateOf<String?>(null) }
    var screenTimeTriggers by remember { mutableStateOf<DeterministicMapper.Certainty?>(null) }
    var lateScreenTriggers by remember { mutableStateOf<DeterministicMapper.Certainty?>(null) }

    // Page 4 — Diet
    var caffeineIntake by remember { mutableStateOf<String?>(null) }
    var caffeineDirection by remember { mutableStateOf<String?>(null) }
    var caffeineCertainty by remember { mutableStateOf<DeterministicMapper.Certainty?>(null) }
    var alcoholFrequency by remember { mutableStateOf<String?>(null) }
    var alcoholTriggers by remember { mutableStateOf<DeterministicMapper.Certainty?>(null) }
    var specificDrinks by remember { mutableStateOf(setOf<String>()) }
    var tyramineFoods by remember { mutableStateOf(mapOf<String, DeterministicMapper.Certainty>()) }
    var histamineFoods by remember { mutableStateOf(mapOf<String, DeterministicMapper.Certainty>()) }
    var glutenSensitivity by remember { mutableStateOf<String?>(null) }
    var glutenTriggers by remember { mutableStateOf<DeterministicMapper.Certainty?>(null) }
    var eatingPatterns by remember { mutableStateOf(mapOf<String, DeterministicMapper.Certainty>()) }
    var waterIntake by remember { mutableStateOf<String?>(null) }
    var tracksNutrition by remember { mutableStateOf<String?>(null) }

    // Page 5 — Weather, Environment, Physical
    var weatherTriggers by remember { mutableStateOf<DeterministicMapper.Certainty?>(null) }
    var specificWeather by remember { mutableStateOf(mapOf<String, DeterministicMapper.Certainty>()) }
    var environmentSensitivities by remember { mutableStateOf(mapOf<String, DeterministicMapper.Certainty>()) }
    var physicalFactors by remember { mutableStateOf(mapOf<String, DeterministicMapper.Certainty>()) }

    // Page 6 — Exercise & Hormones
    var exerciseFrequency by remember { mutableStateOf<String?>(null) }
    var exerciseTriggers by remember { mutableStateOf<DeterministicMapper.Certainty?>(null) }
    var exercisePattern by remember { mutableStateOf(setOf<String>()) }
    var tracksCycle by remember { mutableStateOf<String?>(null) }
    var cyclePatterns by remember { mutableStateOf(mapOf<String, DeterministicMapper.Certainty>()) }
    var cycleLength by remember { mutableStateOf<String?>(null) }
    var cycleMigraineTiming by remember { mutableStateOf(setOf<String>()) }
    var lastPeriodDate by remember { mutableStateOf<String?>(null) }
    var usesContraception by remember { mutableStateOf<String?>(null) }
    var contraceptionEffect by remember { mutableStateOf<String?>(null) }

    // Page 7 — Prodromes
    var physicalProdromes by remember { mutableStateOf(mapOf<String, DeterministicMapper.Certainty>()) }
    var moodProdromes by remember { mutableStateOf(mapOf<String, DeterministicMapper.Certainty>()) }
    var sensoryProdromes by remember { mutableStateOf(mapOf<String, DeterministicMapper.Certainty>()) }

    // Page 8
    var selectedSymptoms by remember { mutableStateOf(setOf<String>()) }
    var selectedMedicines by remember { mutableStateOf(setOf<String>()) }
    var selectedReliefs by remember { mutableStateOf(setOf<String>()) }
    var selectedActivities by remember { mutableStateOf(setOf<String>()) }
    var selectedMissedActivities by remember { mutableStateOf(setOf<String>()) }

    // ── Tier tracking for pool pages ──
    // matched* = labels pre-selected from the story parse + questionnaire seeds ("From what you told us").
    // suggestedShown* = labels pre-selected from *_templates.suggested ("Suggested for your profile").
    var matchedTriggers by remember { mutableStateOf(setOf<String>()) }
    var matchedProdromes by remember { mutableStateOf(setOf<String>()) }
    var matchedSymptoms by remember { mutableStateOf(setOf<String>()) }
    var matchedMedicines by remember { mutableStateOf(setOf<String>()) }
    var matchedReliefs by remember { mutableStateOf(setOf<String>()) }
    var matchedActivities by remember { mutableStateOf(setOf<String>()) }
    var matchedMissedActivities by remember { mutableStateOf(setOf<String>()) }
    var matchedLocations by remember { mutableStateOf(setOf<String>()) }
    var matchedPostdromes by remember { mutableStateOf(setOf<String>()) }
    var suggestedShownTriggers by remember { mutableStateOf(setOf<String>()) }
    var suggestedShownSymptoms by remember { mutableStateOf(setOf<String>()) }
    var suggestedShownReliefs by remember { mutableStateOf(setOf<String>()) }
    var suggestedShownActivities by remember { mutableStateOf(setOf<String>()) }
    var suggestedShownLocations by remember { mutableStateOf(setOf<String>()) }
    // One-shot flags so unticking a suggestion isn't re-applied on revisit.
    // In edit mode every pool counts as already done: the user is revisiting
    // picks they made, and re-offering the suggestion tier would re-tick
    // things they had deliberately unticked.
    var suggestionsApplied by remember {
        mutableStateOf(if (editMode) setOf("triggers", "symptoms", "reliefs", "activities", "locations") else setOf<String>())
    }
    var savedAnswersLoaded by remember { mutableStateOf(false) }
    var additionalNotes by remember { mutableStateOf<String?>(null) }

    // AI state
    var isProcessing by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var aiConfig by remember { mutableStateOf<AiSetupService.AiConfig?>(null) }
    var isApplying by remember { mutableStateOf(false) }
    var applyProgress by remember { mutableStateOf<AiSetupApplier.ApplyProgress?>(null) }

    // ═══════════════════════════════════════════════════════════════
    // Build QuestionnaireAnswers from all state
    // ═══════════════════════════════════════════════════════════════
    fun buildAnswers() = DeterministicMapper.QuestionnaireAnswers(
        gender = gender, ageRange = ageRange,
        frequency = frequency, duration = duration, experience = experience,
        trajectory = trajectory, warningSignsBefore = warningBefore,
        triggerDelay = triggerDelay, dailyRoutine = dailyRoutine, seasonalPattern = seasonalPattern,
        sleepHours = sleepHours, sleepQuality = sleepQuality,
        poorSleepQualityTriggers = poorQualityTriggers ?: DeterministicMapper.Certainty.NO,
        tooLittleSleepTriggers = tooLittleSleepTriggers ?: DeterministicMapper.Certainty.NO,
        oversleepTriggers = oversleepTriggers ?: DeterministicMapper.Certainty.NO,
        sleepIssues = sleepIssues,
        stressLevel = stressLevel,
        stressChangeTriggers = stressChangeTriggers ?: DeterministicMapper.Certainty.NO,
        emotionalPatterns = emotionalPatterns,
        screenTimeDaily = screenTimeDaily,
        screenTimeTriggers = screenTimeTriggers ?: DeterministicMapper.Certainty.NO,
        lateScreenTriggers = lateScreenTriggers ?: DeterministicMapper.Certainty.NO,
        caffeineIntake = caffeineIntake, caffeineDirection = caffeineDirection,
        caffeineCertainty = caffeineCertainty ?: DeterministicMapper.Certainty.NO,
        alcoholFrequency = alcoholFrequency,
        alcoholTriggers = alcoholTriggers ?: DeterministicMapper.Certainty.NO,
        specificDrinks = specificDrinks, tyramineFoods = tyramineFoods,
        histamineFoods = histamineFoods,
        glutenSensitivity = glutenSensitivity,
        glutenTriggers = glutenTriggers ?: DeterministicMapper.Certainty.NO,
        eatingPatterns = eatingPatterns, waterIntake = waterIntake, tracksNutrition = tracksNutrition,
        weatherTriggers = weatherTriggers ?: DeterministicMapper.Certainty.NO,
        specificWeather = specificWeather,
        environmentSensitivities = environmentSensitivities,
        physicalFactors = physicalFactors,
        exerciseFrequency = exerciseFrequency,
        exerciseTriggers = exerciseTriggers ?: DeterministicMapper.Certainty.NO,
        exercisePattern = exercisePattern,
        tracksCycle = tracksCycle, cyclePatterns = cyclePatterns,
        cycleLength = cycleLength, cycleMigraineTiming = cycleMigraineTiming,
        lastPeriodDate = lastPeriodDate,
        usesContraception = usesContraception, contraceptionEffect = contraceptionEffect,
        physicalProdromes = physicalProdromes, moodProdromes = moodProdromes, sensoryProdromes = sensoryProdromes,
        selectedTriggers = selectedTriggers, selectedProdromes = selectedProdromes,
        selectedSymptoms = selectedSymptoms, selectedMedicines = selectedMedicines,
        selectedReliefs = selectedReliefs, selectedActivities = selectedActivities,
        selectedMissedActivities = selectedMissedActivities,
        selectedLocations = selectedLocations,
        selectedPostdromes = selectedPostdromes,
        freeText = additionalNotes,
    )

    // ═══════════════════════════════════════════════════════════════
    // Story parse: extract pre-fills from free text
    // ═══════════════════════════════════════════════════════════════
    fun applyPreFill(pf: OnboardingPreFill) {
        val filled = mutableSetOf<String>()
        // Page 1
        pf.gender?.let { gender = it; filled.add("gender") }
        pf.ageRange?.let { ageRange = it; filled.add("ageRange") }
        pf.frequency?.let { frequency = it; filled.add("frequency") }
        pf.duration?.let { duration = it; filled.add("duration") }
        pf.experience?.let { experience = it; filled.add("experience") }
        pf.trajectory?.let { trajectory = it; filled.add("trajectory") }
        pf.warningBefore?.let { warningBefore = it; filled.add("warningBefore") }
        pf.triggerDelay?.let { triggerDelay = it; filled.add("triggerDelay") }
        pf.dailyRoutine?.let { dailyRoutine = it; filled.add("dailyRoutine") }
        pf.seasonalPattern?.let { seasonalPattern = it; filled.add("seasonalPattern") }
        // Page 2
        pf.sleepHours?.let { sleepHours = it; filled.add("sleepHours") }
        pf.sleepQuality?.let { sleepQuality = it; filled.add("sleepQuality") }
        pf.poorQualityTriggers?.let { poorQualityTriggers = it; filled.add("poorQualityTriggers") }
        pf.tooLittleSleepTriggers?.let { tooLittleSleepTriggers = it; filled.add("tooLittleSleepTriggers") }
        pf.oversleepTriggers?.let { oversleepTriggers = it; filled.add("oversleepTriggers") }
        if (pf.sleepIssues.isNotEmpty()) { sleepIssues = sleepIssues + pf.sleepIssues; filled.add("sleepIssues") }
        // Page 3
        pf.stressLevel?.let { stressLevel = it; filled.add("stressLevel") }
        pf.stressChangeTriggers?.let { stressChangeTriggers = it; filled.add("stressChangeTriggers") }
        if (pf.emotionalPatterns.isNotEmpty()) { emotionalPatterns = emotionalPatterns + pf.emotionalPatterns; filled.add("emotionalPatterns") }
        pf.screenTimeDaily?.let { screenTimeDaily = it; filled.add("screenTimeDaily") }
        pf.screenTimeTriggers?.let { screenTimeTriggers = it; filled.add("screenTimeTriggers") }
        pf.lateScreenTriggers?.let { lateScreenTriggers = it; filled.add("lateScreenTriggers") }
        // Page 4
        pf.caffeineIntake?.let { caffeineIntake = it; filled.add("caffeineIntake") }
        pf.caffeineDirection?.let { caffeineDirection = it; filled.add("caffeineDirection") }
        pf.caffeineCertainty?.let { caffeineCertainty = it; filled.add("caffeineCertainty") }
        pf.alcoholFrequency?.let { alcoholFrequency = it; filled.add("alcoholFrequency") }
        pf.alcoholTriggers?.let { alcoholTriggers = it; filled.add("alcoholTriggers") }
        if (pf.specificDrinks.isNotEmpty()) { specificDrinks = specificDrinks + pf.specificDrinks; filled.add("specificDrinks") }
        if (pf.tyramineFoods.isNotEmpty()) { tyramineFoods = tyramineFoods + pf.tyramineFoods; filled.add("tyramineFoods") }
        if (pf.histamineFoods.isNotEmpty()) { histamineFoods = histamineFoods + pf.histamineFoods; filled.add("histamineFoods") }
        pf.glutenSensitivity?.let { glutenSensitivity = it; filled.add("glutenSensitivity") }
        pf.glutenTriggers?.let { glutenTriggers = it; filled.add("glutenTriggers") }
        if (pf.eatingPatterns.isNotEmpty()) { eatingPatterns = eatingPatterns + pf.eatingPatterns; filled.add("eatingPatterns") }
        pf.waterIntake?.let { waterIntake = it; filled.add("waterIntake") }
        pf.tracksNutrition?.let { tracksNutrition = it; filled.add("tracksNutrition") }
        // Page 5
        pf.weatherTriggers?.let { weatherTriggers = it; filled.add("weatherTriggers") }
        if (pf.specificWeather.isNotEmpty()) { specificWeather = specificWeather + pf.specificWeather; filled.add("specificWeather") }
        if (pf.environmentSensitivities.isNotEmpty()) { environmentSensitivities = environmentSensitivities + pf.environmentSensitivities; filled.add("environmentSensitivities") }
        if (pf.physicalFactors.isNotEmpty()) { physicalFactors = physicalFactors + pf.physicalFactors; filled.add("physicalFactors") }
        // Page 6
        pf.exerciseFrequency?.let { exerciseFrequency = it; filled.add("exerciseFrequency") }
        pf.exerciseTriggers?.let { exerciseTriggers = it; filled.add("exerciseTriggers") }
        if (pf.exercisePattern.isNotEmpty()) { exercisePattern = exercisePattern + pf.exercisePattern; filled.add("exercisePattern") }
        pf.tracksCycle?.let { tracksCycle = it; filled.add("tracksCycle") }
        if (pf.cyclePatterns.isNotEmpty()) { cyclePatterns = cyclePatterns + pf.cyclePatterns; filled.add("cyclePatterns") }
        pf.cycleLength?.let { cycleLength = it; filled.add("cycleLength") }
        if (pf.cycleMigraineTiming.isNotEmpty()) { cycleMigraineTiming = cycleMigraineTiming + pf.cycleMigraineTiming; filled.add("cycleMigraineTiming") }
        pf.lastPeriodDate?.let { lastPeriodDate = it; filled.add("lastPeriodDate") }
        pf.usesContraception?.let { usesContraception = it; filled.add("usesContraception") }
        pf.contraceptionEffect?.let { contraceptionEffect = it; filled.add("contraceptionEffect") }
        // Page 7
        if (pf.physicalProdromes.isNotEmpty()) { physicalProdromes = physicalProdromes + pf.physicalProdromes; filled.add("physicalProdromes") }
        if (pf.moodProdromes.isNotEmpty()) { moodProdromes = moodProdromes + pf.moodProdromes; filled.add("moodProdromes") }
        if (pf.sensoryProdromes.isNotEmpty()) { sensoryProdromes = sensoryProdromes + pf.sensoryProdromes; filled.add("sensoryProdromes") }
        // Pool labels
        if (pf.matchedTriggers.isNotEmpty()) { selectedTriggers = selectedTriggers + pf.matchedTriggers; filled.add("triggers") }
        if (pf.matchedProdromes.isNotEmpty()) { selectedProdromes = selectedProdromes + pf.matchedProdromes; filled.add("prodromes") }
        if (pf.matchedSymptoms.isNotEmpty()) { selectedSymptoms = selectedSymptoms + pf.matchedSymptoms; filled.add("symptoms") }
        if (pf.matchedMedicines.isNotEmpty()) { selectedMedicines = selectedMedicines + pf.matchedMedicines; filled.add("medicines") }
        if (pf.matchedReliefs.isNotEmpty()) { selectedReliefs = selectedReliefs + pf.matchedReliefs; filled.add("reliefs") }
        if (pf.matchedActivities.isNotEmpty()) { selectedActivities = selectedActivities + pf.matchedActivities; filled.add("activities") }
        if (pf.matchedMissedActivities.isNotEmpty()) { selectedMissedActivities = selectedMissedActivities + pf.matchedMissedActivities; filled.add("missedActivities") }
        if (pf.matchedLocations.isNotEmpty()) { selectedLocations = selectedLocations + pf.matchedLocations; filled.add("locations") }
        if (pf.matchedPostdromes.isNotEmpty()) { selectedPostdromes = selectedPostdromes + pf.matchedPostdromes; filled.add("postdromes") }
        matchedTriggers = matchedTriggers + pf.matchedTriggers
        matchedProdromes = matchedProdromes + pf.matchedProdromes
        matchedSymptoms = matchedSymptoms + pf.matchedSymptoms
        matchedMedicines = matchedMedicines + pf.matchedMedicines
        matchedReliefs = matchedReliefs + pf.matchedReliefs
        matchedActivities = matchedActivities + pf.matchedActivities
        matchedMissedActivities = matchedMissedActivities + pf.matchedMissedActivities
        matchedLocations = matchedLocations + pf.matchedLocations
        matchedPostdromes = matchedPostdromes + pf.matchedPostdromes
        preFilledFields = filled
    }

    // Pre-fill from what the user saved last time (redo / edit-from-Profile).
    // Runs before anything else can be tapped; a first-time user has no row
    // and this is a no-op. Not routed through the parse-summary banner: these
    // are the user's own answers, not something the AI inferred.
    LaunchedEffect(Unit) {
        val saved = AiSetupProfileStore.load(appCtx)
        val answers = saved?.answers
        if (answers != null) {
            applyPreFill(AiSetupProfileStore.preFillFromAnswers(answers))
            AiSetupProfileStore.freeText(answers)?.let { additionalNotes = it }
            preFilledFields = emptySet()
        }
        savedAnswersLoaded = true
    }

    fun parseStory() {
        if (storyText.isBlank()) { storyParsed = true; currentPage = AiPage.Q1; return }
        storyLoading = true
        scope.launch {
            try {
                val items = availableItems
                val trigLabels = items?.triggers?.map { it.label } ?: emptyList()
                val prodLabels = items?.prodromes?.map { it.label } ?: emptyList()
                // Include core + pain character + accompanying (everything except postdromes,
                // which are passed as their own pool). All three categories share `selectedSymptoms`
                // so matched labels land on whichever page hosts the category.
                val symLabels = items?.symptoms?.filter {
                    !((it.category ?: "").equals("Postdrome", ignoreCase = true))
                }?.map { it.label } ?: emptyList()
                val medLabels = items?.medicines?.map { it.label } ?: emptyList()
                val relLabels = items?.reliefs?.map { it.label } ?: emptyList()
                val actLabels = items?.activities?.map { it.label } ?: emptyList()
                val missLabels = items?.missedActivities?.map { it.label } ?: emptyList()
                val locLabels = items?.locations?.map { it.label } ?: emptyList()
                val postdromeLabels = items?.postdromes?.map { it.label } ?: emptyList()

                // Step 1: deterministic
                val deter = withContext(Dispatchers.IO) {
                    AiOnboardingParser.deterministicPreFill(
                        storyText, trigLabels, prodLabels, symLabels,
                        medLabels, relLabels, actLabels, missLabels,
                        locLabels, postdromeLabels
                    )
                }

                // Step 2: GPT enhancement
                val token = withContext(Dispatchers.IO) { SessionStore.getValidAccessToken(appCtx) }
                val gpt = if (token != null) {
                    try {
                        withContext(Dispatchers.IO) {
                            AiOnboardingParser.gptPreFill(
                                token, storyText, trigLabels, prodLabels, symLabels,
                                medLabels, relLabels, actLabels, missLabels,
                                locLabels, postdromeLabels, deter
                            )
                        }
                    } catch (_: Exception) { null }
                } else null

                // Step 3: merge and apply
                val merged = AiOnboardingParser.merge(deter, gpt)
                Log.d("AiSetup", "suggested=${merged.suggestedFields} reasons=${merged.suggestionReasons.keys} direct=${merged.directFieldCount}")
                preFill = merged
                applyPreFill(merged)

            } catch (e: Exception) {
                Log.e("AiSetup", "Story parse failed: ${e.message}", e)
            }
            storyParsed = true; storyLoading = false
            showParseSummary = true
            currentPage = AiPage.Q1
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Process: deterministic mapping THEN AI calibration
    // ═══════════════════════════════════════════════════════════════
    fun process() {
        isProcessing = true
        aiError = null
        scope.launch {
            try {
                val answers = buildAnswers()
                val dc = dataContext ?: withContext(Dispatchers.IO) { AiSetupService.buildDataContext(appCtx) }
                val items = availableItems ?: withContext(Dispatchers.IO) { AiSetupService.buildAvailableItems(appCtx) }
                Log.d("AiSetup", "autoTriggerLabels(${items.autoTriggerLabels.size}): ${items.autoTriggerLabels.take(5)}")
                Log.d("AiSetup", "autoProdromeLabels(${items.autoProdromeLabels.size}): ${items.autoProdromeLabels.take(5)}")

                // Step 1: Deterministic mapping (pure Kotlin, instant)
                val mapping = DeterministicMapper.map(answers, dc.enabledMetrics, dc.metricSources)

                // Step 2: AI calibration — two-call architecture
                val result = AiCalibrationService.calibrate(
                    context = appCtx,
                    mapping = mapping,
                    items = items,
                    answers = answers,
                    dataContext = dc,
                )

                result.fold(
                    onSuccess = { config ->
                        // Inject menstruation config if user tracks cycle
                        val finalConfig = if (answers.tracksCycle == "Yes") {
                            val mWeights = DeterministicMapper.buildMenstruationDecayWeights(answers)
                            val mSeverity = config.triggers.firstOrNull { it.label.equals("menstruation_predicted", ignoreCase = true) }?.severity ?: "MILD"
                            val curve = mWeights?.let { listOf(it.dayM7, it.dayM6, it.dayM5, it.dayM4, it.dayM3, it.dayM2, it.dayM1, it.day0, it.dayP1, it.dayP2, it.dayP3, it.dayP4, it.dayP5, it.dayP6, it.dayP7) } ?: emptyList()
                            config.copy(menstruationConfig = AiSetupService.AiMenstruationConfig(
                                avgCycleLength = DeterministicMapper.deriveCycleLength(answers),
                                severity = mSeverity,
                                decayCurve = curve,
                                reasoning = "Based on your reported cycle patterns${if (answers.cycleMigraineTiming.isNotEmpty()) " (${answers.cycleMigraineTiming.joinToString(", ")})" else ""}",
                            ))
                        } else config
                        aiConfig = finalConfig; isProcessing = false; currentPage = AiPage.RESULTS
                    },
                    onFailure = { e -> aiError = e.message ?: "Unknown error"; isProcessing = false; Log.e("AiSetup", "AI calibration failed", e) }
                )
            } catch (e: Exception) {
                aiError = e.message ?: "Unknown error"; isProcessing = false
                Log.e("AiSetup", "Processing exception", e)
            }
        }
    }

    // Edit mode only: the answers are applied and saved, then the weekly
    // learning pass runs straight away rather than waiting for Monday. The
    // three ways recalibrate declines are each said out loud, never swallowed:
    // once-a-day cooldown, free plan (403), fewer than 5 logged attacks.
    suspend fun recheckNow(): AiSetupEditOutcome {
        val result = withContext(Dispatchers.IO) { runRecalibration(appCtx) }
        return when (result.first) {
            "ok" -> AiSetupEditOutcome.ProposalsReady
            "cooldown" -> AiSetupEditOutcome.SavedOnly(
                Strings.tSync("Your answers are saved. The AI already re-checked your profile today, so the next re-check is on %1\$s.", result.second))
            "insufficient_data" -> AiSetupEditOutcome.SavedOnly(
                Strings.tSync("Your answers are saved. The AI needs at least 5 logged migraines before it can re-check your profile — you have %1\$s so far.", result.second))
            "premium_required" -> AiSetupEditOutcome.SavedOnly(
                Strings.tSync("Your answers are saved. Re-checking your profile with the AI is part of Premium, so the next weekly check will not run on the free plan."))
            "no_material_changes" -> AiSetupEditOutcome.SavedOnly(
                Strings.tSync("Your answers are saved. The AI re-checked your profile and found nothing worth changing."))
            else -> AiSetupEditOutcome.SavedOnly(
                Strings.tSync("Your answers are saved, but the re-check did not run: %1\$s", result.second))
        }
    }

    fun applyConfig(modifiedConfig: AiSetupService.AiConfig? = null) {
        val config = modifiedConfig ?: aiConfig ?: return
        aiConfig = config  // store the (possibly modified) config
        isApplying = true
        scope.launch {
            val applied = AiSetupApplier.applyConfig(appCtx, config, buildAnswers()) { progress -> applyProgress = progress }
            if (applied.isFailure) Log.w("AiSetup", "applyConfig partially failed, saving profile anyway", applied.exceptionOrNull())
            // Save answers + AI config to Supabase for community features.
            // Awaited (not fire-and-forget) in edit mode so the recheck that
            // follows reads the row this edit just wrote.
            val save = async(Dispatchers.IO) {
                runCatching { AiSetupProfileStore.save(appCtx, buildAnswers(), config) }
                    .onFailure { Log.w("AiSetup", "Profile store save failed (non-blocking)", it) }
            }
            if (editMode) {
                save.await()
                val outcome = recheckNow()
                isApplying = false
                onEditDone(outcome)
            } else {
                isApplying = false; currentPage = AiPage.COMPANIONS
            }
        }
    }

    LaunchedEffect(currentPage) {
        if (currentPage == AiPage.PROCESSING && !isProcessing && aiConfig == null) process()
    }

    // Silent 1:1 pre-selection from Q1–Q7 answers when entering the matching pool page.
    // Union with whatever the user (or story pre-fill) already selected — never removes.
    LaunchedEffect(currentPage, availableItems) {
        val items = availableItems ?: return@LaunchedEffect
        // Profile-conditional suggestion gates (see *_templates.suggested_condition).
        fun conditionHolds(cond: String?): Boolean = when (cond) {
            null -> true
            "menstruation" -> tracksCycle == "Yes"
            "aura" -> "Aura" in selectedSymptoms || "Aura" in matchedSymptoms
            else -> false
        }
        fun eligible(map: Map<String, String?>, pool: List<AiSetupService.PoolLabel>, exclude: Set<String>): Set<String> =
            pool.map { it.label }
                .filter { l -> map.containsKey(l.lowercase()) && conditionHolds(map[l.lowercase()]) && l !in exclude }
                .toSet()
        when (currentPage) {
            AiPage.TRIGGERS -> {
                val seed = DeterministicMapper.deriveTriggerSeed(buildAnswers(), items.triggers.map { it.label })
                if (seed.isNotEmpty()) { selectedTriggers = selectedTriggers + seed; matchedTriggers = matchedTriggers + seed }
                if ("triggers" !in suggestionsApplied) {
                    suggestionsApplied = suggestionsApplied + "triggers"
                    val sugg = eligible(items.suggestedTriggers, items.triggers, matchedTriggers + selectedTriggers)
                    suggestedShownTriggers = sugg
                    selectedTriggers = selectedTriggers + sugg
                }
            }
            AiPage.PRODROMES -> {
                val seed = DeterministicMapper.deriveProdromeSeed(buildAnswers(), items.prodromes.map { it.label })
                if (seed.isNotEmpty()) { selectedProdromes = selectedProdromes + seed; matchedProdromes = matchedProdromes + seed }
            }
            AiPage.SYMPTOMS -> {
                if ("symptoms" !in suggestionsApplied) {
                    suggestionsApplied = suggestionsApplied + "symptoms"
                    val sugg = eligible(items.suggestedSymptoms, items.symptoms, matchedSymptoms + selectedSymptoms)
                    suggestedShownSymptoms = sugg
                    selectedSymptoms = selectedSymptoms + sugg
                }
            }
            AiPage.RELIEFS -> {
                if ("reliefs" !in suggestionsApplied) {
                    suggestionsApplied = suggestionsApplied + "reliefs"
                    val sugg = eligible(items.suggestedReliefs, items.reliefs, matchedReliefs + selectedReliefs)
                    suggestedShownReliefs = sugg
                    selectedReliefs = selectedReliefs + sugg
                }
            }
            AiPage.ACTIVITIES -> {
                if ("activities" !in suggestionsApplied) {
                    suggestionsApplied = suggestionsApplied + "activities"
                    val sugg = eligible(items.suggestedActivities, items.activities, matchedActivities + selectedActivities)
                    suggestedShownActivities = sugg
                    selectedActivities = selectedActivities + sugg
                }
            }
            AiPage.LOCATIONS -> {
                if ("locations" !in suggestionsApplied) {
                    suggestionsApplied = suggestionsApplied + "locations"
                    val sugg = eligible(items.suggestedLocations, items.locations, matchedLocations + selectedLocations)
                    suggestedShownLocations = sugg
                    selectedLocations = selectedLocations + sugg
                }
            }
            else -> {}
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // UI
    // ═══════════════════════════════════════════════════════════════
    val totalPages = AiPage.entries.size
    val pageNum = AiPage.entries.indexOf(currentPage) + 1
    val bgBrush = remember { Brush.verticalGradient(listOf(Color(0xFF1A0029), Color(0xFF2A003D), Color(0xFF1A0029))) }

    Box(Modifier.fillMaxSize().background(bgBrush)) {
        Column(Modifier.fillMaxSize()) {
            // Progress bar
            LinearProgressIndicator(
                progress = { pageNum.toFloat() / totalPages },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = AppTheme.AccentPink, trackColor = AppTheme.TrackColor,
            )
            Row(Modifier.padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = AppTheme.AccentPink, modifier = Modifier.size(14.dp))
                Text(
                    if (editMode) t("Edit — %1\$s", AiSetupEntry.title(startPage ?: ""))
                    else t("MigraineMe Setup — %1\$s of %2\$s", pageNum, totalPages),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall
                )
            }
            Spacer(Modifier.height(8.dp))

            // Page content
            // What the setup just did, said once, right where the answers land.
            // Dismissed on first page change so it never becomes furniture.
            val pf = preFill
            if (pf != null && showParseSummary && (pf.directFieldCount > 0 || pf.suggestedFieldCount > 0)) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AppTheme.AccentPurple.copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(20.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                t("We filled in %s from what you told us", pf.directFieldCount) +
                                    if (pf.suggestedFieldCount > 0) t(", and suggested %s more", pf.suggestedFieldCount) else "",
                                color = AppTheme.AccentPurple,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                if (pf.suggestedFieldCount > 0)
                                    t("The suggestions come from your profile — worth tracking to find out. Change anything that doesn't fit as you go.")
                                else
                                    t("Check them as you go and change anything that doesn't fit."),
                                color = AppTheme.BodyTextColor,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            CompositionLocalProvider(
                LocalAiSuggestions provides AiSuggestions(
                    fields = preFill?.suggestedFields ?: emptySet(),
                    reasons = preFill?.suggestionReasons ?: emptyMap()
                )
            ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = currentPage,
                    transitionSpec = {
                        if (targetState.ordinal > initialState.ordinal)
                            slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
                        else
                            slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
                    }, label = "aiPage",
                ) { page ->
                    when (page) {
                        AiPage.STORY -> AiQuestionsPageStory(
                            text = storyText,
                            onTextChange = { storyText = it; storyParsed = false; preFill = null; preFilledFields = emptySet(); showParseSummary = false },
                            isLoading = storyLoading,
                            onParse = { parseStory() },
                            onSkip = { currentPage = AiPage.Q1 },
                        )
                        AiPage.Q1 -> AiQuestionsPage1(gender, { gender = it }, ageRange, { ageRange = it }, frequency, { frequency = it }, duration, { duration = it }, experience, { experience = it }, trajectory, { trajectory = it }, warningBefore, { warningBefore = it }, triggerDelay, { triggerDelay = it }, dailyRoutine, { dailyRoutine = it }, seasonalPattern, { seasonalPattern = it })
                        AiPage.Q2 -> AiQuestionsPage2(sleepHours, { sleepHours = it }, sleepQuality, { sleepQuality = it }, poorQualityTriggers, { poorQualityTriggers = it }, tooLittleSleepTriggers, { tooLittleSleepTriggers = it }, oversleepTriggers, { oversleepTriggers = it }, sleepIssues, { i -> sleepIssues = if (i in sleepIssues) sleepIssues - i else sleepIssues + i })
                        AiPage.Q3 -> AiQuestionsPage3(stressLevel, { stressLevel = it }, stressChangeTriggers, { stressChangeTriggers = it }, emotionalPatterns, { emotionalPatterns = it }, screenTimeDaily, { screenTimeDaily = it }, screenTimeTriggers, { screenTimeTriggers = it }, lateScreenTriggers, { lateScreenTriggers = it })
                        AiPage.Q4 -> AiQuestionsPage4(caffeineIntake, { caffeineIntake = it }, caffeineDirection, { caffeineDirection = it }, caffeineCertainty, { caffeineCertainty = it }, alcoholFrequency, { alcoholFrequency = it }, alcoholTriggers, { alcoholTriggers = it }, specificDrinks, { d -> specificDrinks = if (d in specificDrinks) specificDrinks - d else specificDrinks + d }, tyramineFoods, { tyramineFoods = it }, histamineFoods, { histamineFoods = it }, glutenSensitivity, { glutenSensitivity = it }, glutenTriggers, { glutenTriggers = it }, eatingPatterns, { eatingPatterns = it }, waterIntake, { waterIntake = it }, tracksNutrition, { tracksNutrition = it })
                        AiPage.Q5 -> AiQuestionsPage5(weatherTriggers, { weatherTriggers = it }, specificWeather, { specificWeather = it }, environmentSensitivities, { environmentSensitivities = it }, physicalFactors, { physicalFactors = it })
                        AiPage.Q6 -> AiQuestionsPage6(exerciseFrequency, { exerciseFrequency = it }, exerciseTriggers, { exerciseTriggers = it }, exercisePattern, { p -> exercisePattern = if (p in exercisePattern) exercisePattern - p else exercisePattern + p }, tracksCycle, { tracksCycle = it }, cyclePatterns, { cyclePatterns = it }, cycleLength, { cycleLength = it }, cycleMigraineTiming, { t -> cycleMigraineTiming = if (t in cycleMigraineTiming) cycleMigraineTiming - t else cycleMigraineTiming + t }, lastPeriodDate, { lastPeriodDate = it }, usesContraception, { usesContraception = it }, contraceptionEffect, { contraceptionEffect = it })
                        AiPage.Q7 -> AiQuestionsPage7(physicalProdromes, { physicalProdromes = it }, moodProdromes, { moodProdromes = it }, sensoryProdromes, { sensoryProdromes = it })
                        AiPage.TRIGGERS -> AiQuestionsPageTriggers(
                            triggerPool = availableItems?.triggers ?: emptyList(),
                            selected = selectedTriggers,
                            onToggle = { t -> selectedTriggers = if (t in selectedTriggers) selectedTriggers - t else selectedTriggers + t },
                            matched = matchedTriggers,
                            suggested = suggestedShownTriggers,
                            onDeselectSuggested = { selectedTriggers = selectedTriggers - suggestedShownTriggers },
                        )
                        AiPage.PRODROMES -> AiQuestionsPageProdromes(
                            prodromePool = availableItems?.prodromes ?: emptyList(),
                            selected = selectedProdromes,
                            onToggle = { p -> selectedProdromes = if (p in selectedProdromes) selectedProdromes - p else selectedProdromes + p },
                            matched = matchedProdromes,
                        )
                        AiPage.SYMPTOMS -> AiQuestionsPageSymptomsCore(
                            pool = availableItems?.symptomsDuringAttack ?: emptyList(),
                            selected = selectedSymptoms,
                            onToggle = { s -> selectedSymptoms = if (s in selectedSymptoms) selectedSymptoms - s else selectedSymptoms + s },
                            matched = matchedSymptoms,
                            suggested = suggestedShownSymptoms,
                            onDeselectSuggested = { selectedSymptoms = selectedSymptoms - suggestedShownSymptoms },
                        )
                        AiPage.POSTDROMES -> AiQuestionsPagePostdromes(
                            postdromePool = availableItems?.postdromes ?: emptyList(),
                            selected = selectedPostdromes,
                            onToggle = { p -> selectedPostdromes = if (p in selectedPostdromes) selectedPostdromes - p else selectedPostdromes + p },
                            matched = matchedPostdromes,
                        )
                        AiPage.LOCATIONS -> AiQuestionsPageLocations(
                            locationPool = availableItems?.locations ?: emptyList(),
                            selected = selectedLocations,
                            onToggle = { l -> selectedLocations = if (l in selectedLocations) selectedLocations - l else selectedLocations + l },
                            matched = matchedLocations,
                            suggested = suggestedShownLocations,
                            onDeselectSuggested = { selectedLocations = selectedLocations - suggestedShownLocations },
                        )
                        AiPage.MEDICINES -> AiQuestionsPageMedicines(
                            pool = availableItems?.medicines ?: emptyList(),
                            selected = selectedMedicines,
                            onToggle = { m -> selectedMedicines = if (m in selectedMedicines) selectedMedicines - m else selectedMedicines + m },
                            matched = matchedMedicines,
                        )
                        AiPage.RELIEFS -> AiQuestionsPageReliefs(
                            pool = availableItems?.reliefs ?: emptyList(),
                            selected = selectedReliefs,
                            onToggle = { r -> selectedReliefs = if (r in selectedReliefs) selectedReliefs - r else selectedReliefs + r },
                            matched = matchedReliefs,
                            suggested = suggestedShownReliefs,
                            onDeselectSuggested = { selectedReliefs = selectedReliefs - suggestedShownReliefs },
                        )
                        AiPage.ACTIVITIES -> AiQuestionsPageActivities(
                            pool = availableItems?.activities ?: emptyList(),
                            selected = selectedActivities,
                            onToggle = { a -> selectedActivities = if (a in selectedActivities) selectedActivities - a else selectedActivities + a },
                            matched = matchedActivities,
                            suggested = suggestedShownActivities,
                            onDeselectSuggested = { selectedActivities = selectedActivities - suggestedShownActivities },
                        )
                        AiPage.MISSED_ACTIVITIES -> AiQuestionsPageMissedActivities(
                            pool = availableItems?.missedActivities ?: emptyList(),
                            selected = selectedMissedActivities,
                            onToggle = { ma -> selectedMissedActivities = if (ma in selectedMissedActivities) selectedMissedActivities - ma else selectedMissedActivities + ma },
                            matched = matchedMissedActivities,
                        )
                        AiPage.NOTES -> AiQuestionsPageNotes(
                            notes = additionalNotes,
                            onNotesChange = { additionalNotes = it },
                        )
                        AiPage.PROCESSING -> AiProcessingPage(isProcessing, aiError, onRetry = { aiError = null; process() })
                        AiPage.RESULTS -> aiConfig?.let { config ->
                            AiSetupResultsScreen(config = config, availableItems = availableItems, onApply = { modified -> applyConfig(modified) }, onSkip = onSkip, isApplying = isApplying, applyProgress = applyProgress)
                        }
                        AiPage.COMPANIONS -> {
                            val companionToken = remember { mutableStateOf<String?>(null) }
                            LaunchedEffect(Unit) {
                                companionToken.value = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                    SessionStore.getValidAccessToken(appCtx)
                                }
                            }
                            CompanionsOnboardingScreen(
                                accessToken = companionToken.value,
                                recommendedSlugs = aiConfig?.recommendedCompanions ?: emptyList(),
                                onContinue = onComplete
                            )
                        }
                    }
                }
            }
            }

            // Bottom navigation
            if (editMode && currentPage != AiPage.PROCESSING && currentPage != AiPage.RESULTS) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onSkip) { Text(t("Cancel"), color = AppTheme.SubtleTextColor) }
                    Button(onClick = { currentPage = AiPage.PROCESSING }, enabled = savedAnswersLoaded, colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(t("Save & re-check"))
                    }
                }
            } else if (!editMode && currentPage != AiPage.STORY && currentPage != AiPage.PROCESSING && currentPage != AiPage.RESULTS && currentPage != AiPage.COMPANIONS) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    if (currentPage == AiPage.Q1) {
                        TextButton(onClick = { currentPage = AiPage.STORY }) { Text(t("Back"), color = AppTheme.SubtleTextColor) }
                    } else {
                        TextButton(onClick = { val prev = AiPage.entries.getOrNull(currentPage.ordinal - 1); if (prev != null) currentPage = prev }) { Text(t("Back"), color = AppTheme.SubtleTextColor) }
                    }
                    if (currentPage == AiPage.NOTES) {
                        Button(onClick = { currentPage = AiPage.PROCESSING }, colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink), shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(t("Analyse & Configure"))
                        }
                    } else {
                        Button(onClick = { val next = AiPage.entries.getOrNull(currentPage.ordinal + 1); if (next != null) currentPage = next }, colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple), shape = RoundedCornerShape(12.dp)) {
                            Text(t("Next")); Spacer(Modifier.width(4.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            if (currentPage == AiPage.STORY) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onSkip) { Text(t("Skip Setup"), color = AppTheme.SubtleTextColor) }
                    if (storyText.isNotBlank() && !storyLoading) {
                        Button(onClick = { parseStory() }, colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink), shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(t("Find matches & continue"))
                        }
                    } else if (!storyLoading) {
                        Button(onClick = { currentPage = AiPage.Q1 }, colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple), shape = RoundedCornerShape(12.dp)) {
                            Text(t("OR Continue and answer manually")); Spacer(Modifier.width(4.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            if (currentPage == AiPage.PROCESSING && !isProcessing && aiError != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { currentPage = if (editMode) (entryPage ?: AiPage.NOTES) else AiPage.NOTES }) { Text(t("Back"), color = AppTheme.SubtleTextColor) }
                    TextButton(onClick = onSkip) { Text(if (editMode) t("Cancel") else t("Skip Setup"), color = AppTheme.SubtleTextColor) }
                }
            }
        }
    }
}