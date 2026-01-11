package com.migraineme

import android.app.Activity
import android.content.Intent
import android.net.Uri

/**
 * Starts Supabase OAuth for Facebook by opening the system browser.
 * Keeps auth fully in Supabase (no Facebook SDK, no auth-mode changes).
 *
 * Redirect comes back to: migraineme://auth/callback
 */
class FacebookAuthService(
    private val redirectUri: String = "migraineme://auth/callback"
) {
    fun startAuth(activity: Activity) {
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')
        val uri = Uri.parse("$base/auth/v1/authorize").buildUpon()
            .appendQueryParameter("provider", "facebook")
            .appendQueryParameter("redirect_to", redirectUri)
            .build()

        activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
}
