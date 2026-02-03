package com.migraineme

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.concurrent.TimeUnit

/**
 * Worker that pushes Health Connect outbox entries to Supabase.
 * Processes entries from the local Room database outbox.
 */
class HealthConnectPushWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "HCPushWorker"
        private const val UNIQUE_WORK_NAME = "health_connect_push_worker"
        private const val BATCH_SIZE = 50
        private const val SOURCE = "health_connect"

        fun schedule(context: Context) {
            Log.d(TAG, "Scheduling Health Connect push worker")

            val request = PeriodicWorkRequestBuilder<HealthConnectPushWorker>(
                repeatInterval = 15,
                repeatIntervalTimeUnit = TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            Log.d(TAG, "Cancelling Health Connect push worker")
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting Health Connect push")

        try {
            val accessToken = SessionStore.getValidAccessToken(applicationContext)
            if (accessToken == null) {
                Log.w(TAG, "No valid access token")
                return@withContext Result.retry()
            }

            val db = HealthConnectSyncDatabase.get(applicationContext)
            val dao = db.dao()
            val service = SupabaseHealthConnectService(applicationContext)

            var totalProcessed = 0
            var hasMore = true

            while (hasMore) {
                val batch = dao.getOutboxBatch(BATCH_SIZE)
                if (batch.isEmpty()) {
                    hasMore = false
                    continue
                }

                val processedIds = mutableListOf<Long>()

                for (item in batch) {
                    try {
                        val success = processOutboxItem(item, accessToken, service)
                        if (success) {
                            processedIds.add(item.id)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process outbox item ${item.id}: ${e.message}")
                    }
                }

                if (processedIds.isNotEmpty()) {
                    dao.deleteOutboxByIds(processedIds)
                    totalProcessed += processedIds.size
                }

                // Safety: don't loop forever
                if (batch.size < BATCH_SIZE) {
                    hasMore = false
                }
            }

            Log.d(TAG, "Health Connect push completed: $totalProcessed items processed")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Push worker failed: ${e.message}", e)
            Result.retry()
        }
    }

    private suspend fun processOutboxItem(
        item: HealthConnectOutboxEntity,
        accessToken: String,
        service: SupabaseHealthConnectService
    ): Boolean {
        if (item.operation == "DELETE") {
            // Handle deletion by source_measure_id
            return service.deleteBySourceMeasureId(
                accessToken = accessToken,
                recordType = item.recordType,
                sourceMeasureId = item.healthConnectId
            )
        }

        // UPSERT operation
        val payload = try {
            json.decodeFromString<JsonObject>(item.payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse payload: ${item.payload}")
            return false
        }

        return when (item.recordType) {
            HealthConnectRecordTypes.SLEEP -> {
                val durationMinutes = payload["duration_minutes"]?.jsonPrimitive?.long ?: 0
                val startTime = payload["start_time"]?.jsonPrimitive?.content ?: ""
                val endTime = payload["end_time"]?.jsonPrimitive?.content ?: ""
                val remMin = payload["rem_minutes"]?.jsonPrimitive?.long?.toInt() ?: 0
                val deepMin = payload["deep_minutes"]?.jsonPrimitive?.long?.toInt() ?: 0
                val lightMin = payload["light_minutes"]?.jsonPrimitive?.long?.toInt() ?: 0
                val awakeMin = payload["awake_minutes"]?.jsonPrimitive?.long?.toInt() ?: 0

                service.upsertSleep(
                    accessToken = accessToken,
                    date = item.date,
                    durationHours = durationMinutes / 60.0,
                    startTime = startTime,
                    endTime = endTime,
                    remMinutes = remMin,
                    deepMinutes = deepMin,
                    lightMinutes = lightMin,
                    awakeMinutes = awakeMin,
                    sourceId = item.healthConnectId
                )
            }

            HealthConnectRecordTypes.HRV -> {
                val valueMs = payload["value_ms"]?.jsonPrimitive?.double ?: return false
                service.upsertHrv(accessToken, item.date, valueMs, item.healthConnectId)
            }

            HealthConnectRecordTypes.RESTING_HR -> {
                val valueBpm = payload["value_bpm"]?.jsonPrimitive?.long ?: return false
                service.upsertRestingHr(accessToken, item.date, valueBpm.toDouble(), item.healthConnectId)
            }

            HealthConnectRecordTypes.STEPS -> {
                val count = payload["value_count"]?.jsonPrimitive?.long ?: return false
                service.upsertSteps(accessToken, item.date, count, item.healthConnectId)
            }

            HealthConnectRecordTypes.EXERCISE -> {
                val durationMinutes = payload["duration_minutes"]?.jsonPrimitive?.long ?: 0
                val exerciseType = payload["exercise_type"]?.jsonPrimitive?.int ?: 0
                service.upsertExercise(accessToken, item.date, durationMinutes.toInt(), exerciseType, item.healthConnectId)
            }

            HealthConnectRecordTypes.WEIGHT -> {
                val valueKg = payload["value_kg"]?.jsonPrimitive?.double ?: return false
                service.upsertWeight(accessToken, item.date, valueKg, item.healthConnectId)
            }

            HealthConnectRecordTypes.BODY_FAT -> {
                val valuePct = payload["value_pct"]?.jsonPrimitive?.double ?: return false
                service.upsertBodyFat(accessToken, item.date, valuePct, item.healthConnectId)
            }

            HealthConnectRecordTypes.HYDRATION -> {
                val valueMl = payload["value_ml"]?.jsonPrimitive?.double ?: return false
                service.upsertHydration(accessToken, item.date, valueMl, item.healthConnectId)
            }

            HealthConnectRecordTypes.BLOOD_PRESSURE -> {
                val systolic = payload["systolic_mmhg"]?.jsonPrimitive?.double ?: return false
                val diastolic = payload["diastolic_mmhg"]?.jsonPrimitive?.double ?: return false
                service.upsertBloodPressure(accessToken, item.date, systolic, diastolic, item.healthConnectId)
            }

            HealthConnectRecordTypes.BLOOD_GLUCOSE -> {
                val valueMmol = payload["value_mmol_l"]?.jsonPrimitive?.double ?: return false
                val mealType = payload["meal_type"]?.jsonPrimitive?.content ?: "GENERAL"
                service.upsertBloodGlucose(accessToken, item.date, valueMmol, mealType, item.healthConnectId)
            }

            HealthConnectRecordTypes.SPO2 -> {
                val valuePct = payload["value_pct"]?.jsonPrimitive?.double ?: return false
                service.upsertSpo2(accessToken, item.date, valuePct, item.healthConnectId)
            }

            HealthConnectRecordTypes.RESPIRATORY_RATE -> {
                val valueBpm = payload["value_bpm"]?.jsonPrimitive?.double ?: return false
                service.upsertRespiratoryRate(accessToken, item.date, valueBpm, item.healthConnectId)
            }

            HealthConnectRecordTypes.SKIN_TEMP -> {
                val valueCelsius = payload["value_celsius"]?.jsonPrimitive?.double ?: return false
                service.upsertSkinTemp(accessToken, item.date, valueCelsius, item.healthConnectId)
            }

            else -> {
                Log.w(TAG, "Unknown record type: ${item.recordType}")
                false
            }
        }
    }
}
