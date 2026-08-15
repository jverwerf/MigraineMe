package com.migraineme

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    val sideEffectScale: String? = "NONE",
    val sideEffectNotes: String? = null,
    val existingId: String? = null
)

data class ReliefDraft(
    val type: String,
    val notes: String? = null,
    val startAtIso: String? = null,
    val endAtIso: String? = null,
    /** Optional duration entry. Resolved at save as end_at = start_at + minutes
     *  (start may be "same as migraine start", unknown until save). Only used
     *  when no explicit endAtIso was picked. */
    val durationMinutes: Int? = null,
    val reliefScale: String? = "NONE",
    val sideEffectScale: String? = "NONE",
    val sideEffectNotes: String? = null,
    val existingId: String? = null
)

/** end_at = start_at + minutes, for the reliefs duration entry. */
fun addMinutesToIso(startIso: String, minutes: Int): String? = try {
    java.time.OffsetDateTime.parse(startIso).plusMinutes(minutes.toLong()).toInstant().toString()
} catch (_: Exception) {
    try { Instant.parse(startIso).plus(minutes.toLong(), ChronoUnit.MINUTES).toString() }
    catch (_: Exception) { null }
}

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

/**
 * One timestamped pain moment within the attack. startAtIso == null means
 * "at attack start" (the first entry's default); added entries carry an
 * explicit time so the user can capture the pain peaking or moving.
 */
data class PainEntryDraft(
    val id: String = java.util.UUID.randomUUID().toString(),
    val startAtIso: String? = null,
    val severity: Int = 5,
    val locations: List<String> = emptyList()
)

/**
 * One aura moment: the zones the aura covered, and when. Mirrors
 * PainEntryDraft — an aura that starts peripheral and migrates central is the
 * progression ICHD-3 asks about, and a flat zone list cannot express it.
 */
data class AuraEntryDraft(
    val id: String = java.util.UUID.randomUUID().toString(),
    val startAtIso: String? = null,
    val zones: List<String> = emptyList(),
    /** How long this stage lasted. The attack's overall aura duration is the
     *  SUM of the stages that carry one (see setAuraEntries). */
    val durationMinutes: Int? = null,
)

data class Draft(
    val editMigraineId: String? = null,
    val migraine: MigraineDraft? = null,
    val painEntries: List<PainEntryDraft> = listOf(PainEntryDraft()),
    /** Per-symptom intensity, label -> MILD/MODERATE/SEVERE. Only for
     *  non-pain_character symptoms (a migraine type or pain quality has no
     *  separate intensity — that's migraine.severity). */
    val symptomSeverities: Map<String, String> = emptyMap(),
    /** Optional per-symptom timing, label -> ISO start. Absent means
     *  "at attack start" — never defaulted to now(), so an untimed symptom
     *  contributes no invented timeline. */
    val symptomTimes: Map<String, String> = emptyMap(),
    val auraLocations: List<String> = emptyList(),
    val auraDurationMinutes: Int? = null,
    /** Timestamped aura entries. auraLocations stays the union so every
     *  existing consumer keeps working. */
    val auraEntries: List<AuraEntryDraft> = emptyList(),
    val triggers: List<TriggerDraft> = emptyList(),
    val meds: List<MedicineDraft> = emptyList(),
    val rels: List<ReliefDraft> = emptyList(),
    val prodromes: List<ProdromeDraft> = emptyList(),
    val locations: List<LocationDraft> = emptyList(),
    val activities: List<ActivityDraft> = emptyList(),
    val missedActivities: List<MissedActivityDraft> = emptyList()
) {
    /** Mirror written to migraines.pain_locations: union across entries. */
    val painLocationsUnion: List<String>
        get() = painEntries.flatMap { it.locations }.distinct()

    /** Mirror written to migraines.severity: max across entries that actually
     *  logged pain. Entries without locations don't count — otherwise the
     *  untouched default entry (severity 5) would override flows that save
     *  severity as null on purpose (quick log). */
    val maxPainSeverity: Int?
        get() = painEntries.filter { it.locations.isNotEmpty() }.maxOfOrNull { it.severity }
}

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

/** The row id behind a feed entry — how a deleted entry is found in the list. */
fun journalEventId(ev: JournalEvent): String = when (ev) {
    is JournalEvent.Migraine -> ev.row.id
    is JournalEvent.Trigger -> ev.row.id
    is JournalEvent.Medicine -> ev.row.id
    is JournalEvent.Relief -> ev.row.id
    is JournalEvent.Prodrome -> ev.row.id
    is JournalEvent.Location -> ev.row.id
    is JournalEvent.Activity -> ev.row.id
    is JournalEvent.MissedActivity -> ev.row.id
}

/** The timestamp the feed is ordered and paged by. */
fun journalEventStartAt(ev: JournalEvent): String? = when (ev) {
    is JournalEvent.Migraine -> ev.row.startAt
    is JournalEvent.Trigger -> ev.row.startAt
    is JournalEvent.Medicine -> ev.row.startAt
    is JournalEvent.Relief -> ev.row.startAt
    is JournalEvent.Prodrome -> ev.row.startAt
    is JournalEvent.Location -> ev.row.startAt
    is JournalEvent.Activity -> ev.row.startAt
    is JournalEvent.MissedActivity -> ev.row.startAt
}

