package com.migraineme

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * One-time "I am 18 or over" confirmation before Community opens. The Online
 * Safety Act children's access assessment relies on Community being an
 * adults-only space; the self-declaration is stored on the profile
 * (profiles.community_age_confirmed_at) so it is asked once per account.
 * Mirrors components/community/CommunityAgeGate.tsx in the RN apps.
 */
object CommunityAgeGate {
    /** null = could not read (treat as confirmed so a network blip never locks the screen). */
    suspend fun isConfirmed(context: Context): Boolean? {
        return try {
            val token = SessionStore.getValidAccessToken(context) ?: return null
            val userId = SessionStore.readUserId(context) ?: JwtUtils.extractUserIdFromAccessToken(token) ?: return null
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/profiles?user_id=eq.$userId&select=community_age_confirmed_at"
            val request = okhttp3.Request.Builder().url(url).get()
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .build()
            val response = okhttp3.OkHttpClient().newCall(request).execute()
            if (!response.isSuccessful) { response.close(); return null }
            val body = response.body?.string() ?: return null
            response.close()
            // [{"community_age_confirmed_at":null}] vs [{"community_age_confirmed_at":"2026-…"}]
            !body.contains("\"community_age_confirmed_at\":null") && body.contains("community_age_confirmed_at")
        } catch (_: Exception) { null }
    }

    suspend fun confirm(context: Context): Boolean {
        return try {
            val token = SessionStore.getValidAccessToken(context) ?: return false
            val userId = SessionStore.readUserId(context) ?: JwtUtils.extractUserIdFromAccessToken(token) ?: return false
            val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/rest/v1/profiles?user_id=eq.$userId"
            val iso = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString()
            val request = okhttp3.Request.Builder().url(url)
                .patch("""{"community_age_confirmed_at": "$iso"}""".toRequestBody("application/json".toMediaType()))
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build()
            val response = okhttp3.OkHttpClient().newCall(request).execute()
            val ok = response.isSuccessful
            response.close()
            ok
        } catch (_: Exception) { false }
    }
}

@Composable
fun CommunityAgeGateCard(onConfirm: () -> Unit, onNotNow: () -> Unit, busy: Boolean) {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(t("Community is for adults"), color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                t("Posts here are written by other adults. To keep it that way, please confirm your age before you go in."),
                color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onConfirm, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
            ) { Text(t("I am 18 or over"), color = Color.White, fontWeight = FontWeight.Bold) }
            TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth()) {
                Text(t("Not now"), color = AppTheme.SubtleTextColor)
            }
        }
    }
}
