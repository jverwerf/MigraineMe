package com.migraineme

import android.content.Context
import android.util.Log
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.random.Random

object DemoDataSeeder {

    private const val TAG = "DemoSeeder"
    private const val SOURCE = "demo"
    private const val DAYS = 14

    data class SeedProgress(val phase: String = "", val fraction: Float = 0f, val isComplete: Boolean = false)
    private val _progress = MutableStateFlow(SeedProgress())
    val progress: StateFlow<SeedProgress> = _progress
    private val _dataReady = MutableStateFlow(false)
    val dataReady: StateFlow<Boolean> = _dataReady
    private fun prog(phase: String, f: Float) { _progress.value = SeedProgress(phase, f.coerceIn(0f, 1f)) }

    private const val PREFS = "demo_seeder"
    private fun isDemoCleared(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("cleared", false)
    private fun markCleared(c: Context) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("cleared", true).apply() }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private suspend fun upsertRow(client: HttpClient, url: String, token: String, apiKey: String, onConflict: String, body: JsonObject): Boolean {
        val tableName = url.substringAfterLast("/")
        return try {
            val resp = client.post(url) {
                header("Authorization", "Bearer $token"); header("apikey", apiKey)
                header("Prefer", "resolution=merge-duplicates,return=minimal")
                parameter("on_conflict", onConflict)
                contentType(ContentType.Application.Json)
                setBody(JsonArray(listOf(body)).toString())
            }
            if (!resp.status.isSuccess()) {
                Log.e(TAG, "✗ $tableName: ${resp.status} — ${resp.bodyAsText().take(400)}")
                false
            } else true
        } catch (e: Exception) { Log.e(TAG, "✗ $tableName exception: ${e.message}"); false }
    }

    private suspend fun insertRow(client: HttpClient, url: String, token: String, apiKey: String, body: JsonObject): Boolean {
        val tableName = url.substringAfterLast("/")
        return try {
            val resp = client.post(url) {
                header("Authorization", "Bearer $token"); header("apikey", apiKey)
                header("Prefer", "return=minimal")
                contentType(ContentType.Application.Json)
                setBody(JsonArray(listOf(body)).toString())
            }
            if (!resp.status.isSuccess()) {
                Log.e(TAG, "✗ $tableName: ${resp.status} — ${resp.bodyAsText().take(400)}")
                false
            } else true
        } catch (e: Exception) { Log.e(TAG, "✗ $tableName exception: ${e.message}"); false }
    }

    // ── Migraine pattern: days-ago when migraines happen ──────────────────

    // Migraine pattern within 14 days: Pair(daysAgo, severity)
    private val MIGRAINE_DAYS = listOf(
        1 to 7, 3 to 5, 5 to 8, 8 to 6, 10 to 4, 13 to 7
    )
    private val MIGRAINE_DAYS_SET = MIGRAINE_DAYS.map { it.first }.toSet()
    private val PRE_MIGRAINE_DAYS = MIGRAINE_DAYS.map { it.first + 1 }.toSet()
    private fun isBadDay(daysAgo: Int) = daysAgo in MIGRAINE_DAYS_SET || daysAgo in PRE_MIGRAINE_DAYS

    // ── Main seed ───────────────────────────────────────────────────────────

