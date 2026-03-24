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
import java.security.MessageDigest
import java.security.SecureRandom

class GarminAuthService {

    companion object {
        private const val TAG = "GARMIN"

        private val CLIENT_ID = BuildConfig.GARMIN_CLIENT_ID

        // Garmin OAuth2 PKCE endpoints
        private const val AUTH_URL = "https://connect.garmin.com/oauth2Confirm"

        private const val REDIRECT_URI = "https://migraineme.app/garmin/callback"

        private const val PREFS = "garmin_oauth"
        private const val KEY_STATE = "state"
        private const val KEY_CODE_VERIFIER = "code_verifier"
        private const val KEY_LAST_URI = "last_uri"
        private const val KEY_TOKEN_ERROR = "token_error"
    }

    fun startAuth(activity: Activity) {
        val uri = buildAuthUri(activity.applicationContext)
        Log.d(TAG, "Opening Garmin auth URL: $uri")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    fun buildAuthUri(context: Context): Uri {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val state = randomUrlSafe(24)
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)

        prefs.edit()
            .putString(KEY_STATE, state)
            .putString(KEY_CODE_VERIFIER, codeVerifier)
            .putString(KEY_TOKEN_ERROR, "")
            .apply()

        return Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", codeChallenge)
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

        if (expectedState.isBlank()) {
            prefs.edit().putString(KEY_TOKEN_ERROR, "Missing OAuth state. Reconnect Garmin.").apply()
            return false
        }
        if (returnedState != expectedState) {
            prefs.edit().putString(KEY_TOKEN_ERROR, "State mismatch. Reconnect Garmin.").apply()
            return false
        }

        val codeVerifier = prefs.getString(KEY_CODE_VERIFIER, null)
        if (codeVerifier.isNullOrBlank()) {
            prefs.edit().putString(KEY_TOKEN_ERROR, "Missing code_verifier. Reconnect Garmin.").apply()
            return false
        }

        // Exchange code via server-side Edge Function
        // (client_secret must NOT be in the APK per Garmin's rules)
        val res = exchangeCodeViaServer(appCtx, code, codeVerifier)

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

            val store = GarminTokenStore(appCtx)
            store.save(tok)

            prefs.edit()
                .remove(KEY_STATE)
                .remove(KEY_CODE_VERIFIER)
                .remove(KEY_LAST_URI)
                .putString(KEY_TOKEN_ERROR, "")
                .apply()

            true
        } else {
            prefs.edit().putString(
                KEY_TOKEN_ERROR,
                res.exceptionOrNull()?.message ?: "Garmin authentication failed"
            ).apply()
            false
        }
    }

    /**
     * Exchange authorization code + code_verifier for tokens via server-side Edge Function.
     * The Garmin client_secret lives in Supabase secrets — never in the APK.
     */
    private fun exchangeCodeViaServer(
        context: Context,
        code: String,
        codeVerifier: String
    ): Result<GarminToken> {
        val supaAccessToken = SessionStore.readAccessToken(context)
            ?: return Result.failure(IllegalStateException("No Supabase access token"))

        val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/garmin-token-exchange"

        val jsonBody = JSONObject().apply {
            put("code", code)
            put("code_verifier", codeVerifier)
            put("redirect_uri", REDIRECT_URI)
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
                Log.e(TAG, "garmin-token-exchange error $responseCode: $text")
                val msg = runCatching {
                    val jo = JSONObject(text)
                    jo.optString("error", text)
                }.getOrDefault(text)
                Result.failure(IllegalStateException(msg))
            } else {
                val jo = JSONObject(text)
                Result.success(GarminToken.fromExchangeResponse(jo))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "garmin-token-exchange exception", t)
            Result.failure(t)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    suspend fun disconnectWithDebug(context: Context): Pair<Boolean, String> {
        val appCtx = context.applicationContext

        // Clear local tokens and cached device name
        runCatching { GarminTokenStore(appCtx).clear() }
        GarminDeviceNameProvider.clear()
        runCatching {
            appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().clear().apply()
        }

        // Server-side: could call DELETE /wellness-api/rest/user/registration
        // but that requires the access token. For now, clear locally.
        // Users can disconnect via Garmin Connect > Third Party Access.

        return true to "Garmin tokens cleared locally. Revoke access in Garmin Connect if needed."
    }

    fun getTokenError(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_TOKEN_ERROR, null)?.takeIf { it.isNotBlank() }
    }

    // ── PKCE helpers ──

    /**
     * Generate a code verifier: 43-128 character random string.
     * Characters: A-Z, a-z, 0-9, -, ., _, ~
     */
    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(48)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            .take(64) // 64 chars, well within 43-128 range
    }

    /**
     * Generate code challenge: base64url(sha256(code_verifier))
     */
    private fun generateCodeChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun randomUrlSafe(lenBytes: Int): String {
        val b = ByteArray(kotlin.math.max(16, lenBytes))
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            .take(lenBytes * 2)
    }
}

data class GarminToken(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "bearer",
    val expiresAtMillis: Long = 0L,
    val garminUserId: String? = null
) {
    fun isExpiredSoon(skewMs: Long = 300_000L): Boolean { // 5 min buffer
        if (expiresAtMillis <= 0L) return true
        return System.currentTimeMillis() + skewMs >= expiresAtMillis
    }

    companion object {
        fun fromExchangeResponse(jo: JSONObject): GarminToken {
            val access = jo.optString("access_token", "")
            val refresh = jo.optString("refresh_token", "")
            val expiresIn = jo.optLong("expires_in", 86400L)
            val garminUserId = jo.optString("garmin_user_id", null)

            val expiresAt = System.currentTimeMillis() + expiresIn * 1000L

            return GarminToken(
                accessToken = access,
                refreshToken = refresh,
                tokenType = "bearer",
                expiresAtMillis = expiresAt,
                garminUserId = garminUserId
            )
        }
    }
}
