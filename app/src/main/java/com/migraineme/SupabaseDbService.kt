// app/src/main/java/com/migraineme/SupabaseDbService.kt
package com.migraineme

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
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

    /**
     * One window of the Journal feed, pushed down to PostgREST.
     *
     * The feed is a merge of eight tables ordered by start_at descending, so a
     * page is expressed as a time cursor rather than an offset: everything
     * older than [beforeIso] (null = start at the newest entry), no older than
     * [sinceIso], capped at [limit] rows per table.
     *
     * [beforeInclusive] decides whether the bound keeps rows sharing the
     * cursor's own timestamp. It must be true for a page boundary: several rows
     * can share one start_at, a strict bound drops the ones that fell past the
     * previous page, and no later page ever asks for that timestamp again — so
     * they vanish from the feed for good. The caller re-reads the boundary and
     * drops the ids it has already shown. A plain filter ceiling stays strict,
     * since it is a timeframe bound rather than a page boundary.
     *
     * Without this a thousand-entry account read every row of every table, plus
     * one linked-items fan-out per attack, just to draw the first screenful.
     */
    data class JournalWindow(
        val beforeIso: String? = null,
        val sinceIso: String? = null,
        val limit: Int = 60,
        val beforeInclusive: Boolean = false
    )

    private fun HttpRequestBuilder.journalWindow(w: JournalWindow?) {
        if (w == null) return
        // Repeated filters on one column are ANDed by PostgREST, so the two
        // bounds compose into the window the cursor walk needs.
        w.beforeIso?.let { parameter("start_at", if (w.beforeInclusive) "lte.$it" else "lt.$it") }
        w.sinceIso?.let { parameter("start_at", "gte.$it") }
        header("Range-Unit", "items")
        header("Range", "0-${(w.limit - 1).coerceAtLeast(0)}")
    }

    //  MIGRAINES 
    @Serializable
    data class MigraineRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        val type: String? = null,
        val severity: Int? = null,
        @SerialName("start_at") val startAt: String,
        @SerialName("ended_at") val endAt: String? = null,
        val notes: String? = null,
        @SerialName("pain_locations") val painLocations: List<String>? = null,
        @SerialName("aura_locations") val auraLocations: List<String>? = null,
        @SerialName("aura_duration_minutes") val auraDurationMinutes: Int? = null
    )
    @Serializable
    data class MigraineInsert(
        val type: String? = null,
        val severity: Int? = null,
        @SerialName("start_at") val startAt: String,
        @SerialName("ended_at") val endAt: String? = null,
        val notes: String? = null,
        @SerialName("pain_locations") val painLocations: List<String>? = null,
        @SerialName("aura_locations") val auraLocations: List<String>? = null,
        @SerialName("aura_duration_minutes") val auraDurationMinutes: Int? = null
    )
    /** Lightweight row for migraine linking. */
    @Serializable data class MigraneSummaryRow(
        val id: String,
        val type: String? = null,
        val severity: Int? = null,
        @SerialName("start_at") val startAt: String
    )

    /** Fetch migraines within a date range for linking in quick log. */
    suspend fun getNearbyMigraines(accessToken: String, fromDate: String, toDate: String): List<MigraneSummaryRow> {
        val response = client.get("$supabaseUrl/rest/v1/migraines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "id,type,severity,start_at")
            parameter("and", "(start_at.gte.${fromDate}T00:00:00Z,start_at.lte.${toDate}T23:59:59Z)")
            parameter("order", "start_at.desc")
        }
        if (!response.status.isSuccess()) return emptyList()
        return response.body()
    }

    //  PAIN POINTS (timestamped, one row per location per pain entry)
    // Rows sharing (migraine_id, start_at) form one pain entry. The parent
    // migraines row keeps severity = MAX and pain_locations = UNION so
    // Insights / correlation stats / PDF read the mirrors unchanged.
    @Serializable
    data class PainPointRow(
        val id: String,
        @SerialName("migraine_id") val migraineId: String,
        @SerialName("location_id") val locationId: String,
        val severity: Int? = null,
        @SerialName("start_at") val startAt: String
    )
    @Serializable
    data class PainPointInsert(
        @SerialName("migraine_id") val migraineId: String,
        @SerialName("location_id") val locationId: String,
        val severity: Int? = null,
        @SerialName("start_at") val startAt: String
    )

    suspend fun getPainPoints(accessToken: String, migraineId: String): List<PainPointRow> {
        val r = client.get("$supabaseUrl/rest/v1/migraine_pain_points") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("migraine_id", "eq.$migraineId")
            parameter("order", "start_at.asc")
        }
        return if (r.status.isSuccess()) r.body() else emptyList()
    }

    /** All of the user's pain points (RLS scopes to the user) for the journal feed. */
    suspend fun getAllPainPoints(accessToken: String): List<PainPointRow> {
        val r = client.get("$supabaseUrl/rest/v1/migraine_pain_points") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("order", "start_at.asc")
        }
        return if (r.status.isSuccess()) r.body() else emptyList()
    }

    // ── Aura zones (timestamped, one row per zone per moment) ──

    /** Rows sharing (migraine_id, start_at) form one aura entry. The parent
     *  migraines row keeps aura_locations = UNION and aura_duration_minutes =
     *  total, so Insights / correlation stats / chat read the mirrors
     *  unchanged. */
    @Serializable
    data class AuraZoneRow(
        val id: String,
        @SerialName("migraine_id") val migraineId: String,
        val zone: String,
        @SerialName("start_at") val startAt: String? = null,
        @SerialName("duration_minutes") val durationMinutes: Int? = null,
    )

    @Serializable
    data class AuraZoneInsert(
        @SerialName("migraine_id") val migraineId: String,
        val zone: String,
        @SerialName("start_at") val startAt: String,
        @SerialName("duration_minutes") val durationMinutes: Int? = null,
    )

    suspend fun getAuraZones(accessToken: String, migraineId: String): List<AuraZoneRow> {
        val r = client.get("$supabaseUrl/rest/v1/migraine_aura_zones") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("migraine_id", "eq.$migraineId")
            parameter("order", "start_at.asc")
        }
        return if (r.status.isSuccess()) r.body() else emptyList()
    }

    /** All of the user's aura zones (RLS scopes to the user), for the report. */
    suspend fun getAllAuraZones(accessToken: String): List<AuraZoneRow> {
        val r = client.get("$supabaseUrl/rest/v1/migraine_aura_zones") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("order", "start_at.asc")
        }
        return if (r.status.isSuccess()) r.body() else emptyList()
    }

    /** Append-only insert for the evening check-in's aura moment — replace
     *  would wipe the zones logged when the attack started. */
    suspend fun insertAuraZones(accessToken: String, rows: List<AuraZoneInsert>) {
        if (rows.isEmpty()) return
        val ins = client.post("$supabaseUrl/rest/v1/migraine_aura_zones") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            contentType(ContentType.Application.Json)
            setBody(rows)
        }
        if (!ins.status.isSuccess()) error("Insert aura zones failed: ${ins.bodyAsText()}")
    }

    /** Wholesale replace, same as pain points: aura entries have no per-row
     *  identity worth reconciling. */
    suspend fun replaceAuraZones(accessToken: String, migraineId: String, rows: List<AuraZoneInsert>) {
        val del = client.delete("$supabaseUrl/rest/v1/migraine_aura_zones") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("migraine_id", "eq.$migraineId")
        }
        if (!del.status.isSuccess()) error("Delete aura zones failed: ${del.bodyAsText()}")
        if (rows.isEmpty()) return
        val ins = client.post("$supabaseUrl/rest/v1/migraine_aura_zones") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            contentType(ContentType.Application.Json)
            setBody(rows)
        }
        if (!ins.status.isSuccess()) error("Insert aura zones failed: ${ins.bodyAsText()}")
    }

    /** Append-only insert for the evening check-in's "pain update": the wizard's
     *  wholesale replace would wipe the entries logged at attack start. */
    suspend fun insertPainPoints(accessToken: String, rows: List<PainPointInsert>) {
        if (rows.isEmpty()) return
        val ins = client.post("$supabaseUrl/rest/v1/migraine_pain_points") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            contentType(ContentType.Application.Json)
            setBody(rows)
        }
        if (!ins.status.isSuccess()) error("Insert pain points failed: ${ins.bodyAsText()}")
    }

    /** Wholesale replace: pain entries have no per-row identity worth reconciling. */
    suspend fun replacePainPoints(accessToken: String, migraineId: String, rows: List<PainPointInsert>) {
        val del = client.delete("$supabaseUrl/rest/v1/migraine_pain_points") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("migraine_id", "eq.$migraineId")
        }
        if (!del.status.isSuccess()) error("Delete pain points failed: ${del.bodyAsText()}")
        if (rows.isEmpty()) return
        val response = client.post("$supabaseUrl/rest/v1/migraine_pain_points") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            contentType(ContentType.Application.Json)
            setBody(rows)
        }
        if (!response.status.isSuccess()) error("Insert pain points failed: ${response.bodyAsText()}")
    }

    /** All items linked to a specific migraine, for display on the journal card. */
    data class MigraineLinkedItems(
        val triggers: List<TriggerRow> = emptyList(),
        val medicines: List<MedicineRow> = emptyList(),
        val reliefs: List<ReliefRow> = emptyList(),
        val prodromes: List<ProdromeLogRow> = emptyList(),
        val postdromes: List<SymptomLogRow> = emptyList(),
        val activities: List<ActivityLogRow> = emptyList(),
        val locations: List<LocationLogRow> = emptyList(),
        val painPoints: List<PainPointRow> = emptyList(),
        val missedActivities: List<MissedActivityLogRow> = emptyList()
    )

    suspend fun getLinkedItems(accessToken: String, migraineId: String): MigraineLinkedItems {
        suspend fun fetchTriggers(table: String): List<TriggerRow> {
            val r = client.get("$supabaseUrl/rest/v1/$table") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                parameter("migraine_id", "eq.$migraineId")
                parameter("order", "start_at.asc")
            }
            return if (r.status.isSuccess()) r.body() else emptyList()
        }
        suspend fun fetchMedicines(table: String): List<MedicineRow> {
            val r = client.get("$supabaseUrl/rest/v1/$table") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                parameter("migraine_id", "eq.$migraineId")
                parameter("order", "start_at.asc")
            }
            return if (r.status.isSuccess()) r.body() else emptyList()
        }
        suspend fun fetchReliefs(table: String): List<ReliefRow> {
            val r = client.get("$supabaseUrl/rest/v1/$table") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                parameter("migraine_id", "eq.$migraineId")
                parameter("order", "start_at.asc")
            }
            return if (r.status.isSuccess()) r.body() else emptyList()
        }
        suspend fun fetchProdromes(table: String): List<ProdromeLogRow> {
            val r = client.get("$supabaseUrl/rest/v1/$table") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                parameter("migraine_id", "eq.$migraineId")
                parameter("order", "start_at.asc")
            }
            return if (r.status.isSuccess()) r.body() else emptyList()
        }
        suspend fun fetchActivities(table: String): List<ActivityLogRow> {
            val r = client.get("$supabaseUrl/rest/v1/$table") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                parameter("migraine_id", "eq.$migraineId")
                parameter("order", "start_at.asc")
            }
            return if (r.status.isSuccess()) r.body() else emptyList()
        }
        suspend fun fetchLocations(table: String): List<LocationLogRow> {
            val r = client.get("$supabaseUrl/rest/v1/$table") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                parameter("migraine_id", "eq.$migraineId")
                parameter("order", "start_at.asc")
            }
            return if (r.status.isSuccess()) r.body() else emptyList()
        }
        suspend fun fetchMissedActivities(): List<MissedActivityLogRow> {
            val r = client.get("$supabaseUrl/rest/v1/missed_activities") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                parameter("migraine_id", "eq.$migraineId")
                parameter("order", "start_at.asc")
            }
            return if (r.status.isSuccess()) r.body() else emptyList()
        }
        suspend fun fetchPostdromes(): List<SymptomLogRow> {
            val r = client.get("$supabaseUrl/rest/v1/symptoms") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                parameter("migraine_id", "eq.$migraineId")
                parameter("order", "created_at.asc")
            }
            return if (r.status.isSuccess()) r.body() else emptyList()
        }
        // Eight independent single-row-set queries. Run them together: called
        // once per migraine from loadJournal, the sequential version cost 8
        // serial round-trips per attack, which is what left the Journal blank
        // for tens of seconds behind the onboarding tour card.
        return coroutineScope {
            val triggers = async { fetchTriggers("triggers") }
            val medicines = async { fetchMedicines("medicines") }
            val reliefs = async { fetchReliefs("reliefs") }
            val prodromes = async { fetchProdromes("prodromes") }
            val postdromes = async { fetchPostdromes() }
            val activities = async { fetchActivities("time_in_high_hr_zones_daily") }
            val locations = async { fetchLocations("locations") }
            val painPoints = async { getPainPoints(accessToken, migraineId) }
            val missedActivities = async { fetchMissedActivities() }
            MigraineLinkedItems(
                triggers = triggers.await(),
                medicines = medicines.await(),
                reliefs = reliefs.await(),
                prodromes = prodromes.await(),
                postdromes = postdromes.await(),
                activities = activities.await(),
                locations = locations.await(),
                painPoints = painPoints.await(),
                missedActivities = missedActivities.await()
            )
        }
    }

    suspend fun insertMigraine(
        accessToken: String,
        type: String?,
        severity: Int?,
        startAt: String?,
        endAt: String?,
        notes: String?,
        painLocations: List<String>? = null,
        auraLocations: List<String>? = null,
        auraDurationMinutes: Int? = null
    ): MigraineRow {
        val safeStart = startAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString()
        val payload = MigraineInsert(type, severity, safeStart, endAt, notes, painLocations, auraLocations, auraDurationMinutes)
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
    suspend fun getMigraines(accessToken: String, window: JournalWindow? = null): List<MigraineRow> {
        val response: HttpResponse = client.get("$supabaseUrl/rest/v1/migraines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "*")
            parameter("order", "start_at.desc")
            journalWindow(window)
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
        notes: String? = null,
        painLocations: List<String>? = null,
        // When true, pain_locations is written as NULL — the caller owns the
        // field and the user cleared it.
        clearPainLocations: Boolean = false,
        // When true, aura fields are always written, so an emptied aura clears the columns.
        setAura: Boolean = false,
        auraLocations: List<String>? = null,
        auraDurationMinutes: Int? = null
    ): MigraineRow {
        val payload = buildJsonObject {
            type?.let { put("type", it) }
            severity?.let { put("severity", it) }
            startAt?.let { put("start_at", it) }
            endAt?.let { put("ended_at", it) }
            notes?.let { put("notes", it) }
            // Written even when empty so deselecting every location actually
            // clears the column. Omitting the key left a stale array that
            // Insights, correlation stats and the PDF kept reading.
            if (clearPainLocations) put("pain_locations", kotlinx.serialization.json.JsonNull)
            else painLocations?.let { locs ->
                put("pain_locations", kotlinx.serialization.json.JsonArray(locs.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            }
            if (setAura) {
                if (auraLocations.isNullOrEmpty()) put("aura_locations", kotlinx.serialization.json.JsonNull)
                else put("aura_locations", kotlinx.serialization.json.JsonArray(auraLocations.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                if (auraDurationMinutes == null) put("aura_duration_minutes", kotlinx.serialization.json.JsonNull)
                else put("aura_duration_minutes", auraDurationMinutes)
            }
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
    /** Set migraine_id to null on any table row, unlinking it from a migraine. */
    suspend fun unlinkFromMigraine(accessToken: String, table: String, id: String) {
        val payload = buildJsonObject { put("migraine_id", kotlinx.serialization.json.JsonNull) }
        client.patch("$supabaseUrl/rest/v1/$table") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
            contentType(ContentType.Application.Json); setBody(payload)
        }
    }

    /** Set migraine_id on any table row, linking it to a migraine. */
    suspend fun linkToMigraine(accessToken: String, table: String, id: String, migraineId: String) {
        val payload = buildJsonObject { put("migraine_id", migraineId) }
        client.patch("$supabaseUrl/rest/v1/$table") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
            contentType(ContentType.Application.Json); setBody(payload)
        }
    }

    suspend fun deleteMigraine(accessToken: String, id: String) {
        val response = client.delete("$supabaseUrl/rest/v1/migraines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
        }
        if (!response.status.isSuccess()) error("Delete migraine failed: ${response.bodyAsText()}")
    }

    //  TRIGGERS 
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
        notes: String?,
        source: String? = "manual"
    ): TriggerRow {
        val safeStart = startAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString()
        val payload = TriggerInsert(type, safeStart, notes, migraineId, source)
        // Try normal insert first
        val response: HttpResponse = client.post("$supabaseUrl/rest/v1/triggers") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "return=representation")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (response.status.isSuccess()) return response.body()

        // If duplicate key (23505), find existing and update only migraine_id
        val errBody = response.bodyAsText()
        if ("23505" in errBody && migraineId != null) {
            // Find the existing trigger by user+time+type
            val existing: List<TriggerRow> = client.get("$supabaseUrl/rest/v1/triggers") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                parameter("type", "eq.$type")
                parameter("start_at", "eq.$safeStart")
                parameter("select", "*")
            }.body()
            val match = existing.firstOrNull() ?: error("Insert trigger failed: $errBody")
            // PATCH only migraine_id (preserves source, active, etc.)
            val patchPayload = buildJsonObject {
                put("migraine_id", migraineId)
                notes?.let { put("notes", it) }
            }
            val patchResp = client.patch("$supabaseUrl/rest/v1/triggers") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                parameter("id", "eq.${match.id}")
                header("Prefer", "return=representation")
                header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
                contentType(ContentType.Application.Json)
                setBody(patchPayload)
            }
            if (!patchResp.status.isSuccess()) error("Update trigger failed: ${patchResp.bodyAsText()}")
            return patchResp.body()
        }
        error("Insert trigger failed: $errBody")
    }

    /** Lightweight row for recent trigger queries. */
    @Serializable data class RecentTriggerRow(
        val id: String,
        val type: String? = null,
        @SerialName("start_at") val startAt: String? = null
    )

    /** Fetch trigger types logged in the last [daysBack] days. */
    suspend fun getRecentTriggers(accessToken: String, daysBack: Int = 3, referenceDate: String? = null): List<RecentTriggerRow> {
        val refDate = referenceDate?.let {
            try { java.time.LocalDate.parse(it.substring(0, 10)) } catch (_: Exception) { null }
        } ?: return emptyList() // No reference date → no suggestions
        val cutoffStart = refDate.minusDays(daysBack.toLong()).toString() + "T00:00:00Z"
        val cutoffEnd = refDate.plusDays(1).toString() + "T00:00:00Z"
        val response = client.get("$supabaseUrl/rest/v1/triggers") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "id,type,start_at")
            parameter("and", "(start_at.gte.$cutoffStart,start_at.lte.$cutoffEnd)")
            parameter("order", "start_at.desc")
        }
        if (!response.status.isSuccess()) return emptyList()
        return response.body()
    }

    suspend fun getAllTriggers(accessToken: String, window: JournalWindow? = null): List<TriggerRow> {
        val response: HttpResponse = client.get("$supabaseUrl/rest/v1/triggers") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "*")
            parameter("order", "start_at.desc")
            journalWindow(window)
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
        migraineId: String? = null,
        clearMigraineId: Boolean = false,
        category: String? = null,
        moveCategory: Boolean = false
    ): TriggerRow {
        val payload = buildJsonObject {
            type?.let {
                put("type", it)
                // Same contract as updateRelief: a retype moves the row's
                // category to the new pool item's, NULL included when the new
                // label is in no pool. Callers not renaming from the pool
                // leave moveCategory false and the column stays untouched.
                if (moveCategory) {
                    if (category != null) put("category", category)
                    else put("category", kotlinx.serialization.json.JsonNull)
                }
            }
            startAt?.let { put("start_at", it) }
            notes?.let { put("notes", it) }
            if (clearMigraineId) put("migraine_id", kotlinx.serialization.json.JsonNull)
            else migraineId?.let { put("migraine_id", it) }
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
    /**
     * PATCH a trigger row. menstruation_predicted rows cannot be DELETEd
     * (server-side guard prevent_predicted_trigger_deletion), so retirement
     * is active=false — the same convention the backend uses.
     */
    suspend fun patchTrigger(accessToken: String, id: String, fields: kotlinx.serialization.json.JsonObject) {
        val response = client.patch("$supabaseUrl/rest/v1/triggers") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
            contentType(ContentType.Application.Json); setBody(fields)
        }
        if (!response.status.isSuccess()) error("Patch trigger failed: ${response.bodyAsText()}")
    }

    suspend fun deleteTrigger(accessToken: String, id: String) {
        val response = client.delete("$supabaseUrl/rest/v1/triggers") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
        }
        if (!response.status.isSuccess()) error("Delete trigger failed: ${response.bodyAsText()}")
    }

    //  MEDICINES
    @Serializable
    data class MedicineRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        val name: String? = null,
        val amount: String? = null,
        @SerialName("dose_value") val doseValue: Double? = null,
        @SerialName("dose_unit") val doseUnit: String? = null,
        @SerialName("start_at") val startAt: String,
        val notes: String? = null,
        val category: String? = null,
        @SerialName("relief_scale") val reliefScale: String? = "NONE",
        @SerialName("migraine_id") val migraineId: String? = null,
        val source: String? = "manual",
        @SerialName("side_effect_scale") val sideEffectScale: String? = "NONE",
        @SerialName("side_effect_notes") val sideEffectNotes: String? = null
    )
    @Serializable
    data class MedicineInsert(
        val name: String? = null,
        val amount: String? = null,
        @SerialName("dose_value") val doseValue: Double? = null,
        @SerialName("dose_unit") val doseUnit: String? = null,
        @SerialName("start_at") val startAt: String,
        val notes: String? = null,
        val category: String? = null,
        @SerialName("relief_scale") val reliefScale: String? = "NONE",
        @SerialName("migraine_id") val migraineId: String? = null,
        // Stamped like every other hand-logged row. The Journal's Manual/Auto
        // filter is `source == "manual"`, so a NULL here files a row the user
        // typed under "Auto".
        val source: String = "manual",
        @SerialName("side_effect_scale") val sideEffectScale: String? = "NONE",
        @SerialName("side_effect_notes") val sideEffectNotes: String? = null
    )
    suspend fun insertMedicine(
        accessToken: String,
        migraineId: String?,
        name: String?,
        amount: String?,
        startAt: String?,
        notes: String?,
        category: String? = null,
        reliefScale: String? = "NONE",
        sideEffectScale: String? = "NONE",
        sideEffectNotes: String? = null,
        doseValue: Double? = null,
        doseUnit: String? = null
    ): MedicineRow {
        val safeStart = startAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString()
        // Dual-write (one-unit contract): a structured dose mirrors the legacy
        // amount text; free text (AI drafts, legacy callers) is parsed into a
        // structured dose with the contract's fallback rules.
        var dv = doseValue
        var du = doseUnit?.takeIf { dv != null }
        var amt = amount
        if (dv != null) {
            du = du ?: DoseUnits.MG
            amt = amt ?: DoseUnits.legacyAmount(dv, du)
        } else if (!amt.isNullOrBlank()) {
            DoseUnits.parseLegacy(amt)?.let { (v, u) -> dv = v; du = u }
        }
        val payload = MedicineInsert(
            name = name, amount = amt, doseValue = dv, doseUnit = du,
            startAt = safeStart, notes = notes, category = category,
            reliefScale = reliefScale, migraineId = migraineId,
            sideEffectScale = sideEffectScale, sideEffectNotes = sideEffectNotes
        )
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
    suspend fun getAllMedicines(accessToken: String, window: JournalWindow? = null): List<MedicineRow> {
        val response: HttpResponse = client.get("$supabaseUrl/rest/v1/medicines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "*")
            parameter("order", "start_at.desc")
            journalWindow(window)
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
        migraineId: String? = null,
        clearMigraineId: Boolean = false,
        reliefScale: String? = null,
        sideEffectScale: String? = null,
        sideEffectNotes: String? = null,
        doseValue: Double? = null,
        doseUnit: String? = null,
        category: String? = null,
        moveCategory: Boolean = false
    ): MedicineRow {
        // Dual-write (one-unit contract) — see insertMedicine.
        var dv = doseValue
        var du = doseUnit?.takeIf { dv != null }
        var amt = amount
        if (dv != null) {
            du = du ?: DoseUnits.MG
            amt = amt ?: DoseUnits.legacyAmount(dv, du)
        } else if (!amt.isNullOrBlank()) {
            DoseUnits.parseLegacy(amt)?.let { (v, u) -> dv = v; du = u }
        }
        val payload = buildJsonObject {
            name?.let {
                put("name", it)
                // A rename moves the row's category to the new pool item's,
                // NULL included when the new name is in no pool. Callers not
                // renaming from the pool leave moveCategory false.
                if (moveCategory) {
                    if (category != null) put("category", category)
                    else put("category", kotlinx.serialization.json.JsonNull)
                }
            }
            if (amt != null) put("amount", amt)
            dv?.let { put("dose_value", it) }
            du?.let { put("dose_unit", it) }
            startAt?.let { put("start_at", it) }
            notes?.let { put("notes", it) }
            if (clearMigraineId) put("migraine_id", kotlinx.serialization.json.JsonNull)
            else migraineId?.let { put("migraine_id", it) }
            reliefScale?.let { put("relief_scale", it) }
            sideEffectScale?.let { put("side_effect_scale", it) }
            sideEffectNotes?.let { put("side_effect_notes", it) }
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

    //  TREATMENT REGIMENS
    @Serializable
    data class TreatmentRegimenRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        val kind: String,
        val name: String,
        val amount: String? = null,
        @SerialName("dose_value") val doseValue: Double? = null,
        @SerialName("dose_unit") val doseUnit: String? = null,
        val frequency: String? = null,
        @SerialName("start_date") val startDate: String,
        @SerialName("stop_date") val stopDate: String? = null,
        val notes: String? = null,
        @SerialName("group_id") val groupId: String? = null
    )
    @Serializable
    data class TreatmentRegimenInsert(
        // treatment_regimens.user_id is NOT NULL with no default and no
        // set-from-JWT trigger, so it must be sent explicitly or the RLS
        // WITH CHECK (auth.uid() = user_id) rejects the row.
        @SerialName("user_id") val userId: String,
        val kind: String,
        val name: String,
        val amount: String? = null,
        @SerialName("dose_value") val doseValue: Double? = null,
        @SerialName("dose_unit") val doseUnit: String? = null,
        val frequency: String? = null,
        @SerialName("start_date") val startDate: String,
        @SerialName("stop_date") val stopDate: String? = null,
        val notes: String? = null,
        @SerialName("group_id") val groupId: String? = null
    )
    suspend fun insertTreatmentRegimen(
        accessToken: String,
        userId: String,
        kind: String,
        name: String,
        amount: String?,
        frequency: String?,
        startDate: String,
        stopDate: String? = null,
        notes: String? = null,
        groupId: String? = null,
        doseValue: Double? = null,
        doseUnit: String? = null
    ): TreatmentRegimenRow {
        // Dual-write (one-unit contract): structured dose mirrors the legacy
        // amount string. Free-text amounts (device/lifestyle) stay text-only.
        val du = doseUnit?.takeIf { doseValue != null } ?: if (doseValue != null) DoseUnits.MG else null
        val amt = amount ?: doseValue?.let { DoseUnits.legacyAmount(it, du ?: DoseUnits.MG) }
        val payload = TreatmentRegimenInsert(userId, kind, name, amt, doseValue, du, frequency, startDate, stopDate, notes, groupId)
        val response: HttpResponse = client.post("$supabaseUrl/rest/v1/treatment_regimens") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "return=representation")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (!response.status.isSuccess()) error("Insert treatment regimen failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun getTreatmentRegimens(accessToken: String): List<TreatmentRegimenRow> {
        val response: HttpResponse = client.get("$supabaseUrl/rest/v1/treatment_regimens") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "*")
            parameter("order", "start_date.desc")
        }
        if (!response.status.isSuccess()) error("Fetch treatment regimens failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun updateTreatmentRegimen(
        accessToken: String,
        id: String,
        amount: String? = null,
        frequency: String? = null,
        stopDate: String? = null,
        clearStopDate: Boolean = false,
        notes: String? = null,
        groupId: String? = null,
        clearGroupId: Boolean = false
    ): TreatmentRegimenRow {
        val payload = buildJsonObject {
            if (amount != null) put("amount", amount)
            if (frequency != null) put("frequency", frequency)
            if (clearStopDate) put("stop_date", kotlinx.serialization.json.JsonNull)
            else stopDate?.let { put("stop_date", it) }
            notes?.let { put("notes", it) }
            if (clearGroupId) put("group_id", kotlinx.serialization.json.JsonNull)
            else groupId?.let { put("group_id", it) }
        }
        val response = client.patch("$supabaseUrl/rest/v1/treatment_regimens") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
            header("Prefer", "return=representation")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Update treatment regimen failed: ${response.bodyAsText()}")
        return response.body()
    }
    /** Sets only `group_id` (or null to unlink) without touching any other field. */
    suspend fun setTreatmentRegimenGroupId(accessToken: String, id: String, groupId: String?) {
        val payload = buildJsonObject {
            if (groupId == null) put("group_id", kotlinx.serialization.json.JsonNull)
            else put("group_id", groupId)
        }
        val response = client.patch("$supabaseUrl/rest/v1/treatment_regimens") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Set group_id failed: ${response.bodyAsText()}")
    }

    suspend fun deleteTreatmentRegimen(accessToken: String, id: String) {
        val response = client.delete("$supabaseUrl/rest/v1/treatment_regimens") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
        }
        if (!response.status.isSuccess()) error("Delete treatment regimen failed: ${response.bodyAsText()}")
    }

    //  TREATMENT SIDE EFFECT LOGS
    @Serializable
    data class TreatmentSideEffectLogRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("log_date") val logDate: String,
        @SerialName("selected_symptoms") val selectedSymptoms: List<String> = emptyList(),
        val notes: String? = null,
        val source: String? = "check_in"
    )
    @Serializable
    data class TreatmentSideEffectLogInsert(
        @SerialName("log_date") val logDate: String,
        @SerialName("selected_symptoms") val selectedSymptoms: List<String>,
        val notes: String? = null,
        val source: String = "check_in",
        @SerialName("regimen_id") val regimenId: String? = null,
    )
    suspend fun insertTreatmentSideEffectLog(
        accessToken: String,
        logDate: String,
        selectedSymptoms: List<String>,
        notes: String?,
        regimenId: String? = null,
        source: String = "check_in"
    ): TreatmentSideEffectLogRow {
        val payload = TreatmentSideEffectLogInsert(logDate, selectedSymptoms, notes, source, regimenId)
        val response: HttpResponse = client.post("$supabaseUrl/rest/v1/treatment_side_effect_logs") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Insert side-effect log failed: ${response.bodyAsText()}")
        return response.body()
    }

    /** Every migraine without an ended_at, newest first. The Home card pages
     *  through these, so it needs them all rather than just the latest. */
    suspend fun getOpenMigraines(accessToken: String): List<MigraineRow> {
        val response: HttpResponse = client.get("$supabaseUrl/rest/v1/migraines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "*")
            parameter("ended_at", "is.null")
            parameter("order", "start_at.desc")
        }
        if (!response.status.isSuccess()) return emptyList()
        return response.body()
    }

    /** Most-recent migraine without an ended_at, or null if none open. */
    suspend fun getOpenMigraine(accessToken: String): MigraineRow? {
        val response: HttpResponse = client.get("$supabaseUrl/rest/v1/migraines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "*")
            parameter("ended_at", "is.null")
            parameter("order", "start_at.desc")
            parameter("limit", "1")
        }
        if (!response.status.isSuccess()) return null
        val rows: List<MigraineRow> = response.body()
        return rows.firstOrNull()
    }

    /**
     * Did any migraine touch this local day? An attack that started AND ended
     * today leaves `getOpenMigraine` null, so callers that need "was there a
     * migraine today" must ask this, never the open-migraine helper.
     *
     * Matches an attack that starts during the day, or one that started earlier
     * and is either still open or ended during/after the day.
     */
    @Serializable private data class MigraineIdRow(val id: String)

    suspend fun hasMigraineOnDay(accessToken: String, day: java.time.LocalDate): Boolean {
        val zone = java.time.ZoneId.systemDefault()
        val dayStart = day.atStartOfDay(zone).toInstant().toString()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toString()
        val response: HttpResponse = client.get("$supabaseUrl/rest/v1/migraines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id")
            parameter("start_at", "lt.$dayEnd")
            parameter("or", "(ended_at.is.null,ended_at.gte.$dayStart)")
            parameter("limit", "1")
        }
        if (!response.status.isSuccess()) return false
        val rows: List<MigraineIdRow> = response.body()
        return rows.isNotEmpty()
    }

    @Serializable
    data class SymptomLogRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("migraine_id") val migraineId: String? = null,
        val type: String? = null,
        /** MILD/MODERATE/SEVERE, null when unrated or a pain_character symptom. */
        val severity: String? = null,
        /** When the symptom started. null = at attack start. Distinct from
         *  createdAt, which is when the sync trigger created the row. */
        @SerialName("start_at") val startAt: String? = null,
        @SerialName("created_at") val createdAt: String? = null,
    )

    /**
     * `symptoms` rows are created server-side by the sync trigger off
     * `migraines.type`, so severity is written as an update after the migraine
     * save, matched on (migraine_id, type). Never insert here.
     */
    suspend fun setSymptomDetail(
        accessToken: String, migraineId: String, type: String,
        severity: String?, startAt: String?
    ) {
        val payload = buildJsonObject {
            if (severity == null) put("severity", kotlinx.serialization.json.JsonNull)
            else put("severity", severity)
            // Written as SQL NULL when absent, never stamped with now() — an
            // untimed symptom must not look like a real time.
            if (startAt == null) put("start_at", kotlinx.serialization.json.JsonNull)
            else put("start_at", startAt)
        }
        val r = client.patch("$supabaseUrl/rest/v1/symptoms") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("migraine_id", "eq.$migraineId")
            parameter("type", "eq.$type")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!r.status.isSuccess()) error("Set symptom detail failed: ${r.bodyAsText()}")
    }

    suspend fun getSymptomRows(accessToken: String, migraineId: String): List<SymptomLogRow> {
        val r = client.get("$supabaseUrl/rest/v1/symptoms") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("migraine_id", "eq.$migraineId")
        }
        return if (r.status.isSuccess()) r.body() else emptyList()
    }

    /** Fetch per-migraine symptom log rows (postdromes + wizard-side inserts). Mirrors iOS getSymptoms. */
    suspend fun getSymptoms(accessToken: String, days: Int = 365): List<SymptomLogRow> {
        val response: HttpResponse = client.get("$supabaseUrl/rest/v1/symptoms") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,user_id,migraine_id,type,severity,start_at,created_at")
            parameter("created_at", "gte.${Instant.now().minus(Duration.ofDays(days.toLong()))}")
            parameter("order", "created_at.desc")
        }
        if (!response.status.isSuccess()) return emptyList()
        return response.body()
    }

    /** Insert a postdrome symptom row linked to a specific migraine. */
    suspend fun insertMigraineSymptom(
        accessToken: String, migraineId: String, type: String,
        // 'postdrome' marks check-in after-symptoms; the DB's sync trigger only
        // manages phase='active' rows, so postdromes survive later type edits.
        phase: String = "active",
    ) {
        val payload = buildJsonObject {
            put("migraine_id", migraineId)
            put("type", type)
            put("phase", phase)
        }
        val response: HttpResponse = client.post("$supabaseUrl/rest/v1/symptoms") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Insert symptom failed: ${response.bodyAsText()}")
    }

    suspend fun getTreatmentSideEffectLogs(
        accessToken: String, fromDate: String, toDate: String
    ): List<TreatmentSideEffectLogRow> {
        val response: HttpResponse = client.get("$supabaseUrl/rest/v1/treatment_side_effect_logs") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "*")
            parameter("log_date", "gte.$fromDate")
            parameter("log_date", "lte.$toDate")
            parameter("order", "log_date.asc")
        }
        if (!response.status.isSuccess()) error("Fetch side-effect logs failed: ${response.bodyAsText()}")
        return response.body()
    }

    //  TREATMENT EFFICACY (RPC calls)
    @Serializable
    data class TreatmentEfficacyRow(
        @SerialName("baseline_mmd") val baselineMmd: Double? = null,
        @SerialName("rolling_mmd") val rollingMmd: Double? = null,
        @SerialName("trailing_4w_mmd") val trailing4wMmd: Double? = null,
        @SerialName("baseline_severity") val baselineSeverity: Double? = null,
        @SerialName("rolling_severity") val rollingSeverity: Double? = null,
        @SerialName("baseline_duration_h") val baselineDurationH: Double? = null,
        @SerialName("rolling_duration_h") val rollingDurationH: Double? = null,
        @SerialName("pct_change_mmd") val pctChangeMmd: Double? = null,
        @SerialName("weeks_active") val weeksActive: Int = 0,
        @SerialName("weeks_post_ramp") val weeksPostRamp: Int? = null,
        @SerialName("ramp_weeks") val rampWeeks: Int? = null,
        @SerialName("n_attacks_rolling") val nAttacksRolling: Int = 0,
        val band: String = "not_enough_data",
        @SerialName("ramp_complete") val rampComplete: Boolean = false
    )
    @Serializable
    data class TreatmentConfounderRow(
        val metric: String,
        @SerialName("baseline_value") val baselineValue: Double? = null,
        @SerialName("rolling_value") val rollingValue: Double? = null,
        @SerialName("pct_change") val pctChange: Double? = null,
        val direction: String
    )
    @Serializable
    data class TreatmentTriggerShiftRow(
        @SerialName("trigger_type") val triggerType: String,
        @SerialName("baseline_rank") val baselineRank: Int? = null,
        @SerialName("rolling_rank") val rollingRank: Int? = null,
        @SerialName("rank_change") val rankChange: Int? = null
    )
    @Serializable
    data class TreatmentLeaderboardRow(
        @SerialName("regimen_id") val regimenId: String,
        val name: String,
        val kind: String,
        val amount: String? = null,
        val frequency: String? = null,
        @SerialName("start_date") val startDate: String,
        @SerialName("stop_date") val stopDate: String? = null,
        @SerialName("group_id") val groupId: String? = null,
        @SerialName("pct_change_mmd") val pctChangeMmd: Double? = null,
        val band: String
    )

    @Serializable
    data class TreatmentGroupEfficacyRow(
        @SerialName("group_id") val groupId: String,
        @SerialName("member_count") val memberCount: Int,
        @SerialName("member_names") val memberNames: List<String> = emptyList(),
        @SerialName("earliest_start") val earliestStart: String,
        @SerialName("latest_stop") val latestStop: String? = null,
        @SerialName("any_active") val anyActive: Boolean,
        val kind: String,
        @SerialName("baseline_mmd") val baselineMmd: Double? = null,
        @SerialName("rolling_mmd") val rollingMmd: Double? = null,
        @SerialName("trailing_4w_mmd") val trailing4wMmd: Double? = null,
        @SerialName("pct_change_mmd") val pctChangeMmd: Double? = null,
        @SerialName("weeks_active") val weeksActive: Int = 0,
        @SerialName("weeks_post_ramp") val weeksPostRamp: Int? = null,
        @SerialName("ramp_weeks") val rampWeeks: Int? = null,
        @SerialName("n_attacks_rolling") val nAttacksRolling: Int = 0,
        val band: String,
        @SerialName("ramp_complete") val rampComplete: Boolean = false
    )

    @Serializable
    private data class RegimenParam(val p_regimen: String)

    @Serializable
    private data class GroupParam(val p_group: String)

    suspend fun getTreatmentGroupEfficacy(accessToken: String, groupId: String): TreatmentGroupEfficacyRow? {
        val response: HttpResponse = client.post("$supabaseUrl/rest/v1/rpc/compute_group_efficacy") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            contentType(ContentType.Application.Json); setBody(GroupParam(groupId))
        }
        if (!response.status.isSuccess()) return null
        val rows: List<TreatmentGroupEfficacyRow> = response.body()
        return rows.firstOrNull()
    }

    suspend fun getTreatmentEfficacy(accessToken: String, regimenId: String): TreatmentEfficacyRow? {
        val response: HttpResponse = client.post("$supabaseUrl/rest/v1/rpc/compute_treatment_efficacy") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            contentType(ContentType.Application.Json); setBody(RegimenParam(regimenId))
        }
        if (!response.status.isSuccess()) return null
        val rows: List<TreatmentEfficacyRow> = response.body()
        return rows.firstOrNull()
    }

    suspend fun getTreatmentConfounders(accessToken: String, regimenId: String): List<TreatmentConfounderRow> {
        val response: HttpResponse = client.post("$supabaseUrl/rest/v1/rpc/compute_treatment_confounders") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            contentType(ContentType.Application.Json); setBody(RegimenParam(regimenId))
        }
        if (!response.status.isSuccess()) return emptyList()
        return response.body()
    }

    suspend fun getTreatmentTriggerShift(accessToken: String, regimenId: String): List<TreatmentTriggerShiftRow> {
        val response: HttpResponse = client.post("$supabaseUrl/rest/v1/rpc/compute_treatment_trigger_shift") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            contentType(ContentType.Application.Json); setBody(RegimenParam(regimenId))
        }
        if (!response.status.isSuccess()) return emptyList()
        return response.body()
    }

    suspend fun getTreatmentLeaderboard(accessToken: String): List<TreatmentLeaderboardRow> {
        val response: HttpResponse = client.post("$supabaseUrl/rest/v1/rpc/compute_treatment_leaderboard") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            contentType(ContentType.Application.Json); setBody("{}")
        }
        if (!response.status.isSuccess()) return emptyList()
        return response.body()
    }

    @Serializable
    data class TreatmentMmdSeriesPoint(
        @SerialName("week_start") val weekStart: String,
        @SerialName("weeks_from_start") val weeksFromStart: Int,
        val attacks: Int,
        val mmd: Int
    )

    suspend fun getTreatmentMmdSeries(accessToken: String, regimenId: String): List<TreatmentMmdSeriesPoint> {
        val response: HttpResponse = client.post("$supabaseUrl/rest/v1/rpc/compute_treatment_mmd_series") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            contentType(ContentType.Application.Json); setBody(RegimenParam(regimenId))
        }
        if (!response.status.isSuccess()) return emptyList()
        return response.body()
    }

    //  APP-LAUNCH PING
    @Serializable
    private data class RecordAppLaunchParam(val p_platform: String)

    /**
     * Increments user_activity_daily.launch_count for today and adds the
     * platform to the merged platforms array. Idempotent within a day.
     * Best-effort: swallows failures so a network blip never blocks startup.
     */
    suspend fun recordAppLaunch(accessToken: String, platform: String = "android") {
        try {
            client.post("$supabaseUrl/rest/v1/rpc/record_app_launch") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                contentType(ContentType.Application.Json)
                setBody(RecordAppLaunchParam(platform))
            }
        } catch (_: Exception) {}
    }

    //  TREATMENT NARRATIVE EDGE FUNCTION
    @Serializable
    data class TreatmentNarrativeRequest(val regimen_id: String, val force: Boolean = false)
    @Serializable
    data class TreatmentNarrativeResponse(val narrative: String? = null, val cached: Boolean? = null)

    suspend fun getTreatmentNarrative(accessToken: String, regimenId: String, force: Boolean = false): TreatmentNarrativeResponse? {
        val response: HttpResponse = client.post("$supabaseUrl/functions/v1/generate-treatment-narrative") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            contentType(ContentType.Application.Json)
            setBody(TreatmentNarrativeRequest(regimenId, force))
        }
        if (!response.status.isSuccess()) return null
        return response.body()
    }

    //  RELIEFS
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
        @SerialName("migraine_id") val migraineId: String? = null,
        val source: String? = "manual",
        @SerialName("side_effect_scale") val sideEffectScale: String? = "NONE",
        @SerialName("side_effect_notes") val sideEffectNotes: String? = null
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
        // Stamped like every other hand-logged row. The Journal's Manual/Auto
        // filter is `source == "manual"`, so a NULL here files a row the user
        // typed under "Auto".
        val source: String = "manual",
        @SerialName("side_effect_scale") val sideEffectScale: String? = "NONE",
        @SerialName("side_effect_notes") val sideEffectNotes: String? = null
    )
    /**
     * Writes one relief log.
     *
     * [category] is the POOL ITEM's own category (user_reliefs.category), not a
     * per-log field the user picks. It is the authoritative answer to "is this
     * a device?" for every later reader — the 2h outcome follow-up, device
     * stats in Insights, the journal edit sheet. ReliefInsert has always
     * declared the column, but nothing ever filled it, so every relief row this
     * app wrote had category NULL and the follow-up could only guess from the
     * item's NAME against a fixed device list. A device the user renamed, or
     * one not on that list, never got its "did it help?" prompt.
     *
     * Callers holding the pool row pass it straight through. Callers that only
     * ever have a label (drafts rebuilt from an AI parse, the evening
     * check-in, calendar auto-inserts) leave it null and it is resolved from
     * the user's own pool by label. Resolution is best effort: it never fails
     * the insert, and a label that belongs to no pool item simply stays NULL,
     * which leaves name matching as the fallback exactly as before.
     */
    suspend fun insertRelief(
        accessToken: String,
        migraineId: String?,
        type: String?,
        startAt: String?,
        notes: String?,
        endAt: String? = null,
        reliefScale: String? = "NONE",
        sideEffectScale: String? = "NONE",
        sideEffectNotes: String? = null,
        category: String? = null
    ): ReliefRow {
        val safeStart = startAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString()
        // end_at is the single source of truth for duration; a relief with no
        // end simply has end_at NULL (never defaulted to start_at).
        val safeEnd = endAt?.takeIf { it.isNotBlank() }
        val safeCategory = category?.takeIf { it.isNotBlank() }
            ?: ReliefPoolCategories.categoryFor(this, accessToken, type)
        val payload = ReliefInsert(type = type, startAt = safeStart, notes = notes, migraineId = migraineId, category = safeCategory, endAt = safeEnd, reliefScale = reliefScale, sideEffectScale = sideEffectScale, sideEffectNotes = sideEffectNotes)
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
    suspend fun getAllReliefs(accessToken: String, window: JournalWindow? = null): List<ReliefRow> {
        val response: HttpResponse = client.get("$supabaseUrl/rest/v1/reliefs") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "*")
            parameter("order", "start_at.desc")
            journalWindow(window)
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
        migraineId: String? = null,
        endAt: String? = null,
        clearMigraineId: Boolean = false,
        clearEndAt: Boolean = false,
        reliefScale: String? = null,
        sideEffectScale: String? = null,
        sideEffectNotes: String? = null
    ): ReliefRow {
        // Changing the type changes which pool item the row is, so its category
        // has to move with it — including down to NULL when the new label is in
        // no pool. Leaving the old one behind would keep calling a relief a
        // device after the user changed it to something else.
        val retypedCategory = type?.let { ReliefPoolCategories.categoryFor(this, accessToken, it) }
        val payload = buildJsonObject {
            type?.let {
                put("type", it)
                if (retypedCategory != null) put("category", retypedCategory)
                else put("category", kotlinx.serialization.json.JsonNull)
            }
            startAt?.let { put("start_at", it) }
            notes?.let { put("notes", it) }
            if (clearMigraineId) put("migraine_id", kotlinx.serialization.json.JsonNull)
            else migraineId?.let { put("migraine_id", it) }
            // A null endAt is an absent key (column untouched), so wiping an
            // end time back to "no known duration" is asked for explicitly.
            if (clearEndAt) put("end_at", kotlinx.serialization.json.JsonNull)
            else endAt?.let { put("end_at", it) }
            reliefScale?.let { put("relief_scale", it) }
            sideEffectScale?.let { put("side_effect_scale", it) }
            sideEffectNotes?.let { put("side_effect_notes", it) }
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

    //  TRIGGER POOL / PREFS 
    @Serializable data class UserTriggerRow(
        val id: String,
        val label: String,
        val category: String? = null,
        @SerialName("icon_key") val iconKey: String? = null,
        @SerialName("prediction_value") val predictionValue: String? = "NONE",
        val direction: String? = null,
        @SerialName("default_threshold") val defaultThreshold: Double? = null,
        val unit: String? = null,
        @SerialName("enabled_by_default") val enabledByDefault: Boolean = false,
        @SerialName("metric_table") val metricTable: String? = null,
        @SerialName("metric_column") val metricColumn: String? = null,
        @SerialName("display_group") val displayGroup: String? = null,
        @SerialName("alert_enabled") val alertEnabled: Boolean? = null
    )
    @Serializable
    data class TriggerPrefRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("trigger_id") val triggerId: String,
        val position: Int,
        val status: String,
        @SerialName("user_triggers") val trigger: UserTriggerRow? = null
    )
    @Serializable private data class UserTriggerInsert(val label: String, val category: String? = null, @SerialName("prediction_value") val predictionValue: String? = "NONE")

    suspend fun getAllTriggerPool(accessToken: String): List<UserTriggerRow> {
        val response = client.get("$supabaseUrl/rest/v1/user_triggers") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,label,category,icon_key,prediction_value,direction,default_threshold,unit,enabled_by_default,metric_table,metric_column,display_group,alert_enabled"); parameter("order", "metric_table.asc.nullslast,metric_column.asc.nullslast,direction.asc.nullslast,label.asc")
        }
        if (!response.status.isSuccess()) error("Fetch user_triggers failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun upsertTriggerToPool(accessToken: String, label: String, category: String? = null, predictionValue: String? = "NONE"): UserTriggerRow {
        val response = client.post("$supabaseUrl/rest/v1/user_triggers") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            // DB unique constraint is on (user_id, label) — label-only fails with 42P10.
            parameter("on_conflict", "user_id,label")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(UserTriggerInsert(label, category, predictionValue))
        }
        if (!response.status.isSuccess()) error("Upsert user_triggers failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteTriggerFromPool(accessToken: String, triggerId: String) {
        client.delete("$supabaseUrl/rest/v1/trigger_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("trigger_id", "eq.$triggerId")
        }
        val response = client.delete("$supabaseUrl/rest/v1/user_triggers") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$triggerId")
        }
        if (!response.status.isSuccess()) error("Delete user_triggers failed: ${response.bodyAsText()}")
    }
    suspend fun updateTriggerPoolItem(
        accessToken: String,
        triggerId: String,
        predictionValue: String? = null,
        category: String? = null,
        defaultThreshold: Double? = null,
        alertEnabled: Boolean? = null
    ) {
        val payload = buildJsonObject {
            predictionValue?.let { put("prediction_value", it) }
            category?.let { put("category", it) }
            defaultThreshold?.let { put("default_threshold", it) }
            alertEnabled?.let { put("alert_enabled", it) }
        }
        val response = client.patch("$supabaseUrl/rest/v1/user_triggers") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("id", "eq.$triggerId")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (!response.status.isSuccess()) error("Update user_triggers failed: ${response.bodyAsText()}")
    }
    @Serializable private data class TriggerPrefInsert(@SerialName("trigger_id") val triggerId: String, val position: Int, val status: String)
    suspend fun getTriggerPrefs(accessToken: String): List<TriggerPrefRow> {
        val response = client.get("$supabaseUrl/rest/v1/trigger_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,user_id,trigger_id,position,status,user_triggers(id,label,icon_key)")
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

    //  MEDICINE POOL / PREFS
    @Serializable data class UserMedicineRow(
        val id: String,
        val label: String,
        val category: String? = null,
        @SerialName("dose_unit") val doseUnit: String? = null
    )
    @Serializable
    data class MedicinePrefRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("medicine_id") val medicineId: String,
        val position: Int,
        val status: String,
        @SerialName("user_medicines") val medicine: UserMedicineRow? = null
    )
    @Serializable private data class UserMedicineInsert(
        val label: String,
        val category: String? = null,
        @SerialName("dose_unit") val doseUnit: String? = null
    )

    suspend fun getAllMedicinePool(accessToken: String): List<UserMedicineRow> {
        val response = client.get("$supabaseUrl/rest/v1/user_medicines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,label,category,dose_unit"); parameter("order", "label.asc")
        }
        if (!response.status.isSuccess()) error("Fetch user_medicines failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun upsertMedicineToPool(accessToken: String, label: String, category: String? = null, doseUnit: String? = null): UserMedicineRow {
        val response = client.post("$supabaseUrl/rest/v1/user_medicines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "user_id,label")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(UserMedicineInsert(label, category, doseUnit))
        }
        if (!response.status.isSuccess()) error("Upsert user_medicines failed: ${response.bodyAsText()}")
        return response.body()
    }
    /** Sets the medicine's one unit ('mg' or 'amount' for custom items). Old
     *  logs keep their stamped unit — no conversion. */
    suspend fun setMedicineDoseUnit(accessToken: String, medicineId: String, doseUnit: String) {
        val payload = buildJsonObject { put("dose_unit", doseUnit) }
        val response = client.patch("$supabaseUrl/rest/v1/user_medicines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$medicineId")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Set medicine dose_unit failed: ${response.bodyAsText()}")
    }
    suspend fun deleteMedicineFromPool(accessToken: String, medicineId: String) {
        client.delete("$supabaseUrl/rest/v1/medicine_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("medicine_id", "eq.$medicineId")
        }
        val response = client.delete("$supabaseUrl/rest/v1/user_medicines") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$medicineId")
        }
        if (!response.status.isSuccess()) error("Delete user_medicines failed: ${response.bodyAsText()}")
    }
    suspend fun setMedicineCategory(accessToken: String, medicineId: String, category: String?) {
        val payload = categoryPatch(category)
        val response = client.patch("$supabaseUrl/rest/v1/user_medicines") {
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
            parameter("select", "id,user_id,medicine_id,position,status,user_medicines(id,label,category,dose_unit)")
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

    //  RELIEF POOL / PREFS 
    @Serializable data class UserReliefRow(val id: String, val label: String, val category: String? = null, @SerialName("icon_key") val iconKey: String? = null, @SerialName("is_automatable") val isAutomatable: Boolean = false, @SerialName("is_automated") val isAutomated: Boolean = false)
    @Serializable
    data class ReliefPrefRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("relief_id") val reliefId: String,
        val position: Int,
        val status: String,
        @SerialName("user_reliefs") val relief: UserReliefRow? = null
    )
    @Serializable private data class UserReliefInsert(
        val label: String,
        val category: String? = null,
        @SerialName("icon_key") val iconKey: String? = null,
        @SerialName("is_automatable") val isAutomatable: Boolean? = null,
        @SerialName("is_automated") val isAutomated: Boolean? = null
    )

    /**
     * One row of the global relief library.
     *
     * Same shape the new-user seeder reads: seed_pools_for_new_user() copies
     * label, category, icon_key, is_automatable, is_automated out of
     * relief_templates into user_reliefs. Reusing that column set is what makes
     * a library add indistinguishable from a seeded row — in particular the
     * Device rows keep category = 'Device', which is what
     * DeviceCatalog.isDeviceRelief matches on to arm the 2h follow-up.
     */
    @Serializable data class ReliefTemplateRow(
        val label: String,
        val category: String? = null,
        @SerialName("icon_key") val iconKey: String? = null,
        @SerialName("is_automatable") val isAutomatable: Boolean = false,
        @SerialName("is_automated") val isAutomated: Boolean = false
    )

    /** The whole relief library, template order. Filtering against the user's
     *  own pool happens client-side so a search can span both lists. */
    suspend fun getReliefTemplates(accessToken: String): List<ReliefTemplateRow> {
        val response = client.get("$supabaseUrl/rest/v1/relief_templates") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "label,category,icon_key,is_automatable,is_automated")
            parameter("order", "label.asc")
        }
        if (!response.status.isSuccess()) error("Fetch relief_templates failed: ${response.bodyAsText()}")
        return response.body()
    }

    suspend fun getAllReliefPool(accessToken: String): List<UserReliefRow> {
        val response = client.get("$supabaseUrl/rest/v1/user_reliefs") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,label,category,icon_key,is_automatable,is_automated"); parameter("order", "label.asc")
        }
        if (!response.status.isSuccess()) error("Fetch user_reliefs failed: ${response.bodyAsText()}")
        return response.body()
    }
    /**
     * Adds one relief to the user's pool. [iconKey] / [isAutomatable] /
     * [isAutomated] are carried by library adds, which pass the template row's
     * own metadata straight through; a custom add leaves them null so the
     * column defaults apply.
     */
    suspend fun upsertReliefToPool(
        accessToken: String,
        label: String,
        category: String? = null,
        iconKey: String? = null,
        isAutomatable: Boolean? = null,
        isAutomated: Boolean? = null
    ): UserReliefRow {
        val response = client.post("$supabaseUrl/rest/v1/user_reliefs") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "user_id,label")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json)
            setBody(UserReliefInsert(label, category, iconKey, isAutomatable, isAutomated))
        }
        if (!response.status.isSuccess()) error("Upsert user_reliefs failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteReliefFromPool(accessToken: String, reliefId: String) {
        client.delete("$supabaseUrl/rest/v1/relief_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("relief_id", "eq.$reliefId")
        }
        val response = client.delete("$supabaseUrl/rest/v1/user_reliefs") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$reliefId")
        }
        if (!response.status.isSuccess()) error("Delete user_reliefs failed: ${response.bodyAsText()}")
    }
    suspend fun setReliefCategory(accessToken: String, reliefId: String, category: String?) {
        val payload = categoryPatch(category)
        val response = client.patch("$supabaseUrl/rest/v1/user_reliefs") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$reliefId")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Set relief category failed: ${response.bodyAsText()}")
    }
    suspend fun setReliefAutomation(accessToken: String, reliefId: String, enabled: Boolean) {
        val payload = buildJsonObject { put("is_automated", enabled) }
        val response = client.patch("$supabaseUrl/rest/v1/user_reliefs") {
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
            parameter("select", "id,user_id,relief_id,position,status,user_reliefs(id,label,category,icon_key,is_automatable,is_automated)")
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

    //  MIGRAINE POOL / PREFS 
    @Serializable data class UserMigrainePoolRow(val id: String, val label: String)
    @Serializable
    data class MigrainePrefRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("migraine_id") val migraineId: String,
        val position: Int,
        val status: String,
        @SerialName("user_migraines_pool") val migraine: UserMigrainePoolRow? = null
    )
    @Serializable private data class UserMigrainePoolInsert(val label: String)

    suspend fun getAllMigrainePool(accessToken: String): List<UserMigrainePoolRow> {
        val response = client.get("$supabaseUrl/rest/v1/user_migraines_pool") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,label"); parameter("order", "label.asc")
        }
        if (!response.status.isSuccess()) error("Fetch user_migraines_pool failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun upsertMigraineToPool(accessToken: String, label: String): UserMigrainePoolRow {
        val response = client.post("$supabaseUrl/rest/v1/user_migraines_pool") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "user_id,label")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(UserMigrainePoolInsert(label))
        }
        if (!response.status.isSuccess()) error("Upsert user_migraines_pool failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteMigraineFromPool(accessToken: String, migraineId: String) {
        client.delete("$supabaseUrl/rest/v1/migraine_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("migraine_id", "eq.$migraineId")
        }
        val response = client.delete("$supabaseUrl/rest/v1/user_migraines_pool") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$migraineId")
        }
        if (!response.status.isSuccess()) error("Delete user_migraines_pool failed: ${response.bodyAsText()}")
    }
    @Serializable private data class MigrainePrefInsert(@SerialName("migraine_id") val migraineId: String, val position: Int, val status: String)
    suspend fun getMigrainePrefs(accessToken: String): List<MigrainePrefRow> {
        val response = client.get("$supabaseUrl/rest/v1/migraine_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,user_id,migraine_id,position,status,user_migraines_pool(id,label)")
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

    //  WEATHER DAILY 
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

    //  SYMPTOM POOL 
    @Serializable data class UserSymptomRow(
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
        @SerialName("user_symptoms") val symptom: UserSymptomRow? = null
    )
    @Serializable private data class UserSymptomInsert(
        val label: String,
        val category: String? = null,
        @SerialName("icon_key") val iconKey: String? = null
    )

    suspend fun getAllSymptomPool(accessToken: String): List<UserSymptomRow> {
        val response = client.get("$supabaseUrl/rest/v1/user_symptoms") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,label,category,icon_key"); parameter("order", "label.asc")
        }
        if (!response.status.isSuccess()) error("Fetch user_symptoms failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun upsertSymptomToPool(accessToken: String, label: String, category: String? = null, iconKey: String? = null): UserSymptomRow {
        val response = client.post("$supabaseUrl/rest/v1/user_symptoms") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "user_id,label")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(UserSymptomInsert(label, category, iconKey))
        }
        if (!response.status.isSuccess()) error("Upsert user_symptoms failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteSymptomFromPool(accessToken: String, symptomId: String) {
        client.delete("$supabaseUrl/rest/v1/symptom_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("symptom_id", "eq.$symptomId")
        }
        val response = client.delete("$supabaseUrl/rest/v1/user_symptoms") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$symptomId")
        }
        if (!response.status.isSuccess()) error("Delete user_symptoms failed: ${response.bodyAsText()}")
    }
    @Serializable private data class SymptomPrefInsert(@SerialName("symptom_id") val symptomId: String, val position: Int, val status: String)
    suspend fun getSymptomPrefs(accessToken: String): List<SymptomPrefRow> {
        val response = client.get("$supabaseUrl/rest/v1/symptom_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,user_id,symptom_id,position,status,user_symptoms(id,label,category,icon_key)")
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

    //  PRODROME POOL 
    @Serializable data class UserProdromeRow(
        val id: String,
        val label: String,
        val category: String? = null,
        @SerialName("icon_key") val iconKey: String? = null,
        @SerialName("prediction_value") val predictionValue: String? = "NONE",
        val direction: String? = null,
        @SerialName("default_threshold") val defaultThreshold: Double? = null,
        val unit: String? = null,
        @SerialName("enabled_by_default") val enabledByDefault: Boolean = false,
        @SerialName("metric_table") val metricTable: String? = null,
        @SerialName("metric_column") val metricColumn: String? = null,
        @SerialName("display_group") val displayGroup: String? = null,
        @SerialName("alert_enabled") val alertEnabled: Boolean? = null
    )
    @Serializable
    data class ProdromePrefRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("prodrome_id") val prodromeId: String,
        val position: Int,
        val status: String,
        @SerialName("user_prodromes") val prodrome: UserProdromeRow? = null
    )
    @Serializable private data class UserProdromeInsert(
        val label: String,
        val category: String? = null,
        @SerialName("prediction_value") val predictionValue: String? = "NONE"
    )

    suspend fun getAllProdromePool(accessToken: String): List<UserProdromeRow> {
        val response = client.get("$supabaseUrl/rest/v1/user_prodromes") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,label,category,icon_key,prediction_value,direction,default_threshold,unit,enabled_by_default,metric_table,metric_column,display_group,alert_enabled"); parameter("order", "metric_table.asc.nullslast,metric_column.asc.nullslast,direction.asc.nullslast,label.asc")
        }
        if (!response.status.isSuccess()) error("Fetch user_prodromes failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun upsertProdromeToPool(accessToken: String, label: String, category: String? = null, predictionValue: String? = "NONE"): UserProdromeRow {
        val response = client.post("$supabaseUrl/rest/v1/user_prodromes") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "user_id,label")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(UserProdromeInsert(label, category, predictionValue))
        }
        if (!response.status.isSuccess()) error("Upsert user_prodromes failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteProdromeFromPool(accessToken: String, prodromeId: String) {
        client.delete("$supabaseUrl/rest/v1/prodrome_user_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("prodrome_id", "eq.$prodromeId")
        }
        val response = client.delete("$supabaseUrl/rest/v1/user_prodromes") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$prodromeId")
        }
        if (!response.status.isSuccess()) error("Delete user_prodromes failed: ${response.bodyAsText()}")
    }
    suspend fun updateProdromePoolItem(
        accessToken: String,
        prodromeId: String,
        predictionValue: String? = null,
        category: String? = null,
        defaultThreshold: Double? = null,
        alertEnabled: Boolean? = null
    ) {
        val payload = buildJsonObject {
            predictionValue?.let { put("prediction_value", it) }
            category?.let { put("category", it) }
            defaultThreshold?.let { put("default_threshold", it) }
            alertEnabled?.let { put("alert_enabled", it) }
        }
        val response = client.patch("$supabaseUrl/rest/v1/user_prodromes") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("id", "eq.$prodromeId")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (!response.status.isSuccess()) error("Update user_prodromes failed: ${response.bodyAsText()}")
    }
    @Serializable private data class ProdromePrefInsert(@SerialName("prodrome_id") val prodromeId: String, val position: Int, val status: String)
    suspend fun getProdromePrefs(accessToken: String): List<ProdromePrefRow> {
        val response = client.get("$supabaseUrl/rest/v1/prodrome_user_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,user_id,prodrome_id,position,status,user_prodromes(id,label,category,icon_key,prediction_value)")
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

    //  PRODROME LOG 
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
        @SerialName("migraine_id") val migraineId: String? = null,
        val source: String? = "manual"
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

    /** Fetch distinct prodrome types logged in the last [daysBack] days. */
    suspend fun getRecentProdromes(accessToken: String, daysBack: Int = 3, referenceDate: String? = null): List<ProdromeLogRow> {
        val refDate = referenceDate?.let {
            try { java.time.LocalDate.parse(it.substring(0, 10)) } catch (_: Exception) { null }
        } ?: java.time.LocalDate.now()
        val cutoffStart = refDate.minusDays(daysBack.toLong()).toString() + "T00:00:00Z"
        val cutoffEnd = refDate.plusDays(1).toString() + "T00:00:00Z"
        val response = client.get("$supabaseUrl/rest/v1/prodromes") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "id,type,start_at")
            parameter("start_at", "gte.$cutoffStart")
            parameter("start_at", "lte.$cutoffEnd")
            parameter("order", "start_at.desc")
        }
        if (!response.status.isSuccess()) return emptyList()
        return response.body()
    }

    /** Fetch all prodrome log entries. */
    suspend fun getAllProdromeLog(accessToken: String, window: JournalWindow? = null): List<ProdromeLogRow> {
        val response = client.get("$supabaseUrl/rest/v1/prodromes") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "id,type,start_at,notes,migraine_id,source")
            parameter("order", "start_at.desc")
            journalWindow(window)
        }
        if (!response.status.isSuccess()) return emptyList()
        return response.body()
    }

    /** One prodrome log by id — how an edited entry is refreshed in the journal
     *  feed without re-reading the whole window. */
    suspend fun getProdromeLogById(accessToken: String, id: String): ProdromeLogRow {
        val response = client.get("$supabaseUrl/rest/v1/prodromes") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id"); parameter("select", "*")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
        }
        if (!response.status.isSuccess()) error("Get prodrome log by id failed: ${response.bodyAsText()}")
        return response.body()
    }

    suspend fun deleteProdromeLog(accessToken: String, id: String) {
        client.delete("$supabaseUrl/rest/v1/prodromes") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
        }
    }

    suspend fun updateProdromeLog(accessToken: String, id: String, type: String?, startAt: String?, notes: String?, category: String? = null, moveCategory: Boolean = false) {
        val payload = buildJsonObject {
            type?.let {
                put("type", it)
                // Same contract as updateRelief: a retype moves the row's
                // category with it, NULL included when the new label is in
                // no pool; moveCategory false leaves the column untouched.
                if (moveCategory) {
                    if (category != null) put("category", category)
                    else put("category", kotlinx.serialization.json.JsonNull)
                }
            }
            startAt?.let { put("start_at", it) }
            notes?.let { put("notes", it) }
        }
        client.patch("$supabaseUrl/rest/v1/prodromes") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
            contentType(ContentType.Application.Json); setBody(payload)
        }
    }

    //  LOCATION POOL / PREFS 
    @Serializable data class UserLocationRow(val id: String, val label: String, val category: String? = null, @SerialName("icon_key") val iconKey: String? = null, @SerialName("is_automatable") val isAutomatable: Boolean = false, @SerialName("is_automated") val isAutomated: Boolean = false)
    @Serializable
    data class LocationPrefRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("location_id") val locationId: String,
        val position: Int,
        val status: String,
        @SerialName("user_locations") val location: UserLocationRow? = null
    )
    @Serializable private data class UserLocationInsert(val label: String, val category: String? = null)

    suspend fun getAllLocationPool(accessToken: String): List<UserLocationRow> {
        val response = client.get("$supabaseUrl/rest/v1/user_locations") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,label,category,icon_key,is_automatable,is_automated"); parameter("order", "label.asc")
        }
        if (!response.status.isSuccess()) error("Fetch user_locations failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun upsertLocationToPool(accessToken: String, label: String, category: String? = null): UserLocationRow {
        val response = client.post("$supabaseUrl/rest/v1/user_locations") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "user_id,label")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(UserLocationInsert(label, category))
        }
        if (!response.status.isSuccess()) error("Upsert user_locations failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteLocationFromPool(accessToken: String, locationId: String) {
        client.delete("$supabaseUrl/rest/v1/location_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("location_id", "eq.$locationId")
        }
        val response = client.delete("$supabaseUrl/rest/v1/user_locations") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$locationId")
        }
        if (!response.status.isSuccess()) error("Delete user_locations failed: ${response.bodyAsText()}")
    }
    suspend fun setLocationCategory(accessToken: String, locationId: String, category: String?) {
        val payload = categoryPatch(category)
        val response = client.patch("$supabaseUrl/rest/v1/user_locations") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$locationId")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Set location category failed: ${response.bodyAsText()}")
    }
    suspend fun setLocationAutomation(accessToken: String, locationId: String, enabled: Boolean) {
        val payload = buildJsonObject { put("is_automated", enabled) }
        val response = client.patch("$supabaseUrl/rest/v1/user_locations") {
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
            parameter("select", "id,user_id,location_id,position,status,user_locations(id,label,category,icon_key,is_automatable,is_automated)")
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

    //  Location log 
    @Serializable
    data class LocationLogRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        val type: String? = null,
        val category: String? = null,
        @SerialName("start_at") val startAt: String,
        val notes: String? = null,
        @SerialName("migraine_id") val migraineId: String? = null,
        val source: String? = "manual"
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

    /** Fetch all location log entries. */
    suspend fun getAllLocationLog(accessToken: String, window: JournalWindow? = null): List<LocationLogRow> {
        val response = client.get("$supabaseUrl/rest/v1/locations") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("order", "start_at.desc")
            journalWindow(window)
        }
        if (!response.status.isSuccess()) return emptyList()
        return response.body()
    }

    /** One location log by id — see [getProdromeLogById]. */
    suspend fun getLocationLogById(accessToken: String, id: String): LocationLogRow {
        val response = client.get("$supabaseUrl/rest/v1/locations") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id"); parameter("select", "*")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
        }
        if (!response.status.isSuccess()) error("Get location log by id failed: ${response.bodyAsText()}")
        return response.body()
    }

    suspend fun deleteLocationLog(accessToken: String, id: String) {
        client.delete("$supabaseUrl/rest/v1/locations") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
        }
    }

    suspend fun updateLocationLog(accessToken: String, id: String, type: String?, startAt: String?, notes: String?, category: String? = null, moveCategory: Boolean = false) {
        val payload = buildJsonObject {
            type?.let {
                put("type", it)
                // Same contract as updateRelief: a retype moves the row's
                // category with it, NULL included when the new label is in
                // no pool; moveCategory false leaves the column untouched.
                if (moveCategory) {
                    if (category != null) put("category", category)
                    else put("category", kotlinx.serialization.json.JsonNull)
                }
            }
            startAt?.let { put("start_at", it) }
            notes?.let { put("notes", it) }
        }
        client.patch("$supabaseUrl/rest/v1/locations") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
            contentType(ContentType.Application.Json); setBody(payload)
        }
    }

    //  ACTIVITY POOL / PREFS 
    @Serializable data class UserActivityRow(val id: String, val label: String, val category: String? = null, @SerialName("icon_key") val iconKey: String? = null, @SerialName("is_automatable") val isAutomatable: Boolean = false, @SerialName("is_automated") val isAutomated: Boolean = false)
    @Serializable
    data class ActivityPrefRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("activity_id") val activityId: String,
        val position: Int,
        val status: String,
        @SerialName("user_activities") val activity: UserActivityRow? = null
    )
    @Serializable private data class UserActivityInsert(val label: String, val category: String? = null)

    suspend fun getAllActivityPool(accessToken: String): List<UserActivityRow> {
        val response = client.get("$supabaseUrl/rest/v1/user_activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,label,category,icon_key,is_automatable,is_automated"); parameter("order", "label.asc")
        }
        if (!response.status.isSuccess()) error("Fetch user_activities failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun upsertActivityToPool(accessToken: String, label: String, category: String? = null): UserActivityRow {
        val response = client.post("$supabaseUrl/rest/v1/user_activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "user_id,label")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(UserActivityInsert(label, category))
        }
        if (!response.status.isSuccess()) error("Upsert user_activities failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteActivityFromPool(accessToken: String, activityId: String) {
        client.delete("$supabaseUrl/rest/v1/activity_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("activity_id", "eq.$activityId")
        }
        val response = client.delete("$supabaseUrl/rest/v1/user_activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$activityId")
        }
        if (!response.status.isSuccess()) error("Delete user_activities failed: ${response.bodyAsText()}")
    }
    suspend fun setActivityCategory(accessToken: String, activityId: String, category: String?) {
        val payload = categoryPatch(category)
        val response = client.patch("$supabaseUrl/rest/v1/user_activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$activityId")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Set activity category failed: ${response.bodyAsText()}")
    }
    suspend fun setActivityAutomation(accessToken: String, activityId: String, enabled: Boolean) {
        val payload = buildJsonObject { put("is_automated", enabled) }
        val response = client.patch("$supabaseUrl/rest/v1/user_activities") {
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
            parameter("select", "id,user_id,activity_id,position,status,user_activities(id,label,category,icon_key,is_automatable,is_automated)")
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

    //  Activity log (writes to time_in_high_hr_zones_daily) 
    @Serializable
    data class ActivityLogRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("activity_type") val type: String? = null,
        val category: String? = null,
        @SerialName("start_at") val startAt: String? = null,
        @SerialName("end_at") val endAt: String? = null,
        val notes: String? = null,
        @SerialName("migraine_id") val migraineId: String? = null,
        val source: String? = "manual"
    )
    @Serializable
    private data class ActivityLogInsert(
        @SerialName("activity_type") val activityType: String? = null,
        @SerialName("start_at") val startAt: String,
        @SerialName("end_at") val endAt: String? = null,
        val date: String,
        val notes: String? = null,
        @SerialName("migraine_id") val migraineId: String? = null,
        val source: String = "manual",
        @SerialName("value_minutes") val valueMinutes: Int = 0
    )
    suspend fun insertActivity(
        accessToken: String,
        migraineId: String?,
        type: String?,
        startAt: String?,
        endAt: String? = null,
        notes: String?
    ): ActivityLogRow {
        val safeStart = startAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString()
        val dateOnly = safeStart.substringBefore("T").take(10)
        val payload = ActivityLogInsert(
            activityType = type?.lowercase(),
            startAt = safeStart,
            endAt = endAt,
            date = dateOnly,
            notes = notes,
            migraineId = migraineId
        )
        val response: HttpResponse = client.post("$supabaseUrl/rest/v1/time_in_high_hr_zones_daily") {
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

    /** Insert into the correct public.activities table (not the legacy
     *  HR-zones table). Used by the evening check-in's Activities page. */
    suspend fun insertActivityV2(
        accessToken: String,
        migraineId: String?,
        type: String?,
        startAt: String?,
        endAt: String? = null,
        notes: String?
    ): Boolean {
        val safeStart = startAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString()
        val durationMinutes: Int? = endAt?.let {
            try {
                val ms = Instant.parse(it).toEpochMilli() - Instant.parse(safeStart).toEpochMilli()
                (ms / 60_000L).toInt().coerceAtLeast(0)
            } catch (_: Exception) { null }
        }
        val body = kotlinx.serialization.json.buildJsonObject {
            type?.let { put("type", it) }
            put("start_at", safeStart)
            endAt?.let { put("end_at", it) }
            durationMinutes?.let { put("duration_minutes", it) }
            notes?.let { put("notes", it) }
            migraineId?.let { put("migraine_id", it) }
            // activities is the one log table WITHOUT a `source` default, so an
            // omitted key lands NULL rather than 'manual' — and the Journal's
            // Manual/Auto filter is `source == "manual"`, which filed every
            // check-in activity under "Auto".
            put("source", "manual")
        }
        val res = client.post("$supabaseUrl/rest/v1/activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return res.status.isSuccess()
    }

    /** Lightweight row for recent activity queries. */
    @Serializable data class RecentActivityRow(
        val id: String,
        @SerialName("activity_type") val activityType: String? = null,
        @SerialName("start_at") val startAt: String? = null,
        val date: String? = null
    )

    /** Fetch activities logged in the last [daysBack] days from time_in_high_hr_zones_daily. */
    @Serializable data class UpcomingActivityRow(
        val type: String? = null,
        @SerialName("start_at") val startAt: String? = null,
    )

    /** Activities scheduled today through today+daysAhead. Used by the
     *  migraine wizard's MissedActivities page to auto-suggest activities
     *  the user is likely to miss while sick. Queries the consolidated
     *  public.activities table (NOT the legacy HR-zones table that
     *  getRecentActivities currently targets). */
    suspend fun getUpcomingActivities(accessToken: String, daysAhead: Int = 7, referenceDate: String? = null): List<UpcomingActivityRow> {
        val refDate = referenceDate?.let {
            try { java.time.LocalDate.parse(it.substring(0, 10)) } catch (_: Exception) { null }
        } ?: java.time.LocalDate.now()
        val fromIso = refDate.atStartOfDay().atZone(java.time.ZoneOffset.UTC).toInstant().toString()
        val toIso = refDate.plusDays((daysAhead + 1).toLong()).atStartOfDay().atZone(java.time.ZoneOffset.UTC).toInstant().toString()
        val response = client.get("$supabaseUrl/rest/v1/activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "type,start_at")
            parameter("start_at", "gte.$fromIso")
            parameter("start_at", "lt.$toIso")
            parameter("order", "start_at.asc")
        }
        if (!response.status.isSuccess()) return emptyList()
        return response.body()
    }

    suspend fun getRecentActivities(accessToken: String, daysBack: Int = 3, referenceDate: String? = null): List<RecentActivityRow> {
        val refDate = referenceDate?.let {
            try { java.time.LocalDate.parse(it.substring(0, 10)) } catch (_: Exception) { null }
        } ?: java.time.LocalDate.now()
        val cutoffStart = refDate.minusDays(daysBack.toLong()).toString()
        val cutoffEnd = refDate.plusDays(1).toString()
        val response = client.get("$supabaseUrl/rest/v1/time_in_high_hr_zones_daily") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("select", "id,activity_type,start_at,date")
            parameter("date", "gte.$cutoffStart")
            parameter("date", "lte.$cutoffEnd")
            parameter("order", "date.desc")
        }
        if (!response.status.isSuccess()) return emptyList()
        return response.body()
    }

    /** Fetch all activity log entries. */
    suspend fun getAllActivityLog(accessToken: String, window: JournalWindow? = null): List<ActivityLogRow> {
        val response = client.get("$supabaseUrl/rest/v1/time_in_high_hr_zones_daily") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("activity_type", "neq.daily_total")
            parameter("start_at", "not.is.null")
            parameter("order", "start_at.desc")
            journalWindow(window)
        }
        if (!response.status.isSuccess()) return emptyList()
        return response.body()
    }

    /** One activity log by id — see [getProdromeLogById]. */
    suspend fun getActivityLogById(accessToken: String, id: String): ActivityLogRow {
        val response = client.get("$supabaseUrl/rest/v1/time_in_high_hr_zones_daily") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id"); parameter("select", "*")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
        }
        if (!response.status.isSuccess()) error("Get activity log by id failed: ${response.bodyAsText()}")
        return response.body()
    }

    suspend fun deleteActivityLog(accessToken: String, id: String) {
        client.delete("$supabaseUrl/rest/v1/time_in_high_hr_zones_daily") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
        }
    }

    suspend fun updateActivityLog(accessToken: String, id: String, type: String?, startAt: String?, endAt: String? = null, notes: String?, category: String? = null, moveCategory: Boolean = false) {
        val payload = buildJsonObject {
            type?.let {
                put("activity_type", it)
                // Same contract as updateRelief: a retype moves the row's
                // category with it, NULL included when the new label is in
                // no pool; moveCategory false leaves the column untouched.
                if (moveCategory) {
                    if (category != null) put("category", category)
                    else put("category", kotlinx.serialization.json.JsonNull)
                }
            }
            startAt?.let { put("start_at", it) }
            endAt?.let { put("end_at", it) }
            notes?.let { put("notes", it) }
        }
        client.patch("$supabaseUrl/rest/v1/time_in_high_hr_zones_daily") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
            contentType(ContentType.Application.Json); setBody(payload)
        }
    }

    //  MISSED ACTIVITY POOL / PREFS 
    @Serializable data class UserMissedActivityRow(val id: String, val label: String, val category: String? = null, @SerialName("icon_key") val iconKey: String? = null, @SerialName("is_automatable") val isAutomatable: Boolean = false, @SerialName("is_automated") val isAutomated: Boolean = false)
    @Serializable
    data class MissedActivityPrefRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("missed_activity_id") val missedActivityId: String,
        val position: Int,
        val status: String,
        @SerialName("user_missed_activities") val missedActivity: UserMissedActivityRow? = null
    )
    @Serializable private data class UserMissedActivityInsert(val label: String, val category: String? = null)

    suspend fun getAllMissedActivityPool(accessToken: String): List<UserMissedActivityRow> {
        val response = client.get("$supabaseUrl/rest/v1/user_missed_activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,label,category,icon_key,is_automatable,is_automated"); parameter("order", "label.asc")
        }
        if (!response.status.isSuccess()) error("Fetch user_missed_activities failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun upsertMissedActivityToPool(accessToken: String, label: String, category: String? = null): UserMissedActivityRow {
        val response = client.post("$supabaseUrl/rest/v1/user_missed_activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation,resolution=merge-duplicates")
            parameter("on_conflict", "user_id,label")
            header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json); setBody(UserMissedActivityInsert(label, category))
        }
        if (!response.status.isSuccess()) error("Upsert user_missed_activities failed: ${response.bodyAsText()}")
        return response.body()
    }
    suspend fun deleteMissedActivityFromPool(accessToken: String, id: String) {
        client.delete("$supabaseUrl/rest/v1/missed_activity_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("missed_activity_id", "eq.$id")
        }
        val response = client.delete("$supabaseUrl/rest/v1/user_missed_activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
        }
        if (!response.status.isSuccess()) error("Delete user_missed_activities failed: ${response.bodyAsText()}")
    }
    suspend fun setMissedActivityCategory(accessToken: String, id: String, category: String?) {
        val payload = categoryPatch(category)
        val response = client.patch("$supabaseUrl/rest/v1/user_missed_activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
            contentType(ContentType.Application.Json); setBody(payload)
        }
        if (!response.status.isSuccess()) error("Set missed activity category failed: ${response.bodyAsText()}")
    }
    suspend fun setMissedActivityAutomation(accessToken: String, id: String, enabled: Boolean) {
        val payload = buildJsonObject { put("is_automated", enabled) }
        val response = client.patch("$supabaseUrl/rest/v1/user_missed_activities") {
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
            parameter("select", "id,user_id,missed_activity_id,position,status,user_missed_activities(id,label,category,icon_key,is_automatable,is_automated)")
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

    //  Missed Activity log 
    @Serializable
    data class MissedActivityLogRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        val type: String? = null,
        val category: String? = null,
        @SerialName("start_at") val startAt: String,
        val notes: String? = null,
        @SerialName("migraine_id") val migraineId: String? = null,
        val source: String? = "manual",
        /** True when the activity was given up because an attack was EXPECTED,
         *  not because one happened. Never infer this from a null migraineId:
         *  a quick-logged miss on an attack day can be left unlinked. */
        val anticipated: Boolean = false,
        /** Trigger/prodrome pool labels the user gave as the reason. Free text
         *  stays in `notes`. */
        @SerialName("reason_labels") val reasonLabels: List<String>? = null
    )
    @Serializable
    private data class MissedActivityLogInsert(
        val type: String? = null,
        val category: String? = null,
        @SerialName("start_at") val startAt: String,
        val notes: String? = null,
        @SerialName("migraine_id") val migraineId: String? = null,
        val anticipated: Boolean = false,
        @SerialName("reason_labels") val reasonLabels: List<String>? = null
    )
    suspend fun insertMissedActivity(
        accessToken: String,
        migraineId: String?,
        type: String?,
        startAt: String?,
        notes: String?,
        anticipated: Boolean = false,
        reasonLabels: List<String>? = null
    ): MissedActivityLogRow {
        val safeStart = startAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString()
        val payload = MissedActivityLogInsert(
            type = type, startAt = safeStart, notes = notes, migraineId = migraineId,
            anticipated = anticipated, reasonLabels = reasonLabels?.takeIf { it.isNotEmpty() }
        )
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

    /** Fetch all missed activity log entries. */
    suspend fun getAllMissedActivityLog(accessToken: String, window: JournalWindow? = null): List<MissedActivityLogRow> {
        val response = client.get("$supabaseUrl/rest/v1/missed_activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            parameter("order", "start_at.desc")
            journalWindow(window)
        }
        if (!response.status.isSuccess()) return emptyList()
        return response.body()
    }

    suspend fun deleteMissedActivityLog(accessToken: String, id: String) {
        client.delete("$supabaseUrl/rest/v1/missed_activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
        }
    }

    suspend fun updateMissedActivityLog(accessToken: String, id: String, type: String?, startAt: String?, notes: String?) {
        val payload = buildJsonObject {
            type?.let { put("type", it) }
            startAt?.let { put("start_at", it) }
            notes?.let { put("notes", it) }
        }
        client.patch("$supabaseUrl/rest/v1/missed_activities") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
            contentType(ContentType.Application.Json); setBody(payload)
        }
    }

    // 
    // Risk Score – Live + Daily
    // 

    @Serializable
    data class RiskScoreLiveRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        val score: Double,
        val zone: String,
        val percent: Int,
        @SerialName("top_triggers") val topTriggers: String? = null,   // jsonb -> raw string
        val forecast: String? = null,                                   // jsonb -> raw string
        @SerialName("day_risks") val dayRisks: String? = null,          // jsonb -> raw string
        @SerialName("updated_at") val updatedAt: String? = null,
    )

    /** Read the pre-computed live risk score for the current user. Returns null if none exists. */
    suspend fun getRiskScoreLive(accessToken: String): RiskScoreLiveRow? {
        return try {
            val response = client.get("$supabaseUrl/rest/v1/risk_score_live") {
                header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
                parameter("select", "id,user_id,score,zone,percent,top_triggers::text,forecast::text,day_risks::text,updated_at")
                parameter("limit", "1")
            }
            if (!response.status.isSuccess()) {
                android.util.Log.w("SupabaseDb", "getRiskScoreLive HTTP ${response.status}")
                return null
            }
            val raw = response.bodyAsText()
            android.util.Log.d("SupabaseDb", "getRiskScoreLive raw: ${raw.take(500)}")
            if (raw.isBlank() || raw == "[]") return null

            // Parse manually to avoid kotlinx.serialization issues with jsonb
            val arr = org.json.JSONArray(raw)
            if (arr.length() == 0) return null
            val obj = arr.getJSONObject(0)
            RiskScoreLiveRow(
                id = obj.optString("id", ""),
                userId = obj.optString("user_id", ""),
                score = obj.optDouble("score", 0.0),
                zone = obj.optString("zone", "NONE"),
                percent = obj.optInt("percent", 0),
                topTriggers = obj.optString("top_triggers", null)?.takeIf { it != "null" },
                forecast = obj.optString("forecast", null)?.takeIf { it != "null" },
                dayRisks = obj.optString("day_risks", null)?.takeIf { it != "null" },
                updatedAt = obj.optString("updated_at", null)?.takeIf { it != "null" },
            )
        } catch (e: Exception) {
            android.util.Log.e("SupabaseDb", "getRiskScoreLive error: ${e.message}", e)
            null
        }
    }

    @Serializable
    data class RiskScoreDailyRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        val date: String,
        val score: Double,
        val zone: String,
        val percent: Int,
        @SerialName("top_triggers") val topTriggers: kotlinx.serialization.json.JsonElement? = null,
        @SerialName("created_at") val createdAt: String? = null,
    )

    /** Read daily risk score history. Returns most recent first. */
    suspend fun getRiskScoreDaily(accessToken: String, daysBack: Int = 30): List<RiskScoreDailyRow> {
        val cutoff = java.time.LocalDate.now().minusDays(daysBack.toLong()).toString()
        val response = client.get("$supabaseUrl/rest/v1/risk_score_daily") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "*")
            parameter("date", "gte.$cutoff")
            parameter("order", "date.desc")
        }
        if (!response.status.isSuccess()) return emptyList()
        return response.body()
    }

    companion object {
        /** Convert DB metric_table + metric_column → graph chip key (e.g. "sleep:duration"). */
        fun tableColToChipKey(table: String, column: String?): String? =
            TABLE_COL_TO_CHIP_KEY["$table|${column.orEmpty()}"]

        /** Derive graph category from chip key prefix. */
        fun chipCategory(chipKey: String): String = when (chipKey.substringBefore(':')) {
            "sleep" -> "Sleep"; "weather" -> "Weather"; "physical" -> "Physical"
            "mental" -> "Cognitive"; "nutrition" -> "Diet"; else -> "Other"
        }

        // (metric_table|metric_column) → chip key
        val TABLE_COL_TO_CHIP_KEY = mapOf(
            // Sleep
            "sleep_duration_daily|value_hours" to "sleep:duration",
            "sleep_score_daily|value_pct" to "sleep:score",
            "sleep_efficiency_daily|value_pct" to "sleep:efficiency",
            "sleep_disturbances_daily|value_count" to "sleep:disturbances",
            "sleep_stages_daily|value_sws_hm" to "sleep:stages_deep",
            "sleep_stages_daily|value_rem_hm" to "sleep:stages_rem",
            "sleep_stages_daily|value_light_hm" to "sleep:stages_light",
            "fell_asleep_time_daily|value_at" to "sleep:fell_asleep",
            "woke_up_time_daily|value_at" to "sleep:woke_up",
            // Weather
            "user_weather_daily|temp_c_mean" to "weather:temp_c_mean",
            "user_weather_daily|pressure_hpa_mean" to "weather:pressure_hpa_mean",
            "user_weather_daily|humidity_pct_mean" to "weather:humidity_pct_mean",
            "user_weather_daily|wind_speed_mps_mean" to "weather:wind_speed_mps_mean",
            "user_weather_daily|uv_index_max" to "weather:uv_index_max",
            "user_location_daily|altitude_max_m" to "weather:altitude_m",
            "user_location_daily|altitude_change_m" to "weather:altitude_change_m",
            // Physical
            "recovery_score_daily|value_pct" to "physical:recovery",
            "hrv_daily|value_rmssd_ms" to "physical:hrv",
            "resting_hr_daily|value_bpm" to "physical:resting_hr",
            "spo2_daily|value_pct" to "physical:spo2",
            "skin_temp_daily|value_celsius" to "physical:skin_temp",
            "respiratory_rate_daily|value_bpm" to "physical:respiratory_rate",
            "time_in_high_hr_zones_daily|value_minutes" to "physical:high_hr_zones",
            "steps_daily|value_count" to "physical:steps",
            // Mental
            "screen_time_daily|total_hours" to "mental:screen_time",
            "screen_time_late_night|value_hours" to "mental:late_screen_time",
            "ambient_noise_index_daily|day_mean_lmean" to "mental:noise_avg",
            "ambient_noise_index_daily|day_max_lmax" to "mental:noise_high",
            "ambient_noise_index_daily|day_min_lmean" to "mental:noise_low",
            "phone_brightness_daily|value_mean" to "mental:brightness",
            "phone_volume_daily|value_mean_pct" to "mental:volume",
            "phone_dark_mode_daily|value_hours" to "mental:dark_mode",
            "phone_unlock_daily|value_count" to "mental:unlocks",
            // Nutrition
            "nutrition_daily|total_calories" to "nutrition:calories",
            "nutrition_daily|total_protein_g" to "nutrition:protein",
            "nutrition_daily|total_carbs_g" to "nutrition:carbs",
            "nutrition_daily|total_fat_g" to "nutrition:fat",
            "nutrition_daily|total_fiber_g" to "nutrition:fiber",
            "nutrition_daily|total_sugar_g" to "nutrition:sugar",
            "nutrition_daily|total_sodium_mg" to "nutrition:sodium",
            "nutrition_daily|total_caffeine_mg" to "nutrition:caffeine",
            "nutrition_daily|total_cholesterol_mg" to "nutrition:cholesterol",
            "nutrition_daily|total_saturated_fat_g" to "nutrition:saturated_fat",
            "nutrition_daily|total_unsaturated_fat_g" to "nutrition:unsaturated_fat",
            "nutrition_daily|total_trans_fat_g" to "nutrition:trans_fat",
            "nutrition_daily|total_potassium_mg" to "nutrition:potassium",
            "nutrition_daily|total_calcium_mg" to "nutrition:calcium",
            "nutrition_daily|total_iron_mg" to "nutrition:iron",
            "nutrition_daily|total_magnesium_mg" to "nutrition:magnesium",
            "nutrition_daily|total_zinc_mg" to "nutrition:zinc",
            "nutrition_daily|total_selenium_mcg" to "nutrition:selenium",
            "nutrition_daily|total_phosphorus_mg" to "nutrition:phosphorus",
            "nutrition_daily|total_copper_mg" to "nutrition:copper",
            "nutrition_daily|total_manganese_mg" to "nutrition:manganese",
            "nutrition_daily|total_vitamin_a_mcg" to "nutrition:vitamin_a",
            "nutrition_daily|total_vitamin_c_mg" to "nutrition:vitamin_c",
            "nutrition_daily|total_vitamin_d_mcg" to "nutrition:vitamin_d",
            "nutrition_daily|total_vitamin_e_mg" to "nutrition:vitamin_e",
            "nutrition_daily|total_vitamin_k_mcg" to "nutrition:vitamin_k",
            "nutrition_daily|total_vitamin_b6_mg" to "nutrition:vitamin_b6",
            "nutrition_daily|total_vitamin_b12_mcg" to "nutrition:vitamin_b12",
            "nutrition_daily|total_thiamin_mg" to "nutrition:thiamin",
            "nutrition_daily|total_riboflavin_mg" to "nutrition:riboflavin",
            "nutrition_daily|total_niacin_mg" to "nutrition:niacin",
            "nutrition_daily|total_folate_mcg" to "nutrition:folate",
            "nutrition_daily|total_biotin_mcg" to "nutrition:biotin",
            "nutrition_daily|total_pantothenic_acid_mg" to "nutrition:pantothenic_acid",
            "nutrition_daily|max_tyramine_exposure" to "nutrition:tyramine_exposure",
            "nutrition_daily|max_alcohol_exposure" to "nutrition:alcohol_exposure",
            "nutrition_daily|max_gluten_exposure" to "nutrition:gluten_exposure",
            "nutrition_daily|max_histamine_exposure" to "nutrition:histamine_exposure",
        )
    }

    //  TREATMENT SIDE EFFECTS POOL / PREFS

    @Serializable
    data class UserTreatmentSideEffectRow(
        val id: String,
        val label: String,
        val category: String? = null,
        @SerialName("icon_key") val iconKey: String? = null,
    )

    @Serializable
    data class TreatmentSideEffectPrefRow(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("side_effect_id") val sideEffectId: String,
        val position: Int,
        val status: String,
        @SerialName("user_treatment_side_effects") val sideEffect: UserTreatmentSideEffectRow? = null,
    )

    @Serializable
    private data class UserTreatmentSideEffectInsert(
        val label: String,
        val category: String? = null,
        @SerialName("icon_key") val iconKey: String? = null,
    )

    suspend fun getUserTreatmentSideEffects(accessToken: String): List<UserTreatmentSideEffectRow> {
        val response = client.get("$supabaseUrl/rest/v1/user_treatment_side_effects") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,label,category,icon_key"); parameter("order", "label.asc")
        }
        if (!response.status.isSuccess()) error("Fetch user_treatment_side_effects failed: ${response.bodyAsText()}")
        return response.body()
    }

    suspend fun insertTreatmentSideEffectToPool(accessToken: String, label: String, category: String? = null, iconKey: String? = null): UserTreatmentSideEffectRow {
        val response = client.post("$supabaseUrl/rest/v1/user_treatment_side_effects") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            header("Prefer", "return=representation"); header(HttpHeaders.Accept, "application/vnd.pgrst.object+json")
            contentType(ContentType.Application.Json)
            setBody(UserTreatmentSideEffectInsert(label, category, iconKey))
        }
        if (!response.status.isSuccess()) error("Insert user_treatment_side_effects failed: ${response.bodyAsText()}")
        return response.body()
    }

    suspend fun deleteTreatmentSideEffectFromPool(accessToken: String, id: String) {
        val response = client.delete("$supabaseUrl/rest/v1/user_treatment_side_effects") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("id", "eq.$id")
        }
        if (!response.status.isSuccess()) error("Delete user_treatment_side_effects failed: ${response.bodyAsText()}")
    }

    suspend fun getTreatmentSideEffectPrefs(accessToken: String): List<TreatmentSideEffectPrefRow> {
        val response = client.get("$supabaseUrl/rest/v1/treatment_side_effect_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("select", "id,user_id,side_effect_id,position,status,user_treatment_side_effects(id,label,category,icon_key)")
            parameter("order", "position.asc")
        }
        if (!response.status.isSuccess()) error("Fetch treatment_side_effect_preferences failed: ${response.bodyAsText()}")
        return response.body()
    }

    @Serializable
    private data class TreatmentSideEffectPrefInsert(
        @SerialName("side_effect_id") val sideEffectId: String,
        val position: Int,
        val status: String,
    )

    suspend fun insertTreatmentSideEffectPref(accessToken: String, sideEffectId: String, position: Int, status: String = "frequent") {
        // Delete-then-insert mirrors the trigger pattern so re-fav'ing reorders cleanly.
        runCatching {
            client.delete("$supabaseUrl/rest/v1/treatment_side_effect_preferences") {
                header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
                parameter("side_effect_id", "eq.$sideEffectId")
            }
        }
        val response = client.post("$supabaseUrl/rest/v1/treatment_side_effect_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            contentType(ContentType.Application.Json)
            setBody(TreatmentSideEffectPrefInsert(sideEffectId, position, status))
        }
        if (!response.status.isSuccess()) error("Insert treatment_side_effect_preferences failed: ${response.bodyAsText()}")
    }

    suspend fun deleteTreatmentSideEffectPref(accessToken: String, sideEffectId: String) {
        val response = client.delete("$supabaseUrl/rest/v1/treatment_side_effect_preferences") {
            header(HttpHeaders.Authorization, "Bearer $accessToken"); header("apikey", supabaseKey)
            parameter("side_effect_id", "eq.$sideEffectId")
        }
        if (!response.status.isSuccess()) error("Delete treatment_side_effect_preferences failed: ${response.bodyAsText()}")
    }
}

