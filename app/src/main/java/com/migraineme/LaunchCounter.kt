package com.migraineme

import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Fires the Google Ads conversion "third_open" once, on the user's THIRD ACTIVE
 * DAY within 14 days. An active day is a cold launch OR a manual log, counted by
 * the server (rpc active_days_14d) so it matches the business dashboard's
 * "engaged" definition. Before 25 Oct 2026 this fired on the third cold launch.
 * Best-effort, never blocks startup; an offline launch simply retries next time.
 */
object LaunchCounter {
    private const val PREFS = "launch_counter"
    private const val KEY_SENT = "third_open_sent"
    const val EVENT_THIRD_OPEN = "third_open"
    const val ACTIVE_DAYS_NEEDED = 3

    fun maybeFire(ctx: Context, activeDays: Int?) {
        if (activeDays == null || activeDays < ACTIVE_DAYS_NEEDED) return
        try {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (sp.getBoolean(KEY_SENT, false)) return
            FirebaseAnalytics.getInstance(ctx).logEvent(EVENT_THIRD_OPEN, null)
            sp.edit().putBoolean(KEY_SENT, true).apply()
        } catch (_: Exception) {}
    }
}
