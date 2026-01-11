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
import java.time.*
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * WHOOP → PHYS HEALTH DAILY SYNC
 *
 * Mirrors the design of WhoopDailySyncWorkerSleepFields:
 * - Backfills up to TODAY (29 days)
 * - Today is written during backfill
 * - Worker runs daily at 09:00
 *
 * Metrics written:
 * - recovery_score_daily
 * - resting_hr_daily
 * - hrv_daily
 * - skin_temp_daily
 * - spo2_daily
 * - time_in_high_hr_zones_daily (z3 + z4 + z5 monitored, z6 = 0)
 *
 * Unique key: (user_id, source, date)
 */
class WhoopDailyPhysicalHealthWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val access = SessionStore.getValidAccessToken(applicationContext)
                ?: return@withContext Result.success()

            val hasWhoop = WhoopTokenStore(applicationContext).load() != null
            if (!hasWhoop) {
                scheduleNext(applicationContext)
                Log.d(TAG, "PH: WHOOP not connected — skip")
                return@withContext Result.success()
            }

            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val todaySql = today.toString()

            // 1) Backfill up to TODAY
            backfillUpToToday(applicationContext, access)

            val metrics = SupabasePhysicalHealthService(applicationContext)

            // 2) Today should now be written. Check.
            if (metrics.hasRecoveryForDate(access, todaySql, "whoop")) {
                scheduleNext(applicationContext)
                Log.d(TAG, "PH: Skip today $todaySql — already via backfill")
                return@withContext Result.success()
            }

            // 3) Last chance — refresh token
            WhoopAuthService().refresh(applicationContext)

            val (wStart, wEnd) = dayWindow(today, zone)
            val api = WhoopApiService(applicationContext)

            val recObj = api.getRecovery(wStart, wEnd)
            val wrkObj = api.getWorkouts(wStart, wEnd)

            if (recObj == null && wrkObj == null) {
                scheduleNext(applicationContext)
                Log.d(TAG, "PH: Null WHOOP response for $today")
                return@withContext Result.success()
            }

            writeAllForDate(
                context = applicationContext,
                metrics = metrics,
                access = access,
                dateSql = todaySql,
                recoveryRoot = recObj,
                workoutRoot = wrkObj
            )

            scheduleNext(applicationContext)
            Log.d(TAG, "PH: Stored PH metrics for $todaySql (fallback path)")
            Result.success()

        } catch (t: Throwable) {
            scheduleNext(applicationContext)
            Log.d(TAG, "PH error: ${t.message}")
            Result.success()
        }
    }

    companion object {
        private const val TAG = "WhoopDailyPH"
        private const val UNIQUE = "whoop_daily_sync_physical_health_9am"

        fun scheduleNext(context: Context) {
            val now = ZonedDateTime.now(ZoneId.systemDefault())
            var next = now.withHour(9).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next).toMillis()

            val req = OneTimeWorkRequestBuilder<WhoopDailyPhysicalHealthWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }

        fun runOnceNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<WhoopDailyPhysicalHealthWorker>()
                .setInitialDelay(0, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }

        /**
         * Backfill PH Daily Metrics up to today.
         * Anchored on recovery_score_daily same way sleep uses sleep_duration_daily.
         */
        suspend fun backfillUpToToday(context: Context, access: String) {
            try {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val token = WhoopTokenStore(context).load() ?: return

                val metrics = SupabasePhysicalHealthService(context)
                val latestStr = metrics.latestPhysicalDate(access, "whoop")

                val baseline = today.minusDays(29)

                val start = when (latestStr) {
                    null -> baseline
                    else -> {
                        val latest = LocalDate.parse(latestStr)
                        val candidate = latest.plusDays(1)
                        if (candidate.isBefore(baseline)) baseline else candidate
                    }
                }

                if (start.isAfter(today)) return

                WhoopAuthService().refresh(context)
                val api = WhoopApiService(context)

                var cur = start
                while (!cur.isAfter(today)) {
                    val dateSql = cur.toString()

                    if (!metrics.hasRecoveryForDate(access, dateSql, "whoop")) {
                        val (wStart, wEnd) = dayWindow(cur, zone)

                        val recObj = api.getRecovery(wStart, wEnd)
                        val wrkObj = api.getWorkouts(wStart, wEnd)

                        writeAllForDate(context, metrics, access, dateSql, recObj, wrkObj)
                        Log.d(TAG, "PH Backfill wrote $dateSql")
                    }

                    cur = cur.plusDays(1)
                }
            } catch (_: Throwable) {}
        }

        /**
         * Window from previous 12:00 → next day 12:00.
         * Matches your final sleep system.
         */
        private fun dayWindow(day: LocalDate, zone: ZoneId): Pair<Date, Date> {
            val s = day.minusDays(1).atTime(12, 0).atZone(zone).toInstant()
            val e = day.plusDays(1).atTime(12, 0).atZone(zone).toInstant()
            return Date.from(s) to Date.from(e)
        }

        private suspend fun writeAllForDate(
            context: Context,
            metrics: SupabasePhysicalHealthService,
            access: String,
            dateSql: String,
            recoveryRoot: JSONObject?,
            workoutRoot: JSONObject?
        ) {
            val rec = selectFirst(recoveryRoot)
            val wrk = selectFirst(workoutRoot)

            val sourceId = rec?.optString("id")?.takeIf { it.isNotEmpty() }
                ?: wrk?.optString("id")?.takeIf { it.isNotEmpty() }

            // -------- Recovery extraction --------
            val score = rec?.optJSONObject("score")

            val recoveryPct = score?.optDouble("recovery_score", Double.NaN)
            val restingHr = score?.optDouble("resting_heart_rate", Double.NaN)
            val hrv = score?.optDouble("hrv_rmssd_milli", Double.NaN)
            val temp = score?.optDouble("skin_temp_celsius", Double.NaN)
            val spo2 = score?.optDouble("blood_oxygen_pct", Double.NaN)

            // Write recovery score
            if (DataCollectionSettings.isEnabledForWhoop(context, "recovery_score_daily")) {
                if (recoveryPct != null && !recoveryPct.isNaN()) {
                    metrics.upsertRecoveryScoreDaily(access, dateSql, recoveryPct, "whoop", sourceId)
                }
            }

            // Resting HR
            if (DataCollectionSettings.isEnabledForWhoop(context, "resting_hr_daily")) {
                if (restingHr != null && !restingHr.isNaN()) {
                    metrics.upsertRestingHrDaily(access, dateSql, restingHr, "whoop", sourceId)
                }
            }

            // HRV RMSSD
            if (DataCollectionSettings.isEnabledForWhoop(context, "hrv_daily")) {
                if (hrv != null && !hrv.isNaN()) {
                    metrics.upsertHrvDaily(access, dateSql, hrv, "whoop", sourceId)
                }
            }

            // Skin temp
            if (DataCollectionSettings.isEnabledForWhoop(context, "skin_temp_daily")) {
                if (temp != null && !temp.isNaN()) {
                    metrics.upsertSkinTempDaily(access, dateSql, temp, "whoop", sourceId)
                }
            }

            // SpO2
            if (DataCollectionSettings.isEnabledForWhoop(context, "spo2_daily")) {
                if (spo2 != null && !spo2.isNaN()) {
                    metrics.upsertSpo2Daily(access, dateSql, spo2, "whoop", sourceId)
                }
            }

            // -------- HIGH HR ZONES from workout --------
            if (DataCollectionSettings.isEnabledForWhoop(context, "time_in_high_hr_zones_daily")) {
                writeHighHrZones(metrics, access, dateSql, wrk, sourceId)
            }
        }

        private fun selectFirst(root: JSONObject?): JSONObject? {
            if (root == null) return null
            val arr = root.optJSONArray("records") ?: return null
            return if (arr.length() > 0) arr.optJSONObject(0) else null
        }

        private suspend fun writeHighHrZones(
            metrics: SupabasePhysicalHealthService,
            access: String,
            dateSql: String,
            wrk: JSONObject?,
            sourceId: String?
        ) {
            if (wrk == null) return

            val score = wrk.optJSONObject("score") ?: return
            val zd = score.optJSONObject("zone_durations") ?: return

            val z3ms = zd.optDouble("zone_three_milli", 0.0)
            val z4ms = zd.optDouble("zone_four_milli", 0.0)
            val z5ms = zd.optDouble("zone_five_milli", 0.0)

            val z3 = z3ms / 60000.0
            val z4 = z4ms / 60000.0
            val z5 = z5ms / 60000.0
            val z6 = 0.0

            val total = z3 + z4 + z5

            metrics.upsertHighHrDaily(
                access = access,
                date = dateSql,
                totalMinutes = total,
                z3 = z3,
                z4 = z4,
                z5 = z5,
                z6 = z6,
                source = "whoop",
                sourceId = sourceId
            )
        }
    }
}
