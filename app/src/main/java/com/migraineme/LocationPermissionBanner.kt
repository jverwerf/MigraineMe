package com.migraineme

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Android silently revoked our location permission" detector + nudge.
 *
 * Android's "remove permissions for unused apps" strips ACCESS_*_LOCATION when the
 * app hasn't been opened for a few weeks. Nothing in the app surfaces that: the UI
 * looks normal while user_location_daily stops being written, which quietly kills
 * weather, the risk gauge inputs and auto-detected weather triggers.
 *
 * The card only appears when we are confident the state is broken *and* unwanted:
 * the user still has the location metric ON server-side (metric_settings) but the
 * runtime grant is gone. Users who deliberately turned Location off in Data
 * Settings never see it.
 *
 * No animation anywhere — static card only.
 */

private const val PREFS_NAME = "location_permission_nudge"
private const val KEY_SNOOZED_UNTIL = "snoozed_until_ms"
private const val SNOOZE_MS = 7L * 24 * 60 * 60 * 1000 // 7 days

/** What is missing, if anything. */
enum class LocationGrantState {
    /** Everything we declare and need is granted. */
    Ok,

    /** Neither fine nor coarse is granted — nothing can be collected at all. */
    ForegroundMissing,

    /**
     * Foreground is granted but background is not, on API 29+.
     * Only reachable when ACCESS_BACKGROUND_LOCATION is actually declared in the
     * manifest — it currently is not (removed for Google Play policy), so this
     * variant stays dormant until/unless background collection comes back.
     */
    BackgroundMissing
}

/** True when [permission] is declared in the merged manifest. */
private fun isPermissionDeclared(context: Context, permission: String): Boolean = try {
    val info = context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.GET_PERMISSIONS
    )
    info.requestedPermissions?.contains(permission) == true
} catch (_: Exception) {
    false
}

private fun isGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Current runtime location grant state. Cheap — safe to call on every resume. */
fun locationGrantState(context: Context): LocationGrantState {
    val hasFine = isGranted(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
    val hasCoarse = isGranted(context, android.Manifest.permission.ACCESS_COARSE_LOCATION)
    if (!hasFine && !hasCoarse) return LocationGrantState.ForegroundMissing

    val bg = android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
        isPermissionDeclared(context, bg) &&
        !isGranted(context, bg)
    ) {
        return LocationGrantState.BackgroundMissing
    }

    return LocationGrantState.Ok
}

@Composable
fun LocationPermissionBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val activity = context as? Activity
    val prefs = remember { appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var grantState by remember { mutableStateOf(locationGrantState(appContext)) }
    // null = not looked up yet. Only fetched when the grant is actually broken,
    // so healthy users cost zero network calls.
    var metricEnabled by remember { mutableStateOf<Boolean?>(null) }
    var snoozedUntil by remember { mutableStateOf(prefs.getLong(KEY_SNOOZED_UNTIL, 0L)) }
    var resumeTick by remember { mutableStateOf(0) }

    // The permission can be revoked (or granted from system settings) while we are
    // backgrounded, so re-check on every resume, not only on first composition.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val fresh = locationGrantState(appContext)
                // Repaired from system settings while we were backgrounded —
                // start collecting again straight away instead of waiting for
                // the next hourly FCM push.
                if (grantState != LocationGrantState.Ok && fresh == LocationGrantState.Ok) {
                    LocationDailySyncWorker.runOnceNow(appContext)
                }
                grantState = fresh
                resumeTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        grantState = locationGrantState(appContext)
        val anyGranted = result.values.any { it }
        if (anyGranted) LocationDailySyncWorker.runOnceNow(appContext)
        // Denied with no rationale left = "don't ask again" / permanently denied.
        // The runtime dialog will never appear again, so send them to settings.
        val canAskAgain = activity != null && result.keys.any {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }
        if (!anyGranted && !canAskAgain) {
            openAppSettings(appContext)
        }
    }

    val snoozed = System.currentTimeMillis() < snoozedUntil
    val broken = grantState != LocationGrantState.Ok

    // Condition 1: the user still wants location data (metric_settings server-side).
    LaunchedEffect(broken, snoozed, resumeTick) {
        if (broken && !snoozed) {
            metricEnabled = withContext(Dispatchers.IO) {
                EdgeFunctionsService()
                    .getMetricSettings(appContext)
                    .find { it.metric == "user_location_daily" }
                    ?.enabled == true
            }
        }
    }

    if (!broken || snoozed || metricEnabled != true) return

    val body = when (grantState) {
        LocationGrantState.BackgroundMissing ->
            "Android limited MigraineMe to location only while the app is open. " +
                "This happens on its own when an app hasn't been used for a while. " +
                "Weather and risk predictions need location in the background to keep updating."
        else ->
            "Android turned off location access for MigraineMe. This happens on its own " +
                "when an app hasn't been opened for a while. Without it we can't fetch your " +
                "weather, so your risk predictions and weather triggers have stopped updating."
    }

    BaseCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.LocationOff,
                contentDescription = null,
                tint = Color(0xFFFFB74D),
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                t("Location tracking stopped"),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )
        }

        Text(
            body,
            color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.bodySmall
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    when {
                        // Background can only be requested on its own, and from
                        // API 30 the runtime dialog is not offered at all — the
                        // user has to pick "Allow all the time" in settings.
                        grantState == LocationGrantState.BackgroundMissing -> {
                            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                                permissionLauncher.launch(
                                    arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                )
                            } else {
                                openAppSettings(appContext)
                            }
                        }
                        else -> permissionLauncher.launch(
                            arrayOf(
                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                android.Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(t("Turn it back on"), fontWeight = FontWeight.SemiBold)
            }

            TextButton(
                onClick = {
                    val until = System.currentTimeMillis() + SNOOZE_MS
                    prefs.edit().putLong(KEY_SNOOZED_UNTIL, until).apply()
                    snoozedUntil = until
                }
            ) {
                Text(
                    t("Not now"),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