    suspend fun seedDemoData(context: Context) {
        val ctx = context.applicationContext
        Log.d(TAG, "═══ seedDemoData START (30 days) ═══")
        val token = SessionStore.getValidAccessToken(ctx) ?: run { Log.e(TAG, "No token"); return }
        val userId = SessionStore.readUserId(ctx) ?: JwtUtils.extractUserIdFromAccessToken(token)
        Log.d(TAG, "userId=$userId")
        val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
        val metrics = SupabaseMetricsService(ctx)
        val physical = SupabasePhysicalHealthService(ctx)
        val personal = SupabasePersonalService(ctx)
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')
        val key = BuildConfig.SUPABASE_ANON_KEY
        val today = LocalDate.now(ZoneId.systemDefault())

        withContext(Dispatchers.IO) {
            try {
                prog("Seeding sleep data…", 0.05f)
                seedSleep(token, metrics, today);            Log.d(TAG, "✓ Sleep done")
                prog("Seeding physical data…", 0.15f)
                seedPhysical(token, physical, today);        Log.d(TAG, "✓ Physical done")
                prog("Seeding screen time…", 0.22f)
                seedScreenTime(token, personal, today);      Log.d(TAG, "✓ Screen time done")
                prog("Seeding mental health…", 0.30f)
                seedMental(token, userId, base, key, today); Log.d(TAG, "✓ Mental done")
                prog("Seeding weather…", 0.42f)
                seedWeather(token, userId, base, key, today);Log.d(TAG, "✓ Weather done")
                prog("Seeding nutrition…", 0.55f)
                seedNutrition(token, userId, base, key, today);Log.d(TAG, "✓ Nutrition done")
                prog("Seeding migraines…", 0.70f)
                seedMigraines(token, userId, db, base, key, today); Log.d(TAG, "✓ Migraines done")
                prog("Setting risk score…", 0.85f)
                seedRiskScoreLive(token, userId, today)
                prog("All set!", 1.0f)
                _progress.value = SeedProgress("Ready!", 1f, true)
                _dataReady.value = true
                Log.d(TAG, "═══ seedDemoData COMPLETE ═══")
            } catch (e: Exception) {
                Log.e(TAG, "seedDemoData FAILED: ${e.message}", e)
                _progress.value = SeedProgress("Ready!", 1f, true)
                _dataReady.value = true
            }
        }
    }

    // ── Sleep: 30 days, bad sleep before migraines ───────────────────────

    private suspend fun seedSleep(token: String, m: SupabaseMetricsService, today: LocalDate) {
        for (i in 0 until DAYS) {
            val d = today.minusDays(i.toLong()).toString()
            val bad = isBadDay(i)
            try {
                val dur = if (bad) 4.0 + Random.nextDouble(1.5) else 6.5 + Random.nextDouble(2.0)
                val score = if (bad) 30.0 + Random.nextDouble(25.0) else 65.0 + Random.nextDouble(30.0)
                val eff = if (bad) 60.0 + Random.nextDouble(15.0) else 80.0 + Random.nextDouble(18.0)
                val dist = if (bad) 4 + Random.nextInt(5) else Random.nextInt(3)
                m.upsertSleepDurationDaily(token, d, dur, SOURCE, "$SOURCE-$i")
                m.upsertSleepScoreDaily(token, d, score, SOURCE, "$SOURCE-$i")
                m.upsertSleepEfficiencyDaily(token, d, eff, SOURCE, "$SOURCE-$i")
                m.upsertSleepDisturbancesDaily(token, d, dist, SOURCE, "$SOURCE-$i")
                val bH = if (bad) 0 + Random.nextInt(2) else 22 + Random.nextInt(1)
                val bM = Random.nextInt(60)
                val wH = if (bad) 5 + Random.nextInt(1) else 6 + Random.nextInt(2)
                val wM = Random.nextInt(60)
                m.upsertFellAsleepTimeDaily(token, d, "${d}T${bH.toString().padStart(2,'0')}:${bM.toString().padStart(2,'0')}:00", SOURCE, "$SOURCE-$i")
                m.upsertWokeUpTimeDaily(token, d, "${d}T${wH.toString().padStart(2,'0')}:${wM.toString().padStart(2,'0')}:00", SOURCE, "$SOURCE-$i")
            } catch (e: Exception) { Log.w(TAG, "Sleep $i: ${e.message}") }
        }
    }

    // ── Physical: 30 days, degraded HRV/recovery before migraines ────────

