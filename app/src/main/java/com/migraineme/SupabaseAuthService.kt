// FILE: app/src/main/java/com/migraineme/SupabaseAuthService.kt
package com.migraineme

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

object SupabaseAuthService {
    private val baseUrl: String = BuildConfig.SUPABASE_URL
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY

    private val client = HttpClient(Android) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    encodeDefaults = true
                }
            )
        }
    }

    @Serializable
    data class PasswordGrantRequest(val email: String, val password: String)

    @Serializable
    data class SignUpRequest(val email: String, val password: String)

    @Serializable
    data class IdTokenGrantRequest(
        val provider: String,
        @SerialName("id_token") val idToken: String,
        val nonce: String? = null
    )

    @Serializable
    data class RefreshTokenGrantRequest(
        @SerialName("refresh_token") val refreshToken: String
    )

    @Serializable
    data class SessionResponse(
        @SerialName("access_token") val accessToken: String? = null,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("token_type") val tokenType: String? = null,
        @SerialName("expires_in") val expiresIn: Long? = null
    )

    @Serializable
    data class Identity(
        val provider: String? = null
    )

    @Serializable
    data class UserResponse(
        val id: String? = null,
        val email: String? = null,
        val identities: List<Identity>? = null,
        @SerialName("user_metadata") val userMetadata: JsonObject? = null
    )

    @Serializable
    data class UpdateUserRequest(
        val password: String
    )

    @Serializable
    data class RecoverRequest(
        val email: String
    )

    suspend fun signInWithEmail(email: String, password: String): SessionResponse {
        val url = "$baseUrl/auth/v1/token?grant_type=password"
        return client.post(url) {
            header("apikey", anonKey)
            contentType(ContentType.Application.Json)
            setBody(PasswordGrantRequest(email, password))
        }.body()
    }

    suspend fun signUpWithEmail(email: String, password: String): SessionResponse {
        val url = "$baseUrl/auth/v1/signup"
        val response = client.post(url) {
            header("apikey", anonKey)
            contentType(ContentType.Application.Json)
            setBody(SignUpRequest(email, password))
        }
        val rawBody = response.bodyAsText()
        android.util.Log.d("SupabaseAuth", "signUp status: ${response.status}")
        android.util.Log.d("SupabaseAuth", "signUp body: $rawBody")
        return Json { ignoreUnknownKeys = true; isLenient = true }.decodeFromString(rawBody)
    }

    /**
     * Native mobile Google sign-in:
     * - You obtain a Google ID token on-device (Credential Manager)
     * - Exchange it for a Supabase session (access_token / refresh_token)
     */
    suspend fun signInWithGoogleIdToken(idToken: String, nonce: String? = null): SessionResponse {
        val url = "$baseUrl/auth/v1/token?grant_type=id_token"
        return client.post(url) {
            header("apikey", anonKey)
            contentType(ContentType.Application.Json)
            setBody(
                IdTokenGrantRequest(
                    provider = "google",
                    idToken = idToken,
                    nonce = nonce
                )
            )
        }.body()
    }

    /**
     * Refreshes a Supabase session using a refresh_token.
     * Supabase: POST /auth/v1/token?grant_type=refresh_token
     */
    suspend fun refreshSession(refreshToken: String): SessionResponse {
        val url = "$baseUrl/auth/v1/token?grant_type=refresh_token"
        return client.post(url) {
            header("apikey", anonKey)
            contentType(ContentType.Application.Json)
            setBody(RefreshTokenGrantRequest(refreshToken = refreshToken))
        }.body()
    }

    suspend fun signOut(accessToken: String) {
        val url = "$baseUrl/auth/v1/logout"
        client.post(url) {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
        }
    }

    /**
     * Used to determine whether the current user has an email identity.
     */
    suspend fun getUser(accessToken: String): UserResponse {
        val url = "$baseUrl/auth/v1/user"
        return client.get(url) {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
        }.body()
    }

    /**
     * Changes password for current authenticated user.
     * Supabase: PUT /auth/v1/user { "password": "..." }
     */
    suspend fun changePassword(accessToken: String, newPassword: String): UserResponse {
        val url = "$baseUrl/auth/v1/user"
        return client.put(url) {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(UpdateUserRequest(password = newPassword))
        }.body()
    }

    /**
     * Sends password recovery email (reset link) for email/password accounts.
     * Supabase: POST /auth/v1/recover { "email": "..." }
     */
    suspend fun requestPasswordReset(email: String, redirectTo: String? = null) {
        val url = "$baseUrl/auth/v1/recover"
        client.post(url) {
            header("apikey", anonKey)
            contentType(ContentType.Application.Json)
            if (!redirectTo.isNullOrBlank()) {
                parameter("redirect_to", redirectTo)
            }
            setBody(RecoverRequest(email = email))
        }
    }
}
