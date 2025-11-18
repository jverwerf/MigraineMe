// FILE: C:\Users\verwe\Projects\MigraineMe\app\src\main\java\com\migraineme\InsightsViewModel.kt
package com.migraineme

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit

class InsightsViewModel : ViewModel() {

    private val _migraines = MutableStateFlow<List<MigraineSpan>>(emptyList())
    val migraines: StateFlow<List<MigraineSpan>> = _migraines

    private val _reliefs = MutableStateFlow<List<ReliefSpan>>(emptyList())
    val reliefs: StateFlow<List<ReliefSpan>> = _reliefs

    private val _triggers = MutableStateFlow<List<TriggerPoint>>(emptyList())
    val triggers: StateFlow<List<TriggerPoint>> = _triggers

    private val _medicines = MutableStateFlow<List<MedicinePoint>>(emptyList())
    val medicines: StateFlow<List<MedicinePoint>> = _medicines

    // sleep tables for UI
    data class SleepDurationRow(val date: String, val hours: Double)
    data class SleepDisturbancesRow(val date: String, val count: Int)
    data class SleepStagesRow(val date: String, val swsHm: Double, val remHm: Double, val lightHm: Double)

    private val _sleepDuration = MutableStateFlow<List<SleepDurationRow>>(emptyList())
    val sleepDuration: StateFlow<List<SleepDurationRow>> = _sleepDuration

    private val _sleepDisturbances = MutableStateFlow<List<SleepDisturbancesRow>>(emptyList())
    val sleepDisturbances: StateFlow<List<SleepDisturbancesRow>> = _sleepDisturbances

    private val _sleepStages = MutableStateFlow<List<SleepStagesRow>>(emptyList())
    val sleepStages: StateFlow<List<SleepStagesRow>> = _sleepStages

    // location table for UI
    data class UserLocationRow(val date: String, val latitude: Double, val longitude: Double)

    private val _userLocations = MutableStateFlow<List<UserLocationRow>>(emptyList())
    val userLocations: StateFlow<List<UserLocationRow>> = _userLocations

    // NEW: expose latest WHOOP sleep date so any screen that references it compiles
    private val _latestSleepDate = MutableStateFlow<String?>(null)
    val latestSleepDate: StateFlow<String?> = _latestSleepDate

    private val db = SupabaseDbService(
        BuildConfig.SUPABASE_URL,
        BuildConfig.SUPABASE_ANON_KEY
    )

    fun load(context: Context, accessToken: String) {
        viewModelScope.launch {
            try {
                val now = Instant.now()

                val migs = db.getMigraines(accessToken)
                _migraines.value = migs.map { row ->
                    MigraineSpan(
                        start = Instant.parse(row.startAt),
                        end = row.endAt?.let { Instant.parse(it) },
                        severity = row.severity,
                        label = row.type
                    )
                }

                val rels = db.getAllReliefs(accessToken)
                _reliefs.value = rels.map { row ->
                    val start = Instant.parse(row.startAt)
                    val end = row.durationMinutes?.let { start.plus(it.toLong(), ChronoUnit.MINUTES) } ?: now
                    ReliefSpan(
                        start = start,
                        end = end,
                        intensity = null,
                        name = row.type ?: "Relief"
                    )
                }

                val meds = db.getAllMedicines(accessToken)
                _medicines.value = meds.map { row ->
                    MedicinePoint(
                        at = Instant.parse(row.startAt),
                        name = row.name ?: "Medicine",
                        amount = row.amount
                    )
                }

                val trigs = db.getAllTriggers(accessToken)
                _triggers.value = trigs.map { row ->
                    TriggerPoint(
                        at = Instant.parse(row.startAt),
                        name = row.type ?: "Trigger"
                    )
                }

                // sleep tables via metrics service
                val metrics = SupabaseMetricsService(context)

                val dur = metrics.fetchSleepDurationDaily(accessToken, limitDays = 180)
                _sleepDuration.value = dur.map { SleepDurationRow(it.date, it.value_hours) }
                // latest date: list is ordered desc in fetch, so firstOrNull is newest
                _latestSleepDate.value = dur.firstOrNull()?.date

                val dst = metrics.fetchSleepDisturbancesDaily(accessToken, limitDays = 180)
                _sleepDisturbances.value = dst.map { SleepDisturbancesRow(it.date, it.value_count) }

                val stg = metrics.fetchSleepStagesDaily(accessToken, limitDays = 180)
                _sleepStages.value = stg.map { SleepStagesRow(it.date, it.value_sws_hm, it.value_rem_hm, it.value_light_hm) }

                // user location via personal service
                val personal = SupabasePersonalService(context)
                val locs = personal.fetchUserLocationDaily(accessToken, limitDays = 180)
                _userLocations.value = locs.map { UserLocationRow(it.date, it.latitude, it.longitude) }

            } catch (_: Exception) {
                _migraines.value = emptyList()
                _reliefs.value = emptyList()
                _triggers.value = emptyList()
                _medicines.value = emptyList()
                _sleepDuration.value = emptyList()
                _sleepDisturbances.value = emptyList()
                _sleepStages.value = emptyList()
                _userLocations.value = emptyList()
                _latestSleepDate.value = null
            }
        }
    }
}
