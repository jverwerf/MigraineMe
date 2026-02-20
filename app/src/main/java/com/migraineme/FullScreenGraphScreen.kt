package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.DateRangePicker
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenGraphScreen(
    graphType: String,
    onBack: () -> Unit
) {
    val scrollState = rememberScrollState()

    val rangeOptions = listOf(1 to "Day", 7 to "Week", 14 to "2 Weeks", 30 to "Month")
    var selectedDays by remember { mutableStateOf(14) }
    var periodOffset by remember { mutableStateOf(0) }
    var showCustomPicker by remember { mutableStateOf(false) }
    var isCustomRange by remember { mutableStateOf(false) }
    var customEndDate by remember { mutableStateOf(LocalDate.now()) }
    var customDays by remember { mutableStateOf(14) }

    val today = remember { LocalDate.now() }
    val endDate = if (isCustomRange) customEndDate else today.minusDays((periodOffset * selectedDays).toLong())
    val startDate = if (isCustomRange) customEndDate.minusDays(customDays.toLong() - 1) else endDate.minusDays(selectedDays.toLong() - 1)
    val activeDays = if (isCustomRange) customDays else selectedDays

    val isAtPresent = if (isCustomRange) false else periodOffset == 0

    // For weather at present, extend end date to include forecast
    val isWeatherForecast = graphType == "weather" && isAtPresent && !isCustomRange
    val displayEndDate = if (isWeatherForecast) endDate.plusDays(6) else endDate

    val dateFormat = DateTimeFormatter.ofPattern("MMM d")
    val dateRangeLabel = "${startDate.format(dateFormat)} – ${displayEndDate.format(dateFormat)}"

    val title = when (graphType) {
        "sleep" -> "Sleep History"
        "weather" -> if (isWeatherForecast) "Weather History + Forecast" else "Weather History"
        "nutrition" -> "Nutrition History"
        else -> "History"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.FadeColor)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Back navigation
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
        }

        // Time range toggles — 4 presets
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for ((days, label) in rangeOptions) {
                val isSelected = !isCustomRange && selectedDays == days
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        isCustomRange = false
                        selectedDays = days
                        periodOffset = 0
                    },
                    label = { Text(text = label, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AppTheme.AccentPurple.copy(alpha = 0.3f),
                        selectedLabelColor = AppTheme.AccentPurple,
                        containerColor = AppTheme.BaseCardContainer,
                        labelColor = AppTheme.SubtleTextColor
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = if (isSelected) AppTheme.AccentPurple else AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                        selectedBorderColor = AppTheme.AccentPurple,
                        enabled = true,
                        selected = isSelected
                    )
                )
            }
        }

        // Custom range chip — own row
        FilterChip(
            selected = isCustomRange,
            onClick = { showCustomPicker = true },
            label = {
                Text(
                    text = if (isCustomRange) "Custom: ${startDate.format(dateFormat)} – ${endDate.format(dateFormat)}" else "Custom range…",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = AppTheme.AccentPink.copy(alpha = 0.3f),
                selectedLabelColor = AppTheme.AccentPink,
                containerColor = AppTheme.BaseCardContainer,
                labelColor = AppTheme.SubtleTextColor
            ),
            border = FilterChipDefaults.filterChipBorder(
                borderColor = if (isCustomRange) AppTheme.AccentPink else AppTheme.SubtleTextColor.copy(alpha = 0.3f),
                selectedBorderColor = AppTheme.AccentPink,
                enabled = true,
                selected = isCustomRange
            )
        )

        // Navigation: ← date range →
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { if (!isCustomRange) periodOffset += 1 },
                enabled = !isCustomRange
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Earlier",
                    tint = if (isCustomRange) AppTheme.SubtleTextColor.copy(alpha = 0.3f) else AppTheme.AccentPurple,
                    modifier = Modifier.size(28.dp)
                )
            }

            Text(
                text = dateRangeLabel,
                color = if (isAtPresent && !isCustomRange) AppTheme.TitleColor else AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
            )

            IconButton(
                onClick = { if (!isAtPresent && !isCustomRange) periodOffset -= 1 },
                enabled = !isAtPresent && !isCustomRange
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Later",
                    tint = if (isAtPresent || isCustomRange) AppTheme.SubtleTextColor.copy(alpha = 0.3f) else AppTheme.AccentPurple,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Graph
        when (graphType) {
            "sleep" -> SleepHistoryGraph(days = activeDays, endDate = endDate)
            "weather" -> {
                val isCurrent = isAtPresent && !isCustomRange
                val weatherEndDate = if (isCurrent) endDate.plusDays(6) else endDate
                val weatherDays = if (isCurrent) activeDays + 6 else activeDays
                val forecastStart = if (isCurrent) endDate.plusDays(1).toString() else null
                WeatherHistoryGraph(
                    days = weatherDays,
                    endDate = weatherEndDate,
                    forecastStartDate = forecastStart
                )
            }
            "nutrition" -> NutritionHistoryGraph(days = activeDays, endDate = endDate)
        }

        Spacer(Modifier.height(16.dp))
    }

    // Custom date range picker dialog
    if (showCustomPicker) {
        val dateRangePickerState = rememberDateRangePickerState()

        AlertDialog(
            onDismissRequest = { showCustomPicker = false },
            containerColor = Color(0xFF1E0A2E),
            titleContentColor = Color.White,
            textContentColor = AppTheme.BodyTextColor,
            confirmButton = {
                TextButton(
                    onClick = {
                        val startMillis = dateRangePickerState.selectedStartDateMillis
                        val endMillis = dateRangePickerState.selectedEndDateMillis
                        if (startMillis != null && endMillis != null) {
                            val zone = ZoneId.systemDefault()
                            val pickedStart = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()
                            val pickedEnd = Instant.ofEpochMilli(endMillis).atZone(zone).toLocalDate()
                            val dayCount = (pickedEnd.toEpochDay() - pickedStart.toEpochDay() + 1).toInt().coerceAtLeast(1)
                            customEndDate = pickedEnd
                            customDays = dayCount
                            isCustomRange = true
                            periodOffset = 0
                        }
                        showCustomPicker = false
                    },
                    enabled = dateRangePickerState.selectedStartDateMillis != null && dateRangePickerState.selectedEndDateMillis != null
                ) {
                    Text(text = "Apply", color = if (dateRangePickerState.selectedStartDateMillis != null && dateRangePickerState.selectedEndDateMillis != null) AppTheme.AccentPurple else AppTheme.SubtleTextColor)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomPicker = false }) {
                    Text(text = "Cancel", color = AppTheme.SubtleTextColor)
                }
            },
            title = { Text(text = "Select date range") },
            text = {
                DateRangePicker(
                    state = dateRangePickerState,
                    modifier = Modifier.fillMaxWidth().height(450.dp),
                    title = null,
                    headline = null,
                    showModeToggle = false,
                    colors = appDatePickerColors()
                )
            }
        )
    }
}


