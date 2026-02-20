package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@Composable
fun ManageItemsScreen(navController: NavController) {
    val scrollState = rememberScrollState()

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            // Hero
            HeroCard {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .drawBehind { HubIcons.run { drawMigraineStarburst(AppTheme.AccentPink) } }
                )
                Text("Manage Items", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(
                    "Add, remove, or organise your triggers, medicines, reliefs and symptoms",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            // Manage cards
            ManageItemRow(
                title = "Symptoms",
                subtitle = "Pain character & accompanying experiences",
                iconColor = AppTheme.AccentPink,
                drawIcon = { HubIcons.run { drawMigraineStarburst(it) } },
                onClick = { navController.navigate(Routes.MANAGE_SYMPTOMS) }
            )

            ManageItemRow(
                title = "Triggers",
                subtitle = "Manage your trigger pool",
                iconColor = Color(0xFFFFB74D),
                drawIcon = { HubIcons.run { drawTriggerBolt(it) } },
                onClick = { navController.navigate(Routes.MANAGE_TRIGGERS) }
            )

            ManageItemRow(
                title = "Medicines",
                subtitle = "Manage your medicine pool",
                iconColor = Color(0xFF4FC3F7),
                drawIcon = { HubIcons.run { drawMedicinePill(it) } },
                onClick = { navController.navigate(Routes.MANAGE_MEDICINES) }
            )

            ManageItemRow(
                title = "Reliefs",
                subtitle = "Manage your relief pool",
                iconColor = Color(0xFF81C784),
                drawIcon = { HubIcons.run { drawReliefLeaf(it) } },
                onClick = { navController.navigate(Routes.MANAGE_RELIEFS) }
            )

            ManageItemRow(
                title = "Prodromes",
                subtitle = "Early warning signs",
                iconColor = Color(0xFFCE93D8),
                drawIcon = { HubIcons.run { drawProdromeEye(it) } },
                onClick = { navController.navigate(Routes.MANAGE_PRODROMES) }
            )

            ManageItemRow(
                title = "Locations",
                subtitle = "Where were you?",
                iconColor = Color(0xFF64B5F6),
                drawIcon = { HubIcons.run { drawLocationPin(it) } },
                onClick = { navController.navigate(Routes.MANAGE_LOCATIONS) }
            )

            ManageItemRow(
                title = "Activities",
                subtitle = "What were you doing?",
                iconColor = Color(0xFFFF8A65),
                drawIcon = { HubIcons.run { drawActivityPulse(it) } },
                onClick = { navController.navigate(Routes.MANAGE_ACTIVITIES) }
            )

            ManageItemRow(
                title = "Missed Activities",
                subtitle = "What did you miss?",
                iconColor = Color(0xFFEF9A9A),
                drawIcon = { HubIcons.run { drawMissedActivity(it) } },
                onClick = { navController.navigate(Routes.MANAGE_MISSED_ACTIVITIES) }
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ManageItemRow(
    title: String,
    subtitle: String,
    iconColor: Color,
    drawIcon: DrawScope.(Color) -> Unit,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val actualColor = if (enabled) iconColor else iconColor.copy(alpha = 0.4f)

    BaseCard(
        modifier = if (enabled) Modifier.clickable(onClick = onClick) else Modifier
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Round icon circle
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(actualColor.copy(alpha = 0.15f))
                    .border(1.5.dp, actualColor.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .drawBehind { drawIcon(actualColor) }
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    subtitle,
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = AppTheme.SubtleTextColor,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}


