package com.migraineme

/**
 * DeterministicMapper — Maps questionnaire answers to trigger/prodrome
 * severities AND personalized thresholds. Pure logic, no network, fully testable.
 *
 * Flow:
 *   1. Template defaults define CENTER and DELTA for each paired metric
 *   2. Demographics shift the CENTER (gender × age)
 *   3. Questionnaire answers override specific centers
 *   4. Certainty multipliers tighten/loosen the DELTA (sensitivity)
 *   5. Thresholds: low = center - delta, high = center + delta
 *   6. Certainty → severity (Every time→HIGH, Often→MILD, Sometimes→LOW)
 *
 * Band-based threshold generation guarantees low < high always.
 */
object DeterministicMapper {

    // ═════════════════════════════════════════════════════════════════════
    // Certainty Scale
    // ═════════════════════════════════════════════════════════════════════

    enum class Certainty { EVERY_TIME, OFTEN, SOMETIMES, RARELY, NO }

    fun certaintyToSeverity(c: Certainty): String = when (c) {
        Certainty.EVERY_TIME -> "HIGH"
        Certainty.OFTEN      -> "MILD"
        Certainty.SOMETIMES  -> "LOW"
        Certainty.RARELY     -> "LOW"
        Certainty.NO         -> "NONE"
    }

    fun downgrade(c: Certainty): Certainty = when (c) {
        Certainty.EVERY_TIME -> Certainty.OFTEN
        Certainty.OFTEN      -> Certainty.SOMETIMES
        Certainty.SOMETIMES  -> Certainty.RARELY
        Certainty.RARELY     -> Certainty.NO
        Certainty.NO         -> Certainty.NO
    }

    // ═════════════════════════════════════════════════════════════════════
    // Certainty → Delta Multiplier (sensitivity)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Controls how wide the band is around "normal".
     * EVERY_TIME → 0.60 (narrow band = fires sooner, more sensitive)
     * NO         → 1.40 (wide band = fires later, less sensitive)
     *
     * This multiplier is applied to the delta (half-range), NOT to the
     * threshold itself. This prevents threshold crossing.
     */
    fun sensitivityDeltaMultiplier(c: Certainty): Double = when (c) {
        Certainty.EVERY_TIME -> 0.60
        Certainty.OFTEN      -> 0.80
        Certainty.SOMETIMES  -> 1.00
        Certainty.RARELY     -> 1.20
        Certainty.NO         -> 1.40
    }

    /** Exposure triggers: 1=fires at low+, 2=medium+, 3=high only */
    fun exposureThreshold(c: Certainty): Int = when (c) {
        Certainty.EVERY_TIME -> 1; Certainty.OFTEN -> 1
        Certainty.SOMETIMES  -> 2; Certainty.RARELY -> 3; Certainty.NO -> 4
    }

    // ═════════════════════════════════════════════════════════════════════
    // TEMPLATE DEFAULTS — exact values from trigger_templates DB
    // These define the DEFAULT band endpoints.
    // Center = (high + low) / 2, Delta = (high - low) / 2
    // ═════════════════════════════════════════════════════════════════════

    private val TEMPLATE_DEFAULTS = mapOf(
        // Body
        "Blood glucose high" to 180.0, "Blood glucose low" to 70.0,
        "Blood pressure high" to 140.0, "Blood pressure low" to 90.0,
        "Body fat high" to 35.0, "Body fat low" to 8.0,
        "High HR zones high" to 120.0, "High HR zones low" to 5.0,
        "Recovery high" to 85.0, "Recovery low" to 33.0,
        "Steps high" to 25000.0, "Steps low" to 3000.0,
        "Stress high" to 75.0, "Stress low" to 10.0,
        "Weight high" to 120.0, "Weight low" to 45.0,
        // Cognitive
        "Late screen time high" to 2.0, "Late screen time low" to 0.0,
        "Noise high" to 75.0, "Noise low" to 30.0,
        "Screen time high" to 8.0, "Screen time low" to 0.5,
        // Diet
        "Alcohol exposure high" to 1.0, "Alcohol exposure low" to 0.0,
        "Biotin high" to 100.0, "Biotin low" to 10.0,
        "Caffeine high" to 400.0, "Caffeine low" to 50.0,
        "Calcium high" to 1500.0, "Calcium low" to 300.0,
        "Calories high" to 3000.0, "Calories low" to 1200.0,
        "Carbs high" to 400.0, "Carbs low" to 100.0,
        "Cholesterol high" to 400.0, "Cholesterol low" to 100.0,
        "Copper high" to 2.0, "Copper low" to 0.3,
        "Fat high" to 120.0, "Fat low" to 30.0,
        "Fibre high" to 50.0, "Fibre low" to 10.0,
        "Folate high" to 600.0, "Folate low" to 100.0,
        "Gluten exposure high" to 1.0, "Gluten exposure low" to 0.0,
        "Iron high" to 25.0, "Iron low" to 5.0,
        "Magnesium high" to 500.0, "Magnesium low" to 100.0,
        "Manganese high" to 5.0, "Manganese low" to 0.5,
        "Niacin high" to 30.0, "Niacin low" to 5.0,
        "Pantothenic acid high" to 10.0, "Pantothenic acid low" to 1.0,
        "Phosphorus high" to 1500.0, "Phosphorus low" to 300.0,
        "Potassium high" to 5000.0, "Potassium low" to 1000.0,
        "Protein high" to 150.0, "Protein low" to 30.0,
        "Riboflavin high" to 3.0, "Riboflavin low" to 0.3,
        "Saturated fat high" to 25.0, "Saturated fat low" to 5.0,
        "Selenium high" to 100.0, "Selenium low" to 20.0,
        "Sodium high" to 3000.0, "Sodium low" to 500.0,
        "Sugar high" to 80.0, "Sugar low" to 10.0,
        "Thiamin high" to 3.0, "Thiamin low" to 0.3,
        "Trans fat high" to 3.0, "Trans fat low" to 0.0,
        "Tyramine exposure high" to 1.0, "Tyramine exposure low" to 0.0,
        "Unsaturated fat high" to 80.0, "Unsaturated fat low" to 10.0,
        "Vitamin A high" to 1500.0, "Vitamin A low" to 200.0,
        "Vitamin B12 high" to 5.0, "Vitamin B12 low" to 0.5,
        "Vitamin B6 high" to 5.0, "Vitamin B6 low" to 0.5,
        "Vitamin C high" to 200.0, "Vitamin C low" to 20.0,
        "Vitamin D high" to 50.0, "Vitamin D low" to 5.0,
        "Vitamin E high" to 20.0, "Vitamin E low" to 3.0,
        "Vitamin K high" to 200.0, "Vitamin K low" to 20.0,
        "Zinc high" to 15.0, "Zinc low" to 3.0,
        // Environment
        "Altitude change high" to 500.0, "Altitude change low" to 0.0,
        "Altitude high" to 2500.0, "Altitude low" to 0.0,
        "Humidity high" to 80.0, "Humidity low" to 20.0,
        "Pressure high" to 1030.0, "Pressure low" to 990.0,
        "Temperature high" to 35.0, "Temperature low" to 5.0,
        "UV index high" to 8.0, "UV index low" to 1.0,
        "Wind speed high" to 15.0, "Wind speed low" to 1.0,
        // Sleep
        "Bedtime early" to 21.0, "Bedtime late" to 1.0,
        "Deep sleep high" to 3.0, "Deep sleep low" to 0.5,
        "Light sleep high" to 5.0, "Light sleep low" to 1.0,
        "REM sleep high" to 3.5, "REM sleep low" to 0.5,
        "Sleep disturbances high" to 5.0, "Sleep disturbances low" to 1.0,
        "Sleep duration high" to 10.0, "Sleep duration low" to 6.0,
        "Sleep efficiency high" to 98.0, "Sleep efficiency low" to 80.0,
        "Sleep score high" to 95.0, "Sleep score low" to 60.0,
        "Wake time early" to 5.0, "Wake time late" to 10.0,
    )

