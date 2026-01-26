package com.migraineme

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    val nutritionChangesToken: String? = null,
    val lastHourlyRunAtEpochMs: Long? = null,
    val lastPushRunAtEpochMs: Long? = null
)
