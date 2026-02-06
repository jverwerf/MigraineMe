package com.migraineme

import android.content.Context
import android.util.Log
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Supabase service for uploading Health Connect data.
 * Handles all record types with source = "health_connect".
 *
 * IMPORTANT: Different tables have different unique constraints:
 * - Most tables: (user_id, source, date)
 * - time_in_high_hr_zones_daily: (user_id, source, source_measure_id) - stores individual sessions
 *
 * This service handles both patterns appropriately.
 */
class SupabaseHealthConnectService(context: Context) {

    companion object {
        private const val TAG = "SupabaseHCService"
        private const val SOURCE = "health_connect"
    }

    private val supabaseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val supabaseKey = BuildConfig.SUPABASE_ANON_KEY

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true  // CRITICAL: Always include fields with defaults like 'source'
            })
        }
    }

    /**
     * Result of an upsert operation with error classification.
     */
    sealed class UpsertResult {
        object Success : UpsertResult()
        data class RetryableError(val message: String) : UpsertResult()      // Network issues, 5xx - retry later
        data class PermanentError(val message: String) : UpsertResult()      // 4xx client errors - don't retry
    }

    // ============================================================
    // Table-aware upsert helpers
    // ============================================================

    /**
     * Standard upsert for daily tables with (user_id, source, date) uniqueness.
     * Used by: sleep_duration_daily, hrv_daily, resting_hr_daily, steps_daily,
     *          weight_daily, body_fat_daily, hydration_daily, blood_pressure_daily,
     *          blood_glucose_daily, spo2_daily, respiratory_rate_daily, skin_temp_daily,
     *          fell_asleep_time_daily, woke_up_time_daily, sleep_stages_daily
     */
    private suspend inline fun <reified T> upsertDaily(
        accessToken: String,
        table: String,
        row: T
    ): UpsertResult {
        return upsertWithConflict(accessToken, table, row, "user_id,source,date")
    }

    /**
     * Upsert for session-based tables with (user_id, source, source_measure_id) uniqueness.
     * Used by: time_in_high_hr_zones_daily (stores individual exercise sessions)
     */
    private suspend inline fun <reified T> upsertSession(
        accessToken: String,
        table: String,
        row: T
    ): UpsertResult {
        return upsertWithConflict(accessToken, table, row, "user_id,source,source_measure_id")
    }

    /**
     * Generic upsert with explicit conflict resolution.
     */
    private suspend inline fun <reified T> upsertWithConflict(
        accessToken: String,
        table: String,
        row: T,
        onConflict: String
    ): UpsertResult {
        return try {
            val resp = client.post("$supabaseUrl/rest/v1/$table") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                header("Prefer", "resolution=merge-duplicates,return=minimal")
                parameter("on_conflict", onConflict)
                contentType(ContentType.Application.Json)
                setBody(row)
            }

            when {
                resp.status.isSuccess() -> UpsertResult.Success

                resp.status.value in 400..499 -> {
                    val body = resp.bodyAsText()
                    Log.e(TAG, "Upsert to $table failed (permanent): ${resp.status} - $body")
                    
                    // Check if it's a constraint violation we can ignore (duplicate that somehow slipped through)
                    if (body.contains("duplicate") || body.contains("unique")) {
                        Log.w(TAG, "Duplicate detected for $table, treating as success")
                        UpsertResult.Success
                    } else {
                        UpsertResult.PermanentError("${resp.status.value}: $body")
                    }
                }

                else -> {
                    // 5xx or other - retryable
                    val body = resp.bodyAsText()
                    Log.e(TAG, "Upsert to $table failed (retryable): ${resp.status} - $body")
                    UpsertResult.RetryableError("${resp.status.value}: $body")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upsert to $table exception: ${e.message}")
            // Network errors are retryable
            UpsertResult.RetryableError(e.message ?: "Unknown error")
        }
    }

    /**
     * Legacy boolean-returning upsert for backward compatibility.
     * Converts UpsertResult to simple boolean.
     */
    private suspend inline fun <reified T> upsertDailyCompat(
        accessToken: String,
        table: String,
        row: T
    ): Boolean {
        return when (upsertDaily(accessToken, table, row)) {
            is UpsertResult.Success -> true
            is UpsertResult.RetryableError -> false
            is UpsertResult.PermanentError -> false
        }
    }

    private suspend inline fun <reified T> upsertSessionCompat(
        accessToken: String,
        table: String,
        row: T
    ): Boolean {
        return when (upsertSession(accessToken, table, row)) {
            is UpsertResult.Success -> true
            is UpsertResult.RetryableError -> false
            is UpsertResult.PermanentError -> false
        }
    }

    // ============================================================
    // Delete by source_measure_id
    // ============================================================

    suspend fun deleteBySourceMeasureId(
        accessToken: String,
        recordType: String,
        sourceMeasureId: String
    ): Boolean {
        val table = recordTypeToTable(recordType) ?: return false

        return try {
            val resp = client.delete("$supabaseUrl/rest/v1/$table") {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
                header("apikey", supabaseKey)
                parameter("source", "eq.$SOURCE")
                parameter("source_measure_id", "eq.$sourceMeasureId")
            }
            resp.status.isSuccess()
        } catch (e: Exception) {
            Log.e(TAG, "Delete from $table failed: ${e.message}")
            false
        }
    }

    private fun recordTypeToTable(recordType: String): String? {
        return when (recordType) {
            HealthConnectRecordTypes.SLEEP -> "sleep_duration_daily"
            HealthConnectRecordTypes.HRV -> "hrv_daily"
            HealthConnectRecordTypes.RESTING_HR -> "resting_hr_daily"
            HealthConnectRecordTypes.STEPS -> "steps_daily"
            HealthConnectRecordTypes.EXERCISE -> "time_in_high_hr_zones_daily"
            HealthConnectRecordTypes.WEIGHT -> "weight_daily"
            HealthConnectRecordTypes.BODY_FAT -> "body_fat_daily"
            HealthConnectRecordTypes.HYDRATION -> "hydration_daily"
            HealthConnectRecordTypes.BLOOD_PRESSURE -> "blood_pressure_daily"
            HealthConnectRecordTypes.BLOOD_GLUCOSE -> "blood_glucose_daily"
            HealthConnectRecordTypes.SPO2 -> "spo2_daily"
            HealthConnectRecordTypes.RESPIRATORY_RATE -> "respiratory_rate_daily"
            HealthConnectRecordTypes.SKIN_TEMP -> "skin_temp_daily"
            else -> null
        }
    }

    // ============================================================
    // Sleep
    // ============================================================

    @Serializable
    private data class SleepDurationRow(
        val date: String,
        val value_hours: Double,
        val source: String = SOURCE,
        val source_measure_id: String? = null
    )

    @Serializable
    private data class SleepTimeRow(
        val date: String,
        val value_at: String,
        val source: String = SOURCE,
        val source_measure_id: String? = null
    )

    @Serializable
    private data class SleepStagesRow(
        val date: String,
        val rem_minutes: Int,
        val deep_minutes: Int,
        val light_minutes: Int,
        val awake_minutes: Int,
        val source: String = SOURCE,
        val source_measure_id: String? = null
    )

    suspend fun upsertSleep(
        accessToken: String,
        date: String,
        durationHours: Double,
        startTime: String,
        endTime: String,
        remMinutes: Int,
        deepMinutes: Int,
        lightMinutes: Int,
        awakeMinutes: Int,
        sourceId: String
    ): Boolean {
        val results = mutableListOf<Boolean>()

        // Duration
        results.add(upsertDailyCompat(accessToken, "sleep_duration_daily",
            SleepDurationRow(date, durationHours, SOURCE, sourceId)))

        // Fell asleep time
        if (startTime.isNotEmpty()) {
            results.add(upsertDailyCompat(accessToken, "fell_asleep_time_daily",
                SleepTimeRow(date, startTime, SOURCE, sourceId)))
        }

        // Woke up time
        if (endTime.isNotEmpty()) {
            results.add(upsertDailyCompat(accessToken, "woke_up_time_daily",
                SleepTimeRow(date, endTime, SOURCE, sourceId)))
        }

        // Sleep stages (if available)
        if (remMinutes > 0 || deepMinutes > 0 || lightMinutes > 0) {
            results.add(upsertDailyCompat(accessToken, "sleep_stages_daily",
                SleepStagesRow(date, remMinutes, deepMinutes, lightMinutes, awakeMinutes, SOURCE, sourceId)))
        }

        return results.all { it }
    }

    // ============================================================
    // HRV
    // ============================================================

    @Serializable
    private data class HrvRow(
        val date: String,
        val value_rmssd_ms: Double,
        val source: String = SOURCE,
        val source_measure_id: String? = null
    )

    suspend fun upsertHrv(accessToken: String, date: String, valueMs: Double, sourceId: String): Boolean {
        return upsertDailyCompat(accessToken, "hrv_daily", HrvRow(date, valueMs, SOURCE, sourceId))
    }

    // ============================================================
    // Resting HR
    // ============================================================

    @Serializable
    private data class RestingHrRow(
        val date: String,
        val value_bpm: Double,
        val source: String = SOURCE,
        val source_measure_id: String? = null
    )

    suspend fun upsertRestingHr(accessToken: String, date: String, valueBpm: Double, sourceId: String): Boolean {
        return upsertDailyCompat(accessToken, "resting_hr_daily", RestingHrRow(date, valueBpm, SOURCE, sourceId))
    }

    // ============================================================
    // Steps
    // ============================================================

    @Serializable
    private data class StepsRow(
        val date: String,
        val value_count: Long,
        val source: String = SOURCE,
        val source_measure_id: String? = null
    )

    suspend fun upsertSteps(accessToken: String, date: String, count: Long, sourceId: String): Boolean {
        return upsertDailyCompat(accessToken, "steps_daily", StepsRow(date, count, SOURCE, sourceId))
    }

    // ============================================================
    // Exercise (Session-based - uses source_measure_id for uniqueness)
    // ============================================================

    @Serializable
    private data class ExerciseRow(
        val date: String,
        val value_minutes: Int,
        val activity_type: String? = null,
        val source: String = SOURCE,
        val source_measure_id: String? = null,
        // Include start/end times to match WHOOP schema
        val start_at: String? = null,
        val end_at: String? = null
    )

    /**
     * Upsert exercise session.
     * 
     * IMPORTANT: time_in_high_hr_zones_daily uses (user_id, source, source_measure_id) as unique key
     * because it stores individual exercise sessions, not daily totals.
     */
    suspend fun upsertExercise(
        accessToken: String,
        date: String,
        durationMinutes: Int,
        exerciseType: Int,
        sourceId: String,
        startTime: String? = null,
        endTime: String? = null
    ): Boolean {
        val activityName = exerciseTypeToName(exerciseType)
        return upsertSessionCompat(
            accessToken,
            "time_in_high_hr_zones_daily",
            ExerciseRow(
                date = date,
                value_minutes = durationMinutes,
                activity_type = activityName,
                source = SOURCE,
                source_measure_id = sourceId,
                start_at = startTime,
                end_at = endTime
            )
        )
    }

    private fun exerciseTypeToName(type: Int): String {
        return when (type) {
            1 -> "BACK_EXTENSION"
            2 -> "BADMINTON"
            3 -> "BARBELL_SHOULDER_PRESS"
            4 -> "BASEBALL"
            5 -> "BASKETBALL"
            8 -> "BIKING"
            9 -> "BIKING_STATIONARY"
            10 -> "BOOT_CAMP"
            12 -> "BOXING"
            14 -> "BURPEE"
            16 -> "CALISTHENICS"
            17 -> "CRICKET"
            18 -> "CRUNCH"
            19 -> "DANCING"
            20 -> "DEADLIFT"
            22 -> "ELLIPTICAL"
            24 -> "FENCING"
            25 -> "FOOTBALL_AMERICAN"
            26 -> "FOOTBALL_AUSTRALIAN"
            28 -> "GOLF"
            29 -> "GUIDED_BREATHING"
            30 -> "GYMNASTICS"
            31 -> "HANDBALL"
            32 -> "HIGH_INTENSITY_INTERVAL_TRAINING"
            33 -> "HIKING"
            34 -> "ICE_HOCKEY"
            35 -> "ICE_SKATING"
            37 -> "JUMPING_JACK"
            39 -> "LAT_PULL_DOWN"
            40 -> "LUNGE"
            41 -> "MARTIAL_ARTS"
            44 -> "PADDLING"
            45 -> "PARAGLIDING"
            46 -> "PILATES"
            47 -> "PLANK"
            48 -> "RACQUETBALL"
            49 -> "ROCK_CLIMBING"
            50 -> "ROLLER_HOCKEY"
            51 -> "ROWING"
            52 -> "ROWING_MACHINE"
            53 -> "RUGBY"
            54 -> "RUNNING"
            55 -> "RUNNING_TREADMILL"
            56 -> "SAILING"
            57 -> "SCUBA_DIVING"
            58 -> "SKATING"
            59 -> "SKIING"
            60 -> "SNOWBOARDING"
            61 -> "SNOWSHOEING"
            62 -> "SOCCER"
            63 -> "SOFTBALL"
            64 -> "SQUASH"
            65 -> "SQUAT"
            66 -> "STAIR_CLIMBING"
            67 -> "STAIR_CLIMBING_MACHINE"
            68 -> "STRENGTH_TRAINING"
            69 -> "STRETCHING"
            70 -> "SURFING"
            71 -> "SWIMMING_OPEN_WATER"
            72 -> "SWIMMING_POOL"
            73 -> "TABLE_TENNIS"
            74 -> "TENNIS"
            75 -> "VOLLEYBALL"
            76 -> "WALKING"
            77 -> "WATER_POLO"
            78 -> "WEIGHTLIFTING"
            79 -> "WHEELCHAIR"
            80 -> "YOGA"
            else -> "OTHER"
        }
    }

    // ============================================================
    // Weight
    // ============================================================

    @Serializable
    private data class WeightRow(
        val date: String,
        val value_kg: Double,
        val source: String = SOURCE,
        val source_measure_id: String? = null
    )

    suspend fun upsertWeight(accessToken: String, date: String, valueKg: Double, sourceId: String): Boolean {
        return upsertDailyCompat(accessToken, "weight_daily", WeightRow(date, valueKg, SOURCE, sourceId))
    }

    // ============================================================
    // Body Fat
    // ============================================================

    @Serializable
    private data class BodyFatRow(
        val date: String,
        val value_pct: Double,
        val source: String = SOURCE,
        val source_measure_id: String? = null
    )

    suspend fun upsertBodyFat(accessToken: String, date: String, valuePct: Double, sourceId: String): Boolean {
        return upsertDailyCompat(accessToken, "body_fat_daily", BodyFatRow(date, valuePct, SOURCE, sourceId))
    }

    // ============================================================
    // Hydration
    // ============================================================

    @Serializable
    private data class HydrationRow(
        val date: String,
        val value_ml: Double,
        val source: String = SOURCE,
        val source_measure_id: String? = null
    )

    suspend fun upsertHydration(accessToken: String, date: String, valueMl: Double, sourceId: String): Boolean {
        return upsertDailyCompat(accessToken, "hydration_daily", HydrationRow(date, valueMl, SOURCE, sourceId))
    }

    // ============================================================
    // Blood Pressure
    // ============================================================

    @Serializable
    private data class BloodPressureRow(
        val date: String,
        val systolic_mmhg: Double,
        val diastolic_mmhg: Double,
        val source: String = SOURCE,
        val source_measure_id: String? = null
    )

    suspend fun upsertBloodPressure(accessToken: String, date: String, systolic: Double, diastolic: Double, sourceId: String): Boolean {
        return upsertDailyCompat(accessToken, "blood_pressure_daily", BloodPressureRow(date, systolic, diastolic, SOURCE, sourceId))
    }

    // ============================================================
    // Blood Glucose
    // ============================================================

    @Serializable
    private data class BloodGlucoseRow(
        val date: String,
        val value_mmol_l: Double,
        val meal_type: String? = null,
        val source: String = SOURCE,
        val source_measure_id: String? = null
    )

    suspend fun upsertBloodGlucose(accessToken: String, date: String, valueMmol: Double, mealType: String, sourceId: String): Boolean {
        return upsertDailyCompat(accessToken, "blood_glucose_daily", BloodGlucoseRow(date, valueMmol, mealType, SOURCE, sourceId))
    }

    // ============================================================
    // SpO2
    // ============================================================

    @Serializable
    private data class Spo2Row(
        val date: String,
        val value_pct: Double,
        val source: String = SOURCE,
        val source_measure_id: String? = null
    )

    suspend fun upsertSpo2(accessToken: String, date: String, valuePct: Double, sourceId: String): Boolean {
        return upsertDailyCompat(accessToken, "spo2_daily", Spo2Row(date, valuePct, SOURCE, sourceId))
    }

    // ============================================================
    // Respiratory Rate
    // ============================================================

    @Serializable
    private data class RespiratoryRateRow(
        val date: String,
        val value_bpm: Double,
        val source: String = SOURCE,
        val source_measure_id: String? = null
    )

    suspend fun upsertRespiratoryRate(accessToken: String, date: String, valueBpm: Double, sourceId: String): Boolean {
        return upsertDailyCompat(accessToken, "respiratory_rate_daily", RespiratoryRateRow(date, valueBpm, SOURCE, sourceId))
    }

    // ============================================================
    // Skin Temperature
    // ============================================================

    @Serializable
    private data class SkinTempRow(
        val date: String,
        val value_celsius: Double,
        val source: String = SOURCE,
        val source_measure_id: String? = null
    )

    suspend fun upsertSkinTemp(accessToken: String, date: String, valueCelsius: Double, sourceId: String): Boolean {
        return upsertDailyCompat(accessToken, "skin_temp_daily", SkinTempRow(date, valueCelsius, SOURCE, sourceId))
    }
}
