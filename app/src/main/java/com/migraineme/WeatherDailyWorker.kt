package com.migraineme

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Helper to schedule this job to run at 14:00 local every day.
 * Call WeatherDailyWork.enqueue(context) once (e.g., in MainActivity.onCreate()).
 */
object WeatherDailyWork {
    private const val UNIQUE = "weather-daily-14h"

    fun enqueue(context: Context) {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var next = now.withHour(14).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)

        val initialDelayMin = java.time.Duration.between(now, next).toMinutes().coerceAtLeast(1)

        val req = PeriodicWorkRequestBuilder<WeatherDailyWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(initialDelayMin, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE,
            ExistingPeriodicWorkPolicy.UPDATE,
            req
        )
    }
}

/**
 * Worker: fetches a single weather summary and stores it locally (cache) and in Supabase (if session exists).
 */
class WeatherDailyWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            // 1) read saved location (your existing preference)
            val saved = LocationPrefs.flow(applicationContext).first()
                ?: return@runCatching Result.retry()

            val zoneId = ZoneId.systemDefault().id

            // 2) fetch summary (your existing service), save to local cache
            val summary = WeatherService.getSummary(saved.lat, saved.lon, zoneId)
            val nowMillis = System.currentTimeMillis()
            WeatherCache.save(applicationContext, summary, nowMillis)

            // 3) try to upsert to Supabase if we have a session
            val session = readSessionFromPrefs(applicationContext)
            session?.let { s ->
                // Use the user's LOCAL day for the 14h snapshot row
                val todayLocal = ZonedDateTime.now(ZoneId.systemDefault()).toLocalDate()

                SupabaseWeatherService.upsertWeatherDaily(
                    accessToken = s.accessToken,
                    userId = s.userId,
                    date = todayLocal,
                    tempC = summary.tempC,
                    pressureHpa = summary.pressureHpa,
                    humidityPct = summary.humidityPct
                )
            }

            Result.success()
        }.getOrElse {
            it.printStackTrace()
            Result.retry()
        }
    }

    /** Minimal session holder we read from SharedPreferences (implement to match your app). */
    data class Session(val accessToken: String, val userId: String)

    private fun readSessionFromPrefs(context: Context): Session? {
        // Ensure Auth writes these on login; clear on logout.
        val prefs = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE)
        val token = prefs.getString("access_token", null)
        val uid = prefs.getString("user_id", null)
        return if (token != null && uid != null) Session(token, uid) else null
    }
}
