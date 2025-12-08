package com.migraineme

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProfileScreen(
    authVm: AuthViewModel = viewModel()
) {
    val auth by authVm.state.collectAsState()
    val context = LocalContext.current
    val activity = (context as? Activity)
    val scope = rememberCoroutineScope()

    // WHOOP connection status and messages
    val tokenStore = remember { WhoopTokenStore(context) }
    val hasWhoop = remember { mutableStateOf(tokenStore.load() != null) }
    val whoopMessage = remember { mutableStateOf<String?>(null) }
    val whoopError = remember { mutableStateOf<String?>(null) }
    val triedCompleteOnce = remember { mutableStateOf(false) }

    // After deep link, complete WHOOP auth ON BACKGROUND THREAD once
    LaunchedEffect(Unit) {
        if (triedCompleteOnce.value) return@LaunchedEffect
        triedCompleteOnce.value = true

        val prefs = context.getSharedPreferences("whoop_oauth", Context.MODE_PRIVATE)
        val lastUri = prefs.getString("last_uri", null)

        if (!lastUri.isNullOrBlank()) {
            try {
                val ok = withContext(Dispatchers.IO) { WhoopAuthService().completeAuth(context) }
                hasWhoop.value = tokenStore.load() != null
                whoopMessage.value = if (ok) "WHOOP connected" else "WHOOP auth failed"
                whoopError.value = prefs.getString("token_error", null)
                if (!ok) {
                    // Clear pending to avoid loops; keep token_error for visibility
                    prefs.edit()
                        .remove("last_uri")
                        .remove("code")
                        .remove("state")
                        .remove("error")
                        .apply()
                }
            } catch (_: Throwable) {
                whoopMessage.value = "WHOOP auth error"
                whoopError.value = context.getSharedPreferences("whoop_oauth", Context.MODE_PRIVATE)
                    .getString("token_error", null)
                context.getSharedPreferences("whoop_oauth", Context.MODE_PRIVATE).edit()
                    .remove("last_uri")
                    .remove("code")
                    .remove("state")
                    .remove("error")
                    .apply()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Profile", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Divider()
        Spacer(Modifier.height(12.dp))

        Text(
            "Account",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )
        Spacer(Modifier.height(8.dp))
        Text("User ID: ${auth.userId ?: "Not signed in"}")
        Text("Access: ${if (auth.accessToken != null) "Signed in" else "Signed out"}")

        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = { /* navigate to change password or email later */ },
            enabled = auth.accessToken != null
        ) {
            Text("Change password (todo)")
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { /* You can navigate to Routes.LOGOUT from here if you inject NavController */ },
            enabled = auth.accessToken != null
        ) {
            Text("Go to Logout")
        }

        Spacer(Modifier.height(24.dp))
        Divider()
        Spacer(Modifier.height(12.dp))

        Text(
            "WHOOP",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )
        Spacer(Modifier.height(8.dp))
        Text(if (hasWhoop.value) "Status: Connected" else "Status: Not connected")
        whoopMessage.value?.let { msg ->
            Spacer(Modifier.height(4.dp))
            Text(msg, color = MaterialTheme.colorScheme.primary)
        }
        whoopError.value?.let { err ->
            Spacer(Modifier.height(4.dp))
            Text("WHOOP error: $err", color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                // Launch WHOOP consent in the browser using PKCE
                activity?.let { WhoopAuthService().startAuth(it) }
            },
            enabled = activity != null
        ) {
            Text("Connect WHOOP")
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                whoopMessage.value = null
                whoopError.value = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { WhoopAuthService().completeAuth(context) }
                    hasWhoop.value = tokenStore.load() != null
                    whoopMessage.value = if (ok) "WHOOP connected" else "WHOOP auth failed"
                    whoopError.value = context.getSharedPreferences("whoop_oauth", Context.MODE_PRIVATE)
                        .getString("token_error", null)
                }
            }
        ) {
            Text("Complete WHOOP connection")
        }
    }
}
 