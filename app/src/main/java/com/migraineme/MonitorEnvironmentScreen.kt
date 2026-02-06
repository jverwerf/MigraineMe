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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.launch

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
    val weatherConfig = remember { WeatherCardConfigStore.load(context) }

    // Load today's weather
    LaunchedEffect(Unit) {
        scope.launch {
            todayWeather = weatherService.getTodayWeather()
            isLoadingToday = false
        }
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll) {
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


            // Customize card - controls what shows on MONITOR screen's weather card
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
            BaseCard {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Barometric pressure changes, humidity, and temperature fluctuations are common migraine triggers. We track these automatically based on your location.", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            // Today's weather card - show ALL metrics
            BaseCard {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { navController.navigate(Routes.ENV_DATA_HISTORY) },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Today's Weather",
                        color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text("History →", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
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

                    // Top 3 selected metrics
                    val selectedMetrics = weatherConfig.weatherDisplayMetrics.take(3)
                    val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        selectedMetrics.forEachIndexed { index, metric ->
                            val value = weatherMetricValue(todayWeather!!, metric) ?: "—"
                            val label = WeatherCardConfig.WEATHER_METRIC_LABELS[metric] ?: metric
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(value, color = slotColors.getOrElse(index) { slotColors.last() }, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                                Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = AppTheme.SubtleTextColor.copy(alpha = 0.2f))
                    Spacer(Modifier.height(8.dp))
                    Text("All Metrics", color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                    Spacer(Modifier.height(4.dp))

                    // All remaining metrics
                    WeatherCardConfig.ALL_WEATHER_METRICS.forEach { metric ->
                        if (metric !in selectedMetrics) {
                            val value = weatherMetricValue(todayWeather!!, metric)
                            if (value != null) {
                                val label = WeatherCardConfig.WEATHER_METRIC_LABELS[metric] ?: metric
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(label, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
                                    Text(value, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
                                }
                            }
                        }
                    }
                }
            }

            // History Graph
            WeatherHistoryGraph(
                days = 14,
                onClick = { navController.navigate(Routes.FULL_GRAPH_WEATHER) }
            )


        }
    }
}

private fun weatherMetricValue(weather: WeatherDayData, metric: String): String? {
    val v = when (metric) {
        WeatherCardConfig.METRIC_TEMPERATURE -> weather.tempMean
        WeatherCardConfig.METRIC_PRESSURE -> weather.pressureMean
        WeatherCardConfig.METRIC_HUMIDITY -> weather.humidityMean
        WeatherCardConfig.METRIC_WIND_SPEED -> weather.windSpeedMean
        WeatherCardConfig.METRIC_UV_INDEX -> weather.uvIndexMax
        else -> null
    } ?: return null
    if (v == 0.0 && metric != WeatherCardConfig.METRIC_UV_INDEX) return null
    val unit = WeatherCardConfig.WEATHER_METRIC_UNITS[metric] ?: ""
    return if (metric == WeatherCardConfig.METRIC_PRESSURE) String.format("%.0f%s", v, unit)
    else String.format("%.1f%s", v, unit)
}

private fun weatherMetricColor(metric: String): Color = when (metric) {
    WeatherCardConfig.METRIC_TEMPERATURE -> Color(0xFFFF8A65)
    WeatherCardConfig.METRIC_PRESSURE -> Color(0xFF7986CB)
    WeatherCardConfig.METRIC_HUMIDITY -> Color(0xFF4FC3F7)
    WeatherCardConfig.METRIC_WIND_SPEED -> Color(0xFF81C784)
    WeatherCardConfig.METRIC_UV_INDEX -> Color(0xFFFFB74D)
    else -> Color(0xFF4FC3F7)
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

