// FILE: app/src/main/java/com/migraineme/SupabasePhysicalHealthService.kt
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
 * Supabase service for Physical Health metrics.
 * Mirrors SupabaseMetricsServiceSleep.kt (structure, behavior, upsert style).
 *
 * Tables:
 * - recovery_score_daily
 * - resting_hr_daily
 * - hrv_daily
 * - skin_temp_daily
 * - spo2_daily
 * - time_in_high_hr_zones_daily
 * - stress_index_daily
 *
 * NOTE:
 * - time_in_high_hr_zones_daily now also carries "activities" fields:
 *   activity_type, start_at, end_at, plus zone_zero/one/two minutes when available.
 *
 * Unique key everywhere: (user_id, source, date)
 */
class SupabasePhysicalHealthService(context: Context) {

    private val supabaseUrl = BuildConfig.SUPABASE_URL
    private val supabaseKey = BuildConfig.SUPABASE_ANON_KEY

    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    /* ============================================================
     * READ DTOs
     * ============================================================
     */

    @Serializable
    data class RecoveryScoreDailyRead(
        val date: String,
        @SerialName("value_pct") val value_pct: Double
    )

    @Serializable
    data class RestingHrDailyRead(
        val date: String,
        @SerialName("value_bpm") val value_bpm: Double
    )

    @Serializable
    data class HrvDailyRead(
        val date: String,
        @SerialName("value_rmssd_ms") val value_rmssd_ms: Double
    )

    @Serializable
    data class SkinTempDailyRead(
        val date: String,
        @SerialName("value_celsius") val value_celsius: Double
    )

    @Serializable
    data class Spo2DailyRead(
        val date: String,
        @SerialName("value_pct") val value_pct: Double
    )

    @Serializable
    data class StressIndexDailyRead(
        val date: String,
        @SerialName("value_pct") val value_pct: Double,
        val source: String? = null,
        @SerialName("updated_at") val updated_at: String? = null
    )

    @Serializable
    data class HighHrZonesDailyRead(
        val date: String,
        @SerialName("value_minutes") val value_minutes: Double,

        // These are the "high HR" zones used for totals
        @SerialName("zone_three_minutes") val zone_three_minutes: Double,
        @SerialName("zone_four_minutes") val zone_four_minutes: Double,
        @SerialName("zone_five_minutes") val zone_five_minutes: Double,
        @SerialName("zone_six_minutes") val zone_six_minutes: Double,

        // Optional (present when you store full zone splits / activities)
        @SerialName("zone_zero_minutes") val zone_zero_minutes: Double? = null,
        @SerialName("zone_one_minutes") val zone_one_minutes: Double? = null,
        @SerialName("zone_two_minutes") val zone_two_minutes: Double? = null,

        @SerialName("activity_type") val activity_type: String? = null,
        @SerialName("start_at") val start_at: String? = null,
        @SerialName("end_at") val end_at: String? = null,

        @SerialName("source_measure_id") val source_measure_id: String? = null,
        @SerialName("source") val source: String? = null
    )

    /* ============================================================
     * WRITER DTOs
     * ============================================================
     */

    @Serializable private data class PctRow(
        val date: String,
        val value_pct: Double,
        val source: String? = null,
        val source_measure_id: String? = null
    )

    @Serializable private data class HrRow(
        val date: String,
        val value_bpm: Double,
        val source: String? = null,
        val source_measure_id: String? = null
    )

    @Serializable private data class HrvRow(
        val date: String,
        val value_rmssd_ms: Double,
        val source: String? = null,
        val source_measure_id: String? = null
    )

    @Serializable private data class TempRow(
        val date: String,
        val value_celsius: Double,
        val source: String? = null,
        val source_measure_id: String? = null
    )

    @Serializable private data class Spo2Row(
        val date: String,
        val value_pct: Double,
        val source: String? = null,
        val source_measure_id: String? = null
    )

    @Serializable private data class HighHrRow(
        val date: String,
        val value_minutes: Double,
        val zone_three_minutes: Double,
        val zone_four_minutes: Double,
        val zone_five_minutes: Double,
        val zone_six_minutes: Double,
        val source: String? = null,
        val source_measure_id: String? = null
    )

    /* ============================================================
     * GENERIC FETCHER
     * ============================================================
     */

    private suspend inline fun <reified T> getList(
        endpoint: String,
        access: String,
        select: String,
        limit: Int,
        order: String = "date.desc"
    ): List<T> {
        val resp = client.get(endpoint) {
            header(HttpHeaders.Authorization, "Bearer $access")
            header("apikey", supabaseKey)
            parameter("select", select)
            parameter("order", order)
            parameter("limit", limit.toString())
        }
        if (!resp.status.isSuccess()) return emptyList()
        return runCatching { resp.body<List<T>>() }.getOrDefault(emptyList())
    }

    /* ============================================================
     * PUBLIC FETCH API
     * ============================================================
     */

