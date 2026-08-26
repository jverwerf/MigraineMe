package com.migraineme

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Configuration for Insights tab cards - visibility and order.
 * Mirrors MonitorCardConfig. Full Report (pinned top) and the medical
 * disclaimer (pinned bottom) are deliberately NOT part of this config.
 */
@Serializable
data class InsightsCardConfig(
    val cardOrder: List<String> = DEFAULT_ORDER,
    val hiddenCards: Set<String> = emptySet()
) {
    companion object {
        // Card identifiers (shared ids with iOS / VertigoMe / MeSeries)
        const val CARD_RECOMMENDATIONS = "recommendations"
        const val CARD_ACCURACY = "accuracy"
        const val CARD_PATTERNS = "patterns"
        const val CARD_TREATMENTS = "treatments"
        const val CARD_HELPING = "helping"
        const val CARD_CHANGES = "changes"
        const val CARD_CONTEXT = "context"
        const val CARD_IMPACT = "impact"

        // Default order = the fixed order the tab had before it became configurable
        val DEFAULT_ORDER = listOf(
            CARD_RECOMMENDATIONS,
            CARD_ACCURACY,
            CARD_PATTERNS,
            CARD_TREATMENTS,
            CARD_HELPING,
            CARD_CHANGES,
            CARD_CONTEXT,
            CARD_IMPACT
        )

        /** English labels — same literals the Insights tab cards use, so t() translates them. */
        val CARD_LABELS = mapOf(
            CARD_RECOMMENDATIONS to "Recommendations",
            CARD_ACCURACY to "Accuracy",
            CARD_PATTERNS to "What Happened",
            CARD_TREATMENTS to "What Worked",
            CARD_HELPING to "What Strengthens You",
            CARD_CHANGES to "What changed",
            CARD_CONTEXT to "What Were You Doing",
            CARD_IMPACT to "How Did It Impact You"
        )
    }

    fun isVisible(cardId: String): Boolean = cardId !in hiddenCards

    fun getOrderedVisibleCards(): List<String> {
        return cardOrder.filter { it !in hiddenCards }
    }

    fun toggleVisibility(cardId: String): InsightsCardConfig {
        val newHidden = if (cardId in hiddenCards) {
            hiddenCards - cardId
        } else {
            hiddenCards + cardId
        }
        return copy(hiddenCards = newHidden)
    }

    fun moveCard(fromIndex: Int, toIndex: Int): InsightsCardConfig {
        if (fromIndex == toIndex) return this
        if (fromIndex < 0 || fromIndex >= cardOrder.size) return this
        if (toIndex < 0 || toIndex >= cardOrder.size) return this

        val mutableList = cardOrder.toMutableList()
        val item = mutableList.removeAt(fromIndex)
        mutableList.add(toIndex, item)
        return copy(cardOrder = mutableList)
    }
}

/**
 * Store for Insights card configuration (SharedPreferences, per device — same as Monitor)
 */
object InsightsCardConfigStore {
    private const val PREFS_NAME = "insights_card_config"
    private const val KEY_CONFIG = "config_json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(context: Context): InsightsCardConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_CONFIG, null)

        val config = if (jsonStr != null) {
            try {
                json.decodeFromString<InsightsCardConfig>(jsonStr)
            } catch (e: Exception) {
                InsightsCardConfig()
            }
        } else {
            InsightsCardConfig()
        }
        // Migration: drop ids we no longer know, append new card types at the end
        val known = config.cardOrder.filter { it in InsightsCardConfig.DEFAULT_ORDER }
        val missing = InsightsCardConfig.DEFAULT_ORDER.filter { it !in known }
        return if (missing.isNotEmpty() || known.size != config.cardOrder.size) {
            config.copy(cardOrder = known + missing)
        } else config
    }

    fun save(context: Context, config: InsightsCardConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = json.encodeToString(config)
        prefs.edit().putString(KEY_CONFIG, jsonStr).apply()
    }
}
