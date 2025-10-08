package com.migraineme

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import java.time.Instant

class WeatherDailyWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // 1) read saved location
        val saved = LocationPrefs.flow(applicationContext).first()
            ?: return Result.retry() // no location saved yet

        return try {
            // 2) fetch latest summary
            val s = WeatherService.getSummary(
                latitude = saved.lat,
                longitude = saved.lon,
                zoneId = saved.zoneId
            )

            // 3) cache locally for the gauge
            val now = Instant.now().toEpochMilli()
            WeatherCache.save(applicationContext, s, now)

            // 4) (optional) push a daily row to Supabase if desired

            Result.success(
                workDataOf(
                    "tempC" to (s.tempC ?: Double.NaN),
                    "pressureHpa" to (s.pressureHpa ?: Double.NaN),
                    "humidityPct" to (s.humidityPct ?: Double.NaN)
                )
            )
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
