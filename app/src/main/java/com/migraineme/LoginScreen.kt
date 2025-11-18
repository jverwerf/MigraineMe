// FILE: C:\Users\verwe\Projects\MigraineMe\app\src\main\java\com\migraineme\LoginScreen.kt
package com.migraineme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
    val appContext = LocalContext.current.applicationContext

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                            // Persist session as before
                            SessionStore.saveAccessToken(appContext, ses.accessToken)
                            authVm.setSession(ses.accessToken, userId = null)

                            // Schedule daily 09:00 jobs from login (moved from MainActivity)
                            WhoopDailySyncWorkerSleepFields.scheduleNext(appContext)
                            LocationDailySyncWorker.scheduleNext(appContext)

                            // Fill today immediately
                            WhoopDailySyncWorkerSleepFields.runOnceNow(appContext)

                            // Backfill missing WHOOP sleep up to yesterday
                            scope.launch(Dispatchers.IO) {
                                val token = ses.accessToken
                                if (!token.isNullOrBlank()) {
                                    WhoopDailySyncWorkerSleepFields.backfillUpToYesterday(appContext, token)
                                }
                            }

                            onLoggedIn()
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
