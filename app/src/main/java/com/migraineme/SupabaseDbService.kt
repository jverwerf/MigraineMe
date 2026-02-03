// app/src/main/java/com/migraineme/SupabaseDbService.kt
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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

class SupabaseDbService(
    private val supabaseUrl: String,
    private val supabaseKey: String
) {
    @OptIn(ExperimentalSerializationApi::class)
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    explicitNulls = false
                }
            )
        }
    }

    private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299

    // ───────── MIGRAINES ─────────
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
        if (!response.status.isSuccess()) error("Insert migraine failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun getMigraines(accessToken: String): List<MigraineRow> {
        val response: HttpResponse = client.get("$supabaseUrl/rest/v1/migraines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "*")
        }
        if (!response.status.isSuccess()) error("Fetch migraines failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun getMigraineById(accessToken: String, id: String): MigraineRow {
        val response = client.get("$supabaseUrl/rest/v1/migraines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id"); parameter("select", "*")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
        }
        if (!response.status.isSuccess()) error("Get migraine by id failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun updateMigraine(
        accessToken: String,
        id: String,
        type: String? = null,
        severity: Int? = null,
        startAt: String? = null,
        endAt: String? = null,
        notes: String? = null
    ): MigraineRow {
        val payload = buildJsonObject {
            type?.let { put("type", it) }
            severity?.let { put("severity", it) }
            startAt?.let { put("start_at", it) }
            endAt?.let { put("ended_at", it) }
            notes?.let { put("notes", it) }
        }
        val response = client.patch("$supabaseUrl/rest/v1/migraines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
            header("Prefer", "return=representation")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Update migraine failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteMigraine(accessToken: String, id: String) {
        val response = client.delete("$supabaseUrl/rest/v1/migraines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
        }
        if (!response.status.isSuccess()) error("Delete migraine failed: ${response.bodyAsText()}")
    }

    // ───────── TRIGGERS ─────────
    @Serializable
    data class TriggerRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        val type: String? = null,
        @SerialName("start_at") val startAt: String,
        val notes: String? = null,
        @SerialName("migraine_id") val migraineId: String? = null,
        val source: String? = "manual",  // ADD THIS LINE
        val active: Boolean = true       // ADD THIS LINE
    )
    @Serializable
    data class TriggerInsert(
        val type: String? = null,
        @SerialName("start_at") val startAt: String,
        val notes: String? = null,
        @SerialName("migraine_id") val migraineId: String? = null,
        val source: String? = "manual",  // ADD THIS LINE
        val active: Boolean = true       // ADD THIS LINE
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
        if (!response.status.isSuccess()) error("Insert trigger failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun getAllTriggers(accessToken: String): List<TriggerRow> {
        val response: HttpResponse = client.get("$supabaseUrl/rest/v1/triggers") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "*")
        }
        if (!response.status.isSuccess()) error("Fetch triggers failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun getTriggerById(accessToken: String, id: String): TriggerRow {
        val response = client.get("$supabaseUrl/rest/v1/triggers") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id"); parameter("select", "*")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
        }
        if (!response.status.isSuccess()) error("Get trigger by id failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun updateTrigger(
        accessToken: String,
        id: String,
        type: String? = null,
        startAt: String? = null,
        notes: String? = null,
        migraineId: String? = null
    ): TriggerRow {
        val payload = buildJsonObject {
            type?.let { put("type", it) }
            startAt?.let { put("start_at", it) }
            notes?.let { put("notes", it) }
            migraineId?.let { put("migraine_id", it) }
        }
        val response = client.patch("$supabaseUrl/rest/v1/triggers") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
            header("Prefer", "return=representation")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Update trigger failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteTrigger(accessToken: String, id: String) {
        val response = client.delete("$supabaseUrl/rest/v1/triggers") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
        }
        if (!response.status.isSuccess()) error("Delete trigger failed: ${response.bodyAsText()}")
    }

    // ───────── MEDICINES ─────────
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
        if (!response.status.isSuccess()) error("Insert medicine failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun getAllMedicines(accessToken: String): List<MedicineRow> {
        val response: HttpResponse = client.get("$supabaseUrl/rest/v1/medicines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "*")
        }
        if (!response.status.isSuccess()) error("Fetch medicines failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun getMedicineById(accessToken: String, id: String): MedicineRow {
        val response = client.get("$supabaseUrl/rest/v1/medicines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id"); parameter("select", "*")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
        }
        if (!response.status.isSuccess()) error("Get medicine by id failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun updateMedicine(
        accessToken: String,
        id: String,
        name: String? = null,
        amount: String? = null,
        startAt: String? = null,
        notes: String? = null,
        migraineId: String? = null
    ): MedicineRow {
        val payload = buildJsonObject {
            name?.let { put("name", it) }
            if (amount != null) put("amount", amount)
            startAt?.let { put("start_at", it) }
            notes?.let { put("notes", it) }
            migraineId?.let { put("migraine_id", it) }
        }
        val response = client.patch("$supabaseUrl/rest/v1/medicines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
            header("Prefer", "return=representation")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Update medicine failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteMedicine(accessToken: String, id: String) {
        val response = client.delete("$supabaseUrl/rest/v1/medicines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
        }
        if (!response.status.isSuccess()) error("Delete medicine failed: ${response.bodyAsText()}")
    }

    // ───────── RELIEFS ─────────
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
        if (!response.status.isSuccess()) error("Insert relief failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun getAllReliefs(accessToken: String): List<ReliefRow> {
        val response: HttpResponse = client.get("$supabaseUrl/rest/v1/reliefs") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "*")
        }
        if (!response.status.isSuccess()) error("Fetch reliefs failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun getReliefById(accessToken: String, id: String): ReliefRow {
        val response = client.get("$supabaseUrl/rest/v1/reliefs") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id"); parameter("select", "*")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
        }
        if (!response.status.isSuccess()) error("Get relief by id failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun updateRelief(
        accessToken: String,
        id: String,
        type: String? = null,
        durationMinutes: Int? = null,
        startAt: String? = null,
        notes: String? = null,
        migraineId: String? = null
    ): ReliefRow {
        val payload = buildJsonObject {
            type?.let { put("type", it) }
            durationMinutes?.let { put("duration_minutes", it) }
            startAt?.let { put("start_at", it) }
            notes?.let { put("notes", it) }
            migraineId?.let { put("migraine_id", it) }
        }
        val response = client.patch("$supabaseUrl/rest/v1/reliefs") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
            header("Prefer", "return=representation")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Update relief failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteRelief(accessToken: String, id: String) {
        val response = client.delete("$supabaseUrl/rest/v1/reliefs") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
        }
        if (!response.status.isSuccess()) error("Delete relief failed: ${response.bodyAsText()}")
    }

    // ───────── TRIGGER POOL / PREFS ─────────
    @Serializable data class AllTriggerRow(val id: String, val label: String)
    @Serializable
    data class TriggerPrefRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("trigger_id") val triggerId: String,
        val position: Int,
        val status: String,
        @SerialName("all_triggers") val trigger: AllTriggerRow? = null
    )
    @Serializable private data class AllTriggerInsert(val label: String)

    suspend fun getAllTriggerPool(accessToken: String): List<AllTriggerRow> {
        val response = client.get("$supabaseUrl/rest/v1/all_triggers") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,label"); parameter("order", "label.asc")
        }
        if (!response.status.isSuccess()) error("Fetch all_triggers failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun upsertTriggerToPool(accessToken: String, label: String): AllTriggerRow {
        val response = client.post("$supabaseUrl/rest/v1/all_triggers") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "label")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(AllTriggerInsert(label))
        }
        if (!response.status.isSuccess()) error("Upsert all_triggers failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteTriggerFromPool(accessToken: String, triggerId: String) {
        client.delete("$supabaseUrl/rest/v1/trigger_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("trigger_id", "eq.$triggerId")
        }
        val response = client.delete("$supabaseUrl/rest/v1/all_triggers") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$triggerId")
        }
        if (!response.status.isSuccess()) error("Delete all_triggers failed: ${response.bodyAsText()}")
    }
    @Serializable private data class TriggerPrefInsert(@SerialName("trigger_id") val triggerId: String, val position: Int, val status: String)
    suspend fun getTriggerPrefs(accessToken: String): List<TriggerPrefRow> {
        val response = client.get("$supabaseUrl/rest/v1/trigger_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,user_id,trigger_id,position,status,all_triggers(id,label)")
            parameter("order", "position.asc")
        }
        if (!response.status.isSuccess()) error("Fetch trigger prefs failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun insertTriggerPref(accessToken: String, triggerId: String, position: Int, status: String = "frequent"): TriggerPrefRow {
        val response = client.post("$supabaseUrl/rest/v1/trigger_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "user_id,trigger_id")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(TriggerPrefInsert(triggerId, position, status))
        }
        if (!response.status.isSuccess()) error("Insert trigger pref failed: ${response.bodyAsText()}")
        return response.body()
    }
    @Serializable private data class TriggerPrefUpdatePosition(val position: Int)
    suspend fun updateTriggerPrefPosition(accessToken: String, prefId: String, newPosition: Int) {
        val response = client.patch("$supabaseUrl/rest/v1/trigger_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$prefId")
            contentType(ContentType.Application.Json); setBody(TriggerPrefUpdatePosition(newPosition))
        }
        if (!response.status.isSuccess()) error("Update trigger pref failed: ${response.bodyAsText()}")
    }
    @Serializable private data class TriggerPrefUpdateStatus(val status: String)
    suspend fun updateTriggerPrefStatus(accessToken: String, prefId: String, newStatus: String) {
        val response = client.patch("$supabaseUrl/rest/v1/trigger_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$prefId")
            contentType(ContentType.Application.Json); setBody(TriggerPrefUpdateStatus(newStatus))
        }
        if (!response.status.isSuccess()) error("Update trigger status failed: ${response.bodyAsText()}")
    }
    suspend fun deleteTriggerPref(accessToken: String, prefId: String) {
        val response = client.delete("$supabaseUrl/rest/v1/trigger_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$prefId")
        }
        if (!response.status.isSuccess()) error("Delete trigger pref failed: ${response.bodyAsText()}")
    }

    // ───────── MEDICINE POOL / PREFS ─────────
    @Serializable data class AllMedicineRow(val id: String, val label: String)
    @Serializable
    data class MedicinePrefRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("medicine_id") val medicineId: String,
        val position: Int,
        val status: String,
        @SerialName("all_medicines") val medicine: AllMedicineRow? = null
    )
    @Serializable private data class AllMedicineInsert(val label: String)

    suspend fun getAllMedicinePool(accessToken: String): List<AllMedicineRow> {
        val response = client.get("$supabaseUrl/rest/v1/all_medicines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,label"); parameter("order", "label.asc")
        }
        if (!response.status.isSuccess()) error("Fetch all_medicines failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun upsertMedicineToPool(accessToken: String, label: String): AllMedicineRow {
        val response = client.post("$supabaseUrl/rest/v1/all_medicines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "label")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(AllMedicineInsert(label))
        }
        if (!response.status.isSuccess()) error("Upsert all_medicines failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteMedicineFromPool(accessToken: String, medicineId: String) {
        client.delete("$supabaseUrl/rest/v1/medicine_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("medicine_id", "eq.$medicineId")
        }
        val response = client.delete("$supabaseUrl/rest/v1/all_medicines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$medicineId")
        }
        if (!response.status.isSuccess()) error("Delete all_medicines failed: ${response.bodyAsText()}")
    }
    @Serializable private data class MedicinePrefInsert(@SerialName("medicine_id") val medicineId: String, val position: Int, val status: String)
    suspend fun getMedicinePrefs(accessToken: String): List<MedicinePrefRow> {
        val response = client.get("$supabaseUrl/rest/v1/medicine_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,user_id,medicine_id,position,status,all_medicines(id,label)")
            parameter("order", "position.asc")
        }
        if (!response.status.isSuccess()) error("Fetch medicine prefs failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun insertMedicinePref(accessToken: String, medicineId: String, position: Int, status: String = "frequent"): MedicinePrefRow {
        val response = client.post("$supabaseUrl/rest/v1/medicine_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "user_id,medicine_id")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(MedicinePrefInsert(medicineId, position, status))
        }
        if (!response.status.isSuccess()) error("Insert medicine pref failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun updateMedicinePref(accessToken: String, prefId: String, position: Int? = null, status: String? = null) {
        val payload = buildJsonObject {
            position?.let { put("position", it) }
            status?.let { put("status", it) }
        }
        val response = client.patch("$supabaseUrl/rest/v1/medicine_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$prefId")
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Update medicine pref failed: ${response.bodyAsText()}")
    }
    suspend fun deleteMedicinePref(accessToken: String, prefId: String) {
        val response = client.delete("$supabaseUrl/rest/v1/medicine_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$prefId")
        }
        if (!response.status.isSuccess()) error("Delete medicine pref failed: ${response.bodyAsText()}")
    }

    // ───────── RELIEF POOL / PREFS ─────────
    @Serializable data class AllReliefRow(val id: String, val label: String)
    @Serializable
    data class ReliefPrefRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("relief_id") val reliefId: String,
        val position: Int,
        val status: String,
        @SerialName("all_reliefs") val relief: AllReliefRow? = null
    )
    @Serializable private data class AllReliefInsert(val label: String)

    suspend fun getAllReliefPool(accessToken: String): List<AllReliefRow> {
        val response = client.get("$supabaseUrl/rest/v1/all_reliefs") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,label"); parameter("order", "label.asc")
        }
        if (!response.status.isSuccess()) error("Fetch all_reliefs failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun upsertReliefToPool(accessToken: String, label: String): AllReliefRow {
        val response = client.post("$supabaseUrl/rest/v1/all_reliefs") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "label")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(AllReliefInsert(label))
        }
        if (!response.status.isSuccess()) error("Upsert all_reliefs failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteReliefFromPool(accessToken: String, reliefId: String) {
        client.delete("$supabaseUrl/rest/v1/relief_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("relief_id", "eq.$reliefId")
        }
        val response = client.delete("$supabaseUrl/rest/v1/all_reliefs") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$reliefId")
        }
        if (!response.status.isSuccess()) error("Delete all_reliefs failed: ${response.bodyAsText()}")
    }
    @Serializable private data class ReliefPrefInsert(@SerialName("relief_id") val reliefId: String, val position: Int, val status: String)
    suspend fun getReliefPrefs(accessToken: String): List<ReliefPrefRow> {
        val response = client.get("$supabaseUrl/rest/v1/relief_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,user_id,relief_id,position,status,all_reliefs(id,label)")
            parameter("order", "position.asc")
        }
        if (!response.status.isSuccess()) error("Fetch relief prefs failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun insertReliefPref(accessToken: String, reliefId: String, position: Int, status: String = "frequent"): ReliefPrefRow {
        val response = client.post("$supabaseUrl/rest/v1/relief_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "user_id,relief_id")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(ReliefPrefInsert(reliefId, position, status))
        }
        if (!response.status.isSuccess()) error("Insert relief pref failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun updateReliefPref(accessToken: String, prefId: String, position: Int? = null, status: String? = null) {
        val payload = buildJsonObject {
            position?.let { put("position", it) }
            status?.let { put("status", it) }
        }
        val response = client.patch("$supabaseUrl/rest/v1/relief_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$prefId")
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Update relief pref failed: ${response.bodyAsText()}")
    }
    suspend fun deleteReliefPref(accessToken: String, prefId: String) {
        val response = client.delete("$supabaseUrl/rest/v1/relief_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$prefId")
        }
        if (!response.status.isSuccess()) error("Delete relief pref failed: ${response.bodyAsText()}")
    }

    // ───────── MIGRAINE POOL / PREFS ─────────
    @Serializable data class AllMigraineRow(val id: String, val label: String)
    @Serializable
    data class MigrainePrefRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("migraine_id") val migraineId: String,
        val position: Int,
        val status: String,
        @SerialName("all_migraines") val migraine: AllMigraineRow? = null
    )
    @Serializable private data class AllMigraineInsert(val label: String)

    suspend fun getAllMigrainePool(accessToken: String): List<AllMigraineRow> {
        val response = client.get("$supabaseUrl/rest/v1/all_migraines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,label"); parameter("order", "label.asc")
        }
        if (!response.status.isSuccess()) error("Fetch all_migraines failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun upsertMigraineToPool(accessToken: String, label: String): AllMigraineRow {
        val response = client.post("$supabaseUrl/rest/v1/all_migraines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "label")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(AllMigraineInsert(label))
        }
        if (!response.status.isSuccess()) error("Upsert all_migraines failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteMigraineFromPool(accessToken: String, migraineId: String) {
        client.delete("$supabaseUrl/rest/v1/migraine_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("migraine_id", "eq.$migraineId")
        }
        val response = client.delete("$supabaseUrl/rest/v1/all_migraines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$migraineId")
        }
        if (!response.status.isSuccess()) error("Delete all_migraines failed: ${response.bodyAsText()}")
    }
    @Serializable private data class MigrainePrefInsert(@SerialName("migraine_id") val migraineId: String, val position: Int, val status: String)
    suspend fun getMigrainePrefs(accessToken: String): List<MigrainePrefRow> {
        val response = client.get("$supabaseUrl/rest/v1/migraine_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,user_id,migraine_id,position,status,all_migraines(id,label)")
            parameter("order", "position.asc")
        }
        if (!response.status.isSuccess()) error("Fetch migraine prefs failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun insertMigrainePref(accessToken: String, migraineId: String, position: Int, status: String = "frequent"): MigrainePrefRow {
        val response = client.post("$supabaseUrl/rest/v1/migraine_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "user_id,migraine_id")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(MigrainePrefInsert(migraineId, position, status))
        }
        if (!response.status.isSuccess()) error("Insert migraine pref failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun updateMigrainePref(accessToken: String, prefId: String, position: Int? = null, status: String? = null) {
        val payload = buildJsonObject {
            position?.let { put("position", it) }
            status?.let { put("status", it) }
        }
        val response = client.patch("$supabaseUrl/rest/v1/migraine_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$prefId")
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Update migraine pref failed: ${response.bodyAsText()}")
    }
    suspend fun deleteMigrainePref(accessToken: String, prefId: String) {
        val response = client.delete("$supabaseUrl/rest/v1/migraine_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$prefId")
        }
        if (!response.status.isSuccess()) error("Delete migraine pref failed: ${response.bodyAsText()}")
    }

    // ───────── WEATHER DAILY ─────────
    @Serializable
    data class WeatherDailyRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        val date: String, // ISO date "YYYY-MM-DD"
        @SerialName("temp_c") val tempC: Double? = null,
        @SerialName("pressure_hpa") val pressureHpa: Double? = null,
        @SerialName("humidity_pct") val humidityPct: Double? = null,
        @SerialName("created_at") val createdAt: String,
        @SerialName("updated_at") val updatedAt: String
    )

    suspend fun getWeatherDaily(accessToken: String): List<WeatherDailyRow> {
        val response = client.get("$supabaseUrl/rest/v1/weather_daily") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,user_id,date,temp_c,pressure_hpa,humidity_pct,created_at,updated_at")
            parameter("order", "date.asc")
        }
        if (!response.status.isSuccess()) error("Fetch weather_daily failed: ${response.bodyAsText()}")
        return response.body()
    }
}
