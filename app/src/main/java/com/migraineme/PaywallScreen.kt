// FILE: app/src/main/java/com/migraineme/PaywallScreen.kt
package com.migraineme

import android.app.Activity
import androidx.compose.foundation.Image
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Composable
fun PaywallScreen(
    navController: NavController,
    onDismiss: (() -> Unit)? = null,
    headerTitle: String? = null,
    headerSubtitle: String? = null
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scrollState = rememberScrollState()

    var packages by remember { mutableStateOf<List<PackageInfo>>(emptyList()) }
    var selectedPackage by remember { mutableStateOf<PackageInfo?>(null) }
    var loading by remember { mutableStateOf(true) }
    var purchasing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var promoCode by remember { mutableStateOf("") }
    var promoLoading by remember { mutableStateOf(false) }
    var promoSuccess by remember { mutableStateOf<String?>(null) }
    var promoExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val premiumState by PremiumManager.state.collectAsState()

    // Load the real plans from the store. If they can't be loaded we show a notice
    // rather than inventing prices — see StoreUnavailableNotice for why.
    var unavailableReason by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        unavailableReason = null
        PremiumManager.getOfferings { result ->
            when (result) {
                is PremiumManager.OfferingsResult.Available -> {
                    packages = result.packages
                    selectedPackage = result.packages.firstOrNull { it.isAnnual }
                        ?: result.packages.firstOrNull()
                }
                is PremiumManager.OfferingsResult.Unavailable -> {
                    packages = emptyList()
                    selectedPackage = null
                    unavailableReason = result.reason
                }
            }
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
                    t("You're Premium!"),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    t("All features are unlocked."),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        return
    }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(
            scrollState = scroll,
            // Takeover leads with logo + headline at the top of the screen;
            // the default reveal height parks content halfway down the page.
            logoRevealHeight = if (headerTitle != null || headerSubtitle != null) 8.dp else AppTheme.LogoRevealHeight
        ) {

            // Back button
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                IconButton(onClick = { if (onDismiss != null) onDismiss() else navController.popBackStack() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        t("Back"),
                        tint = Color.White
                    )
                }
            }

            // ── Optional takeover header ──
            if (headerTitle != null || headerSubtitle != null) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.brainy_premium),
                        contentDescription = null,
                        modifier = Modifier.size(130.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    if (headerTitle != null) {
                        Text(
                            headerTitle,
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.Center
                        )
                    }
                    if (headerSubtitle != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            headerSubtitle,
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // ── Plan selection card ──
            HeroCard {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // The takeover header already leads with the pose; don't repeat it here.
                    if (headerTitle == null && headerSubtitle == null) {
                        Image(
                            painter = painterResource(id = R.drawable.brainy_premium),
                            contentDescription = null,
                            modifier = Modifier.size(130.dp)
                        )
                        Spacer(Modifier.height(4.dp))
                    }
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
                            t("PREMIUM"),
                            color = AppTheme.AccentPurple,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp
                            )
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        t("Your data is ready to talk"),
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t("Unlock the patterns hidden in your migraine history"),
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
                } else if (unavailableReason != null) {
                    StoreUnavailableNotice(onRetry = { reloadKey++ })
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
                                            if (pkg.isAnnual) t("Annual") else t("Monthly"),
                                            color = Color.White,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        )
                                        if (pkg.isAnnual) {
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                t("BEST VALUE"),
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
                                            t("Just %s/month", pkg.pricePerMonth),
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
                                t("Subscribe Now"),
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
            // Last card on the page, so it carries the watermark (see MaybeWatermarkCard).
            BrainyWatermarkCard(resId = R.drawable.brainy_recs, flipWatermark = true) {
                Text(
                    t("What you get"),
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )

                val features = listOf(
                    FeatureItem(R.drawable.brainy_detective_small, "Full Insights & Spider Charts", "Treatment effectiveness, trigger patterns, and more"),
                    FeatureItem(R.drawable.brainy_risk_small, "7-Day Risk Outlook", "See your estimated risk for the week ahead"),
                    FeatureItem(R.drawable.brainy_trigger_small, "Active Trigger Breakdown", "See exactly what's driving your risk score"),
                    FeatureItem(R.drawable.brainy_migraines_small, "Full History & Journal", "Search and filter your complete migraine history"),
                    FeatureItem(R.drawable.brainy_archer_small, "Smart Calibration", "Personalised tuning of your risk model"),
                    FeatureItem(R.drawable.brainy_briefcase_small, "PDF Reports for Doctors", "Professional reports with charts and timelines"),
                    FeatureItem(R.drawable.brainy_treatments_small, "Monitor Dashboard Trends", "Sleep, physical, mental, and nutrition history"),
                    FeatureItem(R.drawable.brainy_diet_small, "Food Risk Analysis", "Tyramine, gluten, and alcohol risk classification"),
                )

                features.forEach { feat ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        BrainyBlobIcon(resId = feat.resId)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                t(feat.title),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                t(feat.subtitle),
                                color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // ── Promo Code ──
            TextButton(onClick = { promoExpanded = !promoExpanded }) {
                Text(
                    if (promoExpanded) t("Hide promo code") else t("Have a promo code?"),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (promoExpanded) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = promoCode,
                        onValueChange = { promoCode = it.uppercase().take(30); promoSuccess = null; error = null },
                        placeholder = { Text(t("Enter code"), color = AppTheme.SubtleTextColor.copy(alpha = 0.4f)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = AppTheme.AccentPurple,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                            cursorColor = AppTheme.AccentPurple
                        ),
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { /* handled by button */ })
                    )
                    Button(
                        onClick = {
                            if (promoCode.isBlank()) return@Button
                            promoLoading = true
                            error = null
                            promoSuccess = null
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { redeemPromoCode(context, promoCode.trim()) }
                                promoLoading = false
                                if (result.success) {
                                    promoSuccess = result.message
                                    PremiumManager.loadState(context)
                                } else {
                                    error = result.message
                                }
                            }
                        },
                        enabled = promoCode.isNotBlank() && !promoLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppTheme.AccentPurple,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        if (promoLoading) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text(t("Apply"), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                if (promoSuccess != null) {
                    Text(
                        promoSuccess!!,
                        color = Color(0xFF81C784),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
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
                    t("Restore Purchases"),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Subscription terms (clear auto-renewal disclosure)
            Text(
                t("Subscription auto-renews at the price shown until cancelled. Cancel anytime in Play Store settings."),
                color = AppTheme.SubtleTextColor.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    t("Terms of Service"),
                    color = AppTheme.SubtleTextColor.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall.copy(
                        textDecoration = TextDecoration.Underline
                    ),
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.migraineme.app/terms")))
                    }
                )
                Text(
                    "  \u2022  ",
                    color = AppTheme.SubtleTextColor.copy(alpha = 0.4f),
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    t("Privacy Policy"),
                    color = AppTheme.SubtleTextColor.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.labelSmall.copy(
                        textDecoration = TextDecoration.Underline
                    ),
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.migraineme.app/privacy")))
                    }
                )
            }

            if (onDismiss != null) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        t("Not now"),
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
    @androidx.annotation.DrawableRes val resId: Int,
    val title: String,
    val subtitle: String
)

private data class PromoResult(val success: Boolean, val message: String)

private suspend fun redeemPromoCode(context: android.content.Context, code: String): PromoResult {
    val accessToken = SessionStore.getValidAccessToken(context.applicationContext) ?: return PromoResult(false, "Not signed in")
    val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    val jsonBody = """{"code":"$code"}"""
    val request = Request.Builder()
        .url("${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/redeem-promo")
        .post(jsonBody.toRequestBody("application/json".toMediaType()))
        .header("Authorization", "Bearer $accessToken")
        .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
        .build()

    return try {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            val json = org.json.JSONObject(body)
            if (response.isSuccessful && json.optBoolean("ok")) {
                val days = json.optInt("days_granted", 0)
                PromoResult(true, "🎉 $days days of Premium unlocked!")
            } else {
                PromoResult(false, json.optString("message", "Invalid promo code"))
            }
        }
    } catch (e: Exception) {
        PromoResult(false, "Connection error. Please try again.")
    }
}


