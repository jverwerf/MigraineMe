// FILE: app/src/main/java/com/migraineme/SupabaseNearbyService.kt
package com.migraineme

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Practices near the patient, for the lower half of the Guidance list.
 *
 * These are public listings, not people in the practitioner programme. Nothing
 * here can be connected to and nothing here can read a diary, which is why it
 * has its own service and its own table rather than arriving through
 * SupabasePractitionerService.
 *
 * The whole search, the caching and the API key live in the guidance-nearby
 * edge function. The app only asks.
 */
object SupabaseNearbyService {

    private val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val client = HttpClient(Android) {
        install(ContentNegotiation) { json(json) }
    }

    @Serializable
    data class Place(
        val place_id: String,
        /** "google" or "model". A model-found listing has no Google id, so the
         *  Directions button searches by name and address instead. */
        val source: String = "google",
        val name: String,
        val disciplines: List<String> = emptyList(),
        val types: List<String> = emptyList(),
        val address: String? = null,
        val city: String? = null,
        val lat: Double,
        val lng: Double,
        val phone: String? = null,
        val website: String? = null,
        val description: String? = null,
        val business_status: String? = null,
        val image_url: String? = null,
        val distance_km: Double? = null,
    ) {
        /** Two capitals for the tile that stands in for a photo until a
         *  practice claims its listing. */
        val initials: String
            get() = name.split(" ", "-", "&")
                .filter { it.isNotBlank() && it.first().isLetter() }
                .take(2)
                .joinToString("") { it.first().uppercase() }
                .ifEmpty { "?" }
    }

    @Serializable
    private data class Response(
        val places: List<Place> = emptyList(),
        val reason: String? = null,
    )

    /**
     * @param lat/[lng] only when the patient has told us their area by hand.
     *        Left null, the function uses the location the app already uploads
     *        daily, so this costs no permission prompt.
     * @return an empty list for any failure. A missing lower half of the list
     *         is a worse look than an error, but it is not worth breaking the
     *         practitioner cards above it.
     */
    suspend fun near(accessToken: String, lat: Double? = null, lng: Double? = null): List<Place> {
        val body = buildString {
            append("{")
            if (lat != null && lng != null) append("\"lat\":$lat,\"lng\":$lng")
            append("}")
        }
        return runCatching {
            val response = client.post("$baseUrl/functions/v1/guidance-nearby") {
                header("apikey", anonKey)
                header("Authorization", "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if (!response.status.isSuccess()) emptyList()
            else response.body<Response>().places
        }.getOrDefault(emptyList())
    }
}
