package com.migraineme

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Single daily "late cutoff" reminder:
 * - Runs at 11:00 local time
 * - Checks whether today's WHOOP sleep + recovery exist in Supabase
 * - If still missing, posts ONE notification (no repeats)
 *
 * This worker does NOT call WHOOP. It only checks Supabase (cheap).
 */
class WhoopLateDataReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        try {
            val access = SessionStore.getValidAccessToken(ctx) ?: return Result.success()

            val whoopConnected = runCatching { WhoopTokenStore(ctx).load() != null }.getOrDefault(false)
            if (!whoopConnected) return Result.success()

            val zone = ZoneId.systemDefault()
            val todaySql = LocalDate.now(zone).toString()

            val sleepLoaded = runCatching { SupabaseMetricsService(ctx).hasSleepForDate(access, todaySql, "whoop") }
                .getOrDefault(false)

            val recoveryLoaded = runCatching { SupabasePhysicalHealthService(ctx).hasRecoveryForDate(access, todaySql, "whoop") }
                .getOrDefault(false)

            if (!(sleepLoaded && recoveryLoaded)) {
                postNotification(ctx)
            }

            return Result.success()
        } finally {
            // Always schedule the next run, even if today failed.
            scheduleNext(ctx)
        }
    }

    private fun postNotification(context: Context) {
        val channelId = CHANNEL_ID

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                channelId,
                "MigraineMe data reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(ch)
        }

        val notif = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Whoop data still missing")
            .setContentText("We haven’t received today’s Whoop data yet. Open MigraineMe to retry.")
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIF_ID, notif)
    }

    companion object {
        private const val UNIQUE = "whoop_late_data_reminder_11am"
        private const val CHANNEL_ID = "migraineme_data_reminders"
        private const val NOTIF_ID = 91011

        fun scheduleNext(context: Context) {
            val zone = ZoneId.systemDefault()
            val now = ZonedDateTime.now(zone)
            val targetTime = LocalTime.of(11, 0)

            var target = now.withHour(targetTime.hour).withMinute(targetTime.minute).withSecond(0).withNano(0)
            if (!target.isAfter(now)) {
                target = target.plusDays(1)
            }

            val delayMs = java.time.Duration.between(now, target).toMillis().coerceAtLeast(0)

            val req = OneTimeWorkRequestBuilder<WhoopLateDataReminderWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }
}