/** Which table an entry came from — the type filter is really a table choice,
 *  so it decides what a page even has to read. */
enum class JournalTable { MIGRAINE, TRIGGER, MEDICINE, RELIEF, PRODROME, LOCATION, ACTIVITY, MISSED }

/**
 * The Journal's filter selection, as the feed loader needs it.
 *
 * The screen still owns the controls and still applies every filter to what it
 * draws — this is the same selection expressed so the query can be narrowed
 * before rows are fetched. Type picks the tables, the timeframe becomes the
 * window bounds; source and "needs attention" are read off row fields and so
 * are settled in memory.
 */
data class JournalFilter(
    val type: String = "All",
    val source: String = "All",
    val sinceIso: String? = null,
    val untilIso: String? = null
) {
    fun tables(): Set<JournalTable> = when (type) {
        "Migraines" -> setOf(JournalTable.MIGRAINE)
        "Triggers" -> setOf(JournalTable.TRIGGER)
        "Prodromes" -> setOf(JournalTable.PRODROME)
        "Medicines" -> setOf(JournalTable.MEDICINE)
        "Reliefs" -> setOf(JournalTable.RELIEF)
        "Activities" -> setOf(JournalTable.ACTIVITY)
        "Locations" -> setOf(JournalTable.LOCATION)
        "Missed Activities" -> setOf(JournalTable.MISSED)
        else -> JournalTable.entries.toSet()
    }

    /** True when the screen would draw a card for this entry. */
    fun accepts(ev: JournalEvent): Boolean {
        if (type == "Needs attention" && !journalNeedsAttention(ev)) return false
        return when (source) {
            "Manual" -> journalEventSource(ev) == "manual"
            "Auto" -> journalEventSource(ev) != "manual"
            else -> true
        }
    }
}

class LogViewModel(application: Application) : AndroidViewModel(application) {

    private val db = SupabaseDbService(
        BuildConfig.SUPABASE_URL,
        BuildConfig.SUPABASE_ANON_KEY
    )

    private val edge = EdgeFunctionsService()

    private val _draft = MutableStateFlow(Draft())
    val draft: StateFlow<Draft> = _draft

    /** Symptoms that already had an intensity/time stored when this log was
     *  opened for edit. Used so clearing a rating actually writes NULL. */
    private var loadedSymptomKeys: Set<String> = emptySet()

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

                // Pain entries: prefer timestamped child rows (grouped by
                // start_at); legacy attacks fall back to the mirror columns.
                val painEntries = if (linked.painPoints.isNotEmpty()) {
                    linked.painPoints.groupBy { it.startAt }.toSortedMap().map { (startAt, rows) ->
                        PainEntryDraft(
                            startAtIso = startAt,
                            severity = rows.mapNotNull { it.severity }.maxOrNull() ?: row.severity ?: 5,
                            locations = rows.map { it.locationId }
                        )
                    }
                } else {
                    listOf(PainEntryDraft(
                        startAtIso = null,
                        severity = row.severity ?: 5,
                        locations = row.painLocations ?: emptyList()
                    ))
                }

                // Symptom intensities live on the trigger-derived symptoms rows.
                val severities = linked.postdromes
                    .mapNotNull { r -> r.type?.let { t -> r.severity?.let { s -> t to s } } }
                    .toMap()
                val symptomTimes = linked.postdromes
                    .mapNotNull { r -> r.type?.let { t -> r.startAt?.let { s -> t to s } } }
                    .toMap()
                // Remember what was stored so save() can tell "never rated"
                // apart from "rated then cleared" — only the latter needs a
                // PATCH to null it out.
                loadedSymptomKeys = severities.keys + symptomTimes.keys

