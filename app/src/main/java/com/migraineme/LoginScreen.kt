package com.migraineme

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    authVm: AuthViewModel,
    onLoggedIn: () -> Unit,
    onNavigateToSignUp: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val scope = remember { CoroutineScope(Dispatchers.Main) }

    val ctx = LocalContext.current                     // Activity context
    val appCtx = ctx.applicationContext                // For workers only
    val snackbarHostState = remember { SnackbarHostState() }

    // Flag to know login succeeded
    var loginCompleted by remember { mutableStateOf(false) }

    // -------------------------
    // Permission launcher
    // -------------------------
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grantResults ->

        val fine = grantResults[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = grantResults[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (fine || coarse) {
            // Permission granted → start worker
            LocationDailySyncWorker.runOnceNow(appCtx)
            LocationDailySyncWorker.scheduleNext(appCtx)

            // Navigate AFTER worker scheduling
            onLoggedIn()
        } else {
            // Permission denied → still navigate, worker won't run
            onLoggedIn()
        }
    }

    // Check permission using ACTIVITY context
    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fine || coarse
    }

    // When loginCompleted flips to true, we handle permission + worker
    LaunchedEffect(loginCompleted) {
        if (loginCompleted) {
            if (!hasLocationPermission()) {
                // Ask for permission
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            } else {
                // Permission already granted → start worker now
                LocationDailySyncWorker.runOnceNow(appCtx)
                LocationDailySyncWorker.scheduleNext(appCtx)

                // Navigate AFTER worker scheduled
                onLoggedIn()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Sign in", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = {
                    error = null
                    busy = true
                    scope.launch {
                        try {
                            val ses = SupabaseAuthService.signInWithEmail(email.trim(), password)
                            if (!ses.accessToken.isNullOrBlank()) {
                                // Save token
                                SessionStore.saveAccessToken(appCtx, ses.accessToken)
                                authVm.setSession(ses.accessToken, userId = null)

                                // WHOOP + sleep logic unchanged
                                MetricsSyncManager.onLogin(
                                    context = appCtx,
                                    token = ses.accessToken!!,
                                    snackbarHostState = snackbarHostState
                                )

                                // Flag login complete (permission logic starts here)
                                loginCompleted = true

                            } else {
                                error = "Invalid login response."
                            }
                        } catch (e: Exception) {
                            error = e.message ?: "Login failed."
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (busy) "Signing in..." else "Sign in")
            }

            TextButton(onClick = onNavigateToSignUp) {
                Text("Don't have an account? Sign up")
            }
        }
    }
}

