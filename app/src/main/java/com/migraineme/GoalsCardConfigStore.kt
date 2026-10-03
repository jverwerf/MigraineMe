package com.migraineme

import android.content.Context

/**
 * Which goals the Monitor "Goals" card shows. Same shape as
 * PhysicalCardConfigStore: one comma-joined string of ids in SharedPreferences.
 */
object GoalsCardConfigStore {
    private const val PREFS_NAME = "goals_card_config"
    private const val KEY_DISPLAY_GOALS = "goals_display_ids"
    /** How many goals the card shows when nothing was picked. */
    const val DEFAULT_GOALS = 3

    /** The saved ids, in slot order. Empty when nothing was ever picked. */
    fun load(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DISPLAY_GOALS, null).orEmpty().split(",").filter { it.isNotBlank() }
    }

    fun save(context: Context, ids: List<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_DISPLAY_GOALS, ids.joinToString(","))
            .apply()
    }

    /** The goals to show on the card: the saved picks that still exist, in slot
     *  order; when none are saved or none survive, the first three goals. */
    fun displayGoals(context: Context, goals: List<GoalRow>): List<GoalRow> {
        val byId = goals.associateBy { it.id }
        return load(context).mapNotNull { byId[it] }
            .ifEmpty { orderedGoals(goals).take(DEFAULT_GOALS) }
    }
}
