package com.migraineme

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Worker that pushes nutrition data from local outbox to Supabase
 *
 * NOW WITH USDA ENRICHMENT:
 * - Checks if nutrition data is incomplete (missing micronutrients)
 * - Enriches from USDA FoodData Central API before uploading
 * - Marks records as enriched=true
 */
class NutritionOutboxPushWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private val REQUIRED_PERMISSIONS = setOf(
            HealthPermission.getReadPermission(NutritionRecord::class)
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val accessToken = SessionStore.getValidAccessToken(applicationContext)
                ?: return@withContext Result.retry()

            val hc = HealthConnectClient.getOrCreate(applicationContext)
            val granted = hc.permissionController.getGrantedPermissions()
            if (!REQUIRED_PERMISSIONS.all { it in granted }) {
                return@withContext Result.failure()
            }

            val db = NutritionSyncDatabase.get(applicationContext)
            val dao = db.dao()

            val batch = dao.getOutboxBatch(limit = 200)
            if (batch.isEmpty()) {
                dao.markPushRun(System.currentTimeMillis())
                return@withContext Result.success()
            }

            val service = SupabaseNutritionService(applicationContext)
            val enrichmentService = USDAEnrichmentService() // USDA enrichment
            val riskClassifier = FoodRiskClassifierService() // Food risk classification

            val upserts = batch.filter { it.operation == "UPSERT" }
            val deletes = batch.filter { it.operation == "DELETE" }

            val succeededIds = mutableListOf<String>()
            val failedIds = mutableListOf<String>()

            // 1) Push UPSERTs with USDA enrichment + tyramine classification
            for (item in upserts) {
                try {
                    val record = hc.readRecord(NutritionRecord::class, item.healthConnectId).record
                    val date: LocalDate = record.startTime.atZone(ZoneOffset.UTC).toLocalDate()
                    val nutrition = mapToNutritionData(record, date)

                    // Step 1: Enrich nutrition data if needed
                    val enriched = if (enrichmentService.needsEnrichment(nutrition)) {
                        android.util.Log.d("NutritionOutboxPush", "Enriching: ${nutrition.foodName}")
                        enrichmentService.enrichNutrition(nutrition)
                    } else {
                        android.util.Log.d("NutritionOutboxPush", "No enrichment needed: ${nutrition.foodName}")
                        nutrition.copy(enriched = true)
                    }

                    // Step 2: Classify food risks (tyramine, alcohol, gluten)
                    val risks = if (!enriched.foodName.isNullOrBlank()) {
                        riskClassifier.classify(accessToken, enriched.foodName)
                    } else FoodRiskResult()
                    val finalNutrition = enriched.copy(
                        tyramineExposure = risks.tyramine,
                        alcoholExposure = risks.alcohol,
                        glutenExposure = risks.gluten
                    )

                    service.uploadNutritionRecord(accessToken, finalNutrition, item.healthConnectId)
                    succeededIds.add(item.healthConnectId)
                } catch (e: Exception) {
                    failedIds.add(item.healthConnectId)
                    android.util.Log.e("NutritionOutboxPush", "Failed UPSERT id=${item.healthConnectId}: ${e.message}", e)
                }
            }

            // 2) Push DELETEs (batch)
            if (deletes.isNotEmpty()) {
                val idsToDelete = deletes.map { it.healthConnectId }
                try {
                    service.deleteNutritionRecordsByHealthConnectIds(accessToken, idsToDelete)
                    succeededIds.addAll(idsToDelete)
                } catch (e: Exception) {
                    failedIds.addAll(idsToDelete)
                    android.util.Log.e("NutritionOutboxPush", "Failed DELETE batch: ${e.message}", e)
                }
            }

            if (succeededIds.isNotEmpty()) {
                dao.deleteOutboxByIds(succeededIds.distinct())
            }
            if (failedIds.isNotEmpty()) {
                dao.incrementRetry(failedIds.distinct())
            }

            dao.markPushRun(System.currentTimeMillis())

            if (succeededIds.isEmpty() && failedIds.isNotEmpty()) {
                return@withContext Result.retry()
            }

            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("NutritionOutboxPush", "Push worker failed: ${e.message}", e)
            Result.retry()
        }
    }

    private fun mapToNutritionData(record: NutritionRecord, date: LocalDate): NutritionData {
        return NutritionData(
            date = date,
            timestamp = record.startTime,
            endTimestamp = record.endTime,

            foodName = record.name,
            mealType = mapMealType(record.mealType),

            calories = record.energy?.inKilocalories,

            protein = record.protein?.inGrams,
            totalCarbohydrate = record.totalCarbohydrate?.inGrams,
            sugar = record.sugar?.inGrams,
            dietaryFiber = record.dietaryFiber?.inGrams,
            totalFat = record.totalFat?.inGrams,
            saturatedFat = record.saturatedFat?.inGrams,
            unsaturatedFat = record.unsaturatedFat?.inGrams,
            monounsaturatedFat = record.monounsaturatedFat?.inGrams,
            polyunsaturatedFat = record.polyunsaturatedFat?.inGrams,
            transFat = record.transFat?.inGrams,
            cholesterol = record.cholesterol?.inGrams?.times(1000),

            calcium = record.calcium?.inGrams?.times(1000),
            chloride = record.chloride?.inGrams?.times(1000),
            chromium = record.chromium?.inGrams?.times(1_000_000),
            copper = record.copper?.inGrams?.times(1000),
            iodine = record.iodine?.inGrams?.times(1_000_000),
            iron = record.iron?.inGrams?.times(1000),
            magnesium = record.magnesium?.inGrams?.times(1000),
            manganese = record.manganese?.inGrams?.times(1000),
            molybdenum = record.molybdenum?.inGrams?.times(1_000_000),
            phosphorus = record.phosphorus?.inGrams?.times(1000),
            potassium = record.potassium?.inGrams?.times(1000),
            selenium = record.selenium?.inGrams?.times(1_000_000),
            sodium = record.sodium?.inGrams?.times(1000),
            zinc = record.zinc?.inGrams?.times(1000),

            vitaminA = record.vitaminA?.inGrams?.times(1_000_000),
            vitaminB6 = record.vitaminB6?.inGrams?.times(1000),
            vitaminB12 = record.vitaminB12?.inGrams?.times(1_000_000),
            vitaminC = record.vitaminC?.inGrams?.times(1000),
            vitaminD = record.vitaminD?.inGrams?.times(1_000_000),
            vitaminE = record.vitaminE?.inGrams?.times(1000),
            vitaminK = record.vitaminK?.inGrams?.times(1_000_000),
            biotin = record.biotin?.inGrams?.times(1_000_000),
            folate = record.folate?.inGrams?.times(1_000_000),
            folicAcid = record.folicAcid?.inGrams?.times(1_000_000),
            niacin = record.niacin?.inGrams?.times(1000),
            pantothenicAcid = record.pantothenicAcid?.inGrams?.times(1000),
            riboflavin = record.riboflavin?.inGrams?.times(1000),
            thiamin = record.thiamin?.inGrams?.times(1000),

            caffeine = record.caffeine?.inGrams?.times(1000),

            tyramineExposure = null, // Set by FoodRiskClassifierService after enrichment
            alcoholExposure = null,
            glutenExposure = null,

            source = "health_connect",
            enriched = false // Will be set to true after enrichment
        )
    }

    private fun mapMealType(type: Int?): String {
        return when (type) {
            1 -> "breakfast"
            2 -> "lunch"
            3 -> "dinner"
            4 -> "snack"
            else -> "unknown"
        }
    }
}

