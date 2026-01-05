// FILE: app/src/main/java/com/migraineme/LogoutScreen.kt
package com.migraineme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun LogoutScreen(
    authVm: AuthViewModel,   // passed from MainActivity so it's the SAME instance
    onLoggedOut: () -> Unit
) {
    val ctx = LocalContext.current
    val authState by authVm.state.collectAsState()

    // We only navigate away once we've confirmed the session is cleared
    val signOutRequested = remember { mutableStateOf(false) }

    LaunchedEffect(signOutRequested.value, authState.accessToken) {
        if (signOutRequested.value && authState.accessToken.isNullOrBlank()) {
            signOutRequested.value = false
            onLoggedOut()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("Sign out of your account on this device.")
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                // Clear persisted session immediately (prevents any residual token usage)
                SessionStore.clear(ctx.applicationContext)

                signOutRequested.value = true
                authVm.signOut()
                // DO NOT call onLoggedOut() here; we wait for authState to reflect signed-out
            }
        ) {
            Text("Sign out")
        }
    }
}
