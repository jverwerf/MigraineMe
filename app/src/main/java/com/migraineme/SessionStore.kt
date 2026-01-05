// FILE: app/src/main/java/com/migraineme/SessionStore.kt
package com.migraineme

import android.content.Context
import android.content.Context.MODE_PRIVATE

object SessionStore {
    private const val PREFS = "session_prefs"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_AUTH_PROVIDER = "auth_provider" // "email" | "google" | null

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
     * Persists which auth method was used for this session.
     * Expected values: "email", "google"
     */
    fun saveAuthProvider(context: Context, provider: String?) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_AUTH_PROVIDER, provider)
            .apply()
    }

    fun readAuthProvider(context: Context): String? {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_AUTH_PROVIDER, null)
    }

    /**
     * Convenience method to store token + derived user id (+ provider) together.
     * Existing call sites that pass only (context, token, userId) still compile because provider has a default.
     */
    fun saveSession(context: Context, token: String, userId: String?, provider: String? = null) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCESS, token)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_AUTH_PROVIDER, provider)
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
