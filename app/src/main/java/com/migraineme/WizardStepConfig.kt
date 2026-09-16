package com.migraineme

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The user's full-log wizard pages: which are shown and in what order.
 *
 * Keys are shared with the iOS and VertigoMe apps, so they must not change.
 * Review is not in the list: it is always shown and always last.
 */
@Serializable
data class WizardSteps(
    val order: List<String> = WizardStepConfig.DEFAULT_ORDER,
    val hidden: Set<String> = emptySet()
) {
    fun isVisible(key: String): Boolean = key !in hidden

    fun toggleVisibility(key: String): WizardSteps =
        if (key in WizardStepConfig.ALWAYS_SHOWN) this
        else copy(hidden = if (key in hidden) hidden - key else hidden + key)

    /**
     * Normalised order and hidden set, with unknown keys dropped. Notes is
     * always shown, so a stored hidden entry for it is ignored.
     */
    fun normalized(): WizardSteps = WizardSteps(
        order = WizardStepConfig.normalize(order),
        hidden = hidden.filter { it in WizardStepConfig.DEFAULT_ORDER && it !in WizardStepConfig.ALWAYS_SHOWN }.toSet()
    )
}

object WizardStepConfig {
    const val TIMING = "timing"
    const val PAINT = "paint"
    const val SYMPTOMS = "symptoms"
    const val PAIN = "pain"
    const val PRODROMES = "prodromes"
    const val TRIGGERS = "triggers"
    const val MEDICINES = "medicines"
    const val RELIEFS = "reliefs"
    const val LOCATIONS = "locations"
    const val ACTIVITIES = "activities"
    const val POSTDROME = "postdrome"
    const val MISSED = "missed"
    const val NOTES = "notes"

    /** Pages with no show/hide control: they are always in the walk. */
    val ALWAYS_SHOWN: Set<String> = setOf(NOTES)

    val DEFAULT_ORDER: List<String> = listOf(
        TIMING, PAINT, SYMPTOMS, PAIN, PRODROMES, TRIGGERS, MEDICINES,
        RELIEFS, LOCATIONS, ACTIVITIES, POSTDROME, MISSED, NOTES
    )

    val KEY_TO_ROUTE: Map<String, String> = mapOf(
        TIMING to Routes.TIMING,
        PAINT to Routes.PAINT_PICTURE,
        SYMPTOMS to Routes.LOG_MIGRAINE,
        PAIN to Routes.PAIN_LOCATION,
        PRODROMES to Routes.PRODROMES_LOG,
        TRIGGERS to Routes.TRIGGERS,
        MEDICINES to Routes.MEDICINES,
        RELIEFS to Routes.RELIEFS,
        LOCATIONS to Routes.LOCATIONS,
        ACTIVITIES to Routes.ACTIVITIES,
        POSTDROME to Routes.POSTDROMES,
        MISSED to Routes.MISSED_ACTIVITIES,
        NOTES to Routes.NOTES
    )

    private val ROUTE_TO_KEY: Map<String, String> = KEY_TO_ROUTE.entries.associate { (k, v) -> v to k }

    /** English titles; they are translation keys, render with t(). */
    val KEY_TO_TITLE: Map<String, String> = mapOf(
        TIMING to "Timing",
        PAINT to "Paint the Picture",
        SYMPTOMS to "Symptoms",
        PAIN to "Pain",
        PRODROMES to "Prodromes",
        TRIGGERS to "Triggers",
        MEDICINES to "Medicines",
        RELIEFS to "Reliefs",
        LOCATIONS to "Locations",
        ACTIVITIES to "Activities",
        POSTDROME to "Postdrome",
        MISSED to "Missed Activities",
        NOTES to "Notes"
    )

    /**
     * Pure. Drops unknown and duplicate keys, puts missing known keys back at
     * their default position, then moves any non-timing key sitting above
     * paint to directly below paint, keeping relative order. Paint prefills
     * later pages, so only timing may come before it.
     */
    fun normalize(stored: List<String>): List<String> {
        val result = stored.filter { it in DEFAULT_ORDER }.distinct().toMutableList()

        // Missing keys go back after their nearest default predecessor that is present.
        DEFAULT_ORDER.forEachIndexed { defaultIndex, key ->
            if (key in result) return@forEachIndexed
            val predecessor = DEFAULT_ORDER.subList(0, defaultIndex).lastOrNull { it in result }
            val insertAt = if (predecessor == null) 0 else result.indexOf(predecessor) + 1
            result.add(insertAt, key)
        }

        val paintIndex = result.indexOf(PAINT)
        val above = result.subList(0, paintIndex).filter { it != TIMING }
        if (above.isEmpty()) return result
        val rest = result.filter { it !in above }.toMutableList()
        rest.addAll(rest.indexOf(PAINT) + 1, above)
        return rest
    }

    // ── Storage (on-device, same pattern as MonitorCardConfigStore) ─────────

