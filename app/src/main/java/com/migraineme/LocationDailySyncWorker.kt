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

class LocationDailySyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(LOG_TAG, "---- Running LocationDailySyncWorker ----")

            val access = SessionStore.getValidAccessToken(applicationContext)
                ?: return@withContext Result.success()

            // Gate entire location collection by the single table toggle.
            if (!DataCollectionSettings.isActive(applicationContext, "user_location_daily", wearable = null, defaultValue = true)) {
                Log.d(LOG_TAG, "user_location_daily disabled — skip")
                scheduleNext(applicationContext)
                return@withContext Result.success()
            }

            val svc = SupabasePersonalService(applicationContext)
            val today = LocalDate.now(ZoneId.systemDefault())

            val loc = getDeviceLocation(applicationContext)
            if (loc == null) {
                Log.d(LOG_TAG, "No location found → skip")
                scheduleNext(applicationContext)
                return@withContext Result.success()
            }

            val lat = loc.latitude
            val lon = loc.longitude

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

            var ok = 0
            toWrite.forEach { d ->
                runCatching {
                    svc.upsertUserLocationDaily(
                        accessToken = access,
                        date = d.toString(),
                        latitude = lat,
                        longitude = lon,
                        source = "device"
                    )
                    ok++
                }
            }

            Log.d(LOG_TAG, "Inserted $ok of ${toWrite.size} days")

            scheduleNext(applicationContext)
            Result.success()

        } catch (t: Throwable) {
            Log.w(LOG_TAG, "Worker error: ${t.message}")
            scheduleNext(applicationContext)
            Result.success()
        }
    }

    companion object {

        private const val UNIQUE = "location_daily_worker"
        private const val LOG_TAG = "LocationDailySync"

        fun runOnceNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<LocationDailySyncWorker>()
                .setInitialDelay(0, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE, ExistingWorkPolicy.REPLACE, req
            )
        }

        fun scheduleNext(context: Context) {
            val now = ZonedDateTime.now()
            var next = now.withHour(9).withMinute(0).withSecond(0)
            if (!next.isAfter(now)) next = next.plusDays(1)

            val delay = Duration.between(now, next).toMillis()

            val req = OneTimeWorkRequestBuilder<LocationDailySyncWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, req)
        }

        /**
         * Same as WHOOP: backfill runs manually without needing a Worker instance.
         */
        suspend fun backfillUpToToday(context: Context, accessToken: String) {
            // Gate backfill too; otherwise disabling would still write history.
            if (!DataCollectionSettings.isActive(context, "user_location_daily", wearable = null, defaultValue = true)) {
                return
            }

            val svc = SupabasePersonalService(context)

            val today = LocalDate.now(ZoneId.systemDefault())
            val latestStr = svc.latestUserLocationDate(accessToken, "device")
            val latest = latestStr?.let { LocalDate.parse(it) }

            val start = latest?.plusDays(1) ?: today
            if (start.isAfter(today)) return

            val loc = getDeviceLocation(context) ?: return
            val lat = loc.latitude
            val lon = loc.longitude

            var d = start
            while (!d.isAfter(today)) {
                runCatching {
                    svc.upsertUserLocationDaily(
                        accessToken = accessToken,
                        date = d.toString(),
                        latitude = lat,
                        longitude = lon,
                        source = "device"
                    )
                }
                d = d.plusDays(1)
            }
        }

        /**
         * Public location fetcher so backfill can use it.
         */
        fun getDeviceLocation(ctx: Context): Location? {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

            val fine = ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            val coarse = ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!fine && !coarse) return null

            val gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (gps != null) return gps

            val net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val pass = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)

            return listOfNotNull(gps, net, pass).maxByOrNull { it.time }
        }
    }
}
