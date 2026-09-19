package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Side effects on a medicine or relief log: the shared Brainy tile picker
 * (same pool + tiles as the evening check-in's treatment side-effects page),
 * the severity sheet, the notes field, and the display helpers.
 *
 * One implementation for every form: wizard Medicines/Reliefs add + edit,
 * Quick Log Medicine/Relief, Edit Medicine/Relief, Journal edit, Paint the
 * Picture, and the evening check-in's medicine/relief item detail.
 *
 * Data contract: `side_effects` is a list of {label, severity}; the legacy
 * `side_effect_scale` column is always the max severity of that list, so every
 * reader of the scale keeps working.
 */

enum class SideEffectScale(val key: String, val display: String, val color: Color) {
    NONE("NONE", "None", Color(0xFF81C784)),
    SOFT("SOFT", "Soft", Color(0xFFFFB74D)),
    MODERATE("MODERATE", "Moderate", Color(0xFFFF8A65)),
    SEVERE("SEVERE", "Severe", Color(0xFFE57373));

    companion object {
        fun fromString(s: String?): SideEffectScale =
            entries.find { it.key.equals(s, ignoreCase = true) } ?: NONE
    }
}

/** One ticked side effect. [label] is the pool label (canonical English), [severity] is SOFT | MODERATE | SEVERE. */
@Serializable
data class SideEffectItem(
    val label: String,
    val severity: String = "SOFT",
) {
    companion object {
        /** Drops blanks and duplicates, and normalises the severity to SOFT/MODERATE/SEVERE. */
        fun clean(items: List<SideEffectItem>): List<SideEffectItem> =
            items.filter { it.label.isNotBlank() }
                .distinctBy { it.label }
                .map { it.copy(severity = SideEffectScale.fromString(it.severity).takeIf { s -> s != SideEffectScale.NONE }?.key ?: "SOFT") }

        /** Max severity of [items], "NONE" when empty. This is what goes in side_effect_scale. */
        fun maxScale(items: List<SideEffectItem>): String =
            clean(items).maxOfOrNull { SideEffectScale.fromString(it.severity).ordinal }
                ?.let { SideEffectScale.entries[it].key } ?: "NONE"

        /**
         * The scale to write next to [items]: the max of the items when there
         * are any, otherwise [fallback] (a legacy scale the user never touched,
         * or an AI/import scale that came without items), otherwise NONE.
         */
        fun scaleFor(items: List<SideEffectItem>, fallback: String?): String =
            if (clean(items).isNotEmpty()) maxScale(items) else (fallback ?: "NONE")

        fun toJsonArray(items: List<SideEffectItem>): JsonArray = buildJsonArray {
            clean(items).forEach { add(buildJsonObject { put("label", it.label); put("severity", it.severity) }) }
        }
    }
}

/** For rememberSaveable state holding the ticked items (stateSaver = SideEffectItemsSaver). */
val SideEffectItemsSaver: Saver<List<SideEffectItem>, Any> = listSaver(
    save = { items -> items.flatMap { listOf(it.label, it.severity) } },
    restore = { flat -> flat.chunked(2).filter { it.size == 2 }.map { SideEffectItem(it[0], it[1]) } },
)

/**
 * What a display prints after "Side effects:". Items when there are any,
 * e.g. "Drowsiness (soft), Nausea (moderate)"; a legacy row (scale but no
 * items) keeps the old text, e.g. "soft"; null when there is nothing to show.
 */
fun sideEffectsDisplayText(items: List<SideEffectItem>?, scale: String?): String? {
    val clean = SideEffectItem.clean(items.orEmpty())
    if (clean.isNotEmpty()) {
        return clean.joinToString(", ") { "${tSync(it.label)} (${tSync(SideEffectScale.fromString(it.severity).display).lowercase()})" }
    }
    val s = SideEffectScale.fromString(scale)
    return if (s == SideEffectScale.NONE) null else tSync(s.display).lowercase()
}

/** The whole display line: "Side effects: Drowsiness (soft), Nausea (moderate)", or null when there is nothing to show. */
fun sideEffectsLine(items: List<SideEffectItem>?, scale: String?): String? {
    val text = sideEffectsDisplayText(items, scale) ?: return null
    return tSync("Side effects: ") + text
}

/** The collapsed row's one-line summary: "None", "Drowsiness · soft, Nausea · moderate", or a legacy "Soft". */
private fun sideEffectsSummary(items: List<SideEffectItem>, scale: String): String {
    val clean = SideEffectItem.clean(items)
    if (clean.isNotEmpty()) {
        return clean.joinToString(", ") { "${tSync(it.label)} · ${tSync(SideEffectScale.fromString(it.severity).display).lowercase()}" }
    }
    return tSync(SideEffectScale.fromString(scale).display)
}

/**
 * The ONE shared side-effect pool (`user_treatment_side_effects` + favourites
 * in `treatment_side_effect_preferences`), used for treatments, medicines and
 * reliefs. The default list and the load-and-seed logic live here only; the
 * evening check-in and the picker below both go through [loadAndSeed].
 */
object TreatmentSideEffectPool {
    /** label, icon_key, category. icon_key values match SymptomIcons registered
     *  keys so the circles render proper glyphs (not 2-letter initials).
     *  Categories group them sensibly in the Manage screen. */
    val DEFAULTS: List<Triple<String, String, String>> = listOf(
        // Cognitive
        Triple("Brain fog",          "brainfog",   "Cognitive"),
        Triple("Memory trouble",     "brainfog",   "Cognitive"),
        Triple("Confusion",          "aura",       "Cognitive"),
        Triple("Better focus",       "brainfog",   "Cognitive"),
        Triple("Mental clarity",     "aura",       "Cognitive"),
        // Mood
        Triple("Mood shift",         "mood_crash", "Mood"),
        Triple("Irritability",       "burning",    "Mood"),
        Triple("Low mood",           "mood_crash", "Mood"),
        Triple("Calmer",             "mood_lift",  "Mood"),
        Triple("Less anxious",       "heart",      "Mood"),
        Triple("Better mood",        "mood_lift",  "Mood"),
        // Sleep
        Triple("Insomnia",           "moon",       "Sleep"),
        Triple("Drowsiness",         "moon",       "Sleep"),
        Triple("Vivid dreams",       "aura",       "Sleep"),
        Triple("Fatigue",            "fatigue",    "Sleep"),
        Triple("Better sleep",       "moon",       "Sleep"),
        Triple("More energy",        "fatigue",    "Sleep"),
        // Body
        Triple("Dizziness",          "dizziness",  "Body"),
        Triple("Tingling",           "tingling",   "Body"),
        Triple("Headache",           "dullache",   "Body"),
        Triple("Nausea",             "nausea",     "Body"),
        Triple("Dry mouth",          "droplet",    "Body"),
        Triple("Sweating",           "droplet",    "Body"),
        Triple("Tremor",             "throbbing",  "Body"),
        Triple("Muscle weakness",    "weakness",   "Body"),
        Triple("Heart palpitations", "heart",      "Body"),
        Triple("Blurred vision",     "blur",       "Body"),
        Triple("Light sensitivity",  "light",      "Body"),
        Triple("Sound sensitivity",  "sound",      "Body"),
        Triple("Loss of appetite",   "cross",      "Body"),
        Triple("Increased appetite", "cross",      "Body"),
        Triple("Constipation",       "cross",      "Body"),
        Triple("Less pain",          "dullache",   "Body"),
        Triple("Less nausea",        "nausea",     "Body"),
    )

    data class Loaded(
        val pool: List<SupabaseDbService.UserTreatmentSideEffectRow>,
        val favLabels: List<String>,
    )

    /** Last successful load, so a second form opens with tiles straight away. */
    @Volatile
    var cached: Loaded? = null
        private set

    // Two screens can ask at once (check-in + a picker); without this a legacy
    // account could be seeded twice.
    private val mutex = Mutex()

    /** Loads the pool + favourites. Seeds [DEFAULTS] first when the user's pool is empty. */
    suspend fun loadAndSeed(db: SupabaseDbService, token: String): Loaded = withContext(Dispatchers.IO) {
        mutex.withLock {
            var pool = db.getUserTreatmentSideEffects(token)
            if (pool.isEmpty()) {
                for ((label, iconKey, category) in DEFAULTS) {
                    runCatching { db.insertTreatmentSideEffectToPool(token, label, category = category, iconKey = iconKey) }
                }
                pool = db.getUserTreatmentSideEffects(token)
            }
            val prefs = runCatching { db.getTreatmentSideEffectPrefs(token) }.getOrDefault(emptyList())
            val favs = prefs.filter { it.status == "frequent" }
                .mapNotNull { it.sideEffect?.label ?: pool.firstOrNull { p -> p.id == it.sideEffectId }?.label }
            Loaded(pool, favs).also { cached = it }
        }
    }
}

/**
 * "Any side effects?" for a medicine or relief log.
 *
 * Collapsed by default with a one-line summary; expanded it shows the Brainy
 * tile grid from the shared pool (favourites first, then by category). Tapping
 * a tile opens [SideEffectSeveritySheet]. The notes field stays below.
 *
 * The caller holds three pieces of state: the scale, the items and the notes.
 * Whenever the items change this also reports the new scale (max of the items,
 * NONE when the last one is removed), so a caller that never sees a change
 * keeps whatever legacy scale the row already had.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SideEffectPicker(
    sideEffectScale: String,
    onScaleChange: (String) -> Unit,
    sideEffects: List<SideEffectItem>,
    onSideEffectsChange: (List<SideEffectItem>) -> Unit,
    sideEffectNotes: String,
    onNotesChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val speechLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                val updated = if (sideEffectNotes.isBlank()) spoken else "$sideEffectNotes, $spoken"
                onNotesChange(updated)
            }
        }
    }

    fun launchVoice() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Describe side effects…")
        }
        try { speechLauncher.launch(intent) } catch (_: Exception) {
            android.widget.Toast.makeText(context, tSync("Voice input not available"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    var expanded by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(TreatmentSideEffectPool.cached) }
    var loadFailed by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableStateOf(0) }
    var sheetFor by remember { mutableStateOf<String?>(null) }

    // The pool is only needed once the row is opened, so a collapsed row costs nothing.
    LaunchedEffect(expanded, loadAttempt) {
        if (!expanded) return@LaunchedEffect
        loadFailed = false
        val token = runCatching { SessionStore.getValidAccessToken(context) }.getOrNull()
        val fresh = token?.let {
            runCatching {
                TreatmentSideEffectPool.loadAndSeed(SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY), it)
            }.getOrNull()
        }
        if (fresh != null) loaded = fresh else if (loaded == null) loadFailed = true
    }

    fun applyItems(next: List<SideEffectItem>) {
        onSideEffectsChange(next)
        onScaleChange(SideEffectItem.maxScale(next))
    }

    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { expanded = !expanded }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(t("Any side effects?"), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            val summaryScale = SideEffectScale.fromString(SideEffectItem.scaleFor(sideEffects, sideEffectScale))
            Text(
                sideEffectsSummary(sideEffects, sideEffectScale),
                color = if (summaryScale == SideEffectScale.NONE) AppTheme.BodyTextColor else summaryScale.color,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = AppTheme.SubtleTextColor,
                modifier = Modifier.size(20.dp),
            )
        }

        if (expanded) {
            Spacer(Modifier.height(8.dp))
            // A scale with no items (legacy row, or an AI-read scale) has no tile
            // to untick, so it gets its own way back to "None".
            if (sideEffects.isEmpty() && SideEffectScale.fromString(sideEffectScale) != SideEffectScale.NONE) {
                Text(
                    t("Clear"),
                    color = AppTheme.AccentPink.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onScaleChange("NONE") }.padding(vertical = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            val data = loaded
            if (data == null) {
                Text(
                    if (loadFailed) t("Something went wrong. Please try again.") else t("Loading…"),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = if (loadFailed) Modifier.fillMaxWidth().clickable { loadAttempt++ } else Modifier.fillMaxWidth(),
                )
            } else {
                SideEffectTiles(
                    data = data,
                    pickedLabels = sideEffects.map { it.label },
                    onTile = { sheetFor = it },
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        sheetFor?.let { label ->
            val current = sideEffects.firstOrNull { it.label == label }
            SideEffectSeveritySheet(
                label = label,
                selected = current?.severity,
                onSelect = { severity ->
                    // Re-rating keeps the item where it was; a new one goes last.
                    applyItems(
                        if (current != null) sideEffects.map { if (it.label == label) it.copy(severity = severity) else it }
                        else sideEffects + SideEffectItem(label, severity)
                    )
                    sheetFor = null
                },
                onRemove = if (current == null) null else ({
                    applyItems(sideEffects.filter { it.label != label })
                    sheetFor = null
                }),
                onDismiss = { sheetFor = null },
            )
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = sideEffectNotes,
            onValueChange = { if (it.length <= 400) onNotesChange(it) else onNotesChange(it.take(400)) },
            label = { Text(t("Notes"), color = AppTheme.SubtleTextColor) },
            placeholder = { Text(t("e.g. drowsiness, nausea, brain fog…"), color = AppTheme.SubtleTextColor.copy(alpha = 0.5f)) },
            supportingText = {
                Text(
                    "${sideEffectNotes.length}/400",
                    color = if (sideEffectNotes.length >= 400) AppTheme.AccentPink else AppTheme.SubtleTextColor,
                )
            },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = AppTheme.AccentPurple,
                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                cursorColor = AppTheme.AccentPurple,
                focusedLabelColor = AppTheme.AccentPurple,
                unfocusedLabelColor = AppTheme.SubtleTextColor,
            ),
            trailingIcon = {
                IconButton(onClick = { launchVoice() }) {
                    Icon(
                        Icons.Outlined.Mic,
                        contentDescription = t("Voice input"),
                        tint = AppTheme.AccentPurple,
                        modifier = Modifier.size(20.dp)
                    )
                }
            },
            minLines = 1,
            maxLines = 3,
        )
    }
}


/**
 * The Brainy tiles of the side-effect pool: favourites first, then by category.
 * Shared by the medicine/relief picker (a tap opens the severity sheet) and the
 * treatment note (a tap ticks the label), so every side-effect surface shows the
 * same Brainies in the same order.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SideEffectTiles(
    data: TreatmentSideEffectPool.Loaded,
    pickedLabels: List<String>,
    onTile: (String) -> Unit,
) {
    // Ticked labels that are no longer in the pool still get a tile,
    // so they can always be re-rated or removed.
    val poolLabels = data.pool.map { it.label }.toSet()
    val tiles = data.pool.map { SelectableItem(it.label, it.iconKey, it.label in data.favLabels, it.category) } +
        pickedLabels.filter { it !in poolLabels }.map { SelectableItem(it) }
    val favourites = tiles.filter { it.isFavourite }
    val others = tiles.filter { !it.isFavourite }

    @Composable
    fun Tile(item: SelectableItem) {
        // A ticked tile looks exactly like a ticked symptom: plain purple, no severity
        // ring or dot (Jordy 2026-09-19). The level reads in words in the summary row.
        CheckInCircle(
            item.label,
            SymptomIcons.forKey(item.iconKey) ?: SymptomIcons.forKey(item.category) ?: SymptomIcons.forKey(item.label.lowercase()),
            item.label in pickedLabels,
            AppTheme.AccentPurple,
            false,
        ) { onTile(item.label) }
    }

    if (favourites.isNotEmpty()) {
        Text(t("Favourites"), color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            favourites.forEach { Tile(it) }
        }
        Spacer(Modifier.height(16.dp))
    }
    val grouped = others.groupBy { it.category?.takeIf { c -> c.isNotBlank() } ?: "Other" }
    val orderedCats = grouped.keys.sortedWith(compareBy({ it == "Other" }, { it }))
    orderedCats.forEachIndexed { idx, cat ->
        if (idx > 0) Spacer(Modifier.height(12.dp))
        Text(t(cat), color = AppTheme.TitleColor, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            grouped.getValue(cat).forEach { Tile(it) }
        }
    }
}

/**
 * Label-only Brainy picker for a TREATMENT's side effects (the note on the
 * treatment page). Same pool and tiles as everywhere else; a tap ticks or
 * unticks, because treatment_side_effect_logs stores labels, not levels.
 */
@Composable
fun TreatmentSideEffectTilePicker(
    selected: List<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var loaded by remember { mutableStateOf(TreatmentSideEffectPool.cached) }
    var loadFailed by remember { mutableStateOf(false) }
    var loadAttempt by remember { mutableStateOf(0) }
    LaunchedEffect(loadAttempt) {
        loadFailed = false
        val token = runCatching { SessionStore.getValidAccessToken(context) }.getOrNull()
        val fresh = token?.let {
            runCatching {
                TreatmentSideEffectPool.loadAndSeed(SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY), it)
            }.getOrNull()
        }
        if (fresh != null) loaded = fresh else if (loaded == null) loadFailed = true
    }
    Column(modifier) {
        val data = loaded
        if (data == null) {
            Text(
                if (loadFailed) t("Something went wrong. Please try again.") else t("Loading…"),
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodySmall,
                modifier = if (loadFailed) Modifier.fillMaxWidth().clickable { loadAttempt++ } else Modifier.fillMaxWidth(),
            )
        } else {
            SideEffectTiles(data = data, pickedLabels = selected, onTile = onToggle)
        }
    }
}


