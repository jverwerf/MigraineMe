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
import java.security.SecureRandom

class OuraAuthService {

    companion object {
        private const val TAG = "OURA"

        private val CLIENT_ID = BuildConfig.OURA_CLIENT_ID

        // Oura uses separate domains for auth code vs token exchange:
        //   cloud.ouraring.com  → authorization code
        //   api.ouraring.com    → token exchange + API requests
        private const val AUTH_URL = "https://cloud.ouraring.com/oauth/authorize"
        private const val REVOKE_URL = "https://api.ouraring.com/oauth/revoke"

        private const val REDIRECT_URI = "migraineme://oura/callback"

        private const val PREFS = "oura_oauth"
        private const val KEY_STATE = "state"
        private const val KEY_LAST_URI = "last_uri"
        private const val KEY_TOKEN_ERROR = "token_error"

        // Oura V2 scopes
        private const val SCOPE = "personal daily heartrate workout session spo2"
    }

    fun startAuth(activity: Activity) {
        val uri = buildAuthUri(activity.applicationContext)
        Log.d(TAG, "Opening Oura auth URL: $uri")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    fun buildAuthUri(context: Context): Uri {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val state = randomUrlSafe(24)

        prefs.edit()
            .putString(KEY_STATE, state)
            .putString(KEY_TOKEN_ERROR, "")
            .apply()

        // Oura uses standard OAuth2 Authorization Code (no PKCE required)
        return Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("state", state)
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

        if (expectedState.isBlank()) {
            prefs.edit().putString(KEY_TOKEN_ERROR, "Missing OAuth state. Reconnect Oura.").apply()
            return false
        }
        if (returnedState != expectedState) {
            prefs.edit().putString(KEY_TOKEN_ERROR, "State mismatch. Reconnect Oura.").apply()
            return false
        }

        // Token exchange via server-side Edge Function
        val res = exchangeCodeViaServer(appCtx, code)

        return if (res.isSuccess) {
            val tok = res.getOrThrow()

            // Ensure we have a userId
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

            val store = OuraTokenStore(appCtx)
            val existing = store.load()

            val merged = when {
                tok.refreshToken.isNotBlank() -> tok
                existing?.refreshToken?.isNotBlank() == true -> tok.copy(refreshToken = existing.refreshToken)
                else -> {
                    prefs.edit().putString(
                        KEY_TOKEN_ERROR,
                        "Oura did not return refresh_token. Reconnect and ensure offline scope is granted."
                    ).apply()
                    return false
                }
            }

            store.save(merged)

            prefs.edit()
                .remove(KEY_STATE)
                .remove(KEY_LAST_URI)
                .putString(KEY_TOKEN_ERROR, "")
                .apply()

            OuraTokenUploadWorker.enqueueNow(appCtx)
            true
        } else {
            prefs.edit().putString(KEY_TOKEN_ERROR, res.exceptionOrNull()?.message ?: "Oura authentication failed").apply()
            false
        }
    }

    /**
     * Exchange authorization code for tokens via server-side Edge Function.
     * The Oura client_secret lives in Supabase secrets — never in the APK.
     */
    private fun exchangeCodeViaServer(context: Context, code: String): Result<OuraToken> {
        val supaAccessToken = SessionStore.readAccessToken(context) ?: return Result.failure(
            IllegalStateException("No Supabase access token")
        )

        val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/oura-token-exchange"

        val jsonBody = JSONObject().apply {
            put("code", code)
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
                Log.e(TAG, "oura-token-exchange error $responseCode: $text")
                val msg = runCatching {
                    val jo = JSONObject(text)
                    jo.optString("error", text)
                }.getOrDefault(text)
                Result.failure(IllegalStateException(msg))
            } else {
                val jo = JSONObject(text)
                Result.success(OuraToken.fromTokenResponse(jo))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "oura-token-exchange exception", t)
            Result.failure(t)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * Server handles all Oura token refreshes.
     * Just return whatever we have locally.
     */
    suspend fun refreshIfNeeded(context: Context): OuraToken? {
        return OuraTokenStore(context.applicationContext).load()
    }

    suspend fun disconnectWithDebug(context: Context): Pair<Boolean, String> {
        val appCtx = context.applicationContext

        // Oura doesn't have a documented revoke endpoint for third-party apps,
        // so we just clear the local + server tokens.
        // Users can revoke access in the Oura app under Settings > Connected Apps.
        runCatching { OuraTokenStore(appCtx).clear() }
        runCatching {
            appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().clear().apply()
        }

        return true to "Oura tokens cleared locally. Revoke access in the Oura app if needed."
    }

    fun getTokenError(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_TOKEN_ERROR, null)?.takeIf { it.isNotBlank() }
    }

    private fun randomUrlSafe(lenBytes: Int): String {
        val b = ByteArray(kotlin.math.max(16, lenBytes))
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            .take(lenBytes * 2)
    }
}

data class OuraToken(
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
        fun fromTokenResponse(jo: JSONObject): OuraToken {
            val access = jo.optString("access_token", "")
            val refresh = jo.optString("refresh_token", "")
            val type = jo.optString("token_type", "Bearer")

            val nowMs = System.currentTimeMillis()

            val expiresInSecRaw = jo.optLong("expires_in", 0L)
            val expiresInSec = if (expiresInSecRaw > 0L) expiresInSecRaw else 86400L // Oura tokens last ~24h
            val expiresAt = nowMs + expiresInSec * 1000L

            return OuraToken(
                accessToken = access,
                refreshToken = refresh,
                tokenType = type,
                expiresAtMillis = expiresAt
            )
        }
    }
}
