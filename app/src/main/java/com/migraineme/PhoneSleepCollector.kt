package com.migraineme

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId

data class PhoneSleepData(
    val date: String,               // YYYY-MM-DD (the night's date = evening date)
    val fellAsleepMillis: Long,     // epoch millis of estimated sleep start
    val wokeUpMillis: Long,         // epoch millis of estimated wake up
    val durationMinutes: Long,      // total sleep duration in minutes
    val fellAsleepIso: String,      // ISO-8601 timestamp
    val wokeUpIso: String           // ISO-8601 timestamp
)

/**
 * Estimates sleep timing from phone usage patterns.
 *
 * Analyzes UsageStatsManager events to find the longest screen-off gap
 * during nighttime hours (20:00 → 12:00 next day).
 *
 * Logic:
 * - Query all MOVE_TO_FOREGROUND events in the 20:00–12:00 window
 * - The longest gap between consecutive foreground events = sleep period
 * - Start of gap = estimated fell asleep time
 * - End of gap = estimated woke up time
 *
 * Requires PACKAGE_USAGE_STATS permission.
 */
object PhoneSleepCollector {

    private const val TAG = "PhoneSleepCollector"
    private const val MIN_SLEEP_MINUTES = 120L  // ignore gaps shorter than 2 hours

    /**
     * Estimate sleep for a given night.
     *
     * @param context Application context
     * @param date The evening date (YYYY-MM-DD). Analyzes 20:00 on this date → 12:00 next day.
     * @return PhoneSleepData or null if no usable data / permission denied
     */
    fun estimateSleep(context: Context, date: String): PhoneSleepData? {
        try {
            if (!ScreenTimeCollector.hasUsageStatsPermission(context)) {
                Log.w(TAG, "PACKAGE_USAGE_STATS permission not granted")
                return null
            }

            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE)
                    as? UsageStatsManager
            if (usageStatsManager == null) {
                Log.e(TAG, "UsageStatsManager not available")
                return null
            }

            val localDate = LocalDate.parse(date)
            val zoneId = ZoneId.systemDefault()

            // Window: 20:00 on date → 12:00 on date+1
            val windowStart = localDate.atTime(20, 0).atZone(zoneId).toInstant().toEpochMilli()
            val windowEnd = localDate.plusDays(1).atTime(12, 0).atZone(zoneId).toInstant().toEpochMilli()

            Log.d(TAG, "Analyzing sleep window for $date: 20:00-12:00")

            // Collect all MOVE_TO_FOREGROUND event timestamps
            val foregroundTimestamps = mutableListOf<Long>()

            // Add window boundaries as implicit events
            foregroundTimestamps.add(windowStart)

            val events = usageStatsManager.queryEvents(windowStart, windowEnd)
            val event = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    foregroundTimestamps.add(event.timeStamp)
                }
            }

            foregroundTimestamps.add(windowEnd)
            foregroundTimestamps.sort()

            if (foregroundTimestamps.size < 2) {
                Log.d(TAG, "Not enough events to estimate sleep for $date")
                return null
            }

            // Find the longest gap between consecutive foreground events
            var longestGapStart = 0L
            var longestGapEnd = 0L
            var longestGapMs = 0L

            for (i in 0 until foregroundTimestamps.size - 1) {
                val gapMs = foregroundTimestamps[i + 1] - foregroundTimestamps[i]
                if (gapMs > longestGapMs) {
                    longestGapMs = gapMs
                    longestGapStart = foregroundTimestamps[i]
                    longestGapEnd = foregroundTimestamps[i + 1]
                }
            }

            val durationMinutes = longestGapMs / 1000 / 60

            if (durationMinutes < MIN_SLEEP_MINUTES) {
                Log.d(TAG, "Longest gap for $date is ${durationMinutes}min — too short, skipping")
                return null
            }

            val fellAsleepInstant = java.time.Instant.ofEpochMilli(longestGapStart)
            val wokeUpInstant = java.time.Instant.ofEpochMilli(longestGapEnd)

            Log.d(TAG, "Estimated sleep for $date: ${fellAsleepInstant} → ${wokeUpInstant} (${durationMinutes}min)")

            return PhoneSleepData(
                date = date,
                fellAsleepMillis = longestGapStart,
                wokeUpMillis = longestGapEnd,
                durationMinutes = durationMinutes,
                fellAsleepIso = fellAsleepInstant.toString(),
                wokeUpIso = wokeUpInstant.toString()
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error estimating sleep for $date", e)
            return null
        }
    }
}