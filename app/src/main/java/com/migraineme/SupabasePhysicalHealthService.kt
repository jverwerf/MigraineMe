// FILE: app/src/main/java/com/migraineme/SupabasePhysicalHealthService.kt
package com.migraineme

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Supabase REST wrappers for Physical Health daily tables.
 * Tables expected:
 *   recovery_daily(date text, value_pct numeric, source text, source_id text)
 *   rhr_daily(date text, value_bpm numeric, source text, source_id text)
 *   hrv_daily(date text, value_ms numeric, source text, source_id text)
 *   skin_temp_daily(date text, value_c numeric, source text, source_id text)
 *   spo2_daily(date text, value_pct numeric, source text, source_id text)
 *   high_hr_time_daily(date text, value_minutes integer, source text, source_id text)
 */
class SupabasePhysicalHealthService(private val context: Context) {

    private val supabaseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey = BuildConfig.SUPABASE_ANON_KEY

    /* ---------- existence / latest ---------- */

    fun hasRecoveryForDate(accessToken: String, dateIso: String, source: String): Boolean {
        val qp = buildString {
            append("select=date")
            append("&date=eq.").append(encode(dateIso))
            append("&source=eq.").append(encode(source))
            append("&limit=1")
        }
        val (code, body) = httpGet("/rest/v1/recovery_daily", qp, accessToken)
        if (code !in 200..299) return false
        return try { JSONArray(body).length() > 0 } catch (_: Throwable) { false }
    }

    /** Uses recovery as the anchor for "latest" marker. */
    fun latestPhysicalHealthDate(accessToken: String): String? {
        val qp = "select=date&order=date.desc&limit=1"
        val (code, body) = httpGet("/rest/v1/recovery_daily", qp, accessToken)
        if (code !in 200..299 || body.isBlank() || body == "[]") return null
        return try { JSONArray(body).getJSONObject(0).optString("date").takeIf { it.isNotBlank() } } catch (_: Throwable) { null }
    }

    /* ---------- upserts ---------- */

    fun upsertRecoveryDaily(accessToken: String, dateIso: String, valuePct: Double, source: String, sourceId: String?) {
        upsertOne(
            accessToken, "recovery_daily",
            JSONObject().put("date", dateIso).put("value_pct", valuePct).put("source", source).put("source_id", sourceId)
        )
    }

    fun upsertRhrDaily(accessToken: String, dateIso: String, valueBpm: Double, source: String, sourceId: String?) {
        upsertOne(
            accessToken, "rhr_daily",
            JSONObject().put("date", dateIso).put("value_bpm", valueBpm).put("source", source).put("source_id", sourceId)
        )
    }

    fun upsertHrvDaily(accessToken: String, dateIso: String, valueMs: Double, source: String, sourceId: String?) {
        upsertOne(
            accessToken, "hrv_daily",
            JSONObject().put("date", dateIso).put("value_ms", valueMs).put("source", source).put("source_id", sourceId)
        )
    }

    fun upsertSkinTempDaily(accessToken: String, dateIso: String, valueC: Double, source: String, sourceId: String?) {
        upsertOne(
            accessToken, "skin_temp_daily",
            JSONObject().put("date", dateIso).put("value_c", valueC).put("source", source).put("source_id", sourceId)
        )
    }

    fun upsertSpO2Daily(accessToken: String, dateIso: String, valuePct: Double, source: String, sourceId: String?) {
        upsertOne(
            accessToken, "spo2_daily",
            JSONObject().put("date", dateIso).put("value_pct", valuePct).put("source", source).put("source_id", sourceId)
        )
    }

    fun upsertHighHrTimeDaily(accessToken: String, dateIso: String, valueMinutes: Int, source: String, sourceId: String?) {
        upsertOne(
            accessToken, "high_hr_time_daily",
            JSONObject().put("date", dateIso).put("value_minutes", valueMinutes).put("source", source).put("source_id", sourceId)
        )
    }

    /* ---------- reads for Testing ---------- */

    data class RecoveryRead(val date: String, val value_pct: Double)
    data class RhrRead(val date: String, val value_bpm: Double)
    data class HrvRead(val date: String, val value_ms: Double)
    data class SkinTempRead(val date: String, val value_c: Double)
    data class SpO2Read(val date: String, val value_pct: Double)
    data class HighHrTimeRead(val date: String, val value_minutes: Int)

