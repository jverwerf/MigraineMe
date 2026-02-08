package com.migraineme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.navigation.NavController
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Composable
fun TimingScreen(
    navController: NavController,
    vm: LogViewModel,
    onClose: () -> Unit = {}
) {
    val draft by vm.draft.collectAsState()
    val scrollState = rememberScrollState()

    var beganAt by rememberSaveable { mutableStateOf<String?>(null) }
    var endedAt by rememberSaveable { mutableStateOf<String?>(null) }

    // Sync from draft
    LaunchedEffect(draft.migraine) {
        draft.migraine?.let { m ->
            beganAt = m.beganAtIso
            endedAt = m.endedAtIso
        }
    }

    fun syncDraft() {
        vm.setMigraineDraft(beganAtIso = beganAt, endedAtIso = endedAt)
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 60.dp) {

            // Top bar: ← Previous | Title | X Close
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Log", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
                Text("Timing", color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            // Hero
            HeroCard {
                Icon(
                    imageVector = Icons.Outlined.AccessTime,
                    contentDescription = "Timing",
                    tint = AppTheme.AccentPink,
                    modifier = Modifier.size(40.dp)
                )
                Text(
                    "When did it happen?",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )
                Text(
                    "Set when the migraine started and ended",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            // Start time
            BaseCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AccessTime, contentDescription = null, tint = AppTheme.AccentPurple, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Started", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                }
                AppDateTimePicker(
                    label = beganAt?.let { "Started: ${formatIsoDdMmYyHm(it)}" } ?: "Set start time"
                ) { beganAt = it; syncDraft() }
            }

            // End time
            BaseCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AccessTime, contentDescription = null, tint = AppTheme.AccentPurple, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Ended", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                }
                AppDateTimePicker(
                    label = endedAt?.let { "Ended: ${formatIsoDdMmYyHm(it)}" } ?: "Set end time (optional)"
                ) { endedAt = it; syncDraft() }
            }

            // Duration hint
            if (beganAt != null && endedAt != null) {
                val duration = computeDurationText(beganAt!!, endedAt!!)
                if (duration != null) {
                    BaseCard {
                        Text("Duration: $duration", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            // Navigation
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = { navController.popBackStack() },
                    border = BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple)
                ) { Text("Back") }
                Button(
                    onClick = { navController.navigate(Routes.PAIN_LOCATION) },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                ) { Text("Next") }
            }

            Spacer(Modifier.height(32.dp))
        }
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

private fun computeDurationText(startIso: String, endIso: String): String? {
    return try {
        val s = runCatching { OffsetDateTime.parse(startIso).toLocalDateTime() }.getOrNull() ?: LocalDateTime.parse(startIso)
        val e = runCatching { OffsetDateTime.parse(endIso).toLocalDateTime() }.getOrNull() ?: LocalDateTime.parse(endIso)
        val dur = java.time.Duration.between(s, e)
        if (dur.isNegative) return null
        val h = dur.toHours()
        val m = dur.toMinutes() % 60
        when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    } catch (_: Exception) { null }
}
