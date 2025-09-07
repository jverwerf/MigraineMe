package com.migraineme

import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Minimal REST client to list migraine/medicine/relief timestamps from Supabase.
 * Uses BuildConfig.SUPABASE_URL and BuildConfig.SUPABASE_ANON_KEY.
 * No external libs (HttpURLConnection + org.json).
 */
object SupabaseEventsService {

    private val baseUrl: String = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY

    // --- Public API: fetch ISO8601 strings for each table within [start..end] ---
    suspend fun listMigraineBegins(accessToken: String, start: LocalDate, end: LocalDate): List<String> =
        getIsoList(
            path = "migraines",
            accessToken = accessToken,
            // began_at between start 00:00Z and end 23:59:59Z
            filters = listOf(
                "began_at=gte.${start}T00:00:00Z",
                "began_at=lte.${end}T23:59:59Z"
            ),
            select = "began_at",
            order = "began_at.asc"
        )

    suspend fun listMedicineTaken(accessToken: String, start: LocalDate, end: LocalDate): List<String> =
        getIsoList(
            path = "medicines",
            accessToken = accessToken,
            filters = listOf(
                "taken_at=gte.${start}T00:00:00Z",
                "taken_at=lte.${end}T23:59:59Z"
            ),
            select = "taken_at",
            order = "taken_at.asc"
        )

    suspend fun listReliefTaken(accessToken: String, start: LocalDate, end: LocalDate): List<String> =
        getIsoList(
            path = "reliefs",
            accessToken = accessToken,
            filters = listOf(
                "taken_at=gte.${start}T00:00:00Z",
                "taken_at=lte.${end}T23:59:59Z"
            ),
            select = "taken_at",
            order = "taken_at.asc"
        )

    // --- Helpers ---

    private fun open(urlStr: String, accessToken: String): HttpURLConnection {
        val url = URL(urlStr)
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("apikey", anonKey)
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
    }

    private fun httpGet(urlStr: String, accessToken: String): String {
        val conn = open(urlStr, accessToken)
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            if (code !in 200..299) {
                throw RuntimeException("Supabase GET $urlStr failed ($code): $body")
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun getIsoList(
        path: String,
        accessToken: String,
        filters: List<String>,
        select: String,
        order: String
    ): List<String> {
        // Build URL like: /rest/v1/migraines?select=began_at&began_at=gte....&began_at=lte....&order=began_at.asc
        val base = "$baseUrl/rest/v1/$path?select=$select"
        val url = buildString {
            append(base)
            filters.forEach { f -> append("&").append(f) }
            append("&order=").append(order)
        }
        val body = httpGet(url, accessToken)
        val arr = JSONArray(body)
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            // select has just one column; take its value
            val key = select // e.g., "began_at"
            if (!o.isNull(key)) out.add(o.getString(key))
        }
        return out
    }
}
