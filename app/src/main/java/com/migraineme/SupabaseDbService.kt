package com.migraineme

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class SupabaseDbService(
    private val baseUrl: String = BuildConfig.SUPABASE_URL,
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY
) {
    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    /* ============================== Models ============================== */

    @Serializable
    data class MigraineInsert(
        val type: String,
        val severity: Int,
        @SerialName("began_at") val beganAt: String,         // ISO8601
        @SerialName("ended_at") val endedAt: String? = null, // ISO8601 or null
        val notes: String? = null
    )

    @Serializable
    data class MedicineInsert(
        val name: String,
        val amount: String? = null,
        @SerialName("taken_at") val takenAt: String,         // ISO8601
        @SerialName("migraine_id") val migraineId: String? = null,
        val notes: String? = null
    )

    @Serializable
    data class ReliefInsert(
        val type: String,
        @SerialName("duration_minutes") val durationMinutes: Int? = null,
        @SerialName("taken_at") val takenAt: String,         // ISO8601
        @SerialName("migraine_id") val migraineId: String? = null,
        val notes: String? = null
    )

    @Serializable data class IdResponse(val id: String)

    @Serializable
    data class MigraineBasic(
        val id: String,
        val type: String,
        val severity: Int,
        @SerialName("began_at") val beganAt: String,
        @SerialName("ended_at") val endedAt: String? = null
    )

    @Serializable
    data class MedRow(
        val id: String,
        @SerialName("taken_at") val takenAt: String,
        val name: String,
        val amount: String? = null,
        @SerialName("migraine_id") val migraineId: String? = null
    )

    @Serializable
    data class ReliefRow(
        val id: String,
        @SerialName("taken_at") val takenAt: String,
        val type: String,
        @SerialName("duration_minutes") val durationMinutes: Int? = null,
        @SerialName("migraine_id") val migraineId: String? = null
    )

    /* =============================== Inserts =============================== */

    suspend fun insertMigraine(accessToken: String, data: MigraineInsert): IdResponse {
        val url = "$baseUrl/rest/v1/migraines"
        val list: List<IdResponse> = client.post(url) {
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(listOf(data))
        }.body()
        return list.first()
    }

    suspend fun insertMedicine(accessToken: String, data: MedicineInsert): IdResponse {
        val url = "$baseUrl/rest/v1/medicines"
        val list: List<IdResponse> = client.post(url) {
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(listOf(data))
        }.body()
        return list.first()
    }

    suspend fun insertRelief(accessToken: String, data: ReliefInsert): IdResponse {
        val url = "$baseUrl/rest/v1/reliefs"
        val list: List<IdResponse> = client.post(url) {
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(listOf(data))
        }.body()
        return list.first()
    }

    /* ================================ Reads ================================ */

    suspend fun recentMigrainesBasic(accessToken: String, limit: Int = 20): List<MigraineBasic> {
        val url = "$baseUrl/rest/v1/migraines"
        return client.get(url) {
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("select", "id,type,severity,began_at,ended_at")
            parameter("order", "began_at.desc")
            parameter("limit", limit)
        }.body()
    }

    suspend fun recentMedicines(accessToken: String): List<MedRow> {
        val url = "$baseUrl/rest/v1/medicines"
        return client.get(url) {
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("select", "id,taken_at,name,amount,migraine_id")
            parameter("order", "taken_at.desc")
            parameter("limit", 50)
        }.body()
    }

    suspend fun recentReliefs(accessToken: String): List<ReliefRow> {
        val url = "$baseUrl/rest/v1/reliefs"
        return client.get(url) {
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("select", "id,taken_at,type,duration_minutes,migraine_id")
            parameter("order", "taken_at.desc")
            parameter("limit", 50)
        }.body()
    }

    /* ============================= Link / Unlink ============================ */

    suspend fun updateMedicineMigraine(accessToken: String, medicineId: String, migraineId: String?) {
        val url = "$baseUrl/rest/v1/medicines"
        client.patch(url) {
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("id", "eq.$medicineId")
            contentType(ContentType.Application.Json)
            setBody(mapOf("migraine_id" to migraineId))
        }
    }

    suspend fun updateReliefMigraine(accessToken: String, reliefId: String, migraineId: String?) {
        val url = "$baseUrl/rest/v1/reliefs"
        client.patch(url) {
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("id", "eq.$reliefId")
            contentType(ContentType.Application.Json)
            setBody(mapOf("migraine_id" to migraineId))
        }
    }
    /* ============================= Time updates ============================= */

    suspend fun updateMedicineTime(
        accessToken: String,
        id: String,
        takenAtIso: String
    ) {
        val url = "$baseUrl/rest/v1/medicines"
        client.patch(url) {
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("id", "eq.$id")
            contentType(ContentType.Application.Json)
            setBody(mapOf("taken_at" to takenAtIso))
        }
    }

    suspend fun updateReliefTime(
        accessToken: String,
        id: String,
        takenAtIso: String
    ) {
        val url = "$baseUrl/rest/v1/reliefs"
        client.patch(url) {
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("id", "eq.$id")
            contentType(ContentType.Application.Json)
            setBody(mapOf("taken_at" to takenAtIso))
        }
    }

    /* ================================ Deletes =============================== */

    suspend fun deleteMigraine(accessToken: String, id: String) {
        val url = "$baseUrl/rest/v1/migraines"
        client.delete(url) {
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("id", "eq.$id")
        }
    }

    suspend fun deleteMedicine(accessToken: String, id: String) {
        val url = "$baseUrl/rest/v1/medicines"
        client.delete(url) {
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("id", "eq.$id")
        }
    }

    suspend fun deleteRelief(accessToken: String, id: String) {
        val url = "$baseUrl/rest/v1/reliefs"
        client.delete(url) {
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("id", "eq.$id")
        }
    }
    /* ============================= Updates ============================= */

    suspend fun updateMigraine(
        accessToken: String,
        id: String,
        type: String,
        severity: Int,
        endedAt: String?,
        notes: String?
    ) {
        val url = "$baseUrl/rest/v1/migraines"
        client.patch(url) {
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("id", "eq.$id")
            contentType(ContentType.Application.Json)
            setBody(
                buildMap {
                    put("type", type)
                    put("severity", severity)
                    put("ended_at", endedAt)
                    put("notes", notes)
                }
            )
        }
    }

    suspend fun updateMedicineFull(
        accessToken: String,
        id: String,
        name: String,
        amount: String?,
        notes: String?,
        migraineId: String?
    ) {
        val url = "$baseUrl/rest/v1/medicines"
        client.patch(url) {
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("id", "eq.$id")
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "name" to name,
                    "amount" to amount,
                    "notes" to notes,
                    "migraine_id" to migraineId
                )
            )
        }
    }

    suspend fun updateReliefFull(
        accessToken: String,
        id: String,
        type: String,
        durationMinutes: Int?,
        notes: String?,
        migraineId: String?
    ) {
        val url = "$baseUrl/rest/v1/reliefs"
        client.patch(url) {
            header("apikey", anonKey)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            parameter("id", "eq.$id")
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "type" to type,
                    "duration_minutes" to durationMinutes,
                    "notes" to notes,
                    "migraine_id" to migraineId
                )
            )
        }
    }
}
