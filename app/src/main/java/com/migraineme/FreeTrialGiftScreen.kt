// FILE: app/src/main/java/com/migraineme/FreeTrialGiftScreen.kt
package com.migraineme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FreeTrialGiftScreen(onContinue: () -> Unit) {
    val bgBrush = remember {
        Brush.verticalGradient(listOf(Color(0xFF1A0029), Color(0xFF2A003D), Color(0xFF1A0029)))
    }

    Box(Modifier.fillMaxSize().background(bgBrush)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))

            Image(
                painter = painterResource(id = R.drawable.brainy_premium),
                contentDescription = null,
                modifier = Modifier.size(150.dp)
            )

            Spacer(Modifier.height(20.dp))

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

            // Same language as the paywall: blob perks in a card that carries
            // the page watermark.
            BrainyWatermarkCard(resId = R.drawable.brainy_recs, flipWatermark = true) {
                GiftPerk(R.drawable.brainy_risk_small, "7-day risk outlook")
                GiftPerk(R.drawable.brainy_detective_small, "AI daily insights")
                GiftPerk(R.drawable.brainy_ask_small, "Ask MigraineMe chat")
                GiftPerk(R.drawable.brainy_briefcase_small, "PDF reports for your doctor")
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
private fun GiftPerk(@androidx.annotation.DrawableRes resId: Int, text: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BrainyBlobIcon(resId = resId)
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
