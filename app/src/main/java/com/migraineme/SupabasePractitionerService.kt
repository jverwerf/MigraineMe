// FILE: app/src/main/java/com/migraineme/SupabasePractitionerService.kt
package com.migraineme

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.request.setBody
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/**
 * The practitioner side, from the patient's end.
 *
 * Backed by PostgREST tables practitioners / practitioner_clients /
 * practitioner_consent_events / practitioner_access_log /
 * practitioner_appointment_requests.
 *
 * The rule the whole feature rests on: only the patient may widen what a
 * practitioner sees. The practitioner can ask, and her request lands in
 * requested_scopes; nothing she does writes `scopes`. That is enforced by row
 * level security, not by this class, so a bug here cannot leak a diary.
 */
object SupabasePractitionerService {

    private val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    private val client = HttpClient(Android) {
        install(ContentNegotiation) { json(json) }
    }

    /**
     * What a client can share.
     *
     * Consent is per data family, not per bucket: a patient who wants their
     * physio to see sleep should not have to hand over their body weight to do
     * it. The groups the app draws come from consent_scopes, and match the
     * Monitor and Insights cards the patient already reads, so nobody has to
     * work out what a scope name means.
     *
     * These keys are the contract with the database policies. The gate
     * practitioner_can_read(user, scope) tests membership against exactly
     * these strings, so renaming one here without changing the policies
     * silently removes a practitioner's access.
     */
    enum class Scope(val key: String) {
        ATTACKS("attacks"), SYMPTOMS("symptoms"), PRODROMES("prodromes"),
        PAIN_LOCATIONS("pain_locations"), AURA("aura"), ATTACK_NOTES("attack_notes"),
        CONTEXT("context"),
        TRIGGERS("triggers"),
        FOOD("food"),
        MEDICATION("medication"), SIDE_EFFECTS("side_effects"),
        REGIMENS("regimens"), NARRATIVE("narrative"),
        SLEEP("sleep"),
        HEART("heart"), ACTIVITY("activity"), BODY_MEASURES("body_measures"),
        STRESS("stress"), PHONE_USE("phone_use"),
        WEATHER("weather"), AIR_QUALITY("air_quality"),
        CYCLE("cycle"),
        INSIGHTS("insights"), SETUP_PROFILE("setup_profile"), RISK("risk");

        companion object {
            fun fromKey(k: String?): Scope? = entries.firstOrNull { it.key == k }
        }
    }

    /**
     * The groups, in the order the app shows them. Kept here rather than read
     * from consent_scopes on every open: the consent sheet must render with no
     * network at all, or a patient on a bad connection is asked to agree to a
     * list that has not loaded.
     */
    data class ScopeGroup(val title: String, val scopes: List<Scope>)

    val SCOPE_GROUPS: List<ScopeGroup> = listOf(
        ScopeGroup("Migraines", listOf(Scope.ATTACKS, Scope.SYMPTOMS, Scope.PRODROMES,
            Scope.PAIN_LOCATIONS, Scope.AURA, Scope.ATTACK_NOTES, Scope.CONTEXT)),
        ScopeGroup("Triggers", listOf(Scope.TRIGGERS)),
        ScopeGroup("Diet", listOf(Scope.FOOD)),
        ScopeGroup("Medicines", listOf(Scope.MEDICATION, Scope.SIDE_EFFECTS)),
        ScopeGroup("Treatments", listOf(Scope.REGIMENS, Scope.NARRATIVE)),
        ScopeGroup("Sleep", listOf(Scope.SLEEP)),
        ScopeGroup("Physical Health", listOf(Scope.HEART, Scope.ACTIVITY, Scope.BODY_MEASURES)),
        ScopeGroup("Cognitive", listOf(Scope.STRESS, Scope.PHONE_USE)),
        ScopeGroup("Environment", listOf(Scope.WEATHER, Scope.AIR_QUALITY)),
        ScopeGroup("Menstruation", listOf(Scope.CYCLE)),
        ScopeGroup("Risk", listOf(Scope.RISK)),
        ScopeGroup("Insights", listOf(Scope.INSIGHTS, Scope.SETUP_PROFILE)),
    )

    // ---- wire models ----

    @Serializable
    data class BioRow(
        val lang: String? = null,
        val headline: String? = null,
        val bio: String? = null,
        val treats: List<String> = emptyList(),
        val is_source: Boolean = false,
    )

