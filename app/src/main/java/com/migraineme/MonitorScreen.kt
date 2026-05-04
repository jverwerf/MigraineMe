package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.BubbleChart
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
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
    // Bridge: read environment display metrics from new MetricDisplayStore, convert to legacy keys for EnvironmentCard
    var weatherDisplayMetrics by remember {
        mutableStateOf(
            MetricDisplayStore.getDisplayMetrics(ctx, "environment")
                .map { MetricRegistry.toLegacyKey(it) }
                .ifEmpty { WeatherCardConfigStore.load(ctx).weatherDisplayMetrics }
        )
    }
    var sleepConfig by remember { mutableStateOf(SleepCardConfigStore.load(ctx)) }
    // Bridge: read sleep display metrics from new MetricDisplayStore, convert to legacy keys
    var sleepDisplayMetrics by remember {
        mutableStateOf(
            MetricDisplayStore.getDisplayMetrics(ctx, "sleep")
                .map { MetricRegistry.toLegacyKey(it) }
                .ifEmpty { SleepCardConfigStore.load(ctx).sleepDisplayMetrics }
        )
    }
    var physicalConfig by remember { mutableStateOf(PhysicalCardConfigStore.load(ctx)) }
    var physicalDisplayMetrics by remember {
        mutableStateOf(
            MetricDisplayStore.getDisplayMetrics(ctx, "physical")
                .map { MetricRegistry.toLegacyKey(it) }
                .ifEmpty { PhysicalCardConfigStore.load(ctx).physicalDisplayMetrics }
        )
    }
    var mentalConfig by remember { mutableStateOf(MentalCardConfigStore.load(ctx)) }
    // Bridge: read mental display metrics from new MetricDisplayStore, convert to legacy keys
    var mentalDisplayMetrics by remember {
        mutableStateOf(
            MetricDisplayStore.getDisplayMetrics(ctx, "mental")
                .map { MetricRegistry.toLegacyKey(it) }
                .ifEmpty { MentalCardConfigStore.load(ctx).mentalDisplayMetrics }
        )
    }
    
    // Refresh config when returning to screen
    LaunchedEffect(Unit) {
        cardConfig = MonitorCardConfigStore.load(ctx)
        weatherConfig = WeatherCardConfigStore.load(ctx)
        weatherDisplayMetrics = MetricDisplayStore.getDisplayMetrics(ctx, "environment")
            .map { MetricRegistry.toLegacyKey(it) }
            .ifEmpty { WeatherCardConfigStore.load(ctx).weatherDisplayMetrics }
        sleepConfig = SleepCardConfigStore.load(ctx)
        sleepDisplayMetrics = MetricDisplayStore.getDisplayMetrics(ctx, "sleep")
            .map { MetricRegistry.toLegacyKey(it) }
            .ifEmpty { SleepCardConfigStore.load(ctx).sleepDisplayMetrics }
        physicalConfig = PhysicalCardConfigStore.load(ctx)
        physicalDisplayMetrics = MetricDisplayStore.getDisplayMetrics(ctx, "physical")
            .map { MetricRegistry.toLegacyKey(it) }
            .ifEmpty { PhysicalCardConfigStore.load(ctx).physicalDisplayMetrics }
        mentalConfig = MentalCardConfigStore.load(ctx)
        mentalDisplayMetrics = MetricDisplayStore.getDisplayMetrics(ctx, "mental")
            .map { MetricRegistry.toLegacyKey(it) }
            .ifEmpty { MentalCardConfigStore.load(ctx).mentalDisplayMetrics }
    }
    
    // Nutrition data — use same service as MonitorNutritionScreen for full metric coverage
    var nutritionItems by remember { mutableStateOf<List<NutritionLogItem>>(emptyList()) }
    var nutritionLoading by remember { mutableStateOf(true) }
    
    // Weather data
    var weatherSummary by remember { mutableStateOf<WeatherSummary?>(null) }
    var weatherLoading by remember { mutableStateOf(true) }
    
    // Physical health data
    var physicalSummary by remember { mutableStateOf<PhysicalSummary?>(null) }
    var physicalLoading by remember { mutableStateOf(true) }
    var physicalSources by remember { mutableStateOf<List<String>>(emptyList()) }
    
    // Mental health data
    var mentalSummary by remember { mutableStateOf<MentalSummary?>(null) }
    var mentalLoading by remember { mutableStateOf(true) }
    
    // Sleep data
    var sleepSummary by remember { mutableStateOf<SleepSummary?>(null) }
    var sleepLoading by remember { mutableStateOf(true) }

    // True when at least one health data source (Health Connect or any wearable
    // OAuth token) is set up. Used to suppress the "connect a wearable" empty-
    // state nag on the Sleep / Physical cards once a source is already configured.
    var hasHealthSource by remember { mutableStateOf(false) }
    
    // Menstruation data
    var menstruationSettings by remember { mutableStateOf<MenstruationSettings?>(null) }
    var menstruationEnabled by remember { mutableStateOf(false) }
    var menstruationLoading by remember { mutableStateOf(true) }

    // Set of metrics the user has enabled in Data Settings. Metrics not in this
    // set must not appear on Monitor graphs.
    var enabledMetrics by remember { mutableStateOf<Set<String>>(emptySet()) }

    // Risk history data
    var riskHistory by remember { mutableStateOf<List<SupabaseDbService.RiskScoreDailyRow>>(emptyList()) }
    var migraineDates by remember { mutableStateOf<Set<String>>(emptySet()) }
    var riskHistoryLoading by remember { mutableStateOf(true) }
    var riskLive by remember { mutableStateOf<SupabaseDbService.RiskScoreLiveRow?>(null) }
    // Respect Data Settings: favs whose underlying metric is disabled are auto-replaced
    // with the next available pool entry so the Risk card keeps 3 slots when possible.
    val effectiveFavs = remember(enabledMetrics) {
        getEffectiveFavOfFavsRespectingSettings(ctx, enabledMetrics)
    }
    // Resolver for fav-of-fav values
    fun resolveFavValue(key: String): String {
        val parts = key.split(":", limit = 2)
        if (parts.size != 2) return "\u2014"
        val (cat, metric) = parts
        return when (cat) {
            "sleep" -> sleepSummary?.let { sleepMetricDisplayValue(it, metric) } ?: "\u2014"
            "weather" -> weatherSummary?.let { getWeatherMetricValue(it, metric) } ?: "\u2014"
            "physical" -> physicalSummary?.let { physicalMetricDisplayValue(it, metric) } ?: "\u2014"
            "mental" -> mentalSummary?.displayValue(metric) ?: "\u2014"
            "nutrition" -> {
                if (nutritionItems.isEmpty()) return "\u2014"
                val total = nutritionItems.sumOf { it.metricValue(metric) ?: 0.0 }
                if (total <= 0) return "\u2014"
                val registryKey = MetricRegistry.nutritionRegistryKey(metric)
                val unit = MetricRegistry.unit(registryKey)
                val isRisk = metric in setOf("tyramine_exposure", "alcohol_exposure", "gluten_exposure")
                if (isRisk) {
                    when { total >= 3 -> "High"; total >= 2 -> "Med"; total >= 1 -> "Low"; else -> "None" }
                } else if (total >= 10) "${total.toInt()}$unit" else String.format("%.1f$unit", total)
            }
            else -> "\u2014"
        }
    }
    
    LaunchedEffect(authState.accessToken, today) {
        val token = authState.accessToken
        if (token.isNullOrBlank()) {
            nutritionLoading = false
            weatherLoading = false
            physicalLoading = false
            sleepLoading = false
            mentalLoading = false
            menstruationLoading = false
            riskHistoryLoading = false
            return@LaunchedEffect
        }
        
        // Load enabled metric settings up-front — all category loaders depend on this
        withContext(Dispatchers.IO) {
            enabledMetrics = try {
                val edge = EdgeFunctionsService()
                edge.getMetricSettings(ctx).filter { it.enabled }.map { it.metric }.toSet()
            } catch (_: Exception) {
                emptySet()
            }

            // Detect whether any health source is configured so the empty-state
            // hint on Sleep / Physical cards can drop the "connect a wearable" nag.
            val hcConnected = ctx.getSharedPreferences("health_connect", android.content.Context.MODE_PRIVATE)
                .getBoolean("is_connected", false)
            val anyWearable = try { WhoopTokenStore(ctx).load() != null } catch (_: Exception) { false } ||
                              try { OuraTokenStore(ctx).load() != null } catch (_: Exception) { false } ||
                              try { PolarTokenStore(ctx).load() != null } catch (_: Exception) { false } ||
                              try { GarminTokenStore(ctx).load() != null } catch (_: Exception) { false }
            hasHealthSource = hcConnected || anyWearable
        }

        // Load risk history + migraines
        withContext(Dispatchers.IO) {
            try {
                val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                riskLive = db.getRiskScoreLive(token)
                riskHistory = db.getRiskScoreDaily(token, 14).sortedBy { it.date }
                val migraines = db.getMigraines(token)
                migraineDates = migraines.map { it.startAt.take(10) }.toSet()
                android.util.Log.d("MonitorScreen", "Risk history: ${riskHistory.size} days, migraines on: $migraineDates")
            } catch (e: Exception) {
                android.util.Log.e("MonitorScreen", "Risk history load failed: ${e.message}", e)
            }
            riskHistoryLoading = false
        }
        
        // Load nutrition items (same as MonitorNutritionScreen — supports all 34 nutrients)
        withContext(Dispatchers.IO) {
            nutritionItems = if ("nutrition" !in enabledMetrics) {
                emptyList()
            } else {
                try {
                    USDAFoodSearchService(ctx).getTodayNutritionItems()
                } catch (_: Exception) {
                    emptyList()
                }
            }
            nutritionLoading = false
        }
        
        // Load weather summary
        withContext(Dispatchers.IO) {
            weatherSummary = try {
                loadWeatherSummary(ctx, token, today, enabledMetrics)
            } catch (_: Exception) {
                null
            }
            weatherLoading = false
        }

        // Load physical health summary
        withContext(Dispatchers.IO) {
            physicalSummary = try {
                loadPhysicalSummary(ctx, token, today, enabledMetrics)
            } catch (_: Exception) {
                null
            }
            physicalSources = try {
                fetchSourcesForDate(ctx, token, today, listOf("hrv_daily", "resting_hr_daily", "recovery_score_daily", "steps_daily"))
            } catch (_: Exception) {
                emptyList()
            }
            physicalLoading = false
        }

        // Load mental health summary
        withContext(Dispatchers.IO) {
            mentalSummary = try {
                loadMentalSummary(ctx, token, today, enabledMetrics)
            } catch (_: Exception) {
                null
            }
            mentalLoading = false
        }

        // Load sleep summary
        withContext(Dispatchers.IO) {
            sleepSummary = try {
                loadSleepSummary(ctx, token, today, enabledMetrics)
            } catch (_: Exception) {
                null
            }
            sleepLoading = false
        }

        // Load menstruation settings AND check if enabled
        withContext(Dispatchers.IO) {
            try {
                menstruationEnabled = "menstruation" in enabledMetrics

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
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {
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
                // Ensure CARD_RISK is in the order even for existing users
                if (cardId == MonitorCardConfig.CARD_RISK && cardConfig.isVisible(cardId)) {
                    RiskCard(
                        riskLive = riskLive,
                        isLoading = riskHistoryLoading,
                        favOfFavs = effectiveFavs.map { it.key to it.label },
                        resolveFavValue = ::resolveFavValue,
                        onClick = { navController.navigate(Routes.MONITOR_RISK) }
                    )
                } else if (cardConfig.isVisible(cardId)) {
                    when (cardId) {
                        MonitorCardConfig.CARD_NUTRITION -> {
                            val nutDisplayKeys = remember { MetricDisplayStore.getDisplayMetrics(ctx, "nutrition") }
                            NutritionCard(
                                nutritionLoading = nutritionLoading,
                                nutritionItems = nutritionItems,
                                displayMetrics = nutDisplayKeys.map { MetricRegistry.nutritionLegacyKey(it) },
                                onClick = { navController.navigate(Routes.MONITOR_NUTRITION) }
                            )
                        }
                        MonitorCardConfig.CARD_ENVIRONMENT -> {
                            EnvironmentCard(
                                weatherLoading = weatherLoading,
                                weatherSummary = weatherSummary,
                                displayMetrics = filterWeatherDisplayMetrics(weatherDisplayMetrics, enabledMetrics).take(3),
                                onClick = { navController.navigate(Routes.MONITOR_ENVIRONMENT) }
                            )
                        }
                        MonitorCardConfig.CARD_PHYSICAL -> {
                            PhysicalHealthCard(
                                physicalLoading = physicalLoading,
                                physicalSummary = physicalSummary,
                                displayMetrics = filterPhysicalDisplayMetrics(physicalDisplayMetrics, enabledMetrics).take(3),
                                sources = physicalSources,
                                hasHealthSource = hasHealthSource,
                                onClick = { navController.navigate(Routes.MONITOR_PHYSICAL) }
                            )
                        }
                        MonitorCardConfig.CARD_SLEEP -> {
                            SleepCard(
                                sleepLoading = sleepLoading,
                                sleepSummary = sleepSummary,
                                displayMetrics = filterSleepDisplayMetrics(sleepDisplayMetrics, enabledMetrics).take(3),
                                hasHealthSource = hasHealthSource,
                                onClick = { navController.navigate(Routes.MONITOR_SLEEP) }
                            )
                        }
                        MonitorCardConfig.CARD_MENTAL -> {
                            MentalHealthCard(
                                mentalLoading = mentalLoading,
                                mentalSummary = mentalSummary,
                                displayMetrics = filterMentalDisplayMetrics(mentalDisplayMetrics, enabledMetrics).take(3),
                                onClick = { navController.navigate(Routes.MONITOR_MENTAL) }
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
    nutritionItems: List<NutritionLogItem>,
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
            val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                displayMetrics.forEachIndexed { index, metric ->
                    val total = nutritionItems.sumOf { it.metricValue(metric) ?: 0.0 }
                    val registryKey = MetricRegistry.nutritionRegistryKey(metric)
                    val label = MetricRegistry.label(registryKey)
                    val unit = MetricRegistry.unit(registryKey)
                    val formatted = if (total >= 10) "${total.toInt()}$unit" else String.format("%.1f$unit", total)
                    val color = slotColors.getOrElse(index) { slotColors.last() }
                    
                    NutritionMetric(
                        label = label,
                        value = formatted,
                        color = color
                    )
                }
            }
            
            Spacer(Modifier.height(6.dp))
            
            val mealTypes = nutritionItems.mapNotNull { it.mealType?.takeIf { m -> m.isNotBlank() && m != "unknown" } }.toSet()
            Text(
                "${mealTypes.size} meals • ${nutritionItems.size} items today",
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun EnvironmentCard(
    weatherLoading: Boolean,
    weatherSummary: WeatherSummary?,
    displayMetrics: List<String>,
    onClick: () -> Unit
) {
    MonitorCategoryCard(
        icon = Icons.Outlined.Cloud,
        title = "Environment",
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
                    val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))
                    displayMetrics.take(3).forEachIndexed { index, metric ->
                        val label = WeatherCardConfig.WEATHER_METRIC_LABELS[metric] ?: metric
                        val value = getWeatherMetricValue(weather, metric)
                        val unit = WeatherCardConfig.WEATHER_METRIC_UNITS[metric] ?: ""
                        Text(
                            "$label: $value$unit",
                            color = slotColors.getOrElse(index) { slotColors.last() },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

internal fun getWeatherMetricValue(weather: WeatherSummary, metric: String): String {
    return when (metric) {
        WeatherCardConfig.METRIC_TEMPERATURE -> weather.temperature.toString()
        WeatherCardConfig.METRIC_PRESSURE -> weather.pressure.toString()
        WeatherCardConfig.METRIC_HUMIDITY -> weather.humidity.toString()
        WeatherCardConfig.METRIC_WIND_SPEED -> String.format("%.1f", weather.windSpeed)
        WeatherCardConfig.METRIC_UV_INDEX -> weather.uvIndex.toString()
        WeatherCardConfig.METRIC_ALTITUDE -> weather.altitudeMaxM?.let { String.format("%.0f", it) } ?: "—"
        WeatherCardConfig.METRIC_ALTITUDE_CHANGE -> weather.altitudeChangeM?.let { String.format("%.0f", it) } ?: "—"
        else -> "—"
    }
}

@Composable
private fun PhysicalHealthCard(
    physicalLoading: Boolean,
    physicalSummary: PhysicalSummary?,
    displayMetrics: List<String>,
    sources: List<String>,
    hasHealthSource: Boolean,
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
            Text(
                if (hasHealthSource) "No recent data" else "No physical health data",
                color = AppTheme.SubtleTextColor
            )
            if (!hasHealthSource) {
                Text("Connect a wearable to see data", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            val physical = physicalSummary
            val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                displayMetrics.forEachIndexed { index, metric ->
                    val label = PhysicalCardConfig.labelFor(metric)
                    val color = slotColors.getOrElse(index) { slotColors.last() }
                    val value = when (metric) {
                        PhysicalCardConfig.METRIC_RECOVERY -> physical.recoveryScore?.let { "${it.toInt()}%" } ?: "—"
                        PhysicalCardConfig.METRIC_HRV -> physical.hrv?.let { "${it.toInt()} ms" } ?: "—"
                        PhysicalCardConfig.METRIC_RESTING_HR -> physical.restingHr?.let { "${it.toInt()} bpm" } ?: "—"
                        PhysicalCardConfig.METRIC_SPO2 -> physical.spo2?.let { "${it.toInt()}%" } ?: "—"
                        PhysicalCardConfig.METRIC_SKIN_TEMP -> physical.skinTemp?.let { String.format("%.1f°C", it) } ?: "—"
                        PhysicalCardConfig.METRIC_RESPIRATORY_RATE -> physical.respiratoryRate?.let { String.format("%.1f", it) } ?: "—"
                        PhysicalCardConfig.METRIC_STRESS -> physical.stress?.let { String.format("%.0f", it) } ?: "—"
                        PhysicalCardConfig.METRIC_HIGH_HR_ZONES -> physical.highHrZones?.let { "${it.toInt()} min" } ?: "—"
                        PhysicalCardConfig.METRIC_STEPS -> physical.steps?.let { "%,d".format(it) } ?: "—"
                        PhysicalCardConfig.METRIC_WEIGHT -> physical.weight?.let { String.format("%.1f kg", it) } ?: "—"
                        PhysicalCardConfig.METRIC_BODY_FAT -> physical.bodyFat?.let { String.format("%.1f%%", it) } ?: "—"
                        PhysicalCardConfig.METRIC_BLOOD_PRESSURE -> if (physical.bpSystolic != null) "${physical.bpSystolic}/${physical.bpDiastolic}" else "—"
                        PhysicalCardConfig.METRIC_BLOOD_GLUCOSE -> physical.bloodGlucose?.let { String.format("%.0f", it) } ?: "—"
                        else -> "—"
                    }
                    PhysicalMetric(label, value, color)
                }
            }
            if (sources.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SourceBadgeRow(sources)
            }
        }
    }
}

@Composable
private fun SleepCard(
    sleepLoading: Boolean,
    sleepSummary: SleepSummary?,
    displayMetrics: List<String>,
    hasHealthSource: Boolean,
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
            Text(
                if (hasHealthSource) "No recent sleep data" else "No sleep data",
                color = AppTheme.SubtleTextColor
            )
            if (!hasHealthSource) {
                Text("Enable phone sleep or connect a wearable", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            val sleep = sleepSummary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))
                displayMetrics.forEachIndexed { index, metric ->
                    val label = SleepCardConfig.labelFor(metric)
                    val color = slotColors.getOrElse(index) { slotColors.last() }
                    val value = getSleepSummaryMetricValue(sleep, metric)
                    SleepMetric(label, value, color)
                }
            }
            if (sleep.sourceLabel.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                SourceBadgeRow(listOf(sleep.sourceLabel.lowercase()))
            }
        }
    }
}

@Composable
private fun MentalHealthCard(
    mentalLoading: Boolean,
    mentalSummary: MentalSummary?,
    displayMetrics: List<String>,
    onClick: () -> Unit
) {
    MonitorCategoryCard(
        icon = Icons.Outlined.BubbleChart,
        title = "Mental Health",
        iconTint = Color(0xFFBA68C8),
        onClick = onClick
    ) {
        if (mentalLoading) {
            Text("Loading...", color = AppTheme.SubtleTextColor)
        } else if (mentalSummary == null) {
            Text("No mental health data", color = AppTheme.SubtleTextColor)
            Text("Enable metrics in Data Settings", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall)
        } else {
            val mental = mentalSummary
            val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                displayMetrics.forEachIndexed { index, metric ->
                    val label = MentalCardConfig.labelFor(metric)
                    val color = slotColors.getOrElse(index) { slotColors.last() }
                    val value = mental.displayValue(metric)
                    MentalMetric(label, value, color)
                }
            }
        }
    }
}

@Composable
private fun MentalMetric(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
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

private fun getSleepSummaryMetricValue(sleep: SleepSummary, metric: String): String {
    return when (metric) {
        SleepCardConfig.METRIC_DURATION -> String.format("%.1fh", sleep.durationHours)
        SleepCardConfig.METRIC_FELL_ASLEEP -> sleep.fellAsleepDisplay ?: "—"
        SleepCardConfig.METRIC_WOKE_UP -> sleep.wokeUpDisplay ?: "—"
        SleepCardConfig.METRIC_SCORE -> if (sleep.sleepScore > 0) "${sleep.sleepScore}%" else "—"
        SleepCardConfig.METRIC_EFFICIENCY -> if (sleep.efficiency > 0) "${sleep.efficiency}%" else "—"
        SleepCardConfig.METRIC_DISTURBANCES -> sleep.disturbances?.toString() ?: "—"
        SleepCardConfig.METRIC_STAGES_DEEP -> sleep.stagesDeep?.let { formatSleepHM(it) } ?: "—"
        SleepCardConfig.METRIC_STAGES_REM -> sleep.stagesRem?.let { formatSleepHM(it) } ?: "—"
        SleepCardConfig.METRIC_STAGES_LIGHT -> sleep.stagesLight?.let { formatSleepHM(it) } ?: "—"
        else -> "—"
    }
}

private fun formatSleepHM(hm: Double): String {
    val totalMinutes = (hm * 60).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h${minutes}m" else "${minutes}m"
}

// Data classes for summaries

internal data class WeatherSummary(
    val temperature: Int,
    val condition: String,
    val humidity: Int,
    val pressure: Int,
    val uvIndex: Int,
    val windSpeed: Double = 0.0,
    val altitudeMaxM: Double? = null,
    val altitudeChangeM: Double? = null
)

internal data class PhysicalSummary(
    val recoveryScore: Double? = null,
    val hrv: Double? = null,
    val restingHr: Double? = null,
    val spo2: Double? = null,
    val skinTemp: Double? = null,
    val respiratoryRate: Double? = null,
    val stress: Double? = null,
    val highHrZones: Double? = null,
    val steps: Int? = null,
    val weight: Double? = null,
    val bodyFat: Double? = null,
    val bpSystolic: Int? = null,
    val bpDiastolic: Int? = null,
    val bloodGlucose: Double? = null
)

internal data class SleepSummary(
    val durationHours: Double,
    val sleepScore: Int,
    val efficiency: Int,
    val fellAsleepDisplay: String? = null,
    val wokeUpDisplay: String? = null,
    val sourceLabel: String = "",
    val disturbances: Int? = null,
    val stagesDeep: Double? = null,
    val stagesRem: Double? = null,
    val stagesLight: Double? = null
)

// Data loading functions

internal suspend fun loadWeatherSummary(
    ctx: android.content.Context,
    token: String,
    date: String,
    enabledMetrics: Set<String> = emptySet()
): WeatherSummary? {
    return withContext(Dispatchers.IO) {
        // If the user has disabled all environment metrics, don't even load.
        val anyWeatherEnabled = WEATHER_METRIC_KEYS.any { it in enabledMetrics }
                || "user_location_daily" in enabledMetrics
                || "thunderstorm_daily" in enabledMetrics
        if (enabledMetrics.isNotEmpty() && !anyWeatherEnabled) return@withContext null

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

            // Fetch altitude from user_location_daily
            var altitudeMaxM: Double? = null
            var altitudeChangeM: Double? = null
            try {
                val locUrl = "${BuildConfig.SUPABASE_URL}/rest/v1/user_location_daily?user_id=eq.${SessionStore.readUserId(ctx)}&date=eq.$date&select=altitude_max_m,altitude_change_m&limit=1"
                val locReq = Request.Builder().url(locUrl).get()
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .addHeader("Authorization", "Bearer $token").build()
                val locResp = client.newCall(locReq).execute()
                val locBody = locResp.body?.string()
                if (locResp.isSuccessful && !locBody.isNullOrBlank()) {
                    val locArr = org.json.JSONArray(locBody)
                    if (locArr.length() > 0) {
                        val locObj = locArr.getJSONObject(0)
                        altitudeMaxM = locObj.optDouble("altitude_max_m").takeIf { !it.isNaN() }
                        altitudeChangeM = locObj.optDouble("altitude_change_m").takeIf { !it.isNaN() }
                    }
                }
            } catch (_: Exception) { }

            WeatherSummary(
                temperature = obj.optDouble("temp_c_mean", 0.0).toInt(),
                condition = condition,
                humidity = obj.optInt("humidity_pct_mean", 0),
                pressure = obj.optDouble("pressure_hpa_mean", 0.0).toInt(),
                uvIndex = obj.optDouble("uv_index_max", 0.0).toInt(),
                windSpeed = obj.optDouble("wind_speed_mps_mean", 0.0),
                altitudeMaxM = altitudeMaxM,
                altitudeChangeM = altitudeChangeM
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

private val WEATHER_METRIC_KEYS = setOf(
    "temperature_daily",
    "pressure_daily",
    "humidity_daily",
    "wind_daily",
    "uv_daily"
)

internal fun filterWeatherDisplayMetrics(
    displayMetrics: List<String>,
    enabledMetrics: Set<String>
): List<String> {
    if (enabledMetrics.isEmpty()) return displayMetrics
    return displayMetrics.filter { metric ->
        when (metric) {
            WeatherCardConfig.METRIC_TEMPERATURE -> "temperature_daily" in enabledMetrics
            WeatherCardConfig.METRIC_PRESSURE -> "pressure_daily" in enabledMetrics
            WeatherCardConfig.METRIC_HUMIDITY -> "humidity_daily" in enabledMetrics
            WeatherCardConfig.METRIC_WIND_SPEED -> "wind_daily" in enabledMetrics
            WeatherCardConfig.METRIC_UV_INDEX -> "uv_daily" in enabledMetrics
            WeatherCardConfig.METRIC_ALTITUDE,
            WeatherCardConfig.METRIC_ALTITUDE_CHANGE -> "user_location_daily" in enabledMetrics
            else -> true
        }
    }
}

// Map a Sleep card display metric key to its metric_settings table key.
private fun sleepMetricToTable(metric: String): String = when (metric) {
    SleepCardConfig.METRIC_DURATION -> "sleep_duration_daily"
    SleepCardConfig.METRIC_SCORE -> "sleep_score_daily"
    SleepCardConfig.METRIC_EFFICIENCY -> "sleep_efficiency_daily"
    SleepCardConfig.METRIC_DISTURBANCES -> "sleep_disturbances_daily"
    SleepCardConfig.METRIC_STAGES_DEEP,
    SleepCardConfig.METRIC_STAGES_REM,
    SleepCardConfig.METRIC_STAGES_LIGHT -> "sleep_stages_daily"
    SleepCardConfig.METRIC_FELL_ASLEEP -> "fell_asleep_time_daily"
    SleepCardConfig.METRIC_WOKE_UP -> "woke_up_time_daily"
    else -> ""
}

internal fun filterSleepDisplayMetrics(
    displayMetrics: List<String>,
    enabledMetrics: Set<String>
): List<String> {
    if (enabledMetrics.isEmpty()) return displayMetrics
    return displayMetrics.filter { sleepMetricToTable(it) in enabledMetrics }
}

internal fun filterPhysicalDisplayMetrics(
    displayMetrics: List<String>,
    enabledMetrics: Set<String>
): List<String> {
    if (enabledMetrics.isEmpty()) return displayMetrics
    return displayMetrics.filter { PhysicalCardConfig.metricToTable(it) in enabledMetrics }
}

internal fun filterMentalDisplayMetrics(
    displayMetrics: List<String>,
    enabledMetrics: Set<String>
): List<String> {
    if (enabledMetrics.isEmpty()) return displayMetrics
    return displayMetrics.filter { metric ->
        mentalMetricSettingsKeys(metric).any { it in enabledMetrics }
    }
}

// Settings keys that can satisfy a mental display metric. Noise toggles store
// "ambient_noise_samples" while the daily aggregate lives in "ambient_noise_index_daily" —
// either being enabled means we have data to show.
private fun mentalMetricSettingsKeys(metric: String): List<String> = when (metric) {
    MentalCardConfig.METRIC_NOISE,
    MentalCardConfig.METRIC_NOISE_HIGH,
    MentalCardConfig.METRIC_NOISE_AVG,
    MentalCardConfig.METRIC_NOISE_LOW -> listOf("ambient_noise_index_daily", "ambient_noise_samples")
    else -> listOf(MentalCardConfig.metricToTable(metric))
}

// Resolve a "category:metric" fav-of-fav key to the metric_settings table key.
// Returns null when the entry isn't gated by metric_settings (e.g. nutrition) or
// can't be mapped — callers should treat null as "always allowed".
private fun favEntryToTable(key: String): String? {
    val parts = key.split(":", limit = 2)
    if (parts.size != 2) return null
    val (cat, metric) = parts
    return when (cat) {
        "weather" -> when (metric) {
            WeatherCardConfig.METRIC_TEMPERATURE -> "temperature_daily"
            WeatherCardConfig.METRIC_PRESSURE -> "pressure_daily"
            WeatherCardConfig.METRIC_HUMIDITY -> "humidity_daily"
            WeatherCardConfig.METRIC_WIND_SPEED -> "wind_daily"
            WeatherCardConfig.METRIC_UV_INDEX -> "uv_daily"
            WeatherCardConfig.METRIC_ALTITUDE,
            WeatherCardConfig.METRIC_ALTITUDE_CHANGE -> "user_location_daily"
            else -> null
        }
        "sleep" -> sleepMetricToTable(metric).ifBlank { null }
        "physical" -> PhysicalCardConfig.metricToTable(metric).ifBlank { null }
        "mental" -> MentalCardConfig.metricToTable(metric).ifBlank { null }
        else -> null
    }
}

internal fun filterFavsByEnabled(
    favs: List<FavPoolEntry>,
    enabledMetrics: Set<String>
): List<FavPoolEntry> {
    if (enabledMetrics.isEmpty()) return favs
    return favs.filter { entry ->
        val parts = entry.key.split(":", limit = 2)
        if (parts.size == 2 && parts[0] == "mental") {
            return@filter mentalMetricSettingsKeys(parts[1]).any { it in enabledMetrics }
        }
        val table = favEntryToTable(entry.key) ?: return@filter true
        table in enabledMetrics
    }
}

// Same contract as getEffectiveFavOfFavs but drops entries whose underlying metric
// has been disabled in Data Settings, auto-promoting the next available pool entry
// so the Risk card always fills 3 slots when it can.
internal fun getEffectiveFavOfFavsRespectingSettings(
    context: android.content.Context,
    enabledMetrics: Set<String>
): List<FavPoolEntry> {
    val filteredPool = filterFavsByEnabled(buildFavoritesPool(context), enabledMetrics)
    val saved = RiskCardConfigStore.load(context).favOfFavs
    val validSaved = saved.mapNotNull { key -> filteredPool.find { it.key == key } }
    if (validSaved.size >= 3) return validSaved.take(3)
    val selectedKeys = validSaved.map { it.key }.toSet()
    val remaining = filteredPool.filter { it.key !in selectedKeys }
    return (validSaved + remaining).take(3)
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

internal suspend fun loadPhysicalSummary(
    ctx: android.content.Context,
    token: String,
    date: String,
    enabledMetrics: Set<String> = emptySet()
): PhysicalSummary? {
    val userId = SessionStore.readUserId(ctx) ?: return null
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val key = BuildConfig.SUPABASE_ANON_KEY
    val client = OkHttpClient()
    fun enabled(metric: String): Boolean = enabledMetrics.isEmpty() || metric in enabledMetrics

    fun fetchDouble(table: String, column: String): Double? {
        return try {
            val url = "$base/rest/v1/$table?user_id=eq.$userId&date=eq.$date&select=$column&limit=1"
            val req = Request.Builder().url(url).get()
                .addHeader("apikey", key).addHeader("Authorization", "Bearer $token").build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string()
            if (resp.isSuccessful && !body.isNullOrBlank()) {
                val arr = org.json.JSONArray(body)
                if (arr.length() > 0) {
                    val v = arr.getJSONObject(0).optDouble(column)
                    if (!v.isNaN()) v else null
                } else null
            } else null
        } catch (_: Exception) { null }
    }

    fun fetchInt(table: String, column: String): Int? {
        return try {
            val url = "$base/rest/v1/$table?user_id=eq.$userId&date=eq.$date&select=$column&limit=1"
            val req = Request.Builder().url(url).get()
                .addHeader("apikey", key).addHeader("Authorization", "Bearer $token").build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string()
            if (resp.isSuccessful && !body.isNullOrBlank()) {
                val arr = org.json.JSONArray(body)
                if (arr.length() > 0) {
                    val v = arr.getJSONObject(0).optInt(column, Int.MIN_VALUE)
                    if (v != Int.MIN_VALUE) v else null
                } else null
            } else null
        } catch (_: Exception) { null }
    }

    val recovery = if (enabled("recovery_score_daily")) fetchDouble("recovery_score_daily", "value_pct") else null
    val hrv = if (enabled("hrv_daily")) fetchDouble("hrv_daily", "value_rmssd_ms") else null
    val rhr = if (enabled("resting_hr_daily")) fetchDouble("resting_hr_daily", "value_bpm") else null
    val spo2 = if (enabled("spo2_daily")) fetchDouble("spo2_daily", "value_pct") else null
    val skinTemp = if (enabled("skin_temp_daily")) fetchDouble("skin_temp_daily", "value_celsius") else null
    val respiratoryRate = if (enabled("respiratory_rate_daily")) fetchDouble("respiratory_rate_daily", "value_bpm") else null
    val stress = if (enabled("stress_index_daily")) fetchDouble("stress_index_daily", "value") else null
    val highHrZones = if (enabled("time_in_high_hr_zones_daily")) fetchDouble("time_in_high_hr_zones_daily", "value_minutes") else null
    val steps = if (enabled("steps_daily")) fetchInt("steps_daily", "value_count") else null
    val weight = if (enabled("weight_daily")) fetchDouble("weight_daily", "value_kg") else null
    val bodyFat = if (enabled("body_fat_daily")) fetchDouble("body_fat_daily", "value_pct") else null
    val bloodGlucose = if (enabled("blood_glucose_daily")) fetchDouble("blood_glucose_daily", "value_mgdl") else null

    // Blood pressure needs two columns
    var bpSys: Int? = null
    var bpDia: Int? = null
    if (enabled("blood_pressure_daily")) {
        try {
            val url = "$base/rest/v1/blood_pressure_daily?user_id=eq.$userId&date=eq.$date&select=value_systolic,value_diastolic&limit=1"
            val req = Request.Builder().url(url).get()
                .addHeader("apikey", key).addHeader("Authorization", "Bearer $token").build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string()
            if (resp.isSuccessful && !body.isNullOrBlank()) {
                val arr = org.json.JSONArray(body)
                if (arr.length() > 0) {
                    val obj = arr.getJSONObject(0)
                    val s = obj.optDouble("value_systolic")
                    val d = obj.optDouble("value_diastolic")
                    if (!s.isNaN()) bpSys = s.toInt()
                    if (!d.isNaN()) bpDia = d.toInt()
                }
            }
        } catch (_: Exception) {}
    }

    // If nothing at all, return null
    if (recovery == null && hrv == null && rhr == null && spo2 == null && skinTemp == null &&
        respiratoryRate == null && stress == null && highHrZones == null && steps == null &&
        weight == null && bodyFat == null && bpSys == null && bloodGlucose == null) {
        return null
    }

    return PhysicalSummary(
        recoveryScore = recovery,
        hrv = hrv,
        restingHr = rhr,
        spo2 = spo2,
        skinTemp = skinTemp,
        respiratoryRate = respiratoryRate,
        stress = stress,
        highHrZones = highHrZones,
        steps = steps,
        weight = weight,
        bodyFat = bodyFat,
        bpSystolic = bpSys,
        bpDiastolic = bpDia,
        bloodGlucose = bloodGlucose
    )
}

internal suspend fun loadSleepSummary(
    ctx: android.content.Context,
    token: String,
    date: String,
    enabledMetrics: Set<String> = emptySet()
): SleepSummary? {
    val db = SupabaseMetricsService(ctx)
    fun enabled(metric: String): Boolean = enabledMetrics.isEmpty() || metric in enabledMetrics

    // Fetch sleep metrics for today
    val durationList = if (!enabled("sleep_duration_daily")) emptyList() else {
        try { db.fetchSleepDurationDaily(token, 1) } catch (_: Exception) { emptyList() }
    }

    val scoreList = if (!enabled("sleep_score_daily")) emptyList() else {
        try { db.fetchSleepScoreDaily(token, 1) } catch (_: Exception) { emptyList() }
    }

    val efficiencyList = if (!enabled("sleep_efficiency_daily")) emptyList() else {
        try { db.fetchSleepEfficiencyDaily(token, 1) } catch (_: Exception) { emptyList() }
    }

    // Check if we have data for today
    val todayDuration = durationList.find { it.date == date }
    val todayScore = scoreList.find { it.date == date }
    val todayEfficiency = efficiencyList.find { it.date == date }

    if (todayDuration == null && todayScore == null && todayEfficiency == null) {
        return null
    }

    // Fetch fell asleep / woke up / source via raw query
    val userId = SessionStore.readUserId(ctx)
    var fellAsleepDisplay: String? = null
    var wokeUpDisplay: String? = null
    var sourceLabel = ""

    if (userId != null) {
        val client = okhttp3.OkHttpClient()

        // Fell asleep
        if (enabled("fell_asleep_time_daily")) {
            try {
                val url = "${BuildConfig.SUPABASE_URL}/rest/v1/fell_asleep_time_daily?" +
                        "user_id=eq.$userId&date=eq.$date&select=value_at&limit=1"
                val request = okhttp3.Request.Builder().url(url).get()
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .addHeader("Authorization", "Bearer $token").build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val arr = org.json.JSONArray(body)
                    if (arr.length() > 0) {
                        val valueAt = arr.getJSONObject(0).optString("value_at", "")
                        if (valueAt.isNotBlank()) {
                            fellAsleepDisplay = try {
                                val zdt = java.time.ZonedDateTime.parse(valueAt)
                                val local = zdt.withZoneSameInstant(java.time.ZoneId.systemDefault())
                                local.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
                            } catch (_: Exception) { valueAt.take(5) }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Woke up
        if (enabled("woke_up_time_daily")) {
            try {
                val url = "${BuildConfig.SUPABASE_URL}/rest/v1/woke_up_time_daily?" +
                        "user_id=eq.$userId&date=eq.$date&select=value_at&limit=1"
                val request = okhttp3.Request.Builder().url(url).get()
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .addHeader("Authorization", "Bearer $token").build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val arr = org.json.JSONArray(body)
                    if (arr.length() > 0) {
                        val valueAt = arr.getJSONObject(0).optString("value_at", "")
                        if (valueAt.isNotBlank()) {
                            wokeUpDisplay = try {
                                val zdt = java.time.ZonedDateTime.parse(valueAt)
                                val local = zdt.withZoneSameInstant(java.time.ZoneId.systemDefault())
                                local.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
                            } catch (_: Exception) { valueAt.take(5) }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        
        // Source
        try {
            val url = "${BuildConfig.SUPABASE_URL}/rest/v1/sleep_duration_daily?" +
                    "user_id=eq.$userId&date=eq.$date&select=source&limit=1"
            val request = okhttp3.Request.Builder().url(url).get()
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token").build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            if (response.isSuccessful && !body.isNullOrBlank()) {
                val arr = org.json.JSONArray(body)
                if (arr.length() > 0) {
                    val src = arr.getJSONObject(0).optString("source", "")
                    sourceLabel = sourceDisplayLabel(src, ctx)
                }
            }
        } catch (_: Exception) {}
    }
    
    // Fetch disturbances and stages (wearable-only, may be null)
    val todayDisturbances = if (!enabled("sleep_disturbances_daily")) null else {
        try {
            db.fetchSleepDisturbancesDaily(token, 1).find { it.date == date }
        } catch (_: Exception) { null }
    }

    val todayStages = if (!enabled("sleep_stages_daily")) null else {
        try {
            db.fetchSleepStagesDaily(token, 1).find { it.date == date }
        } catch (_: Exception) { null }
    }
    
    return SleepSummary(
        durationHours = todayDuration?.value_hours ?: 0.0,
        sleepScore = todayScore?.value_pct?.toInt() ?: 0,
        efficiency = todayEfficiency?.value_pct?.toInt() ?: 0,
        fellAsleepDisplay = fellAsleepDisplay,
        wokeUpDisplay = wokeUpDisplay,
        sourceLabel = sourceLabel,
        disturbances = todayDisturbances?.value_count,
        stagesDeep = todayStages?.value_sws_hm,
        stagesRem = todayStages?.value_rem_hm,
        stagesLight = todayStages?.value_light_hm
    )
}

// ─── Mental Health Summary ──────────────────────────────────────────────────

internal data class MentalSummary(
    val stress: Double?,
    val screenTimeHours: Double?,
    val lateScreenTimeHours: Double?,
    val noiseIndex: Double?,
    val brightness: Double?,
    val volumePct: Double?,
    val darkModeHours: Double?,
    val unlockCount: Int?
) {
    fun displayValue(metric: String): String = when (metric) {
        MentalCardConfig.METRIC_STRESS -> stress?.let { String.format("%.0f", it) } ?: "—"
        MentalCardConfig.METRIC_SCREEN_TIME -> screenTimeHours?.let { String.format("%.1fh", it) } ?: "—"
        MentalCardConfig.METRIC_LATE_SCREEN_TIME -> lateScreenTimeHours?.let { String.format("%.1fh", it) } ?: "—"
        MentalCardConfig.METRIC_NOISE -> noiseIndex?.let { String.format("%.0f dB", it) } ?: "—"
        MentalCardConfig.METRIC_BRIGHTNESS -> brightness?.let { String.format("%.0f", it) } ?: "—"
        MentalCardConfig.METRIC_VOLUME -> volumePct?.let { "${it.toInt()}%" } ?: "—"
        MentalCardConfig.METRIC_DARK_MODE -> darkModeHours?.let { String.format("%.1fh", it) } ?: "—"
        MentalCardConfig.METRIC_UNLOCKS -> unlockCount?.let { "$it" } ?: "—"
        else -> "—"
    }
}

internal suspend fun loadMentalSummary(
    ctx: android.content.Context,
    token: String,
    date: String,
    enabledMetrics: Set<String> = emptySet()
): MentalSummary? {
    val userId = SessionStore.readUserId(ctx) ?: return null
    val client = OkHttpClient()
    val base = BuildConfig.SUPABASE_URL.trimEnd('/')
    val key = BuildConfig.SUPABASE_ANON_KEY
    fun enabled(metric: String): Boolean = enabledMetrics.isEmpty() || metric in enabledMetrics

    fun fetchDouble(table: String, column: String): Double? {
        return try {
            val url = "$base/rest/v1/$table?user_id=eq.$userId&date=eq.$date&select=$column&limit=1"
            val req = Request.Builder().url(url).get()
                .addHeader("apikey", key).addHeader("Authorization", "Bearer $token").build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string()
            if (resp.isSuccessful && !body.isNullOrBlank()) {
                val arr = org.json.JSONArray(body)
                if (arr.length() > 0) arr.getJSONObject(0).optDouble(column).takeIf { !it.isNaN() } else null
            } else null
        } catch (_: Exception) { null }
    }

    fun fetchInt(table: String, column: String): Int? {
        return try {
            val url = "$base/rest/v1/$table?user_id=eq.$userId&date=eq.$date&select=$column&limit=1"
            val req = Request.Builder().url(url).get()
                .addHeader("apikey", key).addHeader("Authorization", "Bearer $token").build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string()
            if (resp.isSuccessful && !body.isNullOrBlank()) {
                val arr = org.json.JSONArray(body)
                if (arr.length() > 0) {
                    val v = arr.getJSONObject(0).optInt(column, Int.MIN_VALUE)
                    if (v != Int.MIN_VALUE) v else null
                } else null
            } else null
        } catch (_: Exception) { null }
    }

    /** Fetch today's samples from a _samples table, compute AVG of a numeric column */
    fun fetchSamplesAvg(table: String, column: String, tsColumn: String = "sampled_at"): Double? {
        return try {
            val url = "$base/rest/v1/$table?user_id=eq.$userId&${tsColumn}=gte.${date}T00:00:00&${tsColumn}=lt.${date}T23:59:59&select=$column"
            val req = Request.Builder().url(url).get()
                .addHeader("apikey", key).addHeader("Authorization", "Bearer $token").build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string()
            if (resp.isSuccessful && !body.isNullOrBlank()) {
                val arr = org.json.JSONArray(body)
                if (arr.length() > 0) {
                    val values = (0 until arr.length()).mapNotNull {
                        arr.getJSONObject(it).optDouble(column).takeIf { v -> !v.isNaN() }
                    }
                    if (values.isNotEmpty()) values.average() else null
                } else null
            } else null
        } catch (_: Exception) { null }
    }

    /** Fetch today's samples and return MAX of integer column */
    fun fetchSamplesMax(table: String, column: String, tsColumn: String = "sampled_at"): Int? {
        return try {
            val url = "$base/rest/v1/$table?user_id=eq.$userId&${tsColumn}=gte.${date}T00:00:00&${tsColumn}=lt.${date}T23:59:59&select=$column"
            val req = Request.Builder().url(url).get()
                .addHeader("apikey", key).addHeader("Authorization", "Bearer $token").build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string()
            if (resp.isSuccessful && !body.isNullOrBlank()) {
                val arr = org.json.JSONArray(body)
                if (arr.length() > 0) {
                    val values = (0 until arr.length()).mapNotNull {
                        val v = arr.getJSONObject(it).optInt(column, Int.MIN_VALUE)
                        if (v != Int.MIN_VALUE) v else null
                    }
                    values.maxOrNull()
                } else null
            } else null
        } catch (_: Exception) { null }
    }

    /** Fetch dark mode samples, estimate hours as (dark_count / total) * 24 */
    fun fetchDarkModeSamplesHours(): Double? {
        return try {
            val url = "$base/rest/v1/phone_dark_mode_samples?user_id=eq.$userId&sampled_at=gte.${date}T00:00:00&sampled_at=lt.${date}T23:59:59&select=is_dark"
            val req = Request.Builder().url(url).get()
                .addHeader("apikey", key).addHeader("Authorization", "Bearer $token").build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string()
            if (resp.isSuccessful && !body.isNullOrBlank()) {
                val arr = org.json.JSONArray(body)
                val total = arr.length()
                if (total > 0) {
                    val darkCount = (0 until total).count { arr.getJSONObject(it).optBoolean("is_dark", false) }
                    (darkCount.toDouble() / total) * 24.0
                } else null
            } else null
        } catch (_: Exception) { null }
    }

    val stress = if (enabled("stress_index_daily")) fetchDouble("stress_index_daily", "value") else null
    val screenTime = if (enabled("screen_time_daily")) {
        fetchDouble("screen_time_daily", "total_hours") ?: fetchDouble("screen_time_live", "value_hours")
    } else null
    val lateScreen = if (enabled("screen_time_late_night")) fetchDouble("screen_time_late_night", "value_hours") else null
    val noise = if (enabled("ambient_noise_index_daily") || enabled("ambient_noise_samples")) {
        fetchDouble("ambient_noise_index_daily", "day_mean_lmean")
            ?: fetchSamplesAvg("ambient_noise_samples", "l_mean", "start_ts")
    } else null

    // Phone behavior: try daily table first, fall back to live samples
    val brightness = if (enabled("phone_brightness_daily")) {
        fetchDouble("phone_brightness_daily", "value_mean") ?: fetchSamplesAvg("phone_brightness_samples", "value")
    } else null
    val volume = if (enabled("phone_volume_daily")) {
        fetchDouble("phone_volume_daily", "value_mean_pct") ?: fetchSamplesAvg("phone_volume_samples", "value_pct")
    } else null
    val darkMode = if (enabled("phone_dark_mode_daily")) {
        fetchDouble("phone_dark_mode_daily", "value_hours") ?: fetchDarkModeSamplesHours()
    } else null
    val unlocks = if (enabled("phone_unlock_daily")) {
        fetchInt("phone_unlock_daily", "value_count") ?: fetchSamplesMax("phone_unlock_samples", "value_count")
    } else null

    if (stress == null && screenTime == null && lateScreen == null && noise == null &&
        brightness == null && volume == null && darkMode == null && unlocks == null) {
        return null
    }

    return MentalSummary(stress, screenTime, lateScreen, noise, brightness, volume, darkMode, unlocks)
}

// ═══════════════════════════════════════════════════════════════════════════
// Risk Monitor Card (summary card on main Monitor screen)
// ═══════════════════════════════════════════════════════════════════════════
@Composable
private fun RiskCard(
    riskLive: SupabaseDbService.RiskScoreLiveRow?,
    isLoading: Boolean,
    favOfFavs: List<Pair<String, String>>, // key to label
    resolveFavValue: (String) -> String,
    onClick: () -> Unit
) {
    MonitorCategoryCard(
        icon = Icons.Outlined.TrendingUp,
        title = "Risk",
        iconTint = Color(0xFFEF5350),
        onClick = onClick
    ) {
        if (isLoading) {
            Text("Loading...", color = AppTheme.SubtleTextColor)
        } else if (riskLive == null) {
            Text("No risk data yet", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
        } else {
            // Risk's own data
            val zone = riskLive.zone.lowercase().replaceFirstChar { it.uppercase() }
            val triggers = parseTopTriggersFromJson(riskLive.topTriggers)
            val slotColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%.1f".format(riskLive.score), color = slotColors[0], style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text("Score", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(zone, color = slotColors[1], style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text("Zone", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${triggers.size}", color = slotColors[2], style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                    Text("Triggers", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Fav-of-favs beneath
            if (favOfFavs.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = AppTheme.SubtleTextColor.copy(alpha = 0.15f))
                Spacer(Modifier.height(6.dp))
                val favColors = listOf(Color(0xFFFFB74D), Color(0xFF4FC3F7), Color(0xFF81C784))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    favOfFavs.take(3).forEachIndexed { i, (key, label) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(resolveFavValue(key), color = favColors.getOrElse(i) { favColors.last() }, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Text(label, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

