// FILE: app/src/main/java/com/migraineme/ProfileScreen.kt
package com.migraineme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
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
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

@Composable
fun ProfileScreen(
    authVm: AuthViewModel = viewModel(),
    onNavigateChangePassword: () -> Unit
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

    // NEW: forces avatar reload even when avatar_url string doesn't change
    val avatarReloadNonce = remember { mutableStateOf(0L) }

    val canChangePassword = remember { mutableStateOf(false) }

    LaunchedEffect(auth.accessToken) {
        val token = auth.accessToken
        if (token.isNullOrBlank()) {
            canChangePassword.value = false
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

        profileLoading.value = true
        profileError.value = null

        scope.launch {
            try {
                val publicUrl = withContext(Dispatchers.IO) {
                    uploadAvatarToSupabaseStorage(context, token, userId, uri)
                }

                val updated = withContext(Dispatchers.IO) {
                    SupabaseProfileService.updateProfile(
                        accessToken = token,
                        userId = userId,
                        displayName = null,
                        avatarUrl = publicUrl,
                        migraineType = null
                    )
                }

                profile.value = updated

                // NEW: avatar URL string may be the same; force a reload of bitmap anyway.
                avatarReloadNonce.value = System.currentTimeMillis()
            } catch (t: Throwable) {
                avatarUploadErrorDialog.value = t.message ?: "Failed to upload profile picture."
            } finally {
                profileLoading.value = false
            }
        }
    }

    LaunchedEffect(auth.accessToken, auth.userId) {
        val token = auth.accessToken
        val userId = auth.userId
        if (token.isNullOrBlank() || userId.isNullOrBlank()) return@LaunchedEffect

        profileLoading.value = true
        profileError.value = null

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
            selectedMigraineType.value = result.migraineType
        } catch (t: Throwable) {
            profileError.value = t.message
        } finally {
            profileLoading.value = false
        }
    }

    // UPDATED: include nonce so we reload even if avatar_url is unchanged; add cache-buster when fetching.
    LaunchedEffect(profile.value?.avatarUrl, avatarReloadNonce.value) {
        val url = profile.value?.avatarUrl?.trim()
        if (url.isNullOrBlank()) {
            avatarBitmap.value = null
            return@LaunchedEffect
        }

        avatarBitmap.value = null
        try {
            val cb = System.currentTimeMillis()
            val fetchUrl = if (url.contains("?")) "$url&cb=$cb" else "$url?cb=$cb"

            val bmp = withContext(Dispatchers.IO) {
                URL(fetchUrl).openStream().use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }
            avatarBitmap.value = bmp?.asImageBitmap()
        } catch (_: Throwable) {
            avatarBitmap.value = null
        }
    }

    fun saveInline() {
        val token = auth.accessToken ?: return
        val userId = auth.userId ?: return

        profileLoading.value = true
        profileError.value = null

        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    SupabaseProfileService.updateProfile(
                        accessToken = token,
                        userId = userId,
                        displayName = editName.value.trim().ifBlank { null },
                        avatarUrl = null,
                        migraineType = selectedMigraineType.value
                    )
                }
                profile.value = updated
                isEditing.value = false
            } catch (t: Throwable) {
                profileError.value = t.message
            } finally {
                profileLoading.value = false
            }
        }
    }

    fun cancelInline() {
        profile.value?.let {
            editName.value = it.displayName.orEmpty()
            selectedMigraineType.value = it.migraineType
        }
        migraineMenuExpanded.value = false
        isEditing.value = false
    }

    avatarUploadErrorDialog.value?.let { msg ->
        AlertDialog(
            onDismissRequest = { avatarUploadErrorDialog.value = null },
            title = { Text("Upload failed") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { avatarUploadErrorDialog.value = null }) {
                    Text("OK")
                }
            }
        )
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

        Text("Profile details", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))

        val current = profile.value
        val headerName = current?.displayName?.takeIf { it.isNotBlank() } ?: "Name not set"
        val headerMigraine = current?.migraineType?.label ?: "Migraine type not set"
        val userIdText = auth.userId ?: "Not signed in"

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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

                if (isEditing.value) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        enabled = auth.accessToken != null && !profileLoading.value,
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Adjust image")
                    }
                }
            }

            Spacer(Modifier.width(14.dp))

            Box(modifier = Modifier.weight(1f)) {
                Column(modifier = Modifier.padding(end = 96.dp)) {
                    if (isEditing.value) {
                        OutlinedTextField(
                            value = editName.value,
                            onValueChange = { editName.value = it },
                            label = { Text("Name") },
                            singleLine = true,
                            enabled = auth.accessToken != null && !profileLoading.value,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            text = headerName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    if (isEditing.value) {
                        OutlinedButton(
                            onClick = { migraineMenuExpanded.value = true },
                            enabled = auth.accessToken != null && !profileLoading.value,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(selectedMigraineType.value?.label ?: "Select migraine type")
                        }
                    } else {
                        Text(
                            text = headerMigraine,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "User ID: $userIdText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    modifier = Modifier.align(Alignment.TopEnd),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isEditing.value) {
                        IconButton(
                            onClick = { saveInline() },
                            enabled = auth.accessToken != null && !profileLoading.value
                        ) {
                            Icon(Icons.Outlined.Check, contentDescription = "Save")
                        }
                        IconButton(
                            onClick = { cancelInline() },
                            enabled = !profileLoading.value
                        ) {
                            Icon(Icons.Outlined.Close, contentDescription = "Cancel")
                        }
                    } else {
                        IconButton(
                            onClick = { isEditing.value = true },
                            enabled = auth.accessToken != null && !profileLoading.value
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Edit")
                        }
                    }
                }
            }
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

        profileError.value?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(20.dp))
        Divider()
        Spacer(Modifier.height(12.dp))

        if (canChangePassword.value) {
            OutlinedButton(
                onClick = { onNavigateChangePassword() },
                enabled = auth.accessToken != null && !profileLoading.value,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Change password")
            }
        }
    }
}

