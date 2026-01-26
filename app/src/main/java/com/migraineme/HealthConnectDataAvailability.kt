package com.migraineme

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Helper to check if Health Connect actually has nutrition data available
 * Use this to show/hide nutrition features intelligently
 */
object HealthConnectDataAvailability {
    
    /**
     * Check if any nutrition data exists in Health Connect (last 30 days)
     * 
     * @return true if at least one nutrition record exists, false otherwise
     */
    suspend fun hasNutritionData(context: Context): Boolean {
        return try {
            val hc = HealthConnectClient.getOrCreate(context)
            
            // Check last 30 days for any nutrition records
            val end = Instant.now()
            val start = end.minus(30, ChronoUnit.DAYS)
            
            val request = ReadRecordsRequest(
                recordType = NutritionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
            
            val response = hc.readRecords(request)
            
            // Return true if we found at least one record
            response.records.isNotEmpty()
            
        } catch (e: Exception) {
            android.util.Log.e("HealthConnectAvail", "Error checking nutrition data: ${e.message}")
            false
        }
    }
    
    /**
     * Get a user-friendly message about nutrition data availability
     */
    suspend fun getNutritionStatusMessage(context: Context, permissionGranted: Boolean): String {
        if (!permissionGranted) {
            return "Tap to connect Health Connect and track nutrition"
        }
        
        val hasData = hasNutritionData(context)
        
        return if (hasData) {
            "Connected • Syncing nutrition data"
        } else {
            "Connected • No nutrition data yet. Install Cronometer or MyFitnessPal to start tracking."
        }
    }
}
