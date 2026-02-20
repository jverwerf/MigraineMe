// FILE: app/src/main/java/com/migraineme/ProfileScreen.kt
package com.migraineme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

@Composable
fun ProfileScreen(
    authVm: AuthViewModel = viewModel(),
    onBack: () -> Unit = {},
    onNavigateChangePassword: () -> Unit,
    onNavigateToRecalibrationReview: () -> Unit = {},
    onNavigateToPaywall: () -> Unit = {},
) {
    val auth by authVm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val profile = remember { mutableStateOf<SupabaseProfileService.Profile?>(null) }
    val profileError = remember { mutableStateOf<String?>(null) }
    val profileLoading = remember { mutableStateOf(false) }

    val isEditing = remember { mutableStateOf(false) }
    val editName = remember { mutableStateOf("") }
    val selectedMigraineType = remember { mutableStateOf<SupabaseProfileService.MigraineType?>(null) }
    val migraineMenuExpanded = remember { mutableStateOf(false) }

    val avatarBitmap = remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    val avatarUploadErrorDialog = remember { mutableStateOf<String?>(null) }
    val avatarUploading = remember { mutableStateOf(false) }

    val canChangePassword = remember { mutableStateOf(false) }

    // ── AI Setup Profile state ──
    var aiProfile by remember { mutableStateOf<JsonObject?>(null) }
    var aiProfileLoading by remember { mutableStateOf(false) }

    LaunchedEffect(auth.accessToken, auth.userId) {
        val token = auth.accessToken
        if (token.isNullOrBlank()) {
            canChangePassword.value = false
            return@LaunchedEffect
        }
        val storedProvider = SessionStore.readAuthProvider(context)
        if (!storedProvider.isNullOrBlank()) {
            canChangePassword.value = storedProvider == "email"
            return@LaunchedEffect
        }
        try {
            val user = withContext(Dispatchers.IO) { SupabaseAuthService.getUser(token) }
            canChangePassword.value = user.identities?.any { it.provider == "email" } == true
        } catch (_: Throwable) {
            canChangePassword.value = false
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val token = auth.accessToken
        val userId = auth.userId
        if (token.isNullOrBlank() || userId.isNullOrBlank()) {
            avatarUploadErrorDialog.value = "You must be signed in to update your profile picture."
            return@rememberLauncherForActivityResult
        }
        runCatching {
            val bmp = decodePreviewBitmap(context, uri, 512)
            if (bmp != null) avatarBitmap.value = bmp.asImageBitmap()
        }
        profileLoading.value = true
        profileError.value = null
        avatarUploading.value = true
        scope.launch {
            try {
                val publicUrl = withContext(Dispatchers.IO) {
                    uploadAvatarToSupabaseStorage(context, token, userId, uri)
                }
                val cacheBustedUrl = "$publicUrl?v=${System.currentTimeMillis()}"
                val updated = withContext(Dispatchers.IO) {
                    SupabaseProfileService.updateProfile(accessToken = token, userId = userId, displayName = null, avatarUrl = cacheBustedUrl, migraineType = null)
                }
                profile.value = updated
                runCatching {
                    val bmp = withContext(Dispatchers.IO) { URL(cacheBustedUrl).openStream().use { BitmapFactory.decodeStream(it) } }
                    avatarBitmap.value = bmp?.asImageBitmap()
                }
            } catch (t: Throwable) {
                avatarUploadErrorDialog.value = t.message ?: "Failed to upload profile picture."
            } finally {
                avatarUploading.value = false
                profileLoading.value = false
            }
        }
    }

    // Load profile
    LaunchedEffect(auth.accessToken, auth.userId) {
        val token = auth.accessToken
        val userId = auth.userId
        if (token.isNullOrBlank() || userId.isNullOrBlank()) return@LaunchedEffect
        profileLoading.value = true
        profileError.value = null
        try {
            val result = withContext(Dispatchers.IO) {
                SupabaseProfileService.ensureProfile(accessToken = token, userId = userId, displayNameHint = null, avatarUrlHint = null)
            }
            profile.value = result
            editName.value = result.displayName.orEmpty()
            selectedMigraineType.value = result.migraineType
        } catch (t: Throwable) {
            profileError.value = t.message
        } finally {
            profileLoading.value = false
        }
    }

    // Load AI setup profile
    LaunchedEffect(auth.accessToken, auth.userId) {
        val token = auth.accessToken
        val userId = auth.userId
        if (token.isNullOrBlank() || userId.isNullOrBlank()) return@LaunchedEffect
        aiProfileLoading = true
        try {
            val result = withContext(Dispatchers.IO) {
                val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
                val fetchUrl = "$baseUrl/rest/v1/ai_setup_profiles?user_id=eq.$userId&select=answers,ai_config,frequency,duration,experience,trigger_areas,clinical_assessment,summary,gender,age_range,trajectory,seasonal_pattern,tracks_cycle"
                val conn = (URL(fetchUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    val code = conn.responseCode
                    if (code in 200..299) {
                        val text = conn.inputStream.bufferedReader().readText()
                        val arr = Json.parseToJsonElement(text).jsonArray
                        arr.firstOrNull()?.jsonObject
                    } else null
                } finally {
                    conn.disconnect()
                }
            }
            aiProfile = result
        } catch (_: Throwable) {
            aiProfile = null
        } finally {
            aiProfileLoading = false
        }
    }

    // Load avatar
    LaunchedEffect(profile.value?.avatarUrl) {
        val url = profile.value?.avatarUrl?.trim()
        if (url.isNullOrBlank()) { avatarBitmap.value = null; return@LaunchedEffect }
        avatarBitmap.value = null
        try {
            val bmp = withContext(Dispatchers.IO) { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }
            avatarBitmap.value = bmp?.asImageBitmap()
        } catch (_: Throwable) { avatarBitmap.value = null }
    }

    fun saveInline() {
        val token = auth.accessToken ?: return
        val userId = auth.userId ?: return
        profileLoading.value = true
        profileError.value = null
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    SupabaseProfileService.updateProfile(accessToken = token, userId = userId, displayName = editName.value.trim().ifBlank { null }, avatarUrl = null, migraineType = selectedMigraineType.value)
                }
                profile.value = updated
                isEditing.value = false
            } catch (t: Throwable) { profileError.value = t.message } finally { profileLoading.value = false }
        }
    }

    fun cancelInline() {
        profile.value?.let { editName.value = it.displayName.orEmpty(); selectedMigraineType.value = it.migraineType }
        migraineMenuExpanded.value = false
        isEditing.value = false
    }

    // ── Dialogs ──
    if (avatarUploading.value) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            containerColor = Color(0xFF1E0A2E),
            titleContentColor = Color.White,
            textContentColor = AppTheme.BodyTextColor,
            title = { Text("Updating picture") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = AppTheme.AccentPurple)
                    Text("Uploading and updating profile…")
                }
            }
        )
    }

    avatarUploadErrorDialog.value?.let { msg ->
        AlertDialog(
            onDismissRequest = { avatarUploadErrorDialog.value = null },
            containerColor = Color(0xFF1E0A2E),
            titleContentColor = Color.White,
            textContentColor = AppTheme.BodyTextColor,
            title = { Text("Upload failed") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { avatarUploadErrorDialog.value = null }) { Text("OK", color = AppTheme.AccentPurple) } }
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // UI
    // ═══════════════════════════════════════════════════════════════

    val scrollState = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Profile Card ──
        val current = profile.value
        val headerName = current?.displayName?.takeIf { it.isNotBlank() } ?: "Name not set"

        Card(
            colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer),
            shape = AppTheme.BaseCardShape,
            border = AppTheme.BaseCardBorder,
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    // Avatar
                    Box(
                        Modifier.size(72.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(AppTheme.AccentPurple.copy(alpha = 0.3f), AppTheme.AccentPink.copy(alpha = 0.2f)))),
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = avatarBitmap.value
                        if (bmp != null) {
                            Image(bitmap = bmp, contentDescription = "Avatar", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
                        } else {
                            Text(headerName.trim().firstOrNull()?.uppercase() ?: "?", color = Color.White, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    Column(Modifier.weight(1f)) {
                        if (isEditing.value) {
                            OutlinedTextField(
                                value = editName.value, onValueChange = { editName.value = it },
                                label = { Text("Name", color = AppTheme.SubtleTextColor) },
                                singleLine = true,
                                enabled = auth.accessToken != null && !profileLoading.value,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AppTheme.AccentPurple, unfocusedBorderColor = AppTheme.TrackColor, cursorColor = AppTheme.AccentPurple),
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(headerName, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }

                        Spacer(Modifier.height(4.dp))

                        if (isEditing.value) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { migraineMenuExpanded.value = true },
                                enabled = auth.accessToken != null && !profileLoading.value,
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(selectedMigraineType.value?.label ?: "Select migraine type", style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            Text(current?.migraineType?.label ?: "Migraine type not set", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                        }

                        Spacer(Modifier.height(4.dp))
                        Text("ID: ${auth.userId?.take(8) ?: "—"}…", color = AppTheme.SubtleTextColor.copy(alpha = 0.4f), style = MaterialTheme.typography.labelSmall)
                    }

                    // Edit / Save / Cancel buttons
                    Column(horizontalAlignment = Alignment.End) {
                        if (isEditing.value) {
                            IconButton(onClick = { saveInline() }, enabled = auth.accessToken != null && !profileLoading.value, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Outlined.Check, "Save", tint = AppTheme.AccentPurple)
                            }
                            IconButton(onClick = { cancelInline() }, enabled = !profileLoading.value, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Outlined.Close, "Cancel", tint = AppTheme.SubtleTextColor)
                            }
                        } else {
                            IconButton(onClick = { isEditing.value = true }, enabled = auth.accessToken != null && !profileLoading.value, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Outlined.Edit, "Edit", tint = AppTheme.AccentPurple)
                            }
                        }
                    }
                }

                // Edit avatar button
                if (isEditing.value) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        enabled = auth.accessToken != null && !profileLoading.value,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.AccentPink.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPink),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.CameraAlt, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Change profile picture", style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Migraine type dropdown
                DropdownMenu(expanded = migraineMenuExpanded.value, onDismissRequest = { migraineMenuExpanded.value = false }) {
                    DropdownMenuItem(text = { Text("Not set") }, onClick = { selectedMigraineType.value = null; migraineMenuExpanded.value = false })
                    SupabaseProfileService.MigraineType.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = { selectedMigraineType.value = option; migraineMenuExpanded.value = false })
                    }
                }
            }
        }

        // ── Error ──
        profileError.value?.let {
            Text(it, color = Color(0xFFEF5350), style = MaterialTheme.typography.bodySmall)
        }

        // ── Subscription ──
        SubscriptionCard(onNavigateToPaywall = onNavigateToPaywall)

        // ── AI Migraine Profile ──
        if (aiProfileLoading) {
            Card(colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer), shape = AppTheme.BaseCardShape, border = AppTheme.BaseCardBorder) {
                Row(Modifier.padding(20.dp).fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = AppTheme.AccentPurple, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Loading migraine profile…", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (aiProfile != null) {
            AiMigraineProfileCard(aiProfile!!)

            RecalibrationProfileButton(
                onNavigateToReview = onNavigateToRecalibrationReview
            )
        }

        // ── Change Password ──
        if (canChangePassword.value) {
            OutlinedButton(
                onClick = { onNavigateChangePassword() },
                enabled = auth.accessToken != null && !profileLoading.value,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple)
            ) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Change password")
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// AI Migraine Profile Card
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun AiMigraineProfileCard(data: JsonObject) {
    val summary = data["summary"]?.jsonPrimitive?.contentOrNull
    val clinicalAssessment = data["clinical_assessment"]?.jsonPrimitive?.contentOrNull
    val frequency = data["frequency"]?.jsonPrimitive?.contentOrNull
    val duration = data["duration"]?.jsonPrimitive?.contentOrNull
    val experience = data["experience"]?.jsonPrimitive?.contentOrNull
    val trajectory = data["trajectory"]?.jsonPrimitive?.contentOrNull
    val gender = data["gender"]?.jsonPrimitive?.contentOrNull
    val ageRange = data["age_range"]?.jsonPrimitive?.contentOrNull
    val seasonalPattern = data["seasonal_pattern"]?.jsonPrimitive?.contentOrNull
    val tracksCycle = data["tracks_cycle"]?.jsonPrimitive?.booleanOrNull
    val triggerAreas = try {
        data["trigger_areas"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
    } catch (_: Exception) { null }

    val answers = try { data["answers"]?.jsonObject } catch (_: Exception) { null }
    val freeText = answers?.get("free_text")?.jsonPrimitive?.contentOrNull

    var expanded by remember { mutableStateOf(true) }

    Card(
        colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer),
        shape = AppTheme.BaseCardShape,
        border = AppTheme.BaseCardBorder,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // Header
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    Modifier.size(38.dp)
                        .background(Brush.linearGradient(listOf(AppTheme.AccentPink.copy(alpha = 0.3f), AppTheme.AccentPurple.copy(alpha = 0.2f))), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Psychology, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Your Migraine Profile", color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                    Text("From AI setup questionnaire", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = AppTheme.SubtleTextColor, modifier = Modifier.size(20.dp))
            }

            AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                    // Summary
                    if (!summary.isNullOrBlank()) {
                        Row(
                            Modifier.fillMaxWidth()
                                .background(AppTheme.AccentPurple.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Outlined.AutoAwesome, null, tint = AppTheme.AccentPink, modifier = Modifier.size(18.dp))
                            Text(summary, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    // Quick stats row
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (frequency != null) ProfileStatChip(Icons.Outlined.CalendarMonth, frequency, Modifier.weight(1f))
                        if (duration != null) ProfileStatChip(Icons.Outlined.Timer, duration, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (experience != null) ProfileStatChip(Icons.Outlined.History, experience, Modifier.weight(1f))
                        if (trajectory != null) ProfileStatChip(Icons.Outlined.TrendingUp, trajectory, Modifier.weight(1f))
                    }

                    // Demographics
                    val demoItems = listOfNotNull(
                        gender?.let { "Gender" to it },
                        ageRange?.let { "Age" to it },
                        seasonalPattern?.let { "Seasonal" to it },
                        if (tracksCycle == true) "Cycle" to "Tracking" else null,
                    )
                    if (demoItems.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            demoItems.forEach { (label, value) ->
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(AppTheme.TrackColor.copy(alpha = 0.4f))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(value, color = Color.White, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                                        Text(label, color = AppTheme.SubtleTextColor.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }

                    // Trigger areas
                    if (!triggerAreas.isNullOrEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Trigger areas", color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                triggerAreas.forEach { area ->
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(triggerAreaColor(area).copy(alpha = 0.15f))
                                            .border(1.dp, triggerAreaColor(area).copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(area, color = triggerAreaColor(area), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                                    }
                                }
                            }
                        }
                    }

                    // Clinical assessment
                    if (!clinicalAssessment.isNullOrBlank()) {
                        var assessmentExpanded by remember { mutableStateOf(false) }
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF1A1A2E).copy(alpha = 0.9f))
                                .border(1.dp, AppTheme.AccentPink.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                .clickable { assessmentExpanded = !assessmentExpanded }
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.MedicalInformation, null, tint = AppTheme.AccentPink, modifier = Modifier.size(16.dp))
                                Text("AI Clinical Assessment", color = Color.White, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                                Icon(if (assessmentExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = AppTheme.SubtleTextColor, modifier = Modifier.size(16.dp))
                            }
                            AnimatedVisibility(visible = assessmentExpanded) {
                                Text(clinicalAssessment, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }

                    // Free text notes
                    if (!freeText.isNullOrBlank()) {
                        Column(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(AppTheme.TrackColor.copy(alpha = 0.3f))
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Outlined.FormatQuote, null, tint = AppTheme.SubtleTextColor, modifier = Modifier.size(16.dp))
                                Text("Your notes", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(freeText, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionCard(onNavigateToPaywall: () -> Unit) {
    val premiumState by PremiumManager.state.collectAsState()
    val context = LocalContext.current

    if (premiumState.isLoading) return

    Card(
        colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer),
        shape = AppTheme.BaseCardShape,
        border = AppTheme.BaseCardBorder,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // Header
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    Modifier.size(38.dp)
                        .background(
                            Brush.linearGradient(
                                listOf(AppTheme.AccentPurple.copy(alpha = 0.3f), AppTheme.AccentPink.copy(alpha = 0.2f))
                            ),
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.WorkspacePremium, null,
                        tint = Color.White, modifier = Modifier.size(20.dp)
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "Subscription",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        when (premiumState.tier) {
                            PremiumTier.PREMIUM -> premiumState.planType?.replaceFirstChar { it.uppercase() }?.let { "$it plan" } ?: "Premium"
                            PremiumTier.TRIAL -> "${premiumState.trialDaysRemaining} days left on trial"
                            PremiumTier.FREE -> "Free plan"
                        },
                        color = when (premiumState.tier) {
                            PremiumTier.PREMIUM -> AppTheme.AccentPurple
                            PremiumTier.TRIAL -> if (premiumState.isTrialUrgent) Color(0xFFFFB74D) else AppTheme.AccentPurple
                            PremiumTier.FREE -> AppTheme.SubtleTextColor
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }

            when (premiumState.tier) {
                PremiumTier.PREMIUM -> {
                    // ── Premium: show plan details + manage ──
                    val expiryText = premiumState.subscriptionExpiryDate?.let { raw ->
                        try {
                            val instant = java.time.Instant.parse(raw)
                            val local = instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                            "Renews ${local.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))}"
                        } catch (_: Exception) { null }
                    }

                    if (expiryText != null) {
                        Row(
                            Modifier.fillMaxWidth()
                                .background(AppTheme.AccentPurple.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.CalendarMonth, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(16.dp))
                            Text(expiryText, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://play.google.com/store/account/subscriptions")
                                )
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.4f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple)
                    ) {
                        Icon(Icons.Outlined.OpenInNew, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Manage subscription", style = MaterialTheme.typography.labelMedium)
                    }
                }

                PremiumTier.TRIAL, PremiumTier.FREE -> {
                    // ── Free / Trial: feature teaser + upgrade ──
                    val features = listOf(
                        Icons.Outlined.Analytics to "Full insights & spider charts",
                        Icons.Outlined.Timeline to "7-day risk forecast",
                        Icons.Outlined.Psychology to "AI calibration",
                        Icons.Outlined.Description to "PDF reports for your doctor",
                    )

                    features.forEach { (icon, label) ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(icon, null, tint = AppTheme.AccentPurple.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                            Text(label, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    Button(
                        onClick = onNavigateToPaywall,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Outlined.WorkspacePremium, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (premiumState.tier == PremiumTier.TRIAL) "Upgrade now" else "Unlock Premium",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileStatChip(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(AppTheme.TrackColor.copy(alpha = 0.4f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(16.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun triggerAreaColor(area: String): Color = when (area.lowercase()) {
    "sleep" -> Color(0xFF7C4DFF)
    "stress" -> Color(0xFFEF5350)
    "weather" -> Color(0xFF4FC3F7)
    "screen time" -> Color(0xFFFFB74D)
    "diet" -> Color(0xFF81C784)
    "exercise" -> Color(0xFFFF8A65)
    "hormones" -> Color(0xFFCE93D8)
    "environment" -> Color(0xFF4DD0E1)
    "physical" -> Color(0xFFA1887F)
    else -> Color(0xFFB97BFF)
}

// ═══════════════════════════════════════════════════════════════════════════
// Helper functions (unchanged)
// ═══════════════════════════════════════════════════════════════════════════

private fun decodePreviewBitmap(
    context: android.content.Context,
    uri: Uri,
    maxDim: Int
): Bitmap? {
    val resolver = context.contentResolver
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val srcW = bounds.outWidth
    val srcH = bounds.outHeight
    if (srcW <= 0 || srcH <= 0) return null
    var sample = 1
    while ((srcW / sample) > maxDim * 2 || (srcH / sample) > maxDim * 2) {
        sample *= 2
        if (sample >= 128) break
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = max(1, sample) }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
    val maxSide = max(decoded.width, decoded.height)
    if (maxSide <= maxDim) return decoded
    val scale = maxDim.toFloat() / maxSide.toFloat()
    val newW = (decoded.width * scale).toInt().coerceAtLeast(1)
    val newH = (decoded.height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(decoded, newW, newH, true)
    if (scaled !== decoded) decoded.recycle()
    return scaled
}

private fun uploadAvatarToSupabaseStorage(
    context: android.content.Context,
    accessToken: String,
    userId: String,
    uri: Uri
): String {
    val maxOutputBytes = 2 * 1024 * 1024
    val maxInputBytes = 10 * 1024 * 1024
    val maxDim = 1024

    val resolver = context.contentResolver
    val rawMime = resolver.getType(uri)?.lowercase()
    val allowedMimes = setOf("image/jpeg", "image/jpg", "image/png", "image/webp")
    if (rawMime != null && rawMime !in allowedMimes) {
        throw IllegalStateException("Please choose an image (JPG/PNG/WebP).")
    }

    val declaredSize = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getLong(idx) else null
        }
    }.getOrNull()

    if (declaredSize != null && declaredSize > maxInputBytes) {
        throw IllegalStateException("Image is too large. Please choose an image under 10 MB.")
    }

    val originalBytes = readAllBytesWithLimit(resolver, uri, maxInputBytes)
        ?: throw IllegalStateException("Could not read selected image.")

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, bounds)
    val srcW = bounds.outWidth
    val srcH = bounds.outHeight
    if (srcW <= 0 || srcH <= 0) throw IllegalStateException("Selected file is not a valid image.")

    val sample = computePowerOfTwoSampleSize(srcW, srcH, maxDim * 2)
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
    val decoded: Bitmap = BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size, decodeOpts)
        ?: throw IllegalStateException("Could not decode selected image.")

    val scaled = scaleDown(decoded, maxDim)
    if (scaled !== decoded) decoded.recycle()
    val jpegBytes = compressJpegUnderLimit(scaled, maxOutputBytes)
    scaled.recycle()

    val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    val anonKey = BuildConfig.SUPABASE_ANON_KEY
    val bucket = "avatars"
    val objectPath = "$userId.jpg"
    val putUrl = "$baseUrl/storage/v1/object/$bucket/$objectPath?upsert=true"
    val conn = (URL(putUrl).openConnection() as HttpURLConnection).apply {
        requestMethod = "PUT"
        doOutput = true
        setRequestProperty("Authorization", "Bearer $accessToken")
        setRequestProperty("apikey", anonKey)
        setRequestProperty("Content-Type", "image/jpeg")
        setRequestProperty("Content-Length", jpegBytes.size.toString())
    }
    try {
        conn.outputStream.use { os: OutputStream -> os.write(jpegBytes); os.flush() }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = runCatching { conn.errorStream?.readBytes()?.toString(Charsets.UTF_8) }.getOrNull()
            throw IllegalStateException("Upload failed ($code). ${err ?: ""}".trim())
        }
    } finally { conn.disconnect() }
    return "$baseUrl/storage/v1/object/public/$bucket/$objectPath"
}

private fun readAllBytesWithLimit(resolver: android.content.ContentResolver, uri: Uri, maxBytes: Int): ByteArray? {
    resolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(8 * 1024)
        val baos = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val r = input.read(buffer)
            if (r <= 0) break
            total += r
            if (total > maxBytes) throw IllegalStateException("Image is too large. Please choose an image under 10 MB.")
            baos.write(buffer, 0, r)
        }
        return baos.toByteArray()
    }
    return null
}

private fun computePowerOfTwoSampleSize(srcW: Int, srcH: Int, targetMaxDim: Int): Int {
    var inSampleSize = 1
    if (srcW <= 0 || srcH <= 0) return 1
    while ((srcW / inSampleSize) > targetMaxDim || (srcH / inSampleSize) > targetMaxDim) {
        inSampleSize *= 2
        if (inSampleSize >= 128) break
    }
    return max(1, inSampleSize)
}

private fun scaleDown(bmp: Bitmap, maxDim: Int): Bitmap {
    val w = bmp.width; val h = bmp.height; val maxSide = max(w, h)
    if (maxSide <= maxDim) return bmp
    val scale = maxDim.toFloat() / maxSide.toFloat()
    val newW = (w * scale).toInt().coerceAtLeast(1)
    val newH = (h * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bmp, newW, newH, true)
}

private fun compressJpegUnderLimit(bmp: Bitmap, maxBytes: Int): ByteArray {
    var quality = 92
    while (quality >= 10) {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        val bytes = baos.toByteArray()
        if (bytes.size <= maxBytes) return bytes
        quality -= 10
    }
    val baos = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 10, baos)
    return baos.toByteArray()
}



