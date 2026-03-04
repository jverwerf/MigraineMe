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

class OuraTokenUploadWorker(
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

        val ouraToken = runCatching { OuraTokenStore(ctx).load() }.getOrNull()
            ?: return Result.success()

        val edge = EdgeFunctionsService()

        val uploaded = runCatching { edge.upsertOuraTokenToSupabase(ctx, ouraToken) }
            .getOrDefault(false)

        if (!uploaded) {
            Log.w(TAG, "upsert-oura-token failed; retrying")
            return Result.retry()
        }

        // Best-effort backfill
        runCatching { edge.enqueueLoginBackfillGuaranteed(ctx) }
            .onFailure { Log.w(TAG, "enqueue-login-backfill failed (non-fatal): ${it.message}") }

        Log.d(TAG, "Oura token uploaded successfully")
        return Result.success()
    }

    companion object {
        private const val TAG = "OuraTokenUploadWorker"
        private const val UNIQUE_WORK_NAME = "oura_token_upload_and_backfill"

        fun enqueueNow(context: Context) {
            val appCtx = context.applicationContext

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val req = OneTimeWorkRequestBuilder<OuraTokenUploadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(appCtx)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
