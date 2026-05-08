// FILE: app/src/main/java/com/migraineme/FreeTrialGiftScreen.kt
package com.migraineme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.AutoGraph
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FreeTrialGiftScreen(onContinue: () -> Unit) {
    val bgBrush = remember {
        Brush.verticalGradient(listOf(Color(0xFF1A0029), Color(0xFF2A003D), Color(0xFF1A0029)))
    }

    val infiniteTransition = rememberInfiniteTransition(label = "gift-glow")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "pulse"
    )

    Box(Modifier.fillMaxSize().background(bgBrush)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))

            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(140.dp)
                        .scale(pulseScale)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    AppTheme.AccentPurple.copy(alpha = 0.4f),
                                    AppTheme.AccentPink.copy(alpha = 0.25f)
                                )
                            ),
                            CircleShape
                        )
                )
                Box(
                    Modifier
                        .size(140.dp)
                        .border(
                            2.dp,
                            Brush.linearGradient(
                                listOf(AppTheme.AccentPurple, AppTheme.AccentPink)
                            ),
                            CircleShape
                        )
                )
                Icon(
                    Icons.Outlined.CardGiftcard,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(56.dp)
                )
            }

            Spacer(Modifier.height(28.dp))

            Text(
                "Enjoy 14 days on us",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp
                ),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(10.dp))

            Text(
                "Premium is unlocked. No card needed, no strings attached.",
                color = AppTheme.BodyTextColor,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(Modifier.height(28.dp))

            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GiftPerk(Icons.Outlined.AutoGraph, "7-day risk forecast")
                GiftPerk(Icons.Outlined.Insights, "AI daily insights")
                GiftPerk(Icons.Outlined.Chat, "Ask MigraineMe chat")
                GiftPerk(Icons.Outlined.Description, "PDF reports for your doctor")
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPink),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("Let's go", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "You can subscribe anytime from Settings.",
                color = AppTheme.SubtleTextColor.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GiftPerk(icon: ImageVector, text: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(AppTheme.AccentPurple.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = AppTheme.AccentPurple,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