    /** Prodrome template defaults from prodrome_templates DB */
    private val PRODROME_DEFAULTS = mapOf(
        // Physical
        "HRV high" to 150.0, "HRV low" to 20.0,
        "Resting HR high" to 100.0, "Resting HR low" to 40.0,
        "Resp rate high" to 22.0, "Resp rate low" to 10.0,
        "Skin temp high" to 38.0, "Skin temp low" to 35.0,
        "SpO2 high" to 100.0, "SpO2 low" to 95.0,
        // Sensory (phone)
        "Brightness high" to 80.0, "Brightness low" to 20.0,
        "Dark mode high" to 14.0, "Dark mode low" to 2.0,
        "Phone unlocks high" to 150.0, "Phone unlocks low" to 10.0,
        "Volume high" to 80.0, "Volume low" to 20.0,
    )

    // ═════════════════════════════════════════════════════════════════════
    // Band Calculation — Center + Delta
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Given the current (possibly personalized) defaults map, extract the
     * center and delta for a metric base name.
     *
     * E.g. for baseName="Pressure":
     *   highDefault = d["Pressure high"] = 1030
     *   lowDefault  = d["Pressure low"]  = 990
     *   center = 1010, delta = 20
     */
    private data class Band(val center: Double, val delta: Double)

    private fun bandFor(baseName: String, d: Map<String, Double>): Band? {
        val hi = d["$baseName high"] ?: return null
        val lo = d["$baseName low"] ?: return null
        val center = (hi + lo) / 2.0
        val delta = (hi - lo) / 2.0
        return Band(center, delta.coerceAtLeast(0.01)) // delta must be positive
    }

    /**
     * Compute threshold for the "high" direction using band approach.
     * high_threshold = center + delta * sensitivityMultiplier
     *
     * More certain → smaller multiplier → tighter band → fires sooner
     */
    private fun bandHigh(baseName: String, d: Map<String, Double>, cert: Certainty): Double? {
        val band = bandFor(baseName, d) ?: return d["$baseName high"]
        return band.center + band.delta * sensitivityDeltaMultiplier(cert)
    }

    /**
     * Compute threshold for the "low" direction using band approach.
     * low_threshold = center - delta * sensitivityMultiplier
     *
     * More certain → smaller multiplier → tighter band → fires sooner
     */
    private fun bandLow(baseName: String, d: Map<String, Double>, cert: Certainty): Double? {
        val band = bandFor(baseName, d) ?: return d["$baseName low"]
        return band.center - band.delta * sensitivityDeltaMultiplier(cert)
    }

    /**
     * For metrics that only have a "high" trigger (no paired low), or only
     * a "low" trigger, we can still use band-based if a pair exists.
     * Falls back to the old default value if no pair found.
     */
    private fun singleHigh(label: String, d: Map<String, Double>, cert: Certainty): Double? {
        val baseName = label.removeSuffix(" high")
        return bandHigh(baseName, d, cert)
    }

    private fun singleLow(label: String, d: Map<String, Double>, cert: Certainty): Double? {
        val baseName = label.removeSuffix(" low")
        return bandLow(baseName, d, cert)
    }

    // ═════════════════════════════════════════════════════════════════════
    // DEMOGRAPHIC PERSONALIZATION — shifts CENTER
    // ═════════════════════════════════════════════════════════════════════

