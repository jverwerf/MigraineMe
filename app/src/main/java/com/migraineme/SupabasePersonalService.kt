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
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONArray

class SupabasePersonalService(context: Context) {
    private val supabaseUrl = BuildConfig.SUPABASE_URL
    private val supabaseKey = BuildConfig.SUPABASE_ANON_KEY

    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    @Serializable
    private data class LocationWriteRow(
        val date: String,
        val latitude: Double,
        val longitude: Double,
        val source: String,
        val source_measure_id: String? = null,
        val timezone: String? = null
    )

    @Serializable
    private data class LocationHourlyWriteRow(
        val timestamp: String,
        val latitude: Double,
        val longitude: Double,
        val source: String = "device",
        val timezone: String? = null
    )

    @Serializable
    data class UserLocationDailyRead(
        val date: String,
        val latitude: Double,
        val longitude: Double,
        val source: String? = null,
        val source_measure_id: String? = null,
        val timezone: String? = null
    )

    @Serializable
    private data class ScreenTimeDailyWrite(
        val date: String,
        val total_hours: Double,
        val source: String,
        val quality_flags: Map<String, String>? = null
    )

    @Serializable
    private data class ScreenTimeLiveWrite(
        val date: String,
        val value_hours: Double,
        val app_count: Int?,
        val source: String,
        val timezone: String? = null
    )

    @Serializable
    private data class ScreenTimeLateNightWrite(
        val date: String,
        val value_hours: Double,
        val app_count: Int?,
        val source: String,
        val timezone: String? = null
    )

    @Serializable
    private data class PhoneSleepDurationWrite(
        val date: String,
        val value_hours: Double,
        val source: String = "phone",
        val source_measure_id: String? = null
    )

    @Serializable
    private data class PhoneSleepTimeWrite(
        val date: String,
        val value_at: String,
        val source: String = "phone",
        val source_measure_id: String? = null
    )

    suspend fun upsertUserLocationDaily(
        accessToken: String,
        date: String,
        latitude: Double,
        longitude: Double,
        source: String = "device",
        sourceId: String? = null,
        timezone: String? = null
    ) {
        val row = LocationWriteRow(date, latitude, longitude, source, sourceId, timezone)
        postgrestInsert(
            accessToken = accessToken,
            table = "user_location_daily",
            body = listOf(row),
            onConflict = "user_id,source,date"
        )
    }

    /**
     * Insert hourly location data.
     * Uses timestamp with timezone for precise tracking.
     */
    suspend fun insertUserLocationHourly(
        accessToken: String,
        timestamp: String,
        latitude: Double,
        longitude: Double,
        source: String = "device",
        timezone: String? = null
    ) {
        val row = LocationHourlyWriteRow(timestamp, latitude, longitude, source, timezone)
        postgrestInsert(
            accessToken = accessToken,
            table = "user_location_hourly",
            body = listOf(row),
            onConflict = "user_id,timestamp"
        )
    }

