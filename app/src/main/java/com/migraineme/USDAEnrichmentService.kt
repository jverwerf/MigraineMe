package com.migraineme

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Service to enrich nutrition data using USDA FoodData Central API
 * 
 * Takes incomplete nutrition data (e.g. from MyFitnessPal via Health Connect)
 * and fills in missing nutrients by searching the USDA database.
 */
class USDAEnrichmentService {

    companion object {
        private const val TAG = "USDAEnrichment"
        private val API_KEY = BuildConfig.USDA_API_KEY
        private const val BASE_URL = "https://api.nal.usda.gov/fdc/v1"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Enrich nutrition data by searching USDA database
     * 
     * @param nutrition The incomplete nutrition data from Health Connect
     * @return Enriched nutrition data with filled nutrients, or original if enrichment fails
     */
    suspend fun enrichNutrition(nutrition: NutritionData): NutritionData {
        val foodName = nutrition.foodName
        if (foodName.isNullOrBlank()) {
            Log.w(TAG, "No food name provided, cannot enrich")
            return nutrition.copy(enriched = true) // Mark as enriched even though we couldn't
        }

        return try {
            Log.d(TAG, "Searching USDA for: $foodName")
            
            // Search for the food
            val searchResults = searchFood(foodName)
            if (searchResults.foods.isEmpty()) {
                Log.w(TAG, "No USDA results for: $foodName")
                return nutrition.copy(enriched = true)
            }

            // Get the first result (best match)
            val fdcId = searchResults.foods.first().fdcId
            Log.d(TAG, "Found FDC ID: $fdcId")

            // Get detailed nutrient data
            val foodDetails = getFoodDetails(fdcId)
            
            // Merge USDA nutrients with existing data
            val enriched = mergeNutrients(nutrition, foodDetails)
            
            Log.d(TAG, "✅ Successfully enriched: $foodName")
            enriched.copy(enriched = true)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enrich $foodName: ${e.message}", e)
            // Return original data marked as enriched (best effort)
            nutrition.copy(enriched = true)
        }
    }

    /**
     * Search USDA database for a food by name
     */
    private fun searchFood(query: String): USDASearchResponse {
        val url = "$BASE_URL/foods/search?api_key=$API_KEY&query=${query.replace(" ", "%20")}&pageSize=1"
        
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("USDA search failed: ${response.code}")
            }
            
            val body = response.body?.string() ?: throw Exception("Empty response")
            return json.decodeFromString(body)
        }
    }

    /**
     * Get detailed nutrient information for a food by FDC ID
     */
    private fun getFoodDetails(fdcId: Int): USDAFoodDetails {
        val url = "$BASE_URL/food/$fdcId?api_key=$API_KEY"
        
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("USDA details failed: ${response.code}")
            }
            
            val body = response.body?.string() ?: throw Exception("Empty response")
            return json.decodeFromString(body)
        }
    }

    /**
     * Merge USDA nutrients with existing nutrition data
     * Only fills in missing fields, preserves existing data
     */
    private fun mergeNutrients(original: NutritionData, usda: USDAFoodDetails): NutritionData {
        // Create a map of nutrient ID to value from USDA
        val nutrients = usda.foodNutrients.associate { 
            it.nutrient.id to it.amount 
        }

        return original.copy(
            // Only fill in if missing (use original if present)
            calories = original.calories ?: getNutrient(nutrients, 1008),
            
            // Macronutrients
            protein = original.protein ?: getNutrient(nutrients, 1003),
            totalCarbohydrate = original.totalCarbohydrate ?: getNutrient(nutrients, 1005),
            sugar = original.sugar ?: getNutrient(nutrients, 2000),
            dietaryFiber = original.dietaryFiber ?: getNutrient(nutrients, 1079),
            totalFat = original.totalFat ?: getNutrient(nutrients, 1004),
            saturatedFat = original.saturatedFat ?: getNutrient(nutrients, 1258),
            monounsaturatedFat = original.monounsaturatedFat ?: getNutrient(nutrients, 1292),
            polyunsaturatedFat = original.polyunsaturatedFat ?: getNutrient(nutrients, 1293),
            transFat = original.transFat ?: getNutrient(nutrients, 1257),
            cholesterol = original.cholesterol ?: getNutrient(nutrients, 1253),
            
            // Minerals (mg)
            calcium = original.calcium ?: getNutrient(nutrients, 1087),
            iron = original.iron ?: getNutrient(nutrients, 1089),
            magnesium = original.magnesium ?: getNutrient(nutrients, 1090),
            phosphorus = original.phosphorus ?: getNutrient(nutrients, 1091),
            potassium = original.potassium ?: getNutrient(nutrients, 1092),
            sodium = original.sodium ?: getNutrient(nutrients, 1093),
            zinc = original.zinc ?: getNutrient(nutrients, 1095),
            copper = original.copper ?: getNutrient(nutrients, 1098),
            manganese = original.manganese ?: getNutrient(nutrients, 1101),
            selenium = original.selenium ?: getNutrient(nutrients, 1103)?.div(1000), // Convert mcg to mg
            
            // Vitamins
            vitaminA = original.vitaminA ?: getNutrient(nutrients, 1106),
            vitaminC = original.vitaminC ?: getNutrient(nutrients, 1162),
            vitaminD = original.vitaminD ?: getNutrient(nutrients, 1114),
            vitaminE = original.vitaminE ?: getNutrient(nutrients, 1109),
            vitaminK = original.vitaminK ?: getNutrient(nutrients, 1185),
            thiamin = original.thiamin ?: getNutrient(nutrients, 1165),
            riboflavin = original.riboflavin ?: getNutrient(nutrients, 1166),
            niacin = original.niacin ?: getNutrient(nutrients, 1167),
            vitaminB6 = original.vitaminB6 ?: getNutrient(nutrients, 1175),
            folate = original.folate ?: getNutrient(nutrients, 1177),
            vitaminB12 = original.vitaminB12 ?: getNutrient(nutrients, 1178),
            pantothenicAcid = original.pantothenicAcid ?: getNutrient(nutrients, 1170),
            
            // Other
            caffeine = original.caffeine ?: getNutrient(nutrients, 1057)
        )
    }

    /**
     * Helper to safely get a nutrient value by ID
     */
    private fun getNutrient(nutrients: Map<Int, Double>, nutrientId: Int): Double? {
        return nutrients[nutrientId]?.takeIf { it > 0 }
    }

    /**
     * Check if nutrition data needs enrichment
     * Returns true if critical nutrients are missing
     */
    fun needsEnrichment(nutrition: NutritionData): Boolean {
        // If already enriched, skip
        if (nutrition.enriched) return false
        
        // If no food name, can't enrich
        if (nutrition.foodName.isNullOrBlank()) return false
        
        // Check if missing key micronutrients (vitamins/minerals)
        val missingMicronutrients = nutrition.vitaminA == null &&
                nutrition.vitaminC == null &&
                nutrition.calcium == null &&
                nutrition.iron == null
        
        return missingMicronutrients
    }
}

// USDA API Response Models

@Serializable
data class USDASearchResponse(
    val foods: List<USDASearchFood>
)

@Serializable
data class USDASearchFood(
    val fdcId: Int,
    val description: String
)

@Serializable
data class USDAFoodDetails(
    val fdcId: Int,
    val description: String,
    val foodNutrients: List<USDAFoodNutrient>
)

@Serializable
data class USDAFoodNutrient(
    val nutrient: USDANutrient,
    val amount: Double
)

@Serializable
data class USDANutrient(
    val id: Int,
    val name: String,
    val unitName: String
)

