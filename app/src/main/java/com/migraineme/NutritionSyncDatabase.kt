package com.migraineme

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SyncStateEntity::class, NutritionOutboxEntity::class],
    version = 1,
    exportSchema = false
)
abstract class NutritionSyncDatabase : RoomDatabase() {
    abstract fun dao(): NutritionSyncDao

    companion object {
        @Volatile private var INSTANCE: NutritionSyncDatabase? = null

        fun get(context: Context): NutritionSyncDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NutritionSyncDatabase::class.java,
                    "nutrition_sync.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
