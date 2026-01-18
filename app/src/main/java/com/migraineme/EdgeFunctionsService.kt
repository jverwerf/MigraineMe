// FILE: app/src/main/java/com/migraineme/EdgeFunctionsService.kt
package com.migraineme

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

class EdgeFunctionsService {

    @Serializable
    private data class WhoopTokenUpsertBody(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("token_type") val tokenType: String,
        // timestamptz in Supabase. Send ISO8601 or null.
        @SerialName("expires_at") val expiresAtIso: String? = null
    )

    private fun buildClient(): HttpClient {
        return HttpClient(Android) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                        explicitNulls = false
                    }
                )
            }
        }
    }

    /**
     * Upserts WHOOP token into Supabase (public.whoop_tokens) via edge function.
     *
     * Returns true if request succeeded (2xx), false otherwise.
     */
    suspend fun upsertWhoopTokenToSupabase(context: Context, token: WhoopToken): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false

        val expiresIso = token.expiresAtMillis
            .takeIf { it > 0L }
            ?.let { Instant.ofEpochMilli(it).toString() }

        val body = WhoopTokenUpsertBody(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            tokenType = token.tokenType.ifBlank { "Bearer" },
            expiresAtIso = expiresIso
        )

        val client = buildClient()
        return try {
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/upsert-whoop-token"

            val res = client.post(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            val ok = res.status.value in 200..299
            if (!ok) {
                val bodyText = runCatching { res.bodyAsText() }.getOrDefault("")
                Log.e("EdgeFunctionsService", "upsertWhoopToken failed: ${res.status.value} $bodyText")
            }
            ok
        } catch (t: Throwable) {
            Log.e("EdgeFunctionsService", "upsertWhoopToken exception", t)
            false
        } finally {
            client.close()
        }
    }

    /**
     * Best-effort enqueue of login backfill. Does not throw.
     */
    suspend fun enqueueLoginBackfill(context: Context) {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return

        val client = HttpClient(Android)
        try {
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/enqueue-login-backfill"
            client.post(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header("Authorization", "Bearer $supaAccessToken")
                header("Content-Type", "application/json")
            }
        } catch (_: Throwable) {
            // best-effort
        } finally {
            client.close()
        }
    }

    /**
     * Guaranteed-mode enqueue (returns success/failure so WorkManager can retry).
     */
    suspend fun enqueueLoginBackfillGuaranteed(context: Context): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false

        val client = HttpClient(Android)
        return try {
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/enqueue-login-backfill"
            val res = client.post(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                header(HttpHeaders.ContentType, "application/json")
                setBody("{}")
            }

            val ok = res.status.value in 200..299
            if (!ok) {
                val bodyText = runCatching { res.bodyAsText() }.getOrDefault("")
                Log.w("EdgeFunctionsService", "enqueueLoginBackfill failed: ${res.status.value} $bodyText")
            }
            ok
        } catch (t: Throwable) {
            Log.w("EdgeFunctionsService", "enqueueLoginBackfill exception", t)
            false
        } finally {
            client.close()
        }
    }
}
