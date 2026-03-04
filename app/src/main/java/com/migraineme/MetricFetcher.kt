package com.migraineme

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId

/**
 * MetricFetcher — Generic Supabase REST fetcher for metric data.
 *
 * Replaces all typed per-metric fetch functions (fetchHrvDaily, fetchRecoveryScoreDaily, etc.)
 * and their typed DTOs (HrvDailyRead, RecoveryScoreDailyRead, etc.) for Monitor and graph use.
 *
 * NOTE: The typed service classes (SupabasePhysicalHealthService, SupabaseMetricsServiceSleep, etc.)
 * are NOT deleted — they're still used for writing/upserting data and for non-Monitor screens.
 * This fetcher is read-only and used specifically by the dynamic Monitor/graph pipeline.
 */
object MetricFetcher {

    private const val TAG = "MetricFetcher"

    data class DailyValue(val date: String, val value: Double)

    // ── Single-day fetch (Monitor cards: today's data) ───────────────────────

    /**
     * Fetch a single day's value for one metric.
     *
     * @return The value, or null if no data for that date.
     */
    suspend fun fetchSingle(
        token: String,
        table: String,
        column: String,
        unit: String,
        date: String
    ): Double? = withContext(Dispatchers.IO) {
        val userId = SessionStore.readUserId(AppContext.app) ?: return@withContext null
        val client = buildOkHttpClient()
        try {
            val url = "${BuildConfig.SUPABASE_URL}/rest/v1/$table" +
                "?user_id=eq.$userId&date=eq.$date&select=$column&limit=1"
            val request = okhttp3.Request.Builder().url(url).get()
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful || body.isNullOrBlank()) return@withContext null

            val arr = JSONArray(body)
            if (arr.length() == 0) return@withContext null
            val obj = arr.getJSONObject(0)

            if (unit == "time") {
                parseTimestampToDecimalHours(obj.optString(column, ""), shiftPastNoon = false)
            } else {
                obj.optDouble(column).takeIf { !it.isNaN() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchSingle $table.$column failed: ${e.message}")
            null
        }
    }

    /**
     * Fetch a single day's values for multiple columns from one table.
     * Used for multi-column tables (weather, sleep_stages, nutrition, blood_pressure).
     *
     * @return Map of column_name → value
     */
    suspend fun fetchSingleMulti(
        token: String,
        table: String,
        columns: List<String>,
        date: String
    ): Map<String, Double?> = withContext(Dispatchers.IO) {
        val userId = SessionStore.readUserId(AppContext.app) ?: return@withContext emptyMap()
        val client = buildOkHttpClient()
        try {
            val select = columns.joinToString(",")
            val url = "${BuildConfig.SUPABASE_URL}/rest/v1/$table" +
                "?user_id=eq.$userId&date=eq.$date&select=$select&limit=1"
            val request = okhttp3.Request.Builder().url(url).get()
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful || body.isNullOrBlank()) return@withContext emptyMap()

            val arr = JSONArray(body)
            if (arr.length() == 0) return@withContext emptyMap()
            val obj = arr.getJSONObject(0)

            columns.associateWith { col ->
                obj.optDouble(col).takeIf { !it.isNaN() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchSingleMulti $table failed: ${e.message}")
            emptyMap()
        }
    }

    // ── Multi-day fetch (Graphs: date range) ─────────────────────────────────

    /**
     * Fetch a range of daily values for one metric.
     *
     * @param days Number of days to fetch (from most recent)
     * @return List of (date, value) pairs sorted by date descending
     */
    suspend fun fetchDaily(
        token: String,
        table: String,
        column: String,
        unit: String,
        days: Int
    ): List<DailyValue> = withContext(Dispatchers.IO) {
        val userId = SessionStore.readUserId(AppContext.app) ?: return@withContext emptyList()
        val client = buildOkHttpClient()
        try {
            val url = "${BuildConfig.SUPABASE_URL}/rest/v1/$table" +
                "?user_id=eq.$userId&select=date,$column&order=date.desc&limit=$days"
            val request = okhttp3.Request.Builder().url(url).get()
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful || body.isNullOrBlank()) return@withContext emptyList()

            val arr = JSONArray(body)
            if (unit == "time") {
                parseTimestampArray(arr, column, shiftPastNoon = table.contains("fell_asleep"))
            } else {
                parseDoubleArray(arr, column)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchDaily $table.$column failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetch a range of daily values for multiple columns from one table.
     * Groups the result by column.
     *
     * @return Map of column_name → list of (date, value)
     */
    suspend fun fetchDailyMulti(
        token: String,
        table: String,
        columns: List<String>,
        units: Map<String, String>,
        days: Int
    ): Map<String, List<DailyValue>> = withContext(Dispatchers.IO) {
        val userId = SessionStore.readUserId(AppContext.app) ?: return@withContext emptyMap()
        val client = buildOkHttpClient()
        try {
            val select = (listOf("date") + columns).joinToString(",")
            val url = "${BuildConfig.SUPABASE_URL}/rest/v1/$table" +
                "?user_id=eq.$userId&select=$select&order=date.desc&limit=$days"
            val request = okhttp3.Request.Builder().url(url).get()
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (!response.isSuccessful || body.isNullOrBlank()) return@withContext emptyMap()

            val arr = JSONArray(body)
            val result = mutableMapOf<String, MutableList<DailyValue>>()
            for (col in columns) {
                result[col] = mutableListOf()
            }

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val date = obj.optString("date", "").takeIf { it.length >= 10 } ?: continue
                for (col in columns) {
                    val unit = units[col] ?: ""
                    val value = if (unit == "time") {
                        val ts = obj.optString(col, "").takeIf { it.isNotBlank() } ?: continue
                        parseTimestampToDecimalHours(ts, shiftPastNoon = table.contains("fell_asleep"))
                    } else {
                        obj.optDouble(col).takeIf { !it.isNaN() }
                    }
                    if (value != null) {
                        result[col]?.add(DailyValue(date, value))
                    }
                }
            }

            result
        } catch (e: Exception) {
            Log.w(TAG, "fetchDailyMulti $table failed: ${e.message}")
            emptyMap()
        }
    }

    // ── Group fetch (Monitor cards: all metrics in a group for one date) ─────

    /**
     * Fetch today's values for ALL metrics in a monitor group.
     * Groups by table to minimise HTTP requests.
     *
     * @return Map of metric.key → value (Double)
     */
    suspend fun fetchGroupForDate(
        token: String,
        group: String,
        date: String
    ): Map<String, Double?> = withContext(Dispatchers.IO) {
        val metrics = MetricRegistry.byGroup(group)
        if (metrics.isEmpty()) return@withContext emptyMap()

        val result = mutableMapOf<String, Double?>()

        // Group metrics by table for efficient fetching
        val byTable = metrics.groupBy { it.table }

        for ((table, tableMetrics) in byTable) {
            try {
                if (tableMetrics.size == 1 && !MetricRegistry.isMultiColumnTable(table)) {
                    // Single-column table: simple fetch
                    val m = tableMetrics.first()
                    val companions = MetricRegistry.companionColumns(m.table, m.column)
                    if (companions.isEmpty()) {
                        result[m.key] = fetchSingle(token, table, m.column, m.unit, date)
                    } else {
                        // Fetch primary + companions in one query
                        val allCols = listOf(m.column) + companions
                        val values = fetchSingleMulti(token, table, allCols, date)
                        result[m.key] = values[m.column]
                        // Store companions with a special key format for the screen to access
                        for (comp in companions) {
                            result["$table::$comp"] = values[comp]
                        }
                    }
                } else {
                    // Multi-column table: fetch all columns in one query
                    val columns = tableMetrics.map { it.column }.distinct()
                    // Also fetch companion columns
                    val allCompanions = tableMetrics.flatMap {
                        MetricRegistry.companionColumns(it.table, it.column)
                    }.distinct()
                    val allColumns = (columns + allCompanions).distinct()

                    val values = fetchSingleMulti(token, table, allColumns, date)
                    for (m in tableMetrics) {
                        val rawValue = values[m.column]
                        // Time-type metrics need timestamp parsing; fetchSingleMulti returns raw doubles
                        // For time columns we need a separate path
                        if (m.unit == "time") {
                            result[m.key] = fetchSingle(token, table, m.column, m.unit, date)
                        } else {
                            result[m.key] = rawValue
                        }
                    }
                    // Store companions
                    for (comp in allCompanions) {
                        result["$table::$comp"] = values[comp]
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchGroupForDate $table failed: ${e.message}")
                // Continue with other tables
            }
        }

        result
    }

    /**
     * Fetch multi-day values for ALL metrics in a monitor group.
     * Used by history graphs.
     *
     * @return Map of metric.key → list of (date, value)
     */
    suspend fun fetchGroupForRange(
        token: String,
        group: String,
        days: Int
    ): Map<String, List<DailyValue>> = withContext(Dispatchers.IO) {
        val metrics = MetricRegistry.graphableByGroup(group)
        if (metrics.isEmpty()) return@withContext emptyMap()

        val result = mutableMapOf<String, List<DailyValue>>()

        // Group by table
        val byTable = metrics.groupBy { it.table }

        for ((table, tableMetrics) in byTable) {
            try {
                val companions = tableMetrics.flatMap {
                    MetricRegistry.companionColumns(it.table, it.column)
                }.distinct()

                if (tableMetrics.size == 1 && companions.isEmpty() && !MetricRegistry.isMultiColumnTable(table)) {
                    // Simple: one column, one fetch
                    val m = tableMetrics.first()
                    result[m.key] = fetchDaily(token, table, m.column, m.unit, days)
                } else {
                    // Multi-column: fetch all in one query
                    val columns = tableMetrics.map { it.column }.distinct()
                    val allColumns = (columns + companions).distinct()
                    val units = tableMetrics.associate { it.column to it.unit } +
                        companions.associateWith { "" } // companions are always numeric

                    val multiResult = fetchDailyMulti(token, table, allColumns, units, days)
                    for (m in tableMetrics) {
                        result[m.key] = multiResult[m.column] ?: emptyList()
                    }
                    // Store companion data
                    for (comp in companions) {
                        result["$table::$comp"] = multiResult[comp] ?: emptyList()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchGroupForRange $table failed: ${e.message}")
            }
        }

        result
    }

    // ── Parsing helpers ──────────────────────────────────────────────────────

    private fun parseDoubleArray(arr: JSONArray, column: String): List<DailyValue> {
        val list = mutableListOf<DailyValue>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val date = obj.optString("date", "").takeIf { it.length >= 10 } ?: continue
            val value = obj.optDouble(column)
            if (!value.isNaN()) {
                list.add(DailyValue(date, value))
            }
        }
        return list
    }

    private fun parseTimestampArray(
        arr: JSONArray,
        column: String,
        shiftPastNoon: Boolean
    ): List<DailyValue> {
        val list = mutableListOf<DailyValue>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val date = obj.optString("date", "").takeIf { it.length >= 10 } ?: continue
            val ts = obj.optString(column, "").takeIf { it.isNotBlank() } ?: continue
            val hours = parseTimestampToDecimalHours(ts, shiftPastNoon)
            if (hours != null) {
                list.add(DailyValue(date, hours))
            }
        }
        return list
    }

    /**
     * Parse an ISO timestamp string to decimal hours in local timezone.
     * e.g. "2024-01-15T23:30:00Z" → 23.5
     *
     * @param shiftPastNoon If true, hours < 12 get +24 (for bedtimes past midnight)
     */
    private fun parseTimestampToDecimalHours(ts: String, shiftPastNoon: Boolean): Double? {
        return try {
            val inst = Instant.parse(ts)
            val zoned = inst.atZone(ZoneId.systemDefault())
            var hours = zoned.hour + zoned.minute / 60.0
            if (shiftPastNoon && hours < 12.0) hours += 24.0
            hours
        } catch (_: Exception) {
            null
        }
    }

    // ── HTTP client ──────────────────────────────────────────────────────────

    private fun buildOkHttpClient(): okhttp3.OkHttpClient = okhttp3.OkHttpClient()
}
