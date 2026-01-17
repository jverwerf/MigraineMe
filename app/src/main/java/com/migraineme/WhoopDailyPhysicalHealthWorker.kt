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
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * DAILY WHOOP physical health sync.
 *
 * Writes (if enabled):
 * - recovery_score_daily
 * - resting_hr_daily
 * - hrv_daily
 * - skin_temp_daily
 * - spo2_daily
 * - time_in_high_hr_zones_daily
 *
 * Notes:
 * - Uses Supabase session access token from SessionStore.
 * - Skips if WHOOP not connected.
 * - Does NOT schedule itself anymore (backend owns daily schedule).
 */
class WhoopDailyPhysicalHealthWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        try {
            val access = SessionStore.getValidAccessToken(ctx)
                ?: run {
                    debug("No Supabase session — skip")
                    return@withContext Result.success()
                }

            val hasWhoop = WhoopTokenStore(ctx).load() != null
            if (!hasWhoop) {
                debug("WHOOP not connected — skip")
                return@withContext Result.success()
            }

            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val todaySql = today.toString()

            // Backfill up to TODAY (includes today)
            backfillUpToToday(ctx, access)

            val metrics = SupabasePhysicalHealthService(ctx)

            // If already written by backfill, stop.
            // Use recovery_score_daily as the anchor "already synced" signal.
            if (metrics.hasRecoveryForDate(access, todaySql, "whoop")) {
                debug("Skip today $todaySql — already stored (via backfill)")
                return@withContext Result.success()
            }

            // Last attempt: refresh WHOOP token if needed
            WhoopAuthService().refresh(ctx)

            val (wStart, wEnd) = dayWindow(today, zone)
            val api = WhoopApiService(ctx)

            val recoveryRoot = api.getRecovery(wStart, wEnd)
            val workoutsRoot = api.getWorkouts(wStart, wEnd)

            if (recoveryRoot == null) {
                debug("Null WHOOP recovery response for $today")
                return@withContext Result.success()
            }

            val rec = selectFirstRecord(recoveryRoot)
            if (rec == null) {
                debug("No recovery record for $today")
                return@withContext Result.success()
            }

            writeAllForDate(ctx, metrics, access, todaySql, rec)
            writeHighHrZonesIfEnabled(ctx, metrics, access, todaySql, workoutsRoot)

