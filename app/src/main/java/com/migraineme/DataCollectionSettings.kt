package com.migraineme

import android.content.Context

/**
 * Shared reader for DataSettingsScreen preferences, used by Workers and MetricsSyncManager.
 *
 * Matches DataSettingsScreen key format exactly:
 * - Selected wearable per table: data_source_<table>
 * - Active (non-wearable):      data_active_<table>
 * - Active (wearable):         data_active_<table>_<source>
 *
 * Defaults preserve existing behavior: if unset, tables are treated as ACTIVE.
 */
object DataCollectionSettings {

    private const val PREFS = "data_settings"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun selectedWearable(context: Context, table: String, fallback: String = "whoop"): String {
        val key = "data_source_$table"
        return prefs(context).getString(key, null) ?: fallback
    }

    fun isActive(
        context: Context,
        table: String,
        wearable: String? = null,
        defaultValue: Boolean = true
    ): Boolean {
        val key = activeKey(table, wearable)
        return prefs(context).getBoolean(key, defaultValue)
    }

    /**
     * Returns true only if:
     * - Selected wearable for this table is "whoop"
     * - AND the per-wearable active flag is true (default true if unset)
     */
    fun isEnabledForWhoop(context: Context, table: String, defaultValue: Boolean = true): Boolean {
        val selected = selectedWearable(context, table, fallback = "whoop")
        if (selected != "whoop") return false
        return isActive(context, table, wearable = "whoop", defaultValue = defaultValue)
    }

    private fun activeKey(table: String, wearable: String?): String {
        return if (wearable == null) {
            "data_active_$table"
        } else {
            "data_active_${table}_${wearable}"
        }
    }
}
