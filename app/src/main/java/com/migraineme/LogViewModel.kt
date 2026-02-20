package com.migraineme

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
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
    val note: String? = null,
    val existingId: String? = null
)

data class MedicineDraft(
    val name: String? = null,
    val amount: String? = null,
    val notes: String? = null,
    val startAtIso: String? = null,
    val reliefScale: String? = "NONE",
    val existingId: String? = null
)

data class ReliefDraft(
    val type: String,
    val notes: String? = null,
    val startAtIso: String? = null,
    val endAtIso: String? = null,
    val reliefScale: String? = "NONE",
    val existingId: String? = null
)

data class ProdromeDraft(
    val type: String,
    val startAtIso: String? = null,
    val note: String? = null,
    val existingId: String? = null
)

data class LocationDraft(
    val type: String,
    val startAtIso: String? = null,
    val note: String? = null,
    val existingId: String? = null
)

data class ActivityDraft(
    val type: String,
    val startAtIso: String? = null,
    val endAtIso: String? = null,
    val note: String? = null,
    val existingId: String? = null
)

data class MissedActivityDraft(
    val type: String,
    val startAtIso: String? = null,
    val note: String? = null,
    val existingId: String? = null
)

data class Draft(
    val editMigraineId: String? = null,
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
    data class Migraine(val row: SupabaseDbService.MigraineRow, val linked: SupabaseDbService.MigraineLinkedItems = SupabaseDbService.MigraineLinkedItems()) : JournalEvent()
    data class Trigger(val row: SupabaseDbService.TriggerRow) : JournalEvent()
    data class Medicine(val row: SupabaseDbService.MedicineRow) : JournalEvent()
    data class Relief(val row: SupabaseDbService.ReliefRow) : JournalEvent()
    data class Prodrome(val row: SupabaseDbService.ProdromeLogRow) : JournalEvent()
    data class Location(val row: SupabaseDbService.LocationLogRow) : JournalEvent()
    data class Activity(val row: SupabaseDbService.ActivityLogRow) : JournalEvent()
    data class MissedActivity(val row: SupabaseDbService.MissedActivityLogRow) : JournalEvent()
}

class LogViewModel(application: Application) : AndroidViewModel(application) {

    private val db = SupabaseDbService(
        BuildConfig.SUPABASE_URL,
        BuildConfig.SUPABASE_ANON_KEY
    )

    private val edge = EdgeFunctionsService()

    private val _draft = MutableStateFlow(Draft())
    val draft: StateFlow<Draft> = _draft

    /** When non-null, the wizard is in "edit" mode for this migraine. Review will update instead of insert. */
    private val _editMigraineId = MutableStateFlow<String?>(null)
    val editMigraineId: StateFlow<String?> = _editMigraineId