/**
 * PATCH body for a pool item's category: set it, or clear it when null.
 *
 * This used to be written inline as
 * `category?.let { put("category", it) } ?: put("category", JsonNull)`.
 * JsonObjectBuilder.put returns the PREVIOUS value for the key, which is null
 * on a first write, so the let branch evaluated to null and the elvis fallback
 * ran every time — the body sent was always `{"category": null}`. Marking a
 * pool item as a Device, or filing one under any category at all, silently
 * cleared it instead. That is the same column device detection now reads.
 */
private fun categoryPatch(category: String?) = buildJsonObject {
    if (category != null) put("category", category)
    else put("category", kotlinx.serialization.json.JsonNull)
}

/**
 * user_reliefs.category keyed by label, so a relief insert that only ever has
 * a label can still stamp the pool item's own category on the row.
 *
 * (user_id, label) is unique in user_reliefs — upsertReliefToPool relies on
 * exactly that constraint — so a label identifies one pool item and nothing
 * else. Cached per access token: a token belongs to one user, so a sign-out
 * and sign-in as somebody else can never read the previous user's pool.
 *
 * A label that is in the pool but has no category, and a label that is in no
 * pool at all (the generic "Relief" the skip button writes, the widget's
 * "Unknown"), both resolve to null. That is the correct answer, not a failure:
 * the row keeps category NULL and device detection falls back to name matching
 * exactly as it did before.
 */
