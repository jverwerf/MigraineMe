package com.migraineme

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Periodically captures ~60 seconds of ambient loudness metrics and uploads numbers to Supabase.
 *
 * IMPORTANT:
 * - This uses WorkManager foreground execution while recording so microphone access is allowed.
 * - The foreground notification is automatic (no user click), and disappears when work finishes.
 * - No audio is stored or uploaded.
 */
class AmbientNoiseSampleWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        try {
            // Gate collection by your standard settings toggle
            val enabled = DataCollectionSettings.isActive(
                context = ctx,
                table = "ambient_noise_samples",
                wearable = null,
                defaultValue = false
            )
            if (!enabled) {
                Log.d(LOG_TAG, "ambient_noise_samples disabled — skip")
                return@withContext Result.success()
            }

            val hasMicPerm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            if (!hasMicPerm) {
                Log.d(LOG_TAG, "RECORD_AUDIO not granted — skip")
                return@withContext Result.success()
            }

            val access = SessionStore.getValidAccessToken(ctx) ?: return@withContext Result.success()

            // Table requires user_id; prefer persisted value, fallback to auth lookup.
            val userId = SessionStore.readUserId(ctx)
                ?: runCatching { SupabaseAuthService.getUser(access).id }.getOrNull()
                ?: run {
                    Log.d(LOG_TAG, "userId unavailable — skip")
                    return@withContext Result.success()
                }

            // Foreground (notification visible only during recording)
            setForeground(createForegroundInfo(ctx))

            val startTsIso = Instant.now().toString()
            val durationS = 60

            val metrics = AmbientNoiseSampler.capture(durationMs = durationS * 1000L)

            // If we got nothing meaningful, skip upload
            if (metrics.frames <= 0) {
                Log.d(LOG_TAG, "No frames captured — skip upload")
                return@withContext Result.success()
            }

            val flags = buildMap<String, String> {
                put("frames", metrics.frames.toString())
                put("source", "android")
            }

            SupabaseAmbientNoiseService().insertAmbientNoiseSample(
                accessToken = access,
                userId = userId,
                startTsIso = startTsIso,
                durationS = durationS,
                lMean = metrics.lMean,
                lP90 = metrics.lP90,
                lMax = metrics.lMax,
                qualityFlags = flags
            )

            Log.d(LOG_TAG, "Uploaded ambient noise sample frames=${metrics.frames}")
            Result.success()
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "Worker error: ${t.message}")
            // Keep it non-fatal; we don’t want retries to spam mic usage.
            Result.success()
        }
    }

    private fun createForegroundInfo(context: Context): ForegroundInfo {
        ensureChannel(context)

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Measuring ambient sound level")
            .setContentText("MigraineMe is sampling loudness (no audio stored).")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        return ForegroundInfo(FOREGROUND_NOTIF_ID, notif)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                "MigraineMe sensor sampling",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }

    companion object {
        private const val LOG_TAG = "AmbientNoiseWorker"
        private const val UNIQUE = "ambient_noise_samples_periodic"
        private const val CHANNEL_ID = "migraineme_sensor_sampling"
        private const val FOREGROUND_NOTIF_ID = 92001

        /**
         * WorkManager periodic minimum is 15 minutes. 30 minutes is fine.
         * This is not exact scheduling; it’s “best effort” periodic.
         */
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<AmbientNoiseSampleWorker>(30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE,
                ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
        }
    }
}
