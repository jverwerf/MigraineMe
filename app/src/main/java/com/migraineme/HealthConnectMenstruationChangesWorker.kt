package com.migraineme

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.Change
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Worker that detects menstruation period changes in Health Connect
 * 
 * Runs every 15 minutes to detect:
 * - New periods logged in Flo/Clue
 * - Updated period dates
 * - Deleted periods
 * 
 * Similar pattern to HealthConnectNutritionChangesWorker
 */
class HealthConnectMenstruationChangesWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "HCMenstruationChanges"
        val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(MenstruationPeriodRecord::class)
        )
        private const val BACKFILL_DAYS = 730L // 2 years of history
    }
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d(TAG, "Starting menstruation changes check")
            
            val hc = HealthConnectClient.getOrCreate(applicationContext)
            val granted = hc.permissionController.getGrantedPermissions()
            
            if (!REQUIRED_PERMISSIONS.all { it in granted }) {
                android.util.Log.e(TAG, "Missing permissions")
                return@withContext Result.failure()
            }
            
            val db = MenstruationSyncDatabase.get(applicationContext)
            val dao = db.dao()
            
            val existingState = dao.getSyncState()
            val hadTokenAlready = !existingState?.changesToken.isNullOrBlank()
            
            // If no token exists, do initial backfill
            if (!hadTokenAlready) {
                android.util.Log.d(TAG, "No token found - performing initial backfill")
                performInitialBackfill(hc, dao)
                return@withContext Result.success()
            }
            
            // Token exists - process incremental changes
            android.util.Log.d(TAG, "Processing incremental changes")
            processIncrementalChanges(hc, dao, existingState!!)
            
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Worker failed: ${e.message}", e)
            Result.retry()
        }
    }
    
    /**
     * Initial backfill - read last 2 years of periods
     */
    private suspend fun performInitialBackfill(
        hc: HealthConnectClient,
        dao: MenstruationSyncDao
    ) {
        val now = System.currentTimeMillis()
        val end = Instant.now()
        val start = end.minus(BACKFILL_DAYS, ChronoUnit.DAYS)
        
        android.util.Log.d(TAG, "Backfilling periods from $start to $end")
        
        val request = ReadRecordsRequest(
            recordType = MenstruationPeriodRecord::class,
            timeRangeFilter = TimeRangeFilter.between(start, end)
        )
        
        val response = hc.readRecords(request)
        
        android.util.Log.d(TAG, "Found ${response.records.size} historical periods")
        
        // Add all to outbox
        response.records.forEach { record ->
            val id = record.metadata.id
            dao.upsertOutbox(
                MenstruationOutboxEntity(
                    healthConnectId = id,
                    operation = "UPSERT",
                    createdAtEpochMs = now
                )
            )
        }
        
        // Create changes token for future incremental syncs
        val freshToken = hc.getChangesToken(
            ChangesTokenRequest(recordTypes = setOf(MenstruationPeriodRecord::class))
        )
        
        val nowMs = System.currentTimeMillis()
        dao.upsertSyncState(
            MenstruationSyncStateEntity(
                changesToken = freshToken,
                lastHourlyRunAtEpochMs = nowMs
            )
        )
        
        android.util.Log.d(TAG, "Initial backfill complete - ${response.records.size} periods queued")
    }
    
    /**
     * Process incremental changes using changes token
     */
    private suspend fun processIncrementalChanges(
        hc: HealthConnectClient,
        dao: MenstruationSyncDao,
        state: MenstruationSyncStateEntity
    ) {
        var nextToken: String = state.changesToken!!
        var hasMore = true
        var safety = 0
        var changesProcessed = 0
        
        while (hasMore && safety < 50) {
            safety++
            
            val resp = hc.getChanges(nextToken)
            nextToken = resp.nextChangesToken
            hasMore = resp.hasMore
            
            if (resp.changesTokenExpired) {
                android.util.Log.w(TAG, "Changes token expired - getting new token")
                nextToken = hc.getChangesToken(
                    ChangesTokenRequest(recordTypes = setOf(MenstruationPeriodRecord::class))
                )
                break
            }
            
            val now = System.currentTimeMillis()
            
            resp.changes.forEach { change ->
                when (change) {
                    is UpsertionChange -> {
                        val id = change.record.metadata.id
                        dao.upsertOutbox(
                            MenstruationOutboxEntity(
                                healthConnectId = id,
                                operation = "UPSERT",
                                createdAtEpochMs = now
                            )
                        )
                        changesProcessed++
                    }
                    is DeletionChange -> {
                        val id = change.recordId
                        dao.upsertOutbox(
                            MenstruationOutboxEntity(
                                healthConnectId = id,
                                operation = "DELETE",
                                createdAtEpochMs = now
                            )
                        )
                        changesProcessed++
                    }
                }
            }
        }
        
        // Update sync state
        val nowMs = System.currentTimeMillis()
        dao.upsertSyncState(
            state.copy(
                changesToken = nextToken,
                lastHourlyRunAtEpochMs = nowMs
            )
        )
        
        android.util.Log.d(TAG, "Incremental sync complete - $changesProcessed changes processed")
    }
}
