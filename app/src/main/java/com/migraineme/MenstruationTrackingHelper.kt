package com.migraineme

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Centralized helper for menstruation tracking:
 * - Writes menstruation_settings via SupabaseMenstruationService
 * - Enables/disables metric_settings via EdgeFunctionsService
 * - Manages local helpers (PredictedMenstruationHelper, MetricToggleHelper, MenstruationSyncScheduler)
 *
 * UI (dialogs, toasts) stays in the calling screens.
 */
object MenstruationTrackingHelper {

    /**
     * Enable menstruation metric in Supabase + local toggle/scheduler WITHOUT writing menstruation_settings.
     * Useful when Health Connect permission is granted and you want to show setup dialog next.
     */
    suspend fun enableMetricOnly(
        context: Context,
        preferredSource: String?
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val edge = EdgeFunctionsService()
            val ok = edge.upsertMetricSetting(
                context = context.applicationContext,
                metric = "menstruation",
                enabled = true,
                preferredSource = preferredSource
            )
            if (ok) {
                MetricToggleHelper.toggle(context.applicationContext, "menstruation", true)
            }
            ok
        }.getOrElse {
            android.util.Log.e("MenstruationHelper", "enableMetricOnly failed: ${it.message}", it)
            false
        }
    }

    /**
     * Disable menstruation metric in Supabase + local toggle/scheduler cleanup.
     */
    suspend fun disableTracking(
        context: Context,
        preferredSource: String?
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            PredictedMenstruationHelper.delete(context.applicationContext)
            MetricToggleHelper.toggle(context.applicationContext, "menstruation", false)

            val edge = EdgeFunctionsService()
            edge.upsertMetricSetting(
                context = context.applicationContext,
                metric = "menstruation",
                enabled = false,
                preferredSource = preferredSource
            )
        }.onFailure {
            android.util.Log.e("MenstruationHelper", "disableTracking failed: ${it.message}", it)
        }.isSuccess
    }

    /**
     * Save menstruation_settings to Supabase and fully enable tracking (Supabase + local).
     */
    suspend fun saveSettingsAndEnableTracking(
        context: Context,
        lastDate: LocalDate?,
        avgCycle: Int,
        autoUpdate: Boolean,
        preferredSource: String?
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val accessToken = SessionStore.getValidAccessToken(context.applicationContext)
                ?: return@withContext false

            val service = SupabaseMenstruationService(context.applicationContext)
            service.updateSettings(
                accessToken = accessToken,
                lastMenstruationDate = lastDate,
                avgCycleLength = avgCycle,
                autoUpdateAverage = autoUpdate
            )

            PredictedMenstruationHelper.ensureExists(context.applicationContext)
            MetricToggleHelper.toggle(context.applicationContext, "menstruation", true)
            MenstruationSyncScheduler.schedule(context.applicationContext)

            val edge = EdgeFunctionsService()
            edge.upsertMetricSetting(
                context = context.applicationContext,
                metric = "menstruation",
                enabled = true,
                preferredSource = preferredSource
            )
        }.onFailure {
            android.util.Log.e("MenstruationHelper", "saveSettingsAndEnableTracking failed: ${it.message}", it)
        }.getOrDefault(false)
    }

    /**
     * Update menstruation_settings only (no metric toggling).
     */
    suspend fun updateSettingsOnly(
        context: Context,
        lastDate: LocalDate?,
        avgCycle: Int,
        autoUpdate: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val accessToken = SessionStore.getValidAccessToken(context.applicationContext)
                ?: return@withContext false

            val service = SupabaseMenstruationService(context.applicationContext)
            service.updateSettings(
                accessToken = accessToken,
                lastMenstruationDate = lastDate,
                avgCycleLength = avgCycle,
                autoUpdateAverage = autoUpdate
            )
            true
        }.onFailure {
            android.util.Log.e("MenstruationHelper", "updateSettingsOnly failed: ${it.message}", it)
        }.getOrDefault(false)
    }
}
