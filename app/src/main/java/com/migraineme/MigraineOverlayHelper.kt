package com.migraineme

import android.content.Context
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Shared helper for drawing migraine overlay bands on history graphs.
 *
 * Fetches migraine dates from Supabase and provides a draw function
 * that renders semi-transparent red vertical bands on Canvas graphs.
 */
object MigraineOverlayHelper {

    /**
     * Fetch the set of dates (as "yyyy-MM-dd" strings) that had a migraine
     * within the given date range.
     */
    suspend fun fetchMigraineDates(
        context: Context,
        days: Int,
        endDate: LocalDate = LocalDate.now()
    ): Set<String> = withContext(Dispatchers.IO) {
        try {
            val token = SessionStore.getValidAccessToken(context) ?: return@withContext emptySet()
            val dbService = SupabaseDbService(
                supabaseUrl = BuildConfig.SUPABASE_URL,
                supabaseKey = BuildConfig.SUPABASE_ANON_KEY
            )
            val allMigraines = dbService.getMigraines(token)

            val zone = ZoneId.systemDefault()
            val today = endDate
            val cutoff = today.minusDays(days.toLong())

            val dates = mutableSetOf<String>()

            for (m in allMigraines) {
                try {
                    val startDate = parseToLocalDate(m.startAt, zone) ?: continue
                    val endDate = if (m.endAt != null) {
                        parseToLocalDate(m.endAt, zone) ?: startDate
                    } else {
                        startDate
                    }

                    Log.d("MigraineOverlay", "Migraine: startAt=${m.startAt} -> $startDate, endAt=${m.endAt} -> $endDate, cutoff=$cutoff, today=$today")

                    // Add all dates in the migraine span
                    var d = startDate
                    while (!d.isAfter(endDate) && !d.isAfter(today)) {
                        if (!d.isBefore(cutoff)) {
                            dates.add(d.toString())
                        }
                        d = d.plusDays(1)
                    }
                } catch (e: Exception) {
                    Log.e("MigraineOverlay", "Failed to parse migraine: ${m.startAt} / ${m.endAt}", e)
                }
            }

            Log.d("MigraineOverlay", "Final migraine dates for overlay: $dates")

            dates
        } catch (_: Exception) {
            emptySet()
        }
    }

    /**
     * Draw red vertical bands on a Canvas for each migraine date.
     *
     * @param dateList The ordered list of date strings ("yyyy-MM-dd") shown on the graph x-axis
     * @param migraineDates Set of dates that had migraines
     * @param padding Canvas padding in px
     * @param graphWidth Total drawable width in px
     * @param graphHeight Total drawable height in px
     */
    fun DrawScope.drawMigraineBands(
        dateList: List<String>,
        migraineDates: Set<String>,
        padding: Float,
        graphWidth: Float,
        graphHeight: Float
    ) {
        if (migraineDates.isEmpty() || dateList.isEmpty()) return

        val totalDays = dateList.size
        val maxIndex = (totalDays - 1).coerceAtLeast(1)
        val bandColor = Color(0xFFE57373).copy(alpha = 0.15f)
        val borderColor = Color(0xFFE57373).copy(alpha = 0.35f)
        val slotWidth = graphWidth / maxIndex.toFloat()

        // Merge consecutive migraine days into runs, draw one band per run
        var runStart = -1

        for (i in 0..totalDays) {
            val isMigraine = i < totalDays && dateList[i] in migraineDates

            if (isMigraine && runStart == -1) {
                runStart = i
            } else if (!isMigraine && runStart != -1) {
                val runEnd = i - 1
                // Band spans from half-slot before runStart to half-slot after runEnd
                val left = (padding + (runStart.toFloat() / maxIndex) * graphWidth - slotWidth / 2).coerceAtLeast(padding)
                val right = (padding + (runEnd.toFloat() / maxIndex) * graphWidth + slotWidth / 2).coerceAtMost(padding + graphWidth)

                drawRect(
                    color = bandColor,
                    topLeft = Offset(left, padding),
                    size = Size(right - left, graphHeight)
                )
                // Left edge
                drawLine(borderColor, Offset(left, padding), Offset(left, padding + graphHeight), 1.5f)
                // Right edge
                drawLine(borderColor, Offset(right, padding), Offset(right, padding + graphHeight), 1.5f)

                runStart = -1
            }
        }
    }

    /**
     * Parse a timestamp string from Supabase into a LocalDate in the given timezone.
     *
     * Handles all common formats:
     * - "2026-02-05T23:30:00+00:00" (OffsetDateTime — Supabase timestamptz)
     * - "2026-02-05T23:30:00Z" (Instant)
     * - "2026-02-05T23:30:00" (LocalDateTime, assumed UTC)
     * - "2026-02-05" (already a date)
     */
    private fun parseToLocalDate(timestamp: String, zone: ZoneId): LocalDate? {
        // Try OffsetDateTime first (most common from Supabase timestamptz)
        try {
            return OffsetDateTime.parse(timestamp).atZoneSameInstant(zone).toLocalDate()
        } catch (_: Exception) {}

        // Try ZonedDateTime
        try {
            return ZonedDateTime.parse(timestamp).withZoneSameInstant(zone).toLocalDate()
        } catch (_: Exception) {}

        // Try Instant (ends with Z)
        try {
            return Instant.parse(timestamp).atZone(zone).toLocalDate()
        } catch (_: Exception) {}

        // Try LocalDateTime (no timezone info — assume UTC)
        try {
            val ldt = LocalDateTime.parse(timestamp.removeSuffix("Z"))
            return ldt.atZone(ZoneId.of("UTC")).withZoneSameInstant(zone).toLocalDate()
        } catch (_: Exception) {}

        // Try just a date string
        try {
            return LocalDate.parse(timestamp.take(10))
        } catch (_: Exception) {}

        return null
    }
}
