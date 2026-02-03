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

        private const val CLIENT_ID = "354e4d44-3780-4e99-a655-a306776879ee"
        private const val CLIENT_SECRET =
            "ce7314f4cdfab97a16467747a174a0ba8f1a8c561bc1dcc149674171ccd85d00"

        private const val REDIRECT_URI = "whoop://migraineme/callback"

        private const val AUTH_URL = "https://api.prod.whoop.com/oauth/oauth2/auth"
        private const val TOKEN_URL = "https://api.prod.whoop.com/oauth/oauth2/token"
        private const val REVOKE_URL = "https://api.prod.whoop.com/developer/v2/user/access"

        private const val PREFS = "whoop_oauth"
        private const val KEY_STATE = "state"
        private const val KEY_VERIFIER = "code_verifier"
        private const val KEY_LAST_URI = "last_uri"
        private const val KEY_TOKEN_ERROR = "token_error"

        private const val EXPIRY_SKEW_MS = 60_000L

        // WHOOP returns a refresh_token only if the auth request includes the "offline" scope.
        private const val SCOPE =
            "offline read:recovery read:sleep read:workout read:cycles read:body_measurement"
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
            // Force a fresh auth + consent; ignore any existing session
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

            // Defensive: never overwrite a valid refresh token with an empty one.
            // If WHOOP didn't return a refresh_token, keep the existing one (if any) so refresh/upload still works.
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
                "scope" to "offline"
            )
        )

        return if (res.isSuccess) {
            val refreshed = res.getOrThrow()
            val merged = if (refreshed.refreshToken.isBlank()) {
                refreshed.copy(refreshToken = current.refreshToken)
            } else {
                refreshed
            }
            store.save(merged)

            // Keep Supabase whoop_tokens in sync after refresh.
            WhoopTokenUploadWorker.enqueueNow(appCtx)

            true
        } else {
            Log.w(TAG, "refresh failed: ${res.exceptionOrNull()?.message}")
            false
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