                _draft.value = Draft(
                    editMigraineId = migraineId,
                    symptomSeverities = severities,
                    symptomTimes = symptomTimes,
                    migraine = MigraineDraft(
                        type = row.type,
                        symptoms = symptomLabels,
                        severity = row.severity,
                        beganAtIso = row.startAt,
                        endedAtIso = row.endAt,
                        note = row.notes
                    ),
                    painEntries = painEntries,
                    auraLocations = row.auraLocations ?: emptyList(),
                    auraEntries = runCatching { db.getAuraZones(accessToken, migraineId) }
                        .getOrDefault(emptyList())
                        .groupBy { it.startAt }
                        .toSortedMap(compareBy { it ?: "" })
                        .map { (at, rows) -> AuraEntryDraft(startAtIso = at, zones = rows.map { it.zone }, durationMinutes = rows.firstNotNullOfOrNull { it.durationMinutes }) },
                    auraDurationMinutes = row.auraDurationMinutes,
                    triggers = linked.triggers
                        .filter { it.source != "system" }
                        .map { TriggerDraft(it.type ?: "", it.startAt, it.notes, existingId = it.id) },
                    // Named args on purpose: positionally, the 5th slot is
                    // reliefScale and sideEffectScale/-Notes fell to their
                    // "NONE"/null defaults, so a no-op edit wiped a recorded
                    // side effect on every medicine and relief.
                    meds = linked.medicines.map {
                        MedicineDraft(
                            name = it.name, amount = it.amount, notes = it.notes,
                            startAtIso = it.startAt, reliefScale = it.reliefScale,
                            sideEffectScale = it.sideEffectScale,
                            sideEffectNotes = it.sideEffectNotes, existingId = it.id
                        )
                    },
                    rels = linked.reliefs.map {
                        ReliefDraft(
                            type = it.type ?: "", notes = it.notes,
                            startAtIso = it.startAt, endAtIso = it.endAt,
                            reliefScale = it.reliefScale,
                            sideEffectScale = it.sideEffectScale,
                            sideEffectNotes = it.sideEffectNotes, existingId = it.id
                        )
                    },
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

    /** True while an older page is being appended — the first page is already on
     *  screen, so this must never raise the full-screen spinner. */
    private val _journalLoadingMore = MutableStateFlow(false)
    val journalLoadingMore: StateFlow<Boolean> = _journalLoadingMore

    /** False once a page comes back short, i.e. the history is fully loaded. */
    private val _journalHasMore = MutableStateFlow(false)
    val journalHasMore: StateFlow<Boolean> = _journalHasMore

    /** Where the next page starts: the start_at of the oldest loaded entry. */
    private var journalCursor: String? = null
    private var journalFilter = JournalFilter()
    private var journalPageJob: Job? = null

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
    /** One-unit system: pool label → dose_unit ('mg' default). */
    private val _medicineUnitsByLabel = MutableStateFlow<Map<String, String>>(emptyMap())
    val medicineUnitsByLabel: StateFlow<Map<String, String>> = _medicineUnitsByLabel

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
        // Severity set outside PainLocationScreen (AI parser, sliders) must
        // also seed the first pain entry — the saved severity is the MAX
        // across entries, so a stale default entry would clobber it.
        val entries = if (severity != null) {
            val cur = _draft.value.painEntries.ifEmpty { listOf(PainEntryDraft()) }
            listOf(cur.first().copy(severity = severity.coerceIn(1, 10))) + cur.drop(1)
        } else _draft.value.painEntries
        _draft.value = _draft.value.copy(
            migraine = existing.copy(
                type = type ?: existing.type,
                symptoms = symptoms ?: existing.symptoms,
                severity = severity ?: existing.severity,
                beganAtIso = if (clearBeganAt) null else (beganAtIso ?: existing.beganAtIso),
                endedAtIso = if (clearEndedAt) null else (endedAtIso ?: existing.endedAtIso),
                note = if (clearNote) null else (note ?: existing.note)
            ),
            painEntries = entries
        )
    }

    fun setSymptomsDraft(symptoms: List<String>) {
        val existing = _draft.value.migraine ?: MigraineDraft()
        _draft.value = _draft.value.copy(
            migraine = existing.copy(symptoms = symptoms),
            // Drop intensities/times for symptoms that are no longer selected.
            symptomSeverities = _draft.value.symptomSeverities.filterKeys { it in symptoms },
            symptomTimes = _draft.value.symptomTimes.filterKeys { it in symptoms }
        )
    }

    /** null clears it back to "not rated". */
    fun setSymptomSeverity(label: String, severity: String?) {
        val next = _draft.value.symptomSeverities.toMutableMap()
        if (severity == null) next.remove(label) else next[label] = severity
        _draft.value = _draft.value.copy(symptomSeverities = next)
    }

    /** null clears it back to "at attack start". */
    fun setSymptomTime(label: String, startAtIso: String?) {
        val next = _draft.value.symptomTimes.toMutableMap()
        if (startAtIso == null) next.remove(label) else next[label] = startAtIso
        _draft.value = _draft.value.copy(symptomTimes = next)
    }

    /** AI parser / batch apply: fills the first pain entry (parser output has
     *  no per-point times yet — items land at attack start). */
    fun setPainLocationsDraft(locations: List<String>) {
        val cur = _draft.value.painEntries.ifEmpty { listOf(PainEntryDraft()) }
        _draft.value = _draft.value.copy(
            painEntries = listOf(cur.first().copy(locations = locations)) + cur.drop(1)
        )
    }

    // ── Timestamped pain entries ──

    fun addPainEntry() {
        val cur = _draft.value.painEntries
        val entry = PainEntryDraft(
            startAtIso = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            severity = cur.lastOrNull()?.severity ?: 5
        )
        _draft.value = _draft.value.copy(painEntries = cur + entry)
    }

    fun removePainEntry(entryId: String) {
        val cur = _draft.value.painEntries
        if (cur.size <= 1) return
        val next = cur.filterNot { it.id == entryId }
        _draft.value = _draft.value.copy(painEntries = next)
        syncSeverityFromEntries()
    }

