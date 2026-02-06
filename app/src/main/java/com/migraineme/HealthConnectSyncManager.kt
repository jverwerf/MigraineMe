package com.migraineme

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Manager for triggering Health Connect syncs.
 * 
 * Handles:
 * - Sync on app resume (primary method - always works when app is open)
 * - Debouncing to avoid excessive syncs
 * - Checking if Health Connect is available/connected
 * 
 * This ensures data syncs reliably when the user opens the app,
 * complementing the FCM-triggered background syncs which only work
 * when the app is in foreground.
 */
object HealthConnectSyncManager {
    
    private const val TAG = "HCSyncManager"
    private const val PREFS_NAME = "health_connect_sync"
    private const val KEY_LAST_SYNC = "last_sync_time"
    
    private const val WORK_NAME_CHANGES = "health_connect_changes_sync"
    private const val WORK_NAME_PUSH = "health_connect_push_sync"
    
    // Minimum time between syncs (5 minutes)
    // This prevents excessive syncs if user rapidly opens/closes app
    private const val MIN_SYNC_INTERVAL_MS = 5 * 60 * 1000L
    
    /**
     * Triggers Health Connect sync if:
     * 1. Health Connect is available
     * 2. Enough time has passed since last sync
     * 3. User has connected Health Connect (has granted permissions)
     */
    fun triggerSyncIfEnabled(context: Context) {
        try {
            // Check if Health Connect is available
            if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
                Log.d(TAG, "Health Connect not available, skipping sync")
                return
            }
            
            // Check debounce - don't sync too frequently
            if (!shouldSync(context)) {
                Log.d(TAG, "Skipping sync - too soon since last sync")
                return
            }
            
            // Check if user has connected Health Connect (any permissions granted)
            val prefs = context.getSharedPreferences("health_connect", Context.MODE_PRIVATE)
            val isConnected = prefs.getBoolean("is_connected", false)
            if (!isConnected) {
                Log.d(TAG, "Health Connect not connected by user, skipping sync")
                return
            }
            
            // Trigger sync
            Log.d(TAG, "Triggering Health Connect sync on app resume")
            triggerSync(context)
            
            // Update last sync time
            updateLastSyncTime(context)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking sync conditions: ${e.message}")
        }
    }
    
    /**
     * Force trigger sync regardless of debounce.
     * Used when user manually requests sync or when first connecting.
     */
    fun triggerSyncNow(context: Context) {
        Log.d(TAG, "Force triggering Health Connect sync")
        triggerSync(context)
        updateLastSyncTime(context)
    }
    
    private fun triggerSync(context: Context) {
        val workManager = WorkManager.getInstance(context)
        
        // Use REPLACE policy - if work is already running/enqueued, replace it
        // This prevents duplicate workers from piling up
        
        // Trigger changes worker (reads from Health Connect → Room outbox)
        workManager.enqueueUniqueWork(
            WORK_NAME_CHANGES,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<HealthConnectChangesWorker>().build()
        )
        
        // Trigger push worker (pushes from Room outbox → Supabase)
        workManager.enqueueUniqueWork(
            WORK_NAME_PUSH,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<HealthConnectPushWorker>().build()
        )
    }
    
    private fun shouldSync(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSync = prefs.getLong(KEY_LAST_SYNC, 0)
        val now = System.currentTimeMillis()
        return (now - lastSync) >= MIN_SYNC_INTERVAL_MS
    }
    
    private fun updateLastSyncTime(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
    }
    
    /**
     * Call this when user connects Health Connect in settings.
     * Marks HC as connected so sync-on-resume will work.
     * Also triggers an immediate sync.
     */
    fun markAsConnected(context: Context) {
        val prefs = context.getSharedPreferences("health_connect", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_connected", true).apply()
        Log.d(TAG, "Health Connect marked as connected")
        
        // Trigger immediate sync since user just connected
        triggerSync(context)
        updateLastSyncTime(context)
    }
    
    /**
     * Call this when user disconnects Health Connect in settings.
     */
    fun markAsDisconnected(context: Context) {
        val prefs = context.getSharedPreferences("health_connect", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_connected", false).apply()
        Log.d(TAG, "Health Connect marked as disconnected")
    }
}
