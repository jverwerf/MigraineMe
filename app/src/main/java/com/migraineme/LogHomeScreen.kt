package com.migraineme

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class NewTab { MIGRAINE, MEDICINE, RELIEF }

@Composable
fun LogHomeScreen(
    navController: NavHostController,
    authVm: AuthViewModel,
    vm: LogViewModel = viewModel()
) {
    val auth by authVm.state.collectAsState()
    val state by vm.state.collectAsState()

    LaunchedEffect(auth.accessToken) {
        auth.accessToken?.let {
            vm.preloadForNew(it)
            vm.loadHistory(it)
        }
    }

    var mainTab by remember { mutableStateOf(0) }              // 0 = NEW, 1 = HISTORY
    var newTab by remember { mutableStateOf(NewTab.MIGRAINE) } // default to migraine

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Full-width tabs; History label shows per-type incomplete counts with icons
        TabRow(selectedTabIndex = mainTab, modifier = Modifier.fillMaxWidth()) {
            Tab(
                selected = mainTab == 0,
                onClick = { mainTab = 0 },
                modifier = Modifier.weight(1f),
                text = { Text("NEW") }
            )
            Tab(
                selected = mainTab == 1,
                onClick = { mainTab = 1 },
                modifier = Modifier.weight(1f),
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("History")
                        Spacer(Modifier.width(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (state.missingMigraineCount > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("${state.missingMigraineCount}", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            if (state.missingMedicineCount > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Medication, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("${state.missingMedicineCount}", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            if (state.missingReliefCount > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Spa, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("${state.missingReliefCount}", style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            )
        }

        Spacer(Modifier.height(16.dp))

        when (mainTab) {
            0 -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RowScopeFilterChipButton(
                            text = "Migraine",
                            selected = newTab == NewTab.MIGRAINE,
                            icon = { Icon(Icons.Outlined.Psychology, contentDescription = null) }
                        ) { newTab = NewTab.MIGRAINE }

                        RowScopeFilterChipButton(
                            text = "Medicine",
                            selected = newTab == NewTab.MEDICINE,
                            icon = { Icon(Icons.Outlined.Medication, contentDescription = null) }
                        ) { newTab = NewTab.MEDICINE }

                        RowScopeFilterChipButton(
                            text = "Relief",
                            selected = newTab == NewTab.RELIEF,
                            icon = { Icon(Icons.Outlined.Spa, contentDescription = null) }
                        ) { newTab = NewTab.RELIEF }
                    }

                    if (auth.accessToken == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Please sign in.")
                        }
                    } else {
                        when (newTab) {
                            NewTab.MIGRAINE -> MigraineFormInline(
                                accessToken = auth.accessToken!!,
                                vm = vm,
                                onSavedNavigateHome = { navController.navigate(Routes.HOME) { launchSingleTop = true } }
                            )
                            NewTab.MEDICINE -> MedicineFormInline(accessToken = auth.accessToken!!, state = state, vm = vm)
                            NewTab.RELIEF   -> ReliefFormInline(accessToken = auth.accessToken!!, state = state, vm = vm)
                        }
                    }
                }
            }
            1 -> {
                if (auth.accessToken == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Please sign in.")
                    }
                } else {
                    HistoryPaneInline(accessToken = auth.accessToken!!, state = state, vm = vm)
                }
            }
        }
    }
}

/* ====================== NEW: Migraine with meds/reliefs ====================== */

@Composable
private fun MigraineFormInline(
    accessToken: String,
    vm: LogViewModel,
    onSavedNavigateHome: () -> Unit
) {
    val context = LocalContext.current
    val deviceZone = remember { ZoneId.systemDefault() }
    val displayFmt = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm") }
    val isoFmt = remember { java.time.format.DateTimeFormatter.ISO_INSTANT }

    var type by remember { mutableStateOf("Migraine") }
    var severity by remember { mutableStateOf(5f) }
    var notes by remember { mutableStateOf("") }

    var beginDt by remember { mutableStateOf(LocalDateTime.now()) }
    var endDt by remember { mutableStateOf<LocalDateTime?>(null) }

    data class MedRow(val name: String = "", val amount: String = "", val dt: LocalDateTime = LocalDateTime.now())
    val meds = remember { mutableStateListOf<MedRow>() }

    data class ReliefRow(val type: String = "", val durationMin: Int = 30, val dt: LocalDateTime = LocalDateTime.now())
    val rels = remember { mutableStateListOf<ReliefRow>() }

    var showSuccess by remember { mutableStateOf(false) }
    val vmState by vm.state.collectAsState()
    LaunchedEffect(vmState.successMsg) {
        if (vmState.successMsg?.contains("Full entry saved", ignoreCase = true) == true ||
            vmState.successMsg?.contains("Migraine saved", ignoreCase = true) == true
        ) {
            showSuccess = true
        }
    }

    fun pickDateTime(initial: LocalDateTime, onPicked: (LocalDateTime) -> Unit) {
        val initDate = initial.toLocalDate()
        val initTime = initial.toLocalTime()
        DatePickerDialog(
            context,
            { _, y, m, d ->
                TimePickerDialog(
                    context,
                    { _, h, min -> onPicked(LocalDateTime.of(LocalDate.of(y, m + 1, d), LocalTime.of(h, min))) },
                    initTime.hour,
                    initTime.minute,
                    true
                ).show()
            },
            initDate.year, initDate.monthValue - 1, initDate.dayOfMonth
        ).show()
    }

    ElevatedCard(Modifier.fillMaxWidth(), elevation = CardDefaults.elevatedCardElevation(2.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Migraine", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = type, onValueChange = { type = it },
                label = { Text("Type") }, modifier = Modifier.fillMaxWidth()
            )

            Text("Severity: ${severity.toInt()}")
            Slider(value = severity, onValueChange = { severity = it }, valueRange = 0f..10f, steps = 9)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Begin & End", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { pickDateTime(beginDt) { beginDt = it } }, modifier = Modifier.weight(1f)) {
                        Text("Begin: ${beginDt.format(displayFmt)}")
                    }
                    OutlinedButton(onClick = { pickDateTime(endDt ?: beginDt) { endDt = it } }, modifier = Modifier.weight(1f)) {
                        Text("End: ${endDt?.format(displayFmt) ?: "—"}")
                    }
                }
            }

            OutlinedTextField(
                value = notes, onValueChange = { notes = it },
                label = { Text("Notes") }, modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            Text("Medicines (optional)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            meds.forEachIndexed { idx, row ->
                ElevatedCard(Modifier.fillMaxWidth(), elevation = CardDefaults.elevatedCardElevation(1.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(row.name, { meds[idx] = row.copy(name = it) }, label = { Text("Name") }, modifier = Modifier.weight(1f))
                            OutlinedTextField(row.amount, { meds[idx] = row.copy(amount = it) }, label = { Text("Amount") }, modifier = Modifier.weight(1f))
                            IconButton(onClick = { meds.removeAt(idx) }) { Icon(Icons.Outlined.Delete, contentDescription = "Remove") }
                        }
                        OutlinedButton(onClick = { pickDateTime(row.dt) { meds[idx] = row.copy(dt = it) } }) {
                            Text("Time: ${row.dt.format(displayFmt)}")
                        }
                    }
                }
            }
            OutlinedButton(onClick = { meds.add(MedRow()) }) { Text("Add medicine") }

            HorizontalDivider()

            Text("Reliefs (optional)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            rels.forEachIndexed { idx, row ->
                ElevatedCard(Modifier.fillMaxWidth(), elevation = CardDefaults.elevatedCardElevation(1.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(row.type, { rels[idx] = row.copy(type = it) }, label = { Text("Relief type") }, modifier = Modifier.fillMaxWidth())
                        Text("Duration: ${row.durationMin} min")
                        Slider(
                            value = row.durationMin.toFloat(),
                            onValueChange = { newVal -> rels[idx] = row.copy(durationMin = newVal.toInt()) },
                            valueRange = 0f..240f, steps = 23
                        )
                        OutlinedButton(onClick = { pickDateTime(row.dt) { rels[idx] = row.copy(dt = it) } }) {
                            Text("Time: ${row.dt.format(displayFmt)}")
                        }
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            IconButton(onClick = { rels.removeAt(idx) }) { Icon(Icons.Outlined.Delete, contentDescription = "Remove") }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { rels.add(ReliefRow()) }) { Text("Add relief") }

            Button(
                onClick = {
                    val isoFmt = java.time.format.DateTimeFormatter.ISO_INSTANT
                    val beginIso = isoFmt.format(beginDt.atZone(deviceZone).toInstant())
                    val endIso = endDt?.let { isoFmt.format(it.atZone(deviceZone).toInstant()) }

                    val medInputs = meds.filter { it.name.isNotBlank() }.map {
                        LogViewModel.MedInput(
                            name = it.name,
                            amount = it.amount.ifBlank { null },
                            notes = null,
                            takenAtIso = isoFmt.format(it.dt.atZone(deviceZone).toInstant())
                        )
                    }
                    val reliefInputs = rels.filter { it.type.isNotBlank() }.map {
                        LogViewModel.ReliefInput(
                            type = it.type,
                            durationMinutes = it.durationMin,
                            notes = null,
                            takenAtIso = isoFmt.format(it.dt.atZone(deviceZone).toInstant())
                        )
                    }

                    vm.addFull(
                        accessToken = accessToken,
                        type = type,
                        severity = severity.toInt(),
                        beganAtIso = beginIso,
                        endedAtIso = endIso,
                        note = notes.ifBlank { null },
                        meds = medInputs,
                        rels = reliefInputs
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = type.isNotBlank()
            ) { Text("Save migraine + items") }
        }
    }

    if (showSuccess) {
        AlertDialog(
            onDismissRequest = { showSuccess = false; onSavedNavigateHome() },
            title = { Text("Logged") },
            text = { Text("Your migraine (and items) have been saved.") },
            confirmButton = { TextButton(onClick = { showSuccess = false; onSavedNavigateHome() }) { Text("OK") } }
        )
    }
}

/* ====================== single Medicine form ====================== */

@Composable
private fun MedicineFormInline(accessToken: String, state: LogUiState, vm: LogViewModel) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var migraineIdx by remember { mutableStateOf(0) } // 0=None

    val options = listOf("None") + state.migrainesForLink.map { it.second }
    val selectedMigraineId = state.migrainesForLink.getOrNull(migraineIdx - 1)?.first

    ElevatedCard(Modifier.fillMaxWidth(), elevation = CardDefaults.elevatedCardElevation(2.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Medicine", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Which medicine") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("Amount / dose") }, modifier = Modifier.fillMaxWidth())
            Text("Link to migraine (optional)")
            AppDropdown(options, migraineIdx) { migraineIdx = it }
            OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { vm.addMedicine(accessToken, name, amount.ifBlank { null }, selectedMigraineId, notes.ifBlank { null }) },
                modifier = Modifier.fillMaxWidth(),
                enabled = name.isNotBlank()
            ) { Text("Save medicine") }
        }
    }
}

