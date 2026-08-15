package com.migraineme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.navigation.NavController

@Composable
fun NotesScreen(
    navController: NavController,
    vm: LogViewModel,
    onClose: () -> Unit = {}
) {
    val draft by vm.draft.collectAsState()
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    var notes by rememberSaveable { mutableStateOf(draft.migraine?.note ?: "") }

    // Prefill from the draft — which is where "Paint the picture" leaves what
    // the user already typed or spoke earlier in this wizard, so nobody says
    // the same thing twice. Only fills an untouched field: once the user edits
    // here, their version is the one that survives to save.
    LaunchedEffect(draft.migraine?.note) {
        draft.migraine?.let { m ->
            if (notes.isEmpty() && !m.note.isNullOrBlank()) {
                notes = m.note ?: ""
            }
        }
    }

    fun syncDraft() {
        vm.setMigraineDraft(note = notes.ifBlank { null }, clearNote = notes.isBlank())
    }

    // Speech recogniser — same RecognizerIntent path as "Paint the picture".
    val speechLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                notes = if (notes.isBlank()) spoken else "$notes, $spoken"
                syncDraft()
            }
        }
    }

    fun launchVoice() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Add any notes about this migraine…")
        }
        try { speechLauncher.launch(intent) } catch (_: Exception) {
            android.widget.Toast.makeText(context, tSync("Voice input not available"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            // Top bar: ← Previous | Title | X Close
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { syncDraft(); navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Back"), tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(t("Missed Activities"), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = t("Close"), tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            // Hero
            HeroCard {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .drawBehind { HubIcons.run { drawNotesIcon(AppTheme.AccentPink) } }
                )
                Text(t("Notes"), color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(
                    t("Add any additional notes about this migraine"),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            WizardStepNav(onBack = { navController.popBackStack() }, onSkip = { navController.navigate(Routes.REVIEW) })

            // Notes input
            BaseCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .drawBehind { HubIcons.run { drawNotesIcon(AppTheme.AccentPurple) } }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(t("Your notes"), color = AppTheme.TitleColor, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { v -> notes = v; syncDraft() },
                    placeholder = { Text(t("What were you doing? How did it feel? Any patterns you noticed…"), color = AppTheme.SubtleTextColor) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = AppTheme.BodyTextColor,
                        cursorColor = AppTheme.AccentPurple, focusedBorderColor = AppTheme.AccentPurple,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f)
                    ),
                    minLines = 5
                )
                Spacer(Modifier.height(10.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { launchVoice() },
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple),
                        border = BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Outlined.Mic, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t("Voice"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Navigation
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(
                    onClick = { syncDraft(); navController.popBackStack() },
                    border = BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple)
                ) { Text(t("Back")) }
                Button(
                    onClick = { syncDraft(); navController.navigate(Routes.REVIEW) },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                ) { Text(t("Next")) }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

