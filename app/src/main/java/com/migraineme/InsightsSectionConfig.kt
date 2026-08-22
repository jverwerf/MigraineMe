package com.migraineme

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Per-page configuration for the sections INSIDE an Insights detail page
 * (What Happened, What Worked, ...): visibility and order. Same mechanism as
 * InsightsCardConfig (the tab-level cards) and MonitorCardConfig, keyed by page.
 * Section ids are shared with iOS / VertigoMe / MeSeries.
 */
@Serializable
data class InsightsSectionConfig(
    val order: List<String> = emptyList(),
    val hidden: Set<String> = emptySet()
) {
    fun isVisible(id: String): Boolean = id !in hidden

    fun orderedVisible(): List<String> = order.filter { it !in hidden }

    fun toggleVisibility(id: String): InsightsSectionConfig {
        val newHidden = if (id in hidden) hidden - id else hidden + id
        return copy(hidden = newHidden)
    }

    fun moveSection(fromIndex: Int, toIndex: Int): InsightsSectionConfig {
        if (fromIndex == toIndex) return this
        if (fromIndex < 0 || fromIndex >= order.size) return this
        if (toIndex < 0 || toIndex >= order.size) return this
        val mutableList = order.toMutableList()
        val item = mutableList.removeAt(fromIndex)
        mutableList.add(toIndex, item)
        return copy(order = mutableList)
    }
}

/** Registry of pages + their configurable sections (default order = today's order). */
object InsightsSections {
    const val PAGE_PATTERNS = "patterns"
    const val PAGE_TREATMENTS = "treatments"
    const val PAGE_HELPING = "helping"
    const val PAGE_IMPACT = "impact"
    const val PAGE_CHANGES = "changes"
    const val PAGE_ACCURACY = "accuracy"
    const val PAGE_RECOMMENDATIONS = "recommendations"

    // What Happened
    const val PATTERNS_TOP = "top_patterns"
    const val PATTERNS_INTRADAY = "intraday"
    const val PATTERNS_TRIGGER_SYMPTOM = "trigger_symptom"

    // What Worked
    const val TREATMENTS_WHAT_WORKED = "what_worked"
    const val TREATMENTS_PAIN_RESPONSE = "pain_response"
    const val TREATMENTS_USED_TOGETHER = "used_together"
    const val TREATMENTS_WORKS_BEST_WHEN = "works_best_when"

    // What's Helping
    const val HELPING_DIRECT = "helping"
    const val HELPING_HABITS_WHY = "habits_why"

    // How Did It Impact You
    const val IMPACT_SEVERITY = "severity"
    const val IMPACT_PAIN_LOCATIONS = "pain_locations"
    const val IMPACT_AURA = "aura"
    const val IMPACT_SYMPTOMS = "symptoms"
    const val IMPACT_SEVERITY_PREDICTOR = "severity_predictor"
    const val IMPACT_PAIN_MIGRATION = "pain_migration"
    const val IMPACT_MISSED_ACTIVITIES = "missed_activities"

    // What changed
    const val CHANGES_MIGRAINES = "migraines"
    const val CHANGES_ITEMS = "items"
    const val CHANGES_HABITS = "habits"

    // Accuracy
    const val ACCURACY_GAUGE = "gauge"
    const val ACCURACY_MATRIX = "matrix"

    // Recommendations: the category cards (summary card stays pinned on top).
    // Ids = the category keys buildRecommendationSections() emits.
    const val RECS_TRIGGERS = "triggers"
    const val RECS_PRODROMES = "prodromes"
    const val RECS_MEDICINES = "medicines"
    const val RECS_RELIEFS = "reliefs"
    const val RECS_ACTIVITIES = "activities"

    val DEFAULT_SECTIONS: Map<String, List<String>> = mapOf(
        PAGE_PATTERNS to listOf(PATTERNS_TOP, PATTERNS_INTRADAY, PATTERNS_TRIGGER_SYMPTOM),
        PAGE_TREATMENTS to listOf(
            TREATMENTS_WHAT_WORKED, TREATMENTS_PAIN_RESPONSE,
            TREATMENTS_USED_TOGETHER, TREATMENTS_WORKS_BEST_WHEN
        ),
        PAGE_HELPING to listOf(HELPING_DIRECT, HELPING_HABITS_WHY),
        PAGE_IMPACT to listOf(
            IMPACT_SEVERITY, IMPACT_PAIN_LOCATIONS, IMPACT_AURA, IMPACT_SYMPTOMS,
            IMPACT_SEVERITY_PREDICTOR, IMPACT_PAIN_MIGRATION, IMPACT_MISSED_ACTIVITIES
        ),
        PAGE_CHANGES to listOf(CHANGES_MIGRAINES, CHANGES_ITEMS, CHANGES_HABITS),
        PAGE_ACCURACY to listOf(ACCURACY_GAUGE, ACCURACY_MATRIX),
        PAGE_RECOMMENDATIONS to listOf(
            RECS_TRIGGERS, RECS_PRODROMES, RECS_MEDICINES, RECS_RELIEFS, RECS_ACTIVITIES
        ),
    )

