package com.migraineme

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Worker that uploads device location to:
 * - user_location_hourly (every sync, with timestamp)
 * - user_location_daily (once per day, for weather)
 * 
 * Triggered by:
 * - FCM push notification (primary - server sends every hour)
 * - User toggles location ON in app
 * - Login (initial sync)
 * 
 * NO self-scheduling - FCM handles timing.
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

            val loc = getDeviceLocation(applicationContext)
            if (loc == null) {
                Log.w(LOG_TAG, "No location found; will retry")
                return@withContext Result.retry()
            }

            val lat = loc.latitude
            val lon = loc.longitude
            val deviceTimezone = ZoneId.systemDefault().id
            val now = ZonedDateTime.now()
            
            val svc = SupabasePersonalService(applicationContext)

            // 1. Always insert into user_location_hourly
            val hourlySuccess = insertHourlyLocation(
                svc = svc,
                accessToken = access,
                timestamp = now,
                latitude = lat,
                longitude = lon,
                timezone = deviceTimezone
            )
            
            if (hourlySuccess) {
                Log.d(LOG_TAG, "✅ Inserted hourly location for ${now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
            } else {
                Log.w(LOG_TAG, "⚠️ Failed to insert hourly location")
            }

            // 2. Also upsert into user_location_daily (for weather sync)
            val today = LocalDate.now(ZoneId.systemDefault())
            val latestStr = svc.latestUserLocationDate(access, "device")
            val latest = latestStr?.let { LocalDate.parse(it) }

            // Only insert daily if not already done today
            if (latest == null || latest.isBefore(today)) {
                val dailySuccess = runCatching {
                    svc.upsertUserLocationDaily(
                        accessToken = access,
                        date = today.toString(),
                        latitude = lat,
                        longitude = lon,
                        source = "device",
                        timezone = deviceTimezone
                    )
                }.onFailure { e ->
                    Log.e(LOG_TAG, "Daily upsert error: ${e.message}")
                }.isSuccess
                
                if (dailySuccess) {
                    Log.d(LOG_TAG, "✅ Upserted daily location for $today")
                } else {
                    Log.w(LOG_TAG, "⚠️ Failed to upsert daily location")
                }
            } else {
                Log.d(LOG_TAG, "Daily location already exists for $today - skipping daily upsert")
            }

            Result.success()

        } catch (t: Throwable) {
            Log.w(LOG_TAG, "Worker error", t)
            Result.retry()
        }
    }

    private suspend fun insertHourlyLocation(
        svc: SupabasePersonalService,
        accessToken: String,
        timestamp: ZonedDateTime,
        latitude: Double,
        longitude: Double,
        timezone: String
    ): Boolean {
        return try {
            svc.insertUserLocationHourly(
                accessToken = accessToken,
                timestamp = timestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                latitude = latitude,
                longitude = longitude,
                timezone = timezone
            )
            true
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to insert hourly location", e)
            false
        }
    }

    companion object {
        private const val UNIQUE_WORK = "location_daily_worker"
        private const val LOG_TAG = "LocationDailySync"

        /**
         * Run location sync immediately.
         * Called from: FCM push, login, toggle ON
         */
        fun runOnceNow(context: Context) {
            Log.d(LOG_TAG, "runOnceNow called")
            val req = OneTimeWorkRequestBuilder<LocationDailySyncWorker>()
                .setInitialDelay(0, TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK, ExistingWorkPolicy.REPLACE, req
            )
        }

        // Alias for FCM service
        fun runOnce(context: Context) = runOnceNow(context)

        /**
         * Cancel any pending work
         */
        fun cancelAll(context: Context) {
            Log.d(LOG_TAG, "Cancelling location worker")
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
        }

        /**
         * Get device location with multiple fallback strategies.
         * 
         * Uses PRIORITY_BALANCED_POWER_ACCURACY (cell/WiFi) instead of GPS because:
         * - Works instantly (no satellite acquisition needed)
         * - Works indoors
         * - ~100m accuracy is plenty for weather data
         * - Much better battery life
         * 
         * Fallback order:
         * 1. getCurrentLocation() (active request)
         * 2. lastLocation (cached)
         * 3. requestLocationUpdates with timeout
         * 4. Legacy LocationManager
         */
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

            val client = LocationServices.getFusedLocationProviderClient(context)
            
            // Use balanced power (cell/WiFi) - works instantly, good enough for weather
            val priority = Priority.PRIORITY_BALANCED_POWER_ACCURACY

            // Strategy 1: getCurrentLocation (active request, best option)
            try {
                Log.d(LOG_TAG, "Trying getCurrentLocation (balanced/cell+WiFi)...")
                val cts = CancellationTokenSource()
                val loc = withTimeoutOrNull(10_000L) {
                    client.getCurrentLocation(priority, cts.token).await()
                }
                if (loc != null) {
                    Log.d(LOG_TAG, "✅ Got location via getCurrentLocation: ${loc.latitude}, ${loc.longitude}")
                    return loc
                }
                Log.d(LOG_TAG, "getCurrentLocation returned null")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "getCurrentLocation failed: ${e.message}")
            }

            // Strategy 2: lastLocation (cached)
            try {
                Log.d(LOG_TAG, "Trying lastLocation (cached)...")
                val cached = client.lastLocation.await()
                if (cached != null) {
                    Log.d(LOG_TAG, "✅ Got cached location: ${cached.latitude}, ${cached.longitude}")
                    return cached
                }
                Log.d(LOG_TAG, "lastLocation returned null")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "lastLocation failed: ${e.message}")
            }

            // Strategy 3: requestLocationUpdates with timeout
            try {
                Log.d(LOG_TAG, "Trying requestLocationUpdates...")
                val loc = withTimeoutOrNull(15_000L) {
                    requestSingleLocationUpdate(context, priority)
                }
                if (loc != null) {
                    Log.d(LOG_TAG, "✅ Got location via requestLocationUpdates: ${loc.latitude}, ${loc.longitude}")
                    return loc
                }
                Log.d(LOG_TAG, "requestLocationUpdates returned null")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "requestLocationUpdates failed: ${e.message}")
            }

            // Strategy 4: Legacy LocationManager fallback (network provider preferred)
            try {
                Log.d(LOG_TAG, "Trying legacy LocationManager...")
                val loc = getLegacyLocation(context)
                if (loc != null) {
                    Log.d(LOG_TAG, "✅ Got location via LocationManager: ${loc.latitude}, ${loc.longitude}")
                    return loc
                }
                Log.d(LOG_TAG, "LocationManager returned null")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "LocationManager failed: ${e.message}")
            }

            Log.e(LOG_TAG, "❌ All location strategies failed")
            return null
        }

        /**
         * Request a single location update using FusedLocationProviderClient
         */
        @Suppress("MissingPermission")
        private suspend fun requestSingleLocationUpdate(context: Context, priority: Int): Location? {
            return suspendCancellableCoroutine { cont ->
                val client = LocationServices.getFusedLocationProviderClient(context)
                
                val request = LocationRequest.Builder(priority, 0L)
                    .setMaxUpdates(1)
                    .setMinUpdateIntervalMillis(0)
                    .setWaitForAccurateLocation(false)
                    .build()

                val callback = object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        client.removeLocationUpdates(this)
                        if (cont.isActive) {
                            cont.resume(result.lastLocation)
                        }
                    }
                }

                try {
                    client.requestLocationUpdates(request, callback, Looper.getMainLooper())
                } catch (e: Exception) {
                    if (cont.isActive) {
                        cont.resume(null)
                    }
                }

                cont.invokeOnCancellation {
                    client.removeLocationUpdates(callback)
                }
            }
        }

        /**
         * Legacy LocationManager fallback - prioritizes network provider (cell/WiFi)
         * since we want fast, battery-efficient location for weather data.
         */
        @Suppress("MissingPermission")
        private fun getLegacyLocation(context: Context): Location? {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return null

            // Network provider first (cell/WiFi - fast, battery efficient)
            try {
                val network = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (network != null) return network
            } catch (_: Exception) { }

            // Passive provider (uses locations from other apps)
            try {
                val passive = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                if (passive != null) return passive
            } catch (_: Exception) { }

            // GPS last resort (might be stale but better than nothing)
            try {
                val gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (gps != null) return gps
            } catch (_: Exception) { }

            return null
        }
    }
}
