package com.migraineme

// Inline section embedded inside the Garmin connection card in
// ThirdPartyConnectionsScreen.kt. Calls create-watch-pair-code and shows the
// 6-digit code for the MigraineMe Connect IQ app running on the user's Garmin.

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun WatchPairingInline() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val edgeFunctions = remember { EdgeFunctionsService() }

    var code by remember { mutableStateOf<String?>(null) }
    var expiresAt by remember { mutableStateOf<Instant?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }

    LaunchedEffect(Unit) {
        while (true) { now = Instant.now(); delay(1000) }
    }

    suspend fun load() {
        loading = true
        error = null
        val res = edgeFunctions.createWatchPairCode(context)
        loading = false
        if (res == null) { error = "Could not generate code."; return }
        code = res.code
        expiresAt = runCatching { Instant.parse(res.expiresAt) }.getOrNull()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Right-aligned button row that mirrors the "Connected" row above it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Same 100dp empty slot as the logo above, so the button aligns vertically.
            Spacer(Modifier.size(100.dp))
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { scope.launch { load() } },
                enabled = !loading,
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB97BFF)),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Outlined.Watch, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (code == null) "Pair code" else "New code", fontWeight = FontWeight.SemiBold)
            }
        }

        // Code + expiry below, centered. Only visible after Connect tapped.
        if (code != null || loading || error != null) {
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    loading -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    code != null -> {
                        val c = code!!
                        val spaced = if (c.length == 6) "${c.substring(0,3)} ${c.substring(3)}" else c
                        Text(
                            spaced, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospaced, color = Color(0xFFB97BFF)
                        )
                        expiresAt?.let { exp ->
                            val remaining = (exp.epochSecond - now.epochSecond).coerceAtLeast(0)
                            val mins = remaining / 60
                            val secs = remaining % 60
                            Text(
                                if (remaining > 0) "Expires in $mins:${"%02d".format(secs)}" else "Expired",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (remaining > 0) Color.White.copy(alpha = 0.62f) else Color.Red
                            )
                        }
                    }
                    error != null -> Text(error!!, color = Color.Red, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
