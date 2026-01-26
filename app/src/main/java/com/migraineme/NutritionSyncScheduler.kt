package com.migraineme

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Schedules nutrition sync workers
 *
 * - Changes worker: Runs every 15 minutes (detects new/updated/deleted nutrition records)
 * - Push worker: Runs every 15 minutes (uploads to Supabase)
 *
 * Total max delay: ~30 minutes (15 min to detect + 15 min to upload)
 * Typical delay: ~15 minutes (if workers sync in same cycle)
 */
object NutritionSyncScheduler {

    private const val TAG = "NutritionSyncScheduler"
    private const val HOURLY_WORK_NAME = "hc_nutrition_changes_hourly"
    private const val PUSH_WORK_NAME = "nutrition_outbox_push_15m"
    private const val IMMEDIATE_CHANGES_WORK_NAME = "hc_nutrition_changes_immediate"
    private const val IMMEDIATE_PUSH_WORK_NAME = "nutrition_outbox_push_immediate"

    /**
     * Schedule both nutrition workers AND trigger immediate run
     *
     * SMART SCHEDULING:
     * - If workers already exist and are healthy (ENQUEUED/RUNNING) → Do nothing
     * - If workers don't exist or are in bad state (CANCELLED/FAILED) → Create fresh workers
     * - Always triggers immediate sync for instant data sync
     *
     * This ensures:
     * - Safe to call multiple times (idempotent)
     * - Opening screen doesn't reset timers
     * - App restart doesn't break workers
     */
    fun schedule(context: Context) {
        Log.d(TAG, "========== schedule() called ==========")

        val wm = WorkManager.getInstance(context)

        // Check if workers already exist and are healthy
        val workersHealthy = areWorkersHealthy(wm)

        if (workersHealthy) {
            Log.d(TAG, "Workers already running and healthy - skipping schedule")
            // Still trigger immediate sync for fresh data
            triggerImmediateSync(context)
            Log.d(TAG, "========== schedule() complete (no changes) ==========")
            return
        }

        Log.d(TAG, "Workers need scheduling - creating fresh workers")
        logCurrentWorkerStates(context, "BEFORE schedule()")

        val hourlyConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val pushConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val hourly = PeriodicWorkRequestBuilder<HealthConnectNutritionChangesWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(hourlyConstraints)
            .addTag("nutrition")
            .addTag("health_connect")
            .build()

        val push = PeriodicWorkRequestBuilder<NutritionOutboxPushWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(pushConstraints)
            .addTag("nutrition")
            .addTag("supabase")
            .build()

        Log.d(TAG, "Enqueueing HOURLY_WORK with REPLACE policy")
        // Use REPLACE to ensure clean state when rescheduling
        wm.enqueueUniquePeriodicWork(
            HOURLY_WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            hourly
        )

        Log.d(TAG, "Enqueueing PUSH_WORK with REPLACE policy")
        wm.enqueueUniquePeriodicWork(
            PUSH_WORK_NAME,
            ExistingPeriodicWorkPolicy.REPLACE,
            push
        )

        logCurrentWorkerStates(context, "AFTER schedule()")

        Log.d(TAG, "Triggering immediate sync")
        triggerImmediateSync(context)

        Log.d(TAG, "========== schedule() complete ==========")
    }

