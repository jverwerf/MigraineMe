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
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.BubbleChart
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Composable
fun MonitorScreen(
    navController: NavController,
    authVm: AuthViewModel = viewModel()
) {
    val ctx = LocalContext.current.applicationContext
    val authState by authVm.state.collectAsState()
    val scrollState = rememberScrollState()
    
    val today = remember { LocalDate.now(ZoneId.systemDefault()).toString() }
    
    // Card configuration
    var cardConfig by remember { mutableStateOf(MonitorCardConfigStore.load(ctx)) }
    var weatherConfig by remember { mutableStateOf(WeatherCardConfigStore.load(ctx)) }
    
    // Refresh config when returning to screen
    LaunchedEffect(Unit) {
        cardConfig = MonitorCardConfigStore.load(ctx)
        weatherConfig = WeatherCardConfigStore.load(ctx)
    }
    
    // Nutrition data
    var nutritionSummary by remember { mutableStateOf<NutritionSummary?>(null) }
    var nutritionLoading by remember { mutableStateOf(true) }
    
    // Weather data
    var weatherSummary by remember { mutableStateOf<WeatherSummary?>(null) }
    var weatherLoading by remember { mutableStateOf(true) }
    
    // Physical health data
    var physicalSummary by remember { mutableStateOf<PhysicalSummary?>(null) }
    var physicalLoading by remember { mutableStateOf(true) }
    
    // Sleep data
    var sleepSummary by remember { mutableStateOf<SleepSummary?>(null) }
    var sleepLoading by remember { mutableStateOf(true) }
    
    // Menstruation data
    var menstruationSettings by remember { mutableStateOf<MenstruationSettings?>(null) }
    var menstruationEnabled by remember { mutableStateOf(false) }
    var menstruationLoading by remember { mutableStateOf(true) }
    
    LaunchedEffect(authState.accessToken, today) {
        val token = authState.accessToken
        if (token.isNullOrBlank()) {
            nutritionLoading = false
            weatherLoading = false
            physicalLoading = false
            sleepLoading = false
            menstruationLoading = false
            return@LaunchedEffect
        }
        
        // Load nutrition summary
        withContext(Dispatchers.IO) {
            nutritionSummary = try {
                loadNutritionSummary(ctx, token, today)
            } catch (_: Exception) {
                null
            }
            nutritionLoading = false
        }
        
        // Load weather summary
        withContext(Dispatchers.IO) {
            weatherSummary = try {
                loadWeatherSummary(ctx, token, today)
            } catch (_: Exception) {
                null
            }
            weatherLoading = false
        }
        
        // Load physical health summary
        withContext(Dispatchers.IO) {
            physicalSummary = try {
                loadPhysicalSummary(ctx, token, today)
            } catch (_: Exception) {
                null
            }
            physicalLoading = false
        }
        
        // Load sleep summary
        withContext(Dispatchers.IO) {
            sleepSummary = try {
                loadSleepSummary(ctx, token, today)
            } catch (_: Exception) {
                null
            }
            sleepLoading = false
        }
        
        // Load menstruation settings AND check if enabled
        withContext(Dispatchers.IO) {
            try {
                // Check if menstruation metric is enabled
                val edge = EdgeFunctionsService()
                val metricSettings = edge.getMetricSettings(ctx)
                menstruationEnabled = metricSettings.any { 
                    it.metric == "menstruation" && it.enabled 
                }
                
                // Only load settings if enabled
                if (menstruationEnabled) {
                    menstruationSettings = SupabaseMenstruationService(ctx).getSettings(token)
                }
            } catch (_: Exception) {
                menstruationEnabled = false
                menstruationSettings = null
            }
            menstruationLoading = false
        }
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll) {
            // Configure Data Collection Card (HeroCard style like HomeScreen)
            HeroCard(
                modifier = Modifier.clickable { navController.navigate(Routes.MONITOR_CONFIG) }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = "Configure",
                        tint = AppTheme.AccentPurple,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Configure Monitor",
                            color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            "Show, hide, and reorder cards",
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

            // Render cards in configured order
            cardConfig.cardOrder.forEach { cardId ->
                if (cardConfig.isVisible(cardId)) {
                    when (cardId) {
                        MonitorCardConfig.CARD_NUTRITION -> {
                            NutritionCard(
                                nutritionLoading = nutritionLoading,
                                nutritionSummary = nutritionSummary,
                                displayMetrics = cardConfig.nutritionDisplayMetrics,
                                onClick = { navController.navigate(Routes.MONITOR_NUTRITION) }
                            )
                        }
                        MonitorCardConfig.CARD_WEATHER -> {
                            WeatherCard(
                                weatherLoading = weatherLoading,
                                weatherSummary = weatherSummary,
                                displayMetrics = weatherConfig.weatherDisplayMetrics.take(3),
                                onClick = { navController.navigate(Routes.MONITOR_WEATHER) }
                            )
                        }
                        MonitorCardConfig.CARD_PHYSICAL -> {
                            PhysicalHealthCard(
                                physicalLoading = physicalLoading,
                                physicalSummary = physicalSummary,
                                onClick = { navController.navigate(Routes.MONITOR_PHYSICAL) }
                            )
                        }
                        MonitorCardConfig.CARD_SLEEP -> {
                            SleepCard(
                                sleepLoading = sleepLoading,
                                sleepSummary = sleepSummary,
                                onClick = { navController.navigate(Routes.MONITOR_SLEEP) }
                            )
                        }
                        MonitorCardConfig.CARD_MENTAL -> {
                            MentalHealthCard(
                                onClick = { navController.navigate(Routes.MONITOR_MENTAL) }
                            )
                        }
                        MonitorCardConfig.CARD_ENVIRONMENT -> {
                            EnvironmentCard(
                                onClick = { navController.navigate(Routes.MONITOR_ENVIRONMENT) }
                            )
                        }
                        MonitorCardConfig.CARD_MENSTRUATION -> {
                            if (menstruationEnabled || menstruationLoading) {
                                MenstruationCard(
                                    menstruationLoading = menstruationLoading,
                                    menstruationSettings = menstruationSettings,
                                    onClick = { navController.navigate(Routes.MENSTRUATION_SETTINGS) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Individual card composables
@Composable
private fun NutritionCard(
    nutritionLoading: Boolean,
    nutritionSummary: NutritionSummary?,
    displayMetrics: List<String>,
    onClick: () -> Unit
) {
    MonitorCategoryCard(
        icon = Icons.Outlined.Restaurant,
        title = "Nutrition",
        iconTint = Color(0xFFFFB74D),
        onClick = onClick
    ) {
        if (nutritionLoading) {
            Text("Loading...", color = AppTheme.SubtleTextColor)
        } else {
            // Always show metrics even if no data
            val summary = nutritionSummary
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                displayMetrics.forEach { metric ->
                    val todayValue = summary?.todayValues?.get(metric) ?: 0
                    val yesterdayValue = summary?.yesterdayValues?.get(metric)
                    val label = MonitorCardConfig.NUTRITION_METRIC_LABELS[metric] ?: metric
                    val unit = MonitorCardConfig.NUTRITION_METRIC_UNITS[metric] ?: ""
                    val color = getNutritionMetricColor(metric)
                    
                    NutritionMetric(
                        label = label,
                        value = "$todayValue$unit",
                        unit = "",
                        yesterdayValue = yesterdayValue ?: 0,
                        color = color
                    )
                }
            }
            
            Spacer(Modifier.height(6.dp))
            
            val mealCount = summary?.todayMealCount ?: 0
            val recordCount = summary?.todayRecordCount ?: 0
            Text(
                "$mealCount meals • $recordCount items today",
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun getNutritionMetricColor(metric: String): Color {
    return when (metric) {
        MonitorCardConfig.METRIC_CALORIES -> Color(0xFFFFB74D)
        MonitorCardConfig.METRIC_PROTEIN -> Color(0xFF4FC3F7)
        MonitorCardConfig.METRIC_CARBS -> Color(0xFF81C784)
        MonitorCardConfig.METRIC_FAT -> Color(0xFFFF8A65)
        MonitorCardConfig.METRIC_FIBER -> Color(0xFFA5D6A7)
        MonitorCardConfig.METRIC_SUGAR -> Color(0xFFFFAB91)
        MonitorCardConfig.METRIC_SODIUM -> Color(0xFF90A4AE)
        MonitorCardConfig.METRIC_CAFFEINE -> Color(0xFFBA68C8)
        MonitorCardConfig.METRIC_CHOLESTEROL -> Color(0xFFE57373)
        MonitorCardConfig.METRIC_SATURATED_FAT -> Color(0xFFFFCC80)
        MonitorCardConfig.METRIC_IRON -> Color(0xFF8D6E63)
        MonitorCardConfig.METRIC_CALCIUM -> Color(0xFFE0E0E0)
        MonitorCardConfig.METRIC_VITAMIN_C -> Color(0xFFFFEB3B)
        MonitorCardConfig.METRIC_VITAMIN_D -> Color(0xFFFFF176)
        else -> Color(0xFF4FC3F7)
    }
}

@Composable
private fun WeatherCard(
    weatherLoading: Boolean,
    weatherSummary: WeatherSummary?,
    displayMetrics: List<String>,
    onClick: () -> Unit
) {
    MonitorCategoryCard(
        icon = Icons.Outlined.Cloud,
        title = "Weather",
        iconTint = Color(0xFF4FC3F7),
        onClick = onClick
    ) {
        if (weatherLoading) {
            Text("Loading...", color = AppTheme.SubtleTextColor)
        } else if (weatherSummary == null) {
            Text("No weather data", color = AppTheme.SubtleTextColor)
        } else {
            val weather = weatherSummary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "${weather.temperature}°C",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(weather.condition, color = AppTheme.SubtleTextColor)
                }
                Column(horizontalAlignment = Alignment.End) {
                    // Show selected metrics (max 3)
                    displayMetrics.take(3).forEach { metric ->
                        val label = WeatherCardConfig.WEATHER_METRIC_LABELS[metric] ?: metric
                        val value = getWeatherMetricValue(weather, metric)
                        val unit = WeatherCardConfig.WEATHER_METRIC_UNITS[metric] ?: ""
                        Text(
                            "$label: $value$unit",
                            color = AppTheme.BodyTextColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

private fun getWeatherMetricValue(weather: WeatherSummary, metric: String): String {
    return when (metric) {
        WeatherCardConfig.METRIC_TEMPERATURE -> weather.temperature.toString()
        WeatherCardConfig.METRIC_PRESSURE -> weather.pressure.toString()
        WeatherCardConfig.METRIC_HUMIDITY -> weather.humidity.toString()
        WeatherCardConfig.METRIC_WIND_SPEED -> String.format("%.1f", weather.windSpeed)
        WeatherCardConfig.METRIC_UV_INDEX -> weather.uvIndex.toString()
        else -> "—"
    }
}

@Composable
private fun PhysicalHealthCard(
    physicalLoading: Boolean,
    physicalSummary: PhysicalSummary?,
    onClick: () -> Unit
) {
    MonitorCategoryCard(
        icon = Icons.Outlined.FitnessCenter,
        title = "Physical Health",
        iconTint = Color(0xFF81C784),
        onClick = onClick
    ) {
        if (physicalLoading) {
            Text("Loading...", color = AppTheme.SubtleTextColor)
        } else if (physicalSummary == null) {
            Text("No physical health data", color = AppTheme.SubtleTextColor)
            Text("Connect WHOOP to see data", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
        } else {
            val physical = physicalSummary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                PhysicalMetric("Recovery", "${physical.recoveryScore}%", Color(0xFF81C784))
                PhysicalMetric("HRV", "${physical.hrv} ms", Color(0xFF4FC3F7))
                PhysicalMetric("RHR", "${physical.restingHr} bpm", Color(0xFFFF8A65))
            }
        }
    }
}

@Composable
private fun SleepCard(
    sleepLoading: Boolean,
    sleepSummary: SleepSummary?,
    onClick: () -> Unit
) {
    MonitorCategoryCard(
        icon = Icons.Outlined.Bedtime,
        title = "Sleep",
        iconTint = Color(0xFF7986CB),
        onClick = onClick
    ) {
        if (sleepLoading) {
            Text("Loading...", color = AppTheme.SubtleTextColor)
        } else if (sleepSummary == null) {
            Text("No sleep data", color = AppTheme.SubtleTextColor)
            Text("Connect WHOOP to see data", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
        } else {
            val sleep = sleepSummary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SleepMetric("Duration", String.format("%.1fh", sleep.durationHours), Color(0xFF7986CB))
                SleepMetric("Score", "${sleep.sleepScore}%", Color(0xFF4FC3F7))
                SleepMetric("Efficiency", "${sleep.efficiency}%", Color(0xFF81C784))
            }
        }
    }
}

@Composable
private fun MentalHealthCard(onClick: () -> Unit) {
    MonitorCategoryCard(
        icon = Icons.Outlined.BubbleChart,
        title = "Mental Health",
        iconTint = Color(0xFFBA68C8),
        onClick = onClick
    ) {
        Text("Track mood, stress & anxiety", color = AppTheme.SubtleTextColor)
        Text("Coming soon", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EnvironmentCard(onClick: () -> Unit) {
    MonitorCategoryCard(
        icon = Icons.Outlined.Hearing,
        title = "Environment",
        iconTint = Color(0xFF90A4AE),
        onClick = onClick
    ) {
        Text("Ambient noise, screen time & more", color = AppTheme.SubtleTextColor)
        Text("Coming soon", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MenstruationCard(
    menstruationLoading: Boolean,
    menstruationSettings: MenstruationSettings?,
    onClick: () -> Unit
) {
    MonitorCategoryCard(
        icon = Icons.Outlined.FavoriteBorder,
        title = "Menstruation",
        iconTint = Color(0xFFE57373),
        onClick = onClick
    ) {
        if (menstruationLoading) {
            Text("Loading...", color = AppTheme.SubtleTextColor)
        } else if (menstruationSettings == null || menstruationSettings.lastMenstruationDate == null) {
            Text("Not configured", color = AppTheme.SubtleTextColor)
            Text("Tap to set up cycle tracking", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
        } else {
            val settings = menstruationSettings
            val lastDate = settings.lastMenstruationDate!!
            val nextExpected = lastDate.plusDays(settings.avgCycleLength.toLong())
            val todayDate = LocalDate.now()
            val daysUntil = ChronoUnit.DAYS.between(todayDate, nextExpected)
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "Next: $nextExpected",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        when {
                            daysUntil < 0 -> "${-daysUntil} days ago"
                            daysUntil == 0L -> "Today"
                            daysUntil == 1L -> "Tomorrow"
                            else -> "In $daysUntil days"
                        },
                        color = when {
                            daysUntil in -2..2 -> Color(0xFFE57373)
                            daysUntil in 3..7 -> Color(0xFFFFB74D)
                            else -> AppTheme.SubtleTextColor
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "Cycle: ${settings.avgCycleLength} days",
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Last: $lastDate",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun NutritionMetric(
    label: String,
    value: String,
    unit: String,
    yesterdayValue: Int?,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            color = color,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            label,
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "yday: ${yesterdayValue ?: 0}",
            color = AppTheme.SubtleTextColor.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun MonitorCategoryCard(
    icon: ImageVector,
    title: String,
    iconTint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    BaseCard(
        modifier = if (enabled) Modifier.clickable(onClick = onClick) else Modifier
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (enabled) iconTint else iconTint.copy(alpha = 0.4f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                color = if (enabled) AppTheme.TitleColor else AppTheme.TitleColor.copy(alpha = 0.5f),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.weight(1f))
            if (enabled) {
                Text(
                    "→",
                    color = AppTheme.AccentPurple,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        
        Spacer(Modifier.height(8.dp))
        
        content()
    }
}

@Composable
private fun PhysicalMetric(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            color = color,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            label,
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SleepMetric(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            color = color,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            label,
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// Data classes for summaries
data class NutritionSummary(
    // Today's values (aggregated from nutrition_records)
    val todayValues: Map<String, Int?>,
    val todayMealCount: Int,
    val todayRecordCount: Int,
    
    // Yesterday's values (from nutrition_daily)
    val yesterdayValues: Map<String, Int?>
)

data class WeatherSummary(
    val temperature: Int,
    val condition: String,
    val humidity: Int,
    val pressure: Int,
    val uvIndex: Int,
    val windSpeed: Double = 0.0
)

data class PhysicalSummary(
    val recoveryScore: Int,
    val hrv: Int,
    val restingHr: Int
)

data class SleepSummary(
    val durationHours: Double,
    val sleepScore: Int,
    val efficiency: Int
)

// Data loading functions
private suspend fun loadNutritionSummary(ctx: android.content.Context, token: String, date: String): NutritionSummary? {
    return withContext(Dispatchers.IO) {
        try {
            val userId = SessionStore.readUserId(ctx) ?: return@withContext null
            val client = OkHttpClient()
            
            val today = LocalDate.parse(date)
            val yesterday = today.minusDays(1).toString()
            
            // 1. Get yesterday's data from nutrition_daily
            val yesterdayValues = mutableMapOf<String, Int?>()
            
            try {
                val yesterdayUrl = "${BuildConfig.SUPABASE_URL}/rest/v1/nutrition_daily?" +
                    "user_id=eq.$userId&date=eq.$yesterday&select=*"
                
                val yesterdayRequest = Request.Builder()
                    .url(yesterdayUrl)
                    .get()
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                
                val yesterdayResponse = client.newCall(yesterdayRequest).execute()
                val yesterdayBody = yesterdayResponse.body?.string()
                
                if (yesterdayResponse.isSuccessful && !yesterdayBody.isNullOrBlank()) {
                    val arr = org.json.JSONArray(yesterdayBody)
                    if (arr.length() > 0) {
                        val obj = arr.getJSONObject(0)
                        yesterdayValues[MonitorCardConfig.METRIC_CALORIES] = obj.optDouble("total_calories", Double.NaN).takeIf { !it.isNaN() }?.toInt()
                        yesterdayValues[MonitorCardConfig.METRIC_PROTEIN] = obj.optDouble("total_protein_g", Double.NaN).takeIf { !it.isNaN() }?.toInt()
                        yesterdayValues[MonitorCardConfig.METRIC_CARBS] = obj.optDouble("total_carbs_g", Double.NaN).takeIf { !it.isNaN() }?.toInt()
                        yesterdayValues[MonitorCardConfig.METRIC_FAT] = obj.optDouble("total_fat_g", Double.NaN).takeIf { !it.isNaN() }?.toInt()
                        yesterdayValues[MonitorCardConfig.METRIC_FIBER] = obj.optDouble("total_fiber_g", Double.NaN).takeIf { !it.isNaN() }?.toInt()
                        yesterdayValues[MonitorCardConfig.METRIC_SUGAR] = obj.optDouble("total_sugar_g", Double.NaN).takeIf { !it.isNaN() }?.toInt()
                        yesterdayValues[MonitorCardConfig.METRIC_SODIUM] = obj.optDouble("total_sodium_mg", Double.NaN).takeIf { !it.isNaN() }?.toInt()
                        yesterdayValues[MonitorCardConfig.METRIC_CAFFEINE] = obj.optDouble("total_caffeine_mg", Double.NaN).takeIf { !it.isNaN() }?.toInt()
                        yesterdayValues[MonitorCardConfig.METRIC_CHOLESTEROL] = obj.optDouble("total_cholesterol_mg", Double.NaN).takeIf { !it.isNaN() }?.toInt()
                        yesterdayValues[MonitorCardConfig.METRIC_SATURATED_FAT] = obj.optDouble("total_saturated_fat_g", Double.NaN).takeIf { !it.isNaN() }?.toInt()
                        yesterdayValues[MonitorCardConfig.METRIC_IRON] = obj.optDouble("total_iron_mg", Double.NaN).takeIf { !it.isNaN() }?.toInt()
                        yesterdayValues[MonitorCardConfig.METRIC_CALCIUM] = obj.optDouble("total_calcium_mg", Double.NaN).takeIf { !it.isNaN() }?.toInt()
                        yesterdayValues[MonitorCardConfig.METRIC_VITAMIN_C] = obj.optDouble("total_vitamin_c_mg", Double.NaN).takeIf { !it.isNaN() }?.toInt()
                        yesterdayValues[MonitorCardConfig.METRIC_VITAMIN_D] = obj.optDouble("total_vitamin_d_mcg", Double.NaN).takeIf { !it.isNaN() }?.toInt()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("MonitorScreen", "Failed to load yesterday nutrition: ${e.message}")
            }
            
            // 2. Get today's data by aggregating nutrition_records
            val todayValues = mutableMapOf<String, Int?>()
            var todayMealCount = 0
            var todayRecordCount = 0
            
            try {
                // Get today's start/end in ISO format
                val todayStart = "${date}T00:00:00Z"
                val todayEnd = "${date}T23:59:59Z"
                
                val todayUrl = "${BuildConfig.SUPABASE_URL}/rest/v1/nutrition_records?" +
                    "user_id=eq.$userId&timestamp=gte.$todayStart&timestamp=lte.$todayEnd" +
                    "&select=calories,protein,total_carbohydrate,total_fat,dietary_fiber,sugar,sodium,caffeine,cholesterol,saturated_fat,iron,calcium,vitamin_c,vitamin_d,meal_type"
                
                val todayRequest = Request.Builder()
                    .url(todayUrl)
                    .get()
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .addHeader("Authorization", "Bearer $token")
                    .build()
                
                val todayResponse = client.newCall(todayRequest).execute()
                val todayBody = todayResponse.body?.string()
                
                if (todayResponse.isSuccessful && !todayBody.isNullOrBlank()) {
                    val arr = org.json.JSONArray(todayBody)
                    
                    val sums = mutableMapOf<String, Double>()
                    val mealTypes = mutableSetOf<String>()
                    
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        todayRecordCount++
                        
                        fun addToSum(key: String, jsonKey: String) {
                            val v = obj.optDouble(jsonKey, 0.0)
                            if (!v.isNaN()) sums[key] = (sums[key] ?: 0.0) + v
                        }
                        
                        addToSum(MonitorCardConfig.METRIC_CALORIES, "calories")
                        addToSum(MonitorCardConfig.METRIC_PROTEIN, "protein")
                        addToSum(MonitorCardConfig.METRIC_CARBS, "total_carbohydrate")
                        addToSum(MonitorCardConfig.METRIC_FAT, "total_fat")
                        addToSum(MonitorCardConfig.METRIC_FIBER, "dietary_fiber")
                        addToSum(MonitorCardConfig.METRIC_SUGAR, "sugar")
                        addToSum(MonitorCardConfig.METRIC_SODIUM, "sodium")
                        addToSum(MonitorCardConfig.METRIC_CAFFEINE, "caffeine")
                        addToSum(MonitorCardConfig.METRIC_CHOLESTEROL, "cholesterol")
                        addToSum(MonitorCardConfig.METRIC_SATURATED_FAT, "saturated_fat")
                        addToSum(MonitorCardConfig.METRIC_IRON, "iron")
                        addToSum(MonitorCardConfig.METRIC_CALCIUM, "calcium")
                        addToSum(MonitorCardConfig.METRIC_VITAMIN_C, "vitamin_c")
                        addToSum(MonitorCardConfig.METRIC_VITAMIN_D, "vitamin_d")
                        
                        val meal = obj.optString("meal_type", "")
                        if (meal.isNotBlank() && meal != "unknown") mealTypes.add(meal)
                    }
                    
                    if (todayRecordCount > 0) {
                        sums.forEach { (key, value) ->
                            todayValues[key] = value.toInt()
                        }
                        todayMealCount = mealTypes.size
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("MonitorScreen", "Failed to load today nutrition: ${e.message}")
            }
            
            // Return null if no data at all
            if (todayRecordCount == 0 && yesterdayValues.isEmpty()) {
                return@withContext null
            }
            
            NutritionSummary(
                todayValues = todayValues,
                todayMealCount = todayMealCount,
                todayRecordCount = todayRecordCount,
                yesterdayValues = yesterdayValues
            )
        } catch (e: Exception) {
            android.util.Log.e("MonitorScreen", "Error loading nutrition summary: ${e.message}", e)
            null
        }
    }
}

private suspend fun loadWeatherSummary(ctx: android.content.Context, token: String, date: String): WeatherSummary? {
    return withContext(Dispatchers.IO) {
        try {
            val url = "${BuildConfig.SUPABASE_URL}/rest/v1/user_weather_daily?user_id=eq.${SessionStore.readUserId(ctx)}&date=eq.$date&select=*"
            
            val client = OkHttpClient()
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .build()
            
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            
            if (!response.isSuccessful) return@withContext null
            
            val arr = org.json.JSONArray(body)
            if (arr.length() == 0) return@withContext null
            
            val obj = arr.getJSONObject(0)
            
            val weatherCode = obj.optInt("weather_code", 0)
            val condition = weatherCodeToCondition(weatherCode)
            
            WeatherSummary(
                temperature = obj.optDouble("temp_c_mean", 0.0).toInt(),
                condition = condition,
                humidity = obj.optInt("humidity_pct_mean", 0),
                pressure = obj.optDouble("pressure_hpa_mean", 0.0).toInt(),
                uvIndex = obj.optDouble("uv_index_max", 0.0).toInt(),
                windSpeed = obj.optDouble("wind_speed_mps_mean", 0.0)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
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

private suspend fun loadPhysicalSummary(ctx: android.content.Context, token: String, date: String): PhysicalSummary? {
    val db = SupabasePhysicalHealthService(ctx)
    
    // Fetch individual metrics for today
    val recoveryList = try {
        db.fetchRecoveryScoreDaily(token, 1)
    } catch (_: Exception) {
        emptyList()
    }
    
    val hrvList = try {
        db.fetchHrvDaily(token, 1)
    } catch (_: Exception) {
        emptyList()
    }
    
    val rhrList = try {
        db.fetchRestingHrDaily(token, 1)
    } catch (_: Exception) {
        emptyList()
    }
    
    // Check if we have data for today
    val todayRecovery = recoveryList.find { it.date == date }
    val todayHrv = hrvList.find { it.date == date }
    val todayRhr = rhrList.find { it.date == date }
    
    if (todayRecovery == null && todayHrv == null && todayRhr == null) {
        return null
    }
    
    return PhysicalSummary(
        recoveryScore = todayRecovery?.value_pct?.toInt() ?: 0,
        hrv = todayHrv?.value_rmssd_ms?.toInt() ?: 0,
        restingHr = todayRhr?.value_bpm?.toInt() ?: 0
    )
}

private suspend fun loadSleepSummary(ctx: android.content.Context, token: String, date: String): SleepSummary? {
    val db = SupabaseMetricsService(ctx)
    
    // Fetch sleep metrics for today
    val durationList = try {
        db.fetchSleepDurationDaily(token, 1)
    } catch (_: Exception) {
        emptyList()
    }
    
    val scoreList = try {
        db.fetchSleepScoreDaily(token, 1)
    } catch (_: Exception) {
        emptyList()
    }
    
    val efficiencyList = try {
        db.fetchSleepEfficiencyDaily(token, 1)
    } catch (_: Exception) {
        emptyList()
    }
    
    // Check if we have data for today
    val todayDuration = durationList.find { it.date == date }
    val todayScore = scoreList.find { it.date == date }
    val todayEfficiency = efficiencyList.find { it.date == date }
    
    if (todayDuration == null && todayScore == null && todayEfficiency == null) {
        return null
    }
    
    return SleepSummary(
        durationHours = todayDuration?.value_hours ?: 0.0,
        sleepScore = todayScore?.value_pct?.toInt() ?: 0,
        efficiency = todayEfficiency?.value_pct?.toInt() ?: 0
    )
}
