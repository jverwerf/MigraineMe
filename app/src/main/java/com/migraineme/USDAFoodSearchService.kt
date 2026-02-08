package com.migraineme

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Service for searching USDA FoodData Central and adding foods to nutrition_records
 */
class USDAFoodSearchService(private val context: Context) {

    companion object {
        private const val TAG = "USDAFoodSearch"
        private const val API_KEY = BuildConfig.USDA_API_KEY
        private const val BASE_URL = "https://api.nal.usda.gov/fdc/v1"

        private fun dbl(obj: org.json.JSONObject, key: String): Double? =
            obj.optDouble(key).takeIf { !it.isNaN() }

        fun parseNutritionLogItem(obj: org.json.JSONObject): NutritionLogItem = NutritionLogItem(
            id = obj.getString("id"),
            foodName = obj.optString("food_name", "Unknown"),
            mealType = obj.optString("meal_type", "unknown"),
            calories = dbl(obj, "calories"),
            protein = dbl(obj, "protein"),
            totalCarbohydrate = dbl(obj, "total_carbohydrate"),
            totalFat = dbl(obj, "total_fat"),
            sugar = dbl(obj, "sugar"),
            sodium = dbl(obj, "sodium"),
            caffeine = dbl(obj, "caffeine"),
            dietaryFiber = dbl(obj, "dietary_fiber"),
            cholesterol = dbl(obj, "cholesterol"),
            saturatedFat = dbl(obj, "saturated_fat"),
            unsaturatedFat = dbl(obj, "unsaturated_fat"),
            transFat = dbl(obj, "trans_fat"),
            potassium = dbl(obj, "potassium"),
            calcium = dbl(obj, "calcium"),
            iron = dbl(obj, "iron"),
            magnesium = dbl(obj, "magnesium"),
            zinc = dbl(obj, "zinc"),
            selenium = dbl(obj, "selenium"),
            phosphorus = dbl(obj, "phosphorus"),
            copper = dbl(obj, "copper"),
            manganese = dbl(obj, "manganese"),
            vitaminA = dbl(obj, "vitamin_a"),
            vitaminC = dbl(obj, "vitamin_c"),
            vitaminD = dbl(obj, "vitamin_d"),
            vitaminE = dbl(obj, "vitamin_e"),
            vitaminK = dbl(obj, "vitamin_k"),
            vitaminB6 = dbl(obj, "vitamin_b6"),
            vitaminB12 = dbl(obj, "vitamin_b12"),
            thiamin = dbl(obj, "thiamin"),
            riboflavin = dbl(obj, "riboflavin"),
            niacin = dbl(obj, "niacin"),
            folate = dbl(obj, "folate"),
            biotin = dbl(obj, "biotin"),
            pantothenicAcid = dbl(obj, "pantothenic_acid"),
            tyramineExposure = obj.optString("tyramine_exposure", null),
            timestamp = obj.getString("timestamp"),
            source = obj.optString("source", "")
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Search USDA database for foods matching query
     * Prioritizes generic/foundation foods over branded products
     */
    suspend fun searchFoods(query: String): List<USDAFoodSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        
        try {
            // dataType filter: Foundation, SR Legacy = generic foods; Branded = company products
            // We search with Foundation and SR Legacy first for generic results
            val url = "$BASE_URL/foods/search?api_key=$API_KEY" +
                "&query=${query.replace(" ", "%20")}" +
                "&pageSize=25" +
                "&dataType=Foundation,SR%20Legacy,Survey%20(FNDDS)"
            
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "USDA search failed: ${response.code}")
                    return@withContext emptyList()
                }
                
                val body = response.body?.string() ?: return@withContext emptyList()
                val searchResponse = json.decodeFromString<USDASearchResponseFull>(body)
                
                // Sort results: Foundation first, then SR Legacy, then others
                val sortedFoods = searchResponse.foods.sortedBy { food ->
                    when (food.dataType) {
                        "Foundation" -> 0
                        "SR Legacy" -> 1
                        "Survey (FNDDS)" -> 2
                        else -> 3
                    }
                }
                
                sortedFoods.take(20).map { food ->
                    USDAFoodSearchResult(
                        fdcId = food.fdcId,
                        description = food.description,
                        brandName = food.brandName,
                        servingSize = food.servingSize,
                        servingSizeUnit = food.servingSizeUnit,
                        calories = food.foodNutrients.find { it.nutrientId == 1008 }?.value
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Get full nutrient details for a food
     */
    suspend fun getFoodDetails(fdcId: Int): USDAFoodDetailsFull? = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/food/$fdcId?api_key=$API_KEY"
            
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "USDA details failed: ${response.code}")
                    return@withContext null
                }
                
                val body = response.body?.string() ?: return@withContext null
                json.decodeFromString<USDAFoodDetailsFull>(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get details failed: ${e.message}", e)
            null
        }
    }

