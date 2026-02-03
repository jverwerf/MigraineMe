package com.migraineme

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

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
    // Outbox
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
    // Cleanup
    // ============================================================
    
    @Query("DELETE FROM health_connect_outbox WHERE createdAtEpochMs < :beforeEpochMs")
    suspend fun deleteOldOutboxEntries(beforeEpochMs: Long)
    
    @Query("DELETE FROM health_connect_outbox")
    suspend fun clearOutbox()
    
    @Query("DELETE FROM health_connect_sync_state")
    suspend fun clearSyncState()
}