    @Serializable
    data class PractitionerRow(
        val id: String,
        val slug: String,
        val display_name: String,
        val practice_name: String? = null,
        val discipline: String,
        val photo_url: String? = null,
        val website: String? = null,
        val languages: List<String> = emptyList(),
        val country: String? = null,
        val city: String? = null,
        val consult_mode: String = "both",
        val listing_mode: String = "listed",
        val registration_body: String? = null,
        val registration_number: String? = null,
        val practitioner_bios: List<BioRow> = emptyList(),
    ) {
        /** The card copy in the reader's language, then the practitioner's own,
         *  then English. A bio nobody has translated should still show in the
         *  words she wrote it in rather than disappear. */
        fun bioFor(lang: String): BioRow? =
            practitioner_bios.firstOrNull { it.lang == lang }
                ?: practitioner_bios.firstOrNull { it.is_source }
                ?: practitioner_bios.firstOrNull { it.lang == "en" }
                ?: practitioner_bios.firstOrNull()
    }

    @Serializable
    data class LinkRow(
        val id: String,
        val practitioner_id: String,
        val user_id: String,
        val status: String,
        val initiated_by: String = "client",
        val requested_scopes: List<String> = emptyList(),
        val scopes: List<String> = emptyList(),
        val connected_at: String? = null,
        val revoked_at: String? = null,
        val last_viewed_at: String? = null,
        val created_at: String? = null,
        val practitioners: PractitionerRow? = null,
    ) {
        val granted: Set<Scope> get() = scopes.mapNotNull { Scope.fromKey(it) }.toSet()
        val requested: Set<Scope> get() = requested_scopes.mapNotNull { Scope.fromKey(it) }.toSet()
        val isActive: Boolean get() = status == "active"
        val isPending: Boolean get() = status == "pending"
    }

    @Serializable
    data class AccessRow(val practitioner_id: String, val viewed_at: String)

    @Serializable
    data class AppointmentRow(
        val id: String,
        val practitioner_id: String,
        val kind: String = "initial",
        val message: String? = null,
        val preferred_times: String? = null,
        val status: String = "requested",
        val response_note: String? = null,
        val scheduled_for: String? = null,
        val created_at: String? = null,
    )

    private const val PRAC_SELECT =
        "id,slug,display_name,practice_name,discipline,photo_url,website,languages,country,city," +
            "consult_mode,listing_mode,registration_body,registration_number," +
            "practitioner_bios(lang,headline,bio,treats,is_source)"

    // ---- reads ----

    /** The public directory. Only active, listable practitioners come back:
     *  a code-only practitioner has said she does not want clients this way,
     *  and the policy excludes her regardless of what we ask for. */
    suspend fun directory(accessToken: String?): List<PractitionerRow> {
        val url = "$baseUrl/rest/v1/practitioners?status=eq.active" +
            "&listing_mode=in.(bookable,listed)&select=$PRAC_SELECT&order=display_name.asc"
        return client.get(url) {
            header("apikey", anonKey)
            if (!accessToken.isNullOrBlank()) header("Authorization", "Bearer $accessToken")
        }.body()
    }

    suspend fun bySlug(accessToken: String?, slug: String): PractitionerRow? {
        val url = "$baseUrl/rest/v1/practitioners?slug=eq.$slug&status=eq.active" +
            "&listing_mode=in.(bookable,listed)&limit=1&select=$PRAC_SELECT"
        val rows: List<PractitionerRow> = client.get(url) {
            header("apikey", anonKey)
            if (!accessToken.isNullOrBlank()) header("Authorization", "Bearer $accessToken")
        }.body()
        return rows.firstOrNull()
    }

    /** Every practitioner this patient is connected to or has been asked by. */
    suspend fun myLinks(accessToken: String): List<LinkRow> {
        val url = "$baseUrl/rest/v1/practitioner_clients?" +
            "select=id,practitioner_id,user_id,status,initiated_by,requested_scopes,scopes," +
            "connected_at,revoked_at,last_viewed_at,created_at,practitioners($PRAC_SELECT)" +
            "&order=created_at.desc"
        return client.get(url) {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
        }.body()
    }

    /** When each practitioner last opened this patient's diary. Shown to the
     *  patient because being able to see who looked, and when, is the other
     *  half of consent meaning anything. */
    suspend fun lastViewed(accessToken: String): Map<String, String> {
        val url = "$baseUrl/rest/v1/practitioner_access_log?" +
            "select=practitioner_id,viewed_at&order=viewed_at.desc&limit=200"
        val rows: List<AccessRow> = client.get(url) {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
        }.body()
        val out = LinkedHashMap<String, String>()
        for (r in rows) if (!out.containsKey(r.practitioner_id)) out[r.practitioner_id] = r.viewed_at
        return out
    }

