package com.migraineme

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.max

/**
 * WHOOP OAuth + token refresh.
 *
 * IMPORTANT: This class intentionally keeps compatibility methods used by your app:
 *  - startAuth(activity)
 *  - completeAuth(context): Boolean
 *  - refresh(context): Boolean
 */
class WhoopAuthService {

    companion object {
        private const val TAG = "WHOOP"

        // Must match WHOOP dashboard
        private const val CLIENT_ID = "354e4d44-3780-4e99-a655-a306776879ee"

        // Client secret (required by WHOOP token endpoint for confidential clients)
        private const val CLIENT_SECRET =
            "ce7314f4cdfab97a16467747a174a0ba8f1a8c561bc1dcc149674171ccd85d00"

        // Must match:
        //  - AndroidManifest intent-filter for migraineme://whoop/callback
        //  - WHOOP app registered redirect
        private const val REDIRECT_URI = "migraineme://whoop/callback"

        private const val AUTH_URL = "https://api.prod.whoop.com/oauth/oauth2/auth"
        private const val TOKEN_URL = "https://api.prod.whoop.com/oauth/oauth2/token"

        private const val PREFS = "whoop_oauth"
        private const val KEY_STATE = "state"
        private const val KEY_VERIFIER = "code_verifier"
        private const val KEY_LAST_URI = "last_uri"
        private const val KEY_TOKEN_ERROR = "token_error"

        private const val EXPIRY_SKEW_MS = 60_000L

        // FIX:
        // WHOOP returns a refresh token only when requesting the `offline` scope.
        // Without it, refresh_token will be empty and upsert-whoop-token will (correctly) reject.
        private const val SCOPE =
            "offline read:recovery read:sleep read:workout read:cycles read:body_measurement"
    }

    /**
     * Compatibility: called by ThirdPartyConnectionsScreen.
     * Launches browser.
     */
    fun startAuth(activity: Activity) {
        val uri = buildAuthUri(activity.applicationContext)
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    /**
     * Builds auth Uri and stores PKCE + state.
     */
    fun buildAuthUri(context: Context): Uri {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val state = randomUrlSafe(24)
        val verifier = randomUrlSafe(64)
        val challenge = pkceChallengeS256(verifier)

        prefs.edit()
            .putString(KEY_STATE, state)
            .putString(KEY_VERIFIER, verifier)
            .putString(KEY_TOKEN_ERROR, "")
            .apply()

        return Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
    }

    /**
     * Compatibility: called by ThirdPartyConnectionsScreen.
     *
     * Reads stored callback uri from prefs ("whoop_oauth" / "last_uri"),
     * validates state + verifier, exchanges code for token, saves token.
     *
     * Returns true if token saved.
     */
    fun completeAuth(context: Context): Boolean {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val last = prefs.getString(KEY_LAST_URI, null)
        if (last.isNullOrBlank()) return false

        val uri = Uri.parse(last)

        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            prefs.edit().putString(KEY_TOKEN_ERROR, error).apply()
            return false
        }

        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            prefs.edit().putString(KEY_TOKEN_ERROR, "Missing authorization code").apply()
            return false
        }

        val returnedState = uri.getQueryParameter("state").orEmpty()
        val expectedState = prefs.getString(KEY_STATE, "").orEmpty()
        val verifier = prefs.getString(KEY_VERIFIER, "").orEmpty()

        if (expectedState.isBlank() || verifier.isBlank()) {
            prefs.edit().putString(KEY_TOKEN_ERROR, "Missing PKCE state/verifier. Reconnect WHOOP.").apply()
            return false
        }
        if (returnedState != expectedState) {
            prefs.edit().putString(KEY_TOKEN_ERROR, "State mismatch. Reconnect WHOOP.").apply()
            return false
        }

