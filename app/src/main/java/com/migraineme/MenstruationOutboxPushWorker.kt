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
 * NOTE: Settings update (avg cycle, last date, predicted) is now handled by
 * a database trigger (trigger_update_menstruation_prediction) which fires
 * automatically when a menstruation trigger is inserted. No need to update
 * settings from Android code.
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

                    service.upsertHealthConnectMenstruationTrigger(
                        accessToken = accessToken,
                        healthConnectId = item.healthConnectId,
                        startDate = startDate,
                        endDate = endDate
                    )
                    succeededIds.add(item.healthConnectId)

                    android.util.Log.d(TAG, "Upserted menstruation trigger: $startDate to $endDate")
                } catch (e: Exception) {
                    failedIds.add(item.healthConnectId)
                    android.util.Log.e(TAG, "Failed UPSERT id=${item.healthConnectId}: ${e.message}", e)
                }
            }

            // 2) Push DELETEs
            if (deletes.isNotEmpty()) {
                val idsToDelete = deletes.map { it.healthConnectId }
                try {
                    service.deleteHealthConnectMenstruationTriggersByIds(
                        accessToken = accessToken,
                        healthConnectIds = idsToDelete
                    )
                    succeededIds.addAll(idsToDelete)
                    android.util.Log.d(TAG, "Deleted ${idsToDelete.size} menstruation trigger(s)")
                } catch (e: Exception) {
                    failedIds.addAll(idsToDelete)
                    android.util.Log.e(TAG, "Failed DELETE batch: ${e.message}", e)
                }
            }

            // NOTE: No need to call updateMenstruationSettings() here anymore.
            // The database trigger (trigger_update_menstruation_prediction) automatically:
            // - Updates menstruation_settings.last_menstruation_date
            // - Recalculates avg_cycle_length (if auto_update_average is ON)
            // - Updates/creates the menstruation_predicted trigger
            // This happens automatically when the INSERT into triggers table completes.

            // 3) Clean up outbox
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
}