    /**
     * Add food to nutrition_records using already-loaded details (no USDA call needed)
     */
    suspend fun addFoodFromDetails(
        foodDetails: USDAFoodDetailsFull,
        foodName: String,
        mealType: String,
        servings: Double = 1.0
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        try {
            val token = SessionStore.readAccessToken(context) ?: run {
                Log.e(TAG, "No access token")
                return@withContext Pair(false, "Not logged in")
            }
            val userId = SessionStore.readUserId(context) ?: run {
                Log.e(TAG, "No user ID")
                return@withContext Pair(false, "No user ID")
            }
            
            Log.d(TAG, "Adding food: $foodName for user: $userId")
            
            // Build nutrients map from already-loaded USDA data
            val nutrients = foodDetails.foodNutrients.associate { 
                it.nutrient.id to (it.amount ?: 0.0)
            }
            
            Log.d(TAG, "Found ${nutrients.size} nutrients")
            
            // Helper to get nutrient value multiplied by servings
            fun getNutrient(id: Int): Double? = nutrients[id]?.takeIf { it > 0 }?.times(servings)
            
            // Create nutrition record JSON
            val today = java.time.LocalDate.now().toString()
            val nutritionRecord = buildString {
                append("{")
                append("\"id\":\"${UUID.randomUUID()}\",")
                append("\"user_id\":\"$userId\",")
                append("\"date\":\"$today\",")
                append("\"timestamp\":\"${Instant.now()}\",")
                append("\"food_name\":\"${foodName.replace("\"", "\\\"").replace("\n", " ")}\",")
                append("\"meal_type\":\"$mealType\",")
                append("\"source\":\"manual_usda\",")
                
                // Macros
                getNutrient(1008)?.let { append("\"calories\":$it,") }
                getNutrient(1003)?.let { append("\"protein\":$it,") }
                getNutrient(1005)?.let { append("\"total_carbohydrate\":$it,") }
                getNutrient(1004)?.let { append("\"total_fat\":$it,") }
                getNutrient(1079)?.let { append("\"dietary_fiber\":$it,") }
                getNutrient(2000)?.let { append("\"sugar\":$it,") }
                getNutrient(1258)?.let { append("\"saturated_fat\":$it,") }
                getNutrient(1292)?.let { append("\"monounsaturated_fat\":$it,") }
                getNutrient(1293)?.let { append("\"polyunsaturated_fat\":$it,") }
                getNutrient(1257)?.let { append("\"trans_fat\":$it,") }
                getNutrient(1253)?.let { append("\"cholesterol\":$it,") }
                
                // Minerals
                getNutrient(1087)?.let { append("\"calcium\":$it,") }
                getNutrient(1089)?.let { append("\"iron\":$it,") }
                getNutrient(1090)?.let { append("\"magnesium\":$it,") }
                getNutrient(1091)?.let { append("\"phosphorus\":$it,") }
                getNutrient(1092)?.let { append("\"potassium\":$it,") }
                getNutrient(1093)?.let { append("\"sodium\":$it,") }
                getNutrient(1095)?.let { append("\"zinc\":$it,") }
                getNutrient(1098)?.let { append("\"copper\":$it,") }
                getNutrient(1101)?.let { append("\"manganese\":$it,") }
                getNutrient(1103)?.let { append("\"selenium\":$it,") }
                
                // Vitamins
                getNutrient(1106)?.let { append("\"vitamin_a\":$it,") }
                getNutrient(1162)?.let { append("\"vitamin_c\":$it,") }
                getNutrient(1114)?.let { append("\"vitamin_d\":$it,") }
                getNutrient(1109)?.let { append("\"vitamin_e\":$it,") }
                getNutrient(1185)?.let { append("\"vitamin_k\":$it,") }
                getNutrient(1165)?.let { append("\"thiamin\":$it,") }
                getNutrient(1166)?.let { append("\"riboflavin\":$it,") }
                getNutrient(1167)?.let { append("\"niacin\":$it,") }
                getNutrient(1175)?.let { append("\"vitamin_b6\":$it,") }
                getNutrient(1177)?.let { append("\"folate\":$it,") }
                getNutrient(1178)?.let { append("\"vitamin_b12\":$it,") }
                getNutrient(1170)?.let { append("\"pantothenic_acid\":$it,") }
                getNutrient(1176)?.let { append("\"biotin\":$it,") }
                
                // Caffeine
                getNutrient(1057)?.let { append("\"caffeine\":$it,") }
                
                // Tyramine exposure (classify via edge function)
                try {
                    val classifier = TyramineClassifierService()
                    val risk = classifier.classify(token, foodName)
                    if (risk != "none") append("\"tyramine_exposure\":\"$risk\",")
                } catch (_: Exception) {}
                
                // Remove trailing comma and close
                if (endsWith(",")) deleteCharAt(length - 1)
                append("}")
            }
            
