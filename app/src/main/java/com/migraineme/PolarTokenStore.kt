package com.migraineme

import android.content.Context

/**
 * Stores Polar tokens privately.
 * Tokens are bound to the currently logged-in Supabase userId.
 * Polar tokens don't expire — no refresh token needed.
 */
class PolarTokenStore(private val context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("polar_tokens", Context.MODE_PRIVATE)

    private companion object {
        private const val KEY_OWNER_USER_ID = "owner_user_id"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_TYPE = "token_type"
        private const val KEY_POLAR_USER_ID = "polar_user_id"
    }

    fun save(t: PolarToken) {
        val currentUserId = SessionStore.readUserId(app).orEmpty()

        prefs.edit()
            .putString(KEY_OWNER_USER_ID, currentUserId)
            .putString(KEY_ACCESS, t.accessToken)
            .putString(KEY_TYPE, t.tokenType)
            .putString(KEY_POLAR_USER_ID, t.polarUserId)
            .apply()
    }

    fun load(): PolarToken? {
        val currentUserId = SessionStore.readUserId(app).orEmpty()
        val ownerUserId = prefs.getString(KEY_OWNER_USER_ID, "") ?: ""

        if (currentUserId.isBlank()) return null

        if (ownerUserId.isNotBlank() && ownerUserId != currentUserId) {
            clear()
            return null
        }

        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        val type = prefs.getString(KEY_TYPE, "Bearer") ?: "Bearer"
        val polarUserId = prefs.getString(KEY_POLAR_USER_ID, null)

        return PolarToken(
            accessToken = access,
            tokenType = type,
            polarUserId = polarUserId
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
