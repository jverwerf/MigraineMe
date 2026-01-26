package com.migraineme

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThirdPartyConnectionsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // Wearables section
    val wearablesExpanded = remember { mutableStateOf(true) }
    val tokenStore = remember { WhoopTokenStore(context) }
    val hasWhoop = remember { mutableStateOf(tokenStore.load() != null) }
    val whoopErrorDialog = remember { mutableStateOf<String?>(null) }
    val showWhoopDisconnectDialog = remember { mutableStateOf(false) }

    // Health Connect section
    val healthExpanded = remember { mutableStateOf(true) }
    val healthConnectPermissionGranted = remember { mutableStateOf(false) }
    val healthConnectChecking = remember { mutableStateOf(true) }
    val healthConnectAvailable = remember { mutableStateOf(true) }
    val showHealthConnectDisconnectDialog = remember { mutableStateOf(false) }
    val nutritionDataAvailable = remember { mutableStateOf(false) }
    val checkingNutritionData = remember { mutableStateOf(false) }

    val healthConnectPermissions = setOf(
        HealthPermission.getReadPermission(NutritionRecord::class),
        HealthPermission.getReadPermission(MenstruationPeriodRecord::class)
    )

    // Health Connect permission launcher
    val healthConnectLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        android.util.Log.d("HealthConnect", "===== PERMISSION RESULT =====")
        android.util.Log.d("HealthConnect", "Granted set: $granted")

        healthConnectPermissionGranted.value = granted.containsAll(healthConnectPermissions)
        android.util.Log.d("HealthConnect", "Final result: ${healthConnectPermissionGranted.value}")

        if (healthConnectPermissionGranted.value) {
            android.util.Log.d("HealthConnect", "========== PERMISSION GRANTED ==========")

            // Update Supabase FIRST (synchronously in background)
            scope.launch(Dispatchers.IO) {
                try {
                    android.util.Log.d("HealthConnect", "Creating EdgeFunctionsService...")
                    val edge = EdgeFunctionsService()

                    // Update Supabase for nutrition
                    android.util.Log.d("HealthConnect", "Calling upsertMetricSetting for nutrition...")
                    val nutritionSuccess = edge.upsertMetricSetting(
                        context = context.applicationContext,
                        metric = "nutrition",
                        enabled = true,
                        preferredSource = null
                    )
                    android.util.Log.d("HealthConnect", "✅ Nutrition Supabase update result: $nutritionSuccess")

                    // Update Supabase for menstruation
                    android.util.Log.d("HealthConnect", "Calling upsertMetricSetting for menstruation...")
                    val menstruationSuccess = edge.upsertMetricSetting(
                        context = context.applicationContext,
                        metric = "menstruation",
                        enabled = true,
                        preferredSource = null
                    )
                    android.util.Log.d("HealthConnect", "✅ Menstruation Supabase update result: $menstruationSuccess")

                    // Verify by reading back
                    android.util.Log.d("HealthConnect", "Reading back settings from Supabase...")
                    val settings = edge.getMetricSettings(context.applicationContext)
                    android.util.Log.d("HealthConnect", "Got ${settings.size} settings from Supabase:")
                    settings.forEach { setting ->
                        android.util.Log.d("HealthConnect", "  - ${setting.metric}: enabled=${setting.enabled}, preferredSource=${setting.preferredSource}")
                    }

                    // THEN schedule workers (on main thread)
                    withContext(Dispatchers.Main) {
                        if (nutritionSuccess) {
                            android.util.Log.d("HealthConnect", "Scheduling nutrition workers...")
                            MetricToggleHelper.toggle(
                                context.applicationContext,
                                "nutrition",
                                true
                            )
                        } else {
                            android.util.Log.e("HealthConnect", "❌ Skipping nutrition workers - Supabase update failed")
                        }

                        if (menstruationSuccess) {
                            android.util.Log.d("HealthConnect", "Scheduling menstruation workers...")
                            MetricToggleHelper.toggle(
                                context.applicationContext,
                                "menstruation",
                                true
                            )
                        } else {
                            android.util.Log.e("HealthConnect", "❌ Skipping menstruation workers - Supabase update failed")
                        }

                        android.util.Log.d("HealthConnect", "========== SETUP COMPLETE ==========")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("HealthConnect", "❌ Failed to update settings: ${e.message}", e)
                }
            }

            // Check if nutrition data actually exists
            scope.launch(Dispatchers.IO) {
                checkingNutritionData.value = true
                nutritionDataAvailable.value = HealthConnectDataAvailability.hasNutritionData(context)
                checkingNutritionData.value = false
            }

            // Force a fresh backfill after permission grant
            scope.launch(Dispatchers.IO) {
                try {
                    // Log status before
                    NutritionBackfillUtility.logCurrentStatus(context)

                    // Force re-backfill to capture last 7 days
                    android.util.Log.d("HealthConnect", "Starting force re-backfill...")
                    NutritionBackfillUtility.forceReBackfill(context)

                    // Wait for backfill to complete (give it 30 seconds)
                    delay(30_000)

                    // Log status after
                    NutritionBackfillUtility.logCurrentStatus(context)

                    // Now trigger push worker to upload to Supabase
                    val wm = androidx.work.WorkManager.getInstance(context)
                    val immediatePush = androidx.work.OneTimeWorkRequestBuilder<NutritionOutboxPushWorker>()
                        .addTag("immediate_nutrition_push")
                        .build()
                    wm.enqueue(immediatePush)

                    android.util.Log.d("HealthConnect", "Backfill complete, push worker enqueued")
                } catch (e: Exception) {
                    android.util.Log.e("HealthConnect", "Failed to backfill: ${e.message}", e)
                }
            }

            android.widget.Toast.makeText(
                context,
                "Health Connect connected! Tracking enabled in Data Settings.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    // Check Health Connect availability and permission on launch
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val availability = HealthConnectClient.getSdkStatus(context)
                android.util.Log.d("HealthConnect", "SDK Status: $availability")

                when (availability) {
                    HealthConnectClient.SDK_UNAVAILABLE -> {
                        healthConnectAvailable.value = false
                        android.util.Log.e("HealthConnect", "SDK unavailable on this device")
                    }
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                        healthConnectAvailable.value = false
                        android.util.Log.e("HealthConnect", "Health Connect app needs update")
                    }
                    HealthConnectClient.SDK_AVAILABLE -> {
                        healthConnectAvailable.value = true
                        val healthConnectClient = HealthConnectClient.getOrCreate(context)
                        val grantedPermissions = healthConnectClient.permissionController.getGrantedPermissions()
                        healthConnectPermissionGranted.value = healthConnectPermissions.all { it in grantedPermissions }
                        android.util.Log.d("HealthConnect", "Permission granted: ${healthConnectPermissionGranted.value}")
                    }
                    else -> {
                        healthConnectAvailable.value = false
                        android.util.Log.e("HealthConnect", "Unknown SDK status: $availability")
                    }
                }
            } catch (e: Exception) {
                healthConnectAvailable.value = false
                android.util.Log.e("HealthConnect", "Error checking: ${e.message}", e)
            }
            healthConnectChecking.value = false
        }
    }

    // Track which callback URI we already processed
    val lastProcessedUri = remember { mutableStateOf<String?>(null) }

    val whoopLogoResId = remember {
        val pkg = context.packageName
        val r = context.resources
        r.getIdentifier("whoop_logo", "drawable", pkg)
            .takeIf { it != 0 }
            ?: r.getIdentifier("whoop_logo", "mipmap", pkg)
    }

    suspend fun tryCompleteWhoopIfCallbackPresent() {
        val prefs = context.getSharedPreferences("whoop_oauth", Context.MODE_PRIVATE)
        val lastUri = prefs.getString("last_uri", null)

        if (lastUri.isNullOrBlank()) return
        if (lastProcessedUri.value == lastUri) return

        lastProcessedUri.value = lastUri

        val ok = withContext(Dispatchers.IO) {
            WhoopAuthService().completeAuth(context)
        }

        val localToken = tokenStore.load()

        if (ok && localToken != null) {
            hasWhoop.value = true

            withContext(Dispatchers.IO) {
                val edge = EdgeFunctionsService()
                val stored = edge.upsertWhoopTokenToSupabase(context.applicationContext, localToken)

                if (!stored) {
                    whoopErrorDialog.value =
                        "WHOOP connected locally, but failed to store token in Supabase. " +
                                "Check edge function logs for upsert-whoop-token."
                    return@withContext
                }

                edge.enqueueLoginBackfill(context.applicationContext)
            }
        } else {
            hasWhoop.value = false
            whoopErrorDialog.value =
                prefs.getString("token_error", "WHOOP authentication failed")
                    ?: "WHOOP authentication failed"
        }
    }

    val resumeTick = remember { mutableStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeTick.value = resumeTick.value + 1
                // Re-check Health Connect permission on resume
                scope.launch(Dispatchers.IO) {
                    try {
                        val availability = HealthConnectClient.getSdkStatus(context)
                        if (availability == HealthConnectClient.SDK_AVAILABLE) {
                            val healthConnectClient = HealthConnectClient.getOrCreate(context)
                            val grantedPermissions = healthConnectClient.permissionController.getGrantedPermissions()
                            healthConnectPermissionGranted.value = healthConnectPermissions.all { it in grantedPermissions }
                            android.util.Log.d("HealthConnect", "Resume check - granted: ${healthConnectPermissionGranted.value}")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("HealthConnect", "Resume check error: ${e.message}")
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(resumeTick.value) {
        tryCompleteWhoopIfCallbackPresent()
    }

    // WHOOP error dialog
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

    // WHOOP disconnect dialog
    if (showWhoopDisconnectDialog.value) {
        AlertDialog(
            onDismissRequest = { showWhoopDisconnectDialog.value = false },
            title = { Text("Disconnect WHOOP?") },
            text = { Text("Are you sure you want to disconnect WHOOP?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        tokenStore.clear()
                        context.getSharedPreferences("whoop_oauth", Context.MODE_PRIVATE)
                            .edit().clear().apply()
                        hasWhoop.value = false
                        showWhoopDisconnectDialog.value = false
                        lastProcessedUri.value = null
                    }
                ) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = { showWhoopDisconnectDialog.value = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Health Connect disconnect dialog
    if (showHealthConnectDisconnectDialog.value) {
        AlertDialog(
            onDismissRequest = { showHealthConnectDisconnectDialog.value = false },
            title = { Text("Disconnect Health Connect?") },
            text = { Text("This will stop syncing nutrition data. You'll need to revoke the nutrition permission in Health Connect settings.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Cancel nutrition sync workers
                        NutritionSyncScheduler.cancel(context)

                        // Clear local sync database
                        scope.launch(Dispatchers.IO) {
                            try {
                                val db = NutritionSyncDatabase.get(context)
                                db.clearAllTables()
                                android.util.Log.d("HealthConnect", "Cleared local sync database")
                            } catch (e: Exception) {
                                android.util.Log.e("HealthConnect", "Error clearing DB: ${e.message}")
                            }
                        }

                        // Open Health Connect settings so user can revoke permission
                        try {
                            val intent = Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.util.Log.e("HealthConnect", "Can't open settings: ${e.message}")
                        }

                        showHealthConnectDisconnectDialog.value = false
                        healthConnectPermissionGranted.value = false

                        // Upsert Nutrition metric setting to Supabase (disabled)
                        scope.launch(Dispatchers.IO) {
                            try {
                                val edge = EdgeFunctionsService()
                                edge.upsertMetricSetting(
                                    context = context.applicationContext,
                                    metric = "nutrition",
                                    enabled = false,
                                    preferredSource = null
                                )
                            } catch (e: Exception) {
                                android.util.Log.e("HealthConnect", "Failed to disable nutrition metric setting: ${e.message}")
                            }
                        }

                        android.widget.Toast.makeText(
                            context,
                            "Health Connect disconnected. Please revoke nutrition permission in settings.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                ) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = { showHealthConnectDisconnectDialog.value = false }) {
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

        // ========== HEALTH APPS SECTION ==========
        Divider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { healthExpanded.value = !healthExpanded.value },
                    onLongClick = {}
                )
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Health Apps",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (healthExpanded.value)
                    Icons.Outlined.KeyboardArrowUp
                else
                    Icons.Outlined.KeyboardArrowDown,
                contentDescription = null
            )
        }
        Divider()

        if (healthExpanded.value) {
            Spacer(Modifier.height(12.dp))

            // Health Connect Card
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
                        enabled = !healthConnectChecking.value,
                        onClick = {
                            if (!healthConnectAvailable.value) {
                                android.widget.Toast.makeText(
                                    context,
                                    "Health Connect not available. Please install from Play Store.",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                                return@combinedClickable
                            }

                            if (!healthConnectPermissionGranted.value) {
                                scope.launch {
                                    try {
                                        healthConnectLauncher.launch(healthConnectPermissions)
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Failed to open permissions: ${e.message}",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            } else {
                                // Open Health Connect settings
                                try {
                                    val intent = Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Could not open Health Connect settings",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        onLongClick = {
                            // Show disconnect dialog if connected
                            if (healthConnectPermissionGranted.value) {
                                showHealthConnectDisconnectDialog.value = true
                            }
                        }
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Health Connect icon placeholder
                Box(
                    modifier = Modifier.size(80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "HC",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.size(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Health Connect",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (healthConnectChecking.value) {
                            "Checking..."
                        } else if (!healthConnectAvailable.value) {
                            "Not available • Install from Play Store"
                        } else if (healthConnectPermissionGranted.value) {
                            if (checkingNutritionData.value) {
                                "Connected • Checking for nutrition data..."
                            } else if (nutritionDataAvailable.value) {
                                "Connected • Nutrition tracking enabled"
                            } else {
                                "Connected • No nutrition data yet"
                            }
                        } else {
                            "Tap to connect • Track nutrition data"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .border(
                            width = 2.dp,
                            color = if (healthConnectPermissionGranted.value)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.outline,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (healthConnectPermissionGranted.value) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Show helper message if connected but no data
            if (healthConnectPermissionGranted.value && !checkingNutritionData.value && !nutritionDataAvailable.value) {
                Spacer(Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "💡 No nutrition data found",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Install Cronometer or MyFitnessPal, then enable Health Connect sync in their settings to start tracking your nutrition.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Divider()
        }

        // ========== WEARABLES SECTION ==========
        Spacer(Modifier.height(12.dp))
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

            // WHOOP Card
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
                                tokenStore.clear()
                                context.getSharedPreferences("whoop_oauth", Context.MODE_PRIVATE)
                                    .edit().clear().apply()
                                hasWhoop.value = false
                                lastProcessedUri.value = null
                                WhoopAuthService().startAuth(it)
                            }
                        },
                        onLongClick = {
                            if (hasWhoop.value) showWhoopDisconnectDialog.value = true
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
                        Text("W", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(Modifier.size(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (hasWhoop.value) "Sync enabled" else "Tap to connect",
                        style = MaterialTheme.typography.bodyLarge
                    )
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