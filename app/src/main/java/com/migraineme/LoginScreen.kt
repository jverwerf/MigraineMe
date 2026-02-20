// FILE: app/src/main/java/com/migraineme/LoginScreen.kt
package com.migraineme

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var passwordVisible by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var showForgotDialog by remember { mutableStateOf(false) }
    var forgotEmail by remember { mutableStateOf("") }
    var forgotBusy by remember { mutableStateOf(false) }
    var forgotError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    EdgeFunctionsService().upsertMetricSetting(
                        context = appCtx,
                        metric = "user_location_daily",
                        enabled = true
                    )
                }
                LocationDailySyncWorker.runOnceNow(appCtx)
                onLoggedIn()
            }
        } else {
            onLoggedIn()
        }
    }

    LaunchedEffect(needsPermissionPrompt) {
        if (!needsPermissionPrompt) return@LaunchedEffect
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
        val userId = JwtUtils.extractUserIdFromAccessToken(token)
        if (userId.isNullOrBlank()) {
            scope.launch { snackbarHostState.showSnackbar("Login failed: no userId in access token") }
            return
        }
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
                withContext(Dispatchers.IO) {
                    SupabaseProfileService.ensureProfile(
                        accessToken = token,
                        userId = userId,
                        displayNameHint = displayNameHint,
                        avatarUrlHint = avatarUrlHint
                    )
                }
            } catch (t: Throwable) {
                Log.w(LOG_TAG, "Profile hydration failed (non-blocking)", t)
            }

            MetricsSyncManager.onLogin(appCtx, token, snackbarHostState)

            val fine = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

            if (fine || coarse) {
                withContext(Dispatchers.IO) {
                    EdgeFunctionsService().upsertMetricSetting(context = appCtx, metric = "user_location_daily", enabled = true)
                }
                LocationDailySyncWorker.runOnceNow(appCtx)
                onLoggedIn()
            } else {
                needsPermissionPrompt = true
            }
        }
    }

    fun parseFragmentParams(uri: Uri): Map<String, String> {
        val frag = uri.fragment ?: return emptyMap()
        if (frag.isBlank()) return emptyMap()
        return frag.split("&").mapNotNull { kv ->
            val idx = kv.indexOf("=")
            if (idx <= 0) return@mapNotNull null
            Uri.decode(kv.substring(0, idx)) to Uri.decode(kv.substring(idx + 1))
        }.toMap()
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
        if (!errDesc.isNullOrBlank()) { error = errDesc; return true }
        if (accessToken.isNullOrBlank()) { error = "Facebook sign-in failed (missing access token)."; return true }
        handleSuccessfulSession(token = accessToken, refreshToken = refreshToken, expiresIn = expiresIn, providerHint = "facebook")
        return true
    }

    LaunchedEffect(Unit) { tryCompleteSupabaseOAuthReturn() }

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
                val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()
                val result = credentialManager.getCredential(context = ctx, request = request)
                val credential = result.credential
                val googleCred = if (credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) GoogleIdTokenCredential.createFrom(credential.data) else null
                val idToken = googleCred?.idToken
                if (idToken.isNullOrBlank()) { error = "Google sign-in failed."; return@launch }
                val ses = SupabaseAuthService.signInWithGoogleIdToken(idToken)
                ses.accessToken?.let {
                    handleSuccessfulSession(token = it, refreshToken = ses.refreshToken, expiresIn = ses.expiresIn,
                        displayNameHint = googleCred?.displayName, avatarUrlHint = googleCred?.profilePictureUri?.toString(), providerHint = "google")
                } ?: run { error = "Invalid login response." }
            } catch (e: GetCredentialException) {
                error = e.message
            } catch (t: Throwable) {
                error = t.message ?: "Google sign-in failed."
            } finally {
                busy = false
            }
        }
    }

    fun signInWithFacebook() {
        error = null
        val activity = ctx as? Activity ?: run { error = "Facebook sign-in unavailable."; return }
        FacebookAuthService().startAuth(activity)
    }

    // Forgot password dialog
    if (showForgotDialog) {
        AlertDialog(
            onDismissRequest = { if (!forgotBusy) { showForgotDialog = false; forgotError = null } },
            containerColor = Color(0xFF1E0A2E),
            title = { Text("Reset password", color = AppTheme.TitleColor, fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter your email and we'll send you a reset link.", color = AppTheme.BodyTextColor)
                    OutlinedTextField(
                        value = forgotEmail,
                        onValueChange = { forgotEmail = it },
                        label = { Text("Email") },
                        singleLine = true,
                        enabled = !forgotBusy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = themedTextFieldColors()
                    )
                    forgotError?.let {
                        Text(it, color = Color(0xFFE57373), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !forgotBusy,
                    onClick = {
                        val e = forgotEmail.trim()
                        if (e.isBlank()) { forgotError = "Please enter your email."; return@TextButton }
                        forgotBusy = true; forgotError = null
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    SupabaseAuthService.requestPasswordReset(email = e, redirectTo = PASSWORD_RECOVERY_REDIRECT_URL)
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
                ) { Text(if (forgotBusy) "Sending…" else "Send link", color = AppTheme.AccentPurple) }
            },
            dismissButton = {
                TextButton(enabled = !forgotBusy, onClick = { showForgotDialog = false; forgotError = null }) {
                    Text("Cancel", color = AppTheme.SubtleTextColor)
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF1A0028), Color(0xFF2A003D), Color(0xFF12001C))
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(60.dp))

                // Logo
                Image(
                    painter = painterResource(id = R.drawable.logo),
                    contentDescription = "MigraineMe",
                    modifier = Modifier.size(90.dp)
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    "Welcome back",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Sign in to continue tracking",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(36.dp))

                // Social sign-in buttons
                AnimatedVisibility(
                    visible = !showEmailForm,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        AuthButton(
                            onClick = { showEmailForm = true },
                            enabled = !busy,
                            icon = {
                                Icon(Icons.Default.Email, contentDescription = "Email",
                                    modifier = Modifier.size(18.dp), tint = AppTheme.TitleColor)
                            },
                            text = "Continue with email"
                        )

                        AuthButton(
                            onClick = { signInWithFacebook() },
                            enabled = !busy,
                            icon = {
                                Image(
                                    painter = painterResource(id = R.drawable.facebook_logo_primary),
                                    contentDescription = "Facebook",
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            text = "Continue with Facebook"
                        )

                        AuthButton(
                            onClick = { signInWithGoogle() },
                            enabled = !busy,
                            icon = {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_google_logo),
                                    contentDescription = "Google",
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            text = "Continue with Google"
                        )

                        if (busy) {
                            Spacer(Modifier.height(4.dp))
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = AppTheme.AccentPurple,
                                strokeWidth = 2.dp
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

                        Spacer(Modifier.height(4.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text("Don't have an account?", color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onNavigateToSignUp, enabled = !busy) {
                                Text("Sign up", color = AppTheme.AccentPurple,
                                    fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                // Email form
                AnimatedVisibility(
                    visible = showEmailForm,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Email") },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            colors = themedTextFieldColors()
                        )

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            singleLine = true,
                            enabled = !busy,
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = "Toggle password",
                                        tint = AppTheme.SubtleTextColor
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = themedTextFieldColors()
                        )

                        TextButton(
                            enabled = !busy,
                            onClick = { forgotEmail = email.trim(); forgotError = null; showForgotDialog = true },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Forgot password?", color = AppTheme.AccentPurple,
                                style = MaterialTheme.typography.bodySmall)
                        }

                        error?.let {
                            Text(it, color = Color(0xFFE57373),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth())
                        }

                        Button(
                            onClick = {
                                busy = true; error = null
                                scope.launch {
                                    try {
                                        val ses = SupabaseAuthService.signInWithEmail(email.trim(), password)
                                        ses.accessToken?.let {
                                            handleSuccessfulSession(token = it, refreshToken = ses.refreshToken,
                                                expiresIn = ses.expiresIn, providerHint = "email")
                                        } ?: run { error = "Invalid login response." }
                                    } catch (t: Throwable) {
                                        error = t.message ?: "Login failed."
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppTheme.AccentPurple,
                                disabledContainerColor = AppTheme.AccentPurple.copy(alpha = 0.4f)
                            )
                        ) {
                            if (busy) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Text("Sign in", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            }
                        }

                        TextButton(
                            onClick = { showEmailForm = false; error = null },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("← Back to sign in options", color = AppTheme.SubtleTextColor)
                        }
                    }
                }

                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun AuthButton(
    onClick: () -> Unit,
    enabled: Boolean,
    icon: @Composable () -> Unit,
    text: String
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color(0xFF2A0C3C).copy(alpha = 0.65f),
            disabledContainerColor = Color(0xFF2A0C3C).copy(alpha = 0.3f)
        ),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            icon()
            Spacer(Modifier.width(12.dp))
            Text(text, color = AppTheme.BodyTextColor, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun themedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AppTheme.AccentPurple,
    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
    focusedLabelColor = AppTheme.AccentPurple,
    unfocusedLabelColor = AppTheme.SubtleTextColor,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = AppTheme.AccentPurple
)
