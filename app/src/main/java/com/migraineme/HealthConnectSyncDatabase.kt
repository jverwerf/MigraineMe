package com.migraineme

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        HealthConnectSyncStateEntity::class,
        HealthConnectOutboxEntity::class
    ],
    version = 2,  // Incremented from 1 to 2
    exportSchema = false
)
abstract class HealthConnectSyncDatabase : RoomDatabase() {

    abstract fun dao(): HealthConnectSyncDao

    companion object {
        @Volatile
        private var INSTANCE: HealthConnectSyncDatabase? = null

        fun get(context: Context): HealthConnectSyncDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    HealthConnectSyncDatabase::class.java,
                    "health_connect_sync_db"
                )
                    .addMigrations(HealthConnectOutboxMigration.MIGRATION_1_2)  // Add migration
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
