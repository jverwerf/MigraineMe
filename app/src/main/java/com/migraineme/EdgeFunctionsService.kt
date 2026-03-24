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
    private data class OuraTokenUpsertBody(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("token_type") val tokenType: String,
        @SerialName("expires_at") val expiresAtIso: String? = null
    )

    @Serializable
    private data class PolarTokenUpsertBody(
        @SerialName("access_token") val accessToken: String,
        @SerialName("token_type") val tokenType: String = "Bearer",
        @SerialName("x_user_id") val xUserId: String? = null
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
        @SerialName("allowed_sources") val allowedSources: List<String>? = null,
        @SerialName("updated_at") val updatedAt: String
    )

    @Serializable
    private data class TriggerSettingUpsertBody(
        @SerialName("user_id") val userId: String,
        @SerialName("trigger_type") val triggerType: String,
        val enabled: Boolean,
        val threshold: Double? = null,
        @SerialName("updated_at") val updatedAtIso: String
    )

    @Serializable
    data class TriggerSettingResponse(
        @SerialName("user_id") val userId: String,
        @SerialName("trigger_type") val triggerType: String,
        val enabled: Boolean,
        val threshold: Double? = null,
        @SerialName("updated_at") val updatedAt: String
    )

    @Serializable
    data class TriggerDefinitionResponse(
        @SerialName("trigger_type") val triggerType: String,
        val category: String,
        val label: String,
        val description: String,
        @SerialName("metric_table") val metricTable: String,
        @SerialName("metric_column") val metricColumn: String,
        val direction: String,
        @SerialName("default_threshold") val defaultThreshold: Double? = null,
        val unit: String? = null,
        @SerialName("baseline_days") val baselineDays: Int = 14,
        @SerialName("enabled_by_default") val enabledByDefault: Boolean = false
    )

    @Serializable
    private data class ProdromeSettingUpsertBody(
        @SerialName("user_id") val userId: String,
        @SerialName("prodrome_type") val prodromeType: String,
        val enabled: Boolean,
        val threshold: Double? = null,
        @SerialName("updated_at") val updatedAtIso: String
    )

    @Serializable
    data class ProdromeSettingResponse(
        @SerialName("user_id") val userId: String,
        @SerialName("prodrome_type") val prodromeType: String,
        val enabled: Boolean,
        val threshold: Double? = null,
        @SerialName("updated_at") val updatedAt: String
    )

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
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

    suspend fun upsertOuraTokenToSupabase(context: Context, token: OuraToken): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false

        val expiresIso = token.expiresAtMillis
            .takeIf { it > 0L }
            ?.let { Instant.ofEpochMilli(it).toString() }

        val body = OuraTokenUpsertBody(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            tokenType = token.tokenType.ifBlank { "Bearer" },
            expiresAtIso = expiresIso
        )

        val client = buildClient()
        return try {
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/upsert-oura-token"

            val res = client.post(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            val ok = res.status.value in 200..299
            if (!ok) {
                val bodyText = runCatching { res.bodyAsText() }.getOrDefault("")
                Log.e("EdgeFunctionsService", "upsertOuraToken failed: ${res.status.value} $bodyText")
            }
            ok
        } catch (t: Throwable) {
            Log.e("EdgeFunctionsService", "upsertOuraToken exception", t)
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
     * Invoke backfill-polar edge function (awaited).
     * Ensures Polar sync_jobs exist and invokes sync-worker-polar to pull data.
     * Call BEFORE enqueueLoginBackfill so data is available for triggers/risk.
     */
    suspend fun backfillPolar(context: Context): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false
        val userId = SessionStore.readUserId(appCtx) ?: return false

        val client = buildClient()
        return try {
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/backfill-polar"

            val res = client.post(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                contentType(ContentType.Application.Json)
                setBody("""{"user_id":"$userId"}""")
            }

            val ok = res.status.value in 200..299
            if (!ok) {
                val bodyText = runCatching { res.bodyAsText() }.getOrDefault("")
                Log.e("EdgeFunctionsService", "backfillPolar failed: ${res.status.value} $bodyText")
            }
            ok
        } catch (t: Throwable) {
            Log.e("EdgeFunctionsService", "backfillPolar exception", t)
            false
        } finally {
            client.close()
        }
    }

    /**
     * Invoke backfill-oura edge function (awaited).
     * Ensures Oura sync_jobs exist and invokes sync-worker-oura to pull data.
     * Call BEFORE enqueueLoginBackfill so data is available for triggers/risk.
     */
    suspend fun backfillOura(context: Context): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false
        val userId = SessionStore.readUserId(appCtx) ?: return false

        val client = buildClient()
        return try {
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/backfill-oura"

            val res = client.post(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                contentType(ContentType.Application.Json)
                setBody("""{"user_id":"$userId"}""")
            }

            val ok = res.status.value in 200..299
            if (!ok) {
                val bodyText = runCatching { res.bodyAsText() }.getOrDefault("")
                Log.e("EdgeFunctionsService", "backfillOura failed: ${res.status.value} $bodyText")
            }
            ok
        } catch (t: Throwable) {
            Log.e("EdgeFunctionsService", "backfillOura exception", t)
            false
        } finally {
            client.close()
        }
    }

    /**
     * Invoke backfill-garmin edge function (awaited).
     * Ensures Garmin sync_jobs exist and invokes sync-worker-garmin to pull data.
     * Supplements the push-based garmin-backfill with immediate pull-based sync.
     * Call BEFORE enqueueLoginBackfill so data is available for triggers/risk.
     */
    suspend fun backfillGarmin(context: Context): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false
        val userId = SessionStore.readUserId(appCtx) ?: return false

        val client = buildClient()
        return try {
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/backfill-garmin"

            val res = client.post(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                contentType(ContentType.Application.Json)
                setBody("""{"user_id":"$userId"}""")
            }

            val ok = res.status.value in 200..299
            if (!ok) {
                val bodyText = runCatching { res.bodyAsText() }.getOrDefault("")
                Log.e("EdgeFunctionsService", "backfillGarmin failed: ${res.status.value} $bodyText")
            }
            ok
        } catch (t: Throwable) {
            Log.e("EdgeFunctionsService", "backfillGarmin exception", t)
            false
        } finally {
            client.close()
        }
    }

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
                Log.w("EdgeFunctionsService", "enqueue-login-backfill failed: ${res.status.value} $bodyText")
            }
            ok
        } catch (t: Throwable) {
            Log.w("EdgeFunctionsService", "enqueue-login-backfill exception", t)
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
            val url =
                "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/metric_settings?user_id=eq.$userId&select=*"

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

    suspend fun enableDefaultWhoopMetricSettings(context: Context): Boolean {
        val appCtx = context.applicationContext
        val whoopKey = "whoop"

        val metrics = listOf(
            "sleep_duration_daily",
            "sleep_score_daily",
            "sleep_efficiency_daily",
            "sleep_stages_daily",
            "sleep_disturbances_daily",
            "fell_asleep_time_daily",
            "woke_up_time_daily",
            "recovery_score_daily",
            "resting_hr_daily",
            "hrv_daily",
            "skin_temp_daily",
            "spo2_daily",
            "time_in_high_hr_zones_daily",
            "activity_hr_zones_sessions",
            "steps_daily",
            "stress_index_daily",
            "strain_daily"
        )

        var allOk = true
        for (metric in metrics) {
            val ok = runCatching {
                upsertMetricSetting(
                    context = appCtx,
                    metric = metric,
                    enabled = true,
                    preferredSource = whoopKey
                )
            }.getOrDefault(false)

            if (!ok) {
                allOk = false
                Log.w("EdgeFunctionsService", "Failed to enable WHOOP metric setting: $metric")
            }
        }
        return allOk
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Oura Metric Settings
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun enableDefaultOuraMetricSettings(context: Context): Boolean {
        val appCtx = context.applicationContext
        val ouraKey = "oura"

        val metrics = listOf(
            "sleep_duration_daily",
            "sleep_score_daily",
            "sleep_efficiency_daily",
            "sleep_stages_daily",
            "sleep_disturbances_daily",
            "fell_asleep_time_daily",
            "woke_up_time_daily",
            "recovery_score_daily",
            "resting_hr_daily",
            "hrv_daily",
            "skin_temp_daily",
            "spo2_daily",
            "steps_daily",
            "stress_index_daily"
        )

        var allOk = true
        for (metric in metrics) {
            val ok = runCatching {
                upsertMetricSetting(
                    context = appCtx,
                    metric = metric,
                    enabled = true,
                    preferredSource = ouraKey
                )
            }.getOrDefault(false)

            if (!ok) {
                allOk = false
                Log.w("EdgeFunctionsService", "Failed to enable Oura metric setting: $metric")
            }
        }
        return allOk
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Polar Token & Metric Settings
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun upsertPolarTokenToSupabase(context: Context, token: PolarToken): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false

        val body = PolarTokenUpsertBody(
            accessToken = token.accessToken,
            tokenType = token.tokenType.ifBlank { "Bearer" },
            xUserId = token.polarUserId
        )

        val client = buildClient()
        return try {
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/upsert-polar-token"

            val res = client.post(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            val ok = res.status.value in 200..299
            if (!ok) {
                val bodyText = runCatching { res.bodyAsText() }.getOrDefault("")
                Log.e("EdgeFunctionsService", "upsertPolarToken failed: ${res.status.value} $bodyText")
            }
            ok
        } catch (t: Throwable) {
            Log.e("EdgeFunctionsService", "upsertPolarToken exception", t)
            false
        } finally {
            client.close()
        }
    }

    suspend fun enableDefaultPolarMetricSettings(context: Context): Boolean {
        val appCtx = context.applicationContext
        val polarKey = "polar"

        // Polar provides 16 metrics (no stress — computed from HRV/RHR z-scores)
        val metrics = listOf(
            "sleep_duration_daily",
            "sleep_score_daily",
            "sleep_efficiency_daily",
            "sleep_stages_daily",
            "sleep_disturbances_daily",
            "fell_asleep_time_daily",
            "woke_up_time_daily",
            "recovery_score_daily",
            "resting_hr_daily",
            "hrv_daily",
            "respiratory_rate_daily",
            "steps_daily",
            "strain_daily",
            "time_in_high_hr_zones_daily",
            "skin_temp_daily",
            "spo2_daily"
        )

        var allOk = true
        for (metric in metrics) {
            val ok = runCatching {
                upsertMetricSetting(
                    context = appCtx,
                    metric = metric,
                    enabled = true,
                    preferredSource = polarKey
                )
            }.getOrDefault(false)

            if (!ok) {
                allOk = false
                Log.w("EdgeFunctionsService", "Failed to enable Polar metric setting: $metric")
            }
        }
        return allOk
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Garmin Token Exchange & Metric Settings
    // ─────────────────────────────────────────────────────────────────────────

    // NOTE: Garmin token exchange happens server-side (garmin-token-exchange Edge Function)
    // because Garmin requires client_secret which must NOT be embedded in mobile apps.
    // The Edge Function is called directly by GarminAuthService.exchangeCodeViaServer().

    suspend fun enableDefaultGarminMetricSettings(context: Context): Boolean {
        val appCtx = context.applicationContext
        val garminKey = "garmin"

        // Garmin provides 16 metrics (stress is DIRECT, not computed)
        val metrics = listOf(
            "sleep_duration_daily",
            "sleep_score_daily",
            "sleep_efficiency_daily",
            "sleep_stages_daily",
            "sleep_disturbances_daily",
            "fell_asleep_time_daily",
            "woke_up_time_daily",
            "recovery_score_daily",
            "resting_hr_daily",
            "hrv_daily",
            "respiratory_rate_daily",
            "spo2_daily",
            "steps_daily",
            "strain_daily",
            "stress_index_daily",
            "skin_temp_daily"
        )

        var allOk = true
        for (metric in metrics) {
            val ok = runCatching {
                upsertMetricSetting(
                    context = appCtx,
                    metric = metric,
                    enabled = true,
                    preferredSource = garminKey
                )
            }.getOrDefault(false)

            if (!ok) {
                allOk = false
                Log.w("EdgeFunctionsService", "Failed to enable Garmin metric setting: $metric")
            }
        }
        return allOk
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Health Connect Metric Settings
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun enableDefaultHealthConnectMetricSettings(context: Context): Boolean {
        val appCtx = context.applicationContext
        val hcKey = "health_connect"

        val metrics = listOf(
            "sleep_duration_daily",
            "sleep_stages_daily",
            "fell_asleep_time_daily",
            "woke_up_time_daily",
            "resting_hr_daily",
            "hrv_daily",
            "spo2_daily",
            "skin_temp_daily",
            "steps_daily",
            "time_in_high_hr_zones_daily",
            "weight_daily",
            "body_fat_daily",
            "hydration_daily",
            "blood_pressure_daily",
            "blood_glucose_daily",
            "respiratory_rate_daily",
            "stress_index_daily"
        )

        var allOk = true
        for (metric in metrics) {
            val ok = runCatching {
                upsertMetricSetting(
                    context = appCtx,
                    metric = metric,
                    enabled = true,
                    preferredSource = hcKey
                )
            }.getOrDefault(false)

            if (!ok) {
                allOk = false
                Log.w("EdgeFunctionsService", "Failed to enable Health Connect metric: $metric")
            }
        }
        return allOk
    }

    suspend fun disableHealthConnectMetricSettings(context: Context): Boolean {
        val appCtx = context.applicationContext

        val metrics = listOf(
            "sleep_duration_daily",
            "sleep_stages_daily",
            "fell_asleep_time_daily",
            "woke_up_time_daily",
            "resting_hr_daily",
            "hrv_daily",
            "spo2_daily",
            "skin_temp_daily",
            "steps_daily",
            "time_in_high_hr_zones_daily",
            "weight_daily",
            "body_fat_daily",
            "hydration_daily",
            "blood_pressure_daily",
            "blood_glucose_daily",
            "respiratory_rate_daily",
            "stress_index_daily"
        )

        var allOk = true
        for (metric in metrics) {
            val ok = runCatching {
                upsertMetricSetting(
                    context = appCtx,
                    metric = metric,
                    enabled = false,
                    preferredSource = null
                )
            }.getOrDefault(false)

            if (!ok) allOk = false
        }
        return allOk
    }

    // ─────────────────────────────────────────────────────────────────────────

    suspend fun hasAnyMetricData(
        context: Context,
        metric: String
    ): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false

        val client = buildClient()
        return try {
            val url =
                "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/has-metric-data"

            val res = client.post(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                contentType(ContentType.Application.Json)
                setBody(mapOf("metric" to metric))
            }

            if (res.status.value in 200..299) {
                val body = res.body<Map<String, Boolean>>()
                body["hasData"] == true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        } finally {
            client.close()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Trigger Settings
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getTriggerSettings(context: Context): List<TriggerSettingResponse> {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return emptyList()
        val userId = SessionStore.readUserId(appCtx) ?: return emptyList()

        val client = buildClient()
        return try {
            val url =
                "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/trigger_settings?user_id=eq.$userId&select=*"

            val res = client.get(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
            }

            if (res.status.value in 200..299) {
                res.body<List<TriggerSettingResponse>>()
            } else {
                Log.e("EdgeFunctionsService", "getTriggerSettings failed: ${res.status}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "getTriggerSettings error: ${e.message}", e)
            emptyList()
        } finally {
            client.close()
        }
    }

    suspend fun getTriggerDefinitions(context: Context): List<TriggerDefinitionResponse> {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return emptyList()

        val client = buildClient()
        return try {
            val url =
                "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/trigger_definitions?select=*&order=category,trigger_type"

            val res = client.get(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
            }

            if (res.status.value in 200..299) {
                res.body<List<TriggerDefinitionResponse>>()
            } else {
                Log.e("EdgeFunctionsService", "getTriggerDefinitions failed: ${res.status}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "getTriggerDefinitions error: ${e.message}", e)
            emptyList()
        } finally {
            client.close()
        }
    }

    suspend fun upsertTriggerSetting(
        context: Context,
        triggerType: String,
        enabled: Boolean,
        threshold: Double? = null
    ): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false
        val userId = SessionStore.readUserId(appCtx) ?: return false

        val body = TriggerSettingUpsertBody(
            userId = userId,
            triggerType = triggerType,
            enabled = enabled,
            threshold = threshold,
            updatedAtIso = Instant.now().toString()
        )

        val client = buildClient()
        return try {
            val url =
                "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/trigger_settings?on_conflict=user_id,trigger_type"

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
                    "upsertTriggerSetting failed: ${res.status} - ${res.bodyAsText()}"
                )
            }
            ok
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "upsertTriggerSetting exception", e)
            false
        } finally {
            client.close()
        }
    }

    suspend fun seedDefaultTriggerSettings(context: Context): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false
        val userId = SessionStore.readUserId(appCtx) ?: return false

        val defaultTriggers = listOf(
            "recovery_low" to true,
            "recovery_unusually_low" to true
        )

        val client = buildClient()
        var allOk = true

        try {
            for ((triggerType, enabled) in defaultTriggers) {
                val body = TriggerSettingUpsertBody(
                    userId = userId,
                    triggerType = triggerType,
                    enabled = enabled,
                    updatedAtIso = Instant.now().toString()
                )

                val url =
                    "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/trigger_settings?on_conflict=user_id,trigger_type"

                val res = client.post(url) {
                    header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                    header("Prefer", "resolution=ignore-duplicates")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

                val ok = res.status.value in 200..299
                if (!ok) {
                    allOk = false
                    Log.w("EdgeFunctionsService", "Failed to seed trigger setting: $triggerType")
                }
            }
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "seedDefaultTriggerSettings exception", e)
            allOk = false
        } finally {
            client.close()
        }

        return allOk
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prodrome Settings
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getProdromeSettings(context: Context): List<ProdromeSettingResponse> {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return emptyList()
        val userId = SessionStore.readUserId(appCtx) ?: return emptyList()

        val client = buildClient()
        return try {
            val url =
                "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/prodrome_settings?user_id=eq.$userId&select=*"

            val res = client.get(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
            }

            if (res.status.value in 200..299) {
                res.body<List<ProdromeSettingResponse>>()
            } else {
                Log.e("EdgeFunctionsService", "getProdromeSettings failed: ${res.status}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "getProdromeSettings error: ${e.message}", e)
            emptyList()
        } finally {
            client.close()
        }
    }

    suspend fun upsertProdromeSetting(
        context: Context,
        prodromeType: String,
        enabled: Boolean,
        threshold: Double? = null
    ): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false
        val userId = SessionStore.readUserId(appCtx) ?: return false

        val body = ProdromeSettingUpsertBody(
            userId = userId,
            prodromeType = prodromeType,
            enabled = enabled,
            threshold = threshold,
            updatedAtIso = Instant.now().toString()
        )

        val client = buildClient()
        return try {
            val url =
                "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/prodrome_settings?on_conflict=user_id,prodrome_type"

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
                    "upsertProdromeSetting failed: ${res.status} - ${res.bodyAsText()}"
                )
            }
            ok
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "upsertProdromeSetting exception", e)
            false
        } finally {
            client.close()
        }
    }

    suspend fun seedDefaultProdromeSettings(context: Context): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false
        val userId = SessionStore.readUserId(appCtx) ?: return false

        val defaultProdromes = listOf(
            "fatigue_yawning" to false,
            "neck_stiffness" to false,
            "mood_changes" to false
        )

        val client = buildClient()
        var allOk = true

        try {
            for ((prodromeType, enabled) in defaultProdromes) {
                val body = ProdromeSettingUpsertBody(
                    userId = userId,
                    prodromeType = prodromeType,
                    enabled = enabled,
                    updatedAtIso = Instant.now().toString()
                )

                val url =
                    "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/prodrome_settings?on_conflict=user_id,prodrome_type"

                val res = client.post(url) {
                    header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                    header("Prefer", "resolution=ignore-duplicates")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

                val ok = res.status.value in 200..299
                if (!ok) {
                    allOk = false
                    Log.w("EdgeFunctionsService", "Failed to seed prodrome setting: $prodromeType")
                }
            }
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "seedDefaultProdromeSettings exception", e)
            allOk = false
        } finally {
            client.close()
        }

        return allOk
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Metric Settings Seeding
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun seedDefaultMetricSettings(context: Context): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false
        val userId = SessionStore.readUserId(appCtx) ?: return false

        val metrics = listOf(
            "sleep_duration_daily",
            "sleep_score_daily",
            "sleep_efficiency_daily",
            "sleep_stages_daily",
            "sleep_disturbances_daily",
            "fell_asleep_time_daily",
            "woke_up_time_daily",
            "recovery_score_daily",
            "resting_hr_daily",
            "hrv_daily",
            "skin_temp_daily",
            "spo2_daily",
            "time_in_high_hr_zones_daily",
            "activity_hr_zones_sessions",
            "steps_daily",
            "strain_daily",
            "stress_index_daily",
            "screen_time_daily",
            "user_location_daily",
            "ambient_noise_samples",
            "ambient_noise_index_daily",
            "screen_time_late_night",
            "phone_brightness_daily",
            "phone_volume_daily",
            "phone_dark_mode_daily",
            "phone_unlock_daily",
            "nutrition",
            "menstruation",
            "temperature_daily",
            "pressure_daily",
            "humidity_daily",
            "wind_daily",
            "uv_daily",
            "thunderstorm_daily"
        )

        val client = buildClient()
        var allOk = true

        try {
            for (metric in metrics) {
                val body = MetricSettingUpsertBody(
                    userId = userId,
                    metric = metric,
                    enabled = false,
                    preferredSource = null,
                    updatedAtIso = Instant.now().toString()
                )

                val url =
                    "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/metric_settings?on_conflict=user_id,metric"

                val res = client.post(url) {
                    header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                    header("Prefer", "resolution=ignore-duplicates")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

                val ok = res.status.value in 200..299
                if (!ok) {
                    allOk = false
                    Log.w("EdgeFunctionsService", "Failed to seed metric setting: $metric")
                }
            }
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "seedDefaultMetricSettings exception", e)
            allOk = false
        } finally {
            client.close()
        }

        return allOk
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Risk Decay Weights
    // ─────────────────────────────────────────────────────────────────────────

    @Serializable
    data class RiskDecayWeightResponse(
        val id: String,
        @SerialName("user_id") val userId: String,
        val severity: String,
        @SerialName("day_0") val day0: Double,
        @SerialName("day_1") val day1: Double,
        @SerialName("day_2") val day2: Double,
        @SerialName("day_3") val day3: Double,
        @SerialName("day_4") val day4: Double,
        @SerialName("day_5") val day5: Double,
        @SerialName("day_6") val day6: Double,
        @SerialName("updated_at") val updatedAt: String
    )

    @Serializable
    private data class RiskDecayWeightUpsertBody(
        @SerialName("user_id") val userId: String,
        val severity: String,
        @SerialName("day_0") val day0: Double,
        @SerialName("day_1") val day1: Double,
        @SerialName("day_2") val day2: Double,
        @SerialName("day_3") val day3: Double,
        @SerialName("day_4") val day4: Double,
        @SerialName("day_5") val day5: Double,
        @SerialName("day_6") val day6: Double,
        @SerialName("updated_at") val updatedAtIso: String
    )

    suspend fun getRiskDecayWeights(context: Context): List<RiskDecayWeightResponse> {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return emptyList()
        val userId = SessionStore.readUserId(appCtx) ?: return emptyList()

        val client = buildClient()
        return try {
            val url =
                "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/risk_decay_weights?user_id=eq.$userId&select=*&order=severity.asc"

            val res = client.get(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
            }

            if (res.status.value in 200..299) {
                res.body<List<RiskDecayWeightResponse>>()
            } else {
                Log.e("EdgeFunctionsService", "getRiskDecayWeights failed: ${res.status}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "getRiskDecayWeights error: ${e.message}", e)
            emptyList()
        } finally {
            client.close()
        }
    }

    suspend fun upsertRiskDecayWeight(
        context: Context,
        severity: String,
        day0: Double,
        day1: Double,
        day2: Double,
        day3: Double,
        day4: Double,
        day5: Double,
        day6: Double
    ): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false
        val userId = SessionStore.readUserId(appCtx) ?: return false

        val body = RiskDecayWeightUpsertBody(
            userId = userId,
            severity = severity,
            day0 = day0, day1 = day1, day2 = day2,
            day3 = day3, day4 = day4, day5 = day5, day6 = day6,
            updatedAtIso = Instant.now().toString()
        )

        val client = buildClient()
        return try {
            val url =
                "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/risk_decay_weights?on_conflict=user_id,severity"

            val res = client.post(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                header("Prefer", "resolution=merge-duplicates")
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            val ok = res.status.value in 200..299
            if (!ok) {
                Log.e("EdgeFunctionsService", "upsertRiskDecayWeight failed: ${res.status} - ${res.bodyAsText()}")
            }
            ok
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "upsertRiskDecayWeight exception", e)
            false
        } finally {
            client.close()
        }
    }

    suspend fun seedDefaultRiskDecayWeights(context: Context): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false
        val userId = SessionStore.readUserId(appCtx) ?: return false

        data class Default(val severity: String, val d0: Double, val d1: Double, val d2: Double)

        val defaults = listOf(
            Default("HIGH", 10.0, 5.0, 2.5),
            Default("MILD", 6.0, 3.0, 1.5),
            Default("LOW", 3.0, 1.5, 0.0)
        )

        val client = buildClient()
        var allOk = true

        try {
            for (d in defaults) {
                val body = RiskDecayWeightUpsertBody(
                    userId = userId,
                    severity = d.severity,
                    day0 = d.d0, day1 = d.d1, day2 = d.d2,
                    day3 = 0.0, day4 = 0.0, day5 = 0.0, day6 = 0.0,
                    updatedAtIso = Instant.now().toString()
                )

                val url =
                    "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/risk_decay_weights?on_conflict=user_id,severity"

                val res = client.post(url) {
                    header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                    header("Prefer", "resolution=ignore-duplicates")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

                val ok = res.status.value in 200..299
                if (!ok) {
                    allOk = false
                    Log.w("EdgeFunctionsService", "Failed to seed risk decay weight: ${d.severity}")
                }
            }
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "seedDefaultRiskDecayWeights exception", e)
            allOk = false
        } finally {
            client.close()
        }

        return allOk
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Risk Gauge Thresholds
    // ─────────────────────────────────────────────────────────────────────────

    @Serializable
    data class RiskGaugeThresholdResponse(
        val id: String,
        @SerialName("user_id") val userId: String,
        val zone: String,              // NONE | LOW | MILD | HIGH
        @SerialName("min_value") val minValue: Double,
        @SerialName("updated_at") val updatedAt: String
    )

    @Serializable
    private data class RiskGaugeThresholdUpsertBody(
        @SerialName("user_id") val userId: String,
        val zone: String,
        @SerialName("min_value") val minValue: Double,
        @SerialName("updated_at") val updatedAtIso: String
    )

    suspend fun getRiskGaugeThresholds(context: Context): List<RiskGaugeThresholdResponse> {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return emptyList()
        val userId = SessionStore.readUserId(appCtx) ?: return emptyList()

        val client = buildClient()
        return try {
            val url =
                "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/risk_gauge_thresholds?user_id=eq.$userId&select=*&order=min_value.asc"

            val res = client.get(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
            }

            if (res.status.value in 200..299) {
                res.body<List<RiskGaugeThresholdResponse>>()
            } else {
                Log.e("EdgeFunctionsService", "getRiskGaugeThresholds failed: ${res.status}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "getRiskGaugeThresholds error: ${e.message}", e)
            emptyList()
        } finally {
            client.close()
        }
    }

    suspend fun upsertRiskGaugeThreshold(
        context: Context,
        zone: String,
        minValue: Double
    ): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false
        val userId = SessionStore.readUserId(appCtx) ?: return false

        val body = RiskGaugeThresholdUpsertBody(
            userId = userId,
            zone = zone,
            minValue = minValue,
            updatedAtIso = Instant.now().toString()
        )

        val client = buildClient()
        return try {
            val url =
                "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/risk_gauge_thresholds?on_conflict=user_id,zone"

            val res = client.post(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                header("Prefer", "resolution=merge-duplicates")
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            val ok = res.status.value in 200..299
            if (!ok) {
                Log.e("EdgeFunctionsService", "upsertRiskGaugeThreshold failed: ${res.status} - ${res.bodyAsText()}")
            }
            ok
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "upsertRiskGaugeThreshold exception", e)
            false
        } finally {
            client.close()
        }
    }

    suspend fun seedDefaultRiskGaugeThresholds(context: Context): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false
        val userId = SessionStore.readUserId(appCtx) ?: return false

        val defaults = listOf(
            "NONE" to 0.0,
            "LOW" to 3.0,
            "MILD" to 5.0,
            "HIGH" to 10.0
        )

        val client = buildClient()
        var allOk = true

        try {
            for ((zone, minVal) in defaults) {
                val body = RiskGaugeThresholdUpsertBody(
                    userId = userId,
                    zone = zone,
                    minValue = minVal,
                    updatedAtIso = Instant.now().toString()
                )

                val url =
                    "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/risk_gauge_thresholds?on_conflict=user_id,zone"

                val res = client.post(url) {
                    header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                    header("Prefer", "resolution=ignore-duplicates")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

                val ok = res.status.value in 200..299
                if (!ok) {
                    allOk = false
                    Log.w("EdgeFunctionsService", "Failed to seed risk gauge threshold: $zone")
                }
            }
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "seedDefaultRiskGaugeThresholds exception", e)
            allOk = false
        } finally {
            client.close()
        }

        return allOk
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Risk Score Calculation (on-demand)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Trigger on-demand risk score calculation via edge function.
     * Called when risk_score_live is missing or stale (>2h).
     * The edge function recalculates and writes to risk_score_live + risk_score_daily.
     */
    suspend fun triggerRiskCalculation(context: Context, userId: String): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false

        val client = buildClient()
        return try {
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/calculate-risk-score"

            val res = client.post(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                contentType(ContentType.Application.Json)
                setBody("""{"user_id":"$userId"}""")
            }

            val ok = res.status.value in 200..299
            if (!ok) {
                Log.e("EdgeFunctionsService", "triggerRiskCalculation failed: ${res.status} - ${res.bodyAsText()}")
            } else {
                Log.d("EdgeFunctionsService", "triggerRiskCalculation success for $userId")
            }
            ok
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "triggerRiskCalculation exception", e)
            false
        } finally {
            client.close()
        }
    }

    // Recalc Triggers + Prodromes + Risk Score
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Trigger a full recalculation of system triggers, prodromes, and risk scores.
     * Called after the user saves changes to prediction values or thresholds on
     * the Manage Triggers / Manage Prodromes screens.
     *
     * The edge function:
     *   1. Deletes all source='system' triggers + prodromes for the last 7 days
     *   2. Re-evaluates definitions against existing metric data (threshold + 2SD)
     *   3. Recalculates risk_score_daily + risk_score_live (gauge)
     */
    suspend fun triggerRecalc(context: Context): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false
        val userId = SessionStore.readUserId(appCtx) ?: return false

        val client = buildClient()
        return try {
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/recalc-user-triggers"

            val res = client.post(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                contentType(ContentType.Application.Json)
                setBody("""{"user_id":"$userId"}""")
            }

            val ok = res.status.value in 200..299
            if (!ok) {
                Log.e("EdgeFunctionsService", "triggerRecalc failed: ${res.status} - ${res.bodyAsText()}")
            } else {
                Log.d("EdgeFunctionsService", "triggerRecalc success for $userId")
            }
            ok
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "triggerRecalc exception", e)
            false
        } finally {
            client.close()
        }
    }

    /**
     * Recalculate risk scores only (no trigger/prodrome re-evaluation).
     * Called after the user saves gauge thresholds or decay weights in Risk Model settings.
     */
    suspend fun triggerRecalcRiskScores(context: Context): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false
        val userId = SessionStore.readUserId(appCtx) ?: return false

        val client = buildClient()
        return try {
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/recalc-risk-scores"

            val res = client.post(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                contentType(ContentType.Application.Json)
                setBody("""{"user_id":"$userId"}""")
            }

            val ok = res.status.value in 200..299
            if (!ok) {
                Log.e("EdgeFunctionsService", "triggerRecalcRiskScores failed: ${res.status} - ${res.bodyAsText()}")
            } else {
                Log.d("EdgeFunctionsService", "triggerRecalcRiskScores success for $userId")
            }
            ok
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "triggerRecalcRiskScores exception", e)
            false
        } finally {
            client.close()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Menstruation Decay Weights (centered curve for menstruation_predicted)
    // ─────────────────────────────────────────────────────────────────────────

    @Serializable
    data class MenstruationDecayWeightResponse(
        val id: String,
        @SerialName("user_id") val userId: String,
        @SerialName("day_m7") val dayM7: Double,
        @SerialName("day_m6") val dayM6: Double,
        @SerialName("day_m5") val dayM5: Double,
        @SerialName("day_m4") val dayM4: Double,
        @SerialName("day_m3") val dayM3: Double,
        @SerialName("day_m2") val dayM2: Double,
        @SerialName("day_m1") val dayM1: Double,
        @SerialName("day_0") val day0: Double,
        @SerialName("day_p1") val dayP1: Double,
        @SerialName("day_p2") val dayP2: Double,
        @SerialName("day_p3") val dayP3: Double,
        @SerialName("day_p4") val dayP4: Double,
        @SerialName("day_p5") val dayP5: Double,
        @SerialName("day_p6") val dayP6: Double,
        @SerialName("day_p7") val dayP7: Double,
        @SerialName("updated_at") val updatedAt: String
    )

    @Serializable
    private data class MenstruationDecayWeightUpsertBody(
        @SerialName("user_id") val userId: String,
        @SerialName("day_m7") val dayM7: Double,
        @SerialName("day_m6") val dayM6: Double,
        @SerialName("day_m5") val dayM5: Double,
        @SerialName("day_m4") val dayM4: Double,
        @SerialName("day_m3") val dayM3: Double,
        @SerialName("day_m2") val dayM2: Double,
        @SerialName("day_m1") val dayM1: Double,
        @SerialName("day_0") val day0: Double,
        @SerialName("day_p1") val dayP1: Double,
        @SerialName("day_p2") val dayP2: Double,
        @SerialName("day_p3") val dayP3: Double,
        @SerialName("day_p4") val dayP4: Double,
        @SerialName("day_p5") val dayP5: Double,
        @SerialName("day_p6") val dayP6: Double,
        @SerialName("day_p7") val dayP7: Double,
        @SerialName("updated_at") val updatedAtIso: String
    )

    suspend fun getMenstruationDecayWeights(context: Context): MenstruationDecayWeightResponse? {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return null
        val userId = SessionStore.readUserId(appCtx) ?: return null

        val client = buildClient()
        return try {
            val url =
                "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/menstruation_decay_weights?user_id=eq.$userId&select=*&limit=1"

            val res = client.get(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
            }

            if (res.status.value in 200..299) {
                val list = res.body<List<MenstruationDecayWeightResponse>>()
                list.firstOrNull()
            } else {
                Log.e("EdgeFunctionsService", "getMenstruationDecayWeights failed: ${res.status}")
                null
            }
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "getMenstruationDecayWeights error: ${e.message}", e)
            null
        } finally {
            client.close()
        }
    }

    suspend fun upsertMenstruationDecayWeights(
        context: Context,
        weights: MenstruationDecayWeights
    ): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false
        val userId = SessionStore.readUserId(appCtx) ?: return false

        val body = MenstruationDecayWeightUpsertBody(
            userId = userId,
            dayM7 = weights.dayM7, dayM6 = weights.dayM6, dayM5 = weights.dayM5,
            dayM4 = weights.dayM4, dayM3 = weights.dayM3, dayM2 = weights.dayM2,
            dayM1 = weights.dayM1, day0 = weights.day0,
            dayP1 = weights.dayP1, dayP2 = weights.dayP2, dayP3 = weights.dayP3,
            dayP4 = weights.dayP4, dayP5 = weights.dayP5, dayP6 = weights.dayP6,
            dayP7 = weights.dayP7,
            updatedAtIso = Instant.now().toString()
        )

        val client = buildClient()
        return try {
            val url =
                "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/menstruation_decay_weights?on_conflict=user_id"

            val res = client.post(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                header("Prefer", "resolution=merge-duplicates")
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            val ok = res.status.value in 200..299
            if (!ok) {
                Log.e("EdgeFunctionsService", "upsertMenstruationDecayWeights failed: ${res.status} - ${res.bodyAsText()}")
            }
            ok
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "upsertMenstruationDecayWeights exception", e)
            false
        } finally {
            client.close()
        }
    }

    suspend fun seedDefaultMenstruationDecayWeights(context: Context): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false
        val userId = SessionStore.readUserId(appCtx) ?: return false

        val body = MenstruationDecayWeightUpsertBody(
            userId = userId,
            dayM7 = 0.0, dayM6 = 0.0, dayM5 = 0.0, dayM4 = 0.0,
            dayM3 = 0.0, dayM2 = 3.0, dayM1 = 4.5, day0 = 6.0,
            dayP1 = 3.0, dayP2 = 1.5, dayP3 = 0.0, dayP4 = 0.0,
            dayP5 = 0.0, dayP6 = 0.0, dayP7 = 0.0,
            updatedAtIso = Instant.now().toString()
        )

        val client = buildClient()
        return try {
            val url =
                "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/menstruation_decay_weights?on_conflict=user_id"

            val res = client.post(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                header("Prefer", "resolution=ignore-duplicates")
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            val ok = res.status.value in 200..299
            if (!ok) {
                Log.w("EdgeFunctionsService", "seedDefaultMenstruationDecayWeights failed: ${res.status}")
            }
            ok
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "seedDefaultMenstruationDecayWeights exception", e)
            false
        } finally {
            client.close()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Correlation Stats
    // ─────────────────────────────────────────────────────────────────────────

    @Serializable
    data class CorrelationStat(
        val id: String = "",
        @SerialName("factor_name") val factorName: String = "",
        @SerialName("factor_type") val factorType: String = "",   // trigger, treatment, metric, interaction, treatment_interaction
        @SerialName("factor_b") val factorB: String? = null,
        @SerialName("best_lag_days") val bestLagDays: Int = 0,
        @SerialName("lift_ratio") val liftRatio: Float = 0f,
        @SerialName("pct_migraine_windows") val pctMigraineWindows: Float = 0f,
        @SerialName("pct_control_windows") val pctControlWindows: Float = 0f,
        @SerialName("sample_size") val sampleSize: Int = 0,
        @SerialName("p_value") val pValue: Float = 1f,
        @SerialName("suggested_threshold") val suggestedThreshold: Float? = null,
        @SerialName("current_threshold") val currentThreshold: Float? = null,
        @SerialName("threshold_direction") val thresholdDirection: String? = null,
        @SerialName("metric_table") val metricTable: String? = null,
        @SerialName("metric_column") val metricColumn: String? = null,
        @SerialName("lag_details") val lagDetails: kotlinx.serialization.json.JsonObject? = null,
        @SerialName("updated_at") val updatedAt: String = "",
    ) {
        /** Duration lift from lag_details (treatment/treatment_interaction only) */
        val durationLift: Float get() {
            val v = lagDetails?.get("duration_lift")
            return when (v) {
                is kotlinx.serialization.json.JsonPrimitive -> v.content.toFloatOrNull() ?: 0f
                else -> 0f
            }
        }

        /** Severity lift from lag_details (treatment/treatment_interaction only) */
        val severityLift: Float get() {
            val v = lagDetails?.get("severity_lift")
            return when (v) {
                is kotlinx.serialization.json.JsonPrimitive -> v.content.toFloatOrNull() ?: 0f
                else -> 0f
            }
        }

        /** Avg severity of migraines preceded by this trigger */
        val avgSeverity: Float? get() {
            val v = lagDetails?.get("avg_severity")
            return when (v) {
                is kotlinx.serialization.json.JsonPrimitive -> v.content.toFloatOrNull()
                else -> null
            }
        }

        /** Avg duration (hrs) of migraines preceded by this trigger */
        val avgDurationHrs: Float? get() {
            val v = lagDetails?.get("avg_duration_hrs")
            return when (v) {
                is kotlinx.serialization.json.JsonPrimitive -> v.content.toFloatOrNull()
                else -> null
            }
        }

        /** Human-readable description of this finding */
        fun toInsightText(): String = when (factorType) {
            "trigger" -> {
                val lagText = if (bestLagDays == 0) "on the same day"
                    else "$bestLagDays day${if (bestLagDays > 1) "s" else ""} before onset"
                val base = "${factorName} appeared before ${pctMigraineWindows.toInt()}% of your migraines ($lagText). " +
                    "That's ${String.format("%.1f", liftRatio)}x more than normal days."
                val parts = mutableListOf(base)
                val durStr = avgDurationHrs?.let { "avg ${String.format("%.0f", it)}hrs" }
                val sevStr = avgSeverity?.let { "severity ${String.format("%.0f", it)}/10" }
                val extras = listOfNotNull(durStr, sevStr)
                if (extras.isNotEmpty()) parts.add("These migraines: ${extras.joinToString(", ")}.")
                parts.joinToString(" ")
            }
            "metric" -> {
                if (suggestedThreshold != null && currentThreshold != null &&
                    kotlin.math.abs(suggestedThreshold - currentThreshold) > currentThreshold * 0.05f) {
                    val dirText = when (thresholdDirection) {
                        "low" -> "drops below"
                        "high" -> "rises above"
                        else -> "crosses"
                    }
                    "Your migraine risk jumps when ${factorName.lowercase()} $dirText " +
                        "${fmtThreshold(suggestedThreshold, factorName)} — your current alert is set at " +
                        "${fmtThreshold(currentThreshold, factorName)}."
                } else if (suggestedThreshold != null) {
                    val dirText = when (thresholdDirection) {
                        "low" -> "below"
                        "high" -> "above"
                        else -> "around"
                    }
                    "Your migraines cluster when ${factorName.lowercase()} is $dirText " +
                        "${fmtThreshold(suggestedThreshold, factorName)} " +
                        "(${String.format("%.1f", liftRatio)}x lift)."
                } else {
                    "${factorName} shows a ${String.format("%.1f", liftRatio)}x difference on pre-migraine days vs normal days."
                }
            }
            "interaction" -> {
                "${factorName} + ${factorB ?: "?"} together preceded " +
                    "${pctMigraineWindows.toInt()}% of your migraines — " +
                    "${String.format("%.1f", liftRatio)}x more likely than either alone."
            }
            "treatment_interaction" -> {
                val parts = mutableListOf("${factorName} + ${factorB ?: "?"} used together:")
                if (durationLift > 1f) parts.add("${String.format("%.1f", durationLift)}\u00D7 shorter")
                if (severityLift > 1f) parts.add("${String.format("%.1f", severityLift)}\u00D7 milder")
                if (durationLift <= 1f && severityLift <= 1f) parts.add("${String.format("%.1f", liftRatio)}\u00D7 more effective")
                parts.joinToString(" ")
            }
            "treatment" -> {
                val usagePct = pctMigraineWindows.toInt()
                val avgRelief = pctControlWindows // reused field: avg relief score 0-3
                val reliefLabel = when {
                    avgRelief >= 2.5f -> "strong"
                    avgRelief >= 1.5f -> "moderate"
                    avgRelief >= 0.5f -> "mild"
                    else -> "minimal"
                }
                val parts = mutableListOf<String>()
                parts.add("Used in $usagePct% of your migraines with $reliefLabel reported relief.")
                if (durationLift > 1f) parts.add("${String.format("%.1f", durationLift)}\u00D7 shorter migraines.")
                if (severityLift > 1f) parts.add("${String.format("%.1f", severityLift)}\u00D7 milder migraines.")
                if (durationLift <= 1f && severityLift <= 1f) parts.add("No significant duration or severity improvement detected yet.")
                parts.joinToString(" ")
            }
            else -> "${factorName}: ${String.format("%.1f", liftRatio)}x lift"
        }

        fun isSignificant(): Boolean = when (factorType) {
            "treatment", "treatment_interaction" -> {
                // Show if either duration or severity is meaningful, or overall lift is significant
                pValue < 0.1f && (durationLift > 1.1f || severityLift > 1.1f || liftRatio > 1.3f)
            }
            else -> pValue < 0.1f && liftRatio > 1.3f
        }

        companion object {
            fun fmtThreshold(value: Float, metricLabel: String): String {
                val lower = metricLabel.lowercase()
                return when {
                    "sleep" in lower && "duration" in lower -> "${String.format("%.1f", value)}hrs"
                    "hrv" in lower -> "${value.toInt()}ms"
                    "pressure" in lower -> "${value.toInt()}hPa"
                    "humidity" in lower -> "${value.toInt()}%"
                    "temperature" in lower || "temp" in lower -> "${String.format("%.1f", value)}°C"
                    "screen" in lower -> "${String.format("%.1f", value)}hrs"
                    "recovery" in lower || "efficiency" in lower || "score" in lower -> "${value.toInt()}%"
                    "stress" in lower -> "${value.toInt()}"
                    "noise" in lower -> "${value.toInt()}%"
                    "heart" in lower || "hr" in lower -> "${value.toInt()}bpm"
                    else -> String.format("%.1f", value)
                }
            }
        }
    }

    @Serializable
    data class GaugeAccuracy(
        @SerialName("true_positives") val truePositives: Int = 0,
        @SerialName("false_positives") val falsePositives: Int = 0,
        @SerialName("false_negatives") val falseNegatives: Int = 0,
        @SerialName("true_negatives") val trueNegatives: Int = 0,
        @SerialName("total_days") val totalDays: Int = 0,
        @SerialName("sensitivity_pct") val sensitivityPct: Int = 0,
        @SerialName("specificity_pct") val specificityPct: Int = 0,
        @SerialName("false_alarm_rate_pct") val falseAlarmRatePct: Int = 0,
        @SerialName("updated_at") val updatedAt: String = "",
    ) {
        fun catchRateText(): String = "Your gauge caught ${sensitivityPct}% of your migraines"
        fun falseAlarmText(): String = "False alarm rate: ${falseAlarmRatePct}%"
    }

    /**
     * Trigger the compute-correlation-stats edge function.
     * Fire-and-forget — call after migraine save.
     */
    suspend fun triggerCorrelationCompute(context: Context): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false

        val client = buildClient()
        return try {
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/compute-correlation-stats"

            val res = client.post(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }

            val ok = res.status.value in 200..299
            if (!ok) {
                Log.w("EdgeFunctionsService", "triggerCorrelationCompute failed: ${res.status.value} ${res.bodyAsText()}")
            } else {
                Log.d("EdgeFunctionsService", "triggerCorrelationCompute success")
            }
            ok
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "triggerCorrelationCompute exception", e)
            false
        } finally {
            client.close()
        }
    }

    /**
     * Read top significant correlations from PostgREST.
     */
    suspend fun getTopCorrelations(context: Context, limit: Int = 50): List<CorrelationStat> {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return emptyList()

        val client = buildClient()
        return try {
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/correlation_stats" +
                "?order=lift_ratio.desc" +
                "&limit=$limit" +
                "&select=id,factor_name,factor_type,factor_b,best_lag_days,lift_ratio," +
                "pct_migraine_windows,pct_control_windows,sample_size,p_value," +
                "suggested_threshold,current_threshold,threshold_direction," +
                "metric_table,metric_column,lag_details,updated_at"

            val res = client.get(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
            }

            if (res.status.value in 200..299) {
                res.body<List<CorrelationStat>>()
            } else {
                Log.w("EdgeFunctionsService", "getTopCorrelations failed: ${res.status.value}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "getTopCorrelations exception", e)
            emptyList()
        } finally {
            client.close()
        }
    }

    /**
     * Read gauge accuracy for current user.
     */
    suspend fun getGaugeAccuracy(context: Context): GaugeAccuracy? {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return null

        val client = buildClient()
        return try {
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/gauge_accuracy" +
                "?select=*&limit=1"

            val res = client.get(url) {
                header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                header(HttpHeaders.Authorization, "Bearer $supaAccessToken")
                header("Accept", "application/vnd.pgrst.object+json")
            }

            if (res.status.value in 200..299) {
                res.body<GaugeAccuracy>()
            } else {
                Log.w("EdgeFunctionsService", "getGaugeAccuracy failed: ${res.status.value}")
                null
            }
        } catch (e: Exception) {
            Log.e("EdgeFunctionsService", "getGaugeAccuracy exception", e)
            null
        } finally {
            client.close()
        }
    }
}
