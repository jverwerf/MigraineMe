package com.migraineme

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Required by Health Connect to explain why the app needs nutrition permissions
 * before showing the actual permission dialog.
 */
class HealthConnectRationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        android.util.Log.d("HealthConnect", "===== RATIONALE ACTIVITY STARTED =====")
        android.util.Log.d("HealthConnect", "Intent: ${intent.action}")
        android.util.Log.d("HealthConnect", "Extras: ${intent.extras}")

        setContent {
            MaterialTheme {
                RationaleScreen(
                    onContinue = {
                        // Return success to trigger the actual permission dialog
                        setResult(RESULT_OK)
                        finish()
                    },
                    onCancel = {
                        setResult(RESULT_CANCELED)
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
private fun RationaleScreen(
    onContinue: () -> Unit,
    onCancel: () -> Unit
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.weight(0.5f))

            Text(
                "Nutrition Tracking",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                "MigraineMe would like to track your nutrition data to help identify migraine triggers.",
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "We'll read nutrition data from:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BulletPoint("Cronometer")
                BulletPoint("MyFitnessPal")
                BulletPoint("Other nutrition tracking apps")
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "This includes:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BulletPoint("Calories and macronutrients")
                BulletPoint("Vitamins and minerals")
                BulletPoint("Caffeine intake")
                BulletPoint("Meal timing")
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "Your data is only used to analyze potential migraine triggers and is never shared.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = {
                        android.util.Log.d("HealthConnect", "Rationale: User clicked CONTINUE")
                        onContinue()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Continue")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BulletPoint(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text("•", style = MaterialTheme.typography.bodyLarge)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}