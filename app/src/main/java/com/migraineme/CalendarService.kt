package com.migraineme

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads the user's calendar (all calendars) for the window [today-3, today+6]
 * and ships every event to the `sync-calendar-events` edge function. The edge
 * function classifies each event against the user's activity / relief / trigger
 * pools, auto-inserts the high-confidence matches into the corresponding log
 * tables, and returns the queue the check-in screen should ask about.
 */
object CalendarService {

    private const val TAG = "CalendarService"

    fun hasReadPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    // ── Wire types ───────────────────────────────────────────────────

    @Serializable
    data class CalendarEvent(
        @SerialName("event_id") val eventId: String,
        val title: String,
        @SerialName("start_at") val startAt: String,
        @SerialName("end_at") val endAt: String? = null,
        @SerialName("all_day") val allDay: Boolean = false,
        @SerialName("calendar_name") val calendarName: String? = null,
    )

    @Serializable
    data class Mapping(
        @SerialName("event_id") val eventId: String,
        val title: String? = null,
        @SerialName("start_at") val startAt: String? = null,
        @SerialName("end_at") val endAt: String? = null,
        @SerialName("all_day") val allDay: Boolean? = null,
        @SerialName("target_type") val targetType: String? = null,
        @SerialName("target_id") val targetId: String? = null,
        @SerialName("target_label") val targetLabel: String? = null,
        val confidence: Double? = null,
        val importance: String? = null,
        @SerialName("is_new") val isNew: Boolean? = null,
        val decision: String? = null,
        @SerialName("auto_inserted") val autoInserted: Boolean? = null,
        @SerialName("inserted_log_id") val insertedLogId: String? = null,
        val action: String? = null,
    ) {
        /// Composite identity — one calendar event can map to multiple
        /// categories (activity + trigger), so eventId alone isn't unique.
        val compositeKey: String get() = "$eventId|${targetType.orEmpty()}"
    }

    @Serializable
    private data class SyncRequest(val events: List<CalendarEvent>)