    fun setPainEntryLocations(entryId: String, locations: List<String>) {
        updatePainEntry(entryId) { it.copy(locations = locations) }
    }

    fun setPainEntrySeverity(entryId: String, severity: Int) {
        updatePainEntry(entryId) { it.copy(severity = severity.coerceIn(1, 10)) }
        syncSeverityFromEntries()
    }

    fun setPainEntryTime(entryId: String, startAtIso: String?) {
        updatePainEntry(entryId) { it.copy(startAtIso = startAtIso) }
    }

    fun replacePainEntries(entries: List<PainEntryDraft>) {
        _draft.value = _draft.value.copy(painEntries = entries.ifEmpty { listOf(PainEntryDraft()) })
        syncSeverityFromEntries()
    }

    private fun updatePainEntry(entryId: String, transform: (PainEntryDraft) -> PainEntryDraft) {
        _draft.value = _draft.value.copy(
            painEntries = _draft.value.painEntries.map { if (it.id == entryId) transform(it) else it }
        )
    }

    private fun syncSeverityFromEntries() {
        // Overall max (not the location-gated mirror) so the review screen
        // shows what the sliders say even before any dot is tapped.
        val existing = _draft.value.migraine ?: MigraineDraft()
        val overallMax = _draft.value.painEntries.maxOfOrNull { it.severity }
        _draft.value = _draft.value.copy(
            migraine = existing.copy(severity = overallMax ?: existing.severity)
        )
    }

    fun setAuraDraft(locations: List<String>, durationMinutes: Int?) {
        _draft.value = _draft.value.copy(auraLocations = locations, auraDurationMinutes = durationMinutes)
    }

    /** Timestamped aura entries. auraLocations is kept as the union so the
     *  mirror column every other consumer reads stays correct. Overall
     *  duration aggregates: when stages carry their own durations, the
     *  attack's aura_duration_minutes is their SUM (Jordy 2026-08-08);
     *  durationMinutes is only the fallback when no stage has one. */
    fun setAuraEntries(entries: List<AuraEntryDraft>, durationMinutes: Int?) {
        val stageSum = entries.mapNotNull { it.durationMinutes }
            .takeIf { it.isNotEmpty() }?.sum()
        _draft.value = _draft.value.copy(
            auraEntries = entries,
            auraLocations = entries.flatMap { it.zones }.distinct(),
            auraDurationMinutes = stageSum ?: durationMinutes,
        )
    }

    fun addTriggerDraft(trigger: String, startAtIso: String? = null, note: String? = null) {
        _draft.value = _draft.value.copy(
            triggers = _draft.value.triggers + TriggerDraft(trigger, startAtIso, note)
        )
    }

    fun addMedicineDraft(name: String, amount: String?, notes: String?, startAtIso: String? = null, reliefScale: String? = "NONE", sideEffectScale: String? = "NONE", sideEffectNotes: String? = null) {
        _draft.value = _draft.value.copy(
            meds = _draft.value.meds + MedicineDraft(name, amount, notes, startAtIso, reliefScale, sideEffectScale, sideEffectNotes)
        )
    }

