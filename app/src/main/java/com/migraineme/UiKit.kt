package com.migraineme

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Calendar

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

/**
 * Legacy-compatible field used across screens.
 * Opens DatePicker, then TimePicker, and returns an ISO-like string "YYYY-MM-DDTHH:MM:00Z".
 */
@Composable
fun DateTimePickerField(
    label: String,
    onDateTimeSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val cal = Calendar.getInstance()

    var pickedDate by remember { mutableStateOf<String?>(null) }
    var display by remember { mutableStateOf(label) }

    OutlinedButton(
        onClick = {
            DatePickerDialog(
                context,
                { _, y, m, d ->
                    pickedDate = "%04d-%02d-%02d".format(y, m + 1, d)
                    TimePickerDialog(
                        context,
                        { _, h, min ->
                            val iso = "%sT%02d:%02d:00Z".format(pickedDate, h, min)
                            display = iso
                            onDateTimeSelected(iso)
                        },
                        cal.get(Calendar.HOUR_OF_DAY),
                        cal.get(Calendar.MINUTE),
                        true
                    ).show()
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(display)
    }
}

/**
 * Newer name some screens expect. Keep both to avoid refactors.
 * Just forwards to DateTimePickerField.
 */
@Composable
fun AppDateTimePicker(
    label: String,
    onDateTimeSelected: (String) -> Unit
) {
    DateTimePickerField(label = label, onDateTimeSelected = onDateTimeSelected)
}