            Log.d(TAG, "Inserting nutrition record: $nutritionRecord")
            
            // Insert to Supabase
            val supabaseUrl = "${BuildConfig.SUPABASE_URL}/rest/v1/nutrition_records"
            
            Log.d(TAG, "Posting to: $supabaseUrl")
            
            val insertRequest = Request.Builder()
                .url(supabaseUrl)
                .post(nutritionRecord.toRequestBody("application/json".toMediaType()))
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build()

            httpClient.newCall(insertRequest).execute().use { response ->
                val responseBody = response.body?.string()
                if (response.isSuccessful) {
                    Log.d(TAG, "✅ Food added successfully: $foodName")
                    return@withContext Pair(true, null)
                } else {
                    Log.e(TAG, "Failed to insert: ${response.code} - $responseBody")
                    return@withContext Pair(false, "Error ${response.code}: $responseBody")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add food: ${e.message}", e)
            Pair(false, e.message ?: "Unknown error")
        }
    }

    /**
     * Get full nutrient details for a food and add to nutrition_records
     */
    suspend fun addFoodToNutrition(
        fdcId: Int,
        foodName: String,
        mealType: String,
        servings: Double = 1.0
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = SessionStore.readAccessToken(context) ?: return@withContext false
            val userId = SessionStore.readUserId(context) ?: return@withContext false
            
            // Get detailed nutrient data from USDA
            val url = "$BASE_URL/food/$fdcId?api_key=$API_KEY"
            
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val foodDetails = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "USDA details failed: ${response.code}")
                    return@withContext false
                }
                
                val body = response.body?.string() ?: return@withContext false
                json.decodeFromString<USDAFoodDetailsFull>(body)
            }
            
            // Build nutrients map from USDA data
            val nutrients = foodDetails.foodNutrients.associate { 
                it.nutrient.id to (it.amount ?: 0.0)
            }
            
            // Helper to get nutrient value multiplied by servings
            fun getNutrient(id: Int): Double? = nutrients[id]?.takeIf { it > 0 }?.times(servings)
            
            // Create nutrition record JSON
            val nutritionRecord = buildString {
                append("{")
                append("\"id\":\"${UUID.randomUUID()}\",")
                append("\"user_id\":\"$userId\",")
                append("\"timestamp\":\"${Instant.now()}\",")
                append("\"food_name\":\"${foodName.replace("\"", "\\\"")}\",")
                append("\"meal_type\":\"$mealType\",")
                append("\"source\":\"manual_usda\",")
                
                // Macros
                getNutrient(1008)?.let { append("\"calories\":$it,") }
                getNutrient(1003)?.let { append("\"protein\":$it,") }
                getNutrient(1005)?.let { append("\"total_carbohydrate\":$it,") }
                getNutrient(1004)?.let { append("\"total_fat\":$it,") }
                getNutrient(1079)?.let { append("\"dietary_fiber\":$it,") }
                getNutrient(2000)?.let { append("\"sugar\":$it,") }
                getNutrient(1258)?.let { append("\"saturated_fat\":$it,") }
                getNutrient(1292)?.let { append("\"monounsaturated_fat\":$it,") }
                getNutrient(1293)?.let { append("\"polyunsaturated_fat\":$it,") }
                getNutrient(1257)?.let { append("\"trans_fat\":$it,") }
                getNutrient(1253)?.let { append("\"cholesterol\":$it,") }
                
                // Minerals
                getNutrient(1087)?.let { append("\"calcium\":$it,") }
                getNutrient(1089)?.let { append("\"iron\":$it,") }
                getNutrient(1090)?.let { append("\"magnesium\":$it,") }
                getNutrient(1091)?.let { append("\"phosphorus\":$it,") }
                getNutrient(1092)?.let { append("\"potassium\":$it,") }
                getNutrient(1093)?.let { append("\"sodium\":$it,") }
                getNutrient(1095)?.let { append("\"zinc\":$it,") }
                getNutrient(1098)?.let { append("\"copper\":$it,") }
                getNutrient(1101)?.let { append("\"manganese\":$it,") }
                getNutrient(1103)?.let { append("\"selenium\":$it,") }
                
                // Vitamins
                getNutrient(1106)?.let { append("\"vitamin_a\":$it,") }
                getNutrient(1162)?.let { append("\"vitamin_c\":$it,") }
                getNutrient(1114)?.let { append("\"vitamin_d\":$it,") }
                getNutrient(1109)?.let { append("\"vitamin_e\":$it,") }
                getNutrient(1185)?.let { append("\"vitamin_k\":$it,") }
                getNutrient(1165)?.let { append("\"thiamin\":$it,") }
                getNutrient(1166)?.let { append("\"riboflavin\":$it,") }
                getNutrient(1167)?.let { append("\"niacin\":$it,") }
                getNutrient(1175)?.let { append("\"vitamin_b6\":$it,") }
                getNutrient(1177)?.let { append("\"folate\":$it,") }
                getNutrient(1178)?.let { append("\"vitamin_b12\":$it,") }
                getNutrient(1170)?.let { append("\"pantothenic_acid\":$it,") }
                getNutrient(1176)?.let { append("\"biotin\":$it,") }
                
                // Caffeine
                getNutrient(1057)?.let { append("\"caffeine\":$it,") }
                
                // Tyramine exposure (classify via edge function)
                try {
                    val classifier = TyramineClassifierService()
                    val risk = classifier.classify(token, foodName)
                    if (risk != "none") append("\"tyramine_exposure\":\"$risk\",")
                } catch (_: Exception) {}
                
                // Remove trailing comma and close
                if (endsWith(",")) deleteCharAt(length - 1)
                append("}")
            }
            
            Log.d(TAG, "Inserting nutrition record: $nutritionRecord")
            
            // Upsert to Supabase
            val supabaseUrl = "${BuildConfig.SUPABASE_URL}/rest/v1/nutrition_records"
            
            val upsertRequest = Request.Builder()
                .url(supabaseUrl)
                .post(nutritionRecord.toRequestBody("application/json".toMediaType()))
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build()

            httpClient.newCall(upsertRequest).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "✅ Food added successfully: $foodName")
                    return@withContext true
                } else {
                    Log.e(TAG, "Failed to insert: ${response.code} - ${response.body?.string()}")
                    return@withContext false
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add food: ${e.message}", e)
            false
        }
    }
    /**
     * Get today's nutrition items
     */
    suspend fun getTodayNutritionItems(): List<NutritionLogItem> = withContext(Dispatchers.IO) {
        try {
            val token = SessionStore.readAccessToken(context) ?: return@withContext emptyList()
            val userId = SessionStore.readUserId(context) ?: return@withContext emptyList()
            
            val today = java.time.LocalDate.now().toString()
            val todayStart = "${today}T00:00:00Z"
            val todayEnd = "${today}T23:59:59Z"
            
            val url = "${BuildConfig.SUPABASE_URL}/rest/v1/nutrition_records?" +
                "user_id=eq.$userId&timestamp=gte.$todayStart&timestamp=lte.$todayEnd" +
                "&select=*&order=timestamp.desc"
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to get today's items: ${response.code}")
                    return@withContext emptyList()
                }
                
                val body = response.body?.string() ?: return@withContext emptyList()
                val arr = org.json.JSONArray(body)
                
                val items = mutableListOf<NutritionLogItem>()
                for (i in 0 until arr.length()) {
                    items.add(parseNutritionLogItem(arr.getJSONObject(i)))
                }
                items
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get today's items: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Get nutrition items for a specific date
     */
    suspend fun getNutritionItemsForDate(date: String): List<NutritionLogItem> = withContext(Dispatchers.IO) {
        try {
            val token = SessionStore.readAccessToken(context) ?: return@withContext emptyList()
            val userId = SessionStore.readUserId(context) ?: return@withContext emptyList()
            
            val dateStart = "${date}T00:00:00Z"
            val dateEnd = "${date}T23:59:59Z"
            
            val url = "${BuildConfig.SUPABASE_URL}/rest/v1/nutrition_records?" +
                "user_id=eq.$userId&timestamp=gte.$dateStart&timestamp=lte.$dateEnd" +
                "&select=*&order=timestamp.desc"
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to get items for date: ${response.code}")
                    return@withContext emptyList()
                }
                
                val body = response.body?.string() ?: return@withContext emptyList()
                val arr = org.json.JSONArray(body)
                
                val items = mutableListOf<NutritionLogItem>()
                for (i in 0 until arr.length()) {
                    items.add(parseNutritionLogItem(arr.getJSONObject(i)))
                }
                items
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get items for date: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Delete a nutrition item (only manual entries)
     */
    suspend fun deleteNutritionItem(id: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = SessionStore.readAccessToken(context) ?: return@withContext false
            val userId = SessionStore.readUserId(context) ?: return@withContext false
            
            val url = "${BuildConfig.SUPABASE_URL}/rest/v1/nutrition_records?" +
                "id=eq.$id&user_id=eq.$userId&source=eq.manual_usda"
            
            val request = Request.Builder()
                .url(url)
                .delete()
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "✅ Deleted nutrition item: $id")
                    return@withContext true
                } else {
                    Log.e(TAG, "Failed to delete: ${response.code}")
                    return@withContext false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete item: ${e.message}", e)
            false
        }
    }
    
    /**
     * Update a nutrition item (only manual entries) - meal type and servings
     * When servings changes, we scale all nutrient values proportionally
     */
    suspend fun updateNutritionItem(
        id: String,
        mealType: String,
        servingsMultiplier: Double? = null // e.g., 2.0 means double all values
    ): Boolean = withContext(Dispatchers.IO) {
        // ... existing code
        try {
            val token = SessionStore.readAccessToken(context) ?: return@withContext false
            val userId = SessionStore.readUserId(context) ?: return@withContext false
            
            // If just updating meal type
            if (servingsMultiplier == null || servingsMultiplier == 1.0) {
                val updateJson = """{"meal_type":"$mealType"}"""
                
                val url = "${BuildConfig.SUPABASE_URL}/rest/v1/nutrition_records?" +
                    "id=eq.$id&user_id=eq.$userId&source=eq.manual_usda"
                
                val request = Request.Builder()
                    .url(url)
                    .patch(updateJson.toRequestBody("application/json".toMediaType()))
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "✅ Updated nutrition item: $id")
                        return@withContext true
                    } else {
                        Log.e(TAG, "Failed to update: ${response.code} - ${response.body?.string()}")
                        return@withContext false
                    }
                }
            } else {
                // Need to fetch current values and scale them
                val getUrl = "${BuildConfig.SUPABASE_URL}/rest/v1/nutrition_records?id=eq.$id&user_id=eq.$userId"
                
                val getRequest = Request.Builder()
                    .url(getUrl)
                    .get()
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                
                val currentData = httpClient.newCall(getRequest).execute().use { response ->
                    if (!response.isSuccessful) return@withContext false
                    val body = response.body?.string() ?: return@withContext false
                    val arr = org.json.JSONArray(body)
                    if (arr.length() == 0) return@withContext false
                    arr.getJSONObject(0)
                }
                
                // Build update JSON with scaled values
                val updateJson = buildString {
                    append("{")
                    append("\"meal_type\":\"$mealType\"")
                    
                    // Scale all nutrient columns
                    val nutrientColumns = listOf(
                        "calories", "protein", "total_carbohydrate", "total_fat",
                        "dietary_fiber", "sugar", "saturated_fat", "monounsaturated_fat",
                        "polyunsaturated_fat", "trans_fat", "cholesterol",
                        "calcium", "iron", "magnesium", "phosphorus", "potassium",
                        "sodium", "zinc", "copper", "manganese", "selenium",
                        "vitamin_a", "vitamin_c", "vitamin_d", "vitamin_e", "vitamin_k",
                        "thiamin", "riboflavin", "niacin", "vitamin_b6", "folate",
                        "vitamin_b12", "pantothenic_acid", "biotin", "caffeine"
                    )
                    
                    nutrientColumns.forEach { col ->
                        val currentValue = currentData.optDouble(col, Double.NaN)
                        if (!currentValue.isNaN()) {
                            val newValue = currentValue * servingsMultiplier
                            append(",\"$col\":$newValue")
                        }
                    }
                    
                    append("}")
                }
                
                val url = "${BuildConfig.SUPABASE_URL}/rest/v1/nutrition_records?" +
                    "id=eq.$id&user_id=eq.$userId&source=eq.manual_usda"
                
                val request = Request.Builder()
                    .url(url)
                    .patch(updateJson.toRequestBody("application/json".toMediaType()))
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d(TAG, "✅ Updated nutrition item with scaled values: $id")
                        return@withContext true
                    } else {
                        Log.e(TAG, "Failed to update: ${response.code} - ${response.body?.string()}")
                        return@withContext false
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update item: ${e.message}", e)
            false
        }
    }
    
    /**
     * Get ALL nutrition history and calculate normalization in memory
     */
    suspend fun getNutritionHistory(days: Int = 14, endDateOverride: java.time.LocalDate? = null): NutritionHistoryResult = withContext(Dispatchers.IO) {
        try {
            val token = SessionStore.readAccessToken(context) ?: return@withContext NutritionHistoryResult()
            val userId = SessionStore.readUserId(context) ?: return@withContext NutritionHistoryResult()
            
            // Get ALL data from nutrition_daily - one query, store everything
            val allDataUrl = "${BuildConfig.SUPABASE_URL}/rest/v1/nutrition_daily?" +
                "user_id=eq.$userId&order=date.asc"
            
            val allDataRequest = Request.Builder()
                .url(allDataUrl)
                .get()
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .build()
            
            val allDays = mutableListOf<NutritionDayData>()
            
            httpClient.newCall(allDataRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@use
                    val arr = org.json.JSONArray(body)
                    
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        allDays.add(NutritionDayData(
                            date = obj.getString("date"),
                            calories = obj.optDouble("total_calories", 0.0).toInt(),
                            protein = obj.optDouble("total_protein_g", 0.0).toInt(),
                            carbs = obj.optDouble("total_carbs_g", 0.0).toInt(),
                            fat = obj.optDouble("total_fat_g", 0.0).toInt(),
                            fiber = obj.optDouble("total_fiber_g", 0.0).toInt(),
                            sugar = obj.optDouble("total_sugar_g", 0.0).toInt(),
                            sodium = obj.optDouble("total_sodium_mg", 0.0).toInt(),
                            caffeine = obj.optDouble("total_caffeine_mg", 0.0).toInt()
                        ))
                    }
                }
            }
            
            // Also get today's data from nutrition_records (not yet aggregated)
            val today = java.time.LocalDate.now().toString()
            val hasToday = allDays.any { it.date == today }
            
            if (!hasToday) {
                val todayUrl = "${BuildConfig.SUPABASE_URL}/rest/v1/nutrition_records?" +
                    "user_id=eq.$userId&date=eq.$today&select=calories,protein,total_carbohydrate,total_fat,dietary_fiber,sugar,sodium,caffeine"
                
                val todayRequest = Request.Builder()
                    .url(todayUrl)
                    .get()
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                
                httpClient.newCall(todayRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: return@use
                        val arr = org.json.JSONArray(body)
                        
                        if (arr.length() > 0) {
                            var calories = 0; var protein = 0; var carbs = 0; var fat = 0
                            var fiber = 0; var sugar = 0; var sodium = 0; var caffeine = 0
                            
                            for (i in 0 until arr.length()) {
                                val obj = arr.getJSONObject(i)
                                calories += obj.optDouble("calories", 0.0).toInt()
                                protein += obj.optDouble("protein", 0.0).toInt()
                                carbs += obj.optDouble("total_carbohydrate", 0.0).toInt()
                                fat += obj.optDouble("total_fat", 0.0).toInt()
                                fiber += obj.optDouble("dietary_fiber", 0.0).toInt()
                                sugar += obj.optDouble("sugar", 0.0).toInt()
                                sodium += obj.optDouble("sodium", 0.0).toInt()
                                caffeine += obj.optDouble("caffeine", 0.0).toInt()
                            }
                            
                            allDays.add(NutritionDayData(
                                date = today,
                                calories = calories, protein = protein, carbs = carbs, fat = fat,
                                fiber = fiber, sugar = sugar, sodium = sodium, caffeine = caffeine
                            ))
                        }
                    }
                }
            }
            
            // Calculate min/max from ALL data
            val allTimeMin = mutableMapOf<String, Float>()
            val allTimeMax = mutableMapOf<String, Float>()
            
            if (allDays.isNotEmpty()) {
                allTimeMin["calories"] = allDays.minOf { it.calories }.toFloat()
                allTimeMax["calories"] = allDays.maxOf { it.calories }.toFloat()
                allTimeMin["protein"] = allDays.minOf { it.protein }.toFloat()
                allTimeMax["protein"] = allDays.maxOf { it.protein }.toFloat()
                allTimeMin["carbs"] = allDays.minOf { it.carbs }.toFloat()
                allTimeMax["carbs"] = allDays.maxOf { it.carbs }.toFloat()
                allTimeMin["fat"] = allDays.minOf { it.fat }.toFloat()
                allTimeMax["fat"] = allDays.maxOf { it.fat }.toFloat()
                allTimeMin["fiber"] = allDays.minOf { it.fiber }.toFloat()
                allTimeMax["fiber"] = allDays.maxOf { it.fiber }.toFloat()
                allTimeMin["sugar"] = allDays.minOf { it.sugar }.toFloat()
                allTimeMax["sugar"] = allDays.maxOf { it.sugar }.toFloat()
                allTimeMin["sodium"] = allDays.minOf { it.sodium }.toFloat()
                allTimeMax["sodium"] = allDays.maxOf { it.sodium }.toFloat()
                allTimeMin["caffeine"] = allDays.minOf { it.caffeine }.toFloat()
                allTimeMax["caffeine"] = allDays.maxOf { it.caffeine }.toFloat()
            }
            
            // Ensure min != max
            listOf("calories", "protein", "carbs", "fat", "fiber", "sugar", "sodium", "caffeine").forEach { metric ->
                if (allTimeMin[metric] == null) allTimeMin[metric] = 0f
                if (allTimeMax[metric] == null) allTimeMax[metric] = 1f
                if (allTimeMin[metric] == allTimeMax[metric]) {
                    allTimeMax[metric] = allTimeMin[metric]!! + 1f
                }
            }
            
            // Get last N days for display
            val endDate = endDateOverride ?: java.time.LocalDate.now()
            val startDate = endDate.minusDays(days.toLong() - 1)
            
            val displayDays = mutableListOf<NutritionDayData>()
            var currentDate = startDate
            while (!currentDate.isAfter(endDate)) {
                val dateStr = currentDate.toString()
                val dayData = allDays.find { it.date == dateStr } ?: NutritionDayData(date = dateStr)
                displayDays.add(dayData)
                currentDate = currentDate.plusDays(1)
            }
            
            Log.d(TAG, "Nutrition history: ${allDays.size} total days, ${displayDays.size} display days")
            Log.d(TAG, "Calories min/max: ${allTimeMin["calories"]} - ${allTimeMax["calories"]}")
            
            NutritionHistoryResult(
                days = displayDays,
                allDays = allDays,
                allTimeMin = allTimeMin,
                allTimeMax = allTimeMax
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get nutrition history: ${e.message}", e)
            NutritionHistoryResult()
        }
    }
}

