package com.migraineme

import android.content.Context
import android.util.Log

/**
 * Persists Garmin OAuth2 tokens in SharedPreferences, user-bound.
 * Mirrors OuraTokenStore / PolarTokenStore pattern exactly.
 *
 * Garmin tokens expire:
 *   - access_token:  ~24 hours
 *   - refresh_token: ~90 days
 * Refresh is handled server-side via garmin-token-refresh Edge Function.
 */
class GarminTokenStore(context: Context) {

    companion object {
        private const val PREFS = "garmin_tokens"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_TYPE = "token_type"
        private const val KEY_EXPIRES_AT = "expires_at_millis"
        private const val KEY_GARMIN_USER_ID = "garmin_user_id"
        private const val KEY_OWNER = "owner_user_id"
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(token: GarminToken) {
        val userId = runCatching { SessionStore.readUserId(prefs.javaClass.getDeclaredField("mContext").let {
            it.isAccessible = true
            it.get(prefs) as Context
        }) }.getOrNull()

        val ctx = try {
            val field = prefs.javaClass.getDeclaredField("mContext")
            field.isAccessible = true
            field.get(prefs) as? Context
        } catch (_: Exception) { null }

        val ownerUserId = ctx?.let { SessionStore.readUserId(it) }

        prefs.edit()
            .putString(KEY_ACCESS, token.accessToken)
            .putString(KEY_REFRESH, token.refreshToken)
            .putString(KEY_TYPE, token.tokenType)
            .putLong(KEY_EXPIRES_AT, token.expiresAtMillis)
            .putString(KEY_GARMIN_USER_ID, token.garminUserId)
            .putString(KEY_OWNER, ownerUserId)
            .apply()
    }

    fun save(token: GarminToken, context: Context) {
        val ownerUserId = SessionStore.readUserId(context)
        prefs.edit()
            .putString(KEY_ACCESS, token.accessToken)
            .putString(KEY_REFRESH, token.refreshToken)
            .putString(KEY_TYPE, token.tokenType)
            .putLong(KEY_EXPIRES_AT, token.expiresAtMillis)
            .putString(KEY_GARMIN_USER_ID, token.garminUserId)
            .putString(KEY_OWNER, ownerUserId)
            .apply()
    }

    fun load(): GarminToken? {
        val access = prefs.getString(KEY_ACCESS, null)
        if (access.isNullOrBlank()) return null

        return GarminToken(
            accessToken = access,
            refreshToken = prefs.getString(KEY_REFRESH, "") ?: "",
            tokenType = prefs.getString(KEY_TYPE, "bearer") ?: "bearer",
            expiresAtMillis = prefs.getLong(KEY_EXPIRES_AT, 0L),
            garminUserId = prefs.getString(KEY_GARMIN_USER_ID, null)
        )
    }

    fun loadIfOwnedByCurrentUser(context: Context): GarminToken? {
        val currentUser = SessionStore.readUserId(context)
        val owner = prefs.getString(KEY_OWNER, null)

        if (owner != null && currentUser != null && owner != currentUser) {
            Log.w("GarminTokenStore", "Token belongs to $owner, current user is $currentUser — clearing")
            clear()
            return null
        }
        return load()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
