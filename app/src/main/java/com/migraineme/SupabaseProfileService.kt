package com.migraineme

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Source-of-truth app profile data (provider-agnostic).
 *
 * Backed by Supabase PostgREST table: profiles
 * - user_id uuid PK (RLS ensures auth.uid() can only read/write their own row)
 * - display_name text
 * - avatar_url text
 * - migraine_type migraine_type enum (nullable)
 */
object SupabaseProfileService {

    // Keep config consistent with the rest of the app: BuildConfig + anon key.
    private val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    private val client = HttpClient(Android) {
        install(ContentNegotiation) { json(json) }
    }

    // ---- Public models (what the app uses) ----

    enum class MigraineType(val dbValue: String, val label: String) {
        MIGRAINE("migraine", "Migraine"),
        MIGRAINE_WITH_AURA("migraine_with_aura", "Migraine with aura"),
        CLUSTER("cluster", "Cluster"),
        TENSION("tension", "Tension"),
        HEMIPLEGIC("hemiplegic", "Hemiplegic"),
        VESTIBULAR("vestibular", "Vestibular"),
        OTHER("other", "Other");

        companion object {
            fun fromDbValue(value: String?): MigraineType? {
                if (value.isNullOrBlank()) return null
                return entries.firstOrNull { it.dbValue == value }
            }
        }
    }

    data class Profile(
        val userId: String,
        val displayName: String?,
        val avatarUrl: String?,
        val migraineType: MigraineType?
    )

    /**
     * Ensures a row exists for this user and optionally fills missing display_name/avatar_url
     * using provider "hints" (Google/Apple/Facebook).
     *
     * Rules:
     * - Never overwrites a user-provided value.
     * - Only sets fields if they are currently null/blank.
     */
    suspend fun ensureProfile(
        accessToken: String,
        userId: String,
        displayNameHint: String?,
        avatarUrlHint: String?
    ): Profile {
        val existing = getProfile(accessToken, userId)
        if (existing == null) {
            // No row: insert with whatever hints we have (can be null).
            return insertProfile(
                accessToken = accessToken,
                userId = userId,
                displayName = displayNameHint?.takeIf { it.isNotBlank() },
                avatarUrl = avatarUrlHint?.takeIf { it.isNotBlank() },
                migraineType = null
            )
        }

        // Row exists: only fill missing values.
        val needsDisplayName = existing.displayName.isNullOrBlank() && !displayNameHint.isNullOrBlank()
        val needsAvatarUrl = existing.avatarUrl.isNullOrBlank() && !avatarUrlHint.isNullOrBlank()

        if (!needsDisplayName && !needsAvatarUrl) return existing

        return patchProfile(
            accessToken = accessToken,
            userId = userId,
            displayName = if (needsDisplayName) displayNameHint!!.trim() else null,
            avatarUrl = if (needsAvatarUrl) avatarUrlHint!!.trim() else null,
            migraineType = null
        )
    }

    suspend fun getProfile(accessToken: String, userId: String): Profile? {
        val url =
            "$baseUrl/rest/v1/profiles?user_id=eq.$userId&select=user_id,display_name,avatar_url,migraine_type"

        val rows: List<ProfileRow> = client.get(url) {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
        }.body()

        val row = rows.firstOrNull() ?: return null
        return row.toDomain()
    }

    /**
     * Update any combination of fields in one request (used by Profile UI).
     * Pass null for fields you don't want to change.
     */
    suspend fun updateProfile(
        accessToken: String,
        userId: String,
        displayName: String? = null,
        avatarUrl: String? = null,
        migraineType: MigraineType? = null
    ): Profile {
        return patchProfile(
            accessToken = accessToken,
            userId = userId,
            displayName = displayName?.trim(),
            avatarUrl = avatarUrl?.trim(),
            migraineType = migraineType
        )
    }

    // ---- Wire format (PostgREST JSON) ----

    @Serializable
    private data class ProfileRow(
        @SerialName("user_id") val userId: String,
        @SerialName("display_name") val displayName: String? = null,
        @SerialName("avatar_url") val avatarUrl: String? = null,
        @SerialName("migraine_type") val migraineType: String? = null
    ) {
        fun toDomain(): Profile =
            Profile(
                userId = userId,
                displayName = displayName,
                avatarUrl = avatarUrl,
                migraineType = MigraineType.fromDbValue(migraineType)
            )
    }

    @Serializable
    private data class InsertProfileBody(
        @SerialName("user_id") val userId: String,
        @SerialName("display_name") val displayName: String? = null,
        @SerialName("avatar_url") val avatarUrl: String? = null,
        @SerialName("migraine_type") val migraineType: String? = null
    )

    @Serializable
    private data class PatchProfileBody(
        @SerialName("display_name") val displayName: String? = null,
        @SerialName("avatar_url") val avatarUrl: String? = null,
        @SerialName("migraine_type") val migraineType: String? = null
    )

    private suspend fun insertProfile(
        accessToken: String,
        userId: String,
        displayName: String?,
        avatarUrl: String?,
        migraineType: MigraineType?
    ): Profile {
        val url = "$baseUrl/rest/v1/profiles?select=user_id,display_name,avatar_url,migraine_type"

        val body = InsertProfileBody(
            userId = userId,
            displayName = displayName,
            avatarUrl = avatarUrl,
            migraineType = migraineType?.dbValue
        )

        val rows: List<ProfileRow> = client.post(url) {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()

        return rows.first().toDomain()
    }

    private suspend fun patchProfile(
        accessToken: String,
        userId: String,
        displayName: String?,
        avatarUrl: String?,
        migraineType: MigraineType?
    ): Profile {
        val url =
            "$baseUrl/rest/v1/profiles?user_id=eq.$userId&select=user_id,display_name,avatar_url,migraine_type"

        val body = PatchProfileBody(
            displayName = displayName,
            avatarUrl = avatarUrl,
            migraineType = migraineType?.dbValue
        )

        val rows: List<ProfileRow> = client.patch(url) {
            header("apikey", anonKey)
            header("Authorization", "Bearer $accessToken")
            header("Prefer", "return=representation")
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()

        return rows.first().toDomain()
    }
}
