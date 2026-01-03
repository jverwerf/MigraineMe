package com.migraineme

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ThirdPartyConnectionsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = (context as? Activity)
    val scope = rememberCoroutineScope()

    // WHOOP connection status and messages (same logic as before)
    val tokenStore = remember { WhoopTokenStore(context) }
    val hasWhoop = remember { mutableStateOf(tokenStore.load() != null) }
    val whoopMessage = remember { mutableStateOf<String?>(null) }
    val whoopError = remember { mutableStateOf<String?>(null) }
    val triedCompleteOnce = remember { mutableStateOf(false) }

    // After deep link, complete WHOOP auth ONCE (same behavior as before)
    LaunchedEffect(Unit) {
        if (triedCompleteOnce.value) return@LaunchedEffect
        triedCompleteOnce.value = true

        val prefs = context.getSharedPreferences("whoop_oauth", Context.MODE_PRIVATE)
        val lastUri = prefs.getString("last_uri", null)

        if (!lastUri.isNullOrBlank()) {
            try {
                val ok = withContext(Dispatchers.IO) {
                    WhoopAuthService().completeAuth(context)
                }
                hasWhoop.value = tokenStore.load() != null
                whoopMessage.value = if (ok) "WHOOP connected" else "WHOOP auth failed"
                whoopError.value = prefs.getString("token_error", null)
            } catch (_: Throwable) {
                whoopMessage.value = "WHOOP auth error"
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back")
        }

        Spacer(Modifier.height(12.dp))

        Text("Third-party connections", style = MaterialTheme.typography.titleLarge)

        Spacer(Modifier.height(12.dp))
        Divider()
        Spacer(Modifier.height(12.dp))

        // WHOOP section
        Text("WHOOP", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(if (hasWhoop.value) "Status: Connected" else "Status: Not connected")

        whoopMessage.value?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = MaterialTheme.colorScheme.primary)
        }
        whoopError.value?.let {
            Spacer(Modifier.height(6.dp))
            Text("WHOOP error: $it", color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { activity?.let { WhoopAuthService().startAuth(it) } },
            enabled = activity != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Connect WHOOP")
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                scope.launch {
                    try {
                        val ok = withContext(Dispatchers.IO) {
                            WhoopAuthService().completeAuth(context)
                        }
                        hasWhoop.value = tokenStore.load() != null
                        whoopMessage.value = if (ok) "WHOOP connected" else "WHOOP auth failed"
                        val prefs = context.getSharedPreferences("whoop_oauth", Context.MODE_PRIVATE)
                        whoopError.value = prefs.getString("token_error", null)
                    } catch (_: Throwable) {
                        whoopMessage.value = "WHOOP auth error"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Complete WHOOP connection")
        }
    }
}
