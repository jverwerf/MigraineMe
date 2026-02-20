package com.migraineme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun RiskDetailScreen(
    navController: NavController,
    state: HomeUiState
) {
    val scrollState = rememberScrollState()

    var selectedDay by remember { mutableStateOf(0) }
    val dayData = if (selectedDay in state.dayRisks.indices) state.dayRisks[selectedDay] else null
    val displayScore = dayData?.score ?: state.riskScore
    val displayZone = dayData?.zone ?: state.riskZone
    val displayPercent = dayData?.percent ?: state.riskPercent
    val displayTriggers = dayData?.topTriggers ?: state.triggersAtRisk
    val displayRecommendation = state.aiRecommendation

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            // Top bar
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Text("Risk Model", color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.size(48.dp))
            }

            // Gauge + forecast — exact same as HomeScreen
            DetailHeroCard(
                riskPercent = displayPercent,
                riskScore = displayScore,
                riskZone = displayZone,
                forecast = state.forecast,
                selectedDay = selectedDay,
                dayRisks = state.dayRisks,
                onDaySelected = { selectedDay = it }
            )

            // ALL triggers — not limited to 3
            DetailTriggersCard(
                triggers = displayTriggers,
                gaugeMax = state.gaugeMaxScore
            )

            // Recommendation
            DetailRecoCard(recommendation = displayRecommendation)
        }
    }
}

// Below: exact copies of HomeScreen composables, renamed to avoid conflict.

@Composable
private fun DetailHeroCard(
    riskPercent: Int,
    riskScore: Double = 0.0,
    riskZone: RiskZone = RiskZone.NONE,
    forecast: List<Int> = listOf(0, 0, 0, 0, 0, 0, 0),
    selectedDay: Int = 0,
    dayRisks: List<DayRisk> = emptyList(),
    onDaySelected: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val clamped = riskPercent.coerceIn(0, 100)

    val zoneColor = when (riskZone) {
        RiskZone.HIGH -> Color(0xFFE57373)
        RiskZone.MILD -> Color(0xFFFFB74D)
        RiskZone.LOW -> Color(0xFF81C784)
        RiskZone.NONE -> AppTheme.SubtleTextColor
    }

    // Day label for selected day
    val dayLabel = if (selectedDay == 0) "Risk today" else {
        val date = dayRisks.getOrNull(selectedDay)?.date
        if (date != null) "Risk · ${date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))}"
        else "Risk"
    }

    HeroCard(modifier = modifier) {
        Text(
            dayLabel,
            color = AppTheme.TitleColor,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )

        DetailGauge(
            percent = clamped,
            diameter = 220.dp,
            stroke = 16.dp,
            trackColor = AppTheme.TrackColor,
            progressColor = AppTheme.AccentPurple
        )

        // Score display
        Text(
            "%.1f".format(riskScore),
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
        )

        // Zone label
        Text(
            riskZone.label,
            color = zoneColor,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
        )

        DetailOutlook(
            values = forecast,
            selectedDay = selectedDay,
            dayRisks = dayRisks,
            onDaySelected = onDaySelected
        )
    }
}