    private const val PREFS_NAME = "wizard_step_config"
    private const val KEY_CONFIG = "config_json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(context: Context): WizardSteps {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_CONFIG, null)
        val config = if (jsonStr != null) {
            try {
                json.decodeFromString<WizardSteps>(jsonStr)
            } catch (e: Exception) {
                WizardSteps()
            }
        } else {
            WizardSteps()
        }
        return config.normalized()
    }

    fun save(context: Context, config: WizardSteps) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CONFIG, json.encodeToString(config.normalized())).apply()
    }

    // ── Per-run surfacing ────────────────────────────────────────────────────
    //
    // If Paint the Picture fills in data for a page the user has hidden, that
    // page is shown in this run's walk so they can see and check it. Only what
    // Paint changes counts: data prefilled from an existing log never surfaces
    // a page. In memory, one wizard run.

    private val surfaced = mutableSetOf<String>()
    private var paintSnapshot: Draft? = null
    private var leftPaintForward = false

    /** Call when a wizard run starts and when it closes or saves. */
    fun startRun() {
        surfaced.clear()
        paintSnapshot = null
        leftPaintForward = false
    }

    /** Call when the user arrives on Paint the Picture. */
    fun onPaintArrive(draft: Draft) {
        paintSnapshot = draft
        leftPaintForward = false
    }

    /** Call when the user leaves Paint the Picture forward (Next or Skip), before navigating. */
    fun onPaintLeaveForward(draft: Draft, postdromeLabels: Set<String>) {
        leftPaintForward = true
        recordPaintChanges(draft, postdromeLabels)
    }

    /**
     * Call after Paint writes parsed data into the draft. Next can navigate
     * before a parse started on that tap has finished, so a late write still
     * surfaces its pages.
     */
    fun onPaintDraftInjected(draft: Draft, postdromeLabels: Set<String>) {
        if (leftPaintForward) recordPaintChanges(draft, postdromeLabels)
    }

    private fun recordPaintChanges(draft: Draft, postdromeLabels: Set<String>) {
        val before = paintSnapshot ?: return
        surfaced += changedPages(before, draft, postdromeLabels) - ALWAYS_SHOWN
    }

    /**
     * Pure. Page keys whose content differs between two drafts. Fields are
     * grouped by the page that edits them: aura is edited on the Symptoms page,
     * postdrome symptoms share migraine.symptoms and are told apart by label.
     */
    fun changedPages(before: Draft, after: Draft, postdromeLabels: Set<String>): Set<String> {
        val changed = mutableSetOf<String>()
        val b = before.migraine ?: MigraineDraft()
        val a = after.migraine ?: MigraineDraft()

        if (b.beganAtIso != a.beganAtIso || b.endedAtIso != a.endedAtIso) changed += TIMING

        // Symptom labels that were added, removed, or had their intensity/time changed.
        val touched = (b.symptoms.toSet() xor a.symptoms.toSet()) +
            (before.symptomSeverities.keys + after.symptomSeverities.keys)
                .filter { before.symptomSeverities[it] != after.symptomSeverities[it] } +
            (before.symptomTimes.keys + after.symptomTimes.keys)
                .filter { before.symptomTimes[it] != after.symptomTimes[it] }
        if (touched.any { it in postdromeLabels }) changed += POSTDROME
        val auraChanged = before.auraLocations != after.auraLocations ||
            before.auraDurationMinutes != after.auraDurationMinutes ||
            before.auraEntries.map { Triple(it.startAtIso, it.zones, it.durationMinutes) } !=
            after.auraEntries.map { Triple(it.startAtIso, it.zones, it.durationMinutes) }
        if (touched.any { it !in postdromeLabels } || auraChanged) changed += SYMPTOMS

        val painBefore = before.painEntries.map { Triple(it.startAtIso, it.severity, it.locations) }
        val painAfter = after.painEntries.map { Triple(it.startAtIso, it.severity, it.locations) }
        if (painBefore != painAfter || b.severity != a.severity) changed += PAIN

        if (before.prodromes.map { it.copy(existingId = null) } != after.prodromes.map { it.copy(existingId = null) }) changed += PRODROMES
        if (before.triggers.map { it.copy(existingId = null) } != after.triggers.map { it.copy(existingId = null) }) changed += TRIGGERS
        if (before.meds.map { it.copy(existingId = null) } != after.meds.map { it.copy(existingId = null) }) changed += MEDICINES
        if (before.rels.map { it.copy(existingId = null) } != after.rels.map { it.copy(existingId = null) }) changed += RELIEFS
        if (before.locations.map { it.copy(existingId = null) } != after.locations.map { it.copy(existingId = null) }) changed += LOCATIONS
        if (before.activities.map { it.copy(existingId = null) } != after.activities.map { it.copy(existingId = null) }) changed += ACTIVITIES
        if (before.missedActivities.map { it.copy(existingId = null) } != after.missedActivities.map { it.copy(existingId = null) }) changed += MISSED
        return changed
    }

    private infix fun <T> Set<T>.xor(other: Set<T>): Set<T> = (this - other) + (other - this)

    /** Shown in the walk: not hidden, or surfaced by Paint in this run. */
    private fun isShownInRun(config: WizardSteps, key: String): Boolean =
        config.isVisible(key) || key in surfaced

    /**
     * Header back label for a wizard page: the title of the page Back actually
     * returns to, or "Log" when the previous entry is not a wizard page.
     */
    @androidx.compose.runtime.Composable
    fun backLabel(navController: androidx.navigation.NavController): String {
        val previousKey = navController.previousBackStackEntry?.destination?.route?.let { ROUTE_TO_KEY[it] }
        val title = previousKey?.let { KEY_TO_TITLE[it] }
        return if (title != null) t(title) else t("Log")
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    /** First visible page of the full-log wizard, else Review. */
    fun firstRoute(context: Context): String {
        val config = load(context)
        val key = config.order.firstOrNull { isShownInRun(config, it) }
        return key?.let { KEY_TO_ROUTE[it] } ?: Routes.REVIEW
    }

    /**
     * The next visible page after [currentRoute] in the saved order, else Review.
     * Reads the saved config each time, so edits made mid-wizard apply from the
     * current page onward.
     */
    fun nextRoute(context: Context, currentRoute: String): String {
        val config = load(context)
        val currentKey = ROUTE_TO_KEY[currentRoute] ?: return Routes.REVIEW
        val index = config.order.indexOf(currentKey)
        val key = config.order.drop(index + 1).firstOrNull { isShownInRun(config, it) }
        return key?.let { KEY_TO_ROUTE[it] } ?: Routes.REVIEW
    }
}
