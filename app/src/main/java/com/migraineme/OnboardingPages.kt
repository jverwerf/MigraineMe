package com.migraineme

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun OnboardingCenteredPage(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally, content = content)
}

@Composable
fun OnboardingScrollPage(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
fun WelcomePage() {
    OnboardingCenteredPage {
        val anim = rememberInfiniteTransition(label = "pulse")
        val scale by anim.animateFloat(0.95f, 1.05f, infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = "s")
        Box(Modifier.size((100 * scale).dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(80.dp)) {
                drawArc(brush = Brush.sweepGradient(listOf(Color(0xFFB97BFF), Color(0xFFFF7BB0), Color(0xFFB97BFF))),
                    startAngle = 180f, sweepAngle = 180f, useCenter = false, style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("Welcome to MigraineMe", color = Color.White, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text("Your personal migraine prediction companion.\n\nLet's get you set up in a few minutes.",
            color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
fun HowItWorksPage() {
    val steps = listOf(
        Triple(Icons.Outlined.Sensors, "Connect", "Data flows in from your wearable, Health Connect and phone"),
        Triple(Icons.Outlined.Bolt, "Detect", "Unusual patterns get flagged automatically"),
        Triple(Icons.Outlined.Speed, "Score", "Everything adds up to your daily risk"),
        Triple(Icons.Outlined.CalendarMonth, "Predict", "See what's coming 7 days ahead"),
        Triple(Icons.Outlined.AutoAwesome, "Learn", "Gets smarter the more you use it"),
    )
    var activeStep by remember { mutableIntStateOf(-1) }
    var allRevealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(600); for (i in steps.indices) { activeStep = i; delay(1200) }; delay(500); allRevealed = true }
    val lineProgress by animateFloatAsState(if (activeStep >= 0) ((activeStep + 1).toFloat() / steps.size) else 0f, tween(900, easing = FastOutSlowInEasing), label = "line")

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("How it works", color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(32.dp))
        Box(Modifier.fillMaxWidth()) {
            Canvas(Modifier.width(2.dp).height((steps.size * 72).dp).align(Alignment.TopStart).offset(x = 23.dp)) {
                drawLine(Color.White.copy(alpha = 0.08f), Offset(0f, 0f), Offset(0f, size.height), 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(Brush.verticalGradient(listOf(Color(0xFFB97BFF), Color(0xFFFF7BB0))), Offset(0f, 0f), Offset(0f, size.height * lineProgress), 2.dp.toPx(), cap = StrokeCap.Round)
            }
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                steps.forEachIndexed { index, (icon, title, subtitle) ->
                    val isActive = index <= activeStep
                    val alpha by animateFloatAsState(if (isActive) 1f else 0.15f, tween(500), label = "a$index")
                    val scale by animateFloatAsState(if (index == activeStep && !allRevealed) 1.06f else 1f, spring(dampingRatio = 0.6f, stiffness = 200f), label = "s$index")
                    val offsetX by animateDpAsState(if (isActive) 0.dp else 50.dp, tween(500, easing = FastOutSlowInEasing), label = "x$index")
                    Row(Modifier.fillMaxWidth().offset(x = offsetX).graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha },
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp)
                            .background(if (isActive) Brush.linearGradient(listOf(AppTheme.AccentPurple.copy(alpha = 0.3f), AppTheme.AccentPink.copy(alpha = 0.2f))) else Brush.linearGradient(listOf(Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.05f))), CircleShape)
                            .then(if (isActive) Modifier.border(1.5.dp, Brush.linearGradient(listOf(AppTheme.AccentPurple, AppTheme.AccentPink)), CircleShape) else Modifier),
                            contentAlignment = Alignment.Center) {
                            Icon(icon, null, tint = if (isActive) Color.White else Color.White.copy(alpha = 0.3f), modifier = Modifier.size(24.dp))
                        }
                        Column {
                            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Text(subtitle, color = if (isActive) AppTheme.BodyTextColor else AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionsPage(onNavigateToConnections: () -> Unit, wearableConnected: String?, onWearableChanged: (String) -> Unit) {
    OnboardingScrollPage {
        OnboardingIconHeader(Icons.Outlined.Link, "Connect your data")
        Text("The more data MigraineMe has, the better it predicts.", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        listOf("WHOOP" to "Sleep, recovery, HRV, HR, SpO₂, skin temp", "Health Connect" to "Steps, sleep, heart rate, nutrition, and more", "Both" to "Get the best of both sources", "None" to "I'll log everything manually").forEach { (label, desc) ->
            OnboardingChoiceCard(label, desc, wearableConnected == label) { onWearableChanged(label) }
        }
        if (wearableConnected != null && wearableConnected != "None") {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onNavigateToConnections, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.AccentPurple)) {
                Icon(Icons.Outlined.Settings, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text("Set up connections now", color = AppTheme.AccentPurple)
            }
            Text("You can also do this later from the menu.", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
fun DataCollectionPage(wearable: String?, enabledMetrics: MutableMap<String, Boolean>) {
    val hasWearable = wearable == "WHOOP" || wearable == "Both"
    val hasLocation = enabledMetrics["user_location_daily"] == true
    OnboardingScrollPage {
        OnboardingIconHeader(Icons.Outlined.Storage, "What do you want to track?")
        Text("Turn on the data you want MigraineMe to collect. You can change these later in Settings → Data.",
            color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        dataCollectionGroups.forEach { group ->
            Text(group.title, color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 4.dp))
            group.items.forEach { item ->
                val available = when {
                    item.requiresWearable && !hasWearable -> false
                    item.requiresLocation && !hasLocation -> false
                    else -> true
                }
                val enabled = enabledMetrics[item.metric] ?: (available && item.source != "reference")
                DataToggleRow(item, enabled, available) { on -> enabledMetrics[item.metric] = on }
            }
        }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
fun PersonalQuestionsPage1(frequency: String?, onFrequency: (String) -> Unit, duration: String?, onDuration: (String) -> Unit, severity: String?, onSeverity: (String) -> Unit) {
    OnboardingScrollPage {
        OnboardingIconHeader(Icons.Outlined.Person, "About your migraines")
        OnboardingQuestionSection("How often do you get migraines?") {
            listOf("Daily", "2-3x per week", "Weekly", "2-3x per month", "Monthly", "Rarely").forEach { OnboardingChoiceChip(it, frequency == it) { onFrequency(it) } }
        }
        OnboardingQuestionSection("How long do they usually last?") {
            listOf("A few hours", "Half a day", "A full day", "2-3 days", "More than 3 days").forEach { OnboardingChoiceChip(it, duration == it) { onDuration(it) } }
        }
        OnboardingQuestionSection("How severe are they typically?") {
            listOf("Mild — can push through", "Moderate — slows me down", "Severe — can't function", "Debilitating — bed rest required").forEach { OnboardingChoiceChip(it, severity == it) { onSeverity(it) } }
        }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
fun PersonalQuestionsPage2(timing: String?, onTiming: (String) -> Unit, warningSign: String?, onWarningSign: (String) -> Unit, medication: String?, onMedication: (String) -> Unit) {
    OnboardingScrollPage {
        OnboardingIconHeader(Icons.Outlined.Schedule, "Timing & patterns")
        OnboardingQuestionSection("When do they usually hit?") {
            listOf("Morning (wake up with it)", "Afternoon", "Evening", "Night", "No pattern / varies").forEach { OnboardingChoiceChip(it, timing == it) { onTiming(it) } }
        }
        OnboardingQuestionSection("Do you get warning signs before an attack?") {
            listOf("Yes, clearly — I can feel one coming", "Sometimes — occasional hints", "Rarely — they catch me off guard", "No — they come without warning").forEach { OnboardingChoiceChip(it, warningSign == it) { onWarningSign(it) } }
        }
        OnboardingQuestionSection("Do you take preventive or acute medication?") {
            listOf("Yes, preventive daily medication", "Yes, acute medication when needed", "Both preventive and acute", "No medication currently").forEach { OnboardingChoiceChip(it, medication == it) { onMedication(it) } }
        }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
fun PersonalQuestionsPage3(knownTriggerAreas: Set<String>, onTriggerAreas: (Set<String>) -> Unit, familyHistory: String?, onFamilyHistory: (String) -> Unit, trackCycle: String?, onTrackCycle: (String) -> Unit) {
    OnboardingScrollPage {
        OnboardingIconHeader(Icons.Outlined.Lightbulb, "What you already know")
        OnboardingQuestionSection("Which of these seem to affect your migraines? (select all that apply)") {
            listOf("Sleep", "Stress", "Weather", "Screen time", "Diet", "Hormones", "Exercise", "Not sure yet").forEach { area ->
                val selected = area in knownTriggerAreas
                OnboardingChoiceChip(area, selected) {
                    if (area == "Not sure yet") onTriggerAreas(setOf("Not sure yet"))
                    else { val new = knownTriggerAreas.toMutableSet(); new.remove("Not sure yet"); if (selected) new.remove(area) else new.add(area); onTriggerAreas(new) }
                }
            }
        }
        OnboardingQuestionSection("Does anyone in your family get migraines?") {
            listOf("Yes", "No", "Not sure").forEach { OnboardingChoiceChip(it, familyHistory == it) { onFamilyHistory(it) } }
        }
        OnboardingQuestionSection("Do you want to track your menstrual cycle?") {
            listOf("Yes", "No", "Not applicable").forEach { OnboardingChoiceChip(it, trackCycle == it) { onTrackCycle(it) } }
        }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
fun TriggerGroupPage(title: String, icon: ImageVector, questions: List<SeverityQuestion>, answers: MutableMap<String, SeverityChoice>) {
    OnboardingScrollPage {
        OnboardingIconHeader(icon, title)
        Text("How much does each of these affect your migraines?", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        questions.forEach { q -> SeverityQuestionCard(q, answers[q.label] ?: SeverityChoice.NONE) { answers[q.label] = it } }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
fun SuggestionsPage(suggestions: MutableMap<String, SeverityChoice>) {
    OnboardingScrollPage {
        OnboardingIconHeader(Icons.Outlined.AutoAwesome, "Your personalised model")
        Text("Based on your answers, here's what we suggest. Tap to adjust.", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        if (suggestions.isEmpty()) {
            Text("Complete the previous sections to get personalised suggestions.", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
        } else {
            val grouped = suggestions.entries.sortedByDescending { it.value.ordinal }.groupBy { it.value }
            grouped.forEach { (severity, items) ->
                if (severity == SeverityChoice.NONE) return@forEach
                Text("${severity.label} influence", color = severity.color, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 8.dp))
                items.forEach { (label, sev) -> SuggestionRow(label, sev) { suggestions[label] = it } }
            }
            val noneCount = suggestions.count { it.value == SeverityChoice.NONE }
            if (noneCount > 0) Text("$noneCount triggers left at None (no influence)", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
fun RiskModelPage() {
    OnboardingScrollPage {
        OnboardingIconHeader(Icons.Outlined.Speed, "Your Risk Gauge")
        Text("Each trigger has a severity — you decide how much it matters:", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        SeverityExplainRow(SeverityChoice.HIGH, "Reliably triggers your migraines")
        SeverityExplainRow(SeverityChoice.MILD, "Contributes but not always the cause")
        SeverityExplainRow(SeverityChoice.LOW, "Might play a role occasionally")
        SeverityExplainRow(SeverityChoice.NONE, "No influence — doesn't count")
        Spacer(Modifier.height(12.dp))
        Text("Recent triggers weigh more than older ones. The score decays over 7 days.", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text("Adjust everything anytime in Settings → Risk Model.", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(0.dp))
    }
}

@Composable
private fun SeverityExplainRow(severity: SeverityChoice, description: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(severity.color, CircleShape))
        Text("${severity.label}:", color = severity.color, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.width(42.dp))
        Text(description, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
fun CompletePage(saving: Boolean) {
    OnboardingCenteredPage {
        Box(Modifier.size(80.dp).background(Brush.linearGradient(listOf(AppTheme.AccentPurple, AppTheme.AccentPink)), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Almost there!", color = Color.White, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text("Your risk model is personalised.\n\nWe're loading some sample data so you can see the app in action. Next up: a quick tour of every screen.",
            color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp))
    }
}
