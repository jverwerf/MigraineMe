// FILE: C:\Users\verwe\Projects\MigraineMe\app\src\main\java\com\migraineme\LocationDailySyncWorker.kt
package com.migraineme

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Stores the user's latitude/longitude once per day, with backfill.
 * - Runs daily at 09:00 local time.
 * - Uses today's device location.
 * - If prior days are missing (phone off, etc.), fills every missing date
 *   from (latest existing + 1) up to TODAY with today's coordinates.
 * - No-ops on missing session or permission.
 */
class LocationDailySyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val access: String = SessionStore.readAccessToken(applicationContext)
                ?: return@withContext Result.success()

            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val svc = SupabasePersonalService(applicationContext)

            // Use last-known device location once (for today and any backfill rows)
            val loc = getLastKnownLocationPreferGps(applicationContext)
            if (loc == null) {
                Log.d(LOG_TAG, "No last-known location available. Skipping for today.")
                scheduleNext(applicationContext)
                return@withContext Result.success()
            }
            val lat = loc.latitude.coerceIn(-90.0, 90.0)
            val lon = loc.longitude.coerceIn(-180.0, 180.0)

            // Find latest existing date for source=device
            val latestStr = runCatching { svc.latestUserLocationDate(access, source = "device") }.getOrNull()
            val latest = latestStr?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

            // Determine which dates to upsert (inclusive)
            val datesToWrite: List<LocalDate> = when {
                latest == null -> listOf(today) // no data yet -> just today
                latest.isBefore(today) -> {
                    // Fill from the day after latest up to today (inclusive)
                    val out = ArrayList<LocalDate>()
                    var d = latest.plusDays(1)
                    while (!d.isAfter(today)) {
                        out.add(d)
                        d = d.plusDays(1)
                    }
                    out
                }
                else -> emptyList() // already have today or newer
            }

            if (datesToWrite.isEmpty()) {
                Log.d(LOG_TAG, "No missing dates to fill. Latest=$latestStr, today=$today")
                scheduleNext(applicationContext)
                return@withContext Result.success()
            }

            // Upsert each missing date with today's coordinates
            var success = 0
            datesToWrite.forEach { d ->
                val dateSql = d.toString()
                runCatching {
                    svc.upsertUserLocationDaily(
                        accessToken = access,
                        date = dateSql,
                        latitude = lat,
                        longitude = lon,
                        source = "device",
                        sourceId = null
                    )
                    success++
                }.onFailure { t ->
                    Log.w(LOG_TAG, "Upsert user_location_daily failed for $dateSql | ${t.message}")
                }
            }

            Log.d(
                LOG_TAG,
                "Backfill complete: wrote=$success of ${datesToWrite.size} " +
                        "range=${datesToWrite.firstOrNull()}..${datesToWrite.lastOrNull()} lat=$lat lon=$lon"
            )

            scheduleNext(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "Location sync error | ${t::class.simpleName}: ${t.message}")
            scheduleNext(applicationContext)
            Result.success()
        }
    }

    private fun getLastKnownLocationPreferGps(ctx: Context): Location? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        fun hasPerm(name: String): Boolean =
            ContextCompat.checkSelfPermission(ctx, name) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED

        val fine = hasPerm(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = hasPerm(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) return null

        val gps = try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (_: SecurityException) { null }
        if (gps != null) return gps

        var best: Location? = null
        for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            val loc = try { lm.getLastKnownLocation(p) } catch (_: SecurityException) { null }
            if (loc != null && (best == null || loc.time > best!!.time)) best = loc
        }
        return best
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "user_location_daily_9am"
        private const val LOG_TAG = "LocationDailySync"

        fun scheduleNext(context: Context) {
            val now = ZonedDateTime.now(ZoneId.systemDefault())
            var next = now.withHour(9).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delayMillis = max(0L, Duration.between(now, next).toMillis())
            val req = OneTimeWorkRequestBuilder<LocationDailySyncWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }

        fun runOnceNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<LocationDailySyncWorker>()
                .setInitialDelay(0, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }
}