    private fun personalizeDefaults(gender: String?, ageRange: String?): MutableMap<String, Double> {
        val d = TEMPLATE_DEFAULTS.toMutableMap()

        // ── Gender — shifts the center of what's "normal" ──
        when (gender) {
            "Female" -> {
                // Metabolism & body composition
                d["Calories high"] = 2200.0
                d["Calories low"] = 1400.0
                d["Protein high"] = 110.0
                d["Fat high"] = 90.0
                d["Saturated fat high"] = 20.0
                d["Carbs high"] = 320.0
                d["Sodium high"] = 2300.0
                // Body
                d["Weight high"] = 90.0
                d["Weight low"] = 48.0
                d["Body fat low"] = 18.0
                d["Blood pressure high"] = 130.0
                d["Steps high"] = 20000.0
                d["High HR zones high"] = 100.0
                // Micronutrients
                d["Iron low"] = 8.0
                d["Folate low"] = 150.0
                d["Calcium low"] = 400.0
                // Caffeine
                d["Caffeine high"] = 300.0
            }
            "Male" -> {
                d["Body fat high"] = 28.0
                d["Iron high"] = 20.0
            }
        }

        // ── Age — shifts the center of what's "normal" ──
        when (ageRange) {
            "18-25" -> {
                adjustBothEndpoints(d, "Calories", highMul = 1.10, lowMul = 1.05)
                adjustBothEndpoints(d, "Steps", highMul = 1.10)
                adjustBothEndpoints(d, "Protein", highMul = 1.05)
                adjustBothEndpoints(d, "Blood pressure", highMul = 0.93, lowMul = 0.95)
                d["Sleep duration low"] = 6.5
                d["Bedtime late"] = 2.0
                d["Wake time late"] = 11.0
                adjustBothEndpoints(d, "Caffeine", highMul = 0.90)
            }
            "26-35" -> {
                adjustBothEndpoints(d, "Calories", highMul = 1.03)
                adjustBothEndpoints(d, "Steps", highMul = 1.05)
            }
            "46-55" -> {
                adjustBothEndpoints(d, "Calories", highMul = 0.93)
                adjustBothEndpoints(d, "Steps", highMul = 0.85)
                adjustBothEndpoints(d, "Blood pressure", highMul = 1.04)
                adjustBothEndpoints(d, "Caffeine", highMul = 0.85)
                d["Recovery low"] = 30.0
                d["Sleep duration low"] = 5.5
                d["Bedtime late"] = 0.5
                if (gender == "Female") {
                    d["Iron low"] = 5.0
                    d["Folate low"] = 100.0
                }
            }
            "56+" -> {
                adjustBothEndpoints(d, "Calories", highMul = 0.85, lowMul = 0.95)
                adjustBothEndpoints(d, "Steps", highMul = 0.70)
                adjustBothEndpoints(d, "Blood pressure", highMul = 1.07)
                adjustBothEndpoints(d, "Caffeine", highMul = 0.75)
                d["Recovery low"] = 28.0
                d["Sleep duration low"] = 5.5
                d["Sleep duration high"] = 9.0
                d["Bedtime late"] = 0.0
                d["Bedtime early"] = 20.0
                d["Wake time early"] = 4.5
                adjustBothEndpoints(d, "High HR zones", highMul = 0.75)
                if (gender == "Female") {
                    d["Iron low"] = 5.0
                    d["Folate low"] = 100.0
                    d["Calcium low"] = 500.0
                    d["Vitamin D low"] = 10.0
                }
            }
        }

        return d
    }

    /**
     * Helper: when demographics shift a metric, we adjust the band endpoints
     * symmetrically so the CENTER moves but the relative range stays coherent.
     *
     * If only highMul is given, we shift the high endpoint.
     * If only lowMul is given, we shift the low endpoint.
     * Both can be provided.
     *
     * This keeps the defaults map in the same format (keyed by "X high"/"X low")
     * while ensuring the demographic adjustment shifts center, not just one side.
     */
    private fun adjustBothEndpoints(d: MutableMap<String, Double>, baseName: String, highMul: Double? = null, lowMul: Double? = null) {
        highMul?.let { d["$baseName high"] = d["$baseName high"]!! * it }
        lowMul?.let { d["$baseName low"] = d["$baseName low"]!! * it }
    }

    /** Adjusts prodrome template defaults for demographics — shifts CENTER */
    private fun personalizeProdromeDefaults(gender: String?, ageRange: String?): MutableMap<String, Double> {
        val pd = PRODROME_DEFAULTS.toMutableMap()

        // ── Gender — HRV and resting HR differ ──
        when (gender) {
            "Female" -> {
                pd["HRV low"] = 22.0           // slightly higher baseline HRV
                pd["Resting HR high"] = 95.0   // slightly lower ceiling
            }
            "Male" -> {
                pd["HRV low"] = 18.0           // slightly lower baseline
                pd["Resting HR low"] = 38.0    // athletes can be lower
            }
        }

        // ── Age — HRV decreases significantly, HR increases ──
        when (ageRange) {
            "18-25" -> {
                pd["HRV high"] = 180.0         // young adults have high HRV
                pd["HRV low"] = pd["HRV low"]!! * 1.20   // higher floor
                pd["Resting HR high"] = 95.0   // slightly lower
                pd["Resting HR low"] = 45.0    // higher floor
            }
            "26-35" -> {
                pd["HRV high"] = 160.0
                pd["HRV low"] = pd["HRV low"]!! * 1.10
            }
            "46-55" -> {
                pd["HRV high"] = 120.0         // HRV declines
                pd["HRV low"] = pd["HRV low"]!! * 0.85
                pd["Resting HR high"] = 95.0
                pd["SpO2 low"] = 94.0          // slightly lower OK
            }
            "56+" -> {
                pd["HRV high"] = 100.0         // significant decline
                pd["HRV low"] = pd["HRV low"]!! * 0.75
                pd["Resting HR high"] = 90.0   // lower ceiling
                pd["SpO2 low"] = 93.0          // age-adjusted
                pd["Resp rate high"] = 20.0    // slightly tighter
            }
        }

        return pd
    }

    // ═════════════════════════════════════════════════════════════════════
    // Data Classes
    // ═════════════════════════════════════════════════════════════════════

