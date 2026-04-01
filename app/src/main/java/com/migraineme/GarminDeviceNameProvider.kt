package com.migraineme

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Provides the Garmin device display label (e.g. "Garmin Venu 3").
 *
 * Loads device_name from the garmin_tokens Supabase table once per session,
 * caches it in GarminTokenStore SharedPreferences.
 *
 * Usage:
 *   val label = GarminDeviceNameProvider.getLabel(context)  // "Garmin Venu 3" or "Garmin"
 *   GarminDeviceNameProvider.preload(context)               // call once at startup
 */
object GarminDeviceNameProvider {

    private const val TAG = "GarminDeviceName"

    @Volatile
    private var cachedLabel: String? = null

    /**
     * Returns the Garmin display label synchronously.
     * If not yet loaded, falls back to SharedPreferences cache, then "Garmin".
     */
    fun getLabel(context: Context): String {
        cachedLabel?.let { return it }

        // Try SharedPreferences cache
        val stored = GarminTokenStore(context).loadDeviceName()
        if (!stored.isNullOrBlank() && !stored.equals("null", ignoreCase = true)) {
            val label = formatLabel(stored)
            cachedLabel = label
            return label
        }

        return "Garmin"
    }

    /**
     * Preload device name from Supabase garmin_tokens table.
     * Call this once during app startup or when Garmin connection is established.
     */
    suspend fun preload(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val token = SessionStore.getValidAccessToken(context) ?: return@withContext
                val userId = SessionStore.readUserId(context) ?: return@withContext

                val client = okhttp3.OkHttpClient()
                val url = "${BuildConfig.SUPABASE_URL}/rest/v1/garmin_tokens" +
                        "?user_id=eq.$userId&select=device_name&limit=1"
                val request = okhttp3.Request.Builder().url(url).get()
                    .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrBlank()) {
                    val arr = org.json.JSONArray(body)
                    if (arr.length() > 0) {
                        val deviceName = arr.getJSONObject(0).optString("device_name", "")
                        if (deviceName.isNotBlank()) {
                            GarminTokenStore(context).saveDeviceName(deviceName)
                            cachedLabel = formatLabel(deviceName)
                            Log.d(TAG, "Loaded device name: $deviceName -> ${cachedLabel}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load device name: ${e.message}")
            }
        }
    }

    /**
     * Update the cached device name (e.g. after Garmin auth callback sets it).
     */
    fun update(context: Context, deviceName: String) {
        GarminTokenStore(context).saveDeviceName(deviceName)
        cachedLabel = formatLabel(deviceName)
    }

    /**
     * Clear cached label (e.g. on Garmin disconnect).
     */
    fun clear() {
        cachedLabel = null
    }

    /**
     * Format: if deviceName already starts with "Garmin", use as-is.
     * Otherwise prefix with "Garmin ".
     */
    private fun formatLabel(deviceName: String): String {
        if (deviceName.isBlank() || deviceName.equals("null", ignoreCase = true)) return "Garmin"
        return if (deviceName.startsWith("Garmin", ignoreCase = true)) {
            deviceName
        } else {
            "Garmin $deviceName"
        }
    }
}
