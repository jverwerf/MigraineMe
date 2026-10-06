package com.migraineme

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Tip under the Manage row on the wizard list pages: starring the items you use
 * moves them to the top. One X hides it on every wizard page for good.
 */
@Composable
fun WizardFavouritesTip() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("wizard_tips", Context.MODE_PRIVATE) }
    var dismissed by remember { mutableStateOf(prefs.getBoolean("favourites_tip_dismissed", false)) }
    if (dismissed) return
    BaseCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                t("Long list? Tap Manage and star the ones you use. They move to the top."),
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    prefs.edit().putBoolean("favourites_tip_dismissed", true).apply()
                    dismissed = true
                },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = t("Dismiss tip"),
                    tint = AppTheme.SubtleTextColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
