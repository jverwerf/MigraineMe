// FILE: app/src/main/java/com/migraineme/OnboardingPaywallScreen.kt
package com.migraineme

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun OnboardingPaywallScreen(
    onDismiss: () -> Unit,
    onSubscribed: () -> Unit = onDismiss
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var packages by remember { mutableStateOf<List<PackageInfo>>(emptyList()) }
    var selectedPackage by remember { mutableStateOf<PackageInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var purchasing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val premiumState by PremiumManager.state.collectAsState()

    val fallbackPackages = remember {
        listOf(
            PackageInfo(
                identifier = "annual",
                productId = "migraineme_premium_annual",
                price = "£34.99/year",
                pricePerMonth = "£2.92",
                isAnnual = true,
                rcPackage = null
            ),
            PackageInfo(
                identifier = "monthly",
                productId = "migraineme_premium_monthly",
                price = "£4.99/month",
                pricePerMonth = null,
                isAnnual = false,
                rcPackage = null
            )
        )
    }

    LaunchedEffect(Unit) {
        PremiumManager.getOfferings { result ->
            val displayPackages = result.ifEmpty { fallbackPackages }
            packages = displayPackages
            selectedPackage = displayPackages.firstOrNull { it.isAnnual } ?: displayPackages.firstOrNull()
            loading = false
        }
    }

    // Auto-continue when premium is confirmed
    if (premiumState.tier == PremiumTier.PREMIUM) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(1500)
            onSubscribed()
        }

        val bgBrush = remember { Brush.verticalGradient(listOf(Color(0xFF1A0029), Color(0xFF2A003D), Color(0xFF1A0029))) }
        Box(Modifier.fillMaxSize().background(bgBrush), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(80.dp).background(
                        Brush.linearGradient(listOf(AppTheme.AccentPurple, AppTheme.AccentPink)),
                        CircleShape
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(40.dp))
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    "You're Premium!",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "All features are unlocked.",
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        return
    }

    val bgBrush = remember { Brush.verticalGradient(listOf(Color(0xFF1A0029), Color(0xFF2A003D), Color(0xFF1A0029))) }

    Box(Modifier.fillMaxSize().background(bgBrush)) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(20.dp))

            // ── Scrollable content ──
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(24.dp))

                // ── Premium badge ──
                Box(
                    modifier = Modifier
                        .background(
                            Brush.horizontalGradient(
                                listOf(AppTheme.AccentPurple.copy(alpha = 0.2f), AppTheme.AccentPink.copy(alpha = 0.15f))
                            ),
                            RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        "PREMIUM",
                        color = AppTheme.AccentPurple,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    "Your data is\nready to talk",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    "Unlock the patterns hidden in your migraine history",
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(Modifier.height(24.dp))

                // ── Plan selection ──
                if (loading) {
                    CircularProgressIndicator(color = AppTheme.AccentPurple, modifier = Modifier.size(32.dp))
                } else {
                    packages.sortedByDescending { it.isAnnual }.forEach { pkg ->
                        val isSelected = selectedPackage?.identifier == pkg.identifier

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .then(
                                    if (isSelected) Modifier.border(
                                        2.dp,
                                        Brush.horizontalGradient(listOf(AppTheme.AccentPurple, AppTheme.AccentPink)),
                                        RoundedCornerShape(12.dp)
                                    )
                                    else Modifier.border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                )
                                .background(
                                    if (isSelected) AppTheme.AccentPurple.copy(alpha = 0.1f)
                                    else Color.White.copy(alpha = 0.04f)
                                )
                                .clickable { selectedPackage = pkg }
                                .padding(14.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (pkg.isAnnual) "Annual" else "Monthly",
                                            color = Color.White,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                        if (pkg.isAnnual) {
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "BEST VALUE",
                                                color = AppTheme.AccentPurple,
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                modifier = Modifier
                                                    .background(AppTheme.AccentPurple.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    if (pkg.pricePerMonth != null) {
                                        Text(
                                            "Just ${pkg.pricePerMonth}/month",
                                            color = AppTheme.SubtleTextColor,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                Text(
                                    pkg.price,
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        error!!,
                        color = Color(0xFFEF5350),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(20.dp))

                // ── Features ──
                val features = listOf(
                    Triple(Icons.Outlined.Analytics, "Full Insights & Spider Charts", "Treatment effectiveness and trigger patterns"),
                    Triple(Icons.Outlined.Timeline, "7-Day Risk Forecast", "Know your migraine risk before it happens"),
                    Triple(Icons.Outlined.Speed, "Active Trigger Breakdown", "See exactly what's driving your risk score"),
                    Triple(Icons.Outlined.Psychology, "AI Calibration", "Personalised risk model tuned to you"),
                    Triple(Icons.Outlined.Description, "PDF Reports for Doctors", "Professional reports with charts and timelines"),
                    Triple(Icons.Outlined.Restaurant, "AI Food Risk Analysis", "Tyramine, gluten, and alcohol risk scoring"),
                )

                features.forEach { (icon, title, subtitle) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(36.dp).background(AppTheme.AccentPurple.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(18.dp))
                        }
                        Column {
                            Text(title, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                            Text(subtitle, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                Spacer(Modifier.height(8.dp))

                // ── Restore + Legal ──
                TextButton(onClick = {
                    PremiumManager.restorePurchases(
                        onSuccess = { /* state auto-updates */ },
                        onError = { msg -> error = msg }
                    )
                }) {
                    Text("Restore Purchases", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
                }

                Row(horizontalArrangement = Arrangement.Center) {
                    Text(
                        "Terms of Service",
                        color = AppTheme.SubtleTextColor.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall.copy(textDecoration = TextDecoration.Underline)
                    )
                    Text("  •  ", color = AppTheme.SubtleTextColor.copy(alpha = 0.4f), style = MaterialTheme.typography.labelSmall)
                    Text(
                        "Privacy Policy",
                        color = AppTheme.SubtleTextColor.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall.copy(textDecoration = TextDecoration.Underline)
                    )
                }

                Spacer(Modifier.height(16.dp))
            }

            // ── Bottom buttons (matches onboarding layout) ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Not now", color = AppTheme.SubtleTextColor)
                }

                Button(
                    onClick = {
                        val pkg = selectedPackage ?: return@Button
                        val act = activity ?: return@Button
                        purchasing = true
                        error = null
                        PremiumManager.purchase(
                            activity = act,
                            packageInfo = pkg,
                            onSuccess = { purchasing = false },
                            onError = { msg ->
                                purchasing = false
                                error = msg
                            }
                        )
                    },
                    enabled = !purchasing && selectedPackage != null && !loading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppTheme.AccentPink,
                        disabledContainerColor = AppTheme.AccentPink.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (purchasing) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Processing…")
                    } else {
                        Text("Subscribe"); Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