    private suspend fun seedPhysical(token: String, p: SupabasePhysicalHealthService, today: LocalDate) {
        for (i in 0 until DAYS) {
            val d = today.minusDays(i.toLong()).toString()
            val bad = isBadDay(i)
            try {
                p.upsertRecoveryScoreDaily(token, d, if (bad) 20.0+Random.nextDouble(25.0) else 55.0+Random.nextDouble(40.0), SOURCE, "$SOURCE-$i")
                p.upsertRestingHrDaily(token, d, if (bad) 62.0+Random.nextDouble(12.0) else 52.0+Random.nextDouble(10.0), SOURCE, "$SOURCE-$i")
                p.upsertHrvDaily(token, d, if (bad) 20.0+Random.nextDouble(20.0) else 45.0+Random.nextDouble(45.0), SOURCE, "$SOURCE-$i")
                p.upsertSpo2Daily(token, d, if (bad) 93.0+Random.nextDouble(3.0) else 96.0+Random.nextDouble(3.0), SOURCE, "$SOURCE-$i")
                p.upsertSkinTempDaily(token, d, if (bad) 34.0+Random.nextDouble(1.5) else 33.0+Random.nextDouble(1.0), SOURCE, "$SOURCE-$i")
            } catch (e: Exception) { Log.w(TAG, "Phys $i: ${e.message}") }
        }
    }

    // ── Screen time: 30 days, high screen on bad days ───────────────────

    private suspend fun seedScreenTime(token: String, p: SupabasePersonalService, today: LocalDate) {
        for (i in 0 until DAYS) {
            val d = today.minusDays(i.toLong()).toString()
            val bad = isBadDay(i)
            try { p.upsertScreenTimeDaily(token, d, if (bad) 6.0+Random.nextDouble(4.0) else 2.0+Random.nextDouble(3.0), SOURCE) }
            catch (e: Exception) { Log.w(TAG, "Screen $i: ${e.message}") }
        }
    }

    // ── Mental: stress, noise, phone metrics — 30 days ──────────────────

    private suspend fun seedMental(token: String, userId: String?, base: String, key: String, today: LocalDate) {
        if (userId.isNullOrBlank()) { Log.e(TAG, "No userId for mental"); return }
        val client = HttpClient()
        Log.d(TAG, "── seedMental: 30 days ──")
        try {
            for (i in 0 until DAYS) {
                val d = today.minusDays(i.toLong()).toString()
                val bad = isBadDay(i)
                upsertRow(client, "$base/rest/v1/stress_index_daily", token, key, "user_id,date",
                    buildJsonObject {
                        put("date",d); put("user_id",userId)
                        put("value", if (bad) 60.0+Random.nextDouble(35.0) else 15.0+Random.nextDouble(35.0))
                        put("hrv_z", if (bad) -2.0+Random.nextDouble(1.0) else -0.5+Random.nextDouble(2.0))
                        put("rhr_z", if (bad) 0.5+Random.nextDouble(1.5) else -1.0+Random.nextDouble(1.5))
                        put("baseline_window_days", 14)
                    })
                upsertRow(client, "$base/rest/v1/ambient_noise_index_daily", token, key, "user_id,date",
                    buildJsonObject {
                        put("date",d); put("user_id",userId); put("source",SOURCE)
                        put("value_index_pct", if (bad) 55.0+Random.nextDouble(40.0) else 10.0+Random.nextDouble(35.0))
                        put("samples_count", 10+Random.nextInt(50)); put("baseline_days", 14)
                        put("day_mean_lmean", if (bad) 50.0+Random.nextDouble(30.0) else 25.0+Random.nextDouble(25.0))
                    })
                upsertRow(client, "$base/rest/v1/screen_time_late_night", token, key, "user_id,date,source",
                    buildJsonObject {
                        put("date",d); put("user_id",userId); put("source",SOURCE)
                        put("value_hours", if (bad) 1.5+Random.nextDouble(2.5) else Random.nextDouble(1.0))
                    })
                upsertRow(client, "$base/rest/v1/phone_unlock_daily", token, key, "user_id,date",
                    buildJsonObject {
                        put("date",d); put("user_id",userId)
                        put("value_count", if (bad) 80+Random.nextInt(80) else 20+Random.nextInt(50))
                    })
                upsertRow(client, "$base/rest/v1/phone_brightness_daily", token, key, "user_id,date",
                    buildJsonObject {
                        put("date",d); put("user_id",userId)
                        put("value_mean", if (bad) 60.0+Random.nextDouble(35.0) else 25.0+Random.nextDouble(35.0))
                    })
                upsertRow(client, "$base/rest/v1/phone_volume_daily", token, key, "user_id,date",
                    buildJsonObject {
                        put("date",d); put("user_id",userId)
                        put("value_mean_pct", if (bad) 55.0+Random.nextDouble(40.0) else 15.0+Random.nextDouble(35.0))
                    })
                upsertRow(client, "$base/rest/v1/phone_dark_mode_daily", token, key, "user_id,date",
                    buildJsonObject {
                        put("date",d); put("user_id",userId)
                        put("value_hours", if (bad) 2.0+Random.nextDouble(4.0) else 8.0+Random.nextDouble(8.0))
                    })
            }
        } finally { client.close() }
    }

