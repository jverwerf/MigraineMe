// app/src/main/java/com/migraineme/SessionStore.kt
package com.migraineme

import android.content.Context
import android.content.Context.MODE_PRIVATE

object SessionStore {
    private const val PREFS = "session_prefs"
    private const val KEY_ACCESS = "access_token"

    fun saveAccessToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCESS, token)
            .apply()
    }

    fun readAccessToken(context: Context): String? {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(KEY_ACCESS, null)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply()
    }
}
