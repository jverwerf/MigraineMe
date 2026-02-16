package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

enum class SeverityChoice(val label: String, val color: Color) {
    HIGH("High", Color(0xFFE57373)),
    MILD("Mild", Color(0xFFFFB74D)),
    LOW("Low", Color(0xFF81C784)),
    NONE("None", Color.White.copy(alpha = 0.4f))
}

data class SeverityQuestion(val label: String, val displayName: String, val description: String, val type: String)

data class DataCollectionItem(val metric: String, val displayName: String, val description: String, val source: String, val requiresWearable: Boolean = false, val requiresLocation: Boolean = false)
data class DataCollectionGroup(val title: String, val items: List<DataCollectionItem>)

val dataCollectionGroups = listOf(
    DataCollectionGroup("Sleep", listOf(
        DataCollectionItem("sleep_duration_daily", "Sleep duration", "Hours of sleep per night", "both"),
        DataCollectionItem("sleep_score_daily", "Sleep score", "Overall sleep quality score", "wearable", requiresWearable = true),
        DataCollectionItem("sleep_efficiency_daily", "Sleep efficiency", "Time asleep vs time in bed", "wearable", requiresWearable = true),
        DataCollectionItem("sleep_stages_daily", "Sleep stages", "Deep, REM, light sleep breakdown", "wearable", requiresWearable = true),
        DataCollectionItem("sleep_disturbances_daily", "Sleep disturbances", "Wake-ups and restlessness", "wearable", requiresWearable = true),
        DataCollectionItem("fell_asleep_time_daily", "Bedtime", "When you fell asleep", "both"),
        DataCollectionItem("woke_up_time_daily", "Wake time", "When you woke up", "both"),
    )),
    DataCollectionGroup("Physical Health", listOf(
        DataCollectionItem("recovery_score_daily", "Recovery", "Daily recovery percentage", "wearable", requiresWearable = true),
        DataCollectionItem("resting_hr_daily", "Resting heart rate", "Your resting BPM", "wearable", requiresWearable = true),
        DataCollectionItem("hrv_daily", "HRV", "Heart rate variability", "wearable", requiresWearable = true),
        DataCollectionItem("skin_temp_daily", "Skin temperature", "Wrist skin temp deviation", "wearable", requiresWearable = true),
        DataCollectionItem("spo2_daily", "Blood oxygen (SpO₂)", "Oxygen saturation", "wearable", requiresWearable = true),
        DataCollectionItem("steps_daily", "Steps", "Daily step count", "wearable", requiresWearable = true),
        DataCollectionItem("time_in_high_hr_zones_daily", "High HR zones", "Minutes in elevated heart rate", "wearable", requiresWearable = true),
    )),
    DataCollectionGroup("Mental Health & Screen", listOf(
        DataCollectionItem("stress_index_daily", "Stress index", "Computed from HRV and activity", "computed"),
        DataCollectionItem("screen_time_daily", "Screen time", "Total phone screen time", "phone"),
        DataCollectionItem("screen_time_late_night", "Late night screen time", "Screen use after 10pm", "phone"),
        DataCollectionItem("ambient_noise_samples", "Noise sampling", "Ambient noise levels", "phone"),
        DataCollectionItem("phone_brightness_daily", "Phone brightness", "Average screen brightness", "phone"),
        DataCollectionItem("phone_volume_daily", "Phone volume", "Average media volume", "phone"),
        DataCollectionItem("phone_dark_mode_daily", "Dark mode", "Hours in dark mode", "phone"),
        DataCollectionItem("phone_unlock_daily", "Phone unlocks", "How often you check your phone", "phone"),
    )),
    DataCollectionGroup("Environment", listOf(
        DataCollectionItem("user_location_daily", "Location", "Your location for weather data", "phone"),
        DataCollectionItem("temperature_daily", "Temperature", "Local temperature", "reference", requiresLocation = true),
        DataCollectionItem("pressure_daily", "Barometric pressure", "Atmospheric pressure", "reference", requiresLocation = true),
        DataCollectionItem("humidity_daily", "Humidity", "Local humidity levels", "reference", requiresLocation = true),
        DataCollectionItem("wind_daily", "Wind", "Wind speed", "reference", requiresLocation = true),
        DataCollectionItem("uv_daily", "UV index", "UV radiation level", "reference", requiresLocation = true),
    )),
    DataCollectionGroup("Diet", listOf(
        DataCollectionItem("nutrition", "Nutrition tracking", "Log meals and track nutrients, caffeine, alcohol", "phone"),
    )),
    DataCollectionGroup("Menstruation", listOf(
        DataCollectionItem("menstruation", "Menstrual cycle", "Track your cycle and predict periods", "phone"),
    )),
)

