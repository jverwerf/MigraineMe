package com.migraineme

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate

/**
 * Service for uploading/deleting menstruation data to/from Supabase
 */
class SupabaseMenstruationService(private val context: Context) {

    companion object {
        private const val TAG = "SupabaseMenstruation"
        private val SUPABASE_URL = BuildConfig.SUPABASE_URL.trimEnd('/')
        private val SUPABASE_ANON_KEY = BuildConfig.SUPABASE_ANON_KEY
    }

    private val httpClient = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Upload a menstruation period to Supabase
     */
    suspend fun uploadPeriod(
        accessToken: String,
        healthConnectId: String,
        startDate: LocalDate,
        endDate: LocalDate?
    ) {
        val url = "$SUPABASE_URL/rest/v1/menstruation_periods"

        val userId = JwtUtils.extractUserIdFromAccessToken(accessToken)
            ?: throw Exception("Failed to extract user_id from access token")

        val body = buildJsonObject {
            put("user_id", userId)
            put("health_connect_id", healthConnectId)
            put("start_date", startDate.toString())
            endDate?.let { put("end_date", it.toString()) }
            put("source", "health_connect")
        }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("apikey", SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .header("Prefer", "resolution=merge-duplicates")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                throw Exception("Failed to upload period: ${response.code} $errorBody")
            }
        }
    }

    /**
     * Delete menstruation periods by Health Connect IDs
     */
    suspend fun deletePeriodsByHealthConnectIds(
        accessToken: String,
        healthConnectIds: List<String>
    ) {
        if (healthConnectIds.isEmpty()) return

        val idsFilter = healthConnectIds.joinToString(",")
        val url = "$SUPABASE_URL/rest/v1/menstruation_periods?health_connect_id=in.($idsFilter)"

        val request = Request.Builder()
            .url(url)
            .delete()
            .header("apikey", SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer $accessToken")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to delete periods: ${response.code}")
            }
        }
    }

    /**
     * Get user's menstruation settings
     */
    suspend fun getSettings(accessToken: String): MenstruationSettings? {
        val userId = JwtUtils.extractUserIdFromAccessToken(accessToken) ?: return null

        val url = "$SUPABASE_URL/rest/v1/menstruation_settings?user_id=eq.$userId&select=*"

        val request = Request.Builder()
            .url(url)
            .get()
            .header("apikey", SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer $accessToken")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val list = json.decodeFromString<List<MenstruationSettingsDto>>(body)

            return list.firstOrNull()?.let {
                MenstruationSettings(
                    lastMenstruationDate = it.last_menstruation_date?.let { date -> LocalDate.parse(date) },
                    avgCycleLength = it.avg_cycle_length ?: 28,
                    autoUpdateAverage = it.auto_update_average ?: true
                )
            }
        }
    }

    /**
     * Update user's menstruation settings
     */
    suspend fun updateSettings(
        accessToken: String,
        lastMenstruationDate: LocalDate?,
        avgCycleLength: Int,
        autoUpdateAverage: Boolean
    ) {
        val userId = JwtUtils.extractUserIdFromAccessToken(accessToken)
            ?: throw Exception("Failed to extract user_id")

        val url = "$SUPABASE_URL/rest/v1/menstruation_settings"

        val body = buildJsonObject {
            put("user_id", userId)
            lastMenstruationDate?.let { put("last_menstruation_date", it.toString()) }
            put("avg_cycle_length", avgCycleLength)
            put("auto_update_average", autoUpdateAverage)
        }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("apikey", SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .header("Prefer", "resolution=merge-duplicates")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                throw Exception("Failed to update settings: ${response.code} $errorBody")
            }
        }
    }

    /**
     * Get menstruation history (for calculating averages)
     */
    suspend fun getMenstruationHistory(
        accessToken: String,
        limitDays: Int = 365
    ): List<MenstruationPeriod> {
        val userId = JwtUtils.extractUserIdFromAccessToken(accessToken) ?: return emptyList()

        val cutoffDate = LocalDate.now().minusDays(limitDays.toLong())
        val url = "$SUPABASE_URL/rest/v1/menstruation_periods?user_id=eq.$userId&start_date=gte.$cutoffDate&order=start_date.asc&select=start_date,end_date"

        val request = Request.Builder()
            .url(url)
            .get()
            .header("apikey", SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer $accessToken")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            val list = json.decodeFromString<List<MenstruationPeriodDto>>(body)

            return list.map {
                MenstruationPeriod(
                    startDate = LocalDate.parse(it.start_date),
                    endDate = it.end_date?.let { date -> LocalDate.parse(date) }
                )
            }
        }
    }
}

// DTOs
@Serializable
data class MenstruationSettingsDto(
    val user_id: String,
    val last_menstruation_date: String?,
    val avg_cycle_length: Int?,
    val auto_update_average: Boolean?
)

@Serializable
data class MenstruationPeriodDto(
    val start_date: String,
    val end_date: String?
)