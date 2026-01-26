package com.migraineme

import android.content.Context
import androidx.room.*

/**
 * Local database for menstruation sync (similar to nutrition sync)
 * 
 * Tracks:
 * - Sync state (changes token, last run times)
 * - Outbox (periods waiting to upload to Supabase)
 */
@Database(
    entities = [
        MenstruationSyncStateEntity::class,
        MenstruationOutboxEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class MenstruationSyncDatabase : RoomDatabase() {
    
    abstract fun dao(): MenstruationSyncDao
    
    companion object {
        @Volatile
        private var INSTANCE: MenstruationSyncDatabase? = null
        
        fun get(context: Context): MenstruationSyncDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MenstruationSyncDatabase::class.java,
                    "menstruation_sync.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * Sync state - tracks changes token and last run times
 */
@Entity(tableName = "menstruation_sync_state")
data class MenstruationSyncStateEntity(
    @PrimaryKey val id: Int = 1,
    val changesToken: String? = null,
    val lastHourlyRunAtEpochMs: Long? = null,
    val lastPushRunAtEpochMs: Long? = null
)

/**
 * Outbox - periods waiting to upload to Supabase
 */
@Entity(tableName = "menstruation_outbox")
data class MenstruationOutboxEntity(
    @PrimaryKey val healthConnectId: String,
    val operation: String,  // "UPSERT" or "DELETE"
    val createdAtEpochMs: Long,
    val retryCount: Int = 0
)

/**
 * DAO for menstruation sync operations
 */
@Dao
interface MenstruationSyncDao {
    
    // Sync state operations
    @Query("SELECT * FROM menstruation_sync_state WHERE id = 1")
    suspend fun getSyncState(): MenstruationSyncStateEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncState(state: MenstruationSyncStateEntity)
    
    @Query("UPDATE menstruation_sync_state SET lastHourlyRunAtEpochMs = :epochMs WHERE id = 1")
    suspend fun markHourlyRun(epochMs: Long)
    
    @Query("UPDATE menstruation_sync_state SET lastPushRunAtEpochMs = :epochMs WHERE id = 1")
    suspend fun markPushRun(epochMs: Long)
    
    // Outbox operations
    @Query("SELECT * FROM menstruation_outbox ORDER BY createdAtEpochMs ASC LIMIT :limit")
    suspend fun getOutboxBatch(limit: Int): List<MenstruationOutboxEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOutbox(item: MenstruationOutboxEntity)
    
    @Query("DELETE FROM menstruation_outbox WHERE healthConnectId IN (:ids)")
    suspend fun deleteOutboxByIds(ids: List<String>)
    
    @Query("UPDATE menstruation_outbox SET retryCount = retryCount + 1 WHERE healthConnectId IN (:ids)")
    suspend fun incrementRetry(ids: List<String>)
    
    @Query("DELETE FROM menstruation_outbox")
    suspend fun clearOutbox()
    
    @Query("DELETE FROM menstruation_sync_state")
    suspend fun clearSyncState()
}
