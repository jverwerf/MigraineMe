package com.migraineme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Reusable side-effect scale chips + notes field.
 * Used in MedicineAddDialog, MedicineEditDialog, ReliefAddDialog, ReliefEditDialog,
 * QuickLogMedicineScreen, QuickLogReliefScreen, and JournalEditScreen.
 */

enum class SideEffectScale(val key: String, val display: String, val color: Color) {
    NONE("NONE", "None", Color(0xFF81C784)),
    SOFT("SOFT", "Soft", Color(0xFFFFB74D)),
    MODERATE("MODERATE", "Moderate", Color(0xFFFF8A65)),
    SEVERE("SEVERE", "Severe", Color(0xFFE57373));

    companion object {
        fun fromString(s: String?): SideEffectScale =
            entries.find { it.key.equals(s, ignoreCase = true) } ?: NONE
    }
}

@Composable
fun SideEffectChips(
    sideEffectScale: String,
    onScaleChange: (String) -> Unit,
    sideEffectNotes: String,
    onNotesChange: (String) -> Unit,
    modifier: Modifier = Modifier,
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
                val updated = if (sideEffectNotes.isBlank()) spoken else "$sideEffectNotes, $spoken"
                onNotesChange(updated)
            }
        }
    }

    fun launchVoice() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Describe side effects…")
        }
        try { speechLauncher.launch(intent) } catch (_: Exception) {
            android.widget.Toast.makeText(context, "Voice input not available", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier) {
        Text("Any side effects?", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SideEffectScale.entries.forEach { scale ->
                FilterChip(
                    selected = sideEffectScale == scale.key,
                    onClick = { onScaleChange(scale.key) },
                    label = { Text(scale.display, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = scale.color.copy(alpha = 0.3f),
                        selectedLabelColor = Color.White,
                        containerColor = Color.White.copy(alpha = 0.06f),
                        labelColor = AppTheme.SubtleTextColor
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = sideEffectScale == scale.key,
                        borderColor = Color.White.copy(alpha = 0.12f),
                        selectedBorderColor = scale.color.copy(alpha = 0.6f)
                    )
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = sideEffectNotes,
            onValueChange = onNotesChange,
            label = { Text("Notes", color = AppTheme.SubtleTextColor) },
            placeholder = { Text("e.g. drowsiness, nausea, brain fog…", color = AppTheme.SubtleTextColor.copy(alpha = 0.5f)) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = AppTheme.AccentPurple,
                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                cursorColor = AppTheme.AccentPurple,
                focusedLabelColor = AppTheme.AccentPurple,
                unfocusedLabelColor = AppTheme.SubtleTextColor,
            ),
            trailingIcon = {
                IconButton(onClick = { launchVoice() }) {
                    Icon(
                        Icons.Outlined.Mic,
                        contentDescription = "Voice input",
                        tint = AppTheme.AccentPurple,
                        modifier = Modifier.size(20.dp)
                    )
                }
            },
            minLines = 1,
            maxLines = 3,
        )
    }
}
