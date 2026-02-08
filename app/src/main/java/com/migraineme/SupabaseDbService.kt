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
        val notes: String? = null,
        @SerialName("pain_locations") val painLocations: List<String>? = null
    )
    @Serializable
    data class MigraineInsert(
        val type: String? = null,
        val severity: Int? = null,
        @SerialName("start_at") val startAt: String,
        @SerialName("ended_at") val endAt: String? = null,
        val notes: String? = null,
        @SerialName("pain_locations") val painLocations: List<String>? = null
    )
    suspend fun insertMigraine(
        accessToken: String,
        type: String?,
        severity: Int?,
        startAt: String?,
        endAt: String?,
        notes: String?,
        painLocations: List<String>? = null
    ): MigraineRow {
        val safeStart = startAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString()
        val payload = MigraineInsert(type, severity, safeStart, endAt, notes, painLocations)
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
        val category: String? = null,
        @SerialName("relief_scale") val reliefScale: String? = "NONE",
        @SerialName("migraine_id") val migraineId: String? = null
    )
    @Serializable
    data class MedicineInsert(
        val name: String? = null,
        val amount: String? = null,
        @SerialName("start_at") val startAt: String,
        val notes: String? = null,
        val category: String? = null,
        @SerialName("relief_scale") val reliefScale: String? = "NONE",
        @SerialName("migraine_id") val migraineId: String? = null
    )
    suspend fun insertMedicine(
        accessToken: String,
        migraineId: String?,
        name: String?,
        amount: String?,
        startAt: String?,
        notes: String?,
        category: String? = null,
        reliefScale: String? = "NONE"
    ): MedicineRow {
        val safeStart = startAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString()
        val payload = MedicineInsert(name, amount, safeStart, notes, category, reliefScale, migraineId)
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
        val category: String? = null,
        @SerialName("end_at") val endAt: String? = null,
        @SerialName("relief_scale") val reliefScale: String? = "NONE",
        @SerialName("migraine_id") val migraineId: String? = null
    )
    @Serializable
    data class ReliefInsert(
        val type: String? = null,
        @SerialName("start_at") val startAt: String,
        val notes: String? = null,
        @SerialName("migraine_id") val migraineId: String? = null,
        val category: String? = null,
        @SerialName("end_at") val endAt: String? = null,
        @SerialName("relief_scale") val reliefScale: String? = "NONE",
    )
    suspend fun insertRelief(
        accessToken: String,
        migraineId: String?,
        type: String?,
        startAt: String?,
        notes: String?,
        endAt: String? = null,
        reliefScale: String? = "NONE"
    ): ReliefRow {
        val safeStart = startAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString()
        val safeEnd = endAt?.takeIf { it.isNotBlank() } ?: safeStart
        val payload = ReliefInsert(type = type, startAt = safeStart, notes = notes, migraineId = migraineId, endAt = safeEnd, reliefScale = reliefScale)
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
        startAt: String? = null,
        notes: String? = null,
        migraineId: String? = null
    ): ReliefRow {
        val payload = buildJsonObject {
            type?.let { put("type", it) }
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
    @Serializable data class AllTriggerRow(
        val id: String,
        val label: String,
        val category: String? = null,
        @SerialName("prediction_value") val predictionValue: String? = "NONE"
    )
    @Serializable
    data class TriggerPrefRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("trigger_id") val triggerId: String,
        val position: Int,
        val status: String,
        @SerialName("all_triggers") val trigger: AllTriggerRow? = null
    )
    @Serializable private data class AllTriggerInsert(val label: String, val category: String? = null, @SerialName("prediction_value") val predictionValue: String? = "NONE")

    suspend fun getAllTriggerPool(accessToken: String): List<AllTriggerRow> {
        val response = client.get("$supabaseUrl/rest/v1/all_triggers") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,label,category,prediction_value"); parameter("order", "label.asc")
        }
        if (!response.status.isSuccess()) error("Fetch all_triggers failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun upsertTriggerToPool(accessToken: String, label: String, category: String? = null, predictionValue: String? = "NONE"): AllTriggerRow {
        val response = client.post("$supabaseUrl/rest/v1/all_triggers") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "label")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(AllTriggerInsert(label, category, predictionValue))
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
    suspend fun updateTriggerPoolItem(
        accessToken: String,
        triggerId: String,
        predictionValue: String? = null,
        category: String? = null
    ) {
        val payload = buildJsonObject {
            predictionValue?.let { put("prediction_value", it) }
            category?.let { put("category", it) }
        }
        val response = client.patch("$supabaseUrl/rest/v1/all_triggers") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("id", "eq.$triggerId")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (!response.status.isSuccess()) error("Update all_triggers failed: ${response.bodyAsText()}")
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
    @Serializable data class AllMedicineRow(val id: String, val label: String, val category: String? = null)
    @Serializable
    data class MedicinePrefRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("medicine_id") val medicineId: String,
        val position: Int,
        val status: String,
        @SerialName("all_medicines") val medicine: AllMedicineRow? = null
    )
    @Serializable private data class AllMedicineInsert(val label: String, val category: String? = null)

    suspend fun getAllMedicinePool(accessToken: String): List<AllMedicineRow> {
        val response = client.get("$supabaseUrl/rest/v1/all_medicines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,label,category"); parameter("order", "label.asc")
        }
        if (!response.status.isSuccess()) error("Fetch all_medicines failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun upsertMedicineToPool(accessToken: String, label: String, category: String? = null): AllMedicineRow {
        val response = client.post("$supabaseUrl/rest/v1/all_medicines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "label")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(AllMedicineInsert(label, category))
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
    suspend fun setMedicineCategory(accessToken: String, medicineId: String, category: String?) {
        val payload = buildJsonObject { category?.let { put("category", it) } ?: put("category", kotlinx.serialization.json.JsonNull) }
        val response = client.patch("$supabaseUrl/rest/v1/all_medicines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$medicineId")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Set medicine category failed: ${response.bodyAsText()}")
    }
    @Serializable private data class MedicinePrefInsert(@SerialName("medicine_id") val medicineId: String, val position: Int, val status: String)
    suspend fun getMedicinePrefs(accessToken: String): List<MedicinePrefRow> {
        val response = client.get("$supabaseUrl/rest/v1/medicine_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,user_id,medicine_id,position,status,all_medicines(id,label,category)")
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
    @Serializable data class AllReliefRow(val id: String, val label: String, val category: String? = null, @SerialName("is_automatable") val isAutomatable: Boolean = false, @SerialName("is_automated") val isAutomated: Boolean = false)
    @Serializable
    data class ReliefPrefRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("relief_id") val reliefId: String,
        val position: Int,
        val status: String,
        @SerialName("all_reliefs") val relief: AllReliefRow? = null
    )
    @Serializable private data class AllReliefInsert(val label: String, val category: String? = null)

    suspend fun getAllReliefPool(accessToken: String): List<AllReliefRow> {
        val response = client.get("$supabaseUrl/rest/v1/all_reliefs") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,label,category,is_automatable,is_automated"); parameter("order", "label.asc")
        }
        if (!response.status.isSuccess()) error("Fetch all_reliefs failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun upsertReliefToPool(accessToken: String, label: String, category: String? = null): AllReliefRow {
        val response = client.post("$supabaseUrl/rest/v1/all_reliefs") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "label")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(AllReliefInsert(label, category))
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
    suspend fun setReliefCategory(accessToken: String, reliefId: String, category: String?) {
        val payload = buildJsonObject { category?.let { put("category", it) } ?: put("category", kotlinx.serialization.json.JsonNull) }
        val response = client.patch("$supabaseUrl/rest/v1/all_reliefs") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$reliefId")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Set relief category failed: ${response.bodyAsText()}")
    }
    suspend fun setReliefAutomation(accessToken: String, reliefId: String, enabled: Boolean) {
        val payload = buildJsonObject { put("is_automated", enabled) }
        val response = client.patch("$supabaseUrl/rest/v1/all_reliefs") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$reliefId")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Set relief automation failed: ${response.bodyAsText()}")
    }
    @Serializable private data class ReliefPrefInsert(@SerialName("relief_id") val reliefId: String, val position: Int, val status: String)
    suspend fun getReliefPrefs(accessToken: String): List<ReliefPrefRow> {
        val response = client.get("$supabaseUrl/rest/v1/relief_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,user_id,relief_id,position,status,all_reliefs(id,label,category,is_automatable,is_automated)")
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

    // ───────── SYMPTOM POOL ─────────
    @Serializable data class AllSymptomRow(
        val id: String,
        val label: String,
        val category: String? = null,
        @SerialName("icon_key") val iconKey: String? = null
    )
    @Serializable
    data class SymptomPrefRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("symptom_id") val symptomId: String,
        val position: Int,
        val status: String,
        @SerialName("all_symptoms") val symptom: AllSymptomRow? = null
    )
    @Serializable private data class AllSymptomInsert(
        val label: String,
        val category: String? = null,
        @SerialName("icon_key") val iconKey: String? = null
    )

    suspend fun getAllSymptomPool(accessToken: String): List<AllSymptomRow> {
        val response = client.get("$supabaseUrl/rest/v1/all_symptoms") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,label,category,icon_key"); parameter("order", "label.asc")
        }
        if (!response.status.isSuccess()) error("Fetch all_symptoms failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun upsertSymptomToPool(accessToken: String, label: String, category: String? = null, iconKey: String? = null): AllSymptomRow {
        val response = client.post("$supabaseUrl/rest/v1/all_symptoms") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "user_id,label")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(AllSymptomInsert(label, category, iconKey))
        }
        if (!response.status.isSuccess()) error("Upsert all_symptoms failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteSymptomFromPool(accessToken: String, symptomId: String) {
        client.delete("$supabaseUrl/rest/v1/symptom_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("symptom_id", "eq.$symptomId")
        }
        val response = client.delete("$supabaseUrl/rest/v1/all_symptoms") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$symptomId")
        }
        if (!response.status.isSuccess()) error("Delete all_symptoms failed: ${response.bodyAsText()}")
    }
    @Serializable private data class SymptomPrefInsert(@SerialName("symptom_id") val symptomId: String, val position: Int, val status: String)
    suspend fun getSymptomPrefs(accessToken: String): List<SymptomPrefRow> {
        val response = client.get("$supabaseUrl/rest/v1/symptom_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,user_id,symptom_id,position,status,all_symptoms(id,label,category,icon_key)")
            parameter("order", "position.asc")
        }
        if (!response.status.isSuccess()) error("Fetch symptom prefs failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun insertSymptomPref(accessToken: String, symptomId: String, position: Int, status: String = "frequent"): SymptomPrefRow {
        val response = client.post("$supabaseUrl/rest/v1/symptom_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "user_id,symptom_id")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(SymptomPrefInsert(symptomId, position, status))
        }
        if (!response.status.isSuccess()) error("Insert symptom pref failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteSymptomPref(accessToken: String, prefId: String) {
        val response = client.delete("$supabaseUrl/rest/v1/symptom_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$prefId")
        }
        if (!response.status.isSuccess()) error("Delete symptom pref failed: ${response.bodyAsText()}")
    }

    // ───────── PRODROME POOL ─────────
    @Serializable data class AllProdromeRow(
        val id: String,
        val label: String,
        val category: String? = null,
        @SerialName("icon_key") val iconKey: String? = null,
        @SerialName("prediction_value") val predictionValue: String? = "NONE"
    )
    @Serializable
    data class ProdromePrefRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("prodrome_id") val prodromeId: String,
        val position: Int,
        val status: String,
        @SerialName("all_prodromes") val prodrome: AllProdromeRow? = null
    )
    @Serializable private data class AllProdromeInsert(
        val label: String,
        val category: String? = null,
        @SerialName("prediction_value") val predictionValue: String? = "NONE"
    )

    suspend fun getAllProdromePool(accessToken: String): List<AllProdromeRow> {
        val response = client.get("$supabaseUrl/rest/v1/all_prodromes") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,label,category,icon_key,prediction_value"); parameter("order", "label.asc")
        }
        if (!response.status.isSuccess()) error("Fetch all_prodromes failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun upsertProdromeToPool(accessToken: String, label: String, category: String? = null, predictionValue: String? = "NONE"): AllProdromeRow {
        val response = client.post("$supabaseUrl/rest/v1/all_prodromes") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "user_id,label")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(AllProdromeInsert(label, category, predictionValue))
        }
        if (!response.status.isSuccess()) error("Upsert all_prodromes failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteProdromeFromPool(accessToken: String, prodromeId: String) {
        client.delete("$supabaseUrl/rest/v1/prodrome_user_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("prodrome_id", "eq.$prodromeId")
        }
        val response = client.delete("$supabaseUrl/rest/v1/all_prodromes") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$prodromeId")
        }
        if (!response.status.isSuccess()) error("Delete all_prodromes failed: ${response.bodyAsText()}")
    }
    suspend fun updateProdromePoolItem(
        accessToken: String,
        prodromeId: String,
        predictionValue: String? = null,
        category: String? = null
    ) {
        val payload = buildJsonObject {
            predictionValue?.let { put("prediction_value", it) }
            category?.let { put("category", it) }
        }
        val response = client.patch("$supabaseUrl/rest/v1/all_prodromes") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("id", "eq.$prodromeId")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (!response.status.isSuccess()) error("Update all_prodromes failed: ${response.bodyAsText()}")
    }
    @Serializable private data class ProdromePrefInsert(@SerialName("prodrome_id") val prodromeId: String, val position: Int, val status: String)
    suspend fun getProdromePrefs(accessToken: String): List<ProdromePrefRow> {
        val response = client.get("$supabaseUrl/rest/v1/prodrome_user_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,user_id,prodrome_id,position,status,all_prodromes(id,label,category,icon_key,prediction_value)")
            parameter("order", "position.asc")
        }
        if (!response.status.isSuccess()) error("Fetch prodrome prefs failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun insertProdromePref(accessToken: String, prodromeId: String, position: Int, status: String = "frequent"): ProdromePrefRow {
        val response = client.post("$supabaseUrl/rest/v1/prodrome_user_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "user_id,prodrome_id")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(ProdromePrefInsert(prodromeId, position, status))
        }
        if (!response.status.isSuccess()) error("Insert prodrome pref failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteProdromePref(accessToken: String, prefId: String) {
        val response = client.delete("$supabaseUrl/rest/v1/prodrome_user_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$prefId")
        }
        if (!response.status.isSuccess()) error("Delete prodrome pref failed: ${response.bodyAsText()}")
    }

    // ───────── PRODROME LOG ─────────
    @Serializable private data class ProdromeLogInsert(
        val type: String?,
        @SerialName("start_at") val startAt: String,
        val notes: String? = null,
        @SerialName("migraine_id") val migraineId: String? = null,
        val source: String = "manual"
    )
    @Serializable data class ProdromeLogRow(
        val id: String,
        val type: String? = null,
        @SerialName("start_at") val startAt: String? = null,
        val notes: String? = null,
        @SerialName("migraine_id") val migraineId: String? = null
    )
    suspend fun insertProdrome(
        accessToken: String,
        migraineId: String?,
        type: String?,
        startAt: String?,
        notes: String?
    ): ProdromeLogRow {
        val safeStart = startAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString()
        val payload = ProdromeLogInsert(type, safeStart, notes, migraineId)
        val response: HttpResponse = client.post("$supabaseUrl/rest/v1/prodromes") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "return=representation")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (!response.status.isSuccess()) error("Insert prodrome failed: ${response.bodyAsText()}")
        return response.body()
    }

    // ───────── LOCATION POOL / PREFS ─────────
    @Serializable data class AllLocationRow(val id: String, val label: String, val category: String? = null, @SerialName("is_automatable") val isAutomatable: Boolean = false, @SerialName("is_automated") val isAutomated: Boolean = false)
    @Serializable
    data class LocationPrefRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("location_id") val locationId: String,
        val position: Int,
        val status: String,
        @SerialName("all_locations") val location: AllLocationRow? = null
    )
    @Serializable private data class AllLocationInsert(val label: String, val category: String? = null)

    suspend fun getAllLocationPool(accessToken: String): List<AllLocationRow> {
        val response = client.get("$supabaseUrl/rest/v1/all_locations") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,label,category,is_automatable,is_automated"); parameter("order", "label.asc")
        }
        if (!response.status.isSuccess()) error("Fetch all_locations failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun upsertLocationToPool(accessToken: String, label: String, category: String? = null): AllLocationRow {
        val response = client.post("$supabaseUrl/rest/v1/all_locations") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "label")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(AllLocationInsert(label, category))
        }
        if (!response.status.isSuccess()) error("Upsert all_locations failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteLocationFromPool(accessToken: String, locationId: String) {
        client.delete("$supabaseUrl/rest/v1/location_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("location_id", "eq.$locationId")
        }
        val response = client.delete("$supabaseUrl/rest/v1/all_locations") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$locationId")
        }
        if (!response.status.isSuccess()) error("Delete all_locations failed: ${response.bodyAsText()}")
    }
    suspend fun setLocationCategory(accessToken: String, locationId: String, category: String?) {
        val payload = buildJsonObject { category?.let { put("category", it) } ?: put("category", kotlinx.serialization.json.JsonNull) }
        val response = client.patch("$supabaseUrl/rest/v1/all_locations") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$locationId")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Set location category failed: ${response.bodyAsText()}")
    }
    suspend fun setLocationAutomation(accessToken: String, locationId: String, enabled: Boolean) {
        val payload = buildJsonObject { put("is_automated", enabled) }
        val response = client.patch("$supabaseUrl/rest/v1/all_locations") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$locationId")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Set location automation failed: ${response.bodyAsText()}")
    }
    @Serializable private data class LocationPrefInsert(@SerialName("location_id") val locationId: String, val position: Int, val status: String)
    suspend fun getLocationPrefs(accessToken: String): List<LocationPrefRow> {
        val response = client.get("$supabaseUrl/rest/v1/location_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,user_id,location_id,position,status,all_locations(id,label,category,is_automatable,is_automated)")
        }
        if (!response.status.isSuccess()) error("Fetch location_preferences failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun insertLocationPref(accessToken: String, locationId: String, position: Int, status: String = "frequent"): LocationPrefRow {
        val response = client.post("$supabaseUrl/rest/v1/location_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(LocationPrefInsert(locationId, position, status))
        }
        if (!response.status.isSuccess()) error("Insert location_pref failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteLocationPref(accessToken: String, prefId: String) {
        val response = client.delete("$supabaseUrl/rest/v1/location_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$prefId")
        }
        if (!response.status.isSuccess()) error("Delete location_pref failed: ${response.bodyAsText()}")
    }

    // ── Location log ──
    @Serializable
    data class LocationLogRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        val type: String? = null,
        val category: String? = null,
        @SerialName("start_at") val startAt: String,
        val notes: String? = null,
        @SerialName("migraine_id") val migraineId: String? = null
    )
    @Serializable
    private data class LocationLogInsert(
        val type: String? = null,
        val category: String? = null,
        @SerialName("start_at") val startAt: String,
        val notes: String? = null,
        @SerialName("migraine_id") val migraineId: String? = null
    )
    suspend fun insertLocation(
        accessToken: String,
        migraineId: String?,
        type: String?,
        startAt: String?,
        notes: String?
    ): LocationLogRow {
        val safeStart = startAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString()
        val payload = LocationLogInsert(type = type, startAt = safeStart, notes = notes, migraineId = migraineId)
        val response: HttpResponse = client.post("$supabaseUrl/rest/v1/locations") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "return=representation")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (!response.status.isSuccess()) error("Insert location failed: ${response.bodyAsText()}")
        return response.body()
    }

    // ───────── ACTIVITY POOL / PREFS ─────────
    @Serializable data class AllActivityRow(val id: String, val label: String, val category: String? = null, @SerialName("is_automatable") val isAutomatable: Boolean = false, @SerialName("is_automated") val isAutomated: Boolean = false)
    @Serializable
    data class ActivityPrefRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("activity_id") val activityId: String,
        val position: Int,
        val status: String,
        @SerialName("all_activities") val activity: AllActivityRow? = null
    )
    @Serializable private data class AllActivityInsert(val label: String, val category: String? = null)

    suspend fun getAllActivityPool(accessToken: String): List<AllActivityRow> {
        val response = client.get("$supabaseUrl/rest/v1/all_activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,label,category,is_automatable,is_automated"); parameter("order", "label.asc")
        }
        if (!response.status.isSuccess()) error("Fetch all_activities failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun upsertActivityToPool(accessToken: String, label: String, category: String? = null): AllActivityRow {
        val response = client.post("$supabaseUrl/rest/v1/all_activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "label")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(AllActivityInsert(label, category))
        }
        if (!response.status.isSuccess()) error("Upsert all_activities failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteActivityFromPool(accessToken: String, activityId: String) {
        client.delete("$supabaseUrl/rest/v1/activity_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("activity_id", "eq.$activityId")
        }
        val response = client.delete("$supabaseUrl/rest/v1/all_activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$activityId")
        }
        if (!response.status.isSuccess()) error("Delete all_activities failed: ${response.bodyAsText()}")
    }
    suspend fun setActivityCategory(accessToken: String, activityId: String, category: String?) {
        val payload = buildJsonObject { category?.let { put("category", it) } ?: put("category", kotlinx.serialization.json.JsonNull) }
        val response = client.patch("$supabaseUrl/rest/v1/all_activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$activityId")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Set activity category failed: ${response.bodyAsText()}")
    }
    suspend fun setActivityAutomation(accessToken: String, activityId: String, enabled: Boolean) {
        val payload = buildJsonObject { put("is_automated", enabled) }
        val response = client.patch("$supabaseUrl/rest/v1/all_activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$activityId")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Set activity automation failed: ${response.bodyAsText()}")
    }
    @Serializable private data class ActivityPrefInsert(@SerialName("activity_id") val activityId: String, val position: Int, val status: String)
    suspend fun getActivityPrefs(accessToken: String): List<ActivityPrefRow> {
        val response = client.get("$supabaseUrl/rest/v1/activity_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,user_id,activity_id,position,status,all_activities(id,label,category,is_automatable,is_automated)")
        }
        if (!response.status.isSuccess()) error("Fetch activity_preferences failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun insertActivityPref(accessToken: String, activityId: String, position: Int, status: String = "frequent"): ActivityPrefRow {
        val response = client.post("$supabaseUrl/rest/v1/activity_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(ActivityPrefInsert(activityId, position, status))
        }
        if (!response.status.isSuccess()) error("Insert activity_pref failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteActivityPref(accessToken: String, prefId: String) {
        val response = client.delete("$supabaseUrl/rest/v1/activity_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$prefId")
        }
        if (!response.status.isSuccess()) error("Delete activity_pref failed: ${response.bodyAsText()}")
    }

    // ── Activity log ──
    @Serializable
    data class ActivityLogRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        val type: String? = null,
        val category: String? = null,
        @SerialName("start_at") val startAt: String,
        val notes: String? = null,
        @SerialName("migraine_id") val migraineId: String? = null
    )
    @Serializable
    private data class ActivityLogInsert(
        val type: String? = null,
        val category: String? = null,
        @SerialName("start_at") val startAt: String,
        val notes: String? = null,
        @SerialName("migraine_id") val migraineId: String? = null
    )
    suspend fun insertActivity(
        accessToken: String,
        migraineId: String?,
        type: String?,
        startAt: String?,
        notes: String?
    ): ActivityLogRow {
        val safeStart = startAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString()
        val payload = ActivityLogInsert(type = type, startAt = safeStart, notes = notes, migraineId = migraineId)
        val response: HttpResponse = client.post("$supabaseUrl/rest/v1/activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "return=representation")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (!response.status.isSuccess()) error("Insert activity failed: ${response.bodyAsText()}")
        return response.body()
    }

    // ───────── MISSED ACTIVITY POOL / PREFS ─────────
    @Serializable data class AllMissedActivityRow(val id: String, val label: String, val category: String? = null, @SerialName("is_automatable") val isAutomatable: Boolean = false, @SerialName("is_automated") val isAutomated: Boolean = false)
    @Serializable
    data class MissedActivityPrefRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("missed_activity_id") val missedActivityId: String,
        val position: Int,
        val status: String,
        @SerialName("all_missed_activities") val missedActivity: AllMissedActivityRow? = null
    )
    @Serializable private data class AllMissedActivityInsert(val label: String, val category: String? = null)

    suspend fun getAllMissedActivityPool(accessToken: String): List<AllMissedActivityRow> {
        val response = client.get("$supabaseUrl/rest/v1/all_missed_activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,label,category,is_automatable,is_automated"); parameter("order", "label.asc")
        }
        if (!response.status.isSuccess()) error("Fetch all_missed_activities failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun upsertMissedActivityToPool(accessToken: String, label: String, category: String? = null): AllMissedActivityRow {
        val response = client.post("$supabaseUrl/rest/v1/all_missed_activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "label")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(AllMissedActivityInsert(label, category))
        }
        if (!response.status.isSuccess()) error("Upsert all_missed_activities failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteMissedActivityFromPool(accessToken: String, id: String) {
        client.delete("$supabaseUrl/rest/v1/missed_activity_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("missed_activity_id", "eq.$id")
        }
        val response = client.delete("$supabaseUrl/rest/v1/all_missed_activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
        }
        if (!response.status.isSuccess()) error("Delete all_missed_activities failed: ${response.bodyAsText()}")
    }
    suspend fun setMissedActivityCategory(accessToken: String, id: String, category: String?) {
        val payload = buildJsonObject { category?.let { put("category", it) } ?: put("category", kotlinx.serialization.json.JsonNull) }
        val response = client.patch("$supabaseUrl/rest/v1/all_missed_activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Set missed activity category failed: ${response.bodyAsText()}")
    }
    suspend fun setMissedActivityAutomation(accessToken: String, id: String, enabled: Boolean) {
        val payload = buildJsonObject { put("is_automated", enabled) }
        val response = client.patch("$supabaseUrl/rest/v1/all_missed_activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Set missed activity automation failed: ${response.bodyAsText()}")
    }
    @Serializable private data class MissedActivityPrefInsert(@SerialName("missed_activity_id") val missedActivityId: String, val position: Int, val status: String)
    suspend fun getMissedActivityPrefs(accessToken: String): List<MissedActivityPrefRow> {
        val response = client.get("$supabaseUrl/rest/v1/missed_activity_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,user_id,missed_activity_id,position,status,all_missed_activities(id,label,category,is_automatable,is_automated)")
        }
        if (!response.status.isSuccess()) error("Fetch missed_activity_preferences failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun insertMissedActivityPref(accessToken: String, missedActivityId: String, position: Int, status: String = "frequent"): MissedActivityPrefRow {
        val response = client.post("$supabaseUrl/rest/v1/missed_activity_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(MissedActivityPrefInsert(missedActivityId, position, status))
        }
        if (!response.status.isSuccess()) error("Insert missed_activity_pref failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteMissedActivityPref(accessToken: String, prefId: String) {
        val response = client.delete("$supabaseUrl/rest/v1/missed_activity_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$prefId")
        }
        if (!response.status.isSuccess()) error("Delete missed_activity_pref failed: ${response.bodyAsText()}")
    }

    // ── Missed Activity log ──
    @Serializable
    data class MissedActivityLogRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        val type: String? = null,
        val category: String? = null,
        @SerialName("start_at") val startAt: String,
        val notes: String? = null,
        @SerialName("migraine_id") val migraineId: String? = null
    )
    @Serializable
    private data class MissedActivityLogInsert(
        val type: String? = null,
        val category: String? = null,
        @SerialName("start_at") val startAt: String,
        val notes: String? = null,
        @SerialName("migraine_id") val migraineId: String? = null
    )
    suspend fun insertMissedActivity(
        accessToken: String,
        migraineId: String?,
        type: String?,
        startAt: String?,
        notes: String?
    ): MissedActivityLogRow {
        val safeStart = startAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString()
        val payload = MissedActivityLogInsert(type = type, startAt = safeStart, notes = notes, migraineId = migraineId)
        val response: HttpResponse = client.post("$supabaseUrl/rest/v1/missed_activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "return=representation")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (!response.status.isSuccess()) error("Insert missed_activity failed: ${response.bodyAsText()}")
        return response.body()
    }
}
