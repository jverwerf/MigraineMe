package com.migraineme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LocationsScreen(
    navController: NavController,
    vm: LocationViewModel,
    authVm: AuthViewModel,
    logVm: LogViewModel,
    onClose: () -> Unit = {},
    quickLogMode: Boolean = false,
    onSave: (() -> Unit)? = null,
    linkedMigraineId: String? = null,
    onMigraineSelect: ((String?) -> Unit)? = null
) {
    val pool by vm.pool.collectAsState()
    val frequent by vm.frequent.collectAsState()
    val authState by authVm.state.collectAsState()
    val draft by logVm.draft.collectAsState()
    val scrollState = rememberScrollState()

    LaunchedEffect(authState.accessToken) { authState.accessToken?.let { vm.loadAll(it) } }

    fun rebuildDraftWithLocations(locs: List<LocationDraft>) {
        logVm.replaceLocations(locs)
    }

    fun onTap(label: String) {
        val idx = draft.locations.indexOfFirst { it.type == label }
        if (idx >= 0) {
            rebuildDraftWithLocations(draft.locations.toMutableList().apply { removeAt(idx) })
        } else {
            rebuildDraftWithLocations(draft.locations + LocationDraft(type = label))
        }
    }

    val frequentLabels = remember(frequent) { frequent.mapNotNull { it.location?.label }.toSet() }
    val selectedLabels = remember(draft.locations) { draft.locations.map { it.type }.toSet() }
    val grouped = remember(pool) { pool.groupBy { it.category ?: "Other" }.toSortedMap() }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reliefs", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
                Text("Location", color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close", tint = Color.White, modifier = Modifier.size(28.dp)) }
            }

            HeroCard {
                Box(Modifier.size(40.dp).drawBehind {
                    HubIcons.run { drawLocationPin(Color(0xFF64B5F6)) }
                })
                Text("Where were you?", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(
                    if (draft.locations.isEmpty()) "Log your location" else "${draft.locations.size} location${if (draft.locations.size > 1) "s" else ""} added",
                    color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center
                )
                if (draft.locations.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    draft.locations.forEachIndexed { index, loc ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(loc.type, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
                            Icon(Icons.Outlined.Close, "Remove", tint = AppTheme.AccentPink.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp).clickable {
                                    rebuildDraftWithLocations(draft.locations.toMutableList().apply { removeAt(index) })
                                })
                        }
                    }
                }
            }

            BaseCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Locations", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    Text("Manage →", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clickable { navController.navigate(Routes.MANAGE_LOCATIONS) })
                }
            }

            if (quickLogMode && onMigraineSelect != null) {
                val firstIso = draft.locations.firstOrNull()?.startAtIso
                MigrainePickerCard(itemStartAtIso = firstIso, authVm = authVm, selectedMigraineId = linkedMigraineId, onSelect = onMigraineSelect)
            }

            BaseCard {
                if (frequentLabels.isNotEmpty()) {
                    Text("Frequent", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        pool.filter { it.label in frequentLabels }.forEach { loc ->
                            CircleButton(loc.label, loc.label in selectedLabels, Color(0xFF64B5F6), loc.iconKey) { onTap(loc.label) }
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                }
                val entries = grouped.entries.toList()
                entries.forEachIndexed { ci, (cat, items) ->
                    val nonFreq = items.filter { it.label !in frequentLabels }
                    if (nonFreq.isNotEmpty()) {
                        Text(cat, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            nonFreq.forEach { loc -> CircleButton(loc.label, loc.label in selectedLabels, Color(0xFF64B5F6), loc.iconKey) { onTap(loc.label) } }
                        }
                        if (entries.drop(ci + 1).any { (_, its) -> its.any { it.label !in frequentLabels } })
                            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    }
                }
                if (pool.isEmpty()) Text("Loading…", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { navController.popBackStack() },
                    border = BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple)
                ) { Text(if (quickLogMode) "Cancel" else "Back") }
                Button(onClick = { if (quickLogMode) onSave?.invoke() else navController.navigate(Routes.ACTIVITIES) },
                    enabled = !quickLogMode || draft.locations.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                ) { Text(if (quickLogMode) "Save" else "Next") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun CircleButton(label: String, isSelected: Boolean, accent: Color, iconKey: String? = null, onClick: () -> Unit) {
    val icon = LocationIcons.forKey(iconKey)
    val bg = if (isSelected) accent.copy(alpha = 0.40f) else Color.White.copy(alpha = 0.08f)
    val border = if (isSelected) accent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.12f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(bg).border(1.5.dp, border, CircleShape), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = label, tint = if (isSelected) Color.White else AppTheme.SubtleTextColor, modifier = Modifier.size(24.dp))
            } else {
                Text(label.take(2).uppercase(), color = if (isSelected) Color.White else AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (isSelected) Color.White else AppTheme.BodyTextColor,
            style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SimpleTimeDialog(title: String, initialIso: String? = null, onDismiss: () -> Unit, onSkip: (() -> Unit)?, onConfirm: (iso: String?) -> Unit) {
    var pickedIso by remember { mutableStateOf(initialIso) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E0A2E), titleContentColor = Color.White, textContentColor = AppTheme.BodyTextColor,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Time: ${formatLocTime(pickedIso)}", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
                AppDateTimePicker(label = "Select time", onDateTimeSelected = { pickedIso = it })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(pickedIso) }) { Text(if (onSkip != null) "Add" else "Save", color = AppTheme.AccentPurple) } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel", color = AppTheme.SubtleTextColor) }
                if (onSkip != null) TextButton(onClick = onSkip) { Text("Skip", color = Color(0xFF64B5F6)) }
            }
        }
    )
}

private fun formatLocTime(iso: String?): String {
    if (iso.isNullOrBlank()) return "Not set"
    return try {
        val odt = runCatching { OffsetDateTime.parse(iso) }.getOrNull()
        val ldt = odt?.toLocalDateTime() ?: runCatching { LocalDateTime.parse(iso) }.getOrNull()
            ?: runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull() ?: return "Not set"
        ldt.format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
    } catch (_: Exception) { "Not set" }
}


