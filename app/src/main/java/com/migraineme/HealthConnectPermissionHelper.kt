package com.migraineme

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object HealthConnectPermissionHelper {
    suspend fun hasPermission(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
                return@withContext false
            }
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            HealthPermission.getReadPermission(NutritionRecord::class) in granted
        } catch (e: Exception) {
            android.util.Log.e("HealthConnectHelper", "Error checking permission: ${e.message}")
            false
        }
    }
}