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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Central premium state manager. Merges two sources of truth:
 *
 * 1. Supabase `premium_status` table — owns the 14-day app-level free trial.
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
            Log.d(TAG, "initialize() called — userId=$userId, isConfigured=${Purchases.isConfigured}")
            if (!Purchases.isConfigured) {
                Log.d(TAG, "Configuring RevenueCat with key=${REVENUECAT_API_KEY.take(10)}...")
                Purchases.configure(
                    PurchasesConfiguration.Builder(context, REVENUECAT_API_KEY).build()
                )
                Log.d(TAG, "RevenueCat configured successfully")
            }

            if (!userId.isNullOrBlank()) {
                Log.d(TAG, "Logging in to RevenueCat with userId=$userId")
                Purchases.sharedInstance.logIn(userId, object : LogInCallback {
                    override fun onReceived(customerInfo: CustomerInfo, created: Boolean) {
                        Log.d(TAG, "RevenueCat login success — created=$created, entitlements=${customerInfo.entitlements.active.keys}")
                        updateFromRevenueCat(customerInfo)
                    }
                    override fun onError(error: PurchasesError) {
                        Log.e(TAG, "RevenueCat login error: ${error.code} — ${error.message}")
                    }
                })
            } else {
                Log.w(TAG, "No userId provided — skipping RevenueCat login")
            }
        } catch (e: Exception) {
            Log.e(TAG, "RevenueCat init failed: ${e.message}", e)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // State Loading
    // ═══════════════════════════════════════════════════════════

    /**
     * Resolves entitlement, on-device sources first.
     *
     * Order matters, and it is the opposite of what it used to be. RevenueCat
     * answers out of its own disk cache and returns in milliseconds whether or
     * not there is a network — so asking it first means a paying user's state
     * is settled almost immediately, including on a cold launch in aeroplane
     * mode. Only when RevenueCat says "no active subscription" do we need the
     * Supabase round trip that decides trial vs free, and only that case can
     * be slow. Previously the (blocking, up to 10s) Supabase read ran first and
     * held every gated surface in limbo behind it.
     *
     * This never blocks on the network to grant access the user already has:
     * a failed trial read falls back to the last trial_end this device saw,
     * honoured only until it expires.
     */
    suspend fun loadState(context: Context) {
        withContext(Dispatchers.IO) {
            val appCtx = context.applicationContext

            val rcState = loadFromRevenueCat()
            if (rcState.isSubscribed) {
                // Paid access is the strongest answer there is and needs nothing
                // from the server. Publish it and stop — no user who is paying
                // should wait on Supabase to find out they are premium.
                _state.update {
                    PremiumState(
                        tier = PremiumTier.PREMIUM,
                        isLoaded = true,
                        subscriptionExpiryDate = rcState.expiryDate,
                        planType = rcState.planType
                    )
                }
                Log.d(TAG, "Premium state loaded from RevenueCat: PREMIUM (plan=${rcState.planType})")
                return@withContext
            }

            val accessToken = SessionStore.getValidAccessToken(appCtx)
            if (accessToken.isNullOrBlank()) {
                _state.update { PremiumState(isLoaded = true) }
                return@withContext
            }

            val userId = SessionStore.readUserId(appCtx)
                ?: JwtUtils.extractUserIdFromAccessToken(accessToken)
                ?: run { _state.update { PremiumState(isLoaded = true) }; return@withContext }
            val trialState = loadTrialFromSupabase(accessToken, userId)

            // Only a request that actually came back is allowed to change what
            // this device believes about the trial. A read that failed (offline,
            // 5xx) must not be mistaken for "this user has no trial" — that is
            // how a mid-trial user gets locked out on a train.
            if (trialState.reachedServer) saveCachedTrialEnd(appCtx, userId, trialState.trialEnd)

            val current = _state.value
            val localTrialActive = current.tier == PremiumTier.TRIAL &&
                current.trialEndDate?.let {
                    runCatching { Instant.parse(it).isAfter(Instant.now()) }.getOrDefault(false)
                } == true

            // Last trial_end this device was told about, used only when the
            // server could not be reached, and only while it is still in future.
            val cachedTrialEnd = if (trialState.reachedServer) null else readCachedTrialEnd(appCtx, userId)
            val cachedTrialActive = cachedTrialEnd?.let {
                runCatching { Instant.parse(it).isAfter(Instant.now()) }.getOrDefault(false)
            } == true

            val tier = when {
                trialState.isDbSubscribed -> PremiumTier.PREMIUM
                trialState.isTrialActive -> PremiumTier.TRIAL
                // Preserve a freshly-started local trial when the Supabase row
                // hasn't propagated yet (race after onboarding skip).
                localTrialActive -> PremiumTier.TRIAL
                cachedTrialActive -> PremiumTier.TRIAL
                else -> PremiumTier.FREE
            }

            // Neither source could actually be asked and there is no live
            // cached trial to fall back on, so nothing is known. Publishing
            // FREE here would be a guess, and a guess that puts padlocks in
            // front of subscribers. Stay unresolved instead — every gated
            // surface shows its neutral placeholder — and let the next
            // loadState (app resume, or RevenueCat finishing configuration)
            // settle it.
            if (!rcState.consulted && !trialState.reachedServer &&
                !localTrialActive && !cachedTrialActive
            ) {
                Log.w(TAG, "Entitlement unresolved: RevenueCat and Supabase both unavailable")
                return@withContext
            }

            val resolvedTrialEnd = trialState.trialEnd
                ?: cachedTrialEnd
                ?: if (tier == PremiumTier.TRIAL) current.trialEndDate else null

            _state.update {
                PremiumState(
                    tier = tier,
                    trialDaysRemaining = when {
                        trialState.isTrialActive -> trialState.daysRemaining
                        tier == PremiumTier.TRIAL -> daysRemainingUntil(resolvedTrialEnd) ?: current.trialDaysRemaining
                        else -> 0
                    },
                    trialEndDate = resolvedTrialEnd,
                    isLoaded = true,
                    subscriptionExpiryDate = rcState.expiryDate,
                    planType = rcState.planType
                )
            }

            Log.d(TAG, "Premium state loaded: tier=$tier, trialDays=${_state.value.trialDaysRemaining}, reachedServer=${trialState.reachedServer}, rcConsulted=${rcState.consulted}")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Last-known trial end (offline fallback)
    // ═══════════════════════════════════════════════════════════

    private const val PREMIUM_PREFS = "premium_prefs"

    /**
     * Keyed by user, deliberately. Signing out is not the only thing that
     * reaches for a reset — MainActivity resets whenever it has no token in
     * hand, which includes the moment before the stored session has been read
     * on every cold launch. A single global key was therefore wiped on the way
     * into the app, exactly when it was about to be needed. Per-user keys also
     * mean one account's trial can never be read against another's.
     */
    private fun trialEndKey(userId: String) = "cached_trial_end_$userId"

    private fun saveCachedTrialEnd(context: Context, userId: String, trialEnd: String?) {
        val editor = context.getSharedPreferences(PREMIUM_PREFS, Context.MODE_PRIVATE).edit()
        if (trialEnd.isNullOrBlank()) editor.remove(trialEndKey(userId))
        else editor.putString(trialEndKey(userId), trialEnd)
        editor.apply()
    }

    private fun readCachedTrialEnd(context: Context, userId: String): String? =
        context.getSharedPreferences(PREMIUM_PREFS, Context.MODE_PRIVATE)
            .getString(trialEndKey(userId), null)
            ?.takeIf { it.isNotBlank() }

    private fun clearCachedTrialEnd(context: Context, userId: String) {
        context.getSharedPreferences(PREMIUM_PREFS, Context.MODE_PRIVATE)
            .edit().remove(trialEndKey(userId)).apply()
    }

    /** Ceil partial days, same rule the server read uses. */
    private fun daysRemainingUntil(trialEnd: String?): Int? {
        val end = trialEnd?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        val secondsLeft = ChronoUnit.SECONDS.between(Instant.now(), end).coerceAtLeast(0)
        return ((secondsLeft + 86399) / 86400).toInt()
    }

    // ═══════════════════════════════════════════════════════════
    // Supabase Trial
    // ═══════════════════════════════════════════════════════════

    private data class TrialInfo(
        val isTrialActive: Boolean = false,
        val daysRemaining: Int = 0,
        val trialEnd: String? = null,
        val isDbSubscribed: Boolean = false,
        /**
         * Whether the row was actually read. A default [TrialInfo] means two
         * very different things — "this user has no trial" and "we could not
         * ask" — and only the first may be allowed to revoke access.
         */
        val reachedServer: Boolean = false
    )

    private fun loadTrialFromSupabase(accessToken: String, userId: String): TrialInfo {
        return try {
            val url = "${BuildConfig.SUPABASE_URL}/rest/v1/premium_status?user_id=eq.$userId&select=trial_end,rc_subscription_status&limit=1"
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
                // Read succeeded from here on: an empty array really is
                // "no trial row for this user".
                if (arr.length() == 0) return TrialInfo(reachedServer = true)

                val row = arr.getJSONObject(0)
                val trialEndStr = row.optString("trial_end", "")
                val rcStatus = row.optString("rc_subscription_status", "")
                if (trialEndStr.isBlank()) {
                    return TrialInfo(isDbSubscribed = rcStatus == "active", reachedServer = true)
                }

                val trialEnd = Instant.parse(trialEndStr)
                val now = Instant.now()
                // Ceil partial days so a fresh 14-day trial doesn't immediately read "13".
                val secondsLeft = ChronoUnit.SECONDS.between(now, trialEnd).coerceAtLeast(0)
                val daysRemaining = ((secondsLeft + 86399) / 86400).toInt()

                TrialInfo(
                    isTrialActive = now.isBefore(trialEnd),
                    daysRemaining = daysRemaining.coerceAtLeast(0),
                    trialEnd = trialEndStr,
                    isDbSubscribed = rcStatus == "active",
                    reachedServer = true
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
        val planType: String? = null,
        /**
         * Whether RevenueCat actually answered. False means it was not yet
         * configured, errored, or timed out — none of which are the same as
         * "this user has no subscription", and none of which may be used to
         * take premium away from someone.
         */
        val consulted: Boolean = false
    )

    private fun loadFromRevenueCat(): SubscriptionInfo {
        return try {
            // configure() happens in initialize(), which a concurrent loadState
            // (app resume racing first launch) can beat. Not an answer.
            if (!Purchases.isConfigured) return SubscriptionInfo()

            var result = SubscriptionInfo()
            val latch = CountDownLatch(1)

            Purchases.sharedInstance.getCustomerInfo(object : ReceiveCustomerInfoCallback {
                override fun onReceived(customerInfo: CustomerInfo) {
                    result = parseCustomerInfo(customerInfo).copy(consulted = true)
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

    /**
     * Outcome of loading the store's purchasable plans.
     *
     * [Unavailable] is deliberately a distinct case rather than an empty list. Callers
     * must never substitute hardcoded prices for it: doing so produces a paywall that
     * looks sellable but whose buy button cannot work, which is exactly how a Play
     * misconfiguration went unnoticed for two weeks.
     */
    sealed class OfferingsResult {
        data class Available(val packages: List<PackageInfo>) : OfferingsResult()
        /** [reason] is diagnostic, for logs and support. Show users a fixed message. */
        data class Unavailable(val reason: String) : OfferingsResult()
    }

    fun getOfferings(onResult: (OfferingsResult) -> Unit) {
        Log.d(TAG, "getOfferings() called — isConfigured=${Purchases.isConfigured}")
        if (!Purchases.isConfigured) {
            Log.w(TAG, "getOfferings: RevenueCat not configured")
            onResult(OfferingsResult.Unavailable("RevenueCat not configured"))
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
                            val perMonth = pkg.product.price.amountMicros / 1_000_000.0 / 12.0
                            java.text.NumberFormat.getCurrencyInstance().apply {
                                currency = java.util.Currency.getInstance(pkg.product.price.currencyCode)
                            }.format(perMonth)
                        } else {
                            null
                        },
                        isAnnual = pkg.identifier.contains("annual", ignoreCase = true),
                        rcPackage = pkg
                    )
                } ?: emptyList()

                if (packages.isEmpty()) {
                    // Google Play returned no product details for the configured products.
                    // Usually means the subscription is not on sale in this user's country.
                    Log.e(
                        TAG,
                        "getOfferings: store returned no purchasable packages " +
                            "(current offering=${offerings.current?.identifier})"
                    )
                    onResult(OfferingsResult.Unavailable("Store returned no purchasable packages"))
                } else {
                    onResult(OfferingsResult.Available(packages))
                }
            }
            override fun onError(error: PurchasesError) {
                Log.e(TAG, "Failed to get offerings: ${error.message}")
                onResult(OfferingsResult.Unavailable(error.message))
            }
        })
    }

    fun purchase(
        activity: android.app.Activity,
        packageInfo: PackageInfo,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        Log.d(TAG, "purchase() called — isConfigured=${Purchases.isConfigured}, rcPackage=${packageInfo.rcPackage != null}, productId=${packageInfo.productId}")
        if (!Purchases.isConfigured || packageInfo.rcPackage == null) {
            // Should be unreachable: the paywalls only offer packages that came back
            // from getOfferings as Available, so every one of them has a real rcPackage.
            Log.e(TAG, "Purchase blocked — isConfigured=${Purchases.isConfigured}, rcPackage=${packageInfo.rcPackage != null}")
            onError("Subscriptions aren't available right now. Please try again in a moment.")
            return
        }

        Purchases.sharedInstance.purchase(
            PurchaseParams.Builder(activity, packageInfo.rcPackage!!).build(),
            object : PurchaseCallback {
                override fun onCompleted(storeTransaction: StoreTransaction, customerInfo: CustomerInfo) {
                    Log.d(TAG, "Purchase SUCCESS — product=${storeTransaction.productIds}, entitlements=${customerInfo.entitlements.active.keys}")
                    updateFromRevenueCat(customerInfo)
                    onSuccess()
                }
                override fun onError(error: PurchasesError, userCancelled: Boolean) {
                    Log.e(TAG, "Purchase FAILED — cancelled=$userCancelled, code=${error.code}, message=${error.message}")
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
            onError("The store isn't ready yet. Please try again in a moment.")
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
    // Onboarding Trial (14 days)
    // ═══════════════════════════════════════════════════════════

    /**
     * Starts a 14-day onboarding trial so new users can experience all
     * premium features for free. Writes trial_end to the Supabase
     * premium_status table and updates local state so PremiumGate unblurs.
     */
    suspend fun startOnboardingTrial(context: Context) {
        withContext(Dispatchers.IO) {
            val appCtx = context.applicationContext
            val accessToken = SessionStore.getValidAccessToken(appCtx) ?: return@withContext
            val userId = SessionStore.readUserId(appCtx)
                ?: JwtUtils.extractUserIdFromAccessToken(accessToken)
                ?: return@withContext

            // Read-before-write: if this user has already received a trial,
            // hydrate from the existing row and skip the upsert so reruns of
            // onboarding can't extend or re-grant the 14 days.
            val existing = loadTrialFromSupabase(accessToken, userId)
            if (!existing.trialEnd.isNullOrBlank()) {
                saveCachedTrialEnd(appCtx, userId, existing.trialEnd)
                _state.update {
                    it.copy(
                        tier = if (existing.isTrialActive) PremiumTier.TRIAL else PremiumTier.FREE,
                        trialDaysRemaining = existing.daysRemaining,
                        trialEndDate = existing.trialEnd,
                        isLoaded = true
                    )
                }
                Log.d(TAG, "startOnboardingTrial: existing trial_end=${existing.trialEnd}, skipping upsert")
                return@withContext
            }

            val now = Instant.now()
            val trialEnd = now.plus(14, ChronoUnit.DAYS)
            val trialEndStr = trialEnd.toString()

            // Optimistic local update so UI reflects trial immediately
            _state.update {
                it.copy(
                    tier = PremiumTier.TRIAL,
                    trialDaysRemaining = 14,
                    trialEndDate = trialEndStr,
                    isLoaded = true
                )
            }

            try {
                val jsonBody = """{"user_id":"$userId","trial_end":"$trialEndStr"}"""
                val url = "${BuildConfig.SUPABASE_URL}/rest/v1/premium_status?on_conflict=user_id"
                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .header("Authorization", "Bearer $accessToken")
                    .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .header("Prefer", "resolution=merge-duplicates,return=minimal")
                    .header("Content-Type", "application/json")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    // Only a trial the server accepted is allowed into the
                    // offline cache. Caching the optimistic one would let a
                    // trial that was never granted survive across launches.
                    if (response.isSuccessful) saveCachedTrialEnd(appCtx, userId, trialEndStr)
                    Log.d(TAG, "startOnboardingTrial: ${response.code}, trialEnd=$trialEndStr")
                }
            } catch (e: Exception) {
                Log.e(TAG, "startOnboardingTrial failed: ${e.message}", e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Convenience
    // ═══════════════════════════════════════════════════════════

    /**
     * Clears the onboarding trial locally and from Supabase (e.g. when the user skips onboarding).
     */
    suspend fun clearOnboardingTrial(context: Context) {
        withContext(Dispatchers.IO) {
            _state.update { PremiumState(isLoaded = true) }
            val appCtx = context.applicationContext
            val accessToken = SessionStore.getValidAccessToken(appCtx) ?: return@withContext
            val userId = SessionStore.readUserId(appCtx)
                ?: JwtUtils.extractUserIdFromAccessToken(accessToken)
                ?: return@withContext
            clearCachedTrialEnd(appCtx, userId)
            try {
                val url = "${BuildConfig.SUPABASE_URL}/rest/v1/premium_status?user_id=eq.$userId"
                val request = Request.Builder()
                    .url(url)
                    .delete()
                    .header("Authorization", "Bearer $accessToken")
                    .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    Log.d(TAG, "clearOnboardingTrial: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "clearOnboardingTrial failed: ${e.message}", e)
            }
        }
    }

    val isPremium: Boolean
        get() = _state.value.isPremium

    /**
     * No session in hand. Drops back to LOADING rather than FREE, because
     * nothing is known about whoever signs in next — and this also runs on the
     * way into a cold launch, before the stored session has been read.
     *
     * Leaves the cached trial_end alone: it is keyed by user, so it cannot be
     * read against the wrong account, and wiping it here would destroy the
     * offline fallback on every single launch.
     */
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
