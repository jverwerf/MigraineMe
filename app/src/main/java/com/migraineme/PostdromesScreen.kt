package com.migraineme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

/**
 * Postdrome step — recovery symptoms that lingered after the attack ended.
 * Reads/writes from the same draft.migraine.symptoms list as the Symptoms step,
 * filtered to user_symptoms rows with category == "Postdrome".
 * Sits between Activities and MissedActivities in the wizard order.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PostdromesScreen(
    navController: NavController,
    authVm: AuthViewModel,
    vm: LogViewModel,
    symptomVm: SymptomViewModel,
    onClose: () -> Unit = {}
) {
    val draft by vm.draft.collectAsState()
    val authState by authVm.state.collectAsState()
    val scrollState = rememberScrollState()

    val postdromeItems by symptomVm.postdrome.collectAsState()
    val favoriteIds by symptomVm.favoriteIds.collectAsState()

    LaunchedEffect(authState.accessToken) {
        authState.accessToken?.let { symptomVm.loadAll(it) }
    }

    val selectedSymptoms = remember {
        mutableStateListOf<String>().apply {
            draft.migraine?.symptoms?.let { addAll(it) }
        }
    }
    LaunchedEffect(draft.migraine?.symptoms) {
        draft.migraine?.symptoms?.let { dsyms ->
            // Sync any selections made elsewhere in the wizard
            if (selectedSymptoms.toSet() != dsyms.toSet()) {
                selectedSymptoms.clear()
                selectedSymptoms.addAll(dsyms)
            }
        }
    }

    fun syncDraft() {
        val typeLabel = if (selectedSymptoms.isEmpty()) "Migraine" else selectedSymptoms.joinToString(", ")
        vm.setMigraineDraft(
            type = typeLabel,
            symptoms = selectedSymptoms.toList()
        )
    }

    val frequentLabels = postdromeItems.filter { it.id in favoriteIds }.map { it.label }.toSet()
    val frequent = postdromeItems.filter { it.label in frequentLabels }
    val rest = postdromeItems.filter { it.label !in frequentLabels }
    val accent = Color(0xFFCE93D8)

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Back", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
                Text("Log Migraine", color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            HeroCard {
                Text("Postdrome", color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center)
                Text("Recovery symptoms that lingered after the attack ended",
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center)
            }

            // Selected summary
            val selectedPostdromes = selectedSymptoms.filter { sym -> postdromeItems.any { it.label == sym } }
            if (selectedPostdromes.isNotEmpty()) {
                BaseCard {
                    Text("${selectedPostdromes.size} selected", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                    selectedPostdromes.forEach { sym ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(sym, color = Color.White.copy(alpha = 0.82f),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                modifier = Modifier.weight(1f))
                            Text("×", color = accent.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.clickable {
                                    selectedSymptoms.remove(sym)
                                    syncDraft()
                                })
                        }
                    }
                }
            }

            // Manage card
            BaseCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Postdrome", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Text("Manage →", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clickable { navController.navigate(Routes.MANAGE_SYMPTOMS) })
                }
            }

            BaseCard {
                Text("Recovery symptoms", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                if (frequent.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        frequent.forEach { symptom ->
                            SymptomButton(symptom.label, symptom.label in selectedSymptoms, iconKey = symptom.iconKey) {
                                if (symptom.label in selectedSymptoms) selectedSymptoms.remove(symptom.label) else selectedSymptoms.add(symptom.label)
                                syncDraft()
                            }
                        }
                    }
                    if (rest.isNotEmpty()) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    }
                }
                if (rest.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        rest.forEach { symptom ->
                            SymptomButton(symptom.label, symptom.label in selectedSymptoms, iconKey = symptom.iconKey) {
                                if (symptom.label in selectedSymptoms) selectedSymptoms.remove(symptom.label) else selectedSymptoms.add(symptom.label)
                                syncDraft()
                            }
                        }
                    }
                } else if (frequent.isEmpty()) {
                    Text("No postdrome items in your pool. Tap Manage above to add some.",
                        color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Navigation
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(
                    onClick = { navController.popBackStack() },
                    border = BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple)
                ) { Text("Back") }
                Button(
                    onClick = { navController.navigate(Routes.MISSED_ACTIVITIES) },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                ) { Text("Next") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
