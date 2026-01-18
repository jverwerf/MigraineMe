package com.migraineme

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class WhoopTokenUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext

        // Need a valid Supabase token to authenticate edge functions.
        val supaAccessToken = SessionStore.getValidAccessToken(ctx)
            ?.takeIf { it.isNotBlank() }
            ?: return Result.retry()

        // Ensure userId persisted (WhoopTokenStore is user-bound).
        val existingUserId = SessionStore.readUserId(ctx)
        if (existingUserId.isNullOrBlank()) {
            val derived = JwtUtils.extractUserIdFromAccessToken(supaAccessToken)
            if (!derived.isNullOrBlank()) {
                SessionStore.saveUserId(ctx, derived)
            }
        }

        val whoopToken = runCatching { WhoopTokenStore(ctx).load() }.getOrNull()
            ?: return Result.success()

        val edge = EdgeFunctionsService()

        val uploaded = runCatching { edge.upsertWhoopTokenToSupabase(ctx, whoopToken) }
            .getOrDefault(false)

        if (!uploaded) {
            Log.w(TAG, "upsert-whoop-token failed; retrying")
            return Result.retry()
        }

        val backfillEnqueued = runCatching { edge.enqueueLoginBackfillGuaranteed(ctx) }
            .getOrDefault(false)

        if (!backfillEnqueued) {
            Log.w(TAG, "enqueue-login-backfill failed; retrying")
            return Result.retry()
        }

        Log.d(TAG, "WHOOP token uploaded and backfill enqueued")
        return Result.success()
    }

    companion object {
        private const val TAG = "WhoopTokenUploadWorker"
        private const val UNIQUE_WORK_NAME = "whoop_token_upload_and_backfill"

        fun enqueueNow(context: Context) {
            val appCtx = context.applicationContext

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val req = OneTimeWorkRequestBuilder<WhoopTokenUploadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            // CRITICAL: Use REPLACE (not KEEP) so we always trigger a run.
            // KEEP can result in "stuck" ENQUEUED work preventing any future triggers,
            // which matches your symptom: no new edge_audit rows.
            WorkManager.getInstance(appCtx)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
