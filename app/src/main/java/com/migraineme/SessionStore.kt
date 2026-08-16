// FILE: app/src/main/java/com/migraineme/SessionStore.kt
package com.migraineme

import android.content.Context
import android.content.Context.MODE_PRIVATE
import java.io.IOException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Outcome of asking for a usable access token.
 *
 * The distinction is the point: a refresh we could not DELIVER is not the same
 * as a refresh the server REJECTED, and collapsing both to `null` is what let
 * one offline moment read as "signed out". Only [SignedOut] means the user has
 * no session; [Unreachable] means we could not ask, and their stored session is
 * still perfectly good.
 */
sealed class TokenResult {
    data class Valid(val accessToken: String) : TokenResult()
    object Unreachable : TokenResult()
    object SignedOut : TokenResult()
}

object SessionStore {
    private const val PREFS = "session_prefs"

    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EXPIRES_IN = "expires_in_seconds"
    private const val KEY_OBTAINED_AT = "obtained_at_ms"

    private const val KEY_USER_ID = "user_id"
    private const val KEY_AUTH_PROVIDER = "auth_provider" // "email" | "google" | "facebook" | null
    private const val KEY_FCM_TOKEN = "fcm_token"

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
     * Save FCM token for push notifications
     */
    fun saveFcmToken(context: Context, token: String?) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_FCM_TOKEN, token)
            .apply()
    }

    /**
     * Read FCM token
     */
    fun readFcmToken(context: Context): String? {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_FCM_TOKEN, null)
    }

    /**
     * Mark the one-time "trial just ended" takeover as seen for this user
     */
    fun saveTrialEndedSeen(context: Context, userId: String) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean("trial_ended_seen_$userId", true)
            .apply()
    }

    /**
     * Read whether the "trial just ended" takeover was already shown for this user
     */
    fun readTrialEndedSeen(context: Context, userId: String): Boolean {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean("trial_ended_seen_$userId", false)
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
     *
     * IMPORTANT: Ensure KEY_USER_ID is always present when an access token is present.
     * WHOOP token ownership checks depend on a stable Supabase user_id in SessionStore.
     */
    /**
     * A usable access token, or why there isn't one.
     *
     * Callers that only need the token can keep using [getValidAccessToken];
     * callers that decide whether the user is still SIGNED IN must use this,
     * so an unreachable server does not read as a sign-out.
     */
    suspend fun getAccessToken(context: Context): TokenResult {
        return refreshMutex.withLock {
            val access = readAccessToken(context)
            if (access.isNullOrBlank()) return@withLock TokenResult.SignedOut

            // CHANGE #1:
            // If user_id is missing, derive it from the access token JWT and persist it.
            val existingUserId = readUserId(context)
            if (existingUserId.isNullOrBlank()) {
                val derived = JwtUtils.extractUserIdFromAccessToken(access)
                if (!derived.isNullOrBlank()) {
                    saveUserId(context, derived)
                }
            }

            val expiresIn = readExpiresIn(context)
            val obtainedAt = readObtainedAt(context)

            // If we don't have expiry metadata, assume it's still usable (backward compatibility).
            if (expiresIn == null || obtainedAt == null) {
                return@withLock TokenResult.Valid(access)
            }

            val expiresAt = obtainedAt + (expiresIn * 1000L)
            val now = System.currentTimeMillis()

            // Still valid (with skew) -> return.
            if (now + EXPIRY_SKEW_MS < expiresAt) {
                return@withLock TokenResult.Valid(access)
            }

            // Expired -> refresh.
            val refresh = readRefreshToken(context)
            if (refresh.isNullOrBlank()) {
                return@withLock TokenResult.SignedOut
            }

            return@withLock try {
                val ses = SupabaseAuthService.refreshSession(refresh)

                val newAccess = ses.accessToken
                if (newAccess.isNullOrBlank()) {
                    // The call came back, and it came back without a token —
                    // refreshSession does not validate status, so a rejected
                    // refresh deserialises to null fields. That is a genuine
                    // sign-out.
                    TokenResult.SignedOut
                } else {
                    // CHANGE #2:
                    // Always derive userId from the refreshed access token (do not depend on existing prefs).
                    val newUserId =
                        JwtUtils.extractUserIdFromAccessToken(newAccess)
                            ?: readUserId(context)

                    // Supabase may rotate refresh tokens; persist both.
                    saveSession(
                        context = context,
                        accessToken = newAccess,
                        userId = newUserId,
                        provider = readAuthProvider(context),
                        refreshToken = ses.refreshToken ?: refresh,
                        expiresIn = ses.expiresIn,
                        obtainedAtMs = System.currentTimeMillis()
                    )
                    TokenResult.Valid(newAccess)
                }
            } catch (_: IOException) {
                // Never delivered: offline, DNS, timeout, connection reset. The
                // refresh token on this device is untouched and still valid, so
                // the user is NOT signed out — we simply cannot mint a fresh
                // access token until the network is back.
                TokenResult.Unreachable
            } catch (_: Throwable) {
                // Anything else (malformed body, serialisation) — treat as a
                // real failure rather than silently keeping the user in.
                TokenResult.SignedOut
            }
        }
    }

    /**
     * A usable access token, or null. Unchanged contract for the many callers
     * that just need a bearer token; use [getAccessToken] when the ANSWER
     * matters (i.e. when deciding whether the user is signed in).
     */
    suspend fun getValidAccessToken(context: Context): String? =
        (getAccessToken(context) as? TokenResult.Valid)?.accessToken
}
