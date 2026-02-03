package com.migraineme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AutoTriggersTab() {
    val context = LocalContext.current.applicationContext
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val edge = remember { EdgeFunctionsService() }

    val triggerSettingsMap = remember {
        mutableStateOf<Map<String, EdgeFunctionsService.TriggerSettingResponse>>(emptyMap())
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { edge.getTriggerSettings(context) }
                .onSuccess { list ->
                    triggerSettingsMap.value = list.associateBy { it.triggerType }
                }
                .onFailure { e ->
                    android.util.Log.e(
                        "TriggerSettings",
                        "Failed to load trigger settings: ${e.message}",
                        e
                    )
                }
        }
    }

    val sections = remember {
        listOf(
            TriggerSection(
                title = "Recovery",
                description = "Get notified when your recovery score indicates potential issues.",
                rows = listOf(
                    TriggerRow(
                        triggerType = "recovery_low",
                        label = "Low recovery",
                        description = "Recovery below 33%"
                    ),
                    TriggerRow(
                        triggerType = "recovery_unusually_low",
                        label = "Unusually low recovery",
                        description = "Recovery 2 standard deviations below your average"
                    )
                )
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    "Automatic Triggers",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Configure which health patterns should automatically create triggers. " +
                            "These triggers are checked daily at 9 AM your local time.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        for (section in sections) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(section.title, style = MaterialTheme.typography.titleMedium)
                    if (section.description.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            section.description,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.alpha(0.7f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))

                    for ((idx, row) in section.rows.withIndex()) {
                        TriggerRowUi(
                            row = row,
                            triggerSettingsMap = triggerSettingsMap.value,
                            onToggle = { triggerType, enabled ->
                                scope.launch(Dispatchers.IO) {
                                    val ok = edge.upsertTriggerSetting(
                                        context = context,
                                        triggerType = triggerType,
                                        enabled = enabled
                                    )
                                    if (ok) {
                                        runCatching { edge.getTriggerSettings(context) }
                                            .onSuccess { list ->
                                                withContext(Dispatchers.Main) {
                                                    triggerSettingsMap.value =
                                                        list.associateBy { it.triggerType }
                                                }
                                            }
                                    }
                                }
                            }
                        )

                        if (idx != section.rows.lastIndex) {
                            Spacer(Modifier.height(10.dp))
                            Divider()
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun TriggerRowUi(
    row: TriggerRow,
    triggerSettingsMap: Map<String, EdgeFunctionsService.TriggerSettingResponse>,
    onToggle: (String, Boolean) -> Unit
) {
    val setting = triggerSettingsMap[row.triggerType]
    val enabled = setting?.enabled ?: true

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp)
        ) {
            Text(row.label, style = MaterialTheme.typography.bodyMedium)
            if (row.description.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    row.description,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.alpha(0.7f)
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        Switch(
            checked = enabled,
            onCheckedChange = { newValue ->
                onToggle(row.triggerType, newValue)
            }
        )
    }
}