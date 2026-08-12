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
 * Rationale activity shown when user taps the (i) info button on ANY permission dialog.
 * This explains ALL permissions the app may request.
 */
class HealthConnectRationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        android.util.Log.d("PermissionRationale", "===== RATIONALE ACTIVITY STARTED =====")
        android.util.Log.d("PermissionRationale", "Intent: ${intent.action}")
        android.util.Log.d("PermissionRationale", "Extras: ${intent.extras}")

        setContent {
            MaterialTheme {
                RationaleScreen(
                    onContinue = {
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
            Spacer(Modifier.weight(0.3f))

            Text(
                t("App Permissions"),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                t("MigraineMe collects various data to help identify your migraine triggers. Here's what we use and why:"),
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(Modifier.height(8.dp))

            // Location Section
            Text(
                t("📍 Location"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                t("Used to get local weather data (temperature, pressure, humidity) which can trigger migraines. Requires \"Allow all the time\" for background updates."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            // Microphone Section
            Text(
                t("🎤 Microphone"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                t("Used to sample ambient noise levels. We only measure volume (decibels), not actual audio content."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            // Health Connect Section
            Text(
                t("❤️ Health Connect"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                t("Used to read nutrition, sleep, heart rate, HRV, steps, menstruation, and other health data from apps like Cronometer, Fitbit, Samsung Health, etc."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            // Screen Time Section
            Text(
                t("📱 Usage Access"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                t("Used to track screen time which may correlate with migraines."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            Divider()

            Spacer(Modifier.height(8.dp))

            Text(
                t("Your data is stored securely and used only to analyze your migraine patterns. We never share your data with third parties."),
                style = MaterialTheme.typography.bodySmall,
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
                    Text(t("Cancel"))
                }

                Button(
                    onClick = {
                        android.util.Log.d("PermissionRationale", "User clicked CONTINUE")
                        onContinue()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(t("Continue"))
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
