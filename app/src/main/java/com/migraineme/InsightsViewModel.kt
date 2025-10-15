// app/src/main/java/com/migraineme/InsightsViewModel.kt
package com.migraineme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit

class InsightsViewModel : ViewModel() {

    private val db = SupabaseDbService(
        BuildConfig.SUPABASE_URL,
        BuildConfig.SUPABASE_ANON_KEY
    )

    private val _migraines = MutableStateFlow<List<MigraineSpan>>(emptyList())
    val migraines: StateFlow<List<MigraineSpan>> = _migraines

    private val _reliefs = MutableStateFlow<List<ReliefSpan>>(emptyList())
    val reliefs: StateFlow<List<ReliefSpan>> = _reliefs

    private val _triggers = MutableStateFlow<List<TriggerPoint>>(emptyList())
    val triggers: StateFlow<List<TriggerPoint>> = _triggers

    private val _medicines = MutableStateFlow<List<MedicinePoint>>(emptyList())
    val medicines: StateFlow<List<MedicinePoint>> = _medicines

    fun load(accessToken: String) {
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
            } catch (_: Exception) {
                _migraines.value = emptyList()
                _reliefs.value = emptyList()
                _triggers.value = emptyList()
                _medicines.value = emptyList()
            }
        }
    }
}