    suspend fun myAppointments(accessToken: String): List<AppointmentRow> {
        val url = "$baseUrl/rest/v1/practitioner_appointment_requests?" +
            "select=id,practitioner_id,kind,message,preferred_times,status,response_note,scheduled_for,created_at" +
            "&order=created_at.desc"
        return client.get(url) {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
        }.body()
    }

    // ---- writes ----

    /**
     * Connect to a practitioner, granting exactly the scopes ticked.
     *
     * The consent event is written alongside, because the row only ever holds
     * what is true now; the record of who agreed to what, and when, has to
     * survive the patient later narrowing or revoking it.
     */
    suspend fun connect(
        accessToken: String,
        userId: String,
        practitionerId: String,
        scopes: Set<Scope>,
    ): LinkRow? {
        val body = buildJsonObject {
            put("practitioner_id", practitionerId)
            put("user_id", userId)
            put("status", "active")
            put("initiated_by", "client")
            putJsonArray("scopes") { scopes.forEach { add(it.key) } }
            putJsonArray("requested_scopes") { scopes.forEach { add(it.key) } }
            put("connected_at", nowIso())
        }
        val rows: List<LinkRow> = client.post("$baseUrl/rest/v1/practitioner_clients") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
        val link = rows.firstOrNull()
        if (link != null) logConsent(accessToken, link.id, "granted", emptySet(), scopes)
        return link
    }

    /** Answer a practitioner's request. Granting nothing is a decline, and is
     *  recorded as one rather than left as a request nobody ever answered. */
    suspend fun respondToRequest(
        accessToken: String,
        link: LinkRow,
        scopes: Set<Scope>,
    ) {
        val declining = scopes.isEmpty()
        val body = buildJsonObject {
            put("status", if (declining) "declined" else "active")
            putJsonArray("scopes") { scopes.forEach { add(it.key) } }
            if (!declining) put("connected_at", nowIso())
            put("updated_at", nowIso())
        }
        client.patch("$baseUrl/rest/v1/practitioner_clients?id=eq.${link.id}") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        logConsent(accessToken, link.id, if (declining) "declined" else "granted", link.granted, scopes)
    }

    /** Change what an already-connected practitioner may see. */
    suspend fun updateScopes(accessToken: String, link: LinkRow, scopes: Set<Scope>) {
        val before = link.granted
        val body = buildJsonObject {
            putJsonArray("scopes") { scopes.forEach { add(it.key) } }
            put("updated_at", nowIso())
        }
        client.patch("$baseUrl/rest/v1/practitioner_clients?id=eq.${link.id}") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val action = if (scopes.size < before.size) "narrowed" else "widened"
        logConsent(accessToken, link.id, action, before, scopes)
    }

    /** Stop sharing entirely. The link is kept, revoked, rather than deleted:
     *  the patient should be able to see that it happened and when. */
    suspend fun revoke(accessToken: String, link: LinkRow) {
        val body = buildJsonObject {
            put("status", "revoked")
            putJsonArray("scopes") { }
            put("revoked_at", nowIso())
            put("updated_at", nowIso())
        }
        client.patch("$baseUrl/rest/v1/practitioner_clients?id=eq.${link.id}") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        logConsent(accessToken, link.id, "revoked", link.granted, emptySet())
    }

    /** Ask for a first appointment. No money passes through this; it is a
     *  request the practitioner accepts or declines. */
    suspend fun requestAppointment(
        accessToken: String,
        userId: String,
        practitionerId: String,
        message: String?,
        preferredTimes: String?,
    ) {
        val body = buildJsonObject {
            put("practitioner_id", practitionerId)
            put("user_id", userId)
            put("kind", "initial")
            if (!message.isNullOrBlank()) put("message", message)
            if (!preferredTimes.isNullOrBlank()) put("preferred_times", preferredTimes)
            put("status", "requested")
        }
        client.post("$baseUrl/rest/v1/practitioner_appointment_requests") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    private suspend fun logConsent(
        accessToken: String,
        linkId: String,
        action: String,
        before: Set<Scope>,
        after: Set<Scope>,
    ) {
        val body = buildJsonObject {
            put("link_id", linkId)
            put("action", action)
            putJsonArray("scopes_before") { before.forEach { add(it.key) } }
            putJsonArray("scopes_after") { after.forEach { add(it.key) } }
            put("actor", "client")
            put("surface", "android")
        }
        runCatching {
            client.post("$baseUrl/rest/v1/practitioner_consent_events") {
                header("apikey", anonKey)
                header("Authorization", "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
    }

    private fun nowIso(): String =
        java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
