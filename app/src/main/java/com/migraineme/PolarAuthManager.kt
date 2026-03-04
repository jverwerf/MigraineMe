package com.migraineme

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Manages Polar AccessLink OAuth2 flow.
 *
 * Flow:
 * 1. launchAuth() opens Polar Flow in browser for user authorization
 * 2. User grants access → redirected back with authorization code
 * 3. exchangeCodeForToken() exchanges code for access token
 *    Token endpoint: POST https://polarremote.com/v2/oauth2/token
 *    Auth: Basic base64(client_id:client_secret)
 * 4. Token is sent to upsert-polar-token edge function
 *
 * Key Polar differences:
 * - Token endpoint is polarremote.com (not polaraccesslink.com)
 * - Uses Basic auth header (not body params)
 * - Access tokens don't expire unless revoked — no refresh flow needed
 * - Response includes x_user_id (Polar ecosystem user ID)
 * - Must register user via POST /v3/users before accessing data (done server-side)
 */
object PolarAuthManager {

    private const val TAG = "PolarAuthManager"

    private const val AUTH_URL = "https://flow.polar.com/oauth2/authorization"
    private const val TOKEN_URL = "https://polarremote.com/v2/oauth2/token"
    private const val REDIRECT_URI = "migraineme://polar/callback"

    // These must match what's configured at https://admin.polaraccesslink.com
    private val CLIENT_ID: String get() = BuildConfig.POLAR_CLIENT_ID
    private val CLIENT_SECRET: String get() = BuildConfig.POLAR_CLIENT_SECRET

    /**
     * Launch Polar OAuth authorization in browser.
     */
    fun launchAuth(context: Context) {
        val uri = Uri.parse(AUTH_URL)
            .buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", "accesslink.read_all")
            .build()

        Log.d(TAG, "Launching Polar auth: $uri")
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    /**
     * Exchange authorization code for access token.
     * Returns JSONObject with access_token, token_type, expires_in, x_user_id.
     */
    suspend fun exchangeCodeForToken(code: String): JSONObject = withContext(Dispatchers.IO) {
        val client = HttpClient(io.ktor.client.engine.android.Android)
        try {
            // Basic auth: base64(client_id:client_secret)
            val credentials = "$CLIENT_ID:$CLIENT_SECRET"
            val basicAuth = Base64.encodeToString(credentials.toByteArray(), Base64.NO_WRAP)

            val response = client.submitForm(
                url = TOKEN_URL,
                formParameters = parameters {
                    append("grant_type", "authorization_code")
                    append("code", code)
                    append("redirect_uri", REDIRECT_URI)
                }
            ) {
                header(HttpHeaders.Authorization, "Basic $basicAuth")
                header(HttpHeaders.Accept, "application/json;charset=UTF-8")
            }

            val bodyText = response.bodyAsText()
            Log.d(TAG, "Token response: ${response.status} body=${bodyText.take(200)}")

            if (!response.status.isSuccess()) {
                throw Exception("Token exchange failed: ${response.status} $bodyText")
            }

            JSONObject(bodyText)
        } finally {
            client.close()
        }
    }

    /**
     * Send token to our edge function for storage and metric setup.
     */
    suspend fun sendTokenToBackend(context: Context, tokenResponse: JSONObject) = withContext(Dispatchers.IO) {
        val accessToken = tokenResponse.optString("access_token", "")
        val xUserId = tokenResponse.opt("x_user_id")  // Can be Long or String
        val tokenType = tokenResponse.optString("token_type", "bearer")
        val expiresIn = tokenResponse.optLong("expires_in", 0)

        if (accessToken.isBlank()) {
            throw Exception("No access_token in Polar token response")
        }

        val appCtx = context.applicationContext
        val supabaseAccessToken = SessionStore.getValidAccessToken(appCtx)
            ?: throw IllegalStateException("No Supabase session")

        val payload = JSONObject().apply {
            put("access_token", accessToken)
            put("token_type", tokenType)
            put("expires_in", expiresIn)
            if (xUserId != null) put("x_user_id", xUserId)
        }

        val client = HttpClient(io.ktor.client.engine.android.Android)
        try {
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/upsert-polar-token"
            val response = client.post(url) {
                header(HttpHeaders.Authorization, "Bearer $supabaseAccessToken")
                header(HttpHeaders.ContentType, "application/json")
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                setBody(payload.toString())
            }

            val bodyText = response.bodyAsText()
            Log.d(TAG, "Backend response: ${response.status} $bodyText")

            if (!response.status.isSuccess()) {
                throw Exception("Backend upsert failed: ${response.status} $bodyText")
            }

            JSONObject(bodyText)
        } finally {
            client.close()
        }
    }

    /**
     * Handle the OAuth redirect callback.
     * Call this from Activity.onNewIntent() when receiving migraineme://polar/callback?code=...
     */
    suspend fun handleCallback(context: Context, uri: Uri): Result<JSONObject> {
        return try {
            val code = uri.getQueryParameter("code")
                ?: return Result.failure(Exception("No authorization code in callback"))

            val error = uri.getQueryParameter("error")
            if (error != null) {
                return Result.failure(Exception("Polar auth error: $error"))
            }

            Log.d(TAG, "Got auth code, exchanging for token...")
            val tokenResponse = exchangeCodeForToken(code)

            Log.d(TAG, "Got token, sending to backend...")
            val backendResponse = sendTokenToBackend(context, tokenResponse)

            Result.success(backendResponse)
        } catch (e: Exception) {
            Log.e(TAG, "handleCallback failed", e)
            Result.failure(e)
        }
    }

    /**
     * Check if Polar is connected (token exists in local store).
     */
    fun isConnected(context: Context): Boolean {
        return PolarTokenStore(context).load() != null
    }
}
