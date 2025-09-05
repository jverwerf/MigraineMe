package com.migraineme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter

/* ---------- UI models ---------- */

data class HistoryItem(
    val id: String,
    val type: String, // "Migraine" | "Medicine" | "Relief"
    val at: Instant,
    val title: String,
    val subtitle: String? = null,
    val migraineId: String? = null, // for meds/reliefs
    val hasFullTimestamps: Boolean = true,
    val missingAmount: Boolean = false,   // medicines
    val missingDuration: Boolean = false  // reliefs
)

data class LogUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val successMsg: String? = null,
    val history: List<HistoryItem> = emptyList(),
    val migrainesForLink: List<Pair<String, String>> = emptyList(), // id -> label
    // Existing badge counters
    val missingTimeCount: Int = 0,
    val missingAmountCount: Int = 0,
    val missingDurationCount: Int = 0,
    val incompleteCount: Int = 0, // kept for compatibility
    // NEW: per-type incomplete counters (used by History tab UI)
    val missingMigraineCount: Int = 0,
    val missingMedicineCount: Int = 0,
    val missingReliefCount: Int = 0
)

/* ---------- ViewModel ---------- */

class LogViewModel : ViewModel() {
    private val _state = MutableStateFlow(LogUiState())
    val state: StateFlow<LogUiState> = _state

    private val db = SupabaseDbService()
    private val iso = DateTimeFormatter.ISO_INSTANT

    /* ---------- preload + history ---------- */

    fun preloadForNew(accessToken: String) {
        viewModelScope.launch {
            try {
                val m = db.recentMigrainesBasic(accessToken).map {
                    val label = "${it.type} • sev ${it.severity} • ${it.beganAt.take(16)}"
                    it.id to label
                }
                _state.value = _state.value.copy(migrainesForLink = m)
            } catch (_: Exception) { /* ignore */ }
        }
    }

