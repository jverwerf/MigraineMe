package com.migraineme

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Banner showing which Health Connect data sources are missing
 */
@Composable
fun HealthConnectMissingDataBanner(
    hasNutritionData: Boolean,
    hasMenstruationData: Boolean
) {
    // Only show if at least one is missing
    if (hasNutritionData && hasMenstruationData) {
        return
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    "Missing Data from Health Connect",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            
            // Nutrition missing
            if (!hasNutritionData) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "• Nutrition - No data found",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "  We suggest: Cronometer or MyFitnessPal (totals only)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "  Note: Data syncs every 15 minutes after logging",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Menstruation missing
            if (!hasMenstruationData) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "• Menstruation - No data found",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "  No compatible apps available yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "  Set up manual tracking in Data Settings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
