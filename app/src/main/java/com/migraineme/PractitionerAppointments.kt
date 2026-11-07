package com.migraineme

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private fun whenText(iso: String?): String = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
}.getOrDefault(iso.orEmpty())

private fun instant(iso: String?) = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()

/** Opens the phone's calendar with the session filled in; the client presses save there. Needs no permission. */
fun appointmentCalendarIntent(startIso: String?, endIso: String?, title: String): android.content.Intent? {
    val start = instant(startIso) ?: return null
    val end = instant(endIso) ?: start.plusSeconds(1800)
    return android.content.Intent(android.content.Intent.ACTION_INSERT)
        .setData(android.provider.CalendarContract.Events.CONTENT_URI)
        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.toEpochMilli())
        .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, end.toEpochMilli())
        .putExtra(android.provider.CalendarContract.Events.TITLE, title)
}

/**
 * Appointments with one practitioner, inside their card on the sharing page:
 * what they suggested (accept or decline), what you suggested, then upcoming
 * and past sessions, including sessions from their practice software. Either
 * side can suggest. The server does the booking.
 */
@Composable
fun PractitionerAppointments(
    practitionerId: String,
    practitionerName: String,
    token: suspend () -> String?,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var data by remember { mutableStateOf<SupabasePractitionerService.MyAppointments?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var suggesting by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var time by remember { mutableStateOf<LocalTime?>(null) }

    LaunchedEffect(practitionerId, reload) {
        val tk = token() ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { SupabasePractitionerService.myAppointmentsWithSessions(tk) } }
            .onSuccess { data = it; error = null }
            .onFailure { error = it.message }
    }

    fun run(block: suspend (String) -> Unit) {
        scope.launch {
            val tk = token() ?: return@launch
            busy = true
            runCatching { withContext(Dispatchers.IO) { block(tk) } }
                .onSuccess { error = null; suggesting = false; date = null; time = null; reload++ }
                .onFailure { error = it.message }
            busy = false
        }
    }

    if (practitionerId in data?.closed.orEmpty()) return

    val rows = data?.requests.orEmpty().filter { it.practitioner_id == practitionerId }
    val sessions = data?.sessions.orEmpty().filter { it.practitioner_id == practitionerId }
    val open = rows.filter { it.status == "requested" }
    val fromThem = open.filter { it.proposed_by == "practitioner" }
    val fromMe = open.filter { it.proposed_by == "client" }
    val inSessions = sessions.map { it.id }.toSet()
    // Accepted times that are not in the practice software show up here too.
    val kept = rows.filter { it.status == "accepted" && it.scheduled_for != null && it.external_id !in inSessions }
    val all = sessions.map { Triple(it.starts_at, it.type, it.ends_at) } + kept.map { Triple(it.scheduled_for, null, it.proposed_end) }
    val now = java.time.Instant.now()
    val upcoming = all.filter { instant(it.first)?.isAfter(now) == true }.sortedBy { instant(it.first) }
    val past = all.filter { instant(it.first)?.isAfter(now) == false }.sortedByDescending { instant(it.first) }.take(5)
    // Only for someone who connected their calendar in the app.
    val calendarConnected = CalendarService.hasReadPermission(context)

    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(t("Appointments"), style = MaterialTheme.typography.bodyMedium, color = Color.White, fontWeight = FontWeight.SemiBold)

        fromThem.forEach { r ->
            Text(
                t("%1\$s suggested %2\$s", practitionerName, whenText(r.proposed_start)),
                style = MaterialTheme.typography.bodyMedium, color = Color.White,
            )
            r.message?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = AppTheme.SubtleTextColor)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { run { SupabasePractitionerService.respondToAppointment(it, r.id, true) } }) { Text(t("Accept")) }
                OutlinedButton(enabled = !busy, onClick = { run { SupabasePractitionerService.respondToAppointment(it, r.id, false) } }) { Text(t("Decline")) }
            }
        }

        fromMe.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        r.proposed_start?.let { t("You suggested %1\$s", whenText(it)) } ?: t("Asked for an appointment"),
                        style = MaterialTheme.typography.bodySmall, color = Color.White,
                    )
                    Text(t("Waiting for an answer"), style = MaterialTheme.typography.bodySmall, color = AppTheme.SubtleTextColor)
                }
                TextButton(enabled = !busy, onClick = { run { SupabasePractitionerService.withdrawAppointment(it, r.id) } }) { Text(t("Withdraw")) }
            }
        }

        if (upcoming.isNotEmpty()) {
            Text(t("Upcoming"), style = MaterialTheme.typography.bodySmall, color = AppTheme.SubtleTextColor)
            upcoming.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(whenText(s.first) + (s.second?.let { ty -> " · $ty" } ?: ""), style = MaterialTheme.typography.bodySmall, color = Color.White, modifier = Modifier.weight(1f))
                    if (calendarConnected) {
                        val title = t("Appointment with %1\$s", practitionerName)
                        TextButton(onClick = { appointmentCalendarIntent(s.first, s.third, title)?.let { context.startActivity(it) } }) { Text(t("Add to calendar")) }
                    }
                }
            }
        }
        if (past.isNotEmpty()) {
            Text(t("Past"), style = MaterialTheme.typography.bodySmall, color = AppTheme.SubtleTextColor)
            past.forEach { Text(whenText(it.first) + (it.second?.let { ty -> " · $ty" } ?: ""), style = MaterialTheme.typography.bodySmall, color = AppTheme.SubtleTextColor) }
        }

        if (suggesting) {
            val tomorrow = LocalDate.now().plusDays(1)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                    val d = date ?: tomorrow
                    DatePickerDialog(context, { _, y, m, day -> date = LocalDate.of(y, m + 1, day) }, d.year, d.monthValue - 1, d.dayOfMonth)
                        .apply { datePicker.minDate = System.currentTimeMillis() }.show()
                }) { Text(date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) ?: t("Pick a date"), maxLines = 1) }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                    val c = time ?: LocalTime.of(10, 0)
                    TimePickerDialog(context, { _, h, m -> time = LocalTime.of(h, m) }, c.hour, c.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
                }) { Text(time?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) ?: t("Pick a time"), maxLines = 1) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy && date != null && time != null,
                    onClick = {
                        val at = LocalDateTime.of(date, time).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
                        run { SupabasePractitionerService.suggestAppointment(it, practitionerId, at) }
                    },
                ) { Text(t("Send suggestion")) }
                TextButton(onClick = { suggesting = false }) { Text(t("Cancel")) }
            }
        } else {
            OutlinedButton(onClick = { suggesting = true }, modifier = Modifier.fillMaxWidth()) { Text(t("Suggest a time")) }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB4A8)) }
    }
}
