package com.migraineme

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
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
private const val LOG_TAG = "LoginScreen"

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

    var needsPermissionPrompt by remember { mutableStateOf(false) }

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
        Log.d(LOG_TAG, "permissionLauncher callback: grants=$grants")
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (granted) {
            Log.d(LOG_TAG, "Permission granted, enabling location metric")
            scope.launch {
                withContext(Dispatchers.IO) {
                    EdgeFunctionsService().upsertMetricSetting(
                        context = appCtx,
                        metric = "user_location_daily",
                        enabled = true
                    )
                }
                // Run now - worker's finally block will schedule next 9AM
                Log.d(LOG_TAG, "Starting location worker from permissionLauncher")
                LocationDailySyncWorker.runOnceNow(appCtx)
                LocationWatchdogWorker.schedule(appCtx)
                Log.d(LOG_TAG, "Calling onLoggedIn from permissionLauncher")
                onLoggedIn()
            }
        } else {
            Log.d(LOG_TAG, "Permission denied, calling onLoggedIn")
            onLoggedIn()
        }
    }

    // Only used to trigger permission prompt after login completes
    LaunchedEffect(needsPermissionPrompt) {
        if (!needsPermissionPrompt) return@LaunchedEffect
        Log.d(LOG_TAG, "Launching permission prompt")
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    fun handleSuccessfulSession(
        token: String,
        refreshToken: String? = null,
        expiresIn: Long? = null,
        displayNameHint: String? = null,
        avatarUrlHint: String? = null,
        providerHint: String? = null
    ) {
        Log.d(LOG_TAG, "handleSuccessfulSession called, provider=$providerHint")

        val userId = JwtUtils.extractUserIdFromAccessToken(token)
        if (userId.isNullOrBlank()) {
            Log.e(LOG_TAG, "No userId in access token")
            scope.launch {
                snackbarHostState.showSnackbar("Login failed: no userId in access token")
            }
            return
        }

        Log.d(LOG_TAG, "userId=$userId, saving session")
        SessionStore.saveSession(
            context = appCtx,
            accessToken = token,
            userId = userId,
            provider = providerHint,
            refreshToken = refreshToken,
            expiresIn = expiresIn,
            obtainedAtMs = System.currentTimeMillis()
        )
        authVm.setSession(token, userId)

        scope.launch {
            try {
                Log.d(LOG_TAG, "Ensuring profile")
                withContext(Dispatchers.IO) {
                    SupabaseProfileService.ensureProfile(
                        accessToken = token,
                        userId = userId,
                        displayNameHint = displayNameHint,
                        avatarUrlHint = avatarUrlHint
                    )
                }
                Log.d(LOG_TAG, "Profile ensured")
            } catch (t: Throwable) {
                Log.w(LOG_TAG, "Profile hydration failed (non-blocking)", t)
            }

            Log.d(LOG_TAG, "Calling MetricsSyncManager.onLogin")
            MetricsSyncManager.onLogin(appCtx, token, snackbarHostState)
            Log.d(LOG_TAG, "MetricsSyncManager.onLogin completed")

            // Check permissions and start worker
            val fine = ContextCompat.checkSelfPermission(
                appCtx,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val coarse = ContextCompat.checkSelfPermission(
                appCtx,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            Log.d(LOG_TAG, "Permissions: fine=$fine, coarse=$coarse")

            if (fine || coarse) {
                Log.d(LOG_TAG, "Permission already granted, enabling location metric and starting worker")
                withContext(Dispatchers.IO) {
                    EdgeFunctionsService().upsertMetricSetting(
                        context = appCtx,
                        metric = "user_location_daily",
                        enabled = true
                    )
                }
                // Run now - worker's finally block will schedule next 9AM
                LocationDailySyncWorker.runOnceNow(appCtx)
                LocationWatchdogWorker.schedule(appCtx)
                Log.d(LOG_TAG, "Calling onLoggedIn")
                onLoggedIn()
            } else {
                Log.d(LOG_TAG, "No permission, will prompt")
                needsPermissionPrompt = true
            }
        }
    }

    fun parseFragmentParams(uri: Uri): Map<String, String> {
        val frag = uri.fragment ?: return emptyMap()
        if (frag.isBlank()) return emptyMap()

        return frag.split("&")
            .mapNotNull { kv ->
                val idx = kv.indexOf("=")
                if (idx <= 0) return@mapNotNull null
                val k = Uri.decode(kv.substring(0, idx))
                val v = Uri.decode(kv.substring(idx + 1))
                k to v
            }
            .toMap()
    }

    fun tryCompleteSupabaseOAuthReturn(): Boolean {
        val prefs = ctx.getSharedPreferences("supabase_oauth", android.content.Context.MODE_PRIVATE)
        val last = prefs.getString("last_uri", null) ?: return false

        prefs.edit().remove("last_uri").apply()

        val uri = Uri.parse(last)
        val frag = parseFragmentParams(uri)

        val accessToken = frag["access_token"]
        val refreshToken = frag["refresh_token"]
        val expiresIn = frag["expires_in"]?.toLongOrNull()

        val errDesc = frag["error_description"] ?: frag["error"]

        if (!errDesc.isNullOrBlank()) {
            error = errDesc
            return true
        }

        if (accessToken.isNullOrBlank()) {
            error = "Facebook sign-in failed (missing access token)."
            return true
        }

        handleSuccessfulSession(
            token = accessToken,
            refreshToken = refreshToken,
            expiresIn = expiresIn,
            displayNameHint = null,
            avatarUrlHint = null,
            providerHint = "facebook"
        )
        return true
    }

    LaunchedEffect(Unit) {
        tryCompleteSupabaseOAuthReturn()
    }

    fun signInWithGoogle() {
        Log.d(LOG_TAG, "signInWithGoogle called")
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
                    Log.e(LOG_TAG, "Google sign-in failed: no idToken")
                    error = "Google sign-in failed."
                    return@launch
                }

                Log.d(LOG_TAG, "Got Google idToken, calling SupabaseAuthService.signInWithGoogleIdToken")
                val ses = SupabaseAuthService.signInWithGoogleIdToken(idToken)
                ses.accessToken?.let {
                    Log.d(LOG_TAG, "Got Supabase accessToken, calling handleSuccessfulSession")
                    handleSuccessfulSession(
                        token = it,
                        refreshToken = ses.refreshToken,
                        expiresIn = ses.expiresIn,
                        displayNameHint = googleCred?.displayName,
                        avatarUrlHint = googleCred?.profilePictureUri?.toString(),
                        providerHint = "google"
                    )
                } ?: run {
                    Log.e(LOG_TAG, "Invalid login response: no accessToken")
                    error = "Invalid login response."
                }
            } catch (e: GetCredentialException) {
                Log.e(LOG_TAG, "GetCredentialException", e)
                error = e.message
            } catch (t: Throwable) {
                Log.e(LOG_TAG, "Google sign-in error", t)
                error = t.message ?: "Google sign-in failed."
            } finally {
                busy = false
            }
        }
    }

    fun signInWithFacebook() {
        error = null

        val activity = ctx as? Activity
        if (activity == null) {
            error = "Facebook sign-in unavailable."
            return
        }

        FacebookAuthService().startAuth(activity)
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
                    Text("Enter your email and we'll send you a reset link.")
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
                    onClick = { signInWithFacebook() },
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
                            painter = painterResource(id = R.drawable.facebook_logo_primary),
                            contentDescription = "Facebook",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Continue with Facebook")
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
                                        refreshToken = ses.refreshToken,
                                        expiresIn = ses.expiresIn,
                                        displayNameHint = null,
                                        avatarUrlHint = null,
                                        providerHint = "email"
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