    // ── Weather: 30 days, pressure drops before migraines ───────────────

    private suspend fun seedWeather(token: String, userId: String?, base: String, key: String, today: LocalDate) {
        if (userId.isNullOrBlank()) { Log.e(TAG, "No userId for weather"); return }
        val client = HttpClient()
        Log.d(TAG, "── seedWeather: 30 days ──")
        try {
            // Delete existing to avoid RLS USING conflict
            try { client.delete("$base/rest/v1/user_weather_daily") {
                header("Authorization","Bearer $token"); header("apikey",key)
                parameter("user_id","eq.$userId")
            } } catch (_: Exception) {}

            for (i in 0 until DAYS) {
                val d = today.minusDays(i.toLong()).toString()
                val bad = isBadDay(i)
                upsertRow(client, "$base/rest/v1/user_weather_daily", token, key, "user_id,date",
                    buildJsonObject {
                        put("date",d); put("user_id",userId)
                        // Low pressure on bad days (classic migraine trigger)
                        put("temp_c_mean", if (bad) 5.0+Random.nextDouble(8.0) else 12.0+Random.nextDouble(12.0))
                        put("pressure_hpa_mean", if (bad) (980+Random.nextInt(15)).toDouble() else (1010+Random.nextInt(20)).toDouble())
                        put("humidity_pct_mean", if (bad) 75.0+Random.nextDouble(20.0) else 35.0+Random.nextDouble(30.0))
                        put("wind_speed_mps_mean", if (bad) 8.0+Random.nextDouble(10.0) else 1.0+Random.nextDouble(6.0))
                        put("uv_index_max", if (bad) 1.0+Random.nextDouble(2.0) else 3.0+Random.nextDouble(6.0))
                        put("weather_code", if (bad) listOf(45,61,63,80,95).random() else listOf(0,1,2,3).random())
                        put("is_thunderstorm_day", bad && Random.nextFloat() < 0.3f)
                    })
            }
        } finally { client.close() }
    }

    // ── Nutrition: 30-day daily + today records ──────────────────────────

