package com.migraineme

import android.content.Context
import androidx.core.content.edit

/**
 * Mirrors iOS MedicineCardConfig.swift. Stores the user's favourite medicine NAMES (not metric keys)
 * since the pool is dynamic per user. Card preview shows favourites first, drops favourites with no
 * recent logs, and back-fills with the next most-used medicines.
 */
object MedicineCardConfig {
    const val CARD_SLOT_COUNT = 3
    private const val PREFS = "monitor_prefs"
    private const val KEY = "medicine_card_config_favourites"

    fun loadFavourites(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }
    }

    fun saveFavourites(context: Context, names: List<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY, names.joinToString("\n")) }
    }
}

/**
 * Per-medicine aggregates over today / last 7 days / last 30 days.
 * `amountX` is the formatted sum (e.g. "1200mg"). `logsX` is the dose-count, used only for sorting
 * and favourite-fallback decisions.
 */
data class MedicineSummaryEntry(
    val name: String,
    val category: String?,
    val amountToday: String,
    val amountWeek: String,
    val amountMonth: String,
    val logsToday: Int,
    val logsWeek: Int,
    val logsMonth: Int,
)

data class MedicineCategorySummary(
    val category: String,
    val amountToday: String,
    val amountWeek: String,
    val amountMonth: String,
    val logsToday: Int,
    val logsWeek: Int,
    val logsMonth: Int,
)

/**
 * Parses "400mg", "50 mg", "2.5g", "1 tablet" → (value, unit-lowercased).
 * Returns null when raw is null/blank/unparseable.
 */
fun parseMedicineAmount(raw: String?): Pair<Double, String>? {
    if (raw.isNullOrBlank()) return null
    val trimmed = raw.trim().lowercase()
    val regex = Regex("""^\s*([\d]+(?:\.[\d]+)?)\s*([a-z]+)?""")
    val m = regex.find(trimmed) ?: return null
    val value = m.groupValues[1].toDoubleOrNull() ?: return null
    val unit = m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "mg"
    return value to unit
}

/**
 * Formats a list of (value, unit) pairs as a single human string. Groups by unit, picks the
 * dominant unit's sum. If unparseable entries also exist, appends a "+" marker.
 */
fun formatAmountSum(parsed: List<Pair<Double, String>>, unparseable: Int): String {
    if (parsed.isEmpty() && unparseable == 0) return "-"
    val byUnit = mutableMapOf<String, Double>()
    for ((v, u) in parsed) byUnit[u] = (byUnit[u] ?: 0.0) + v
    val sorted = byUnit.entries.sortedByDescending { it.value }
    val top = sorted.firstOrNull() ?: return if (unparseable > 0) "${unparseable}×" else "-"
    val valStr = if (top.value % 1.0 == 0.0) top.value.toInt().toString()
                 else "%.1f".format(top.value)
    val extras = (sorted.size - 1) + (if (unparseable > 0) 1 else 0)
    return "$valStr${top.key}" + if (extras > 0) " +" else ""
}

/**
 * Resolves the medicines shown on the Monitor card preview. Favourites first (only when they have
 * data in the active window), then back-fills with the most-used medicines.
 */
fun resolveMedicineCardSlots(
    favourites: List<String>,
    allEntries: List<MedicineSummaryEntry>,
    slotCount: Int = MedicineCardConfig.CARD_SLOT_COUNT,
): List<MedicineSummaryEntry> {
    val byName = allEntries.associateBy { it.name }
    val chosen = mutableListOf<MedicineSummaryEntry>()
    val used = mutableSetOf<String>()

    for (fav in favourites) {
        if (chosen.size >= slotCount) break
        val entry = byName[fav] ?: continue
        if (entry.logsWeek == 0) continue
        chosen.add(entry); used.add(entry.name)
    }
    for (entry in allEntries) {
        if (chosen.size >= slotCount) break
        if (entry.name in used) continue
        chosen.add(entry); used.add(entry.name)
    }
    return chosen
}
