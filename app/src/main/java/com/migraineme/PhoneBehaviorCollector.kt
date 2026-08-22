// FILE: app/src/main/java/com/migraineme/PhoneBehaviorCollector.kt
package com.migraineme

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId

/**
 * Point-in-time snapshot of phone behavioral signals.
 */
data class PhoneBehaviorSnapshot(
    val brightnessPct: Int,       // 0-100 screen brightness as percentage
    val volumePct: Int,           // 0-100 media volume as percentage
    val isDarkMode: Boolean,      // true = dark mode active
    val unlockCount: Int,         // cumulative unlock count for the day so far
    val screenOn: Boolean         // false = phone asleep; do not store screen signals
)

/**
 * Reads phone behavioral signals from Android system APIs.
 *
 * - Brightness: Settings.System.SCREEN_BRIGHTNESS, converted to a 0-100 percentage
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
            val screenOn = isScreenOn(context)
            val brightnessPct = getBrightnessPct(context)
            val volumePct = getMediaVolumePct(context)
            val isDark = isDarkMode(context)
            val unlocks = getUnlockCountToday(context)

            Log.d(TAG, "Snapshot: screenOn=$screenOn, brightness=$brightnessPct%, volume=$volumePct%, dark=$isDark, unlocks=$unlocks")

            PhoneBehaviorSnapshot(
                brightnessPct = brightnessPct,
                volumePct = volumePct,
                isDarkMode = isDark,
                unlockCount = unlocks,
                screenOn = screenOn
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting phone behavior snapshot", e)
            null
        }
    }

    // ─── Brightness ──────────────────────────────────────────────────────────

    /**
     * Read current screen brightness as a percentage (0-100).
     *
     * Settings.System.SCREEN_BRIGHTNESS is a raw panel value whose maximum is
     * device-specific (255 on most, higher on some OEM builds), so it is scaled
     * by the platform's own `config_screenBrightnessSettingMaximum`. Storing the
     * raw value is what made a full-brightness reading render as "255%" and put
     * every ordinary daytime reading above the 80 "brightness high" threshold.
     */
    fun getBrightnessPct(context: Context): Int {
        return try {
            val raw = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                -1
            )
            if (raw < 0) return 50 // unreadable: mid-scale, same as the old 128/255
            val max = maxBrightnessSetting()
            ((raw.toFloat() / max) * 100f).toInt().coerceIn(0, 100)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read brightness", e)
            50
        }
    }

    /**
     * The device's maximum value for SCREEN_BRIGHTNESS. Read from the platform
     * integer resource; 255 when that lookup is unavailable.
     */
    private fun maxBrightnessSetting(): Int {
        return try {
            val res = android.content.res.Resources.getSystem()
            val id = res.getIdentifier(
                "config_screenBrightnessSettingMaximum", "integer", "android"
            )
            val max = if (id != 0) res.getInteger(id) else 0
            if (max > 0) max else 255
        } catch (e: Exception) {
            255
        }
    }

    // ─── Screen state ────────────────────────────────────────────────────────

    /**
     * True when the screen is on. Brightness, volume and dark mode are only
     * meaningful while the phone is being used: sampling hourly around the
     * clock averaged 8-10 sleeping readings into every day, which pulled the
     * daily brightness mean under the "brightness low" threshold before the
     * user had touched the phone, and made a scheduled night theme look like
     * a full day of dark mode.
     */
    fun isScreenOn(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return true
            pm.isInteractive
        } catch (e: Exception) {
            Log.w(TAG, "Could not read screen state", e)
            true
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