    private suspend fun seedNutrition(token: String, userId: String?, base: String, key: String, today: LocalDate) {
        if (userId.isNullOrBlank()) { Log.e(TAG, "No userId for nutrition"); return }
        val client = HttpClient()
        Log.d(TAG, "── seedNutrition: 30 days ──")
        try {
            for (i in 0 until DAYS) {
                val d = today.minusDays(i.toLong()).toString()
                val bad = isBadDay(i)
                upsertRow(client, "$base/rest/v1/nutrition_daily", token, key, "user_id,source,date",
                    buildJsonObject {
                        put("date",d); put("source",SOURCE); put("user_id",userId)
                        put("total_calories", if (bad) 1200.0+Random.nextDouble(600.0) else 1800.0+Random.nextDouble(800.0))
                        put("total_protein_g", if (bad) 30.0+Random.nextDouble(30.0) else 60.0+Random.nextDouble(50.0))
                        put("total_carbs_g", if (bad) 200.0+Random.nextDouble(150.0) else 150.0+Random.nextDouble(100.0))
                        put("total_fat_g", if (bad) 50.0+Random.nextDouble(40.0) else 40.0+Random.nextDouble(30.0))
                        put("total_fiber_g", if (bad) 5.0+Random.nextDouble(8.0) else 15.0+Random.nextDouble(15.0))
                        put("total_sugar_g", if (bad) 60.0+Random.nextDouble(50.0) else 20.0+Random.nextDouble(30.0))
                        put("total_sodium_mg", if (bad) 2500.0+Random.nextDouble(1500.0) else 1000.0+Random.nextDouble(1000.0))
                        put("total_caffeine_mg", if (bad) 350.0+Random.nextDouble(200.0) else 50.0+Random.nextDouble(150.0))
                        put("total_saturated_fat_g",(80+Random.nextInt(200))/10.0)
                        put("total_unsaturated_fat_g",(150+Random.nextInt(350))/10.0)
                        put("total_trans_fat_g",Random.nextInt(30)/10.0)
                        put("total_cholesterol_mg",(100+Random.nextInt(300)).toDouble())
                        put("total_potassium_mg",(1500+Random.nextInt(3000)).toDouble())
                        put("total_calcium_mg",(400+Random.nextInt(800)).toDouble())
                        put("total_iron_mg",(50+Random.nextInt(150))/10.0)
                        put("total_magnesium_mg", if (bad) 80.0+Random.nextDouble(60.0) else 200.0+Random.nextDouble(200.0))
                        put("total_zinc_mg",(30+Random.nextInt(120))/10.0)
                        put("total_phosphorus_mg",(400+Random.nextInt(1000)).toDouble())
                        put("total_copper_mg",(3+Random.nextInt(15))/10.0)
                        put("total_manganese_mg",(5+Random.nextInt(40))/10.0)
                        put("total_selenium_mcg",(20+Random.nextInt(60)).toDouble())
                        put("total_vitamin_a_mcg",(200+Random.nextInt(1000)).toDouble())
                        put("total_vitamin_c_mg",(20+Random.nextInt(150)).toDouble())
                        put("total_vitamin_d_mcg",(20+Random.nextInt(300))/10.0)
                        put("total_vitamin_e_mg",(30+Random.nextInt(120))/10.0)
                        put("total_vitamin_k_mcg",(20+Random.nextInt(150)).toDouble())
                        put("total_vitamin_b6_mg",(5+Random.nextInt(40))/10.0)
                        put("total_vitamin_b12_mcg",(5+Random.nextInt(40))/10.0)
                        put("total_folate_mcg",(100+Random.nextInt(300)).toDouble())
                        put("total_niacin_mg",(50+Random.nextInt(250))/10.0)
                        put("total_riboflavin_mg",(3+Random.nextInt(25))/10.0)
                        put("total_thiamin_mg",(3+Random.nextInt(20))/10.0)
                        put("total_pantothenic_acid_mg",(10+Random.nextInt(80))/10.0)
                        put("total_biotin_mcg",(10+Random.nextInt(80)).toDouble())
                        put("max_tyramine_exposure", if (bad) 1+Random.nextInt(2) else 0)
                        put("max_gluten_exposure", if (bad) 1+Random.nextInt(2) else 0)
                    })
            }
            // Today's nutrition_records for Monitor card
            val meals = listOf(
                Triple("Oatmeal with berries", "breakfast", "08:00:00Z"),
                Triple("Grilled chicken salad", "lunch", "12:30:00Z"),
                Triple("Salmon with vegetables", "dinner", "19:00:00Z")
            )
            val cals = listOf(350.0, 520.0, 680.0)
            val prot = listOf(12.0, 35.0, 42.0)
            val carb = listOf(55.0, 30.0, 25.0)
            val fat = listOf(8.0, 18.0, 28.0)
            for (idx in meals.indices) {
                val (name, meal, time) = meals[idx]
                insertRow(client, "$base/rest/v1/nutrition_records", token, key,
                    buildJsonObject {
                        put("user_id", userId); put("food_name", name); put("meal_type", meal)
                        put("timestamp", "${today}T$time"); put("date", today.toString()); put("source", SOURCE)
                        put("calories", cals[idx]); put("protein", prot[idx])
                        put("total_carbohydrate", carb[idx]); put("total_fat", fat[idx])
                        put("dietary_fiber", 3.0+Random.nextDouble(8.0))
                        put("sugar", 5.0+Random.nextDouble(20.0))
                        put("sodium", 200.0+Random.nextDouble(600.0))
                        put("caffeine", if (idx==0) 95.0 else 0.0)
                        put("cholesterol", 20.0+Random.nextDouble(80.0))
                        put("potassium", 200.0+Random.nextDouble(500.0))
                        put("calcium", 50.0+Random.nextDouble(200.0))
                        put("iron", 1.0+Random.nextDouble(5.0))
                        put("magnesium", 20.0+Random.nextDouble(80.0))
                        put("vitamin_c", 5.0+Random.nextDouble(40.0))
                    })
            }
        } finally { client.close() }
    }

