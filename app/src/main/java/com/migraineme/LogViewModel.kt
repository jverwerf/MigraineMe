package com.migraineme

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter

// --- drafts ---
data class MigraineDraft(
    val type: String? = null,
    val symptoms: List<String> = emptyList(),
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
    val startAtIso: String? = null,
    val reliefScale: String? = "NONE"
)

data class ReliefDraft(
    val type: String,
    val notes: String? = null,
    val startAtIso: String? = null,
    val endAtIso: String? = null,
    val reliefScale: String? = "NONE"
)

data class ProdromeDraft(
    val type: String,
    val startAtIso: String? = null,
    val note: String? = null
)

data class LocationDraft(
    val type: String,
    val startAtIso: String? = null,
    val note: String? = null
)

data class ActivityDraft(
    val type: String,
    val startAtIso: String? = null,
    val note: String? = null
)

data class MissedActivityDraft(
    val type: String,
    val startAtIso: String? = null,
    val note: String? = null
)

data class Draft(
    val migraine: MigraineDraft? = null,
    val painLocations: List<String> = emptyList(),
    val triggers: List<TriggerDraft> = emptyList(),
    val meds: List<MedicineDraft> = emptyList(),
    val rels: List<ReliefDraft> = emptyList(),
    val prodromes: List<ProdromeDraft> = emptyList(),
    val locations: List<LocationDraft> = emptyList(),
    val activities: List<ActivityDraft> = emptyList(),
    val missedActivities: List<MissedActivityDraft> = emptyList()
)

// --- journal event feed ---
sealed class JournalEvent {
    data class Migraine(val row: SupabaseDbService.MigraineRow) : JournalEvent()
    data class Trigger(val row: SupabaseDbService.TriggerRow) : JournalEvent()
    data class Medicine(val row: SupabaseDbService.MedicineRow) : JournalEvent()
    data class Relief(val row: SupabaseDbService.ReliefRow) : JournalEvent()
}

class LogViewModel(application: Application) : AndroidViewModel(application) {

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

    // --- single-entry edit state ---
    private val _editMigraine = MutableStateFlow<SupabaseDbService.MigraineRow?>(null)
    val editMigraine: StateFlow<SupabaseDbService.MigraineRow?> = _editMigraine

    private val _editTrigger = MutableStateFlow<SupabaseDbService.TriggerRow?>(null)
    val editTrigger: StateFlow<SupabaseDbService.TriggerRow?> = _editTrigger

    private val _editMedicine = MutableStateFlow<SupabaseDbService.MedicineRow?>(null)
    val editMedicine: StateFlow<SupabaseDbService.MedicineRow?> = _editMedicine

    private val _editRelief = MutableStateFlow<SupabaseDbService.ReliefRow?>(null)
    val editRelief: StateFlow<SupabaseDbService.ReliefRow?> = _editRelief

    // --- dropdown options (flat kept for compatibility) ---
    private val _migraineOptions = MutableStateFlow<List<String>>(emptyList())
    val migraineOptions: StateFlow<List<String>> = _migraineOptions

    private val _triggerOptions = MutableStateFlow<List<String>>(emptyList())
    val triggerOptions: StateFlow<List<String>> = _triggerOptions

    private val _medicineOptions = MutableStateFlow<List<String>>(emptyList())
    val medicineOptions: StateFlow<List<String>> = _medicineOptions

    private val _reliefOptions = MutableStateFlow<List<String>>(emptyList())
    val reliefOptions: StateFlow<List<String>> = _reliefOptions

    // --- sectioned options ---
    private val _migraineOptionsFrequent = MutableStateFlow<List<String>>(emptyList())
    val migraineOptionsFrequent: StateFlow<List<String>> = _migraineOptionsFrequent
    private val _migraineOptionsAll = MutableStateFlow<List<String>>(emptyList())
    val migraineOptionsAll: StateFlow<List<String>> = _migraineOptionsAll

    private val _triggerOptionsFrequent = MutableStateFlow<List<String>>(emptyList())
    val triggerOptionsFrequent: StateFlow<List<String>> = _triggerOptionsFrequent
    private val _triggerOptionsAll = MutableStateFlow<List<String>>(emptyList())
    val triggerOptionsAll: StateFlow<List<String>> = _triggerOptionsAll

    private val _medicineOptionsFrequent = MutableStateFlow<List<String>>(emptyList())
    val medicineOptionsFrequent: StateFlow<List<String>> = _medicineOptionsFrequent
    private val _medicineOptionsAll = MutableStateFlow<List<String>>(emptyList())
    val medicineOptionsAll: StateFlow<List<String>> = _medicineOptionsAll

