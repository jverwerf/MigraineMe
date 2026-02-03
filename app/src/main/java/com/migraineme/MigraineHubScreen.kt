package com.migraineme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Healing
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@Composable
fun MigraineHubScreen(navController: NavController) {
    val scrollState = rememberScrollState()

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll) {
            // Hero Card - Log Full Migraine
            HeroCard(
                modifier = Modifier.clickable { navController.navigate(Routes.LOG_MIGRAINE) }
            ) {
                Icon(
                    imageVector = Icons.Outlined.Psychology,
                    contentDescription = "Migraine",
                    tint = AppTheme.AccentPink,
                    modifier = Modifier.size(48.dp)
                )
                
                Spacer(Modifier.height(8.dp))
                
                Text(
                    "Log Migraine",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                
                Text(
                    "Full migraine with triggers, medicines & reliefs",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                
                Spacer(Modifier.height(12.dp))
                
                Text(
                    "Tap to start →",
                    color = AppTheme.AccentPurple,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }
            
            // Quick Log Section Title
            BaseCard {
                Text(
                    "Quick Log",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    "Log items without a migraine",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            // Quick Log Cards Row 1
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickLogCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Bolt,
                    title = "Trigger",
                    subtitle = "Log a trigger",
                    iconTint = Color(0xFFFFB74D), // Orange
                    onClick = { navController.navigate(Routes.QUICK_LOG_TRIGGER) }
                )
                
                QuickLogCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Medication,
                    title = "Medicine",
                    subtitle = "Log a medicine",
                    iconTint = Color(0xFF4FC3F7), // Light blue
                    onClick = { navController.navigate(Routes.QUICK_LOG_MEDICINE) }
                )
            }
            
            // Quick Log Cards Row 2
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickLogCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Healing,
                    title = "Relief",
                    subtitle = "Log a relief",
                    iconTint = Color(0xFF81C784), // Green
                    onClick = { navController.navigate(Routes.QUICK_LOG_RELIEF) }
                )
                
                // Placeholder for Symptoms (coming later)
                QuickLogCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Psychology,
                    title = "Symptoms",
                    subtitle = "Coming soon",
                    iconTint = AppTheme.SubtleTextColor,
                    enabled = false,
                    onClick = { /* TODO: Navigate to symptoms */ }
                )
            }
        }
    }
}

@Composable
private fun QuickLogCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconTint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    BaseCard(
        modifier = modifier
            .then(
                if (enabled) Modifier.clickable(onClick = onClick)
                else Modifier
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (enabled) iconTint else AppTheme.SubtleTextColor.copy(alpha = 0.5f),
                modifier = Modifier.size(36.dp)
            )
            
            Spacer(Modifier.height(8.dp))
            
            Text(
                title,
                color = if (enabled) AppTheme.BodyTextColor else AppTheme.SubtleTextColor.copy(alpha = 0.5f),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )
            
            Text(
                subtitle,
                color = if (enabled) AppTheme.SubtleTextColor else AppTheme.SubtleTextColor.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}
