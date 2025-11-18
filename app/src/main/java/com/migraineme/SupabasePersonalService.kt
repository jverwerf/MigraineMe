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

/**
 * Supabase service for *personal* user signals.
 * Keeps same Ktor + PostgREST style as other services. No auth flow changes.
 *
 * Table assumed:
 *   user_location_daily(
 *     date date not null,
 *     latitude double precision not null,
 *     longitude double precision not null,
 *     source text not null,
 *     source_measure_id text,
 *     user_id uuid not null default auth.uid(),
 *     unique (user_id, source, date)
 *   )
 */
class SupabasePersonalService(context: Context) {
    private val supabaseUrl = BuildConfig.SUPABASE_URL
    private val supabaseKey = BuildConfig.SUPABASE_ANON_KEY
    private val appContext = context.applicationContext

    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    // ----- DTOs -----

    @Serializable
    private data class LocationWriteRow(
        val date: String,
        val latitude: Double,
        val longitude: Double,
        val source: String,
        val source_measure_id: String? = null
    )

    @Serializable
    data class UserLocationDailyRead(
        val date: String,
        val latitude: Double,
        val longitude: Double,
        val source: String? = null,
        val source_measure_id: String? = null
    )

    // ----- Writes -----

    /**
     * Upsert user daily location.
     * on_conflict = (user_id, source, date) to avoid duplicates per source per day.
     */
    suspend fun upsertUserLocationDaily(
        accessToken: String,
        date: String,              // "YYYY-MM-DD"
        latitude: Double,
        longitude: Double,
        source: String = "device", // e.g., "device", "whoop", etc.
        sourceId: String? = null
    ) {
        val row = LocationWriteRow(
            date = date,
            latitude = latitude,
            longitude = longitude,
            source = source,
            source_measure_id = sourceId
        )
        postgrestInsert(
            accessToken = accessToken,
            table = "user_location_daily",
            body = listOf(row),
            onConflict = "user_id,source,date"
        )
    }

    // ----- Reads -----

    /**
     * Fetch recent locations. Default 14 most-recent days.
     */
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

    /**
     * Point existence check for a given date+source.
     */
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
     * Latest existing date for a source, ordered descending. Returns ISO "YYYY-MM-DD" or null.
     */
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
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Earliest existing date for a source, ordered ascending. Returns ISO "YYYY-MM-DD" or null.
     */
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
        } catch (_: Throwable) {
            null
        }
    }

    // ----- Helpers -----

    private suspend inline fun <reified T> postgrestInsert(
        accessToken: String,
        table: String,
        body: T,
        onConflict: String? = null
    ) {
        val resp = client.post("$supabaseUrl/rest/v1/$table") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "resolution=merge-duplicates,return=representation")
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
