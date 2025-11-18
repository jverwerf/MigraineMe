// FILE: C:\Users\verwe\Projects\MigraineMe\app\src\main\java\com\migraineme\WhoopSyncLogStore.kt
package com.migraineme

import android.content.Context
import java.time.Instant

/**
 * Rolling log for WHOOP sync. Stored in SharedPreferences.
 * Keeps up to MAX_CHARS characters.
 */
class WhoopSyncLogStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun append(line: String) {
        val ts = Instant.now().toString()
        val entry = "$ts | $line\n"
        val cur = prefs.getString(KEY_LOG, "") ?: ""
        var out = cur + entry
        if (out.length > MAX_CHARS) {
            out = out.substring(out.length - MAX_CHARS)
            val idx = out.indexOf('\n')
            if (idx >= 0) out = out.substring(idx + 1)
        }
        prefs.edit().putString(KEY_LOG, out).apply()
    }

    fun get(): String? = prefs.getString(KEY_LOG, null)

    fun clear() {
        prefs.edit().remove(KEY_LOG).apply()
    }

    companion object {
        private const val PREFS = "whoop_sync_log_prefs"
        private const val KEY_LOG = "log"
        private const val MAX_CHARS = 8000
    }
}
