package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RemoveCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Second page of the evening check-in. The edge function has already
 * auto-saved every calendar event GPT classified as activity / relief /
 * trigger (deduped per day). This page lists those auto-saves with an Undo
 * button per item — opt-out, not opt-in. Tapping Undo deletes the log row
 * and caches the decision so the same title doesn't get re-saved.
 */
@Composable
fun CalendarCheckInPage(
    activityVm: ActivityViewModel,
    reliefVm: ReliefViewModel,
    triggerVm: TriggerViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val hasPermission = CalendarService.hasReadPermission(context)
    val loadingState = remember { mutableStateOf(true) }
    val mappingsState = remember { mutableStateOf<List<CalendarService.Mapping>>(emptyList()) }
    val pendingState = remember { mutableStateMapOf<String, ItemState>() }

    LaunchedEffect(hasPermission) {
        if (hasPermission) loadInto(loadingState, mappingsState, context)
        else loadingState.value = false
    }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Header()

        if (!hasPermission) {
            Text(
                "Connect your calendar in Data Settings to see suggestions here.",
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 24.dp),
            )
            return@Column
        }

        if (loadingState.value) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp,
                modifier = Modifier.padding(vertical = 24.dp).size(28.dp))
            return@Column
        }

        val mappings = mappingsState.value
        if (mappings.isEmpty()) {
            Text("No calendar events to log today.",
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 24.dp))
        } else {
            mappings.forEach { m ->
                val state = pendingState[m.eventId] ?: ItemState.Saved
                EventCard(
                    mapping = m,
                    state = state,
                    onUndo = {
                        scope.launch {
                            pendingState[m.eventId] = ItemState.Undoing
                            val ok = CalendarService.skip(context, m)
                            pendingState[m.eventId] = if (ok) ItemState.Undone
                            else ItemState.Error("Could not undo")
                            if (ok) {
                                SessionStore.readAccessToken(context)?.let { tok ->
                                    when (m.targetType) {
                                        "activity" -> activityVm.loadAll(tok)
                                        "relief" -> reliefVm.loadAll(tok)
                                        "trigger" -> triggerVm.loadAll(tok)
                                    }
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

private sealed interface ItemState {
    data object Saved : ItemState
    data object Undoing : ItemState
    data object Undone : ItemState
    data class Error(val msg: String) : ItemState
}

@Composable
private fun Header() {
    Column(
        Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.CalendarMonth, null, tint = Color(0xFF64B5F6),
            modifier = Modifier.size(36.dp))
        Text("From your calendar", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "These were added automatically. Tap Undo to remove anything that's not right.",
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

private suspend fun loadInto(
    loading: MutableState<Boolean>,
    out: MutableState<List<CalendarService.Mapping>>,
    context: android.content.Context,
) {
    loading.value = true
    val all = CalendarService.syncWindow(context)
    out.value = CalendarService.reviewQueue(all).sortedBy { it.startAt ?: "" }
    loading.value = false
}

@Composable
private fun EventCard(
    mapping: CalendarService.Mapping,
    state: ItemState,
    onUndo: () -> Unit,
) {
    val type = mapping.targetType ?: "activity"
    val label = mapping.targetLabel ?: "(unknown)"
    val categoryColor = when (type) {
        "activity" -> Color(0xFF64B5F6)
        "relief" -> Color(0xFF81C784)
        "trigger" -> Color(0xFFFFB74D)
        else -> AppTheme.SubtleTextColor
    }
    val isUndone = state is ItemState.Undone
    val needsVerify = (mapping.isNew == true) || ((mapping.confidence ?: 1.0) < 0.7)

    Column(
        Modifier.fillMaxWidth().background(
            Color.White.copy(alpha = if (isUndone) 0.02f else 0.06f),
            RoundedCornerShape(12.dp)
        ).border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(mapping.title ?: "(no title)",
                    color = Color.White, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium)
                formatRange(mapping.startAt, mapping.endAt, mapping.allDay == true)?.let {
                    Text(it, color = AppTheme.SubtleTextColor, fontSize = 10.sp)
                }
            }
            Box(
                Modifier.background(categoryColor.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(type.replaceFirstChar { it.uppercase() },
                    color = categoryColor, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isUndone) Icons.Outlined.RemoveCircle else Icons.Filled.CheckCircle,
                null,
                tint = if (isUndone) AppTheme.SubtleTextColor else Color(0xFF81C784),
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (isUndone) "Removed" else "Saved as $type: $label",
                color = if (isUndone) AppTheme.SubtleTextColor else Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (needsVerify && state is ItemState.Saved) {
                Box(
                    Modifier.background(Color(0xFFFFB74D).copy(alpha = 0.18f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("verify", color = Color(0xFFFFB74D), fontSize = 9.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.width(6.dp))
            }
            when (state) {
                is ItemState.Saved ->
                    Box(
                        Modifier.background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                            .clickable { onUndo() }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Undo", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                is ItemState.Undoing ->
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp,
                        modifier = Modifier.size(14.dp))
                is ItemState.Undone -> {}
                is ItemState.Error ->
                    Text(state.msg, color = Color(0xFFE57373), fontSize = 10.sp)
            }
        }
    }
}

private fun formatRange(start: String?, end: String?, allDay: Boolean): String? {
    if (start == null) return null
    return try {
        val s = OffsetDateTime.parse(start)
        val day = s.format(DateTimeFormatter.ofPattern("E d MMM"))
        if (allDay) return day
        val hm = DateTimeFormatter.ofPattern("HH:mm")
        val startStr = "$day ${s.format(hm)}"
        if (end != null) {
            val e = OffsetDateTime.parse(end)
            "$startStr – ${e.format(hm)}"
        } else startStr
    } catch (_: Exception) {
        null
    }
}
