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
        val timezone: String? = null,
        val altitude_m: Double? = null,
        val altitude_max_m: Double? = null,
        val altitude_min_m: Double? = null,
        val altitude_change_m: Double? = null
    )

    @Serializable
    private data class LocationHourlyWriteRow(
        val timestamp: String,
        val latitude: Double,
        val longitude: Double,
        val source: String = "device",
        val timezone: String? = null,
        val altitude_m: Double? = null
    )

    @Serializable
    data class UserLocationDailyRead(
        val date: String,
        val latitude: Double,
        val longitude: Double,
        val source: String? = null,
        val source_measure_id: String? = null,
        val timezone: String? = null,
        val altitude_m: Double? = null
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

    @Serializable
    private data class PhoneBrightnessSampleWrite(
        val user_id: String,
        val sampled_at: String,
        val value: Int,
        val source: String = "android"
    )

    @Serializable
    private data class PhoneVolumeSampleWrite(
        val user_id: String,
        val sampled_at: String,
        val value_pct: Int,
        val stream_type: String? = "media",
        val source: String = "android"
    )

    @Serializable
    private data class PhoneDarkModeSampleWrite(
        val user_id: String,
        val sampled_at: String,
        val is_dark: Boolean,
        val source: String = "android"
    )

    @Serializable
    private data class PhoneUnlockSampleWrite(
        val user_id: String,
        val sampled_at: String,
        val value_count: Int,
        val source: String = "android"
    )

    suspend fun upsertUserLocationDaily(
        accessToken: String,
        date: String,
        latitude: Double,
        longitude: Double,
        source: String = "device",
        sourceId: String? = null,
        timezone: String? = null,
        altitudeM: Double? = null,
        altitudeMaxM: Double? = null,
        altitudeMinM: Double? = null,
        altitudeChangeM: Double? = null
    ) {
        val row = LocationWriteRow(date, latitude, longitude, source, sourceId, timezone, altitudeM, altitudeMaxM, altitudeMinM, altitudeChangeM)
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
        timezone: String? = null,
        altitudeM: Double? = null
    ) {
        val row = LocationHourlyWriteRow(timestamp, latitude, longitude, source, timezone, altitudeM)
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
            parameter("select", "date,latitude,longitude,source,source_measure_id,altitude_m")
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

    /**
     * Fetch all hourly altitude readings for a given date.
     * Returns list of altitude values (non-null only).
     * Used to compute running max/min/change for daily aggregation.
     */
    suspend fun fetchHourlyAltitudesForDate(
        accessToken: String,
        date: String
    ): List<Double> {
        val resp = client.get("$supabaseUrl/rest/v1/user_location_hourly") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "altitude_m")
            parameter("altitude_m", "not.is.null")
            // Filter by date portion of timestamp
            parameter("timestamp", "gte.${date}T00:00:00")
            parameter("timestamp", "lt.${date}T23:59:59.999")
            parameter("order", "timestamp.asc")
        }
        if (!resp.status.isSuccess()) return emptyList()
        val body = resp.bodyAsText().trim()
        if (body.isEmpty() || body == "[]") return emptyList()
        return try {
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                if (obj.isNull("altitude_m")) null else obj.getDouble("altitude_m")
            }
        } catch (_: Throwable) {
            emptyList()
        }
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

    suspend fun upsertPhoneSleepData(
        accessToken: String,
        date: String,
        durationHours: Double,
        fellAsleepIso: String,
        wokeUpIso: String,
        timezone: String? = null
    ) {
        postgrestInsert(
            accessToken = accessToken,
            table = "sleep_duration_daily",
            body = listOf(PhoneSleepDurationWrite(date, durationHours)),
            onConflict = "user_id,source,date"
        )
        postgrestInsert(
            accessToken = accessToken,
            table = "fell_asleep_time_daily",
            body = listOf(PhoneSleepTimeWrite(date, fellAsleepIso)),
            onConflict = "user_id,source,date"
        )
        postgrestInsert(
            accessToken = accessToken,
            table = "woke_up_time_daily",
            body = listOf(PhoneSleepTimeWrite(date, wokeUpIso)),
            onConflict = "user_id,source,date"
        )
    }

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

    // ========== PHONE BEHAVIOR METHODS ==========

    suspend fun insertPhoneBrightnessSample(
        accessToken: String,
        userId: String,
        sampledAtIso: String,
        brightness: Int
    ) {
        postgrestInsert(
            accessToken = accessToken,
            table = "phone_brightness_samples",
            body = PhoneBrightnessSampleWrite(
                user_id = userId,
                sampled_at = sampledAtIso,
                value = brightness
            )
        )
    }

    suspend fun insertPhoneVolumeSample(
        accessToken: String,
        userId: String,
        sampledAtIso: String,
        volumePct: Int,
        streamType: String = "media"
    ) {
        postgrestInsert(
            accessToken = accessToken,
            table = "phone_volume_samples",
            body = PhoneVolumeSampleWrite(
                user_id = userId,
                sampled_at = sampledAtIso,
                value_pct = volumePct,
                stream_type = streamType
            )
        )
    }

    suspend fun insertPhoneDarkModeSample(
        accessToken: String,
        userId: String,
        sampledAtIso: String,
        isDark: Boolean
    ) {
        postgrestInsert(
            accessToken = accessToken,
            table = "phone_dark_mode_samples",
            body = PhoneDarkModeSampleWrite(
                user_id = userId,
                sampled_at = sampledAtIso,
                is_dark = isDark
            )
        )
    }

    suspend fun insertPhoneUnlockSample(
        accessToken: String,
        userId: String,
        sampledAtIso: String,
        unlockCount: Int
    ) {
        postgrestInsert(
            accessToken = accessToken,
            table = "phone_unlock_samples",
            body = PhoneUnlockSampleWrite(
                user_id = userId,
                sampled_at = sampledAtIso,
                value_count = unlockCount
            )
        )
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
