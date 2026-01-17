package com.migraineme

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Data Settings Screen
 *
 * Settings are persisted locally (SharedPreferences).
 * Additionally mirrored to Supabase (public.metric_settings) so backend jobs can use:
 * - enabled (per metric)
 * - preferred_source (per metric)
 * - allowed_sources (per metric)
 */
@Composable
fun DataSettingsScreen() {
    val context = LocalContext.current.applicationContext
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    // Connected wearables (only WHOOP is implemented right now)
    val whoopConnected = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        whoopConnected.value = WhoopTokenStore(context).load() != null
        Log.d("DataSettingsScreen", "whoopConnected=${whoopConnected.value}")
    }
    val connectedWearables = remember(whoopConnected.value) {
        buildList {
            if (whoopConnected.value) add(WearableSource.WHOOP)
        }
    }
    val hasAnyWearable = connectedWearables.isNotEmpty()

    val store = remember { DataSettingsStore(context) }

    val sections = remember {
        listOf(
            DataSection(
                title = "Sleep",
                rows = listOf(
                    wearableRow("sleep_duration_daily", "Wearable sleep sync"),
                    wearableRow("sleep_score_daily", "Wearable sleep sync"),
                    wearableRow("sleep_efficiency_daily", "Wearable sleep sync"),
                    wearableRow("sleep_stages_daily", "Wearable sleep sync"),
                    wearableRow("sleep_disturbances_daily", "Wearable sleep sync"),
                    wearableRow("fell_asleep_time_daily", "Wearable sleep sync"),
                    wearableRow("woke_up_time_daily", "Wearable sleep sync")
                )
            ),
            DataSection(
                title = "Physical Health",
                rows = listOf(
                    wearableRow("recovery_score_daily", "Wearable recovery sync"),
                    wearableRow("resting_hr_daily", "Wearable recovery sync"),
                    wearableRow("hrv_daily", "Wearable recovery sync"),
                    wearableRow("skin_temp_daily", "Wearable recovery sync"),
                    wearableRow("spo2_daily", "Wearable recovery sync"),
                    wearableRow("time_in_high_hr_zones_daily", "Wearable workout sync"),
                    DataRow(
                        table = "steps_daily",
                        collectedByKind = CollectedByKind.WEARABLE,
                        collectedByLabel = "Wearable steps sync (not implemented)",
                        defaultWearable = WearableSource.WHOOP
                    )
                )
            ),
            DataSection(
                title = "Mental Health",
                rows = listOf(
                    manualRow("migraines", "User log entry"),
                    manualRow("triggers", "User log entry"),
                    manualRow("medicines", "User log entry"),
                    manualRow("reliefs", "User log entry")
                )
            ),
            DataSection(
                title = "Environment",
                rows = listOf(
                    phoneRow("user_location_daily", "Phone GPS/location"),
                    referenceRow("city", "Reference dataset (read-only)"),
                    referenceRow("city_weather_daily", "Reference dataset (read-only)")
                )
            ),
            DataSection(
                title = "Diet",
                rows = emptyList()
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text("Data", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Control which data tables are collected. Wearable options appear only after you connect a wearable.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))

        sections.forEachIndexed { idx, section ->
            SectionCard(
                section = section,
                store = store,
                connectedWearables = connectedWearables,
                hasAnyWearable = hasAnyWearable,
                onMetricChanged = { metric, enabled, preferredSource, allowedSources ->
                    scope.launch(Dispatchers.IO) {
                        mirrorSettingToSupabase(context, metric, enabled, preferredSource, allowedSources)
                    }
                }
            )

            if (idx != sections.lastIndex) Spacer(Modifier.height(14.dp))
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SectionCard(
    section: DataSection,
    store: DataSettingsStore,
    connectedWearables: List<WearableSource>,
    hasAnyWearable: Boolean,
    onMetricChanged: (metric: String, enabled: Boolean, preferredSource: String?, allowedSources: List<String>) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(section.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Type",
                    modifier = Modifier.weight(0.46f),
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    "Collected By",
                    modifier = Modifier.weight(0.34f),
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    "Active",
                    modifier = Modifier.weight(0.20f),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Spacer(Modifier.height(6.dp))
            Divider()

            if (section.rows.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "No tables configured in this section.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.alpha(0.7f)
                )
                Spacer(Modifier.height(6.dp))
                return@Column
            }

            section.rows.forEachIndexed { i, row ->
                val isWearableRow = row.collectedByKind == CollectedByKind.WEARABLE
                val isReferenceRow = row.collectedByKind == CollectedByKind.REFERENCE

                val enabled =
                    when {
                        isReferenceRow -> false
                        isWearableRow -> hasAnyWearable
                        else -> true
                    }

                Spacer(Modifier.height(10.dp))
                DataRowUi(
                    row = row,
                    store = store,
                    connectedWearables = connectedWearables,
                    enabled = enabled,
                    greyOut = isWearableRow && !hasAnyWearable,
                    onMetricChanged = onMetricChanged
                )

                if (i != section.rows.lastIndex) {
                    Spacer(Modifier.height(10.dp))
                    Divider()
                }
            }

            val containsWearable = section.rows.any { it.collectedByKind == CollectedByKind.WEARABLE }
            if (containsWearable && !hasAnyWearable) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Connect a wearable to enable these settings.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.alpha(0.7f)
                )
            }
        }
    }
}

