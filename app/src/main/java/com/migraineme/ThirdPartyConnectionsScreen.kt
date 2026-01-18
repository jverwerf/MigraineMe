package com.migraineme

import android.app.Activity
import android.content.Context
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
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

    val wearablesExpanded = remember { mutableStateOf(true) }
    val tokenStore = remember { WhoopTokenStore(context) }
    val hasWhoop = remember { mutableStateOf(tokenStore.load() != null) }
    val whoopErrorDialog = remember { mutableStateOf<String?>(null) }
    val showDisconnectDialog = remember { mutableStateOf(false) }

    // Prevent re-processing the same callback URI repeatedly
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

        // Nothing to do
        if (lastUri.isNullOrBlank()) return

        // Already processed this callback
        if (lastProcessedUri.value == lastUri) return

        // Mark as processed immediately to avoid loops if completion fails
        lastProcessedUri.value = lastUri

        val ok = withContext(Dispatchers.IO) {
            WhoopAuthService().completeAuth(context)
        }

        val localToken = tokenStore.load()

        if (ok && localToken != null) {
            hasWhoop.value = true

            withContext(Dispatchers.IO) {
                val edge = EdgeFunctionsService()

                // 1) Store token in Supabase (whoop_tokens)
                val stored = edge.upsertWhoopTokenToSupabase(context.applicationContext, localToken)
                if (!stored) {
                    whoopErrorDialog.value =
                        "WHOOP connected locally, but failed to store token in Supabase. " +
                                "Check Edge Function logs for upsert-whoop-token."
                    return@withContext
                }

                // 2) Enqueue backfill after token exists server-side
                edge.enqueueLoginBackfill(context.applicationContext)
            }
        } else {
            hasWhoop.value = false
            whoopErrorDialog.value =
                prefs.getString("token_error", "WHOOP authentication failed")
                    ?: "WHOOP authentication failed"
        }
    }

    // Run completion when coming back from WHOOP browser flow.
    // This is the missing part: LaunchedEffect(Unit) runs once; ON_RESUME runs after returning.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    tryCompleteWhoopIfCallbackPresent()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Also attempt once on first composition (safe no-op if no last_uri)
    LaunchedEffect(Unit) {
        tryCompleteWhoopIfCallbackPresent()
    }

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

    if (showDisconnectDialog.value) {
        AlertDialog(
            onDismissRequest = { showDisconnectDialog.value = false },
            title = { Text("Disconnect WHOOP?") },
            text = { Text("Are you sure you want to disconnect WHOOP?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        tokenStore.clear()
                        context.getSharedPreferences("whoop_oauth", Context.MODE_PRIVATE)
                            .edit().clear().apply()
                        hasWhoop.value = false
                        lastProcessedUri.value = null
                        showDisconnectDialog.value = false
                    }
                ) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectDialog.value = false }) {
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
        Divider()
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
                            if (hasWhoop.value) showDisconnectDialog.value = true
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
