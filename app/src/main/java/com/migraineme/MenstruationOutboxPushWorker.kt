package com.migraineme

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Worker that pushes menstruation periods from local outbox to Supabase
 * 
 * Runs every 15 minutes to upload:
 * - New periods
 * - Updated periods
 * - Deleted periods
 * 
 * Also handles:
 * - Recalculating weighted average (if auto-update enabled)
 * - Updating last_menstruation_date
 */
class MenstruationOutboxPushWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "MenstruationOutboxPush"
        private val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(MenstruationPeriodRecord::class)
        )
    }
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d(TAG, "Starting menstruation push")
            
            val accessToken = SessionStore.getValidAccessToken(applicationContext)
                ?: return@withContext Result.retry()
            
            val hc = HealthConnectClient.getOrCreate(applicationContext)
            val granted = hc.permissionController.getGrantedPermissions()
            
            if (!REQUIRED_PERMISSIONS.all { it in granted }) {
                android.util.Log.e(TAG, "Missing permissions")
                return@withContext Result.failure()
            }
            
            val db = MenstruationSyncDatabase.get(applicationContext)
            val dao = db.dao()
            
            val batch = dao.getOutboxBatch(limit = 200)
            if (batch.isEmpty()) {
                dao.markPushRun(System.currentTimeMillis())
                android.util.Log.d(TAG, "Outbox empty, nothing to push")
                return@withContext Result.success()
            }
            
            val service = SupabaseMenstruationService(applicationContext)
            
            val upserts = batch.filter { it.operation == "UPSERT" }
            val deletes = batch.filter { it.operation == "DELETE" }
            
            val succeededIds = mutableListOf<String>()
            val failedIds = mutableListOf<String>()
            
            // 1) Push UPSERTs
            for (item in upserts) {
                try {
                    val record = hc.readRecord(MenstruationPeriodRecord::class, item.healthConnectId).record
                    val startDate: LocalDate = record.startTime.atZone(ZoneOffset.UTC).toLocalDate()
                    val endDate: LocalDate? = record.endTime?.atZone(ZoneOffset.UTC)?.toLocalDate()
                    
                    service.uploadPeriod(accessToken, item.healthConnectId, startDate, endDate)
                    succeededIds.add(item.healthConnectId)
                    
                    android.util.Log.d(TAG, "Uploaded period: $startDate to $endDate")
                } catch (e: Exception) {
                    failedIds.add(item.healthConnectId)
                    android.util.Log.e(TAG, "Failed UPSERT id=${item.healthConnectId}: ${e.message}", e)
                }
            }
            
            // 2) Push DELETEs
            if (deletes.isNotEmpty()) {
                val idsToDelete = deletes.map { it.healthConnectId }
                try {
                    service.deletePeriodsByHealthConnectIds(accessToken, idsToDelete)
                    succeededIds.addAll(idsToDelete)
                    android.util.Log.d(TAG, "Deleted ${idsToDelete.size} periods")
                } catch (e: Exception) {
                    failedIds.addAll(idsToDelete)
                    android.util.Log.e(TAG, "Failed DELETE batch: ${e.message}", e)
                }
            }
            
            // 3) Update menstruation_settings (last date + avg cycle if auto-update enabled)
            if (succeededIds.isNotEmpty() && upserts.isNotEmpty()) {
                try {
                    updateMenstruationSettings(service, accessToken)
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Failed to update settings: ${e.message}", e)
                }
            }
            
            // 4) Clean up outbox
            if (succeededIds.isNotEmpty()) {
                dao.deleteOutboxByIds(succeededIds.distinct())
            }
            if (failedIds.isNotEmpty()) {
                dao.incrementRetry(failedIds.distinct())
            }
            
            dao.markPushRun(System.currentTimeMillis())
            
            android.util.Log.d(TAG, "Push complete - succeeded: ${succeededIds.size}, failed: ${failedIds.size}")
            
            if (succeededIds.isEmpty() && failedIds.isNotEmpty()) {
                return@withContext Result.retry()
            }
            
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Push worker failed: ${e.message}", e)
            Result.retry()
        }
    }
    
    /**
     * Update menstruation_settings with latest data
     * - Update last_menstruation_date to most recent period
     * - Recalculate avg_cycle_length if auto_update_average is enabled
     */
    private suspend fun updateMenstruationSettings(
        service: SupabaseMenstruationService,
        accessToken: String
    ) {
        // Get current settings
        val settings = service.getSettings(accessToken) ?: return
        
        // Get all historical periods
        val periods = service.getMenstruationHistory(accessToken, limitDays = 365)
        if (periods.isEmpty()) return
        
        // Find most recent period
        val mostRecent = periods.maxByOrNull { it.startDate } ?: return
        
        // Calculate new average if auto-update enabled
        val newAvgCycle = if (settings.autoUpdateAverage) {
            val sortedPeriods = periods.sortedBy { it.startDate }
            MenstruationCalculator.calculateWeightedAverage(sortedPeriods)
        } else {
            settings.avgCycleLength // Keep existing
        }
        
        // Update settings
        service.updateSettings(
            accessToken = accessToken,
            lastMenstruationDate = mostRecent.startDate,
            avgCycleLength = newAvgCycle,
            autoUpdateAverage = settings.autoUpdateAverage
        )
        
        android.util.Log.d(TAG, "Updated settings - last: ${mostRecent.startDate}, avg: $newAvgCycle")
    }
}