@Composable
private fun DataRowUi(
    row: DataRow,
    store: DataSettingsStore,
    connectedWearables: List<WearableSource>,
    enabled: Boolean,
    greyOut: Boolean,
    onMetricChanged: (metric: String, enabled: Boolean, preferredSource: String?, allowedSources: List<String>) -> Unit
) {
    val alpha = if (greyOut) 0.55f else 1.0f

    var selectedWearable by remember(row.table) {
        mutableStateOf(store.getSelectedWearable(row.table, row.defaultWearable))
    }

    var active by remember(row.table) {
        mutableStateOf(
            store.getActive(
                table = row.table,
                wearable = if (row.collectedByKind == CollectedByKind.WEARABLE) selectedWearable else null,
                defaultValue = defaultActiveFor(row)
            )
        )
    }

    if (row.collectedByKind == CollectedByKind.WEARABLE) {
        val allowed = connectedWearables
        if (allowed.isNotEmpty() && !allowed.contains(selectedWearable)) {
            selectedWearable = allowed.first()
            store.setSelectedWearable(row.table, selectedWearable)
            active = store.getActive(
                table = row.table,
                wearable = selectedWearable,
                defaultValue = defaultActiveFor(row)
            )

            onMetricChanged(
                row.table,
                active,
                selectedWearable.key,
                allowed.map { it.key }
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(0.46f)) {
            Text(row.table, style = MaterialTheme.typography.bodyMedium)
            if (row.collectedByLabel.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    row.collectedByLabel,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.alpha(0.75f)
                )
            }
        }

        Column(modifier = Modifier.weight(0.34f)) {
            when (row.collectedByKind) {
                CollectedByKind.PHONE -> Text("Phone", style = MaterialTheme.typography.bodyMedium)
                CollectedByKind.MANUAL -> Text("User", style = MaterialTheme.typography.bodyMedium)
                CollectedByKind.REFERENCE -> Text("Reference", style = MaterialTheme.typography.bodyMedium)

                CollectedByKind.WEARABLE -> {
                    val options = connectedWearables.map { it.label }
                    val selectedIndex = options.indexOf(selectedWearable.label).coerceAtLeast(0)

                    if (options.isEmpty()) {
                        Text("No wearable", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        AppDropdown(
                            options = options,
                            selectedIndex = selectedIndex,
                            onSelected = { idx ->
                                val newSel = connectedWearables.getOrNull(idx) ?: return@AppDropdown
                                selectedWearable = newSel
                                store.setSelectedWearable(row.table, newSel)

                                active = store.getActive(
                                    table = row.table,
                                    wearable = newSel,
                                    defaultValue = defaultActiveFor(row)
                                )

                                onMetricChanged(
                                    row.table,
                                    active,
                                    newSel.key,
                                    connectedWearables.map { it.key }
                                )
                            }
                        )
                    }
                }
            }
        }

        Column(modifier = Modifier.weight(0.20f)) {
            val canToggle = enabled && row.collectedByKind != CollectedByKind.REFERENCE
            Row {
                Switch(
                    checked = active,
                    onCheckedChange = { new ->
                        if (!canToggle) return@Switch
                        active = new
                        store.setActive(
                            table = row.table,
                            wearable = if (row.collectedByKind == CollectedByKind.WEARABLE) selectedWearable else null,
                            value = new
                        )

                        when (row.collectedByKind) {
                            CollectedByKind.PHONE -> {
                                onMetricChanged(
                                    row.table,
                                    new,
                                    "device",
                                    listOf("device")
                                )
                            }

                            CollectedByKind.WEARABLE -> {
                                onMetricChanged(
                                    row.table,
                                    new,
                                    selectedWearable.key,
                                    connectedWearables.map { it.key }
                                )
                            }

                            else -> Unit
                        }
                    },
                    enabled = canToggle
                )
                Spacer(Modifier.width(2.dp))
            }
        }
    }
}

private fun defaultActiveFor(row: DataRow): Boolean {
    return when (row.collectedByKind) {
        CollectedByKind.REFERENCE -> false
        else -> true
    }
}

private suspend fun mirrorSettingToSupabase(
    context: Context,
    metric: String,
    enabled: Boolean,
    preferredSource: String?,
    allowedSources: List<String>
) {
    try {
        val access = SessionStore.getValidAccessToken(context)
        if (access.isNullOrBlank()) {
            Log.w("DataSettingsScreen", "No Supabase session token; not mirroring (metric=$metric)")
            return
        }

        SupabaseDataCollectionSettingsService(context).upsertMetricSetting(
            supabaseAccessToken = access,
            metric = metric,
            enabled = enabled,
            preferredSource = preferredSource,
            allowedSources = allowedSources
        )
    } catch (t: Throwable) {
        Log.w("DataSettingsScreen", "Mirror setting failed ($metric): ${t.message}", t)
    }
}

private fun wearableRow(table: String, label: String): DataRow =
    DataRow(
        table = table,
        collectedByKind = CollectedByKind.WEARABLE,
        collectedByLabel = label,
        defaultWearable = WearableSource.WHOOP
    )

private fun phoneRow(table: String, label: String): DataRow =
    DataRow(
        table = table,
        collectedByKind = CollectedByKind.PHONE,
        collectedByLabel = label
    )

private fun manualRow(table: String, label: String): DataRow =
    DataRow(
        table = table,
        collectedByKind = CollectedByKind.MANUAL,
        collectedByLabel = label
    )

private fun referenceRow(table: String, label: String): DataRow =
    DataRow(
        table = table,
        collectedByKind = CollectedByKind.REFERENCE,
        collectedByLabel = label
    )

private data class DataSection(
    val title: String,
    val rows: List<DataRow>
)

private data class DataRow(
    val table: String,
    val collectedByKind: CollectedByKind,
    val collectedByLabel: String,
    val defaultWearable: WearableSource? = null
)

private enum class CollectedByKind {
    PHONE,
    WEARABLE,
    MANUAL,
    REFERENCE
}

private enum class WearableSource(val key: String, val label: String) {
    WHOOP("whoop", "WHOOP")
}

private class DataSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("data_settings", Context.MODE_PRIVATE)

    fun getSelectedWearable(table: String, fallback: WearableSource?): WearableSource {
        val key = "data_source_$table"
        val raw = prefs.getString(key, null) ?: fallback?.key ?: WearableSource.WHOOP.key
        return WearableSource.values().firstOrNull { it.key == raw } ?: WearableSource.WHOOP
    }

    fun setSelectedWearable(table: String, wearable: WearableSource) {
        prefs.edit().putString("data_source_$table", wearable.key).apply()
    }

    fun getActive(
        table: String,
        wearable: WearableSource?,
        defaultValue: Boolean
    ): Boolean {
        val key = activeKey(table, wearable)
        return prefs.getBoolean(key, defaultValue)
    }

    fun setActive(
        table: String,
        wearable: WearableSource?,
        value: Boolean
    ) {
        prefs.edit().putBoolean(activeKey(table, wearable), value).apply()
    }

    private fun activeKey(table: String, wearable: WearableSource?): String {
        return if (wearable == null) {
            "data_active_$table"
        } else {
            "data_active_${table}_${wearable.key}"
        }
    }
}