    private val _reliefOptionsFrequent = MutableStateFlow<List<String>>(emptyList())
    val reliefOptionsFrequent: StateFlow<List<String>> = _reliefOptionsFrequent
    private val _reliefOptionsAll = MutableStateFlow<List<String>>(emptyList())
    val reliefOptionsAll: StateFlow<List<String>> = _reliefOptionsAll

    // ---- draft mutators ----
    fun setMigraineDraft(
        type: String? = null,
        severity: Int? = null,
        beganAtIso: String? = null,
        endedAtIso: String? = null,
        note: String? = null,
        symptoms: List<String>? = null,
        // Pass true to explicitly clear a nullable field to null
        clearBeganAt: Boolean = false,
        clearEndedAt: Boolean = false,
        clearNote: Boolean = false
    ) {
        val existing = _draft.value.migraine ?: MigraineDraft()
        _draft.value = _draft.value.copy(
            migraine = existing.copy(
                type = type ?: existing.type,
                symptoms = symptoms ?: existing.symptoms,
                severity = severity ?: existing.severity,
                beganAtIso = if (clearBeganAt) null else (beganAtIso ?: existing.beganAtIso),
                endedAtIso = if (clearEndedAt) null else (endedAtIso ?: existing.endedAtIso),
                note = if (clearNote) null else (note ?: existing.note)
            )
        )
    }

    fun setSymptomsDraft(symptoms: List<String>) {
        val existing = _draft.value.migraine ?: MigraineDraft()
        _draft.value = _draft.value.copy(
            migraine = existing.copy(symptoms = symptoms)
        )
    }

    fun setPainLocationsDraft(locations: List<String>) {
        _draft.value = _draft.value.copy(painLocations = locations)
    }

    fun addTriggerDraft(trigger: String, startAtIso: String? = null, note: String? = null) {
        _draft.value = _draft.value.copy(
            triggers = _draft.value.triggers + TriggerDraft(trigger, startAtIso, note)
        )
    }

    fun addMedicineDraft(name: String, amount: String?, notes: String?, startAtIso: String? = null, reliefScale: String? = "NONE") {
        _draft.value = _draft.value.copy(
            meds = _draft.value.meds + MedicineDraft(name, amount, notes, startAtIso, reliefScale)
        )
    }

    fun addReliefDraft(type: String, notes: String? = null, startAtIso: String? = null, endAtIso: String? = null, reliefScale: String? = "NONE") {
        _draft.value = _draft.value.copy(
            rels = _draft.value.rels + ReliefDraft(type, notes, startAtIso, endAtIso, reliefScale)
        )
    }

    fun addProdromeDraft(type: String, startAtIso: String? = null, note: String? = null) {
        _draft.value = _draft.value.copy(
            prodromes = _draft.value.prodromes + ProdromeDraft(type, startAtIso, note)
        )
    }

    fun addLocationDraft(type: String, startAtIso: String? = null, note: String? = null) {
        _draft.value = _draft.value.copy(
            locations = _draft.value.locations + LocationDraft(type, startAtIso, note)
        )
    }

    fun addActivityDraft(type: String, startAtIso: String? = null, note: String? = null) {
        _draft.value = _draft.value.copy(
            activities = _draft.value.activities + ActivityDraft(type, startAtIso, note)
        )
    }

