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
 * FINAL VERSION — TARGET DATE = WAKE-UP DAY
 *
 * Rules:
 * - A sleep belongs to the LocalDate of its WAKE-UP timestamp.
 * - backfillUpToToday includes TODAY.
 * - Today is written inside backfillUpToToday().
 * - doWork() will skip today afterwards if already written.
 */
class WhoopDailySyncWorkerSleepFields(
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

            val metrics = SupabaseMetricsService(ctx)

            // If already written by backfill, stop.
            if (metrics.hasSleepForDate(access, todaySql, "whoop")) {
                debug("Skip today $todaySql — already stored (via backfill)")
                return@withContext Result.success()
            }

            // Last attempt
            WhoopAuthService().refresh(ctx)

            val (wStart, wEnd) = dayWindow(today, zone)
            val api = WhoopApiService(ctx)
            val root = api.getSleep(wStart, wEnd)

            if (root == null) {
                debug("Null WHOOP response for $today")
                return@withContext Result.success()
            }

            val record = selectRecordByWakeup(root, today, zone)
            if (record == null) {
                debug("No record waking on $today")
                return@withContext Result.success()
            }

            writeAllForDate(ctx, metrics, access, todaySql, record)

            debug("Stored WHOOP sleep for $todaySql (fallback path)")
            Result.success()

        } catch (t: Throwable) {
            debug("Error: ${t.message}")
            Result.success()
        } finally {
            // Keep the 09:00 chain alive
            scheduleNext(ctx)
        }
    }

    private fun debug(msg: String) = Log.d("WhoopDailySync", msg)

    companion object {
        private const val UNIQUE_RUN_NOW = "whoop_daily_sync_sleep_fields_run_now"
        private const val UNIQUE_9AM = "whoop_daily_sync_sleep_fields_9am"

        fun scheduleNext(context: Context) {
            val now = ZonedDateTime.now(ZoneId.systemDefault())
            var next = now.withHour(17).withMinute(35).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next).toMillis()

            val req = OneTimeWorkRequestBuilder<WhoopDailySyncWorkerSleepFields>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_9AM,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }

        fun runOnceNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<WhoopDailySyncWorkerSleepFields>()
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

                val metrics = SupabaseMetricsService(context)
                val latestStr = metrics.latestSleepDate(access, "whoop")

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

                    if (!metrics.hasSleepForDate(access, dateSql, "whoop")) {
                        val (wStart, wEnd) = dayWindow(cur, zone)
                        val root = api.getSleep(wStart, wEnd)

                        if (root != null) {
                            val rec = selectRecordByWakeup(root, cur, zone)
                            if (rec != null) {
                                writeAllForDate(context, metrics, access, dateSql, rec)
                                Log.d("WhoopDailySync", "Backfill wrote $dateSql")
                            }
                        }
                    }

                    cur = cur.plusDays(1)
                }
            } catch (_: Throwable) {}
        }

        private fun dayWindow(day: LocalDate, zone: ZoneId): Pair<Date, Date> {
            val s = day.minusDays(1).atTime(12, 0).atZone(zone).toInstant()
            val e = day.plusDays(1).atTime(12, 0).atZone(zone).toInstant()
            return Date.from(s) to Date.from(e)
        }

        private fun selectRecordByWakeup(
            root: JSONObject,
            target: LocalDate,
            zone: ZoneId
        ): JSONObject? {
            var bestExact: JSONObject? = null
            var latestEnd: JSONObject? = null
            var latestEndInstant: Instant? = null

            fun inspect(obj: JSONObject?) {
                if (obj == null) return
                val endUtc = obj.optString("end").takeIf { it.isNotEmpty() } ?: return
                val tzMin = tzMinutes(obj)

                val endLocal = runCatching {
                    Instant.parse(endUtc).plusSeconds(tzMin * 60L)
                }.getOrNull() ?: return

                val endDateLocal = endLocal.atZone(zone).toLocalDate()

                if (endDateLocal == target) bestExact = obj
                if (latestEndInstant == null || endLocal.isAfter(latestEndInstant)) {
                    latestEndInstant = endLocal
                    latestEnd = obj
                }
            }

            val arrays = arrayOf("records", "data", "items")
            for (k in arrays) {
                val arr = root.optJSONArray(k) ?: continue
                for (i in 0 until arr.length()) inspect(arr.optJSONObject(i))
            }

            if (bestExact != null) return bestExact
            if (latestEnd != null) return latestEnd
            if (root.has("end")) {
                inspect(root)
                return bestExact ?: latestEnd
            }
            return null
        }

        private suspend fun writeAllForDate(
            context: Context,
            metrics: SupabaseMetricsService,
            access: String,
            dateSql: String,
            rec: JSONObject
        ) {
            val sourceId = rec.optString("id").takeIf { it.isNotEmpty() }

            val score = rec.optJSONObject("score")
            val stage = score?.optJSONObject("stage_summary")

            val light = stage?.optLong("total_light_sleep_time_milli", 0L) ?: 0L
            val sws = stage?.optLong("total_slow_wave_sleep_time_milli", 0L) ?: 0L
            val rem = stage?.optLong("total_rem_sleep_time_milli", 0L) ?: 0L
            val durationMs = score?.optLong("sleep_duration_milli", 0L) ?: 0L

            val hours = (if (durationMs > 0) durationMs else light + sws + rem) / 3_600_000.0
            val dist = stage?.optInt("disturbance_count", 0) ?: 0

            val perf = score?.optDouble("sleep_performance_percentage", Double.NaN) ?: Double.NaN
            val eff = score?.optDouble("sleep_efficiency_percentage", Double.NaN) ?: Double.NaN

            val fellIso = parseTimestamp(rec, "start")
            val wokeIso = parseTimestamp(rec, "end")

            if (DataCollectionSettings.isEnabledForWhoop(context, "sleep_duration_daily")) {
                metrics.upsertSleepDurationDaily(access, dateSql, hours, "whoop", sourceId)
            }
            if (DataCollectionSettings.isEnabledForWhoop(context, "fell_asleep_time_daily")) {
                fellIso?.let { metrics.upsertFellAsleepTimeDaily(access, dateSql, it, "whoop", sourceId) }
            }
            if (DataCollectionSettings.isEnabledForWhoop(context, "woke_up_time_daily")) {
                wokeIso?.let { metrics.upsertWokeUpTimeDaily(access, dateSql, it, "whoop", sourceId) }
            }
            if (DataCollectionSettings.isEnabledForWhoop(context, "sleep_disturbances_daily")) {
                metrics.upsertSleepDisturbancesDaily(access, dateSql, dist, "whoop", sourceId)
            }
            if (DataCollectionSettings.isEnabledForWhoop(context, "sleep_stages_daily")) {
                metrics.upsertSleepStagesDaily(access, dateSql, sws, rem, light, "whoop", sourceId)
            }

            if (DataCollectionSettings.isEnabledForWhoop(context, "sleep_score_daily") && !perf.isNaN()) {
                metrics.upsertSleepScoreDaily(access, dateSql, perf, "whoop", sourceId)
            }

            if (DataCollectionSettings.isEnabledForWhoop(context, "sleep_efficiency_daily") && !eff.isNaN()) {
                metrics.upsertSleepEfficiencyDaily(access, dateSql, eff, "whoop", sourceId)
            }
        }

        private fun parseTimestamp(rec: JSONObject, key: String): String? {
            val utc = rec.optString(key).takeIf { it.isNotEmpty() } ?: return null
            val tzMin = tzMinutes(rec)
            return runCatching {
                val inst = Instant.parse(utc).plusSeconds(tzMin * 60L)
                OffsetDateTime.ofInstant(inst, ZoneOffset.UTC).toString()
            }.getOrNull()
        }

        private fun tzMinutes(rec: JSONObject): Long {
            return when {
                rec.has("timezone_offset_minutes") ->
                    rec.optLong("timezone_offset_minutes", 0L)
                rec.has("timezone_offset") ->
                    rec.optLong("timezone_offset", 0L) / 60L
                else -> 0L
            }
        }
    }
}
