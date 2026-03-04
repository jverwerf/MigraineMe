package com.migraineme

import android.content.Context
import android.util.Log
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

            val lastPeriod = allTriggers
                .filter { it.type == "menstruation" && it.source != "system" }
                .maxByOrNull { it.startAt }

            val predictedDate: LocalDate = if (lastPeriod != null) {
                val lastDate = LocalDate.parse(lastPeriod.startAt.substring(0, 10))
                lastDate.plusDays(settings.avgCycleLength.toLong())
            } else if (settings.lastMenstruationDate != null) {
                settings.lastMenstruationDate.plusDays(settings.avgCycleLength.toLong())
            } else {
                LocalDate.now().plusDays(settings.avgCycleLength.toLong())
            }

            // Delete existing predicted triggers
            val existingPredicted = allTriggers.filter { trigger ->
                trigger.type == "menstruation_predicted" && trigger.source == "system"
            }
            for (old in existingPredicted) {
                try {
                    db.deleteTrigger(accessToken, old.id)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete old predicted trigger ${old.id}: ${e.message}")
                }
            }

            // Create single predicted trigger on the predicted date
            db.insertTrigger(
                accessToken = accessToken,
                migraineId = null,
                type = "menstruation_predicted",
                startAt = "${predictedDate}T09:00:00Z",
                notes = "Predicted menstruation"
            )

            Log.d(TAG, "Created predicted trigger for $predictedDate")
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
            val predicted = allTriggers.filter { trigger ->
                trigger.type == "menstruation_predicted" && trigger.source == "system"
            }

            for (trigger in predicted) {
                try { db.deleteTrigger(accessToken, trigger.id) }
                catch (e: Exception) { Log.w(TAG, "Failed to delete predicted trigger ${trigger.id}: ${e.message}") }
            }

            Log.d(TAG, "Deleted ${predicted.size} predicted trigger(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete predicted triggers", e)
        }
    }
}
