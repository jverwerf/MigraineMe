package com.migraineme

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

class SupabaseDbService(
    private val supabaseUrl: String,
    private val supabaseKey: String
) {
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    explicitNulls = false // omit nulls
                }
            )
        }
    }

    private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299

    // ───────────────────────── MIGRAINES ─────────────────────────

    @Serializable
    data class MigraineRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        val type: String? = null,
        val severity: Int? = null,
        @SerialName("start_at") val startAt: String,
        @SerialName("ended_at") val endAt: String? = null,
        val notes: String? = null
    )

    @Serializable
    data class MigraineInsert(
        val type: String? = null,
        val severity: Int? = null,
        @SerialName("start_at") val startAt: String,
        @SerialName("ended_at") val endAt: String? = null,
        val notes: String? = null
    )

    suspend fun insertMigraine(
        accessToken: String,
        type: String?,
        severity: Int?,
        startAt: String?,
        endAt: String?,
        notes: String?
    ): MigraineRow {
        val safeStart = startAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString()
        val payload = MigraineInsert(type, severity, safeStart, endAt, notes)

        val response: HttpResponse = client.post("$supabaseUrl/rest/v1/migraines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "return=representation")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }

        val raw = response.bodyAsText()
        println("DEBUG insertMigraine: ${response.status} $raw")
        if (!response.status.isSuccess()) throw RuntimeException("Insert migraine failed")
        return response.body()
    }

    suspend fun getMigraines(accessToken: String): List<MigraineRow> {
        val response: HttpResponse = client.get("$supabaseUrl/rest/v1/migraines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "*")
        }
        val raw = response.bodyAsText()
        println("DEBUG getMigraines: ${response.status} $raw")
        if (!response.status.isSuccess()) throw RuntimeException("Fetch migraines failed")
        return response.body()
    }

    // ───────────────────────── TRIGGERS ─────────────────────────

    @Serializable
    data class TriggerRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        val type: String? = null,
        @SerialName("start_at") val startAt: String,
        val notes: String? = null,
        @SerialName("migraine_id") val migraineId: String? = null
    )

    @Serializable
    data class TriggerInsert(
        val type: String? = null,
        @SerialName("start_at") val startAt: String,
        val notes: String? = null,
        @SerialName("migraine_id") val migraineId: String? = null
    )

    suspend fun insertTrigger(
        accessToken: String,
        migraineId: String?,
        type: String?,
        startAt: String?,
        notes: String?
    ): TriggerRow {
        val safeStart = startAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString()
        val payload = TriggerInsert(type, safeStart, notes, migraineId)

        val response: HttpResponse = client.post("$supabaseUrl/rest/v1/triggers") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "return=representation")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }

        val raw = response.bodyAsText()
        println("DEBUG insertTrigger: ${response.status} $raw")
        if (!response.status.isSuccess()) throw RuntimeException("Insert trigger failed")
        return response.body()
    }

    suspend fun getAllTriggers(accessToken: String): List<TriggerRow> {
        val response: HttpResponse = client.get("$supabaseUrl/rest/v1/triggers") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "*")
        }
        val raw = response.bodyAsText()
        println("DEBUG getTriggers: ${response.status} $raw")
        if (!response.status.isSuccess()) throw RuntimeException("Fetch triggers failed")
        return response.body()
    }

    // ───────────────────────── MEDICINES ─────────────────────────

    @Serializable
    data class MedicineRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        val name: String? = null,
        val amount: String? = null,
        @SerialName("start_at") val startAt: String,
        val notes: String? = null,
        @SerialName("migraine_id") val migraineId: String? = null
    )

    @Serializable
    data class MedicineInsert(
        val name: String? = null,
        val amount: String? = null,
        @SerialName("start_at") val startAt: String,
        val notes: String? = null,
        @SerialName("migraine_id") val migraineId: String? = null
    )

    suspend fun insertMedicine(
        accessToken: String,
        migraineId: String?,
        name: String?,
        amount: String?,
        startAt: String?,
        notes: String?
    ): MedicineRow {
        val safeStart = startAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString()
        val payload = MedicineInsert(name, amount, safeStart, notes, migraineId)

        val response: HttpResponse = client.post("$supabaseUrl/rest/v1/medicines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "return=representation")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }

        val raw = response.bodyAsText()
        println("DEBUG insertMedicine: ${response.status} $raw")
        if (!response.status.isSuccess()) throw RuntimeException("Insert medicine failed")
        return response.body()
    }

    suspend fun getAllMedicines(accessToken: String): List<MedicineRow> {
        val response: HttpResponse = client.get("$supabaseUrl/rest/v1/medicines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "*")
        }
        val raw = response.bodyAsText()
        println("DEBUG getMedicines: ${response.status} $raw")
        if (!response.status.isSuccess()) throw RuntimeException("Fetch medicines failed")
        return response.body()
    }

    // ───────────────────────── RELIEFS ─────────────────────────

    @Serializable
    data class ReliefRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        val type: String? = null,
        @SerialName("duration_minutes") val durationMinutes: Int? = null,
        @SerialName("start_at") val startAt: String,
        val notes: String? = null,
        @SerialName("migraine_id") val migraineId: String? = null
    )

    @Serializable
    data class ReliefInsert(
        val type: String? = null,
        @SerialName("duration_minutes") val durationMinutes: Int? = null,
        @SerialName("start_at") val startAt: String,
        val notes: String? = null,
        @SerialName("migraine_id") val migraineId: String? = null
    )

    suspend fun insertRelief(
        accessToken: String,
        migraineId: String?,
        type: String?,
        durationMinutes: Int?,
        startAt: String?,
        notes: String?
    ): ReliefRow {
        val safeStart = startAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString()
        val payload = ReliefInsert(type, durationMinutes, safeStart, notes, migraineId)

        val response: HttpResponse = client.post("$supabaseUrl/rest/v1/reliefs") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "return=representation")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }

        val raw = response.bodyAsText()
        println("DEBUG insertRelief: ${response.status} $raw")
        if (!response.status.isSuccess()) throw RuntimeException("Insert relief failed")
        return response.body()
    }

    suspend fun getAllReliefs(accessToken: String): List<ReliefRow> {
        val response: HttpResponse = client.get("$supabaseUrl/rest/v1/reliefs") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "*")
        }
        val raw = response.bodyAsText()
        println("DEBUG getReliefs: ${response.status} $raw")
        if (!response.status.isSuccess()) throw RuntimeException("Fetch reliefs failed")
        return response.body()
    }

    // ───────────────────────── TRIGGER PREFERENCES ─────────────────────────

    @Serializable
    data class AllTriggerRow(val id: String, val label: String)

    @Serializable
    data class TriggerPrefRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("trigger_id") val triggerId: String,
        val position: Int,
        val status: String, // NEW
        @SerialName("all_triggers") val trigger: AllTriggerRow? = null
    )

    @Serializable private data class AllTriggerInsert(val label: String)

    suspend fun getAllTriggerPool(accessToken: String): List<AllTriggerRow> {
        val response = client.get("$supabaseUrl/rest/v1/all_triggers") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "id,label")
            parameter("order", "label.asc")
        }
        val raw = response.bodyAsText()
        println("DEBUG getAllTriggerPool: ${response.status} $raw")
        if (!response.status.isSuccess()) throw RuntimeException("Fetch pool failed")
        return response.body()
    }

    suspend fun upsertTriggerToPool(accessToken: String, label: String): AllTriggerRow {
        val response = client.post("$supabaseUrl/rest/v1/all_triggers") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "label")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json)
            setBody(AllTriggerInsert(label))
        }
        val raw = response.bodyAsText()
        println("DEBUG upsertTriggerToPool: ${response.status} $raw")
        if (!response.status.isSuccess()) throw RuntimeException("Upsert pool failed")
        return response.body()
    }

    suspend fun getTriggerPrefs(accessToken: String): List<TriggerPrefRow> {
        val response = client.get("$supabaseUrl/rest/v1/trigger_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "id,user_id,trigger_id,position,status,all_triggers(id,label)")
            parameter("order", "position.asc")
        }
        val raw = response.bodyAsText()
        println("DEBUG getTriggerPrefs: ${response.status} $raw")
        if (!response.status.isSuccess()) throw RuntimeException("Fetch prefs failed")
        return response.body()
    }

    @Serializable private data class TriggerPrefInsert(
        @SerialName("trigger_id") val triggerId: String,
        val position: Int,
        val status: String
    )

    suspend fun insertTriggerPref(
        accessToken: String,
        triggerId: String,
        position: Int,
        status: String = "frequent"
    ): TriggerPrefRow {
        val response = client.post("$supabaseUrl/rest/v1/trigger_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "user_id,trigger_id")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json)
            setBody(TriggerPrefInsert(triggerId, position, status))
        }
        val raw = response.bodyAsText()
        println("DEBUG insertTriggerPref: ${response.status} $raw")
        if (!response.status.isSuccess()) throw RuntimeException("Insert pref failed")
        return response.body()
    }

    @Serializable private data class TriggerPrefUpdatePosition(val position: Int)

    suspend fun updateTriggerPrefPosition(accessToken: String, prefId: String, newPosition: Int) {
        val response = client.patch("$supabaseUrl/rest/v1/trigger_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("id", "eq.$prefId")
            contentType(ContentType.Application.Json)
            setBody(TriggerPrefUpdatePosition(newPosition))
        }
        val raw = response.bodyAsText()
        println("DEBUG updateTriggerPrefPosition: ${response.status} $raw")
        if (!response.status.isSuccess()) throw RuntimeException("Update pref failed")
    }

    @Serializable private data class TriggerPrefUpdateStatus(val status: String)

    suspend fun updateTriggerPrefStatus(accessToken: String, prefId: String, newStatus: String) {
        val response = client.patch("$supabaseUrl/rest/v1/trigger_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("id", "eq.$prefId")
            contentType(ContentType.Application.Json)
            setBody(TriggerPrefUpdateStatus(newStatus))
        }
        val raw = response.bodyAsText()
        println("DEBUG updateTriggerPrefStatus: ${response.status} $raw")
        if (!response.status.isSuccess()) throw RuntimeException("Update status failed")
    }

    suspend fun deleteTriggerPref(accessToken: String, prefId: String) {
        val response = client.delete("$supabaseUrl/rest/v1/trigger_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("id", "eq.$prefId")
        }
        val raw = response.bodyAsText()
        println("DEBUG deleteTriggerPref: ${response.status} $raw")
        if (!response.status.isSuccess()) throw RuntimeException("Delete pref failed")
    }
}
