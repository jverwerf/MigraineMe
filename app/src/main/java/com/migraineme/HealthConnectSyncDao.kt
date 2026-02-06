package com.migraineme

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface HealthConnectSyncDao {

    // ============================================================
    // Sync State
    // ============================================================

    @Query("SELECT * FROM health_connect_sync_state WHERE id = 1")
    suspend fun getSyncState(): HealthConnectSyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncState(state: HealthConnectSyncStateEntity)

    // ============================================================
    // Outbox - Basic Operations
    // ============================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutbox(item: HealthConnectOutboxEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutboxBatch(items: List<HealthConnectOutboxEntity>)

    @Query("SELECT * FROM health_connect_outbox ORDER BY createdAtEpochMs ASC LIMIT :limit")
    suspend fun getOutboxBatch(limit: Int): List<HealthConnectOutboxEntity>

    @Query("SELECT * FROM health_connect_outbox WHERE recordType = :recordType ORDER BY createdAtEpochMs ASC LIMIT :limit")
    suspend fun getOutboxByType(recordType: String, limit: Int): List<HealthConnectOutboxEntity>

    @Query("DELETE FROM health_connect_outbox WHERE id IN (:ids)")
    suspend fun deleteOutboxByIds(ids: List<Long>)

    @Query("DELETE FROM health_connect_outbox WHERE healthConnectId = :healthConnectId")
    suspend fun deleteOutboxByHealthConnectId(healthConnectId: String)

    @Query("SELECT COUNT(*) FROM health_connect_outbox")
    suspend fun getOutboxCount(): Int

    @Query("SELECT COUNT(*) FROM health_connect_outbox WHERE recordType = :recordType")
    suspend fun getOutboxCountByType(recordType: String): Int

    // ============================================================
    // Outbox - Retry Management (NEW)
    // ============================================================

    /**
     * Get pending items only (excludes failed and permanent_failure).
     * This is the main query used by the push worker.
     */
    @Query("SELECT * FROM health_connect_outbox WHERE status = 'pending' ORDER BY createdAtEpochMs ASC LIMIT :limit")
    suspend fun getPendingOutboxBatch(limit: Int): List<HealthConnectOutboxEntity>

    /**
     * Increment retry count for items that failed with transient errors.
     */
    @Query("UPDATE health_connect_outbox SET retryCount = retryCount + 1 WHERE id IN (:ids)")
    suspend fun incrementRetryCount(ids: List<Long>)

    /**
     * Update last error message for debugging.
     */
    @Query("UPDATE health_connect_outbox SET lastError = :error WHERE id = :id")
    suspend fun updateLastError(id: Long, error: String)

    /**
     * Mark items as "failed" after exceeding max retries.
     * These can be retried later (e.g., after an app update fixes the issue).
     */
    @Query("UPDATE health_connect_outbox SET status = 'failed' WHERE retryCount >= :maxRetries AND status = 'pending'")
    suspend fun markExceededRetriesAsFailed(maxRetries: Int)

    /**
     * Mark items as "permanent_failure" (client errors that won't be fixed by retrying).
     */
    @Query("UPDATE health_connect_outbox SET status = 'permanent_failure', lastError = :error WHERE id IN (:ids)")
    suspend fun markAsPermanentFailure(ids: List<Long>, error: String)

    /**
     * Reset failed items to pending status.
     * Call this after deploying a fix to give items another chance.
     */
    @Query("UPDATE health_connect_outbox SET status = 'pending', retryCount = 0 WHERE status = 'failed'")
    suspend fun retryFailedItems()

    /**
     * Reset ALL non-pending items (both failed and permanent_failure).
     * Use with caution - typically after a major fix or schema change.
     */
    @Query("UPDATE health_connect_outbox SET status = 'pending', retryCount = 0 WHERE status != 'pending'")
    suspend fun resetAllFailedItems()

    /**
     * Delete old permanent failures (cleanup after 30 days).
     */
    @Query("DELETE FROM health_connect_outbox WHERE status = 'permanent_failure' AND createdAtEpochMs < :beforeEpochMs")
    suspend fun deleteOldPermanentFailures(beforeEpochMs: Long)

    // ============================================================
    // Cleanup
    // ============================================================

    @Query("DELETE FROM health_connect_outbox WHERE createdAtEpochMs < :beforeEpochMs")
    suspend fun deleteOldOutboxEntries(beforeEpochMs: Long)

    @Query("DELETE FROM health_connect_outbox")
    suspend fun clearOutbox()

    @Query("DELETE FROM health_connect_sync_state")
    suspend fun clearSyncState()
}
