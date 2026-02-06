package com.migraineme

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
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
            WeightRecord::class to HealthConnectRecordTypes.WEIGHT,
            BodyFatRecord::class to HealthConnectRecordTypes.BODY_FAT,
            HydrationRecord::class to HealthConnectRecordTypes.HYDRATION,
            BloodPressureRecord::class to HealthConnectRecordTypes.BLOOD_PRESSURE,
            BloodGlucoseRecord::class to HealthConnectRecordTypes.BLOOD_GLUCOSE,
            OxygenSaturationRecord::class to HealthConnectRecordTypes.SPO2,
            RespiratoryRateRecord::class to HealthConnectRecordTypes.RESPIRATORY_RATE,
            BodyTemperatureRecord::class to HealthConnectRecordTypes.SKIN_TEMP
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
            HealthConnectRecordTypes.WEIGHT to "weight_daily",
            HealthConnectRecordTypes.BODY_FAT to "body_fat_daily",
            HealthConnectRecordTypes.HYDRATION to "hydration_daily",
            HealthConnectRecordTypes.BLOOD_PRESSURE to "blood_pressure_daily",
            HealthConnectRecordTypes.BLOOD_GLUCOSE to "blood_glucose_daily",
            HealthConnectRecordTypes.SPO2 to "spo2_daily",
            HealthConnectRecordTypes.RESPIRATORY_RATE to "respiratory_rate_daily",
            HealthConnectRecordTypes.SKIN_TEMP to "skin_temp_daily"
        )

        fun getRequiredPermissions(): Set<String> = SUPPORTED_RECORDS.keys.map {
            HealthPermission.getReadPermission(it)
        }.toSet()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Creates notification channel for Android O+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Health Data Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Syncing health data from Health Connect"
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
            .setSmallIcon(R.drawable.ic_health_connect)
            .setContentTitle("Syncing Health Data")
            .setContentText("Reading data from Health Connect...")
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
            // On error, return ALL metrics as enabled to avoid blocking data collection
            // This is a fail-open approach to prevent data loss on network issues
            RECORD_TYPE_TO_METRIC.values.toSet()
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
            recordToOutboxEntry(record, recordType, "UPSERT")
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
                        val entry = recordToOutboxEntry(change.record, recordType, "UPSERT")
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

    private fun recordToOutboxEntry(record: Record, recordType: String, operation: String): HealthConnectOutboxEntity? {
        return try {
            val (date, payload) = extractRecordData(record, recordType)
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

    private fun extractRecordData(record: Record, recordType: String): Pair<String, String> {
        return when (record) {
            is SleepSessionRecord -> {
                val date = record.endTime.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val duration = Duration.between(record.startTime, record.endTime).toMinutes()
                
                var remMin = 0L; var deepMin = 0L; var lightMin = 0L; var awakeMin = 0L
                for (stage in record.stages) {
                    val mins = Duration.between(stage.startTime, stage.endTime).toMinutes()
                    when (stage.stage) {
                        SleepSessionRecord.STAGE_TYPE_REM -> remMin += mins
                        SleepSessionRecord.STAGE_TYPE_DEEP -> deepMin += mins
                        SleepSessionRecord.STAGE_TYPE_LIGHT -> lightMin += mins
                        SleepSessionRecord.STAGE_TYPE_AWAKE -> awakeMin += mins
                    }
                }
                
                val payload = """{"duration_minutes":$duration,"start_time":"${record.startTime}","end_time":"${record.endTime}","rem_minutes":$remMin,"deep_minutes":$deepMin,"light_minutes":$lightMin,"awake_minutes":$awakeMin}"""
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
            
            is WeightRecord -> {
                val date = record.time.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val payload = """{"value_kg":${record.weight.inKilograms}}"""
                date to payload
            }
            
            is BodyFatRecord -> {
                val date = record.time.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val payload = """{"value_pct":${record.percentage.value}}"""
                date to payload
            }
            
            is HydrationRecord -> {
                val date = record.endTime.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val payload = """{"value_ml":${record.volume.inMilliliters}}"""
                date to payload
            }
            
            is BloodPressureRecord -> {
                val date = record.time.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val payload = """{"systolic_mmhg":${record.systolic.inMillimetersOfMercury},"diastolic_mmhg":${record.diastolic.inMillimetersOfMercury}}"""
                date to payload
            }
            
            is BloodGlucoseRecord -> {
                val date = record.time.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val mealType = record.relationToMeal?.toString() ?: "GENERAL"
                val payload = """{"value_mmol_l":${record.level.inMillimolesPerLiter},"meal_type":"$mealType"}"""
                date to payload
            }
            
            is OxygenSaturationRecord -> {
                val date = record.time.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val payload = """{"value_pct":${record.percentage.value}}"""
                date to payload
            }
            
            is RespiratoryRateRecord -> {
                val date = record.time.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val payload = """{"value_bpm":${record.rate}}"""
                date to payload
            }
            
            is BodyTemperatureRecord -> {
                val date = record.time.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                val payload = """{"value_celsius":${record.temperature.inCelsius}}"""
                date to payload
            }
            
            else -> throw IllegalArgumentException("Unsupported record type: ${record::class.simpleName}")
        }
    }

    private fun getTokenForType(state: HealthConnectSyncStateEntity, recordType: String): String? {
        return when (recordType) {
            HealthConnectRecordTypes.SLEEP -> state.sleepToken
            HealthConnectRecordTypes.HRV -> state.hrvToken
            HealthConnectRecordTypes.RESTING_HR -> state.restingHrToken
            HealthConnectRecordTypes.STEPS -> state.stepsToken
            HealthConnectRecordTypes.EXERCISE -> state.exerciseToken
            HealthConnectRecordTypes.WEIGHT -> state.weightToken
            HealthConnectRecordTypes.BODY_FAT -> state.bodyFatToken
            HealthConnectRecordTypes.HYDRATION -> state.hydrationToken
            HealthConnectRecordTypes.BLOOD_PRESSURE -> state.bloodPressureToken
            HealthConnectRecordTypes.BLOOD_GLUCOSE -> state.bloodGlucoseToken
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
            HealthConnectRecordTypes.WEIGHT -> state.copy(weightToken = token)
            HealthConnectRecordTypes.BODY_FAT -> state.copy(bodyFatToken = token)
            HealthConnectRecordTypes.HYDRATION -> state.copy(hydrationToken = token)
            HealthConnectRecordTypes.BLOOD_PRESSURE -> state.copy(bloodPressureToken = token)
            HealthConnectRecordTypes.BLOOD_GLUCOSE -> state.copy(bloodGlucoseToken = token)
            HealthConnectRecordTypes.SPO2 -> state.copy(spo2Token = token)
            HealthConnectRecordTypes.RESPIRATORY_RATE -> state.copy(respiratoryRateToken = token)
            HealthConnectRecordTypes.SKIN_TEMP -> state.copy(skinTempToken = token)
            else -> state
        }
    }
}