    fun addReliefDraft(type: String, notes: String? = null, startAtIso: String? = null, endAtIso: String? = null, reliefScale: String? = "NONE", sideEffectScale: String? = "NONE", sideEffectNotes: String? = null) {
        _draft.value = _draft.value.copy(
            rels = _draft.value.rels + ReliefDraft(type = type, notes = notes, startAtIso = startAtIso, endAtIso = endAtIso, reliefScale = reliefScale, sideEffectScale = sideEffectScale, sideEffectNotes = sideEffectNotes)
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

    fun clearDraft() {
        _draft.value = Draft()
        _editMigraineId.value = null
        loadedSymptomKeys = emptySet()
        autoSelectedFor.clear()
        autoAdded.clear()
    }

    // ── Auto-select bookkeeping ──
    //
    // Wizard steps pre-select recent entries for the migraine's start date. This
    // has to be remembered next to the draft, NOT in the step's `remember`
    // state: navigating away from a step drops its composition, and on the way
    // back the step would no longer recognise its own earlier picks. That is how
    // suggestions for a date the user has since changed stay on the log.
    //
    // Keyed by step ("triggers", "prodromes", "activities", "missed").

    private val autoSelectedFor = mutableMapOf<String, String>()
    private val autoAdded = mutableMapOf<String, Set<String>>()

    /** Reference date this step last auto-selected for, or null if it hasn't. */
    fun autoSelectedRefDate(step: String): String? = autoSelectedFor[step]

    /** Entry types this step auto-selected for [autoSelectedRefDate]. */
    fun autoSelectedTypes(step: String): Set<String> = autoAdded[step] ?: emptySet()

    fun recordAutoSelect(step: String, refDate: String, added: Set<String>) {
        autoSelectedFor[step] = refDate
        autoAdded[step] = added
    }

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
                launch(Dispatchers.IO) {
                    try { edge.triggerCorrelationCompute(getApplication()) }
                    catch (e: Exception) { e.printStackTrace() }
                }
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
                val row = db.insertRelief(accessToken, migraineId = null, type = type, startAt = nowIso, notes = notes)
                DeviceReliefOutcomeWorker.scheduleIfDevice(getApplication<Application>().applicationContext, row.id, type)
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
        meds: List<MedicineDraft>,
        rels: List<ReliefDraft>
    ) {
        val triggersSnapshot = _draft.value.triggers
        val prodromesSnapshot = _draft.value.prodromes
        val locationsSnapshot = _draft.value.locations
        val activitiesSnapshot = _draft.value.activities
        val missedActivitiesSnapshot = _draft.value.missedActivities
        val auraLocationsSnapshot = _draft.value.auraLocations
        val auraDurationSnapshot = _draft.value.auraDurationMinutes
        val painEntriesSnapshot = _draft.value.painEntries
        val auraEntriesSnapshot = _draft.value.auraEntries
        val symptomSeveritiesSnapshot = _draft.value.symptomSeverities
        val symptomTimesSnapshot = _draft.value.symptomTimes
        val symptomKeysToWrite =
            symptomSeveritiesSnapshot.keys + symptomTimesSnapshot.keys + loadedSymptomKeys
        // Mirrors: severity = MAX across pain entries, pain_locations = UNION.
        val painUnion = _draft.value.painLocationsUnion
        val mirroredSeverity = listOfNotNull(_draft.value.maxPainSeverity, severity).maxOrNull()

        viewModelScope.launch {
            try {
                val migraineStart = beganAtIso.ifBlank {
                    DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                }

                // Update migraine itself
                println("DEBUG updateFull: updating migraineId=$migraineId")
                db.updateMigraine(
                    accessToken, migraineId, type, mirroredSeverity, migraineStart, endedAtIso, note,
                    painUnion.takeIf { it.isNotEmpty() },
                    clearPainLocations = painUnion.isEmpty(),
                    setAura = true,
                    auraLocations = auraLocationsSnapshot,
                    auraDurationMinutes = auraDurationSnapshot
                )

                // Timestamped pain points: wholesale replace. Entry time nil =
                // attack start.
                runCatching {
                    db.replacePainPoints(accessToken, migraineId, painEntriesSnapshot.flatMap { entry ->
                        entry.locations.map { loc ->
                            SupabaseDbService.PainPointInsert(
                                migraineId = migraineId,
                                locationId = loc,
                                severity = entry.severity,
                                startAt = entry.startAtIso ?: migraineStart
                            )
                        }
                    })
                }.onFailure { println("DEBUG updateFull: pain points FAILED: ${it.message}") }

                // Timestamped aura zones, same rule: an entry with no time
                // falls back to the attack start rather than inventing one.
                runCatching {
                    db.replaceAuraZones(accessToken, migraineId, auraEntriesSnapshot.flatMap { entry ->
                        entry.zones.map { zone ->
                            SupabaseDbService.AuraZoneInsert(
                                migraineId = migraineId,
                                zone = zone,
                                startAt = entry.startAtIso ?: migraineStart,
                                durationMinutes = entry.durationMinutes
                            )
                        }
                    })
                }.onFailure { println("DEBUG updateFull: aura zones FAILED: ${it.message}") }

                // Symptom severity. The `symptoms` rows are created by the DB
                // sync trigger off `migraines.type` above, so this runs after
                // and only updates them.
                // Skip untouched symptoms so the update doesn't null out a
                // value the user never opened the sheet for.
                for (label in symptomKeysToWrite) {
                    runCatching {
                        db.setSymptomDetail(accessToken, migraineId, label,
                            symptomSeveritiesSnapshot[label], symptomTimesSnapshot[label])
                    }.onFailure { println("DEBUG updateFull: symptom detail '$label' FAILED: ${it.message}") }
                }

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
                        runCatching { db.updateMedicine(accessToken, m.existingId, m.name, m.amount, m.startAtIso ?: migraineStart, m.notes, reliefScale = m.reliefScale, sideEffectScale = m.sideEffectScale, sideEffectNotes = m.sideEffectNotes) }
                    } else {
                        runCatching { db.insertMedicine(accessToken, migraineId, m.name, m.amount, m.startAtIso ?: migraineStart, m.notes, reliefScale = m.reliefScale, sideEffectScale = m.sideEffectScale, sideEffectNotes = m.sideEffectNotes) }
                    }
                }

                // --- Reliefs ---
                val keepRelIds = rels.mapNotNull { it.existingId }.toSet()
                oldLinked.reliefs.filter { it.source != "system" && it.id !in keepRelIds }.forEach {
                    runCatching { db.deleteRelief(accessToken, it.id) }
                }
                for (r in rels.filter { it.type.isNotBlank() }) {
                    val rStart = r.startAtIso ?: migraineStart
                    // end_at only when known: explicit end, or start + minutes.
                    // Skipped duration = end_at stays NULL.
                    val rEnd = r.endAtIso ?: r.durationMinutes?.let { addMinutesToIso(rStart, it) }
                    if (r.existingId != null) {
                        runCatching { db.updateRelief(accessToken, r.existingId, r.type, rStart, r.notes, endAt = rEnd, reliefScale = r.reliefScale, sideEffectScale = r.sideEffectScale, sideEffectNotes = r.sideEffectNotes) }
                    } else {
                        runCatching {
                            val row = db.insertRelief(accessToken, migraineId, r.type, rStart, r.notes, rEnd, r.reliefScale, sideEffectScale = r.sideEffectScale, sideEffectNotes = r.sideEffectNotes)
                            if (r.reliefScale == null || r.reliefScale == "NONE") {
                                DeviceReliefOutcomeWorker.scheduleIfDevice(getApplication<Application>().applicationContext, row.id, r.type)
                            }
                        }
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
        meds: List<MedicineDraft>,
        rels: List<ReliefDraft>
    ) {
        val triggersSnapshot = _draft.value.triggers
        val prodromesSnapshot = _draft.value.prodromes
        val locationsSnapshot = _draft.value.locations
        val activitiesSnapshot = _draft.value.activities
        val missedActivitiesSnapshot = _draft.value.missedActivities
        val auraLocationsSnapshot = _draft.value.auraLocations
        val auraDurationSnapshot = _draft.value.auraDurationMinutes
        val painEntriesSnapshot = _draft.value.painEntries
        val auraEntriesSnapshot = _draft.value.auraEntries
        val symptomSeveritiesSnapshot = _draft.value.symptomSeverities
        val symptomTimesSnapshot = _draft.value.symptomTimes
        val symptomKeysToWrite =
            symptomSeveritiesSnapshot.keys + symptomTimesSnapshot.keys + loadedSymptomKeys
        // Mirrors: severity = MAX across pain entries, pain_locations = UNION.
        val painUnion = _draft.value.painLocationsUnion
        val mirroredSeverity = listOfNotNull(_draft.value.maxPainSeverity, severity).maxOrNull()

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
                    severity = mirroredSeverity,
                    startAt = migraineStart,
                    endAt = endedAtIso,
                    notes = note,
                    painLocations = painUnion.takeIf { it.isNotEmpty() },
                    auraLocations = auraLocationsSnapshot.takeIf { it.isNotEmpty() },
                    auraDurationMinutes = auraDurationSnapshot
                )

                // Timestamped pain points (wholesale insert for the new attack)
                runCatching {
                    db.replacePainPoints(accessToken, migraine.id, painEntriesSnapshot.flatMap { entry ->
                        entry.locations.map { loc ->
                            SupabaseDbService.PainPointInsert(
                                migraineId = migraine.id,
                                locationId = loc,
                                severity = entry.severity,
                                startAt = entry.startAtIso ?: migraineStart
                            )
                        }
                    })
                }.onFailure { println("DEBUG addFull: pain points FAILED: ${it.message}") }

                // Timestamped aura zones, same rule: an entry with no time
                // falls back to the attack start rather than inventing one.
                runCatching {
                    db.replaceAuraZones(accessToken, migraine.id, auraEntriesSnapshot.flatMap { entry ->
                        entry.zones.map { zone ->
                            SupabaseDbService.AuraZoneInsert(
                                migraineId = migraine.id,
                                zone = zone,
                                startAt = entry.startAtIso ?: migraineStart,
                                durationMinutes = entry.durationMinutes
                            )
                        }
                    })
                }.onFailure { println("DEBUG addFull: aura zones FAILED: ${it.message}") }

                // Symptom severity — updates the rows the sync trigger just
                // created off `migraines.type`.
                for (label in symptomKeysToWrite) {
                    runCatching {
                        db.setSymptomDetail(accessToken, migraine.id, label,
                            symptomSeveritiesSnapshot[label], symptomTimesSnapshot[label])
                    }.onFailure { println("DEBUG addFull: symptom detail '$label' FAILED: ${it.message}") }
                }

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
                            reliefScale = m.reliefScale,
                            sideEffectScale = m.sideEffectScale,
                            sideEffectNotes = m.sideEffectNotes
                        )
                    } catch (e: Exception) { e.printStackTrace() }
                }