    fun fetchRecoveryDaily(accessToken: String, limitDays: Int = 180): List<RecoveryRead> {
        val qp = "select=date,value_pct&order=date.desc&limit=$limitDays"
        val (code, body) = httpGet("/rest/v1/recovery_daily", qp, accessToken)
        if (code !in 200..299 || body.isBlank() || body == "[]") return emptyList()
        val arr = JSONArray(body)
        val out = ArrayList<RecoveryRead>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(RecoveryRead(o.optString("date"), o.optDouble("value_pct")))
        }
        return out
    }

    fun fetchRhrDaily(accessToken: String, limitDays: Int = 180): List<RhrRead> {
        val qp = "select=date,value_bpm&order=date.desc&limit=$limitDays"
        val (code, body) = httpGet("/rest/v1/rhr_daily", qp, accessToken)
        if (code !in 200..299 || body.isBlank() || body == "[]") return emptyList()
        val arr = JSONArray(body)
        val out = ArrayList<RhrRead>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(RhrRead(o.optString("date"), o.optDouble("value_bpm")))
        }
        return out
    }

    fun fetchHrvDaily(accessToken: String, limitDays: Int = 180): List<HrvRead> {
        val qp = "select=date,value_ms&order=date.desc&limit=$limitDays"
        val (code, body) = httpGet("/rest/v1/hrv_daily", qp, accessToken)
        if (code !in 200..299 || body.isBlank() || body == "[]") return emptyList()
        val arr = JSONArray(body)
        val out = ArrayList<HrvRead>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(HrvRead(o.optString("date"), o.optDouble("value_ms")))
        }
        return out
    }

    fun fetchSkinTempDaily(accessToken: String, limitDays: Int = 180): List<SkinTempRead> {
        val qp = "select=date,value_c&order=date.desc&limit=$limitDays"
        val (code, body) = httpGet("/rest/v1/skin_temp_daily", qp, accessToken)
        if (code !in 200..299 || body.isBlank() || body == "[]") return emptyList()
        val arr = JSONArray(body)
        val out = ArrayList<SkinTempRead>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(SkinTempRead(o.optString("date"), o.optDouble("value_c")))
        }
        return out
    }

    fun fetchSpO2Daily(accessToken: String, limitDays: Int = 180): List<SpO2Read> {
        val qp = "select=date,value_pct&order=date.desc&limit=$limitDays"
        val (code, body) = httpGet("/rest/v1/spo2_daily", qp, accessToken)
        if (code !in 200..299 || body.isBlank() || body == "[]") return emptyList()
        val arr = JSONArray(body)
        val out = ArrayList<SpO2Read>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(SpO2Read(o.optString("date"), o.optDouble("value_pct")))
        }
        return out
    }

    fun fetchHighHrTimeDaily(accessToken: String, limitDays: Int = 180): List<HighHrTimeRead> {
        val qp = "select=date,value_minutes&order=date.desc&limit=$limitDays"
        val (code, body) = httpGet("/rest/v1/high_hr_time_daily", qp, accessToken)
        if (code !in 200..299 || body.isBlank() || body == "[]") return emptyList()
        val arr = JSONArray(body)
        val out = ArrayList<HighHrTimeRead>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(HighHrTimeRead(o.optString("date"), o.optInt("value_minutes")))
        }
        return out
    }

    /* ---------- HTTP helpers ---------- */

    private fun httpGet(path: String, query: String, bearer: String): Pair<Int, String> {
        val urlStr = "$supabaseUrl$path?$query"
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("apikey", anonKey)
            setRequestProperty("Authorization", "Bearer $bearer")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15000
            readTimeout = 30000
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = BufferedReader(stream.reader()).use { it.readText() }
        conn.disconnect()
        return code to body
    }

    private fun upsertOne(accessToken: String, table: String, obj: JSONObject) {
        val urlStr = "$supabaseUrl/rest/v1/$table?on_conflict=date,source"
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("apikey", anonKey)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Prefer", "resolution=merge-duplicates")
            connectTimeout = 15000
            readTimeout = 30000
        }
        val payload = JSONArray().put(obj).toString()
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
        val code = conn.responseCode
        // Drain stream to allow connection reuse (don’t bind to reserved '_' names)
        val ignoreBytes = (if (code in 200..299) conn.inputStream else conn.errorStream)
        ignoreBytes?.use { it.readBytes() }
        conn.disconnect()
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
}