/* ====================== single Relief form ====================== */

@Composable
private fun ReliefFormInline(accessToken: String, state: LogUiState, vm: LogViewModel) {
    var type by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf(30f) }
    var notes by remember { mutableStateOf("") }
    var migraineIdx by remember { mutableStateOf(0) } // 0=None

    val options = listOf("None") + state.migrainesForLink.map { it.second }
    val selectedMigraineId = state.migrainesForLink.getOrNull(migraineIdx - 1)?.first

    ElevatedCard(Modifier.fillMaxWidth(), elevation = CardDefaults.elevatedCardElevation(2.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Relief", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = type, onValueChange = { type = it }, label = { Text("Which relief") }, modifier = Modifier.fillMaxWidth())
            Text("Duration: ${duration.toInt()} min")
            Slider(value = duration, onValueChange = { duration = it }, valueRange = 0f..240f, steps = 23)
            Text("Link to migraine (optional)")
            AppDropdown(options, migraineIdx) { migraineIdx = it }
            OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { vm.addRelief(accessToken, type, duration.toInt(), selectedMigraineId, notes.ifBlank { null }) },
                modifier = Modifier.fillMaxWidth(),
                enabled = type.isNotBlank()
            ) { Text("Save relief") }
        }
    }
}

/* ====================== HISTORY: show what's missing on each card ====================== */