    fun addMissedActivityDraft(type: String, startAtIso: String? = null, note: String? = null) {
        _draft.value = _draft.value.copy(
            missedActivities = _draft.value.missedActivities + MissedActivityDraft(type, startAtIso, note)
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

    fun addRelief(accessToken: String, type: String, notes: String? = null) {
        viewModelScope.launch {
            try {
                val nowIso = Instant.now().toString()
                db.insertRelief(accessToken, migraineId = null, type = type, startAt = nowIso, notes = notes)
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
        painLocations: List<String>,
        meds: List<MedicineDraft>,
        rels: List<ReliefDraft>
    ) {
        val triggersSnapshot = _draft.value.triggers
        val prodromesSnapshot = _draft.value.prodromes
        val locationsSnapshot = _draft.value.locations
        val activitiesSnapshot = _draft.value.activities
        val missedActivitiesSnapshot = _draft.value.missedActivities

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
                    notes = note,
                    painLocations = painLocations.takeIf { it.isNotEmpty() }
                )

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
                            notes = m.notes,
                            reliefScale = m.reliefScale
                        )
                    } catch (e: Exception) { e.printStackTrace() }
                }

                for (r in rels.filter { it.type.isNotBlank() }) {
                    try {
                        db.insertRelief(
                            accessToken = accessToken,
                            migraineId = migraine.id,
                            type = r.type,
                            startAt = r.startAtIso ?: migraineStart,
                            notes = r.notes,
                            endAt = r.endAtIso,
                            reliefScale = r.reliefScale
                        )
                    } catch (e: Exception) { e.printStackTrace() }
                }

                for (p in prodromesSnapshot.filter { it.type.isNotBlank() }) {
                    try {
                        db.insertProdrome(
                            accessToken = accessToken,
                            migraineId = migraine.id,
                            type = p.type,
                            startAt = p.startAtIso ?: migraineStart,
                            notes = p.note
                        )
                    } catch (e: Exception) { e.printStackTrace() }
                }

                for (loc in locationsSnapshot.filter { it.type.isNotBlank() }) {
                    try {
                        db.insertLocation(
                            accessToken = accessToken,
                            migraineId = migraine.id,
                            type = loc.type,
                            startAt = loc.startAtIso ?: migraineStart,
                            notes = loc.note
                        )
                    } catch (e: Exception) { e.printStackTrace() }
                }

                for (act in activitiesSnapshot.filter { it.type.isNotBlank() }) {
                    try {
                        db.insertActivity(
                            accessToken = accessToken,
                            migraineId = migraine.id,
                            type = act.type,
                            startAt = act.startAtIso ?: migraineStart,
                            notes = act.note
                        )
                    } catch (e: Exception) { e.printStackTrace() }
                }

                for (ma in missedActivitiesSnapshot.filter { it.type.isNotBlank() }) {
                    try {
                        db.insertMissedActivity(
                            accessToken = accessToken,
                            migraineId = migraine.id,
                            type = ma.type,
                            startAt = ma.startAtIso ?: migraineStart,
                            notes = ma.note
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

    // ---- removals ----
    fun removeMigraine(accessToken: String, id: String) {
        viewModelScope.launch {
            try {
                db.deleteMigraine(accessToken, id)
                loadJournal(accessToken)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun removeTrigger(accessToken: String, id: String) {
        viewModelScope.launch {
            try {
                db.deleteTrigger(accessToken, id)
                loadJournal(accessToken)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun removeMedicine(accessToken: String, id: String) {
        viewModelScope.launch {
            try {
                db.deleteMedicine(accessToken, id)
                loadJournal(accessToken)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun removeRelief(accessToken: String, id: String) {
        viewModelScope.launch {
            try {
                db.deleteRelief(accessToken, id)
                loadJournal(accessToken)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    // ---- load single by id for edit ----
    fun loadMigraineById(accessToken: String, id: String) {
        viewModelScope.launch {
            try {
                _editMigraine.value = db.getMigraineById(accessToken, id)
            } catch (e: Exception) { e.printStackTrace(); _editMigraine.value = null }
        }
    }

    fun loadTriggerById(accessToken: String, id: String) {
        viewModelScope.launch {
            try {
                _editTrigger.value = db.getTriggerById(accessToken, id)
            } catch (e: Exception) { e.printStackTrace(); _editTrigger.value = null }
        }
    }

    fun loadMedicineById(accessToken: String, id: String) {
        viewModelScope.launch {
            try {
                _editMedicine.value = db.getMedicineById(accessToken, id)
            } catch (e: Exception) { e.printStackTrace(); _editMedicine.value = null }
        }
    }

    fun loadReliefById(accessToken: String, id: String) {
        viewModelScope.launch {
            try {
                _editRelief.value = db.getReliefById(accessToken, id)
            } catch (e: Exception) { e.printStackTrace(); _editRelief.value = null }
        }
    }

    // ---- updates for edit screens ----
    fun updateMigraine(
        accessToken: String,
        id: String,
        type: String? = null,
        severity: Int? = null,
        startAt: String? = null,
        endAt: String? = null,
        notes: String? = null
    ) {
        viewModelScope.launch {
            try {
                val updated = db.updateMigraine(accessToken, id, type, severity, startAt, endAt, notes)
                _editMigraine.value = updated
                loadJournal(accessToken)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun updateTrigger(
        accessToken: String,
        id: String,
        type: String? = null,
        startAt: String? = null,
        notes: String? = null,
        migraineId: String? = null
    ) {
        viewModelScope.launch {
            try {
                val updated = db.updateTrigger(accessToken, id, type, startAt, notes, migraineId)
                _editTrigger.value = updated
                loadJournal(accessToken)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun updateMedicine(
        accessToken: String,
        id: String,
        name: String? = null,
        amount: String? = null,
        startAt: String? = null,
        notes: String? = null,
        migraineId: String? = null
    ) {
        viewModelScope.launch {
            try {
                val updated = db.updateMedicine(accessToken, id, name, amount, startAt, notes, migraineId)
                _editMedicine.value = updated
                loadJournal(accessToken)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun updateRelief(
        accessToken: String,
        id: String,
        type: String? = null,
        startAt: String? = null,
        notes: String? = null,
        migraineId: String? = null
    ) {
        viewModelScope.launch {
            try {
                val updated = db.updateRelief(accessToken, id, type, startAt, notes, migraineId)
                _editRelief.value = updated
                loadJournal(accessToken)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    // ---- option loaders for dropdowns ----
    fun loadMigraineOptions(accessToken: String) {
        viewModelScope.launch {
            try {
                val prefs = db.getMigrainePrefs(accessToken)
                val pool = db.getAllMigrainePool(accessToken)

                val frequent = prefs
                    .filter { it.status == "frequent" }
                    .sortedBy { it.position }
                    .mapNotNull { it.migraine?.label?.trim() }
                    .filter { it.isNotEmpty() }

                val all = pool.mapNotNull { it.label.trim() }.filter { it.isNotEmpty() }
                val allMinusFrequent = all.filter { it !in frequent }.sorted()

                _migraineOptionsFrequent.value = frequent
                _migraineOptionsAll.value = allMinusFrequent
                _migraineOptions.value = buildList { addAll(frequent); addAll(allMinusFrequent) }
            } catch (e: Exception) {
                e.printStackTrace()
                _migraineOptionsFrequent.value = emptyList()
                _migraineOptionsAll.value = emptyList()
                _migraineOptions.value = emptyList()
            }
        }
    }

    fun loadTriggerOptions(accessToken: String) {
        viewModelScope.launch {
            try {
                val prefs = db.getTriggerPrefs(accessToken)
                val pool = db.getAllTriggerPool(accessToken)

                val frequent = prefs
                    .filter { it.status == "frequent" }
                    .sortedBy { it.position }
                    .mapNotNull { it.trigger?.label?.trim() }
                    .filter { it.isNotEmpty() }

                val all = pool.mapNotNull { it.label.trim() }.filter { it.isNotEmpty() }
                val allMinusFrequent = all.filter { it !in frequent }.sorted()

                _triggerOptionsFrequent.value = frequent
                _triggerOptionsAll.value = allMinusFrequent
                _triggerOptions.value = buildList { addAll(frequent); addAll(allMinusFrequent) }
            } catch (e: Exception) {
                e.printStackTrace()
                _triggerOptionsFrequent.value = emptyList()
                _triggerOptionsAll.value = emptyList()
                _triggerOptions.value = emptyList()
            }
        }
    }

    fun loadMedicineOptions(accessToken: String) {
        viewModelScope.launch {
            try {
                val prefs = db.getMedicinePrefs(accessToken)
                val pool = db.getAllMedicinePool(accessToken)

                val frequent = prefs
                    .filter { it.status == "frequent" }
                    .sortedBy { it.position }
                    .mapNotNull { it.medicine?.label?.trim() }
                    .filter { it.isNotEmpty() }

                val all = pool.mapNotNull { it.label.trim() }.filter { it.isNotEmpty() }
                val allMinusFrequent = all.filter { it !in frequent }.sorted()

                _medicineOptionsFrequent.value = frequent
                _medicineOptionsAll.value = allMinusFrequent
                _medicineOptions.value = buildList { addAll(frequent); addAll(allMinusFrequent) }
            } catch (e: Exception) {
                e.printStackTrace()
                _medicineOptionsFrequent.value = emptyList()
                _medicineOptionsAll.value = emptyList()
                _medicineOptions.value = emptyList()
            }
        }
    }

    fun loadReliefOptions(accessToken: String) {
        viewModelScope.launch {
            try {
                val prefs = db.getReliefPrefs(accessToken)
                val pool = db.getAllReliefPool(accessToken)

                val frequent = prefs
                    .filter { it.status == "frequent" }
                    .sortedBy { it.position }
                    .mapNotNull { it.relief?.label?.trim() }
                    .filter { it.isNotEmpty() }

                val all = pool.mapNotNull { it.label.trim() }.filter { it.isNotEmpty() }
                val allMinusFrequent = all.filter { it !in frequent }.sorted()

                _reliefOptionsFrequent.value = frequent
                _reliefOptionsAll.value = allMinusFrequent
                _reliefOptions.value = buildList { addAll(frequent); addAll(allMinusFrequent) }
            } catch (e: Exception) {
                e.printStackTrace()
                _reliefOptionsFrequent.value = emptyList()
                _reliefOptionsAll.value = emptyList()
                _reliefOptions.value = emptyList()
            }
        }
    }
}
