package com.migraineme

import android.content.Context
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Helper to check and manually trigger the nutrition push worker
 */
object NutritionPushHelper {
    
    private const val TAG = "NutritionPushHelper"
    
    /**
     * Manually trigger the push worker to upload outbox to Supabase
     */
    suspend fun triggerPushNow(context: Context) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Manually triggering nutrition push worker...")
            
            // Check what's in outbox first
            val db = NutritionSyncDatabase.get(context)
            val dao = db.dao()
            val outbox = dao.getOutboxBatch(1000)
            
            Log.d(TAG, "Outbox has ${outbox.size} items before push")
            outbox.take(5).forEach { item ->
                Log.d(TAG, "  - ${item.operation}: ${item.healthConnectId.take(8)}...")
            }
            
            // Trigger immediate push
            val work = OneTimeWorkRequestBuilder<NutritionOutboxPushWorker>()
                .addTag("manual_nutrition_push")
                .build()
            
            WorkManager.getInstance(context).enqueue(work)
            
            Log.d(TAG, "Push worker enqueued. Check logs for 'NutritionOutboxPush' tag")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger push", e)
            throw e
        }
    }
    
    /**
     * Check if there's a valid Supabase access token
     */
    suspend fun checkSupabaseToken(context: Context): Boolean = withContext(Dispatchers.IO) {
        val token = SessionStore.getValidAccessToken(context)
        val hasToken = !token.isNullOrBlank()
        
        Log.d(TAG, "Supabase token present: $hasToken")
        if (hasToken) {
            Log.d(TAG, "Token preview: ${token?.take(20)}...")
        } else {
            Log.e(TAG, "NO SUPABASE TOKEN! User may not be logged in.")
        }
        
        hasToken
    }
    
    /**
     * Get detailed push worker status
     */
    suspend fun getDetailedPushStatus(context: Context): PushStatus = withContext(Dispatchers.IO) {
        val db = NutritionSyncDatabase.get(context)
        val dao = db.dao()
        
        val state = dao.getSyncState()
        val outbox = dao.getOutboxBatch(1000)
        val hasToken = checkSupabaseToken(context)
        
        PushStatus(
            outboxCount = outbox.size,
            lastPushRun = state?.lastPushRunAtEpochMs,
            hasSupabaseToken = hasToken,
            outboxSample = outbox.take(3).map { 
                "${it.operation}: ${it.healthConnectId.take(12)}... (retry: ${it.retryCount})" 
            }
        )
    }
    
    /**
     * Log detailed push status
     */
    suspend fun logPushStatus(context: Context) {
        try {
            val status = getDetailedPushStatus(context)
            
            Log.d(TAG, "═══════════════════════════════════════")
            Log.d(TAG, "NUTRITION PUSH STATUS")
            Log.d(TAG, "═══════════════════════════════════════")
            Log.d(TAG, "Outbox Count: ${status.outboxCount}")
            Log.d(TAG, "Last Push Run: ${status.lastPushRun?.let { java.util.Date(it) } ?: "never"}")
            Log.d(TAG, "Has Supabase Token: ${status.hasSupabaseToken}")
            
            if (status.outboxSample.isNotEmpty()) {
                Log.d(TAG, "Sample items:")
                status.outboxSample.forEach { Log.d(TAG, "  $it") }
            }
            
            Log.d(TAG, "═══════════════════════════════════════")
            
            // Warnings
            if (status.outboxCount > 0 && !status.hasSupabaseToken) {
                Log.e(TAG, "⚠️ WARNING: Outbox has items but NO Supabase token!")
                Log.e(TAG, "→ User needs to log in to Supabase first")
            }
            
            if (status.outboxCount > 100) {
                Log.w(TAG, "⚠️ WARNING: Large outbox (${status.outboxCount} items)")
                Log.w(TAG, "→ This may take several push cycles to clear")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get push status", e)
        }
    }
    
    data class PushStatus(
        val outboxCount: Int,
        val lastPushRun: Long?,
        val hasSupabaseToken: Boolean,
        val outboxSample: List<String>
    )
}
