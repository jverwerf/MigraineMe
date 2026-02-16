package com.migraineme

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

class HealthConnectNutritionChangesWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(NutritionRecord::class)
        )

        // One-time backfill window when token doesn't exist yet
        private const val BACKFILL_DAYS = 14L
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val hc = HealthConnectClient.getOrCreate(applicationContext)
            val granted = hc.permissionController.getGrantedPermissions()
            if (!REQUIRED_PERMISSIONS.all { it in granted }) {
                return@withContext Result.failure()
            }

            val db = NutritionSyncDatabase.get(applicationContext)
            val dao = db.dao()

            val existingState = dao.getSyncState()
            val hadTokenAlready = !existingState?.nutritionChangesToken.isNullOrBlank()

            // If no token exists yet, do a one-time backfill FIRST, then create token.
            if (!hadTokenAlready) {
                val now = System.currentTimeMillis()
                val end = Instant.now()
                val start = end.minus(BACKFILL_DAYS, ChronoUnit.DAYS)

                val request = ReadRecordsRequest(
                    recordType = NutritionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )

                val response = hc.readRecords(request)

                // Enqueue all records as UPSERT so the push worker uploads them to Supabase.
                response.records.forEach { record ->
                    val id = record.metadata.id
                    dao.upsertOutbox(
                        NutritionOutboxEntity(
                            healthConnectId = id,
                            operation = "UPSERT",
                            createdAtEpochMs = now
                        )
                    )
                }

                // Create a token AFTER backfill so changes start "from now".
                val freshToken = hc.getChangesToken(
                    ChangesTokenRequest(recordTypes = setOf(NutritionRecord::class))
                )

                val nowMs = System.currentTimeMillis()
                dao.upsertSyncState(
                    (existingState ?: SyncStateEntity()).copy(
                        nutritionChangesToken = freshToken,
                        lastHourlyRunAtEpochMs = nowMs
                    )
                )

                return@withContext Result.success()
            }

            // Token exists: normal incremental change processing.
            var nextToken: String = existingState!!.nutritionChangesToken!!

            var hasMore = true
            var safety = 0

            while (hasMore && safety < 50) {
                safety++

                val resp = hc.getChanges(nextToken)
                nextToken = resp.nextChangesToken
                hasMore = resp.hasMore

                if (resp.changesTokenExpired) {
                    // Token expired: reset token (next run will continue from new token).
                    nextToken = hc.getChangesToken(
                        ChangesTokenRequest(recordTypes = setOf(NutritionRecord::class))
                    )
                    break
                }

                val now = System.currentTimeMillis()

                resp.changes.forEach { change ->
                    when (change) {
                        is UpsertionChange -> {
                            val id = change.record.metadata.id
                            dao.upsertOutbox(
                                NutritionOutboxEntity(
                                    healthConnectId = id,
                                    operation = "UPSERT",
                                    createdAtEpochMs = now
                                )
                            )
                        }
                        is DeletionChange -> {
                            val id = change.recordId
                            dao.upsertOutbox(
                                NutritionOutboxEntity(
                                    healthConnectId = id,
                                    operation = "DELETE",
                                    createdAtEpochMs = now
                                )
                            )
                        }
                    }
                }
            }

            val nowMs = System.currentTimeMillis()
            dao.upsertSyncState(
                existingState.copy(
                    nutritionChangesToken = nextToken,
                    lastHourlyRunAtEpochMs = nowMs
                )
            )

            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("HcNutritionChanges", "Worker failed: ${e.message}", e)
            Result.retry()
        }
    }
}

