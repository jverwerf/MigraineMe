// FILE: app/src/main/java/com/migraineme/PaywallScreen.kt
package com.migraineme

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.navigation.NavController

@Composable
fun PaywallScreen(
    navController: NavController,
    onDismiss: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scrollState = rememberScrollState()

    var packages by remember { mutableStateOf<List<PackageInfo>>(emptyList()) }
    var selectedPackage by remember { mutableStateOf<PackageInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var purchasing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val premiumState by PremiumManager.state.collectAsState()

    // Load offerings — with fallback for when RevenueCat isn't configured yet
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

    // If already premium, show success and navigate away
    if (premiumState.tier == PremiumTier.PREMIUM) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(1500)
            if (onDismiss != null) onDismiss() else navController.popBackStack()
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF81C784),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "You're Premium!",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    "All features are unlocked.",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        return
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll) {

            // Back button
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                IconButton(onClick = { if (onDismiss != null) onDismiss() else navController.popBackStack() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        "Back",
                        tint = Color.White
                    )
                }
            }

            // ── Plan selection card ──
            HeroCard {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        AppTheme.AccentPurple.copy(alpha = 0.2f),
                                        AppTheme.AccentPink.copy(alpha = 0.15f)
                                    )
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
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Your data is ready to talk",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Unlock the patterns hidden in your migraine history",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(12.dp))

                if (loading) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = AppTheme.AccentPurple,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                } else {
                    packages.sortedByDescending { it.isAnnual }.forEach { pkg ->
                        val isSelected = selectedPackage?.identifier == pkg.identifier

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .then(
                                    if (isSelected) {
                                        Modifier.border(
                                            2.dp,
                                            Brush.horizontalGradient(
                                                listOf(AppTheme.AccentPurple, Color(0xFFFF7BB0))
                                            ),
                                            RoundedCornerShape(12.dp)
                                        )
                                    } else {
                                        Modifier.border(
                                            1.dp,
                                            Color.White.copy(alpha = 0.15f),
                                            RoundedCornerShape(12.dp)
                                        )
                                    }
                                )
                                .background(
                                    if (isSelected) AppTheme.AccentPurple.copy(alpha = 0.12f)
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
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        )
                                        if (pkg.isAnnual) {
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "BEST VALUE",
                                                color = AppTheme.AccentPurple,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                modifier = Modifier
                                                    .background(
                                                        AppTheme.AccentPurple.copy(alpha = 0.15f),
                                                        RoundedCornerShape(4.dp)
                                                    )
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
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

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
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppTheme.AccentPurple,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(26.dp),
                        enabled = !purchasing && selectedPackage != null
                    ) {
                        if (purchasing) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                "Subscribe Now",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }

                    if (error != null) {
                        Text(
                            error!!,
                            color = Color(0xFFEF5350),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // ── Features card ──
            BaseCard {
                Text(
                    "What you get",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )

                val features = listOf(
                    FeatureItem(Icons.Outlined.Analytics, "Full Insights & Spider Charts", "Treatment effectiveness, trigger patterns, and more"),
                    FeatureItem(Icons.Outlined.Timeline, "7-Day Risk Forecast", "Know your migraine risk before it happens"),
                    FeatureItem(Icons.Outlined.Speed, "Active Trigger Breakdown", "See exactly what's driving your risk score"),
                    FeatureItem(Icons.Outlined.History, "Full History & Journal", "Search and filter your complete migraine history"),
                    FeatureItem(Icons.Outlined.Psychology, "AI Calibration", "Personalised AI neurologist for your risk model"),
                    FeatureItem(Icons.Outlined.Description, "PDF Reports for Doctors", "Professional reports with charts and timelines"),
                    FeatureItem(Icons.Outlined.TrendingUp, "Monitor Dashboard Trends", "Sleep, physical, mental, and nutrition history"),
                    FeatureItem(Icons.Outlined.Restaurant, "AI Food Risk Analysis", "Tyramine, gluten, and alcohol risk classification"),
                )

                features.forEach { feat ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            feat.icon,
                            contentDescription = null,
                            tint = AppTheme.AccentPurple,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                feat.title,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                feat.subtitle,
                                color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // ── Restore + Legal ──
            TextButton(
                onClick = {
                    PremiumManager.restorePurchases(
                        onSuccess = { /* state auto-updates */ },
                        onError = { msg -> error = msg }
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Restore Purchases",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    "Terms of Service",
                    color = AppTheme.SubtleTextColor.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall.copy(
                        textDecoration = TextDecoration.Underline
                    )
                )
                Text(
                    "  \u2022  ",
                    color = AppTheme.SubtleTextColor.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    "Privacy Policy",
                    color = AppTheme.SubtleTextColor.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall.copy(
                        textDecoration = TextDecoration.Underline
                    )
                )
            }

            if (onDismiss != null) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Not now",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

private data class FeatureItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String
)

