package com.migraineme

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The separate yes to health data. Ticked on the sign-in screen, recorded after
 * sign-in, and asked once from anyone signed in who has no record. A failed
 * network check never blocks: the question simply comes back on a later open.
 */
object HealthConsent {
    const val POLICY_VERSION = "2026-11-07"
    const val PRIVACY_URL = "https://migraineme.app/privacy"
    private const val PREFS = "health_consent"
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markTicked(c: Context, ticked: Boolean) = prefs(c).edit().putBoolean("ticked", ticked).apply()

    /** True when this person must be asked now. */
    suspend fun needsAsking(c: Context, token: String, userId: String): Boolean {
        val p = prefs(c)
        if (p.getBoolean("yes_$userId", false)) return false
        if (p.getBoolean("ticked", false)) {
            runCatching { give(c, token, userId) }
            return false
        }
        val exists = runCatching { SupabasePractitionerService.healthConsentExists(token, userId) }.getOrNull()
            ?: return false
        if (exists) p.edit().putBoolean("yes_$userId", true).apply()
        return !exists
    }

    suspend fun give(c: Context, token: String, userId: String) {
        SupabasePractitionerService.recordHealthConsent(token, userId, POLICY_VERSION)
        prefs(c).edit().putBoolean("yes_$userId", true).remove("ticked").apply()
    }
}

/** The tick with its line and a link to the policy. Same on sign-in and on the one-time screen. */
@Composable
fun HealthConsentTick(checked: Boolean, onChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(
                checkedColor = AppTheme.AccentPurple,
                uncheckedColor = AppTheme.SubtleTextColor,
                checkmarkColor = androidx.compose.ui.graphics.Color.White,
            ),
        )
        Column(Modifier.weight(1f)) {
            Text(
                t("I agree to my health data being used to run this app, as described in the Privacy Policy. We never sell it or use it for advertising."),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.BodyTextColor,
                modifier = Modifier.clickable { onChange(!checked) },
            )
            Text(
                t("Privacy Policy"),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.AccentPurple,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HealthConsent.PRIVACY_URL))) }
                },
            )
        }
    }
}

/** Asks once from someone signed in with no record. Not now signs them out. */
@Composable
fun HealthConsentGate(authVm: AuthViewModel, userId: String?, onNotNow: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(false) }
    var ticked by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(userId) {
        asking = false
        if (userId.isNullOrBlank()) return@LaunchedEffect
        val token = authVm.getValidAccessToken(ctx) ?: return@LaunchedEffect
        asking = withContext(Dispatchers.IO) { HealthConsent.needsAsking(ctx, token, userId) }
    }
    if (!asking) return
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = AppTheme.BaseCardSolid), shape = AppTheme.BaseCardShape) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t("Your health data"), style = MaterialTheme.typography.titleMedium, color = AppTheme.TitleColor, fontWeight = FontWeight.SemiBold)
                HealthConsentTick(ticked) { ticked = it }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { asking = false; onNotNow() }) { Text(t("Not now")) }
                    Button(
                        enabled = ticked && !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                val token = authVm.getValidAccessToken(ctx)
                                if (token != null && !userId.isNullOrBlank()) {
                                    // A failed save asks again next time; it does not lock anyone out now.
                                    withContext(Dispatchers.IO) { runCatching { HealthConsent.give(ctx, token, userId) } }
                                }
                                busy = false
                                asking = false
                            }
                        },
                    ) { Text(t("Continue")) }
                }
            }
        }
    }
}
