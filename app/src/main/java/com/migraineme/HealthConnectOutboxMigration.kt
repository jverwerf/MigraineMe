package com.migraineme

import android.content.Context
import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration to add retry tracking columns to health_connect_outbox table.
 *
 * IMPORTANT: Update your database version in HealthConnectSyncDatabase and add this migration.
 *
 * Example in HealthConnectSyncDatabase:
 * ```
 * @Database(
 *     entities = [HealthConnectOutboxEntity::class, ...],
 *     version = 2,  // Increment from 1 to 2
 *     ...
 * )
 * abstract class HealthConnectSyncDatabase : RoomDatabase() {
 *     companion object {
 *         fun get(context: Context): HealthConnectSyncDatabase {
 *             return Room.databaseBuilder(...)
 *                 .addMigrations(HealthConnectOutboxMigration.MIGRATION_1_2)
 *                 .build()
 *         }
 *     }
 * }
 * ```
 */
object HealthConnectOutboxMigration {

    private const val TAG = "HCOutboxMigration"

    /**
     * Migration from version 1 to 2.
     * Adds retryCount, status, and lastError columns to health_connect_outbox.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            Log.d(TAG, "Running migration 1 -> 2: Adding retry tracking columns")

            // Add retryCount column with default 0
            database.execSQL(
                "ALTER TABLE health_connect_outbox ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0"
            )

            // Add status column with default 'pending'
            database.execSQL(
                "ALTER TABLE health_connect_outbox ADD COLUMN status TEXT NOT NULL DEFAULT 'pending'"
            )

            // Add lastError column (nullable)
            database.execSQL(
                "ALTER TABLE health_connect_outbox ADD COLUMN lastError TEXT DEFAULT NULL"
            )

            Log.d(TAG, "Migration 1 -> 2 complete")
        }
    }
}

/**
 * One-time utility to reset failed items after deploying the fix.
 *
 * Call this once after the fix is deployed to give stuck items another chance.
 * Can be triggered from a debug menu, on app update, or via a feature flag.
 */
object HealthConnectOutboxRecovery {

    private const val TAG = "HCOutboxRecovery"

    /**
     * Reset all failed items to pending status.
     * Call this after deploying the encodeDefaults fix.
     */
    suspend fun retryAllFailedItems(context: Context) {
        val db = HealthConnectSyncDatabase.get(context)
        val dao = db.dao()

        // Reset items that failed due to transient errors
        dao.retryFailedItems()

        Log.i(TAG, "Reset failed items to pending status")
    }

    /**
     * Reset ALL non-pending items (both failed and permanent_failure).
     * Use after a major fix that should resolve permanent failures too.
     */
    suspend fun resetAllItems(context: Context) {
        val db = HealthConnectSyncDatabase.get(context)
        val dao = db.dao()

        dao.resetAllFailedItems()

        Log.i(TAG, "Reset ALL failed items to pending status")
    }

    /**
     * Clear the entire outbox and let Health Connect re-sync.
     * Use as last resort if data is corrupted.
     *
     * This is safe because Health Connect still has the original data.
     */
    suspend fun clearAndResync(context: Context) {
        val db = HealthConnectSyncDatabase.get(context)
        val dao = db.dao()

        // Clear outbox
        dao.clearOutbox()

        // Clear sync state to force full re-read from Health Connect
        dao.clearSyncState()

        Log.i(TAG, "Cleared outbox and sync state - will re-sync from Health Connect")
    }
}
