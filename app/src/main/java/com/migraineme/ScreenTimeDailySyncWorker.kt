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
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Daily worker that collects yesterday's screen time and uploads directly to screen_time_daily.
 * 
 * Runs daily at 10 AM (device time) and handles backfill for missing days.
 * Follows the same pattern as LocationDailySyncWorker.
 * 
 * Unlike ambient noise (which uploads samples then processes them), screen time is uploaded
 * directly to the daily table since Android already computed the daily total.
 */
class ScreenTimeDailySyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(LOG_TAG, "Worker started")
        
        var shouldScheduleNext = true

        try {
            // Check if user has granted PACKAGE_USAGE_STATS permission
            if (!ScreenTimeCollector.hasUsageStatsPermission(applicationContext)) {
                Log.d(LOG_TAG, "PACKAGE_USAGE_STATS permission not granted - skipping")
                // Don't retry - this is expected if user hasn't granted permission
                return@withContext Result.success()
            }

            // Get valid Supabase access token
            val access = SessionStore.getValidAccessToken(applicationContext)
            if (access.isNullOrBlank()) {
                Log.d(LOG_TAG, "No valid access token - skipping")
                return@withContext Result.success()
            }

            val svc = SupabasePersonalService(applicationContext)

            // Get the latest date we've already uploaded
            val latestDateStr = svc.latestScreenTimeDate(access, source = "android")
            val latestDate = latestDateStr?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

            val today = LocalDate.now()
            val yesterday = today.minusDays(1)

            // Determine which dates to upload
            val toWrite = if (latestDate == null) {
                // No data yet - upload yesterday only (don't backfill too far initially)
                listOf(yesterday)
            } else {
                // Backfill any missing days between latestDate and yesterday (up to 7 days)
                val daysSinceLatest = ChronoUnit.DAYS.between(latestDate, yesterday)
                when {
                    daysSinceLatest <= 0 -> {
                        // Already up to date
                        Log.d(LOG_TAG, "Already up to date (latest=$latestDate, yesterday=$yesterday)")
                        emptyList()
                    }
                    daysSinceLatest <= 7 -> {
                        // Backfill up to 7 days
                        (1..daysSinceLatest).map { latestDate.plusDays(it) }
                    }
                    else -> {
                        // Gap too large - just upload yesterday
                        Log.d(LOG_TAG, "Gap too large ($daysSinceLatest days) - uploading yesterday only")
                        listOf(yesterday)
                    }
                }
            }

            if (toWrite.isEmpty()) {
                Log.d(LOG_TAG, "Nothing to upload")
                return@withContext Result.success()
            }

            Log.d(LOG_TAG, "Uploading screen time for ${toWrite.size} days: ${toWrite.first()} to ${toWrite.last()}")

            var ok = 0
            var fail = 0

            toWrite.forEach { date ->
                runCatching {
                    val dateStr = date.toString()
                    
                    // Collect screen time for this date
                    val screenTime = ScreenTimeCollector.getDailyScreenTime(applicationContext, dateStr)
                    
                    if (screenTime == null) {
                        Log.w(LOG_TAG, "Failed to collect screen time for $dateStr")
                        fail++
                        return@forEach
                    }

                    // Convert seconds to hours
                    val totalHours = screenTime.totalSeconds / 3600.0

                    // Build quality flags
                    val flags = buildMap {
                        put("app_count", screenTime.appCount.toString())
                        put("source", "android")
                    }

                    // Upload directly to screen_time_daily (no intermediate processing)
                    svc.upsertScreenTimeDaily(
                        accessToken = access,
                        date = dateStr,
                        totalHours = totalHours,
                        source = "android",
                        qualityFlags = flags
                    )
                    
                    Log.d(LOG_TAG, "Uploaded screen time for $dateStr: ${String.format("%.2f", totalHours)}h (${screenTime.appCount} apps)")
                    ok++
                    
                }.onFailure { e ->
                    fail++
                    Log.e(LOG_TAG, "Upload failed for $date", e)
                }
            }

            Log.d(LOG_TAG, "Uploaded $ok of ${toWrite.size} days (fail=$fail)")

            if (fail > 0) {
                // Treat as retryable: common causes are transient network/auth
                shouldScheduleNext = false
                return@withContext Result.retry()
            }

            Result.success()

        } catch (t: Throwable) {
            Log.w(LOG_TAG, "Worker error", t)
            shouldScheduleNext = false
            Result.retry()
        } finally {
            if (shouldScheduleNext) {
                scheduleNext(applicationContext)
            }
        }
    }

    companion object {

        private const val UNIQUE = "screen_time_daily_worker"
        private const val LOG_TAG = "ScreenTimeDailySync"

        /**
         * Run once immediately (useful for testing or manual trigger).
         */
        fun runOnceNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<ScreenTimeDailySyncWorker>()
                .setInitialDelay(0, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE, ExistingWorkPolicy.REPLACE, req
            )
        }

        /**
         * Schedule the next run at 10 AM tomorrow (device time).
         * This is called automatically after each successful run.
         */
        fun scheduleNext(context: Context) {
            val now = ZonedDateTime.now()
            
            // Target: 10:00 AM device time
            val targetHour = 10
            val targetMinute = 0
            
            var nextRun = now
                .with(LocalTime.of(targetHour, targetMinute, 0))
                .truncatedTo(ChronoUnit.MINUTES)
            
            // If 10 AM today has already passed, schedule for 10 AM tomorrow
            if (!nextRun.isAfter(now)) {
                nextRun = nextRun.plusDays(1)
            }
            
            val delayMinutes = ChronoUnit.MINUTES.between(now, nextRun)
            
            Log.d(LOG_TAG, "Scheduling next run at $nextRun (in $delayMinutes minutes)")
            
            val req = OneTimeWorkRequestBuilder<ScreenTimeDailySyncWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE, ExistingWorkPolicy.REPLACE, req
            )
        }

        /**
         * Cancel all scheduled runs.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
        }
    }
}
