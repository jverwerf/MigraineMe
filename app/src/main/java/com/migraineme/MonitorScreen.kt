package com.migraineme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Weather removed. Monitor is a placeholder for future signals.
 * No ViewModels. No permissions. No background work.
 */
@Composable
fun MonitorScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Monitor", style = MaterialTheme.typography.titleLarge)
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text(
            "No weather data is collected or displayed.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "Add new monitors here later.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
