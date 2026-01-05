package com.migraineme

import android.content.Context

/**
 * Stores WHOOP tokens privately.
 *
 * Fix: bind WHOOP tokens to the currently logged-in Supabase userId.
 * - On save(): record owner_user_id
 * - On load(): only return tokens if owner_user_id matches current SessionStore userId
 *   Otherwise clear and return null, so another account won't "inherit" WHOOP connection.
 */
class WhoopTokenStore(private val context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("whoop_tokens", Context.MODE_PRIVATE)

    private companion object {
        private const val KEY_OWNER_USER_ID = "owner_user_id"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_TYPE = "token_type"
        private const val KEY_EXPIRES = "expires_at"
    }

    fun save(t: WhoopToken) {
        val currentUserId = SessionStore.readUserId(app).orEmpty()

        prefs.edit()
            .putString(KEY_OWNER_USER_ID, currentUserId)
            .putString(KEY_ACCESS, t.accessToken)
            .putString(KEY_REFRESH, t.refreshToken)
            .putString(KEY_TYPE, t.tokenType)
            .putLong(KEY_EXPIRES, t.expiresAtMillis)
            .apply()
    }

    fun load(): WhoopToken? {
        val currentUserId = SessionStore.readUserId(app).orEmpty()
        val ownerUserId = prefs.getString(KEY_OWNER_USER_ID, "") ?: ""

        // If there is no current Supabase session, treat as disconnected
        if (currentUserId.isBlank()) return null

        // If tokens belong to a different user, clear and treat as disconnected
        if (ownerUserId.isNotBlank() && ownerUserId != currentUserId) {
            clear()
            return null
        }

        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        val refresh = prefs.getString(KEY_REFRESH, "") ?: ""
        val type = prefs.getString(KEY_TYPE, "Bearer") ?: "Bearer"
        val exp = prefs.getLong(KEY_EXPIRES, 0L)
        return WhoopToken(access, refresh, type, exp)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
