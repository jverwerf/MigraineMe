// FILE: app/src/main/java/com/migraineme/PhoneBehaviorSyncWorker.kt
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
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Phone behavior sync worker — triggered by FCM (hourly via sync_hourly).
 *
 * Collects a single snapshot of brightness, volume, dark mode, and unlock count,
 * then inserts one row into each of the 4 sample tables:
 *   - phone_brightness_samples
 *   - phone_volume_samples
 *   - phone_dark_mode_samples
 *   - phone_unlock_samples
 */
class PhoneBehaviorSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Worker started")

        try {
            val ctx = applicationContext

            // Get valid Supabase access token
            val access = SessionStore.getValidAccessToken(ctx)
            if (access.isNullOrBlank()) {
                Log.d(TAG, "No valid access token — skipping")
                return@withContext Result.success()
            }

            val userId = SessionStore.readUserId(ctx)
            if (userId.isNullOrBlank()) {
                Log.d(TAG, "No user ID — skipping")
                return@withContext Result.success()
            }

            // Check which metrics are enabled
            val enabledMetrics = try {
                val edge = EdgeFunctionsService()
                val settings = edge.getMetricSettings(ctx)
                settings.filter { it.enabled }.map { it.metric }.toSet()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check metric settings: ${e.message}")
                return@withContext Result.success()
            }

            val anyEnabled = METRICS.any { it in enabledMetrics }
            if (!anyEnabled) {
                Log.d(TAG, "No phone behavior metrics enabled — skipping")
                return@withContext Result.success()
            }

            // Collect snapshot
            val snapshot = PhoneBehaviorCollector.collectSnapshot(ctx)
            if (snapshot == null) {
                Log.w(TAG, "Failed to collect snapshot")
                return@withContext Result.success()
            }

            val svc = SupabasePersonalService(ctx)
            val now = Instant.now().toString()
            var ok = 0
            var fail = 0

            // Insert brightness sample
            if ("phone_brightness_daily" in enabledMetrics) {
                runCatching {
                    svc.insertPhoneBrightnessSample(access, userId, now, snapshot.brightness)
                    ok++
                    Log.d(TAG, "Inserted brightness sample: ${snapshot.brightness}")
                }.onFailure { e ->
                    fail++
                    Log.e(TAG, "Failed to insert brightness sample", e)
                }
            }

            // Insert volume sample
            if ("phone_volume_daily" in enabledMetrics) {
                runCatching {
                    svc.insertPhoneVolumeSample(access, userId, now, snapshot.volumePct)
                    ok++
                    Log.d(TAG, "Inserted volume sample: ${snapshot.volumePct}%")
                }.onFailure { e ->
                    fail++
                    Log.e(TAG, "Failed to insert volume sample", e)
                }
            }

            // Insert dark mode sample
            if ("phone_dark_mode_daily" in enabledMetrics) {
                runCatching {
                    svc.insertPhoneDarkModeSample(access, userId, now, snapshot.isDarkMode)
                    ok++
                    Log.d(TAG, "Inserted dark mode sample: ${snapshot.isDarkMode}")
                }.onFailure { e ->
                    fail++
                    Log.e(TAG, "Failed to insert dark mode sample", e)
                }
            }

            // Insert unlock sample
            if ("phone_unlock_daily" in enabledMetrics) {
                runCatching {
                    svc.insertPhoneUnlockSample(access, userId, now, snapshot.unlockCount)
                    ok++
                    Log.d(TAG, "Inserted unlock sample: ${snapshot.unlockCount}")
                }.onFailure { e ->
                    fail++
                    Log.e(TAG, "Failed to insert unlock sample", e)
                }
            }

            Log.d(TAG, "Done: $ok inserted, $fail failed")
            Result.success()

        } catch (t: Throwable) {
            Log.e(TAG, "Worker error", t)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "PhoneBehaviorSync"
        private const val UNIQUE_WORK = "phone_behavior_sync"

        /** Metric keys that this worker serves (matched against metric_settings) */
        val METRICS = setOf(
            "phone_brightness_daily",
            "phone_volume_daily",
            "phone_dark_mode_daily",
            "phone_unlock_daily"
        )

        /**
         * Run once immediately — called by FCM handler.
         */
        fun runOnce(context: Context) {
            Log.d(TAG, "Enqueuing phone behavior sync")
            val req = OneTimeWorkRequestBuilder<PhoneBehaviorSyncWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK, ExistingWorkPolicy.REPLACE, req
            )
        }
    }
}
