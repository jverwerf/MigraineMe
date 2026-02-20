package com.migraineme

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Classifies tyramine exposure risk for a food item.
 * 
 * Calls the classify-tyramine edge function, which:
 * 1. Checks a shared cache table first
 * 2. On miss, calls GPT-4o-mini for classification
 * 3. Caches the result for all future lookups
 * 
 * Returns: "none", "low", "medium", or "high"
 */
class TyramineClassifierService {

    companion object {
        private const val TAG = "TyramineClassifier"
        private val BASE_URL = BuildConfig.SUPABASE_URL
        private val ANON_KEY = BuildConfig.SUPABASE_ANON_KEY
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Classify tyramine exposure for a food item.
     * 
     * @param accessToken User's Supabase access token (for edge function auth)
     * @param foodName The food name to classify
     * @return Risk level: "none", "low", "medium", or "high"
     */
    fun classify(accessToken: String, foodName: String): String {
        if (foodName.isBlank()) {
            Log.w(TAG, "Empty food name, returning none")
            return "none"
        }

        return try {
            val url = "${BASE_URL.trimEnd('/')}/functions/v1/classify-tyramine"

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
                    return "none"
                }

                val responseBody = response.body?.string() ?: return "none"
                val json = JSONObject(responseBody)
                val risk = json.optString("risk", "none")
                val cached = json.optBoolean("cached", false)

                Log.d(TAG, "✅ $foodName → $risk (cached=$cached)")
                risk
            }
        } catch (e: Exception) {
            Log.e(TAG, "Classification failed for '$foodName': ${e.message}", e)
            "none" // Fail safe — don't block nutrition sync
        }
    }
}