    suspend fun fetchRecoveryScoreDaily(access: String, days: Int = 14): List<RecoveryScoreDailyRead> =
        getList("$supabaseUrl/rest/v1/recovery_score_daily", access, "date,value_pct", days)

    suspend fun fetchRestingHrDaily(access: String, days: Int = 14): List<RestingHrDailyRead> =
        getList("$supabaseUrl/rest/v1/resting_hr_daily", access, "date,value_bpm", days)

    suspend fun fetchHrvDaily(access: String, days: Int = 14): List<HrvDailyRead> =
        getList("$supabaseUrl/rest/v1/hrv_daily", access, "date,value_rmssd_ms", days)

    suspend fun fetchSkinTempDaily(access: String, days: Int = 14): List<SkinTempDailyRead> =
        getList("$supabaseUrl/rest/v1/skin_temp_daily", access, "date,value_celsius", days)

    suspend fun fetchSpo2Daily(access: String, days: Int = 14): List<Spo2DailyRead> =
        getList("$supabaseUrl/rest/v1/spo2_daily", access, "date,value_pct", days)

    suspend fun fetchStressIndexDaily(access: String, days: Int = 30): List<StressIndexDailyRead> =
        getList("$supabaseUrl/rest/v1/stress_index_daily", access, "date,value_pct,source,updated_at", days)

    /**
     * Daily-style fetch (still ordered by date.desc).
     * We also select activity + zone0..2 fields so TestingScreenComplete can display "activities"
     * from the SAME table without changing auth patterns.
     */
    suspend fun fetchHighHrDaily(access: String, days: Int = 14): List<HighHrZonesDailyRead> =
        getList(
            "$supabaseUrl/rest/v1/time_in_high_hr_zones_daily",
            access,
            "date,value_minutes,zone_zero_minutes,zone_one_minutes,zone_two_minutes,zone_three_minutes,zone_four_minutes,zone_five_minutes,zone_six_minutes,activity_type,start_at,end_at,source,source_measure_id",
            days
        )

    /**
     * Activities-style view: same table, just ordered by start_at (most recent first).
     */
    suspend fun fetchHighHrActivities(access: String, limitRows: Int = 50): List<HighHrZonesDailyRead> =
        getList(
            "$supabaseUrl/rest/v1/time_in_high_hr_zones_daily",
            access,
            "date,value_minutes,zone_zero_minutes,zone_one_minutes,zone_two_minutes,zone_three_minutes,zone_four_minutes,zone_five_minutes,zone_six_minutes,activity_type,start_at,end_at,source,source_measure_id",
            limitRows,
            order = "start_at.desc"
        )

    /* ============================================================
     * CONFLICT CHECKERS
     * ============================================================
     */

    suspend fun hasRecoveryForDate(access: String, date: String, source: String): Boolean {
        val resp = client.get("$supabaseUrl/rest/v1/recovery_score_daily") {
            header(HttpHeaders.Authorization, "Bearer $access")
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

    suspend fun latestPhysicalDate(access: String, source: String): String? {
        val resp = client.get("$supabaseUrl/rest/v1/recovery_score_daily") {
            header(HttpHeaders.Authorization, "Bearer $access")
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

    /* ============================================================
     * UPSERTS
     * ============================================================
     */

    suspend fun upsertRecoveryScoreDaily(
        access: String, date: String, valuePct: Double, source: String?, sourceId: String?
    ) = upsert(access, "recovery_score_daily", PctRow(date, valuePct, source, sourceId))

    suspend fun upsertRestingHrDaily(
        access: String, date: String, bpm: Double, source: String?, sourceId: String?
    ) = upsert(access, "resting_hr_daily", HrRow(date, bpm, source, sourceId))

    suspend fun upsertHrvDaily(
        access: String, date: String, rmssd: Double, source: String?, sourceId: String?
    ) = upsert(access, "hrv_daily", HrvRow(date, rmssd, source, sourceId))

    suspend fun upsertSkinTempDaily(
        access: String, date: String, celsius: Double, source: String?, sourceId: String?
    ) = upsert(access, "skin_temp_daily", TempRow(date, celsius, source, sourceId))

    suspend fun upsertSpo2Daily(
        access: String, date: String, pct: Double, source: String?, sourceId: String?
    ) = upsert(access, "spo2_daily", Spo2Row(date, pct, source, sourceId))

    suspend fun upsertHighHrDaily(
        access: String,
        date: String,
        totalMinutes: Double,
        z3: Double,
        z4: Double,
        z5: Double,
        z6: Double,
        source: String?,
        sourceId: String?
    ) = upsert(
        access,
        "time_in_high_hr_zones_daily",
        HighHrRow(date, totalMinutes, z3, z4, z5, z6, source, sourceId)
    )

    private suspend inline fun <reified T> upsert(
        accessToken: String,
        table: String,
        row: T
    ) {
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
}