    // ── Migraines: 11 episodes with linked triggers, meds, reliefs ──────

    private suspend fun seedMigraines(token: String, userId: String?, db: SupabaseDbService, base: String, key: String, today: LocalDate) {
        if (userId.isNullOrBlank()) { Log.e(TAG, "No userId for migraines"); return }
        // Pre-delete all demo entries so triggers get proper migraine_id on re-run
        val client = HttpClient()
        try {
            for (t in listOf("triggers","medicines","reliefs","migraines")) {
                try { client.delete("$base/rest/v1/$t") {
                    header("Authorization","Bearer $token"); header("apikey",key)
                    parameter("notes","like.*[demo]*")
                } } catch (_: Exception) {}
            }
            Log.d(TAG, "✓ Cleared old demo migraines/triggers/medicines/reliefs")
        } catch (_: Exception) {}

        val types = listOf("migraine_with_aura","migraine_without_aura","tension_type")
        val triggerTypes = listOf(
            "Sleep duration low", "Pressure low", "Screen time high",
            "Stress high", "Caffeine high", "Humidity high", "Late screen time high"
        )

        for ((idx, entry) in MIGRAINE_DAYS.withIndex()) {
            val (daysAgo, severity) = entry
            val day = today.minusDays(daysAgo.toLong())
            val h = listOf(7,9,11,14,16,18).random()
            val dur = listOf(4,6,8,10,12).random()
            try {
                val m = db.insertMigraine(token, types[idx % types.size], severity,
                    "${day}T${h.toString().padStart(2,'0')}:00:00Z",
                    "${day}T${(h+dur).coerceAtMost(23).toString().padStart(2,'0')}:00:00Z",
                    "[demo] Episode ${idx+1}",
                    listOf("right_temple","behind_right_eye","forehead","left_temple").shuffled().take(2))

                // Link 2-3 triggers to each migraine (source=system so metrics auto-select on chart)
                val linked = triggerTypes.shuffled().take(2 + Random.nextInt(2))
                for (trig in linked) {
                    try {
                        upsertRow(client, "$base/rest/v1/triggers", token, key, "user_id,start_at,type",
                            buildJsonObject {
                                put("user_id", userId); put("type", trig); put("migraine_id", m.id)
                                put("start_at", "${day}T${(h-2).coerceAtLeast(4).toString().padStart(2,'0')}:00:00Z")
                                put("notes", "[demo]"); put("source", "system")
                            })
                    } catch (_: Exception) {}
                }

                // Medication for most migraines
                if (Random.nextFloat() < 0.8f) {
                    try { db.insertMedicine(token, m.id, null, null,
                        "${day}T${h.toString().padStart(2,'0')}:30:00Z", "[demo] Ibuprofen 400mg") } catch (_: Exception) {}
                }

                // Relief for some
                if (Random.nextFloat() < 0.6f) {
                    try { db.insertRelief(token, m.id, null,
                        "${day}T${(h+1).toString().padStart(2,'0')}:00:00Z", "[demo] Dark room + cold compress") } catch (_: Exception) {}
                }

                Log.d(TAG, "✓ Migraine ${idx+1}/${MIGRAINE_DAYS.size} on $day (sev=$severity)")
            } catch (e: Exception) { Log.w(TAG, "Migraine $idx: ${e.message}") }
        }

        // Standalone trigger observations on bad days
        for (i in 0 until DAYS) {
            val d = today.minusDays(i.toLong())
            if (isBadDay(i)) {
                val trig = triggerTypes.random()
                try { upsertRow(client, "$base/rest/v1/triggers", token, key, "user_id,start_at,type",
                    buildJsonObject {
                        put("user_id", userId); put("type", trig)
                        put("start_at", "${d}T12:00:00Z")
                        put("notes", "[demo]"); put("source", "system")
                    }) } catch (_: Exception) {}
            }
        }
        client.close()
    }

