package com.migraineme

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThirdPartyConnectionsScreen(
    onBack: () -> Unit // intentionally unused
) {
    val context = LocalContext.current
    val activity = (context as? Activity)
    val scope = rememberCoroutineScope()

    val wearablesExpanded = remember { mutableStateOf(true) }

    val tokenStore = remember { WhoopTokenStore(context) }
    val hasWhoop = remember { mutableStateOf(tokenStore.load() != null) }
    val whoopErrorDialog = remember { mutableStateOf<String?>(null) }
    val triedCompleteOnce = remember { mutableStateOf(false) }
    val showDisconnectDialog = remember { mutableStateOf(false) }

    val whoopLogoResId = remember {
        val pkg = context.packageName
        val r = context.resources
        val drawableId = r.getIdentifier("whoop_logo", "drawable", pkg)
        if (drawableId != 0) drawableId else r.getIdentifier("whoop_logo", "mipmap", pkg)
    }

    // Try to complete auth if MainActivity stored a callback URI.
    LaunchedEffect(Unit) {
        if (triedCompleteOnce.value) return@LaunchedEffect
        triedCompleteOnce.value = true

        val prefs = context.getSharedPreferences("whoop_oauth", Context.MODE_PRIVATE)
        val lastUri = prefs.getString("last_uri", null)

        if (!lastUri.isNullOrBlank()) {
            val ok = withContext(Dispatchers.IO) {
                WhoopAuthService().completeAuth(context)
            }

            val stored = tokenStore.load()

            if (ok && stored != null) {
                // Persist WHOOP token in Supabase for backend jobs
                withContext(Dispatchers.IO) {
                    runCatching {
                        val appCtx = context.applicationContext
                        val supaAccess = SessionStore.getValidAccessToken(appCtx)
                        val userId = SessionStore.readUserId(appCtx)
                        if (!supaAccess.isNullOrBlank() && !userId.isNullOrBlank()) {
                            SupabaseWhoopTokenService(appCtx).upsertToken(
                                supabaseAccessToken = supaAccess,
                                userId = userId,
                                token = stored
                            )
                        }
                    }
                }

                // NEW: When WHOOP becomes connected, seed ALL wearable metrics into metric_settings
                // so Supabase immediately reflects the full per-metric config matrix.
                withContext(Dispatchers.IO) {
                    runCatching {
                        val appCtx = context.applicationContext
                        val supaAccess = SessionStore.getValidAccessToken(appCtx)
                        if (!supaAccess.isNullOrBlank()) {
                            seedMetricSettingsForWhoopConnection(appCtx, supaAccess)
                        }
                    }
                }

                hasWhoop.value = true
            } else {
                hasWhoop.value = false
                whoopErrorDialog.value =
                    prefs.getString("token_error", "WHOOP authentication failed")
                        ?: "WHOOP authentication failed"
            }
        }
    }

    whoopErrorDialog.value?.let { msg ->
        AlertDialog(
            onDismissRequest = { whoopErrorDialog.value = null },
            title = { Text("WHOOP connection failed") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { whoopErrorDialog.value = null }) {
                    Text("OK")
                }
            }
        )
    }

    if (showDisconnectDialog.value) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog.value = false },
            title = { Text("Disconnect WHOOP?") },
            text = { Text("Are you sure you want to disconnect WHOOP?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val appCtx = context.applicationContext

                            // Delete token row from Supabase, then clear local token
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    val supaAccess = SessionStore.getValidAccessToken(appCtx)
                                    val userId = SessionStore.readUserId(appCtx)
                                    if (!supaAccess.isNullOrBlank() && !userId.isNullOrBlank()) {
                                        SupabaseWhoopTokenService(appCtx).deleteToken(
                                            supabaseAccessToken = supaAccess,
                                            userId = userId
                                        )
                                    }
                                }
                            }

                            WhoopTokenStore(context).clear()
                            context.getSharedPreferences("whoop_oauth", Context.MODE_PRIVATE)
                                .edit().clear().apply()

                            // NEW: Clear WHOOP availability in metric_settings so backend won't try WHOOP collection
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    val supaAccess = SessionStore.getValidAccessToken(appCtx)
                                    if (!supaAccess.isNullOrBlank()) {
                                        seedMetricSettingsForWhoopDisconnect(appCtx, supaAccess)
                                    }
                                }
                            }

                            hasWhoop.value = false
                            showDisconnectDialog.value = false
                        }
                    }
                ) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog.value = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Divider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { wearablesExpanded.value = !wearablesExpanded.value },
                    onLongClick = {}
                )
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Wearables",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (wearablesExpanded.value)
                    Icons.Outlined.KeyboardArrowUp
                else
                    Icons.Outlined.KeyboardArrowDown,
                contentDescription = null
            )
        }
        Divider()

        if (wearablesExpanded.value) {
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .combinedClickable(
                        enabled = activity != null,
                        onClick = {
                            activity?.let {
                                // Connect or RECONNECT: always start from a clean state
                                WhoopTokenStore(context).clear()
                                context.getSharedPreferences("whoop_oauth", Context.MODE_PRIVATE)
                                    .edit().clear().apply()
                                hasWhoop.value = false
                                WhoopAuthService().startAuth(it)
                            }
                        },
                        onLongClick = {
                            if (hasWhoop.value) {
                                showDisconnectDialog.value = true
                            }
                        }
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(102.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (whoopLogoResId != 0) {
                        Image(
                            painter = painterResource(id = whoopLogoResId),
                            contentDescription = "WHOOP logo",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text(
                            text = "W",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.size(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    if (!hasWhoop.value) {
                        Text(
                            text = "Not connected",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text(
                        text = if (hasWhoop.value) "Sync enabled" else "Tap to connect",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (hasWhoop.value) {
                        Text(
                            text = "Hold to disconnect",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .border(
                            width = 2.dp,
                            color = if (hasWhoop.value)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.outline,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (hasWhoop.value) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Divider()
        }
    }
}

/**
 * Wearable metrics currently exposed in DataSettingsScreen and seeded on WHOOP connect/disconnect.
 * This ensures Supabase metric_settings always contains the full matrix without requiring UI interaction.
 */
private fun wearableMetrics(): List<String> {
    return listOf(
        "sleep_duration_daily",
        "sleep_score_daily",
        "sleep_efficiency_daily",
        "sleep_stages_daily",
        "sleep_disturbances_daily",
        "fell_asleep_time_daily",
        "woke_up_time_daily",
        "recovery_score_daily",
        "resting_hr_daily",
        "hrv_daily",
        "skin_temp_daily",
        "spo2_daily",
        "time_in_high_hr_zones_daily",
        "steps_daily"
    )
}

/**
 * On WHOOP connect:
 * - enabled = local toggle state (default true)
 * - preferred_source = "whoop"
 * - allowed_sources = ["whoop"]
 */
private suspend fun seedMetricSettingsForWhoopConnection(appCtx: Context, supaAccess: String) {
    val rows = wearableMetrics().map { metric ->
        val enabled = DataCollectionSettings.isActive(
            context = appCtx,
            table = metric,
            wearable = "whoop",
            defaultValue = true
        )

        SupabaseDataCollectionSettingsService.MetricSettingRow(
            metric = metric,
            enabled = enabled,
            preferredSource = "whoop",
            allowedSources = listOf("whoop")
        )
    }

    SupabaseDataCollectionSettingsService(appCtx).upsertMetricSettingsBatch(
        supabaseAccessToken = supaAccess,
        rows = rows
    )
}

/**
 * On WHOOP disconnect:
 * - enabled remains based on local toggle state
 * - preferred_source cleared
 * - allowed_sources cleared
 */
private suspend fun seedMetricSettingsForWhoopDisconnect(appCtx: Context, supaAccess: String) {
    val rows = wearableMetrics().map { metric ->
        val enabled = DataCollectionSettings.isActive(
            context = appCtx,
            table = metric,
            wearable = "whoop",
            defaultValue = true
        )

        SupabaseDataCollectionSettingsService.MetricSettingRow(
            metric = metric,
            enabled = enabled,
            preferredSource = null,
            allowedSources = emptyList()
        )
    }

    SupabaseDataCollectionSettingsService(appCtx).upsertMetricSettingsBatch(
        supabaseAccessToken = supaAccess,
        rows = rows
    )
}
