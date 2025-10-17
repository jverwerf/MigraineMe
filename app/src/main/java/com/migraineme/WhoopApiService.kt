package com.migraineme

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Minimal WHOOP v2 API client using HttpURLConnection.
 * Endpoints shown:
 * - GET /v2/recovery?start=YYYY-MM-DD&end=YYYY-MM-DD
 * - GET /v2/sleep?start=YYYY-MM-DD&end=YYYY-MM-DD
 * - GET /v2/workout?start=YYYY-MM-DD&end=YYYY-MM-DD
 *
 * Returns JSONObject so you can map into your canonicals elsewhere without changing Supabase.
 */
class WhoopApiService(private val context: Context) {
    private val base = "https://api.prod.whoop.com"

    /** Returns a valid access token, refreshing as needed */
    private fun token(): String? {
        val auth = WhoopAuthService()
        val store = WhoopTokenStore(context)
        val t = store.load() ?: return null
        return if (t.isExpiredSoon()) auth.refresh(context)?.accessToken else t.accessToken
    }

    fun getRecovery(startDate: Date, endDate: Date): JSONObject? =
        getJson("/v2/recovery", startDate, endDate)

    fun getSleep(startDate: Date, endDate: Date): JSONObject? =
        getJson("/v2/sleep", startDate, endDate)

    fun getWorkouts(startDate: Date, endDate: Date): JSONObject? =
        getJson("/v2/workout", startDate, endDate)

    // --- helpers ---

    private fun getJson(path: String, start: Date, end: Date): JSONObject? {
        val tok = token() ?: return null
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val qs = "start=${URLEncoder.encode(fmt.format(start), "UTF-8")}" +
                "&end=${URLEncoder.encode(fmt.format(end), "UTF-8")}"
        val u = java.net.URL("$base$path?$qs")
        val conn = (u.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $tok")
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }
        conn.disconnect()
        return if (code in 200..299 && text != null) JSONObject(text) else null
    }
}