    /**
     * English labels — the same literals the section headings already render,
     * so t() translates them. Only "Logged items" is new (that card has no heading).
     */
    val SECTION_LABELS: Map<String, Map<String, String>> = mapOf(
        PAGE_PATTERNS to mapOf(
            PATTERNS_TOP to "Patterns",
            PATTERNS_INTRADAY to "Pain response",
            PATTERNS_TRIGGER_SYMPTOM to "What These Triggers Do to You",
        ),
        PAGE_TREATMENTS to mapOf(
            TREATMENTS_WHAT_WORKED to "What Worked",
            TREATMENTS_PAIN_RESPONSE to "Pain response",
            TREATMENTS_USED_TOGETHER to "Used Together",
            TREATMENTS_WORKS_BEST_WHEN to "Works Best When…",
        ),
        PAGE_HELPING to mapOf(
            HELPING_DIRECT to "Well Done",
            HELPING_HABITS_WHY to "What Drives It",
        ),
        PAGE_IMPACT to mapOf(
            IMPACT_SEVERITY to "Severity",
            IMPACT_PAIN_LOCATIONS to "Pain Locations",
            IMPACT_AURA to "Your Aura",
            IMPACT_SYMPTOMS to "Your Symptoms",
            IMPACT_SEVERITY_PREDICTOR to "What tends to run worse",
            IMPACT_PAIN_MIGRATION to "How your pain moves",
            IMPACT_MISSED_ACTIVITIES to "Missed Activities",
        ),
        PAGE_CHANGES to mapOf(
            CHANGES_MIGRAINES to "Your migraines",
            CHANGES_ITEMS to "Logged items",
            CHANGES_HABITS to "Daily habits",
        ),
        PAGE_ACCURACY to mapOf(
            ACCURACY_GAUGE to "Gauge Accuracy",
            ACCURACY_MATRIX to "Detailed Breakdown",
        ),
        PAGE_RECOMMENDATIONS to mapOf(
            RECS_TRIGGERS to "Triggers",
            RECS_PRODROMES to "Prodromes",
            RECS_MEDICINES to "Medicines",
            RECS_RELIEFS to "Reliefs",
            RECS_ACTIVITIES to "Activities",
        ),
    )

    /** English page titles — the same literals the Insights tab / top bar use. */
    val PAGE_TITLES: Map<String, String> = mapOf(
        PAGE_PATTERNS to "What Happened",
        PAGE_TREATMENTS to "What Worked",
        PAGE_HELPING to "What's Helping",
        PAGE_IMPACT to "How Did It Impact You",
        PAGE_CHANGES to "What changed",
        PAGE_ACCURACY to "Accuracy",
        PAGE_RECOMMENDATIONS to "Recommendations",
    )

    /** The page's small Brainy blob, used on every row of its config list. */
    fun smallIcon(page: String): Int = when (page) {
        PAGE_PATTERNS -> R.drawable.brainy_detective_small
        PAGE_TREATMENTS -> R.drawable.brainy_shield_small
        PAGE_HELPING -> R.drawable.brainy_gardener_small
        PAGE_IMPACT -> R.drawable.brainy_recover_small
        PAGE_CHANGES -> R.drawable.brainy_risk_small
        PAGE_ACCURACY -> R.drawable.brainy_archer_small
        PAGE_RECOMMENDATIONS -> R.drawable.brainy_recs_small
        else -> R.drawable.brainy_detective_small
    }
}

/**
 * Store for per-page section configuration (SharedPreferences, per device —
 * same as the Monitor and Insights card configs). One JSON entry per page id.
 */
object InsightsSectionConfigStore {
    private const val PREFS_NAME = "insights_section_config"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(context: Context, page: String): InsightsSectionConfig {
        val defaults = InsightsSections.DEFAULT_SECTIONS[page] ?: emptyList()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(page, null)
        val config = if (jsonStr != null) {
            try {
                json.decodeFromString<InsightsSectionConfig>(jsonStr)
            } catch (e: Exception) {
                InsightsSectionConfig()
            }
        } else {
            InsightsSectionConfig()
        }
        // Migration: drop ids we no longer know, append new sections at the end
        val known = config.order.filter { it in defaults }
        val missing = defaults.filter { it !in known }
        return config.copy(order = known + missing)
    }

    fun save(context: Context, page: String, config: InsightsSectionConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(page, json.encodeToString(config)).apply()
    }
}
