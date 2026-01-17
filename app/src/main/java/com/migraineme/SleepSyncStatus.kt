package com.migraineme

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/**
 * Renders a compact status text for WHOOP sleep sync/backfill.
 *
 * NOTE:
 * - Daily scheduling is handled on the backend (not WorkManager).
 */
@Composable
fun SleepSyncStatus(
    accessToken: String?,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current.applicationContext
    var text by remember { mutableStateOf("Loading sleep sync status…") }

    LaunchedEffect(accessToken) {
        text = buildStatusText(ctx, accessToken)
    }

    Column(modifier = modifier) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
    }
}

private suspend fun buildStatusText(ctx: Context, accessToken: String?): String =
    withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)

        val whoopConnected = (WhoopTokenStore(ctx).load() != null)

        val token = accessToken ?: SessionStore.readAccessToken(ctx)

        val metrics = SupabaseMetricsService(ctx)
        val latestDate: String? = if (!token.isNullOrBlank()) {
            runCatching { metrics.latestSleepDate(token, source = "whoop") }.getOrNull()
        } else null

        val backfillStart = latestDate?.let {
            runCatching { LocalDate.parse(it).plusDays(1) }.getOrNull()
        }

        val backfillWindow =
            if (backfillStart != null && backfillStart.isBefore(today)) {
                val end = yesterday
                val days =
                    if (!end.isBefore(backfillStart)) (end.toEpochDay() - backfillStart.toEpochDay() + 1).toInt() else 0
                if (days > 0) "$backfillStart → $end ($days days)" else "Up to date"
            } else {
                "Up to date"
            }

        buildString {
            appendLine("Sleep sync status")
            appendLine("• WHOOP connected: ${if (whoopConnected) "Yes" else "No"}")
            appendLine("• Latest Supabase date (whoop): ${latestDate ?: "—"}")
            appendLine("• Backfill window: $backfillWindow")
            append("• Daily sync schedule: Backend")
        }
    }
