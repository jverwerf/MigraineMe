// FILE: app/src/main/java/com/migraineme/PremiumManager.kt
package com.migraineme

import android.content.Context
import android.util.Log
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Offerings
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesError
import com.revenuecat.purchases.interfaces.LogInCallback
import com.revenuecat.purchases.interfaces.PurchaseCallback
import com.revenuecat.purchases.interfaces.ReceiveCustomerInfoCallback
import com.revenuecat.purchases.interfaces.ReceiveOfferingsCallback
import com.revenuecat.purchases.models.StoreTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Central premium state manager. Merges two sources of truth:
 *
 * 1. Supabase `premium_status` table — owns the 30-day app-level free trial.
 * 2. RevenueCat — owns paid subscription state (monthly/annual).
 *
 * Rule: if EITHER source says premium → user is premium.
 */
object PremiumManager {

    private const val TAG = "PremiumManager"
    private const val ENTITLEMENT_ID = "premium"
    private val REVENUECAT_API_KEY = BuildConfig.REVENUECAT_API_KEY

    private val _state = MutableStateFlow(PremiumState())
    val state: StateFlow<PremiumState> = _state

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // ═══════════════════════════════════════════════════════════
    // Initialization
    // ═══════════════════════════════════════════════════════════

    fun initialize(context: Context, userId: String?) {
        try {
            if (!Purchases.isConfigured) {
                Purchases.configure(
                    PurchasesConfiguration.Builder(context, REVENUECAT_API_KEY).build()
                )
            }

            if (!userId.isNullOrBlank()) {
                Purchases.sharedInstance.logIn(userId, object : LogInCallback {
                    override fun onReceived(customerInfo: CustomerInfo, created: Boolean) {
                        updateFromRevenueCat(customerInfo)
                    }
                    override fun onError(error: PurchasesError) {
                        Log.w(TAG, "RevenueCat login error: ${error.message}")
                    }
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "RevenueCat init failed: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // State Loading
    // ═══════════════════════════════════════════════════════════

    suspend fun loadState(context: Context) {
        withContext(Dispatchers.IO) {
            val appCtx = context.applicationContext
            val accessToken = SessionStore.getValidAccessToken(appCtx)
            if (accessToken.isNullOrBlank()) {
                _state.update { PremiumState(isLoaded = true) }
                return@withContext
            }

            val trialState = loadTrialFromSupabase(accessToken)
            val rcState = loadFromRevenueCat()

            val tier = when {
                rcState.isSubscribed -> PremiumTier.PREMIUM
                trialState.isDbSubscribed -> PremiumTier.PREMIUM
                trialState.isTrialActive -> PremiumTier.TRIAL
                else -> PremiumTier.FREE
            }

            _state.update {
                PremiumState(
                    tier = tier,
                    trialDaysRemaining = trialState.daysRemaining,
                    trialEndDate = trialState.trialEnd,
                    isLoaded = true,
                    subscriptionExpiryDate = rcState.expiryDate,
                    planType = rcState.planType
                )
            }

            Log.d(TAG, "Premium state loaded: tier=$tier, trialDays=${trialState.daysRemaining}, subscribed=${rcState.isSubscribed}")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Supabase Trial
    // ═══════════════════════════════════════════════════════════

    private data class TrialInfo(
        val isTrialActive: Boolean = false,
        val daysRemaining: Int = 0,
        val trialEnd: String? = null,
        val isDbSubscribed: Boolean = false
    )

    private fun loadTrialFromSupabase(accessToken: String): TrialInfo {
        return try {
            val url = "${BuildConfig.SUPABASE_URL}/rest/v1/premium_status?select=trial_end,rc_subscription_status&limit=1"
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer $accessToken")
                .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to load trial: ${response.code}")
                    return TrialInfo()
                }

                val body = response.body?.string() ?: return TrialInfo()
                val arr = org.json.JSONArray(body)
                if (arr.length() == 0) return TrialInfo()

                val row = arr.getJSONObject(0)
                val trialEndStr = row.optString("trial_end", "")
                val rcStatus = row.optString("rc_subscription_status", "")
                if (trialEndStr.isBlank()) return TrialInfo()

                val trialEnd = Instant.parse(trialEndStr)
                val now = Instant.now()
                val daysRemaining = ChronoUnit.DAYS.between(now, trialEnd).toInt()

                TrialInfo(
                    isTrialActive = now.isBefore(trialEnd),
                    daysRemaining = daysRemaining.coerceAtLeast(0),
                    trialEnd = trialEndStr,
                    isDbSubscribed = rcStatus == "active"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Trial load error: ${e.message}", e)
            TrialInfo()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // RevenueCat Subscription
    // ═══════════════════════════════════════════════════════════

    private data class SubscriptionInfo(
        val isSubscribed: Boolean = false,
        val expiryDate: String? = null,
        val planType: String? = null
    )

    private fun loadFromRevenueCat(): SubscriptionInfo {
        return try {
            if (!Purchases.isConfigured) return SubscriptionInfo()

            var result = SubscriptionInfo()
            val latch = CountDownLatch(1)

            Purchases.sharedInstance.getCustomerInfo(object : ReceiveCustomerInfoCallback {
                override fun onReceived(customerInfo: CustomerInfo) {
                    result = parseCustomerInfo(customerInfo)
                    latch.countDown()
                }
                override fun onError(error: PurchasesError) {
                    Log.w(TAG, "RevenueCat error: ${error.message}")
                    latch.countDown()
                }
            })

            latch.await(5, TimeUnit.SECONDS)
            result
        } catch (e: Exception) {
            Log.e(TAG, "RevenueCat load error: ${e.message}", e)
            SubscriptionInfo()
        }
    }

    private fun updateFromRevenueCat(customerInfo: CustomerInfo) {
        val rcState = parseCustomerInfo(customerInfo)
        _state.update { current ->
            val tier = when {
                rcState.isSubscribed -> PremiumTier.PREMIUM
                current.tier == PremiumTier.TRIAL -> PremiumTier.TRIAL
                else -> PremiumTier.FREE
            }
            current.copy(
                tier = tier,
                subscriptionExpiryDate = rcState.expiryDate,
                planType = rcState.planType,
                isLoaded = true
            )
        }
    }

    private fun parseCustomerInfo(info: CustomerInfo): SubscriptionInfo {
        val entitlement = info.entitlements[ENTITLEMENT_ID]
        val isActive = entitlement?.isActive == true

        val planType = when {
            entitlement?.productIdentifier?.contains("annual") == true -> "annual"
            entitlement?.productIdentifier?.contains("monthly") == true -> "monthly"
            else -> null
        }

        return SubscriptionInfo(
            isSubscribed = isActive,
            expiryDate = entitlement?.expirationDate?.toString(),
            planType = planType
        )
    }

    // ═══════════════════════════════════════════════════════════
    // Purchase Flow
    // ═══════════════════════════════════════════════════════════

    fun getOfferings(onResult: (List<PackageInfo>) -> Unit) {
        if (!Purchases.isConfigured) {
            onResult(emptyList())
            return
        }

        Purchases.sharedInstance.getOfferings(object : ReceiveOfferingsCallback {
            override fun onReceived(offerings: Offerings) {
                val packages = offerings.current?.availablePackages?.map { pkg ->
                    PackageInfo(
                        identifier = pkg.identifier,
                        productId = pkg.product.id,
                        price = pkg.product.price.formatted,
                        pricePerMonth = if (pkg.identifier.contains("annual", ignoreCase = true)) {
                            val annual = pkg.product.price.amountMicros / 1_000_000.0
                            String.format("%.2f", annual / 12.0)
                        } else {
                            null
                        },
                        isAnnual = pkg.identifier.contains("annual", ignoreCase = true),
                        rcPackage = pkg
                    )
                } ?: emptyList()
                onResult(packages)
            }
            override fun onError(error: PurchasesError) {
                Log.e(TAG, "Failed to get offerings: ${error.message}")
                onResult(emptyList())
            }
        })
    }

    fun purchase(
        activity: android.app.Activity,
        packageInfo: PackageInfo,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!Purchases.isConfigured || packageInfo.rcPackage == null) {
            onError("Billing not configured")
            return
        }

        Purchases.sharedInstance.purchase(
            PurchaseParams.Builder(activity, packageInfo.rcPackage!!).build(),
            object : PurchaseCallback {
                override fun onCompleted(storeTransaction: StoreTransaction, customerInfo: CustomerInfo) {
                    updateFromRevenueCat(customerInfo)
                    onSuccess()
                }
                override fun onError(error: PurchasesError, userCancelled: Boolean) {
                    if (userCancelled) {
                        onError("Purchase cancelled")
                    } else {
                        onError(error.message)
                    }
                }
            }
        )
    }

    fun restorePurchases(
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!Purchases.isConfigured) {
            onError("Billing not configured")
            return
        }

        Purchases.sharedInstance.restorePurchases(object : ReceiveCustomerInfoCallback {
            override fun onReceived(customerInfo: CustomerInfo) {
                updateFromRevenueCat(customerInfo)
                onSuccess()
            }
            override fun onError(error: PurchasesError) {
                onError(error.message)
            }
        })
    }

    // ═══════════════════════════════════════════════════════════
    // Convenience
    // ═══════════════════════════════════════════════════════════

    val isPremium: Boolean
        get() = _state.value.isPremium

    fun reset() {
        _state.update { PremiumState() }
        if (Purchases.isConfigured) {
            Purchases.sharedInstance.logOut()
        }
    }
}

data class PackageInfo(
    val identifier: String,
    val productId: String,
    val price: String,
    val pricePerMonth: String? = null,
    val isAnnual: Boolean = false,
    internal val rcPackage: Package? = null
)
