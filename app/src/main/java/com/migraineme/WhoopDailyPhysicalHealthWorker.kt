// FILE: app/src/main/java/com/migraineme/WhoopDailyPhysicalHealthWorker.kt
package com.migraineme

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import java.util.concurrent.TimeUnit

class WhoopDailyPhysicalHealthWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val access: String = SessionStore.readAccessToken(applicationContext)
                ?: return@withContext Result.success()

            if (WhoopTokenStore(applicationContext).load() == null) {
                scheduleNext(applicationContext); return@withContext Result.success()
            }

            val zone = ZoneId.systemDefault()
            val todayLocal = LocalDate.now(zone)
            val dateSql = todayLocal.toString()

            val svc = SupabasePhysicalHealthService(applicationContext)
            if (svc.hasRecoveryForDate(access, dateSql, source = "whoop")) {
                scheduleNext(applicationContext); return@withContext Result.success()
            }

            WhoopAuthService().refresh(applicationContext)
            val (wStart, wEnd) = window(todayLocal, zone)
            val api = WhoopApiService(applicationContext)

            api.getRecovery(wStart, wEnd)?.let { root ->
                val rec = selectRecordForDate(root, todayLocal, zone)
                if (rec != null) writeAllForDate(svc, access, dateSql, rec)
            }

            api.getWorkouts(wStart, wEnd)?.let { root ->
                val minutes = sumHighHrMinutes(root)
                svc.upsertHighHrTimeDaily(access, dateSql, minutes, "whoop", null)
            }

            scheduleNext(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            Log.d("WhoopDailySync", "PH sync error: ${t.message ?: "unknown"}")
            scheduleNext(applicationContext); Result.success()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "whoop_daily_physical_health_9am"

        fun scheduleNext(context: Context) {
            val now = ZonedDateTime.now(ZoneId.systemDefault())
            var next = now.withHour(9).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delayMillis = Duration.between(now, next).toMillis()
            val req = OneTimeWorkRequestBuilder<WhoopDailyPhysicalHealthWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }

        fun runOnceNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<WhoopDailyPhysicalHealthWorker>()
                .setInitialDelay(0, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }

        /** Backfill from the day **after** latest stored up through **today** (inclusive). */
        suspend fun backfillUpToToday(context: Context, accessToken: String) {
            try {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                if (WhoopTokenStore(context).load() == null) return
                val svc = SupabasePhysicalHealthService(context)
                val latest = svc.latestPhysicalHealthDate(accessToken)

                // If nothing yet, do nothing here (first run will be today's on login/runOnceNow)
                val start = latest?.let { LocalDate.parse(it).plusDays(1) } ?: return
                if (start.isAfter(today)) return

                WhoopAuthService().refresh(context)
                val api = WhoopApiService(context)

                var cur = start
                while (!cur.isAfter(today)) {
                    val dateSql = cur.toString()
                    if (!svc.hasRecoveryForDate(accessToken, dateSql, "whoop")) {
                        val (wStart, wEnd) = window(cur, zone)
                        api.getRecovery(wStart, wEnd)?.let { root ->
                            val rec = selectRecordForDate(root, cur, zone)
                            if (rec != null) writeAllForDate(svc, accessToken, dateSql, rec)
                        }
                        api.getWorkouts(wStart, wEnd)?.let { root ->
                            val minutes = sumHighHrMinutes(root)
                            svc.upsertHighHrTimeDaily(accessToken, dateSql, minutes, "whoop", null)
                        }
                    }
                    cur = cur.plusDays(1)
                }
            } catch (_: Throwable) { }
        }

        private fun window(day: LocalDate, zone: ZoneId): Pair<Date, Date> {
            val start = day.minusDays(1).atTime(12, 0).atZone(zone).toInstant()
            val end = day.plusDays(1).atTime(12, 0).atZone(zone).toInstant()
            return Date.from(start) to Date.from(end)
        }

        private fun selectRecordForDate(root: JSONObject, target: LocalDate, zone: ZoneId): JSONObject? {
            var best: JSONObject? = null
            var bestEndMs = Long.MIN_VALUE
            val arr = root.optJSONArray("records") ?: return null
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val endUtc = obj.optString("end", null) ?: continue
                val tzMin = obj.optInt("timezone_offset_minutes", 0)
                val endLocal = try { java.time.Instant.parse(endUtc).plusSeconds(tzMin.toLong() * 60L) } catch (_: Throwable) { continue }
                val endDateLocal = endLocal.atZone(zone).toLocalDate()
                if (endDateLocal == target) return obj
                val ms = endLocal.toEpochMilli()
                if (ms > bestEndMs) { best = obj; bestEndMs = ms }
            }
            return best
        }

        private suspend fun writeAllForDate(svc: SupabasePhysicalHealthService, access: String, dateSql: String, rec: JSONObject) {
            val score = rec.optJSONObject("score")
            val sid = rec.optString("id", null)
            score?.optDouble("recovery_score")?.let { if (!it.isNaN()) svc.upsertRecoveryDaily(access, dateSql, it, "whoop", sid) }
            score?.optDouble("user_calibrating_resting_heart_rate")?.let { if (!it.isNaN()) svc.upsertRhrDaily(access, dateSql, it, "whoop", sid) }
            score?.optDouble("user_calibrating_hrv_rmssd_milli")?.let { if (!it.isNaN()) svc.upsertHrvDaily(access, dateSql, it, "whoop", sid) }
            score?.optDouble("delta_skin_temp_celsius")?.let { if (!it.isNaN()) svc.upsertSkinTempDaily(access, dateSql, it, "whoop", sid) }
            score?.optDouble("spo2_percentage")?.let { if (!it.isNaN()) svc.upsertSpO2Daily(access, dateSql, it, "whoop", sid) }
        }

        private fun sumHighHrMinutes(root: JSONObject): Int {
            var total = 0
            val recs = root.optJSONArray("records") ?: return 0
            for (i in 0 until recs.length()) {
                val w = recs.optJSONObject(i) ?: continue
                val zones = w.optJSONObject("score")?.optJSONObject("minutes_in_heart_rate_zones")
                if (zones != null) total += zones.optInt("zone_four", 0) + zones.optInt("zone_five", 0)
            }
            return total
        }
    }
}
