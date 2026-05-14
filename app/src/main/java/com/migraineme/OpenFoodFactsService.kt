package com.migraineme

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Open Food Facts product. Nutrients are normalised to the units expected by
 * the nutrition_records table (the same units USDA writes), per 100g of product.
 * Energy is kcal, macros g, most minerals/water-soluble vitamins mg,
 * fat-soluble vitamins + B12/B9/K/biotin/selenium µg, caffeine mg.
 */
data class OFFProduct(
    val barcode: String,
    val name: String,
    val brand: String?,
    val imageUrl: String?,
    val servingQuantityGrams: Double?,
    val nutrientsPer100g: Map<String, Double>
)

class OpenFoodFactsService {

    companion object {
        private const val TAG = "OpenFoodFacts"
        private const val BASE_URL = "https://world.openfoodfacts.org/api/v2/product"
        private const val USER_AGENT =
            "MigraineMe-Android/${BuildConfig.VERSION_NAME} (https://migraineme.app)"

        // OFF stores most nutriment values in grams per 100g. Scale converts that
        // to the unit the nutrition_records column expects.
        // Each entry: nutrition_records column → (list of OFF nutriment keys to try, scale factor)
        private val NUTRIENT_MAP: List<Triple<String, List<String>, Double>> = listOf(
            Triple("protein", listOf("proteins"), 1.0),
            Triple("total_carbohydrate", listOf("carbohydrates"), 1.0),
            Triple("total_fat", listOf("fat"), 1.0),
            Triple("dietary_fiber", listOf("fiber"), 1.0),
            Triple("sugar", listOf("sugars"), 1.0),
            Triple("saturated_fat", listOf("saturated-fat"), 1.0),
            Triple("monounsaturated_fat", listOf("monounsaturated-fat"), 1.0),
            Triple("polyunsaturated_fat", listOf("polyunsaturated-fat"), 1.0),
            Triple("trans_fat", listOf("trans-fat"), 1.0),
            Triple("cholesterol", listOf("cholesterol"), 1000.0),
            Triple("calcium", listOf("calcium"), 1000.0),
            Triple("iron", listOf("iron"), 1000.0),
            Triple("magnesium", listOf("magnesium"), 1000.0),
            Triple("phosphorus", listOf("phosphorus"), 1000.0),
            Triple("potassium", listOf("potassium"), 1000.0),
            Triple("zinc", listOf("zinc"), 1000.0),
            Triple("copper", listOf("copper"), 1000.0),
            Triple("manganese", listOf("manganese"), 1000.0),
            Triple("selenium", listOf("selenium"), 1_000_000.0),
            Triple("vitamin_a", listOf("vitamin-a"), 1_000_000.0),
            Triple("vitamin_c", listOf("vitamin-c"), 1000.0),
            Triple("vitamin_d", listOf("vitamin-d"), 1_000_000.0),
            Triple("vitamin_e", listOf("vitamin-e"), 1000.0),
            Triple("vitamin_k", listOf("vitamin-k"), 1_000_000.0),
            Triple("thiamin", listOf("vitamin-b1"), 1000.0),
            Triple("riboflavin", listOf("vitamin-b2"), 1000.0),
            Triple("niacin", listOf("vitamin-pp", "vitamin-b3"), 1000.0),
            Triple("vitamin_b6", listOf("vitamin-b6"), 1000.0),
            Triple("folate", listOf("vitamin-b9", "folates", "folate"), 1_000_000.0),
            Triple("vitamin_b12", listOf("vitamin-b12"), 1_000_000.0),
            Triple("pantothenic_acid", listOf("pantothenic-acid"), 1000.0),
            Triple("biotin", listOf("biotin"), 1_000_000.0),
            Triple("caffeine", listOf("caffeine"), 1000.0)
        )
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch product by barcode. Returns null if not found or on network error.
     */
    suspend fun fetchProduct(barcode: String): OFFProduct? = withContext(Dispatchers.IO) {
        if (barcode.isBlank()) return@withContext null

        try {
            val url = "$BASE_URL/$barcode.json"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "OFF fetch failed for $barcode: ${response.code}")
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                val root = JSONObject(body)
                if (root.optInt("status", 0) != 1) {
                    Log.d(TAG, "OFF: product $barcode not in database")
                    return@withContext null
                }
                parseProduct(barcode, root.getJSONObject("product"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "OFF fetch error for $barcode: ${e.message}", e)
            null
        }
    }

    private fun parseProduct(barcode: String, product: JSONObject): OFFProduct {
        val name = listOf("product_name", "product_name_en", "generic_name")
            .firstNotNullOfOrNull { product.optString(it).takeIf { v -> v.isNotBlank() } }
            ?: "Unknown product"

        val brand = product.optString("brands").takeIf { it.isNotBlank() }
            ?.split(",")?.firstOrNull()?.trim()

        val imageUrl = listOf("image_front_url", "image_url", "image_small_url")
            .firstNotNullOfOrNull { product.optString(it).takeIf { v -> v.isNotBlank() } }

        val servingGrams = parseServingGrams(product)

        val nutriments = product.optJSONObject("nutriments") ?: JSONObject()
        val mapped = mutableMapOf<String, Double>()

        // Calories: prefer kcal directly; fall back to kJ × 0.239
        val kcal = nutriments.optDoubleOrNull("energy-kcal_100g")
            ?: nutriments.optDoubleOrNull("energy_100g")?.let { it * 0.239006 }
        if (kcal != null && kcal > 0) mapped["calories"] = kcal

        // Sodium: prefer direct; fall back to salt × 0.4
        val sodiumG = nutriments.optDoubleOrNull("sodium_100g")
            ?: nutriments.optDoubleOrNull("salt_100g")?.let { it * 0.4 }
        if (sodiumG != null && sodiumG > 0) mapped["sodium"] = sodiumG * 1000.0

        for ((column, offKeys, scale) in NUTRIENT_MAP) {
            val raw = offKeys.firstNotNullOfOrNull {
                nutriments.optDoubleOrNull("${it}_100g")
            } ?: continue
            if (raw > 0) mapped[column] = raw * scale
        }

        return OFFProduct(
            barcode = barcode,
            name = name,
            brand = brand,
            imageUrl = imageUrl,
            servingQuantityGrams = servingGrams,
            nutrientsPer100g = mapped
        )
    }

    private fun parseServingGrams(product: JSONObject): Double? {
        product.optDoubleOrNull("serving_quantity")?.let { if (it > 0) return it }
        val text = product.optString("serving_size")
        if (text.isBlank()) return null
        val match = Regex("""([0-9]+(?:[.,][0-9]+)?)\s*g""", RegexOption.IGNORE_CASE).find(text)
        return match?.groupValues?.get(1)?.replace(",", ".")?.toDoubleOrNull()?.takeIf { it > 0 }
    }
}

private fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val v = optDouble(key, Double.NaN)
    return if (v.isNaN()) null else v
}
