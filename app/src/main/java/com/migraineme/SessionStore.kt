// FILE: app/src/main/java/com/migraineme/SessionStore.kt
package com.migraineme

import android.content.Context
import android.content.Context.MODE_PRIVATE
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SessionStore {
    private const val PREFS = "session_prefs"

    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EXPIRES_IN = "expires_in_seconds"
    private const val KEY_OBTAINED_AT = "obtained_at_ms"

    private const val KEY_USER_ID = "user_id"
    private const val KEY_AUTH_PROVIDER = "auth_provider" // "email" | "google" | "facebook" | null

    // Small skew so we refresh a bit before expiry to avoid edge failures in workers.
    private const val EXPIRY_SKEW_MS = 60_000L

    private val refreshMutex = Mutex()

    fun saveAccessToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCESS, token)
            .apply()
    }

    fun saveRefreshToken(context: Context, refreshToken: String?) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_REFRESH, refreshToken)
            .apply()
    }

    fun saveExpiresIn(context: Context, expiresInSeconds: Long?) {
        val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
        if (expiresInSeconds == null) {
            prefs.remove(KEY_EXPIRES_IN)
        } else {
            prefs.putLong(KEY_EXPIRES_IN, expiresInSeconds)
        }
        prefs.apply()
    }

    fun saveObtainedAt(context: Context, obtainedAtMs: Long) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putLong(KEY_OBTAINED_AT, obtainedAtMs)
            .apply()
    }

    /**
     * Saves the user id separately so UI (e.g., Profile) can display a stable identity
     * without needing a network call.
     */
    fun saveUserId(context: Context, userId: String?) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_USER_ID, userId)
            .apply()
    }

    /**
     * Persists which auth method was used for this session.
     * Expected values: "email", "google", "facebook"
     */
    fun saveAuthProvider(context: Context, provider: String?) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_AUTH_PROVIDER, provider)
            .apply()
    }

    fun readAuthProvider(context: Context): String? {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_AUTH_PROVIDER, null)
    }

    /**
     * New canonical session writer.
     *
     * Note: first three params match your old call sites (context, token, userId),
     * so existing positional calls still work.
     */
    fun saveSession(
        context: Context,
        accessToken: String,
        userId: String?,
        provider: String? = null,
        refreshToken: String? = null,
        expiresIn: Long? = null,
        obtainedAtMs: Long = System.currentTimeMillis()
    ) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCESS, accessToken)
            .putString(KEY_REFRESH, refreshToken)
            .apply()

        saveUserId(context, userId)
        saveAuthProvider(context, provider)
        saveExpiresIn(context, expiresIn)
        saveObtainedAt(context, obtainedAtMs)
    }

    fun readAccessToken(context: Context): String? {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_ACCESS, null)
    }

    fun readRefreshToken(context: Context): String? {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_REFRESH, null)
    }

    fun readExpiresIn(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)
        return if (prefs.contains(KEY_EXPIRES_IN)) prefs.getLong(KEY_EXPIRES_IN, 0L) else null
    }

    fun readObtainedAt(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)
        return if (prefs.contains(KEY_OBTAINED_AT)) prefs.getLong(KEY_OBTAINED_AT, 0L) else null
    }

    fun readUserId(context: Context): String? {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_USER_ID, null)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * Returns a valid access token.
     *
     * - If the current access token is not expired (with skew), return it.
     * - If expired and refresh_token exists, refresh via Supabase and persist new tokens.
     * - If we cannot refresh, returns null (workers should treat as "skip").
     */
    suspend fun getValidAccessToken(context: Context): String? {
        return refreshMutex.withLock {
            val access = readAccessToken(context)
            if (access.isNullOrBlank()) return@withLock null

            val expiresIn = readExpiresIn(context)
            val obtainedAt = readObtainedAt(context)

            // If we don't have expiry metadata, assume it's still usable (backward compatibility).
            if (expiresIn == null || obtainedAt == null) {
                return@withLock access
            }

            val expiresAt = obtainedAt + (expiresIn * 1000L)
            val now = System.currentTimeMillis()

            // Still valid (with skew) -> return.
            if (now + EXPIRY_SKEW_MS < expiresAt) {
                return@withLock access
            }

            // Expired -> refresh.
            val refresh = readRefreshToken(context)
            if (refresh.isNullOrBlank()) {
                return@withLock null
            }

            return@withLock try {
                val ses = SupabaseAuthService.refreshSession(refresh)

                val newAccess = ses.accessToken
                if (newAccess.isNullOrBlank()) {
                    null
                } else {
                    // Supabase may rotate refresh tokens; persist both.
                    saveSession(
                        context = context,
                        accessToken = newAccess,
                        userId = readUserId(context),
                        provider = readAuthProvider(context),
                        refreshToken = ses.refreshToken ?: refresh,
                        expiresIn = ses.expiresIn,
                        obtainedAtMs = System.currentTimeMillis()
                    )
                    newAccess
                }
            } catch (_: Throwable) {
                null
            }
        }
    }
}
