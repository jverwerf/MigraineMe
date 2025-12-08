package com.migraineme

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.FutureTask

/**
 * WHOOP OAuth2 (PKCE) client for a CONFIDENTIAL app.
 * Uses client_secret_post (client_id and client_secret in the POST body).
 * No Supabase changes.
 */
class WhoopAuthService(
    private val clientId: String = "354e4d44-3780-4e99-a655-a306776879ee",
    private val clientSecret: String = "ce7314f4cdfab97a16467747a174a0ba8f1a8c561bc1dcc149674171ccd85d00",
    private val redirectUri: String = "migraineme://whoop/callback",
    private val scopes: String = "read:recovery read:sleep read:workout read:cycles read:body_measurement"
) {
    private val authBase = "https://api.prod.whoop.com/oauth/oauth2/auth"
    private val tokenUrl = "https://api.prod.whoop.com/oauth/oauth2/token"
    private val prefsName = "whoop_oauth"
    private val logTag = "WHOOP"

    /** Step 1: start OAuth with PKCE */
    fun startAuth(activity: Activity) {
        val prefs = activity.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val state = randomUrlSafe(16)
        val (verifier, challenge) = pkcePair()
        prefs.edit()
            .putString("pkce_verifier", verifier)
            .putString("oauth_state", state)
            .remove("token_error")
            .apply()

        val uri = Uri.parse(authBase).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", scopes)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    /**
     * Exchange code for tokens using PKCE.
     * On failure, writes "token_error" to whoop_oauth prefs.
     */
    fun completeAuth(context: Context): Boolean {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        fun fail(msg: String): Boolean {
            Log.e(logTag, "completeAuth failed: $msg")
            prefs.edit().putString("token_error", msg).apply()
            return false
        }

        val last = prefs.getString("last_uri", null) ?: return fail("missing last_uri")
        val dataUri = Uri.parse(last)
        val code = dataUri.getQueryParameter("code") ?: return fail("missing code")
        val returnedState = dataUri.getQueryParameter("state")
        val savedState = prefs.getString("oauth_state", null)
        if (savedState != null && savedState != returnedState) return fail("state mismatch")

        val verifier = prefs.getString("pkce_verifier", null) ?: return fail("missing pkce_verifier")

        val body = mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "code_verifier" to verifier,
            "client_id" to clientId,
            "client_secret" to clientSecret
        ).formUrlEncode()

        // Run HTTP off the main thread
        val resp = bg { httpPost(tokenUrl, "application/x-www-form-urlencoded", body) }
        if (resp.code !in 200..299) {
            val serverMsg = resp.body ?: "no body"
            Log.e(logTag, "token endpoint error ${resp.code}: $serverMsg")
            val err = try {
                val o = JSONObject(serverMsg)
                listOfNotNull(
                    o.optString("error", null),
                    o.optString("error_description", null)
                ).joinToString(": ").ifBlank { serverMsg }
            } catch (_: Throwable) { serverMsg }
            return fail(err)
        }

        val tok = WhoopToken.parse(resp.body ?: "") ?: return fail("parse token json failed")
        WhoopTokenStore(context).save(tok)

        prefs.edit()
            .remove("last_uri")
            .remove("code")
            .remove("state")
            .remove("error")
            .remove("oauth_state")
            .remove("pkce_verifier")
            .remove("token_error")
            .apply()

        return true
    }

    /** Refresh tokens if needed. Returns updated token or null on failure. */
    fun refresh(context: Context): WhoopToken? {
        val store = WhoopTokenStore(context)
        val current = store.load() ?: return null
        if (!current.isExpiredSoon()) return current

        val body = mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to current.refreshToken,
            "client_id" to clientId,
            "client_secret" to clientSecret
        ).formUrlEncode()

        // Run HTTP off the main thread
        val resp = bg { httpPost(tokenUrl, "application/x-www-form-urlencoded", body) }
        if (resp.code !in 200..299) {
            Log.e(logTag, "refresh error ${resp.code}: ${resp.body}")
            return null
        }
        val tok = WhoopToken.parse(resp.body ?: return null) ?: return null
        store.save(tok)
        return tok
    }

    // --- helpers ---

    /** Run a block on a background thread and wait for the result. */
    private fun <T> bg(block: () -> T): T {
        val task = FutureTask(block)
        Thread(task, "whoop-http").start()
        return task.get()
    }

    private fun pkcePair(): Pair<String, String> {
        val verifier = randomUrlSafe(32)
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val challenge = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        return verifier to challenge
    }

    private fun randomUrlSafe(n: Int): String {
        val buf = ByteArray(n)
        SecureRandom().nextBytes(buf)
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun Map<String, String>.formUrlEncode(): String =
        entries.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" }

    private data class HttpResp(val code: Int, val body: String?)

    private fun httpPost(url: String, contentType: String, body: String): HttpResp {
        val u = java.net.URL(url)
        val conn = (u.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", contentType)
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = try {
            if (code in 200..299) conn.inputStream else conn.errorStream
        } catch (_: Throwable) {
            null
        }
        val text = stream?.bufferedReader()?.use { it.readText() }
        conn.disconnect()
        return HttpResp(code, text)
    }
}

/** Token model parsed from WHOOP token response JSON */
data class WhoopToken(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresAtMillis: Long
) {
    fun isExpiredSoon(skewMillis: Long = 5 * 60 * 1000): Boolean =
        System.currentTimeMillis() >= (expiresAtMillis - skewMillis)

    companion object {
        fun parse(json: String): WhoopToken? {
            return try {
                val obj = org.json.JSONObject(json)
                val access = obj.getString("access_token")
                val refresh = obj.optString("refresh_token", "")
                val type = obj.optString("token_type", "Bearer")
                val expiresIn = obj.optLong("expires_in", 3600L)
                val expiresAt = System.currentTimeMillis() + expiresIn * 1000L
                WhoopToken(access, refresh, type, expiresAt)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
