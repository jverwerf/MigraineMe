package com.migraineme

import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Local launch counter feeding Google Ads.
 *
 * Bumped at the same spot as the server-side daily activity ping, so it mirrors
 * user_activity_daily.launch_count. On the THIRD launch it logs a single
 * Firebase Analytics event ("third_open"), which Google Ads imports as the
 * conversion the app campaigns bid for: people who come back, not click-and-go
 * installs. Best-effort, never blocks startup.
 */
object LaunchCounter {
    private const val PREFS = "launch_counter"
    private const val KEY_COUNT = "n"
    private const val KEY_SENT = "third_open_sent"
    const val EVENT_THIRD_OPEN = "third_open"

    fun bump(ctx: Context) {
        try {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val n = sp.getInt(KEY_COUNT, 0) + 1
            sp.edit().putInt(KEY_COUNT, n).apply()
            if (n >= 3 && !sp.getBoolean(KEY_SENT, false)) {
                FirebaseAnalytics.getInstance(ctx).logEvent(EVENT_THIRD_OPEN, null)
                sp.edit().putBoolean(KEY_SENT, true).apply()
            }
        } catch (_: Exception) {}
    }
}
