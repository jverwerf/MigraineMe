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

        val supaAccessToken = SessionStore.getValidAccessToken(ctx)
            ?.takeIf { it.isNotBlank() }
            ?: return Result.retry()

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

        // Best-effort backfill — do NOT retry the whole worker if this fails.
        // The server will handle backfill via cron regardless.
        runCatching { edge.enqueueLoginBackfillGuaranteed(ctx) }
            .onFailure { Log.w(TAG, "enqueue-login-backfill failed (non-fatal): ${it.message}") }

        Log.d(TAG, "WHOOP token uploaded successfully")
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

            WorkManager.getInstance(appCtx)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }
}