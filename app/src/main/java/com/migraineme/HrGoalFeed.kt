package com.migraineme

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord

/**
 * Whether heart rate goals (kind hr_threshold) can fill in by themselves.
 * Heart rate reaches the app from Garmin, Oura or Polar (server side), or from
 * Health Connect on this phone once the heart-rate read permission is granted.
 * WHOOP gives no heart rate.
 */
data class HrFeed(
    /** A source that carries heart rate is connected. */
    val fed: Boolean,
    /** WHOOP is connected and no other wearable is. Only meaningful when not fed. */
    val whoopOnly: Boolean,
)

/** Local reads only (Health Connect grant + the token stores), no network. Call off the main thread. */
suspend fun hrGoalFeed(context: Context): HrFeed {
    val ctx = context.applicationContext
    val hc = runCatching {
        HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE &&
            HealthPermission.getReadPermission(HeartRateRecord::class) in
            HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions()
    }.getOrDefault(false)
    val garmin = runCatching { GarminTokenStore(ctx).load() != null }.getOrDefault(false)
    val oura = runCatching { OuraTokenStore(ctx).load() != null }.getOrDefault(false)
    val polar = runCatching { PolarTokenStore(ctx).load() != null }.getOrDefault(false)
    val whoop = runCatching { WhoopTokenStore(ctx).load() != null }.getOrDefault(false)
    return HrFeed(
        fed = hc || garmin || oura || polar,
        whoopOnly = whoop && !garmin && !oura && !polar,
    )
}

/** Same orange as the location banner's icon. */
val HrWarningOrange = Color(0xFFFFB74D)

/** True once the store knows heart rate is not being fed. Unknown (not checked yet) counts as fine. */
@Composable
fun hrGoalNotFed(): Boolean {
    val feed by PractitionerGoalsStore.hrFeed.collectAsState()
    return feed?.fed == false
}

/**
 * Attention card for a heart rate goal that cannot fill in by itself.
 * Static, no motion. The caller decides when to show it (see [hrGoalNotFed]).
 */
@Composable
fun HrNotFedWarning(onOpenConnections: () -> Unit, modifier: Modifier = Modifier) {
    val feed by PractitionerGoalsStore.hrFeed.collectAsState()
    BaseCard(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = HrWarningOrange,
                modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(t("Heart rate is not coming in"), color = Color.White,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
        }
        Text(
            if (feed?.whoopOnly == true)
                t("WHOOP does not share heart rate, so this goal cannot fill in by itself. Connect Garmin, Oura, Polar or Health Connect.")
            else
                t("No heart rate is reaching the app, so this goal cannot fill in by itself. Connect a watch or Health Connect."),
            color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall
        )
        Button(
            onClick = onOpenConnections,
            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple, contentColor = Color.White),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(t("Open connections"), fontWeight = FontWeight.SemiBold)
        }
    }
}
