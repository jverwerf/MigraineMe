package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Direction for migraine lookup relative to the logged item's date.
 *
 * FORWARD: triggers/prodromes/activities — the migraine comes *after* the item
 *   → show migraines from itemDate to itemDate+2
 * BACKWARD: reliefs/medicines/locations/missed — the migraine came *before* the item
 *   → show migraines from itemDate-2 to itemDate
 */
enum class LinkDirection { FORWARD, BACKWARD }

/**
 * Dialog shown in quick-log mode to let the user link their logged item(s) to a nearby migraine.
 * Shows auto-suggested migraines from the relevant date range, plus an expandable dropdown
 * to browse all migraines from the last 30 days.
 */
@Composable
fun MigraineLinkerDialog(
    direction: LinkDirection,
    itemStartAtIso: String?,
    authVm: AuthViewModel,
    onLink: (migraineId: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val db = remember { SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) }
    val authState by authVm.state.collectAsState()

    // Suggested migraines (from date range)
    var suggested by remember { mutableStateOf<List<SupabaseDbService.MigraneSummaryRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // All recent migraines (last 30 days) for dropdown
    var allRecent by remember { mutableStateOf<List<SupabaseDbService.MigraneSummaryRow>>(emptyList()) }
    var allLoading by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }

    var selectedId by remember { mutableStateOf<String?>(null) }

    val today = remember { LocalDate.now() }

    // Determine the item date
    val itemDate = remember(itemStartAtIso) {
        try {
            if (itemStartAtIso != null) LocalDate.parse(itemStartAtIso.substring(0, 10))
            else LocalDate.now()
        } catch (_: Exception) { LocalDate.now() }
    }

    // Compute suggested date range
    val (fromDate, toDate) = remember(itemDate, direction) {
        when (direction) {
            LinkDirection.FORWARD -> itemDate to itemDate.plusDays(2)
            LinkDirection.BACKWARD -> itemDate.minusDays(2) to itemDate
        }
    }

    // Fetch suggested migraines
    LaunchedEffect(authState.accessToken, fromDate, toDate) {
        val token = authState.accessToken ?: return@LaunchedEffect
        loading = true
        suggested = try {
            db.getNearbyMigraines(token, fromDate.toString(), toDate.toString())
        } catch (_: Exception) { emptyList() }
        loading = false
    }

    // Fetch all recent when dropdown opened
    LaunchedEffect(showAll, authState.accessToken) {
        if (showAll && allRecent.isEmpty()) {
            val token = authState.accessToken ?: return@LaunchedEffect
            allLoading = true
            allRecent = try {
                val from = today.minusDays(30).toString()
                val to = today.toString()
                db.getNearbyMigraines(token, from, to)
            } catch (_: Exception) { emptyList() }
            allLoading = false
        }
    }

    // IDs already in suggested list
    val suggestedIds = remember(suggested) { suggested.map { it.id }.toSet() }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E0A2E),
        titleContentColor = Color.White,
        textContentColor = AppTheme.BodyTextColor,
        title = { Text("Link to migraine?") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val hint = when (direction) {
                    LinkDirection.FORWARD -> "Which migraine did this lead to?"
                    LinkDirection.BACKWARD -> "Which migraine was this for?"
                }
                Text(hint, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)

                // ── Suggested migraines ──
                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AppTheme.AccentPurple, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else if (suggested.isEmpty()) {
                    Text(
                        "No migraines found nearby",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    )
                } else {
                    Text("Suggested", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                    suggested.forEach { m ->
                        MigraineRow(m, selectedId == m.id, today) { selectedId = if (selectedId == m.id) null else m.id }
                    }
                }

                // ── Browse all dropdown ──
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                Row(
                    modifier = Modifier.fillMaxWidth().clickable { showAll = !showAll }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Browse all migraines",
                        color = AppTheme.AccentPurple,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Icon(
                        if (showAll) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = AppTheme.AccentPurple,
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (showAll) {
                    if (allLoading) {
                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = AppTheme.AccentPurple, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        // Show migraines not already in suggested list
                        val extra = allRecent.filter { it.id !in suggestedIds }
                        if (extra.isEmpty()) {
                            Text(
                                "No other migraines in the last 30 days",
                                color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        } else {
                            extra.forEach { m ->
                                MigraineRow(m, selectedId == m.id, today) { selectedId = if (selectedId == m.id) null else m.id }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onLink(selectedId) }) {
                Text(if (selectedId != null) "Link & Save" else "Save without linking", color = AppTheme.AccentPurple)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = AppTheme.SubtleTextColor)
            }
        }
    )
}

@Composable
private fun MigraineRow(
    m: SupabaseDbService.MigraneSummaryRow,
    isSelected: Boolean,
    today: LocalDate,
    onClick: () -> Unit
) {
    val migraineDate = try {
        Instant.parse(m.startAt).atZone(ZoneId.systemDefault()).toLocalDateTime()
    } catch (_: Exception) { null }

    val dayLabel = migraineDate?.let {
        val d = it.toLocalDate()
        when {
            d == today -> "Today"
            d == today.minusDays(1) -> "Yesterday"
            d == today.plusDays(1) -> "Tomorrow"
            d == today.minusDays(2) -> "2 days ago"
            else -> d.format(DateTimeFormatter.ofPattern("EEE dd MMM"))
        }
    } ?: "Unknown"

    val timeLabel = migraineDate?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: ""
    val severityLabel = m.severity?.let { "Severity $it/10" } ?: ""
    val typeLabel = m.type?.replaceFirstChar { it.uppercase() } ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) AppTheme.AccentPurple.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "$dayLabel $timeLabel",
                color = if (isSelected) Color.White else AppTheme.BodyTextColor,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
            )
            if (typeLabel.isNotEmpty() || severityLabel.isNotEmpty()) {
                Text(
                    listOfNotNull(typeLabel.ifEmpty { null }, severityLabel.ifEmpty { null }).joinToString(" · "),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        if (isSelected) {
            Text("✓", color = AppTheme.AccentPurple, style = MaterialTheme.typography.titleMedium)
        }
    }
}
