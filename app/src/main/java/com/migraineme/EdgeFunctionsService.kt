package com.migraineme

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
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
        @SerialName("expires_at") val expiresAtIso: String? = null
    )

    @Serializable
    private data class MetricSettingUpsertBody(
        @SerialName("user_id") val userId: String,
        val metric: String,
        val enabled: Boolean,
        @SerialName("preferred_source") val preferredSource: String? = null,
        @SerialName("updated_at") val updatedAtIso: String
    )

    @Serializable
    data class MetricSettingResponse(
        @SerialName("user_id") val userId: String,
        val metric: String,
        val enabled: Boolean,
        @SerialName("preferred_source") val preferredSource: String? = null,
        @SerialName("updated_at") val updatedAt: String
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

    suspend fun enqueueLoginBackfill(context: Context): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false

        val client = buildClient()
        return try {
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/enqueue-login-backfill"

            val res = client.post(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                contentType(ContentType.Application.Json)
            }

            val ok = res.status.value in 200..299
            if (!ok) {
                Log.e("EdgeFunctionsService", "enqueueLoginBackfill failed: ${res.status.value}")
            }
            ok
        } catch (t: Throwable) {
            Log.e("EdgeFunctionsService", "enqueueLoginBackfill exception", t)
            false
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

        val client = buildClient()
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

    suspend fun upsertMetricSetting(
        context: Context,
        metric: String,
        enabled: Boolean,
        preferredSource: String? = null
    ): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false
        val userId = SessionStore.readUserId(appCtx) ?: return false

        val body = MetricSettingUpsertBody(
            userId = userId,
            metric = metric,
            enabled = enabled,
            preferredSource = preferredSource,
            updatedAtIso = Instant.now().toString()
        )

        val client = buildClient()
        return try {
            val url =
                "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/metric_settings?on_conflict=user_id,metric"

            val res = client.post(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                header("Prefer", "resolution=merge-duplicates")
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            val ok = res.status.value in 200..299
            if (!ok) {
                Log.e(
                    "EdgeFunctionsService",
                    "upsertMetricSetting failed: ${res.status} - ${res.bodyAsText()}"
                )
            }
            ok
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "upsertMetricSetting exception", e)
            false
        } finally {
            client.close()
        }
    }

    suspend fun getMetricSettings(context: Context): List<MetricSettingResponse> {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return emptyList()
        val userId = SessionStore.readUserId(appCtx) ?: return emptyList()

        val client = buildClient()
        return try {
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/metric_settings?user_id=eq.$userId&select=*"

            val res = client.get(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
            }

            if (res.status.value in 200..299) {
                res.body<List<MetricSettingResponse>>()
            } else {
                Log.e("EdgeFunctionsService", "getMetricSettings failed: ${res.status}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "getMetricSettings error: ${e.message}", e)
            emptyList()
        } finally {
            client.close()
        }
    }
}