package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickMigraineScreen(
    navController: NavController,
    authVm: AuthViewModel,
    symptomVm: SymptomViewModel,
    onClose: () -> Unit = {}
) {
    val authState by authVm.state.collectAsState()
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    // Symptom pools
    val painCharacter by symptomVm.painCharacter.collectAsState()
    val accompanying by symptomVm.accompanying.collectAsState()
    val favorites by symptomVm.favorites.collectAsState()

    LaunchedEffect(authState.accessToken) {
        authState.accessToken?.let { symptomVm.loadAll(it) }
    }

    // State
    val selectedSymptoms = remember { mutableStateListOf<String>() }
    var severity by rememberSaveable { mutableStateOf(5) }
    var beganAtIso by rememberSaveable { mutableStateOf<String?>(null) }
    var endedAtIso by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun save() {
        val token = authState.accessToken ?: return
        saving = true
        scope.launch {
            try {
                val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                val typeLabel = if (selectedSymptoms.isEmpty()) "Migraine" else selectedSymptoms.joinToString(", ")
                db.insertMigraine(
                    accessToken = token,
                    type = typeLabel,
                    severity = severity,
                    startAt = beganAtIso ?: Instant.now().toString(),
                    endAt = endedAtIso,
                    notes = null,
                    painLocations = null
                )
                navController.popBackStack()
            } catch (e: Exception) {
                e.printStackTrace()
                saving = false
            }
        }
    }

    // Split frequent by category
    val freqPainIds = favorites.filter { it.symptom?.category == "pain_character" }.mapNotNull { it.symptom?.label }.toSet()
    val freqAccompIds = favorites.filter { it.symptom?.category == "accompanying" }.mapNotNull { it.symptom?.label }.toSet()

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            // Top bar
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(48.dp))
                Text("Quick Log", color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, "Close", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            // Hero
            HeroCard {
                Box(Modifier.size(40.dp).drawBehind { HubIcons.run { drawMigraineStarburst(AppTheme.AccentPink) } })
                Text("Log a migraine", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(
                    if (selectedSymptoms.isEmpty()) "Select symptoms below"
                    else "${selectedSymptoms.size} symptom${if (selectedSymptoms.size > 1) "s" else ""} selected",
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center
                )
            }

            // ── Severity ──
            BaseCard {
                Text("Severity", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("$severity", color = AppTheme.AccentPink, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
                    Slider(
                        value = severity.toFloat(),
                        onValueChange = { severity = it.toInt() },
                        valueRange = 1f..10f,
                        steps = 8,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = AppTheme.AccentPink,
                            activeTrackColor = AppTheme.AccentPink,
                            inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                        )
                    )
                    Text("/10", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
            }

            // ── Timing ──
            BaseCard {
                Text("Timing", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                Spacer(Modifier.height(8.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Started", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(4.dp))
                        AppDateTimePicker(label = formatQuickTime(beganAtIso) ?: "Now", onDateTimeSelected = { beganAtIso = it })
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Ended", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(4.dp))
                        AppDateTimePicker(label = formatQuickTime(endedAtIso) ?: "Still going", onDateTimeSelected = { endedAtIso = it })
                    }
                }
            }

            // ── Symptoms ──
            // Pain character
            BaseCard {
                Text("Pain character", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))

                if (freqPainIds.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        painCharacter.filter { it.label in freqPainIds }.forEach { s ->
                            QuickSymptomButton(s.label, s.label in selectedSymptoms, s.iconKey) {
                                if (s.label in selectedSymptoms) selectedSymptoms.remove(s.label) else selectedSymptoms.add(s.label)
                            }
                        }
                    }
                    val rest = painCharacter.filter { it.label !in freqPainIds }
                    if (rest.isNotEmpty()) HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                }
                val nonFreqPain = painCharacter.filter { it.label !in freqPainIds }
                if (nonFreqPain.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        nonFreqPain.forEach { s ->
                            QuickSymptomButton(s.label, s.label in selectedSymptoms, s.iconKey) {
                                if (s.label in selectedSymptoms) selectedSymptoms.remove(s.label) else selectedSymptoms.add(s.label)
                            }
                        }
                    }
                }
            }

            // Accompanying
            BaseCard {
                Text("Accompanying", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))

                if (freqAccompIds.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        accompanying.filter { it.label in freqAccompIds }.forEach { s ->
                            QuickSymptomButton(s.label, s.label in selectedSymptoms, s.iconKey) {
                                if (s.label in selectedSymptoms) selectedSymptoms.remove(s.label) else selectedSymptoms.add(s.label)
                            }
                        }
                    }
                    val rest = accompanying.filter { it.label !in freqAccompIds }
                    if (rest.isNotEmpty()) HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                }
                val nonFreqAccomp = accompanying.filter { it.label !in freqAccompIds }
                if (nonFreqAccomp.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        nonFreqAccomp.forEach { s ->
                            QuickSymptomButton(s.label, s.label in selectedSymptoms, s.iconKey) {
                                if (s.label in selectedSymptoms) selectedSymptoms.remove(s.label) else selectedSymptoms.add(s.label)
                            }
                        }
                    }
                }
            }

            // ── Save ──
            Button(
                onClick = { save() },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink)
            ) {
                Text(if (saving) "Saving…" else "Save Migraine", fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun QuickSymptomButton(label: String, isSelected: Boolean, iconKey: String? = null, onClick: () -> Unit) {
    val icon = SymptomIcons.forLabel(label, iconKey)
    val circleColor = if (isSelected) AppTheme.AccentPurple.copy(alpha = 0.40f) else Color.White.copy(alpha = 0.08f)
    val borderColor = if (isSelected) AppTheme.AccentPurple.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.12f)
    val iconTint = if (isSelected) Color.White else AppTheme.SubtleTextColor
    val textColor = if (isSelected) Color.White else AppTheme.BodyTextColor

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(circleColor).border(1.5.dp, borderColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(26.dp))
            } else {
                Text(label.take(2).uppercase(), color = iconTint, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = textColor, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth())
    }
}

private fun formatQuickTime(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return try {
        val odt = runCatching { OffsetDateTime.parse(iso) }.getOrNull()
        val ldt = odt?.toLocalDateTime()
            ?: runCatching { LocalDateTime.parse(iso) }.getOrNull()
            ?: runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull()
            ?: return null
        ldt.format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
    } catch (_: Exception) { null }
}

