package com.migraineme

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun DataSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
    Spacer(Modifier.height(14.dp))
}

@Composable
fun MetricToggle(
    label: String,
    metric: String,
    source: String?,
    settings: Map<String, EdgeFunctionsService.MetricSettingResponse>,
    viewModel: DataSettingsViewModel,
    onToggle: ((Boolean) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val key = if (source != null) "${metric}_${source}" else "${metric}_null"
    val enabled = settings[key]?.enabled ?: true

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = enabled,
            onCheckedChange = { newValue ->
                onToggle?.invoke(newValue)
                scope.launch {
                    viewModel.toggleMetric(metric, newValue, source)
                }
            }
        )
    }
}

@Composable
fun MenstruationSection(
    settings: MenstruationSettings?,
    onSetup: () -> Unit,
    onEdit: () -> Unit
) {
    if (settings == null || settings.lastMenstruationDate == null) {
        TextButton(onClick = onSetup) {
            Text("Set up menstruation tracking")
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Menstruation",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Last: ${settings.lastMenstruationDate}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Cycle: ${settings.avgCycleLength} days",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onEdit) {
                Text("Edit")
            }
        }
    }
}