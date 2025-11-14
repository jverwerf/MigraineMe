package com.migraineme

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Writes wearable-agnostic daily metrics into tables.
 * Tables:
 * - sleep_duration_daily(date, value_hours,  source, source_measure_id, user_id default auth.uid())
 * - fell_asleep_time_daily(date, value_at,   source, source_measure_id, user_id default auth.uid())
 * - sleep_disturbances_daily(date, value_count, source, source_measure_id, user_id default auth.uid())
 * - sleep_performance_daily(date, value_pct, source, source_measure_id, user_id default auth.uid())
 * - sleep_stages_daily(date, value_sws_ms, value_rem_ms, value_light_ms, source, source_measure_id, user_id default auth.uid())  <-- NEW
 * Unique (user_id, source, date).
 */
class SupabaseMetricsService(context: Context) {
    private val supabaseUrl = BuildConfig.SUPABASE_URL
    private val supabaseKey = BuildConfig.SUPABASE_ANON_KEY
    private val appContext = context.applicationContext

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                }
            )
        }
    }

    @Serializable private data class DurationRow(
        val date: String,
        val value_hours: Double,
        val source: String,
        val source_measure_id: String? = null
    )
    @Serializable private data class FellAsleepRow(
        val date: String,
        val value_at: String,
        val source: String,
        val source_measure_id: String? = null
    )
    @Serializable private data class DisturbancesRow(
        val date: String,
        val value_count: Int,
        val source: String,
        val source_measure_id: String? = null
    )
    @Serializable private data class PerformanceRow(
        val date: String,
        val value_pct: Double,
        val source: String,
        val source_measure_id: String? = null
    )
    @Serializable private data class StagesRow( // NEW
        val date: String,
        val value_sws_ms: Long,
        val value_rem_ms: Long,
        val value_light_ms: Long,
        val source: String,
        val source_measure_id: String? = null
    )

    suspend fun upsertSleepDurationDaily(
        accessToken: String, date: String, valueHours: Double, source: String, sourceId: String?
    ) {
        upsert(accessToken, "sleep_duration_daily", DurationRow(date, valueHours, source, sourceId))
    }

    suspend fun upsertFellAsleepTimeDaily(
        accessToken: String, date: String, valueAtIso: String, source: String, sourceId: String?
    ) {
        upsert(accessToken, "fell_asleep_time_daily", FellAsleepRow(date, valueAtIso, source, sourceId))
    }

    suspend fun upsertSleepDisturbancesDaily(
        accessToken: String, date: String, count: Int, source: String, sourceId: String?
    ) {
        upsert(accessToken, "sleep_disturbances_daily", DisturbancesRow(date, count, source, sourceId))
    }

    suspend fun upsertSleepPerformanceDaily(
        accessToken: String, date: String, pct: Double, source: String, sourceId: String?
    ) {
        upsert(accessToken, "sleep_performance_daily", PerformanceRow(date, pct, source, sourceId))
    }

    // NEW: SWS/REM/Light in one row
    suspend fun upsertSleepStagesDaily(
        accessToken: String, date: String, swsMs: Long, remMs: Long, lightMs: Long, source: String, sourceId: String?
    ) {
        upsert(accessToken, "sleep_stages_daily", StagesRow(date, swsMs, remMs, lightMs, source, sourceId))
    }

    private suspend inline fun <reified T> upsert(accessToken: String, table: String, row: T) {
        val response = client.post("$supabaseUrl/rest/v1/$table") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "resolution=merge-duplicates,return=representation")
            parameter("on_conflict", "user_id,source,date")
            contentType(ContentType.Application.Json) // required for List<T> serialization
            setBody(listOf(row))
        }
        if (!response.status.isSuccess()) {
            val bodyTxt = runCatching { response.body<String>() }.getOrNull()
            error("Upsert $table failed: HTTP ${response.status.value} ${bodyTxt ?: ""}".trim())
        }
    }
}
