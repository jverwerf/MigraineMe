package com.migraineme

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules menstruation sync workers
 *
 * - Changes worker: Runs every 15 minutes (detects new/updated/deleted periods)
 * - Push worker: Runs every 15 minutes (uploads to Supabase)
 *
 * Total max delay: ~30 minutes (15 min to detect + 15 min to upload)
 */
object MenstruationSyncScheduler {

    private const val CHANGES_WORK_NAME = "hc_menstruation_changes_15m"
    private const val PUSH_WORK_NAME = "menstruation_outbox_push_15m"

    /**
     * Schedule both menstruation workers
     */
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)

        val changesConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val pushConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val changes = PeriodicWorkRequestBuilder<HealthConnectMenstruationChangesWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(changesConstraints)
            .addTag("menstruation")
            .addTag("health_connect")
            .build()

        val push = PeriodicWorkRequestBuilder<MenstruationOutboxPushWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(pushConstraints)
            .addTag("menstruation")
            .addTag("supabase")
            .build()

        wm.enqueueUniquePeriodicWork(
            CHANGES_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            changes
        )

        wm.enqueueUniquePeriodicWork(
            PUSH_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            push
        )

        android.util.Log.d("MenstruationSync", "Scheduled workers - 15 min intervals")
    }

    /**
     * Cancel both menstruation workers
     */
    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(CHANGES_WORK_NAME)
        wm.cancelUniqueWork(PUSH_WORK_NAME)

        android.util.Log.d("MenstruationSync", "Cancelled workers")
    }
}