    data class QuestionnaireAnswers(
        // Page 1 — Demographics & Migraine Profile
        val gender: String? = null,
        val ageRange: String? = null,
        val frequency: String? = null,
        val duration: String? = null,
        val experience: String? = null,
        val trajectory: String? = null,
        val warningSignsBefore: String? = null,
        val triggerDelay: String? = null,
        val dailyRoutine: String? = null,
        val seasonalPattern: String? = null,
        // Page 2 — Sleep
        val sleepHours: String? = null,
        val sleepQuality: String? = null,
        val poorSleepQualityTriggers: Certainty = Certainty.NO,
        val tooLittleSleepTriggers: Certainty = Certainty.NO,
        val oversleepTriggers: Certainty = Certainty.NO,
        val sleepIssues: Set<String> = emptySet(),
        // Page 3 — Stress & Screen
        val stressLevel: String? = null,
        val stressChangeTriggers: Certainty = Certainty.NO,
        val emotionalPatterns: Map<String, Certainty> = emptyMap(),
        val screenTimeDaily: String? = null,
        val screenTimeTriggers: Certainty = Certainty.NO,
        val lateScreenTriggers: Certainty = Certainty.NO,
        // Page 4 — Diet
        val caffeineIntake: String? = null,
        val caffeineDirection: String? = null,
        val caffeineCertainty: Certainty = Certainty.NO,
        val alcoholFrequency: String? = null,
        val alcoholTriggers: Certainty = Certainty.NO,
        val specificDrinks: Set<String> = emptySet(),
        val tyramineFoods: Map<String, Certainty> = emptyMap(),
        val glutenSensitivity: String? = null,
        val glutenTriggers: Certainty = Certainty.NO,
        val eatingPatterns: Map<String, Certainty> = emptyMap(),
        val waterIntake: String? = null,
        val tracksNutrition: String? = null,
        // Page 5 — Weather, Environment, Physical
        val weatherTriggers: Certainty = Certainty.NO,
        val specificWeather: Map<String, Certainty> = emptyMap(),
        val environmentSensitivities: Map<String, Certainty> = emptyMap(),
        val physicalFactors: Map<String, Certainty> = emptyMap(),
        // Page 6 — Exercise & Hormones
        val exerciseFrequency: String? = null,
        val exerciseTriggers: Certainty = Certainty.NO,
        val exercisePattern: Set<String> = emptySet(),
        val tracksCycle: String? = null,
        val cyclePatterns: Map<String, Certainty> = emptyMap(),
        val usesContraception: String? = null,
        val contraceptionEffect: String? = null,
        // Page 7 — Prodromes
        val physicalProdromes: Map<String, Certainty> = emptyMap(),
        val moodProdromes: Map<String, Certainty> = emptyMap(),
        val sensoryProdromes: Map<String, Certainty> = emptyMap(),
        // Page 8
        val selectedMigraineTypes: Set<String> = emptySet(),
        val selectedSymptoms: Set<String> = emptySet(),
        val selectedMedicines: Set<String> = emptySet(),
        val selectedReliefs: Set<String> = emptySet(),
        val selectedActivities: Set<String> = emptySet(),
        val selectedMissedActivities: Set<String> = emptySet(),
        val freeText: String? = null,
    )

    data class TriggerSetting(
        val label: String,
        val severity: String,
        val threshold: Double? = null,
        val exposureLevel: Int? = null,
        val favorite: Boolean = false,
    )

    data class ProdromeSetting(val label: String, val severity: String, val threshold: Double? = null, val favorite: Boolean = false)

    data class MappingResult(
        val triggers: Map<String, TriggerSetting>,
        val prodromes: Map<String, ProdromeSetting>,
        val favorites: Favorites,
        val profileContext: ProfileContext,
    )

    data class Favorites(
        val triggers: List<String>, val prodromes: List<String>,
        val symptoms: List<String>, val medicines: List<String>,
        val reliefs: List<String>, val activities: List<String>,
        val missedActivities: List<String>,
    )

    data class ProfileContext(
        val gender: String?, val ageRange: String?,
        val frequency: String?, val duration: String?,
        val experience: String?, val trajectory: String?,
        val warningSignsBefore: String?, val triggerDelay: String?,
        val dailyRoutine: String?, val seasonalPattern: String?,
    )

    // ═════════════════════════════════════════════════════════════════════
    // Questionnaire → Override baselines (shifts CENTER)
    // ═════════════════════════════════════════════════════════════════════

    private fun sleepDurationCenter(hours: String?): Double? = when (hours) {
        "< 5h" -> 4.5; "5-6h" -> 5.5; "6-7h" -> 6.5
        "7-8h" -> 7.5; "8-9h" -> 8.5; "9+h" -> 9.5; else -> null
    }
    private fun caffeineMgCenter(intake: String?): Double? = when (intake) {
        "None" -> 30.0; "1-2 cups" -> 175.0; "3-4 cups" -> 350.0; "5+ cups" -> 500.0; else -> null
    }
    private fun screenTimeHoursCenter(daily: String?): Double? = when (daily) {
        "< 2h" -> 1.5; "2-4h" -> 3.0; "4-8h" -> 6.0; "8-12h" -> 10.0; "12h+" -> 13.0; else -> null
    }
    private fun stressIndexCenter(level: String?): Double? = when (level) {
        "Low" -> 25.0; "Moderate" -> 50.0; "High" -> 70.0; "Very high" -> 85.0; else -> null
    }
    private fun stepsCenter(frequency: String?): Double? = when (frequency) {
        "Daily" -> 12000.0; "Few times/week" -> 8000.0; "Weekly" -> 6000.0
        "Rarely" -> 4000.0; "Never" -> 2000.0; else -> null
    }

    /**
     * Override defaults for a metric pair by shifting the CENTER while
     * preserving the default delta (half-range).
     *
     * E.g. if user sleeps 5-6h (center=5.5), and template has
     * Sleep duration high=10, low=6 → default delta=2.0
     * New: high = 5.5 + 2.0 = 7.5, low = 5.5 - 2.0 = 3.5
     */
    private fun overrideCenterPreserveDelta(d: MutableMap<String, Double>, baseName: String, newCenter: Double) {
        val hi = d["$baseName high"] ?: return
        val lo = d["$baseName low"] ?: return
        val delta = (hi - lo) / 2.0
        d["$baseName high"] = newCenter + delta
        d["$baseName low"] = newCenter - delta
    }

    /**
     * For metrics where the user-reported value IS the relevant threshold
     * (e.g. caffeine intake — the threshold is near their normal intake),
     * we set both endpoints relative to that value with a reasonable range.
     */
    private fun overrideCenterWithRange(d: MutableMap<String, Double>, baseName: String, newCenter: Double, halfRange: Double) {
        d["$baseName high"] = newCenter + halfRange
        d["$baseName low"] = newCenter - halfRange
    }

    // ═════════════════════════════════════════════════════════════════════
    // MAIN ENTRY POINT
    // ═════════════════════════════════════════════════════════════════════

