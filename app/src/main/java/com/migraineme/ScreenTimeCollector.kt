package com.migraineme

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId

data class ScreenTimeData(
    val date: String, // YYYY-MM-DD
    val totalSeconds: Int,
    val appCount: Int
)

/**
 * Utility to extract daily screen time from Android's UsageStatsManager.
 * 
 * Requires PACKAGE_USAGE_STATS permission (granted via Settings > Apps > Special Access).
 */
object ScreenTimeCollector {

    private const val TAG = "ScreenTimeCollector"

    /**
     * Get total screen time for a specific date in the device's timezone.
     * 
     * @param context Application context
     * @param date The date to query (YYYY-MM-DD format)
     * @return ScreenTimeData with total seconds and app count, or null if permission denied or error
     */
    fun getDailyScreenTime(context: Context, date: String): ScreenTimeData? {
        try {
            if (!hasUsageStatsPermission(context)) {
                Log.w(TAG, "PACKAGE_USAGE_STATS permission not granted")
                return null
            }

            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usageStatsManager == null) {
                Log.e(TAG, "UsageStatsManager not available")
                return null
            }

            // Parse the date and get start/end of day in device timezone
            val localDate = LocalDate.parse(date)
            val zoneId = ZoneId.systemDefault()
            
            val startOfDay = localDate.atStartOfDay(zoneId)
            val endOfDay = localDate.plusDays(1).atStartOfDay(zoneId)
            
            val startMillis = startOfDay.toInstant().toEpochMilli()
            val endMillis = endOfDay.toInstant().toEpochMilli()

            Log.d(TAG, "Querying screen time for $date ($startMillis to $endMillis)")

            // Query usage stats for the day
            val usageStatsList = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startMillis,
                endMillis
            )

            if (usageStatsList.isNullOrEmpty()) {
                Log.d(TAG, "No usage stats found for $date")
                return ScreenTimeData(date, 0, 0)
            }

            // Sum up total foreground time across all apps
            var totalTimeInForeground = 0L
            var appCount = 0

            for (usageStats in usageStatsList) {
                val timeInForeground = usageStats.totalTimeInForeground
                if (timeInForeground > 0) {
                    totalTimeInForeground += timeInForeground
                    appCount++
                }
            }

            val totalSeconds = (totalTimeInForeground / 1000).toInt()
            Log.d(TAG, "Total screen time for $date: ${totalSeconds}s across $appCount apps")

            return ScreenTimeData(date, totalSeconds, appCount)

        } catch (e: Exception) {
            Log.e(TAG, "Error getting screen time for $date", e)
            return null
        }
    }

    /**
     * Check if the app has PACKAGE_USAGE_STATS permission.
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.e(TAG, "Error checking usage stats permission", e)
            false
        }
    }

    /**
     * Get yesterday's screen time (most common use case for daily sync).
     */
    fun getYesterdayScreenTime(context: Context): ScreenTimeData? {
        val yesterday = LocalDate.now().minusDays(1).toString()
        return getDailyScreenTime(context, yesterday)
    }
}
