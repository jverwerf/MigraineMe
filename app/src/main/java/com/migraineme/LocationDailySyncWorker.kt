package com.migraineme

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Daily worker that uploads device location to user_location_daily.
 * 
 * Scheduling strategy:
 * - Login: runOnceNow() + scheduleNext()
 * - Toggle ON: cancelAll() + runOnceNow() + scheduleNext()
 * - LocationWatchdogWorker: checks if scheduled, calls scheduleNext() if not
 * - This worker: just does the work, NO scheduling
 */
class LocationDailySyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(LOG_TAG, "---- Running LocationDailySyncWorker ----")

            val access = SessionStore.getValidAccessToken(applicationContext)
            if (access == null) {
                Log.w(LOG_TAG, "No valid access token; will retry")
                return@withContext Result.retry()
            }

            // Check Supabase metric_settings for location enabled
            val edge = EdgeFunctionsService()
            val settings = edge.getMetricSettings(applicationContext)
            val locationEnabled = settings.find { it.metric == "user_location_daily" }?.enabled ?: false

            if (!locationEnabled) {
                Log.d(LOG_TAG, "user_location_daily disabled in Supabase – skip")
                return@withContext Result.success()
            }

            val svc = SupabasePersonalService(applicationContext)
            val today = LocalDate.now(ZoneId.systemDefault())

            val loc = getDeviceLocation(applicationContext)
            if (loc == null) {
                Log.w(LOG_TAG, "No location found; will retry")
                return@withContext Result.retry()
            }

            val lat = loc.latitude
            val lon = loc.longitude

            // Get device timezone once
            val deviceTimezone = ZoneId.systemDefault().id  // e.g. "Europe/London"

            val latestStr = svc.latestUserLocationDate(access, "device")
            val latest = latestStr?.let { LocalDate.parse(it) }

            val toWrite: List<LocalDate> =
                when {
                    latest == null -> listOf(today)
                    latest.isBefore(today) -> {
                        val out = mutableListOf<LocalDate>()
                        var d = latest.plusDays(1)
                        while (!d.isAfter(today)) {
                            out.add(d)
                            d = d.plusDays(1)
                        }
                        out
                    }
                    else -> emptyList()
                }

            if (toWrite.isEmpty()) {
                Log.d(LOG_TAG, "No days to write (already up to date)")
                return@withContext Result.success()
            }

            var ok = 0
            var fail = 0

            toWrite.forEach { d ->
                runCatching {
                    svc.upsertUserLocationDaily(
                        accessToken = access,
                        date = d.toString(),
                        latitude = lat,
                        longitude = lon,
                        source = "device",
                        timezone = deviceTimezone
                    )
                    ok++
                }.onFailure { e ->
                    fail++
                    Log.e(LOG_TAG, "Upsert failed for $d (lat=$lat lon=$lon)", e)
                }
            }

            Log.d(LOG_TAG, "Inserted $ok of ${toWrite.size} days (fail=$fail)")

            if (fail > 0) {
                return@withContext Result.retry()
            }

            Result.success()

        } catch (t: Throwable) {
            Log.w(LOG_TAG, "Worker error", t)
            Result.retry()
        } finally {
            // Always schedule next 9AM run (watchdog is just a safety net)
            scheduleNext(applicationContext)
        }
    }

    companion object {
        private const val UNIQUE = "location_daily_worker"
        private const val UNIQUE_NOW = "location_daily_worker_now"
        private const val LOG_TAG = "LocationDailySync"

        fun runOnceNow(context: Context) {
            Log.d(LOG_TAG, "runOnceNow called")
            val req = OneTimeWorkRequestBuilder<LocationDailySyncWorker>()
                .setInitialDelay(0, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NOW, ExistingWorkPolicy.REPLACE, req
            )
        }

        fun scheduleNext(context: Context) {
            val now = ZonedDateTime.now()
            var next = now.withHour(9).withMinute(0).withSecond(0)
            if (!next.isAfter(now)) next = next.plusDays(1)

            val delay = Duration.between(now, next).toMillis()
            Log.d(LOG_TAG, "Scheduling next run at $next (delay=${delay}ms)")

            val req = OneTimeWorkRequestBuilder<LocationDailySyncWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancelAll(context: Context) {
            Log.d(LOG_TAG, "Cancelling all location workers")
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NOW)
        }

        suspend fun getDeviceLocation(context: Context): Location? {
            val hasFine = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val hasCoarse = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasFine && !hasCoarse) {
                Log.w(LOG_TAG, "No location permission")
                return null
            }

            return try {
                val client = LocationServices.getFusedLocationProviderClient(context)
                val priority = if (hasFine) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
                val cts = CancellationTokenSource()
                client.getCurrentLocation(priority, cts.token).await()
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error getting location", e)
                null
            }
        }
    }
}
