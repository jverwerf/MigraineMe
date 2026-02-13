package com.migraineme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Composable
fun ReviewLogScreen(navController: NavHostController, authVm: AuthViewModel, vm: LogViewModel, onClose: () -> Unit = {}) {
    val authState by authVm.state.collectAsState()
    val draft by vm.draft.collectAsState()
    val scrollState = rememberScrollState()

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 60.dp) {

            // Top bar: ← Previous | Title | X Close
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Notes", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
                Text("Review Log", color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            // Hero
            HeroCard {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .drawBehind { HubIcons.run { drawReviewCheck(AppTheme.AccentPurple) } }
                )
                Text("Review Log", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(
                    "Review your entries before saving",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            // Migraine
            draft.migraine?.let { m ->
                ReviewSection(drawIcon = { HubIcons.run { drawMigraineStarburst(it) } }, title = "Migraine", iconTint = AppTheme.AccentPink) {
                    if (m.symptoms.isNotEmpty()) {
                        Text("Symptoms", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        Text(
                            m.symptoms.joinToString(" • "),
                            color = AppTheme.BodyTextColor,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    ReviewRow("Severity", m.severity?.toString() ?: "-")
                    ReviewRow("Start", formatIsoDdMmYyHm(m.beganAtIso))
                    ReviewRow("End", formatIsoDdMmYyHm(m.endedAtIso))
                    if (!m.note.isNullOrBlank()) ReviewRow("Note", m.note)
                }
            }

            // Pain Locations
            if (draft.painLocations.isNotEmpty()) {
                ReviewSection(drawIcon = { HubIcons.run { drawMigraineStarburst(it) } }, title = "Pain Locations (${draft.painLocations.size})", iconTint = AppTheme.AccentPink) {
                    val labels = draft.painLocations.mapNotNull { ALL_PAIN_POINTS_MAP[it] }
                    Text(
                        labels.joinToString(" • "),
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Triggers
            if (draft.triggers.isNotEmpty()) {
                ReviewSection(drawIcon = { HubIcons.run { drawTriggerBolt(it) } }, title = "Triggers (${draft.triggers.size})", iconTint = Color(0xFFFFB74D)) {
                    draft.triggers.forEach { t ->
                        ReviewRow(t.type, formatIsoDdMmYyHm(t.startAtIso))
                    }
                }
            }

            // Medicines
            if (draft.meds.isNotEmpty()) {
                ReviewSection(drawIcon = { HubIcons.run { drawMedicinePill(it) } }, title = "Medicines (${draft.meds.size})", iconTint = Color(0xFF4FC3F7)) {
                    draft.meds.forEach { m ->
                        ReviewRow(m.name ?: "?", "${m.amount ?: "-"} · ${formatIsoDdMmYyHm(m.startAtIso)}")
                    }
                }
            }

            // Reliefs
            if (draft.rels.isNotEmpty()) {
                ReviewSection(drawIcon = { HubIcons.run { drawReliefLeaf(it) } }, title = "Reliefs (${draft.rels.size})", iconTint = Color(0xFF81C784)) {
                    draft.rels.forEach { r ->
                        ReviewRow(r.type, "${formatIsoDdMmYyHm(r.startAtIso)}${if (r.endAtIso != null) " → ${formatIsoDdMmYyHm(r.endAtIso)}" else ""}")
                    }
                }
            }

            // Locations
            if (draft.locations.isNotEmpty()) {
                ReviewSection(drawIcon = { HubIcons.run { drawLocationPin(it) } }, title = "Locations (${draft.locations.size})", iconTint = Color(0xFF64B5F6)) {
                    draft.locations.forEach { loc ->
                        ReviewRow(loc.type, formatIsoDdMmYyHm(loc.startAtIso))
                    }
                }
            }

            // Activities
            if (draft.activities.isNotEmpty()) {
                ReviewSection(drawIcon = { HubIcons.run { drawActivityPulse(it) } }, title = "Activities (${draft.activities.size})", iconTint = Color(0xFFFF8A65)) {
                    draft.activities.forEach { act ->
                        ReviewRow(act.type, formatIsoDdMmYyHm(act.startAtIso))
                    }
                }
            }

            // Missed Activities
            if (draft.missedActivities.isNotEmpty()) {
                ReviewSection(drawIcon = { HubIcons.run { drawMissedActivity(it) } }, title = "Missed (${draft.missedActivities.size})", iconTint = Color(0xFFEF9A9A)) {
                    draft.missedActivities.forEach { ma ->
                        ReviewRow(ma.type, formatIsoDdMmYyHm(ma.startAtIso))
                    }
                }
            }

            // Empty state
            if (draft.migraine == null && draft.triggers.isEmpty() && draft.meds.isEmpty() && draft.rels.isEmpty() && draft.locations.isEmpty() && draft.activities.isEmpty() && draft.missedActivities.isEmpty()) {
                BaseCard {
                    Text("Nothing to review yet", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }

            // Save
            Button(
                onClick = {
                    val token = authState.accessToken ?: return@Button
                    val migraine = draft.migraine
                    val editId = vm.editMigraineId.value ?: draft.editMigraineId
                    println("DEBUG ReviewLogScreen Save: editMigraineId.value=${vm.editMigraineId.value}, draft.editMigraineId=${draft.editMigraineId}, resolved editId=$editId")
                    if (migraine != null) {
                        if (editId != null) {
                            vm.updateFull(
                                accessToken = token,
                                migraineId = editId,
                                type = migraine.type,
                                severity = migraine.severity,
                                beganAtIso = migraine.beganAtIso ?: "",
                                endedAtIso = migraine.endedAtIso,
                                note = migraine.note,
                                meds = draft.meds,
                                rels = draft.rels,
                                painLocations = draft.painLocations
                            )
                        } else {
                            vm.addFull(
                                accessToken = token,
                                type = migraine.type,
                                severity = migraine.severity,
                                beganAtIso = migraine.beganAtIso ?: "",
                                endedAtIso = migraine.endedAtIso,
                                note = migraine.note,
                                meds = draft.meds,
                                rels = draft.rels,
                                painLocations = draft.painLocations
                            )
                        }
                    }
                    vm.clearDraft()
                    navController.popBackStack(Routes.JOURNAL, inclusive = false)
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                shape = AppTheme.BaseCardShape
            ) {
                val editId by vm.editMigraineId.collectAsState()
                val isEdit = editId != null || draft.editMigraineId != null
                Text(if (isEdit) "Update Log" else "Save Log", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            }

            // Back
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                OutlinedButton(
                    onClick = { navController.popBackStack() },
                    border = BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple)
                ) { Text("Back") }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ReviewSection(
    drawIcon: DrawScope.(Color) -> Unit,
    title: String,
    iconTint: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    BaseCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .drawBehind { drawIcon(iconTint) }
            )
            Spacer(Modifier.width(8.dp))
            Text(title, color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
        }
        content()
    }
}

@Composable
private fun ReviewRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatIsoDdMmYyHm(iso: String?): String {
    if (iso.isNullOrBlank()) return "-"
    return try {
        val odt = runCatching { OffsetDateTime.parse(iso) }.getOrNull()
        val ldt = odt?.toLocalDateTime() ?: LocalDateTime.parse(iso)
        ldt.format(DateTimeFormatter.ofPattern("dd/MM/yy HH:mm"))
    } catch (_: Exception) { "-" }
}

