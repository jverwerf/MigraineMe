package com.migraineme

import android.content.Context
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * NUTRITION BACKFILL UTILITY
 *
 * Helper functions to diagnose and fix nutrition backfill issues.
 *
 * USAGE:
 * Add a debug button in your ThirdPartyConnectionsScreen:
 *
 * ```kotlin
 * Button(onClick = {
 *     scope.launch {
 *         NutritionBackfillUtility.forceReBackfill(context)
 *     }
 * }) {
 *     Text("Force Re-Backfill (Debug)")
 * }
 * ```
 */
object NutritionBackfillUtility {

    private const val TAG = "NutritionBackfillUtil"

    /**
     * Force a complete re-backfill by:
     * 1. Clearing the sync token (so the worker thinks it's the first run)
     * 2. Clearing the outbox (to avoid duplicates)
     * 3. Triggering an immediate worker run
     *
     * This is useful when:
     * - The initial backfill failed or was incomplete
     * - You granted permission but data wasn't captured
     * - You want to test the backfill process
     */
    suspend fun forceReBackfill(context: Context) = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting force re-backfill...")

            val db = NutritionSyncDatabase.get(context)
            val dao = db.dao()

            // Get current state for logging
            val beforeState = dao.getSyncState()
            val beforeOutbox = dao.getOutboxBatch(1000).size

            Log.d(TAG, "Before: token=${beforeState?.nutritionChangesToken?.take(10)}..., outbox=$beforeOutbox")

            // Clear the sync token so backfill will trigger
            dao.upsertSyncState(
                SyncStateEntity(
                    id = 1,
                    nutritionChangesToken = null,  // This is the key - null means "never synced"
                    lastHourlyRunAtEpochMs = null,
                    lastPushRunAtEpochMs = beforeState?.lastPushRunAtEpochMs
                )
            )

            // Optionally clear outbox to avoid duplicates
            // (Comment this out if you want to keep existing outbox items)
            val outboxIds = dao.getOutboxBatch(1000).map { it.healthConnectId }
            if (outboxIds.isNotEmpty()) {
                dao.deleteOutboxByIds(outboxIds)
                Log.d(TAG, "Cleared ${outboxIds.size} items from outbox")
            }

            Log.d(TAG, "Sync token cleared. Triggering immediate worker run...")

            // Trigger immediate worker run
            val work = OneTimeWorkRequestBuilder<HealthConnectNutritionChangesWorker>()
                .addTag("force_backfill")
                .build()

            WorkManager.getInstance(context).enqueue(work)

            Log.d(TAG, "Force re-backfill initiated. Worker will run shortly and capture last 7 days of data.")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to force re-backfill", e)
            throw e
        }
    }

    /**
     * Get detailed status of the nutrition sync system.
     * Useful for debugging in logs.
     */
    suspend fun getDetailedStatus(context: Context): NutritionSyncStatus = withContext(Dispatchers.IO) {
        val db = NutritionSyncDatabase.get(context)
        val dao = db.dao()

        val state = dao.getSyncState()
        val outbox = dao.getOutboxBatch(1000)

        NutritionSyncStatus(
            hasToken = !state?.nutritionChangesToken.isNullOrBlank(),
            tokenPreview = state?.nutritionChangesToken?.take(20),
            lastHourlyRun = state?.lastHourlyRunAtEpochMs,
            lastPushRun = state?.lastPushRunAtEpochMs,
            outboxCount = outbox.size,
            outboxUpserts = outbox.count { it.operation == "UPSERT" },
            outboxDeletes = outbox.count { it.operation == "DELETE" }
        )
    }

    /**
     * Log current sync status to logcat for debugging
     */
    suspend fun logCurrentStatus(context: Context) {
        try {
            val status = getDetailedStatus(context)

            Log.d(TAG, "═══════════════════════════════════════")
            Log.d(TAG, "NUTRITION SYNC STATUS")
            Log.d(TAG, "═══════════════════════════════════════")
            Log.d(TAG, "Has Token: ${status.hasToken}")
            Log.d(TAG, "Token Preview: ${status.tokenPreview ?: "null"}")
            Log.d(TAG, "Last Hourly Run: ${status.lastHourlyRun?.let { java.util.Date(it) } ?: "never"}")
            Log.d(TAG, "Last Push Run: ${status.lastPushRun?.let { java.util.Date(it) } ?: "never"}")
            Log.d(TAG, "Outbox Total: ${status.outboxCount}")
            Log.d(TAG, "  - UPSERTs: ${status.outboxUpserts}")
            Log.d(TAG, "  - DELETEs: ${status.outboxDeletes}")
            Log.d(TAG, "═══════════════════════════════════════")

            if (status.hasToken && status.outboxCount == 0) {
                Log.w(TAG, "⚠️ WARNING: Token exists but outbox is empty!")
                Log.w(TAG, "This suggests:")
                Log.w(TAG, "  1. Backfill ran but found no data in Health Connect, OR")
                Log.w(TAG, "  2. Data was already pushed to Supabase, OR")
                Log.w(TAG, "  3. There's an issue with the backfill logic")
            }

            if (!status.hasToken && status.outboxCount > 0) {
                Log.w(TAG, "⚠️ WARNING: Outbox has data but no token!")
                Log.w(TAG, "This is unusual - token should be created after backfill")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to get status", e)
        }
    }

    data class NutritionSyncStatus(
        val hasToken: Boolean,
        val tokenPreview: String?,
        val lastHourlyRun: Long?,
        val lastPushRun: Long?,
        val outboxCount: Int,
        val outboxUpserts: Int,
        val outboxDeletes: Int
    )
}