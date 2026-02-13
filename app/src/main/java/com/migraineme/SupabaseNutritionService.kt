package com.migraineme

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.LocalDate

/**
 * Service for uploading/deleting nutrition data to/from Supabase
 *
 * CRITICAL FIX: Now properly sets user_id to satisfy RLS policies
 */
class SupabaseNutritionService(private val context: Context) {

    companion object {
        private const val SUPABASE_URL = BuildConfig.SUPABASE_URL
        private const val SUPABASE_ANON_KEY = BuildConfig.SUPABASE_ANON_KEY
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * Upload a single nutrition record to Supabase
     * Includes health_connect_id for deletion tracking
     *
     * CRITICAL FIX: Now properly sets user_id to satisfy RLS policies
     */
    suspend fun uploadNutritionRecord(
        accessToken: String,
        nutrition: NutritionData,
        healthConnectId: String
    ) {
        val url = "$SUPABASE_URL/rest/v1/nutrition_records"

        // CRITICAL FIX: Extract user_id from the JWT access token
        val userId = JwtUtils.extractUserIdFromAccessToken(accessToken)
            ?: throw Exception("Failed to extract user_id from access token - cannot satisfy RLS policy")

        android.util.Log.d("SupabaseNutrition", "Uploading nutrition record for user: $userId, HC ID: ${healthConnectId.take(12)}...")

        val body = buildJsonObject {
            // CRITICAL: Set user_id for RLS policy
            put("user_id", userId)

            // Add health_connect_id for deletion tracking
            put("health_connect_id", healthConnectId)

            put("date", nutrition.date.toString())
            put("timestamp", nutrition.timestamp.toString())
            nutrition.endTimestamp?.let { put("end_timestamp", it.toString()) }

            // Food details
            nutrition.foodName?.let { put("food_name", it) }
            put("meal_type", nutrition.mealType)

            // Energy
            nutrition.calories?.let { put("calories", it) }

            // Macronutrients
            nutrition.protein?.let { put("protein", it) }
            nutrition.totalCarbohydrate?.let { put("total_carbohydrate", it) }
            nutrition.sugar?.let { put("sugar", it) }
            nutrition.dietaryFiber?.let { put("dietary_fiber", it) }
            nutrition.totalFat?.let { put("total_fat", it) }
            nutrition.saturatedFat?.let { put("saturated_fat", it) }
            nutrition.unsaturatedFat?.let { put("unsaturated_fat", it) }
            nutrition.monounsaturatedFat?.let { put("monounsaturated_fat", it) }
            nutrition.polyunsaturatedFat?.let { put("polyunsaturated_fat", it) }
            nutrition.transFat?.let { put("trans_fat", it) }
            nutrition.cholesterol?.let { put("cholesterol", it) }

            // Minerals
            nutrition.calcium?.let { put("calcium", it) }
            nutrition.chloride?.let { put("chloride", it) }
            nutrition.chromium?.let { put("chromium", it) }
            nutrition.copper?.let { put("copper", it) }
            nutrition.iodine?.let { put("iodine", it) }
            nutrition.iron?.let { put("iron", it) }
            nutrition.magnesium?.let { put("magnesium", it) }
            nutrition.manganese?.let { put("manganese", it) }
            nutrition.molybdenum?.let { put("molybdenum", it) }
            nutrition.phosphorus?.let { put("phosphorus", it) }
            nutrition.potassium?.let { put("potassium", it) }
            nutrition.selenium?.let { put("selenium", it) }
            nutrition.sodium?.let { put("sodium", it) }
            nutrition.zinc?.let { put("zinc", it) }

            // Vitamins
            nutrition.vitaminA?.let { put("vitamin_a", it) }
            nutrition.vitaminB6?.let { put("vitamin_b6", it) }
            nutrition.vitaminB12?.let { put("vitamin_b12", it) }
            nutrition.vitaminC?.let { put("vitamin_c", it) }
            nutrition.vitaminD?.let { put("vitamin_d", it) }
            nutrition.vitaminE?.let { put("vitamin_e", it) }
            nutrition.vitaminK?.let { put("vitamin_k", it) }
            nutrition.biotin?.let { put("biotin", it) }
            nutrition.folate?.let { put("folate", it) }
            nutrition.folicAcid?.let { put("folic_acid", it) }
            nutrition.niacin?.let { put("niacin", it) }
            nutrition.pantothenicAcid?.let { put("pantothenic_acid", it) }
            nutrition.riboflavin?.let { put("riboflavin", it) }
            nutrition.thiamin?.let { put("thiamin", it) }

            // Other
            nutrition.caffeine?.let { put("caffeine", it) }
            nutrition.tyramineExposure?.let { put("tyramine_exposure", it) }
            nutrition.alcoholExposure?.let { put("alcohol_exposure", it) }
            nutrition.glutenExposure?.let { put("gluten_exposure", it) }

            // Metadata
            put("source", nutrition.source)
            put("enriched", nutrition.enriched)
        }

        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("apikey", SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .header("Prefer", "resolution=merge-duplicates")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                android.util.Log.e("SupabaseNutrition", "Upload failed: ${response.code} $errorBody")
                throw Exception("Failed to upload nutrition: ${response.code} $errorBody")
            }
            android.util.Log.d("SupabaseNutrition", "✅ Successfully uploaded nutrition record")
        }
    }

