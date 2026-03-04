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

class PolarAuthService {

    companion object {
        private const val TAG = "POLAR"

        private val CLIENT_ID = BuildConfig.POLAR_CLIENT_ID
        private val CLIENT_SECRET = BuildConfig.POLAR_CLIENT_SECRET

        // Polar OAuth endpoints:
        //   flow.polar.com        → authorization code
        //   polarremote.com       → token exchange (Basic auth)
        private const val AUTH_URL = "https://flow.polar.com/oauth2/authorization"
        private const val TOKEN_URL = "https://polarremote.com/v2/oauth2/token"

        private const val REDIRECT_URI = "migraineme://polar/callback"

        private const val PREFS = "polar_oauth"
        private const val KEY_STATE = "state"
        private const val KEY_LAST_URI = "last_uri"
        private const val KEY_TOKEN_ERROR = "token_error"
    }

    fun startAuth(activity: Activity) {
        val uri = buildAuthUri(activity.applicationContext)
        Log.d(TAG, "Opening Polar auth URL: $uri")
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

        return Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", "accesslink.read_all")
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
            prefs.edit().putString(KEY_TOKEN_ERROR, "Missing OAuth state. Reconnect Polar.").apply()
            return false
        }
        if (returnedState != expectedState) {
            prefs.edit().putString(KEY_TOKEN_ERROR, "State mismatch. Reconnect Polar.").apply()
            return false
        }

        // Token exchange — Polar uses Basic auth (client_id:client_secret)
        // Client-side exchange since tokens don't expire (no server-side refresh needed)
        val res = exchangeCode(code)

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

            val store = PolarTokenStore(appCtx)
            store.save(tok)

            prefs.edit()
                .remove(KEY_STATE)
                .remove(KEY_LAST_URI)
                .putString(KEY_TOKEN_ERROR, "")
                .apply()

            true
        } else {
            prefs.edit().putString(
                KEY_TOKEN_ERROR,
                res.exceptionOrNull()?.message ?: "Polar authentication failed"
            ).apply()
            false
        }
    }

    /**
     * Exchange authorization code for access token.
     * Polar uses Basic auth: base64(client_id:client_secret)
     * Response includes: access_token, token_type, x_user_id
     * Polar tokens don't expire — no refresh token.
     */
    private fun exchangeCode(code: String): Result<PolarToken> {
        val credentials = "$CLIENT_ID:$CLIENT_SECRET"
        val basicAuth = Base64.encodeToString(credentials.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val body = "grant_type=authorization_code&code=$code&redirect_uri=${Uri.encode(REDIRECT_URI)}"

        val conn = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json;charset=UTF-8")
            setRequestProperty("Authorization", "Basic $basicAuth")
        }

        return try {
            conn.outputStream.use { os ->
                os.write(body.toByteArray(Charsets.UTF_8))
            }

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { s ->
                BufferedReader(InputStreamReader(s)).readText()
            }.orEmpty()

            if (responseCode !in 200..299) {
                Log.e(TAG, "Token exchange error $responseCode: $text")
                Result.failure(IllegalStateException("Polar token exchange failed: $responseCode"))
            } else {
                val jo = JSONObject(text)
                Result.success(PolarToken.fromTokenResponse(jo))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Token exchange exception", t)
            Result.failure(t)
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    suspend fun disconnectWithDebug(context: Context): Pair<Boolean, String> {
        val appCtx = context.applicationContext

        // Clear local tokens
        runCatching { PolarTokenStore(appCtx).clear() }
        runCatching {
            appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().clear().apply()
        }

        // Deregister user from Polar AccessLink
        // DELETE /v3/users/{user-id} would revoke access, but requires the access token
        // For now, just clear locally. Users can revoke in Polar Flow app.

        return true to "Polar tokens cleared locally. Revoke access in Polar Flow if needed."
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

data class PolarToken(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val polarUserId: String? = null
) {
    companion object {
        fun fromTokenResponse(jo: JSONObject): PolarToken {
            val access = jo.optString("access_token", "")
            val type = jo.optString("token_type", "Bearer")
            // x_user_id can be a Long or String
            val xUserId = when {
                jo.has("x_user_id") -> jo.opt("x_user_id")?.toString()
                else -> null
            }

            return PolarToken(
                accessToken = access,
                tokenType = type,
                polarUserId = xUserId
            )
        }
    }
}
