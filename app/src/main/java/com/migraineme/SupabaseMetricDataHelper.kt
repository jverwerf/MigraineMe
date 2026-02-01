package com.migraineme

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SupabaseMetricDataHelper {

    /**
     * Returns true if at least one row exists for this metric
     * for the current user.
     *
     * Uses Edge Function (auth + RLS safe).
     */
    suspend fun hasAnyData(
        context: Context,
        metric: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            EdgeFunctionsService().hasAnyMetricData(
                context = context,
                metric = metric
            )
        } catch (_: Exception) {
            false
        }
    }
}

