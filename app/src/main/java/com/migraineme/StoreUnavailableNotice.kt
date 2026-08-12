// FILE: app/src/main/java/com/migraineme/StoreUnavailableNotice.kt
package com.migraineme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Shown on the paywalls when the store has no purchasable products.
 *
 * Deliberately shows no prices and no buy button. Both paywalls used to fall back
 * to hardcoded prices whenever offerings failed to load, which rendered a plan card
 * that looked completely normal and a Subscribe button that could never work. That
 * hid a Play misconfiguration (subscriptions were on sale in the UK only) from every
 * non-UK user for two weeks: they saw a price, tapped, and got "Billing not
 * configured". If we cannot sell, we say so.
 */
@Composable
fun StoreUnavailableNotice(onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = AppTheme.SubtleTextColor,
            modifier = Modifier.size(32.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            t("Subscriptions aren't available right now"),
            color = Color.White,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            t("We couldn't load the plans from the store, so there's nothing to show you ") +
                t("yet. You haven't been charged. This is usually temporary."),
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = onRetry,
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple)
        ) {
            Text(t("Try again"))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            t("Still stuck? Email help@migraineme.app and we'll sort it out."),
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center
        )
    }
}
