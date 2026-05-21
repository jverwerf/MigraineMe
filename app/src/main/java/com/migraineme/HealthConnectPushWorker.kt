package com.migraineme

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Worker that pushes Health Connect outbox entries to Supabase.
 * Processes entries from the local Room database outbox.
 *
 * TRIGGERING: This worker is triggered by FCM push (sync_hourly) from the backend.
 * It is NOT scheduled locally - the backend controls when syncs happen.
 *
 * FLOW:
 * 1. FCM arrives with type=sync_hourly
 * 2. MigraineMeFirebaseService triggers this worker via OneTimeWorkRequest
 * 3. Worker reads PENDING outbox items from Room DB
 * 4. Worker pushes each item to Supabase
 * 5. Successful items are deleted from outbox
 * 6. Failed items have their retry count incremented
 * 7. Items exceeding MAX_RETRIES are marked as "failed" (can retry later)
 * 8. Permanent failures (4xx) are marked as "permanent_failure"
 *
 * ERROR HANDLING:
 * - Transient errors (network, 5xx): Increment retry, will try again next run
 * - Permanent errors (4xx): Mark as permanent_failure, won't retry automatically
 * - After MAX_RETRIES: Mark as failed, can be manually retried after fix deployed
 */
class HealthConnectPushWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "HCPushWorker"
        private const val BATCH_SIZE = 50
        private const val MAX_RETRIES = 5
        private const val MAX_WORKER_ATTEMPTS = 3  // Don't let WorkManager retry forever either
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting Health Connect push (attempt: $runAttemptCount)")

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
            var totalFailed = 0
            var hasMore = true

            while (hasMore) {
                // Only fetch PENDING items (not failed or permanent_failure)
                val rawBatch = dao.getPendingOutboxBatch(BATCH_SIZE)
                if (rawBatch.isEmpty()) {
                    hasMore = false
                    continue
                }

                val successIds = mutableListOf<Long>()
                val retryableFailureIds = mutableListOf<Long>()
                val permanentFailureIds = mutableListOf<Long>()
                val errorMessages = mutableMapOf<Long, String>()

                // Aggregate SLEEP items by date so duration/start/end/stages reflect
                // the sum of all sessions ending on that date, not just the last one
                // processed. Sessions other than the representative are marked success
                // (their data has been folded into the representative's payload).
                val batch = aggregateSleepByDate(rawBatch, successIds)

                for (item in batch) {
                    try {
                        val result = processOutboxItem(item, accessToken, service)

                        when (result) {
                            is ProcessResult.Success -> {
                                successIds.add(item.id)
                            }
                            is ProcessResult.RetryableFailure -> {
                                retryableFailureIds.add(item.id)
                                errorMessages[item.id] = result.message
                                Log.w(TAG, "Retryable failure for item ${item.id}: ${result.message}")
                            }
                            is ProcessResult.PermanentFailure -> {
                                permanentFailureIds.add(item.id)
                                errorMessages[item.id] = result.message
                                Log.e(TAG, "Permanent failure for item ${item.id}: ${result.message}")
                            }
                        }
                    } catch (e: Exception) {
                        // Unexpected exceptions are treated as retryable
                        retryableFailureIds.add(item.id)
                        errorMessages[item.id] = e.message ?: "Unknown error"
                        Log.e(TAG, "Exception processing outbox item ${item.id}: ${e.message}")
                    }
                }

                // Delete successful items
                if (successIds.isNotEmpty()) {
                    dao.deleteOutboxByIds(successIds)
                    totalProcessed += successIds.size
                }

                // Increment retry count for transient failures
                if (retryableFailureIds.isNotEmpty()) {
                    dao.incrementRetryCount(retryableFailureIds)
                    totalFailed += retryableFailureIds.size

                    // Update error messages for debugging
                    for (id in retryableFailureIds) {
                        errorMessages[id]?.let { dao.updateLastError(id, it) }
                    }
                }

                // Mark permanent failures
                if (permanentFailureIds.isNotEmpty()) {
                    val errorMsg = permanentFailureIds.mapNotNull { errorMessages[it] }.firstOrNull() ?: "Client error"
                    dao.markAsPermanentFailure(permanentFailureIds, errorMsg)
                    totalFailed += permanentFailureIds.size
                }

                // Safety: don't loop forever
                if (batch.size < BATCH_SIZE) {
                    hasMore = false
                }
            }

            // Mark items that have exceeded max retries as "failed" (removes them from pending queue)
            dao.markExceededRetriesAsFailed(MAX_RETRIES)

            Log.d(TAG, "Health Connect push completed: $totalProcessed succeeded, $totalFailed failed")

            // Check if there are still pending items to process
            val remainingCount = dao.getOutboxCount()  // Use existing method
            val pendingBatch = dao.getPendingOutboxBatch(1)
            val hasPendingWork = pendingBatch.isNotEmpty()
            
            when {
                !hasPendingWork -> {
                    // All done!
                    Log.d(TAG, "All items processed successfully")
                    Result.success()
                }
                totalProcessed > 0 -> {
                    // We made progress - retry to continue processing
                    Log.d(TAG, "$remainingCount items remain, made progress, scheduling retry")
                    Result.retry()
                }
                runAttemptCount >= MAX_WORKER_ATTEMPTS -> {
                    // We've tried multiple times with no progress - stop and wait for next trigger
                    // This prevents infinite retry loops when all items are failing
                    Log.w(TAG, "No progress after $runAttemptCount attempts, waiting for next scheduled run")
                    Result.success()
                }
                else -> {
                    // No progress but haven't hit limit - retry with backoff
                    Log.d(TAG, "No progress this run, will retry (attempt $runAttemptCount of $MAX_WORKER_ATTEMPTS)")
                    Result.retry()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Push worker failed: ${e.message}", e)
            
            // If we've tried too many times, give up until next trigger
            if (runAttemptCount >= MAX_WORKER_ATTEMPTS) {
                Log.w(TAG, "Worker failed after $runAttemptCount attempts, waiting for next scheduled run")
                Result.success()
            } else {
                Result.retry()
            }
        }
    }

    /**
     * Fold all SLEEP upsert items in `batch` that share a date into one representative
     * item whose payload sums duration, sums stage minutes, takes the earliest start time
     * and the latest end time. Non-representative sleep items are added to `successIds`
     * (their data is already counted in the representative).
     *
     * Without this, a date with multiple sessions (night + nap) would get one row per
     * session, and only the last upsert would survive because the row is keyed by
     * (user, date, source).
     */
    private fun aggregateSleepByDate(
        batch: List<HealthConnectOutboxEntity>,
        successIds: MutableList<Long>
    ): List<HealthConnectOutboxEntity> {
        val sleepUpserts = batch.filter {
            it.recordType == HealthConnectRecordTypes.SLEEP && it.operation == "UPSERT"
        }
        if (sleepUpserts.size < 2) return batch

        val groups = sleepUpserts.groupBy { it.date }
        val replacementsById = mutableMapOf<Long, HealthConnectOutboxEntity>()
        val droppedIds = mutableSetOf<Long>()

        for ((_, items) in groups) {
            if (items.size < 2) continue

            var totalDuration = 0L
            var totalRem = 0L
            var totalDeep = 0L
            var totalLight = 0L
            var totalAwake = 0L
            var earliestStart: String? = null
            var latestEnd: String? = null

            for (item in items) {
                val p = try { json.decodeFromString<JsonObject>(item.payload) } catch (_: Exception) { null }
                if (p == null) continue
                totalDuration += p["duration_minutes"]?.jsonPrimitive?.long ?: 0
                totalRem += p["rem_minutes"]?.jsonPrimitive?.long ?: 0
                totalDeep += p["deep_minutes"]?.jsonPrimitive?.long ?: 0
                totalLight += p["light_minutes"]?.jsonPrimitive?.long ?: 0
                totalAwake += p["awake_minutes"]?.jsonPrimitive?.long ?: 0
                val s = p["start_time"]?.jsonPrimitive?.content ?: ""
                val e = p["end_time"]?.jsonPrimitive?.content ?: ""
                if (s.isNotEmpty() && (earliestStart == null || s < earliestStart!!)) earliestStart = s
                if (e.isNotEmpty() && (latestEnd == null || e > latestEnd!!)) latestEnd = e
            }

            val rep = items.first()
            val mergedPayload = """{"duration_minutes":$totalDuration,"start_time":"${earliestStart ?: ""}","end_time":"${latestEnd ?: ""}","rem_minutes":$totalRem,"deep_minutes":$totalDeep,"light_minutes":$totalLight,"awake_minutes":$totalAwake}"""
            replacementsById[rep.id] = rep.copy(payload = mergedPayload)
            for (item in items.drop(1)) {
                droppedIds.add(item.id)
                successIds.add(item.id)
            }
        }

        if (droppedIds.isEmpty() && replacementsById.isEmpty()) return batch
        return batch.mapNotNull { item ->
            if (item.id in droppedIds) null
            else replacementsById[item.id] ?: item
        }
    }

    /**
     * Result of processing a single outbox item.
     */
    sealed class ProcessResult {
        object Success : ProcessResult()
        data class RetryableFailure(val message: String) : ProcessResult()
        data class PermanentFailure(val message: String) : ProcessResult()
    }

    private suspend fun processOutboxItem(
        item: HealthConnectOutboxEntity,
        accessToken: String,
        service: SupabaseHealthConnectService
    ): ProcessResult {
        if (item.operation == "DELETE") {
            val success = service.deleteBySourceMeasureId(
                accessToken = accessToken,
                recordType = item.recordType,
                sourceMeasureId = item.healthConnectId
            )
            return if (success) ProcessResult.Success else ProcessResult.RetryableFailure("Delete failed")
        }

        // UPSERT operation
        val payload = try {
            json.decodeFromString<JsonObject>(item.payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse payload: ${item.payload}")
            // Invalid payload is a permanent failure - retrying won't help
            return ProcessResult.PermanentFailure("Invalid payload: ${e.message}")
        }

        val success = when (item.recordType) {
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
                val valueMs = payload["value_ms"]?.jsonPrimitive?.double
                    ?: return ProcessResult.PermanentFailure("Missing value_ms")
                service.upsertHrv(accessToken, item.date, valueMs, item.healthConnectId)
            }

            HealthConnectRecordTypes.RESTING_HR -> {
                val valueBpm = payload["value_bpm"]?.jsonPrimitive?.long
                    ?: return ProcessResult.PermanentFailure("Missing value_bpm")
                service.upsertRestingHr(accessToken, item.date, valueBpm.toDouble(), item.healthConnectId)
            }

            HealthConnectRecordTypes.STEPS -> {
                val count = payload["value_count"]?.jsonPrimitive?.long
                    ?: return ProcessResult.PermanentFailure("Missing value_count")
                service.upsertSteps(accessToken, item.date, count, item.healthConnectId)
            }

            HealthConnectRecordTypes.EXERCISE -> {
                val durationMinutes = payload["duration_minutes"]?.jsonPrimitive?.long ?: 0
                val exerciseType = payload["exercise_type"]?.jsonPrimitive?.int ?: 0
                // Pass start/end times for session-based upsert
                val startTime = payload["start_time"]?.jsonPrimitive?.content
                val endTime = payload["end_time"]?.jsonPrimitive?.content
                service.upsertExercise(
                    accessToken = accessToken,
                    date = item.date,
                    durationMinutes = durationMinutes.toInt(),
                    exerciseType = exerciseType,
                    sourceId = item.healthConnectId,
                    startTime = startTime,
                    endTime = endTime
                )
            }

            HealthConnectRecordTypes.WEIGHT -> {
                val valueKg = payload["value_kg"]?.jsonPrimitive?.double
                    ?: return ProcessResult.PermanentFailure("Missing value_kg")
                service.upsertWeight(accessToken, item.date, valueKg, item.healthConnectId)
            }

            HealthConnectRecordTypes.BODY_FAT -> {
                val valuePct = payload["value_pct"]?.jsonPrimitive?.double
                    ?: return ProcessResult.PermanentFailure("Missing value_pct")
                service.upsertBodyFat(accessToken, item.date, valuePct, item.healthConnectId)
            }

            HealthConnectRecordTypes.HYDRATION -> {
                val valueMl = payload["value_ml"]?.jsonPrimitive?.double
                    ?: return ProcessResult.PermanentFailure("Missing value_ml")
                service.upsertHydration(accessToken, item.date, valueMl, item.healthConnectId)
            }

            HealthConnectRecordTypes.BLOOD_PRESSURE -> {
                val systolic = payload["systolic_mmhg"]?.jsonPrimitive?.double
                    ?: return ProcessResult.PermanentFailure("Missing systolic_mmhg")
                val diastolic = payload["diastolic_mmhg"]?.jsonPrimitive?.double
                    ?: return ProcessResult.PermanentFailure("Missing diastolic_mmhg")
                service.upsertBloodPressure(accessToken, item.date, systolic, diastolic, item.healthConnectId)
            }

            HealthConnectRecordTypes.BLOOD_GLUCOSE -> {
                val valueMmol = payload["value_mmol_l"]?.jsonPrimitive?.double
                    ?: return ProcessResult.PermanentFailure("Missing value_mmol_l")
                val mealType = payload["meal_type"]?.jsonPrimitive?.content ?: "GENERAL"
                service.upsertBloodGlucose(accessToken, item.date, valueMmol, mealType, item.healthConnectId)
            }

            HealthConnectRecordTypes.SPO2 -> {
                val valuePct = payload["value_pct"]?.jsonPrimitive?.double
                    ?: return ProcessResult.PermanentFailure("Missing value_pct")
                service.upsertSpo2(accessToken, item.date, valuePct, item.healthConnectId)
            }

            HealthConnectRecordTypes.RESPIRATORY_RATE -> {
                val valueBpm = payload["value_bpm"]?.jsonPrimitive?.double
                    ?: return ProcessResult.PermanentFailure("Missing value_bpm")
                service.upsertRespiratoryRate(accessToken, item.date, valueBpm, item.healthConnectId)
            }

            HealthConnectRecordTypes.SKIN_TEMP -> {
                val valueCelsius = payload["value_celsius"]?.jsonPrimitive?.double
                    ?: return ProcessResult.PermanentFailure("Missing value_celsius")
                service.upsertSkinTemp(accessToken, item.date, valueCelsius, item.healthConnectId)
            }

            else -> {
                Log.w(TAG, "Unknown record type: ${item.recordType}")
                return ProcessResult.PermanentFailure("Unknown record type: ${item.recordType}")
            }
        }

        return if (success) ProcessResult.Success else ProcessResult.RetryableFailure("Upsert returned false")
    }
}
