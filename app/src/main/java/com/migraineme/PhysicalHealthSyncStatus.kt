// FILE: C:\Users\verwe\Projects\MigraineMe\app\src\main\java\com\migraineme\PhysicalHealthSyncStatus.kt
package com.migraineme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*

/**
 * Minimal, non-blocking status banner for Physical Health sync (WHOOP).
 * 'source' has a default so existing calls compile even if they don't pass it.
 */
@Composable
fun PhysicalHealthSyncStatus(
    accessToken: String?,
    source: String = "whoop",
    refreshKey: Int = 0,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier
) {
    // Consume refreshKey so caller can trigger a recompute; avoids "unused" warnings.
    LaunchedEffect(refreshKey) { /* no-op; hook for future refresh */ }

    val tokenOk = !accessToken.isNullOrBlank()
    val tokenPreview = if (tokenOk) "present" else "missing"

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "Physical Health ($source) status",
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            "Auth token: $tokenPreview; daily job scheduled at 09:00.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
