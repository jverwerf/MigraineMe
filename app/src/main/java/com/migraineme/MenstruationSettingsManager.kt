package com.migraineme

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

class MenstruationSettingsManager(private val context: Context) {

    private val service = SupabaseMenstruationService(context)
    private val edge = EdgeFunctionsService()

    suspend fun loadSettings(): Result<MenstruationSettings?> {
        return withContext(Dispatchers.IO) {
            try {
                val token = SessionStore.getValidAccessToken(context)
                if (token == null) {
                    Log.w(TAG, "No access token")
                    return@withContext Result.success(null)
                }

                val settings = service.getSettings(token)
                Log.d(TAG, "Loaded: $settings")
                Result.success(settings)
            } catch (e: Exception) {
                Log.e(TAG, "Load failed: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    suspend fun updateSettings(
        lastDate: LocalDate?,
        avgCycle: Int,
        autoUpdate: Boolean
    ): Result<MenstruationSettings> {
        return withContext(Dispatchers.IO) {
            try {
                val token = SessionStore.getValidAccessToken(context)
                    ?: return@withContext Result.failure(Exception("No access token"))

                service.updateSettings(
                    accessToken = token,
                    lastMenstruationDate = lastDate,
                    avgCycleLength = avgCycle,
                    autoUpdateAverage = autoUpdate
                )

                edge.upsertMetricSetting(
                    context = context,
                    metric = "menstruation",
                    enabled = true,
                    preferredSource = null
                )

                MetricToggleHelper.toggle(context, "menstruation", true)

                Log.d(TAG, "Updated successfully")
                Result.success(MenstruationSettings(lastDate, avgCycle, autoUpdate))
            } catch (e: Exception) {
                Log.e(TAG, "Update failed: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    companion object {
        private const val TAG = "MenstruationManager"
    }
}