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
fun MonitorEnvironmentScreen(
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
                    "Environment",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                
                Text(
                    "Track environmental factors that may trigger migraines",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            BaseCard {
                Text(
                    "Data tracked:",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Text("• Ambient noise levels", color = AppTheme.BodyTextColor)
                Text("• Screen time duration", color = AppTheme.BodyTextColor)
                Text("• Light exposure", color = AppTheme.BodyTextColor)
                Text("• Air quality index", color = AppTheme.BodyTextColor)
                Text("• Location changes", color = AppTheme.BodyTextColor)
            }
            
            BaseCard {
                Text(
                    "Environmental triggers:",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    "Your environment plays a crucial role in migraine development. Loud noises, bright lights, strong smells, and air quality changes can all trigger attacks.",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            BaseCard {
                Text(
                    "Common environmental triggers:",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Text("• Loud or persistent noise", color = AppTheme.BodyTextColor)
                Text("• Bright or flickering lights", color = AppTheme.BodyTextColor)
                Text("• Strong odors or perfumes", color = AppTheme.BodyTextColor)
                Text("• Extended screen exposure", color = AppTheme.BodyTextColor)
                Text("• Travel and altitude changes", color = AppTheme.BodyTextColor)
                Text("• Poor air quality / pollution", color = AppTheme.BodyTextColor)
            }
            
            BaseCard {
                Text(
                    "Permissions needed:",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Text("• Microphone (ambient noise)", color = AppTheme.SubtleTextColor)
                Text("• Usage access (screen time)", color = AppTheme.SubtleTextColor)
                Text("• Location (air quality, altitude)", color = AppTheme.SubtleTextColor)
            }
        }
    }
}
