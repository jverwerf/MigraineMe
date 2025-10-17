package com.migraineme

import android.content.Context

/**
 * Stores WHOOP tokens privately. No Supabase changes.
 */
class WhoopTokenStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("whoop_tokens", Context.MODE_PRIVATE)

    fun save(t: WhoopToken) {
        prefs.edit()
            .putString("access_token", t.accessToken)
            .putString("refresh_token", t.refreshToken)
            .putString("token_type", t.tokenType)
            .putLong("expires_at", t.expiresAtMillis)
            .apply()
    }

    fun load(): WhoopToken? {
        val access = prefs.getString("access_token", null) ?: return null
        val refresh = prefs.getString("refresh_token", "") ?: ""
        val type = prefs.getString("token_type", "Bearer") ?: "Bearer"
        val exp = prefs.getLong("expires_at", 0L)
        return WhoopToken(access, refresh, type, exp)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