    fun map(answers: QuestionnaireAnswers, enabledMetrics: Map<String, Boolean> = emptyMap()): MappingResult {
        // 1) Personalize defaults for demographics (shifts CENTER)
        val d = personalizeDefaults(answers.gender, answers.ageRange)
        val pd = personalizeProdromeDefaults(answers.gender, answers.ageRange)

        // 2) Override with user-reported specifics (shifts CENTER)
        sleepDurationCenter(answers.sleepHours)?.let {
            overrideCenterPreserveDelta(d, "Sleep duration", it)
        }
        caffeineMgCenter(answers.caffeineIntake)?.let {
            // Caffeine: center at their intake, ±30% range
            overrideCenterWithRange(d, "Caffeine", it, it * 0.30)
        }
        screenTimeHoursCenter(answers.screenTimeDaily)?.let {
            // Screen time: center at their usage, ±2h range
            overrideCenterWithRange(d, "Screen time", it, 2.0)
        }
        stressIndexCenter(answers.stressLevel)?.let {
            // Stress: center at their level, ±20 range
            overrideCenterWithRange(d, "Stress", it, 20.0)
        }
        stepsCenter(answers.exerciseFrequency)?.let {
            // Steps: center at their typical, ±40% range
            overrideCenterWithRange(d, "Steps", it, it * 0.40)
        }

        // 3) Map everything
        val triggers = mutableMapOf<String, TriggerSetting>()
        val prodromes = mutableMapOf<String, ProdromeSetting>()

        mapSleep(answers, d, triggers)
        mapStressAndScreen(answers, d, triggers)
        mapDiet(answers, d, triggers)
        mapEnvironment(answers, d, triggers)
        mapPhysical(answers, triggers)
        mapExercise(answers, d, triggers)
        mapHormones(answers, triggers)
        mapConnectedMetrics(enabledMetrics, d, pd, triggers, prodromes)
        mapProdromes(answers, pd, prodromes)

        return MappingResult(
            triggers, prodromes, buildFavorites(answers, triggers, prodromes),
            ProfileContext(answers.gender, answers.ageRange, answers.frequency, answers.duration,
                answers.experience, answers.trajectory, answers.warningSignsBefore,
                answers.triggerDelay, answers.dailyRoutine, answers.seasonalPattern),
        )
    }

    // ═════════════════════════════════════════════════════════════════════
    // Threshold helpers: high/low/manual — NOW BAND-BASED
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Create a "high" trigger with band-based threshold.
     * The label is expected to end in " high" (e.g. "Pressure high").
     */
    private fun trigHigh(label: String, sev: String, d: Map<String, Double>, cert: Certainty, fav: Boolean = false) =
        TriggerSetting(label, sev, singleHigh(label, d, cert), favorite = fav)

    /**
     * Create a "low" trigger with band-based threshold.
     * The label is expected to end in " low" (e.g. "Pressure low").
     */
    private fun trigLow(label: String, sev: String, d: Map<String, Double>, cert: Certainty, fav: Boolean = false) =
        TriggerSetting(label, sev, singleLow(label, d, cert), favorite = fav)

    private fun trigManual(label: String, sev: String, fav: Boolean = false) =
        TriggerSetting(label, sev, favorite = fav)

    /**
     * Create a "high" prodrome with band-based threshold.
     */
    private fun prodHigh(label: String, sev: String, pd: Map<String, Double>, cert: Certainty, fav: Boolean = false) =
        ProdromeSetting(label, sev, singleHigh(label, pd, cert), favorite = fav)

    /**
     * Create a "low" prodrome with band-based threshold.
     */
    private fun prodLow(label: String, sev: String, pd: Map<String, Double>, cert: Certainty, fav: Boolean = false) =
        ProdromeSetting(label, sev, singleLow(label, pd, cert), favorite = fav)

    private fun prodManual(label: String, sev: String, fav: Boolean = false) =
        ProdromeSetting(label, sev, favorite = fav)

    // ═════════════════════════════════════════════════════════════════════
    // SLEEP (Page 2)
    // ═════════════════════════════════════════════════════════════════════

    private fun mapSleep(a: QuestionnaireAnswers, d: Map<String, Double>, out: MutableMap<String, TriggerSetting>) {
        val qualityCert = a.poorSleepQualityTriggers
        if (qualityCert != Certainty.NO) {
            val sev = certaintyToSeverity(qualityCert)
            out["Sleep score low"] = trigLow("Sleep score low", sev, d, qualityCert, fav = true)
            out["Sleep efficiency low"] = trigLow("Sleep efficiency low", sev, d, qualityCert)
            out["Sleep disturbances high"] = trigHigh("Sleep disturbances high", sev, d, qualityCert)
            out["Deep sleep low"] = trigLow("Deep sleep low", sev, d, qualityCert)
            out["REM sleep low"] = trigLow("REM sleep low", sev, d, qualityCert)
            out["Light sleep high"] = trigHigh("Light sleep high", sev, d, qualityCert)
        }

        val littleCert = a.tooLittleSleepTriggers
        if (littleCert != Certainty.NO) {
            val sev = certaintyToSeverity(littleCert)
            out["Sleep duration low"] = trigLow("Sleep duration low", sev, d, littleCert, fav = true)
        }

        if (a.oversleepTriggers != Certainty.NO) {
            val osev = certaintyToSeverity(a.oversleepTriggers)
            val oc = a.oversleepTriggers
            out["Sleep duration high"] = trigHigh("Sleep duration high", osev, d, oc, fav = true)
            out["Sleep score high"] = trigHigh("Sleep score high", osev, d, oc)
            out["Sleep efficiency high"] = trigHigh("Sleep efficiency high", osev, d, oc)
            out["Sleep disturbances low"] = trigLow("Sleep disturbances low", osev, d, oc)
            out["Deep sleep high"] = trigHigh("Deep sleep high", osev, d, oc)
            out["REM sleep high"] = trigHigh("REM sleep high", osev, d, oc)
        }

        val bestSleepCert = listOf(qualityCert, littleCert).maxByOrNull { it.ordinal } ?: Certainty.NO
        if (bestSleepCert != Certainty.NO) {
            val irregCert = if (a.sleepQuality == "Varies a lot") bestSleepCert else downgrade(bestSleepCert)
            val irregSev = certaintyToSeverity(irregCert)
            out["Bedtime late"] = trigHigh("Bedtime late", irregSev, d, irregCert)
            out["Bedtime early"] = trigLow("Bedtime early", irregSev, d, irregCert)
            out["Wake time late"] = trigHigh("Wake time late", irregSev, d, irregCert)
            out["Wake time early"] = trigLow("Wake time early", irregSev, d, irregCert)
        }

        if ("Irregular schedule" in a.sleepIssues && bestSleepCert != Certainty.NO) {
            val sameLevel = certaintyToSeverity(bestSleepCert)
            for (l in listOf("Bedtime late", "Bedtime early", "Wake time late", "Wake time early")) {
                out[l] = out[l]?.copy(severity = sameLevel) ?: TriggerSetting(l, sameLevel, d[l])
            }
        }
        if ("Sleep apnea" in a.sleepIssues) out["Sleep apnea"] = trigManual("Sleep apnea", certaintyToSeverity(bestSleepCert))
        if ("Jet lag" in a.sleepIssues) out["Jet lag"] = trigManual("Jet lag", "LOW")
    }

