package com.migraineme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow

/* ---------- Top-level types ---------- */

private enum class Step { CORE, TRIGGERS, MEDICINES, RELIEFS, REVIEW }

private data class PendingTrigger(val type: String, val startIso: String?)
private data class PendingMedicine(val name: String, val amount: String?, val startIso: String?, val notes: String?)
private data class PendingRelief(val type: String, val durationMinutes: Int?, val startIso: String?, val notes: String?)

/* ----------------------------------------------------------------------------- */

@Composable
fun EditMigraineScreen(
    navController: NavController,
    authVm: AuthViewModel,
    vm: LogViewModel,
    id: String
) {
    val auth by authVm.state.collectAsState()
    val token = auth.accessToken

    // Supabase access unchanged
    val db = remember { SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) }
    val scope = rememberCoroutineScope()

    // Stepper (same order as draft flow)
    var step by remember { mutableStateOf(Step.CORE) }

    // Base row and loading
    var row by remember { mutableStateOf<SupabaseDbService.MigraineRow?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    // Pools and prefs
    var mgFrequent by remember { mutableStateOf(listOf<String>()) }
    var mgAll by remember { mutableStateOf(listOf<String>()) }

    var trigPool by remember { mutableStateOf(listOf<SupabaseDbService.AllTriggerRow>()) }
    var trigFreq by remember { mutableStateOf(listOf<String>()) }

    var medPool by remember { mutableStateOf(listOf<SupabaseDbService.AllMedicineRow>()) }
    var medFreq by remember { mutableStateOf(listOf<String>()) }

    var relPool by remember { mutableStateOf(listOf<SupabaseDbService.AllReliefRow>()) }
    var relFreq by remember { mutableStateOf(listOf<String>()) }

    // Linked rows
    var linkedTriggers by remember { mutableStateOf(listOf<SupabaseDbService.TriggerRow>()) }
    var linkedMeds by remember { mutableStateOf(listOf<SupabaseDbService.MedicineRow>()) }
    var linkedRels by remember { mutableStateOf(listOf<SupabaseDbService.ReliefRow>()) }

    // Draft (single save)
    var selectedLabel by remember { mutableStateOf<String?>(null) }
    var severity by remember { mutableStateOf(5f) }
    var beganAt by remember { mutableStateOf<String?>(null) }
    var endedAt by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf(TextFieldValue("")) }

    // Queues for adds and deletes
    var addTriggers by remember { mutableStateOf(listOf<PendingTrigger>()) }
    var addMeds by remember { mutableStateOf(listOf<PendingMedicine>()) }
    var addRels by remember { mutableStateOf(listOf<PendingRelief>()) }

    var deleteTriggerIds by remember { mutableStateOf(setOf<String>()) }
    var deleteMedicineIds by remember { mutableStateOf(setOf<String>()) }
    var deleteReliefIds by remember { mutableStateOf(setOf<String>()) }

    // UI flags
    var typeMenuOpen by remember { mutableStateOf(false) }
    var showAddTrig by remember { mutableStateOf<String?>(null) }
    var showAddMed by remember { mutableStateOf<String?>(null) }
    var showAddRel by remember { mutableStateOf<String?>(null) }

    // Validation
    val endBeforeStart by derivedStateOf { isEndBeforeStart(beganAt, endedAt) }

    // Load data
    LaunchedEffect(token, id) {
        if (token.isNullOrBlank()) return@LaunchedEffect
        try {
            loading = true
            error = null

            row = db.getMigraineById(token, id)

            val mgPrefs = db.getMigrainePrefs(token)
            mgFrequent = mgPrefs.filter { it.status == "frequent" }.sortedBy { it.position }
                .mapNotNull { it.migraine?.label }
            mgAll = db.getAllMigrainePool(token).map { it.label }

            val tprefs = db.getTriggerPrefs(token)
            trigFreq = tprefs.filter { it.status == "frequent" }.sortedBy { it.position }
                .mapNotNull { it.trigger?.label }
            trigPool = db.getAllTriggerPool(token)

            val mprefs = db.getMedicinePrefs(token)
            medFreq = mprefs.filter { it.status == "frequent" }.sortedBy { it.position }
                .mapNotNull { it.medicine?.label }
            medPool = db.getAllMedicinePool(token)

            val rprefs = db.getReliefPrefs(token)
            relFreq = rprefs.filter { it.status == "frequent" }.sortedBy { it.position }
                .mapNotNull { it.relief?.label }
            relPool = db.getAllReliefPool(token)

            linkedTriggers = db.getAllTriggers(token).filter { it.migraineId == id }.sortedByDescending { it.startAt }
            linkedMeds = db.getAllMedicines(token).filter { it.migraineId == id }.sortedByDescending { it.startAt }
            linkedRels = db.getAllReliefs(token).filter { it.migraineId == id }.sortedByDescending { it.startAt }
        } catch (e: Exception) {
            e.printStackTrace()
            error = e.message ?: "Failed to load"
        } finally {
            loading = false
        }
    }

    // Seed draft
    LaunchedEffect(row?.id) {
        row?.let {
            selectedLabel = it.type ?: "Migraine"
            severity = (it.severity ?: 5).coerceIn(1, 10).toFloat()
            beganAt = it.startAt
            endedAt = it.endAt
            notes = TextFieldValue(it.notes ?: "")
            addTriggers = emptyList(); addMeds = emptyList(); addRels = emptyList()
            deleteTriggerIds = emptySet(); deleteMedicineIds = emptySet(); deleteReliefIds = emptySet()
        }
    }

    // Bottom bar and stepper
    Scaffold(
        bottomBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        when (step) {
                            Step.CORE -> navController.popBackStack()
                            Step.TRIGGERS -> step = Step.CORE
                            Step.MEDICINES -> step = Step.TRIGGERS
                            Step.RELIEFS -> step = Step.MEDICINES
                            Step.REVIEW -> step = Step.RELIEFS
                        }
                    },
                    enabled = !saving
                ) { Text(if (step == Step.CORE) "Back" else "Previous") }

                when (step) {
                    Step.CORE, Step.TRIGGERS, Step.MEDICINES, Step.RELIEFS -> {
                        Button(
                            onClick = {
                                step = when (step) {
                                    Step.CORE -> Step.TRIGGERS
                                    Step.TRIGGERS -> Step.MEDICINES
                                    Step.MEDICINES -> Step.RELIEFS
                                    Step.RELIEFS -> Step.REVIEW
                                    Step.REVIEW -> Step.REVIEW
                                }
                            },
                            enabled = !saving && !(step == Step.CORE && endBeforeStart)
                        ) { Text("Next") }
                    }
                    Step.REVIEW -> {
                        Button(
                            onClick = {
                                if (token.isNullOrBlank() || row == null) return@Button
                                scope.launch {
                                    try {
                                        saving = true
                                        if (endBeforeStart) {
                                            error = "End time cannot be before start time"
                                            saving = false
                                            return@launch
                                        }
                                        // Update migraine
                                        row = db.updateMigraine(
                                            accessToken = token,
                                            id = id,
                                            type = selectedLabel,
                                            severity = severity.toInt(),
                                            startAt = beganAt,
                                            endAt = endedAt,
                                            notes = notes.text
                                        )
                                        // Deletes
                                        deleteTriggerIds.forEach { runCatching { db.deleteTrigger(token, it) } }
                                        deleteMedicineIds.forEach { runCatching { db.deleteMedicine(token, it) } }
                                        deleteReliefIds.forEach { runCatching { db.deleteRelief(token, it) } }
                                        // Adds
                                        addTriggers.forEach { t ->
                                            runCatching {
                                                db.insertTrigger(
                                                    accessToken = token,
                                                    migraineId = id,
                                                    type = t.type,
                                                    startAt = t.startIso ?: beganAt,
                                                    notes = null
                                                )
                                            }
                                        }
                                        addMeds.forEach { m ->
                                            runCatching {
                                                db.insertMedicine(
                                                    accessToken = token,
                                                    migraineId = id,
                                                    name = m.name,
                                                    amount = m.amount,
                                                    startAt = m.startIso ?: beganAt,
                                                    notes = m.notes
                                                )
                                            }
                                        }
                                        addRels.forEach { r ->
                                            runCatching {
                                                db.insertRelief(
                                                    accessToken = token,
                                                    migraineId = id,
                                                    type = r.type,
                                                    durationMinutes = r.durationMinutes,
                                                    startAt = r.startIso ?: beganAt,
                                                    notes = r.notes
                                                )
                                            }
                                        }
                                        vm.loadJournal(token)
                                        navController.popBackStack()
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        error = e.message ?: "Failed to save"
                                    } finally {
                                        saving = false
                                    }
                                }
                            },
                            enabled = !saving && !endBeforeStart
                        ) { Text(if (saving) "Saving..." else "Save") }
                    }
                }
            }
        }
    ) { inner ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        if (error != null || row == null) {
            Column(Modifier.fillMaxSize().padding(inner).padding(16.dp)) {
                Text(error ?: "Not found")
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { navController.popBackStack() }) { Text("Back") }
            }
            return@Scaffold
        }

        when (step) {
            Step.CORE -> CorePage(
                selectedLabel = selectedLabel,
                onSelectLabel = { selectedLabel = it },
                mgFrequent = mgFrequent,
                mgAll = mgAll,
                typeMenuOpen = typeMenuOpen,
                setTypeMenuOpen = { typeMenuOpen = it },
                severity = severity,
                setSeverity = { severity = it.coerceIn(1f, 10f) },
                beganAt = beganAt,
                setBeganAt = { beganAt = it },
                endedAt = endedAt,
                setEndedAt = { endedAt = it },
                endBeforeStart = endBeforeStart,
                notes = notes,
                setNotes = { notes = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(horizontal = 16.dp)
            )
            Step.TRIGGERS -> TriggersPage(
                trigFreq = trigFreq,
                trigPool = trigPool.map { it.label },
                linked = linkedTriggers,
                addQueue = addTriggers,
                onQueueRemove = { addTriggers = addTriggers.filterNot { p -> p === it } },
                deleteIds = deleteTriggerIds,
                toggleDelete = { idToggle ->
                    deleteTriggerIds = if (deleteTriggerIds.contains(idToggle)) deleteTriggerIds - idToggle else deleteTriggerIds + idToggle
                },
                setShowAddTrig = { showAddTrig = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(horizontal = 16.dp)
            )
            Step.MEDICINES -> MedicinesPage(
                medFreq = medFreq,
                medPool = medPool.map { it.label },
                linked = linkedMeds,
                addQueue = addMeds,
                onQueueRemove = { addMeds = addMeds.filterNot { p -> p === it } },
                deleteIds = deleteMedicineIds,
                toggleDelete = { idToggle ->
                    deleteMedicineIds = if (deleteMedicineIds.contains(idToggle)) deleteMedicineIds - idToggle else deleteMedicineIds + idToggle
                },
                setShowAddMed = { showAddMed = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(horizontal = 16.dp)
            )
            Step.RELIEFS -> ReliefsPage(
                relFreq = relFreq,
                relPool = relPool.map { it.label },
                linked = linkedRels,
                addQueue = addRels,
                onQueueRemove = { addRels = addRels.filterNot { p -> p === it } },
                deleteIds = deleteReliefIds,
                toggleDelete = { idToggle ->
                    deleteReliefIds = if (deleteReliefIds.contains(idToggle)) deleteReliefIds - idToggle else deleteReliefIds + idToggle
                },
                setShowAddRel = { showAddRel = it },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(horizontal = 16.dp)
            )
            Step.REVIEW -> ReviewPage(
                label = selectedLabel,
                severity = severity.toInt(),
                beganAt = beganAt,
                endedAt = endedAt,
                notes = notes.text,
                deletes = Triple(deleteTriggerIds.size, deleteMedicineIds.size, deleteReliefIds.size),
                adds = Triple(addTriggers.size, addMeds.size, addRels.size),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(16.dp)
            )
        }
    }

    // Add dialogs (queue only)
    if (showAddTrig != null) {
        TimeAddDialog(
            title = "Add trigger",
            onDismiss = { showAddTrig = null },
            onConfirm = { iso ->
                val label = showAddTrig ?: return@TimeAddDialog
                addTriggers = listOf(PendingTrigger(type = label, startIso = iso)) + addTriggers
                showAddTrig = null
            }
        )
    }
    if (showAddMed != null) {
        MedicineAddDialog(
            title = "Add medicine",
            name = showAddMed!!,
            onDismiss = { showAddMed = null },
            onConfirm = { amount, iso, notesText ->
                val name = showAddMed ?: return@MedicineAddDialog
                addMeds = listOf(PendingMedicine(name, amount, iso, notesText)) + addMeds
                showAddMed = null
            }
        )
    }
    if (showAddRel != null) {
        ReliefAddDialog(
            title = "Add relief",
            typeLabel = showAddRel!!,
            onDismiss = { showAddRel = null },
            onConfirm = { durationMinutes, iso, notesText ->
                val type = showAddRel ?: return@ReliefAddDialog
                addRels = listOf(PendingRelief(type, durationMinutes, iso, notesText)) + addRels
                showAddRel = null
            }
        )
    }
}

/* -------------------- Pages -------------------- */

@Composable
private fun CorePage(
    selectedLabel: String?,
    onSelectLabel: (String) -> Unit,
    mgFrequent: List<String>,
    mgAll: List<String>,
    typeMenuOpen: Boolean,
    setTypeMenuOpen: (Boolean) -> Unit,
    severity: Float,
    setSeverity: (Float) -> Unit,
    beganAt: String?,
    setBeganAt: (String?) -> Unit,
    endedAt: String?,
    setEndedAt: (String?) -> Unit,
    endBeforeStart: Boolean,
    notes: TextFieldValue,
    setNotes: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column {
                OutlinedTextField(
                    value = selectedLabel ?: "Migraine",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Migraine selection") },
                    trailingIcon = {
                        IconButton(onClick = { setTypeMenuOpen(true) }) {
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose migraine")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                DropdownMenu(expanded = typeMenuOpen, onDismissRequest = { setTypeMenuOpen(false) }) {
                    if (mgFrequent.isNotEmpty()) {
                        DropdownMenuItem(text = { Text("Frequent") }, onClick = {}, enabled = false)
                        mgFrequent.forEach { label ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                setTypeMenuOpen(false); onSelectLabel(label)
                            })
                        }
                        HorizontalDivider()
                    }
                    if (mgAll.isNotEmpty()) {
                        DropdownMenuItem(text = { Text("All") }, onClick = {}, enabled = false)
                        mgAll.forEach { label ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                setTypeMenuOpen(false); onSelectLabel(label)
                            })
                        }
                    }
                }
            }
        }
        item {
            Column {
                Text("Severity: ${severity.toInt()}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = severity,
                    onValueChange = setSeverity,
                    valueRange = 1f..10f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppDateTimePicker(label = "Start time") { iso -> setBeganAt(iso) }
                Text("Current: ${formatIsoDdMmYyHm(beganAt)}")
                AppDateTimePicker(label = "End time") { iso -> setEndedAt(iso) }
                Text("Current: ${formatIsoDdMmYyHm(endedAt)}")
                if (endBeforeStart) {
                    Text("End time cannot be before start time", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        item {
            OutlinedTextField(
                value = notes,
                onValueChange = setNotes,
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun TriggersPage(
    trigFreq: List<String>,
    trigPool: List<String>,
    linked: List<SupabaseDbService.TriggerRow>,
    addQueue: List<PendingTrigger>,
    onQueueRemove: (PendingTrigger) -> Unit,
    deleteIds: Set<String>,
    toggleDelete: (String) -> Unit,
    setShowAddTrig: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Text("Triggers", style = MaterialTheme.typography.titleMedium) }
        items(linked, key = { it.id }) { t ->
            val mark = deleteIds.contains(t.id)
            val isPredicted = t.type == "menstruation_predicted"
            
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Type: ${t.type ?: "-"}")
                        Text("Time: ${formatIsoDdMmYyHm(t.startAt)}")
                        if (!t.notes.isNullOrBlank()) Text("Notes: ${t.notes}")
                        if (mark) Text("Marked for deletion", color = MaterialTheme.colorScheme.error)
                        if (isPredicted) {
                            Text(
                                "Predicted",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    // Only show Edit/Delete for non-predicted triggers
                    if (!isPredicted) {
                        Row {
                            TextButton(onClick = { /* optional edit route */ }) { Text("Edit") }
                            IconButton(onClick = { toggleDelete(t.id) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Toggle delete trigger")
                            }
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (trigFreq.isNotEmpty()) {
                    Text("Frequent", style = MaterialTheme.typography.titleSmall)
                    FlowRowWrap { trigFreq.forEach { label -> AssistChip(onClick = { setShowAddTrig(label) }, label = { Text(label) }) } }
                }
                val remaining = trigPool.filter { it !in trigFreq }
                if (remaining.isNotEmpty()) {
                    Text("All", style = MaterialTheme.typography.titleSmall)
                    FlowRowWrap { remaining.forEach { label -> AssistChip(onClick = { setShowAddTrig(label) }, label = { Text(label) }) } }
                }
            }
        }
        if (addQueue.isNotEmpty()) {
            item { Text("Pending triggers", style = MaterialTheme.typography.titleSmall) }
            items(addQueue) { pt ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Type: ${pt.type}")
                            Text("Time: ${formatIsoDdMmYyHm(pt.startIso)}")
                        }
                        TextButton(onClick = { onQueueRemove(pt) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MedicinesPage(
    medFreq: List<String>,
    medPool: List<String>,
    linked: List<SupabaseDbService.MedicineRow>,
    addQueue: List<PendingMedicine>,
    onQueueRemove: (PendingMedicine) -> Unit,
    deleteIds: Set<String>,
    toggleDelete: (String) -> Unit,
    setShowAddMed: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Text("Medicines", style = MaterialTheme.typography.titleMedium) }
        items(linked, key = { it.id }) { m ->
            val mark = deleteIds.contains(m.id)
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Name: ${m.name ?: "-"}")
                        Text("Amount: ${m.amount ?: "-"}")
                        Text("Time: ${formatIsoDdMmYyHm(m.startAt)}")
                        if (!m.notes.isNullOrBlank()) Text("Notes: ${m.notes}")
                        if (mark) Text("Marked for deletion", color = MaterialTheme.colorScheme.error)
                    }
                    Row {
                        TextButton(onClick = { /* optional edit route */ }) { Text("Edit") }
                        IconButton(onClick = { toggleDelete(m.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Toggle delete medicine")
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (medFreq.isNotEmpty()) {
                    Text("Frequent", style = MaterialTheme.typography.titleSmall)
                    FlowRowWrap { medFreq.forEach { label -> AssistChip(onClick = { setShowAddMed(label) }, label = { Text(label) }) } }
                }
                val remaining = medPool.filter { it !in medFreq }
                if (remaining.isNotEmpty()) {
                    Text("All", style = MaterialTheme.typography.titleSmall)
                    FlowRowWrap { remaining.forEach { label -> AssistChip(onClick = { setShowAddMed(label) }, label = { Text(label) }) } }
                }
            }
        }
        if (addQueue.isNotEmpty()) {
            item { Text("Pending medicines", style = MaterialTheme.typography.titleSmall) }
            items(addQueue) { pm ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Name: ${pm.name}")
                            Text("Amount: ${pm.amount ?: "-"}")
                            Text("Time: ${formatIsoDdMmYyHm(pm.startIso)}")
                            if (!pm.notes.isNullOrBlank()) Text("Notes: ${pm.notes}")
                        }
                        TextButton(onClick = { onQueueRemove(pm) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReliefsPage(
    relFreq: List<String>,
    relPool: List<String>,
    linked: List<SupabaseDbService.ReliefRow>,
    addQueue: List<PendingRelief>,
    onQueueRemove: (PendingRelief) -> Unit,
    deleteIds: Set<String>,
    toggleDelete: (String) -> Unit,
    setShowAddRel: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Text("Reliefs", style = MaterialTheme.typography.titleMedium) }
        items(linked, key = { it.id }) { r ->
            val mark = deleteIds.contains(r.id)
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Type: ${r.type ?: "-"}")
                        Text("Duration: ${r.durationMinutes ?: 0} min")
                        Text("Time: ${formatIsoDdMmYyHm(r.startAt)}")
                        if (!r.notes.isNullOrBlank()) Text("Notes: ${r.notes}")
                        if (mark) Text("Marked for deletion", color = MaterialTheme.colorScheme.error)
                    }
                    Row {
                        TextButton(onClick = { /* optional edit route */ }) { Text("Edit") }
                        IconButton(onClick = { toggleDelete(r.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Toggle delete relief")
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (relFreq.isNotEmpty()) {
                    Text("Frequent", style = MaterialTheme.typography.titleSmall)
                    FlowRowWrap { relFreq.forEach { label -> AssistChip(onClick = { setShowAddRel(label) }, label = { Text(label) }) } }
                }
                val remaining = relPool.filter { it !in relFreq }
                if (remaining.isNotEmpty()) {
                    Text("All", style = MaterialTheme.typography.titleSmall)
                    FlowRowWrap { remaining.forEach { label -> AssistChip(onClick = { setShowAddRel(label) }, label = { Text(label) }) } }
                }
            }
        }
        if (addQueue.isNotEmpty()) {
            item { Text("Pending reliefs", style = MaterialTheme.typography.titleSmall) }
            items(addQueue) { pr ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Type: ${pr.type}")
                            Text("Duration: ${pr.durationMinutes ?: 0} min")
                            Text("Time: ${formatIsoDdMmYyHm(pr.startIso)}")
                            if (!pr.notes.isNullOrBlank()) Text("Notes: ${pr.notes}")
                        }
                        TextButton(onClick = { onQueueRemove(pr) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewPage(
    label: String?,
    severity: Int,
    beganAt: String?,
    endedAt: String?,
    notes: String?,
    deletes: Triple<Int, Int, Int>,
    adds: Triple<Int, Int, Int>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Review", style = MaterialTheme.typography.titleLarge)
        Text("Migraine: ${label ?: "Migraine"}")
        Text("Severity: $severity")
        Text("Start: ${formatIsoDdMmYyHm(beganAt)}")
        Text("End: ${formatIsoDdMmYyHm(endedAt)}")
        if (!notes.isNullOrBlank()) Text("Notes: $notes")
        Spacer(Modifier.height(8.dp))
        Text("Queued changes:", style = MaterialTheme.typography.titleMedium)
        Text("Delete → Triggers: ${deletes.first}, Medicines: ${deletes.second}, Reliefs: ${deletes.third}")
        Text("Add → Triggers: ${adds.first}, Medicines: ${adds.second}, Reliefs: ${adds.third}")
    }
}

/* -------------------- Helpers -------------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowWrap(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) { content() }
}

private fun formatIsoDdMmYyHm(iso: String?): String {
    if (iso.isNullOrBlank()) return "-"
    return try {
        val odt = runCatching { OffsetDateTime.parse(iso) }.getOrNull()
        val ldt = odt?.toLocalDateTime() ?: LocalDateTime.parse(iso)
        ldt.format(DateTimeFormatter.ofPattern("dd/MM/yy HH:mm"))
    } catch (_: Exception) {
        "-"
    }
}

private fun isEndBeforeStart(startIso: String?, endIso: String?): Boolean {
    if (startIso.isNullOrBlank() || endIso.isNullOrBlank()) return false
    val start = parseInstantFlexible(startIso) ?: return false
    val end = parseInstantFlexible(endIso) ?: return false
    return end.isBefore(start)
}

private fun parseInstantFlexible(iso: String): Instant? {
    return runCatching { Instant.parse(iso) }.getOrElse {
        runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
    }
}

/* ---------- local dialogs ---------- */

@Composable
private fun TimeAddDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit
) {
    var pickedIso by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(pickedIso) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Time: ${formatIsoDdMmYyHm(pickedIso)}")
                AppDateTimePicker(label = "Select time") { iso -> pickedIso = iso }
            }
        }
    )
}

@Composable
private fun MedicineAddDialog(
    title: String,
    name: String,
    onDismiss: () -> Unit,
    onConfirm: (amount: String?, startIso: String?, notes: String?) -> Unit
) {
    var amount by remember { mutableStateOf("") }
    var pickedIso by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(amount.ifBlank { null }, pickedIso, notes.ifBlank { null }) }) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Medicine: $name")
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount") },
                    modifier = Modifier.fillMaxWidth()
                )
                AppDateTimePicker(label = "Select time") { iso -> pickedIso = iso }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

@Composable
private fun ReliefAddDialog(
    title: String,
    typeLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (durationMinutes: Int?, startIso: String?, notes: String?) -> Unit
) {
    var durationText by remember { mutableStateOf("") }
    var pickedIso by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val dur = durationText.toIntOrNull()
                    onConfirm(dur, pickedIso, notes.ifBlank { null })
                }
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Relief: $typeLabel")
                OutlinedTextField(
                    value = durationText,
                    onValueChange = { v -> durationText = v.filter { it.isDigit() }.take(4) },
                    label = { Text("Duration minutes") },
                    modifier = Modifier.fillMaxWidth()
                )
                AppDateTimePicker(label = "Select time") { iso -> pickedIso = iso }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}
