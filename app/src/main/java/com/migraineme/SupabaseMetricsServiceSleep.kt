// FILE: app/src/main/java/com/migraineme/SupabaseMetricsServiceSleep.kt
package com.migraineme

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Supabase metrics writer + reader for sleep tables.
 * (Same behavior as before, plus score/efficiency.)
 *
 * Tables:
 * - sleep_duration_daily(date, value_hours, source, source_measure_id, user_id default auth.uid())
 * - fell_asleep_time_daily(date, value_at, source, source_measure_id, user_id default auth.uid())
 * - woke_up_time_daily(date, value_at, source, source_measure_id, user_id default auth.uid())
 * - sleep_disturbances_daily(date, value_count, source, source_measure_id, user_id default auth.uid())
 * - sleep_stages_daily(date, value_sws_hm, value_rem_hm, value_light_hm, source, source_measure_id, user_id default auth.uid())
 * - sleep_score_daily(date, value_pct, source, source_measure_id, user_id default auth.uid())
 * - sleep_efficiency_daily(date, value_pct, source, source_measure_id, user_id default auth.uid())
 * Unique key everywhere: (user_id, source, date)
 */
class SupabaseMetricsService(context: Context) {

    private val supabaseUrl = BuildConfig.SUPABASE_URL
    private val supabaseKey = BuildConfig.SUPABASE_ANON_KEY

    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    /* ============ READ DTOs ============ */

    @Serializable
    data class SleepDurationDailyRead(val date: String, @SerialName("value_hours") val value_hours: Double)

    @Serializable
    data class SleepDisturbancesDailyRead(val date: String, @SerialName("value_count") val value_count: Int)

    @Serializable
    data class SleepStagesDailyRead(
        val date: String,
        @SerialName("value_sws_hm") val value_sws_hm: Double,
        @SerialName("value_rem_hm") val value_rem_hm: Double,
        @SerialName("value_light_hm") val value_light_hm: Double
    )

    @Serializable
    data class SleepScoreDailyRead(val date: String, @SerialName("value_pct") val value_pct: Double)

    @Serializable
    data class SleepEfficiencyDailyRead(val date: String, @SerialName("value_pct") val value_pct: Double)

    /* ============ WRITER DTOs ============ */

    @Serializable private data class DurationRow(
        val date: String,
        val value_hours: Double,
        val source: String? = null,
        val source_measure_id: String? = null
    )

    @Serializable private data class FellAsleepRow(
        val date: String,
        val value_at: String,
        val source: String? = null,
        val source_measure_id: String? = null
    )

    @Serializable private data class WakeRow(
        val date: String,
        val value_at: String,
        val source: String? = null,
        val source_measure_id: String? = null
    )

    @Serializable private data class DisturbancesRow(
        val date: String,
        val value_count: Int,
        val source: String? = null,
        val source_measure_id: String? = null
    )

    @Serializable private data class StagesRow(
        val date: String,
        val value_sws_hm: Double,
        val value_rem_hm: Double,
        val value_light_hm: Double,
        val source: String? = null,
        val source_measure_id: String? = null
    )

    @Serializable private data class PctRow(
        val date: String,
        val value_pct: Double,
        val source: String? = null,
        val source_measure_id: String? = null
    )

    /* ============ FETCHERS ============ */

    suspend fun fetchSleepDurationDaily(accessToken: String, limitDays: Int = 14): List<SleepDurationDailyRead> {
        return getList("$supabaseUrl/rest/v1/sleep_duration_daily", accessToken, "date,value_hours", limitDays)
    }

    suspend fun fetchSleepDisturbancesDaily(accessToken: String, limitDays: Int = 14): List<SleepDisturbancesDailyRead> {
        return getList("$supabaseUrl/rest/v1/sleep_disturbances_daily", accessToken, "date,value_count", limitDays)
    }

    suspend fun fetchSleepStagesDaily(accessToken: String, limitDays: Int = 14): List<SleepStagesDailyRead> {
        return getList(
            "$supabaseUrl/rest/v1/sleep_stages_daily",
            accessToken,
            "date,value_sws_hm,value_rem_hm,value_light_hm",
            limitDays
        )
    }

    suspend fun fetchSleepScoreDaily(accessToken: String, limitDays: Int = 14): List<SleepScoreDailyRead> {
        return getList("$supabaseUrl/rest/v1/sleep_score_daily", accessToken, "date,value_pct", limitDays)
    }

