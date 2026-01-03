// app/src/main/java/com/migraineme/SessionStore.kt
package com.migraineme

import android.content.Context
import android.content.Context.MODE_PRIVATE

object SessionStore {
    private const val PREFS = "session_prefs"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_USER_ID = "user_id"

    fun saveAccessToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCESS, token)
            .apply()
    }

    /**
     * Saves the user id separately so UI (e.g., Profile) can display a stable identity
     * without needing a network call.
     */
    fun saveUserId(context: Context, userId: String?) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_USER_ID, userId)
            .apply()
    }

    /**
     * Convenience method to store both token + derived user id together.
     * Keeps existing call sites compatible (they can still call saveAccessToken()).
     */
    fun saveSession(context: Context, token: String, userId: String?) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCESS, token)
            .putString(KEY_USER_ID, userId)
            .apply()
    }

    fun readAccessToken(context: Context): String? {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_ACCESS, null)
    }

    fun readUserId(context: Context): String? {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_USER_ID, null)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply()
    }
}