val sleepTriggerQuestions = listOf(
    SeverityQuestion("Sleep duration low", "Short sleep", "Not sleeping long enough (< 6-7 hours)", "trigger"),
    SeverityQuestion("Sleep disturbances high", "Restless sleep", "Frequent wake-ups or tossing and turning", "trigger"),
    SeverityQuestion("Bedtime late", "Late bedtime", "Going to bed much later than usual", "trigger"),
    SeverityQuestion("Deep sleep low", "Poor deep sleep", "Not enough restorative deep sleep", "trigger"),
    SeverityQuestion("REM sleep low", "Low REM sleep", "Below your normal REM percentage", "trigger"),
    SeverityQuestion("Sleep efficiency low", "Poor sleep efficiency", "Lots of time in bed but not sleeping", "trigger"),
    SeverityQuestion("Sleep score low", "Low sleep score", "Overall poor sleep quality score", "trigger"),
)

val bodyTriggerQuestions = listOf(
    SeverityQuestion("Recovery low", "Low recovery", "Poor recovery score from your wearable", "trigger"),
    SeverityQuestion("Stress high", "High physiological stress", "Elevated stress index", "trigger"),
    SeverityQuestion("Steps low", "Sedentary day", "Very low step count / no movement", "trigger"),
    SeverityQuestion("High HR zones low", "No exercise", "No time in elevated heart rate zones", "trigger"),
    SeverityQuestion("High HR zones high", "Over-exercise", "Too much intense exercise", "trigger"),
    SeverityQuestion("Recovery high", "Unusual high recovery", "Unexpectedly high recovery (can signal change)", "trigger"),
)

val environmentTriggerQuestions = listOf(
    SeverityQuestion("Pressure low", "Low pressure", "Falling barometric pressure / storm fronts", "trigger"),
    SeverityQuestion("Pressure high", "High pressure", "Rising barometric pressure", "trigger"),
    SeverityQuestion("Humidity high", "High humidity", "Muggy, heavy air", "trigger"),
    SeverityQuestion("Temperature low", "Cold weather", "Sharp drops in temperature", "trigger"),
    SeverityQuestion("Temperature high", "Hot weather", "Heat waves, high temperatures", "trigger"),
    SeverityQuestion("Wind speed high", "Strong wind", "Windy or gusty conditions", "trigger"),
    SeverityQuestion("UV high", "High UV", "High ultraviolet radiation", "trigger"),
)

val cognitiveTriggerQuestions = listOf(
    SeverityQuestion("Screen time high", "Excess screen time", "Too many hours on phone/computer", "trigger"),
    SeverityQuestion("Late screen time high", "Late night screens", "Screen use close to bedtime", "trigger"),
    SeverityQuestion("Noise high", "Noisy environment", "Loud or sustained noise exposure", "trigger"),
    SeverityQuestion("Stress", "Emotional stress", "Work pressure, anxiety, conflict", "trigger"),
    SeverityQuestion("Anger", "Anger", "Episodes of anger or frustration", "trigger"),
    SeverityQuestion("Anxiety", "Anxiety", "Feeling anxious or worried", "trigger"),
    SeverityQuestion("Let-down", "Let-down effect", "Migraine after stress ends (weekends, holidays)", "trigger"),
)

val dietTriggerQuestions = listOf(
    SeverityQuestion("Alcohol exposure high", "Alcohol", "Wine, beer, spirits", "trigger"),
    SeverityQuestion("Caffeine high", "Too much caffeine", "Excess coffee, tea, energy drinks", "trigger"),
    SeverityQuestion("Caffeine low", "Caffeine withdrawal", "Missing your usual caffeine intake", "trigger"),
    SeverityQuestion("Calories low", "Skipped meals", "Undereating, fasting, missed meals", "trigger"),
    SeverityQuestion("Dehydration", "Dehydration", "Not drinking enough water", "trigger"),
    SeverityQuestion("Gluten exposure high", "Gluten", "Foods containing gluten", "trigger"),
    SeverityQuestion("Calories high", "Overeating", "Eating significantly more than usual", "trigger"),
)

val hormonalTriggerQuestions = listOf(
    SeverityQuestion("menstruation_predicted", "Menstrual cycle", "Period-related migraines (before, during, or after)", "trigger"),
)

val physicalProdromeQuestions = listOf(
    SeverityQuestion("HRV low", "Low HRV", "Heart rate variability drops before attacks", "prodrome"),
    SeverityQuestion("Resting HR high", "Elevated resting HR", "Resting heart rate higher than usual", "prodrome"),
    SeverityQuestion("SpO2 low", "Low blood oxygen", "SpO₂ dips before migraines", "prodrome"),
    SeverityQuestion("Skin temp low", "Skin temp drop", "Skin temperature decreases", "prodrome"),
    SeverityQuestion("Skin temp high", "Skin temp rise", "Skin temperature increases", "prodrome"),
    SeverityQuestion("Resp rate high", "Fast breathing", "Respiratory rate increases", "prodrome"),
    SeverityQuestion("Muscle tension", "Neck/shoulder tension", "Stiffness in neck or shoulders", "prodrome"),
    SeverityQuestion("Frequent urination", "Frequent urination", "Needing to urinate more often", "prodrome"),
)

