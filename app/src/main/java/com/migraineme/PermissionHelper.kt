package com.migraineme

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.NutritionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PermissionHelper {

    suspend fun hasHealthConnectNutrition(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val status = HealthConnectClient.getSdkStatus(context)
                if (status != HealthConnectClient.SDK_AVAILABLE) return@withContext false

                val client = HealthConnectClient.getOrCreate(context)
                val granted = client.permissionController.getGrantedPermissions()
                HealthPermission.getReadPermission(NutritionRecord::class) in granted
            } catch (_: Exception) {
                false
            }
        }
    }

    suspend fun hasHealthConnectMenstruation(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val status = HealthConnectClient.getSdkStatus(context)
                if (status != HealthConnectClient.SDK_AVAILABLE) return@withContext false

                val client = HealthConnectClient.getOrCreate(context)
                val granted = client.permissionController.getGrantedPermissions()
                HealthPermission.getReadPermission(MenstruationPeriodRecord::class) in granted
            } catch (_: Exception) {
                false
            }
        }
    }

    fun hasMicrophone(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasScreenTime(context: Context): Boolean {
        return ScreenTimePermissionHelper.hasPermission(context)
    }

    fun isBatteryOptimizationExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}