package com.migraineme

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

@Composable
fun ProfileScreen(
    authVm: AuthViewModel = viewModel(),
    onOpenThirdPartyConnections: () -> Unit
) {
    val auth by authVm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val profile = remember { mutableStateOf<SupabaseProfileService.Profile?>(null) }
    val profileError = remember { mutableStateOf<String?>(null) }
    val profileMessage = remember { mutableStateOf<String?>(null) }
    val profileLoading = remember { mutableStateOf(false) }

    val isEditing = remember { mutableStateOf(false) }
    val editName = remember { mutableStateOf("") }
    val editAvatarUrl = remember { mutableStateOf("") }
    val selectedMigraineType = remember { mutableStateOf<SupabaseProfileService.MigraineType?>(null) }
    val migraineMenuExpanded = remember { mutableStateOf(false) }

    val avatarBitmap = remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    // WHOOP summary status for Profile section (no connection logic here; that's in the separate screen)
    val tokenStore = remember { WhoopTokenStore(context) }
    val hasWhoop = remember { mutableStateOf(tokenStore.load() != null) }

    LaunchedEffect(auth.accessToken, auth.userId) {
        val token = auth.accessToken
        val userId = auth.userId
        if (token.isNullOrBlank() || userId.isNullOrBlank()) return@LaunchedEffect

        profileLoading.value = true
        profileError.value = null
        profileMessage.value = null

        try {
            val result = withContext(Dispatchers.IO) {
                SupabaseProfileService.ensureProfile(
                    accessToken = token,
                    userId = userId,
                    displayNameHint = null,
                    avatarUrlHint = null
                )
            }
            profile.value = result
            editName.value = result.displayName.orEmpty()
            editAvatarUrl.value = result.avatarUrl.orEmpty()
            selectedMigraineType.value = result.migraineType
        } catch (t: Throwable) {
            profileError.value = t.message
        } finally {
            profileLoading.value = false
        }
    }

    LaunchedEffect(profile.value?.avatarUrl) {
        val url = profile.value?.avatarUrl?.trim()
        if (url.isNullOrBlank()) {
            avatarBitmap.value = null
            return@LaunchedEffect
        }

        avatarBitmap.value = null
        try {
            val bmp = withContext(Dispatchers.IO) {
                URL(url).openStream().use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }
            avatarBitmap.value = bmp?.asImageBitmap()
        } catch (_: Throwable) {
            avatarBitmap.value = null
        }
    }

    fun saveProfile() {
        val token = auth.accessToken ?: return
        val userId = auth.userId ?: return

        profileLoading.value = true
        profileError.value = null
        profileMessage.value = null

        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    SupabaseProfileService.updateProfile(
                        accessToken = token,
                        userId = userId,
                        displayName = editName.value.trim().ifBlank { null },
                        avatarUrl = editAvatarUrl.value.trim().ifBlank { null },
                        migraineType = selectedMigraineType.value
                    )
                }
                profile.value = updated
                isEditing.value = false
                profileMessage.value = "Saved"
            } catch (t: Throwable) {
                profileError.value = t.message
            } finally {
                profileLoading.value = false
            }
        }
    }

    // keep WHOOP status up-to-date when returning to Profile
    LaunchedEffect(Unit) {
        hasWhoop.value = tokenStore.load() != null
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

        Text("Account", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("User ID: ${auth.userId ?: "Not signed in"}")
        Text("Access: ${if (auth.accessToken != null) "Signed in" else "Signed out"}")

        Spacer(Modifier.height(16.dp))

        Text("Profile details", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))

        val current = profile.value
        val headerName = current?.displayName?.takeIf { it.isNotBlank() } ?: "Name not set"
        val headerMigraine = current?.migraineType?.label ?: "Migraine type not set"

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                tonalElevation = 2.dp,
                modifier = Modifier.size(70.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = avatarBitmap.value
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = "Avatar",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                        )
                    } else {
                        Text(
                            text = headerName.trim().firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.headlineMedium
                        )
                    }
                }
            }

            Spacer(Modifier.width(14.dp))

            Box(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(end = 56.dp)) {
                    Text(
                        text = headerName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = headerMigraine,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(modifier = Modifier.align(Alignment.TopEnd)) {
                    IconButton(
                        onClick = { isEditing.value = !isEditing.value },
                        enabled = auth.accessToken != null && !profileLoading.value
                    ) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Edit")
                    }
                    TextButton(
                        onClick = { isEditing.value = !isEditing.value },
                        enabled = auth.accessToken != null && !profileLoading.value
                    ) {
                        Text("Edit")
                    }
                }
            }
        }

        profileError.value?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        profileMessage.value?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.primary)
        }

        if (isEditing.value) {
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = editName.value,
                onValueChange = { editName.value = it },
                label = { Text("Name") },
                singleLine = true,
                enabled = auth.accessToken != null && !profileLoading.value,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = editAvatarUrl.value,
                onValueChange = { editAvatarUrl.value = it },
                label = { Text("Avatar URL") },
                singleLine = true,
                enabled = auth.accessToken != null && !profileLoading.value,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { migraineMenuExpanded.value = true },
                enabled = auth.accessToken != null && !profileLoading.value,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(selectedMigraineType.value?.label ?: "Select migraine type")
            }

            DropdownMenu(
                expanded = migraineMenuExpanded.value,
                onDismissRequest = { migraineMenuExpanded.value = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Not set") },
                    onClick = {
                        selectedMigraineType.value = null
                        migraineMenuExpanded.value = false
                    }
                )
                SupabaseProfileService.MigraineType.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = {
                            selectedMigraineType.value = option
                            migraineMenuExpanded.value = false
                        }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = { saveProfile() },
                enabled = auth.accessToken != null && !profileLoading.value,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (profileLoading.value) "Saving..." else "Save")
            }
        }

        Spacer(Modifier.height(20.dp))
        Divider()
        Spacer(Modifier.height(12.dp))

        // ---- Third-party connections (new section under Profile) ----
        Text("Third-party connections", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("WHOOP: ${if (hasWhoop.value) "Connected" else "Not connected"}")

        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { onOpenThirdPartyConnections() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Manage connections")
        }

        Spacer(Modifier.height(20.dp))
        Divider()
        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = { /* change password later */ },
            enabled = auth.accessToken != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Change password (todo)")
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { authVm.signOut() },
            enabled = auth.accessToken != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Go to Logout")
        }
    }
}
