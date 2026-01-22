package com.migraineme

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Writes derived ambient noise samples to:
 * - public.ambient_noise_samples
 *
 * Table (as created earlier):
 * - id uuid default gen_random_uuid()
 * - user_id uuid NOT NULL
 * - start_ts timestamptz NOT NULL
 * - duration_s int NOT NULL
 * - l_mean double precision NOT NULL
 * - l_p90 double precision NULL
 * - l_max double precision NULL
 * - quality_flags jsonb default {}
 * - created_at timestamptz default now()
 */
class SupabaseAmbientNoiseService {

    private val supabaseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val supabaseKey = BuildConfig.SUPABASE_ANON_KEY

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false })
        }
    }

    @Serializable
    private data class AmbientNoiseInsert(
        @SerialName("user_id") val userId: String,
        @SerialName("start_ts") val startTs: String,
        @SerialName("duration_s") val durationS: Int,
        @SerialName("l_mean") val lMean: Double,
        @SerialName("l_p90") val lP90: Double? = null,
        @SerialName("l_max") val lMax: Double? = null,
        @SerialName("quality_flags") val qualityFlags: Map<String, String> = emptyMap()
    )

    suspend fun insertAmbientNoiseSample(
        accessToken: String,
        userId: String,
        startTsIso: String,
        durationS: Int,
        lMean: Double,
        lP90: Double?,
        lMax: Double?,
        qualityFlags: Map<String, String>
    ) {
        val payload = AmbientNoiseInsert(
            userId = userId,
            startTs = startTsIso,
            durationS = durationS,
            lMean = lMean,
            lP90 = lP90,
            lMax = lMax,
            qualityFlags = qualityFlags
        )

        val resp: HttpResponse = client.post("$supabaseUrl/rest/v1/ambient_noise_samples") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            header("apikey", supabaseKey)
            // No need to return rows; keeps bandwidth low.
            header("Prefer", "return=minimal")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }

        if (!resp.status.isSuccess()) {
            error("Insert ambient_noise_samples failed: ${resp.bodyAsText()}")
        }
    }
}