// Result containing history data and all-time min/max for normalization
data class NutritionHistoryResult(
    val days: List<NutritionDayData> = emptyList(),
    val allDays: List<NutritionDayData> = emptyList(),
    val allTimeMin: Map<String, Float> = emptyMap(),
    val allTimeMax: Map<String, Float> = emptyMap()
)

// Daily nutrition data for graph
data class NutritionDayData(
    val date: String = "",
    val calories: Int = 0,
    val protein: Int = 0,
    val carbs: Int = 0,
    val fat: Int = 0,
    val fiber: Int = 0,
    val sugar: Int = 0,
    val sodium: Int = 0,
    val caffeine: Int = 0
)

// Search result for display
data class USDAFoodSearchResult(
    val fdcId: Int,
    val description: String,
    val brandName: String?,
    val servingSize: Double?,
    val servingSizeUnit: String?,
    val calories: Double?
)

// Logged nutrition item
data class NutritionLogItem(
    val id: String,
    val foodName: String,
    val mealType: String,
    val calories: Double?,
    val protein: Double?,
    val totalCarbohydrate: Double?,
    val totalFat: Double?,
    val sugar: Double?,
    val sodium: Double?,
    val caffeine: Double?,
    val dietaryFiber: Double?,
    val cholesterol: Double?,
    val saturatedFat: Double?,
    val unsaturatedFat: Double?,
    val transFat: Double?,
    val potassium: Double?,
    val calcium: Double?,
    val iron: Double?,
    val magnesium: Double?,
    val zinc: Double?,
    val selenium: Double?,
    val phosphorus: Double?,
    val copper: Double?,
    val manganese: Double?,
    val vitaminA: Double?,
    val vitaminC: Double?,
    val vitaminD: Double?,
    val vitaminE: Double?,
    val vitaminK: Double?,
    val vitaminB6: Double?,
    val vitaminB12: Double?,
    val thiamin: Double?,
    val riboflavin: Double?,
    val niacin: Double?,
    val folate: Double?,
    val biotin: Double?,
    val pantothenicAcid: Double?,
    val tyramineExposure: String?,
    val timestamp: String,
    val source: String
)

