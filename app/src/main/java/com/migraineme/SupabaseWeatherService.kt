package com.migraineme

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.LocalDate

/**
 * Minimal REST client for Supabase table: weather_daily
 * Uses BuildConfig.SUPABASE_URL and BuildConfig.SUPABASE_ANON_KEY.
 * No external HTTP libs required (HttpURLConnection + org.json).
 */
object SupabaseWeatherService {

    private val baseUrl: String = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY

    data class WeatherDailyRow(
        val id: String,
        val user_id: String,
        val date: String,
        val temp_c: Double? = null,
        val pressure_hpa: Double? = null,
        val humidity_pct: Double? = null
    )

    /**
     * Upsert a single row identified by (user_id, date).
     */
    suspend fun upsertWeatherDaily(
        accessToken: String,
        userId: String,
        date: LocalDate,
        tempC: Double?,
        pressureHpa: Double?,
        humidityPct: Double?
    ) {
        val url = "$baseUrl/rest/v1/weather_daily?on_conflict=user_id,date"

        // Supabase expects an array for bulk insert/upsert
        val payload = JSONArray().put(
            JSONObject().apply {
                put("user_id", userId)
                put("date", date.toString()) // yyyy-MM-dd
                if (tempC != null) put("temp_c", tempC)
                if (pressureHpa != null) put("pressure_hpa", pressureHpa)
                if (humidityPct != null) put("humidity_pct", humidityPct)
            }
        )

        httpPostJson(url, accessToken, payload.toString())
        // We ignore the response body; Supabase returns the row(s) if Prefer header is set.
    }

    /**
     */
    suspend fun listWeatherDaily(
        accessToken: String,
        start: LocalDate,
        end: LocalDate
    ): List<WeatherDailyRow> {
        // Supabase PostgREST filter syntax
        val url = "$baseUrl/rest/v1/weather_daily?date=gte.${start}&date=lte.${end}&order=date.asc"
        val body = httpGet(url, accessToken)
        val arr = JSONArray(body ?: "[]")
        val out = ArrayList<WeatherDailyRow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                WeatherDailyRow(
                    id = o.optString("id"),
                    user_id = o.optString("user_id"),
                    date = o.optString("date"),
                    temp_c = if (o.isNull("temp_c")) null else o.getDouble("temp_c"),
                    pressure_hpa = if (o.isNull("pressure_hpa")) null else o.getDouble("pressure_hpa"),
                    humidity_pct = if (o.isNull("humidity_pct")) null else o.getDouble("humidity_pct")
                )
            )
        }
        return out
    }

    // ----------------------- HTTP helpers -----------------------

    private fun open(urlStr: String, method: String, accessToken: String): HttpURLConnection {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            doInput = true
            setRequestProperty("apikey", anonKey)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        return conn
    }

    private fun httpPostJson(urlStr: String, accessToken: String, json: String): String? {
        val conn = open(urlStr, "POST", accessToken).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // Prefer return=representation is optional; we don’t need the response, but keep it consistent
            setRequestProperty("Prefer", "return=representation,resolution=merge-duplicates")
        }
        try {
            BufferedWriter(OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8)).use { w ->
                w.write(json)
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let { BufferedReader(InputStreamReader(it)).use { br -> br.readText() } }
            if (code !in 200..299) {
                throw RuntimeException("Supabase POST failed ($code): $body")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGet(urlStr: String, accessToken: String): String? {
        val conn = open(urlStr, "GET", accessToken)
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let { BufferedReader(InputStreamReader(it)).use { br -> br.readText() } }
            if (code !in 200..299) {
                throw RuntimeException("Supabase GET failed ($code): $body")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }
}
