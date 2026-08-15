package com.migraineme

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Purple-themed Material3 DatePicker colors used across the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun appDatePickerColors(): DatePickerColors = DatePickerDefaults.colors(
    containerColor = Color(0xFF1E0A2E),
    titleContentColor = Color.White,
    headlineContentColor = Color.White,
    weekdayContentColor = AppTheme.SubtleTextColor,
    subheadContentColor = AppTheme.SubtleTextColor,
    navigationContentColor = Color.White,
    yearContentColor = AppTheme.BodyTextColor,
    currentYearContentColor = AppTheme.AccentPurple,
    selectedYearContentColor = Color.White,
    selectedYearContainerColor = AppTheme.AccentPurple,
    dayContentColor = AppTheme.BodyTextColor,
    selectedDayContentColor = Color.White,
    selectedDayContainerColor = AppTheme.AccentPurple,
    todayContentColor = AppTheme.AccentPurple,
    todayDateBorderColor = AppTheme.AccentPurple,
    dayInSelectionRangeContentColor = Color.White,
    dayInSelectionRangeContainerColor = AppTheme.AccentPurple.copy(alpha = 0.3f)
)

/**
 * Purple-themed Material3 TimePicker colors used across the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun appTimePickerColors(): TimePickerColors = TimePickerDefaults.colors(
    clockDialColor = Color(0xFF2A0C3C),
    clockDialSelectedContentColor = Color.White,
    clockDialUnselectedContentColor = AppTheme.BodyTextColor,
    selectorColor = AppTheme.AccentPurple,
    containerColor = Color(0xFF1E0A2E),
    periodSelectorBorderColor = AppTheme.AccentPurple.copy(alpha = 0.5f),
    periodSelectorSelectedContainerColor = AppTheme.AccentPurple,
    periodSelectorUnselectedContainerColor = Color.Transparent,
    periodSelectorSelectedContentColor = Color.White,
    periodSelectorUnselectedContentColor = AppTheme.SubtleTextColor,
    timeSelectorSelectedContainerColor = AppTheme.AccentPurple.copy(alpha = 0.3f),
    timeSelectorUnselectedContainerColor = Color(0xFF2A0C3C),
    timeSelectorSelectedContentColor = Color.White,
    timeSelectorUnselectedContentColor = AppTheme.BodyTextColor
)

/**
 * Days from today onwards are unselectable. Timestamps in this app are wall
 * clock, so "today" is the local day — a UTC-based comparison would open
 * tomorrow early for anyone east of Greenwich.
 */
@OptIn(ExperimentalMaterial3Api::class)
private object PastOnlyDates : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
        !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneId.of("UTC")).toLocalDate()
            .isAfter(LocalDate.now())

    override fun isSelectableYear(year: Int): Boolean = year <= LocalDate.now().year
}

/**
 * Themed DateTimePicker field — replaces the legacy Android DatePickerDialog.
 * Shows a Material3 DatePicker then TimePicker in our purple theme.
 *
 * [allowFuture] defaults to true because some call sites legitimately stamp a
 * future moment (a missed activity is often something still on the calendar).
 * Anything recording an event that has already happened passes false: the date
 * grid then stops at today, and a time later than now on today's date is
 * refused inline rather than accepted silently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimePickerField(
    label: String,
    allowFuture: Boolean = true,
    onDateTimeSelected: (String) -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }

    val datePickerState = rememberDatePickerState(
        selectableDates = if (allowFuture) DatePickerDefaults.AllDates else PastOnlyDates
    )
    val timePickerState = rememberTimePickerState(
        initialHour = LocalTime.now().hour,
        initialMinute = LocalTime.now().minute,
        is24Hour = true
    )

    // Date picker button
    OutlinedButton(
        onClick = { showDatePicker = true },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.BodyTextColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
    ) {
        Text(t(label), color = AppTheme.BodyTextColor)
    }

    // Date picker dialog
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            pickedDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                            showDatePicker = false
                            showTimePicker = true
                        }
                    },
                    enabled = datePickerState.selectedDateMillis != null
                ) { Text(t("Next"), color = if (datePickerState.selectedDateMillis != null) AppTheme.AccentPurple else AppTheme.SubtleTextColor) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(t("Cancel"), color = AppTheme.SubtleTextColor)
                }
            },
            colors = DatePickerDefaults.colors(containerColor = Color(0xFF1E0A2E))
        ) {
            DatePicker(
                state = datePickerState,
                colors = appDatePickerColors(),
                title = { Text(t("Select date"), color = Color.White, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
                headline = null,
                showModeToggle = false
            )
        }
    }

    // Time picker dialog
    if (showTimePicker && pickedDate != null) {
        // The date grid can only stop at whole days, so today plus a later
        // clock time is the one way a past-only field can still land ahead of
        // now. Caught here rather than on save.
        val isFutureTime = !allowFuture && pickedDate!!.atTime(timePickerState.hour, timePickerState.minute)
            .isAfter(LocalDateTime.now())
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            containerColor = Color(0xFF1E0A2E),
            titleContentColor = Color.White,
            confirmButton = {
                TextButton(
                    onClick = {
                        val iso = "%sT%02d:%02d:00Z".format(
                            pickedDate!!.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            timePickerState.hour,
                            timePickerState.minute
                        )
                        onDateTimeSelected(iso)
                        showTimePicker = false
                    },
                    enabled = !isFutureTime
                ) { Text(t("Done"), color = if (isFutureTime) AppTheme.SubtleTextColor else AppTheme.AccentPurple) }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(t("Cancel"), color = AppTheme.SubtleTextColor)
                }
            },
            title = { Text(t("Select time")) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                ) {
                    TimePicker(
                        state = timePickerState,
                        colors = appTimePickerColors()
                    )
                    if (isFutureTime) {
                        Text(t("Time cannot be in the future"), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        )
    }
}

/**
 * Alias — some screens use this name.
 */
@Composable
fun AppDateTimePicker(
    label: String,
    allowFuture: Boolean = true,
    onDateTimeSelected: (String) -> Unit
) {
    DateTimePickerField(label = label, allowFuture = allowFuture, onDateTimeSelected = onDateTimeSelected)
}

/**
 * Themed date-only picker for screens that need a styled date selector.
 * Note: MenstruationSettingsScreen has its own private AppDatePicker — use this for new screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemedDatePicker(
    isoDate: String,
    enabled: Boolean = true,
    onDateSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showPicker by remember { mutableStateOf(false) }

    val parsed = isoDate.trim().takeIf { it.isNotBlank() }?.let { raw ->
        runCatching { LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
    }
    val initial = parsed ?: LocalDate.now()
    val displayText = if (isoDate.isBlank()) "Select date" else isoDate

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    )

    OutlinedButton(
        onClick = { showPicker = true },
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.BodyTextColor),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
    ) {
        Text(displayText, color = if (enabled) Color.White else AppTheme.SubtleTextColor)
    }

    if (showPicker) {
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val picked = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                            onDateSelected(picked.format(DateTimeFormatter.ISO_LOCAL_DATE))
                            showPicker = false
                        }
                    },
                    enabled = datePickerState.selectedDateMillis != null
                ) { Text(t("OK"), color = if (datePickerState.selectedDateMillis != null) AppTheme.AccentPurple else AppTheme.SubtleTextColor) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(t("Cancel"), color = AppTheme.SubtleTextColor)
                }
            },
            colors = DatePickerDefaults.colors(containerColor = Color(0xFF1E0A2E))
        ) {
            DatePicker(
                state = datePickerState,
                colors = appDatePickerColors(),
                title = null,
                headline = null,
                showModeToggle = false
            )
        }
    }
}