    // ── Risk score live ──────────────────────────────────────────────────

    private suspend fun seedRiskScoreLive(token: String, userId: String?, today: LocalDate) {
        if (userId.isNullOrBlank()) { Log.e(TAG, "No userId for risk"); return }
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')
        val key = BuildConfig.SUPABASE_ANON_KEY
        val client = HttpClient()
        try {
            val trigs = buildJsonArray {
                add(buildJsonObject { put("name","Sleep duration low"); put("score",4); put("severity","HIGH"); put("daysActive",5) })
                add(buildJsonObject { put("name","Pressure low"); put("score",3); put("severity","MILD"); put("daysActive",4) })
                add(buildJsonObject { put("name","Screen time high"); put("score",2); put("severity","MILD"); put("daysActive",3) })
                add(buildJsonObject { put("name","Caffeine high"); put("score",2); put("severity","MILD"); put("daysActive",2) })
            }
            val pcts = listOf(52,40,30,45,35,25,20)
            val forecast = buildJsonArray { pcts.forEach { add(it) } }
            val dayRisks = buildJsonArray {
                for (i in 0 until 7) {
                    val d = today.plusDays(i.toLong()); val p = pcts[i]
                    val z = when { p>=60->"HIGH"; p>=30->"MILD"; p>=10->"LOW"; else->"NONE" }
                    add(buildJsonObject { put("date",d.toString()); put("score",p/10.0); put("zone",z); put("percent",p); put("top_triggers",trigs) })
                }
            }
            val payload = buildJsonObject {
                put("user_id", userId); put("score",5.2); put("zone","MILD"); put("percent",52)
                put("top_triggers",trigs); put("forecast",forecast); put("day_risks",dayRisks)
                put("updated_at", java.time.Instant.now().toString())
            }
            val r = client.post("$base/rest/v1/risk_score_live") {
                header("Authorization","Bearer $token"); header("apikey",key)
                header("Prefer","resolution=merge-duplicates,return=representation")
                parameter("on_conflict","user_id")
                contentType(ContentType.Application.Json)
                setBody(JsonArray(listOf(payload)).toString())
            }
            val body = r.bodyAsText()
            Log.d(TAG, "risk_score_live: ${r.status} → ${body.take(200)}")
            if (r.status.isSuccess() && body != "[]") Log.d(TAG, "✓ Risk score set")
            else Log.e(TAG, "✗ Risk score failed or empty")
        } catch (e: Exception) { Log.e(TAG, "risk: ${e.message}", e) }
        finally { client.close() }
    }

    // ── Clear ALL ───────────────────────────────────────────────────────

