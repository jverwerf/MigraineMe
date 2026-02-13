package com.migraineme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CustomizeTriggersScreen() {
    val context = LocalContext.current.applicationContext
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val edge = remember { EdgeFunctionsService() }

    var triggerSettingsMap by remember {
        mutableStateOf<Map<String, EdgeFunctionsService.TriggerSettingResponse>>(emptyMap())
    }
    var definitions by remember {
        mutableStateOf<List<EdgeFunctionsService.TriggerDefinitionResponse>>(emptyList())
    }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                val defs = edge.getTriggerDefinitions(context)
                val settings = edge.getTriggerSettings(context)
                defs to settings
            }.onSuccess { (defs, settings) ->
                withContext(Dispatchers.Main) {
                    definitions = defs
                    triggerSettingsMap = settings.associateBy { it.triggerType }
                    isLoading = false
                }
            }.onFailure { e ->
                android.util.Log.e(
                    "TriggerSettings",
                    "Failed to load trigger data: ${e.message}",
                    e
                )
                withContext(Dispatchers.Main) { isLoading = false }
            }
        }
    }

    // Group definitions by category (e.g. "sleep", "physical", "environment")
    val sections = remember(definitions) {
        definitions.groupBy { it.category }
            .map { (category, defs) ->
                val title = category.replaceFirstChar { it.uppercase() }
                title to defs.sortedBy { it.triggerType }
            }
            .sortedBy { it.first }
    }

    fun refreshSettings() {
        scope.launch(Dispatchers.IO) {
            runCatching { edge.getTriggerSettings(context) }
                .onSuccess { list ->
                    withContext(Dispatchers.Main) {
                        triggerSettingsMap = list.associateBy { it.triggerType }
                    }
                }
        }
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll) {
            // Info Card
            HeroCard {
                Text(
                    "Automatic Triggers",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Configure which health patterns should automatically create triggers. " +
                            "These triggers are checked every hour as new data arrives.",
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (isLoading) {
                HeroCard {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (sections.isEmpty()) {
                HeroCard {
                    Text(
                        "No trigger definitions found.",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                for ((title, defs) in sections) {
                    HeroCard {
                        Text(
                            title,
                            color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(Modifier.height(16.dp))

                        for ((idx, def) in defs.withIndex()) {
                            CustomizeTriggerRowUi(
                                def = def,
                                setting = triggerSettingsMap[def.triggerType],
                                onToggle = { enabled ->
                                    val currentThreshold = triggerSettingsMap[def.triggerType]?.threshold
                                    scope.launch(Dispatchers.IO) {
                                        val ok = edge.upsertTriggerSetting(
                                            context = context,
                                            triggerType = def.triggerType,
                                            enabled = enabled,
                                            threshold = currentThreshold
                                        )
                                        if (ok) refreshSettings()
                                    }
                                },
                                onThresholdChange = { newThreshold ->
                                    val currentEnabled = triggerSettingsMap[def.triggerType]?.enabled
                                        ?: def.enabledByDefault
                                    scope.launch(Dispatchers.IO) {
                                        val ok = edge.upsertTriggerSetting(
                                            context = context,
                                            triggerType = def.triggerType,
                                            enabled = currentEnabled,
                                            threshold = newThreshold
                                        )
                                        if (ok) refreshSettings()
                                    }
                                }
                            )

                            if (idx != defs.lastIndex) {
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomizeTriggerRowUi(
    def: EdgeFunctionsService.TriggerDefinitionResponse,
    setting: EdgeFunctionsService.TriggerSettingResponse?,
    onToggle: (Boolean) -> Unit,
    onThresholdChange: (Double?) -> Unit
) {
    val enabled = setting?.enabled ?: def.enabledByDefault
    val hasThreshold = def.direction == "below" || def.direction == "above"
    val effectiveThreshold = setting?.threshold ?: def.defaultThreshold

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    def.label,
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    def.description,
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.width(10.dp))

            Switch(
                checked = enabled,
                onCheckedChange = { newValue -> onToggle(newValue) }
            )
        }

        // Threshold input for absolute triggers (not 2SD)
        if (hasThreshold && enabled) {
            Spacer(Modifier.height(8.dp))

            var textValue by remember(effectiveThreshold) {
                mutableStateOf(effectiveThreshold?.let { formatThresholdDisplay(it, def.unit) } ?: "")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Threshold:",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(80.dp)
                )
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { newText ->
                        textValue = newText
                        val parsed = newText.toDoubleOrNull()
                        if (parsed != null) {
                            onThresholdChange(parsed)
                        }
                    },
                    modifier = Modifier.width(100.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    def.unit ?: "",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun formatThresholdDisplay(value: Double, unit: String?): String {
    return when (unit) {
        "hours" -> String.format("%.1f", value)
        "%" -> String.format("%.0f", value)
        "count" -> String.format("%.0f", value)
        "time" -> String.format("%.0f", value)
        else -> String.format("%.1f", value)
    }
}
