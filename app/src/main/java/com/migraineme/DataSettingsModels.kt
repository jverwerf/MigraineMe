package com.migraineme

import android.content.Context
import android.os.PowerManager

// ─────────────────────────────────────────────────────────────────────────────
// Enums
// ─────────────────────────────────────────────────────────────────────────────

enum class CollectedByKind {
    PHONE,
    WEARABLE,
    MANUAL,
    REFERENCE,
    PHONE_OR_WEARABLE,
    COMPUTED
}

enum class WearableSource(val key: String, val label: String) {
    WHOOP("whoop", "WHOOP"),
    HEALTH_CONNECT("health_connect", "Health Connect")
}

enum class PhoneSource(val key: String, val label: String) {
    PHONE("phone", "Phone"),
    HEALTH_CONNECT("health_connect", "Health Connect")
}

// ─────────────────────────────────────────────────────────────────────────────
// Data Classes
// ─────────────────────────────────────────────────────────────────────────────

data class DataSection(
    val title: String,
    val rows: List<DataRow>
)

data class DataRow(
    val table: String,
    val collectedByKind: CollectedByKind,
    val collectedByLabel: String,
    val defaultWearable: WearableSource? = null
)

data class TriggerSection(
    val title: String,
    val description: String,
    val rows: List<TriggerRow>
)

data class TriggerRow(
    val triggerType: String,
    val label: String,
    val description: String
)

// ─────────────────────────────────────────────────────────────────────────────
// Helper Functions
// ─────────────────────────────────────────────────────────────────────────────

fun wearableRow(table: String, label: String): DataRow =
    DataRow(
        table = table,
        collectedByKind = CollectedByKind.WEARABLE,
        collectedByLabel = label,
        defaultWearable = WearableSource.WHOOP
    )

fun phoneRow(table: String, label: String): DataRow =
    DataRow(
        table = table,
        collectedByKind = CollectedByKind.PHONE,
        collectedByLabel = label
    )

fun manualRow(table: String, label: String): DataRow =
    DataRow(
        table = table,
        collectedByKind = CollectedByKind.MANUAL,
        collectedByLabel = label
    )

fun referenceRow(table: String, label: String): DataRow =
    DataRow(
        table = table,
        collectedByKind = CollectedByKind.REFERENCE,
        collectedByLabel = label
    )

/**
 * Row that can be collected by either phone (usage stats) or wearable.
 * Never greyed out — phone is always a valid fallback source.
 * When wearable is connected, user can choose wearable or phone.
 * When no wearable, defaults to phone source.
 */
fun phoneOrWearableRow(table: String, label: String): DataRow =
    DataRow(
        table = table,
        collectedByKind = CollectedByKind.PHONE_OR_WEARABLE,
        collectedByLabel = label,
        defaultWearable = WearableSource.WHOOP
    )

/**
 * Row for computed/derived metrics (e.g. stress index from HRV + resting HR).
 * Shows "Computed" label instead of a source selector.
 * May have dependency requirements on other metrics.
 */
fun computedRow(table: String, label: String): DataRow =
    DataRow(
        table = table,
        collectedByKind = CollectedByKind.COMPUTED,
        collectedByLabel = label
    )

fun defaultActiveFor(row: DataRow): Boolean {
    return when (row.collectedByKind) {
        CollectedByKind.REFERENCE -> false
        else -> true
    }
}

fun isBatteryOptimizationExempt(context: Context): Boolean {
    return try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Throwable) {
        false
    }
}

fun hasAskedMicPermission(context: Context): Boolean {
    return context.getSharedPreferences("data_settings", Context.MODE_PRIVATE)
        .getBoolean("mic_permission_asked", false)
}

fun markAskedMicPermission(context: Context) {
    context.getSharedPreferences("data_settings", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("mic_permission_asked", true)
        .apply()
}

fun hasAskedLocationPermission(context: Context): Boolean {
    return context.getSharedPreferences("data_settings", Context.MODE_PRIVATE)
        .getBoolean("location_permission_asked", false)
}

fun markAskedLocationPermission(context: Context) {
    context.getSharedPreferences("data_settings", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("location_permission_asked", true)
        .apply()
}

class DataSettingsStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("data_settings", Context.MODE_PRIVATE)

    fun getActive(table: String, wearable: WearableSource?, defaultValue: Boolean): Boolean {
        return prefs.getBoolean(activeKey(table, wearable), defaultValue)
    }

    fun setActive(table: String, wearable: WearableSource?, value: Boolean) {
        prefs.edit().putBoolean(activeKey(table, wearable), value).apply()
    }

    private fun activeKey(table: String, wearable: WearableSource?): String {
        return if (wearable == null) {
            "data_active_$table"
        } else {
            "data_active_${table}_${wearable.key}"
        }
    }
}