/**
 * Opens when a side-effect tile is tapped. Same pattern as SymptomSeveritySheet
 * (LogHomeScreen): three levels, picking one closes the sheet, and an already
 * ticked side effect also gets a "Remove … from this log" action. No time picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SideEffectSeveritySheet(
    label: String,
    selected: String?,
    onSelect: (String) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val levels = listOf(
        SideEffectScale.SOFT to "Noticeable but I could carry on",
        SideEffectScale.MODERATE to "Hard to ignore, it got in the way",
        SideEffectScale.SEVERE to "Overwhelming, I couldn't function",
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppTheme.DialogContainer.copy(alpha = 0.96f),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(t(label),
                color = Color.White,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                t("How bad was it?"),
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(4.dp))

            levels.forEach { (scale, blurb) ->
                val isOn = SideEffectScale.fromString(selected) == scale && selected != null
                // Same row as SymptomSeveritySheet: title + one line, no dot.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isOn) AppTheme.AccentPurple else Color.White.copy(alpha = 0.06f))
                        .border(
                            1.dp,
                            if (isOn) Color.Transparent else AppTheme.AccentPurple.copy(alpha = 0.25f),
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelect(scale.key) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        t(scale.display),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        t(blurb),
                        color = if (isOn) Color.White.copy(alpha = 0.85f) else AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (onRemove != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    t("Remove %s from this log", t(label)),
                    color = AppTheme.AccentPink.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().clickable { onRemove() },
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
