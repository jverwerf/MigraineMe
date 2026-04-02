package com.migraineme

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MenstruationPeriodRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
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
    val scrollState = rememberScrollState()

    // WHOOP state
    val tokenStore = remember { WhoopTokenStore(context) }
    val hasWhoop = remember { mutableStateOf(tokenStore.load() != null) }
    val whoopErrorDialog = remember { mutableStateOf<String?>(null) }
    val showWhoopDisconnectDialog = remember { mutableStateOf(false) }

    // WHOOP access gate
    val whoopAccessStatus = remember { mutableStateOf<WhoopAccessGate.AccessStatus?>(null) }
    val showWhoopAccessDialog = remember { mutableStateOf(false) }
    val whoopAccessRequesting = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!hasWhoop.value) {
            whoopAccessStatus.value = WhoopAccessGate.checkAccess(context)
        }
    }

    // Oura state
    val ouraTokenStore = remember { OuraTokenStore(context) }
    val hasOura = remember { mutableStateOf(ouraTokenStore.load() != null) }
    val ouraErrorDialog = remember { mutableStateOf<String?>(null) }
    val showOuraDisconnectDialog = remember { mutableStateOf(false) }

    // Polar state
    val polarTokenStore = remember { PolarTokenStore(context) }
    val hasPolar = remember { mutableStateOf(polarTokenStore.load() != null) }
    val polarErrorDialog = remember { mutableStateOf<String?>(null) }
    val showPolarDisconnectDialog = remember { mutableStateOf(false) }

    val garminTokenStore = remember { GarminTokenStore(context) }
    val hasGarmin = remember { mutableStateOf(garminTokenStore.load() != null) }
    val garminErrorDialog = remember { mutableStateOf<String?>(null) }
    val showGarminDisconnectDialog = remember { mutableStateOf(false) }

    // Health Connect state
    val healthConnectChecking = remember { mutableStateOf(true) }
    val healthConnectAvailable = remember { mutableStateOf(true) }
    val showHealthConnectDisconnectDialog = remember { mutableStateOf(false) }
    val nutritionDataAvailable = remember { mutableStateOf(false) }
    val checkingNutritionData = remember { mutableStateOf(false) }

    // Metric enabled flags from Supabase
    val nutritionEnabled = remember { mutableStateOf(false) }
    val menstruationEnabled = remember { mutableStateOf(false) }

    // Permission flags from Health Connect
    val nutritionPermissionGranted = remember { mutableStateOf(false) }
    val menstruationPermissionGranted = remember { mutableStateOf(false) }
    val sleepPermissionGranted = remember { mutableStateOf(false) }
    val hrvPermissionGranted = remember { mutableStateOf(false) }
    val stepsPermissionGranted = remember { mutableStateOf(false) }
    val restingHrPermissionGranted = remember { mutableStateOf(false) }
    val weightPermissionGranted = remember { mutableStateOf(false) }
    val spo2PermissionGranted = remember { mutableStateOf(false) }

    // Permission definitions
    val nutritionPermission = HealthPermission.getReadPermission(NutritionRecord::class)
    val menstruationPermission = HealthPermission.getReadPermission(MenstruationPeriodRecord::class)
    val sleepPermission = HealthPermission.getReadPermission(SleepSessionRecord::class)
    val hrvPermission = HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)
    val stepsPermission = HealthPermission.getReadPermission(StepsRecord::class)
    val restingHrPermission = HealthPermission.getReadPermission(RestingHeartRateRecord::class)
    val weightPermission = HealthPermission.getReadPermission(WeightRecord::class)
    val spo2Permission = HealthPermission.getReadPermission(OxygenSaturationRecord::class)
    val exercisePermission = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    val bodyFatPermission = HealthPermission.getReadPermission(BodyFatRecord::class)
    val hydrationPermission = HealthPermission.getReadPermission(HydrationRecord::class)
    val bloodPressurePermission = HealthPermission.getReadPermission(BloodPressureRecord::class)
    val bloodGlucosePermission = HealthPermission.getReadPermission(BloodGlucoseRecord::class)
    val respiratoryRatePermission = HealthPermission.getReadPermission(RespiratoryRateRecord::class)
    val bodyTempPermission = HealthPermission.getReadPermission(BodyTemperatureRecord::class)

    val allHealthConnectPermissions = setOf(
        nutritionPermission, menstruationPermission, sleepPermission, hrvPermission,
        stepsPermission, restingHrPermission, weightPermission, spo2Permission,
        exercisePermission, bodyFatPermission, hydrationPermission,
        bloodPressurePermission, bloodGlucosePermission, respiratoryRatePermission, bodyTempPermission
    )

    val anyWearablePermissionGranted = remember {
        derivedStateOf {
            sleepPermissionGranted.value || hrvPermissionGranted.value ||
                    stepsPermissionGranted.value || restingHrPermissionGranted.value ||
                    weightPermissionGranted.value || spo2PermissionGranted.value
        }
    }

    val anyHCConnected by remember {
        derivedStateOf {
            nutritionPermissionGranted.value || menstruationPermissionGranted.value || anyWearablePermissionGranted.value
        }
    }

    // Helper functions
    suspend fun refreshMetricEnabledFlags() {
        withContext(Dispatchers.IO) {
            val settings = EdgeFunctionsService().getMetricSettings(context.applicationContext)
            val map = settings.associateBy { it.metric }
            withContext(Dispatchers.Main) {
                nutritionEnabled.value = map["nutrition"]?.enabled == true
                menstruationEnabled.value = map["menstruation"]?.enabled == true
            }
        }
    }

    suspend fun refreshHealthConnectPermissions() {
        withContext(Dispatchers.IO) {
            try {
                val availability = HealthConnectClient.getSdkStatus(context)
                if (availability == HealthConnectClient.SDK_AVAILABLE) {
                    val hc = HealthConnectClient.getOrCreate(context)
                    val granted = hc.permissionController.getGrantedPermissions()
                    withContext(Dispatchers.Main) {
                        nutritionPermissionGranted.value = nutritionPermission in granted
                        menstruationPermissionGranted.value = menstruationPermission in granted
                        sleepPermissionGranted.value = sleepPermission in granted
                        hrvPermissionGranted.value = hrvPermission in granted
                        stepsPermissionGranted.value = stepsPermission in granted
                        restingHrPermissionGranted.value = restingHrPermission in granted
                        weightPermissionGranted.value = weightPermission in granted
                        spo2PermissionGranted.value = spo2Permission in granted
                    }
                }
            } catch (_: Exception) { }
        }
    }

    /**
     * Switch phone sleep metrics (duration, fell_asleep, woke_up) to a new source.
     * Called when wearable connects (switch away from phone) or disconnects (switch back to phone).
     * Does NOT auto-enable disabled metrics — only switches preferred_source.
     */
    suspend fun switchPhoneSleepSource(newSource: String) {
        withContext(Dispatchers.IO) {
            val edge = EdgeFunctionsService()
            val sleepMetrics = listOf(
                "sleep_duration_daily",
                "fell_asleep_time_daily",
                "woke_up_time_daily"
            )
            val settings = edge.getMetricSettings(context.applicationContext)
            val settingsMap = settings.associateBy { it.metric }

            for (metric in sleepMetrics) {
                val current = settingsMap[metric]
                val isEnabled = current?.enabled ?: false
                runCatching {
                    edge.upsertMetricSetting(
                        context = context.applicationContext,
                        metric = metric,
                        enabled = isEnabled,
                        preferredSource = newSource
                    )
                }
            }
            Log.d("ThirdPartyConnections", "Switched phone sleep metrics to source: $newSource")

            // If switching back to phone and metrics are enabled, trigger sync
            if (newSource == "phone") {
                PhoneSleepSyncWorker.runOnce(context.applicationContext)
            }
        }
    }

    suspend fun enableNutritionWorkersAndBackfillIfNeeded(showToast: Boolean) {
        if (!nutritionPermissionGranted.value) return
        withContext(Dispatchers.Main) {
            MetricToggleHelper.toggle(context.applicationContext, "nutrition", true)
            if (showToast) {
                android.widget.Toast.makeText(context, "Nutrition connected.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        scope.launch(Dispatchers.IO) {
            checkingNutritionData.value = true
            nutritionDataAvailable.value = HealthConnectDataAvailability.hasNutritionData(context)
            checkingNutritionData.value = false
            try {
                NutritionBackfillUtility.forceReBackfill(context)
                delay(30_000)
                val wm = androidx.work.WorkManager.getInstance(context)
                val immediatePush = androidx.work.OneTimeWorkRequestBuilder<NutritionOutboxPushWorker>()
                    .addTag("immediate_nutrition_push").build()
                wm.enqueue(immediatePush)
            } catch (e: Exception) {
                android.util.Log.e("HealthConnect", "Failed to backfill: ${e.message}", e)
            }
        }
    }

    suspend fun enableMenstruationWorkersIfNeeded(showToast: Boolean) {
        if (!menstruationPermissionGranted.value) return
        withContext(Dispatchers.Main) {
            MetricToggleHelper.toggle(context.applicationContext, "menstruation", true)
            if (showToast) {
                android.widget.Toast.makeText(context, "Menstruation connected.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    suspend fun enableHealthConnectWearablesIfNeeded() {
        if (!anyWearablePermissionGranted.value) return
        withContext(Dispatchers.IO) {
            val edge = EdgeFunctionsService()
            runCatching { edge.enableDefaultHealthConnectMetricSettings(context.applicationContext) }
            HealthConnectSyncManager.markAsConnected(context.applicationContext)
        }
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(context, "Health Connect wearables connected!", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // Health Connect permission launcher
    val healthConnectLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        nutritionPermissionGranted.value = granted.contains(nutritionPermission)
        menstruationPermissionGranted.value = granted.contains(menstruationPermission)
        sleepPermissionGranted.value = granted.contains(sleepPermission)
        hrvPermissionGranted.value = granted.contains(hrvPermission)
        stepsPermissionGranted.value = granted.contains(stepsPermission)
        restingHrPermissionGranted.value = granted.contains(restingHrPermission)
        weightPermissionGranted.value = granted.contains(weightPermission)
        spo2PermissionGranted.value = granted.contains(spo2Permission)

        scope.launch {
            runCatching { refreshMetricEnabledFlags() }
            if (nutritionEnabled.value && nutritionPermissionGranted.value) {
                enableNutritionWorkersAndBackfillIfNeeded(showToast = false)
            }
            if (menstruationEnabled.value && menstruationPermissionGranted.value) {
                enableMenstruationWorkersIfNeeded(showToast = false)
            }
            if (anyWearablePermissionGranted.value) {
                enableHealthConnectWearablesIfNeeded()
            }
        }
    }

    // Check Health Connect availability on launch
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val availability = HealthConnectClient.getSdkStatus(context)
                healthConnectAvailable.value = availability == HealthConnectClient.SDK_AVAILABLE
            } catch (_: Exception) {
                healthConnectAvailable.value = false
            }
            healthConnectChecking.value = false
        }
        runCatching { refreshHealthConnectPermissions() }
        runCatching { refreshMetricEnabledFlags() }
    }

    // WHOOP callback handling
    val lastProcessedUri = remember { mutableStateOf<String?>(null) }
    val whoopLogoResId = remember {
        context.resources.getIdentifier("whoop_logo", "drawable", context.packageName)
            .takeIf { it != 0 } ?: context.resources.getIdentifier("whoop_logo", "mipmap", context.packageName)
    }

    val ouraLogoResId = remember {
        context.resources.getIdentifier("oura_logo", "drawable", context.packageName)
    }

    val polarLogoResId = remember {
        context.resources.getIdentifier("polar_logo", "drawable", context.packageName)
    }

    val garminLogoResId = remember {
        context.resources.getIdentifier("garmin_logo", "drawable", context.packageName)
    }

    suspend fun tryCompleteWhoopIfCallbackPresent() {
        val prefs = context.getSharedPreferences("whoop_oauth", Context.MODE_PRIVATE)
        val lastUri = prefs.getString("last_uri", null)
        if (lastUri.isNullOrBlank()) return

        // If already connected, just refresh UI state and return
        val existingToken = tokenStore.load()
        if (existingToken != null) {
            hasWhoop.value = true
            return
        }

        // Only process if we haven't already processed this exact URI
        if (lastProcessedUri.value == lastUri) return
        lastProcessedUri.value = lastUri

        val ok = withContext(Dispatchers.IO) { WhoopAuthService().completeAuth(context) }
        val localToken = tokenStore.load()

        if (ok && localToken != null) {
            hasWhoop.value = true
            withContext(Dispatchers.IO) {
                val edge = EdgeFunctionsService()
                val stored = edge.upsertWhoopTokenToSupabase(context.applicationContext, localToken)
                if (stored) {
                    runCatching { edge.enableDefaultWhoopMetricSettings(context.applicationContext) }
                    // Switch phone sleep metrics to WHOOP source
                    runCatching { switchPhoneSleepSource("whoop") }
                    edge.enqueueLoginBackfill(context.applicationContext)
                }
            }
        } else {
            hasWhoop.value = false
            whoopErrorDialog.value = prefs.getString("token_error", "WHOOP authentication failed")
            // Clear stale OAuth state so we don't retry on next screen visit
            prefs.edit().remove("last_uri").remove("token_error").apply()
        }
    }

    // ── Oura callback handling ──
    val ouraLastProcessedUri = remember { mutableStateOf<String?>(null) }

    suspend fun tryCompleteOuraIfCallbackPresent() {
        val prefs = context.getSharedPreferences("oura_oauth", Context.MODE_PRIVATE)
        val lastUri = prefs.getString("last_uri", null)
        if (lastUri.isNullOrBlank()) return

        // If already connected, just refresh UI state and return
        val existingToken = ouraTokenStore.load()
        if (existingToken != null) {
            hasOura.value = true
            return
        }

        // Only process if we haven't already processed this exact URI
        if (ouraLastProcessedUri.value == lastUri) return
        ouraLastProcessedUri.value = lastUri

        val ok = withContext(Dispatchers.IO) { OuraAuthService().completeAuth(context) }
        val localToken = ouraTokenStore.load()

        if (ok && localToken != null) {
            hasOura.value = true
            withContext(Dispatchers.IO) {
                val edge = EdgeFunctionsService()
                val stored = edge.upsertOuraTokenToSupabase(context.applicationContext, localToken)
                if (stored) {
                    runCatching { edge.enableDefaultOuraMetricSettings(context.applicationContext) }
                    // Switch phone sleep metrics to Oura source
                    runCatching { switchPhoneSleepSource("oura") }
                    // Pull Oura data before backfill-all runs triggers/risk
                    runCatching { edge.backfillOura(context.applicationContext) }
                    edge.enqueueLoginBackfill(context.applicationContext)
                }
            }
        } else {
            hasOura.value = false
            ouraErrorDialog.value = prefs.getString("token_error", "Oura authentication failed")
            // Clear stale OAuth state so we don't retry on next screen visit
            prefs.edit().remove("last_uri").remove("token_error").apply()
        }
    }

    // ── Polar callback handling ──
    val polarLastProcessedUri = remember { mutableStateOf<String?>(null) }

    suspend fun tryCompletePolarIfCallbackPresent() {
        val prefs = context.getSharedPreferences("polar_oauth", Context.MODE_PRIVATE)
        val lastUri = prefs.getString("last_uri", null)
        if (lastUri.isNullOrBlank()) return

        // If already connected, just refresh UI state and return
        val existingToken = polarTokenStore.load()
        if (existingToken != null) {
            hasPolar.value = true
            return
        }

        // Only process if we haven't already processed this exact URI
        if (polarLastProcessedUri.value == lastUri) return
        polarLastProcessedUri.value = lastUri

        val ok = withContext(Dispatchers.IO) { PolarAuthService().completeAuth(context) }
        val localToken = polarTokenStore.load()

        if (ok && localToken != null) {
            hasPolar.value = true
            withContext(Dispatchers.IO) {
                val edge = EdgeFunctionsService()
                val stored = edge.upsertPolarTokenToSupabase(context.applicationContext, localToken)
                if (stored) {
                    runCatching { edge.enableDefaultPolarMetricSettings(context.applicationContext) }
                    // Switch phone sleep metrics to Polar source
                    runCatching { switchPhoneSleepSource("polar") }
                    // Pull Polar data before backfill-all runs triggers/risk
                    runCatching { edge.backfillPolar(context.applicationContext) }
                    edge.enqueueLoginBackfill(context.applicationContext)
                }
            }
        } else {
            hasPolar.value = false
            polarErrorDialog.value = prefs.getString("token_error", "Polar authentication failed")
            // Clear stale OAuth state so we don't retry on next screen visit
            prefs.edit().remove("last_uri").remove("token_error").apply()
        }
    }

    // ── Garmin callback handling ──
    val garminLastProcessedUri = remember { mutableStateOf<String?>(null) }
    val garminExchangeInProgress = remember { mutableStateOf(false) }

    suspend fun tryCompleteGarminIfCallbackPresent() {
        if (garminExchangeInProgress.value) return
        val prefs = context.getSharedPreferences("garmin_oauth", Context.MODE_PRIVATE)
        val lastUri = prefs.getString("last_uri", null)
        if (lastUri.isNullOrBlank()) return

        val existingToken = garminTokenStore.load()
        if (existingToken != null) {
            hasGarmin.value = true
            return
        }

        if (garminLastProcessedUri.value == lastUri) return
        garminLastProcessedUri.value = lastUri
        garminExchangeInProgress.value = true

        try {
            // Garmin token exchange happens server-side via Edge Function
            val ok = withContext(Dispatchers.IO) { GarminAuthService().completeAuth(context) }
            val localToken = garminTokenStore.load()

            if (ok && localToken != null) {
                hasGarmin.value = true
                withContext(Dispatchers.IO) {
                    val edge = EdgeFunctionsService()
                    // Token exchange Edge Function already enables metrics + enqueues backfill
                    // But also enable on client side as backup
                    runCatching { edge.enableDefaultGarminMetricSettings(context.applicationContext) }
                    runCatching { switchPhoneSleepSource("garmin") }
                    // Pull Garmin data before backfill-all runs triggers/risk
                    runCatching { edge.backfillGarmin(context.applicationContext) }
                    edge.enqueueLoginBackfill(context.applicationContext)
                }
            } else {
                hasGarmin.value = false
                garminErrorDialog.value = prefs.getString("token_error", "Garmin authentication failed")
                // Clear stale OAuth state so we don't retry on next screen visit
                prefs.edit().remove("last_uri").remove("token_error").apply()
            }
        } finally {
            garminExchangeInProgress.value = false
        }
    }

    // Try completing WHOOP OAuth on first screen load (returning from OAuth redirect)
    LaunchedEffect("whoop_initial_check") {
        hasWhoop.value = tokenStore.load() != null
        tryCompleteWhoopIfCallbackPresent()
        if (!hasWhoop.value) {
            kotlinx.coroutines.delay(1500)
            lastProcessedUri.value = null
            tryCompleteWhoopIfCallbackPresent()
        }
        if (!hasWhoop.value) {
            kotlinx.coroutines.delay(2000)
            lastProcessedUri.value = null
            tryCompleteWhoopIfCallbackPresent()
        }
    }

    // Try completing Oura OAuth on first screen load
    LaunchedEffect("oura_initial_check") {
        hasOura.value = ouraTokenStore.load() != null
        tryCompleteOuraIfCallbackPresent()
        if (!hasOura.value) {
            kotlinx.coroutines.delay(1500)
            ouraLastProcessedUri.value = null
            tryCompleteOuraIfCallbackPresent()
        }
        if (!hasOura.value) {
            kotlinx.coroutines.delay(2000)
            ouraLastProcessedUri.value = null
            tryCompleteOuraIfCallbackPresent()
        }
    }

    // Try completing Polar OAuth on first screen load
    LaunchedEffect("polar_initial_check") {
        hasPolar.value = polarTokenStore.load() != null
        tryCompletePolarIfCallbackPresent()
        if (!hasPolar.value) {
            kotlinx.coroutines.delay(1500)
            polarLastProcessedUri.value = null
            tryCompletePolarIfCallbackPresent()
        }
        if (!hasPolar.value) {
            kotlinx.coroutines.delay(2000)
            polarLastProcessedUri.value = null
            tryCompletePolarIfCallbackPresent()
        }
    }

    // Try completing Garmin OAuth on first screen load
    LaunchedEffect("garmin_initial_check") {
        hasGarmin.value = garminTokenStore.load() != null
        tryCompleteGarminIfCallbackPresent()
        if (!hasGarmin.value) {
            kotlinx.coroutines.delay(1500)
            garminLastProcessedUri.value = null
            tryCompleteGarminIfCallbackPresent()
        }
        if (!hasGarmin.value) {
            kotlinx.coroutines.delay(2000)
            garminLastProcessedUri.value = null
            tryCompleteGarminIfCallbackPresent()
        }
    }

    val resumeTick = remember { mutableStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick.value++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(resumeTick.value) {
        // Always refresh hasWhoop from token store on resume
        hasWhoop.value = tokenStore.load() != null
        tryCompleteWhoopIfCallbackPresent()
        // Retry after delay — OAuth callback may not have written to prefs yet
        if (!hasWhoop.value) {
            kotlinx.coroutines.delay(1500)
            lastProcessedUri.value = null  // Reset so retry actually processes
            tryCompleteWhoopIfCallbackPresent()
        }
        // One more retry for slow connections
        if (!hasWhoop.value) {
            kotlinx.coroutines.delay(2000)
            lastProcessedUri.value = null
            tryCompleteWhoopIfCallbackPresent()
        }
        // Always refresh hasOura from token store on resume
        hasOura.value = ouraTokenStore.load() != null
        tryCompleteOuraIfCallbackPresent()
        if (!hasOura.value) {
            kotlinx.coroutines.delay(1500)
            ouraLastProcessedUri.value = null
            tryCompleteOuraIfCallbackPresent()
        }
        if (!hasOura.value) {
            kotlinx.coroutines.delay(2000)
            ouraLastProcessedUri.value = null
            tryCompleteOuraIfCallbackPresent()
        }
        // Always refresh hasPolar from token store on resume
        hasPolar.value = polarTokenStore.load() != null
        tryCompletePolarIfCallbackPresent()
        if (!hasPolar.value) {
            kotlinx.coroutines.delay(1500)
            polarLastProcessedUri.value = null
            tryCompletePolarIfCallbackPresent()
        }
        if (!hasPolar.value) {
            kotlinx.coroutines.delay(2000)
            polarLastProcessedUri.value = null
            tryCompletePolarIfCallbackPresent()
        }
        // Always refresh hasGarmin from token store on resume
        hasGarmin.value = garminTokenStore.load() != null
        tryCompleteGarminIfCallbackPresent()
        if (!hasGarmin.value) {
            kotlinx.coroutines.delay(1500)
            garminLastProcessedUri.value = null
            tryCompleteGarminIfCallbackPresent()
        }
        if (!hasGarmin.value) {
            kotlinx.coroutines.delay(2000)
            garminLastProcessedUri.value = null
            tryCompleteGarminIfCallbackPresent()
        }
        runCatching { refreshHealthConnectPermissions() }
        runCatching { refreshMetricEnabledFlags() }
    }

    // Dialogs

    // WHOOP access request dialog
    if (showWhoopAccessDialog.value) {
        val isPending = whoopAccessStatus.value == WhoopAccessGate.AccessStatus.PENDING
        AlertDialog(
            onDismissRequest = { showWhoopAccessDialog.value = false },
            title = { Text("WHOOP Integration") },
            text = {
                Text(
                    if (isPending)
                        "Your request is being reviewed. We\u2019ll enable WHOOP for your account shortly."
                    else
                        "WHOOP integration is currently invite-only. Request access and we\u2019ll enable it for your account."
                )
            },
            confirmButton = {
                if (!isPending) {
                    TextButton(
                        onClick = {
                            whoopAccessRequesting.value = true
                            scope.launch {
                                val ok = WhoopAccessGate.requestAccess(context)
                                if (ok) whoopAccessStatus.value = WhoopAccessGate.AccessStatus.PENDING
                                whoopAccessRequesting.value = false
                                showWhoopAccessDialog.value = false
                                if (ok) {
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(context, "Access requested!", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        },
                        enabled = !whoopAccessRequesting.value
                    ) {
                        Text(if (whoopAccessRequesting.value) "Requesting\u2026" else "Request Access")
                    }
                } else {
                    TextButton(onClick = { showWhoopAccessDialog.value = false }) { Text("OK") }
                }
            },
            dismissButton = {
                if (!isPending) {
                    TextButton(onClick = { showWhoopAccessDialog.value = false }) { Text("Cancel") }
                }
            }
        )
    }

    whoopErrorDialog.value?.let { msg ->
        AlertDialog(
            onDismissRequest = { whoopErrorDialog.value = null },
            title = { Text("WHOOP connection failed") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { whoopErrorDialog.value = null }) { Text("OK") } }
        )
    }

    if (showWhoopDisconnectDialog.value) {
        AlertDialog(
            onDismissRequest = { showWhoopDisconnectDialog.value = false },
            title = { Text("Disconnect WHOOP?") },
            text = { Text("Are you sure you want to disconnect WHOOP?") },
            confirmButton = {
                TextButton(onClick = {
                    showWhoopDisconnectDialog.value = false
                    scope.launch(Dispatchers.IO) {
                        WhoopAuthService().disconnectWithDebug(context.applicationContext)

                        // Check if Oura, Polar, Garmin, or HC wearables still connected; if not, fall back to phone
                        val ouraStillConnected = OuraTokenStore(context.applicationContext).load() != null
                        val polarStillConnected = PolarTokenStore(context.applicationContext).load() != null
                        val garminStillConnected = GarminTokenStore(context.applicationContext).load() != null
                        val hcStillConnected = try {
                            val hc = HealthConnectClient.getOrCreate(context)
                            val granted = hc.permissionController.getGrantedPermissions()
                            granted.contains(sleepPermission)
                        } catch (_: Exception) { false }

                        if (ouraStillConnected) {
                            runCatching { switchPhoneSleepSource("oura") }
                        } else if (polarStillConnected) {
                            runCatching { switchPhoneSleepSource("polar") }
                        } else if (garminStillConnected) {
                            runCatching { switchPhoneSleepSource("garmin") }
                        } else if (hcStillConnected) {
                            runCatching { switchPhoneSleepSource("health_connect") }
                        } else {
                            runCatching { switchPhoneSleepSource("phone") }
                        }

                        withContext(Dispatchers.Main) {
                            hasWhoop.value = false
                            lastProcessedUri.value = null
                            android.widget.Toast.makeText(context, "WHOOP disconnected.", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { showWhoopDisconnectDialog.value = false }) { Text("Cancel") } }
        )
    }

    // Oura dialogs
    ouraErrorDialog.value?.let { msg ->
        AlertDialog(
            onDismissRequest = { ouraErrorDialog.value = null },
            title = { Text("Oura connection failed") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { ouraErrorDialog.value = null }) { Text("OK") } }
        )
    }

    if (showOuraDisconnectDialog.value) {
        AlertDialog(
            onDismissRequest = { showOuraDisconnectDialog.value = false },
            title = { Text("Disconnect Oura?") },
            text = { Text("Are you sure you want to disconnect Oura?") },
            confirmButton = {
                TextButton(onClick = {
                    showOuraDisconnectDialog.value = false
                    scope.launch(Dispatchers.IO) {
                        OuraAuthService().disconnectWithDebug(context.applicationContext)

                        // Check if WHOOP, Polar, Garmin, or HC wearables still connected; if not, fall back to phone
                        val whoopStillConnected = WhoopTokenStore(context.applicationContext).load() != null
                        val polarStillConnected = PolarTokenStore(context.applicationContext).load() != null
                        val garminStillConnected = GarminTokenStore(context.applicationContext).load() != null
                        val hcStillConnected = try {
                            val hc = HealthConnectClient.getOrCreate(context)
                            val granted = hc.permissionController.getGrantedPermissions()
                            granted.contains(sleepPermission)
                        } catch (_: Exception) { false }

                        if (whoopStillConnected) {
                            runCatching { switchPhoneSleepSource("whoop") }
                        } else if (polarStillConnected) {
                            runCatching { switchPhoneSleepSource("polar") }
                        } else if (garminStillConnected) {
                            runCatching { switchPhoneSleepSource("garmin") }
                        } else if (hcStillConnected) {
                            runCatching { switchPhoneSleepSource("health_connect") }
                        } else {
                            runCatching { switchPhoneSleepSource("phone") }
                        }

                        withContext(Dispatchers.Main) {
                            hasOura.value = false
                            ouraLastProcessedUri.value = null
                            android.widget.Toast.makeText(context, "Oura disconnected.", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { showOuraDisconnectDialog.value = false }) { Text("Cancel") } }
        )
    }

    // Polar dialogs
    polarErrorDialog.value?.let { msg ->
        AlertDialog(
            onDismissRequest = { polarErrorDialog.value = null },
            title = { Text("Polar connection failed") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { polarErrorDialog.value = null }) { Text("OK") } }
        )
    }

    if (showPolarDisconnectDialog.value) {
        AlertDialog(
            onDismissRequest = { showPolarDisconnectDialog.value = false },
            title = { Text("Disconnect Polar?") },
            text = { Text("Are you sure you want to disconnect Polar?") },
            confirmButton = {
                TextButton(onClick = {
                    showPolarDisconnectDialog.value = false
                    scope.launch(Dispatchers.IO) {
                        PolarAuthService().disconnectWithDebug(context.applicationContext)

                        // Check if WHOOP, Oura, Garmin, or HC wearables still connected; if not, fall back to phone
                        val whoopStillConnected = WhoopTokenStore(context.applicationContext).load() != null
                        val ouraStillConnected = OuraTokenStore(context.applicationContext).load() != null
                        val garminStillConnected = GarminTokenStore(context.applicationContext).load() != null
                        val hcStillConnected = try {
                            val hc = HealthConnectClient.getOrCreate(context)
                            val granted = hc.permissionController.getGrantedPermissions()
                            granted.contains(sleepPermission)
                        } catch (_: Exception) { false }

                        if (whoopStillConnected) {
                            runCatching { switchPhoneSleepSource("whoop") }
                        } else if (ouraStillConnected) {
                            runCatching { switchPhoneSleepSource("oura") }
                        } else if (garminStillConnected) {
                            runCatching { switchPhoneSleepSource("garmin") }
                        } else if (hcStillConnected) {
                            runCatching { switchPhoneSleepSource("health_connect") }
                        } else {
                            runCatching { switchPhoneSleepSource("phone") }
                        }

                        withContext(Dispatchers.Main) {
                            hasPolar.value = false
                            polarLastProcessedUri.value = null
                            android.widget.Toast.makeText(context, "Polar disconnected.", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { showPolarDisconnectDialog.value = false }) { Text("Cancel") } }
        )
    }

    garminErrorDialog.value?.let { msg ->
        AlertDialog(
            onDismissRequest = { garminErrorDialog.value = null },
            title = { Text("Garmin connection failed") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { garminErrorDialog.value = null }) { Text("OK") } }
        )
    }

    if (showGarminDisconnectDialog.value) {
        AlertDialog(
            onDismissRequest = { showGarminDisconnectDialog.value = false },
            title = { Text("Disconnect Garmin?") },
            text = { Text("Are you sure you want to disconnect Garmin?") },
            confirmButton = {
                TextButton(onClick = {
                    showGarminDisconnectDialog.value = false
                    scope.launch(Dispatchers.IO) {
                        GarminAuthService().disconnectWithDebug(context.applicationContext)

                        // Check if WHOOP, Oura, Polar, or HC wearables still connected; if not, fall back to phone
                        val whoopStillConnected = WhoopTokenStore(context.applicationContext).load() != null
                        val ouraStillConnected = OuraTokenStore(context.applicationContext).load() != null
                        val polarStillConnected = PolarTokenStore(context.applicationContext).load() != null
                        val hcStillConnected = try {
                            val hc = HealthConnectClient.getOrCreate(context)
                            val granted = hc.permissionController.getGrantedPermissions()
                            granted.contains(sleepPermission)
                        } catch (_: Exception) { false }

                        if (whoopStillConnected) {
                            runCatching { switchPhoneSleepSource("whoop") }
                        } else if (ouraStillConnected) {
                            runCatching { switchPhoneSleepSource("oura") }
                        } else if (polarStillConnected) {
                            runCatching { switchPhoneSleepSource("polar") }
                        } else if (hcStillConnected) {
                            runCatching { switchPhoneSleepSource("health_connect") }
                        } else {
                            runCatching { switchPhoneSleepSource("phone") }
                        }

                        withContext(Dispatchers.Main) {
                            hasGarmin.value = false
                            garminLastProcessedUri.value = null
                            android.widget.Toast.makeText(context, "Garmin disconnected.", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { showGarminDisconnectDialog.value = false }) { Text("Cancel") } }
        )
    }

    if (showHealthConnectDisconnectDialog.value) {
        AlertDialog(
            onDismissRequest = { showHealthConnectDisconnectDialog.value = false },
            title = { Text("Disconnect?") },
            text = { Text("This will stop syncing Health Connect data. To fully disconnect, revoke permissions in Health Connect settings.") },
            confirmButton = {
                TextButton(onClick = {
                    NutritionSyncScheduler.cancel(context)
                    // Use SyncManager instead of cancelling workers directly
                    HealthConnectSyncManager.markAsDisconnected(context.applicationContext)

                    scope.launch(Dispatchers.IO) {
                        try { NutritionSyncDatabase.get(context).clearAllTables() } catch (_: Exception) {}
                        try {
                            val hcDb = HealthConnectSyncDatabase.get(context)
                            hcDb.dao().clearSyncState()
                            hcDb.dao().clearOutbox()
                        } catch (_: Exception) {}

                        val edge = EdgeFunctionsService()
                        edge.upsertMetricSetting(context.applicationContext, "nutrition", false, null)
                        edge.upsertMetricSetting(context.applicationContext, "menstruation", false, null)
                        edge.disableHealthConnectMetricSettings(context.applicationContext)

                        // Check if WHOOP, Oura, Polar, or Garmin still connected; if not, fall back to phone
                        val whoopStillConnected = WhoopTokenStore(context.applicationContext).load() != null
                        val ouraStillConnected = OuraTokenStore(context.applicationContext).load() != null
                        val polarStillConnected = PolarTokenStore(context.applicationContext).load() != null
                        val garminStillConnected = GarminTokenStore(context.applicationContext).load() != null
                        if (whoopStillConnected) {
                            runCatching { switchPhoneSleepSource("whoop") }
                        } else if (ouraStillConnected) {
                            runCatching { switchPhoneSleepSource("oura") }
                        } else if (polarStillConnected) {
                            runCatching { switchPhoneSleepSource("polar") }
                        } else if (garminStillConnected) {
                            runCatching { switchPhoneSleepSource("garmin") }
                        } else {
                            runCatching { switchPhoneSleepSource("phone") }
                        }
                    }

                    try {
                        context.startActivity(Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"))
                    } catch (_: Exception) {}

                    showHealthConnectDisconnectDialog.value = false
                    nutritionPermissionGranted.value = false
                    menstruationPermissionGranted.value = false
                    sleepPermissionGranted.value = false
                    hrvPermissionGranted.value = false
                    stepsPermissionGranted.value = false
                    restingHrPermissionGranted.value = false
                    weightPermissionGranted.value = false
                    spo2PermissionGranted.value = false
                    nutritionEnabled.value = false
                    menstruationEnabled.value = false

                    android.widget.Toast.makeText(context, "Disconnected.", android.widget.Toast.LENGTH_LONG).show()
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { showHealthConnectDisconnectDialog.value = false }) { Text("Cancel") } }
        )
    }

    // Check if all HC permissions granted
    val allHCConnected by remember {
        derivedStateOf {
            nutritionPermissionGranted.value && menstruationPermissionGranted.value &&
                    sleepPermissionGranted.value && hrvPermissionGranted.value &&
                    stepsPermissionGranted.value && restingHrPermissionGranted.value &&
                    weightPermissionGranted.value && spo2PermissionGranted.value
        }
    }

    // UI
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
    ) {
        // Connected Services section
            Text(
                "Connected Services",
                color = AppTheme.AccentPurple,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Health Connect Row — heart icon + label (matches iOS Apple Health style)
            Box(Modifier.spotlightTarget("health_connect_card")) {
                HealthConnectRow(
                    isConnected = anyHCConnected,
                    onClick = {
                        if (!healthConnectAvailable.value) {
                            android.widget.Toast.makeText(context, "Health Connect not available.", android.widget.Toast.LENGTH_LONG).show()
                            return@HealthConnectRow
                        }
                        scope.launch { runCatching { healthConnectLauncher.launch(allHealthConnectPermissions) } }
                    },
                    onLongClick = {
                        if (anyHCConnected) showHealthConnectDisconnectDialog.value = true
                    }
                )
            }

            Spacer(Modifier.height(12.dp))

            // WHOOP Row
            Column(Modifier.spotlightTarget("wearables_group")) {
            Box(Modifier.spotlightTarget("whoop_card")) {
                ConnectionRowLogoOnly(
                    logoResId = whoopLogoResId,
                    fallbackLetter = "W",
                    isConnected = hasWhoop.value,
                    statusLabel = if (!hasWhoop.value) {
                        when (whoopAccessStatus.value) {
                            WhoopAccessGate.AccessStatus.PENDING -> "Requested"
                            WhoopAccessGate.AccessStatus.NONE -> "Request Access"
                            else -> null
                        }
                    } else null,
                    onClick = {
                        if (!hasWhoop.value) {
                            when (whoopAccessStatus.value) {
                                WhoopAccessGate.AccessStatus.APPROVED -> {
                                    // Access granted — proceed with OAuth
                                    if (TourManager.isActive() && TourManager.currentPhase() == CoachPhase.SETUP) {
                                        context.getSharedPreferences("whoop_oauth", Context.MODE_PRIVATE)
                                            .edit().putBoolean("return_to_setup", true).apply()
                                    }
                                    activity?.let { WhoopAuthService().startAuth(it) }
                                }
                                else -> {
                                    // Not approved yet — show request dialog
                                    showWhoopAccessDialog.value = true
                                }
                            }
                        }
                    },
                    onLongClick = {
                        if (hasWhoop.value) showWhoopDisconnectDialog.value = true
                    }
                )
            }

            Spacer(Modifier.height(12.dp))

            // Oura Row
            ConnectionRowLogoOnly(
                logoResId = ouraLogoResId,
                fallbackLetter = "O",
                isConnected = hasOura.value,
                onClick = {
                    if (!hasOura.value) {
                        if (TourManager.isActive() && TourManager.currentPhase() == CoachPhase.SETUP) {
                            context.getSharedPreferences("oura_oauth", Context.MODE_PRIVATE)
                                .edit().putBoolean("return_to_setup", true).apply()
                        }
                        activity?.let { OuraAuthService().startAuth(it) }
                    }
                },
                onLongClick = {
                    if (hasOura.value) showOuraDisconnectDialog.value = true
                }
            )

            Spacer(Modifier.height(12.dp))

            // Polar Row
            ConnectionRowLogoOnly(
                logoResId = polarLogoResId,
                fallbackLetter = "P",
                isConnected = hasPolar.value,
                onClick = {
                    if (!hasPolar.value) {
                        if (TourManager.isActive() && TourManager.currentPhase() == CoachPhase.SETUP) {
                            context.getSharedPreferences("polar_oauth", Context.MODE_PRIVATE)
                                .edit().putBoolean("return_to_setup", true).apply()
                        }
                        activity?.let { PolarAuthService().startAuth(it) }
                    }
                },
                onLongClick = {
                    if (hasPolar.value) showPolarDisconnectDialog.value = true
                }
            )

            Spacer(Modifier.height(12.dp))

            // Garmin Row
            ConnectionRowLogoOnly(
                logoResId = garminLogoResId,
                fallbackLetter = "G",
                isConnected = hasGarmin.value,
                onClick = {
                    if (!hasGarmin.value) {
                        if (TourManager.isActive() && TourManager.currentPhase() == CoachPhase.SETUP) {
                            context.getSharedPreferences("garmin_oauth", Context.MODE_PRIVATE)
                                .edit().putBoolean("return_to_setup", true).apply()
                        }
                        activity?.let { GarminAuthService().startAuth(it) }
                    }
                },
                onLongClick = {
                    if (hasGarmin.value) showGarminDisconnectDialog.value = true
                }
            )
            } // end wearables_group spotlight

            Spacer(Modifier.height(32.dp))
        }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConnectionRowLogoOnly(
    logoResId: Int,
    fallbackLetter: String,
    isConnected: Boolean,
    statusLabel: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    BaseCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(start = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Logo - 100dp
            if (logoResId != 0) {
                Image(
                    painter = painterResource(id = logoResId),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(100.dp)
                )
            } else {
                Box(
                    modifier = Modifier.size(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        fallbackLetter,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 40.sp
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Connect/Connected/Custom status Button
            val buttonLabel = when {
                isConnected -> "Connected"
                statusLabel != null -> statusLabel
                else -> "Connect"
            }
            val buttonColor = when {
                isConnected -> AppTheme.AccentPurple.copy(alpha = 0.3f)
                statusLabel == "Requested" -> Color.White.copy(alpha = 0.15f)
                else -> AppTheme.AccentPurple
            }
            Button(
                onClick = onClick,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    buttonLabel,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HealthConnectRow(
    isConnected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    BaseCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(start = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.size(100.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(38.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Health Connect",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                )
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onClick,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) AppTheme.AccentPurple.copy(alpha = 0.3f) else AppTheme.AccentPurple
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    if (isConnected) "Connected" else "Connect",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConnectionRow(
    logoResId: Int,
    fallbackLetter: String,
    title: String,
    isConnected: Boolean,
    warningText: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    BaseCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(start = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Logo - 100dp
            if (logoResId != 0) {
                Image(
                    painter = painterResource(id = logoResId),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(100.dp)
                )
            } else {
                Box(
                    modifier = Modifier.size(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        fallbackLetter,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 40.sp
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            // Title only
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                if (warningText != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        warningText,
                        color = Color(0xFFFFB74D),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            // Connect/Connected Button
            Button(
                onClick = onClick,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isConnected) AppTheme.AccentPurple.copy(alpha = 0.3f) else AppTheme.AccentPurple
                ),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    if (isConnected) "Connected" else "Connect",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}



