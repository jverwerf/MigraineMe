package com.migraineme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ═══════════════════════════════════════════════════════════════════
//  Missed: why
// ═══════════════════════════════════════════════════════════════════

/**
 * Why that thing was skipped, over the picker, the moment it is tapped. As a
 * section further down the page it read as unrelated and got missed.
 *
 * Reasons come from the trigger and prodrome pools, favourites first, plus a
 * free-text line. All three are equal — nothing here grades one kind of reason
 * against another.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun MissedWhySheet(
    label: String,
    triggerItems: List<SelectableItem>,
    prodromeItems: List<SelectableItem>,
    selectedReasons: List<String>,
    note: String,
    onToggleReason: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = Color(0xFF2A003D),
    dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.3f)) },
  ) {
    val sheetScroll = rememberScrollState()
    val sheetCtx = androidx.compose.ui.platform.LocalContext.current
    // Same voice contract as every other notes field: what is spoken is
    // appended to what is already typed, never replaces it.
    val whySpeechLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                onNoteChange(if (note.isBlank()) spoken else "$note, $spoken")
            }
        }
    }
    var showAllTriggers by rememberSaveable { mutableStateOf(false) }
    var showAllProdromes by rememberSaveable { mutableStateOf(false) }

    val triggerFavs = remember(triggerItems) { triggerItems.filter { it.isFavourite } }
    val prodromeFavs = remember(prodromeItems) { prodromeItems.filter { it.isFavourite } }
    // Favourites when there are any, otherwise the first handful. Never the
    // whole pool: an account with nothing favourited would get a page of chips
    // to scroll past, which is what asking here is meant to avoid.
    val unfavvedShown = 12
    val triggersShown = when {
        showAllTriggers -> triggerItems
        triggerFavs.isNotEmpty() -> triggerFavs
        else -> triggerItems.take(unfavvedShown)
    }
    val prodromesShown = when {
        showAllProdromes -> prodromeItems
        prodromeFavs.isNotEmpty() -> prodromeFavs
        else -> prodromeItems.take(unfavvedShown)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(sheetScroll)
            .padding(horizontal = 20.dp)
    ) {
        Text(t("Why did you skip %s?", tSync(label)), color = Color.White,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(4.dp))
        Text(t("No migraine today, so tell us what stopped you. Optional."),
            color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)

        if (triggersShown.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text(t("Triggers"), color = AppTheme.TitleColor,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            triggersShown.forEach { item ->
                CheckInCircle(item.label, TriggerIcons.forKey(item.iconKey) ?: TriggerIcons.forKey(item.category),
                    item.label in selectedReasons, Color(0xFFFFB74D), false) { onToggleReason(item.label) }
            }
        }
        }
        if (triggersShown.size < triggerItems.size || showAllTriggers) {
            TextButton(onClick = { showAllTriggers = !showAllTriggers }) {
                Text(if (showAllTriggers) t("Show fewer") else t("Show all"),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
        }

        // Heading only when there is something under it: an account whose
        // prodrome pool is all device-derived would otherwise get a bare
        // "Warning signs" label with nothing beneath it.
        if (prodromesShown.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text(t("Warning signs"), color = AppTheme.TitleColor,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            prodromesShown.forEach { item ->
                CheckInCircle(item.label, ProdromeIcons.forKey(item.iconKey) ?: ProdromeIcons.forKey(item.category),
                    item.label in selectedReasons, Color(0xFF9575CD), false) { onToggleReason(item.label) }
            }
        }
        }
        if (prodromesShown.size < prodromeItems.size || showAllProdromes) {
            TextButton(onClick = { showAllProdromes = !showAllProdromes }) {
                Text(if (showAllProdromes) t("Show fewer") else t("Show all"),
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(t("Anything else"), color = AppTheme.TitleColor,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = note,
            onValueChange = onNoteChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(t("In your own words"), color = AppTheme.SubtleTextColor) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AppTheme.AccentPurple,
                unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                cursorColor = AppTheme.AccentPurple,
            ),
            shape = RoundedCornerShape(12.dp),
            minLines = 2,
            trailingIcon = {
                IconButton(onClick = {
                    val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    }
                    try { whySpeechLauncher.launch(intent) } catch (_: Exception) {
                        android.widget.Toast.makeText(sheetCtx, tSync("Voice input not available"), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Outlined.Mic, contentDescription = t("Voice input"),
                        tint = AppTheme.AccentPurple, modifier = Modifier.size(20.dp))
                }
            },
        )

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
            shape = RoundedCornerShape(12.dp),
        ) { Text(t("Done"), fontWeight = FontWeight.SemiBold) }

        Spacer(Modifier.height(24.dp))
    }
    }
}

