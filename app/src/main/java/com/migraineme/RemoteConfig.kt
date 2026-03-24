package com.migraineme

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * Fetches OAuth client IDs from Supabase app_config table.
 * Falls back to BuildConfig values if remote fetch fails.
 * Caches in SharedPreferences for offline access.
 */
object RemoteConfig {

    private const val TAG = "RemoteConfig"
    private const val PREFS = "remote_config"

    @Volatile
    private var loaded = false
    private val cache = mutableMapOf<String, String>()

    /**
     * Load config from Supabase. Call once at app startup.
     * Safe to call multiple times — skips if already loaded.
     */
    suspend fun load(context: Context) {
        if (loaded) return
        withContext(Dispatchers.IO) {
            try {
                val token = SessionStore.getValidAccessToken(context) ?: return@withContext
                val client = OkHttpClient()
                val url = "${BuildConfig.SUPABASE_URL}/rest/v1/app_config?select=key,value"
                val request = Request.Builder().url(url).get()
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string()
                response.close()

                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val arr = JSONArray(body)
                    val prefs = context.applicationContext
                        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val editor = prefs.edit()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val key = obj.getString("key")
                        val value = obj.getString("value")
                        cache[key] = value
                        editor.putString(key, value)
                    }
                    editor.apply()
                    loaded = true
                    Log.d(TAG, "Loaded ${arr.length()} config values")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load remote config: ${e.message}")
            }

            // If network failed, load from SharedPreferences cache
            if (!loaded) {
                loadFromPrefs(context)
            }
        }
    }

    private fun loadFromPrefs(context: Context) {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.all.forEach { (key, value) ->
            if (value is String) cache[key] = value
        }
        if (cache.isNotEmpty()) {
            loaded = true
            Log.d(TAG, "Loaded ${cache.size} config values from cache")
        }
    }

    /**
     * Get a config value. Falls back to SharedPreferences cache, then to provided default.
     */
    fun get(key: String, default: String): String {
        return cache[key] ?: default
    }

    /** Garmin client ID — falls back to BuildConfig value */
    fun garminClientId(): String = get("GARMIN_CLIENT_ID", BuildConfig.GARMIN_CLIENT_ID)

    /** WHOOP client ID — falls back to BuildConfig value */
    fun whoopClientId(): String = get("WHOOP_CLIENT_ID", BuildConfig.WHOOP_CLIENT_ID)
}
