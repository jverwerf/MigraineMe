package com.migraineme

import android.content.Context
import android.util.Log
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.random.Random

object DemoDataSeeder {

    private const val TAG = "DemoSeeder"
    private const val SOURCE = "demo"
    private const val DAYS = 7

    // ── Safety-net tracking ─────────────────────────────────────────────────
    private const val PREFS_NAME = "demo_seeder"
    private const val KEY_DEMO_CLEARED = "demo_cleared"

    private fun isDemoCleared(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEMO_CLEARED, false)

    private fun markDemoCleared(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DEMO_CLEARED, true).apply()
    }

    // ── Seed ────────────────────────────────────────────────────────────────

    suspend fun seedDemoData(context: Context) {
        val appCtx = context.applicationContext
        val token = SessionStore.getValidAccessToken(appCtx) ?: run {
            Log.e(TAG, "No access token"); return
        }
        val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
        val metrics = SupabaseMetricsService(appCtx)
        val physical = SupabasePhysicalHealthService(appCtx)
        val personal = SupabasePersonalService(appCtx)
        val today = LocalDate.now(ZoneId.systemDefault())

        withContext(Dispatchers.IO) {
            try {
                seedSleep(token, metrics, today)
                seedPhysical(token, physical, today)
                seedScreenTime(token, personal, today)
                seedWeather(token, today)
                seedNutrition(token, today)
                seedMigraines(token, db, today)
                Log.d(TAG, "Demo data seeded successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to seed demo data: ${e.message}")
            }
        }
    }

    private suspend fun seedSleep(token: String, metrics: SupabaseMetricsService, today: LocalDate) {
        for (i in 0 until DAYS) {
            val date = today.minusDays(i.toLong()).toString()
            val isWeekend = today.minusDays(i.toLong()).dayOfWeek.value >= 6
            val duration = 6.0 + Random.nextDouble(2.5) - if (isWeekend) 0.5 else 0.0
            val score = 55.0 + Random.nextDouble(40.0)
            val efficiency = 75.0 + Random.nextDouble(20.0)
            val disturbances = Random.nextInt(8)
            val bedHour = 22 + Random.nextInt(2)
            val bedMin = Random.nextInt(60)
            val wakeHour = 6 + Random.nextInt(2)
            val wakeMin = Random.nextInt(60)
            try {
                metrics.upsertSleepDurationDaily(token, date, (duration * 100).roundToInt() / 100.0, SOURCE, "$SOURCE-$i")
                metrics.upsertSleepScoreDaily(token, date, (score * 10).roundToInt() / 10.0, SOURCE, "$SOURCE-$i")
                metrics.upsertSleepEfficiencyDaily(token, date, (efficiency * 10).roundToInt() / 10.0, SOURCE, "$SOURCE-$i")
                metrics.upsertSleepDisturbancesDaily(token, date, disturbances, SOURCE, "$SOURCE-$i")
                metrics.upsertFellAsleepTimeDaily(token, date, "${date}T${bedHour.toString().padStart(2, '0')}:${bedMin.toString().padStart(2, '0')}:00", SOURCE, "$SOURCE-$i")
                metrics.upsertWokeUpTimeDaily(token, date, "${date}T${wakeHour.toString().padStart(2, '0')}:${wakeMin.toString().padStart(2, '0')}:00", SOURCE, "$SOURCE-$i")
            } catch (e: Exception) { Log.w(TAG, "Sleep seed day $i: ${e.message}") }
        }
    }

    private suspend fun seedPhysical(token: String, physical: SupabasePhysicalHealthService, today: LocalDate) {
        for (i in 0 until DAYS) {
            val date = today.minusDays(i.toLong()).toString()
            val recovery = 30.0 + Random.nextDouble(65.0)
            val restingHr = 52.0 + Random.nextDouble(16.0)
            val hrv = 35.0 + Random.nextDouble(60.0)
            val spo2 = 94.0 + Random.nextDouble(5.0)
            val skinTemp = 33.0 + Random.nextDouble(2.0)
            try {
                physical.upsertRecoveryScoreDaily(token, date, (recovery * 10).roundToInt() / 10.0, SOURCE, "$SOURCE-$i")
                physical.upsertRestingHrDaily(token, date, (restingHr * 10).roundToInt() / 10.0, SOURCE, "$SOURCE-$i")
                physical.upsertHrvDaily(token, date, (hrv * 10).roundToInt() / 10.0, SOURCE, "$SOURCE-$i")
                physical.upsertSpo2Daily(token, date, (spo2 * 10).roundToInt() / 10.0, SOURCE, "$SOURCE-$i")
                physical.upsertSkinTempDaily(token, date, (skinTemp * 100).roundToInt() / 100.0, SOURCE, "$SOURCE-$i")
            } catch (e: Exception) { Log.w(TAG, "Physical seed day $i: ${e.message}") }
        }
    }

    private suspend fun seedScreenTime(token: String, personal: SupabasePersonalService, today: LocalDate) {
        for (i in 0 until DAYS) {
            val date = today.minusDays(i.toLong()).toString()
            val hours = 2.0 + Random.nextDouble(5.0)
            try {
                personal.upsertScreenTimeDaily(token, date, (hours * 100).roundToInt() / 100.0, SOURCE)
            } catch (e: Exception) { Log.w(TAG, "Screen seed day $i: ${e.message}") }
        }
    }

    private suspend fun seedWeather(token: String, today: LocalDate) {
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')
        val key = BuildConfig.SUPABASE_ANON_KEY
        val client = HttpClient()
        try {
            for (i in 0 until DAYS) {
                val date = today.minusDays(i.toLong()).toString()
                val temp = 8.0 + Random.nextDouble(18.0)
                val pressure = 990.0 + Random.nextDouble(40.0)
                val humidity = 30.0 + Random.nextDouble(60.0)
                val wind = 1.0 + Random.nextDouble(12.0)
                val uv = Random.nextDouble(10.0)
                try {
                    val body = buildJsonObject {
                        put("date", date)
                        put("source", SOURCE)
                        put("temp_c_mean", (temp * 10).roundToInt() / 10.0)
                        put("pressure_hpa_mean", (pressure * 10).roundToInt() / 10.0)
                        put("humidity_pct_mean", (humidity * 10).roundToInt() / 10.0)
                        put("wind_speed_mps_mean", (wind * 10).roundToInt() / 10.0)
                        put("uv_index_max", (uv * 10).roundToInt() / 10.0)
                    }
                    client.post("$base/rest/v1/user_weather_daily") {
                        header("Authorization", "Bearer $token")
                        header("apikey", key)
                        header("Prefer", "resolution=merge-duplicates,return=minimal")
                        parameter("on_conflict", "user_id,source,date")
                        contentType(ContentType.Application.Json)
                        setBody(JsonArray(listOf(body)).toString())
                    }
                } catch (e: Exception) { Log.w(TAG, "Weather seed day $i: ${e.message}") }
            }
        } finally { client.close() }
    }

    private suspend fun seedNutrition(token: String, today: LocalDate) {
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')
        val key = BuildConfig.SUPABASE_ANON_KEY
        val client = HttpClient()
        try {
            for (i in 0 until DAYS) {
                val date = today.minusDays(i.toLong()).toString()
                val calories = 1500.0 + Random.nextDouble(1200.0)
                val protein = 40.0 + Random.nextDouble(80.0)
                val carbs = 150.0 + Random.nextDouble(200.0)
                val fat = 40.0 + Random.nextDouble(60.0)
                val caffeine = 50.0 + Random.nextDouble(300.0)
                try {
                    val body = buildJsonObject {
                        put("date", date)
                        put("source", SOURCE)
                        put("total_calories", calories.roundToInt().toDouble())
                        put("total_protein_g", (protein * 10).roundToInt() / 10.0)
                        put("total_carbs_g", (carbs * 10).roundToInt() / 10.0)
                        put("total_fat_g", (fat * 10).roundToInt() / 10.0)
                        put("total_caffeine_mg", (caffeine * 10).roundToInt() / 10.0)
                    }
                    client.post("$base/rest/v1/nutrition_daily") {
                        header("Authorization", "Bearer $token")
                        header("apikey", key)
                        header("Prefer", "resolution=merge-duplicates,return=minimal")
                        parameter("on_conflict", "user_id,source,date")
                        contentType(ContentType.Application.Json)
                        setBody(JsonArray(listOf(body)).toString())
                    }
                } catch (e: Exception) { Log.w(TAG, "Nutrition seed day $i: ${e.message}") }
            }
        } finally { client.close() }
    }

    private suspend fun seedMigraines(token: String, db: SupabaseDbService, today: LocalDate) {
        val migraineDays = listOf(1, 3, 5)
        val types = listOf("migraine_with_aura", "migraine_without_aura", "tension_type")
        val severities = listOf(8, 6, 4)
        val notes = listOf(
            "[demo] Started after stressful meeting",
            "[demo] Woke up with it, poor sleep night before",
            "[demo] Long screen day"
        )
        val locations = listOf(
            listOf("right_temple", "behind_right_eye"),
            listOf("forehead", "both_temples"),
            listOf("left_temple", "neck")
        )
        val durations = listOf(8, 12, 4)
        val triggerTypes = listOf("Sleep duration low", "Pressure low", "Screen time high")

        for (idx in migraineDays.indices) {
            val day = today.minusDays(migraineDays[idx].toLong())
            val startHour = if (idx % 2 == 0) 14 else 9
            val startAt = "${day}T${startHour.toString().padStart(2, '0')}:00:00Z"
            val endAt = "${day}T${(startHour + durations[idx]).coerceAtMost(23).toString().padStart(2, '0')}:00:00Z"
            try {
                val migraine = db.insertMigraine(token, types[idx], severities[idx], startAt, endAt, notes[idx], locations[idx])
                db.insertTrigger(token, migraine.id, triggerTypes[idx], "${day}T${(startHour - 2).coerceAtLeast(6).toString().padStart(2, '0')}:00:00Z", "[demo]")
                db.insertMedicine(token, migraine.id, null, null, "${day}T${startHour.toString().padStart(2, '0')}:30:00Z", "[demo] Took medication")
                if (idx % 2 == 0) {
                    db.insertRelief(token, migraine.id, null, "${day}T${(startHour + 1).toString().padStart(2, '0')}:00:00Z", "[demo] Dark room rest")
                }
                Log.d(TAG, "Seeded migraine ${idx + 1} on $day")
            } catch (e: Exception) { Log.w(TAG, "Migraine seed $idx: ${e.message}") }
        }

        // Standalone triggers on recent days so the risk gauge shows a score
        val standaloneDays = listOf(0, 1, 2, 3, 4, 5, 6)
        val standaloneTriggers = listOf("Sleep duration low", "Pressure low", "Screen time high", "Stress", "Caffeine high", "Sleep disturbances high", "Temperature high")
        for (i in standaloneDays.indices) {
            val day = today.minusDays(standaloneDays[i].toLong())
            try {
                db.insertTrigger(token, null, standaloneTriggers[i], "${day}T12:00:00Z", "[demo]")
            } catch (e: Exception) { Log.w(TAG, "Standalone trigger $i: ${e.message}") }
        }
    }

    // ── Clear ───────────────────────────────────────────────────────────────

    suspend fun clearDemoData(context: Context) {
        val appCtx = context.applicationContext
        val token = SessionStore.getValidAccessToken(appCtx) ?: return
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')
        val key = BuildConfig.SUPABASE_ANON_KEY

        val metricTables = listOf(
            "sleep_duration_daily", "sleep_score_daily", "sleep_efficiency_daily",
            "sleep_disturbances_daily", "fell_asleep_time_daily", "woke_up_time_daily",
            "recovery_score_daily", "resting_hr_daily", "hrv_daily", "spo2_daily",
            "skin_temp_daily", "screen_time_daily", "user_weather_daily", "nutrition_daily"
        )
        val notesTables = listOf("migraines", "medicines", "reliefs", "triggers")

        withContext(Dispatchers.IO) {
            val client = HttpClient()
            try {
                for (table in metricTables) {
                    try {
                        client.delete("$base/rest/v1/$table") {
                            header("Authorization", "Bearer $token")
                            header("apikey", key)
                            parameter("source", "eq.$SOURCE")
                        }
                    } catch (e: Exception) { Log.w(TAG, "Clear $table: ${e.message}") }
                }
                for (table in notesTables) {
                    try {
                        client.delete("$base/rest/v1/$table") {
                            header("Authorization", "Bearer $token")
                            header("apikey", key)
                            parameter("notes", "like.*[demo]*")
                        }
                    } catch (e: Exception) { Log.w(TAG, "Clear $table: ${e.message}") }
                }
                Log.d(TAG, "Demo data cleared")
                markDemoCleared(appCtx)
            } finally { client.close() }
        }
    }

    // ── Safety net ──────────────────────────────────────────────────────────

    /**
     * Safety net: call this any time the user reaches the main app.
     * If demo data was never confirmed-cleared (e.g. tour was skipped,
     * interrupted, or clearDemoData silently failed), this will retry.
     * If cleanup already succeeded, it's a no-op (cheap SharedPrefs check).
     */
    suspend fun ensureDemoCleared(context: Context) {
        val appCtx = context.applicationContext
        if (isDemoCleared(appCtx)) return
        Log.d(TAG, "Demo data not yet cleared — running safety-net cleanup")
        clearDemoData(appCtx)
    }
}
