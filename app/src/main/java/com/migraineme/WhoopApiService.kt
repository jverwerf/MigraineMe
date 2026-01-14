// FILE: app/src/main/java/com/migraineme/WhoopApiService.kt
package com.migraineme

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * WHOOP Developer API client (v2).
 *
 * Uses ISO-8601 start/end (UTC) and pagination (next_token), merging into {"records":[...]}.
 * Auth mode/storage unchanged: always uses WhoopAuthService().refreshIfNeeded(context) to obtain a valid WhoopToken.
 */
class WhoopApiService(private val context: Context) {

    private val base = "https://api.prod.whoop.com/developer/v2"
    private val logTag = "WHOOP"

    data class HttpResult(val json: JSONObject?, val httpCode: Int, val errorSummary: String?)

    /**
     * Worker helper: Sleep records for a time window.
     */
    suspend fun getSleep(startDate: Date, endDate: Date): JSONObject? =
        getSleepWithCode(startDate, endDate).json

    /**
     * Same as getSleep() but returns HTTP code + short error snippet for logging/debugging.
     */
    suspend fun getSleepWithCode(startDate: Date, endDate: Date): HttpResult =
        fetchPagedWithCode("/activity/sleep", startDate, endDate)

    /**
     * Worker helper: Recovery records for a time window.
     */
    suspend fun getRecovery(startDate: Date, endDate: Date): JSONObject? =
        fetchPagedWithCode("/recovery", startDate, endDate).json

    /**
     * Worker helper: Workout records for a time window.
     */
    suspend fun getWorkouts(startDate: Date, endDate: Date): JSONObject? =
        fetchPagedWithCode("/activity/workout", startDate, endDate).json

    /**
     * Returns a valid WHOOP token (refreshing if needed). Null => not connected / cannot refresh.
     * This keeps the auth approach we standardized elsewhere.
     */
    private suspend fun token(): WhoopToken? {
        return WhoopAuthService().refreshIfNeeded(context)
    }

    /** Merge all pages into {"records":[...]} and return HTTP code and small error snippet if non-2xx. */
    private suspend fun fetchPagedWithCode(path: String, start: Date, end: Date): HttpResult {
        val tok = token() ?: run {
            Log.w(logTag, "GET $path -> no WHOOP token (not connected)")
            return HttpResult(null, 0, "no token")
        }

        // Your working format: ISO-8601 UTC strings (NOT epoch millis)
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
                    // keep your request param name
                    append("&nextToken=").append(URLEncoder.encode(nextToken!!, "UTF-8"))
                }
            }

            val fullUrl = "$base$path?$qs"
            val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "${tok.tokenType} ${tok.accessToken}")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "MigraineMe/1.0 (Android)")
                connectTimeout = 15000
                readTimeout = 30000
            }

            val code = try {
                conn.responseCode
            } catch (t: Throwable) {
                Log.e(logTag, "HTTP open $path failed: ${t.message} url=$fullUrl", t)
                runCatching { conn.disconnect() }
                return HttpResult(null, 0, "network: ${t.message}")
            }

            lastHttp = code

            if (code == HttpURLConnection.HTTP_NO_CONTENT) {
                Log.d(logTag, "GET $path page=$page -> 204 No Content url=$fullUrl")
                runCatching { conn.disconnect() }
                break
            }

            val body = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.use { s ->
                    BufferedReader(InputStreamReader(s)).readText()
                }
            } catch (t: Throwable) {
                Log.e(logTag, "HTTP read $path failed: ${t.message} url=$fullUrl", t)
                null
            } finally {
                runCatching { conn.disconnect() }
            }

            if (code !in 200..299) {
                val snippet = body?.take(400).orEmpty()
                Log.w(logTag, "GET $path -> HTTP $code bodyLen=${body?.length ?: 0} url=$fullUrl")
                lastErr = "code=$code ${snippet.take(120)}".trim()
                // Stop immediately on non-2xx so workers surface the reason (401/403/etc)
                return HttpResult(null, code, snippet.take(120))
            }

            if (body.isNullOrBlank()) {
                Log.w(logTag, "GET $path -> 2xx empty body (page=$page) url=$fullUrl")
                break
            }

            val obj = try {
                JSONObject(body)
            } catch (t: Throwable) {
                Log.e(logTag, "JSON parse $path failed: ${t.message}. body=${body.take(200)} url=$fullUrl")
                return HttpResult(null, 0, "json:${t.message}")
            }

            val records = obj.optJSONArray("records") ?: JSONArray()
            for (i in 0 until records.length()) all.put(records.get(i))

            // Your working response field name
            val raw = obj.optString("next_token")
            nextToken = raw.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }

            Log.d(
                logTag,
                "GET $path page=$page -> ${records.length()} recs, nextToken=${if (nextToken == null) "none" else "present"}"
            )
            page++
        } while (!nextToken.isNullOrBlank())

        return HttpResult(JSONObject().put("records", all), lastHttp, lastErr)
    }
}
