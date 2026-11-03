package com.migraineme

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Blocked community members (App Store guideline 1.2, mirrored on Android so
 * both apps behave the same). A blocked person's forum posts and replies,
 * article replies and blog replies leave the blocker's feeds at once: every
 * community fetch runs its rows through [hide]. Rows live in
 * public.user_blocks, RLS per blocker.
 */
object BlockedUsers {
    private const val TAG = "BlockedUsers"
    private val supabaseUrl = BuildConfig.SUPABASE_URL
    private val supabaseKey = BuildConfig.SUPABASE_ANON_KEY
    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
    }

    @Serializable
    data class BlockedUserRow(
        val id: String,
        @SerialName("blocked_id") val blockedId: String,
        @SerialName("created_at") val createdAt: String? = null,
        val profiles: ForumProfile? = null
    )

    @Serializable
    private data class IdRow(@SerialName("blocked_id") val blockedId: String)

    // One fetch per session, dropped whenever the list changes. Community
    // screens call ids() on every load, so it has to be cheap after the first.
    @Volatile private var cache: Set<String>? = null

    fun invalidate() { cache = null }

    suspend fun ids(accessToken: String?): Set<String> {
        if (accessToken.isNullOrBlank()) return emptySet()
        cache?.let { return it }
        return try {
            val response = client.get("$supabaseUrl/rest/v1/user_blocks") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                parameter("select", "blocked_id")
            }
            if (response.status.value !in 200..299) emptySet()
            else response.body<List<IdRow>>().map { it.blockedId }.toSet().also { cache = it }
        } catch (e: Exception) {
            Log.e(TAG, "ids error", e); emptySet()
        }
    }

    /** Drops rows written by a blocked user. */
    suspend fun <T> hide(accessToken: String?, rows: List<T>, authorOf: (T) -> String?): List<T> {
        if (rows.isEmpty()) return rows
        val blocked = ids(accessToken)
        if (blocked.isEmpty()) return rows
        return rows.filter { authorOf(it) !in blocked }
    }

    suspend fun block(accessToken: String, userId: String): Boolean {
        return try {
            val response = client.post("$supabaseUrl/rest/v1/user_blocks") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                header("Prefer", "return=minimal")
                contentType(ContentType.Application.Json)
                setBody("{\"blocked_id\":\"$userId\"}")
            }
            invalidate()
            // 409 = already blocked, which is the state we wanted.
            response.status.value in 200..299 || response.status.value == 409
        } catch (e: Exception) {
            Log.e(TAG, "block error", e); false
        }
    }

    suspend fun unblock(accessToken: String, blockId: String): Boolean {
        return try {
            val response = client.delete("$supabaseUrl/rest/v1/user_blocks") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                parameter("id", "eq.$blockId")
            }
            invalidate()
            response.status.value in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "unblock error", e); false
        }
    }

    suspend fun list(accessToken: String): List<BlockedUserRow> {
        return try {
            val response = client.get("$supabaseUrl/rest/v1/user_blocks") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                parameter("select", "id,blocked_id,created_at,profiles!user_blocks_blocked_profile_fkey(display_name,avatar_url)")
                parameter("order", "created_at.desc")
            }
            if (response.status.value !in 200..299) emptyList() else response.body()
        } catch (e: Exception) {
            Log.e(TAG, "list error", e); emptyList()
        }
    }
}
