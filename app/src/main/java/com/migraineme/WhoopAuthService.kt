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

class WhoopAuthService {

    companion object {
        private const val TAG = "WHOOP"

        // Client ID loaded from BuildConfig (set in local.properties)
        private val CLIENT_ID = BuildConfig.WHOOP_CLIENT_ID

        // Client secret is SERVER-SIDE ONLY — never in the APK.
        // Token exchange happens via the whoop-token-exchange Edge Function.

        private const val REDIRECT_URI = "whoop://migraineme/callback"

        private const val AUTH_URL = "https://api.prod.whoop.com/oauth/oauth2/auth"
        private const val REVOKE_URL = "https://api.prod.whoop.com/developer/v2/user/access"

        private const val PREFS = "whoop_oauth"
        private const val KEY_STATE = "state"
        private const val KEY_VERIFIER = "code_verifier"
        private const val KEY_LAST_URI = "last_uri"
        private const val KEY_TOKEN_ERROR = "token_error"

        private const val EXPIRY_SKEW_MS = 60_000L

        private const val SCOPE =
            "offline read:recovery read:sleep read:workout read:cycles read:body_measurement read:profile"
    }

    fun startAuth(activity: Activity) {
        val uri = buildAuthUri(activity.applicationContext)
        Log.e(TAG, "Opening WHOOP auth URL: $uri")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

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
            .appendQueryParameter("scope", SCOPE.replace("+", " "))
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
    }

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

        // ── Token exchange via server-side Edge Function ──
        // The client secret never leaves the server.
        val res = exchangeCodeViaServer(appCtx, code, verifier)

        return if (res.isSuccess) {
            val tok = res.getOrThrow()

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

            val store = WhoopTokenStore(appCtx)
            val existing = store.load()

            val merged = when {
                tok.refreshToken.isNotBlank() -> tok
                existing?.refreshToken?.isNotBlank() == true -> tok.copy(refreshToken = existing.refreshToken)
                else -> {
                    prefs.edit().putString(
                        KEY_TOKEN_ERROR,
                        "WHOOP did not return refresh_token. Reconnect and ensure offline scope is granted."
                    ).apply()
                    return false
                }
            }

            store.save(merged)

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
     * Exchange authorization code for tokens via server-side Edge Function.
     * The WHOOP client_secret lives in Supabase secrets — never in the APK.
     */
    private fun exchangeCodeViaServer(context: Context, code: String, codeVerifier: String): Result<WhoopToken> {
        val supaAccessToken = SessionStore.readAccessToken(context) ?: return Result.failure(
            IllegalStateException("No Supabase access token")
        )

        val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/whoop-token-exchange"

        val jsonBody = JSONObject().apply {
            put("code", code)
            put("code_verifier", codeVerifier)
        }.toString()

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $supaAccessToken")
            setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
        }

        return try {
            conn.outputStream.use { os ->
                os.write(jsonBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { s ->
                BufferedReader(InputStreamReader(s)).readText()
            }.orEmpty()

            if (responseCode !in 200..299) {
                Log.e(TAG, "whoop-token-exchange error $responseCode: $text")
                val msg = runCatching {
                    val jo = JSONObject(text)
                    jo.optString("error", text)
                }.getOrDefault(text)
                Result.failure(IllegalStateException(msg))
            } else {
                val jo = JSONObject(text)
                Result.success(WhoopToken.fromTokenResponse(jo))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "whoop-token-exchange exception", t)
            Result.failure(t)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * Server (sync-worker) handles all WHOOP token refreshes.
     * App must never refresh locally to avoid racing with the server
     * and burning the refresh token.
     */
    fun refresh(context: Context): Boolean {
        return true
    }

    /**
     * Server handles all WHOOP token refreshes.
     * Just return whatever we have locally.
     */
    suspend fun refreshIfNeeded(context: Context): WhoopToken? {
        return WhoopTokenStore(context.applicationContext).load()
    }

    suspend fun revokeAccessWithDebug(context: Context): Pair<Boolean, String> {
        val appCtx = context.applicationContext
        val tok = refreshIfNeeded(appCtx)

        if (tok == null || tok.accessToken.isBlank()) {
            return false to "No local WHOOP access token available to revoke."
        }

        val conn = (URL(REVOKE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            setRequestProperty("Authorization", "${tok.tokenType} ${tok.accessToken}")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { s ->
                BufferedReader(InputStreamReader(s)).readText()
            }.orEmpty()

            val ok = (code == 204) || (code in 200..299)
            if (!ok) {
                Log.w(TAG, "WHOOP revoke failed HTTP $code: $body")
            }

            ok to "HTTP $code${if (body.isNotBlank()) ": $body" else ""}"
        } catch (t: Throwable) {
            Log.w(TAG, "WHOOP revoke exception: ${t.message}", t)
            false to "Exception: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    suspend fun disconnectWithDebug(context: Context): Pair<Boolean, String> {
        val appCtx = context.applicationContext

        val (revoked, debug) = runCatching { revokeAccessWithDebug(appCtx) }
            .getOrElse { false to "Exception: ${it.javaClass.simpleName}: ${it.message ?: "unknown"}" }

        runCatching { WhoopTokenStore(appCtx).clear() }
        runCatching {
            appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().clear().apply()
        }

        return revoked to debug
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
