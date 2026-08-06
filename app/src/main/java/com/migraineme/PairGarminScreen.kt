package com.migraineme

// Garmin connection card with the iOS GarminConnectionCard layout:
// logo on the left, two same-width buttons stacked tight on the right
// (Connected/Connect + Pair code), and the 6-digit code shown centered
// below once generated.

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun GarminConnectionCard(
    logoResId: Int,
    isConnected: Boolean,
    onConnect: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val edgeFunctions = remember { EdgeFunctionsService() }

    var code by remember { mutableStateOf<String?>(null) }
    var expiresAt by remember { mutableStateOf<Instant?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }

    // Flipped pairing: the watch shows a code, the user types it here.
    var showPairEntry by remember { mutableStateOf(false) }
    var entryCode by remember { mutableStateOf("") }
    var claiming by remember { mutableStateOf(false) }
    var claimed by remember { mutableStateOf(false) }
    var claimError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { while (true) { now = Instant.now(); delay(1000) } }

    suspend fun loadCode() {
        loading = true
        error = null
        val res = edgeFunctions.createWatchPairCode(context)
        loading = false
        if (res == null) { error = "Could not generate code."; return }
        code = res.code
        expiresAt = runCatching { Instant.parse(res.expiresAt) }.getOrNull()
    }

    val buttonWidth = 160.dp
    val accent = Color(0xFFB97BFF)

    BaseCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Logo, vertically centered.
            Box(modifier = Modifier.size(100.dp), contentAlignment = Alignment.Center) {
                if (logoResId != 0) {
                    Image(
                        painter = painterResource(id = logoResId),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(100.dp)
                    )
                } else {
                    Text("G", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 40.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            // Two same-width buttons stacked.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = onConnect,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isConnected) accent.copy(alpha = 0.3f) else accent
                    ),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    modifier = Modifier.width(buttonWidth)
                ) {
                    Text(if (isConnected) "Connected" else "Connect",
                        color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = { showPairEntry = !showPairEntry },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    modifier = Modifier.width(buttonWidth)
                ) {
                    Icon(Icons.Outlined.Watch, contentDescription = null,
                        modifier = Modifier.size(16.dp), tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text("Pair watch", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Flipped pairing: type the code the WATCH is showing.
        if (showPairEntry) {
            Spacer(Modifier.height(8.dp))
            if (claimed) {
                Text(
                    "✓ Watch paired",
                    color = Color(0xFF81C784), fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            } else {
                Text(
                    "Open MigraineMe on your watch, tap Pair, and enter the code it shows:",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.62f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = entryCode,
                        onValueChange = { new ->
                            entryCode = new.filter { it.isDigit() }.take(6)
                            claimError = null
                        },
                        placeholder = { Text("123456", fontFamily = FontFamily.Monospace) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        ),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                        ),
                        modifier = Modifier.width(150.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                claiming = true
                                claimError = null
                                val err = edgeFunctions.claimWatchPairCode(context, entryCode)
                                claiming = false
                                if (err == null) { claimed = true; entryCode = "" }
                                else { claimError = err }
                            }
                        },
                        enabled = entryCode.length == 6 && !claiming,
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accent)
                    ) {
                        Text(if (claiming) "…" else "Link",
                            color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
                claimError?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = Color.Red, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Spacer(Modifier.height(6.dp))
                // Legacy watch builds show no code and ask the user to type
                // one on the watch instead — mint it here for them.
                TextButton(onClick = { scope.launch { loadCode() } }, enabled = !loading) {
                    Text(
                        if (loading) "Getting code…"
                        else "Watch asks you to type a code instead? Tap here",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.62f)
                    )
                }
            }
        }

        // Code + countdown below, centered.
        if (code != null) {
            val c = code!!
            val spaced = if (c.length == 6) "${c.substring(0,3)} ${c.substring(3)}" else c
            Spacer(Modifier.height(4.dp))
            Text(
                spaced, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, color = accent,
                modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            expiresAt?.let { exp ->
                val remaining = (exp.epochSecond - now.epochSecond).coerceAtLeast(0)
                val mins = remaining / 60
                val secs = remaining % 60
                Text(
                    if (remaining > 0) "Expires in $mins:${"%02d".format(secs)}" else "Expired",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (remaining > 0) Color.White.copy(alpha = 0.62f) else Color.Red,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        if (error != null) {
            Text(error!!, color = Color.Red, style = MaterialTheme.typography.bodySmall)
        }
    }
}
