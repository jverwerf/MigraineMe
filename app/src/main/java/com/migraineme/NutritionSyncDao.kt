package com.migraineme

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface NutritionSyncDao {

    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun getSyncState(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncState(state: SyncStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOutbox(item: NutritionOutboxEntity)

    @Query("SELECT * FROM nutrition_outbox ORDER BY createdAtEpochMs ASC LIMIT :limit")
    suspend fun getOutboxBatch(limit: Int): List<NutritionOutboxEntity>

    @Query("DELETE FROM nutrition_outbox WHERE healthConnectId IN (:ids)")
    suspend fun deleteOutboxByIds(ids: List<String>)

    @Query("UPDATE nutrition_outbox SET retryCount = retryCount + 1 WHERE healthConnectId IN (:ids)")
    suspend fun incrementRetry(ids: List<String>)

    @Transaction
    suspend fun markHourlyRun(token: String?, nowMs: Long) {
        val current = getSyncState() ?: SyncStateEntity()
        upsertSyncState(
            current.copy(
                nutritionChangesToken = token,
                lastHourlyRunAtEpochMs = nowMs
            )
        )
    }

    @Transaction
    suspend fun markPushRun(nowMs: Long) {
        val current = getSyncState() ?: SyncStateEntity()
        upsertSyncState(
            current.copy(lastPushRunAtEpochMs = nowMs)
        )
    }
}
