package com.migraineme

import android.content.Context
import android.util.Log
import java.time.LocalDate

object PredictedMenstruationHelper {

    private const val TAG = "PredictedMenstruation"

    suspend fun ensureExists(context: Context) {
        try {
            val appContext = context.applicationContext
            val accessToken = SessionStore.getValidAccessToken(appContext) ?: return

            val db = SupabaseDbService(
                BuildConfig.SUPABASE_URL,
                BuildConfig.SUPABASE_ANON_KEY
            )

            // Check if predicted trigger already exists
            val allTriggers = db.getAllTriggers(accessToken)
            val existing = allTriggers.firstOrNull { trigger ->
                trigger.type == "menstruation_predicted" &&
                        trigger.source == "system"
            }

            if (existing != null) {
                Log.d(TAG, "Predicted trigger already exists")
                return
            }

            // Get menstruation settings
            val menstruationService = SupabaseMenstruationService(appContext)
            val settings = menstruationService.getSettings(accessToken) ?: run {
                // Create default settings if they don't exist
                menstruationService.updateSettings(
                    accessToken = accessToken,
                    lastMenstruationDate = null,
                    avgCycleLength = 28,
                    autoUpdateAverage = true
                )
                MenstruationSettings(
                    lastMenstruationDate = null,
                    avgCycleLength = 28,
                    autoUpdateAverage = true
                )
            }

            // Get last real menstruation trigger
            val lastPeriod = allTriggers
                .filter { it.type == "menstruation" && it.source != "system" }
                .maxByOrNull { it.startAt }

            // Calculate predicted date
            val predictedDate = if (lastPeriod != null) {
                val lastDate = LocalDate.parse(lastPeriod.startAt.substring(0, 10))
                lastDate.plusDays(settings.avgCycleLength.toLong())
            } else if (settings.lastMenstruationDate != null) {
                settings.lastMenstruationDate.plusDays(settings.avgCycleLength.toLong())
            } else {
                LocalDate.now().plusDays(settings.avgCycleLength.toLong())
            }

            // Create predicted trigger using insertTrigger
            db.insertTrigger(
                accessToken = accessToken,
                migraineId = null,
                type = "menstruation_predicted",
                startAt = "${predictedDate}T09:00:00Z",
                notes = "Predicted menstruation"
            )

            Log.d(TAG, "Created predicted trigger for date: $predictedDate")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure predicted trigger exists", e)
        }
    }

    suspend fun delete(context: Context) {
        try {
            val appContext = context.applicationContext
            val accessToken = SessionStore.getValidAccessToken(appContext) ?: return

            val db = SupabaseDbService(
                BuildConfig.SUPABASE_URL,
                BuildConfig.SUPABASE_ANON_KEY
            )

            // Find predicted trigger
            val allTriggers = db.getAllTriggers(accessToken)
            val predicted = allTriggers.firstOrNull { trigger ->
                trigger.type == "menstruation_predicted" &&
                        trigger.source == "system"
            }

            if (predicted == null) {
                Log.d(TAG, "No predicted trigger found")
                return
            }

            // Update to inactive using updateTrigger
            db.updateTrigger(
                accessToken = accessToken,
                id = predicted.id,
                type = predicted.type,
                startAt = predicted.startAt,
                notes = predicted.notes,
                migraineId = predicted.migraineId
            )

            Log.d(TAG, "Deactivated predicted trigger")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete predicted trigger", e)
        }
    }
}