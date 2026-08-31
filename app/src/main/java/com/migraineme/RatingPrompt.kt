package com.migraineme

import android.app.Activity
import android.content.Context
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Native in-app rating request (Play In-App Review).
 *
 * Only ever called from a positive moment (the Well done card on Home), and
 * only when no migraine was logged in the last 24h — the caller checks that.
 * Own guards: at least 7 days since the app first saw the user, and at most
 * one ask every 120 days. Play applies its own quota on top, so the flow can
 * silently show nothing; that's expected and fine.
 */
object RatingPrompt {
    private const val PREFS = "rating_prompt"
    private const val KEY_FIRST_SEEN = "first_seen"
    private const val KEY_LAST_ASKED = "last_asked"
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val MIN_DAYS_BEFORE_FIRST_ASK = 7L
    private const val MIN_DAYS_BETWEEN_ASKS = 120L

    fun maybeAsk(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val firstSeen = prefs.getLong(KEY_FIRST_SEEN, 0L)
        if (firstSeen == 0L) {
            prefs.edit().putLong(KEY_FIRST_SEEN, now).apply()
            return
        }
        if (now - firstSeen < MIN_DAYS_BEFORE_FIRST_ASK * DAY_MS) return
        val lastAsked = prefs.getLong(KEY_LAST_ASKED, 0L)
        if (lastAsked != 0L && now - lastAsked < MIN_DAYS_BETWEEN_ASKS * DAY_MS) return
        // Mark before launching: if Play shows the dialog we must not re-ask,
        // and the API gives no signal about whether it actually appeared.
        prefs.edit().putLong(KEY_LAST_ASKED, now).apply()
        val manager = ReviewManagerFactory.create(activity)
        manager.requestReviewFlow().addOnSuccessListener { info ->
            manager.launchReviewFlow(activity, info)
        }
    }
}
