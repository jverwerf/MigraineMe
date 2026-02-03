package com.migraineme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation.NavController

@Composable
fun MonitorPhysicalScreen(
    navController: NavController,
    authVm: AuthViewModel
) {
    val scrollState = rememberScrollState()

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll) {
            // Back navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            }
            
            HeroCard {
                Text(
                    "Physical Health",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                
                Text(
                    "Data from WHOOP and other wearables",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            BaseCard {
                Text(
                    "Recovery metrics:",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Text("• Recovery score", color = AppTheme.BodyTextColor)
                Text("• HRV (Heart Rate Variability)", color = AppTheme.BodyTextColor)
                Text("• Resting heart rate", color = AppTheme.BodyTextColor)
                Text("• SpO2 (Blood oxygen)", color = AppTheme.BodyTextColor)
                Text("• Skin temperature", color = AppTheme.BodyTextColor)
            }
            
            BaseCard {
                Text(
                    "Sleep metrics:",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Text("• Sleep duration", color = AppTheme.BodyTextColor)
                Text("• Sleep efficiency", color = AppTheme.BodyTextColor)
                Text("• Sleep stages (REM, deep, light)", color = AppTheme.BodyTextColor)
                Text("• Sleep disturbances", color = AppTheme.BodyTextColor)
                Text("• Time asleep/awake", color = AppTheme.BodyTextColor)
            }
            
            BaseCard {
                Text(
                    "Why this matters:",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    "Poor sleep and low HRV are strongly correlated with migraine onset. We use these metrics to predict your migraine risk.",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
