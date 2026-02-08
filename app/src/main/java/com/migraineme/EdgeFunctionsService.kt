
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
        @SerialName("allowed_sources") val allowedSources: List<String>? = null,
        @SerialName("updated_at") val updatedAt: String
    )

    @Serializable
    private data class TriggerSettingUpsertBody(
        @SerialName("user_id") val userId: String,
        @SerialName("trigger_type") val triggerType: String,
        val enabled: Boolean,
        @SerialName("updated_at") val updatedAtIso: String
    )

    @Serializable
    data class TriggerSettingResponse(
        @SerialName("user_id") val userId: String,
        @SerialName("trigger_type") val triggerType: String,
        val enabled: Boolean,
        @SerialName("updated_at") val updatedAt: String
    )

    @Serializable
    private data class ProdromeSettingUpsertBody(
        @SerialName("user_id") val userId: String,
        @SerialName("prodrome_type") val prodromeType: String,
        val enabled: Boolean,
        @SerialName("updated_at") val updatedAtIso: String
    )

    @Serializable
    data class ProdromeSettingResponse(
        @SerialName("user_id") val userId: String,
        @SerialName("prodrome_type") val prodromeType: String,
        val enabled: Boolean,
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
            "stress_index_daily"
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

    suspend fun upsertTriggerSetting(
        context: Context,
        triggerType: String,
        enabled: Boolean
    ): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false
        val userId = SessionStore.readUserId(appCtx) ?: return false

        val body = TriggerSettingUpsertBody(
            userId = userId,
            triggerType = triggerType,
            enabled = enabled,
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
        enabled: Boolean
    ): Boolean {
        val appCtx = context.applicationContext
        val supaAccessToken = SessionStore.getValidAccessToken(appCtx) ?: return false
        val userId = SessionStore.readUserId(appCtx) ?: return false

        val body = ProdromeSettingUpsertBody(
            userId = userId,
            prodromeType = prodromeType,
            enabled = enabled,
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
}

