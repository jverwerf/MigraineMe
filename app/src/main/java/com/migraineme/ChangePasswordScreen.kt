package com.migraineme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChangePasswordScreen(
    authVm: AuthViewModel,
    onDone: () -> Unit
) {
    val auth by authVm.state.collectAsState()
    val scope = rememberCoroutineScope()

    val loading = remember { mutableStateOf(false) }
    val canUse = remember { mutableStateOf(false) }

    val currentPassword = remember { mutableStateOf("") }
    val newPassword = remember { mutableStateOf("") }
    val confirmPassword = remember { mutableStateOf("") }

    val errorDialog = remember { mutableStateOf<String?>(null) }
    val successDialog = remember { mutableStateOf(false) }

    LaunchedEffect(auth.accessToken) {
        val token = auth.accessToken
        if (token.isNullOrBlank()) {
            canUse.value = false
            return@LaunchedEffect
        }

        loading.value = true
        try {
            val user = withContext(Dispatchers.IO) { SupabaseAuthService.getUser(token) }
            canUse.value = user.identities?.any { it.provider == "email" } == true
        } catch (_: Throwable) {
            canUse.value = false
        } finally {
            loading.value = false
        }
    }

    fun validate(): String? {
        if (currentPassword.value.isBlank()) return "Please enter your current password."
        if (newPassword.value.isBlank() || confirmPassword.value.isBlank())
            return "Please fill in all password fields."
        if (newPassword.value != confirmPassword.value)
            return "New passwords do not match."
        if (newPassword.value.length < 8)
            return "Password must be at least 8 characters."
        return null
    }

    fun submit() {
        val token = auth.accessToken
        val userId = auth.userId

        if (token.isNullOrBlank() || userId.isNullOrBlank()) {
            errorDialog.value = "Not signed in."
            return
        }

        if (!canUse.value) {
            errorDialog.value = "Password changes are only available for email accounts."
            return
        }

        val v = validate()
        if (v != null) {
            errorDialog.value = v
            return
        }

        loading.value = true
        scope.launch {
            try {
                // 1) fetch email (authoritative)
                val email = withContext(Dispatchers.IO) {
                    SupabaseAuthService.getUser(token).email
                } ?: throw IllegalStateException("Could not determine email.")

                // 2) re-authenticate with current password
                val session = withContext(Dispatchers.IO) {
                    SupabaseAuthService.signInWithEmail(email, currentPassword.value)
                }

                val newAccessToken = session.accessToken
                    ?: throw IllegalStateException("Re-authentication failed.")

                // 3) change password using fresh token
                withContext(Dispatchers.IO) {
                    SupabaseAuthService.changePassword(newAccessToken, newPassword.value)
                }

                successDialog.value = true
            } catch (t: Throwable) {
                errorDialog.value = t.message ?: "Failed to change password."
            } finally {
                loading.value = false
            }
        }
    }

    errorDialog.value?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorDialog.value = null },
            title = { Text("Error") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { errorDialog.value = null }) { Text("OK") }
            }
        )
    }

    if (successDialog.value) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Password updated") },
            text = { Text("Your password has been changed.") },
            confirmButton = {
                TextButton(onClick = {
                    successDialog.value = false
                    onDone()
                }) { Text("Done") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Change password", style = MaterialTheme.typography.titleLarge)
        Divider()

        OutlinedTextField(
            value = currentPassword.value,
            onValueChange = { currentPassword.value = it },
            label = { Text("Current password") },
            singleLine = true,
            enabled = !loading.value && canUse.value,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = newPassword.value,
            onValueChange = { newPassword.value = it },
            label = { Text("New password") },
            singleLine = true,
            enabled = !loading.value && canUse.value,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = confirmPassword.value,
            onValueChange = { confirmPassword.value = it },
            label = { Text("Confirm new password") },
            singleLine = true,
            enabled = !loading.value && canUse.value,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = { submit() },
            enabled = !loading.value && canUse.value,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (loading.value) "Updating..." else "Update password")
        }
    }
}
