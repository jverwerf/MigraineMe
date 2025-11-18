// FILE: C:\Users\verwe\Projects\MigraineMe\app\src\main\java\com\migraineme\WhoopApiService.kt
package com.migraineme

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * WHOOP Developer API client (v2).
 * Adds getSleepWithCode() that returns HTTP status + error snippet for UI logging.
 * Auth mode/storage unchanged.
 */
class WhoopApiService(private val context: Context) {
    private val base = "https://api.prod.whoop.com/developer/v2"
    private val logTag = "WhoopDailySync"

    data class HttpResult(val json: JSONObject?, val httpCode: Int, val errorSummary: String?)

    fun getSleep(startDate: Date, endDate: Date): JSONObject? =
        getSleepWithCode(startDate, endDate).json

    fun getSleepWithCode(startDate: Date, endDate: Date): HttpResult =
        fetchPagedWithCode("/activity/sleep", startDate, endDate)

    fun getRecovery(startDate: Date, endDate: Date): JSONObject? =
        fetchPagedWithCode("/recovery", startDate, endDate).json

    fun getWorkouts(startDate: Date, endDate: Date): JSONObject? =
        fetchPagedWithCode("/activity/workout", startDate, endDate).json

    private fun token(): String? {
        val store = WhoopTokenStore(context)
        val t = store.load() ?: return null
        // Try refresh if expiring soon; if refresh fails, we'll still call once to capture server code
        return if (t.isExpiredSoon()) WhoopAuthService().refresh(context)?.accessToken else t.accessToken
    }

    /** Merge all pages into {"records":[...]} and return HTTP code and small error snippet if non-2xx. */
    private fun fetchPagedWithCode(path: String, start: Date, end: Date): HttpResult {
        val tok = token() ?: run {
            Log.w(logTag, "HTTP $path -> no token")
            return HttpResult(null, 0, "no token")
        }

        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val startIso = iso.format(start)
        val endIso = iso.format(end)

        val all = JSONArray()
        var nextToken: String? = null
        var page = 0
        var lastHttp = 200
        var lastErr: String? = null

        do {
            val qs = buildString {
                append("start=").append(URLEncoder.encode(startIso, "UTF-8"))
                append("&end=").append(URLEncoder.encode(endIso, "UTF-8"))
                append("&limit=25")
                if (!nextToken.isNullOrBlank()) {
                    append("&nextToken=").append(URLEncoder.encode(nextToken!!, "UTF-8"))
                }
            }
            val fullUrl = "$base$path?$qs"
            val url = java.net.URL(fullUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $tok")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "MigraineMe/1.0 (Android)")
                connectTimeout = 15000
                readTimeout = 30000
            }

            val code = try { conn.responseCode } catch (t: Throwable) {
                Log.e(logTag, "HTTP open $path failed: ${t.message} url=$fullUrl", t)
                conn.disconnect()
                return HttpResult(null, 0, "network: ${t.message}")
            }
            lastHttp = code

            if (code == HttpURLConnection.HTTP_NO_CONTENT) {
                Log.d(logTag, "HTTP $path page=$page -> 204 No Content url=$fullUrl")
                conn.disconnect()
                break
            }

            val body = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.use { it.readText() }
            } catch (t: Throwable) {
                Log.e(logTag, "HTTP read $path failed: ${t.message} url=$fullUrl", t)
                null
            } finally {
                conn.disconnect()
            }

            if (code !in 200..299) {
                val snippet = body?.take(120)
                Log.w(logTag, "HTTP $path -> $code bodyLen=${body?.length ?: 0} url=$fullUrl")
                lastErr = "code=$code ${snippet ?: ""}".trim()
                // Stop on non-2xx to surface the reason (e.g., 401) to UI/worker
                return HttpResult(null, code, snippet)
            }
            if (body.isNullOrBlank()) {
                Log.w(logTag, "HTTP $path -> 2xx empty body (page=$page) url=$fullUrl")
                break
            }

            val obj = try { JSONObject(body) } catch (t: Throwable) {
                Log.e(logTag, "JSON parse $path failed: ${t.message}. body=${body.take(200)} url=$fullUrl")
                return HttpResult(null, 0, "json:${t.message}")
            }

            val records = obj.optJSONArray("records") ?: JSONArray()
            for (i in 0 until records.length()) all.put(records.get(i))

            val raw = obj.optString("next_token")
            nextToken = raw.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

            Log.d(logTag, "HTTP $path page=$page -> ${records.length()} recs, nextToken=${if (nextToken == null) "none" else "present"}")
            page++
        } while (!nextToken.isNullOrBlank())

        return HttpResult(JSONObject().put("records", all), lastHttp, lastErr)
    }
}