                for (r in rels.filter { it.type.isNotBlank() }) {
                    try {
                        val rStart = r.startAtIso ?: migraineStart
                        val row = db.insertRelief(
                            accessToken = accessToken,
                            migraineId = migraine.id,
                            type = r.type,
                            startAt = rStart,
                            notes = r.notes,
                            // end_at only when known; skipped = NULL
                            endAt = r.endAtIso ?: r.durationMinutes?.let { addMinutesToIso(rStart, it) },
                            reliefScale = r.reliefScale,
                            sideEffectScale = r.sideEffectScale,
                            sideEffectNotes = r.sideEffectNotes
                        )
                        if (r.reliefScale == null || r.reliefScale == "NONE") {
                            DeviceReliefOutcomeWorker.scheduleIfDevice(getApplication<Application>().applicationContext, row.id, r.type)
                        }
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
    //
    // The feed is a merge of eight tables newest-first. It used to be read whole
    // — every row of every table, plus one linked-items fan-out per attack —
    // and then handed to the screen, which composed a Card for every entry. On
    // an account with a thousand-odd entries that is two full copies of the
    // history live at once whenever it reloads, and on a 192 MB heap deleting a
    // single entry was enough to run out of memory. Now it is read a page at a
    // time against a time cursor, and a delete edits the list in place.

    /** Entries per page — a couple of screens of cards, so the list arrives
     *  already scrollable and the next page is fetched before the user reaches
     *  the bottom. Sized against the feed's own shape: a busy month is roughly
     *  this many entries, so the first page is usually the whole recent view. */
    private val journalPageSize = 60

    /**
     * Applies a filter selection. A change resets paging and reloads from the
     * newest entry, since the filters decide both which tables are worth
     * reading and how far back the window goes.
     */
    fun applyJournalFilter(accessToken: String, filter: JournalFilter) {
        if (filter == journalFilter && _journal.value.isNotEmpty()) return
        journalFilter = filter
        loadJournal(accessToken)
    }

    /** Reloads the feed from the newest entry under the current filter. */
    fun loadJournal(accessToken: String) {
        journalPageJob?.cancel()
        journalPageJob = viewModelScope.launch {
            _journalLoading.value = true
            journalCursor = journalFilter.untilIso
            try {
                // Trigger labels are needed before the first card renders.
                val context = getApplication<android.app.Application>().applicationContext
                val defs = edge.getTriggerDefinitions(context)
                _triggerLabelMap.value = defs.associate { it.triggerType to it.label }

                val page = fetchJournalPage(accessToken)
                _journal.value = page
                println("DEBUG loadJournal: first page=${page.size} hasMore=${_journalHasMore.value}")
            } catch (e: Exception) {
                e.printStackTrace()
                _journal.value = emptyList()
                _journalHasMore.value = false
            } finally {
                _journalLoading.value = false
            }
        }
    }

    /** Appends the next older page. No-op while one is already in flight or the
     *  history is exhausted, so scroll events can fire it freely. */
    fun loadMoreJournal(accessToken: String) {
        if (!_journalHasMore.value) return
        if (_journalLoading.value || _journalLoadingMore.value) return
        journalPageJob = viewModelScope.launch {
            _journalLoadingMore.value = true
            try {
                val page = fetchJournalPage(accessToken)
                if (page.isNotEmpty()) _journal.value = _journal.value + page
                println("DEBUG loadMoreJournal: +${page.size} total=${_journal.value.size}")
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _journalLoadingMore.value = false
            }
        }
    }

    /**
     * Reads one page of the merged feed, advancing [journalCursor].
     *
     * The filters that narrow the query — which tables, and how far back — are
     * pushed down to PostgREST. The two that cannot be (source, and the
     * "needs attention" test, both of which the screen evaluates from row
     * fields) are applied here as well, and the walk keeps pulling windows
     * until it has a full page of entries the screen will actually draw.
     * Rows that fail are dropped rather than kept, so memory stays bounded by
     * what is on screen instead of by how much history the account has.
     */
    private suspend fun fetchJournalPage(accessToken: String): List<JournalEvent> {
        val kept = mutableListOf<JournalEvent>()
        val tables = journalFilter.tables()

        while (kept.size < journalPageSize) {
            val window = SupabaseDbService.JournalWindow(
                beforeIso = journalCursor,
                sinceIso = journalFilter.sinceIso,
                limit = journalPageSize
            )

            val flat = coroutineScope {
                buildList {
                    if (JournalTable.TRIGGER in tables) add(async { db.getAllTriggers(accessToken, window).map { JournalEvent.Trigger(it) } })
                    if (JournalTable.MEDICINE in tables) add(async { db.getAllMedicines(accessToken, window).map { JournalEvent.Medicine(it) } })
                    if (JournalTable.RELIEF in tables) add(async { db.getAllReliefs(accessToken, window).map { JournalEvent.Relief(it) } })
                    if (JournalTable.PRODROME in tables) add(async { db.getAllProdromeLog(accessToken, window).map { JournalEvent.Prodrome(it) } })
                    if (JournalTable.LOCATION in tables) add(async { db.getAllLocationLog(accessToken, window).map { JournalEvent.Location(it) } })
                    if (JournalTable.ACTIVITY in tables) add(async { db.getAllActivityLog(accessToken, window).map { JournalEvent.Activity(it) } })
                    if (JournalTable.MISSED in tables) add(async { db.getAllMissedActivityLog(accessToken, window).map { JournalEvent.MissedActivity(it) } })
                }.awaitAll().flatten()
            }
            val migraineRows =
                if (JournalTable.MIGRAINE in tables) db.getMigraines(accessToken, window) else emptyList()

            val raw = (migraineRows.map { JournalEvent.Migraine(it, SupabaseDbService.MigraineLinkedItems()) } + flat)
                .sortedByDescending { journalEventStartAt(it) ?: "" }

            if (raw.isEmpty()) {
                _journalHasMore.value = false
                break
            }

            // Only the newest page-worth is complete: below that timestamp a
            // table's own window may have cut rows off, so they are left for
            // the next walk rather than shown out of order.
            val complete = if (raw.size > journalPageSize) raw.take(journalPageSize) else raw
            val nextCursor = journalEventStartAt(complete.last())
            val exhausted = raw.size < journalPageSize || nextCursor == null || nextCursor == journalCursor
            journalCursor = nextCursor
            _journalHasMore.value = !exhausted

            kept += complete.filter { journalFilter.accepts(it) }

            if (exhausted) break
        }

        // The linked items are only needed for the attacks actually on screen —
        // this was the fan-out that made a full reload so expensive.
        val withLinked = coroutineScope {
            kept.chunked(5).flatMap { chunk ->
                chunk.map { ev ->
                    async {
                        if (ev is JournalEvent.Migraine) {
                            val linked = try { db.getLinkedItems(accessToken, ev.row.id) } catch (_: Exception) { SupabaseDbService.MigraineLinkedItems() }
                            JournalEvent.Migraine(ev.row, linked)
                        } else ev
                    }
                }.awaitAll()
            }
        }
        return withLinked
    }

    /**
     * Drops entries from the loaded feed by row id.
     *
     * A delete used to call loadJournal, which re-read every table and every
     * attack's linked items to render one fewer card — the second copy of the
     * feed being built while the first was still held is what tipped a heavy
     * account over its heap. The rows are already known here, so the list is
     * edited in place and the server is not asked again.
     */
    private fun dropFromJournal(ids: Set<String>) {
        if (ids.isEmpty()) return
        _journal.value = _journal.value.filterNot { journalEventId(it) in ids }
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
                // The manual linked rows are feed entries in their own right and
                // have just been deleted too, so they go with it.
                val alsoDeleted = buildSet {
                    add(id)
                    linked.triggers.filter { it.source != "system" }.forEach { add(it.id) }
                    linked.medicines.filter { it.source != "system" }.forEach { add(it.id) }
                    linked.reliefs.filter { it.source != "system" }.forEach { add(it.id) }
                    linked.prodromes.filter { it.source != "system" }.forEach { add(it.id) }
                    linked.activities.filter { it.source != "system" }.forEach { add(it.id) }
                    linked.locations.filter { it.source != "system" }.forEach { add(it.id) }
                }
                dropFromJournal(alsoDeleted)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun removeTrigger(accessToken: String, id: String) {
        viewModelScope.launch {
            try {
                db.deleteTrigger(accessToken, id)
                dropFromJournal(setOf(id))
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun removeMedicine(accessToken: String, id: String) {
        viewModelScope.launch {
            try {
                db.deleteMedicine(accessToken, id)
                dropFromJournal(setOf(id))
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun removeRelief(accessToken: String, id: String) {
        viewModelScope.launch {
            try {
                db.deleteRelief(accessToken, id)
                dropFromJournal(setOf(id))
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun removeProdrome(accessToken: String, id: String) {
        viewModelScope.launch {
            try {
                db.deleteProdromeLog(accessToken, id)
                dropFromJournal(setOf(id))
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun removeLocation(accessToken: String, id: String) {
        viewModelScope.launch {
            try {
                db.deleteLocationLog(accessToken, id)
                dropFromJournal(setOf(id))
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun removeActivity(accessToken: String, id: String) {
        viewModelScope.launch {
            try {
                db.deleteActivityLog(accessToken, id)
                dropFromJournal(setOf(id))
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun removeMissedActivity(accessToken: String, id: String) {
        viewModelScope.launch {
            try {
                db.deleteMissedActivityLog(accessToken, id)
                dropFromJournal(setOf(id))
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
        migraineId: String? = null,
        reliefScale: String? = null,
        sideEffectScale: String? = null,
        sideEffectNotes: String? = null,
        doseValue: Double? = null,
        doseUnit: String? = null
    ) {
        viewModelScope.launch {
            try {
                val updated = db.updateMedicine(accessToken, id, name, amount, startAt, notes, migraineId, reliefScale = reliefScale, sideEffectScale = sideEffectScale, sideEffectNotes = sideEffectNotes, doseValue = doseValue, doseUnit = doseUnit)
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
        endAt: String? = null,
        notes: String? = null,
        migraineId: String? = null,
        reliefScale: String? = null,
        sideEffectScale: String? = null,
        sideEffectNotes: String? = null
    ) {
        viewModelScope.launch {
            try {
                val updated = db.updateRelief(accessToken, id, type, startAt, notes, migraineId, endAt = endAt, reliefScale = reliefScale, sideEffectScale = sideEffectScale, sideEffectNotes = sideEffectNotes)
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
                _medicineUnitsByLabel.value = pool.associate { it.label to (it.doseUnit ?: DoseUnits.MG) }
            } catch (e: Exception) {
                e.printStackTrace()
                _medicineOptionsFrequent.value = emptyList()
                _medicineOptionsAll.value = emptyList()
                _medicineOptions.value = emptyList()
                _medicineUnitsByLabel.value = emptyMap()
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





