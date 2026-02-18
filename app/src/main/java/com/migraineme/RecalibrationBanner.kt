package com.migraineme

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun RecalibrationBanner(
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var hasProposals by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Always check DB for pending proposals (don't rely solely on FCM flag)
        val confirmed = withContext(Dispatchers.IO) {
            RecalibrationViewModel.hasPendingProposals(context)
        }
        hasProposals = confirmed

        // Clear stale FCM flag if no proposals
        if (!confirmed) {
            context.getSharedPreferences("recalibration", Context.MODE_PRIVATE)
                .edit().putBoolean("has_proposals", false).apply()
        }
    }

    if (!hasProposals) return

    Card(
        onClick = {
            context.getSharedPreferences("recalibration", Context.MODE_PRIVATE)
                .edit().putBoolean("has_proposals", false).apply()
            onTap()
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "AI has suggestions for you",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Based on your recent data, we have some recommendations to improve your setup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}
