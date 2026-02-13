package com.migraineme

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Configuration for Monitor screen cards - visibility and order
 */
@Serializable
data class MonitorCardConfig(
    val cardOrder: List<String> = DEFAULT_ORDER,
    val hiddenCards: Set<String> = emptySet(),
    val nutritionDisplayMetrics: List<String> = DEFAULT_NUTRITION_METRICS
) {
    companion object {
        // Card identifiers
        const val CARD_NUTRITION = "nutrition"
        const val CARD_PHYSICAL = "physical"
        const val CARD_SLEEP = "sleep"
        const val CARD_MENTAL = "mental"
        const val CARD_ENVIRONMENT = "environment"
        const val CARD_MENSTRUATION = "menstruation"
        
        val DEFAULT_ORDER = listOf(
            CARD_NUTRITION,
            CARD_ENVIRONMENT,
            CARD_PHYSICAL,
            CARD_SLEEP,
            CARD_MENTAL,
            CARD_MENSTRUATION
        )
        
        val CARD_LABELS = mapOf(
            CARD_NUTRITION to "Nutrition",
            CARD_PHYSICAL to "Physical Health",
            CARD_SLEEP to "Sleep",
            CARD_MENTAL to "Mental Health",
            CARD_ENVIRONMENT to "Environment",
            CARD_MENSTRUATION to "Menstruation"
        )
        
        // Nutrition metric identifiers
        const val METRIC_CALORIES = "calories"
        const val METRIC_PROTEIN = "protein"
        const val METRIC_CARBS = "carbs"
        const val METRIC_FAT = "fat"
        const val METRIC_FIBER = "fiber"
        const val METRIC_SUGAR = "sugar"
        const val METRIC_SODIUM = "sodium"
        const val METRIC_CAFFEINE = "caffeine"
        const val METRIC_CHOLESTEROL = "cholesterol"
        const val METRIC_SATURATED_FAT = "saturated_fat"
        const val METRIC_UNSATURATED_FAT = "unsaturated_fat"
        const val METRIC_TRANS_FAT = "trans_fat"
        const val METRIC_POTASSIUM = "potassium"
        const val METRIC_CALCIUM = "calcium"
        const val METRIC_IRON = "iron"
        const val METRIC_MAGNESIUM = "magnesium"
        const val METRIC_ZINC = "zinc"
        const val METRIC_SELENIUM = "selenium"
        const val METRIC_PHOSPHORUS = "phosphorus"
        const val METRIC_COPPER = "copper"
        const val METRIC_MANGANESE = "manganese"
        const val METRIC_VITAMIN_A = "vitamin_a"
        const val METRIC_VITAMIN_C = "vitamin_c"
        const val METRIC_VITAMIN_D = "vitamin_d"
        const val METRIC_VITAMIN_E = "vitamin_e"
        const val METRIC_VITAMIN_K = "vitamin_k"
        const val METRIC_VITAMIN_B6 = "vitamin_b6"
        const val METRIC_VITAMIN_B12 = "vitamin_b12"
        const val METRIC_THIAMIN = "thiamin"
        const val METRIC_RIBOFLAVIN = "riboflavin"
        const val METRIC_NIACIN = "niacin"
        const val METRIC_FOLATE = "folate"
        const val METRIC_BIOTIN = "biotin"
        const val METRIC_PANTOTHENIC_ACID = "pantothenic_acid"
        const val METRIC_TYRAMINE_EXPOSURE = "tyramine_exposure"
        const val METRIC_ALCOHOL_EXPOSURE = "alcohol_exposure"
        const val METRIC_GLUTEN_EXPOSURE = "gluten_exposure"
        
        val DEFAULT_NUTRITION_METRICS = listOf(
            METRIC_CALORIES,
            METRIC_PROTEIN,
            METRIC_CAFFEINE
        )
        
        val ALL_NUTRITION_METRICS = listOf(
            METRIC_BIOTIN,
            METRIC_CAFFEINE,
            METRIC_CALCIUM,
            METRIC_CALORIES,
            METRIC_CARBS,
            METRIC_CHOLESTEROL,
            METRIC_COPPER,
            METRIC_FAT,
            METRIC_FIBER,
            METRIC_FOLATE,
            METRIC_IRON,
            METRIC_MAGNESIUM,
            METRIC_MANGANESE,
            METRIC_NIACIN,
            METRIC_PANTOTHENIC_ACID,
            METRIC_PHOSPHORUS,
            METRIC_POTASSIUM,
            METRIC_PROTEIN,
            METRIC_RIBOFLAVIN,
            METRIC_SATURATED_FAT,
            METRIC_SELENIUM,
            METRIC_SODIUM,
            METRIC_SUGAR,
            METRIC_THIAMIN,
            METRIC_TRANS_FAT,
            METRIC_UNSATURATED_FAT,
            METRIC_VITAMIN_A,
            METRIC_VITAMIN_B6,
            METRIC_VITAMIN_B12,
            METRIC_VITAMIN_C,
            METRIC_VITAMIN_D,
            METRIC_VITAMIN_E,
            METRIC_VITAMIN_K,
            METRIC_ZINC,
            METRIC_TYRAMINE_EXPOSURE,
            METRIC_ALCOHOL_EXPOSURE,
            METRIC_GLUTEN_EXPOSURE
        )
        
        val NUTRITION_METRIC_LABELS = mapOf(
            METRIC_CALORIES to "Calories",
            METRIC_PROTEIN to "Protein",
            METRIC_CARBS to "Carbs",
            METRIC_FAT to "Fat",
            METRIC_FIBER to "Fiber",
            METRIC_SUGAR to "Sugar",
            METRIC_SODIUM to "Sodium",
            METRIC_CAFFEINE to "Caffeine",
            METRIC_CHOLESTEROL to "Cholesterol",
            METRIC_SATURATED_FAT to "Sat. Fat",
            METRIC_UNSATURATED_FAT to "Unsat. Fat",
            METRIC_TRANS_FAT to "Trans Fat",
            METRIC_POTASSIUM to "Potassium",
            METRIC_CALCIUM to "Calcium",
            METRIC_IRON to "Iron",
            METRIC_MAGNESIUM to "Magnesium",
            METRIC_ZINC to "Zinc",
            METRIC_SELENIUM to "Selenium",
            METRIC_PHOSPHORUS to "Phosphorus",
            METRIC_COPPER to "Copper",
            METRIC_MANGANESE to "Manganese",
            METRIC_VITAMIN_A to "Vitamin A",
            METRIC_VITAMIN_C to "Vitamin C",
            METRIC_VITAMIN_D to "Vitamin D",
            METRIC_VITAMIN_E to "Vitamin E",
            METRIC_VITAMIN_K to "Vitamin K",
            METRIC_VITAMIN_B6 to "Vitamin B6",
            METRIC_VITAMIN_B12 to "Vitamin B12",
            METRIC_THIAMIN to "Thiamin (B1)",
            METRIC_RIBOFLAVIN to "Riboflavin (B2)",
            METRIC_NIACIN to "Niacin (B3)",
            METRIC_FOLATE to "Folate (B9)",
            METRIC_BIOTIN to "Biotin (B7)",
            METRIC_PANTOTHENIC_ACID to "Pantothenic (B5)",
            METRIC_TYRAMINE_EXPOSURE to "Tyramine",
            METRIC_ALCOHOL_EXPOSURE to "Alcohol",
            METRIC_GLUTEN_EXPOSURE to "Gluten"
        )
        
        val NUTRITION_METRIC_UNITS = mapOf(
            METRIC_CALORIES to "",
            METRIC_PROTEIN to "g",
            METRIC_CARBS to "g",
            METRIC_FAT to "g",
            METRIC_FIBER to "g",
            METRIC_SUGAR to "g",
            METRIC_SODIUM to "mg",
            METRIC_CAFFEINE to "mg",
            METRIC_CHOLESTEROL to "mg",
            METRIC_SATURATED_FAT to "g",
            METRIC_UNSATURATED_FAT to "g",
            METRIC_TRANS_FAT to "g",
            METRIC_POTASSIUM to "mg",
            METRIC_CALCIUM to "mg",
            METRIC_IRON to "mg",
            METRIC_MAGNESIUM to "mg",
            METRIC_ZINC to "mg",
            METRIC_SELENIUM to "mcg",
            METRIC_PHOSPHORUS to "mg",
            METRIC_COPPER to "mg",
            METRIC_MANGANESE to "mg",
            METRIC_VITAMIN_A to "mcg",
            METRIC_VITAMIN_C to "mg",
            METRIC_VITAMIN_D to "mcg",
            METRIC_VITAMIN_E to "mg",
            METRIC_VITAMIN_K to "mcg",
            METRIC_VITAMIN_B6 to "mg",
            METRIC_VITAMIN_B12 to "mcg",
            METRIC_THIAMIN to "mg",
            METRIC_RIBOFLAVIN to "mg",
            METRIC_NIACIN to "mg",
            METRIC_FOLATE to "mcg",
            METRIC_BIOTIN to "mcg",
            METRIC_PANTOTHENIC_ACID to "mg",
            METRIC_TYRAMINE_EXPOSURE to "",
            METRIC_ALCOHOL_EXPOSURE to "",
            METRIC_GLUTEN_EXPOSURE to ""
        )

        /** Set of categorical risk metrics (use MAX not SUM, display as labels) */
        val RISK_METRICS = setOf(METRIC_TYRAMINE_EXPOSURE, METRIC_ALCOHOL_EXPOSURE, METRIC_GLUTEN_EXPOSURE)

        fun isRiskMetric(metric: String) = metric in RISK_METRICS
    }
    
    fun isVisible(cardId: String): Boolean = cardId !in hiddenCards
    
    fun getOrderedVisibleCards(): List<String> {
        return cardOrder.filter { it !in hiddenCards }
    }
    
    fun toggleVisibility(cardId: String): MonitorCardConfig {
        val newHidden = if (cardId in hiddenCards) {
            hiddenCards - cardId
        } else {
            hiddenCards + cardId
        }
        return copy(hiddenCards = newHidden)
    }
    
    fun moveCard(fromIndex: Int, toIndex: Int): MonitorCardConfig {
        if (fromIndex == toIndex) return this
        if (fromIndex < 0 || fromIndex >= cardOrder.size) return this
        if (toIndex < 0 || toIndex >= cardOrder.size) return this
        
        val mutableList = cardOrder.toMutableList()
        val item = mutableList.removeAt(fromIndex)
        mutableList.add(toIndex, item)
        return copy(cardOrder = mutableList)
    }
    
    fun toggleNutritionMetric(metric: String): MonitorCardConfig {
        val current = nutritionDisplayMetrics.toMutableList()
        if (metric in current) {
            current.remove(metric)
        } else if (current.size < 3) {
            current.add(metric)
        }
        return copy(nutritionDisplayMetrics = current)
    }
}

/**
 * Store for Monitor card configuration
 */
object MonitorCardConfigStore {
    private const val PREFS_NAME = "monitor_card_config"
    private const val KEY_CONFIG = "config_json"
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }
    
    fun load(context: Context): MonitorCardConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_CONFIG, null)
        
        return if (jsonStr != null) {
            try {
                json.decodeFromString<MonitorCardConfig>(jsonStr)
            } catch (e: Exception) {
                MonitorCardConfig()
            }
        } else {
            MonitorCardConfig()
        }
    }
    
    fun save(context: Context, config: MonitorCardConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = json.encodeToString(config)
        prefs.edit().putString(KEY_CONFIG, jsonStr).apply()
    }
}