    // ═════════════════════════════════════════════════════════════════════
    // STRESS & SCREEN (Page 3)
    // ═════════════════════════════════════════════════════════════════════

    private fun mapStressAndScreen(a: QuestionnaireAnswers, d: Map<String, Double>, out: MutableMap<String, TriggerSetting>) {
        for ((pattern, cert) in a.emotionalPatterns) {
            if (cert == Certainty.NO) continue
            val sev = certaintyToSeverity(cert)
            when (pattern) {
                "Spike in stress" -> { out["Stress"] = trigManual("Stress", sev); out["Stress high"] = trigHigh("Stress high", sev, d, cert) }
                "Anxiety"    -> out["Anxiety"] = trigManual("Anxiety", sev)
                "Anger"      -> out["Anger"] = trigManual("Anger", sev)
                "Let-down"   -> { out["Let-down"] = trigManual("Let-down", sev); out["Stress low"] = trigLow("Stress low", sev, d, cert) }
                "Feeling low" -> out["Depression"] = trigManual("Depression", sev)
            }
        }
        if (a.screenTimeTriggers != Certainty.NO) {
            val sev = certaintyToSeverity(a.screenTimeTriggers)
            out["Screen time high"] = trigHigh("Screen time high", sev, d, a.screenTimeTriggers)
            out["Computer/screen"] = trigManual("Computer/screen", sev)
        }
        if (a.lateScreenTriggers != Certainty.NO) {
            out["Late screen time high"] = trigHigh("Late screen time high", certaintyToSeverity(a.lateScreenTriggers), d, a.lateScreenTriggers)
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // DIET (Page 4)
    // ═════════════════════════════════════════════════════════════════════

    private fun mapDiet(a: QuestionnaireAnswers, d: Map<String, Double>, out: MutableMap<String, TriggerSetting>) {
        if (a.caffeineCertainty != Certainty.NO && a.caffeineDirection != "No") {
            val sev = certaintyToSeverity(a.caffeineCertainty)
            val cc = a.caffeineCertainty
            when (a.caffeineDirection) {
                "Too much" -> out["Caffeine high"] = trigHigh("Caffeine high", sev, d, cc, fav = true)
                "Missing"  -> out["Caffeine low"] = trigLow("Caffeine low", sev, d, cc, fav = true)
                "Both" -> {
                    out["Caffeine high"] = trigHigh("Caffeine high", sev, d, cc, fav = true)
                    out["Caffeine low"] = trigLow("Caffeine low", sev, d, cc, fav = true)
                }
                "Not sure" -> {
                    out["Caffeine high"] = TriggerSetting("Caffeine high", "LOW", d["Caffeine high"])
                    out["Caffeine low"] = TriggerSetting("Caffeine low", "LOW", d["Caffeine low"])
                }
            }
        }

        if (a.alcoholTriggers != Certainty.NO) {
            out["Alcohol exposure high"] = TriggerSetting("Alcohol exposure high",
                certaintyToSeverity(a.alcoholTriggers), exposureLevel = exposureThreshold(a.alcoholTriggers), favorite = true)
        }

        val maxTyrCert = a.tyramineFoods.values.maxByOrNull { it.ordinal }
        if (maxTyrCert != null && maxTyrCert != Certainty.NO) {
            val rwFloor = if ("Red wine" in a.specificDrinks) Certainty.SOMETIMES else Certainty.NO
            val fc = if (rwFloor.ordinal < maxTyrCert.ordinal) rwFloor else maxTyrCert
            out["Tyramine exposure high"] = TriggerSetting("Tyramine exposure high",
                certaintyToSeverity(fc), exposureLevel = exposureThreshold(fc), favorite = true)
        } else if ("Red wine" in a.specificDrinks) {
            out["Tyramine exposure high"] = TriggerSetting("Tyramine exposure high", "LOW", exposureLevel = 2)
        }

        if (a.glutenTriggers != Certainty.NO) {
            out["Gluten exposure high"] = TriggerSetting("Gluten exposure high",
                certaintyToSeverity(a.glutenTriggers), exposureLevel = exposureThreshold(a.glutenTriggers), favorite = true)
        }

        for ((pattern, cert) in a.eatingPatterns) {
            if (cert == Certainty.NO) continue
            val sev = certaintyToSeverity(cert)
            when (pattern) {
                "Skipping meals" -> { out["Skipped meals"] = trigManual("Skipped meals", sev, fav = true); out["Calories low"] = trigLow("Calories low", sev, d, cert) }
                "Sugar"       -> out["Sugar high"] = trigHigh("Sugar high", sev, d, cert)
                "Salty food"  -> out["Sodium high"] = trigHigh("Sodium high", sev, d, cert)
                "Overeating"  -> out["Calories high"] = trigHigh("Calories high", sev, d, cert)
                "Dehydration" -> out["Dehydration"] = trigManual("Dehydration", sev, fav = true)
            }
        }

        when (a.tracksNutrition) {
            "Yes, regularly" -> {
                out["Magnesium low"] = out.getOrDefault("Magnesium low", TriggerSetting("Magnesium low", "MILD", d["Magnesium low"]))
                out["Riboflavin low"] = TriggerSetting("Riboflavin low", "MILD", d["Riboflavin low"])
                out["Vitamin D low"] = TriggerSetting("Vitamin D low", "MILD", d["Vitamin D low"])
                out["Iron low"] = TriggerSetting("Iron low", "MILD", d["Iron low"])
                val allNutrition = listOf(
                    "Protein high", "Protein low", "Carbs high", "Carbs low", "Fat high", "Fat low",
                    "Fibre high", "Fibre low", "Cholesterol high", "Cholesterol low",
                    "Saturated fat high", "Saturated fat low", "Unsaturated fat high", "Unsaturated fat low",
                    "Trans fat high", "Trans fat low", "Calcium high", "Calcium low",
                    "Potassium high", "Potassium low", "Zinc high", "Zinc low",
                    "Selenium high", "Selenium low", "Phosphorus high", "Phosphorus low",
                    "Copper high", "Copper low", "Manganese high", "Manganese low",
                    "Biotin high", "Biotin low", "Folate high", "Folate low",
                    "Niacin high", "Niacin low", "Pantothenic acid high", "Pantothenic acid low",
                    "Thiamin high", "Thiamin low", "Vitamin A high", "Vitamin A low",
                    "Vitamin B6 high", "Vitamin B6 low", "Vitamin B12 high", "Vitamin B12 low",
                    "Vitamin C high", "Vitamin C low", "Vitamin E high", "Vitamin E low",
                    "Vitamin K high", "Vitamin K low",
                )
                for (label in allNutrition) { if (label !in out) out[label] = TriggerSetting(label, "LOW", d[label]) }
            }
            "Sometimes" -> {
                out["Magnesium low"] = out.getOrDefault("Magnesium low", TriggerSetting("Magnesium low", "LOW", d["Magnesium low"]))
                out["Riboflavin low"] = TriggerSetting("Riboflavin low", "LOW", d["Riboflavin low"])
                out["Vitamin D low"] = TriggerSetting("Vitamin D low", "LOW", d["Vitamin D low"])
                out["Iron low"] = TriggerSetting("Iron low", "LOW", d["Iron low"])
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // WEATHER & ENVIRONMENT (Page 5)
    // ═════════════════════════════════════════════════════════════════════

    private fun mapEnvironment(a: QuestionnaireAnswers, d: Map<String, Double>, out: MutableMap<String, TriggerSetting>) {
        if ("Not sure which" in a.specificWeather) {
            for (l in listOf("Pressure high", "Pressure low", "Temperature high", "Temperature low",
                "Humidity high", "Humidity low", "Wind speed high", "UV index high")) {
                out[l] = TriggerSetting(l, "LOW", d[l])
            }
        } else {
            for ((weather, cert) in a.specificWeather) {
                if (cert == Certainty.NO) continue
                val sev = certaintyToSeverity(cert)
                when (weather) {
                    "Pressure changes" -> {
                        out["Pressure high"] = trigHigh("Pressure high", sev, d, cert, fav = true)
                        out["Pressure low"] = trigLow("Pressure low", sev, d, cert, fav = true)
                    }
                    "Hot weather"   -> out["Temperature high"] = trigHigh("Temperature high", sev, d, cert)
                    "Cold weather"  -> out["Temperature low"] = trigLow("Temperature low", sev, d, cert)
                    "Humidity"      -> out["Humidity high"] = trigHigh("Humidity high", sev, d, cert)
                    "Dry air"       -> out["Humidity low"] = trigLow("Humidity low", sev, d, cert)
                    "Wind"          -> out["Wind speed high"] = trigHigh("Wind speed high", sev, d, cert)
                    "Sunshine"      -> out["UV index high"] = trigHigh("UV index high", sev, d, cert)
                    "Thunderstorms" -> {
                        out["Pressure low"] = trigLow("Pressure low", sev, d, cert, fav = true)
                        out["Humidity high"] = trigHigh("Humidity high", sev, d, cert)
                    }
                }
            }
        }

        for ((env, cert) in a.environmentSensitivities) {
            if (cert == Certainty.NO) continue
            val sev = certaintyToSeverity(cert)
            when (env) {
                "Fluorescent lights" -> out["Fluorescent light"] = trigManual("Fluorescent light", sev, fav = true)
                "Strong smells"      -> out["Strong smell"] = trigManual("Strong smell", sev)
                "Loud noise"         -> out["Noise high"] = trigHigh("Noise high", sev, d, cert)
                "Smoke"              -> out["Smoke"] = trigManual("Smoke", sev)
                "Altitude"           -> {
                    out["Altitude high"] = trigHigh("Altitude high", sev, d, cert)
                    out["Altitude change high"] = trigHigh("Altitude change high", sev, d, cert)
                    out["Altitude low"] = TriggerSetting("Altitude low", "LOW", d["Altitude low"])
                    out["Altitude change low"] = TriggerSetting("Altitude change low", "LOW", d["Altitude change low"])
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // PHYSICAL (Page 5)
    // ═════════════════════════════════════════════════════════════════════

    private fun mapPhysical(a: QuestionnaireAnswers, out: MutableMap<String, TriggerSetting>) {
        for ((factor, cert) in a.physicalFactors) {
            if (cert == Certainty.NO) continue
            val sev = certaintyToSeverity(cert)
            when (factor) {
                "Allergies"         -> out["Allergies"] = trigManual("Allergies", sev)
                "Being ill"         -> out["Illness"] = trigManual("Illness", sev)
                "Low blood sugar"   -> out["Low blood sugar"] = trigManual("Low blood sugar", sev)
                "Medication change" -> out["Medication change"] = trigManual("Medication change", sev)
                "Motion sickness"   -> { out["Motion sickness"] = trigManual("Motion sickness", sev); out["Travel"] = trigManual("Travel", sev) }
                "Tobacco"           -> out["Tobacco"] = trigManual("Tobacco", sev)
                "Sexual activity"   -> out["Sexual activity"] = trigManual("Sexual activity", sev)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // EXERCISE (Page 6)
    // ═════════════════════════════════════════════════════════════════════

    private fun mapExercise(a: QuestionnaireAnswers, d: Map<String, Double>, out: MutableMap<String, TriggerSetting>) {
        if (a.exerciseTriggers == Certainty.NO) return
        val sev = certaintyToSeverity(a.exerciseTriggers)
        if ("Intense exercise" in a.exercisePattern) {
            out["High HR zones high"] = trigHigh("High HR zones high", sev, d, a.exerciseTriggers, fav = true)
            out["Steps high"] = trigHigh("Steps high", sev, d, a.exerciseTriggers)
        }
        if ("When inactive" in a.exercisePattern) {
            out["Steps low"] = trigLow("Steps low", sev, d, a.exerciseTriggers)
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // HORMONES (Page 6)
    // ═════════════════════════════════════════════════════════════════════

    private fun mapHormones(a: QuestionnaireAnswers, out: MutableMap<String, TriggerSetting>) {
        for ((pattern, cert) in a.cyclePatterns) {
            if (cert == Certainty.NO) continue
            val sev = certaintyToSeverity(cert)
            when (pattern) {
                "Around my period"  -> out["Menstruation"] = trigManual("Menstruation", sev, fav = true)
                "Around ovulation"  -> out["Ovulation"] = trigManual("Ovulation", sev)
            }
        }
        when (a.contraceptionEffect) {
            "Made them worse — every time" -> out["Contraceptive"] = trigManual("Contraceptive", "HIGH")
            "Made them worse — sometimes"  -> out["Contraceptive"] = trigManual("Contraceptive", "MILD")
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // CONNECTED METRICS
    // ═════════════════════════════════════════════════════════════════════

    private fun mapConnectedMetrics(em: Map<String, Boolean>, d: Map<String, Double>, pd: Map<String, Double>, t: MutableMap<String, TriggerSetting>, p: MutableMap<String, ProdromeSetting>) {
        fun enT(metric: String, vararg labels: String) { if (em[metric] == true) for (l in labels) if (l !in t) t[l] = TriggerSetting(l, "LOW", d[l]) }
        fun enP(metric: String, vararg labels: String) { if (em[metric] == true) for (l in labels) if (l !in p) p[l] = ProdromeSetting(l, "LOW", pd[l]) }

        enT("recovery_score_daily", "Recovery high", "Recovery low")
        enT("stress_index_daily", "Stress high", "Stress low")
        enT("steps_daily", "Steps high", "Steps low")
        enT("time_in_high_hr_zones_daily", "High HR zones high", "High HR zones low")
        enT("blood_pressure_daily", "Blood pressure high", "Blood pressure low")
        enT("blood_glucose_daily", "Blood glucose high", "Blood glucose low")
        enT("body_fat_daily", "Body fat high", "Body fat low")
        enT("weight_daily", "Weight high", "Weight low")

        enP("hrv_daily", "HRV high", "HRV low")
        enP("resting_hr_daily", "Resting HR high", "Resting HR low")
        enP("spo2_daily", "SpO2 high", "SpO2 low")
        enP("skin_temp_daily", "Skin temp high", "Skin temp low")
        enP("respiratory_rate_daily", "Resp rate high", "Resp rate low")
        enP("phone_brightness_daily", "Brightness high", "Brightness low")
        enP("phone_dark_mode_daily", "Dark mode high", "Dark mode low")
        enP("phone_volume_daily", "Volume high", "Volume low")
        enP("phone_unlock_daily", "Phone unlocks high", "Phone unlocks low")
    }

    // ═════════════════════════════════════════════════════════════════════
    // PRODROMES (Page 7)
    // ═════════════════════════════════════════════════════════════════════

    private fun mapProdromes(a: QuestionnaireAnswers, pd: Map<String, Double>, out: MutableMap<String, ProdromeSetting>) {
        for ((s, c) in a.physicalProdromes) {
            if (c == Certainty.NO) continue; val sev = certaintyToSeverity(c)
            when (s) {
                "Neck stiffness" -> out["Muscle tension"] = prodManual("Muscle tension", sev, fav = true)
                "Yawning"        -> out["Yawning"] = prodManual("Yawning", sev, fav = true)
                "Urination"      -> out["Frequent urination"] = prodManual("Frequent urination", sev)
                "Stuffy nose"    -> out["Nasal congestion"] = prodManual("Nasal congestion", sev)
                "Watery eyes"    -> out["Tearing"] = prodManual("Tearing", sev)
                "Muscle tension" -> {
                    val ex = out["Muscle tension"]
                    if (ex == null || severityRank(sev) > severityRank(ex.severity))
                        out["Muscle tension"] = prodManual("Muscle tension", sev, fav = true)
                }
            }
        }
        for ((s, c) in a.moodProdromes) {
            if (c == Certainty.NO) continue; val sev = certaintyToSeverity(c)
            when (s) {
                "Concentrating"    -> out["Difficulty focusing"] = prodManual("Difficulty focusing", sev)
                "Words"            -> out["Word-finding trouble"] = prodManual("Word-finding trouble", sev)
                "Irritability"     -> out["Irritability"] = prodManual("Irritability", sev, fav = true)
                "Mood swings"      -> out["Mood change"] = prodManual("Mood change", sev)
                "Feeling low"      -> out["Depression"] = prodManual("Depression", sev)
                "Unusually happy"  -> out["Euphoria"] = prodManual("Euphoria", sev)
                "Food cravings"    -> out["Food cravings"] = prodManual("Food cravings", sev)
                "Loss of appetite" -> out["Loss of appetite"] = prodManual("Loss of appetite", sev)
            }
        }
        for ((s, c) in a.sensoryProdromes) {
            if (c == Certainty.NO) continue; val sev = certaintyToSeverity(c)
            val dc = downgrade(c); val ds = certaintyToSeverity(dc)
            when (s) {
                "Light" -> {
                    out["Sensitivity to light"] = prodManual("Sensitivity to light", sev, fav = true)
                    if ("Brightness low" !in out) out["Brightness low"] = prodLow("Brightness low", ds, pd, dc)
                    if ("Dark mode high" !in out) out["Dark mode high"] = prodHigh("Dark mode high", ds, pd, dc)
                }
                "Sound"    -> out["Sensitivity to sound"] = prodManual("Sensitivity to sound", sev, fav = true)
                "Smell"    -> out["Sensitivity to smell"] = prodManual("Sensitivity to smell", sev)
                "Tingling" -> out["Tingling"] = prodManual("Tingling", sev)
                "Numbness" -> out["Numbness"] = prodManual("Numbness", sev)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // FAVORITES & HELPERS
    // ═════════════════════════════════════════════════════════════════════

    private fun buildFavorites(a: QuestionnaireAnswers, t: Map<String, TriggerSetting>, p: Map<String, ProdromeSetting>) = Favorites(
        triggers = t.filter { it.value.severity != "NONE" }.map { it.key },
        prodromes = p.filter { it.value.severity != "NONE" }.map { it.key },
        symptoms = a.selectedSymptoms.toList(), medicines = a.selectedMedicines.toList(),
        reliefs = a.selectedReliefs.toList(), activities = a.selectedActivities.toList(),
        missedActivities = a.selectedMissedActivities.toList(),
    )

    private fun severityRank(sev: String): Int = when (sev) { "HIGH" -> 3; "MILD" -> 2; "LOW" -> 1; else -> 0 }
}
