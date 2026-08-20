package com.migraineme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

/**
 * Journal quick-add: a "+ Add" card at the bottom of a migraine entry that
 * opens standalone add flows pre-linked to that migraine. The full edit
 * (prefillForEdit → wizard) stays untouched; this sits on top of it.
 *
 * Write paths follow the evening check-in, not the wizard: pain and aura are
 * APPEND-only child rows plus one parent mirror update — the wizard's
 * wholesale replace would wipe what was logged at attack start.
 */

// ── The "+ Add" card and its option rows ───────────────────────

enum class QuickAddKind(val key: String, val label: String, val color: Color, val compact: Boolean) {
    PAIN("pain", "Pain", Color(0xFFFF7BB0), false),
    AURA("aura", "Aura", Color(0xFFB97BFF), false),
    SYMPTOM("symptom", "Symptom", Color(0xFFE58FD0), false),
    MEDICINE("medicine", "Medicine", Color(0xFF4FC3F7), false),
    RELIEF("relief", "Relief", Color(0xFF81C784), false),
    POSTDROME("postdrome", "Postdrome", Color(0xFF9575CD), false),
    TRIGGER("trigger", "Trigger", Color(0xFFFFB74D), true),
    PRODROME("prodrome", "Prodrome", Color(0xFFFF8A65), true),
    ACTIVITY("activity", "Activity", Color(0xFF4DD0E1), true),
    LOCATION("location", "Location", Color(0xFFA1887F), true),
}

/** The app's hand-drawn category icons (HubIcons), same set the Home quick-log
 *  strip and the migraine hub use — never generic dots. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawQuickAddIcon(kind: QuickAddKind, color: Color) {
    HubIcons.run {
        when (kind) {
            QuickAddKind.PAIN -> drawLocationPin(color)
            QuickAddKind.AURA -> drawRipple(color)
            QuickAddKind.SYMPTOM -> drawMigraineStarburst(color)
            QuickAddKind.MEDICINE -> drawMedicinePill(color)
            QuickAddKind.RELIEF -> drawReliefLeaf(color)
            QuickAddKind.POSTDROME -> drawMoonSleep(color)
            QuickAddKind.TRIGGER -> drawTriggerBolt(color)
            QuickAddKind.PRODROME -> drawProdromeEye(color)
            QuickAddKind.ACTIVITY -> drawActivityPulse(color)
            QuickAddKind.LOCATION -> drawCompass(color)
        }
    }
}

@Composable
private fun QuickAddIconChip(kind: QuickAddKind, circle: androidx.compose.ui.unit.Dp, icon: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(circle)
            .clip(CircleShape)
            .background(kind.color.copy(alpha = 0.15f))
            .border(1.dp, kind.color.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(icon).drawBehind { drawQuickAddIcon(kind, kind.color) })
    }
}

@Composable
fun MigraineQuickAddSection(onAdd: (QuickAddKind) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Spacer(Modifier.height(6.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Transparent)
            .border(BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.35f)), RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            t("+ Add"),
            color = AppTheme.AccentPurple,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
        )
    }

    if (expanded) {
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            QuickAddKind.entries.filter { !it.compact }.forEach { kind ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), RoundedCornerShape(10.dp))
                        .clickable { expanded = false; onAdd(kind) }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    QuickAddIconChip(kind, circle = 28.dp, icon = 15.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(t(kind.label), color = Color.White, fontSize = 13.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                QuickAddKind.entries.filter { it.compact }.forEach { kind ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), RoundedCornerShape(10.dp))
                            .clickable { expanded = false; onAdd(kind) }
                            .padding(horizontal = 2.dp, vertical = 6.dp)
                    ) {
                        QuickAddIconChip(kind, circle = 20.dp, icon = 11.dp)
                        Spacer(Modifier.width(5.dp))
                        Text(t(kind.label), color = Color.White, fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ── Shared save-side helpers ───────────────────────────────────

/** Post-save housekeeping every quick-add path shares: refresh the migraine's
 *  feed event (its linked lists changed) and recalc the risk gauge, the same
 *  as every sibling write path. */
internal suspend fun quickAddFinish(
    ctx: android.content.Context,
    logVm: LogViewModel,
    token: String,
    migraineId: String,
) {
    logVm.refreshMigraineInJournal(token, migraineId)
    runCatching { EdgeFunctionsService().triggerRecalcRiskScores(ctx.applicationContext) }
}

// ── Standalone pain add: copy of the wizard's pain entry card ──

