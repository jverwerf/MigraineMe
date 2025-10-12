package com.migraineme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow

@Composable
fun EditMigraineScreen(
    navController: NavController,
    authVm: AuthViewModel,
    vm: LogViewModel,
    id: String
) {
    val auth by authVm.state.collectAsState()
    val token = auth.accessToken

    // Local service to avoid changing VMs
    val db = remember { SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) }
    val scope = rememberCoroutineScope()

    // Core row state
    var row by remember { mutableStateOf<SupabaseDbService.MigraineRow?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Pools and prefs for dropdowns
    var mgFrequent by remember { mutableStateOf(listOf<String>()) }
    var mgAll by remember { mutableStateOf(listOf<String>()) }

    var trigPool by remember { mutableStateOf(listOf<SupabaseDbService.AllTriggerRow>()) }
    var trigFreq by remember { mutableStateOf(listOf<String>()) }

    var medPool by remember { mutableStateOf(listOf<SupabaseDbService.AllMedicineRow>()) }
    var medFreq by remember { mutableStateOf(listOf<String>()) }

    var relPool by remember { mutableStateOf(listOf<SupabaseDbService.AllReliefRow>()) }
    var relFreq by remember { mutableStateOf(listOf<String>()) }

    // Linked items
    var linkedTriggers by remember { mutableStateOf(listOf<SupabaseDbService.TriggerRow>()) }
    var linkedMeds by remember { mutableStateOf(listOf<SupabaseDbService.MedicineRow>()) }
    var linkedRels by remember { mutableStateOf(listOf<SupabaseDbService.ReliefRow>()) }

    LaunchedEffect(token, id) {
        if (token.isNullOrBlank()) return@LaunchedEffect
        try {
            loading = true
            error = null
            // Load migraine row
            val r = db.getMigraineById(token, id)
            row = r

            // Load pools and prefs
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

            // Load linked items from full lists and filter by migraineId
            val allT = db.getAllTriggers(token)
            linkedTriggers = allT.filter { it.migraineId == id }
            val allM = db.getAllMedicines(token)
            linkedMeds = allM.filter { it.migraineId == id }
            val allR = db.getAllReliefs(token)
            linkedRels = allR.filter { it.migraineId == id }
        } catch (e: Exception) {
            e.printStackTrace()
            error = e.message ?: "Failed to load"
        } finally {
            loading = false
        }
    }

    // UI state mirrors draft
    var menuOpen by remember { mutableStateOf(false) }
    var selectedLabel by remember { mutableStateOf<String?>(null) }
    var severity by remember { mutableStateOf(5f) }
    var beganAt by remember { mutableStateOf<String?>(null) }
    var endedAt by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf(TextFieldValue("")) }

    // Bootstrap UI state from row
    LaunchedEffect(row?.id) {
        row?.let {
            selectedLabel = it.type ?: "Migraine"
            severity = (it.severity ?: 5).coerceIn(1, 10).toFloat()
            beganAt = it.startAt
            endedAt = it.endAt
            notes = TextFieldValue(it.notes ?: "")
        }
    }

    // Add dialogs
    var showAddTrig by remember { mutableStateOf<String?>(null) } // holds selected trigger label
    var showAddMed by remember { mutableStateOf<String?>(null) }  // medicine name
    var showAddRel by remember { mutableStateOf<String?>(null) }  // relief type

    Scaffold(
        bottomBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(onClick = { navController.popBackStack() }) { Text("Back") }
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        if (token.isNullOrBlank()) return@Button
                        scope.launch {
                            try {
                                db.deleteMigraine(token, id)
                                // refresh journal so badges remain correct
                                vm.loadJournal(token)
                                navController.popBackStack()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                ) { Text("Delete") }
            }
        }
    ) { inner ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        if (error != null || row == null) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(16.dp)
            ) {
                Text(error ?: "Not found")
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { navController.popBackStack() }) { Text("Back") }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Migraine type dropdown like draft
            item {
                Column {
                    OutlinedTextField(
                        value = selectedLabel ?: "Migraine",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Migraine selection") },
                        trailingIcon = {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose migraine")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (mgFrequent.isNotEmpty()) {
                            DropdownMenuItem(text = { Text("Frequent") }, onClick = {}, enabled = false)
                            mgFrequent.forEach { label ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    menuOpen = false
                                    selectedLabel = label
                                    if (!token.isNullOrBlank()) {
                                        scope.launch {
                                            val updated = db.updateMigraine(
                                                accessToken = token,
                                                id = id,
                                                type = label
                                            )
                                            row = updated
                                        }
                                    }
                                })
                            }
                            Divider()
                        }
                        if (mgAll.isNotEmpty()) {
                            DropdownMenuItem(text = { Text("All") }, onClick = {}, enabled = false)
                            mgAll.forEach { label ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    menuOpen = false
                                    selectedLabel = label
                                    if (!token.isNullOrBlank()) {
                                        scope.launch {
                                            val updated = db.updateMigraine(
                                                accessToken = token,
                                                id = id,
                                                type = label
                                            )
                                            row = updated
                                        }
                                    }
                                })
                            }
                        }
                    }
                }
            }

            // Severity slider
            item {
                Column {
                    Text("Severity: ${severity.toInt()}", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = severity,
                        onValueChange = {
                            severity = it.coerceIn(1f, 10f)
                        },
                        onValueChangeFinished = {
                            if (!token.isNullOrBlank()) {
                                scope.launch {
                                    val updated = db.updateMigraine(
                                        accessToken = token,
                                        id = id,
                                        severity = severity.toInt()
                                    )
                                    row = updated
                                }
                            }
                        },
                        valueRange = 1f..10f,
                        steps = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Start / End time
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppDateTimePicker(label = "Start time") { iso ->
                        beganAt = iso
                        if (!token.isNullOrBlank()) {
                            scope.launch {
                                val updated = db.updateMigraine(
                                    accessToken = token,
                                    id = id,
                                    startAt = iso
                                )
                                row = updated
                            }
                        }
                    }
                    Text("Current: ${formatIsoDdMmYyHm(beganAt)}")
                    AppDateTimePicker(label = "End time") { iso ->
                        endedAt = iso
                        if (!token.isNullOrBlank()) {
                            scope.launch {
                                val updated = db.updateMigraine(
                                    accessToken = token,
                                    id = id,
                                    endAt = iso
                                )
                                row = updated
                            }
                        }
                    }
                    Text("Current: ${formatIsoDdMmYyHm(endedAt)}")
                }
            }

            // Notes
            item {
                OutlinedTextField(
                    value = notes,
                    onValueChange = {
                        notes = it
                    },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            // Save notes button
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = {
                            if (!token.isNullOrBlank()) {
                                scope.launch {
                                    val updated = db.updateMigraine(
                                        accessToken = token,
                                        id = id,
                                        notes = notes.text
                                    )
                                    row = updated
                                }
                            }
                        }
                    ) { Text("Save notes") }
                }
            }

            // Linked TRIGGERS section
            item { Text("Triggers", style = MaterialTheme.typography.titleMedium) }
            items(linkedTriggers, key = { it.id }) { t ->
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
                        }
                        Row {
                            TextButton(onClick = { navController.navigate("${Routes.EDIT_TRIGGER}/${t.id}") }) {
                                Text("Edit")
                            }
                            IconButton(onClick = {
                                if (token.isNullOrBlank()) return@IconButton
                                scope.launch {
                                    db.deleteTrigger(token, t.id)
                                    linkedTriggers = linkedTriggers.filterNot { it.id == t.id }
                                    vm.loadJournal(token)
                                }
                            }) { Icon(Icons.Filled.Delete, contentDescription = "Delete trigger") }
                        }
                    }
                }
            }
            // Add trigger chips
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (trigFreq.isNotEmpty()) {
                        Text("Frequent", style = MaterialTheme.typography.titleSmall)
                        FlowRowWrap {
                            trigFreq.forEach { label ->
                                AssistChip(onClick = { showAddTrig = label }, label = { Text(label) })
                            }
                        }
                    }
                    val remainingTrig = trigPool.map { it.label }.filter { it !in trigFreq }
                    if (remainingTrig.isNotEmpty()) {
                        Text("All", style = MaterialTheme.typography.titleSmall)
                        FlowRowWrap {
                            remainingTrig.forEach { label ->
                                AssistChip(onClick = { showAddTrig = label }, label = { Text(label) })
                            }
                        }
                    }
                }
            }

            // Linked MEDICINES
            item { Text("Medicines", style = MaterialTheme.typography.titleMedium) }
            items(linkedMeds, key = { it.id }) { m ->
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
                        }
                        Row {
                            TextButton(onClick = { navController.navigate("${Routes.EDIT_MEDICINE}/${m.id}") }) {
                                Text("Edit")
                            }
                            IconButton(onClick = {
                                if (token.isNullOrBlank()) return@IconButton
                                scope.launch {
                                    db.deleteMedicine(token, m.id)
                                    linkedMeds = linkedMeds.filterNot { it.id == m.id }
                                    vm.loadJournal(token)
                                }
                            }) { Icon(Icons.Filled.Delete, contentDescription = "Delete medicine") }
                        }
                    }
                }
            }
            // Add medicine chips
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (medFreq.isNotEmpty()) {
                        Text("Frequent", style = MaterialTheme.typography.titleSmall)
                        FlowRowWrap {
                            medFreq.forEach { label ->
                                AssistChip(onClick = { showAddMed = label }, label = { Text(label) })
                            }
                        }
                    }
                    val remaining = medPool.map { it.label }.filter { it !in medFreq }
                    if (remaining.isNotEmpty()) {
                        Text("All", style = MaterialTheme.typography.titleSmall)
                        FlowRowWrap {
                            remaining.forEach { label ->
                                AssistChip(onClick = { showAddMed = label }, label = { Text(label) })
                            }
                        }
                    }
                }
            }

            // Linked RELIEFS
            item { Text("Reliefs", style = MaterialTheme.typography.titleMedium) }
            items(linkedRels, key = { it.id }) { r ->
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
                        }
                        Row {
                            TextButton(onClick = { navController.navigate("${Routes.EDIT_RELIEF}/${r.id}") }) {
                                Text("Edit")
                            }
                            IconButton(onClick = {
                                if (token.isNullOrBlank()) return@IconButton
                                scope.launch {
                                    db.deleteRelief(token, r.id)
                                    linkedRels = linkedRels.filterNot { it.id == r.id }
                                    vm.loadJournal(token)
                                }
                            }) { Icon(Icons.Filled.Delete, contentDescription = "Delete relief") }
                        }
                    }
                }
            }
            // Add relief chips
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (relFreq.isNotEmpty()) {
                        Text("Frequent", style = MaterialTheme.typography.titleSmall)
                        FlowRowWrap {
                            relFreq.forEach { label ->
                                AssistChip(onClick = { showAddRel = label }, label = { Text(label) })
                            }
                        }
                    }
                    val remaining = relPool.map { it.label }.filter { it !in relFreq }
                    if (remaining.isNotEmpty()) {
                        Text("All", style = MaterialTheme.typography.titleSmall)
                        FlowRowWrap {
                            remaining.forEach { label ->
                                AssistChip(onClick = { showAddRel = label }, label = { Text(label) })
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(96.dp)) }
        }
    }

    // Trigger add dialog
    if (showAddTrig != null) {
        TimeAddDialog(
            title = "Add trigger",
            onDismiss = { showAddTrig = null },
            onConfirm = { iso ->
                val label = showAddTrig ?: return@TimeAddDialog
                if (!token.isNullOrBlank()) {
                    scope.launch {
                        try {
                            val t = db.insertTrigger(
                                accessToken = token,
                                migraineId = id,
                                type = label,
                                startAt = iso,
                                notes = null
                            )
                            linkedTriggers = listOf(t) + linkedTriggers
                            vm.loadJournal(token)
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
                showAddTrig = null
            }
        )
    }

    // Medicine add dialog
    if (showAddMed != null) {
        MedicineAddDialog(
            title = "Add medicine",
            name = showAddMed!!,
            onDismiss = { showAddMed = null },
            onConfirm = { amount, iso, notesText ->
                if (!token.isNullOrBlank()) {
                    scope.launch {
                        try {
                            val m = db.insertMedicine(
                                accessToken = token,
                                migraineId = id,
                                name = showAddMed,
                                amount = amount,
                                startAt = iso,
                                notes = notesText
                            )
                            linkedMeds = listOf(m) + linkedMeds
                            vm.loadJournal(token)
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
                showAddMed = null
            }
        )
    }

    // Relief add dialog
    if (showAddRel != null) {
        ReliefAddDialog(
            title = "Add relief",
            typeLabel = showAddRel!!,
            onDismiss = { showAddRel = null },
            onConfirm = { durationMinutes, iso, notesText ->
                if (!token.isNullOrBlank()) {
                    scope.launch {
                        try {
                            val r = db.insertRelief(
                                accessToken = token,
                                migraineId = id,
                                type = showAddRel,
                                durationMinutes = durationMinutes,
                                startAt = iso,
                                notes = notesText
                            )
                            linkedRels = listOf(r) + linkedRels
                            vm.loadJournal(token)
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
                showAddRel = null
            }
        )
    }
}

/* ---------- small UI helpers (local to this screen) ---------- */

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
