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
 * Screen time sync worker - triggered by FCM (hourly).
 *
 * Does THREE things each run:
 * 1. Updates screen_time_live with TODAY's running total (upserts same row)
 * 2. Finalizes any COMPLETED days (yesterday and before) to screen_time_daily
 * 3. Finalizes late-night screen time (22:00–06:00) for completed nights
 */
class ScreenTimeSyncWorker(
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

            // Get valid Supabase access token
            val access = SessionStore.getValidAccessToken(applicationContext)
            if (access.isNullOrBlank()) {
                Log.d(TAG, "No valid access token - skipping")
                return@withContext Result.success()
            }

            val svc = SupabasePersonalService(applicationContext)
            val today = LocalDate.now()
            val yesterday = today.minusDays(1)
            val timezone = ZoneId.systemDefault().id

            // ========== 1. UPDATE TODAY'S LIVE DATA ==========
            runCatching {
                val todayData = ScreenTimeCollector.getDailyScreenTime(applicationContext, today.toString())
                if (todayData != null) {
                    val totalHours = todayData.totalSeconds / 3600.0
                    svc.upsertScreenTimeLive(
                        accessToken = access,
                        date = today.toString(),
                        totalHours = totalHours,
                        appCount = todayData.appCount,
                        source = "android",
                        timezone = timezone
                    )
                    Log.d(TAG, "Updated live screen time for today: ${String.format("%.2f", totalHours)}h")
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed to update live screen time", e)
            }

            // ========== 2. FINALIZE COMPLETED DAYS ==========
            // Get the latest date in screen_time_daily
            val latestDailyStr = svc.latestScreenTimeDate(access, source = "android")
            val latestDaily = latestDailyStr?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

            // Determine which completed days need to be finalized
            val toFinalize = if (latestDaily == null) {
                // No daily data yet - finalize yesterday only
                listOf(yesterday)
            } else {
                val daysSince = ChronoUnit.DAYS.between(latestDaily, yesterday)
                when {
                    daysSince <= 0 -> emptyList() // Already up to date
                    daysSince <= 7 -> (1..daysSince).map { latestDaily.plusDays(it) }
                    else -> listOf(yesterday) // Gap too large, just do yesterday
                }
            }

            if (toFinalize.isNotEmpty()) {
                Log.d(TAG, "Finalizing ${toFinalize.size} days to screen_time_daily")

                var ok = 0
                var fail = 0

                toFinalize.forEach { date ->
                    runCatching {
                        val dateStr = date.toString()
                        val screenTime = ScreenTimeCollector.getDailyScreenTime(applicationContext, dateStr)

                        if (screenTime == null) {
                            Log.w(TAG, "No screen time data for $dateStr")
                            fail++
                            return@forEach
                        }

                        val totalHours = screenTime.totalSeconds / 3600.0
                        val flags = mapOf(
                            "app_count" to screenTime.appCount.toString(),
                            "source" to "android"
                        )

                        svc.upsertScreenTimeDaily(
                            accessToken = access,
                            date = dateStr,
                            totalHours = totalHours,
                            source = "android",
                            qualityFlags = flags
                        )

                        Log.d(TAG, "Finalized screen time for $dateStr: ${String.format("%.2f", totalHours)}h")
                        ok++
                    }.onFailure { e ->
                        fail++
                        Log.e(TAG, "Failed to finalize $date", e)
                    }
                }

                Log.d(TAG, "Finalized $ok of ${toFinalize.size} days (fail=$fail)")
            }

            // ========== 3. FINALIZE LATE-NIGHT SCREEN TIME ==========
            // A late-night window for date D = 22:00 on D → 06:00 on D+1.
            // It is complete once we are past 06:00 on D+1, i.e. today >= D+2,
            // meaning we can finalize D = today - 2 (yesterday's evening is not yet complete).
            runCatching {
                val latestLateNightStr = svc.latestScreenTimeLateNightDate(access, source = "android")
                val latestLateNight = latestLateNightStr?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

                // The most recent completable night is day-before-yesterday
                val latestCompletable = today.minusDays(2)

                val nightsToFinalize = if (latestLateNight == null) {
                    listOf(latestCompletable)
                } else {
                    val daysSince = ChronoUnit.DAYS.between(latestLateNight, latestCompletable)
                    when {
                        daysSince <= 0 -> emptyList()
                        daysSince <= 7 -> (1..daysSince).map { latestLateNight.plusDays(it) }
                        else -> listOf(latestCompletable)
                    }
                }

                var lnOk = 0
                var lnFail = 0

                nightsToFinalize.forEach { date ->
                    runCatching {
                        val dateStr = date.toString()
                        val lateNight = ScreenTimeCollector.getLateNightScreenTime(applicationContext, dateStr)

                        if (lateNight == null) {
                            Log.w(TAG, "No late-night screen time data for $dateStr")
                            lnFail++
                            return@forEach
                        }

                        val totalHours = lateNight.totalSeconds / 3600.0
                        svc.upsertScreenTimeLateNight(
                            accessToken = access,
                            date = dateStr,
                            totalHours = totalHours,
                            appCount = lateNight.appCount,
                            source = "android",
                            timezone = timezone
                        )

                        Log.d(TAG, "Finalized late-night screen time for $dateStr: ${String.format("%.2f", totalHours)}h")
                        lnOk++
                    }.onFailure { e ->
                        lnFail++
                        Log.e(TAG, "Failed to finalize late-night for $date", e)
                    }
                }

                if (nightsToFinalize.isNotEmpty()) {
                    Log.d(TAG, "Finalized late-night: $lnOk of ${nightsToFinalize.size} (fail=$lnFail)")
                }
            }.onFailure { e ->
                Log.e(TAG, "Failed late-night screen time finalization", e)
            }

            Result.success()

        } catch (t: Throwable) {
            Log.e(TAG, "Worker error", t)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ScreenTimeSyncWorker"
        private const val UNIQUE_WORK = "screen_time_sync"

        /**
         * Run once immediately - called by FCM handler.
         */
        fun runOnce(context: Context) {
            Log.d(TAG, "Enqueuing screen time sync")
            val req = OneTimeWorkRequestBuilder<ScreenTimeSyncWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK, ExistingWorkPolicy.REPLACE, req
            )
        }
    }
}