// USDA API response models for search
@Serializable
data class USDASearchResponseFull(
    val foods: List<USDASearchFoodFull> = emptyList()
)

@Serializable
data class USDASearchFoodFull(
    val fdcId: Int,
    val description: String,
    val dataType: String? = null,
    val brandName: String? = null,
    val servingSize: Double? = null,
    val servingSizeUnit: String? = null,
    val foodNutrients: List<USDASearchNutrient> = emptyList()
)

@Serializable
data class USDASearchNutrient(
    val nutrientId: Int,
    val value: Double? = null
)

// USDA API response models for food details
@Serializable
data class USDAFoodDetailsFull(
    val fdcId: Int,
    val description: String,
    val servingSize: Double? = null,
    val servingSizeUnit: String? = null,
    val foodNutrients: List<USDAFoodNutrientFull> = emptyList()
)

@Serializable
data class USDAFoodNutrientFull(
    val nutrient: USDANutrientInfo,
    val amount: Double? = null
)

@Serializable
data class USDANutrientInfo(
    val id: Int,
    val name: String,
    val unitName: String = ""
)


fun NutritionLogItem.metricValue(metric: String): Double? = when (metric) {
    MonitorCardConfig.METRIC_CALORIES -> calories
    MonitorCardConfig.METRIC_PROTEIN -> protein
    MonitorCardConfig.METRIC_CARBS -> totalCarbohydrate
    MonitorCardConfig.METRIC_FAT -> totalFat
    MonitorCardConfig.METRIC_SUGAR -> sugar
    MonitorCardConfig.METRIC_SODIUM -> sodium
    MonitorCardConfig.METRIC_CAFFEINE -> caffeine
    MonitorCardConfig.METRIC_FIBER -> dietaryFiber
    MonitorCardConfig.METRIC_CHOLESTEROL -> cholesterol
    MonitorCardConfig.METRIC_SATURATED_FAT -> saturatedFat
    MonitorCardConfig.METRIC_UNSATURATED_FAT -> unsaturatedFat
    MonitorCardConfig.METRIC_TRANS_FAT -> transFat
    MonitorCardConfig.METRIC_POTASSIUM -> potassium
    MonitorCardConfig.METRIC_CALCIUM -> calcium
    MonitorCardConfig.METRIC_IRON -> iron
    MonitorCardConfig.METRIC_MAGNESIUM -> magnesium
    MonitorCardConfig.METRIC_ZINC -> zinc
    MonitorCardConfig.METRIC_SELENIUM -> selenium
    MonitorCardConfig.METRIC_PHOSPHORUS -> phosphorus
    MonitorCardConfig.METRIC_COPPER -> copper
    MonitorCardConfig.METRIC_MANGANESE -> manganese
    MonitorCardConfig.METRIC_VITAMIN_A -> vitaminA
    MonitorCardConfig.METRIC_VITAMIN_C -> vitaminC
    MonitorCardConfig.METRIC_VITAMIN_D -> vitaminD
    MonitorCardConfig.METRIC_VITAMIN_E -> vitaminE
    MonitorCardConfig.METRIC_VITAMIN_K -> vitaminK
    MonitorCardConfig.METRIC_VITAMIN_B6 -> vitaminB6
    MonitorCardConfig.METRIC_VITAMIN_B12 -> vitaminB12
    MonitorCardConfig.METRIC_THIAMIN -> thiamin
    MonitorCardConfig.METRIC_RIBOFLAVIN -> riboflavin
    MonitorCardConfig.METRIC_NIACIN -> niacin
    MonitorCardConfig.METRIC_FOLATE -> folate
    MonitorCardConfig.METRIC_BIOTIN -> biotin
    MonitorCardConfig.METRIC_PANTOTHENIC_ACID -> pantothenicAcid
    MonitorCardConfig.METRIC_TYRAMINE_EXPOSURE -> when (tyramineExposure) {
        "high" -> 3.0
        "medium" -> 2.0
        "low" -> 1.0
        else -> 0.0
    }
    else -> null
}