val sensoryProdromeQuestions = listOf(
    SeverityQuestion("Sensitivity to light", "Light sensitivity", "Bright lights feel painful or overwhelming", "prodrome"),
    SeverityQuestion("Sensitivity to sound", "Sound sensitivity", "Normal sounds feel too loud", "prodrome"),
    SeverityQuestion("Sensitivity to smell", "Smell sensitivity", "Strong odours become unbearable", "prodrome"),
    SeverityQuestion("Numbness", "Numbness", "Tingling or numbness in face or hands", "prodrome"),
    SeverityQuestion("Tingling", "Tingling", "Pins and needles sensations", "prodrome"),
)

val moodProdromeQuestions = listOf(
    SeverityQuestion("Mood change", "Mood changes", "Unusual irritability, sadness, or euphoria", "prodrome"),
    SeverityQuestion("Irritability", "Irritability", "Being unusually short-tempered", "prodrome"),
    SeverityQuestion("Depression", "Low mood", "Feeling down or depressed", "prodrome"),
    SeverityQuestion("Euphoria", "Euphoria", "Unusual feelings of elation", "prodrome"),
    SeverityQuestion("Difficulty focusing", "Brain fog", "Trouble concentrating or reading", "prodrome"),
    SeverityQuestion("Word-finding trouble", "Word-finding trouble", "Struggling to find the right words", "prodrome"),
)

val autonomicProdromeQuestions = listOf(
    SeverityQuestion("Yawning", "Excessive yawning", "Yawning frequently without being tired", "prodrome"),
    SeverityQuestion("Food cravings", "Food cravings", "Unusual cravings for sweets or specific foods", "prodrome"),
    SeverityQuestion("Loss of appetite", "Loss of appetite", "Not feeling hungry at all", "prodrome"),
    SeverityQuestion("Nasal congestion", "Nasal congestion", "Stuffy nose without a cold", "prodrome"),
    SeverityQuestion("Tearing", "Tearing", "Watery eyes without cause", "prodrome"),
)

fun generateSuggestions(
    frequency: String?, duration: String?, severity: String?, timing: String?, warningSign: String?,
    triggerAreas: Set<String>, wearable: String?, medication: String?, familyHistory: String?, trackCycle: String?,
): Map<String, SeverityChoice> {
    val map = mutableMapOf<String, SeverityChoice>()
    val base = when (frequency) { "Daily", "2-3x per week" -> SeverityChoice.HIGH; "Weekly" -> SeverityChoice.MILD; else -> SeverityChoice.LOW }
    val lower = when (base) { SeverityChoice.HIGH -> SeverityChoice.MILD; SeverityChoice.MILD -> SeverityChoice.LOW; else -> SeverityChoice.LOW }

    if ("Sleep" in triggerAreas) {
        map["Sleep duration low"] = base; map["Sleep disturbances high"] = lower; map["Bedtime late"] = SeverityChoice.LOW
        if (wearable != "None" && wearable != null) { map["Sleep score low"] = lower; map["Sleep efficiency low"] = SeverityChoice.LOW }
    }
    if ("Stress" in triggerAreas) {
        map["Stress"] = base; map["Anxiety"] = lower; map["Let-down"] = SeverityChoice.LOW
        if (wearable != "None" && wearable != null) { map["Stress high"] = lower; map["Recovery low"] = lower }
    }
    if ("Weather" in triggerAreas) { map["Pressure low"] = base; map["Temperature high"] = lower; map["Temperature low"] = lower; map["Humidity high"] = SeverityChoice.LOW; map["Wind speed high"] = SeverityChoice.LOW }
    if ("Screen time" in triggerAreas) { map["Screen time high"] = base; map["Late screen time high"] = lower; map["Noise high"] = SeverityChoice.LOW }
    if ("Diet" in triggerAreas) { map["Alcohol exposure high"] = base; map["Caffeine high"] = lower; map["Caffeine low"] = lower; map["Calories low"] = lower; map["Dehydration"] = lower; map["Gluten exposure high"] = SeverityChoice.LOW }
    if ("Hormones" in triggerAreas || trackCycle == "Yes") { map["menstruation_predicted"] = base }
    if ("Exercise" in triggerAreas) { map["High HR zones low"] = lower; map["High HR zones high"] = SeverityChoice.LOW; map["Steps low"] = SeverityChoice.LOW }

    if (warningSign == "Yes, clearly") {
        map["Sensitivity to light"] = SeverityChoice.HIGH; map["Sensitivity to sound"] = SeverityChoice.MILD; map["Sensitivity to smell"] = SeverityChoice.MILD
        map["Mood change"] = SeverityChoice.MILD; map["Muscle tension"] = SeverityChoice.MILD; map["Difficulty focusing"] = SeverityChoice.MILD
        map["Yawning"] = SeverityChoice.LOW; map["Food cravings"] = SeverityChoice.LOW; map["Numbness"] = SeverityChoice.LOW
    } else if (warningSign == "Sometimes") {
        map["Sensitivity to light"] = SeverityChoice.MILD; map["Sensitivity to sound"] = SeverityChoice.LOW
        map["Mood change"] = SeverityChoice.LOW; map["Muscle tension"] = SeverityChoice.LOW; map["Yawning"] = SeverityChoice.LOW
    }

    if (wearable == "WHOOP" || wearable == "Both") {
        map["HRV low"] = lower; map["Resting HR high"] = SeverityChoice.LOW; map["SpO2 low"] = SeverityChoice.LOW; map["Skin temp low"] = SeverityChoice.LOW
    }

    if (severity == "Severe" || severity == "Debilitating") {
        for (key in map.keys.toList()) { if (map[key] == SeverityChoice.LOW) map[key] = SeverityChoice.MILD }
    }
    if (timing == "Morning") {
        if ("Sleep duration low" !in map) map["Sleep duration low"] = SeverityChoice.MILD
        if ("Sleep disturbances high" !in map) map["Sleep disturbances high"] = SeverityChoice.LOW
    }
    return map
}

