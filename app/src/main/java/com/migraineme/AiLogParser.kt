package com.migraineme

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ═════════════════════════════════════════════════════════════════════
//  Data models with inferred flags
// ═════════════════════════════════════════════════════════════════════

data class AiParsedField<T>(
    val value: T,
    val inferred: Boolean    // true = AI-guessed / default, false = explicitly stated
)

data class AiMatchItemV2(
    val label: String,
    val category: String,
    val inferred: Boolean = false,
    // Timing — all item types
    val startAtIso: String? = null,
    val endAtIso: String? = null,
    // Medicine-specific
    val amount: String? = null,
    // Medicine + Relief shared
    val reliefScale: String? = null,
    val sideEffectScale: String? = null,
    val sideEffectNotes: String? = null
)

data class AiLogParseResultV2(
    val severity: AiParsedField<Int>? = null,
    val beganAtIso: AiParsedField<String>? = null,
    val endedAtIso: AiParsedField<String>? = null,
    val painLocations: List<AiParsedField<String>> = emptyList(),
    val symptoms: List<AiParsedField<String>> = emptyList(),
    val matches: List<AiMatchItemV2> = emptyList()
)

// ═════════════════════════════════════════════════════════════════════
//  Deterministic Pre-Parser
// ═════════════════════════════════════════════════════════════════════

internal fun deterministicParse(
    text: String,
    triggerPool: List<String>,
    prodromePool: List<String>,
    medicinePool: List<String>,
    reliefPool: List<String>,
    activityPool: List<String>,
    locationPool: List<String>,
    missedActivityPool: List<String>,
    painLocationOptions: List<String>,
    symptomOptions: List<String>
): AiLogParseResultV2 {
    val lower = text.lowercase().trim()

    // ── Severity ────────────────────────────────────────────────
    val severity = parseSeverity(lower)

    // ── Timing ──────────────────────────────────────────────────
    val beganAt = parseBeganAt(lower)
    val endedAt = parseEndedAt(lower)

    // ── Pain locations ──────────────────────────────────────────
    val painLocs = parsePainLocations(lower, painLocationOptions)

    // ── Symptoms ────────────────────────────────────────────────
    val symptoms = parseSymptoms(lower, symptomOptions)

    // ── Pool item matching (expanded synonym map) ───────────────
    val matches = mutableListOf<AiMatchItemV2>()
    triggerPool.forEach { label ->
        if (hitExpanded(lower, label)) {
            val time = inferItemTime(lower, label)
            matches.add(AiMatchItemV2(label, "trigger", inferred = false, startAtIso = time))
        }
    }
    prodromePool.forEach { label ->
        if (hitExpanded(lower, label)) {
            val time = inferItemTime(lower, label)
            matches.add(AiMatchItemV2(label, "prodrome", inferred = false, startAtIso = time))
        }
    }
    medicinePool.forEach { label ->
        if (hitExpanded(lower, label)) {
            val time = inferItemTime(lower, label)
            val amount = inferMedicineAmount(lower, label)
            val relief = inferReliefScale(lower, label)
            val (seScale, seNotes) = inferSideEffects(lower, label)
            matches.add(AiMatchItemV2(label, "medicine", inferred = false,
                startAtIso = time, amount = amount, reliefScale = relief,
                sideEffectScale = seScale, sideEffectNotes = seNotes))
        }
    }
    reliefPool.forEach { label ->
        if (hitExpanded(lower, label)) {
            val time = inferItemTime(lower, label)
            val relief = inferReliefScale(lower, label)
            val (seScale, seNotes) = inferSideEffects(lower, label)
            matches.add(AiMatchItemV2(label, "relief", inferred = false,
                startAtIso = time, reliefScale = relief,
                sideEffectScale = seScale, sideEffectNotes = seNotes))
        }
    }
    activityPool.forEach { label ->
        if (hitExpanded(lower, label)) {
            val time = inferItemTime(lower, label)
            matches.add(AiMatchItemV2(label, "activity", inferred = false, startAtIso = time))
        }
    }
    locationPool.forEach { label ->
        if (hitExpanded(lower, label)) {
            val time = inferItemTime(lower, label)
            matches.add(AiMatchItemV2(label, "location", inferred = false, startAtIso = time))
        }
    }
    missedActivityPool.forEach { label ->
        if (hitExpanded(lower, label)) {
            val time = inferItemTime(lower, label)
            matches.add(AiMatchItemV2(label, "missed_activity", inferred = false, startAtIso = time))
        }
    }

    return AiLogParseResultV2(
        severity = severity,
        beganAtIso = beganAt,
        endedAtIso = endedAt,
        painLocations = painLocs,
        symptoms = symptoms,
        matches = matches
    )
}

// ── Severity keywords → 1-10 scale ─────────────────────────────

