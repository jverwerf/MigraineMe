package com.migraineme

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Spacer


private const val PASSWORD_RECOVERY_REDIRECT_URL = "https://www.andlane.co.uk/migraineme-recover"

@Composable
fun LoginScreen(
    authVm: AuthViewModel,
    onLoggedIn: () -> Unit,
    onNavigateToSignUp: () -> Unit
) {
    val scope = remember { CoroutineScope(Dispatchers.Main) }
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext
    val snackbarHostState = remember { SnackbarHostState() }

    var loginCompleted by remember { mutableStateOf(false) }

    var showEmailForm by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Forgot password dialog state
    var showForgotDialog by remember { mutableStateOf(false) }
    var forgotEmail by remember { mutableStateOf("") }
    var forgotBusy by remember { mutableStateOf(false) }
    var forgotError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            LocationDailySyncWorker.runOnceNow(appCtx)
            LocationDailySyncWorker.scheduleNext(appCtx)
        }
    }

    LaunchedEffect(loginCompleted) {
        if (!loginCompleted) return@LaunchedEffect

        val fine = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fine || coarse) {
            LocationDailySyncWorker.runOnceNow(appCtx)
            LocationDailySyncWorker.scheduleNext(appCtx)
            onLoggedIn()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    fun handleSuccessfulSession(
        token: String,
        displayNameHint: String? = null,
        avatarUrlHint: String? = null
    ) {
        val userId = JwtUtils.extractUserIdFromAccessToken(token)
        if (userId.isNullOrBlank()) {
            scope.launch {
                snackbarHostState.showSnackbar("Login failed: no userId in access token")
            }
            return
        }

        SessionStore.saveSession(appCtx, token, userId)
        authVm.setSession(token, userId)

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SupabaseProfileService.ensureProfile(
                        accessToken = token,
                        userId = userId,
                        displayNameHint = displayNameHint,
                        avatarUrlHint = avatarUrlHint
                    )
                }
            } catch (_: Throwable) {
                // Never block login on profile hydration.
            }

            MetricsSyncManager.onLogin(appCtx, token, snackbarHostState)
            loginCompleted = true
        }
    }

    fun signInWithGoogle() {
        error = null
        busy = true

        scope.launch {
            try {
                val credentialManager = CredentialManager.create(ctx)

                val googleIdOption = GetGoogleIdOption.Builder()
                    .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(true)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = credentialManager.getCredential(
                    context = ctx,
                    request = request
                )

                val credential = result.credential

                val googleCred =
                    if (credential is CustomCredential &&
                        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                    ) {
                        GoogleIdTokenCredential.createFrom(credential.data)
                    } else null

                val idToken = googleCred?.idToken

                if (idToken.isNullOrBlank()) {
                    error = "Google sign-in failed."
                    return@launch
                }

                val ses = SupabaseAuthService.signInWithGoogleIdToken(idToken)
                ses.accessToken?.let {
                    handleSuccessfulSession(
                        token = it,
                        displayNameHint = googleCred?.displayName,
                        avatarUrlHint = googleCred?.profilePictureUri?.toString()
                    )
                } ?: run {
                    error = "Invalid login response."
                }
            } catch (e: GetCredentialException) {
                error = e.message
            } catch (t: Throwable) {
                error = t.message ?: "Google sign-in failed."
            } finally {
                busy = false
            }
        }
    }

    if (showForgotDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!forgotBusy) {
                    showForgotDialog = false
                    forgotError = null
                }
            },
            title = { Text("Reset password") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter your email and we’ll send you a reset link.")
                    OutlinedTextField(
                        value = forgotEmail,
                        onValueChange = { forgotEmail = it },
                        label = { Text("Email") },
                        singleLine = true,
                        enabled = !forgotBusy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    forgotError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !forgotBusy,
                    onClick = {
                        val e = forgotEmail.trim()
                        if (e.isBlank()) {
                            forgotError = "Please enter your email."
                            return@TextButton
                        }

                        forgotBusy = true
                        forgotError = null

                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    SupabaseAuthService.requestPasswordReset(
                                        email = e,
                                        redirectTo = PASSWORD_RECOVERY_REDIRECT_URL
                                    )
                                }
                                showForgotDialog = false
                                snackbarHostState.showSnackbar("Password reset email sent (if the account exists).")
                            } catch (t: Throwable) {
                                forgotError = t.message ?: "Failed to send reset email."
                            } finally {
                                forgotBusy = false
                            }
                        }
                    }
                ) { Text(if (forgotBusy) "Sending." else "Send link") }
            },
            dismissButton = {
                TextButton(
                    enabled = !forgotBusy,
                    onClick = {
                        showForgotDialog = false
                        forgotError = null
                    }
                ) { Text("Cancel") }
            }
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // App logo (app/src/main/res/drawable/logo.png)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.logo),
                    contentDescription = "MigraineMe",
                    modifier = Modifier.size(110.dp)
                )
            }

            if (!showEmailForm) {
                OutlinedButton(
                    onClick = { showEmailForm = true },
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Email,
                            contentDescription = "Email",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Continue with email")
                    }
                }

                OutlinedButton(
                    onClick = { signInWithGoogle() },
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_google_logo),
                            contentDescription = "Google",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Continue with Google")
                    }
                }

                Divider()

                TextButton(
                    onClick = onNavigateToSignUp,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Create account")
                }
            }

            if (showEmailForm) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                TextButton(
                    enabled = !busy,
                    onClick = {
                        forgotEmail = email.trim()
                        forgotError = null
                        showForgotDialog = true
                    }
                ) {
                    Text("Forgot password?")
                }

                Button(
                    onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            try {
                                val ses = SupabaseAuthService.signInWithEmail(
                                    email.trim(),
                                    password
                                )
                                ses.accessToken?.let {
                                    handleSuccessfulSession(
                                        token = it,
                                        displayNameHint = null,
                                        avatarUrlHint = null
                                    )
                                } ?: run {
                                    error = "Invalid login response."
                                }
                            } catch (t: Throwable) {
                                error = t.message ?: "Login failed."
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Continue")
                }

                TextButton(
                    onClick = { showEmailForm = false },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Back")
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
