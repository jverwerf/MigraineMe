package com.migraineme

/**
 * Mirrors SleepSyncStatus.kt style.
 * A simple sealed status used by PH worker or UI if needed.
 *
 * Not referenced anywhere unless you add it.
 * Safe placeholder for future use.
 */
sealed class PhysicalHealthSyncStatus {
    object Idle : PhysicalHealthSyncStatus()
    object Syncing : PhysicalHealthSyncStatus()
    object Success : PhysicalHealthSyncStatus()
    data class Error(val message: String?) : PhysicalHealthSyncStatus()
}
