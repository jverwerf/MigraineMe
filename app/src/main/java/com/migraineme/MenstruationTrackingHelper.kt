package com.migraineme

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

object MenstruationTrackingHelper {

    private const val TAG = "MenstruationHelper"

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
            if (ok) MetricToggleHelper.toggle(context.applicationContext, "menstruation", true)
            ok
        }.getOrElse {
            Log.e(TAG, "enableMetricOnly failed: ${it.message}", it)
            false
        }
    }

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
        }.onFailure { Log.e(TAG, "disableTracking failed: ${it.message}", it) }.isSuccess
    }

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
            service.updateSettings(accessToken, lastDate, avgCycle, autoUpdate)

            ensureManualMenstruationTrigger(accessToken, lastDate)
            ensurePredictedTriggerPoolEntry(context.applicationContext, accessToken)

            PredictedMenstruationHelper.ensureExists(context.applicationContext)
            MetricToggleHelper.toggle(context.applicationContext, "menstruation", true)
            MenstruationSyncScheduler.schedule(context.applicationContext)

            val edge = EdgeFunctionsService()
            edge.seedDefaultRiskDecayWeights(context.applicationContext)
            edge.seedDefaultMenstruationDecayWeights(context.applicationContext)
            edge.upsertMetricSetting(
                context = context.applicationContext,
                metric = "menstruation",
                enabled = true,
                preferredSource = preferredSource
            )
        }.onFailure { Log.e(TAG, "saveSettingsAndEnableTracking failed: ${it.message}", it) }
            .getOrDefault(false)
    }

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
            service.updateSettings(accessToken, lastDate, avgCycle, autoUpdate)

            ensureManualMenstruationTrigger(accessToken, lastDate)
            ensurePredictedTriggerPoolEntry(context.applicationContext, accessToken)
            PredictedMenstruationHelper.ensureExists(context.applicationContext)

            // recalc-risk-scores edge fn early-returns "no_decay_config" if either
            // risk_decay_weights or menstruation_decay_weights is missing for the user.
            // MetricsSyncManager seeds risk_decay_weights at login but it can fail
            // silently — re-seed both here (idempotent upserts) so the recalc has
            // everything it needs the first time the user touches menstruation.
            val edge = EdgeFunctionsService()
            edge.seedDefaultRiskDecayWeights(context.applicationContext)
            edge.seedDefaultMenstruationDecayWeights(context.applicationContext)

            true
        }.onFailure { Log.e(TAG, "updateSettingsOnly failed: ${it.message}", it) }
            .getOrDefault(false)
    }

    /**
     * Ensure user_triggers pool entries exist for both "Menstruation" (manual
     * triggers) and "menstruation_predicted" (system-generated predicted
     * triggers) so the recalc edge function can look up severities for gauge
     * scoring. Without the plain "Menstruation" entry, triggerSevMap returns
     * NONE for the type and the manual menstruation contributes 0.
     */
    private suspend fun ensurePredictedTriggerPoolEntry(
        appContext: Context,
        accessToken: String
    ) {
        try {
            val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
            val triggerPool = db.getAllTriggerPool(accessToken)

            val menstruationEntry = triggerPool.firstOrNull {
                it.label.equals("Menstruation", ignoreCase = true)
            }
            val severity = menstruationEntry?.predictionValue?.takeIf { it != "NONE" } ?: "MILD"

            if (menstruationEntry == null) {
                db.upsertTriggerToPool(
                    accessToken = accessToken,
                    label = "Menstruation",
                    category = "Menstrual Cycle",
                    predictionValue = severity
                )
                Log.d(TAG, "Created Menstruation pool entry with severity=$severity")
            }

            db.upsertTriggerToPool(
                accessToken = accessToken,
                label = "menstruation_predicted",
                category = "Menstrual Cycle",
                predictionValue = severity
            )

            Log.d(TAG, "Ensured menstruation_predicted pool entry with severity=$severity")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to ensure menstruation pool entries: ${e.message}")
        }
    }

    private suspend fun ensureManualMenstruationTrigger(
        accessToken: String,
        lastDate: LocalDate?
    ) {
        if (lastDate == null) return

        val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
        val day = lastDate.toString()
        val alreadyLogged = db.getAllTriggers(accessToken).any { t ->
            t.type == "menstruation" &&
                    (t.source ?: "manual") == "manual" &&
                    t.startAt.startsWith(day)
        }

        if (alreadyLogged) return

        db.insertTrigger(
            accessToken = accessToken,
            migraineId = null,
            type = "menstruation",
            startAt = "${day}T09:00:00Z",
            notes = "Logged via menstruation settings"
        )
    }
}
