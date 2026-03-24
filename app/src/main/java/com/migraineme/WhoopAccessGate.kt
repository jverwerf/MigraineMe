package com.migraineme

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gates WHOOP connection behind a manual approval table in Supabase.
 *
 * Table: whoop_access
 *   - user_id  UUID PRIMARY KEY REFERENCES auth.users(id)
 *   - status   TEXT NOT NULL DEFAULT 'pending'   ('pending' | 'approved')
 *   - requested_at  TIMESTAMPTZ DEFAULT now()
 *
 * SQL to create:
 *   CREATE TABLE IF NOT EXISTS whoop_access (
 *     user_id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
 *     status TEXT NOT NULL DEFAULT 'pending',
 *     requested_at TIMESTAMPTZ DEFAULT now()
 *   );
 *   ALTER TABLE whoop_access ENABLE ROW LEVEL SECURITY;
 *   CREATE POLICY "Users can read own row" ON whoop_access FOR SELECT USING (auth.uid() = user_id);
 *   CREATE POLICY "Users can insert own row" ON whoop_access FOR INSERT WITH CHECK (auth.uid() = user_id);
 */
object WhoopAccessGate {

    enum class AccessStatus { APPROVED, PENDING, NONE }

    private const val TAG = "WhoopAccessGate"

    /**
     * Check current user's WHOOP access status.
     */
    suspend fun checkAccess(context: Context): AccessStatus = withContext(Dispatchers.IO) {
        try {
            val token = SessionStore.getValidAccessToken(context) ?: return@withContext AccessStatus.NONE
            val userId = SessionStore.readUserId(context) ?: return@withContext AccessStatus.NONE

            val client = OkHttpClient()
            val url = "${BuildConfig.SUPABASE_URL}/rest/v1/whoop_access" +
                    "?user_id=eq.$userId&select=status&limit=1"
            val request = Request.Builder().url(url).get()
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()

            if (response.isSuccessful && !body.isNullOrBlank()) {
                val arr = JSONArray(body)
                if (arr.length() > 0) {
                    val status = arr.getJSONObject(0).optString("status", "pending")
                    return@withContext if (status == "approved") AccessStatus.APPROVED else AccessStatus.PENDING
                }
            }
            AccessStatus.NONE
        } catch (e: Exception) {
            Log.e(TAG, "checkAccess failed: ${e.message}")
            AccessStatus.NONE
        }
    }

    /**
     * Request WHOOP access — inserts a "pending" row for the current user.
     * Returns true if successfully inserted.
     */
    suspend fun requestAccess(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = SessionStore.getValidAccessToken(context) ?: return@withContext false
            val userId = SessionStore.readUserId(context) ?: return@withContext false

            val client = OkHttpClient()
            val url = "${BuildConfig.SUPABASE_URL}/rest/v1/whoop_access"
            val json = JSONObject().apply {
                put("user_id", userId)
                put("status", "pending")
            }
            val request = Request.Builder().url(url)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Prefer", "return=minimal")
                .build()

            val response = client.newCall(request).execute()
            response.close()
            val ok = response.isSuccessful || response.code == 409 // 409 = already exists
            if (!ok) Log.e(TAG, "requestAccess failed: ${response.code}")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "requestAccess failed: ${e.message}")
            false
        }
    }
}
