package com.migraineme

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for Health Connect sync outbox.
 * Stores pending changes to be pushed to Supabase.
 */
@Entity(tableName = "health_connect_outbox")
data class HealthConnectOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    
    /** Health Connect record ID */
    val healthConnectId: String,
    
    /** Record type (e.g., "sleep", "hrv", "resting_hr", "steps", etc.) */
    val recordType: String,
    
    /** Operation: UPSERT or DELETE */
    val operation: String,
    
    /** Date for the record (YYYY-MM-DD) */
    val date: String,
    
    /** JSON payload with record-specific data */
    val payload: String,
    
    /** When this outbox entry was created */
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * Supported record types for Health Connect sync.
 */
object HealthConnectRecordTypes {
    const val SLEEP = "sleep"
    const val HRV = "hrv"
    const val RESTING_HR = "resting_hr"
    const val STEPS = "steps"
    const val EXERCISE = "exercise"
    const val WEIGHT = "weight"
    const val BODY_FAT = "body_fat"
    const val HYDRATION = "hydration"
    const val BLOOD_PRESSURE = "blood_pressure"
    const val BLOOD_GLUCOSE = "blood_glucose"
    const val SPO2 = "spo2"
    const val RESPIRATORY_RATE = "respiratory_rate"
    const val SKIN_TEMP = "skin_temp"
}
