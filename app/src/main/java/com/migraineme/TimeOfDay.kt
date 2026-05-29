package com.migraineme

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Helpers for turning Supabase `value_at` timestamp strings into either
 * decimal hours-of-day (for plotting) or a formatted "h:mm a" label (for
 * display). The strings come back from PostgREST in Postgres `timestamptz`
 * text format, e.g. "2026-05-28 22:30:00+00" or with fractional seconds
 * "2026-05-28 23:22:11.2+00" — not strict ISO-8601 — so a raw
 * Instant.parse() throws and the chart shows nothing.
 */
object TimeOfDay {

    /** Parse Supabase timestamp text into an Instant. Tolerates ISO-8601 and
     *  Postgres `timestamptz` text. Returns null on unparseable input. */
    fun parseInstant(raw: String): Instant? {
        if (raw.isBlank()) return null
        // Strict ISO 8601 first ("2026-05-28T22:30:00Z" / "...+00:00").
        runCatching { return Instant.parse(raw) }
        // OffsetDateTime tolerates the space separator and short offsets only
        // partially; normalize to ISO 8601 (space → 'T', "+00" → "+00:00").
        var iso = raw.replace(" ", "T")
        val offsetSuffix = Regex("([+-])(\\d{2})$").find(iso)
        if (offsetSuffix != null) {
            iso = iso.dropLast(3) + offsetSuffix.value + ":00"
        }
        runCatching { return OffsetDateTime.parse(iso).toInstant() }
        runCatching {
            return LocalDateTime.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneId.systemDefault())
                .toInstant()
        }
        return null
    }

    /** Decimal hour-of-day in the device timezone (e.g. 22:30 → 22.5). */
    fun toHoursOfDay(raw: String): Double? {
        val instant = parseInstant(raw) ?: return null
        val ldt = instant.atZone(ZoneId.systemDefault())
        return ldt.hour + ldt.minute / 60.0
    }

    /** "h:mm a" formatted label (e.g. "10:30 PM") in the device timezone. */
    fun formatHmma(raw: String): String? {
        val instant = parseInstant(raw) ?: return null
        val time = LocalTime.from(instant.atZone(ZoneId.systemDefault()))
        return time.format(DateTimeFormatter.ofPattern("h:mm a"))
    }
}