    suspend fun fetchUserLocationDaily(
        accessToken: String,
        limitDays: Int = 14
    ): List<UserLocationDailyRead> {
        val resp = client.get("$supabaseUrl/rest/v1/user_location_daily") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "date,latitude,longitude,source,source_measure_id")
            parameter("order", "date.desc")
            parameter("limit", limitDays.toString())
        }
        if (!resp.status.isSuccess()) return emptyList()
        return runCatching { resp.body<List<UserLocationDailyRead>>() }.getOrNull() ?: emptyList()
    }

    suspend fun hasUserLocationForDate(
        accessToken: String,
        date: String,
        source: String = "device"
    ): Boolean {
        val resp = client.get("$supabaseUrl/rest/v1/user_location_daily") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("date", "eq.$date")
            parameter("source", "eq.$source")
            parameter("select", "date")
            parameter("limit", "1")
        }
        if (!resp.status.isSuccess()) return false
        val txt = resp.bodyAsText().trim()
        return txt.startsWith("[{")
    }

    suspend fun latestUserLocationDate(
        accessToken: String,
        source: String = "device"
    ): String? {
        val resp = client.get("$supabaseUrl/rest/v1/user_location_daily") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "date")
            parameter("source", "eq.$source")
            parameter("order", "date.desc")
            parameter("limit", "1")
        }
        if (!resp.status.isSuccess()) return null
        val body = resp.bodyAsText().trim()
        if (body.isEmpty() || body == "[]") return null
        return try {
            val arr = JSONArray(body)
            if (arr.length() == 0) null else arr.getJSONObject(0).getString("date")
        } catch (_: Throwable) { null }
    }

    suspend fun earliestUserLocationDate(
        accessToken: String,
        source: String = "device"
    ): String? {
        val resp = client.get("$supabaseUrl/rest/v1/user_location_daily") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "date")
            parameter("source", "eq.$source")
            parameter("order", "date.asc")
            parameter("limit", "1")
        }
        if (!resp.status.isSuccess()) return null
        val body = resp.bodyAsText().trim()
        if (body.isEmpty() || body == "[]") return null
        return try {
            val arr = JSONArray(body)
            if (arr.length() == 0) null else arr.getJSONObject(0).getString("date")
        } catch (_: Throwable) { null }
    }

    // ========== SCREEN TIME METHODS ==========

    /**
     * Upsert daily screen time directly to screen_time_daily table.
     * No intermediate processing needed - Android already computed the daily total.
     */
    suspend fun upsertScreenTimeDaily(
        accessToken: String,
        date: String,
        totalHours: Double,
        source: String = "android",
        qualityFlags: Map<String, String>? = null
    ) {
        val row = ScreenTimeDailyWrite(date, totalHours, source, qualityFlags)
        postgrestInsert(
            accessToken = accessToken,
            table = "screen_time_daily",
            body = listOf(row),
            onConflict = "user_id,date,source"
        )
    }

    /**
     * Upsert live screen time for today.
     * This is called hourly by FCM and overwrites the same row for today.
     */
    suspend fun upsertScreenTimeLive(
        accessToken: String,
        date: String,
        totalHours: Double,
        appCount: Int? = null,
        source: String = "android",
        timezone: String? = null
    ) {
        val row = ScreenTimeLiveWrite(date, totalHours, appCount, source, timezone)
        postgrestInsert(
            accessToken = accessToken,
            table = "screen_time_live",
            body = listOf(row),
            onConflict = "user_id,date"
        )
    }

    suspend fun latestScreenTimeDate(
        accessToken: String,
        source: String = "android"
    ): String? {
        val resp = client.get("$supabaseUrl/rest/v1/screen_time_daily") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "date")
            parameter("source", "eq.$source")
            parameter("order", "date.desc")
            parameter("limit", "1")
        }
        if (!resp.status.isSuccess()) return null
        val body = resp.bodyAsText().trim()
        if (body.isEmpty() || body == "[]") return null
        return try {
            val arr = JSONArray(body)
            if (arr.length() == 0) null else arr.getJSONObject(0).getString("date")
        } catch (_: Throwable) { null }
    }

    // ========== LATE-NIGHT SCREEN TIME METHODS ==========

    /**
     * Upsert late-night screen time (22:00–06:00 window).
     * Date represents the evening date (22:00 on this date through 06:00 next day).
     */
    suspend fun upsertScreenTimeLateNight(
        accessToken: String,
        date: String,
        totalHours: Double,
        appCount: Int? = null,
        source: String = "android",
        timezone: String? = null
    ) {
        val row = ScreenTimeLateNightWrite(date, totalHours, appCount, source, timezone)
        postgrestInsert(
            accessToken = accessToken,
            table = "screen_time_late_night",
            body = listOf(row),
            onConflict = "user_id,date,source"
        )
    }

    suspend fun latestScreenTimeLateNightDate(
        accessToken: String,
        source: String = "android"
    ): String? {
        val resp = client.get("$supabaseUrl/rest/v1/screen_time_late_night") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "date")
            parameter("source", "eq.$source")
            parameter("order", "date.desc")
            parameter("limit", "1")
        }
        if (!resp.status.isSuccess()) return null
        val body = resp.bodyAsText().trim()
        if (body.isEmpty() || body == "[]") return null
        return try {
            val arr = JSONArray(body)
            if (arr.length() == 0) null else arr.getJSONObject(0).getString("date")
        } catch (_: Throwable) { null }
    }

    // ========== PHONE SLEEP METHODS ==========

    /**
     * Upsert phone-estimated sleep data to sleep tables with source = "phone".
     * Writes to three tables: sleep_duration_daily, fell_asleep_time_daily, woke_up_time_daily.
     */
    suspend fun upsertPhoneSleepData(
        accessToken: String,
        date: String,
        durationHours: Double,
        fellAsleepIso: String,
        wokeUpIso: String,
        timezone: String? = null
    ) {
        // 1. Sleep duration
        postgrestInsert(
            accessToken = accessToken,
            table = "sleep_duration_daily",
            body = listOf(PhoneSleepDurationWrite(date, durationHours)),
            onConflict = "user_id,source,date"
        )

        // 2. Fell asleep time
        postgrestInsert(
            accessToken = accessToken,
            table = "fell_asleep_time_daily",
            body = listOf(PhoneSleepTimeWrite(date, fellAsleepIso)),
            onConflict = "user_id,source,date"
        )

        // 3. Woke up time
        postgrestInsert(
            accessToken = accessToken,
            table = "woke_up_time_daily",
            body = listOf(PhoneSleepTimeWrite(date, wokeUpIso)),
            onConflict = "user_id,source,date"
        )
    }

    /**
     * Get latest phone sleep date from sleep_duration_daily where source = "phone".
     */
    suspend fun latestPhoneSleepDate(
        accessToken: String
    ): String? {
        val resp = client.get("$supabaseUrl/rest/v1/sleep_duration_daily") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "date")
            parameter("source", "eq.phone")
            parameter("order", "date.desc")
            parameter("limit", "1")
        }
        if (!resp.status.isSuccess()) return null
        val body = resp.bodyAsText().trim()
        if (body.isEmpty() || body == "[]") return null
        return try {
            val arr = JSONArray(body)
            if (arr.length() == 0) null else arr.getJSONObject(0).getString("date")
        } catch (_: Throwable) { null }
    }

    private suspend inline fun <reified T> postgrestInsert(
        accessToken: String,
        table: String,
        body: T,
        onConflict: String? = null
    ) {
        val resp = client.post("$supabaseUrl/rest/v1/$table") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "resolution=merge-duplicates,return=minimal")
            onConflict?.let { parameter("on_conflict", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!resp.status.isSuccess()) {
            val msg = runCatching { resp.bodyAsText() }.getOrNull()
            error("Upsert into $table failed: HTTP ${resp.status.value} ${msg ?: ""}".trim())
        }
    }
}