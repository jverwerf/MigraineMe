package com.migraineme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// ═══════════════════════════════════════════════════════════════════════════
// Private helpers (copies from v1 — keeps old file untouched)
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun QPageHeader(icon: ImageVector, title: String, subtitle: String, pageNum: Int, totalPages: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).background(Brush.linearGradient(listOf(AppTheme.AccentPurple.copy(alpha = 0.3f), AppTheme.AccentPink.copy(alpha = 0.2f))), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        if (pageNum > 0 || totalPages > 0) {
            Spacer(Modifier.height(4.dp))
            Text("Question page $pageNum of $totalPages", color = AppTheme.SubtleTextColor.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun QCard(label: String, icon: ImageVector? = null, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) { Icon(icon, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                Text(label, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            }
            if (subtitle != null) { Spacer(Modifier.height(4.dp)); Text(subtitle, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall) }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun QSingleChips(options: List<String>, selected: String?, onSelect: (String) -> Unit) {
    val rows = options.chunked(3)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (option in row) {
                    val sel = option == selected
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (sel) AppTheme.AccentPurple.copy(alpha = 0.3f) else AppTheme.TrackColor.copy(alpha = 0.3f)).border(1.dp, if (sel) AppTheme.AccentPurple else Color.Transparent, RoundedCornerShape(10.dp)).clickable { onSelect(option) }.padding(vertical = 10.dp, horizontal = 8.dp), contentAlignment = Alignment.Center) {
                        Text(option, color = if (sel) Color.White else AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun QMultiChips(options: List<String>, selected: Set<String>, onToggle: (String) -> Unit) {
    val rows = options.chunked(3)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (option in row) {
                    val sel = option in selected
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (sel) AppTheme.AccentPink.copy(alpha = 0.25f) else AppTheme.TrackColor.copy(alpha = 0.3f)).border(1.dp, if (sel) AppTheme.AccentPink else Color.Transparent, RoundedCornerShape(10.dp)).clickable { onToggle(option) }.padding(vertical = 10.dp, horizontal = 8.dp), contentAlignment = Alignment.Center) {
                        Text(option, color = if (sel) Color.White else AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private enum class PoolType { SYMPTOM, MEDICINE, RELIEF, ACTIVITY, MISSED_ACTIVITY, TRIGGER, PRODROME }

@Composable
private fun QPoolMultiSelect(items: List<AiSetupService.PoolLabel>, selected: Set<String>, onToggle: (String) -> Unit, accentColor: Color = AppTheme.AccentPink, poolType: PoolType = PoolType.SYMPTOM) {
    val grouped: Map<String, List<AiSetupService.PoolLabel>> = items.groupBy { item -> item.category ?: "Other" }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((category, poolItems) in grouped) {
            Text(category, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
            val rows = poolItems.chunked(4)
            for (row in rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    for (item in row) {
                        val sel = item.label in selected
                        val circleColor = if (sel) accentColor.copy(alpha = 0.40f) else Color.White.copy(alpha = 0.08f)
                        val borderColor = if (sel) accentColor.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.12f)
                        val iconTint = if (sel) Color.White else AppTheme.SubtleTextColor
                        val textColor = if (sel) Color.White else AppTheme.BodyTextColor

                        val icon: ImageVector? = when (poolType) {
                            PoolType.MEDICINE -> MedicineIcons.forKey(item.category)
                            PoolType.RELIEF -> ReliefIcons.forLabel(item.label, item.iconKey)
                            PoolType.SYMPTOM -> SymptomIcons.forLabel(item.label, item.iconKey)
                            PoolType.ACTIVITY -> ActivityIcons.forLabel(item.label, item.iconKey)
                            PoolType.MISSED_ACTIVITY -> MissedActivityIcons.forLabel(item.label, item.iconKey)
                            PoolType.TRIGGER -> TriggerIcons.forKey(item.iconKey) ?: TriggerIcons.forKey(item.label.lowercase())
                            PoolType.PRODROME -> ProdromeIcons.forKey(item.iconKey) ?: ProdromeIcons.forKey(item.label.lowercase())
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .weight(1f)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { onToggle(item.label) }
                                )
                                .padding(vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(circleColor)
                                    .border(width = 1.5.dp, color = borderColor, shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (icon != null) {
                                    Icon(imageVector = icon, contentDescription = item.label, tint = iconTint, modifier = Modifier.size(22.dp))
                                } else {
                                    Text(item.label.take(2).uppercase(), color = iconTint, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                item.label,
                                color = textColor,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun QFreeText(value: String, onValueChange: (String) -> Unit, hint: String) {
    var text by remember { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != text) text = value }

    val context = androidx.compose.ui.platform.LocalContext.current

    // Speech recogniser launcher (same pattern as daily NotePage)
    val speechLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                val updated = if (text.isBlank()) spoken else "$text, $spoken"
                text = updated
                onValueChange(updated)
            }
        }
    }

    fun launchVoice() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Tell us about your migraines…")
        }
        try { speechLauncher.launch(intent) } catch (_: Exception) {
            android.widget.Toast.makeText(context, "Voice input not available", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    OutlinedTextField(
        value = text, onValueChange = { text = it; onValueChange(it) },
        placeholder = { Text(hint, color = AppTheme.SubtleTextColor.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall) },
        textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = AppTheme.TrackColor, focusedBorderColor = AppTheme.AccentPurple, cursorColor = AppTheme.AccentPurple, unfocusedContainerColor = AppTheme.TrackColor.copy(alpha = 0.3f), focusedContainerColor = AppTheme.TrackColor.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth(),
        minLines = 3, maxLines = 6,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { launchVoice() },
        modifier = Modifier.height(40.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple),
        border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f))
    ) {
        Icon(Icons.Outlined.Mic, null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("Voice", style = MaterialTheme.typography.bodySmall)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Page 1 — Migraine Profile (8 questions)
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun AiQuestionsPage1(
    gender: String?, onGender: (String) -> Unit,
    ageRange: String?, onAgeRange: (String) -> Unit,
    frequency: String?, onFrequency: (String) -> Unit,
    duration: String?, onDuration: (String) -> Unit,
    experience: String?, onExperience: (String) -> Unit,
    trajectory: String?, onTrajectory: (String) -> Unit,
    warningBefore: String?, onWarningBefore: (String) -> Unit,
    triggerDelay: String?, onTriggerDelay: (String) -> Unit,
    dailyRoutine: String?, onDailyRoutine: (String) -> Unit,
    seasonalPattern: String?, onSeasonalPattern: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        QPageHeader(Icons.Outlined.Psychology, "About You & Your Migraines", "Help us personalise your experience", 1, 8)
        QCard("What is your gender?", Icons.Outlined.Person, "Used to personalise thresholds (e.g. nutrition, body composition)") { QSingleChips(listOf("Female", "Male", "Prefer not to say"), gender, onGender) }
        QCard("What is your age range?", Icons.Outlined.Cake) { QSingleChips(listOf("18-25", "26-35", "36-45", "46-55", "56+"), ageRange, onAgeRange) }
        QCard("How often do you get migraines?", Icons.Outlined.CalendarMonth) {
            QSingleChips(listOf("A few per year", "Every 1-2 months", "1-3 per month", "Weekly", "Chronic"), frequency, onFrequency)
        }
        QCard("How long do they usually last?", Icons.Outlined.Timer) {
            QSingleChips(listOf("< 4 hours", "4-12 hours", "12-24 hours", "1-3 days", "3+ days"), duration, onDuration)
        }
        QCard("How long have you been getting migraines?", Icons.Outlined.History) {
            QSingleChips(listOf("New / recent", "1-5 years", "5-10 years", "10+ years"), experience, onExperience)
        }
        QCard("Have they been getting better, worse, or the same?", Icons.Outlined.TrendingUp) {
            QSingleChips(listOf("Getting worse", "Getting better", "About the same", "Just started"), trajectory, onTrajectory)
        }
        QCard("Do you get warning signs before a migraine?", Icons.Outlined.Sensors) {
            QSingleChips(listOf("Yes, always", "Sometimes", "Rarely", "Never"), warningBefore, onWarningBefore)
        }
        QCard("After a trigger, how quickly does the migraine come?", Icons.Outlined.Speed) {
            QSingleChips(listOf("Within hours", "Next day", "Within 2-3 days", "Up to a week", "Not sure"), triggerDelay, onTriggerDelay)
        }
        QCard("What best describes your daily routine?", Icons.Outlined.Work) {
            QSingleChips(listOf("Regular 9-5", "Shift work / rotating", "Irregular / freelance", "Student", "Stay at home"), dailyRoutine, onDailyRoutine)
        }
        QCard("Do your migraines follow a seasonal pattern?", Icons.Outlined.WbSunny) {
            QSingleChips(listOf("Worse in winter", "Worse in summer", "Worse in spring", "No pattern", "Not sure"), seasonalPattern, onSeasonalPattern)
        }
        Spacer(Modifier.height(80.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Page 2 — Sleep
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun AiQuestionsPage2(
    sleepHours: String?, onSleepHours: (String) -> Unit,
    sleepQuality: String?, onSleepQuality: (String) -> Unit,
    poorQualityTriggers: DeterministicMapper.Certainty?, onPoorQualityTriggers: (DeterministicMapper.Certainty) -> Unit,
    tooLittleSleepTriggers: DeterministicMapper.Certainty?, onTooLittleSleepTriggers: (DeterministicMapper.Certainty) -> Unit,
    oversleepTriggers: DeterministicMapper.Certainty?, onOversleepTriggers: (DeterministicMapper.Certainty) -> Unit,
    sleepIssues: Set<String>, onToggleSleepIssue: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        QPageHeader(Icons.Outlined.Bedtime, "Sleep", "Sleep is one of the most common migraine triggers", 2, 8)
        QCard("How many hours do you usually sleep?", Icons.Outlined.Schedule) { QSingleChips(listOf("< 5h", "5-6h", "6-7h", "7-8h", "8-9h", "9+h"), sleepHours, onSleepHours) }
        QCard("How would you rate your sleep quality?", Icons.Outlined.NightsStay) { QSingleChips(listOf("Good", "OK", "Poor", "Varies a lot"), sleepQuality, onSleepQuality) }
        QCard("Does POOR QUALITY sleep trigger a migraine?", Icons.Outlined.Bolt, "Restless, waking up, light sleep") { SingleCertaintySelect(poorQualityTriggers, onPoorQualityTriggers) }
        QCard("Does TOO LITTLE sleep trigger a migraine?", Icons.Outlined.Bolt, "Not enough hours") { SingleCertaintySelect(tooLittleSleepTriggers, onTooLittleSleepTriggers) }
        QCard("Does TOO MUCH sleep trigger a migraine?", Icons.Outlined.HotelClass) { SingleCertaintySelect(oversleepTriggers, onOversleepTriggers) }
        val anySleepTrigger = listOf(poorQualityTriggers, tooLittleSleepTriggers, oversleepTriggers).any { it != null && it != DeterministicMapper.Certainty.NO }
        AnimatedVisibility(visible = anySleepTrigger, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            QCard("Any specific sleep issues?", Icons.Outlined.Warning, "Select all that apply") { QMultiChips(listOf("Irregular schedule", "Sleep apnea", "Jet lag", "None of these"), sleepIssues, onToggleSleepIssue) }
        }
        Spacer(Modifier.height(80.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Page 3 — Stress, Emotions & Screen
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun AiQuestionsPage3(
    stressLevel: String?, onStressLevel: (String) -> Unit,
    stressChangeTriggers: DeterministicMapper.Certainty?, onStressChangeTriggers: (DeterministicMapper.Certainty) -> Unit,
    emotionalPatterns: Map<String, DeterministicMapper.Certainty>, onEmotionalPatterns: (Map<String, DeterministicMapper.Certainty>) -> Unit,
    screenTimeDaily: String?, onScreenTimeDaily: (String) -> Unit,
    screenTimeTriggers: DeterministicMapper.Certainty?, onScreenTimeTriggers: (DeterministicMapper.Certainty) -> Unit,
    lateScreenTriggers: DeterministicMapper.Certainty?, onLateScreenTriggers: (DeterministicMapper.Certainty) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        QPageHeader(Icons.Outlined.Psychology, "Stress & Screen", "Emotional and cognitive triggers", 3, 8)
        QCard("How would you describe your general stress level?", Icons.Outlined.Whatshot) { QSingleChips(listOf("Low", "Moderate", "High", "Very high"), stressLevel, onStressLevel) }
        QCard("Does a CHANGE in your stress level trigger migraines?", Icons.Outlined.Bolt) { SingleCertaintySelect(stressChangeTriggers, onStressChangeTriggers) }
        AnimatedVisibility(visible = stressChangeTriggers != null && stressChangeTriggers != DeterministicMapper.Certainty.NO, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            QCard("Which emotional patterns?", Icons.Outlined.Mood, "Select all, set certainty") {
                CertaintyMultiSelect(items = listOf(CertaintyItem("Spike in stress", "A spike in stress", "Work pressure, deadlines"), CertaintyItem("Anxiety", "Anxiety or worry"), CertaintyItem("Anger", "Anger or frustration"), CertaintyItem("Let-down", "After stress ENDS", "Weekend/holiday let-down"), CertaintyItem("Feeling low", "Feeling low or depressed")), selections = emotionalPatterns, onSelectionChanged = onEmotionalPatterns, showNoneOption = false)
            }
        }
        QCard("How much screen time do you have daily?", Icons.Outlined.PhoneAndroid) { QSingleChips(listOf("< 2h", "2-4h", "4-8h", "8-12h", "12h+"), screenTimeDaily, onScreenTimeDaily) }
        QCard("Does screen time trigger migraines?", Icons.Outlined.Bolt) { SingleCertaintySelect(screenTimeTriggers, onScreenTimeTriggers) }
        AnimatedVisibility(visible = screenTimeTriggers != null && screenTimeTriggers != DeterministicMapper.Certainty.NO, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            QCard("Does late-night screen use make it worse?", Icons.Outlined.DarkMode) { SingleCertaintySelect(lateScreenTriggers, onLateScreenTriggers) }
        }
        Spacer(Modifier.height(80.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Page 4 — Diet & Substances
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun AiQuestionsPage4(
    caffeineIntake: String?, onCaffeineIntake: (String) -> Unit,
    caffeineDirection: String?, onCaffeineDirection: (String) -> Unit,
    caffeineCertainty: DeterministicMapper.Certainty?, onCaffeineCertainty: (DeterministicMapper.Certainty) -> Unit,
    alcoholFrequency: String?, onAlcoholFrequency: (String) -> Unit,
    alcoholTriggers: DeterministicMapper.Certainty?, onAlcoholTriggers: (DeterministicMapper.Certainty) -> Unit,
    specificDrinks: Set<String>, onToggleDrink: (String) -> Unit,
    tyramineFoods: Map<String, DeterministicMapper.Certainty>, onTyramineFoods: (Map<String, DeterministicMapper.Certainty>) -> Unit,
    glutenSensitivity: String?, onGlutenSensitivity: (String) -> Unit,
    glutenTriggers: DeterministicMapper.Certainty?, onGlutenTriggers: (DeterministicMapper.Certainty) -> Unit,
    eatingPatterns: Map<String, DeterministicMapper.Certainty>, onEatingPatterns: (Map<String, DeterministicMapper.Certainty>) -> Unit,
    waterIntake: String?, onWaterIntake: (String) -> Unit,
    tracksNutrition: String?, onTracksNutrition: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        QPageHeader(Icons.Outlined.Restaurant, "Diet & Substances", "Food, drink, and nutrition triggers", 4, 8)
        QCard("How much caffeine do you have daily?", Icons.Outlined.LocalCafe) { QSingleChips(listOf("None", "1-2 cups", "3-4 cups", "5+ cups"), caffeineIntake, onCaffeineIntake) }
        QCard("Does caffeine affect your migraines?", Icons.Outlined.Bolt) { QSingleChips(listOf("Too much triggers it", "Missing caffeine triggers it", "Both ways", "Not sure", "No"), caffeineDirection, onCaffeineDirection) }
        AnimatedVisibility(visible = caffeineDirection != null && caffeineDirection != "No", enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            QCard("How certain about the caffeine link?", Icons.Outlined.TrendingUp) { SingleCertaintySelect(caffeineCertainty, onCaffeineCertainty) }
        }
        QCard("How often do you drink alcohol?", Icons.Outlined.LocalBar) { QSingleChips(listOf("Never", "Occasionally", "Weekly", "Daily"), alcoholFrequency, onAlcoholFrequency) }
        AnimatedVisibility(visible = alcoholFrequency != null && alcoholFrequency != "Never", enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                QCard("Does alcohol trigger migraines?", Icons.Outlined.Bolt) { SingleCertaintySelect(alcoholTriggers, onAlcoholTriggers) }
                AnimatedVisibility(visible = alcoholTriggers != null && alcoholTriggers != DeterministicMapper.Certainty.NO, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    QCard("Are specific drinks worse?", Icons.Outlined.WineBar, "Select all that apply") { QMultiChips(listOf("Red wine", "Beer", "White wine", "Spirits", "Any alcohol"), specificDrinks, onToggleDrink) }
                }
            }
        }
        QCard("Do any of these foods trigger migraines?", Icons.Outlined.Fastfood, "Select all, set certainty") {
            CertaintyMultiSelect(items = listOf(CertaintyItem("Aged cheese", "Aged cheese", "Parmesan, brie, blue cheese"), CertaintyItem("Chocolate", "Chocolate"), CertaintyItem("Cured meats", "Cured or processed meats", "Salami, bacon, hot dogs"), CertaintyItem("Fermented foods", "Fermented foods", "Soy sauce, kimchi, miso")), selections = tyramineFoods, onSelectionChanged = onTyramineFoods)
        }
        QCard("Are you sensitive to gluten?", Icons.Outlined.SetMeal) { QSingleChips(listOf("Yes, diagnosed", "I suspect so", "No", "Not sure"), glutenSensitivity, onGlutenSensitivity) }
        AnimatedVisibility(visible = glutenSensitivity == "Yes, diagnosed" || glutenSensitivity == "I suspect so", enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            QCard("Does eating gluten trigger migraines?", Icons.Outlined.Bolt) { SingleCertaintySelect(glutenTriggers, onGlutenTriggers) }
        }
        QCard("Do any eating patterns trigger migraines?", Icons.Outlined.NoMeals, "Select all, set certainty") {
            CertaintyMultiSelect(items = listOf(CertaintyItem("Skipping meals", "Skipping meals or fasting"), CertaintyItem("Sugar", "Eating too much sugar"), CertaintyItem("Salty food", "Eating very salty food"), CertaintyItem("Overeating", "Overeating"), CertaintyItem("Dehydration", "Dehydration / not drinking enough")), selections = eatingPatterns, onSelectionChanged = onEatingPatterns)
        }
        QCard("How much water do you drink daily?", Icons.Outlined.WaterDrop) { QSingleChips(listOf("< 1L", "1-2L", "2-3L", "3L+"), waterIntake, onWaterIntake) }
        QCard("Do you track your nutrition?", Icons.Outlined.Inventory, "Food diary, MyFitnessPal, etc.") { QSingleChips(listOf("Yes, regularly", "Sometimes", "No"), tracksNutrition, onTracksNutrition) }
        Spacer(Modifier.height(80.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Page 5 — Weather, Environment & Physical
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun AiQuestionsPage5(
    weatherTriggers: DeterministicMapper.Certainty?, onWeatherTriggers: (DeterministicMapper.Certainty) -> Unit,
    specificWeather: Map<String, DeterministicMapper.Certainty>, onSpecificWeather: (Map<String, DeterministicMapper.Certainty>) -> Unit,
    environmentSensitivities: Map<String, DeterministicMapper.Certainty>, onEnvironmentSensitivities: (Map<String, DeterministicMapper.Certainty>) -> Unit,
    physicalFactors: Map<String, DeterministicMapper.Certainty>, onPhysicalFactors: (Map<String, DeterministicMapper.Certainty>) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        QPageHeader(Icons.Outlined.Cloud, "Weather, Environment & Physical", "External and physical triggers", 5, 8)
        QCard("Does weather affect your migraines?", Icons.Outlined.Thunderstorm) { SingleCertaintySelect(weatherTriggers, onWeatherTriggers) }
        AnimatedVisibility(visible = weatherTriggers != null && weatherTriggers != DeterministicMapper.Certainty.NO, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            QCard("Which weather changes?", Icons.Outlined.Air, "Select all, set certainty") {
                CertaintyMultiSelect(items = listOf(CertaintyItem("Pressure changes", "Pressure/barometric changes", "Before storms"), CertaintyItem("Hot weather", "Hot weather or heat waves"), CertaintyItem("Cold weather", "Cold weather"), CertaintyItem("Humidity", "Humid or muggy weather"), CertaintyItem("Dry air", "Dry air"), CertaintyItem("Wind", "Strong wind"), CertaintyItem("Sunshine", "Bright sunshine / strong UV"), CertaintyItem("Thunderstorms", "Thunderstorms / electrical storms"), CertaintyItem("Not sure which", "Not sure — weather just affects me")), selections = specificWeather, onSelectionChanged = onSpecificWeather, showNoneOption = false)
            }
        }
        QCard("Are you sensitive to any of these?", Icons.Outlined.Visibility, "Select all, set certainty") {
            CertaintyMultiSelect(items = listOf(CertaintyItem("Fluorescent lights", "Bright or fluorescent lights"), CertaintyItem("Strong smells", "Strong smells (perfume, cleaning products)"), CertaintyItem("Loud noise", "Loud noise or sudden sounds"), CertaintyItem("Smoke", "Smoke or fumes"), CertaintyItem("Altitude", "High altitude or altitude changes")), selections = environmentSensitivities, onSelectionChanged = onEnvironmentSensitivities)
        }
        QCard("Do any physical factors trigger migraines?", Icons.Outlined.Healing, "Select all, set certainty") {
            CertaintyMultiSelect(items = listOf(CertaintyItem("Allergies", "Allergies or hayfever"), CertaintyItem("Being ill", "Being ill (cold, flu, infection)"), CertaintyItem("Low blood sugar", "Low blood sugar (shaky, faint)"), CertaintyItem("Medication change", "Changing or missing medication"), CertaintyItem("Motion sickness", "Motion sickness or travel"), CertaintyItem("Tobacco", "Tobacco or nicotine"), CertaintyItem("Sexual activity", "Sexual activity")), selections = physicalFactors, onSelectionChanged = onPhysicalFactors)
        }
        Spacer(Modifier.height(80.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Page 6 — Exercise & Hormones
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun AiQuestionsPage6(
    exerciseFrequency: String?, onExerciseFrequency: (String) -> Unit,
    exerciseTriggers: DeterministicMapper.Certainty?, onExerciseTriggers: (DeterministicMapper.Certainty) -> Unit,
    exercisePattern: Set<String>, onToggleExercisePattern: (String) -> Unit,
    tracksCycle: String?, onTracksCycle: (String) -> Unit,
    cyclePatterns: Map<String, DeterministicMapper.Certainty>, onCyclePatterns: (Map<String, DeterministicMapper.Certainty>) -> Unit,
    cycleLength: String?, onCycleLength: (String) -> Unit,
    cycleMigraineTiming: Set<String>, onToggleCycleMigraineTiming: (String) -> Unit,
    lastPeriodDate: String?, onLastPeriodDate: (String) -> Unit,
    usesContraception: String?, onUsesContraception: (String) -> Unit,
    contraceptionEffect: String?, onContraceptionEffect: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        QPageHeader(Icons.Outlined.FitnessCenter, "Exercise & Hormones", "Physical activity and hormonal triggers", 6, 8)
        QCard("How often do you exercise?", Icons.Outlined.DirectionsRun) { QSingleChips(listOf("Daily", "Few times/week", "Weekly", "Rarely", "Never"), exerciseFrequency, onExerciseFrequency) }
        QCard("Does exercise trigger migraines?", Icons.Outlined.Bolt) { SingleCertaintySelect(exerciseTriggers, onExerciseTriggers) }
        AnimatedVisibility(visible = exerciseTriggers != null && exerciseTriggers != DeterministicMapper.Certainty.NO, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            QCard("Which pattern?", Icons.Outlined.Loop, "Select all that apply") { QMultiChips(listOf("During or after intense exercise", "When I haven't exercised"), exercisePattern, onToggleExercisePattern) }
        }
        QCard("Do you track your menstrual cycle?", Icons.Outlined.Female) { QSingleChips(listOf("Yes", "No", "Not applicable"), tracksCycle, onTracksCycle) }
        AnimatedVisibility(visible = tracksCycle == "Yes", enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                QCard("Do migraines relate to your cycle?", Icons.Outlined.Loop, "Select all, set certainty") {
                    CertaintyMultiSelect(items = listOf(CertaintyItem("Around my period", "Around my period"), CertaintyItem("Around ovulation", "Around ovulation (mid-cycle)")), selections = cyclePatterns, onSelectionChanged = onCyclePatterns, showNoneOption = true)
                }
                QCard("How long is your average cycle?", Icons.Outlined.CalendarMonth) { QSingleChips(listOf("< 25 days", "25-28 days", "28-32 days", "32-35 days", "> 35 days", "Irregular"), cycleLength, onCycleLength) }
                QCard("When did your last period start?", Icons.Outlined.DateRange, "Helps us predict your next one") {
                    val ctx = LocalContext.current
                    val parsed = lastPeriodDate?.takeIf { it.isNotBlank() }?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                    val initial = parsed ?: java.time.LocalDate.now()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                android.app.DatePickerDialog(ctx, { _, y, m, d -> onLastPeriodDate("%04d-%02d-%02d".format(y, m + 1, d)) },
                                    initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(if (lastPeriodDate.isNullOrBlank()) "Select date" else lastPeriodDate, color = Color.White) }
                        if (!lastPeriodDate.isNullOrBlank()) {
                            TextButton(onClick = { onLastPeriodDate("") }) { Text("Clear", color = AppTheme.AccentPurple) }
                        }
                    }
                    if (lastPeriodDate.isNullOrBlank()) Text("Optional — you can set this later", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
                AnimatedVisibility(visible = cyclePatterns.any { it.key == "Around my period" && it.value != DeterministicMapper.Certainty.NO }, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    QCard("When relative to your period?", Icons.Outlined.Schedule, "Select all that apply") {
                        QMultiChips(listOf("1-2 days before", "3-5 days before", "During my period", "1-2 days after"), cycleMigraineTiming, onToggleCycleMigraineTiming)
                    }
                }
            }
        }
        AnimatedVisibility(visible = tracksCycle != "Not applicable" && tracksCycle != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                QCard("Do you use hormonal contraception?", Icons.Outlined.Medication) { QSingleChips(listOf("Yes", "No"), usesContraception, onUsesContraception) }
                AnimatedVisibility(visible = usesContraception == "Yes", enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    QCard("Has contraception affected your migraines?", Icons.Outlined.Bolt) { QSingleChips(listOf("Worse — every time", "Worse — sometimes", "No change", "Actually helps"), contraceptionEffect, onContraceptionEffect) }
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Page 7 — Warning Signs (Prodromes)
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun AiQuestionsPage7(
    physicalProdromes: Map<String, DeterministicMapper.Certainty>, onPhysicalProdromes: (Map<String, DeterministicMapper.Certainty>) -> Unit,
    moodProdromes: Map<String, DeterministicMapper.Certainty>, onMoodProdromes: (Map<String, DeterministicMapper.Certainty>) -> Unit,
    sensoryProdromes: Map<String, DeterministicMapper.Certainty>, onSensoryProdromes: (Map<String, DeterministicMapper.Certainty>) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        QPageHeader(Icons.Outlined.Sensors, "Warning Signs", "Subtle changes before a migraine can help predict attacks", 7, 8)
        QCard("Before a migraine, do you notice physical changes?", Icons.Outlined.AccessibilityNew, "Select all, set certainty") {
            CertaintyMultiSelect(items = listOf(CertaintyItem("Neck stiffness", "Neck stiffness or tension"), CertaintyItem("Yawning", "Excessive yawning"), CertaintyItem("Urination", "Frequent need to urinate"), CertaintyItem("Stuffy nose", "Stuffy or runny nose"), CertaintyItem("Watery eyes", "Watery eyes"), CertaintyItem("Muscle tension", "General muscle tension (shoulders, jaw)")), selections = physicalProdromes, onSelectionChanged = onPhysicalProdromes)
        }
        QCard("Mood or thinking changes?", Icons.Outlined.Mood, "Select all, set certainty") {
            CertaintyMultiSelect(items = listOf(CertaintyItem("Concentrating", "Difficulty concentrating"), CertaintyItem("Words", "Can't find the right words"), CertaintyItem("Irritability", "Irritability or short temper"), CertaintyItem("Mood swings", "Mood swings"), CertaintyItem("Feeling low", "Feeling unusually low or sad"), CertaintyItem("Unusually happy", "Unusually happy or energetic"), CertaintyItem("Food cravings", "Intense food cravings"), CertaintyItem("Loss of appetite", "Loss of appetite")), selections = moodProdromes, onSelectionChanged = onMoodProdromes)
        }
        QCard("Sensory changes?", Icons.Outlined.Visibility, "Select all, set certainty") {
            CertaintyMultiSelect(items = listOf(CertaintyItem("Light", "Sensitivity to light"), CertaintyItem("Sound", "Sensitivity to sound"), CertaintyItem("Smell", "Sensitivity to smell"), CertaintyItem("Tingling", "Tingling or pins and needles"), CertaintyItem("Numbness", "Numbness")), selections = sensoryProdromes, onSelectionChanged = onSensoryProdromes)
        }
        Spacer(Modifier.height(80.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Page 8 — Symptoms, Medicines & More
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun AiQuestionsPage8(
    symptomPool: List<AiSetupService.PoolLabel>,
    medicinePool: List<AiSetupService.PoolLabel>,
    reliefPool: List<AiSetupService.PoolLabel>,
    activityPool: List<AiSetupService.PoolLabel>,
    missedActivityPool: List<AiSetupService.PoolLabel>,
    selectedSymptoms: Set<String>, onToggleSymptom: (String) -> Unit,
    selectedMedicines: Set<String>, onToggleMedicine: (String) -> Unit,
    selectedReliefs: Set<String>, onToggleRelief: (String) -> Unit,
    selectedActivities: Set<String>, onToggleActivity: (String) -> Unit,
    selectedMissedActivities: Set<String>, onToggleMissed: (String) -> Unit,
    additionalNotes: String?, onAdditionalNotes: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        QPageHeader(Icons.Outlined.MedicalServices, "Symptoms, Medicines & More", "Select what you experience and use", 8, 8)
        QCard("What symptoms do you experience?", Icons.Outlined.Healing, "Tap all that apply") { QPoolMultiSelect(symptomPool, selectedSymptoms, onToggleSymptom, AppTheme.AccentPink, PoolType.SYMPTOM) }
        QCard("What medicines do you take?", Icons.Outlined.Medication, "Tap all that apply") { QPoolMultiSelect(medicinePool, selectedMedicines, onToggleMedicine, Color(0xFF4FC3F7), PoolType.MEDICINE) }
        QCard("What helps relieve your migraines?", Icons.Outlined.Spa, "Tap all that apply") { QPoolMultiSelect(reliefPool, selectedReliefs, onToggleRelief, Color(0xFF81C784), PoolType.RELIEF) }
        QCard("What are you usually doing when migraines hit?", Icons.Outlined.DirectionsRun, "Tap all that apply") { QPoolMultiSelect(activityPool, selectedActivities, onToggleActivity, Color(0xFFFF8A65), PoolType.ACTIVITY) }
        QCard("What do you miss because of migraines?", Icons.Outlined.EventBusy, "Tap all that apply") { QPoolMultiSelect(missedActivityPool, selectedMissedActivities, onToggleMissed, Color(0xFFFF7043), PoolType.MISSED_ACTIVITY) }
        QCard("Anything else we should know?", Icons.Outlined.Mic, "Type or speak — helps AI understand you better") { QFreeText(additionalNotes ?: "", onAdditionalNotes, "e.g. chocolate is really bad, I work night shifts, migraines always come after flying...") }
        Spacer(Modifier.height(80.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Story Page — voice/text input to pre-fill everything
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun AiQuestionsPageStory(
    text: String,
    onTextChange: (String) -> Unit,
    isLoading: Boolean,
    onParse: () -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current

    val speechLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                onTextChange(if (text.isBlank()) spoken else "$text $spoken")
            }
        }
    }

    fun launchVoice() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Tell us about your migraines…")
        }
        try { speechLauncher.launch(intent) } catch (_: Exception) {
            android.widget.Toast.makeText(context, "Voice input not available", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Hero card with speech bubble icon, title & subtitle (matches iOS)
        Card(
            colors = CardDefaults.cardColors(containerColor = AppTheme.HeroCardContainer),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(48.dp).background(AppTheme.AccentPurple.copy(alpha = 0.25f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Chat, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("Tell us about your migraines", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text("Tap the mic and talk, or type below. AI will use this to pre-fill your profile.", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }

        // Mic button
        Button(
            onClick = { launchVoice() },
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple.copy(alpha = 0.15f)),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(Icons.Outlined.Mic, contentDescription = "Tap to speak", tint = AppTheme.AccentPurple, modifier = Modifier.size(36.dp))
        }
        Text("Tap to speak", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)

        // Text input area
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text("e.g. \"I get migraines about twice a month, usually triggered by poor sleep and stress. They last about a day. I take sumatriptan and lie in a dark room. I notice neck stiffness before they start…\"", color = AppTheme.SubtleTextColor.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = AppTheme.BodyTextColor,
                cursorColor = AppTheme.AccentPurple, focusedBorderColor = AppTheme.AccentPurple,
                unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                focusedContainerColor = Color.White.copy(alpha = 0.06f)
            ),
            shape = RoundedCornerShape(12.dp),
            minLines = 5, maxLines = 10,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White)
        )

        // Loading indicator
        if (isLoading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(16.dp), Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Analysing…", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(80.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Triggers Page — pool selector (same style as symptoms/medicines on Q8)
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun AiQuestionsPageTriggers(
    triggerPool: List<AiSetupService.PoolLabel>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        QPageHeader(Icons.Outlined.Whatshot, "Your Triggers", "Tap anything that triggers your migraines — we've pre-selected what we found", 9, 14)
        if (selected.isNotEmpty()) {
            Text("${selected.size} selected", color = AppTheme.AccentPurple, style = MaterialTheme.typography.labelSmall)
        }
        QPoolMultiSelect(triggerPool, selected, onToggle, Color(0xFFFFB74D), PoolType.TRIGGER)
        Spacer(Modifier.height(80.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Prodromes Page — pool selector (same style as symptoms/medicines on Q8)
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun AiQuestionsPageProdromes(
    prodromePool: List<AiSetupService.PoolLabel>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        QPageHeader(Icons.Outlined.Sensors, "Warning Signs", "Tap any signs you notice before a migraine — we've pre-selected what we found", 10, 14)
        if (selected.isNotEmpty()) {
            Text("${selected.size} selected", color = AppTheme.AccentPurple, style = MaterialTheme.typography.labelSmall)
        }
        QPoolMultiSelect(prodromePool, selected, onToggle, Color(0xFF9575CD), PoolType.PRODROME)
        Spacer(Modifier.height(80.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Processing Page
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun AiProcessingPage(isLoading: Boolean, error: String?, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(80.dp).background(
            Brush.linearGradient(listOf(AppTheme.AccentPurple.copy(alpha = 0.3f), AppTheme.AccentPink.copy(alpha = 0.2f))),
            RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(24.dp))
        if (isLoading) {
            Text("Personalising your app...", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("MigraineMe is analysing your migraine profile to configure everything.", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.6f).height(4.dp).clip(RoundedCornerShape(2.dp)), color = AppTheme.AccentPink, trackColor = AppTheme.TrackColor)
            Spacer(Modifier.height(12.dp))
            Text("This takes about 5 seconds", color = AppTheme.SubtleTextColor.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
        }
        if (error != null) {
            Text("Something went wrong", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(error, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple), shape = RoundedCornerShape(12.dp)) { Text("Try Again") }
            Spacer(Modifier.height(8.dp))
            Text("Or press Next to skip AI setup", color = AppTheme.SubtleTextColor.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
        }
    }
}