    @Serializable
    private data class SyncResponse(val mappings: List<Mapping> = emptyList())

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun buildClient(): HttpClient =
        HttpClient(Android) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                    encodeDefaults = true
                })
            }
        }

    // ── Read calendar ────────────────────────────────────────────────

    fun readEvents(context: Context, daysBack: Int = 3, daysForward: Int = 7): List<CalendarEvent> {
        if (!hasReadPermission(context)) return emptyList()
        val zone = ZoneId.systemDefault()
        val startMillis = LocalDate.now().minusDays(daysBack.toLong())
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = LocalDate.now().plusDays(daysForward.toLong())
            .atStartOfDay(zone).toInstant().toEpochMilli()

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME,
            CalendarContract.Events.DELETED,
            CalendarContract.Events.STATUS,
        )

        val selection = "${CalendarContract.Events.DTSTART} >= ? AND " +
            "${CalendarContract.Events.DTSTART} < ? AND " +
            "${CalendarContract.Events.DELETED} = 0"
        val args = arrayOf(startMillis.toString(), endMillis.toString())

        val out = mutableListOf<CalendarEvent>()
        try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI, projection, selection, args, null
            )?.use { cur ->
                val idIdx = cur.getColumnIndexOrThrow(CalendarContract.Events._ID)
                val titleIdx = cur.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                val dtStartIdx = cur.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
                val dtEndIdx = cur.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
                val allDayIdx = cur.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)
                val calNameIdx = cur.getColumnIndexOrThrow(CalendarContract.Events.CALENDAR_DISPLAY_NAME)
                val statusIdx = cur.getColumnIndex(CalendarContract.Events.STATUS)
                while (cur.moveToNext()) {
                    val status = if (statusIdx >= 0) cur.getInt(statusIdx) else 0
                    if (status == CalendarContract.Events.STATUS_CANCELED) continue
                    val title = cur.getString(titleIdx)?.trim().orEmpty()
                    if (title.isEmpty()) continue
                    val id = cur.getLong(idIdx)
                    val dtStart = cur.getLong(dtStartIdx)
                    val dtEnd = if (!cur.isNull(dtEndIdx)) cur.getLong(dtEndIdx) else 0L
                    val allDay = cur.getInt(allDayIdx) == 1
                    val calName = cur.getString(calNameIdx)

                    out.add(
                        CalendarEvent(
                            eventId = id.toString(),
                            title = title,
                            startAt = Instant.ofEpochMilli(dtStart).toString(),
                            endAt = if (dtEnd > 0L) Instant.ofEpochMilli(dtEnd).toString() else null,
                            allDay = allDay,
                            calendarName = calName,
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CALENDAR denied: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "readEvents failed: ${e.message}", e)
        }
        return out
    }

    // ── Sync with edge function ──────────────────────────────────────

    /** Fetches the window, posts to the edge function, returns mappings. */
    suspend fun syncWindow(context: Context): List<Mapping> {
        val appCtx = context.applicationContext
        val token = SessionStore.getValidAccessToken(appCtx) ?: return emptyList()
        val events = readEvents(appCtx)
        if (events.isEmpty()) return emptyList()

        val client = buildClient()
        return try {
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/sync-calendar-events"
            val res = client.post(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(SyncRequest(events))
            }
            if (!res.status.isSuccess()) {
                Log.e(TAG, "sync-calendar-events ${res.status}: ${res.bodyAsText().take(300)}")
                return emptyList()
            }
            res.body<SyncResponse>().mappings
        } catch (e: Exception) {
            Log.e(TAG, "syncWindow failed: ${e.message}", e)
            emptyList()
        } finally {
            client.close()
        }
    }

    /** Items the check-in's calendar page should surface — every classified
     *  activity / relief / trigger from the window. The edge function has
     *  already auto-saved them; the UI presents an Undo per row. */
    fun reviewQueue(mappings: List<Mapping>): List<Mapping> =
        mappings.filter { m ->
            val type = m.targetType ?: ""
            if (type.isEmpty() || type == "skip") return@filter false
            if (m.decision == "user_skipped") return@filter false
            true
        }

    // ── User decisions ───────────────────────────────────────────────

    suspend fun confirmAsExisting(
        context: Context,
        mapping: Mapping,
        targetType: String,
        targetId: String,
        targetLabel: String,
    ): Boolean {
        val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return false
        return insertLog(context, token, mapping, targetType, targetLabel)
            && patchMapping(
                context, token, mapping.eventId, mapping.targetType,
                buildJsonObject {
                    put("target_type", targetType)
                    put("target_id", targetId)
                    put("target_label", targetLabel)
                    put("decision", "user_confirmed")
                    put("auto_inserted", true)
                }
            )
    }

    suspend fun confirmAsNew(
        context: Context,
        mapping: Mapping,
        targetType: String,
        newLabel: String,
        category: String?,
        iconKey: String?,
    ): Boolean {
        val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return false
        val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
        try {
            when (targetType) {
                "activity" -> db.upsertActivityToPool(token, newLabel, category)
                "relief" -> db.upsertReliefToPool(token, newLabel, category)
                "trigger" -> db.upsertTriggerToPool(token, newLabel, category)
                else -> return false
            }
            if (iconKey != null) {
                // best-effort icon set via PATCH on the pool row
                val table = when (targetType) {
                    "activity" -> "user_activities"
                    "relief" -> "user_reliefs"
                    "trigger" -> "user_triggers"
                    else -> null
                }
                if (table != null) patchPoolIcon(context, token, table, newLabel, iconKey)
            }
        } catch (e: Exception) {
            Log.e(TAG, "addToPool failed: ${e.message}", e)
            return false
        }
        return insertLog(context, token, mapping, targetType, newLabel)
            && patchMapping(
                context, token, mapping.eventId, mapping.targetType,
                buildJsonObject {
                    put("target_type", targetType)
                    put("target_label", newLabel)
                    put("decision", "user_confirmed")
                    put("auto_inserted", true)
                }
            )
    }

    /** One-shot undo: delete the log row and reset auto_inserted on the
     *  mapping, but keep the title cache untouched. Future events with the
     *  same title may still be suggested by GPT on the next sync. */
    suspend fun undoOnce(context: Context, mapping: Mapping): Boolean {
        val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return false
        mapping.insertedLogId?.let { id ->
            val table = when (mapping.targetType) {
                "activity" -> "activities"
                "relief" -> "reliefs"
                "trigger" -> "triggers"
                else -> null
            }
            if (table != null) {
                runCatching { deleteLogRow(context, token, table, id) }
            }
        }
        val ok = patchMapping(
            context, token, mapping.eventId, mapping.targetType,
            buildJsonObject {
                put("auto_inserted", false)
                put("inserted_log_id", JsonNull)
            }
        )
        if (ok) {
            runCatching { EdgeFunctionsService().triggerRecalcRiskScores(context) }
        }
        return ok
    }

    /** Permanent skip: delete the log row AND cache decision=user_skipped so
     *  the same title (and re-syncs of the same event) never resurface.
     *  Visible/editable in ManageCalendarSkipsScreen. */
    suspend fun neverSuggestAgain(context: Context, mapping: Mapping): Boolean {
        val token = SessionStore.getValidAccessToken(context.applicationContext) ?: return false
        mapping.insertedLogId?.let { id ->
            val table = when (mapping.targetType) {
                "activity" -> "activities"
                "relief" -> "reliefs"
                "trigger" -> "triggers"
                else -> null
            }
            if (table != null) {
                runCatching { deleteLogRow(context, token, table, id) }
            }
        }
        val ok = patchMapping(
            context, token, mapping.eventId, mapping.targetType,
            buildJsonObject {
                put("decision", "user_skipped")
                put("target_type", "skip")
                put("auto_inserted", false)
                put("inserted_log_id", JsonNull)
            }
        )
        if (ok) {
            runCatching { EdgeFunctionsService().triggerRecalcRiskScores(context) }
        }
        return ok
    }

    /** Back-compat shim — keep `skip` as an alias for the permanent variant
     *  so EveningCheckInScreen.kt (and other call sites) keep compiling. */
    suspend fun skip(context: Context, mapping: Mapping): Boolean =
        neverSuggestAgain(context, mapping)

    private suspend fun deleteLogRow(context: Context, token: String, table: String, id: String) {
        val client = buildClient()
        try {
            val userId = SessionStore.readUserId(context.applicationContext) ?: return
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/$table" +
                "?id=eq.$id&user_id=eq.$userId"
            client.delete(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $token")
                header("Prefer", "return=minimal")
            }
        } finally {
            client.close()
        }
    }

    // ── Internal helpers ────────────────────────────────────────────

    private suspend fun insertLog(
        context: Context, token: String, mapping: Mapping,
        targetType: String, targetLabel: String
    ): Boolean {
        val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
        val startAt = mapping.startAt ?: Instant.now().toString()
        val endAt = mapping.endAt
        val notes = mapping.title ?: mapping.targetLabel
        return try {
            when (targetType) {
                "activity" -> insertActivityRow(context, token, mapping, targetLabel)
                "relief" -> {
                    db.insertRelief(
                        accessToken = token, migraineId = null, type = targetLabel,
                        startAt = startAt, notes = notes, endAt = endAt,
                        reliefScale = "NONE", sideEffectScale = "NONE", sideEffectNotes = null
                    )
                    true
                }
                "trigger" -> {
                    db.insertTrigger(
                        accessToken = token, migraineId = null, type = targetLabel,
                        startAt = startAt, notes = notes
                    )
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "insertLog ($targetType) failed: ${e.message}", e)
            false
        }
    }

    /** Direct insert into public.activities — the consolidated table that
     *  carries source/source_measure_id/duration_minutes for idempotent upserts.
     *  Bypasses SupabaseDbService.insertActivity which still targets the old
     *  HR-zones table. */
    private suspend fun insertActivityRow(
        context: Context, token: String, mapping: Mapping, targetLabel: String
    ): Boolean {
        val client = buildClient()
        return try {
            val startAt = mapping.startAt ?: Instant.now().toString()
            val endAt = mapping.endAt
            val durationMin: Int? = if (endAt != null) {
                try {
                    val ms = Instant.parse(endAt).toEpochMilli() - Instant.parse(startAt).toEpochMilli()
                    (ms / 60_000L).toInt().coerceAtLeast(0)
                } catch (_: Exception) { null }
            } else null
            val body = buildJsonObject {
                put("type", targetLabel)
                put("source", "calendar")
                put("source_measure_id", mapping.eventId)
                put("start_at", startAt)
                if (endAt != null) put("end_at", endAt)
                if (durationMin != null) put("duration_minutes", durationMin)
                mapping.title?.let { put("notes", it) }
            }
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/activities" +
                "?on_conflict=user_id,source,source_measure_id"
            val res = client.post(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $token")
                header("Prefer", "resolution=merge-duplicates,return=minimal")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (!res.status.isSuccess()) {
                Log.e(TAG, "insert activity failed: ${res.status} ${res.bodyAsText().take(300)}")
                false
            } else true
        } catch (e: Exception) {
            Log.e(TAG, "insert activity threw: ${e.message}", e)
            false
        } finally {
            client.close()
        }
    }

    private suspend fun patchMapping(
        context: Context, token: String, eventId: String, targetType: String?, fields: JsonElement
    ): Boolean {
        val client = buildClient()
        return try {
            val userId = SessionStore.readUserId(context.applicationContext) ?: return false
            val typeFilter = if (!targetType.isNullOrEmpty()) "&target_type=eq.$targetType" else ""
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/calendar_event_mappings" +
                "?event_id=eq.$eventId&user_id=eq.$userId$typeFilter"
            val res = client.patch(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $token")
                header("Prefer", "return=minimal")
                contentType(ContentType.Application.Json)
                setBody(fields)
            }
            if (!res.status.isSuccess()) {
                Log.e(TAG, "patch mapping ${res.status}: ${res.bodyAsText().take(300)}")
                false
            } else true
        } catch (e: Exception) {
            Log.e(TAG, "patch mapping threw: ${e.message}", e)
            false
        } finally {
            client.close()
        }
    }

    private suspend fun patchPoolIcon(
        context: Context, token: String, table: String, label: String, iconKey: String
    ) {
        val client = buildClient()
        try {
            val userId = SessionStore.readUserId(context.applicationContext) ?: return
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/$table" +
                "?user_id=eq.$userId&label=eq.$label"
            val body = buildJsonObject { put("icon_key", iconKey) }
            client.patch(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $token")
                header("Prefer", "return=minimal")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (_: Exception) {
        } finally {
            client.close()
        }
    }
}
