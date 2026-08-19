package com.migraineme

/**
 * Canonical answer values for the AI-setup questionnaire.
 *
 * These strings are DATA, not display copy. They are what the chips store, what
 * `ai_setup_profiles.answers` persists, what the ai-setup edge function is
 * documented to emit (supabase/functions/ai-setup/index.ts, "=== SCHEMA ==="),
 * what [AiOnboardingParser] whitelists, and what [DeterministicMapper] matches
 * on. Every one of those four places must use the constants below so they
 * cannot drift apart again.
 *
 * They must never be translated: QSingleChips / QMultiChips call t() on the
 * option for display only and hand the raw canonical value back to the caller.
 */
object AiSetupOptions {

    // caffeine_direction
    const val CAFFEINE_TOO_MUCH = "Too much triggers it"
    const val CAFFEINE_MISSING = "Missing caffeine triggers it"
    const val CAFFEINE_BOTH = "Both ways"
    const val CAFFEINE_NOT_SURE = "Not sure"
    const val CAFFEINE_NO = "No"
    val CAFFEINE_DIRECTION = listOf(
        CAFFEINE_TOO_MUCH, CAFFEINE_MISSING, CAFFEINE_BOTH, CAFFEINE_NOT_SURE, CAFFEINE_NO,
    )

    // exercise_pattern
    const val EXERCISE_INTENSE = "During or after intense exercise"
    const val EXERCISE_INACTIVE = "When I haven't exercised"
    val EXERCISE_PATTERN = listOf(EXERCISE_INTENSE, EXERCISE_INACTIVE)

    // contraception_effect
    const val CONTRACEPTION_WORSE_EVERY_TIME = "Worse — every time"
    const val CONTRACEPTION_WORSE_SOMETIMES = "Worse — sometimes"
    const val CONTRACEPTION_NO_CHANGE = "No change"
    const val CONTRACEPTION_HELPS = "Actually helps"
    val CONTRACEPTION_EFFECT = listOf(
        CONTRACEPTION_WORSE_EVERY_TIME, CONTRACEPTION_WORSE_SOMETIMES,
        CONTRACEPTION_NO_CHANGE, CONTRACEPTION_HELPS,
    )
}
