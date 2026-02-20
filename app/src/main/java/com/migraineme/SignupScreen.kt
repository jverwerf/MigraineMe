// FILE: app/src/main/java/com/migraineme/SignupScreen.kt
package com.migraineme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SignupScreen(
    authVm: AuthViewModel,
    onSignedUpAndLoggedIn: () -> Unit,
    onNavigateToLogin: () -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmationSent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val scope = remember { CoroutineScope(Dispatchers.Main) }
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext

    val passwordsMatch = password == confirm || confirm.isEmpty()
    val passwordStrong = password.length >= 6

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
                "Create account",
                color = AppTheme.TitleColor,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Start tracking your migraines today",
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(36.dp))

            // Confirmation sent state
            AnimatedVisibility(visible = confirmationSent, enter = fadeIn(), exit = fadeOut()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A0C3C).copy(alpha = 0.8f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = AppTheme.AccentPurple,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            "Check your email",
                            color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            "We sent a confirmation link to $email.\nClick it to activate your account, then sign in.",
                            color = AppTheme.BodyTextColor,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = onNavigateToLogin,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                        ) {
                            Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Go to sign in", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        }
                    }
                }
            }

            AnimatedVisibility(visible = !confirmationSent, enter = fadeIn(), exit = fadeOut()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; error = null },
                        label = { Text("Email") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = themedTextFieldColors()
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; error = null },
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
                        supportingText = if (password.isNotEmpty() && !passwordStrong) {
                            { Text("At least 6 characters", color = Color(0xFFFFB74D), style = MaterialTheme.typography.labelSmall) }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                        colors = themedTextFieldColors()
                    )

                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it; error = null },
                        label = { Text("Confirm password") },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = if (confirmVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { confirmVisible = !confirmVisible }) {
                                Icon(
                                    if (confirmVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = "Toggle password",
                                    tint = AppTheme.SubtleTextColor
                                )
                            }
                        },
                        isError = confirm.isNotEmpty() && !passwordsMatch,
                        supportingText = if (confirm.isNotEmpty() && !passwordsMatch) {
                            { Text("Passwords don't match", color = Color(0xFFE57373), style = MaterialTheme.typography.labelSmall) }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                        colors = themedTextFieldColors()
                    )

                    error?.let {
                        Text(
                            it,
                            color = Color(0xFFE57373),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Button(
                        onClick = {
                            error = null
                            if (!passwordStrong) { error = "Password must be at least 6 characters."; return@Button }
                            if (password != confirm) { error = "Passwords do not match."; return@Button }
                            busy = true
                            scope.launch {
                                try {
                                    val ses = SupabaseAuthService.signUpWithEmail(email.trim(), password)
                                    val access = ses.accessToken

                                    android.util.Log.d("SignupScreen", "signUp response: accessToken=${if (access.isNullOrBlank()) "null/blank" else "present"}, refreshToken=${ses.refreshToken?.take(10)}, expiresIn=${ses.expiresIn}")

                                    if (!access.isNullOrBlank()) {
                                        // Email confirmation is disabled — user is immediately logged in
                                        val userId = JwtUtils.extractUserIdFromAccessToken(access)
                                        SessionStore.saveSession(
                                            context = appCtx,
                                            accessToken = access,
                                            userId = userId,
                                            provider = "email",
                                            refreshToken = ses.refreshToken,
                                            expiresIn = ses.expiresIn,
                                            obtainedAtMs = System.currentTimeMillis()
                                        )
                                        authVm.setSession(access, userId)
                                        onSignedUpAndLoggedIn()
                                    } else {
                                        // Email confirmation is enabled — show confirmation screen
                                        android.util.Log.d("SignupScreen", "No access token returned — email confirmation required, showing confirmation screen")
                                        confirmationSent = true
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.e("SignupScreen", "signUpWithEmail threw: ${e.javaClass.simpleName}: ${e.message}")
                                    error = e.message ?: "Sign up failed."
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && email.isNotBlank() && password.isNotBlank() && confirm.isNotBlank() && passwordsMatch && passwordStrong,
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
                            Text("Create account", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = Color.White.copy(alpha = 0.12f)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Already have an account?", color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onNavigateToLogin, enabled = !busy) {
                            Text("Sign in", color = AppTheme.AccentPurple, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
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
    cursorColor = AppTheme.AccentPurple,
    errorBorderColor = Color(0xFFE57373),
    errorLabelColor = Color(0xFFE57373),
    errorTextColor = Color.White,
    errorCursorColor = Color(0xFFE57373),
    errorTrailingIconColor = AppTheme.SubtleTextColor
)
