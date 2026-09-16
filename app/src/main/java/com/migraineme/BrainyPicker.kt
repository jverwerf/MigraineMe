package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * "Pick an icon" for a custom pool item: the bundled Brainies for [kind] (see
 * BrainyLogManifest.pickerEntries) plus a "Generate new icon" button that asks
 * generate-symptom-icon to draw a Brainy from the typed [label] (~20 s, 50/day).
 * A drawn Brainy is shown as the first tile of the grid, already selected.
 *
 * [selectedKey] is either a manifest key (stored in icon_key and resolved by
 * BrainyLogManifest.keyFor) or the drawn icon's URL (see isDrawnIconKey).
 * Tapping the selected tile again clears the choice.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrainyPickerGrid(
    kind: String,
    label: String,
    accessToken: String?,
    accent: Color,
    selectedKey: String?,
    onSelect: (String?) -> Unit,
) {
    val entries = remember(kind) { BrainyLogManifest.pickerEntries(kind) }
    val scope = rememberCoroutineScope()
    var drawing by remember { mutableStateOf(false) }
    var drawnUrl by remember { mutableStateOf<String?>(null) }
    var drawError by remember { mutableStateOf<String?>(null) }
    val canDraw = label.isNotBlank() && !drawing && accessToken != null
    val failMsg = t("Could not draw one right now. Pick one from the list.")

    @Composable
    fun tile(chosen: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(if (chosen) accent.copy(alpha = 0.40f) else Color.White.copy(alpha = 0.08f))
                .border(1.5.dp, if (chosen) accent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.12f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { content() }
    }

    Column {
        Text(t("Pick an icon"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // The drawn Brainy (if any) goes first so it is never lost in the grid.
            drawnUrl?.let { url ->
                val chosen = selectedKey == url
                tile(chosen, { onSelect(if (chosen) null else url) }) { CustomBrainyImage(url, 34.dp) }
            }
            entries.forEach { (key, res) ->
                val chosen = selectedKey == key
                tile(chosen, { onSelect(if (chosen) null else key) }) {
                    androidx.compose.foundation.Image(painterResource(res), contentDescription = null, modifier = Modifier.size(34.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = {
                val token = accessToken ?: return@OutlinedButton
                drawing = true; drawError = null
                scope.launch {
                    val drawn = EdgeFunctionsService().generateSymptomIcon(token, label.trim(), kind)
                    drawing = false
                    val u = drawn?.iconUrl
                    if (u != null) { drawnUrl = u; onSelect(u) }
                    else drawError = failMsg
                }
            },
            enabled = canDraw,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, accent.copy(alpha = if (canDraw) 0.6f else 0.25f)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = accent,
                disabledContentColor = AppTheme.SubtleTextColor
            )
        ) {
            if (drawing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = accent)
            } else {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(t("Generate new icon"))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                drawing -> t("Drawing your Brainy… about 20 seconds.")
                drawError != null -> drawError!!
                label.isBlank() -> t("Type a name first to have a Brainy drawn for it.")
                else -> t("A Brainy will be drawn from the name.")
            },
            color = if (drawError != null) Color(0xFFE57373) else AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall
        )
    }
}
