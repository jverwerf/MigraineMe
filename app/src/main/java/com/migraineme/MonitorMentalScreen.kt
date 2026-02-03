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
fun MonitorMentalScreen(
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
                    "Mental Health",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                
                Text(
                    "Track your mood, stress levels, and emotional wellbeing",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            BaseCard {
                Text(
                    "Coming soon:",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Text("• Daily mood logging", color = AppTheme.BodyTextColor)
                Text("• Stress level tracking", color = AppTheme.BodyTextColor)
                Text("• Anxiety indicators", color = AppTheme.BodyTextColor)
                Text("• Emotional triggers identification", color = AppTheme.BodyTextColor)
                Text("• Mood patterns over time", color = AppTheme.BodyTextColor)
            }
            
            BaseCard {
                Text(
                    "Why mental health matters:",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    "Stress, anxiety, and emotional changes are significant migraine triggers. By tracking your mental state, we can help identify patterns and predict when you're at higher risk.",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            BaseCard {
                Text(
                    "The mind-migraine connection:",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Text("• Stress can trigger migraines directly", color = AppTheme.BodyTextColor)
                Text("• 'Let-down' migraines occur after stress ends", color = AppTheme.BodyTextColor)
                Text("• Anxiety can increase pain sensitivity", color = AppTheme.BodyTextColor)
                Text("• Depression is common with chronic migraine", color = AppTheme.BodyTextColor)
            }
        }
    }
}
