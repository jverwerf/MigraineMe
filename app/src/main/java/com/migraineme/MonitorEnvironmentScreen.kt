package com.migraineme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState

import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Tune

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Maps data_settings metric keys → MetricRegistry keys.
 * Centralised in MetricRegistry.dataSettingsKey() / MetricRegistry.enabledKeys().
 */

@Composable
fun MonitorEnvironmentScreen(
    navController: NavController,
    authVm: AuthViewModel
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    val weatherService = remember { WeatherHistoryService(context) }

    // Today's weather
    var todayWeather by remember { mutableStateOf<WeatherDayData?>(null) }
    var isLoadingToday by remember { mutableStateOf(true) }

    // Forecast data (next 6 days)
    var forecastDays by remember { mutableStateOf<List<WeatherDayData>>(emptyList()) }

    // Data settings — which individual metrics are enabled
    var enabledRegistryKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var settingsLoaded by remember { mutableStateOf(false) }

    // Track resume count to trigger reloads
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var resumeCount by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                resumeCount++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Load data on first composition and on each resume
    androidx.compose.runtime.LaunchedEffect(resumeCount) {
        val firstLoad = !settingsLoaded
        if (firstLoad) isLoadingToday = true

        withContext(Dispatchers.IO) {
            try {
                val edge = EdgeFunctionsService()
                val settings = edge.getMetricSettings(context)
                enabledRegistryKeys = MetricRegistry.enabledKeys(settings, "environment")
            } catch (_: Exception) {
                enabledRegistryKeys = MetricRegistry.byGroup("environment").map { it.key }.toSet()
            }
            settingsLoaded = true
        }

        if (enabledRegistryKeys.isNotEmpty()) {
            todayWeather = weatherService.getTodayWeather()
            val today = LocalDate.now()
            val result = weatherService.getWeatherHistory(7, today.plusDays(6))
            forecastDays = result.days.filter { it.date > today.toString() }
        }
        isLoadingToday = false
    }

    // All environment metrics from registry, filtered by enabled state
    val allMetrics = remember(settingsLoaded, enabledRegistryKeys) {
        MetricRegistry.byGroup("environment").filter { it.key in enabledRegistryKeys }
    }

    // Display metrics — filtered to only enabled ones
    val displayKeys = remember(settingsLoaded, enabledRegistryKeys) {
        MetricDisplayStore.getDisplayMetrics(context, "environment")
            .filter { it in enabledRegistryKeys }
            .ifEmpty { allMetrics.take(3).map { it.key } }
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {
            // Back navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            }

            // Customize card
            HeroCard(
                modifier = Modifier.clickable { navController.navigate(Routes.WEATHER_CONFIG) }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Tune,
                        contentDescription = "Configure",
                        tint = AppTheme.AccentPurple,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Customize Monitor Card",
                            color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            "Choose 3 metrics for the Weather card on Monitor",
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        "→",
                        color = AppTheme.AccentPurple,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            DismissableInfoCard(
                key = "monitor_environment",
                text = "Barometric pressure changes, humidity, and temperature fluctuations are common migraine triggers. We track these automatically based on your location."
            )

            // Show disabled message if ALL environment tracking is off
            if (settingsLoaded && enabledRegistryKeys.isEmpty()) {
                BaseCard {
                    Text(
                        "Environment tracking is disabled",
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Enable environment tracking in Data Settings to see weather data and correlations.",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Go to Data Settings →",
                        color = AppTheme.AccentPurple,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clickable { navController.navigate(Routes.DATA) }
                    )
                }
            } else {
                // Today's weather card
                BaseCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { if (PremiumManager.isPremium) navController.navigate(Routes.ENV_DATA_HISTORY) else navController.navigate(Routes.PAYWALL) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Today's Weather",
                            color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        if (PremiumManager.isPremium) { Text("History →", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall) } else { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { Icon(Icons.Outlined.Lock, contentDescription = "Premium", tint = AppTheme.AccentPurple, modifier = Modifier.size(14.dp)); Text("History", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall) } }
                    }
                    Spacer(Modifier.height(8.dp))

                    if (isLoadingToday) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = AppTheme.AccentPurple,
                                strokeWidth = 2.dp
                            )
                        }
                    } else if (todayWeather == null) {
                        Text(
                            "No weather data for today",
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        // Weather condition
                        val condition = weatherCodeToCondition(todayWeather!!.weatherCode)
                        Text(
                            condition,
                            color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )

                        if (todayWeather!!.isThunderstormDay) {
                            Text(
                                "⚡ Thunderstorm detected",
                                color = Color(0xFFFFB74D),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        // Top 3 selected metrics (only enabled ones)
                        val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            displayKeys.forEachIndexed { index, key ->
                                val metric = MetricRegistry.get(key)
                                val value = weatherValueByKey(todayWeather!!, key)
                                val formatted = if (value != null && metric != null) {
                                    MetricFormatter.format(value, metric.unit, metric.column)
                                } else "—"
                                val label = metric?.label ?: key
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(formatted, color = slotColors.getOrElse(index) { slotColors.last() }, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                                    Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }

                        // "All Metrics" section — only if there are non-displayed enabled metrics
                        val remainingMetrics = allMetrics.filter { it.key !in displayKeys }
                        if (remainingMetrics.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            HorizontalDivider(color = AppTheme.SubtleTextColor.copy(alpha = 0.2f))
                            Spacer(Modifier.height(8.dp))
                            Text("All Metrics", color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                            Spacer(Modifier.height(4.dp))

                            remainingMetrics.forEach { m ->
                                val value = weatherValueByKey(todayWeather!!, m.key)
                                val formatted = if (value != null) {
                                    MetricFormatter.format(value, m.unit, m.column)
                                } else "—"
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(m.label, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
                                    Text(formatted, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
                                }
                            }
                        }
                    }
                }

                // 7-Day Forecast — premium only
                if (forecastDays.isNotEmpty()) {
                    PremiumGate(
                        message = "Unlock 7-Day Forecast",
                        subtitle = "See weather conditions ahead of time",
                        onUpgrade = { navController.navigate(Routes.PAYWALL) }
                    ) {
                    BaseCard {
                        Text(
                            "7-Day Forecast",
                            color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(Modifier.height(8.dp))

                        val dayFormatter = DateTimeFormatter.ofPattern("EEE")
                        val dateFormatter = DateTimeFormatter.ofPattern("d MMM")
                        val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))

                        forecastDays.forEach { day ->
                            val date = LocalDate.parse(day.date)
                            val dayLabel = if (date == LocalDate.now().plusDays(1)) "Tomorrow" else date.format(dayFormatter)
                            val condition = weatherCodeToCondition(day.weatherCode)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(0.8f)) {
                                    Text(
                                        dayLabel,
                                        color = Color(0xFF4FC3F7),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                    Text(
                                        date.format(dateFormatter),
                                        color = AppTheme.SubtleTextColor,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Text(
                                    condition,
                                    color = AppTheme.BodyTextColor,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Column(horizontalAlignment = Alignment.End) {
                                    displayKeys.forEachIndexed { index, key ->
                                        val metric = MetricRegistry.get(key)
                                        val value = weatherValueByKey(day, key)
                                        val formatted = if (value != null && metric != null) {
                                            MetricFormatter.format(value, metric.unit, metric.column)
                                        } else "—"
                                        Text(
                                            formatted,
                                            color = slotColors.getOrElse(index) { slotColors.last() },
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                                        )
                                    }
                                }
                            }
                            if (day != forecastDays.last()) {
                                HorizontalDivider(color = AppTheme.SubtleTextColor.copy(alpha = 0.1f))
                            }
                        }
                    }
                    } // end PremiumGate
                }

                // History + Forecast Graph — premium only
                PremiumGate(
                    message = "Unlock Weather Trends",
                    subtitle = "Track environmental patterns over time",
                    onUpgrade = { navController.navigate(Routes.PAYWALL) }
                ) {
                    BaseCard {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { navController.navigate(Routes.FULL_GRAPH_WEATHER) },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("History + Forecast", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                            Text("→", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        WeatherHistoryGraph(
                            days = 21,
                            endDate = LocalDate.now().plusDays(6),
                            forecastStartDate = LocalDate.now().plusDays(1).toString(),
                            onClick = { navController.navigate(Routes.FULL_GRAPH_WEATHER) }
                        )
                    }
                }
            } // end enabled check
        }
    }
}

/**
 * Bridge: extract a value from WeatherDayData using a MetricRegistry key.
 */
private fun weatherValueByKey(weather: WeatherDayData, key: String): Double? {
    val v = when (key) {
        "user_weather_daily::temp_c_mean" -> weather.tempMean
        "user_weather_daily::pressure_hpa_mean" -> weather.pressureMean
        "user_weather_daily::humidity_pct_mean" -> weather.humidityMean
        "user_weather_daily::wind_speed_mps_mean" -> weather.windSpeedMean
        "user_weather_daily::uv_index_max" -> weather.uvIndexMax
        "user_location_daily::altitude_max_m" -> weather.altitudeMaxM
        "user_location_daily::altitude_change_m" -> weather.altitudeChangeM
        else -> null
    }
    return v?.takeIf { it != 0.0 || key == "user_weather_daily::uv_index_max" || key == "user_location_daily::altitude_max_m" }
}

private fun weatherCodeToCondition(code: Int): String {
    return when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing rain"
        71, 73, 75 -> "Snow"
        77 -> "Snow grains"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Unknown"
    }
}
