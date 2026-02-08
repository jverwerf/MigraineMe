package com.migraineme

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Simple centered top bar made from primitives. */
@Composable
fun SimpleTopBar(title: String) {
    Surface(tonalElevation = 3.dp) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
fun AppFormCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}

/** Public reusable dropdown (index-based) */
@Composable
fun AppDropdown(
    options: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val label = options.getOrNull(selectedIndex) ?: "None"

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text(label) }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { idx, text ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelected(idx)
                        expanded = false
                    }
                )
            }
        }
    }
}

// DateTimePickerField, AppDateTimePicker, and AppDatePicker
// have been moved to AppDateTimePickers.kt with Material3 purple theming.
