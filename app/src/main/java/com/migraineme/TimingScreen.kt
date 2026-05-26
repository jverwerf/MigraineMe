package com.migraineme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Composable
fun TimingScreen(
    navController: NavController,
    authVm: AuthViewModel,
    vm: LogViewModel,
    onClose: () -> Unit = {}
) {
    val authState by authVm.state.collectAsState()
    val draft by vm.draft.collectAsState()
    val scrollState = rememberScrollState()

    var beganAt by rememberSaveable { mutableStateOf(draft.migraine?.beganAtIso) }
    var endedAt by rememberSaveable { mutableStateOf(draft.migraine?.endedAtIso) }

    // Sync from draft when it changes
    LaunchedEffect(draft.migraine?.beganAtIso, draft.migraine?.endedAtIso) {
        draft.migraine?.let { m ->
            if (m.beganAtIso != null && beganAt == null) beganAt = m.beganAtIso
            if (m.endedAtIso != null && endedAt == null) endedAt = m.endedAtIso
        }
    }

    fun syncDraft() {
        vm.setMigraineDraft(beganAtIso = beganAt, endedAtIso = endedAt)
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            // Top bar: ← Previous | Title | X Close
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Log", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            // Mid-attack escape hatch — one tap saves an attack starting now and exits.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                AppTheme.AccentPink.copy(alpha = 0.35f),
                                AppTheme.AccentPurple.copy(alpha = 0.35f)
                            )
                        )
                    )
                    .border(1.dp, AppTheme.AccentPink.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .clickable(enabled = authState.accessToken != null) {
                        val token = authState.accessToken ?: return@clickable
                        vm.clearDraft()
                        val nowIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                        vm.addFull(
                            accessToken = token,
                            type = "Migraine",
                            severity = null,
                            beganAtIso = nowIso,
                            endedAtIso = null,
                            note = null,
                            painLocations = emptyList(),
                            meds = emptyList(),
                            rels = emptyList()
                        )
                        vm.clearDraft()
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.HOME) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(AppTheme.AccentPink)
                            .drawBehind { drawMigraineIcon(Color.White) }
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "It's happening right now",
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            "Tap to log an attack starting now. Add details later.",
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(18.dp)
                    )
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
                    onClick = { navController.navigate(Routes.PAINT_PICTURE) },
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

private fun DrawScope.drawMigraineIcon(color: Color) {
    val w = size.width; val h = size.height
    val stroke = Stroke(w * 0.035f, cap = StrokeCap.Round)
    drawCircle(color.copy(alpha = 0.6f), radius = w * 0.08f, center = Offset(w * 0.5f, h * 0.5f), style = Fill)
    drawCircle(color, radius = w * 0.15f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(w * 0.03f, cap = StrokeCap.Round))
    drawLine(color, Offset(w * 0.50f, h * 0.30f), Offset(w * 0.50f, h * 0.08f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.50f, h * 0.70f), Offset(w * 0.50f, h * 0.92f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.30f, h * 0.50f), Offset(w * 0.08f, h * 0.50f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.70f, h * 0.50f), Offset(w * 0.92f, h * 0.50f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.36f, h * 0.36f), Offset(w * 0.20f, h * 0.20f), strokeWidth = w * 0.03f, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.64f, h * 0.36f), Offset(w * 0.80f, h * 0.20f), strokeWidth = w * 0.03f, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.36f, h * 0.64f), Offset(w * 0.20f, h * 0.80f), strokeWidth = w * 0.03f, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.64f, h * 0.64f), Offset(w * 0.80f, h * 0.80f), strokeWidth = w * 0.03f, cap = StrokeCap.Round)
    drawCircle(color, radius = w * 0.24f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
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


