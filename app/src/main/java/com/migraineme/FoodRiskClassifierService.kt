package com.migraineme

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Result of food risk classification.
 * Each field is "none", "low", "medium", or "high".
 */
data class FoodRiskResult(
    val tyramine: String = "none",
    val alcohol: String = "none",
    val gluten: String = "none",
    val cached: Boolean = false
)

/**
 * Classifies food risks (tyramine, alcohol, gluten) via edge function.
 *
 * Calls classify-food-risks edge function, which:
 * 1. Checks a shared cache table first
 * 2. On miss, calls GPT-4o-mini for all three in one call
 * 3. Caches the result for all future lookups
 */
class FoodRiskClassifierService {

    companion object {
        private const val TAG = "FoodRiskClassifier"
        private val BASE_URL = BuildConfig.SUPABASE_URL
        private val ANON_KEY = BuildConfig.SUPABASE_ANON_KEY
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Classify all food risks for a food item.
     *
     * @param accessToken User's Supabase access token
     * @param foodName The food name to classify
     * @return FoodRiskResult with tyramine, alcohol, gluten levels
     */
    fun classify(accessToken: String, foodName: String): FoodRiskResult {
        if (foodName.isBlank()) {
            Log.w(TAG, "Empty food name, returning defaults")
            return FoodRiskResult()
        }

        return try {
            val url = "${BASE_URL.trimEnd('/')}/functions/v1/classify-food-risks"

            val body = JSONObject().apply {
                put("food_name", foodName)
            }

            val request = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $accessToken")
                .header("apikey", ANON_KEY)
                .header("Content-Type", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Edge function failed: ${response.code}")
                    return FoodRiskResult()
                }

                val responseBody = response.body?.string() ?: return FoodRiskResult()
                val json = JSONObject(responseBody)

                val result = FoodRiskResult(
                    tyramine = json.optString("tyramine", "none"),
                    alcohol = json.optString("alcohol", "none"),
                    gluten = json.optString("gluten", "none"),
                    cached = json.optBoolean("cached", false)
                )

                Log.d(TAG, "✅ $foodName → T:${result.tyramine} A:${result.alcohol} G:${result.gluten} (cached=${result.cached})")
                result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Classification failed for '$foodName': ${e.message}", e)
            FoodRiskResult()
        }
    }
}
