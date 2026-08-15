package com.migraineme

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/**
 * Manages the single system-generated menstruation_predicted trigger.
 *
 * Places ONE trigger on the predicted period date. The edge function
 * applies the user's menstruation_decay_weights curve (day_m7…day_p7)
 * centered on that date, so the gauge ramps up before and tapers after.
 */
object PredictedMenstruationHelper {

    private const val TAG = "PredictedMenstruation"

    suspend fun ensureExists(context: Context) {
        try {
            val appContext = context.applicationContext
            val accessToken = SessionStore.getValidAccessToken(appContext) ?: return

            val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
            val allTriggers = db.getAllTriggers(accessToken)

            val menstruationService = SupabaseMenstruationService(appContext)
            val settings = menstruationService.getSettings(accessToken)
                ?: MenstruationSettings(null, 28, true)

            // Case-insensitive: a pool-logged period arrives as capital-M
            // "Menstruation" and counts as a period log too.
            val lastPeriod = allTriggers
                .filter { it.type.equals("menstruation", ignoreCase = true) && it.source != "system" }
                .maxByOrNull { it.startAt }

            val predictedDate: LocalDate = if (lastPeriod != null) {
                val lastDate = LocalDate.parse(lastPeriod.startAt.substring(0, 10))
                lastDate.plusDays(settings.avgCycleLength.toLong())
            } else if (settings.lastMenstruationDate != null) {
                settings.lastMenstruationDate.plusDays(settings.avgCycleLength.toLong())
            } else {
                LocalDate.now().plusDays(settings.avgCycleLength.toLong())
            }

            // Mirror the DB trigger's canonical-row logic: one predicted row,
            // source='system', on the target date; every other predicted row is
            // retired with active=false (DELETE is blocked server-side by
            // prevent_predicted_trigger_deletion).
            val targetDayPrefix = predictedDate.toString()
            val allPredicted = allTriggers.filter { it.type == "menstruation_predicted" }
            val onTarget = allPredicted.firstOrNull { it.startAt.startsWith(targetDayPrefix) }
            val wrongDate = allPredicted.filter { !it.startAt.startsWith(targetDayPrefix) && it.active }

            for (old in wrongDate) {
                try {
                    db.patchTrigger(accessToken, old.id, buildJsonObject { put("active", false) })
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to retire stale predicted trigger ${old.id}: ${e.message}")
                }
            }

            if (onTarget != null) {
                // Promote whatever sits on the target date to the canonical
                // system row (it may be a legacy 'manual' or retired one).
                if (onTarget.source != "system" || !onTarget.active) {
                    try {
                        db.patchTrigger(accessToken, onTarget.id, buildJsonObject {
                            put("source", "system")
                            put("active", true)
                            put("notes", "Predicted menstruation")
                        })
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to promote predicted trigger ${onTarget.id}: ${e.message}")
                    }
                }
                Log.d(TAG, "Predicted trigger already present for $predictedDate")
            } else {
                try {
                    db.insertTrigger(
                        accessToken = accessToken,
                        migraineId = null,
                        type = "menstruation_predicted",
                        startAt = "${predictedDate}T09:00:00Z",
                        notes = "Predicted menstruation",
                        source = "system"
                    )
                    Log.d(TAG, "Created predicted trigger for $predictedDate")
                } catch (e: Exception) {
                    // 23505 = the DB trigger raced us and inserted between the read and the
                    // insert. Safe to ignore — there's a row at the right date now.
                    if (e.message?.contains("23505") == true) {
                        Log.d(TAG, "Predicted trigger inserted concurrently for $predictedDate")
                    } else throw e
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure predicted trigger exists", e)
        }
    }

    suspend fun delete(context: Context) {
        try {
            val appContext = context.applicationContext
            val accessToken = SessionStore.getValidAccessToken(appContext) ?: return

            val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
            val allTriggers = db.getAllTriggers(accessToken)
            // Any source: legacy rows may carry 'manual'. DELETE is blocked by
            // the server guard, so retire with active=false instead.
            val predicted = allTriggers.filter { trigger ->
                trigger.type == "menstruation_predicted" && trigger.active
            }

            for (trigger in predicted) {
                try { db.patchTrigger(accessToken, trigger.id, buildJsonObject { put("active", false) }) }
                catch (e: Exception) { Log.w(TAG, "Failed to retire predicted trigger ${trigger.id}: ${e.message}") }
            }

            Log.d(TAG, "Retired ${predicted.size} predicted trigger(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete predicted triggers", e)
        }
    }
}