    /** Pre-populate the draft from an existing migraine + all linked items (ignoring automated/system items). */
    fun prefillForEdit(accessToken: String, migraineId: String, onReady: () -> Unit) {
        viewModelScope.launch {
            try {
                val migraines = db.getMigraines(accessToken)
                val row = migraines.find { it.id == migraineId } ?: return@launch
                val linked = db.getLinkedItems(accessToken, migraineId)

                _editMigraineId.value = migraineId
                println("DEBUG prefillForEdit: setting editMigraineId=$migraineId")
                // The type field stores joined symptom labels like "Throbbing, Nausea"
                // Split back into individual symptoms for the picker
                val symptomLabels = row.type
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() && it != "Migraine" }
                    ?: emptyList()

                _draft.value = Draft(
                    editMigraineId = migraineId,
                    migraine = MigraineDraft(
                        type = row.type,
                        symptoms = symptomLabels,
                        severity = row.severity,
                        beganAtIso = row.startAt,
                        endedAtIso = row.endAt,
                        note = row.notes
                    ),
                    painLocations = row.painLocations ?: emptyList(),
                    triggers = linked.triggers
                        .filter { it.source != "system" }
                        .map { TriggerDraft(it.type ?: "", it.startAt, it.notes, existingId = it.id) },
                    meds = linked.medicines.map { MedicineDraft(it.name, it.amount, it.notes, it.startAt, null, existingId = it.id) },
                    rels = linked.reliefs.map { ReliefDraft(it.type ?: "", it.notes, it.startAt, it.endAt, null, existingId = it.id) },
                    prodromes = linked.prodromes.map { ProdromeDraft(it.type ?: "", it.startAt, it.notes, existingId = it.id) },
                    locations = linked.locations.map { LocationDraft(it.type ?: "", it.startAt, it.notes, existingId = it.id) },
                    activities = linked.activities.map { ActivityDraft(it.type ?: "", it.startAt, it.endAt, it.notes, existingId = it.id) }
                )
                onReady()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private val _migraines = MutableStateFlow<List<SupabaseDbService.MigraineRow>>(emptyList())
    val migraines: StateFlow<List<SupabaseDbService.MigraineRow>> = _migraines

    private val _journal = MutableStateFlow<List<JournalEvent>>(emptyList())
    val journal: StateFlow<List<JournalEvent>> = _journal

    private val _journalLoading = MutableStateFlow(false)
    val journalLoading: StateFlow<Boolean> = _journalLoading

    private val _triggerLabelMap = MutableStateFlow<Map<String, String>>(emptyMap())
    val triggerLabelMap: StateFlow<Map<String, String>> = _triggerLabelMap

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

    fun addActivityDraft(type: String, startAtIso: String? = null, endAtIso: String? = null, note: String? = null) {
        _draft.value = _draft.value.copy(
            activities = _draft.value.activities + ActivityDraft(type, startAtIso, endAtIso, note)
        )
    }

    fun addMissedActivityDraft(type: String, startAtIso: String? = null, note: String? = null) {
        _draft.value = _draft.value.copy(
            missedActivities = _draft.value.missedActivities + MissedActivityDraft(type, startAtIso, note)
        )
    }

    fun clearDraft() { _draft.value = Draft(); _editMigraineId.value = null }

    // Direct list replacements – preserves editMigraineId and existingIds on other lists
    fun replaceTriggers(triggers: List<TriggerDraft>) {
        _draft.value = _draft.value.copy(triggers = triggers)
    }
    fun replaceProdromes(prodromes: List<ProdromeDraft>) {
        _draft.value = _draft.value.copy(prodromes = prodromes)
    }
    fun replaceMedicines(meds: List<MedicineDraft>) {
        _draft.value = _draft.value.copy(meds = meds)
    }
    fun replaceReliefs(rels: List<ReliefDraft>) {
        _draft.value = _draft.value.copy(rels = rels)
    }
    fun replaceLocations(locations: List<LocationDraft>) {
        _draft.value = _draft.value.copy(locations = locations)
    }
    fun replaceActivities(activities: List<ActivityDraft>) {
        _draft.value = _draft.value.copy(activities = activities)
    }
    fun replaceMissedActivities(missedActivities: List<MissedActivityDraft>) {
        _draft.value = _draft.value.copy(missedActivities = missedActivities)
    }

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
    /** Update an existing migraine + update/insert/delete linked items. */
    fun updateFull(
        accessToken: String,
        migraineId: String,
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

                // Update migraine itself
                println("DEBUG updateFull: updating migraineId=$migraineId")
                db.updateMigraine(accessToken, migraineId, type, severity, migraineStart, endedAtIso, note, painLocations.takeIf { it.isNotEmpty() })

                // --- Triggers: delete removed, update existing, insert new ---
                val oldLinked = db.getLinkedItems(accessToken, migraineId)
                val keepTriggerIds = triggersSnapshot.mapNotNull { it.existingId }.toSet()
                oldLinked.triggers.filter { it.source != "system" && it.id !in keepTriggerIds }.forEach {
                    runCatching { db.deleteTrigger(accessToken, it.id) }
                }
                for (t in triggersSnapshot.filter { it.type.isNotBlank() }) {
                    if (t.existingId != null) {
                        runCatching { db.updateTrigger(accessToken, t.existingId, t.type, t.startAtIso ?: migraineStart, t.note) }
                    } else {
                        runCatching { db.insertTrigger(accessToken, migraineId, t.type, t.startAtIso ?: migraineStart, t.note) }
                    }
                }

                // --- Medicines ---
                val keepMedIds = meds.mapNotNull { it.existingId }.toSet()
                oldLinked.medicines.filter { it.source != "system" && it.id !in keepMedIds }.forEach {
                    runCatching { db.deleteMedicine(accessToken, it.id) }
                }
                for (m in meds.filter { !it.name.isNullOrBlank() }) {
                    if (m.existingId != null) {
                        runCatching { db.updateMedicine(accessToken, m.existingId, m.name, m.amount, m.startAtIso ?: migraineStart, m.notes) }
                    } else {
                        runCatching { db.insertMedicine(accessToken, migraineId, m.name, m.amount, m.startAtIso ?: migraineStart, m.notes, m.reliefScale) }
                    }
                }

                // --- Reliefs ---
                val keepRelIds = rels.mapNotNull { it.existingId }.toSet()
                oldLinked.reliefs.filter { it.source != "system" && it.id !in keepRelIds }.forEach {
                    runCatching { db.deleteRelief(accessToken, it.id) }
                }
                for (r in rels.filter { it.type.isNotBlank() }) {
                    val rStart = r.startAtIso ?: migraineStart
                    val rEnd = r.endAtIso ?: rStart
                    if (r.existingId != null) {
                        runCatching { db.updateRelief(accessToken, r.existingId, r.type, rStart, r.notes, endAt = rEnd) }
                    } else {
                        runCatching { db.insertRelief(accessToken, migraineId, r.type, rStart, r.notes, rEnd, r.reliefScale) }
                    }
                }

                // --- Prodromes ---
                val keepProdIds = prodromesSnapshot.mapNotNull { it.existingId }.toSet()
                oldLinked.prodromes.filter { it.source != "system" && it.id !in keepProdIds }.forEach {
                    runCatching { db.deleteProdromeLog(accessToken, it.id) }
                }
                for (p in prodromesSnapshot.filter { it.type.isNotBlank() }) {
                    if (p.existingId != null) {
                        runCatching { db.updateProdromeLog(accessToken, p.existingId, p.type, p.startAtIso ?: migraineStart, p.note) }
                    } else {
                        runCatching { db.insertProdrome(accessToken, migraineId, p.type, p.startAtIso ?: migraineStart, p.note) }
                    }
                }

                // --- Locations ---
                val keepLocIds = locationsSnapshot.mapNotNull { it.existingId }.toSet()
                oldLinked.locations.filter { it.source != "system" && it.id !in keepLocIds }.forEach {
                    runCatching { db.deleteLocationLog(accessToken, it.id) }
                }
                for (loc in locationsSnapshot.filter { it.type.isNotBlank() }) {
                    if (loc.existingId != null) {
                        runCatching { db.updateLocationLog(accessToken, loc.existingId, loc.type, loc.startAtIso ?: migraineStart, loc.note) }
                    } else {
                        runCatching { db.insertLocation(accessToken, migraineId, loc.type, loc.startAtIso ?: migraineStart, loc.note) }
                    }
                }

                // --- Activities ---
                val keepActIds = activitiesSnapshot.mapNotNull { it.existingId }.toSet()
                oldLinked.activities.filter { it.source != "system" && it.id !in keepActIds }.forEach {
                    runCatching { db.deleteActivityLog(accessToken, it.id) }
                }
                for (act in activitiesSnapshot.filter { it.type.isNotBlank() }) {
                    val aStart = act.startAtIso ?: migraineStart
                    val aEnd = act.endAtIso ?: aStart
                    if (act.existingId != null) {
                        runCatching { db.updateActivityLog(accessToken, act.existingId, act.type, aStart, aEnd, act.note) }
                    } else {
                        runCatching { db.insertActivity(accessToken, migraineId, act.type, aStart, aEnd, act.note) }
                    }
                }

                // --- Missed Activities ---
                for (ma in missedActivitiesSnapshot.filter { it.type.isNotBlank() }) {
                    if (ma.existingId != null) {
                        runCatching { db.updateMissedActivityLog(accessToken, ma.existingId, ma.type, ma.startAtIso ?: migraineStart, ma.note) }
                    } else {
                        runCatching { db.insertMissedActivity(accessToken, migraineId, ma.type, ma.startAtIso ?: migraineStart, ma.note) }
                    }
                }

                loadJournal(accessToken)

                // Recompute correlation stats in background (fire-and-forget)
                launch(Dispatchers.IO) {
                    try { edge.triggerCorrelationCompute(getApplication()) }
                    catch (_: Exception) { }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

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

        println("DEBUG addFull: triggersSnapshot.size=${triggersSnapshot.size}, types=${triggersSnapshot.map { it.type }}")
        println("DEBUG addFull: prodromesSnapshot.size=${prodromesSnapshot.size}, meds=${meds.size}, rels=${rels.size}")

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

                // Auto-detected triggers/prodromes are pre-selected in the UI and already
                // included in the draft snapshots below – no separate linking needed.

                println("DEBUG addFull: about to insert ${triggersSnapshot.size} triggers for migraineId=${migraine.id}")
                for (t in triggersSnapshot.filter { it.type.isNotBlank() }) {
                    try {
                        db.insertTrigger(
                            accessToken = accessToken,
                            migraineId = migraine.id,
                            type = t.type,
                            startAt = t.startAtIso ?: migraineStart,
                            notes = t.note
                        )
                    } catch (e: Exception) { println("DEBUG addFull: trigger FAILED ${t.type}: ${e.message}"); e.printStackTrace() }
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
                        val rStart = r.startAtIso ?: migraineStart
                        db.insertRelief(
                            accessToken = accessToken,
                            migraineId = migraine.id,
                            type = r.type,
                            startAt = rStart,
                            notes = r.notes,
                            endAt = r.endAtIso ?: rStart,
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
                        val aStart = act.startAtIso ?: migraineStart
                        db.insertActivity(
                            accessToken = accessToken,
                            migraineId = migraine.id,
                            type = act.type,
                            startAt = aStart,
                            endAt = act.endAtIso ?: aStart,
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

                // Recompute correlation stats in background (fire-and-forget)
                launch(Dispatchers.IO) {
                    try { edge.triggerCorrelationCompute(getApplication()) }
                    catch (_: Exception) { }
                }
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
            _journalLoading.value = true
            try {
                val migraineRows = db.getMigraines(accessToken)
                val migraines = migraineRows.map { row ->
                    val linked = try { db.getLinkedItems(accessToken, row.id) } catch (_: Exception) { SupabaseDbService.MigraineLinkedItems() }
                    JournalEvent.Migraine(row, linked)
                }
                val triggers = db.getAllTriggers(accessToken).map { JournalEvent.Trigger(it) }
                val medicines = db.getAllMedicines(accessToken).map { JournalEvent.Medicine(it) }
                val reliefs = db.getAllReliefs(accessToken).map { JournalEvent.Relief(it) }
                val prodromes = db.getAllProdromeLog(accessToken).map { JournalEvent.Prodrome(it) }
                val locations = db.getAllLocationLog(accessToken).map { JournalEvent.Location(it) }
                val activities = db.getAllActivityLog(accessToken).map { JournalEvent.Activity(it) }
                val missedActivities = db.getAllMissedActivityLog(accessToken).map { JournalEvent.MissedActivity(it) }

                // Load trigger definitions for label lookup
                val context = getApplication<android.app.Application>().applicationContext
                val defs = edge.getTriggerDefinitions(context)
                _triggerLabelMap.value = defs.associate { it.triggerType to it.label }

                val merged = (migraines + triggers + medicines + reliefs + prodromes + locations + activities + missedActivities).sortedByDescending { ev ->
                    when (ev) {
                        is JournalEvent.Migraine -> ev.row.startAt
                        is JournalEvent.Trigger -> ev.row.startAt
                        is JournalEvent.Medicine -> ev.row.startAt
                        is JournalEvent.Relief -> ev.row.startAt
                        is JournalEvent.Prodrome -> ev.row.startAt
                        is JournalEvent.Location -> ev.row.startAt
                        is JournalEvent.Activity -> ev.row.startAt
                        is JournalEvent.MissedActivity -> ev.row.startAt
                    }
                }
                _journal.value = merged
                println("DEBUG loadJournal: count=${merged.size}")
            } catch (e: Exception) {
                e.printStackTrace()
                _journal.value = emptyList()
            } finally {
                _journalLoading.value = false
            }
        }
    }

    // ---- removals ----
    fun removeMigraine(accessToken: String, id: String) {
        viewModelScope.launch {
            try {
                // Unlink ALL linked items first (set migraine_id = null)
                val linked = db.getLinkedItems(accessToken, id)
                linked.triggers.forEach { runCatching { db.updateTrigger(accessToken, it.id, clearMigraineId = true) } }
                linked.medicines.forEach { runCatching { db.updateMedicine(accessToken, it.id, clearMigraineId = true) } }
                linked.reliefs.forEach { runCatching { db.updateRelief(accessToken, it.id, clearMigraineId = true) } }
                linked.prodromes.forEach { runCatching { db.unlinkFromMigraine(accessToken, "prodromes", it.id) } }
                linked.activities.forEach { runCatching { db.unlinkFromMigraine(accessToken, "time_in_high_hr_zones_daily", it.id) } }
                linked.locations.forEach { runCatching { db.unlinkFromMigraine(accessToken, "locations", it.id) } }
                // Now delete the migraine itself
                db.deleteMigraine(accessToken, id)
                // Then delete manual linked items (system items survive via RLS + app filter)
                linked.triggers.filter { it.source != "system" }.forEach { runCatching { db.deleteTrigger(accessToken, it.id) } }
                linked.medicines.filter { it.source != "system" }.forEach { runCatching { db.deleteMedicine(accessToken, it.id) } }
                linked.reliefs.filter { it.source != "system" }.forEach { runCatching { db.deleteRelief(accessToken, it.id) } }
                linked.prodromes.filter { it.source != "system" }.forEach { runCatching { db.deleteProdromeLog(accessToken, it.id) } }
                linked.activities.filter { it.source != "system" }.forEach { runCatching { db.deleteActivityLog(accessToken, it.id) } }
                linked.locations.filter { it.source != "system" }.forEach { runCatching { db.deleteLocationLog(accessToken, it.id) } }
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

    fun removeProdrome(accessToken: String, id: String) {
        viewModelScope.launch {
            try {
                db.deleteProdromeLog(accessToken, id)
                loadJournal(accessToken)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun removeLocation(accessToken: String, id: String) {
        viewModelScope.launch {
            try {
                db.deleteLocationLog(accessToken, id)
                loadJournal(accessToken)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun removeActivity(accessToken: String, id: String) {
        viewModelScope.launch {
            try {
                db.deleteActivityLog(accessToken, id)
                loadJournal(accessToken)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun removeMissedActivity(accessToken: String, id: String) {
        viewModelScope.launch {
            try {
                db.deleteMissedActivityLog(accessToken, id)
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

                // Show all triggers in the logging wizard (including NONE prediction)
                // so system-detected anomalies (e.g. HRV 2SD) are surfaced for the user
                val visiblePool = pool

                val frequent = prefs
                    .filter { it.status == "frequent" }
                    .sortedBy { it.position }
                    .mapNotNull { it.trigger?.label?.trim() }
                    .filter { it.isNotEmpty() }

                val all = visiblePool.mapNotNull { it.label.trim() }.filter { it.isNotEmpty() }
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





