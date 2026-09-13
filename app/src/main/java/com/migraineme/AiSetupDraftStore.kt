package com.migraineme

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Local, per-user draft of a first-run AI setup so a process death mid-flow
 * (the app killed right after apply, a crash, low memory) resumes on the same
 * page with the answers kept instead of restarting from the story page.
 *
 * Everything in AiSetupScreen lives in plain `remember` state and the answers
 * only reach ai_setup_profiles AFTER apply, so before this the whole
 * questionnaire was lost on any process death. The draft is written on every
 * state change while in first-run mode (never in edit mode), keyed by the
 * signed-in user id, and cleared when apply finishes, when the user skips
 * setup, or when onboarding completes. It is never cleared on process death.
 *
 * `answers` is the same JSON shape AiSetupProfileStore writes to
 * ai_setup_profiles.answers, so the restore goes through the exact same
 * reader (`preFillFromAnswers`) as a redo / edit.
 */
object AiSetupDraftStore {

    private const val PREFS = "ai_setup_draft"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_STORY_TEXT = "story_text"
    private const val KEY_STORY_PARSED = "story_parsed"
    private const val KEY_PAGE = "page"
    private const val KEY_ANSWERS = "answers"
    private const val KEY_SAVED_AT = "saved_at"

    data class Draft(
        val storyText: String,
        val storyParsed: Boolean,
        /** AiPage name as saved; the screen maps PROCESSING/RESULTS/COMPANIONS back to NOTES. */
        val page: String,
        val answers: JsonObject,
        val savedAt: Long,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when a draft exists for the currently signed-in user. Cheap: no JSON parse. */
    fun exists(context: Context): Boolean {
        val userId = SessionStore.readUserId(context) ?: return false
        val p = prefs(context)
        return p.getString(KEY_USER_ID, null) == userId && p.getString(KEY_PAGE, null) != null
    }

    /** The draft for the signed-in user, or null (none, or it belongs to another user). */
    fun load(context: Context): Draft? {
        val userId = SessionStore.readUserId(context) ?: return null
        val p = prefs(context)
        if (p.getString(KEY_USER_ID, null) != userId) return null
        val page = p.getString(KEY_PAGE, null) ?: return null
        val answers = runCatching {
            json.parseToJsonElement(p.getString(KEY_ANSWERS, null) ?: "{}").jsonObject
        }.getOrNull() ?: JsonObject(emptyMap())
        return Draft(
            storyText = p.getString(KEY_STORY_TEXT, null) ?: "",
            storyParsed = p.getBoolean(KEY_STORY_PARSED, false),
            page = page,
            answers = answers,
            savedAt = p.getLong(KEY_SAVED_AT, 0L),
        )
    }

    /**
     * Writes the draft synchronously (commit, not apply) so it is on disk
     * before the caller moves on: a process death right after is the whole
     * reason this exists. Call off the main thread.
     */
    fun save(context: Context, storyText: String, storyParsed: Boolean, page: String, answersJson: String) {
        val userId = SessionStore.readUserId(context) ?: return
        prefs(context).edit()
            .putString(KEY_USER_ID, userId)
            .putString(KEY_STORY_TEXT, storyText)
            .putBoolean(KEY_STORY_PARSED, storyParsed)
            .putString(KEY_PAGE, page)
            .putString(KEY_ANSWERS, answersJson)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .commit()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
