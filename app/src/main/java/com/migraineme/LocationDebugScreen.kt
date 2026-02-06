package com.migraineme

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

private const val LOG_TAG = "LocationDebug"

@Composable
fun LocationDebugScreen() {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    
    var logs by remember { mutableStateOf(listOf<String>()) }
    fun log(msg: String) {
        Log.d(LOG_TAG, msg)
        logs = logs + "[${java.time.LocalTime.now().toString().take(8)}] $msg"
    }
    
    // Status variables
    var hasLocationPermission by remember { mutableStateOf(false) }
    var metricEnabled by remember { mutableStateOf<Boolean?>(null) }
    var latestLocationDate by remember { mutableStateOf<String?>(null) }
    var accessToken by remember { mutableStateOf<String?>(null) }
    
    // Check status on load
    LaunchedEffect(Unit) {
        log("--- Status Check ---")
        
        // Check permission
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        hasLocationPermission = fine || coarse
        log("Location permission: $hasLocationPermission (fine=$fine, coarse=$coarse)")
        
        // Check token
        accessToken = SessionStore.getValidAccessToken(ctx)
        log("Access token: ${if (accessToken != null) "Present (${accessToken!!.take(20)}...)" else "NULL"}")
        
        if (accessToken == null) {
            log("ERROR: No valid access token!")
            return@LaunchedEffect
        }
        
        // Check metric_settings
        withContext(Dispatchers.IO) {
            try {
                val edge = EdgeFunctionsService()
                val settings = edge.getMetricSettings(ctx)
                val locationSetting = settings.find { it.metric == "user_location_daily" }
                metricEnabled = locationSetting?.enabled
                log("metric_settings for user_location_daily: enabled=$metricEnabled")
                log("  Full setting: $locationSetting")
            } catch (e: Exception) {
                log("ERROR fetching metric_settings: ${e.message}")
            }
        }
        
        // Check latest location date
        withContext(Dispatchers.IO) {
            try {
                val svc = SupabasePersonalService(ctx)
                latestLocationDate = svc.latestUserLocationDate(accessToken!!, "device")
                log("Latest user_location_daily (source=device): $latestLocationDate")
            } catch (e: Exception) {
                log("ERROR fetching latest location date: ${e.message}")
            }
        }
        
        log("--- End Status Check ---")
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text("Location Debug", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        
        // Status summary
        Text("Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        StatusRow("Permission", if (hasLocationPermission) "✅ Granted" else "❌ Denied")
        StatusRow("Access Token", if (accessToken != null) "✅ Present" else "❌ Missing")
        StatusRow("Metric Enabled", when (metricEnabled) {
            true -> "✅ Enabled"
            false -> "❌ Disabled"
            null -> "⏳ Loading..."
        })
        StatusRow("Latest Data", latestLocationDate ?: "None")
        StatusRow("Today", LocalDate.now(ZoneId.systemDefault()).toString())
        
        Spacer(Modifier.height(16.dp))
        Divider()
        Spacer(Modifier.height(16.dp))
        
        // Action buttons
        Text("Actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    log("--- Enable Metric ---")
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            try {
                                val success = EdgeFunctionsService().upsertMetricSetting(
                                    context = ctx,
                                    metric = "user_location_daily",
                                    enabled = true
                                )
                                log("upsertMetricSetting returned: $success")
                                
                                // Re-check
                                val settings = EdgeFunctionsService().getMetricSettings(ctx)
                                val setting = settings.find { it.metric == "user_location_daily" }
                                metricEnabled = setting?.enabled
                                log("Re-fetched metric_settings: enabled=${setting?.enabled}")
                            } catch (e: Exception) {
                                log("ERROR: ${e.message}")
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Enable Metric")
            }
            
            Button(
                onClick = {
                    log("--- Disable Metric ---")
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            try {
                                val success = EdgeFunctionsService().upsertMetricSetting(
                                    context = ctx,
                                    metric = "user_location_daily",
                                    enabled = false
                                )
                                log("upsertMetricSetting returned: $success")
                                metricEnabled = false
                            } catch (e: Exception) {
                                log("ERROR: ${e.message}")
                            }
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
            ) {
                Text("Disable Metric")
            }
        }
        
        Spacer(Modifier.height(8.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    log("--- Run Worker Now ---")
                    LocationDailySyncWorker.runOnceNow(ctx)
                    log("Worker enqueued via runOnceNow()")
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
            ) {
                Text("Run Worker Now")
            }
            
            Button(
                onClick = {
                    log("--- Schedule 9AM ---")
                    log("Worker scheduled via scheduleNext()")
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
            ) {
                Text("Schedule 9AM")
            }
        }
        
        Spacer(Modifier.height(8.dp))
        
        Button(
            onClick = {
                log("--- Full Test (Enable + Wait + Run) ---")
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try {
                            log("Step 1: Enabling metric...")
                            val success = EdgeFunctionsService().upsertMetricSetting(
                                context = ctx,
                                metric = "user_location_daily",
                                enabled = true
                            )
                            log("  upsertMetricSetting returned: $success")
                            
                            log("Step 2: Waiting 1 second for Supabase propagation...")
                            kotlinx.coroutines.delay(1000)
                            
                            log("Step 3: Verifying metric is enabled...")
                            val settings = EdgeFunctionsService().getMetricSettings(ctx)
                            val setting = settings.find { it.metric == "user_location_daily" }
                            log("  metric_settings shows: enabled=${setting?.enabled}")
                            metricEnabled = setting?.enabled
                            
                            if (setting?.enabled == true) {
                                log("Step 4: Running worker...")
                                withContext(Dispatchers.Main) {
                                    LocationDailySyncWorker.runOnceNow(ctx)
                                }
                                log("  Worker enqueued!")
                                
                                log("Step 5: Waiting 5 seconds for worker to complete...")
                                kotlinx.coroutines.delay(5000)
                                
                                log("Step 6: Checking for new data...")
                                val token = SessionStore.getValidAccessToken(ctx)
                                if (token != null) {
                                    val svc = SupabasePersonalService(ctx)
                                    val latest = svc.latestUserLocationDate(token, "device")
                                    latestLocationDate = latest
                                    log("  Latest location date: $latest")
                                    
                                    val today = LocalDate.now(ZoneId.systemDefault()).toString()
                                    if (latest == today) {
                                        log("✅ SUCCESS! Location data written for today!")
                                    } else {
                                        log("⚠️ Latest data is $latest, not today ($today)")
                                    }
                                }
                            } else {
                                log("❌ Metric not enabled after upsert! Check Supabase.")
                            }
                        } catch (e: Exception) {
                            log("ERROR: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
        ) {
            Text("🧪 Full Test (Enable → Wait → Run → Verify)")
        }
        
        Spacer(Modifier.height(8.dp))
        
        Button(
            onClick = {
                logs = emptyList()
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
        ) {
            Text("Clear Logs")
        }
        
        Spacer(Modifier.height(16.dp))
        Divider()
        Spacer(Modifier.height(16.dp))
        
        // Logs
        Text("Logs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        
        logs.asReversed().forEach { logLine ->
            Text(
                logLine,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
        }
        
        if (logs.isEmpty()) {
            Text("No logs yet", color = Color.Gray)
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.Gray)
        Text(value)
    }
}