private fun uploadAvatarToSupabaseStorage(
    context: android.content.Context,
    accessToken: String,
    userId: String,
    uri: Uri
): String {
    val maxOutputBytes = 2 * 1024 * 1024 // 2 MB final upload
    val maxInputBytes = 10 * 1024 * 1024 // 10 MB safety cap
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
    if (srcW <= 0 || srcH <= 0) {
        throw IllegalStateException("Selected file is not a valid image.")
    }

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
        conn.outputStream.use { os: OutputStream ->
            os.write(jpegBytes)
            os.flush()
        }

        val code = conn.responseCode
        if (code !in 200..299) {
            val err = runCatching {
                conn.errorStream?.readBytes()?.toString(Charsets.UTF_8)
            }.getOrNull()
            throw IllegalStateException("Upload failed ($code). ${err ?: ""}".trim())
        }
    } finally {
        conn.disconnect()
    }

    return "$baseUrl/storage/v1/object/public/$bucket/$objectPath"
}

private fun readAllBytesWithLimit(
    resolver: android.content.ContentResolver,
    uri: Uri,
    maxBytes: Int
): ByteArray? {
    resolver.openInputStream(uri)?.use { input ->
        val buffer = ByteArray(8 * 1024)
        val baos = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val r = input.read(buffer)
            if (r <= 0) break
            total += r
            if (total > maxBytes) {
                throw IllegalStateException("Image is too large. Please choose an image under 10 MB.")
            }
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
    val w = bmp.width
    val h = bmp.height
    val maxSide = max(w, h)
    if (maxSide <= maxDim) return bmp

    val scale = maxDim.toFloat() / maxSide.toFloat()
    val newW = (w * scale).toInt().coerceAtLeast(1)
    val newH = (h * scale).toInt().coerceAtLeast(1)

    return Bitmap.createScaledBitmap(bmp, newW, newH, true)
}

private fun compressJpegUnderLimit(bmp: Bitmap, maxBytes: Int): ByteArray {
    val qualities = intArrayOf(90, 85, 80, 75, 70, 65, 60, 55, 50)

    for (q in qualities) {
        val baos = ByteArrayOutputStream()
        val ok = bmp.compress(Bitmap.CompressFormat.JPEG, q, baos)
        if (!ok) continue
        val out = baos.toByteArray()
        if (out.size <= maxBytes) return out
    }

    throw IllegalStateException("Image is too large even after compression. Please choose a smaller image.")
}
