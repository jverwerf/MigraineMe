package com.migraineme

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate

/**
 * Service for uploading/deleting menstruation data to/from Supabase
 *
 * As of Step 4, menstruation events are stored in `triggers` (type="menstruation").
 * Legacy menstruation_periods methods are kept for compatibility but forward to triggers logic.
 */
class SupabaseMenstruationService(private val context: Context) {

    companion object {
        private val SUPABASE_URL = BuildConfig.SUPABASE_URL.trimEnd('/')
        private val SUPABASE_ANON_KEY = BuildConfig.SUPABASE_ANON_KEY
    }

    private val httpClient = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Legacy compatibility method.
     *
     * Previously wrote to `menstruation_periods`. Now forwards to triggers-based upsert.
     */
    suspend fun uploadPeriod(
        accessToken: String,
        healthConnectId: String,
        startDate: LocalDate,
        endDate: LocalDate?
    ) {
        upsertHealthConnectMenstruationTrigger(
            accessToken = accessToken,
            healthConnectId = healthConnectId,
            startDate = startDate,
            endDate = endDate
        )
    }

    /**
     * Legacy compatibility method.
     *
     * Previously deleted from `menstruation_periods`. Now forwards to triggers-based delete.
     */
    suspend fun deletePeriodsByHealthConnectIds(
        accessToken: String,
        healthConnectIds: List<String>
    ) {
        deleteHealthConnectMenstruationTriggersByIds(
            accessToken = accessToken,
            healthConnectIds = healthConnectIds
        )
    }

    @Serializable
    private data class TriggerIdDto(
        val id: String
    )

    @Serializable
    private data class TriggerMenstruationDto(
        val start_at: String,
        val notes: String? = null,
        val source: String? = null
    )

    private fun buildNotes(endDate: LocalDate?): String? {
        // Keep notes free for real notes; only store optional end_date for now.
        return endDate?.let { "end_date=$it" }
    }

    /**
     * Upsert a menstruation trigger sourced from Health Connect.
     *
     * Mapping key:
     * - source = "health_connect"
     * - source_measure_id = Health Connect record ID
     */
    suspend fun upsertHealthConnectMenstruationTrigger(
        accessToken: String,
        healthConnectId: String,
        startDate: LocalDate,
        endDate: LocalDate?
    ) {
        val userId = JwtUtils.extractUserIdFromAccessToken(accessToken)
            ?: throw Exception("Failed to extract user_id from access token")

        val baseUrl = "$SUPABASE_URL/rest/v1/triggers"
        val startAtIso = "${startDate}T09:00:00Z"
        val notes = buildNotes(endDate)

        // Find existing trigger for this Health Connect record (by source_measure_id).
        val findUrl =
            "$baseUrl?select=id&user_id=eq.$userId&type=eq.menstruation&source=eq.health_connect&source_measure_id=eq.$healthConnectId"

        val findReq = Request.Builder()
            .url(findUrl)
            .get()
            .header("apikey", SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer $accessToken")
            .build()

        val existingId: String? = httpClient.newCall(findReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string()
                throw Exception("Failed to query triggers: ${resp.code} $err")
            }
            val body = resp.body?.string().orEmpty()
            val list = runCatching { json.decodeFromString<List<TriggerIdDto>>(body) }
                .getOrDefault(emptyList())
            list.firstOrNull()?.id
        }

        val payload = buildJsonObject {
            put("user_id", userId)
            put("type", "menstruation")
            put("start_at", startAtIso)
            if (notes != null) put("notes", notes) else put("notes", JsonNull)
            put("source", "health_connect")
            put("source_measure_id", healthConnectId)
            put("active", true)
        }

        if (existingId != null) {
            val patchReq = Request.Builder()
                .url("$baseUrl?id=eq.$existingId")
                .patch(payload.toString().toRequestBody("application/json".toMediaType()))
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .build()

            httpClient.newCall(patchReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string()
                    throw Exception("Failed to update trigger: ${resp.code} $err")
                }
            }
        } else {
            val postReq = Request.Builder()
                .url(baseUrl)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .build()

            httpClient.newCall(postReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string()
                    throw Exception("Failed to insert trigger: ${resp.code} $err")
                }
            }
        }
    }

    /**
     * Delete Health Connect sourced menstruation triggers by Health Connect IDs.
     *
     * Uses source_measure_id as the mapping key.
     */
    suspend fun deleteHealthConnectMenstruationTriggersByIds(
        accessToken: String,
        healthConnectIds: List<String>
    ) {
        if (healthConnectIds.isEmpty()) return

        val userId = JwtUtils.extractUserIdFromAccessToken(accessToken)
            ?: throw Exception("Failed to extract user_id from access token")

        val baseUrl = "$SUPABASE_URL/rest/v1/triggers"

        for (id in healthConnectIds) {
            val deleteUrl =
                "$baseUrl?user_id=eq.$userId&type=eq.menstruation&source=eq.health_connect&source_measure_id=eq.$id"

            val request = Request.Builder()
                .url(deleteUrl)
                .delete()
                .header("apikey", SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer $accessToken")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string()
                    throw Exception("Failed to delete trigger(s) for source_measure_id=$id: ${response.code} $err")
                }
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
     *
     * - Upserts on user_id (on_conflict=user_id) so repeated saves update the same row.
     * - Sends last_menstruation_date explicitly (JsonNull when cleared) so clearing the date works.
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
            if (lastMenstruationDate != null) {
                put("last_menstruation_date", lastMenstruationDate.toString())
            } else {
                put("last_menstruation_date", JsonNull)
            }
            put("avg_cycle_length", avgCycleLength)
            put("auto_update_average", autoUpdateAverage)
        }

        val request = Request.Builder()
            .url("$url?on_conflict=user_id")
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
     * Get menstruation history (for calculating averages).
     *
     * Reads from triggers(type="menstruation") and parses optional end_date from notes.
     */
    suspend fun getMenstruationHistory(
        accessToken: String,
        limitDays: Int = 365
    ): List<MenstruationPeriod> {
        val userId = JwtUtils.extractUserIdFromAccessToken(accessToken) ?: return emptyList()

        val cutoffDate = LocalDate.now().minusDays(limitDays.toLong())
        val cutoffIso = "${cutoffDate}T00:00:00Z"

        val url =
            "$SUPABASE_URL/rest/v1/triggers?user_id=eq.$userId&type=eq.menstruation&source=in.(manual,health_connect)&start_at=gte.$cutoffIso&order=start_at.asc&select=start_at,notes,source"

        val request = Request.Builder()
            .url(url)
            .get()
            .header("apikey", SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer $accessToken")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()

            val body = response.body?.string() ?: return emptyList()
            val list = runCatching { json.decodeFromString<List<TriggerMenstruationDto>>(body) }
                .getOrDefault(emptyList())

            return list.mapNotNull { row ->
                val startDate = runCatching { LocalDate.parse(row.start_at.substring(0, 10)) }.getOrNull()
                    ?: return@mapNotNull null

                val endDate = row.notes
                    ?.let { notesStr ->
                        val match = Regex("end_date=(\\d{4}-\\d{2}-\\d{2})").find(notesStr)
                        match?.groupValues?.getOrNull(1)
                    }
                    ?.let { dateStr -> runCatching { LocalDate.parse(dateStr) }.getOrNull() }

                MenstruationPeriod(
                    startDate = startDate,
                    endDate = endDate
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
