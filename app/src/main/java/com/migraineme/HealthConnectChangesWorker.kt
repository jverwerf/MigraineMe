@file:OptIn(androidx.health.connect.client.feature.ExperimentalMindfulnessSessionApi::class)

package com.migraineme

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass

/**
 * Worker that detects changes in Health Connect and queues them to the outbox.
 * Handles ALL supported Health Connect record types.
 * 
 * Pattern: Health Connect → Changes Worker → Room Outbox → Push Worker → Supabase
 * 
 * TRIGGERING: This worker is triggered by FCM push (sync_hourly) from the backend.
 * It is NOT scheduled locally - the backend controls when syncs happen.
 * 
 * FILTERING: This worker checks metric_settings from Supabase before collecting.
 * If a metric is disabled, data will NOT be collected for that record type.
 * This means user toggles in DataSettings actually control what gets synced.
 */
class HealthConnectChangesWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "HCChangesWorker"
        private const val BACKFILL_DAYS = 14L
        
        // Notification constants for foreground service
        private const val NOTIFICATION_CHANNEL_ID = "health_connect_sync"
        private const val NOTIFICATION_ID = 1001

        // All supported record types with their permissions
        val SUPPORTED_RECORDS: Map<KClass<out Record>, String> = mapOf(
            SleepSessionRecord::class to HealthConnectRecordTypes.SLEEP,
            HeartRateVariabilityRmssdRecord::class to HealthConnectRecordTypes.HRV,
            RestingHeartRateRecord::class to HealthConnectRecordTypes.RESTING_HR,
            StepsRecord::class to HealthConnectRecordTypes.STEPS,
            ExerciseSessionRecord::class to HealthConnectRecordTypes.EXERCISE,
            OxygenSaturationRecord::class to HealthConnectRecordTypes.SPO2,
            RespiratoryRateRecord::class to HealthConnectRecordTypes.RESPIRATORY_RATE,
            BodyTemperatureRecord::class to HealthConnectRecordTypes.SKIN_TEMP,
            ActiveCaloriesBurnedRecord::class to HealthConnectRecordTypes.ACTIVE_CALORIES,
            BloodGlucoseRecord::class to HealthConnectRecordTypes.BLOOD_GLUCOSE
        )

        /**
         * Maps Health Connect record types to their corresponding metric names in metric_settings.
         * This is used to check if a metric is enabled before collecting data.
         */
        private val RECORD_TYPE_TO_METRIC: Map<String, String> = mapOf(
            HealthConnectRecordTypes.SLEEP to "sleep_duration_daily",
            HealthConnectRecordTypes.HRV to "hrv_daily",
            HealthConnectRecordTypes.RESTING_HR to "resting_hr_daily",
            HealthConnectRecordTypes.STEPS to "steps_daily",
            HealthConnectRecordTypes.EXERCISE to "time_in_high_hr_zones_daily",
            HealthConnectRecordTypes.SPO2 to "spo2_daily",
            HealthConnectRecordTypes.RESPIRATORY_RATE to "respiratory_rate_daily",
            HealthConnectRecordTypes.SKIN_TEMP to "skin_temp_daily",
            HealthConnectRecordTypes.ACTIVE_CALORIES to "strain_daily"
        )

        fun getRequiredPermissions(): Set<String> = SUPPORTED_RECORDS.keys.map {
            HealthPermission.getReadPermission(it)
        }.toSet() + (if (MonitorCardConfig.GOALS_ENABLED) setOf(
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(MindfulnessSessionRecord::class)
        ) else emptySet())
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Creates notification channel for Android O+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                tSync("Health Data Sync"),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = tSync("Syncing health data from Health Connect")
            }
            val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    /**
     * Provides ForegroundInfo for running as a foreground service.
     * This is required for Health Connect background access.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tSync("Syncing Health Data"))
            .setContentText(tSync("Reading data from Health Connect..."))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Run as foreground service to get Health Connect access
        setForeground(getForegroundInfo())
        
        Log.d(TAG, "Starting Health Connect changes sync")

        try {
            if (HealthConnectClient.getSdkStatus(applicationContext) != HealthConnectClient.SDK_AVAILABLE) {
                Log.w(TAG, "Health Connect not available")
                return@withContext Result.success()
            }

            val hc = HealthConnectClient.getOrCreate(applicationContext)
            val granted = hc.permissionController.getGrantedPermissions()
            
            val db = HealthConnectSyncDatabase.get(applicationContext)
            val dao = db.dao()
            
            var syncState = dao.getSyncState() ?: HealthConnectSyncStateEntity()

            // Fetch enabled metrics from Supabase metric_settings
            val enabledMetrics = fetchEnabledHealthConnectMetrics()
            Log.d(TAG, "Enabled HC metrics: $enabledMetrics")

            // Process each record type that has permission granted AND is enabled
            for ((recordClass, recordType) in SUPPORTED_RECORDS) {
                val permission = HealthPermission.getReadPermission(recordClass)
                if (permission !in granted) {
                    continue
                }

                // Check if this metric is enabled in metric_settings
                val metricName = RECORD_TYPE_TO_METRIC[recordType]
                if (metricName != null && metricName !in enabledMetrics) {
                    Log.d(TAG, "Skipping $recordType - metric '$metricName' is disabled")
                    continue
                }

                try {
                    syncState = processRecordType(hc, dao, syncState, recordClass, recordType)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing $recordType: ${e.message}", e)
                }
            }

            // Practitioner heart-rate goals: computed from raw samples, written
            // straight to Supabase (no outbox: two days, a handful of rows, idempotent).
            try {
                syncHrThresholdGoals(hc, granted)
            } catch (e: Exception) {
                Log.e(TAG, "HR threshold goals failed: ${e.message}", e)
            }

            // Meditation goals: mindfulness sessions, same direct-write path.
            try {
                syncMindfulness(hc, granted)
            } catch (e: Exception) {
                Log.e(TAG, "Mindfulness sync failed: ${e.message}", e)
            }

            // Update sync state
            dao.upsertSyncState(syncState.copy(lastSyncAtEpochMs = System.currentTimeMillis()))

            Log.d(TAG, "Health Connect changes sync completed")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Changes worker failed: ${e.message}", e)
            Result.retry()
        }
    }

    /**
     * Fetches the set of enabled metric names from Supabase metric_settings.
     * Only returns metrics where:
     * - enabled = true
     * - preferred_source = "health_connect" OR allowed_sources contains "health_connect"
     * 
     * @return Set of enabled metric names (e.g., "hrv_daily", "weight_daily")
     */
    private suspend fun fetchEnabledHealthConnectMetrics(): Set<String> {
        return try {
            val edge = EdgeFunctionsService()
            val settings = edge.getMetricSettings(applicationContext)
            
            settings
                .filter { setting ->
                    setting.enabled && isHealthConnectSource(setting)
                }
                .map { it.metric }
                .toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch metric_settings: ${e.message}")
            // Fail-closed: if we can't verify what's enabled, don't sync anything.
            // Data will be picked up on the next successful run.
            emptySet()
        }
    }

    /**
     * Checks if a metric setting is configured to use Health Connect as source.
     */
    private fun isHealthConnectSource(setting: EdgeFunctionsService.MetricSettingResponse): Boolean {
        val preferredSource = setting.preferredSource?.lowercase() ?: ""
        val allowedSources = setting.allowedSources?.map { it.lowercase() } ?: emptyList()
        
        return preferredSource == "health_connect" || 
               allowedSources.contains("health_connect")
    }

    private suspend fun processRecordType(
        hc: HealthConnectClient,
        dao: HealthConnectSyncDao,
        state: HealthConnectSyncStateEntity,
        recordClass: KClass<out Record>,
        recordType: String
    ): HealthConnectSyncStateEntity {
        
        val existingToken = getTokenForType(state, recordType)
        
        if (existingToken == null) {
            // First run: backfill
            Log.d(TAG, "No token for $recordType - doing backfill")
            return doBackfill(hc, dao, state, recordClass, recordType)
        } else {
            // Incremental sync
            Log.d(TAG, "Processing changes for $recordType")
            return processChanges(hc, dao, state, recordClass, recordType, existingToken)
        }
    }

    private suspend fun doBackfill(
        hc: HealthConnectClient,
        dao: HealthConnectSyncDao,
        state: HealthConnectSyncStateEntity,
        recordClass: KClass<out Record>,
        recordType: String
    ): HealthConnectSyncStateEntity {
        
        val end = Instant.now()
        val start = end.minus(BACKFILL_DAYS, ChronoUnit.DAYS)

        val records = hc.readRecords(
            ReadRecordsRequest(
                recordType = recordClass,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        ).records

        Log.d(TAG, "Backfilling ${records.size} $recordType records")

        val outboxItems = records.mapNotNull { record ->
            recordToOutboxEntry(hc, record, recordType, "UPSERT")
        }

        if (outboxItems.isNotEmpty()) {
            dao.insertOutboxBatch(outboxItems)
        }

        // Create token for future syncs
        val newToken = hc.getChangesToken(
            ChangesTokenRequest(recordTypes = setOf(recordClass))
        )

        return setTokenForType(state, recordType, newToken)
    }

    private suspend fun processChanges(
        hc: HealthConnectClient,
        dao: HealthConnectSyncDao,
        state: HealthConnectSyncStateEntity,
        recordClass: KClass<out Record>,
        recordType: String,
        token: String
    ): HealthConnectSyncStateEntity {
        
        var nextToken = token
        var hasMore = true
        var safety = 0

        while (hasMore && safety < 50) {
            safety++
            
            val resp = hc.getChanges(nextToken)
            nextToken = resp.nextChangesToken
            hasMore = resp.hasMore

            if (resp.changesTokenExpired) {
                Log.w(TAG, "$recordType token expired, creating new one")
                nextToken = hc.getChangesToken(
                    ChangesTokenRequest(recordTypes = setOf(recordClass))
                )
                break
            }

            val outboxItems = mutableListOf<HealthConnectOutboxEntity>()

            for (change in resp.changes) {
                when (change) {
                    is UpsertionChange -> {
                        val entry = recordToOutboxEntry(hc, change.record, recordType, "UPSERT")
                        if (entry != null) outboxItems.add(entry)
                    }
                    is DeletionChange -> {
                        outboxItems.add(
                            HealthConnectOutboxEntity(
                                healthConnectId = change.recordId,
                                recordType = recordType,
                                operation = "DELETE",
                                date = "",
                                payload = "{}"
                            )
                        )
                    }
                }
            }

            if (outboxItems.isNotEmpty()) {
                dao.insertOutboxBatch(outboxItems)
            }
        }

        return setTokenForType(state, recordType, nextToken)
    }

    private suspend fun recordToOutboxEntry(hc: HealthConnectClient, record: Record, recordType: String, operation: String): HealthConnectOutboxEntity? {
        return try {
            val (date, payload) = extractRecordData(hc, record, recordType)
            HealthConnectOutboxEntity(
                healthConnectId = record.metadata.id,
                recordType = recordType,
                operation = operation,
                date = date,
                payload = payload
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert record to outbox entry: ${e.message}")
            null
        }
    }

    private suspend fun extractRecordData(hc: HealthConnectClient, record: Record, recordType: String): Pair<String, String> {
        return when (record) {
            is SleepSessionRecord -> {
                val date = record.endTime.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val duration = Duration.between(record.startTime, record.endTime).toMinutes()
                
                var remMin = 0L; var deepMin = 0L; var lightMin = 0L; var awakeMin = 0L
                var awakeCount = 0
                for (stage in record.stages) {
                    val mins = Duration.between(stage.startTime, stage.endTime).toMinutes()
                    when (stage.stage) {
                        SleepSessionRecord.STAGE_TYPE_REM -> remMin += mins
                        SleepSessionRecord.STAGE_TYPE_DEEP -> deepMin += mins
                        SleepSessionRecord.STAGE_TYPE_LIGHT -> lightMin += mins
                        SleepSessionRecord.STAGE_TYPE_AWAKE -> { awakeMin += mins; awakeCount += 1 }
                    }
                }

                val payload = """{"duration_minutes":$duration,"start_time":"${record.startTime}","end_time":"${record.endTime}","rem_minutes":$remMin,"deep_minutes":$deepMin,"light_minutes":$lightMin,"awake_minutes":$awakeMin,"awake_count":$awakeCount}"""
                date to payload
            }
            
            is HeartRateVariabilityRmssdRecord -> {
                val date = record.time.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val payload = """{"value_ms":${record.heartRateVariabilityMillis}}"""
                date to payload
            }
            
            is RestingHeartRateRecord -> {
                val date = record.time.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val payload = """{"value_bpm":${record.beatsPerMinute}}"""
                date to payload
            }
            
            is StepsRecord -> {
                val date = record.endTime.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val payload = """{"value_count":${record.count}}"""
                date to payload
            }
            
            is ExerciseSessionRecord -> {
                val date = record.endTime.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val duration = Duration.between(record.startTime, record.endTime).toMinutes()
                val payload = """{"duration_minutes":$duration,"exercise_type":${record.exerciseType},"start_time":"${record.startTime}","end_time":"${record.endTime}"}"""
                date to payload
            }
            
            is OxygenSaturationRecord -> {
                val date = record.time.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val payload = """{"value_pct":${record.percentage.value}}"""
                date to payload
            }
            
            is BloodGlucoseRecord -> {
                val date = record.time.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                // Health Connect stores glucose as a BloodGlucose unit; the column is mmol/L.
                val payload = """{"value_mmol_l":${record.level.inMillimolesPerLiter}}"""
                date to payload
            }

            is RespiratoryRateRecord -> {
                val date = record.time.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val payload = """{"value_bpm":${record.rate}}"""
                date to payload
            }
            
            is ActiveCaloriesBurnedRecord -> {
                val date = record.endTime.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val kcal = record.energy.inKilocalories
                val payload = """{"value_kcal":$kcal}"""
                date to payload
            }

            is BodyTemperatureRecord -> {
                val date = record.time.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val todayCelsius = record.temperature.inCelsius
                // Compute deviation against a 7-day baseline so format matches Whoop/Oura.
                val end = record.time
                val start = end.minus(7, ChronoUnit.DAYS)
                val history = runCatching {
                    hc.readRecords(
                        ReadRecordsRequest(
                            recordType = BodyTemperatureRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(start, end)
                        )
                    ).records
                }.getOrDefault(emptyList())
                val baselineSamples = history.map { it.temperature.inCelsius }.filter { it > 0 }
                if (baselineSamples.isEmpty()) {
                    // No baseline yet — skip writing so we don't pollute with absolute temps.
                    throw IllegalStateException("skin_temp: no 7-day baseline available")
                }
                val baseline = baselineSamples.average()
                val deviation = todayCelsius - baseline
                date to """{"value_celsius":$deviation}"""
            }
            
            else -> throw IllegalArgumentException("Unsupported record type: ${record::class.simpleName}")
        }
    }

    /**
     * For every active hr_threshold goal: today's and yesterday's heart rate
     * samples (local days) → minutes at or above the threshold and the longest
     * stretch, one hr_threshold_daily row per (day, threshold). Same rule as
     * the server uses for Garmin, see [HrThresholdMath]. Needs the heart rate
     * read permission; without it there is nothing to measure and the detail
     * screen says so.
     */
    private suspend fun syncHrThresholdGoals(hc: HealthConnectClient, granted: Set<String>) {
        if (HealthPermission.getReadPermission(HeartRateRecord::class) !in granted) return
        val token = SessionStore.getValidAccessToken(applicationContext) ?: return
        val userId = SessionStore.readUserId(applicationContext) ?: return
        val goals = PractitionerGoalsStore.goals.value.ifEmpty {
            runCatching { SupabasePractitionerService.myGoals(token) }.getOrDefault(emptyList())
        }
        val thresholds = goals.filter { it.isHr && it.isActive }.mapNotNull { it.threshold_bpm }.toSet()
        if (thresholds.isEmpty()) return

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val rows = mutableListOf<SupabasePractitionerService.HrThresholdDailyRow>()
        for (day in listOf(today.minusDays(1), today)) {
            val start = day.atStartOfDay(zone).toInstant()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant()
            val samples = mutableListOf<Pair<Long, Long>>()
            var pageToken: String? = null
            do {
                val resp = hc.readRecords(
                    ReadRecordsRequest(
                        recordType = HeartRateRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end),
                        pageToken = pageToken
                    )
                )
                for (rec in resp.records) for (smp in rec.samples) {
                    if (smp.time >= start && smp.time < end) samples += smp.time.toEpochMilli() to smp.beatsPerMinute
                }
                pageToken = resp.pageToken
            } while (pageToken != null)
            if (samples.isEmpty()) continue
            samples.sortBy { it.first }
            for (th in thresholds) {
                val (above, longest) = HrThresholdMath.compute(samples, th)
                rows += SupabasePractitionerService.HrThresholdDailyRow(
                    user_id = userId, date = day.toString(), threshold_bpm = th,
                    minutes_above = above, longest_run_minutes = longest
                )
            }
        }
        Log.d(TAG, "HR threshold goals: ${rows.size} rows for ${thresholds.size} thresholds")
        SupabasePractitionerService.upsertHrThresholdDaily(token, rows)
    }

    /**
     * Meditation goals: mindfulness sessions from yesterday 00:00 (local) to
     * now → minutes and session count per local day (a session counts on the
     * day it started), one mindfulness_daily row per day that has a session.
     * Only when goals are on, the device's Health Connect has mindfulness
     * sessions, and the read permission is granted.
     */
    private suspend fun syncMindfulness(hc: HealthConnectClient, granted: Set<String>) {
        if (!MonitorCardConfig.GOALS_ENABLED) return
        if (hc.features.getFeatureStatus(HealthConnectFeatures.FEATURE_MINDFULNESS_SESSION) !=
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        ) return
        if (HealthPermission.getReadPermission(MindfulnessSessionRecord::class) !in granted) return
        val token = SessionStore.getValidAccessToken(applicationContext) ?: return
        val userId = SessionStore.readUserId(applicationContext) ?: return

        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).minusDays(1).atStartOfDay(zone).toInstant()
        val now = Instant.now()
        val secondsByDay = mutableMapOf<LocalDate, Long>()
        val countByDay = mutableMapOf<LocalDate, Int>()
        var pageToken: String? = null
        do {
            val resp = hc.readRecords(
                ReadRecordsRequest(
                    recordType = MindfulnessSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, now),
                    pageToken = pageToken
                )
            )
            for (rec in resp.records) {
                if (rec.startTime < start) continue
                val day = rec.startTime.atZone(zone).toLocalDate()
                val seconds = Duration.between(rec.startTime, rec.endTime).seconds.coerceAtLeast(0)
                secondsByDay[day] = (secondsByDay[day] ?: 0L) + seconds
                countByDay[day] = (countByDay[day] ?: 0) + 1
            }
            pageToken = resp.pageToken
        } while (pageToken != null)

        val rows = countByDay.map { (day, count) ->
            SupabasePractitionerService.MindfulnessDailyRow(
                user_id = userId, date = day.toString(),
                duration_minutes = Math.round((secondsByDay[day] ?: 0L) / 60.0).toInt(),
                session_count = count
            )
        }
        Log.d(TAG, "Mindfulness: ${rows.size} day rows")
        SupabasePractitionerService.upsertMindfulnessDaily(token, rows)
    }

    private fun getTokenForType(state: HealthConnectSyncStateEntity, recordType: String): String? {
        return when (recordType) {
            HealthConnectRecordTypes.SLEEP -> state.sleepToken
            HealthConnectRecordTypes.HRV -> state.hrvToken
            HealthConnectRecordTypes.RESTING_HR -> state.restingHrToken
            HealthConnectRecordTypes.STEPS -> state.stepsToken
            HealthConnectRecordTypes.EXERCISE -> state.exerciseToken
            HealthConnectRecordTypes.SPO2 -> state.spo2Token
            HealthConnectRecordTypes.RESPIRATORY_RATE -> state.respiratoryRateToken
            HealthConnectRecordTypes.SKIN_TEMP -> state.skinTempToken
            else -> null
        }
    }

    private fun setTokenForType(state: HealthConnectSyncStateEntity, recordType: String, token: String): HealthConnectSyncStateEntity {
        return when (recordType) {
            HealthConnectRecordTypes.SLEEP -> state.copy(sleepToken = token)
            HealthConnectRecordTypes.HRV -> state.copy(hrvToken = token)
            HealthConnectRecordTypes.RESTING_HR -> state.copy(restingHrToken = token)
            HealthConnectRecordTypes.STEPS -> state.copy(stepsToken = token)
            HealthConnectRecordTypes.EXERCISE -> state.copy(exerciseToken = token)
            HealthConnectRecordTypes.SPO2 -> state.copy(spo2Token = token)
            HealthConnectRecordTypes.RESPIRATORY_RATE -> state.copy(respiratoryRateToken = token)
            HealthConnectRecordTypes.SKIN_TEMP -> state.copy(skinTempToken = token)
            else -> state
        }
    }
}


