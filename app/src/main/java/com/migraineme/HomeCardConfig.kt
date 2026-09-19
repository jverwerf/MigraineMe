package com.migraineme

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Configuration for Home tab cards - visibility and order.
 * Mirrors InsightsCardConfig / MonitorCardConfig. The trial banner and the
 * recalibration banner (pinned top), the medical disclaimer and the Customize
 * entry row (pinned bottom) are deliberately NOT part of this config.
 */
@Serializable
data class HomeCardConfig(
    val cardOrder: List<String> = DEFAULT_ORDER,
    val hiddenCards: Set<String> = emptySet()
) {
    companion object {
        // Card identifiers — stable English ids, never shown
        /** The slot that holds the in-progress attack card, or the quick-log strip. */
        const val CARD_QUICKLOG = "quicklog"
        const val CARD_RISK = "risk"
        const val CARD_LOCATION = "location"
        const val CARD_ASK = "ask"
        const val CARD_WELLDONE = "welldone"
        const val CARD_INSIGHT = "insight"
        const val CARD_CONTRIBUTORS = "contributors"
        const val CARD_EXERCISES = "exercises"

        // Default order = the fixed order Home had before it became configurable,
        // with Exercises placed right after the risk gauge.
        val DEFAULT_ORDER = listOf(
            CARD_QUICKLOG,
            CARD_RISK,
            CARD_EXERCISES,
            CARD_LOCATION,
            CARD_ASK,
            CARD_WELLDONE,
            CARD_INSIGHT,
            CARD_CONTRIBUTORS
        )

        /** Logging and today's risk are what Home is for: reorderable, never hideable. */
        val ALWAYS_VISIBLE = setOf(CARD_QUICKLOG, CARD_RISK)

        /** Cards that can carry the Brainy watermark: the LAST visible one in the user's order gets it. */
        val WATERMARK_CARDS = setOf(CARD_ASK, CARD_INSIGHT, CARD_CONTRIBUTORS)

        /** English labels — where a Home card already shows a title, the same literal, so t() reuses its translation. */
        val CARD_LABELS = mapOf(
            CARD_QUICKLOG to "Quick log",
            CARD_RISK to "Risk today",
            CARD_LOCATION to "Location reminder",
            CARD_ASK to "Ask MigraineMe",
            CARD_WELLDONE to "Well done",
            CARD_INSIGHT to "MigraineMe Recommendation",
            CARD_CONTRIBUTORS to "Top contributors",
            CARD_EXERCISES to "Exercises"
        )
    }

    fun canHide(cardId: String): Boolean = cardId !in ALWAYS_VISIBLE

    fun isVisible(cardId: String): Boolean = cardId in ALWAYS_VISIBLE || cardId !in hiddenCards

    fun getOrderedVisibleCards(): List<String> {
        return cardOrder.filter { isVisible(it) }
    }

    fun toggleVisibility(cardId: String): HomeCardConfig {
        if (!canHide(cardId)) return this
        val newHidden = if (cardId in hiddenCards) {
            hiddenCards - cardId
        } else {
            hiddenCards + cardId
        }
        return copy(hiddenCards = newHidden)
    }

    fun moveCard(fromIndex: Int, toIndex: Int): HomeCardConfig {
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
 * Store for Home card configuration (SharedPreferences, per device — same as Insights / Monitor)
 */
object HomeCardConfigStore {
    private const val PREFS_NAME = "home_card_config"
    private const val KEY_CONFIG = "config_json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(context: Context): HomeCardConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_CONFIG, null)

        val config = if (jsonStr != null) {
            try {
                json.decodeFromString<HomeCardConfig>(jsonStr)
            } catch (e: Exception) {
                HomeCardConfig()
            }
        } else {
            HomeCardConfig()
        }
        // Migration: drop ids we no longer know, append new card types at the end.
        // distinct() guards a hand-edited or corrupted list: a repeated id would
        // crash the keyed loop on Home.
        val known = config.cardOrder.filter { it in HomeCardConfig.DEFAULT_ORDER }.distinct()
        val missing = HomeCardConfig.DEFAULT_ORDER.filter { it !in known }
        val hidden = config.hiddenCards - HomeCardConfig.ALWAYS_VISIBLE
        return if (missing.isNotEmpty() || known.size != config.cardOrder.size || hidden.size != config.hiddenCards.size) {
            config.copy(cardOrder = known + missing, hiddenCards = hidden)
        } else config
    }

    fun save(context: Context, config: HomeCardConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = json.encodeToString(config)
        prefs.edit().putString(KEY_CONFIG, jsonStr).apply()
    }
}
