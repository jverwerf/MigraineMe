package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogHomeScreen(
    navController: NavController,
    authVm: AuthViewModel,
    vm: LogViewModel,
    symptomVm: SymptomViewModel,
    onClose: () -> Unit = {}
) {
    val draft by vm.draft.collectAsState()
    val authState by authVm.state.collectAsState()
    val scrollState = rememberScrollState()

    // Load symptoms from Supabase
    val painCharacter by symptomVm.painCharacter.collectAsState()
    val accompanying by symptomVm.accompanying.collectAsState()
    val favorites by symptomVm.favorites.collectAsState()
    val favoriteIds by symptomVm.favoriteIds.collectAsState()

    LaunchedEffect(authState.accessToken) {
        authState.accessToken?.let { symptomVm.loadAll(it) }
    }

    // UI state — initialize from draft if available
    val selectedSymptoms = remember {
        mutableStateListOf<String>().apply {
            draft.migraine?.symptoms?.let { addAll(it) }
        }
    }
    var notes by rememberSaveable { mutableStateOf(draft.migraine?.note ?: "") }

    // Sync from existing draft when it changes
    LaunchedEffect(draft.migraine?.symptoms, draft.migraine?.note) {
        draft.migraine?.let { m ->
            if (selectedSymptoms.isEmpty() && m.symptoms.isNotEmpty()) {
                selectedSymptoms.addAll(m.symptoms)
            }
            if (notes.isEmpty() && !m.note.isNullOrBlank()) {
                notes = m.note ?: ""
            }
        }
    }

    // Auto-init draft
    LaunchedEffect(Unit) {
        if (draft.migraine == null) {
            vm.setMigraineDraft(type = "Migraine", severity = 5, beganAtIso = null, endedAtIso = null, note = null)
        }
    }

    fun syncDraft() {
        val typeLabel = if (selectedSymptoms.isEmpty()) "Migraine" else selectedSymptoms.joinToString(", ")
        vm.setMigraineDraft(
            type = typeLabel,
            note = notes.ifBlank { null },
            symptoms = selectedSymptoms.toList()
        )
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 60.dp) {

            // Top bar: ← Previous | Title | X Close
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onClose() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Home", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
                Text("Log Migraine", color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            // Hero
            HeroCard {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .drawBehind { HubIcons.run { drawMigraineStarburst(AppTheme.AccentPink) } }
                )
                Text(
                    "What are you experiencing?",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )
                Text(
                    if (selectedSymptoms.isEmpty()) "Select all that apply"
                    else "${selectedSymptoms.size} symptom${if (selectedSymptoms.size > 1) "s" else ""} selected",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            // Manage card (always on top)
            BaseCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Symptoms", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Text("Manage →", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clickable { navController.navigate(Routes.MANAGE_SYMPTOMS) })
                }
            }

            // Split frequent by category
            val freqPainIds = favorites.filter { it.symptom?.category == "pain_character" }.mapNotNull { it.symptom?.label }.toSet()
            val freqAccompIds = favorites.filter { it.symptom?.category == "accompanying" }.mapNotNull { it.symptom?.label }.toSet()

            // Pain character card
            BaseCard {
                Text("Pain character", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))

                // Frequent pain symptoms first
                if (freqPainIds.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        painCharacter.filter { it.label in freqPainIds }.forEach { symptom ->
                            SymptomButton(symptom.label, symptom.label in selectedSymptoms, iconKey = symptom.iconKey) {
                                if (symptom.label in selectedSymptoms) selectedSymptoms.remove(symptom.label) else selectedSymptoms.add(symptom.label)
                                syncDraft()
                            }
                        }
                    }
                    // Divider between frequent and rest
                    val remainingPain = painCharacter.filter { it.label !in freqPainIds }
                    if (remainingPain.isNotEmpty()) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    }
                }

                // Remaining pain symptoms
                val remainingPain = painCharacter.filter { it.label !in freqPainIds }
                if (remainingPain.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        remainingPain.forEach { symptom ->
                            SymptomButton(symptom.label, symptom.label in selectedSymptoms, iconKey = symptom.iconKey) {
                                if (symptom.label in selectedSymptoms) selectedSymptoms.remove(symptom.label) else selectedSymptoms.add(symptom.label)
                                syncDraft()
                            }
                        }
                    }
                } else if (freqPainIds.isEmpty()) {
                    Text("Loading…", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Accompanying experience card
            BaseCard {
                Text("Accompanying experience", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))

                // Frequent accompanying symptoms first
                if (freqAccompIds.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        accompanying.filter { it.label in freqAccompIds }.forEach { symptom ->
                            SymptomButton(symptom.label, symptom.label in selectedSymptoms, iconKey = symptom.iconKey) {
                                if (symptom.label in selectedSymptoms) selectedSymptoms.remove(symptom.label) else selectedSymptoms.add(symptom.label)
                                syncDraft()
                            }
                        }
                    }
                    // Divider between frequent and rest
                    val remainingAccomp = accompanying.filter { it.label !in freqAccompIds }
                    if (remainingAccomp.isNotEmpty()) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    }
                }

                // Remaining accompanying symptoms
                val remainingAccomp = accompanying.filter { it.label !in freqAccompIds }
                if (remainingAccomp.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        remainingAccomp.forEach { symptom ->
                            SymptomButton(symptom.label, symptom.label in selectedSymptoms, iconKey = symptom.iconKey) {
                                if (symptom.label in selectedSymptoms) selectedSymptoms.remove(symptom.label) else selectedSymptoms.add(symptom.label)
                                syncDraft()
                            }
                        }
                    }
                } else if (freqAccompIds.isEmpty()) {
                    Text("Loading…", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Notes Card
            BaseCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Notes, contentDescription = null, tint = AppTheme.AccentPurple, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Notes", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { v -> notes = v; syncDraft() },
                    placeholder = { Text("Add notes about this migraine…", color = AppTheme.SubtleTextColor) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = AppTheme.BodyTextColor,
                        cursorColor = AppTheme.AccentPurple, focusedBorderColor = AppTheme.AccentPurple,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f)
                    ),
                    minLines = 2
                )
            }

            // Navigation
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = { navController.navigate(Routes.TIMING) },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                ) { Text("Next") }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SymptomButton(label: String, isSelected: Boolean, iconKey: String? = null, onClick: () -> Unit) {
    val icon = SymptomIcons.forLabel(label, iconKey)
    val circleColor = if (isSelected) AppTheme.AccentPurple.copy(alpha = 0.40f) else Color.White.copy(alpha = 0.08f)
    val borderColor = if (isSelected) AppTheme.AccentPurple.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.12f)
    val iconTint = if (isSelected) Color.White else AppTheme.SubtleTextColor
    val textColor = if (isSelected) Color.White else AppTheme.BodyTextColor

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(circleColor)
                .then(
                    Modifier.border(width = 1.5.dp, color = borderColor, shape = CircleShape)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(26.dp))
            } else {
                Text(
                    label.take(2).uppercase(),
                    color = iconTint,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = textColor,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