    /**
     * Delete nutrition records by Health Connect IDs (batch operation)
     */
    suspend fun deleteNutritionRecordsByHealthConnectIds(
        accessToken: String,
        healthConnectIds: List<String>
    ) {
        if (healthConnectIds.isEmpty()) return

        // Build filter: health_connect_id.in.(id1,id2,id3)
        val idsFilter = healthConnectIds.joinToString(",")
        val url = "$SUPABASE_URL/rest/v1/nutrition_records?health_connect_id=in.($idsFilter)"

        val request = Request.Builder()
            .url(url)
            .delete()
            .header("apikey", SUPABASE_ANON_KEY)
            .header("Authorization", "Bearer $accessToken")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to delete nutrition records: ${response.code} ${response.body?.string()}")
            }
            android.util.Log.d("SupabaseNutrition", "Deleted ${healthConnectIds.size} records from Supabase")
        }
    }
}

/**
 * Data class representing nutrition data for upload
 */
data class NutritionData(
    // Timing
    val date: LocalDate,
    val timestamp: Instant,
    val endTimestamp: Instant?,

    // Food details
    val foodName: String?,
    val mealType: String,

    // Energy
    val calories: Double?,

    // Macronutrients (grams)
    val protein: Double?,
    val totalCarbohydrate: Double?,
    val sugar: Double?,
    val dietaryFiber: Double?,
    val totalFat: Double?,
    val saturatedFat: Double?,
    val unsaturatedFat: Double?,
    val monounsaturatedFat: Double?,
    val polyunsaturatedFat: Double?,
    val transFat: Double?,
    val cholesterol: Double?, // mg

    // Minerals (mg unless noted)
    val calcium: Double?,
    val chloride: Double?,
    val chromium: Double?, // mcg
    val copper: Double?,
    val iodine: Double?, // mcg
    val iron: Double?,
    val magnesium: Double?,
    val manganese: Double?,
    val molybdenum: Double?, // mcg
    val phosphorus: Double?,
    val potassium: Double?,
    val selenium: Double?, // mcg
    val sodium: Double?,
    val zinc: Double?,

    // Vitamins
    val vitaminA: Double?, // mcg RAE
    val vitaminB6: Double?, // mg
    val vitaminB12: Double?, // mcg
    val vitaminC: Double?, // mg
    val vitaminD: Double?, // mcg
    val vitaminE: Double?, // mg
    val vitaminK: Double?, // mcg
    val biotin: Double?, // mcg (B7)
    val folate: Double?, // mcg (B9)
    val folicAcid: Double?, // mcg
    val niacin: Double?, // mg (B3)
    val pantothenicAcid: Double?, // mg (B5)
    val riboflavin: Double?, // mg (B2)
    val thiamin: Double?, // mg (B1)

    // Other
    val caffeine: Double?, // mg
    val tyramineExposure: String? = null, // "none", "low", "medium", "high"
    val alcoholExposure: String? = null,  // "none", "low", "medium", "high"
    val glutenExposure: String? = null,   // "none", "low", "medium", "high"

    // Metadata
    val source: String,
    val enriched: Boolean
)

