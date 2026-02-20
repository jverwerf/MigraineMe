// FILE: app/src/main/java/com/migraineme/PremiumState.kt
package com.migraineme

/**
 * Represents the user's premium status across the app.
 *
 * Sources of truth:
 *  - Supabase `premium_status` table → owns the 30-day app trial
 *  - RevenueCat → owns paid subscription state
 *
 * PremiumManager merges both into this single state.
 */
enum class PremiumTier {
    /** 30-day free trial (auto-granted on signup) */
    TRIAL,
    /** Active paid subscription via RevenueCat */
    PREMIUM,
    /** Trial expired, no active subscription */
    FREE
}

data class PremiumState(
    val tier: PremiumTier = PremiumTier.FREE,

    /** Days remaining in trial (0 if not in trial) */
    val trialDaysRemaining: Int = 0,

    /** ISO timestamp when trial ends (null if not in trial) */
    val trialEndDate: String? = null,

    /** Whether the state has been loaded from backend */
    val isLoaded: Boolean = false,

    /** RevenueCat subscription expiry (null if no subscription) */
    val subscriptionExpiryDate: String? = null,

    /** Which plan the user is on: "monthly", "annual", or null */
    val planType: String? = null
) {
    /** True if user has access to premium features (trial OR paid) */
    val isPremium: Boolean
        get() = tier == PremiumTier.TRIAL || tier == PremiumTier.PREMIUM

    /** True if user is in trial and should see trial banner */
    val showTrialBanner: Boolean
        get() = tier == PremiumTier.TRIAL

    /** True if trial is ending soon (last 7 days) — banner becomes more prominent */
    val isTrialUrgent: Boolean
        get() = tier == PremiumTier.TRIAL && trialDaysRemaining <= 7

    /** True if state hasn't loaded yet — show loading/skeleton, not paywall */
    val isLoading: Boolean
        get() = !isLoaded
}
