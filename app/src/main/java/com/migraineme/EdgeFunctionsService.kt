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
}