private fun parseSeverity(text: String): AiParsedField<Int>? {
    // Explicit number: "severity 7", "pain 8/10", "like a 6"
    val numPattern = Regex("""(?:severity|pain|level|rating|it'?s?\s+(?:a|an)?)\s*(\d{1,2})\s*(?:/\s*10|out\s*of\s*10)?""")
    numPattern.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { n ->
        if (n in 1..10) return AiParsedField(n, inferred = false)
    }
    // Also match standalone "N/10" pattern
    Regex("""(\d{1,2})\s*/\s*10""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { n ->
        if (n in 1..10) return AiParsedField(n, inferred = false)
    }

    // Keyword mapping — ordered from highest to lowest
    val severityKeywords: List<Pair<List<String>, Int>> = listOf(
        listOf("worst ever", "worst migraine", "excruciating", "unbearable", "agony", "10 out of") to 10,
        listOf("worst", "absolutely awful", "screaming", "crying from pain") to 9,
        listOf("terrible", "really bad", "horrible", "awful", "extremely painful", "intense") to 8,
        listOf("severe", "very bad", "very painful", "bad migraine", "strong") to 7,
        listOf("pretty bad", "quite bad", "quite painful", "significant") to 6,
        listOf("moderate", "medium", "noticeable", "uncomfortable", "decent") to 5,
        listOf("mild", "not too bad", "slight headache", "manageable", "light", "minor") to 3,
        listOf("barely", "faint", "tiny", "hardly", "barely noticeable", "just a twinge") to 2,
    )

    for ((keywords, score) in severityKeywords) {
        if (keywords.any { text.contains(it) }) {
            return AiParsedField(score, inferred = false)
        }
    }

    // Default suggestion: 4 (mild-moderate) when something is clearly described as a migraine/headache
    if (text.contains("migraine") || text.contains("headache") || text.contains("head pain") || text.contains("head hurt")) {
        return AiParsedField(4, inferred = true)
    }

    return null
}

// ── Time parsing ────────────────────────────────────────────────

private fun parseBeganAt(text: String): AiParsedField<String>? {
    val now = OffsetDateTime.now()
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()

    // "started at 3pm", "began at 14:00", "hit me at 2:30"
    val timePattern = Regex("""(?:started?|began?|hit|woke.*with|kicked in)\s*(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm|AM|PM)?""")
    timePattern.find(text)?.let { match ->
        val h = match.groupValues[1].toIntOrNull() ?: return@let
        val m = match.groupValues[2].toIntOrNull() ?: 0
        val ampm = match.groupValues[3].lowercase()
        val hour = when {
            ampm == "pm" && h < 12 -> h + 12
            ampm == "am" && h == 12 -> 0
            ampm.isEmpty() && h in 1..12 -> h  // assume as-is
            else -> h
        }
        if (hour in 0..23) {
            val dt = today.atTime(hour, m).atZone(zone).toOffsetDateTime()
            return AiParsedField(dt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), inferred = false)
        }
    }

    // Relative phrases
    val relativeMap: List<Pair<List<String>, () -> OffsetDateTime>> = listOf(
        listOf("this morning", "woke up with", "when i woke") to {
            today.atTime(7, 0).atZone(zone).toOffsetDateTime()
        },
        listOf("this afternoon", "after lunch") to {
            today.atTime(13, 0).atZone(zone).toOffsetDateTime()
        },
        listOf("this evening", "tonight") to {
            today.atTime(18, 0).atZone(zone).toOffsetDateTime()
        },
        listOf("last night", "yesterday evening") to {
            today.minusDays(1).atTime(21, 0).atZone(zone).toOffsetDateTime()
        },
        listOf("yesterday morning") to {
            today.minusDays(1).atTime(8, 0).atZone(zone).toOffsetDateTime()
        },
        listOf("yesterday") to {
            today.minusDays(1).atTime(12, 0).atZone(zone).toOffsetDateTime()
        },
        listOf("an hour ago", "1 hour ago") to {
            now.minusHours(1)
        },
        listOf("a couple hours ago", "couple of hours ago", "2 hours ago", "few hours ago") to {
            now.minusHours(2)
        },
        listOf("30 minutes ago", "half hour ago", "half an hour ago") to {
            now.minusMinutes(30)
        },
    )

    for ((phrases, timeFactory) in relativeMap) {
        if (phrases.any { text.contains(it) }) {
            val dt = timeFactory()
            return AiParsedField(dt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), inferred = false)
        }
    }

    return null
}

private fun parseEndedAt(text: String): AiParsedField<String>? {
    val now = OffsetDateTime.now()
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()

    // "still going", "still have it", "hasn't stopped"
    if (text.contains("still going") || text.contains("still have") || text.contains("hasn't stopped") ||
        text.contains("not gone") || text.contains("still there") || text.contains("ongoing")) {
        return null  // explicitly open-ended, don't suggest
    }

    // "lasted X hours"
    Regex("""lasted?\s+(?:about\s+)?(\d+)\s*(?:hours?|hrs?)""").find(text)?.let { match ->
        val hours = match.groupValues[1].toLongOrNull() ?: return@let
        // We need a beganAt to compute from — return null here, the merge logic can compute it
        return null // handled during merge with beganAt
    }

    // "ended at 5pm", "stopped at 14:00", "went away at 3"
    val timePattern = Regex("""(?:ended?|stopped?|went away|cleared|gone)\s*(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm|AM|PM)?""")
    timePattern.find(text)?.let { match ->
        val h = match.groupValues[1].toIntOrNull() ?: return@let
        val m = match.groupValues[2].toIntOrNull() ?: 0
        val ampm = match.groupValues[3].lowercase()
        val hour = when {
            ampm == "pm" && h < 12 -> h + 12
            ampm == "am" && h == 12 -> 0
            ampm.isEmpty() && h in 1..12 -> h
            else -> h
        }
        if (hour in 0..23) {
            val dt = today.atTime(hour, m).atZone(zone).toOffsetDateTime()
            return AiParsedField(dt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), inferred = false)
        }
    }

    // "it's gone now", "went away", "finally stopped"
    if (text.contains("gone now") || text.contains("went away") || text.contains("finally stopped") ||
        text.contains("cleared up") || text.contains("feels better now") || text.contains("over now")) {
        return AiParsedField(now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), inferred = true)
    }

    return null
}

// ── Pain location parsing ───────────────────────────────────────

private fun parsePainLocations(text: String, options: List<String>): List<AiParsedField<String>> {
    val results = mutableListOf<AiParsedField<String>>()

    // Direct label matches first
    for (opt in options) {
        if (text.contains(opt.lowercase())) {
            results.add(AiParsedField(opt, inferred = false))
        }
    }

    // Keyword → location inference
    val locationKeywords: Map<String, List<String>> = mapOf(
        "left temple" to listOf("left side", "left temple"),
        "right temple" to listOf("right side", "right temple"),
        "Left Temple" to listOf("left side of my head", "left side"),
        "Right Temple" to listOf("right side of my head", "right side"),
        "Left Eye" to listOf("behind my left eye", "left eye"),
        "Right Eye" to listOf("behind my right eye", "right eye"),
        "Forehead Center" to listOf("forehead", "across my forehead", "front of my head"),
        "Forehead Left" to listOf("left forehead"),
        "Forehead Right" to listOf("right forehead"),
        "Top of Head" to listOf("top of my head", "top of head", "crown"),
        "Occipital Center" to listOf("back of my head", "back of head", "occipital"),
        "Base of Skull Center" to listOf("base of skull", "base of my skull"),
        "Neck Left" to listOf("left neck", "neck"),
        "Neck Right" to listOf("right neck"),
        "Left Jaw / TMJ" to listOf("jaw", "left jaw", "tmj"),
        "Right Jaw / TMJ" to listOf("right jaw"),
        "Nose Bridge" to listOf("nose bridge", "bridge of my nose", "between my eyes"),
        "Left Sinus" to listOf("left sinus", "sinus"),
        "Right Sinus" to listOf("right sinus"),
        "Left Shoulder" to listOf("left shoulder", "shoulder"),
        "Right Shoulder" to listOf("right shoulder"),
        "Left Brow" to listOf("left brow", "left eyebrow"),
        "Right Brow" to listOf("right brow", "right eyebrow"),
    )

    // "behind both eyes" / "behind my eyes"
    if (text.contains("behind my eyes") || text.contains("behind both eyes") || text.contains("behind the eyes")) {
        if (results.none { it.value == "Left Eye" }) results.add(AiParsedField("Left Eye", inferred = false))
        if (results.none { it.value == "Right Eye" }) results.add(AiParsedField("Right Eye", inferred = false))
    }

    // "both sides" / "both temples"
    if (text.contains("both sides") || text.contains("both temples")) {
        if (results.none { it.value == "Left Temple" }) results.add(AiParsedField("Left Temple", inferred = false))
        if (results.none { it.value == "Right Temple" }) results.add(AiParsedField("Right Temple", inferred = false))
    }

    // "neck" without left/right qualifier → both
    if (text.contains("neck") && !text.contains("left neck") && !text.contains("right neck")) {
        if (results.none { it.value == "Neck Left" }) results.add(AiParsedField("Neck Left", inferred = true))
        if (results.none { it.value == "Neck Right" }) results.add(AiParsedField("Neck Right", inferred = true))
    }

    for ((location, keywords) in locationKeywords) {
        if (results.any { it.value == location }) continue  // already matched
        val validLocation = options.find { it.equals(location, ignoreCase = true) } ?: continue
        if (keywords.any { text.contains(it) }) {
            results.add(AiParsedField(validLocation, inferred = false))
        }
    }

    // Validate all results against available options
    return results.filter { f -> options.any { it.equals(f.value, ignoreCase = true) } }
        .map { f -> AiParsedField(options.first { it.equals(f.value, ignoreCase = true) }, f.inferred) }
        .distinctBy { it.value }
}

// ── Symptom parsing ─────────────────────────────────────────────

private fun parseSymptoms(text: String, options: List<String>): List<AiParsedField<String>> {
    val results = mutableListOf<AiParsedField<String>>()

    val symptomKeywords: Map<String, List<String>> = mapOf(
        "Throbbing" to listOf("throbbing", "pounding", "pulsating", "pulsing", "beating"),
        "Stabbing" to listOf("stabbing", "sharp", "piercing", "shooting"),
        "Pressure" to listOf("pressure", "pressing", "squeezing", "tight", "tightness", "band around"),
        "Burning" to listOf("burning", "hot", "searing"),
        "Aching" to listOf("aching", "dull ache", "dull pain", "constant pain"),
        "Nausea" to listOf("nausea", "nauseous", "queasy", "felt sick", "feel sick", "stomach"),
        "Vomiting" to listOf("vomiting", "vomited", "threw up", "throwing up", "sick"),
        "Light sensitivity" to listOf("sensitive to light", "light sensitivity", "photophobia", "bright light", "can't stand light"),
        "Sound sensitivity" to listOf("sensitive to sound", "sound sensitivity", "phonophobia", "loud", "noise"),
        "Aura" to listOf("aura", "visual aura", "zigzag", "flashing", "visual disturbance"),
        "Dizziness" to listOf("dizzy", "dizziness", "vertigo", "lightheaded", "light headed", "room spinning"),
        "Brain fog" to listOf("brain fog", "foggy", "can't concentrate", "confused", "confusion", "fuzzy"),
        "Neck stiffness" to listOf("stiff neck", "neck stiffness", "neck pain", "tight neck"),
        "Fatigue" to listOf("fatigue", "tired", "exhausted", "no energy", "worn out", "wiped out"),
        "Tingling" to listOf("tingling", "pins and needles", "numbness", "numb"),
        "Blurred vision" to listOf("blurry", "blurred", "can't see", "vision"),
        "Tearing" to listOf("teary", "watery eyes", "eye watering", "tearing"),
        "Nasal congestion" to listOf("stuffy nose", "congested", "blocked nose", "runny nose"),
    )

    for ((symptom, keywords) in symptomKeywords) {
        val validSymptom = options.find { it.equals(symptom, ignoreCase = true) } ?: continue
        if (keywords.any { text.contains(it) }) {
            results.add(AiParsedField(validSymptom, inferred = false))
        }
    }

    // Also try direct label matching for anything not covered by keywords
    for (opt in options) {
        if (results.any { it.value.equals(opt, ignoreCase = true) }) continue
        if (text.contains(opt.lowercase())) {
            results.add(AiParsedField(opt, inferred = false))
        }
    }

    return results.distinctBy { it.value }
}

// ── Expanded synonym matching for pool items ────────────────────

internal fun hitExpanded(text: String, label: String): Boolean {
    val l = label.lowercase()
    if (text.contains(l)) return true
    val words = l.split(" ", "_", "-").filter { it.length > 2 }
    if (words.size > 1 && words.all { text.contains(it) }) return true
    return EXPANDED_SYNONYMS[l]?.any { text.contains(it) } == true
}

private val EXPANDED_SYNONYMS: Map<String, List<String>> = mapOf(
    // Triggers
    "alcohol" to listOf("wine", "beer", "drink", "drinking", "booze", "cocktail", "spirits", "vodka", "whisky", "whiskey", "gin", "rum", "champagne", "prosecco", "pint", "pub"),
    "red wine" to listOf("red wine", "merlot", "cabernet", "shiraz", "pinot noir"),
    "white wine" to listOf("white wine", "chardonnay", "sauvignon", "pinot grigio", "riesling"),
    "poor sleep" to listOf("slept badly", "bad sleep", "insomnia", "couldn't sleep", "barely slept", "tossed and turned", "restless night", "woke up lots"),
    "skipped meal" to listOf("skipped lunch", "skipped dinner", "skipped breakfast", "didn't eat", "forgot to eat", "missed lunch", "missed dinner", "missed breakfast", "no breakfast", "no lunch"),
    "dehydration" to listOf("dehydrated", "not enough water", "thirsty", "didn't drink enough", "forgot to drink"),
    "stress" to listOf("stressed", "stressful", "anxious", "overwhelmed", "worried", "panic", "deadline", "pressure at work", "argument", "fight"),
    "bright light" to listOf("bright lights", "glare", "fluorescent", "sun", "sunlight", "harsh light"),
    "loud noise" to listOf("loud", "noisy", "noise", "concert", "music", "construction"),
    "weather change" to listOf("weather", "barometric", "storm", "rain", "humidity", "hot day", "cold front", "temperature change"),
    "neck stiffness" to listOf("stiff neck", "neck pain", "tight neck", "neck tension"),
    "caffeine" to listOf("coffee", "espresso", "energy drink", "tea", "too much coffee", "latte", "cappuccino"),
    "menstruation" to listOf("period", "menstrual", "cycle", "time of the month", "pms"),
    "processed food" to listOf("junk food", "fast food", "takeaway", "takeout", "pizza", "burger", "fried food"),
    "cheese" to listOf("cheese", "aged cheese", "cheddar", "brie", "camembert"),
    "chocolate" to listOf("chocolate", "cocoa"),
    "screen time" to listOf("screen", "screens", "computer", "laptop", "phone", "monitor", "staring at screen"),
    "overexertion" to listOf("overexerted", "overdid it", "pushed too hard", "too much exercise", "exhausting workout"),
    "travel" to listOf("travelled", "traveled", "flight", "flew", "long drive", "road trip", "jet lag"),
    "strong smell" to listOf("strong smell", "perfume", "chemical", "fumes", "paint", "cleaning products"),
    "lack of exercise" to listOf("didn't exercise", "no exercise", "sedentary", "sat all day"),
    "irregular sleep" to listOf("irregular sleep", "sleep schedule", "different bed time", "jet lag"),

    // Prodromes
    "nausea" to listOf("nauseous", "queasy", "felt sick"),
    "fatigue" to listOf("exhausted", "tired", "worn out", "wiped"),
    "brain fog" to listOf("foggy", "can't concentrate", "confused", "fuzzy headed"),
    "light sensitivity" to listOf("sensitive to light", "photophobia"),
    "sound sensitivity" to listOf("sensitive to sound", "phonophobia"),
    "aura" to listOf("visual aura", "zigzag", "flashing", "visual disturbance", "sparkles", "spots"),
    "dizziness" to listOf("dizzy", "vertigo", "lightheaded", "light headed"),
    "mood change" to listOf("irritable", "moody", "emotional", "snappy", "cranky"),
    "food craving" to listOf("craving", "cravings", "hungry", "starving"),
    "yawning" to listOf("yawning", "kept yawning"),
    "neck stiffness" to listOf("stiff neck", "neck pain", "tight neck"),

    // Medicines
    "ibuprofen" to listOf("advil", "motrin", "nurofen", "brufen"),
    "paracetamol" to listOf("acetaminophen", "tylenol", "panadol", "calpol"),
    "sumatriptan" to listOf("imigran", "imitrex", "triptan"),
    "aspirin" to listOf("aspirin", "disprin"),
    "naproxen" to listOf("naproxen", "aleve", "naprosyn"),
    "rizatriptan" to listOf("rizatriptan", "maxalt"),
    "zolmitriptan" to listOf("zolmitriptan", "zomig"),
    "codeine" to listOf("codeine", "co-codamol", "cocodamol"),
    "amitriptyline" to listOf("amitriptyline"),
    "propranolol" to listOf("propranolol"),
    "topiramate" to listOf("topiramate", "topamax"),

    // Reliefs
    "meditation" to listOf("meditated", "mindfulness", "breathing exercise", "calm app"),
    "ice" to listOf("ice pack", "cold pack", "cold compress", "frozen peas"),
    "rest" to listOf("rested", "nap", "napped", "lay down", "laid down", "slept"),
    "dark room" to listOf("darkness", "lay in dark", "dark room", "closed curtains", "blackout"),
    "heat" to listOf("heat pack", "hot water bottle", "warm compress", "heating pad"),
    "massage" to listOf("massage", "rubbed my temples", "rubbed my neck"),
    "bath" to listOf("bath", "hot bath", "warm bath", "shower"),
    "walk" to listOf("went for a walk", "fresh air", "walked"),
    "water" to listOf("drank water", "hydrated", "lots of water"),

    // Activities
    "work" to listOf("work", "working", "office", "at work", "desk"),
    "exercise" to listOf("exercise", "gym", "workout", "training"),
    "running" to listOf("running", "run", "jog", "jogging"),
    "cycling" to listOf("cycling", "bike", "biking"),
    "swimming" to listOf("swimming", "swim", "pool"),
    "yoga" to listOf("yoga"),
    "cooking" to listOf("cooking", "cooked", "made dinner", "made lunch"),
    "driving" to listOf("driving", "drove", "long drive"),
    "shopping" to listOf("shopping", "shops", "supermarket", "grocery"),
    "socialising" to listOf("socialising", "socializing", "friends", "party", "pub", "dinner out", "restaurant"),
    "gardening" to listOf("gardening", "garden"),
    "cleaning" to listOf("cleaning", "housework", "hoovering", "vacuuming"),

    // Locations
    "home" to listOf("at home", "house"),
    "office" to listOf("office", "at work", "workplace"),
    "outdoors" to listOf("outside", "outdoors", "park", "garden"),
    "car" to listOf("in the car", "driving", "passenger"),
    "gym" to listOf("gym", "fitness centre"),

    // Missed activities
    "lunch" to listOf("skipped lunch", "missed lunch", "no lunch"),
    "breakfast" to listOf("skipped breakfast", "missed breakfast", "no breakfast"),
    "dinner" to listOf("skipped dinner", "missed dinner", "no dinner"),
)

// ═════════════════════════════════════════════════════════════════════
//  Updated GPT Full Parse — fills gaps from deterministic pass
// ═════════════════════════════════════════════════════════════════════

internal suspend fun callGptForFullLogParseV2(
    accessToken: String,
    noteText: String,
    triggers: List<String>,
    prodromes: List<String>,
    medicines: List<String>,
    reliefs: List<String>,
    activities: List<String>,
    locations: List<String>,
    missedActivities: List<String>,
    painLocationOptions: List<String>,
    symptomOptions: List<String>,
    deterministicResult: AiLogParseResultV2
): AiLogParseResultV2 {

    // Build "already found" summary so GPT focuses on gaps
    val alreadyFound = buildString {
        deterministicResult.severity?.let { append("severity: ${it.value}, ") }
        deterministicResult.beganAtIso?.let { append("began_at: ${it.value}, ") }
        deterministicResult.endedAtIso?.let { append("ended_at: ${it.value}, ") }
        if (deterministicResult.painLocations.isNotEmpty()) append("pain_locations: ${deterministicResult.painLocations.map { it.value }}, ")
        if (deterministicResult.symptoms.isNotEmpty()) append("symptoms: ${deterministicResult.symptoms.map { it.value }}, ")
        if (deterministicResult.matches.isNotEmpty()) append("items: ${deterministicResult.matches.map { "${it.label} (${it.category})" }}")
    }

    // System prompt lives server-side under context_type "log_parser".
    // user_message carries all dynamic data: user text, parser results, pools.
    val userMessage = """
User said: "$noteText"

Already found by deterministic parser: $alreadyFound
Today's date: ${java.time.LocalDate.now()}

TRIGGER pool: ${triggers.joinToString(", ")}
PRODROME pool: ${prodromes.joinToString(", ")}
MEDICINE pool: ${medicines.joinToString(", ")}
RELIEF pool: ${reliefs.joinToString(", ")}
ACTIVITY pool: ${activities.joinToString(", ")}
LOCATION pool: ${locations.joinToString(", ")}
MISSED ACTIVITY pool: ${missedActivities.joinToString(", ")}
PAIN LOCATION options: ${painLocationOptions.joinToString(", ")}
SYMPTOM options: ${symptomOptions.joinToString(", ")}

Extract everything you can. JSON object only.
""".trimIndent()

    val requestBody = org.json.JSONObject().apply {
        put("context_type", "log_parser")
        put("user_message", userMessage)
    }

    val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/ai-setup"

    val client = okhttp3.OkHttpClient()
    val request = okhttp3.Request.Builder()
        .url(url)
        .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
        .header("Authorization", "Bearer $accessToken")
        .header("Content-Type", "application/json")
        .post(okhttp3.RequestBody.create(
            "application/json".toMediaTypeOrNull(),
            requestBody.toString()
        ))
        .build()

    val response = client.newCall(request).execute()
    val text = response.body?.string() ?: ""
    if (!response.isSuccessful) throw Exception("AI failed: ${response.code}")

    val clean = text.replace("```json", "").replace("```", "").trim()
    val obj = org.json.JSONObject(clean)

    // Parse severity
    val severity = if (obj.has("severity") && !obj.isNull("severity")) {
        val v = obj.optInt("severity", -1)
        val inf = obj.optBoolean("severity_inferred", true)
        if (v in 1..10) AiParsedField(v, inf) else null
    } else null

    // Parse began_at
    val beganAt = if (obj.has("began_at") && !obj.isNull("began_at")) {
        val v = obj.optString("began_at", "")
        val inf = obj.optBoolean("began_at_inferred", true)
        if (v.isNotBlank()) AiParsedField(v, inf) else null
    } else null

    // Parse ended_at
    val endedAt = if (obj.has("ended_at") && !obj.isNull("ended_at")) {
        val v = obj.optString("ended_at", "")
        val inf = obj.optBoolean("ended_at_inferred", true)
        if (v.isNotBlank()) AiParsedField(v, inf) else null
    } else null

    // Parse pain locations
    val painLocs = mutableListOf<AiParsedField<String>>()
    val plArr = obj.optJSONArray("pain_locations")
    val plInfArr = obj.optJSONArray("pain_locations_inferred")
    if (plArr != null) {
        for (i in 0 until plArr.length()) {
            val loc = plArr.getString(i)
            val inf = plInfArr?.optBoolean(i, true) ?: true
            if (loc in painLocationOptions) painLocs.add(AiParsedField(loc, inf))
        }
    }

    // Parse symptoms
    val syms = mutableListOf<AiParsedField<String>>()
    val symArr = obj.optJSONArray("symptoms")
    val symInfArr = obj.optJSONArray("symptoms_inferred")
    if (symArr != null) {
        for (i in 0 until symArr.length()) {
            val sym = symArr.getString(i)
            val inf = symInfArr?.optBoolean(i, true) ?: true
            if (sym in symptomOptions) syms.add(AiParsedField(sym, inf))
        }
    }

    // Parse items
    val allPools = mapOf(
        "trigger" to triggers, "prodrome" to prodromes, "medicine" to medicines,
        "relief" to reliefs, "activity" to activities, "location" to locations,
        "missed_activity" to missedActivities
    )
    val matches = mutableListOf<AiMatchItemV2>()
    obj.optJSONArray("items")?.let { arr ->
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val label = item.getString("label")
            val cat = item.getString("category")
            val inf = item.optBoolean("inferred", true)
            val pool = allPools[cat]
            if (pool != null && label in pool) {
                val startAt = item.optString("start_at", "").takeIf { it.isNotBlank() && it != "null" }
                val endAt = item.optString("end_at", "").takeIf { it.isNotBlank() && it != "null" }
                val amount = item.optString("amount", "").takeIf { it.isNotBlank() && it != "null" }
                val reliefScale = item.optString("relief_scale", "").takeIf { it.isNotBlank() && it != "null" }
                val sideEffectScale = item.optString("side_effect_scale", "").takeIf { it.isNotBlank() && it != "null" }
                val sideEffectNotes = item.optString("side_effect_notes", "").takeIf { it.isNotBlank() && it != "null" }
                matches.add(AiMatchItemV2(
                    label = label, category = cat, inferred = inf,
                    startAtIso = startAt,
                    endAtIso = if (cat == "activity" || cat == "relief") endAt else null,
                    amount = if (cat == "medicine") amount else null,
                    reliefScale = if (cat == "medicine" || cat == "relief") reliefScale else null,
                    sideEffectScale = if (cat == "medicine" || cat == "relief") sideEffectScale else null,
                    sideEffectNotes = if (cat == "medicine" || cat == "relief") sideEffectNotes else null
                ))
            }
        }
    }

    return AiLogParseResultV2(severity, beganAt, endedAt, painLocs, syms, matches)
}

// ═════════════════════════════════════════════════════════════════════
//  Merge deterministic + GPT results
// ═════════════════════════════════════════════════════════════════════

internal fun mergeResults(
    deterministic: AiLogParseResultV2,
    gpt: AiLogParseResultV2?
): AiLogParseResultV2 {
    if (gpt == null) return deterministic

    // Severity: prefer deterministic explicit > GPT explicit > deterministic inferred > GPT inferred
    val severity = pickBest(deterministic.severity, gpt.severity)

    // Timing: same priority
    val beganAt = pickBest(deterministic.beganAtIso, gpt.beganAtIso)
    val endedAt = pickBest(deterministic.endedAtIso, gpt.endedAtIso)

    // Pain locations: merge, deduplicate, prefer explicit over inferred
    val painLocs = mergeFieldLists(deterministic.painLocations, gpt.painLocations)

    // Symptoms: merge
    val symptoms = mergeFieldLists(deterministic.symptoms, gpt.symptoms)

    // Items: merge, deduplicate by label+category, prefer explicit, fill in extra fields
    val allMatches = mutableMapOf<String, AiMatchItemV2>()  // key = "$label|$category"
    for (m in deterministic.matches) {
        val key = "${m.label}|${m.category}"
        allMatches[key] = m
    }
    for (m in gpt.matches) {
        val key = "${m.label}|${m.category}"
        val existing = allMatches[key]
        if (existing == null) {
            allMatches[key] = m
        } else {
            // Merge: keep explicit over inferred, fill in missing extra fields from GPT
            allMatches[key] = existing.copy(
                inferred = existing.inferred && m.inferred,
                startAtIso = existing.startAtIso ?: m.startAtIso,
                endAtIso = existing.endAtIso ?: m.endAtIso,
                amount = existing.amount ?: m.amount,
                reliefScale = existing.reliefScale ?: m.reliefScale,
                sideEffectScale = existing.sideEffectScale ?: m.sideEffectScale,
                sideEffectNotes = existing.sideEffectNotes ?: m.sideEffectNotes
            )
        }
    }

    return AiLogParseResultV2(
        severity = severity,
        beganAtIso = beganAt,
        endedAtIso = endedAt,
        painLocations = painLocs,
        symptoms = symptoms,
        matches = allMatches.values.toList()
    )
}

// ═════════════════════════════════════════════════════════════════════
//  Evening Check-In: richer item models
// ═════════════════════════════════════════════════════════════════════

data class CheckInTriggerItem(
    val label: String,
    val startAtIso: String? = null,
    val note: String? = null,
    val inferred: Boolean = false
)

data class CheckInProdromeItem(
    val label: String,
    val startAtIso: String? = null,
    val note: String? = null,
    val inferred: Boolean = false
)

data class CheckInMedicineItem(
    val label: String,
    val amount: String? = null,
    val startAtIso: String? = null,
    val note: String? = null,
    val reliefScale: String? = "NONE",
    val sideEffectScale: String? = "NONE",
    val sideEffectNotes: String? = null,
    val inferred: Boolean = false
)

data class CheckInReliefItem(
    val label: String,
    val startAtIso: String? = null,
    val endAtIso: String? = null,
    val note: String? = null,
    val reliefScale: String? = "NONE",
    val sideEffectScale: String? = "NONE",
    val sideEffectNotes: String? = null,
    val inferred: Boolean = false
)

data class CheckInParseResult(
    val triggers: List<CheckInTriggerItem> = emptyList(),
    val prodromes: List<CheckInProdromeItem> = emptyList(),
    val medicines: List<CheckInMedicineItem> = emptyList(),
    val reliefs: List<CheckInReliefItem> = emptyList()
)

// ═════════════════════════════════════════════════════════════════════
//  Evening Check-In: deterministic parse
// ═════════════════════════════════════════════════════════════════════

internal fun deterministicParseCheckIn(
    text: String,
    triggerPool: List<String>,
    prodromePool: List<String>,
    medicinePool: List<String>,
    reliefPool: List<String>
): CheckInParseResult {
    val lower = text.lowercase().trim()

    val triggers = mutableListOf<CheckInTriggerItem>()
    val prodromes = mutableListOf<CheckInProdromeItem>()
    val medicines = mutableListOf<CheckInMedicineItem>()
    val reliefs = mutableListOf<CheckInReliefItem>()

    // Match triggers
    triggerPool.forEach { label ->
        if (hitExpanded(lower, label)) {
            val time = inferItemTime(lower, label)
            triggers.add(CheckInTriggerItem(label, startAtIso = time, inferred = false))
        }
    }

    // Match prodromes
    prodromePool.forEach { label ->
        if (hitExpanded(lower, label)) {
            val time = inferItemTime(lower, label)
            prodromes.add(CheckInProdromeItem(label, startAtIso = time, inferred = false))
        }
    }

    // Match medicines with amount + relief scale
    medicinePool.forEach { label ->
        if (hitExpanded(lower, label)) {
            val time = inferItemTime(lower, label)
            val amount = inferMedicineAmount(lower, label)
            val relief = inferReliefScale(lower, label)
            medicines.add(CheckInMedicineItem(
                label, amount = amount, startAtIso = time,
                reliefScale = relief, inferred = false
            ))
        }
    }

    // Match reliefs with relief scale + side effects
    reliefPool.forEach { label ->
        if (hitExpanded(lower, label)) {
            val time = inferItemTime(lower, label)
            val relief = inferReliefScale(lower, label)
            reliefs.add(CheckInReliefItem(
                label, startAtIso = time,
                reliefScale = relief, inferred = false
            ))
        }
    }

    return CheckInParseResult(triggers, prodromes, medicines, reliefs)
}

// ── Infer time for a specific item from surrounding context ──────

private fun inferItemTime(text: String, label: String): String? {
    val l = label.lowercase()
    val now = java.time.OffsetDateTime.now()
    val today = java.time.LocalDate.now()
    val zone = java.time.ZoneId.systemDefault()

    // Find the label position and look for time phrases nearby
    val labelIdx = text.indexOf(l).takeIf { it >= 0 }
    // Check for time phrases in the whole text (evening check-in context is usually short)

    // "took ibuprofen at 3pm", "had coffee at 8am"
    val nearby = if (labelIdx != null) {
        val start = (labelIdx - 40).coerceAtLeast(0)
        val end = (labelIdx + l.length + 40).coerceAtMost(text.length)
        text.substring(start, end)
    } else text

    Regex("""at\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm|AM|PM)?""").find(nearby)?.let { match ->
        val h = match.groupValues[1].toIntOrNull() ?: return@let
        val m = match.groupValues[2].toIntOrNull() ?: 0
        val ampm = match.groupValues[3].lowercase()
        val hour = when {
            ampm == "pm" && h < 12 -> h + 12
            ampm == "am" && h == 12 -> 0
            ampm.isEmpty() && h in 1..12 -> h
            else -> h
        }
        if (hour in 0..23) {
            return today.atTime(hour, m).atZone(zone).toOffsetDateTime()
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }
    }

    // General time phrases
    val timeMap: List<Pair<List<String>, java.time.OffsetDateTime>> = listOf(
        listOf("this morning", "in the morning", "before work") to
                today.atTime(8, 0).atZone(zone).toOffsetDateTime(),
        listOf("at lunch", "lunchtime", "midday", "noon") to
                today.atTime(12, 0).atZone(zone).toOffsetDateTime(),
        listOf("this afternoon", "after lunch") to
                today.atTime(14, 0).atZone(zone).toOffsetDateTime(),
        listOf("this evening", "tonight", "after work", "after dinner") to
                today.atTime(19, 0).atZone(zone).toOffsetDateTime(),
        listOf("before bed", "bedtime", "late tonight") to
                today.atTime(22, 0).atZone(zone).toOffsetDateTime(),
        listOf("last night", "yesterday evening") to
                today.minusDays(1).atTime(21, 0).atZone(zone).toOffsetDateTime(),
        listOf("yesterday") to
                today.minusDays(1).atTime(12, 0).atZone(zone).toOffsetDateTime(),
    )

    for ((phrases, time) in timeMap) {
        if (phrases.any { nearby.contains(it) || text.contains(it) }) {
            return time.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }
    }

    return null
}

// ── Infer medicine amount ───────────────────────────────────────

private fun inferMedicineAmount(text: String, label: String): String? {
    val l = label.lowercase()
    val labelIdx = text.indexOf(l).takeIf { it >= 0 } ?: return null
    val start = (labelIdx - 30).coerceAtLeast(0)
    val end = (labelIdx + l.length + 30).coerceAtMost(text.length)
    val nearby = text.substring(start, end)

    // "took 2 ibuprofen", "had 400mg paracetamol", "2x sumatriptan", "a couple of ibuprofen"
    Regex("""(\d+)\s*(?:x\s*)?(?:mg|MG)?\s*${Regex.escape(l)}""").find(nearby)?.let {
        return it.groupValues[1]
    }
    Regex("""${Regex.escape(l)}\s*(\d+)\s*(?:mg|MG)""").find(nearby)?.let {
        return "${it.groupValues[1]}mg"
    }
    Regex("""(\d+)\s*(?:mg|MG)\s*(?:of\s*)?${Regex.escape(l)}""").find(nearby)?.let {
        return "${it.groupValues[1]}mg"
    }
    Regex("""took\s+(\d+)\s+${Regex.escape(l)}""").find(nearby)?.let {
        return it.groupValues[1]
    }
    Regex("""took\s+(?:a\s+)?(?:couple|two|three|four)\s+(?:of\s+)?${Regex.escape(l)}""").find(nearby)?.let {
        val word = it.value
        return when {
            "couple" in word || "two" in word -> "2"
            "three" in word -> "3"
            "four" in word -> "4"
            else -> null
        }
    }

    return null
}

// ── Infer relief scale ──────────────────────────────────────────

private fun inferReliefScale(text: String, label: String): String? {
    val l = label.lowercase()
    val labelIdx = text.indexOf(l).takeIf { it >= 0 } ?: return null
    val start = (labelIdx - 50).coerceAtLeast(0)
    val end = (labelIdx + l.length + 50).coerceAtMost(text.length)
    val nearby = text.substring(start, end)

    // Check for relief indicators
    val highRelief = listOf("really helped", "helped a lot", "worked great", "worked well", "cured", "fixed it", "amazing", "saved me", "life saver")
    val someRelief = listOf("helped a bit", "helped some", "kind of helped", "somewhat", "took the edge off", "slightly better")
    val noRelief = listOf("didn't help", "didn't work", "no effect", "useless", "waste", "nothing happened", "still there")

    return when {
        highRelief.any { nearby.contains(it) } -> "HIGH"
        someRelief.any { nearby.contains(it) } -> "MODERATE"
        noRelief.any { nearby.contains(it) } -> "LOW"
        else -> null
    }
}

// ── Infer side effect scale ─────────────────────────────────────

private fun inferSideEffects(text: String, label: String): Pair<String?, String?> {
    val l = label.lowercase()
    val labelIdx = text.indexOf(l).takeIf { it >= 0 } ?: return null to null
    val start = (labelIdx - 50).coerceAtLeast(0)
    val end = (labelIdx + l.length + 60).coerceAtMost(text.length)
    val nearby = text.substring(start, end)

    val sideEffectKeywords = listOf("side effect", "made me", "felt weird", "dizzy after", "drowsy", "sleepy", "nauseous after", "stomach", "upset stomach")

    if (sideEffectKeywords.any { nearby.contains(it) }) {
        val severeWords = listOf("terrible", "awful", "really bad", "horrible", "severe", "worst")
        val scale = if (severeWords.any { nearby.contains(it) }) "HIGH" else "MODERATE"
        // Try to capture what the side effect was
        val noteWords = listOf("drowsy", "sleepy", "dizzy", "nauseous", "stomach", "tired", "foggy", "jittery", "anxious")
        val notes = noteWords.filter { nearby.contains(it) }.joinToString(", ")
        return scale to notes.ifBlank { null }
    }

    return null to null
}

// ═════════════════════════════════════════════════════════════════════
//  Evening Check-In: GPT parse with full field extraction
// ═════════════════════════════════════════════════════════════════════

internal suspend fun callGptForCheckInParse(
    accessToken: String,
    noteText: String,
    triggers: List<String>,
    prodromes: List<String>,
    medicines: List<String>,
    reliefs: List<String>,
    deterministicResult: CheckInParseResult
): CheckInParseResult {

    val alreadyFound = buildString {
        if (deterministicResult.triggers.isNotEmpty()) append("triggers: ${deterministicResult.triggers.map { it.label }}, ")
        if (deterministicResult.prodromes.isNotEmpty()) append("prodromes: ${deterministicResult.prodromes.map { it.label }}, ")
        if (deterministicResult.medicines.isNotEmpty()) append("medicines: ${deterministicResult.medicines.map { "${it.label} (amount=${it.amount}, relief=${it.reliefScale})" }}, ")
        if (deterministicResult.reliefs.isNotEmpty()) append("reliefs: ${deterministicResult.reliefs.map { "${it.label} (relief=${it.reliefScale})" }}")
    }

    val today = LocalDate.now()

    // System prompt lives server-side under context_type "log_parser_evening".
    // user_message carries all dynamic data: user text, parser results, pools.
    val userMessage = """
User said: "$noteText"

Already found by deterministic parser: $alreadyFound
Today's date: $today

TRIGGER pool: ${triggers.joinToString(", ")}
PRODROME pool: ${prodromes.joinToString(", ")}
MEDICINE pool: ${medicines.joinToString(", ")}
RELIEF pool: ${reliefs.joinToString(", ")}

Extract everything. JSON only.
""".trimIndent()

    val requestBody = org.json.JSONObject().apply {
        put("context_type", "log_parser_evening")
        put("user_message", userMessage)
    }

    val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/ai-setup"

    val client = okhttp3.OkHttpClient()
    val request = okhttp3.Request.Builder()
        .url(url)
        .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
        .header("Authorization", "Bearer $accessToken")
        .header("Content-Type", "application/json")
        .post(okhttp3.RequestBody.create(
            "application/json".toMediaTypeOrNull(),
            requestBody.toString()
        ))
        .build()

    val response = client.newCall(request).execute()
    val text = response.body?.string() ?: ""
    if (!response.isSuccessful) throw Exception("AI failed: ${response.code}")

    val clean = text.replace("```json", "").replace("```", "").trim()
    val obj = org.json.JSONObject(clean)

    val allPools = mapOf(
        "trigger" to triggers, "prodrome" to prodromes,
        "medicine" to medicines, "relief" to reliefs
    )

    val resTriggers = mutableListOf<CheckInTriggerItem>()
    val resProdromes = mutableListOf<CheckInProdromeItem>()
    val resMedicines = mutableListOf<CheckInMedicineItem>()
    val resReliefs = mutableListOf<CheckInReliefItem>()

    obj.optJSONArray("items")?.let { arr ->
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val label = item.getString("label")
            val cat = item.getString("category")
            val pool = allPools[cat] ?: continue
            if (label !in pool) continue

            val inf = item.optBoolean("inferred", true)
            val startAt = item.optString("start_at", "").takeIf { it.isNotBlank() && it != "null" }
            val amount = item.optString("amount", "").takeIf { it.isNotBlank() && it != "null" }
            val reliefScale = item.optString("relief_scale", "NONE").takeIf { it.isNotBlank() && it != "null" } ?: "NONE"
            val sideEffectScale = item.optString("side_effect_scale", "NONE").takeIf { it.isNotBlank() && it != "null" } ?: "NONE"
            val sideEffectNotes = item.optString("side_effect_notes", "").takeIf { it.isNotBlank() && it != "null" }

            when (cat) {
                "trigger" -> resTriggers.add(CheckInTriggerItem(label, startAt, null, inf))
                "prodrome" -> resProdromes.add(CheckInProdromeItem(label, startAt, null, inf))
                "medicine" -> resMedicines.add(CheckInMedicineItem(label, amount, startAt, null, reliefScale, sideEffectScale, sideEffectNotes, inf))
                "relief" -> resReliefs.add(CheckInReliefItem(label, startAt, null, null, reliefScale, sideEffectScale, sideEffectNotes, inf))
            }
        }
    }

    return CheckInParseResult(resTriggers, resProdromes, resMedicines, resReliefs)
}

// ═════════════════════════════════════════════════════════════════════
//  Evening Check-In: merge deterministic + GPT
// ═════════════════════════════════════════════════════════════════════

internal fun mergeCheckInResults(
    deterministic: CheckInParseResult,
    gpt: CheckInParseResult?
): CheckInParseResult {
    if (gpt == null) return deterministic

    fun <T> mergeByLabel(
        detList: List<T>,
        gptList: List<T>,
        getLabel: (T) -> String,
        getInferred: (T) -> Boolean,
        merge: (T, T) -> T
    ): List<T> {
        val map = mutableMapOf<String, T>()
        for (item in detList) map[getLabel(item)] = item
        for (item in gptList) {
            val existing = map[getLabel(item)]
            if (existing == null) {
                map[getLabel(item)] = item
            } else if (getInferred(existing) && !getInferred(item)) {
                map[getLabel(item)] = merge(existing, item)
            } else {
                // Keep existing (deterministic explicit), but merge in any extra fields from GPT
                map[getLabel(item)] = merge(existing, item)
            }
        }
        return map.values.toList()
    }

    val triggers = mergeByLabel(
        deterministic.triggers, gpt.triggers,
        { it.label }, { it.inferred },
        { det, gpt -> det.copy(
            startAtIso = det.startAtIso ?: gpt.startAtIso,
            note = det.note ?: gpt.note,
            inferred = det.inferred && gpt.inferred
        )}
    )

    val prodromes = mergeByLabel(
        deterministic.prodromes, gpt.prodromes,
        { it.label }, { it.inferred },
        { det, gpt -> det.copy(
            startAtIso = det.startAtIso ?: gpt.startAtIso,
            note = det.note ?: gpt.note,
            inferred = det.inferred && gpt.inferred
        )}
    )

    val medicines = mergeByLabel(
        deterministic.medicines, gpt.medicines,
        { it.label }, { it.inferred },
        { det, gpt -> det.copy(
            amount = det.amount ?: gpt.amount,
            startAtIso = det.startAtIso ?: gpt.startAtIso,
            reliefScale = if (det.reliefScale == "NONE" || det.reliefScale == null) gpt.reliefScale else det.reliefScale,
            sideEffectScale = if (det.sideEffectScale == "NONE" || det.sideEffectScale == null) gpt.sideEffectScale else det.sideEffectScale,
            sideEffectNotes = det.sideEffectNotes ?: gpt.sideEffectNotes,
            inferred = det.inferred && gpt.inferred
        )}
    )

    val reliefItems = mergeByLabel(
        deterministic.reliefs, gpt.reliefs,
        { it.label }, { it.inferred },
        { det, gpt -> det.copy(
            startAtIso = det.startAtIso ?: gpt.startAtIso,
            endAtIso = det.endAtIso ?: gpt.endAtIso,
            reliefScale = if (det.reliefScale == "NONE" || det.reliefScale == null) gpt.reliefScale else det.reliefScale,
            sideEffectScale = if (det.sideEffectScale == "NONE" || det.sideEffectScale == null) gpt.sideEffectScale else det.sideEffectScale,
            sideEffectNotes = det.sideEffectNotes ?: gpt.sideEffectNotes,
            inferred = det.inferred && gpt.inferred
        )}
    )

    return CheckInParseResult(triggers, prodromes, medicines, reliefItems)
}

private fun <T> pickBest(a: AiParsedField<T>?, b: AiParsedField<T>?): AiParsedField<T>? {
    if (a == null) return b
    if (b == null) return a
    // Prefer explicit (not inferred) over inferred
    return if (!a.inferred) a else if (!b.inferred) b else a
}

private fun mergeFieldLists(
    a: List<AiParsedField<String>>,
    b: List<AiParsedField<String>>
): List<AiParsedField<String>> {
    val map = mutableMapOf<String, AiParsedField<String>>()
    for (f in a) map[f.value] = f
    for (f in b) {
        val existing = map[f.value]
        if (existing == null || (existing.inferred && !f.inferred)) {
            map[f.value] = f
        }
    }
    return map.values.toList()
}
