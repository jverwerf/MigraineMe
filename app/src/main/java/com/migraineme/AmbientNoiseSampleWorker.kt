package com.migraineme

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Captures ~60 seconds of ambient loudness metrics and uploads numbers to Supabase.
 */
class AmbientNoiseSampleWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext

        Log.d(LOG_TAG, "Worker started - checking settings")

        // Check Supabase metric_settings for ambient noise enabled
        val enabled = try {
            val edge = EdgeFunctionsService()
            val settings = edge.getMetricSettings(ctx)
            val isEnabled = settings.find { it.metric == "ambient_noise_samples" }?.enabled ?: false
            // Cache locally for ScreenOnReceiver
            AmbientNoisePrefs.setEnabled(ctx, isEnabled)
            isEnabled
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to check Supabase settings: ${e.message}, assuming disabled")
            false
        }

        if (!enabled) {
            Log.d(LOG_TAG, "ambient_noise_samples disabled in Supabase — cancel loop")
            AmbientNoisePrefs.setEnabled(ctx, false)
            cancel(ctx)
            return@withContext Result.success()
        }

        try {
            val hasMicPerm =
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
            if (!hasMicPerm) {
                Log.d(LOG_TAG, "RECORD_AUDIO not granted — skip")
                return@withContext Result.success()
            }

            val access = SessionStore.getValidAccessToken(ctx)
            if (access == null) {
                Log.d(LOG_TAG, "No valid Supabase access token — skip")
                return@withContext Result.success()
            }

            val userId = SessionStore.readUserId(ctx)
                ?: runCatching { SupabaseAuthService.getUser(access).id }.getOrNull()
                ?: run {
                    Log.d(LOG_TAG, "userId unavailable — skip")
                    return@withContext Result.success()
                }

            try {
                setForeground(createForegroundInfo(ctx))
            } catch (t: Throwable) {
                Log.w(LOG_TAG, "Unable to start foreground execution for mic: ${t.message}")
                return@withContext Result.success()
            }

            val startTsIso = Instant.now().toString()
            val durationS = 60

            Log.d(LOG_TAG, "Starting capture for ${durationS}s")
            val metrics = AmbientNoiseSampler.capture(durationMs = durationS * 1000L)

            if (metrics.frames <= 0) {
                Log.d(LOG_TAG, "No frames captured — skip upload")
                return@withContext Result.success()
            }

            // Skip silent samples (screen off / mic blocked)
            if (metrics.lMean == 0.0 && metrics.lMax == 0.0) {
                Log.d(LOG_TAG, "Silent sample detected (likely screen off) — skip upload")
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

            // Record successful sample time locally for ScreenOnReceiver
            AmbientNoisePrefs.recordSuccessfulSample(ctx, metrics.lMean, metrics.lMax)

            Log.d(LOG_TAG, "Uploaded ambient noise sample frames=${metrics.frames}")
            Result.success()
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "Worker error: ${t.message}", t)
            Result.success()
        } finally {
            scheduleNext(ctx)
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

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

        return ForegroundInfo(FOREGROUND_NOTIF_ID, notif, fgsType)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
        private const val UNIQUE = "ambient_noise_samples_loop"

        private const val CHANNEL_ID = "migraineme_sensor_sampling"
        private const val FOREGROUND_NOTIF_ID = 92001
        private const val LOOP_DELAY_MINUTES = 30L

        /**
         * Schedule worker to run soon (used by regular loop).
         * Uses KEEP policy - won't interrupt if already running/queued.
         */
        fun schedule(context: Context) {
            try {
                Log.d(LOG_TAG, "Scheduling ambient noise sampling worker")

                val req = OneTimeWorkRequestBuilder<AmbientNoiseSampleWorker>()
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE,
                    ExistingWorkPolicy.KEEP,
                    req
                )

                Log.d(LOG_TAG, "Worker scheduled successfully")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error scheduling worker: ${e.message}", e)
            }
        }

        /**
         * Schedule worker to run immediately (used by ScreenOnReceiver).
         * Uses REPLACE policy - cancels any pending delayed work to run now.
         */
        fun scheduleImmediate(context: Context) {
            try {
                Log.d(LOG_TAG, "Scheduling IMMEDIATE ambient noise sampling")

                val req = OneTimeWorkRequestBuilder<AmbientNoiseSampleWorker>()
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE,
                    ExistingWorkPolicy.REPLACE,
                    req
                )

                Log.d(LOG_TAG, "Immediate worker scheduled")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error scheduling immediate worker: ${e.message}", e)
            }
        }

        private fun scheduleNext(context: Context) {
            try {
                Log.d(LOG_TAG, "Scheduling next run in $LOOP_DELAY_MINUTES minutes")

                val req = OneTimeWorkRequestBuilder<AmbientNoiseSampleWorker>()
                    .setInitialDelay(LOOP_DELAY_MINUTES, TimeUnit.MINUTES)
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE,
                    ExistingWorkPolicy.KEEP,
                    req
                )
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error scheduling next worker: ${e.message}", e)
            }
        }

        fun cancel(context: Context) {
            try {
                Log.d(LOG_TAG, "Cancelling ambient noise sampling worker")
                WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error cancelling worker: ${e.message}", e)
            }
        }
    }
}
