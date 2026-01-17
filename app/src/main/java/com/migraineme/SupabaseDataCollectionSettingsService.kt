package com.migraineme

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Mirrors local metric settings into Supabase (public.metric_settings).
 *
 * Auth pattern unchanged:
 * - Authorization: Bearer <supabase session token>
 * - apikey: anon key
 */
class SupabaseDataCollectionSettingsService(context: Context) {

    private val app = context.applicationContext
    private val supabaseUrl = BuildConfig.SUPABASE_URL
    private val supabaseKey = BuildConfig.SUPABASE_ANON_KEY

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    @Serializable
    data class MetricSettingRow(
        @SerialName("metric") val metric: String,
        @SerialName("enabled") val enabled: Boolean,
        @SerialName("preferred_source") val preferredSource: String? = null,
        @SerialName("allowed_sources") val allowedSources: List<String> = emptyList()
    )

    /**
     * Upserts rows for the current user (RLS enforces auth.uid()).
     * Table PK is (user_id, metric) so PostgREST upsert must use on_conflict=user_id,metric.
     */
    suspend fun upsertMetricSettingsBatch(
        supabaseAccessToken: String,
        rows: List<MetricSettingRow>
    ) {
        if (rows.isEmpty()) return

        val resp = client.post("$supabaseUrl/rest/v1/metric_settings") {
            header(HttpHeaders.Authorization, "Bearer $supabaseAccessToken")
            header("apikey", supabaseKey)
            header("Prefer", "resolution=merge-duplicates,return=minimal")
            parameter("on_conflict", "user_id,metric")
            contentType(ContentType.Application.Json)
            setBody(rows)
        }

        if (!resp.status.isSuccess()) {
            error("Upsert metric_settings failed: ${resp.body<String>()}")
        }
    }

    /**
     * Convenience wrapper used by DataSettingsScreen for immediate per-metric updates.
     */
    suspend fun upsertMetricSetting(
        supabaseAccessToken: String,
        metric: String,
        enabled: Boolean,
        preferredSource: String?,
        allowedSources: List<String>
    ) {
        upsertMetricSettingsBatch(
            supabaseAccessToken = supabaseAccessToken,
            rows = listOf(
                MetricSettingRow(
                    metric = metric,
                    enabled = enabled,
                    preferredSource = preferredSource,
                    allowedSources = allowedSources
                )
            )
        )
    }
}