    suspend fun clearDemoData(context: Context) {
        val ctx = context.applicationContext
        val token = SessionStore.getValidAccessToken(ctx) ?: return
        val base = BuildConfig.SUPABASE_URL.trimEnd('/'); val key = BuildConfig.SUPABASE_ANON_KEY
        val userId = SessionStore.readUserId(ctx) ?: JwtUtils.extractUserIdFromAccessToken(token)
        Log.d(TAG, "═══ clearDemoData START ═══")
        val sourceTables = listOf("sleep_duration_daily","sleep_score_daily","sleep_efficiency_daily","sleep_disturbances_daily","fell_asleep_time_daily","woke_up_time_daily","recovery_score_daily","resting_hr_daily","hrv_daily","spo2_daily","skin_temp_daily","screen_time_daily","nutrition_daily","nutrition_records")
        val userIdTables = listOf("stress_index_daily","ambient_noise_index_daily","screen_time_late_night","phone_unlock_daily","phone_brightness_daily","phone_volume_daily","phone_dark_mode_daily","user_weather_daily")
        val notesTables = listOf("migraines","medicines","reliefs","triggers")
        withContext(Dispatchers.IO) {
            val client = HttpClient()
            try {
                for (t in sourceTables) { try { client.delete("$base/rest/v1/$t") { header("Authorization","Bearer $token"); header("apikey",key); parameter("source","eq.$SOURCE") } } catch (_: Exception) {} }
                // userIdTables: ambient_noise & screen_time_late_night have source column; rest are app-only sensors (no WHOOP data)
                val userIdWithSource = listOf("ambient_noise_index_daily","screen_time_late_night")
                val userIdNoSource = listOf("stress_index_daily","phone_unlock_daily","phone_brightness_daily","phone_volume_daily","phone_dark_mode_daily","user_weather_daily")
                for (t in userIdWithSource) { try { client.delete("$base/rest/v1/$t") { header("Authorization","Bearer $token"); header("apikey",key); parameter("source","eq.$SOURCE") } } catch (_: Exception) {} }
                for (t in userIdNoSource) { try { client.delete("$base/rest/v1/$t") { header("Authorization","Bearer $token"); header("apikey",key); parameter("user_id","eq.$userId") } } catch (_: Exception) {} }
                for (t in notesTables) { try { client.delete("$base/rest/v1/$t") { header("Authorization","Bearer $token"); header("apikey",key); parameter("notes","like.*[demo]*") } } catch (_: Exception) {} }
                // Also delete auto-generated system triggers/prodromes (created by edge function from demo metrics)
                try { client.delete("$base/rest/v1/triggers") { header("Authorization","Bearer $token"); header("apikey",key); parameter("source","eq.system") } } catch (_: Exception) {}
                try { client.delete("$base/rest/v1/prodromes") { header("Authorization","Bearer $token"); header("apikey",key); parameter("source","eq.system") } } catch (_: Exception) {}
                if (!userId.isNullOrBlank()) { try {
                    val payload = buildJsonObject { put("user_id",userId); put("score",0); put("zone","NONE"); put("percent",0); put("top_triggers",JsonNull); put("forecast",JsonNull); put("day_risks",JsonNull); put("updated_at",java.time.Instant.now().toString()) }
                    client.post("$base/rest/v1/risk_score_live") { header("Authorization","Bearer $token"); header("apikey",key); header("Prefer","resolution=merge-duplicates,return=minimal"); parameter("on_conflict","user_id"); contentType(ContentType.Application.Json); setBody(JsonArray(listOf(payload)).toString()) }
                } catch (_: Exception) {} }
                Log.d(TAG, "═══ clearDemoData COMPLETE ═══")
                markCleared(ctx); _dataReady.value = false; _progress.value = SeedProgress()
            } finally { client.close() }
        }
    }

    suspend fun ensureDemoCleared(context: Context) {
        if (isDemoCleared(context.applicationContext)) return
        clearDemoData(context)
    }
}
