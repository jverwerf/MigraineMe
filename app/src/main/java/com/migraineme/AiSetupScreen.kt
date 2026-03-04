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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AiPage { STORY, Q1, Q2, Q3, Q4, Q5, Q6, Q7, TRIGGERS, PRODROMES, Q8, PROCESSING, RESULTS, COMPANIONS }

@Composable
fun AiSetupScreen(
    onComplete: () -> Unit,
    onSkip: () -> Unit,
) {
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext
    val scope = rememberCoroutineScope()

    var currentPage by remember { mutableStateOf(AiPage.STORY) }

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

    // ── Trigger / prodrome pool selections ──
    var selectedTriggers by remember { mutableStateOf(setOf<String>()) }
    var selectedProdromes by remember { mutableStateOf(setOf<String>()) }

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
        freeText = additionalNotes,
    )

    // ═══════════════════════════════════════════════════════════════
    // Story parse: extract pre-fills from free text
    // ═══════════════════════════════════════════════════════════════
    fun applyPreFill(pf: OnboardingPreFill) {
        val filled = mutableSetOf<String>()
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
        pf.sleepHours?.let { sleepHours = it; filled.add("sleepHours") }
        pf.sleepQuality?.let { sleepQuality = it; filled.add("sleepQuality") }
        pf.stressLevel?.let { stressLevel = it; filled.add("stressLevel") }
        pf.screenTimeDaily?.let { screenTimeDaily = it; filled.add("screenTimeDaily") }
        pf.caffeineIntake?.let { caffeineIntake = it; filled.add("caffeineIntake") }
        pf.alcoholFrequency?.let { alcoholFrequency = it; filled.add("alcoholFrequency") }
        pf.exerciseFrequency?.let { exerciseFrequency = it; filled.add("exerciseFrequency") }
        pf.tracksCycle?.let { tracksCycle = it; filled.add("tracksCycle") }
        if (pf.matchedTriggers.isNotEmpty()) { selectedTriggers = selectedTriggers + pf.matchedTriggers; filled.add("triggers") }
        if (pf.matchedProdromes.isNotEmpty()) { selectedProdromes = selectedProdromes + pf.matchedProdromes; filled.add("prodromes") }
        if (pf.matchedSymptoms.isNotEmpty()) { selectedSymptoms = selectedSymptoms + pf.matchedSymptoms; filled.add("symptoms") }
        if (pf.matchedMedicines.isNotEmpty()) { selectedMedicines = selectedMedicines + pf.matchedMedicines; filled.add("medicines") }
        if (pf.matchedReliefs.isNotEmpty()) { selectedReliefs = selectedReliefs + pf.matchedReliefs; filled.add("reliefs") }
        if (pf.matchedActivities.isNotEmpty()) { selectedActivities = selectedActivities + pf.matchedActivities; filled.add("activities") }
        if (pf.matchedMissedActivities.isNotEmpty()) { selectedMissedActivities = selectedMissedActivities + pf.matchedMissedActivities; filled.add("missedActivities") }
        preFilledFields = filled
    }

    fun parseStory() {
        if (storyText.isBlank()) { storyParsed = true; currentPage = AiPage.Q1; return }
        storyLoading = true
        scope.launch {
            try {
                val items = availableItems
                val trigLabels = items?.triggers?.map { it.label } ?: emptyList()
                val prodLabels = items?.prodromes?.map { it.label } ?: emptyList()
                val symLabels = items?.symptoms?.map { it.label } ?: emptyList()
                val medLabels = items?.medicines?.map { it.label } ?: emptyList()
                val relLabels = items?.reliefs?.map { it.label } ?: emptyList()
                val actLabels = items?.activities?.map { it.label } ?: emptyList()
                val missLabels = items?.missedActivities?.map { it.label } ?: emptyList()

                // Step 1: deterministic
                val deter = withContext(Dispatchers.IO) {
                    AiOnboardingParser.deterministicPreFill(
                        storyText, trigLabels, prodLabels, symLabels,
                        medLabels, relLabels, actLabels, missLabels
                    )
                }

                // Step 2: GPT enhancement
                val token = withContext(Dispatchers.IO) { SessionStore.getValidAccessToken(appCtx) }
                val gpt = if (token != null) {
                    try {
                        withContext(Dispatchers.IO) {
                            AiOnboardingParser.gptPreFill(
                                token, storyText, trigLabels, prodLabels, symLabels,
                                medLabels, relLabels, actLabels, missLabels, deter
                            )
                        }
                    } catch (_: Exception) { null }
                } else null

                // Step 3: merge and apply
                val merged = AiOnboardingParser.merge(deter, gpt)
                preFill = merged
                applyPreFill(merged)

            } catch (e: Exception) {
                Log.e("AiSetup", "Story parse failed: ${e.message}", e)
            }
            storyParsed = true; storyLoading = false
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

    fun applyConfig(modifiedConfig: AiSetupService.AiConfig? = null) {
        val config = modifiedConfig ?: aiConfig ?: return
        aiConfig = config  // store the (possibly modified) config
        isApplying = true
        scope.launch {
            AiSetupApplier.applyConfig(appCtx, config, buildAnswers()) { progress -> applyProgress = progress }.fold(
                onSuccess = {
                    // Save answers + AI config to Supabase for community features
                    launch(Dispatchers.IO) {
                        runCatching { AiSetupProfileStore.save(appCtx, buildAnswers(), config) }
                            .onFailure { Log.w("AiSetup", "Profile store save failed (non-blocking)", it) }
                    }
                    isApplying = false; currentPage = AiPage.COMPANIONS
                },
                onFailure = {
                    // Still try to save even if apply partially failed
                    launch(Dispatchers.IO) {
                        runCatching { AiSetupProfileStore.save(appCtx, buildAnswers(), config) }
                            .onFailure { Log.w("AiSetup", "Profile store save failed (non-blocking)", it) }
                    }
                    isApplying = false; currentPage = AiPage.COMPANIONS
                }
            )
        }
    }

    LaunchedEffect(currentPage) {
        if (currentPage == AiPage.PROCESSING && !isProcessing && aiConfig == null) process()
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
                Text("MigraineMe Setup — $pageNum of $totalPages", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(8.dp))

            // Page content
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
                            onTextChange = { storyText = it; storyParsed = false; preFill = null; preFilledFields = emptySet() },
                            isLoading = storyLoading,
                            onParse = { parseStory() },
                            onSkip = { currentPage = AiPage.Q1 },
                        )
                        AiPage.Q1 -> AiQuestionsPage1(gender, { gender = it }, ageRange, { ageRange = it }, frequency, { frequency = it }, duration, { duration = it }, experience, { experience = it }, trajectory, { trajectory = it }, warningBefore, { warningBefore = it }, triggerDelay, { triggerDelay = it }, dailyRoutine, { dailyRoutine = it }, seasonalPattern, { seasonalPattern = it })
                        AiPage.Q2 -> AiQuestionsPage2(sleepHours, { sleepHours = it }, sleepQuality, { sleepQuality = it }, poorQualityTriggers, { poorQualityTriggers = it }, tooLittleSleepTriggers, { tooLittleSleepTriggers = it }, oversleepTriggers, { oversleepTriggers = it }, sleepIssues, { i -> sleepIssues = if (i in sleepIssues) sleepIssues - i else sleepIssues + i })
                        AiPage.Q3 -> AiQuestionsPage3(stressLevel, { stressLevel = it }, stressChangeTriggers, { stressChangeTriggers = it }, emotionalPatterns, { emotionalPatterns = it }, screenTimeDaily, { screenTimeDaily = it }, screenTimeTriggers, { screenTimeTriggers = it }, lateScreenTriggers, { lateScreenTriggers = it })
                        AiPage.Q4 -> AiQuestionsPage4(caffeineIntake, { caffeineIntake = it }, caffeineDirection, { caffeineDirection = it }, caffeineCertainty, { caffeineCertainty = it }, alcoholFrequency, { alcoholFrequency = it }, alcoholTriggers, { alcoholTriggers = it }, specificDrinks, { d -> specificDrinks = if (d in specificDrinks) specificDrinks - d else specificDrinks + d }, tyramineFoods, { tyramineFoods = it }, glutenSensitivity, { glutenSensitivity = it }, glutenTriggers, { glutenTriggers = it }, eatingPatterns, { eatingPatterns = it }, waterIntake, { waterIntake = it }, tracksNutrition, { tracksNutrition = it })
                        AiPage.Q5 -> AiQuestionsPage5(weatherTriggers, { weatherTriggers = it }, specificWeather, { specificWeather = it }, environmentSensitivities, { environmentSensitivities = it }, physicalFactors, { physicalFactors = it })
                        AiPage.Q6 -> AiQuestionsPage6(exerciseFrequency, { exerciseFrequency = it }, exerciseTriggers, { exerciseTriggers = it }, exercisePattern, { p -> exercisePattern = if (p in exercisePattern) exercisePattern - p else exercisePattern + p }, tracksCycle, { tracksCycle = it }, cyclePatterns, { cyclePatterns = it }, cycleLength, { cycleLength = it }, cycleMigraineTiming, { t -> cycleMigraineTiming = if (t in cycleMigraineTiming) cycleMigraineTiming - t else cycleMigraineTiming + t }, lastPeriodDate, { lastPeriodDate = it }, usesContraception, { usesContraception = it }, contraceptionEffect, { contraceptionEffect = it })
                        AiPage.Q7 -> AiQuestionsPage7(physicalProdromes, { physicalProdromes = it }, moodProdromes, { moodProdromes = it }, sensoryProdromes, { sensoryProdromes = it })
                        AiPage.TRIGGERS -> AiQuestionsPageTriggers(
                            triggerPool = availableItems?.triggers ?: emptyList(),
                            selected = selectedTriggers,
                            onToggle = { t -> selectedTriggers = if (t in selectedTriggers) selectedTriggers - t else selectedTriggers + t },
                        )
                        AiPage.PRODROMES -> AiQuestionsPageProdromes(
                            prodromePool = availableItems?.prodromes ?: emptyList(),
                            selected = selectedProdromes,
                            onToggle = { p -> selectedProdromes = if (p in selectedProdromes) selectedProdromes - p else selectedProdromes + p },
                        )
                        AiPage.Q8 -> AiQuestionsPage8(
                            symptomPool = availableItems?.symptoms ?: emptyList(),
                            medicinePool = availableItems?.medicines ?: emptyList(),
                            reliefPool = availableItems?.reliefs ?: emptyList(),
                            activityPool = availableItems?.activities ?: emptyList(),
                            missedActivityPool = availableItems?.missedActivities ?: emptyList(),
                            selectedSymptoms, { s -> selectedSymptoms = if (s in selectedSymptoms) selectedSymptoms - s else selectedSymptoms + s },
                            selectedMedicines, { m -> selectedMedicines = if (m in selectedMedicines) selectedMedicines - m else selectedMedicines + m },
                            selectedReliefs, { r -> selectedReliefs = if (r in selectedReliefs) selectedReliefs - r else selectedReliefs + r },
                            selectedActivities, { a -> selectedActivities = if (a in selectedActivities) selectedActivities - a else selectedActivities + a },
                            selectedMissedActivities, { ma -> selectedMissedActivities = if (ma in selectedMissedActivities) selectedMissedActivities - ma else selectedMissedActivities + ma },
                            additionalNotes, { additionalNotes = it },
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

            // Bottom navigation
            if (currentPage != AiPage.STORY && currentPage != AiPage.PROCESSING && currentPage != AiPage.RESULTS && currentPage != AiPage.COMPANIONS) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    if (currentPage == AiPage.Q1) {
                        TextButton(onClick = { currentPage = AiPage.STORY }) { Text("Back", color = AppTheme.SubtleTextColor) }
                    } else {
                        TextButton(onClick = { val prev = AiPage.entries.getOrNull(currentPage.ordinal - 1); if (prev != null) currentPage = prev }) { Text("Back", color = AppTheme.SubtleTextColor) }
                    }
                    if (currentPage == AiPage.Q8) {
                        Button(onClick = { currentPage = AiPage.PROCESSING }, colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink), shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Analyse & Configure")
                        }
                    } else {
                        Button(onClick = { val next = AiPage.entries.getOrNull(currentPage.ordinal + 1); if (next != null) currentPage = next }, colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple), shape = RoundedCornerShape(12.dp)) {
                            Text("Next"); Spacer(Modifier.width(4.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            if (currentPage == AiPage.STORY) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onSkip) { Text("Skip Setup", color = AppTheme.SubtleTextColor) }
                    if (storyText.isNotBlank() && !storyLoading) {
                        Button(onClick = { parseStory() }, colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink), shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Find matches & continue")
                        }
                    } else if (!storyLoading) {
                        Button(onClick = { currentPage = AiPage.Q1 }, colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple), shape = RoundedCornerShape(12.dp)) {
                            Text("Skip to questions"); Spacer(Modifier.width(4.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            if (currentPage == AiPage.PROCESSING && !isProcessing && aiError != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { currentPage = AiPage.Q8 }) { Text("Back", color = AppTheme.SubtleTextColor) }
                    TextButton(onClick = onSkip) { Text("Skip Setup", color = AppTheme.SubtleTextColor) }
                }
            }
        }
    }
}