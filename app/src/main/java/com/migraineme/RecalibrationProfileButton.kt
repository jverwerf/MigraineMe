package com.migraineme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * "Re-assess my profile" button for the ProfileScreen.
 *
 * Invokes the recalibrate edge function and navigates to the review screen.
 *
 * ADD THIS to ProfileScreen.kt right after the AiMigraineProfileCard:
 *
 *     if (aiProfile != null) {
 *         AiMigraineProfileCard(aiProfile!!)
 *
 *         RecalibrationProfileButton(
 *             onNavigateToReview = { navController.navigate("recalibration_review") }
 *         )
 *     }
 */
@Composable
fun RecalibrationProfileButton(
    onNavigateToReview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = {
                running = true
                statusMessage = null
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            runRecalibration(ctx)
                        }
                        when (result.first) {
                            "ok" -> {
                                statusMessage = null
                                onNavigateToReview()
                            }
                            "no_profile" -> {
                                statusMessage = "Please complete your profile setup first — we need your migraine profile to generate personalised suggestions."
                            }
                            "insufficient_data" -> {
                                statusMessage = "No learning available yet — we need at least 5 logged migraines to spot patterns (you have ${result.second} so far). Keep logging and we'll have suggestions for you soon!"
                            }
                            "cooldown" -> {
                                statusMessage = "Your next re-assessment is available on ${result.second}. We space these out monthly so we can properly measure how changes are working for you."
                            }
                            else -> {
                                statusMessage = result.second
                            }
                        }
                    } catch (e: Exception) {
                        statusMessage = "Something went wrong: ${e.message}"
                    } finally {
                        running = false
                    }
                }
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
            shape = RoundedCornerShape(12.dp),
        ) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
                Spacer(Modifier.width(8.dp))
                Text("Analysing your data...")
            } else {
                Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Re-assess my profile")
            }
        }

        if (statusMessage != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardContainer),
                shape = AppTheme.BaseCardShape,
                border = AppTheme.BaseCardBorder,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        statusMessage!!,
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private suspend fun runRecalibration(context: android.content.Context): Pair<String, String> {
    val accessToken = SessionStore.getValidAccessToken(context.applicationContext)
        ?: return "error" to "Not authenticated"

    val client = OkHttpClient.Builder()
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    val request = Request.Builder()
        .url("${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/recalibrate")
        .post("""{"mode": "profile_only"}""".toRequestBody("application/json".toMediaType()))
        .header("Authorization", "Bearer $accessToken")
        .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
        .build()

    client.newCall(request).execute().use { response ->
        val body = response.body?.string() ?: ""
        return if (response.isSuccessful) {
            try {
                val json = JSONObject(body)
                val status = json.optString("status", "unknown")
                // For cooldown, return the date; for insufficient_data, return count
                val detail = json.optString("next_recalibration_at", "")
                    .takeIf { it.isNotBlank() }
                    ?.substring(0, 10)
                    ?: json.optInt("migraine_count", 0).toString()
                status to detail
            } catch (_: Exception) {
                "error" to body
            }
        } else {
            "error" to "Error ${response.code}: $body"
        }
    }
}