    fun loadHistory(accessToken: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, successMsg = null)
            try {
                // Migraines
                val migraines = db.recentMigrainesBasic(accessToken).map {
                    val full = it.endedAt != null
                    HistoryItem(
                        id = it.id,
                        type = "Migraine",
                        at = Instant.parse(it.beganAt),
                        title = "${it.type} (sev ${it.severity})",
                        subtitle = it.endedAt?.let { e -> "Ended ${e.take(16)}" },
                        hasFullTimestamps = full
                    )
                }

                // Medicines
                val meds = db.recentMedicines(accessToken).map {
                    val hasTs = !it.takenAt.isNullOrBlank()
                    val atInstant = it.takenAt
                        ?.let { ta -> runCatching { Instant.parse(ta) }.getOrElse { Instant.EPOCH } }
                        ?: Instant.EPOCH
                    val missingAmt = it.amount.isNullOrBlank()
                    HistoryItem(
                        id = it.id,
                        type = "Medicine",
                        at = atInstant,
                        title = it.name,
                        subtitle = buildString {
                            if (!it.amount.isNullOrBlank()) append(it.amount)
                            if (it.migraineId != null) {
                                if (isNotEmpty()) append(" • ")
                                append("Linked")
                            } else {
                                if (isNotEmpty()) append(" • ")
                                append("Unlinked")
                            }
                            if (!hasTs) {
                                if (isNotEmpty()) append(" • ")
                                append("No time")
                            }
                            if (missingAmt) {
                                if (isNotEmpty()) append(" • ")
                                append("No amount")
                            }
                        },
                        migraineId = it.migraineId,
                        hasFullTimestamps = hasTs,
                        missingAmount = missingAmt
                    )
                }

                // Reliefs
                val rels = db.recentReliefs(accessToken).map {
                    val hasTs = !it.takenAt.isNullOrBlank()
                    val atInstant = it.takenAt
                        ?.let { ta -> runCatching { Instant.parse(ta) }.getOrElse { Instant.EPOCH } }
                        ?: Instant.EPOCH
                    val missingDur = it.durationMinutes == null
                    HistoryItem(
                        id = it.id,
                        type = "Relief",
                        at = atInstant,
                        title = it.type,
                        subtitle = buildString {
                            it.durationMinutes?.let { d -> append("$d min") }
                            if (it.migraineId != null) {
                                if (isNotEmpty()) append(" • ")
                                append("Linked")
                            } else {
                                if (isNotEmpty()) append(" • ")
                                append("Unlinked")
                            }
                            if (!hasTs) {
                                if (isNotEmpty()) append(" • ")
                                append("No time")
                            }
                            if (missingDur) {
                                if (isNotEmpty()) append(" • ")
                                append("No duration")
                            }
                        },
                        migraineId = it.migraineId,
                        hasFullTimestamps = hasTs,
                        missingDuration = missingDur
                    )
                }

                val all = (migraines + meds + rels).sortedByDescending { it.at }

                // Existing counters
                val missingTime =
                    migraines.count { !it.hasFullTimestamps } +
                            meds.count { !it.hasFullTimestamps } +
                            rels.count { !it.hasFullTimestamps }
                val missingAmount = meds.count { it.missingAmount }
                val missingDuration = rels.count { it.missingDuration }

                // NEW: per-type completeness
                val missingMigraine = migraines.count { !it.hasFullTimestamps }
                val missingMedicine = meds.count { (!it.hasFullTimestamps) || it.missingAmount }
                val missingRelief = rels.count { (!it.hasFullTimestamps) || it.missingDuration }

                _state.value = _state.value.copy(
                    loading = false,
                    history = all,
                    missingTimeCount = missingTime,
                    missingAmountCount = missingAmount,
                    missingDurationCount = missingDuration,
                    incompleteCount = missingTime, // compatibility
                    missingMigraineCount = missingMigraine,
                    missingMedicineCount = missingMedicine,
                    missingReliefCount = missingRelief
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Failed to load history")
            }
        }
    }

    /* ---------- Inserts (single) ---------- */

    fun addMigraine(
        accessToken: String,
        type: String,
        severity: Int,
        notes: String?,
        endedAtIso: String? = null,
        beganAtIso: String = iso.format(Instant.now())
    ) {
        viewModelScope.launch {
            try {
                db.insertMigraine(
                    accessToken,
                    SupabaseDbService.MigraineInsert(
                        type = type,
                        severity = severity,
                        beganAt = beganAtIso,
                        endedAt = endedAtIso,
                        notes = notes
                    )
                )
                _state.value = _state.value.copy(successMsg = "Migraine saved")
                loadHistory(accessToken); preloadForNew(accessToken)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Failed to save migraine")
            }
        }
    }

    fun addMedicine(
        accessToken: String,
        name: String,
        amount: String?,
        migraineId: String?,
        notes: String?
    ) {
        viewModelScope.launch {
            try {
                db.insertMedicine(
                    accessToken,
                    SupabaseDbService.MedicineInsert(
                        name = name,
                        amount = amount,
                        takenAt = iso.format(Instant.now()),
                        migraineId = migraineId,
                        notes = notes
                    )
                )
                _state.value = _state.value.copy(successMsg = "Medicine saved")
                loadHistory(accessToken)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Failed to save medicine")
            }
        }
    }

    fun addRelief(
        accessToken: String,
        type: String,
        durationMin: Int?,
        migraineId: String?,
        notes: String?
    ) {
        viewModelScope.launch {
            try {
                db.insertRelief(
                    accessToken,
                    SupabaseDbService.ReliefInsert(
                        type = type,
                        durationMinutes = durationMin,
                        takenAt = iso.format(Instant.now()),
                        migraineId = migraineId,
                        notes = notes
                    )
                )
                _state.value = _state.value.copy(successMsg = "Relief saved")
                loadHistory(accessToken)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Failed to save relief")
            }
        }
    }

    /* ---------- Quick actions (timestamp only) ---------- */

    fun quickStampMigraine(accessToken: String) {
        addMigraine(
            accessToken = accessToken,
            type = "Migraine",
            severity = 5,
            notes = "Quick action",
            endedAtIso = null,
            beganAtIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        )
    }

    fun quickStampMedicine(accessToken: String) {
        addMedicine(
            accessToken = accessToken,
            name = "Medicine",
            amount = null,
            migraineId = null,
            notes = "Quick action"
        )
    }

    fun quickStampRelief(accessToken: String) {
        addRelief(
            accessToken = accessToken,
            type = "Relief",
            durationMin = null,
            migraineId = null,
            notes = "Quick action"
        )
    }

    /* ---------- FULL insert (migraine + many meds/reliefs) ---------- */

    data class MedInput(
        val name: String,
        val amount: String? = null,
        val notes: String? = null,
        val takenAtIso: String? = null
    )
    data class ReliefInput(
        val type: String,
        val durationMinutes: Int? = null,
        val notes: String? = null,
        val takenAtIso: String? = null
    )

    fun addFull(
        accessToken: String,
        type: String,
        severity: Int,
        beganAtIso: String,
        endedAtIso: String?,
        note: String?,
        meds: List<MedInput>,
        rels: List<ReliefInput>
    ) {
        viewModelScope.launch {
            try {
                val m = db.insertMigraine(
                    accessToken,
                    SupabaseDbService.MigraineInsert(
                        type = type,
                        severity = severity,
                        beganAt = beganAtIso,
                        endedAt = endedAtIso,
                        notes = note
                    )
                )

                meds.filter { it.name.isNotBlank() }.forEach { mi ->
                    db.insertMedicine(
                        accessToken,
                        SupabaseDbService.MedicineInsert(
                            name = mi.name,
                            amount = mi.amount,
                            takenAt = mi.takenAtIso ?: iso.format(Instant.now()),
                            migraineId = m.id,
                            notes = mi.notes
                        )
                    )
                }
                rels.filter { it.type.isNotBlank() }.forEach { ri ->
                    db.insertRelief(
                        accessToken,
                        SupabaseDbService.ReliefInsert(
                            type = ri.type,
                            durationMinutes = ri.durationMinutes,
                            takenAt = ri.takenAtIso ?: iso.format(Instant.now()),
                            migraineId = m.id,
                            notes = ri.notes
                        )
                    )
                }

                _state.value = _state.value.copy(successMsg = "Full entry saved")
                loadHistory(accessToken); preloadForNew(accessToken)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Failed to save full entry")
            }
        }
    }

    /* ---------- Edits ---------- */

    fun updateMigraine(
        accessToken: String,
        id: String,
        type: String,
        severity: Int,
        notes: String?,
        endedAtIso: String?
    ) {
        viewModelScope.launch {
            try {
                db.updateMigraine(accessToken, id, type, severity, endedAtIso, notes)
                loadHistory(accessToken); preloadForNew(accessToken)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Failed to update migraine")
            }
        }
    }

    fun updateMedicine(
        accessToken: String,
        id: String,
        name: String,
        amount: String?,
        notes: String?,
        migraineId: String?
    ) {
        viewModelScope.launch {
            try {
                db.updateMedicineFull(accessToken, id, name, amount, notes, migraineId)
                loadHistory(accessToken); preloadForNew(accessToken)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Failed to update medicine")
            }
        }
    }

    fun updateRelief(
        accessToken: String,
        id: String,
        type: String,
        durationMinutes: Int?,
        notes: String?,
        migraineId: String?
    ) {
        viewModelScope.launch {
            try {
                db.updateReliefFull(accessToken, id, type, durationMinutes, notes, migraineId)
                loadHistory(accessToken); preloadForNew(accessToken)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Failed to update relief")
            }
        }
    }

    /* ---------- “Set time later” helpers ---------- */
    fun updateMedicineTime(accessToken: String, id: String, takenAtIso: String) {
        viewModelScope.launch {
            try {
                db.updateMedicineTime(accessToken, id, takenAtIso)
                loadHistory(accessToken)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Failed to set medicine time")
            }
        }
    }

    fun updateReliefTime(accessToken: String, id: String, takenAtIso: String) {
        viewModelScope.launch {
            try {
                db.updateReliefTime(accessToken, id, takenAtIso)
                loadHistory(accessToken)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Failed to set relief time")
            }
        }
    }

    /* ---------- Deletes ---------- */
    fun deleteMigraine(accessToken: String, id: String) {
        viewModelScope.launch {
            try {
                db.deleteMigraine(accessToken, id)
                loadHistory(accessToken); preloadForNew(accessToken)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Failed to delete migraine")
            }
        }
    }
    fun deleteMedicine(accessToken: String, id: String) {
        viewModelScope.launch {
            try {
                db.deleteMedicine(accessToken, id)
                loadHistory(accessToken)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Failed to delete medicine")
            }
        }
    }
    fun deleteRelief(accessToken: String, id: String) {
        viewModelScope.launch {
            try {
                db.deleteRelief(accessToken, id)
                loadHistory(accessToken)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Failed to delete relief")
            }
        }
    }
}
