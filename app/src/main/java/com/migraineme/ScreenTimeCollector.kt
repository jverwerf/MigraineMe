package com.migraineme

import android.app.AppOpsManager
import android.app.usage.UsageEvents
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

            val measured = measureInteractiveTime(usageStatsManager, startMillis, endMillis)
            Log.d(TAG, "Total screen time for $date: ${measured.totalSeconds}s across ${measured.appCount} apps")

            return ScreenTimeData(date, measured.totalSeconds, measured.appCount)

        } catch (e: Exception) {
            Log.e(TAG, "Error getting screen time for $date", e)
            return null
        }
    }

    /**
     * Get screen time during late-night window: 22:00 on [date] to 06:00 on [date+1].
     * Uses INTERVAL_BEST to get finer-grained buckets that can be filtered by hour.
     *
     * @param context Application context
     * @param date The evening date (YYYY-MM-DD). Window = this date 22:00 → next day 06:00.
     * @return ScreenTimeData with total seconds and app count for the late-night window, or null on error
     */
    fun getLateNightScreenTime(context: Context, date: String): ScreenTimeData? {
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

            val localDate = LocalDate.parse(date)
            val zoneId = ZoneId.systemDefault()

            // Window: 22:00 on date → 06:00 on date+1
            val windowStart = localDate.atTime(22, 0).atZone(zoneId)
            val windowEnd = localDate.plusDays(1).atTime(6, 0).atZone(zoneId)

            val startMillis = windowStart.toInstant().toEpochMilli()
            val endMillis = windowEnd.toInstant().toEpochMilli()

            Log.d(TAG, "Querying late-night screen time for $date (22:00-06:00): $startMillis to $endMillis")

            val measured = measureInteractiveTime(usageStatsManager, startMillis, endMillis)
            Log.d(TAG, "Late-night screen time for $date: ${measured.totalSeconds}s across ${measured.appCount} apps")

            return ScreenTimeData(date, measured.totalSeconds, measured.appCount)

        } catch (e: Exception) {
            Log.e(TAG, "Error getting late-night screen time for $date", e)
            return null
        }
    }

    // ─── Interactive-time measurement ────────────────────────────────────────

    private data class Measured(val totalSeconds: Int, val appCount: Int)

    // UsageEvents.Event constants used as literals so the code compiles and runs
    // on API levels where the newer names are not available.
    private const val EV_ACTIVITY_RESUMED = 1        // MOVE_TO_FOREGROUND
    private const val EV_ACTIVITY_PAUSED = 2         // MOVE_TO_BACKGROUND
    private const val EV_SCREEN_NON_INTERACTIVE = 16
    private const val EV_KEYGUARD_SHOWN = 17
    private const val EV_ACTIVITY_STOPPED = 23
    private const val EV_DEVICE_SHUTDOWN = 26

    /** A single foreground stretch is never credited more than this. */
    private const val MAX_INTERVAL_MS = 2L * 60 * 60 * 1000

    /** How far before the window to start reading events, to catch a session
     *  that was already in the foreground when the window opened. */
    private const val LOOKBACK_MS = 12L * 60 * 60 * 1000

    /**
     * Measure real screen-on foreground time in [startMillis, endMillis).
     *
     * Replaces summing `UsageStats.totalTimeInForeground` across packages, which
     * over-counted badly: it credits apps that hold the foreground with the
     * screen off (music, navigation), it counts overlapping sessions twice, and
     * its buckets bleed across day boundaries. On live data that produced days
     * of 19.7 hours.
     *
     * Here at most one interval is open at a time — a new ACTIVITY_RESUMED closes
     * the previous app's interval — and the screen going off, the keyguard
     * appearing or the device shutting down closes it too. Intervals are clamped
     * to the window, so nothing outside the day can leak in.
     */
    private fun measureInteractiveTime(
        usageStatsManager: UsageStatsManager,
        startMillis: Long,
        endMillis: Long
    ): Measured {
        val perPackage = mutableMapOf<String, Long>()
        var openPackage: String? = null
        var openSince = 0L

        fun close(at: Long) {
            val pkg = openPackage ?: return
            openPackage = null
            val from = maxOf(openSince, startMillis)
            val to = minOf(at, endMillis)
            var duration = to - from
            if (duration <= 0) return
            if (duration > MAX_INTERVAL_MS) duration = MAX_INTERVAL_MS
            perPackage[pkg] = (perPackage[pkg] ?: 0L) + duration
        }

        val events = usageStatsManager.queryEvents(startMillis - LOOKBACK_MS, endMillis)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val ts = event.timeStamp
            when (event.eventType) {
                EV_ACTIVITY_RESUMED -> {
                    close(ts)
                    openPackage = event.packageName
                    openSince = ts
                }
                EV_ACTIVITY_PAUSED, EV_ACTIVITY_STOPPED -> {
                    if (event.packageName == openPackage) close(ts)
                }
                EV_SCREEN_NON_INTERACTIVE, EV_KEYGUARD_SHOWN, EV_DEVICE_SHUTDOWN -> close(ts)
            }
        }
        close(endMillis)

        val totalMs = perPackage.values.sum()
        return Measured(
            totalSeconds = (totalMs / 1000).toInt(),
            appCount = perPackage.count { it.value > 0 }
        )
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