package com.migraineme

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Daily background sync of the user's calendar. Fires once per day around
 * 07:00 local time, reads the calendar (today-3..today+6), posts to
 * sync-calendar-events, lets the edge function auto-save + clean up deleted
 * events. The evening check-in's calendar page still does its own opt-out
 * sync — this just makes sure that when the user opens it the auto-save is
 * already done and the gauge reflects today + the next 6 days.
 */
class CalendarDailySyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "---- CalendarDailySyncWorker tick ----")

            val token = SessionStore.getValidAccessToken(applicationContext)
            if (token == null) {
                Log.w(TAG, "No valid access token; retry")
                return@withContext Result.retry()
            }

            // Respect the metric_settings toggle. If the user has not turned
            // Calendar events on, we do nothing.
            val edge = EdgeFunctionsService()
            val settings = edge.getMetricSettings(applicationContext)
            val enabled = settings.find { it.metric == "calendar_events" }?.enabled ?: false
            if (!enabled) {
                Log.d(TAG, "calendar_events disabled in metric_settings – skip")
                return@withContext Result.success()
            }

            if (!CalendarService.hasReadPermission(applicationContext)) {
                Log.d(TAG, "READ_CALENDAR not granted – skip")
                return@withContext Result.success()
            }

            // syncWindow() does the read + edge function call + auto-save.
            val mappings = CalendarService.syncWindow(applicationContext)
            Log.d(TAG, "Synced ${mappings.size} mappings")
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "Worker error", t)
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK = "calendar_daily_sync"
        private const val TAG = "CalendarDailySync"

        /** Schedule the worker to run every day at ~07:00 local time. Safe
         *  to call repeatedly — uses KEEP so we don't double-schedule. */
        fun schedule(context: Context) {
            val now = LocalDateTime.now(ZoneId.systemDefault())
            val today7am = now.with(LocalTime.of(7, 0))
            val next7am = if (now.isBefore(today7am)) today7am else today7am.plusDays(1)
            val delayMillis = java.time.Duration.between(now, next7am).toMillis()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val req = PeriodicWorkRequestBuilder<CalendarDailySyncWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, req
            )
            Log.d(TAG, "Scheduled (first fire in ${delayMillis / 60_000} min)")
        }

        /** Force-replace the periodic schedule. Use after a settings change
         *  to make sure the next run is at the right time. */
        fun reschedule(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
            schedule(context)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
        }

        /** Fire a one-shot calendar sync right now (no schedule, no constraints).
         *  Used from app launch / foreground entry so mappings are fresh before
         *  the user opens the daily check-in. */
        fun runOnceNow(context: Context) {
            val req = androidx.work.OneTimeWorkRequestBuilder<CalendarDailySyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${UNIQUE_WORK}_oneshot",
                androidx.work.ExistingWorkPolicy.REPLACE,
                req,
            )
            Log.d(TAG, "runOnceNow enqueued")
        }
    }
}
