package com.migraineme

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Phone-based sleep sync worker - triggered by FCM (hourly via sync_hourly).
 *
 * Estimates sleep from phone usage patterns (longest screen-off gap at night)
 * and writes to Supabase sleep tables with source = "phone".
 *
 * Only runs if:
 * - PACKAGE_USAGE_STATS permission is granted
 * - sleep_duration_daily metric is enabled with preferred_source or allowed_sources containing "phone"
 *
 * Writes to: sleep_duration_daily, fell_asleep_time_daily, woke_up_time_daily
 * (same tables as WHOOP/Health Connect, different source value)
 */
class PhoneSleepSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Worker started")

        try {
            // Check permission
            if (!ScreenTimeCollector.hasUsageStatsPermission(applicationContext)) {
                Log.d(TAG, "PACKAGE_USAGE_STATS permission not granted - skipping")
                return@withContext Result.success()
            }

            // Check if phone sleep is enabled in metric_settings
            if (!isPhoneSleepEnabled()) {
                Log.d(TAG, "Phone sleep not enabled in metric_settings - skipping")
                return@withContext Result.success()
            }

            // Get valid Supabase access token
            val access = SessionStore.getValidAccessToken(applicationContext)
            if (access.isNullOrBlank()) {
                Log.d(TAG, "No valid access token - skipping")
                return@withContext Result.success()
            }

            val svc = SupabasePersonalService(applicationContext)
            val today = LocalDate.now()
            val timezone = ZoneId.systemDefault().id

            // A sleep night for date D = 20:00 on D → 12:00 on D+1.
            // It is complete once we are past 12:00 on D+1, i.e. today >= D+2,
            // meaning we can finalize D = today - 2.
            val latestCompletable = today.minusDays(2)

            // Get latest finalized phone sleep date
            val latestStr = svc.latestPhoneSleepDate(access)
            val latestFinalized = latestStr?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

            val toFinalize = if (latestFinalized == null) {
                listOf(latestCompletable)
            } else {
                val daysSince = ChronoUnit.DAYS.between(latestFinalized, latestCompletable)
                when {
                    daysSince <= 0 -> emptyList()
                    daysSince <= 7 -> (1..daysSince).map { latestFinalized.plusDays(it) }
                    else -> listOf(latestCompletable)
                }
            }

            if (toFinalize.isEmpty()) {
                Log.d(TAG, "No nights to finalize")
                return@withContext Result.success()
            }

            Log.d(TAG, "Finalizing ${toFinalize.size} nights of phone sleep")

            var ok = 0
            var fail = 0

            toFinalize.forEach { date ->
                runCatching {
                    val dateStr = date.toString()
                    val sleep = PhoneSleepCollector.estimateSleep(applicationContext, dateStr)

                    if (sleep == null) {
                        Log.w(TAG, "No sleep estimate for $dateStr")
                        fail++
                        return@forEach
                    }

                    val durationHours = sleep.durationMinutes / 60.0

                    svc.upsertPhoneSleepData(
                        accessToken = access,
                        date = dateStr,
                        durationHours = durationHours,
                        fellAsleepIso = sleep.fellAsleepIso,
                        wokeUpIso = sleep.wokeUpIso,
                        timezone = timezone
                    )

                    Log.d(TAG, "Finalized phone sleep for $dateStr: ${String.format("%.1f", durationHours)}h (${sleep.fellAsleepIso} → ${sleep.wokeUpIso})")
                    ok++
                }.onFailure { e ->
                    fail++
                    Log.e(TAG, "Failed to finalize phone sleep for $date", e)
                }
            }

            Log.d(TAG, "Phone sleep finalization complete: $ok ok, $fail fail")
            Result.success()

        } catch (t: Throwable) {
            Log.e(TAG, "Worker error", t)
            Result.retry()
        }
    }

    /**
     * Check metric_settings to see if phone sleep is enabled.
     * Returns true if sleep_duration_daily has preferred_source="phone"
     * or allowed_sources contains "phone".
     */
    private suspend fun isPhoneSleepEnabled(): Boolean {
        return try {
            val edge = EdgeFunctionsService()
            val settings = edge.getMetricSettings(applicationContext)

            val sleepSetting = settings.find { it.metric == "sleep_duration_daily" }
            if (sleepSetting == null || !sleepSetting.enabled) return false

            val preferred = sleepSetting.preferredSource?.lowercase() ?: ""
            val allowed = sleepSetting.allowedSources?.map { it.lowercase() } ?: emptyList()

            preferred == "phone" || allowed.contains("phone")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check metric_settings: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "PhoneSleepSyncWorker"
        private const val UNIQUE_WORK = "phone_sleep_sync"

        fun runOnce(context: Context) {
            Log.d(TAG, "Enqueuing phone sleep sync")
            val req = OneTimeWorkRequestBuilder<PhoneSleepSyncWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK, ExistingWorkPolicy.REPLACE, req
            )
        }
    }
}