private class QuickPainEntry(
    val id: String = UUID.randomUUID().toString(),
    startAtIso: String?,
    severity: Int = 5,
    locations: List<String> = emptyList(),
) {
    var startAtIso by mutableStateOf(startAtIso)
    var severity by mutableStateOf(severity)
    var locations by mutableStateOf(locations)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickAddPainScreen(
    navController: NavController,
    authVm: AuthViewModel,
    logVm: LogViewModel,
    migraineId: String,
    migraineStartAtIso: String?,
) {
    val ctx = LocalContext.current
    val auth by authVm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val db = remember { SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) }
    val scroll = rememberScrollState()

    val entries = remember { mutableStateListOf(QuickPainEntry(startAtIso = migraineStartAtIso)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ScrollFadeContainer(scrollState = scroll) { scrollState ->
        ScrollableScreenContent(scrollState = scrollState, logoRevealHeight = 0.dp) {

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Outlined.Close, contentDescription = t("Close"), tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            HeroCard {
                Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = AppTheme.AccentPink, modifier = Modifier.size(40.dp))
                Text(t("Add pain"), color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(
                    t("Rate the severity and mark where you feel it. Entries are added to this migraine on top of what's already logged."),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            entries.forEachIndexed { index, entry ->
                QuickPainEntryCard(
                    entry = entry,
                    index = index,
                    canRemove = entries.size > 1,
                    onRemove = { entries.remove(entry) }
                )
            }

            OutlinedButton(
                onClick = { entries.add(QuickPainEntry(startAtIso = migraineStartAtIso)) },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.35f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple)
            ) { Text(t("+ Add another entry"), fontWeight = FontWeight.SemiBold) }

            if (error != null) {
                Text(error!!, color = Color(0xFFE57373), style = MaterialTheme.typography.bodySmall)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(
                    onClick = { navController.popBackStack() },
                    border = BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple)
                ) { Text(t("Cancel")) }
                Button(
                    enabled = !saving,
                    onClick = {
                        val rows = entries.flatMap { e ->
                            e.locations.map { loc ->
                                SupabaseDbService.PainPointInsert(
                                    migraineId = migraineId, locationId = loc,
                                    severity = e.severity,
                                    startAt = e.startAtIso ?: migraineStartAtIso ?: Instant.now().toString()
                                )
                            }
                        }
                        if (rows.isEmpty()) {
                            error = "Mark at least one location"
                            return@Button
                        }
                        saving = true; error = null
                        scope.launch {
                            val token = auth.accessToken
                            if (token.isNullOrBlank()) { error = "Not signed in"; saving = false; return@launch }
                            withContext(Dispatchers.IO) {
                                try {
                                    // Append, never replacePainPoints: the attack-start entries must survive.
                                    db.insertPainPoints(token, rows)
                                    val migraine = db.getMigraineById(token, migraineId)
                                    val union = ((migraine.painLocations ?: emptyList()) + rows.map { it.locationId }).distinct()
                                    db.updateMigraine(token, migraineId, painLocations = union)
                                } catch (e: Exception) {
                                    error = "Save failed: ${e.message}"
                                }
                            }
                            saving = false
                            if (error == null) {
                                quickAddFinish(ctx, logVm, auth.accessToken ?: "", migraineId)
                                navController.popBackStack()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(t("Save"))
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickPainEntryCard(
    entry: QuickPainEntry,
    index: Int,
    canRemove: Boolean,
    onRemove: () -> Unit,
) {
    var showBack by rememberSaveable(entry.id) { mutableStateOf(false) }

    BaseCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (index == 0) t("New pain entry") else t("Pain entry %s", index + 1),
                color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.weight(1f))
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Outlined.Close, contentDescription = t("Remove entry"), tint = AppTheme.AccentPink, modifier = Modifier.size(18.dp))
                }
            }
        }

        AppDateTimePicker(
            label = entry.startAtIso?.let { "When: ${formatQuickAddIso(it)}" } ?: t("Set time")
        ) { entry.startAtIso = it }
        Spacer(Modifier.height(8.dp))

        val sev = entry.severity
        val sevColor = lerp(AppTheme.AccentPurple, AppTheme.AccentPink, (sev - 1) / 9f)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Severity"), color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.weight(1f))
            Text("$sev", color = sevColor, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
            Text(" / 10", color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.titleSmall)
        }

        Slider(
            value = sev.toFloat(),
            onValueChange = { v -> entry.severity = v.toInt() },
            valueRange = 1f..10f,
            steps = 8,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = AppTheme.AccentPurple,
                activeTrackColor = AppTheme.AccentPurple,
                inactiveTrackColor = AppTheme.TrackColor
            )
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(t("Mild"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            Text(t("Severe"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(8.dp))

        Text(t("Where did you feel the pain?"), color = AppTheme.TitleColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
        Text(
            t("Tap the dots on the head to mark where it hurts"),
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.bodySmall
        )

        if (entry.locations.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                (if (entry.locations.size == 1) t("1 area selected") else t("%s areas selected", entry.locations.size)),
                color = AppTheme.AccentPurple,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                entry.locations.forEach { id ->
                    val label = ALL_PAIN_POINTS_MAP[id] ?: id
                    AssistChip(
                        onClick = { entry.locations = entry.locations - id },
                        label = { Text(t(label), fontSize = 11.sp) },
                        trailingIcon = { Text("✕", fontSize = 10.sp, color = AppTheme.AccentPink) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = AppTheme.AccentPurple.copy(alpha = 0.20f),
                            labelColor = Color.White
                        ),
                        border = AssistChipDefaults.assistChipBorder(
                            enabled = true,
                            borderColor = AppTheme.AccentPurple.copy(alpha = 0.35f)
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            QuickAddToggleButton(t("Front"), !showBack, { showBack = false }, Modifier.weight(1f))
            QuickAddToggleButton(t("Back"), showBack, { showBack = true }, Modifier.weight(1f))
        }

        Spacer(Modifier.height(12.dp))

        PainPointOverlay(
            imageRes = if (showBack) R.drawable.painpointsback else R.drawable.painpoints,
            points = if (showBack) BACK_PAIN_POINTS else FRONT_PAIN_POINTS,
            selected = entry.locations,
            onToggle = { id ->
                entry.locations = if (id in entry.locations) entry.locations - id else entry.locations + id
            }
        )
    }
}

@Composable
private fun QuickAddToggleButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        color = if (selected) AppTheme.AccentPurple else Color.Transparent,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier
                .padding(vertical = 10.dp)
                .fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color.White else AppTheme.SubtleTextColor,
            fontSize = 14.sp
        )
    }
}

// ── Standalone symptom / postdrome add ─────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickAddSymptomScreen(
    navController: NavController,
    authVm: AuthViewModel,
    logVm: LogViewModel,
    symptomVm: SymptomViewModel,
    migraineId: String,
    postdrome: Boolean,
) {
    val ctx = LocalContext.current
    val auth by authVm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val db = remember { SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) }
    val scroll = rememberScrollState()

    val pool by (if (postdrome) symptomVm.postdrome else symptomVm.accompanying).collectAsState()
    LaunchedEffect(auth.accessToken) { auth.accessToken?.let { symptomVm.loadAll(it) } }

    val selected = remember { mutableStateListOf<String>() }
    val severities = remember { mutableStateMapOf<String, String>() }
    val times = remember { mutableStateMapOf<String, String>() }
    var sheetLabel by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ScrollFadeContainer(scrollState = scroll) { scrollState ->
        ScrollableScreenContent(scrollState = scrollState, logoRevealHeight = 0.dp) {

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Outlined.Close, contentDescription = t("Close"), tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            HeroCard {
                Text(
                    if (postdrome) t("Add postdrome") else t("Add symptom"),
                    color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    if (postdrome) t("What lingered after the attack? Tap one to rate it.")
                    else t("What did you feel? Tap one to rate it."),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            BaseCard {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    pool.forEach { item ->
                        SymptomButton(
                            label = item.label,
                            isSelected = item.label in selected,
                            iconKey = item.iconKey,
                            onClick = {
                                if (item.label !in selected) selected.add(item.label)
                                sheetLabel = item.label
                            }
                        )
                    }
                }
                if (pool.isEmpty()) {
                    Text(
                        t("Nothing in your list yet."),
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (error != null) {
                Text(error!!, color = Color(0xFFE57373), style = MaterialTheme.typography.bodySmall)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(
                    onClick = { navController.popBackStack() },
                    border = BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple)
                ) { Text(t("Cancel")) }
                Button(
                    enabled = !saving && selected.isNotEmpty(),
                    onClick = {
                        saving = true; error = null
                        scope.launch {
                            val token = auth.accessToken
                            if (token.isNullOrBlank()) { error = "Not signed in"; saving = false; return@launch }
                            withContext(Dispatchers.IO) {
                                try {
                                    if (postdrome) {
                                        // Check-in path: postdrome rows are inserted directly;
                                        // the sync trigger only manages phase='active' rows.
                                        selected.forEach { label ->
                                            db.insertMigraineSymptom(token, migraineId, label, phase = "postdrome")
                                        }
                                    } else {
                                        // Active symptoms are never inserted directly: append to
                                        // migraines.type and the sync trigger materialises the rows.
                                        val migraine = db.getMigraineById(token, migraineId)
                                        val existingTypes = migraine.type
                                            ?.split(", ")?.map { it.trim() }
                                            ?.filter { it.isNotBlank() && it != "Migraine" } ?: emptyList()
                                        val newSymptoms = selected.filter { s -> existingTypes.none { it.equals(s, ignoreCase = true) } }
                                        if (newSymptoms.isNotEmpty()) {
                                            db.updateMigraine(token, migraineId, type = (existingTypes + newSymptoms).joinToString(", "))
                                        }
                                    }
                                    // Severity/time land as an update after the rows exist,
                                    // same contract as the wizard.
                                    selected.forEach { label ->
                                        val sev = severities[label]
                                        val at = times[label]
                                        if (sev != null || at != null) {
                                            runCatching { db.setSymptomDetail(token, migraineId, label, sev, at) }
                                        }
                                    }
                                } catch (e: Exception) {
                                    error = "Save failed: ${e.message}"
                                }
                            }
                            saving = false
                            if (error == null) {
                                quickAddFinish(ctx, logVm, auth.accessToken ?: "", migraineId)
                                navController.popBackStack()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(t("Save"))
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    sheetLabel?.let { label ->
        SymptomSeveritySheet(
            label = label,
            selected = severities[label],
            time = times[label],
            onSelect = { sev ->
                if (sev == null) severities.remove(label) else severities[label] = sev
                sheetLabel = null
            },
            onSetTime = { at -> if (at == null) times.remove(label) else times[label] = at },
            onRemove = {
                selected.remove(label)
                severities.remove(label)
                times.remove(label)
                sheetLabel = null
            },
            onDismiss = { sheetLabel = null }
        )
    }
}

// ── Aura add: the shared sheet, presented over the journal ─────

@Composable
fun JournalAuraAddSheet(
    authVm: AuthViewModel,
    logVm: LogViewModel,
    migraineId: String,
    migraineStartAtIso: String?,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val auth by authVm.state.collectAsState()
    val scope = rememberCoroutineScope()
    val db = remember { SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY) }

    var prev by remember { mutableStateOf<List<AuraPrevEntry>>(emptyList()) }
    // Moments staged through "It moved — add another" before the final save.
    val staged = remember { mutableStateListOf<Triple<List<String>, Int?, String?>>() }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(migraineId) {
        val token = auth.accessToken ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            prev = runCatching {
                db.getAuraZones(token, migraineId)
                    .groupBy { it.startAt }
                    .map { (at, rows) ->
                        AuraPrevEntry(
                            startAtIso = at,
                            durationMinutes = rows.firstNotNullOfOrNull { it.durationMinutes },
                            zones = rows.map { it.zone }
                        )
                    }
            }.getOrDefault(emptyList())
        }
    }

    fun save(finalZones: List<String>, finalDuration: Int?, finalMomentIso: String?) {
        if (saving) return
        val moments = staged.toList() + if (finalZones.isNotEmpty()) listOf(Triple(finalZones, finalDuration, finalMomentIso)) else emptyList()
        if (moments.isEmpty()) { onClose(); return }
        saving = true
        scope.launch {
            val token = auth.accessToken
            if (token.isNullOrBlank()) { saving = false; onClose(); return@launch }
            withContext(Dispatchers.IO) {
                runCatching {
                    val rows = moments.flatMap { (zones, dur, at) ->
                        zones.map { zone ->
                            SupabaseDbService.AuraZoneInsert(
                                migraineId = migraineId, zone = zone,
                                startAt = at ?: migraineStartAtIso ?: Instant.now().toString(),
                                durationMinutes = dur
                            )
                        }
                    }
                    // Append-only: the zones logged at attack start must survive.
                    db.insertAuraZones(token, rows)
                    val migraine = db.getMigraineById(token, migraineId)
                    val union = ((migraine.auraLocations ?: emptyList()) + rows.map { it.zone }).distinct()
                    val added = moments.sumOf { it.second ?: 0 }
                    val total = ((migraine.auraDurationMinutes ?: 0) + added).takeIf { it > 0 }
                    db.updateMigraine(
                        token, migraineId,
                        setAura = true,
                        auraLocations = union,
                        auraDurationMinutes = total ?: migraine.auraDurationMinutes
                    )
                }
            }
            saving = false
            quickAddFinish(ctx, logVm, auth.accessToken ?: "", migraineId)
            onClose()
        }
    }

    AuraDetailSheet(
        onSave = { zones, dur, momentIso -> save(zones, dur, momentIso) },
        onDismiss = onClose,
        previousEntries = prev + staged.map { (zones, dur, at) -> AuraPrevEntry(at, dur, zones) },
        onAddMoment = { zones, dur, momentIso -> staged.add(Triple(zones, dur, momentIso)) }
    )
}

private fun formatQuickAddIso(iso: String): String = try {
    val odt = runCatching { java.time.OffsetDateTime.parse(iso) }.getOrNull()
    val ldt = odt?.toLocalDateTime() ?: java.time.LocalDateTime.parse(iso)
    ldt.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy HH:mm"))
} catch (_: Exception) { iso }
