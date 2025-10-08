package com.migraineme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter

// --- drafts ---
data class MigraineDraft(
    val type: String? = null,
    val severity: Int? = null,
    val beganAtIso: String? = null,
    val endedAtIso: String? = null,
    val note: String? = null
)

data class TriggerDraft(
    val type: String,
    val startAtIso: String? = null,
    val note: String? = null
)

data class MedicineDraft(
    val name: String? = null,
    val amount: String? = null,
    val notes: String? = null,
    val startAtIso: String? = null
)

data class ReliefDraft(
    val type: String,
    val durationMinutes: Int? = null,
    val notes: String? = null,
    val startAtIso: String? = null
)

data class Draft(
    val migraine: MigraineDraft? = null,
    val triggers: List<TriggerDraft> = emptyList(),
    val meds: List<MedicineDraft> = emptyList(),
    val rels: List<ReliefDraft> = emptyList()
)

// --- journal event feed ---
sealed class JournalEvent {
    data class Migraine(val row: SupabaseDbService.MigraineRow) : JournalEvent()
    data class Trigger(val row: SupabaseDbService.TriggerRow) : JournalEvent()
    data class Medicine(val row: SupabaseDbService.MedicineRow) : JournalEvent()
    data class Relief(val row: SupabaseDbService.ReliefRow) : JournalEvent()
}

class LogViewModel : ViewModel() {

    private val db = SupabaseDbService(
        BuildConfig.SUPABASE_URL,
        BuildConfig.SUPABASE_ANON_KEY
    )

    private val _draft = MutableStateFlow(Draft())
    val draft: StateFlow<Draft> = _draft

    private val _migraines = MutableStateFlow<List<SupabaseDbService.MigraineRow>>(emptyList())
    val migraines: StateFlow<List<SupabaseDbService.MigraineRow>> = _migraines

    private val _journal = MutableStateFlow<List<JournalEvent>>(emptyList())
    val journal: StateFlow<List<JournalEvent>> = _journal

    // ---- draft mutators ----
    fun setMigraineDraft(
        type: String?,
        severity: Int?,
        beganAtIso: String?,
        endedAtIso: String?,
        note: String?
    ) {
        _draft.value = _draft.value.copy(
            migraine = MigraineDraft(type, severity, beganAtIso, endedAtIso, note)
        )
    }

    fun addTriggerDraft(trigger: String, startAtIso: String? = null, note: String? = null) {
        _draft.value = _draft.value.copy(
            triggers = _draft.value.triggers + TriggerDraft(trigger, startAtIso, note)
        )
    }

    fun addMedicineDraft(name: String, amount: String?, notes: String?, startAtIso: String? = null) {
        _draft.value = _draft.value.copy(
            meds = _draft.value.meds + MedicineDraft(name, amount, notes, startAtIso)
        )
    }

    fun addReliefDraft(type: String, durationMinutes: Int?, notes: String?, startAtIso: String? = null) {
        _draft.value = _draft.value.copy(
            rels = _draft.value.rels + ReliefDraft(type, durationMinutes, notes, startAtIso)
        )
    }

    fun clearDraft() { _draft.value = Draft() }

    // ---- quick actions ----
    fun addMigraine(accessToken: String) {
        viewModelScope.launch {
            try {
                val nowIso = Instant.now().toString()
                db.insertMigraine(accessToken, type = "Migraine", severity = null, startAt = nowIso, endAt = null, notes = null)
                loadJournal(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun addMedicine(accessToken: String, name: String, amount: String? = null, notes: String? = null) {
        viewModelScope.launch {
            try {
                val nowIso = Instant.now().toString()
                db.insertMedicine(accessToken, migraineId = null, name = name, amount = amount, startAt = nowIso, notes = notes)
                loadJournal(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun addRelief(accessToken: String, type: String, durationMinutes: Int? = null, notes: String? = null) {
        viewModelScope.launch {
            try {
                val nowIso = Instant.now().toString()
                db.insertRelief(accessToken, migraineId = null, type = type, durationMinutes = durationMinutes, startAt = nowIso, notes = notes)
                loadJournal(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ---- final save ----
    fun addFull(
        accessToken: String,
        type: String?,
        severity: Int?,
        beganAtIso: String,
        endedAtIso: String?,
        note: String?,
        meds: List<MedicineDraft>,
        rels: List<ReliefDraft>
    ) {
        // ✅ snapshot triggers immediately
        val triggersSnapshot = _draft.value.triggers

        viewModelScope.launch {
            try {
                val migraineStart = beganAtIso.ifBlank {
                    DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                }

                val migraine = db.insertMigraine(
                    accessToken = accessToken,
                    type = type,
                    severity = severity,
                    startAt = migraineStart,
                    endAt = endedAtIso,
                    notes = note
                )

                // ✅ use snapshot so triggers aren’t lost
                for (t in triggersSnapshot.filter { it.type.isNotBlank() }) {
                    try {
                        db.insertTrigger(
                            accessToken = accessToken,
                            migraineId = migraine.id,
                            type = t.type,
                            startAt = t.startAtIso ?: migraineStart,
                            notes = t.note
                        )
                    } catch (e: Exception) { e.printStackTrace() }
                }

                for (m in meds.filter { !it.name.isNullOrBlank() }) {
                    try {
                        db.insertMedicine(
                            accessToken = accessToken,
                            migraineId = migraine.id,
                            name = m.name,
                            amount = m.amount,
                            startAt = m.startAtIso ?: migraineStart,
                            notes = m.notes
                        )
                    } catch (e: Exception) { e.printStackTrace() }
                }

                for (r in rels.filter { it.type.isNotBlank() }) {
                    try {
                        db.insertRelief(
                            accessToken = accessToken,
                            migraineId = migraine.id,
                            type = r.type,
                            durationMinutes = r.durationMinutes,
                            startAt = r.startAtIso ?: migraineStart,
                            notes = r.notes
                        )
                    } catch (e: Exception) { e.printStackTrace() }
                }

                loadJournal(accessToken)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ---- fetch only migraines ----
    fun loadMigraines(accessToken: String) {
        viewModelScope.launch {
            try {
                val rows = db.getMigraines(accessToken)
                _migraines.value = rows
                println("DEBUG loadMigraines: count=${rows.size}")
            } catch (e: Exception) {
                e.printStackTrace()
                _migraines.value = emptyList()
            }
        }
    }

    // ---- unified journal feed ----
    fun loadJournal(accessToken: String) {
        viewModelScope.launch {
            try {
                val migraines = db.getMigraines(accessToken).map { JournalEvent.Migraine(it) }
                val triggers = db.getAllTriggers(accessToken).map { JournalEvent.Trigger(it) }
                val medicines = db.getAllMedicines(accessToken).map { JournalEvent.Medicine(it) }
                val reliefs = db.getAllReliefs(accessToken).map { JournalEvent.Relief(it) }

                val merged = (migraines + triggers + medicines + reliefs).sortedByDescending { ev ->
                    when (ev) {
                        is JournalEvent.Migraine -> ev.row.startAt
                        is JournalEvent.Trigger -> ev.row.startAt
                        is JournalEvent.Medicine -> ev.row.startAt
                        is JournalEvent.Relief -> ev.row.startAt
                    }
                }
                _journal.value = merged
                println("DEBUG loadJournal: count=${merged.size}")
            } catch (e: Exception) {
                e.printStackTrace()
                _journal.value = emptyList()
            }
        }
    }
}