@Composable
private fun HistoryPaneInline(
    accessToken: String,
    state: LogUiState,
    vm: LogViewModel
) {
    var editMigraine by remember { mutableStateOf<HistoryItem?>(null) }
    var editMedicine by remember { mutableStateOf<HistoryItem?>(null) }
    var editRelief by remember { mutableStateOf<HistoryItem?>(null) }

    data class DeleteTarget(val type: String, val id: String)
    var deleteTarget by remember { mutableStateOf<DeleteTarget?>(null) }

    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp)) }
    state.successMsg?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp)) }

    if (state.loading && state.history.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(state.history) { item ->
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.elevatedCardElevation(1.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${item.type}: ${item.title}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            when (item.type) {
                                "Migraine" -> editMigraine = item
                                "Medicine" -> editMedicine = item
                                "Relief"   -> editRelief = item
                            }
                        }) { Icon(Icons.Outlined.Edit, contentDescription = "Edit") }
                        IconButton(onClick = { deleteTarget = DeleteTarget(item.type, item.id) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                        }
                    }
                    Spacer(Modifier.height(6.dp))

                    item.subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                    }

                    // Concise line describing what's missing
                    val missing = buildList {
                        if (!item.hasFullTimestamps) add("time")
                        if (item.type == "Medicine" && item.missingAmount) add("amount")
                        if (item.type == "Relief" && item.missingDuration) add("duration")
                    }
                    if (missing.isNotEmpty()) {
                        Text(
                            "Missing: ${missing.joinToString(", ")}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }

    // Delete confirm
    deleteTarget?.let { tgt ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete ${tgt.type}?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    when (tgt.type) {
                        "Migraine" -> vm.deleteMigraine(accessToken, tgt.id)
                        "Medicine" -> vm.deleteMedicine(accessToken, tgt.id)
                        "Relief"   -> vm.deleteRelief(accessToken, tgt.id)
                    }
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }

    // Edit dialogs (your previous ones still apply)
}

/* ---------- tiny helpers ---------- */

private fun showTimePicker(
    context: android.content.Context,
    onIso: (String) -> Unit
) {
    val now = LocalDateTime.now()
    val isoFmt = java.time.format.DateTimeFormatter.ISO_INSTANT
    val zone = ZoneId.systemDefault()
    DatePickerDialog(
        context,
        { _, y, m, d ->
            TimePickerDialog(
                context,
                { _, h, min ->
                    val dt = LocalDateTime.of(LocalDate.of(y, m + 1, d), LocalTime.of(h, min))
                    onIso(isoFmt.format(dt.atZone(zone).toInstant()))
                },
                now.hour, now.minute, true
            ).show()
        },
        now.year, now.monthValue - 1, now.dayOfMonth
    ).show()
}

@Composable
private fun RowScope.RowScopeFilterChipButton(
    text: String,
    selected: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    if (selected) {
        FilledTonalButton(onClick = onClick, modifier = Modifier.weight(1f)) {
            icon(); Spacer(Modifier.width(8.dp)); Text(text)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f)) {
            icon(); Spacer(Modifier.width(8.dp)); Text(text)
        }
    }
}
