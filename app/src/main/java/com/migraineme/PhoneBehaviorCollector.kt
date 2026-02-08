// FILE: app/src/main/java/com/migraineme/PhoneBehaviorCollector.kt
package com.migraineme

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId

/**
 * Point-in-time snapshot of phone behavioral signals.
 */
data class PhoneBehaviorSnapshot(
    val brightness: Int,          // 0-255 raw Android brightness
    val volumePct: Int,           // 0-100 media volume as percentage
    val isDarkMode: Boolean,      // true = dark mode active
    val unlockCount: Int          // cumulative unlock count for the day so far
)

/**
 * Reads phone behavioral signals from Android system APIs.
 *
 * - Brightness: Settings.System.SCREEN_BRIGHTNESS (0-255)
 * - Volume: AudioManager media stream volume as percentage
 * - Dark mode: Configuration.uiMode night mask
 * - Unlock count: UsageStatsManager KEYGUARD_HIDDEN events (requires PACKAGE_USAGE_STATS)
 */
object PhoneBehaviorCollector {

    private const val TAG = "PhoneBehaviorCollector"

    /**
     * Collect a single snapshot of all phone behavioral metrics.
     *
     * @param context Application context
     * @return PhoneBehaviorSnapshot, or null on critical error
     */
    fun collectSnapshot(context: Context): PhoneBehaviorSnapshot? {
        return try {
            val brightness = getBrightness(context)
            val volumePct = getMediaVolumePct(context)
            val isDark = isDarkMode(context)
            val unlocks = getUnlockCountToday(context)

            Log.d(TAG, "Snapshot: brightness=$brightness, volume=$volumePct%, dark=$isDark, unlocks=$unlocks")

            PhoneBehaviorSnapshot(
                brightness = brightness,
                volumePct = volumePct,
                isDarkMode = isDark,
                unlockCount = unlocks
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting phone behavior snapshot", e)
            null
        }
    }

    // ─── Brightness ──────────────────────────────────────────────────────────

    /**
     * Read current screen brightness (0-255).
     * Falls back to 128 if adaptive brightness is on and manual value is unavailable.
     */
    fun getBrightness(context: Context): Int {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128 // default if unreadable
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not read brightness", e)
            128
        }
    }

    // ─── Volume ──────────────────────────────────────────────────────────────

    /**
     * Read current media volume as a percentage (0-100).
     */
    fun getMediaVolumePct(context: Context): Int {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return 50

            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

            if (max <= 0) return 0
            ((current.toFloat() / max) * 100).toInt().coerceIn(0, 100)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read media volume", e)
            50
        }
    }

    // ─── Dark Mode ───────────────────────────────────────────────────────────

    /**
     * Check if the device is currently in dark mode.
     */
    fun isDarkMode(context: Context): Boolean {
        return try {
            val nightModeFlags = context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK
            nightModeFlags == Configuration.UI_MODE_NIGHT_YES
        } catch (e: Exception) {
            Log.w(TAG, "Could not read dark mode status", e)
            false
        }
    }

    // ─── Unlock Count ────────────────────────────────────────────────────────

    /**
     * Count KEYGUARD_HIDDEN events for today using UsageStatsManager.
     * Requires PACKAGE_USAGE_STATS permission.
     *
     * @return Number of unlocks today, or 0 if permission denied or error
     */
    fun getUnlockCountToday(context: Context): Int {
        return getUnlockCount(context, LocalDate.now().toString())
    }

    /**
     * Count KEYGUARD_HIDDEN events for a specific date.
     *
     * @param context Application context
     * @param date Date string in YYYY-MM-DD format
     * @return Number of unlocks on that date
     */
    fun getUnlockCount(context: Context, date: String): Int {
        return try {
            if (!ScreenTimeCollector.hasUsageStatsPermission(context)) {
                Log.w(TAG, "PACKAGE_USAGE_STATS permission not granted — unlock count unavailable")
                return 0
            }

            val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE)
                    as? UsageStatsManager ?: return 0

            val localDate = LocalDate.parse(date)
            val zoneId = ZoneId.systemDefault()
            val startMillis = localDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val endMillis = localDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()

            val events = usageStatsManager.queryEvents(startMillis, endMillis)
            val event = UsageEvents.Event()
            var count = 0

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) {
                    count++
                }
            }

            Log.d(TAG, "Unlock count for $date: $count")
            count
        } catch (e: Exception) {
            Log.e(TAG, "Error counting unlocks for $date", e)
            0
        }
    }
}
