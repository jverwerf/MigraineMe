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
fun MonitorSleepScreen(
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
                    "Sleep",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                
                Text(
                    "Sleep data from WHOOP and other wearables",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            BaseCard {
                Text(
                    "Sleep metrics tracked:",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Text("• Sleep duration", color = AppTheme.BodyTextColor)
                Text("• Sleep score", color = AppTheme.BodyTextColor)
                Text("• Sleep efficiency", color = AppTheme.BodyTextColor)
                Text("• Sleep stages (REM, deep, light)", color = AppTheme.BodyTextColor)
                Text("• Sleep disturbances", color = AppTheme.BodyTextColor)
                Text("• Time fell asleep / woke up", color = AppTheme.BodyTextColor)
            }
            
            BaseCard {
                Text(
                    "Why sleep matters:",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    "Poor sleep quality and irregular sleep patterns are among the most common migraine triggers. Tracking your sleep helps identify patterns and predict migraine risk.",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            BaseCard {
                Text(
                    "Sleep hygiene tips:",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Text("• Maintain consistent sleep/wake times", color = AppTheme.BodyTextColor)
                Text("• Avoid screens 1 hour before bed", color = AppTheme.BodyTextColor)
                Text("• Keep bedroom cool and dark", color = AppTheme.BodyTextColor)
                Text("• Limit caffeine after 2pm", color = AppTheme.BodyTextColor)
                Text("• Aim for 7-9 hours per night", color = AppTheme.BodyTextColor)
            }
        }
    }
}
