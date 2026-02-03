package com.migraineme

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for storing Health Connect sync state.
 * Stores change tokens for each record type.
 */
@Entity(tableName = "health_connect_sync_state")
data class HealthConnectSyncStateEntity(
    @PrimaryKey val id: Int = 1, // Single row
    
    // Change tokens for each record type
    val sleepToken: String? = null,
    val hrvToken: String? = null,
    val restingHrToken: String? = null,
    val stepsToken: String? = null,
    val exerciseToken: String? = null,
    val weightToken: String? = null,
    val bodyFatToken: String? = null,
    val hydrationToken: String? = null,
    val bloodPressureToken: String? = null,
    val bloodGlucoseToken: String? = null,
    val spo2Token: String? = null,
    val respiratoryRateToken: String? = null,
    val skinTempToken: String? = null,
    
    // Last sync timestamps
    val lastSyncAtEpochMs: Long = 0
)
