package com.migraineme

import android.content.Context
import android.util.Log
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
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
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Date

/**
 * Called from LoginScreen after successful auth.
 *
 * Responsibilities:
 * - Best-effort WHOOP token refresh (does not change auth approach)
 * - Upload WHOOP token to Supabase (server worker source of truth)
 * - Seed default metric settings for new users
 * - Seed default trigger settings for new users
 *
 * Does NOT:
 * - Change Supabase auth
 * - Change Whoop auth structure
 * - Run/schedule on-device WHOOP daily sync workers (WHOOP ingestion is backend-driven now)
 */
object MetricsSyncManager {

    private val baseUrl: String = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = true
                    explicitNulls = false
                }
            )
        }
    }

    @Serializable
    private data class EmptyBody(val ok: Boolean = true)

    @Serializable
    private data class UpsertWhoopTokenBody(
        val access_token: String,
        val refresh_token: String,
        val token_type: String,
        val expires_at: String?
    )

    private suspend fun enqueueLoginBackfillBestEffort(accessToken: String) {
        val url = "$baseUrl/functions/v1/enqueue-login-backfill"

        try {
            val resp = client.post(url) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", anonKey)
                contentType(ContentType.Application.Json)
                setBody(EmptyBody())
            }

            if (!resp.status.isSuccess()) {
                val txt = runCatching { resp.bodyAsText() }.getOrNull()
                Log.w(
                    "MetricsSyncManager",
                    "enqueue-login-backfill failed: HTTP ${resp.status.value} ${txt ?: ""}".trim()
                )
            } else {
                Log.d("MetricsSyncManager", "enqueue-login-backfill ok")
            }
        } catch (t: Throwable) {
            Log.w("MetricsSyncManager", "enqueue-login-backfill error: ${t.message}")
        }
    }

    private suspend fun upsertWhoopTokenToSupabaseBestEffort(
        context: Context,
        accessToken: String
    ) {
        val appCtx = context.applicationContext
        val localTok = runCatching { WhoopTokenStore(appCtx).load() }.getOrNull() ?: return

        val url = "$baseUrl/functions/v1/upsert-whoop-token"

        val expiresAtIso = if (localTok.expiresAtMillis > 0L) {
            runCatching { Date(localTok.expiresAtMillis).toInstant().toString() }.getOrNull()
        } else {
            null
        }

        val body = UpsertWhoopTokenBody(
            access_token = localTok.accessToken,
            refresh_token = localTok.refreshToken,
            token_type = localTok.tokenType.ifBlank { "Bearer" },
            expires_at = expiresAtIso
        )

        try {
            val resp = client.post(url) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", anonKey)
                contentType(ContentType.Application.Json)
                setBody(body)
            }

            if (!resp.status.isSuccess()) {
                val txt = runCatching { resp.bodyAsText() }.getOrNull()
                Log.w(
                    "MetricsSyncManager",
                    "upsert-whoop-token failed: HTTP ${resp.status.value} ${txt ?: ""}".trim()
                )
            } else {
                Log.d("MetricsSyncManager", "upsert-whoop-token ok")
            }
        } catch (t: Throwable) {
            Log.w("MetricsSyncManager", "upsert-whoop-token error: ${t.message}")
        }
    }

    private suspend fun seedMetricSettingsBestEffort(context: Context) {
        try {
            val ok = EdgeFunctionsService().seedDefaultMetricSettings(context)
            if (ok) {
                Log.d("MetricsSyncManager", "seedDefaultMetricSettings ok")
            } else {
                Log.w("MetricsSyncManager", "seedDefaultMetricSettings partial failure")
            }
        } catch (t: Throwable) {
            Log.w("MetricsSyncManager", "seedDefaultMetricSettings error: ${t.message}")
        }
    }

    private suspend fun seedTriggerSettingsBestEffort(context: Context) {
        try {
            val ok = EdgeFunctionsService().seedDefaultTriggerSettings(context)
            if (ok) {
                Log.d("MetricsSyncManager", "seedDefaultTriggerSettings ok")
            } else {
                Log.w("MetricsSyncManager", "seedDefaultTriggerSettings partial failure")
            }
        } catch (t: Throwable) {
            Log.w("MetricsSyncManager", "seedDefaultTriggerSettings error: ${t.message}")
        }
    }


    private suspend fun seedRiskDecayWeightsBestEffort(context: Context) {
        try {
            val ok = EdgeFunctionsService().seedDefaultRiskDecayWeights(context)
            if (ok) {
                Log.d("MetricsSyncManager", "seedDefaultRiskDecayWeights ok")
            } else {
                Log.w("MetricsSyncManager", "seedDefaultRiskDecayWeights partial failure")
            }
        } catch (t: Throwable) {
            Log.w("MetricsSyncManager", "seedDefaultRiskDecayWeights error: ${t.message}")
        }
    }

    private suspend fun seedRiskGaugeThresholdsBestEffort(context: Context) {
        try {
            val ok = EdgeFunctionsService().seedDefaultRiskGaugeThresholds(context)
            if (ok) {
                Log.d("MetricsSyncManager", "seedDefaultRiskGaugeThresholds ok")
            } else {
                Log.w("MetricsSyncManager", "seedDefaultRiskGaugeThresholds partial failure")
            }
        } catch (t: Throwable) {
            Log.w("MetricsSyncManager", "seedDefaultRiskGaugeThresholds error: ${t.message}")
        }
    }
    private fun isWhoopMetricEnabled(
        settings: List<EdgeFunctionsService.MetricSettingResponse>,
        metric: String
    ): Boolean {
        val setting = settings.find { it.metric == metric } ?: return false
        return setting.enabled && setting.preferredSource == "whoop"
    }

    suspend fun onLogin(
        context: Context,
        token: String,
        snackbarHostState: SnackbarHostState
    ) {
        val appCtx = context.applicationContext

        withContext(Dispatchers.IO) {
            try {
                // Enqueue backfill jobs on login (best-effort)
                enqueueLoginBackfillBestEffort(token)

                // If WHOOP token exists locally, upload it to Supabase (best-effort)
                upsertWhoopTokenToSupabaseBestEffort(appCtx, token)

                // Seed default metric settings (best-effort, ignores duplicates)
                seedMetricSettingsBestEffort(appCtx)

                // Seed default trigger settings (best-effort, ignores duplicates)
                seedTriggerSettingsBestEffort(appCtx)

                // Seed default risk decay weights (best-effort, ignores duplicates)
                seedRiskDecayWeightsBestEffort(appCtx)

                // Seed default risk gauge thresholds (best-effort, ignores duplicates)
                seedRiskGaugeThresholdsBestEffort(appCtx)

                // WHOOP connection = token exists locally
                val whoopConnected = runCatching { WhoopTokenStore(appCtx).load() != null }.getOrDefault(false)

                // Get metric settings from Supabase
                val settings = try {
                    EdgeFunctionsService().getMetricSettings(appCtx)
                } catch (e: Exception) {
                    Log.w("MetricsSyncManager", "Failed to get metric settings: ${e.message}")
                    emptyList()
                }

                val whoopSleepEnabled =
                    isWhoopMetricEnabled(settings, "sleep_duration_daily") ||
                    isWhoopMetricEnabled(settings, "sleep_score_daily") ||
                    isWhoopMetricEnabled(settings, "sleep_efficiency_daily") ||
                    isWhoopMetricEnabled(settings, "sleep_stages_daily") ||
                    isWhoopMetricEnabled(settings, "sleep_disturbances_daily") ||
                    isWhoopMetricEnabled(settings, "fell_asleep_time_daily") ||
                    isWhoopMetricEnabled(settings, "woke_up_time_daily")

                val whoopPhysicalEnabled =
                    isWhoopMetricEnabled(settings, "recovery_score_daily") ||
                    isWhoopMetricEnabled(settings, "resting_hr_daily") ||
                    isWhoopMetricEnabled(settings, "hrv_daily") ||
                    isWhoopMetricEnabled(settings, "skin_temp_daily") ||
                    isWhoopMetricEnabled(settings, "spo2_daily") ||
                    isWhoopMetricEnabled(settings, "time_in_high_hr_zones_daily") ||
                    isWhoopMetricEnabled(settings, "steps_daily")

                if (whoopConnected && (whoopSleepEnabled || whoopPhysicalEnabled)) {
                    runCatching { WhoopAuthService().refresh(appCtx) }.onFailure {
                        Log.w("MetricsSyncManager", "WHOOP refresh failed: ${it.message}")
                    }
                } else if (!whoopConnected && (whoopSleepEnabled || whoopPhysicalEnabled)) {
                    withContext(Dispatchers.Main) {
                        snackbarHostState.showSnackbar(
                            message = "Whoop not connected — connect Whoop to collect data.",
                            duration = SnackbarDuration.Short
                        )
                    }
                }

                // NOTE: Location and noise workers are NOT started here anymore.
                // They are only started when user explicitly enables them in DataSettings
                // and grants permission.

            } catch (t: Throwable) {
                Log.w("MetricsSyncManager", "onLogin error: ${t.message}")
            }

            Unit
        }
    }
}
