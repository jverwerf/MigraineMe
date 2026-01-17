package com.migraineme

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
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
import java.time.Instant

/**
 * Stores WHOOP tokens in Supabase (public.whoop_tokens).
 *
 * Uses the existing Supabase REST pattern used throughout the app:
 *  - Authorization: Bearer <supabase access token>
 *  - apikey: BuildConfig.SUPABASE_ANON_KEY
 */
class SupabaseWhoopTokenService(context: Context) {

    private val supabaseUrl = BuildConfig.SUPABASE_URL
    private val supabaseKey = BuildConfig.SUPABASE_ANON_KEY

    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    @Serializable
    private data class WhoopTokenRow(
        @SerialName("user_id") val userId: String,
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("token_type") val tokenType: String = "Bearer",
        @SerialName("expires_at") val expiresAt: String? = null
    )

    /**
     * Upsert the WHOOP token row for this user (one row per user_id).
     */
    suspend fun upsertToken(
        supabaseAccessToken: String,
        userId: String,
        token: WhoopToken
    ) {
        val expiresAtIso = token.expiresAtMillis
            .takeIf { it > 0L }
            ?.let { Instant.ofEpochMilli(it).toString() }

        val row = WhoopTokenRow(
            userId = userId,
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            tokenType = token.tokenType.ifBlank { "Bearer" },
            expiresAt = expiresAtIso
        )

        val resp = client.post("$supabaseUrl/rest/v1/whoop_tokens") {
            header(HttpHeaders.Authorization, "Bearer $supabaseAccessToken")
            header("apikey", supabaseKey)
            header("Prefer", "resolution=merge-duplicates,return=minimal")
            parameter("on_conflict", "user_id")
            contentType(ContentType.Application.Json)
            setBody(listOf(row))
        }

        if (!resp.status.isSuccess()) {
            error("Upsert whoop_tokens failed: ${resp.body<String>()}")
        }
    }

    /**
     * Delete the WHOOP token row for this user.
     */
    suspend fun deleteToken(
        supabaseAccessToken: String,
        userId: String
    ) {
        val resp = client.delete("$supabaseUrl/rest/v1/whoop_tokens") {
            header(HttpHeaders.Authorization, "Bearer $supabaseAccessToken")
            header("apikey", supabaseKey)
            parameter("user_id", "eq.$userId")
        }

        if (!resp.status.isSuccess()) {
            error("Delete whoop_tokens failed: ${resp.body<String>()}")
        }
    }
}
