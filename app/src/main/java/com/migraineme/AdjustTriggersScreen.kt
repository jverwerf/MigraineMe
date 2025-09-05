package com.migraineme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun AdjustTriggersScreen() {
    // Local state only; wire to real storage later.
    val (sleepWeight, setSleepWeight) = remember { mutableFloatStateOf(0.8f) }
    val (screensWeight, setScreensWeight) = remember { mutableFloatStateOf(0.6f) }
    val (hydrationWeight, setHydrationWeight) = remember { mutableFloatStateOf(0.5f) }
    val (custom, setCustom) = remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Adjust triggers", style = MaterialTheme.typography.titleLarge)
        Divider()

        TriggerRow(
            title = "Poor sleep",
            value = sleepWeight,
            onChange = setSleepWeight
        )
        TriggerRow(
            title = "Screen time",
            value = screensWeight,
            onChange = setScreensWeight
        )
        TriggerRow(
            title = "Hydration",
            value = hydrationWeight,
            onChange = setHydrationWeight
        )

        Card(elevation = CardDefaults.cardElevation(2.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Add custom trigger", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = custom,
                    onValueChange = setCustom,
                    label = { Text("e.g., Weather changes") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { /* save custom trigger later */ },
                    enabled = custom.isNotBlank(),
                    modifier = Modifier.align(Alignment.End)
                ) { Text("Add") }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { /* persist to backend later */ },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save changes") }
    }
}

@Composable
private fun TriggerRow(
    title: String,
    value: Float,
    onChange: (Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(String.format("%.0f%%", value * 100))
            }
            Slider(value = value, onValueChange = onChange)
        }
    }
}

