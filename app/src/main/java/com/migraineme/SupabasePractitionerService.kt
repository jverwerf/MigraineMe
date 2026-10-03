// FILE: app/src/main/java/com/migraineme/SupabasePractitionerService.kt
package com.migraineme

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
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

    /**
     * The categories as agreed with Stephanie on the card, in her order, with
     * the app's own extras placed where they belong. Kept here rather than
     * fetched: the consent sheet must render with no network at all, or a
     * patient on a bad connection is asked to agree to a list that has not
     * loaded.
     */
    val SCOPE_GROUPS: List<ScopeGroup> = listOf(
        ScopeGroup("Migraines", listOf(
            Scope.ATTACKS, Scope.SYMPTOMS, Scope.PRODROMES, Scope.PAIN_LOCATIONS,
            Scope.AURA, Scope.ATTACK_NOTES, Scope.CONTEXT)),
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
        val quote: String? = null,
        val bio: String? = null,
        val treats: List<String> = emptyList(),
        val meta: List<String> = emptyList(),
        val facts: List<Fact> = emptyList(),
        val is_source: Boolean = false,
    )

    /** One of the card's two stat boxes. */
    @Serializable
    data class Fact(val k: String = "", val v: String = "", val tone: String? = null)

    /** One thing a practitioner offers, in her own words and at her own price. */
    @Serializable
    data class Offer(
        val lang: String = "de",
        val sort: Int = 0,
        val title: String = "",
        val price: String? = null,
        val subtitle: String? = null,
        val bullets: List<String> = emptyList(),
        val kind: String = "service",
        val image_url: String? = null,
    )

    /** A titled block of her own writing — how the work unfolds, and so on. */
    @Serializable
    data class Section(
        val lang: String = "de",
        val sort: Int = 0,
        val title: String = "",
        val body: String? = null,
        val items: List<String> = emptyList(),
    )

    @Serializable
    data class PractitionerRow(
        val id: String,
        val slug: String,
        val display_name: String,
        val practice_name: String? = null,
        val discipline: String,
        val photo_url: String? = null,
        val banner_url: String? = null,
        val logo_url: String? = null,
        val facts: List<Fact> = emptyList(),
        val website: String? = null,
        val languages: List<String> = emptyList(),
        val country: String? = null,
        val city: String? = null,
        val consult_mode: String = "both",
        val listing_mode: String = "listed",
        val booking_url: String? = null,
        val booking_urls: Map<String, String>? = null,
        val registration_body: String? = null,
        val registration_number: String? = null,
        val practitioner_bios: List<BioRow> = emptyList(),
        val practitioner_offers: List<Offer> = emptyList(),
        val practitioner_sections: List<Section> = emptyList(),
    ) {
        /** Her booking page in the reader's language, then English, then
         *  the single booking_url. */
        fun bookingUrlFor(lang: String): String? =
            (booking_urls?.get(lang) ?: booking_urls?.get("en") ?: booking_url)
                ?.trim()?.takeIf { it.isNotEmpty() }

        fun sectionsFor(lang: String): List<Section> {
            val own = practitioner_sections.filter { it.lang == lang }
                .ifEmpty { practitioner_sections.filter { it.lang == "en" } }
                .ifEmpty { practitioner_sections }
            return own.sortedBy { it.sort }
        }
        /** Her offers in the reader's language, on the same fallback as the
         *  card: a price list in a language you cannot read is worse than one
         *  in English. */
        fun offersFor(lang: String): List<Offer> {
            val own = practitioner_offers.filter { it.lang == lang }
                .ifEmpty { practitioner_offers.filter { it.lang == "en" } }
                .ifEmpty { practitioner_offers }
            return own.sortedBy { it.sort }
        }
        /** The card copy in the reader's language, then English, then the
         *  language she wrote it in. English before the source language on
         *  purpose: a Dutch reader with no Dutch row is far likelier to read
         *  English than Swiss German. A card nobody has translated still shows
         *  in her own words rather than disappearing. */
        fun bioFor(lang: String): BioRow? =
            practitioner_bios.firstOrNull { it.lang == lang }
                ?: practitioner_bios.firstOrNull { it.lang == "en" }
                ?: practitioner_bios.firstOrNull { it.is_source }
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

        // Offered by the patient and waiting on her. A practitioner cannot
        // ask for a diary, so this is the only thing "pending" can mean.
        val isOffered: Boolean get() = status == "pending"
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

    // ---- goals (set by a practitioner on the web dashboard, or by the client
    // in the app; practitioner_id NULL = the client's own, which only the
    // client may edit or delete) ----

    /** One goal. `title` is an English key for the presets ("Heart rate
     *  training", "Chin tucks", "Meditation", a film title); free text falls
     *  back to itself through t(). */
    @Serializable
    data class GoalRow(
        val id: String,
        val practitioner_id: String? = null,
        val user_id: String,
        val kind: String,
        val title: String = "",
        val exercise_id: String? = null,
        val threshold_bpm: Int? = null,
        val target_minutes: Double? = null,
        val target_count: Int? = null,
        val times_per_week: Int? = null,
        /** kind 'metric' only: the goal_metric_catalog key, 'gte' | 'lte' |
         *  'consistent', and the target (null for 'consistent'). */
        val metric_key: String? = null,
        val direction: String? = null,
        val target_value: Double? = null,
        val reminder_times: List<String> = emptyList(),
        val reminders_enabled: Boolean = true,
        val status: String = "active",
        val note: String? = null,
        val created_at: String? = null,
        val updated_at: String? = null,
        /** Filled by a DB trigger from practitioners.display_name; clients
         *  cannot read most practitioner rows, so the name is copied here. */
        val practitioner_name: String? = null,
    ) {
        /** Set by the client in the app: editable and deletable here. */
        val isOwn: Boolean get() = practitioner_id == null
        val practitionerName: String? get() = practitioner_name?.takeIf { it.isNotBlank() }
        val isActive: Boolean get() = status == "active"
        val isPaused: Boolean get() = status == "paused"
        val isHr: Boolean get() = kind == KIND_HR
        val isExercise: Boolean get() = kind == KIND_EXERCISE
        val isMindfulness: Boolean get() = kind == KIND_MINDFULNESS
        val isDaily: Boolean get() = kind == KIND_DAILY
        /** Filled in by the server from the client's own data; never logged by hand. */
        val isMetric: Boolean get() = kind == KIND_METRIC
        val isConsistent: Boolean get() = direction == DIR_CONSISTENT
        /** Logged as a count, one tap = one: exercise films and free daily counters. */
        val isCounter: Boolean get() = isExercise || isDaily
        /** null times_per_week means every day. */
        val daysPerWeek: Int get() = times_per_week ?: 7
        val everyDay: Boolean get() = times_per_week == null || times_per_week >= 7
        /** "08:00:00" -> "08:00". */
        val reminderLabels: List<String> get() = reminder_times.map { it.take(5) }
    }

    const val KIND_HR = "hr_threshold"
    const val KIND_EXERCISE = "exercise_count"
    const val KIND_MINDFULNESS = "mindfulness_minutes"
    /** A free counter the client names: "Drink water, 8 times a day". */
    const val KIND_DAILY = "daily_count"
    /** Any catalogue metric: at least / at most a value, or staying consistent. */
    const val KIND_METRIC = "metric"
    const val DIR_GTE = "gte"
    const val DIR_LTE = "lte"
    /** Within 2 standard deviations of the 14-day average. */
    const val DIR_CONSISTENT = "consistent"

    /** One row of goal_metric_catalog. `label` is an English key for t(). */
    @Serializable
    data class GoalMetric(
        val key: String,
        val label: String = "",
        val grp: String = "",
        val unit: String = "",
        val default_direction: String = DIR_GTE,
        val default_target: Double? = null,
        val step: Double = 1.0,
        val sort: Int = 0,
        /** 'number' or 'level'. Level: target and readings are ranks 0..3 (None / Low / Medium / High). */
        val scale: String = "number",
    ) {
        val isLevel: Boolean get() = scale == "level"
    }

    /** What the goal editor saves. Fields that do not belong to [kind] are
     *  written as null, so switching kind on an edit leaves nothing stale. */
    data class GoalDraft(
        val kind: String,
        val title: String,
        val exerciseId: String? = null,
        val thresholdBpm: Int? = null,
        val targetMinutes: Double? = null,
        val targetCount: Int? = null,
        /** null = every day. */
        val timesPerWeek: Int? = null,
        val metricKey: String? = null,
        val direction: String? = null,
        val targetValue: Double? = null,
        /** "HH:mm:ss" */
        val reminderTimes: List<String> = emptyList(),
    )

    @Serializable
    data class GoalLogRow(
        val id: String? = null,
        val goal_id: String,
        val user_id: String,
        val date: String,
        val count: Int = 0,
        val minutes: Double = 0.0,
        val source: String = "manual",
    )

    /** One day from goal_daily_progress: value = count for exercise goals,
     *  minutes for the others (hr: longest stretch at or above threshold),
     *  that day's reading for metric goals (null = no data that day).
     *  is_estimate on a 'consistent' metric goal = baseline still building. */
    @Serializable
    data class GoalProgressRow(
        val day: String,
        val value: Double? = null,
        val achieved: Boolean = false,
        val is_estimate: Boolean = false,
    )

    @Serializable
    data class HrThresholdDailyRow(
        val user_id: String,
        val date: String,
        val threshold_bpm: Int,
        val minutes_above: Double,
        val longest_run_minutes: Double,
        val is_estimate: Boolean = false,
        val source: String = "health_connect",
    )

    @Serializable
    data class MindfulnessDailyRow(
        val user_id: String,
        val date: String,
        val duration_minutes: Int,
        val session_count: Int,
        val source: String = "health_connect",
    )

    private const val PRAC_SELECT =
        "id,slug,display_name,practice_name,discipline,photo_url,banner_url,logo_url,facts," +
            "website,languages,country,city,consult_mode,listing_mode,booking_url,booking_urls," +
            "registration_body,registration_number," +
            "practitioner_bios(lang,headline,quote,bio,treats,meta,facts,is_source),"+
            "practitioner_offers(lang,sort,title,price,subtitle,bullets,kind,image_url),"+
            "practitioner_sections(lang,sort,title,body,items)"

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
        // Filter to the caller's own links. A practitioner who also uses the app
        // can read every link where SHE is the practitioner (RLS "practitioner
        // reads own links"), and without this filter each of her clients showed
        // up as a card she could not stop sharing (Jordy, 10-01).
        val uid = JwtUtils.extractUserIdFromAccessToken(accessToken) ?: return emptyList()
        val url = "$baseUrl/rest/v1/practitioner_clients?user_id=eq.$uid&" +
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

    /** Every goal that is still on the client's plate: active and paused, never ended. */
    suspend fun myGoals(accessToken: String): List<GoalRow> {
        // Own goals only: a practitioner using the app also reads the goals she set.
        val uid = JwtUtils.extractUserIdFromAccessToken(accessToken) ?: return emptyList()
        val url = "$baseUrl/rest/v1/practitioner_goals?user_id=eq.$uid&status=neq.ended" +
            "&select=id,practitioner_id,user_id,kind,title,exercise_id,threshold_bpm,target_minutes," +
            "target_count,times_per_week,metric_key,direction,target_value,reminder_times,reminders_enabled,status,note,created_at,updated_at," +
            "practitioner_name" +
            "&order=created_at.asc"
        return client.get(url) {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
        }.body()
    }

    /** The metrics a goal can be set on (weather is not in it), in display order. */
    suspend fun goalMetrics(accessToken: String): List<GoalMetric> {
        val url = "$baseUrl/rest/v1/goal_metric_catalog" +
            "?select=key,label,grp,unit,default_direction,default_target,step,sort,scale&order=sort.asc"
        return client.get(url) {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
        }.requireOk("goalMetrics").body()
    }

    /** Per-day progress for one goal, both dates inclusive (ISO yyyy-MM-dd). */
    suspend fun goalProgress(accessToken: String, goalId: String, from: String, to: String): List<GoalProgressRow> {
        val body = buildJsonObject {
            put("p_goal_id", goalId); put("p_from", from); put("p_to", to)
        }
        return client.post("$baseUrl/rest/v1/rpc/goal_daily_progress") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
    }

    /**
     * Add to today's manual log for a goal. One row per (goal, day, source):
     * the current row is read first and the new totals upserted, so two taps
     * make two chin tucks rather than overwriting each other.
     */
    suspend fun logGoal(
        accessToken: String,
        goal: GoalRow,
        date: String,
        countDelta: Int = 0,
        minutesDelta: Double = 0.0,
    ): GoalLogRow {
        val existing: List<GoalLogRow> = client.get(
            "$baseUrl/rest/v1/practitioner_goal_logs?goal_id=eq.${goal.id}&date=eq.$date&source=eq.manual" +
                "&select=id,goal_id,user_id,date,count,minutes,source&limit=1"
        ) {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
        }.requireOk("logGoal read").body()
        val cur = existing.firstOrNull()
        val row = GoalLogRow(
            goal_id = goal.id,
            user_id = goal.user_id,
            date = date,
            count = ((cur?.count ?: 0) + countDelta).coerceAtLeast(0),
            minutes = ((cur?.minutes ?: 0.0) + minutesDelta).coerceAtLeast(0.0),
            source = "manual",
        )
        client.post("$baseUrl/rest/v1/practitioner_goal_logs?on_conflict=goal_id,date,source") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
            header("Prefer", "resolution=merge-duplicates,return=minimal")
            contentType(ContentType.Application.Json)
            setBody(row)
        }.requireOk("logGoal")
        return row
    }

    /** Mute or unmute a goal's reminder pushes. The server owns the schedule. */
    suspend fun setGoalReminders(accessToken: String, goalId: String, enabled: Boolean) {
        client.post("$baseUrl/rest/v1/rpc/set_goal_reminders") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("goal_id", goalId); put("enabled", enabled) })
        }.requireOk("setGoalReminders")
    }

    private fun JsonObjectBuilder.putDraft(d: GoalDraft) {
        put("kind", d.kind)
        put("title", d.title)
        if (d.exerciseId != null) put("exercise_id", d.exerciseId) else put("exercise_id", JsonNull)
        if (d.thresholdBpm != null) put("threshold_bpm", d.thresholdBpm) else put("threshold_bpm", JsonNull)
        if (d.targetMinutes != null) put("target_minutes", d.targetMinutes) else put("target_minutes", JsonNull)
        if (d.targetCount != null) put("target_count", d.targetCount) else put("target_count", JsonNull)
        if (d.timesPerWeek != null) put("times_per_week", d.timesPerWeek) else put("times_per_week", JsonNull)
        if (d.metricKey != null) put("metric_key", d.metricKey) else put("metric_key", JsonNull)
        if (d.direction != null) put("direction", d.direction) else put("direction", JsonNull)
        if (d.targetValue != null) put("target_value", d.targetValue) else put("target_value", JsonNull)
        putJsonArray("reminder_times") { d.reminderTimes.forEach { add(it) } }
    }

    private suspend fun HttpResponse.requireOk(what: String): HttpResponse {
        if (!status.isSuccess()) error("$what failed: ${status.value} ${runCatching { bodyAsText() }.getOrDefault("")}")
        return this
    }

    /** Add one of the client's own goals (practitioner_id NULL). */
    suspend fun insertGoal(accessToken: String, draft: GoalDraft): GoalRow? {
        val uid = JwtUtils.extractUserIdFromAccessToken(accessToken) ?: error("no user id in token")
        val body = buildJsonObject {
            put("user_id", uid)
            put("practitioner_id", JsonNull)
            putDraft(draft)
            put("status", "active")
            put("reminders_enabled", true)
        }
        val rows: List<GoalRow> = client.post("$baseUrl/rest/v1/practitioner_goals") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(body)
        }.requireOk("insertGoal").body()
        return rows.firstOrNull()
    }

    /** Change one of the client's own goals. RLS refuses practitioner-set rows. */
    suspend fun updateGoal(accessToken: String, goalId: String, draft: GoalDraft) {
        val body = buildJsonObject {
            putDraft(draft)
            put("updated_at", nowIso())
        }
        client.patch("$baseUrl/rest/v1/practitioner_goals?id=eq.$goalId") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(body)
        }.requireOk("updateGoal")
    }

    /** End one of the client's own goals: deleted, not kept as ended. */
    suspend fun deleteGoal(accessToken: String, goalId: String) {
        client.delete("$baseUrl/rest/v1/practitioner_goals?id=eq.$goalId") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
        }.requireOk("deleteGoal")
    }

    /** Phone-side heart rate stretches, one row per (day, threshold). */
    suspend fun upsertHrThresholdDaily(accessToken: String, rows: List<HrThresholdDailyRow>) {
        if (rows.isEmpty()) return
        client.post("$baseUrl/rest/v1/hr_threshold_daily?on_conflict=user_id,date,threshold_bpm,source") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
            header("Prefer", "resolution=merge-duplicates,return=minimal")
            contentType(ContentType.Application.Json)
            setBody(rows)
        }
    }

    /** Phone-side mindfulness sessions from Health Connect, one row per day. */
    suspend fun upsertMindfulnessDaily(accessToken: String, rows: List<MindfulnessDailyRow>) {
        if (rows.isEmpty()) return
        client.post("$baseUrl/rest/v1/mindfulness_daily?on_conflict=user_id,date,source") {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
            header("Prefer", "resolution=merge-duplicates,return=minimal")
            contentType(ContentType.Application.Json)
            setBody(rows)
        }
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
            // "pending", not "active": she has to accept before anything is
            // readable. practitioner_can_read gates on "active", so until she
            // answers this row grants nothing.
            put("status", "pending")
            put("initiated_by", "client")
            putJsonArray("scopes") { scopes.forEach { add(it.key) } }
            putJsonArray("requested_scopes") { scopes.forEach { add(it.key) } }
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
