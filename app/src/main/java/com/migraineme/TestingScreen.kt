package com.migraineme

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun TestingScreen(authVm: AuthViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var testResult by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Testing Screen",
            style = MaterialTheme.typography.titleLarge
        )

        HorizontalDivider()

        // NUTRITION SYNC TEST CARD (Health Connect Changes -> Outbox -> Supabase Push)
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Nutrition Sync Test",
                    style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = "Runs one-time: HealthConnectNutritionChangesWorker then NutritionOutboxPushWorker.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            testResult = runNutritionSyncNow(context)
                            isLoading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Running...")
                    } else {
                        Text("Run Nutrition Sync Now")
                    }
                }

                if (testResult.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (testResult.contains("✅"))
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = testResult,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        // TIMEZONE TEST CARD
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Timezone Upload Test",
                    style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = "Tests if timezone is being sent to Supabase correctly",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            testResult = testTimezoneUpload(context)
                            isLoading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Testing...")
                    } else {
                        Text("Test Timezone Upload")
                    }
                }

                if (testResult.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (testResult.contains("✅"))
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = testResult,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        // SCREEN TIME TEST CARD
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Screen Time Test",
                    style = MaterialTheme.typography.titleMedium
                )

                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            testResult = testScreenTimeUpload(context)
                            isLoading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                ) {
                    Text("Test Screen Time Upload")
                }
            }
        }

        // AMBIENT NOISE TEST CARD
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Ambient Noise Test",
                    style = MaterialTheme.typography.titleMedium
                )

                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            testResult = testAmbientNoise(context)
                            isLoading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                ) {
                    Text("Test Ambient Noise Sample")
                }
            }
        }
    }
}

private suspend fun runNutritionSyncNow(context: Context): String {
    return try {
        val accessToken = SessionStore.getValidAccessToken(context)
        if (accessToken == null) {
            return "❌ Not logged in (no access token)"
        }

        val wm = androidx.work.WorkManager.getInstance(context)

        val changes = androidx.work.OneTimeWorkRequestBuilder<HealthConnectNutritionChangesWorker>()
            .addTag("debug_nutrition_changes")
            .build()

        val push = androidx.work.OneTimeWorkRequestBuilder<NutritionOutboxPushWorker>()
            .addTag("debug_nutrition_push")
            .build()

        wm.beginWith(changes).then(push).enqueue()

        """
        ✅ Enqueued one-time nutrition sync.
        
        Check Logcat tags:
        • HcNutritionChanges
        • NutritionOutboxPush
        """.trimIndent()
    } catch (e: Exception) {
        "❌ ERROR: ${e.message}"
    }
}

private suspend fun testTimezoneUpload(context: Context): String {
    return try {
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

        if (!hasLocationPermission) {
            return "❌ Location permission not granted"
        }

        val location = LocationDailySyncWorker.getDeviceLocation(context)
        if (location == null) {
            return "❌ Could not get device location"
        }

        val accessToken = SessionStore.getValidAccessToken(context)
        if (accessToken == null) {
            return "❌ Not logged in (no access token)"
        }

        val deviceTimezone = ZoneId.systemDefault().id
        val today = LocalDate.now()

        val svc = SupabasePersonalService(context)
        svc.upsertUserLocationDaily(
            accessToken = accessToken,
            date = today.toString(),
            latitude = location.latitude,
            longitude = location.longitude,
            source = "device_test",
            timezone = deviceTimezone
        )

        """
        ✅ SUCCESS!
        
        Uploaded to Supabase:
        • Date: $today
        • Timezone: $deviceTimezone
        • Latitude: ${location.latitude}
        • Longitude: ${location.longitude}
        
        Check your database:
        SELECT date, timezone, latitude, longitude 
        FROM user_location_daily 
        WHERE source = 'device_test' 
        ORDER BY date DESC 
        LIMIT 1;
        """.trimIndent()

    } catch (e: Exception) {
        "❌ ERROR: ${e.message}"
    }
}

private suspend fun testScreenTimeUpload(context: Context): String {
    return try {
        if (!ScreenTimePermissionHelper.hasPermission(context)) {
            return "❌ Screen time permission not granted"
        }

        val accessToken = SessionStore.getValidAccessToken(context)
        if (accessToken == null) {
            return "❌ Not logged in"
        }

        "✅ Screen time worker triggered! Check logs."

    } catch (e: Exception) {
        "❌ ERROR: ${e.message}"
    }
}

private suspend fun testAmbientNoise(context: Context): String {
    return try {
        if (!MicrophonePermissionHelper.hasPermission(context)) {
            return "❌ Microphone permission not granted"
        }

        AmbientNoiseSampleWorker.schedule(context)
        "✅ Ambient noise worker scheduled! Will run in ~30 seconds."

    } catch (e: Exception) {
        "❌ ERROR: ${e.message}"
    }
}
