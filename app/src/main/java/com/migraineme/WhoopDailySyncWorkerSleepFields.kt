// FILE: app/src/main/java/com/migraineme/WhoopDailySyncWorkerSleepFields.kt
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Daily WHOOP sleep sync.
 * - Select record whose WAKE-UP (end) local date == target day.
 * - Writes duration, fell-asleep, woke-up, disturbances, stages, score, efficiency.
 */
class WhoopDailySyncWorkerSleepFields(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val access: String = SessionStore.readAccessToken(applicationContext)
                ?: return@withContext Result.success()

            val hasWhoop = WhoopTokenStore(applicationContext).load() != null
            if (!hasWhoop) {
                scheduleNext(applicationContext)
                debugLog("WHOOP not connected | No token found. Skipped.")
                return@withContext Result.success()
            }

            val zone = ZoneId.systemDefault()
            val todayLocal = LocalDate.now(zone)
            val dateSql = todayLocal.toString()

            val metrics = SupabaseMetricsService(applicationContext)
            if (metrics.hasSleepForDate(access, dateSql, "whoop")) {
                scheduleNext(applicationContext)
                debugLog("WHOOP skip | already have sleep_duration_daily for $dateSql")
                return@withContext Result.success()
            }

            WhoopAuthService().refresh(applicationContext)

            val (wStart, wEnd) = dayStraddlingWindow(todayLocal, zone)
            val api = WhoopApiService(applicationContext)
            val sleepJson: JSONObject? = api.getSleep(wStart, wEnd)
            if (sleepJson == null) {
                scheduleNext(applicationContext)
                debugLog("WHOOP sleep | Null response for $todayLocal")
                return@withContext Result.success()
            }

            val record = selectRecordForDate(sleepJson, todayLocal, zone)
            if (record == null) {
                scheduleNext(applicationContext)
                debugLog("WHOOP sleep | No record waking on $todayLocal")
                return@withContext Result.success()
            }

            writeAllForDate(metrics, access, dateSql, record)

            scheduleNext(applicationContext)
            debugLog("WHOOP sync attempted | date=$dateSql id=${optStringOrNull(record, "id") ?: "NA"}")
            Result.success()
        } catch (t: Throwable) {
            scheduleNext(applicationContext)
            debugLog("WHOOP sync error | ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
            Result.success()
        }
    }

    private fun debugLog(msg: String) = Log.d("WhoopDailySync", msg)

    companion object {
        private const val UNIQUE_WORK_NAME = "whoop_daily_sync_sleep_fields_9am"

        fun scheduleNext(context: Context) {
            val now = ZonedDateTime.now(ZoneId.systemDefault())
            var next = now.withHour(9).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delayMillis = Duration.between(now, next).toMillis()
            val req = OneTimeWorkRequestBuilder<WhoopDailySyncWorkerSleepFields>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }

        fun runOnceNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<WhoopDailySyncWorkerSleepFields>()
                .setInitialDelay(0, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }

        /** Backfill from (latest+1) to yesterday inclusive. */
        suspend fun backfillUpToYesterday(context: Context, accessToken: String) {
            try {
                val zone = ZoneId.systemDefault()
                val today = LocalDate.now(zone)
                val yesterday = today.minusDays(1)

                val hasWhoop = WhoopTokenStore(context).load() != null
                if (!hasWhoop) return

                val metrics = SupabaseMetricsService(context)
                val latest = metrics.latestSleepDate(accessToken, source = "whoop") ?: return

                var cur = LocalDate.parse(latest).plusDays(1)
                if (!cur.isBefore(today)) return

                WhoopAuthService().refresh(context)
                val api = WhoopApiService(context)

                while (!cur.isAfter(yesterday)) {
                    val dateSql = cur.toString()
                    if (!metrics.hasSleepForDate(accessToken, dateSql, "whoop")) {
                        val (wStart, wEnd) = dayStraddlingWindow(cur, zone)
                        val root: JSONObject? = api.getSleep(wStart, wEnd)
                        if (root != null) {
                            val record = selectRecordForDate(root, cur, zone)
                            if (record != null) {
                                writeAllForDate(metrics, accessToken, dateSql, record)
                            }
                        }
                    }
                    cur = cur.plusDays(1)
                }
            } catch (_: Throwable) {
                // best-effort
            }
        }

        /** Window from previous day 12:00 to next day 12:00 in device zone. */
        private fun dayStraddlingWindow(day: LocalDate, zone: ZoneId): Pair<Date, Date> {
            val start = day.minusDays(1).atTime(12, 0).atZone(zone).toInstant()
            val end = day.plusDays(1).atTime(12, 0).atZone(zone).toInstant()
            return Date.from(start) to Date.from(end)
        }

        /** Pick the record whose wake-up local date equals target; fallback to latest end within window. */
        private fun selectRecordForDate(root: JSONObject, target: LocalDate, zone: ZoneId): JSONObject? {
            var bestExact: JSONObject? = null
            var latestEnd: JSONObject? = null
            var latestEndTs: Instant? = null

            fun consider(obj: JSONObject?) {
                if (obj == null) return
                val endUtc = optStringOrNull(obj, "end") ?: return
                val tzMinutes = timezoneMinutes(obj)
                val endInstantLocal = try {
                    Instant.parse(endUtc).plusSeconds(tzMinutes.toLong() * 60L)
                } catch (_: Throwable) { return }
                val endDateLocal = endInstantLocal.atZone(zone).toLocalDate()

                if (endDateLocal == target) bestExact = obj
                if (latestEndTs == null || endInstantLocal.isAfter(latestEndTs)) {
                    latestEndTs = endInstantLocal
                    latestEnd = obj
                }
            }

            val keys = arrayOf("records", "data", "items")
            for (k in keys) {
                val arr = root.optJSONArray(k) ?: continue
                var i = 0
                while (i < arr.length()) {
                    consider(arr.optJSONObject(i))
                    i++
                }
            }
            if (bestExact != null) return bestExact
            if (latestEnd != null) return latestEnd
            if (root.has("end")) {
                consider(root)
                return bestExact ?: latestEnd
            }
            return null
        }

        /** Write all fields for the selected record, including score/efficiency when present. */
        private suspend fun writeAllForDate(metrics: SupabaseMetricsService, access: String, dateSql: String, record: JSONObject) {
            val sourceId = optStringOrNull(record, "id")

            val score = record.optJSONObject("score")
            val stage = score?.optJSONObject("stage_summary")

            val lightMs = stage?.optLong("total_light_sleep_time_milli", 0L) ?: 0L
            val swsMs = stage?.optLong("total_slow_wave_sleep_time_milli", 0L) ?: 0L
            val remMs = stage?.optLong("total_rem_sleep_time_milli", 0L) ?: 0L

            val durationMs = score?.optLong("sleep_duration_milli", 0L) ?: 0L
            val stageSumMs = max(0L, lightMs) + max(0L, swsMs) + max(0L, remMs)
            val hours = (if (durationMs > 0L) durationMs else stageSumMs) / 3_600_000.0

            val disturbances = stage?.optInt("disturbance_count", 0) ?: 0

            // WHOOP score fields (percentages, 0..100)
            val performancePct = score?.optDouble("sleep_performance_percentage", Double.NaN)
            val efficiencyPct = score?.optDouble("sleep_efficiency_percentage", Double.NaN)

            val fellAsleepAtIso = parseFellAsleepAt(record)
            val wokeUpAtIso = parseWokeUpAt(record)

            metrics.upsertSleepDurationDaily(access, dateSql, hours, "whoop", sourceId)
            fellAsleepAtIso?.let { metrics.upsertFellAsleepTimeDaily(access, dateSql, it, "whoop", sourceId) }
            wokeUpAtIso?.let { metrics.upsertWokeUpTimeDaily(access, dateSql, it, "whoop", sourceId) }
            metrics.upsertSleepDisturbancesDaily(access, dateSql, disturbances, "whoop", sourceId)
            metrics.upsertSleepStagesDaily(access, dateSql, swsMs, remMs, lightMs, "whoop", sourceId)

            if (performancePct != null && !performancePct.isNaN()) {
                metrics.upsertSleepScoreDaily(access, dateSql, performancePct, "whoop", sourceId)
            }
            if (efficiencyPct != null && !efficiencyPct.isNaN()) {
                metrics.upsertSleepEfficiencyDaily(access, dateSql, efficiencyPct, "whoop", sourceId)
            }

            Log.d(
                "WhoopDailySync",
                "WHOOP write | date=$dateSql hours=${"%.2f".format(hours)} score=${performancePct ?: "NA"} eff=${efficiencyPct ?: "NA"} " +
                        "dist=$disturbances SWS=${swsMs}ms REM=${remMs}ms Light=${lightMs}ms " +
                        "fellAsleepAt=${fellAsleepAtIso ?: "NA"} wokeUpAt=${wokeUpAtIso ?: "NA"} id=${sourceId ?: "NA"}"
            )
        }

        private fun parseFellAsleepAt(record: JSONObject): String? {
            val startIsoUtc = optStringOrNull(record, "start") ?: return null
            val tzMinutes = timezoneMinutes(record)
            return try {
                val instant = Instant.parse(startIsoUtc).plusSeconds(tzMinutes.toLong() * 60L)
                java.time.OffsetDateTime.ofInstant(instant, ZoneOffset.UTC).toString()
            } catch (_: Throwable) { null }
        }

        private fun parseWokeUpAt(record: JSONObject): String? {
            val endIsoUtc = optStringOrNull(record, "end") ?: return null
            val tzMinutes = timezoneMinutes(record)
            return try {
                val instant = Instant.parse(endIsoUtc).plusSeconds(tzMinutes.toLong() * 60L)
                java.time.OffsetDateTime.ofInstant(instant, ZoneOffset.UTC).toString()
            } catch (_: Throwable) { null }
        }

        private fun timezoneMinutes(record: JSONObject): Int {
            return when {
                record.has("timezone_offset_minutes") -> record.optInt("timezone_offset_minutes", 0)
                record.has("timezone_offset") -> (record.optInt("timezone_offset", 0) / 60.0).toInt()
                else -> 0
            }
        }

        private fun optStringOrNull(obj: JSONObject, key: String): String? =
            obj.optString(key).takeIf { it.isNotEmpty() }
    }
}