    suspend fun fetchSleepEfficiencyDaily(accessToken: String, limitDays: Int = 14): List<SleepEfficiencyDailyRead> {
        return getList("$supabaseUrl/rest/v1/sleep_efficiency_daily", accessToken, "date,value_pct", limitDays)
    }

    private suspend inline fun <reified T> getList(
        endpoint: String,
        access: String,
        select: String,
        limit: Int
    ): List<T> {
        val resp = client.get(endpoint) {
            header(HttpHeaders.Authorization, "Bearer $access")
            header("apikey", supabaseKey)
            parameter("select", select)
            parameter("order", "date.desc")
            parameter("limit", limit.toString())
        }
        if (!resp.status.isSuccess()) return emptyList()
        return runCatching { resp.body<List<T>>() }.getOrDefault(emptyList())
    }

    /** True if a sleep row already exists for date & source in sleep_duration_daily. */
    suspend fun hasSleepForDate(accessToken: String, date: String, source: String): Boolean {
        val resp = client.get("$supabaseUrl/rest/v1/sleep_duration_daily") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("date", "eq.$date")
            parameter("source", "eq.$source")
            parameter("select", "date")
            parameter("limit", "1")
        }
        if (!resp.status.isSuccess()) return false
        val body = runCatching { resp.body<List<Map<String, String>>>() }.getOrDefault(emptyList())
        return body.isNotEmpty()
    }

    /** Latest date we have for WHOOP in sleep_duration_daily (used as backfill anchor). */
    suspend fun latestSleepDate(accessToken: String, source: String): String? {
        val resp = client.get("$supabaseUrl/rest/v1/sleep_duration_daily") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("source", "eq.$source")
            parameter("select", "date")
            parameter("order", "date.desc")
            parameter("limit", "1")
        }
        if (!resp.status.isSuccess()) return null
        val rows = runCatching { resp.body<List<Map<String, String>>>() }.getOrDefault(emptyList())
        return rows.firstOrNull()?.get("date")
    }

    /* ============ UPSERTS ============ */

    suspend fun upsertSleepDurationDaily(
        accessToken: String, date: String, valueHours: Double, source: String?, sourceId: String?
    ) { upsert(accessToken, "sleep_duration_daily", DurationRow(date, valueHours, source, sourceId)) }

    suspend fun upsertFellAsleepTimeDaily(
        accessToken: String, date: String, valueAtIso: String, source: String?, sourceId: String?
    ) { upsert(accessToken, "fell_asleep_time_daily", FellAsleepRow(date, valueAtIso, source, sourceId)) }

    suspend fun upsertWokeUpTimeDaily(
        accessToken: String, date: String, valueAtIso: String, source: String?, sourceId: String?
    ) { upsert(accessToken, "woke_up_time_daily", WakeRow(date, valueAtIso, source, sourceId)) }

    suspend fun upsertSleepDisturbancesDaily(
        accessToken: String, date: String, count: Int, source: String?, sourceId: String?
    ) { upsert(accessToken, "sleep_disturbances_daily", DisturbancesRow(date, count, source, sourceId)) }

    suspend fun upsertSleepStagesDaily(
        accessToken: String, date: String, swsMs: Long, remMs: Long, lightMs: Long, source: String?, sourceId: String?
    ) {
        val row = StagesRow(
            date = date,
            value_sws_hm = msToHM(swsMs),
            value_rem_hm = msToHM(remMs),
            value_light_hm = msToHM(lightMs),
            source = source,
            source_measure_id = sourceId
        )
        upsert(accessToken, "sleep_stages_daily", row)
    }

    suspend fun upsertSleepScoreDaily(
        accessToken: String, date: String, pct: Double, source: String?, sourceId: String?
    ) { upsert(accessToken, "sleep_score_daily", PctRow(date, pct, source, sourceId)) }

    suspend fun upsertSleepEfficiencyDaily(
        accessToken: String, date: String, pct: Double, source: String?, sourceId: String?
    ) { upsert(accessToken, "sleep_efficiency_daily", PctRow(date, pct, source, sourceId)) }

    private suspend inline fun <reified T> upsert(accessToken: String, table: String, row: T) {
        val resp = client.post("$supabaseUrl/rest/v1/$table") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "resolution=merge-duplicates,return=minimal")
            parameter("on_conflict", "user_id,source,date")
            contentType(ContentType.Application.Json)
            setBody(listOf(row))
        }
        if (!resp.status.isSuccess()) {
            error("Upsert $table failed: HTTP ${resp.status.value}")
        }
    }

    private fun msToHM(ms: Long): Double {
        val totalMinutes = (ms / 60000).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return hours + (minutes / 100.0)
    }
}
