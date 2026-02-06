package com.migraineme

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for Health Connect sync outbox.
 * Stores pending changes to be pushed to Supabase.
 *
 * RETRY BEHAVIOR:
 * - Items start with status = "pending" and retryCount = 0
 * - On transient failures (network, 5xx), retryCount increments
 * - After MAX_RETRIES, status changes to "failed"
 * - On permanent failures (4xx client errors), status changes to "permanent_failure"
 * - "failed" items can be retried later (e.g., after app update)
 * - "permanent_failure" items need manual intervention or data fix
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
    val createdAtEpochMs: Long = System.currentTimeMillis(),

    /** Number of failed retry attempts */
    val retryCount: Int = 0,

    /**
     * Status of this outbox item:
     * - "pending": Ready to be processed
     * - "failed": Exceeded max retries (transient errors) - can retry later
     * - "permanent_failure": Client error (4xx) - needs data fix
     */
    val status: String = "pending",

    /** Last error message for debugging */
    val lastError: String? = null
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_FAILED = "failed"
        const val STATUS_PERMANENT_FAILURE = "permanent_failure"

        const val MAX_RETRIES = 5
    }
}

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