            debug("Stored WHOOP physical health for $todaySql")
            Result.success()

        } catch (t: Throwable) {
            debug("Error: ${t.message}")
            Result.success()
        }
    }

    private fun debug(msg: String) = Log.d("WhoopPhysicalSync", msg)

    companion object {
        private const val UNIQUE_RUN_NOW = "whoop_daily_physical_health_run_now"

        fun runOnceNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<WhoopDailyPhysicalHealthWorker>()
                .setInitialDelay(0, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_RUN_NOW,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }

        suspend fun backfillUpToToday(context: Context, access: String) {
            try {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                WhoopTokenStore(context).load() ?: return

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
                        val recoveryRoot = api.getRecovery(wStart, wEnd)
                        val workoutsRoot = api.getWorkouts(wStart, wEnd)

                        if (recoveryRoot != null) {
                            val rec = selectFirstRecord(recoveryRoot)
                            if (rec != null) {
                                writeAllForDate(context, metrics, access, dateSql, rec)
                                writeHighHrZonesIfEnabled(context, metrics, access, dateSql, workoutsRoot)
                                Log.d("WhoopPhysicalSync", "Backfill wrote $dateSql")
                            } else {
                                Log.d("WhoopPhysicalSync", "Backfill: no recovery record for $cur")
                            }
                        } else {
                            Log.d("WhoopPhysicalSync", "Backfill: null WHOOP recovery response for $cur")
                        }
                    }

                    cur = cur.plusDays(1)
                }
            } catch (_: Throwable) {
            }
        }

        /** Window from previous 12:00 → next day 12:00. */
        private fun dayWindow(day: LocalDate, zone: ZoneId): Pair<Date, Date> {
            val s = day.minusDays(1).atTime(12, 0).atZone(zone).toInstant()
            val e = day.plusDays(1).atTime(12, 0).atZone(zone).toInstant()
            return Date.from(s) to Date.from(e)
        }

        private fun selectFirstRecord(root: JSONObject): JSONObject? {
            val arrays = arrayOf("records", "data", "items")
            for (k in arrays) {
                val arr = root.optJSONArray(k) ?: continue
                if (arr.length() > 0) return arr.optJSONObject(0)
            }
            return null
        }

        private suspend fun writeAllForDate(
            context: Context,
            metrics: SupabasePhysicalHealthService,
            access: String,
            dateSql: String,
            rec: JSONObject
        ) {
            val sourceId = rec.optString("id").takeIf { it.isNotEmpty() }

            val score = rec.optJSONObject("score")

            val recoveryPct = (score?.optDouble("recovery_score", Double.NaN) ?: Double.NaN)
            val restingHr = (score?.optDouble("resting_heart_rate", Double.NaN) ?: Double.NaN)
            val hrv = (score?.optDouble("hrv_rmssd_milli", Double.NaN) ?: Double.NaN)
            val temp = (score?.optDouble("skin_temp_celsius", Double.NaN) ?: Double.NaN)
            val spo2 = (score?.optDouble("spo2_percentage", Double.NaN) ?: Double.NaN)

            if (DataCollectionSettings.isEnabledForWhoop(context, "recovery_score_daily")) {
                if (!recoveryPct.isNaN()) {
                    metrics.upsertRecoveryScoreDaily(access, dateSql, recoveryPct, "whoop", sourceId)
                }
            }

            if (DataCollectionSettings.isEnabledForWhoop(context, "resting_hr_daily")) {
                if (!restingHr.isNaN()) {
                    metrics.upsertRestingHrDaily(access, dateSql, restingHr, "whoop", sourceId)
                }
            }

            if (DataCollectionSettings.isEnabledForWhoop(context, "hrv_daily")) {
                if (!hrv.isNaN()) {
                    metrics.upsertHrvDaily(access, dateSql, hrv, "whoop", sourceId)
                }
            }

            if (DataCollectionSettings.isEnabledForWhoop(context, "skin_temp_daily")) {
                if (!temp.isNaN()) {
                    metrics.upsertSkinTempDaily(access, dateSql, temp, "whoop", sourceId)
                }
            }

            if (DataCollectionSettings.isEnabledForWhoop(context, "spo2_daily")) {
                if (!spo2.isNaN()) {
                    metrics.upsertSpo2Daily(access, dateSql, spo2, "whoop", sourceId)
                }
            }
        }

        private suspend fun writeHighHrZonesIfEnabled(
            context: Context,
            metrics: SupabasePhysicalHealthService,
            access: String,
            dateSql: String,
            workoutsRoot: JSONObject?
        ) {
            if (!DataCollectionSettings.isEnabledForWhoop(context, "time_in_high_hr_zones_daily")) return
            if (workoutsRoot == null) return

            val records = workoutsRoot.optJSONArray("records") ?: return
            if (records.length() == 0) return

            var z3ms = 0L
            var z4ms = 0L
            var z5ms = 0L
            var z6ms = 0L

            for (i in 0 until records.length()) {
                val wrk = records.optJSONObject(i) ?: continue
                val score = wrk.optJSONObject("score") ?: continue
                val zones = score.optJSONObject("heart_rate_zone_duration") ?: continue

                z3ms += zones.optLong("zone_three_milli", 0L)
                z4ms += zones.optLong("zone_four_milli", 0L)
                z5ms += zones.optLong("zone_five_milli", 0L)
                z6ms += zones.optLong("zone_six_milli", 0L)
            }

            val z3 = z3ms / 60_000.0
            val z4 = z4ms / 60_000.0
            val z5 = z5ms / 60_000.0
            val z6 = z6ms / 60_000.0
            val total = z3 + z4 + z5 + z6

            val sourceId = records.optJSONObject(0)?.optString("id")?.takeIf { it.isNotEmpty() }

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
