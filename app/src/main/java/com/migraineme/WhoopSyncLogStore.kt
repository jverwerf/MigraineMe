package com.migraineme

import android.content.Context
import org.json.JSONObject
import java.time.Instant

/**
 * Rolling log + per-table daily sync outcomes for WHOOP.
 *
 * Rolling log:
 * - append(), get(), clear() operate on a single text blob (capped)
 *
 * Per-table outcomes (NEW):
 * - Stored per day ("YYYY-MM-DD") as JSON in SharedPreferences
 * - Allows UI to distinguish: couldn't fetch vs no data vs stored, per table
 */
class WhoopSyncLogStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // -------------------------
    // Rolling log (existing)
    // -------------------------

    fun append(line: String) {
        val ts = Instant.now().toString()
        val entry = "$ts | $line\n"
        val cur = prefs.getString(KEY_LOG, "") ?: ""
        var out = cur + entry
        if (out.length > MAX_CHARS) {
            out = out.substring(out.length - MAX_CHARS)
            val idx = out.indexOf('\n')
            if (idx >= 0) out = out.substring(idx + 1)
        }
        prefs.edit().putString(KEY_LOG, out).apply()
    }

    fun get(): String? = prefs.getString(KEY_LOG, null)

    /**
     * Clears ONLY the rolling log. Outcomes are kept.
     */
    fun clear() {
        prefs.edit().remove(KEY_LOG).apply()
    }

    // -------------------------
    // Per-table outcomes (NEW)
    // -------------------------

    enum class TableOutcomeType {
        FETCH_OK_STORED,
        FETCH_OK_NO_DATA,
        FETCH_FAILED
    }

    data class TableOutcome(
        val type: TableOutcomeType,
        val atIso: String,
        val note: String?
    )

    /**
     * Record an outcome for a specific table + date.
     *
     * date: "YYYY-MM-DD"
     * table: e.g. "spo2_daily"
     */
    fun setOutcome(
        date: String,
        table: String,
        type: TableOutcomeType,
        note: String? = null
    ) {
        val key = outcomesKey(date)
        val root = loadOutcomesJson(date)

        val entry = JSONObject()
            .put("type", type.name)
            .put("at", Instant.now().toString())

        if (!note.isNullOrBlank()) entry.put("note", note)

        root.put(table, entry)
        prefs.edit().putString(key, root.toString()).apply()
    }

    /**
     * Read outcome for one table on a given date.
     * Returns null if we never recorded an attempt (NOT_TRIED is implicit).
     */
    fun getOutcome(date: String, table: String): TableOutcome? {
        val root = loadOutcomesJson(date)
        if (!root.has(table)) return null

        val entry = root.optJSONObject(table) ?: return null
        val typeStr = entry.optString("type", "")
        val atIso = entry.optString("at", "")
        val note = entry.optString("note", null)

        val type = runCatching { TableOutcomeType.valueOf(typeStr) }.getOrNull() ?: return null
        if (atIso.isBlank()) return null

        return TableOutcome(type = type, atIso = atIso, note = note)
    }

    /**
     * Get all recorded outcomes for a date.
     * Tables never attempted won't appear in the map.
     */
    fun getOutcomesForDate(date: String): Map<String, TableOutcome> {
        val root = loadOutcomesJson(date)
        val out = mutableMapOf<String, TableOutcome>()

        val keys = root.keys()
        while (keys.hasNext()) {
            val table = keys.next()
            val entry = root.optJSONObject(table) ?: continue

            val typeStr = entry.optString("type", "")
            val atIso = entry.optString("at", "")
            val note = entry.optString("note", null)

            val type = runCatching { TableOutcomeType.valueOf(typeStr) }.getOrNull() ?: continue
            if (atIso.isBlank()) continue

            out[table] = TableOutcome(type = type, atIso = atIso, note = note)
        }

        return out
    }

    fun clearOutcomesForDate(date: String) {
        prefs.edit().remove(outcomesKey(date)).apply()
    }

    fun clearAllOutcomes() {
        val editor = prefs.edit()
        for (k in prefs.all.keys) {
            if (k.startsWith(KEY_OUTCOMES_PREFIX)) editor.remove(k)
        }
        editor.apply()
    }

    private fun outcomesKey(date: String): String = "$KEY_OUTCOMES_PREFIX$date"

    private fun loadOutcomesJson(date: String): JSONObject {
        val raw = prefs.getString(outcomesKey(date), null) ?: return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    companion object {
        private const val PREFS = "whoop_sync_log_prefs"
        private const val KEY_LOG = "log"
        private const val MAX_CHARS = 8000
        private const val KEY_OUTCOMES_PREFIX = "outcomes_"
    }
}