// ─── Shared UI components ────────────────────────────────────

@Composable
fun OnboardingIconHeader(icon: ImageVector, title: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp).background(AppTheme.AccentPurple.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
fun OnboardingChoiceCard(label: String, description: String, isSelected: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }
        .then(if (isSelected) Modifier.border(1.5.dp, AppTheme.AccentPurple, RoundedCornerShape(12.dp)) else Modifier),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) AppTheme.AccentPurple.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.04f)),
        shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (isSelected) Box(Modifier.size(20.dp).background(AppTheme.AccentPurple, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
            } else Box(Modifier.size(20.dp).border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape))
            Column {
                Text(label, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                Text(description, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun OnboardingChoiceChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .background(if (isSelected) AppTheme.AccentPurple.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.04f))
        .then(if (isSelected) Modifier.border(1.dp, AppTheme.AccentPurple, RoundedCornerShape(10.dp)) else Modifier)
        .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, color = if (isSelected) AppTheme.AccentPurple else AppTheme.BodyTextColor,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal))
    }
}

@Composable
fun OnboardingQuestionSection(question: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(question, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

@Composable
fun SeverityQuestionCard(question: SeverityQuestion, selected: SeverityChoice, onSelect: (SeverityChoice) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(question.displayName, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
            Text(question.description, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SeverityChoice.entries.reversed().forEach { choice ->
                    val isSelected = choice == selected
                    Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) choice.color.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.04f))
                        .then(if (isSelected) Modifier.border(1.5.dp, choice.color, RoundedCornerShape(8.dp)) else Modifier.border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp)))
                        .clickable { onSelect(choice) }, contentAlignment = Alignment.Center) {
                        Text(choice.label, color = if (isSelected) choice.color else AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal))
                    }
                }
            }
        }
    }
}

@Composable
fun SuggestionRow(label: String, severity: SeverityChoice, onChange: (SeverityChoice) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SeverityChoice.entries.reversed().forEach { choice ->
                val isSelected = choice == severity
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (isSelected) choice.color.copy(alpha = 0.25f) else Color.Transparent)
                    .then(if (isSelected) Modifier.border(1.dp, choice.color, RoundedCornerShape(6.dp)) else Modifier)
                    .clickable { onChange(choice) }, contentAlignment = Alignment.Center) {
                    Text(choice.label.first().toString(), color = if (isSelected) choice.color else Color.White.copy(alpha = 0.25f),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}

@Composable
fun DataToggleRow(item: DataCollectionItem, enabled: Boolean, available: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(item.displayName, color = if (available) Color.White else AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
            Text(if (!available) "Requires ${if (item.requiresWearable) "wearable" else "location"}" else item.description,
                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
        }
        Switch(checked = enabled && available, onCheckedChange = { if (available) onToggle(it) }, enabled = available,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppTheme.AccentPurple,
                uncheckedThumbColor = Color.White.copy(alpha = 0.5f), uncheckedTrackColor = AppTheme.TrackColor))
    }
}
