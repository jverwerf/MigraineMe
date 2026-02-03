package com.migraineme

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Checks if Health Connect has any data for specific metrics
 */
object HealthConnectDataChecker {
    
    data class DataStatus(
        val hasNutritionData: Boolean,
        val hasMenstruationData: Boolean
    )
    
    /**
     * Check if Health Connect has ANY records for nutrition and menstruation
     * Returns immediately if no data found (doesn't wait for sync)
     */
    suspend fun checkDataAvailability(context: Context): DataStatus {
        return withContext(Dispatchers.IO) {
            try {
                val client = HealthConnectClient.getOrCreate(context)
                
                // Check last 12 months (enough to determine if app is syncing)
                val now = Instant.now()
                val oneYearAgo = now.minus(365, ChronoUnit.DAYS)
                
                // Check nutrition
                val nutritionRequest = ReadRecordsRequest(
                    recordType = NutritionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(oneYearAgo, now)
                )
                val nutritionResponse = client.readRecords(nutritionRequest)
                val hasNutrition = nutritionResponse.records.isNotEmpty()
                
                Log.d("HC_DataChecker", "Nutrition records found: ${nutritionResponse.records.size}")
                
                // Check menstruation
                val menstruationRequest = ReadRecordsRequest(
                    recordType = MenstruationPeriodRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(oneYearAgo, now)
                )
                val menstruationResponse = client.readRecords(menstruationRequest)
                val hasMenstruation = menstruationResponse.records.isNotEmpty()
                
                Log.d("HC_DataChecker", "Menstruation records found: ${menstruationResponse.records.size}")
                
                DataStatus(
                    hasNutritionData = hasNutrition,
                    hasMenstruationData = hasMenstruation
                )
                
            } catch (e: Exception) {
                Log.e("HC_DataChecker", "Failed to check data: ${e.message}", e)
                // Return false for both on error (assume no data)
                DataStatus(
                    hasNutritionData = false,
                    hasMenstruationData = false
                )
            }
        }
    }
}