    /**
     * Check if workers are already running and healthy
     * Returns true if both workers exist and are in good state (ENQUEUED or RUNNING)
     */
    private fun areWorkersHealthy(wm: WorkManager): Boolean {
        return try {
            val hourlyInfo = wm.getWorkInfosForUniqueWork(HOURLY_WORK_NAME).get()
            val pushInfo = wm.getWorkInfosForUniqueWork(PUSH_WORK_NAME).get()

            if (hourlyInfo.isEmpty() || pushInfo.isEmpty()) {
                Log.d(TAG, "Workers don't exist")
                return false
            }

            val hourlyState = hourlyInfo.firstOrNull()?.state
            val pushState = pushInfo.firstOrNull()?.state

            val healthyStates = setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING)

            val hourlyHealthy = hourlyState in healthyStates
            val pushHealthy = pushState in healthyStates

            Log.d(TAG, "Worker health check - hourly: $hourlyState ($hourlyHealthy), push: $pushState ($pushHealthy)")

            hourlyHealthy && pushHealthy
        } catch (e: Exception) {
            Log.e(TAG, "Error checking worker health: ${e.message}", e)
            false
        }
    }

    /**
     * Trigger immediate one-time sync (used on connect and for manual push)
     * Runs both workers in sequence: changes detection → upload to Supabase
     */
    fun triggerImmediateSync(context: Context) {
        Log.d(TAG, "========== triggerImmediateSync() called ==========")

        val wm = WorkManager.getInstance(context)

        val changesConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val pushConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val immediateChanges = OneTimeWorkRequestBuilder<HealthConnectNutritionChangesWorker>()
            .setConstraints(changesConstraints)
            .addTag("nutrition")
            .addTag("health_connect")
            .addTag("immediate")
            .build()

        val immediatePush = OneTimeWorkRequestBuilder<NutritionOutboxPushWorker>()
            .setConstraints(pushConstraints)
            .addTag("nutrition")
            .addTag("supabase")
            .addTag("immediate")
            .build()

        Log.d(TAG, "Enqueueing immediate changes worker with REPLACE policy")
        wm.enqueueUniqueWork(
            IMMEDIATE_CHANGES_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            immediateChanges
        )

        Log.d(TAG, "Enqueueing immediate push worker with REPLACE policy")
        wm.enqueueUniqueWork(
            IMMEDIATE_PUSH_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            immediatePush
        )

        logCurrentWorkerStates(context, "AFTER triggerImmediateSync()")
        Log.d(TAG, "========== triggerImmediateSync() complete ==========")
    }

    /**
     * Cancel both nutrition workers
     */
    fun cancel(context: Context) {
        Log.d(TAG, "========== cancel() called ==========")
        logCurrentWorkerStates(context, "BEFORE cancel()")

        val wm = WorkManager.getInstance(context)

        Log.d(TAG, "Cancelling all nutrition workers")
        wm.cancelUniqueWork(HOURLY_WORK_NAME)
        wm.cancelUniqueWork(PUSH_WORK_NAME)
        wm.cancelUniqueWork(IMMEDIATE_CHANGES_WORK_NAME)
        wm.cancelUniqueWork(IMMEDIATE_PUSH_WORK_NAME)

        logCurrentWorkerStates(context, "AFTER cancel()")
        Log.d(TAG, "========== cancel() complete ==========")
    }

    /**
     * Log current state of all nutrition workers
     */
    private fun logCurrentWorkerStates(context: Context, label: String) {
        Log.d(TAG, "---------- Worker States: $label ----------")

        val wm = WorkManager.getInstance(context)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val hourlyInfo = wm.getWorkInfosForUniqueWork(HOURLY_WORK_NAME).get()
                val pushInfo = wm.getWorkInfosForUniqueWork(PUSH_WORK_NAME).get()
                val immediateChangesInfo = wm.getWorkInfosForUniqueWork(IMMEDIATE_CHANGES_WORK_NAME).get()
                val immediatePushInfo = wm.getWorkInfosForUniqueWork(IMMEDIATE_PUSH_WORK_NAME).get()

                Log.d(TAG, "HOURLY_WORK (${HOURLY_WORK_NAME}):")
                logWorkInfoList(hourlyInfo)

                Log.d(TAG, "PUSH_WORK (${PUSH_WORK_NAME}):")
                logWorkInfoList(pushInfo)

                Log.d(TAG, "IMMEDIATE_CHANGES (${IMMEDIATE_CHANGES_WORK_NAME}):")
                logWorkInfoList(immediateChangesInfo)

                Log.d(TAG, "IMMEDIATE_PUSH (${IMMEDIATE_PUSH_WORK_NAME}):")
                logWorkInfoList(immediatePushInfo)

            } catch (e: Exception) {
                Log.e(TAG, "Error getting worker states: ${e.message}", e)
            }
        }

        Log.d(TAG, "---------- End Worker States ----------")
    }

    /**
     * Log details about a list of WorkInfo objects
     */
    private fun logWorkInfoList(workInfoList: List<WorkInfo>) {
        if (workInfoList.isEmpty()) {
            Log.d(TAG, "  → NO WORKERS FOUND")
            return
        }

        workInfoList.forEachIndexed { index, workInfo ->
            Log.d(TAG, "  Worker #$index:")
            Log.d(TAG, "    ID: ${workInfo.id}")
            Log.d(TAG, "    State: ${workInfo.state}")
            Log.d(TAG, "    Run Attempt: ${workInfo.runAttemptCount}")
            Log.d(TAG, "    Tags: ${workInfo.tags}")

            if (workInfo.state == WorkInfo.State.ENQUEUED) {
                Log.d(TAG, "    Next run: scheduled by WorkManager")
            }

            if (workInfo.outputData.keyValueMap.isNotEmpty()) {
                Log.d(TAG, "    Output: ${workInfo.outputData.keyValueMap}")
            }
        }
    }

    /**
     * Utility function to check worker status (can be called from UI/testing)
     */
    fun logAllNutritionWorkerStatus(context: Context) {
        Log.d(TAG, "========== MANUAL STATUS CHECK ==========")
        logCurrentWorkerStates(context, "MANUAL CHECK")
        Log.d(TAG, "========== END MANUAL STATUS CHECK ==========")
    }
}