/**
 * Time at or above a heart rate threshold from raw samples. The rule, shared
 * with the server's Garmin path so both sources read the same:
 *  - samples sorted by time; each covers the time until the next sample,
 *    capped at 60 s (the last one covers 15 s);
 *  - a stretch starts at the first sample at or above the threshold, survives
 *    dips below it of up to 60 s, and ends on a gap in samples over 60 s or a
 *    longer dip;
 *  - longest_run = the longest stretch; minutes_above = the covered seconds of
 *    every sample at or above the threshold. Both rounded to 0.1 min.
 */
object HrThresholdMath {
    private const val CAP_S = 60.0
    private const val LAST_S = 15.0
    private const val DIP_S = 60.0
    private const val GAP_S = 60.0

    /** [samples] = (epoch millis, bpm), sorted ascending. Returns (minutesAbove, longestRunMinutes). */
    fun compute(samples: List<Pair<Long, Long>>, threshold: Int): Pair<Double, Double> {
        var aboveS = 0.0
        var longestS = 0.0
        var runStartMs: Long? = null
        var runEndMs = 0L
        var dipSinceMs: Long? = null
        for (i in samples.indices) {
            val (t, bpm) = samples[i]
            val next = samples.getOrNull(i + 1)?.first
            val coverS = if (next == null) LAST_S else minOf((next - t) / 1000.0, CAP_S)
            val gapS = if (i == 0) 0.0 else (t - samples[i - 1].first) / 1000.0
            if (runStartMs != null && gapS > GAP_S) {
                longestS = maxOf(longestS, (runEndMs - runStartMs) / 1000.0)
                runStartMs = null; dipSinceMs = null
            }
            if (bpm >= threshold) {
                aboveS += coverS
                if (runStartMs == null) runStartMs = t
                dipSinceMs = null
                runEndMs = t + (coverS * 1000).toLong()
            } else if (runStartMs != null) {
                if (dipSinceMs == null) dipSinceMs = t
                val dipS = (t - dipSinceMs) / 1000.0 + coverS
                if (dipS > DIP_S) {
                    longestS = maxOf(longestS, (runEndMs - runStartMs) / 1000.0)
                    runStartMs = null; dipSinceMs = null
                }
            }
        }
        if (runStartMs != null) longestS = maxOf(longestS, (runEndMs - runStartMs) / 1000.0)
        fun tenthMin(s: Double) = Math.round(s / 60.0 * 10.0) / 10.0
        return tenthMin(aboveS) to tenthMin(longestS)
    }
}
