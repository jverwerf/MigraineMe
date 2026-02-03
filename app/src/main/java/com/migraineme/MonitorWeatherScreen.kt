package com.migraineme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
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
fun MonitorWeatherScreen(
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
                Text(
                    "Today's Weather",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
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

                    // Show ALL metrics
                    WeatherMetricRow("Temperature", todayWeather!!.tempMean, "°C")
                    WeatherMetricRow("Pressure", todayWeather!!.pressureMean, "hPa")
                    WeatherMetricRow("Humidity", todayWeather!!.humidityMean, "%")
                    WeatherMetricRow("Wind Speed", todayWeather!!.windSpeedMean, "m/s")
                    WeatherMetricRow("UV Index", todayWeather!!.uvIndexMax, "")
                }
            }

            // History Graph
            WeatherHistoryGraph(days = 14)
        }
    }
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