@Composable
private fun DetailOutlook(
    values: List<Int>,
    selectedDay: Int = 0,
    dayRisks: List<DayRisk> = emptyList(),
    onDaySelected: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val dayFmt = DateTimeFormatter.ofPattern("EEE")

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            "7-day forecast",
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelMedium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            for (i in 0 until 7) {
                val percent = values.getOrNull(i)?.coerceIn(0, 100) ?: 0
                val date = today.plusDays(i.toLong())
                val dayLabel = if (i == 0) "Today" else date.format(dayFmt)
                val isSelected = i == selectedDay

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.clickable { onDaySelected(i) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .background(
                                        color = AppTheme.AccentPurple.copy(alpha = 0.25f),
                                        shape = CircleShape
                                    )
                            )
                        }
                        DetailMini(
                            percent = percent,
                            size = 36.dp,
                            strokeWidth = 3.5.dp
                        )
                    }
                    Text(
                        dayLabel,
                        color = if (isSelected) AppTheme.AccentPurple else if (i == 0) Color.White else AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailMini(
    percent: Int,
    size: Dp,
    strokeWidth: Dp
) {
    val clamped = percent.coerceIn(0, 100)
    val p = clamped / 100f

    val progressColor = lerp(AppTheme.AccentPurple, AppTheme.AccentPink, p)

    Box(
        modifier = Modifier.width(size).height(size * 0.62f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val sw = strokeWidth.toPx()
            val radius = (minOf(this.size.width, this.size.height * 2f) / 2f) - sw
            val cx = this.size.width / 2f; val cy = this.size.height
            drawArc(AppTheme.TrackColor, 180f, 180f, false,
                Offset(cx - radius, cy - radius), Size(radius * 2f, radius * 2f),
                style = Stroke(sw * 0.7f, cap = StrokeCap.Round))
            if (p > 0f) {
                drawArc(progressColor, 180f, 180f * p, false,
                    Offset(cx - radius, cy - radius), Size(radius * 2f, radius * 2f),
                    style = Stroke(sw, cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun DetailTriggersCard(
    triggers: List<TriggerScore>,
    gaugeMax: Double = 10.0
) {
    if (triggers.isEmpty()) return

    BaseCard {
        Text(
            "All contributors",
            color = AppTheme.TitleColor,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )

        Spacer(Modifier.height(8.dp))

        triggers.forEach { t ->
            val sevColor = when (t.severity.uppercase()) {
                "HIGH" -> Color(0xFFE57373)
                "MILD" -> Color(0xFFFFB74D)
                else -> Color(0xFF81C784)
            }
            val sevBg = sevColor.copy(alpha = 0.12f)
            val totalPts = triggers.sumOf { it.score }.coerceAtLeast(1)
            val pctOfTotal = (t.score * 100) / totalPts
            val barFraction = (t.score.toFloat() / gaugeMax.toFloat()).coerceIn(0f, 1f)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(sevColor, CircleShape)
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Top row: name + severity chip
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            t.name,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(Modifier.width(8.dp))

                        Box(
                            modifier = Modifier
                                .background(
                                    color = sevBg,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                t.severity,
                                color = sevColor,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }

                    // Progress bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(
                                color = AppTheme.TrackColor,
                                shape = RoundedCornerShape(2.dp)
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(barFraction)
                                .height(4.dp)
                                .background(
                                    color = sevColor,
                                    shape = RoundedCornerShape(2.dp)
                                )
                        )
                    }

                    // Bottom row: points + days active
                    Text(
                        "${pctOfTotal}% of risk · ${t.score} pts · ${if (t.daysActive == 1) "today only" else "${t.daysActive} days active"}",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRecoCard(recommendation: String) {
    BaseCard {
        Text(
            "Recommendation",
            color = AppTheme.TitleColor,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )
        Text(
            recommendation.ifBlank { "—" },
            color = AppTheme.BodyTextColor
        )
    }
}

@Composable
private fun DetailGauge(
    percent: Int,
    diameter: Dp,
    stroke: Dp,
    trackColor: Color,
    progressColor: Color
) {
    val clamped = percent.coerceIn(0, 100)

    val anim = remember { Animatable(0f) }
    LaunchedEffect(clamped) {
        anim.animateTo(
            targetValue = clamped.toFloat(),
            animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing)
        )
    }
    val p = (anim.value / 100f).coerceIn(0f, 1f)

    val progressStroke = stroke
    val trackStroke = (stroke.value * 0.72f).dp

    val width = diameter
    val height = diameter * 0.62f

    Box(
        modifier = Modifier.width(width).height(height),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = (minOf(size.width, size.height * 2f) / 2f) - progressStroke.toPx()
            val cx = size.width / 2f
            val cy = size.height

            val startAngle = 180f
            val fullSweep = 180f
            val sweep = fullSweep * p

            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = fullSweep,
                useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = trackStroke.toPx(), cap = StrokeCap.Round)
            )

            val tickCount = 11
            val tickOuter = radius + trackStroke.toPx() * 0.10f
            val tickInner = radius - trackStroke.toPx() * 0.55f
            for (i in 0 until tickCount) {
                val a = startAngle + (fullSweep / (tickCount - 1)) * i
                val rad = Math.toRadians(a.toDouble())
                val ox = cx + cos(rad).toFloat() * tickOuter
                val oy = cy + sin(rad).toFloat() * tickOuter
                val ix = cx + cos(rad).toFloat() * tickInner
                val iy = cy + sin(rad).toFloat() * tickInner
                drawLine(
                    color = Color.White.copy(alpha = 0.14f),
                    start = Offset(ix, iy),
                    end = Offset(ox, oy),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            drawArc(
                color = progressColor.copy(alpha = 0.22f),
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(cx - radius, cy - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = (progressStroke.toPx() * 1.75f), cap = StrokeCap.Round)
            )

            val segs = 42
            val segSweep = sweep / segs
            for (j in 0 until segs) {
                val t = if (segs == 1) 1f else j / (segs - 1f)
                val c = lerp(AppTheme.AccentPurple, AppTheme.AccentPink, t)
                val sa = startAngle + segSweep * j
                drawArc(
                    color = c,
                    startAngle = sa,
                    sweepAngle = segSweep.coerceAtLeast(0f),
                    useCenter = false,
                    topLeft = Offset(cx - radius, cy - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = progressStroke.toPx(), cap = StrokeCap.Round)
                )
            }

            val endAngle = startAngle + sweep
            val endRad = Math.toRadians(endAngle.toDouble())
            val ex = cx + cos(endRad).toFloat() * radius
            val ey = cy + sin(endRad).toFloat() * radius
            drawCircle(
                color = Color.White.copy(alpha = 0.90f),
                radius = (progressStroke.toPx() * 0.42f),
                center = Offset(ex, ey)
            )
            drawCircle(
                color = AppTheme.AccentPink.copy(alpha = 0.95f),
                radius = (progressStroke.toPx() * 0.30f),
                center = Offset(ex, ey)
            )
        }
    }
}


