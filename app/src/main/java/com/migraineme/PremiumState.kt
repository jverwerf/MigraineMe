// FILE: app/src/main/java/com/migraineme/PremiumState.kt
package com.migraineme

/**
 * Represents the user's premium status across the app.
 *
 * Sources of truth:
 *  - Supabase `premium_status` table → owns the 14-day app trial
 *  - RevenueCat → owns paid subscription state
 *
 * PremiumManager merges both into this single state.
 */
enum class PremiumTier {
    /** 14-day free trial (started from onboarding) */
    TRIAL,
    /** Active paid subscription via RevenueCat */
    PREMIUM,
    /** Trial expired, no active subscription */
    FREE
}

/**
 * What a gated surface is allowed to draw right now.
 *
 * Three states, deliberately — "entitled" and "not entitled" are not the whole
 * story, and collapsing [LOADING] into either of them is a bug in one of two
 * directions:
 *
 *  - folded into [ENTITLED], the gate fails OPEN and free users get every
 *    premium surface unblurred for as long as entitlement takes to resolve;
 *  - folded into [NOT_ENTITLED], the gate fails CLOSED and paying users are
 *    shown padlocks and upsells for something they already bought.
 *
 * [LOADING] must therefore render neither: no premium content, and no lock,
 * blur, price or "Upgrade" call to action. A quiet placeholder that holds the
 * layout's shape, and inert controls, until the real answer lands.
 */
enum class PremiumAccess {
    /** Entitlement has not resolved yet. Show a neutral placeholder. */
    LOADING,
    /** Trial or paid subscription — render the real thing. */
    ENTITLED,
    /** Resolved as free tier — blur, lock and upsell are correct here. */
    NOT_ENTITLED
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
    /**
     * The one place the three-state answer is derived. Every gated surface
     * should branch on this rather than on a pair of booleans, so that the
     * loading case cannot be forgotten at a call site.
     */
    val access: PremiumAccess
        get() = when {
            !isLoaded -> PremiumAccess.LOADING
            tier == PremiumTier.TRIAL || tier == PremiumTier.PREMIUM -> PremiumAccess.ENTITLED
            else -> PremiumAccess.NOT_ENTITLED
        }

    /**
     * True only once entitlement has resolved AND grants access (trial OR paid).
     *
     * Note what this is not: `!isPremium` does not mean "free tier", it means
     * "not known to be premium", which is also true while loading. Anything
     * that shows a lock or an upsell must test [access] instead.
     */
    val isPremium: Boolean
        get() = access == PremiumAccess.ENTITLED

    /** True if user is in trial and should see trial banner */
    val showTrialBanner: Boolean
        get() = tier == PremiumTier.TRIAL

    /** True if trial is ending soon (last 7 days) — banner becomes more prominent */
    val isTrialUrgent: Boolean
        get() = tier == PremiumTier.TRIAL && trialDaysRemaining <= 7

    /** True if state hasn't loaded yet — show loading/skeleton, not paywall */
    val isLoading: Boolean
        get() = access == PremiumAccess.LOADING
}