        val res = postForm(
            TOKEN_URL,
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to CLIENT_ID,
                "client_secret" to CLIENT_SECRET,
                "redirect_uri" to REDIRECT_URI,
                "code" to code,
                "code_verifier" to verifier
            )
        )

        return if (res.isSuccess) {
            val tok = res.getOrThrow()

            // CRITICAL: WhoopTokenStore binds tokens to SessionStore.userId.
            // Ensure userId is persisted before saving, derived from the already-stored Supabase access token.
            val currentUserId = SessionStore.readUserId(appCtx)
            if (currentUserId.isNullOrBlank()) {
                val access = SessionStore.readAccessToken(appCtx)
                if (!access.isNullOrBlank()) {
                    val derived = JwtUtils.extractUserIdFromAccessToken(access)
                    if (!derived.isNullOrBlank()) {
                        SessionStore.saveUserId(appCtx, derived)
                    }
                }
            }

            WhoopTokenStore(appCtx).save(tok)

            prefs.edit()
                .remove(KEY_STATE)
                .remove(KEY_VERIFIER)
                .remove(KEY_LAST_URI)
                .putString(KEY_TOKEN_ERROR, "")
                .apply()

            WhoopTokenUploadWorker.enqueueNow(appCtx)

            true
        } else {
            prefs.edit().putString(KEY_TOKEN_ERROR, res.exceptionOrNull()?.message ?: "WHOOP authentication failed").apply()
            false
        }
    }

    /**
     * Compatibility: called by MetricsSyncManager.
     * Best-effort refresh of WHOOP token if expired soon.
     * Returns true if token is valid after this call.
     */
    fun refresh(context: Context): Boolean {
        val appCtx = context.applicationContext
        val store = WhoopTokenStore(appCtx)
        val current = store.load() ?: return false

        if (!current.isExpiredSoon(EXPIRY_SKEW_MS)) return true
        if (current.refreshToken.isBlank()) return false

        val res = postForm(
            TOKEN_URL,
            mapOf(
                "grant_type" to "refresh_token",
                "client_id" to CLIENT_ID,
                "client_secret" to CLIENT_SECRET,
                "refresh_token" to current.refreshToken,
                // WHOOP recommends requesting offline when refreshing as well.
                "scope" to "offline"
            )
        )

        return if (res.isSuccess) {
            val refreshed = res.getOrThrow()

            // Preserve existing refresh token if WHOOP omits it in refresh response.
            val merged = if (refreshed.refreshToken.isBlank()) {
                refreshed.copy(refreshToken = current.refreshToken)
            } else {
                refreshed
            }

            store.save(merged)
            true
        } else {
            Log.w(TAG, "refresh failed: ${res.exceptionOrNull()?.message}")
            false
        }
    }

    fun getValidAccessToken(context: Context): String? {
        val appCtx = context.applicationContext
        val store = WhoopTokenStore(appCtx)
        val tok = store.load() ?: return null

        return if (!tok.isExpiredSoon(EXPIRY_SKEW_MS)) {
            tok.accessToken
        } else {
            val ok = refresh(appCtx)
            store.load()?.accessToken?.takeIf { ok }
        }
    }

    suspend fun refreshIfNeeded(context: Context): WhoopToken? {
        val appCtx = context.applicationContext
        val store = WhoopTokenStore(appCtx)
        val tok = store.load() ?: return null

        return if (!tok.isExpiredSoon(EXPIRY_SKEW_MS)) {
            tok
        } else {
            val ok = refresh(appCtx)
            store.load()?.takeIf { ok }
        }
    }

    private fun postForm(url: String, form: Map<String, String>): Result<WhoopToken> {
        val body = form.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            conn.outputStream.use { os ->
                os.write(body.toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { s ->
                BufferedReader(InputStreamReader(s)).readText()
            }.orEmpty()

            if (code !in 200..299) {
                Log.e(TAG, "token endpoint error $code: $text")
                val msg = runCatching {
                    val jo = JSONObject(text)
                    val err = jo.optString("error")
                    val desc = jo.optString("error_description")
                    if (err.isNotBlank()) "$err: $desc" else text
                }.getOrDefault(text)
                Result.failure(IllegalStateException(msg))
            } else {
                val jo = JSONObject(text)
                Result.success(WhoopToken.fromTokenResponse(jo))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun pkceChallengeS256(verifier: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(verifier.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun randomUrlSafe(lenBytes: Int): String {
        val b = ByteArray(max(16, lenBytes))
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            .take(lenBytes * 2)
    }
}

data class WhoopToken(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresAtMillis: Long = 0L
) {
    fun isExpiredSoon(skewMs: Long = 60_000L): Boolean {
        if (expiresAtMillis <= 0L) return false
        return System.currentTimeMillis() + skewMs >= expiresAtMillis
    }

    companion object {
        fun fromTokenResponse(jo: JSONObject): WhoopToken {
            val access = jo.optString("access_token", "")
            val refresh = jo.optString("refresh_token", "")
            val type = jo.optString("token_type", "Bearer")

            val nowMs = System.currentTimeMillis()

            val expiresInSecRaw = jo.optLong("expires_in", 0L)
            val expiresInSec = if (expiresInSecRaw > 0L) expiresInSecRaw else 3600L
            val expiresAt = nowMs + expiresInSec * 1000L

            return WhoopToken(
                accessToken = access,
                refreshToken = refresh,
                tokenType = type,
                expiresAtMillis = expiresAt
            )
        }
    }
}
