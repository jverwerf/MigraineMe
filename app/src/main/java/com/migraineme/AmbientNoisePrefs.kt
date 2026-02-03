package com.migraineme

import android.content.Context

/**
 * Local storage for ambient noise settings and last sample time.
 * Avoids network calls on every screen unlock.
 */
object AmbientNoisePrefs {
    private const val PREFS_NAME = "ambient_noise_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_SUCCESSFUL_SAMPLE_TIME = "last_successful_sample_time"
    private const val KEY_LAST_SAMPLE_L_MEAN = "last_sample_l_mean"
    private const val KEY_LAST_SAMPLE_L_MAX = "last_sample_l_max"

    private fun prefs(context: Context) = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getLastSuccessfulSampleTime(context: Context): Long {
        return prefs(context).getLong(KEY_LAST_SUCCESSFUL_SAMPLE_TIME, 0L)
    }

    /**
     * Call this after a successful (non-zero) sample upload.
     */
    fun recordSuccessfulSample(context: Context, lMean: Double, lMax: Double) {
        prefs(context).edit()
            .putLong(KEY_LAST_SUCCESSFUL_SAMPLE_TIME, System.currentTimeMillis())
            .putFloat(KEY_LAST_SAMPLE_L_MEAN, lMean.toFloat())
            .putFloat(KEY_LAST_SAMPLE_L_MAX, lMax.toFloat())
            .apply()
    }

    /**
     * Get last sample values (for display purposes if needed)
     */
    fun getLastSampleValues(context: Context): Pair<Float, Float> {
        val p = prefs(context)
        return Pair(
            p.getFloat(KEY_LAST_SAMPLE_L_MEAN, 0f),
            p.getFloat(KEY_LAST_SAMPLE_L_MAX, 0f)
        )
    }
}