private object ReliefPoolCategories {
    private val mutex = Mutex()
    private var forToken: String? = null
    private var byLabel: Map<String, String> = emptyMap()
    private var lastMissReloadMs = 0L

    /** A miss can mean "added to the pool since we loaded", so re-read — but
     *  not on every generic label, or the widget would refetch the pool per tap. */
    private const val MISS_RELOAD_INTERVAL_MS = 60_000L

    suspend fun categoryFor(db: SupabaseDbService, accessToken: String, label: String?): String? {
        val key = label?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return mutex.withLock {
            if (forToken != accessToken) reload(db, accessToken)
            byLabel[key] ?: run {
                val now = System.currentTimeMillis()
                if (now - lastMissReloadMs >= MISS_RELOAD_INTERVAL_MS) {
                    lastMissReloadMs = now
                    reload(db, accessToken)
                }
                byLabel[key]
            }
        }
    }

    /** Clears first so a failed read can never serve another user's pool, and
     *  leaves forToken null on failure so the next insert retries. */
    private suspend fun reload(db: SupabaseDbService, accessToken: String) {
        forToken = null
        byLabel = emptyMap()
        val rows = runCatching { db.getAllReliefPool(accessToken) }.getOrNull() ?: return
        byLabel = rows.mapNotNull { row ->
            row.category?.takeIf { it.isNotBlank() }?.let { row.label.trim().lowercase() to it }
        }.toMap()
        forToken = accessToken
    }
}




