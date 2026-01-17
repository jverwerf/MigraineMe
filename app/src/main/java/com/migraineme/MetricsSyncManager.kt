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

/**
 * Called from LoginScreen after successful auth.
 *
 * Responsibilities:
 * - Best-effort WHOOP token refresh (does not change auth approach)
 * - Schedule WHOOP daily workers (sleep + physical) if enabled + connected
 * - Schedule Location worker if enabled
 * - Best-effort enqueue of server-side WHOOP backfill jobs on login
 *
 * Does NOT:
 * - Change Supabase auth
 * - Change Whoop auth structure
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

    /**
     * Triggers the Edge Function:
     * POST {SUPABASE_URL}/functions/v1/enqueue-login-backfill
     *
     * Requires:
     * - Authorization: Bearer <user access token>
     *
     * Best-effort: failures are logged, never block login.
     */
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
                // Optional: keep quiet to avoid noise; logs exist in Edge Function.
                Log.d("MetricsSyncManager", "enqueue-login-backfill ok")
            }
        } catch (t: Throwable) {
            Log.w("MetricsSyncManager", "enqueue-login-backfill error: ${t.message}")
        }
    }

    suspend fun onLogin(
        context: Context,
        token: String,
        snackbarHostState: SnackbarHostState
    ) {
        val appCtx = context.applicationContext

        withContext(Dispatchers.IO) {
            try {
                // NEW: enqueue server-side login backfill jobs (best-effort, never blocks login)
                enqueueLoginBackfillBestEffort(token)

                // WHOOP connection = token exists locally
                val whoopConnected = runCatching { WhoopTokenStore(appCtx).load() != null }.getOrDefault(false)

                // Determine if ANY WHOOP tables are enabled (sleep / physical)
                val whoopSleepEnabled =
                    DataCollectionSettings.isEnabledForWhoop(appCtx, "sleep_duration_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "sleep_score_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "sleep_efficiency_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "sleep_stages_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "sleep_disturbances_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "fell_asleep_time_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "woke_up_time_daily")

                val whoopPhysicalEnabled =
                    DataCollectionSettings.isEnabledForWhoop(appCtx, "recovery_score_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "resting_hr_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "hrv_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "skin_temp_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "spo2_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "time_in_high_hr_zones_daily") ||
                            DataCollectionSettings.isEnabledForWhoop(appCtx, "steps_daily")

                // If WHOOP connected, refresh Whoop token once (best-effort).
                // If it fails, we still schedule; workers will log failures and retry next day.
                if (whoopConnected && (whoopSleepEnabled || whoopPhysicalEnabled)) {
                    runCatching { WhoopAuthService().refresh(appCtx) }.onFailure {
                        Log.w("MetricsSyncManager", "WHOOP refresh failed: ${it.message}")
                    }

                    // Schedule + run once now (only if the category is enabled)
                    if (whoopSleepEnabled) {
                        WhoopDailySyncWorkerSleepFields.runOnceNow(appCtx)
                        WhoopDailySyncWorkerSleepFields.scheduleNext(appCtx)
                    }

                    if (whoopPhysicalEnabled) {
                        WhoopDailyPhysicalHealthWorker.runOnceNow(appCtx)
                        WhoopDailyPhysicalHealthWorker.scheduleNext(appCtx)
                    }
                } else if (!whoopConnected && (whoopSleepEnabled || whoopPhysicalEnabled)) {
                    // User has WHOOP collection enabled but no connection.
                    withContext(Dispatchers.Main) {
                        snackbarHostState.showSnackbar(
                            message = "Whoop not connected — connect Whoop to collect data.",
                            duration = SnackbarDuration.Short
                        )
                    }
                }

                // Location (NOT Whoop-specific)
                val locationEnabled =
                    DataCollectionSettings.isActive(
                        context = appCtx,
                        table = "user_location_daily",
                        wearable = null,
                        defaultValue = true
                    )

                if (locationEnabled) {
                    LocationDailySyncWorker.runOnceNow(appCtx)
                    LocationDailySyncWorker.scheduleNext(appCtx)
                    LocationDailySyncWorker.backfillUpToToday(appCtx, token)
                }

            } catch (t: Throwable) {
                Log.w("MetricsSyncManager", "onLogin error: ${t.message}")
            }

            Unit
        }
    }
}
