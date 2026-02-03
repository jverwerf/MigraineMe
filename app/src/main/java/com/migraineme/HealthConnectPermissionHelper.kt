package com.migraineme

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.NutritionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object HealthConnectPermissionHelper {

    /**
     * Backwards-compatible alias (original helper only checked Nutrition).
     */
    suspend fun hasPermission(context: Context): Boolean = hasNutritionPermission(context)

    suspend fun hasNutritionPermission(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
                return@withContext false
            }
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            HealthPermission.getReadPermission(NutritionRecord::class) in granted
        } catch (e: Exception) {
            android.util.Log.e("HealthConnectHelper", "Error checking nutrition permission: ${e.message}")
            false
        }
    }

    suspend fun hasMenstruationPermission(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
                return@withContext false
            }
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            HealthPermission.getReadPermission(MenstruationPeriodRecord::class) in granted
        } catch (e: Exception) {
            android.util.Log.e("HealthConnectHelper", "Error checking menstruation permission: ${e.message}")
            false
        }
    }
}
