package com.migraineme

import android.content.Context

/**
 * Helper for toggling metrics ON/OFF with worker scheduling
 *
 * This is the SINGLE SOURCE OF TRUTH for worker scheduling.
 * Does NOT manage state - that's in Supabase.
 *
 * Used by:
 * - ThirdPartyConnectionsScreen (when granting permissions)
 * - DataSettingsScreen (when user manually toggles)
 */
object MetricToggleHelper {

    /**
     * Toggle a metric ON or OFF
     *
     * This function ONLY schedules or cancels workers.
     * State management is handled separately via Supabase.
     *
     * @param context Application context
     * @param metric The metric name (e.g., "nutrition", "menstruation")
     * @param enabled True to enable, false to disable
     */
    fun toggle(
        context: Context,
        metric: String,
        enabled: Boolean
    ) {
        android.util.Log.d("MetricToggle", "Toggling $metric to ${if (enabled) "ON" else "OFF"}")

        // Schedule or cancel workers based on metric type
        if (enabled) {
            when (metric) {
                "nutrition" -> {
                    NutritionSyncScheduler.schedule(context)
                    android.util.Log.d("MetricToggle", "Scheduled nutrition workers")
                }
                "menstruation" -> {
                    MenstruationSyncScheduler.schedule(context)
                    android.util.Log.d("MetricToggle", "Scheduled menstruation workers")
                }
                "ambient_noise_samples" -> {
                    AmbientNoiseSampleWorker.schedule(context)
                    AmbientNoiseWatchdogWorker.schedule(context)
                    android.util.Log.d("MetricToggle", "Scheduled ambient noise workers")
                }
                "screen_time_daily" -> {
                    ScreenTimeDailySyncWorker.scheduleNext(context)
                    ScreenTimeWatchdogWorker.schedule(context)
                    android.util.Log.d("MetricToggle", "Scheduled screen time workers")
                }
            }
        } else {
            when (metric) {
                "nutrition" -> {
                    NutritionSyncScheduler.cancel(context)
                    android.util.Log.d("MetricToggle", "Cancelled nutrition workers")
                }
                "menstruation" -> {
                    MenstruationSyncScheduler.cancel(context)
                    android.util.Log.d("MetricToggle", "Cancelled menstruation workers")
                }
                "ambient_noise_samples" -> {
                    AmbientNoiseSampleWorker.cancel(context)
                    AmbientNoiseWatchdogWorker.cancel(context)
                    android.util.Log.d("MetricToggle", "Cancelled ambient noise workers")
                }
                "screen_time_daily" -> {
                    ScreenTimeDailySyncWorker.cancel(context)
                    ScreenTimeWatchdogWorker.cancel(context)
                    android.util.Log.d("MetricToggle", "Cancelled screen time workers")
                }
            }
        }
    }
}