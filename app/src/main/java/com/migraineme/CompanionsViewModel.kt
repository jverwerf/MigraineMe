// app/src/main/java/com/migraineme/CompanionsViewModel.kt
package com.migraineme

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "CompanionsVM"

// ── Data models ──

@Serializable
data class CompanionRow(
    val id: String,
    val name: String,
    val slug: String,
    val subtitle: String = "",
    @SerialName("migraine_type") val migraineType: String = "",
    val triggers: List<String> = emptyList(),
    val interests: List<String> = emptyList(),
    val personality: String = "",
    @SerialName("tone_guide") val toneGuide: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("is_active") val isActive: Boolean = true
)

@Serializable
data class SubscriptionRow(
    val id: String,
    @SerialName("companion_id") val companionId: String
)

data class CompanionsState(
    val companions: List<CompanionRow> = emptyList(),
    val subscribedIds: Set<String> = emptySet(),  // companion IDs the user is subscribed to
    val loading: Boolean = false,
    val error: String? = null
)

class CompanionsViewModel : ViewModel() {

    private val supabaseUrl = BuildConfig.SUPABASE_URL
    private val supabaseKey = BuildConfig.SUPABASE_ANON_KEY

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true })
        }
    }

    private val _state = MutableStateFlow(CompanionsState())
    val state: StateFlow<CompanionsState> = _state

    fun loadAll(accessToken: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            try {
                // Fetch companions
                val companions: List<CompanionRow> = client.get("$supabaseUrl/rest/v1/ai_companions") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    header("apikey", supabaseKey)
                    parameter("is_active", "eq.true")
                    parameter("order", "created_at.asc")
                }.body()

                // Fetch user's subscriptions
                val subs: List<SubscriptionRow> = client.get("$supabaseUrl/rest/v1/ai_companion_subscriptions") {
                    header(HttpHeaders.Authorization, "Bearer $accessToken")
                    header("apikey", supabaseKey)
                    parameter("select", "id,companion_id")
                }.body()

                _state.value = _state.value.copy(
                    companions = companions,
                    subscribedIds = subs.map { it.companionId }.toSet(),
                    loading = false,
                    error = null
                )
            } catch (e: Exception) {
                Log.e(TAG, "loadAll error: ${e::class.simpleName}: ${e.message}", e)
                _state.value = _state.value.copy(loading = false, error = e.message)
            }
        }
    }

    fun toggleSubscription(accessToken: String, companionId: String) {
        viewModelScope.launch {
            val current = _state.value.subscribedIds

            if (companionId in current) {
                // Optimistic remove
                _state.value = _state.value.copy(subscribedIds = current - companionId)
                try {
                    client.delete("$supabaseUrl/rest/v1/ai_companion_subscriptions") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        header("apikey", supabaseKey)
                        parameter("companion_id", "eq.$companionId")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "unsubscribe error", e)
                    _state.value = _state.value.copy(subscribedIds = current)
                }
            } else {
                // Optimistic add
                _state.value = _state.value.copy(subscribedIds = current + companionId)
                try {
                    client.post("$supabaseUrl/rest/v1/ai_companion_subscriptions") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        header("apikey", supabaseKey)
                        contentType(ContentType.Application.Json)
                        setBody(mapOf("companion_id" to companionId))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "subscribe error", e)
                    _state.value = _state.value.copy(subscribedIds = current)
                }
            }
        }
    }

    /**
     * Subscribe to multiple companions at once (used during onboarding).
     */
    fun subscribeToMultiple(accessToken: String, companionIds: Set<String>) {
        viewModelScope.launch {
            val current = _state.value.subscribedIds
            _state.value = _state.value.copy(subscribedIds = current + companionIds)

            for (id in companionIds) {
                if (id in current) continue // already subscribed
                try {
                    client.post("$supabaseUrl/rest/v1/ai_companion_subscriptions") {
                        header(HttpHeaders.Authorization, "Bearer $accessToken")
                        header("apikey", supabaseKey)
                        contentType(ContentType.Application.Json)
                        setBody(mapOf("companion_id" to id))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "bulk subscribe error for $id", e)
                }
            }
        }
    }

}
