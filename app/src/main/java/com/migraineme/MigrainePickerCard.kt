package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * Inline migraine picker card for quick log screens.
 * Shows migraines within ±3 days of the item date,
 * with an expandable "Browse all" section for last 30 days.
 *
 * @param itemStartAtIso The start_at of the logged item (determines center date). Defaults to today.
 * @param authVm For access token.
 * @param selectedMigraineId Currently selected migraine ID (state hoisted to parent).
 * @param onSelect Called when user selects/deselects a migraine.
 */
@Composable
fun MigrainePickerCard(
    itemStartAtIso: String? = null,
    authVm: AuthViewModel,
    selectedMigraineId: String?,
    onSelect: (String?) -> Unit
) {
    val db = remember { SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) }
    val authState by authVm.state.collectAsState()

    var suggested by remember { mutableStateOf<List<SupabaseDbService.MigraneSummaryRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var allRecent by remember { mutableStateOf<List<SupabaseDbService.MigraneSummaryRow>>(emptyList()) }
    var allLoading by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    val today = remember { LocalDate.now() }

    val itemDate = remember(itemStartAtIso) {
        try {
            if (itemStartAtIso != null) LocalDate.parse(itemStartAtIso.substring(0, 10))
            else LocalDate.now()
        } catch (_: Exception) { LocalDate.now() }
    }

    val (fromDate, toDate) = remember(itemDate) {
        itemDate.minusDays(3) to itemDate.plusDays(3)
    }

    // Fetch suggested
    LaunchedEffect(authState.accessToken, fromDate, toDate) {
        val token = authState.accessToken ?: return@LaunchedEffect
        loading = true
        suggested = try {
            db.getNearbyMigraines(token, fromDate.toString(), toDate.toString())
        } catch (_: Exception) { emptyList() }
        loading = false
    }

    // Fetch all when expanded
    LaunchedEffect(expanded, authState.accessToken) {
        if (expanded && allRecent.isEmpty()) {
            val token = authState.accessToken ?: return@LaunchedEffect
            allLoading = true
            allRecent = try {
                db.getNearbyMigraines(token, today.minusDays(30).toString(), today.toString())
            } catch (_: Exception) { emptyList() }
            allLoading = false
        }
    }

    val suggestedIds = remember(suggested) { suggested.map { it.id }.toSet() }

    BaseCard {
        Text("Link to migraine", color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))

        if (loading) {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AppTheme.AccentPurple, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        } else if (suggested.isEmpty() && !expanded) {
            Text("No nearby migraines found", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
        } else {
            suggested.forEach { m ->
                PickerMigraineRow(m, selectedMigraineId == m.id, today) {
                    onSelect(if (selectedMigraineId == m.id) null else m.id)
                }
            }
        }

        // Browse all
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Browse all", color = AppTheme.AccentPurple, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null, tint = AppTheme.AccentPurple, modifier = Modifier.size(20.dp)
            )
        }

        if (expanded) {
            if (allLoading) {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppTheme.AccentPurple, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else {
                val extra = allRecent.filter { it.id !in suggestedIds }
                if (extra.isEmpty()) {
                    Text("No other migraines in the last 30 days", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                } else {
                    extra.forEach { m ->
                        PickerMigraineRow(m, selectedMigraineId == m.id, today) {
                            onSelect(if (selectedMigraineId == m.id) null else m.id)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerMigraineRow(
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
    val severityLabel = m.severity?.let { "$it/10" } ?: ""
    val typeLabel = m.type?.replaceFirstChar { it.uppercase() } ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) AppTheme.AccentPurple.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
