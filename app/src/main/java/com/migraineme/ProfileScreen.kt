package com.migraineme

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ProfileScreen(
    authVm: AuthViewModel = viewModel()
) {
    val auth by authVm.state.collectAsState()

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
    }
}
