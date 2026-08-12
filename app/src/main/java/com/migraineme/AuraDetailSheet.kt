package com.migraineme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Aura zone model ────────────────────────────────────────────
// Zone ids are "<eye>_<cell>", e.g. "left_top_left", "right_bottom_right",
// stored in migraines.aura_locations (text[]).

object AuraZones {
    val EYES = listOf("left" to "Left eye", "right" to "Right eye")

    // 3x3 grid per eye, row-major — this drives both the log sheet and the
    // Insights heat-map, so the two can never drift apart.
    val ROWS: List<List<String>> = listOf(
        listOf("top_left", "top_center", "top_right"),
        listOf("center_left", "center_center", "center_right"),
        listOf("bottom_left", "bottom_center", "bottom_right"),
    )

    private val CELL_LABELS = mapOf(
        "top_left" to "top left",
        "top_center" to "top center",
        "top_right" to "top right",
        "center_left" to "center left",
        "center_center" to "center",
        "center_right" to "center right",
        "bottom_left" to "bottom left",
        "bottom_center" to "bottom center",
        "bottom_right" to "bottom right",
    )

    val ALL_LABELS: Map<String, String> by lazy {
        EYES.flatMap { (eyeId, eyeLabel) ->
            ROWS.flatten().map { cellId -> "${eyeId}_$cellId" to "$eyeLabel · ${CELL_LABELS[cellId] ?: cellId}" }
        }.toMap()
    }

    /** Display label, composed from translated pieces at call time so a
     *  language switch is picked up. The id (and ALL_LABELS keys) stay
     *  English — they are the stored values. */
    fun label(id: String): String {
        for ((eyeId, eyeLabel) in EYES) {
            if (id.startsWith("${eyeId}_")) {
                val cell = CELL_LABELS[id.removePrefix("${eyeId}_")] ?: return id
                return "${tSync(eyeLabel)} \u00b7 ${tSync(cell)}"
            }
        }
        return id
    }
}

/** True for symptom labels that should open the aura detail sheet when tapped. */
fun isAuraSymptomLabel(label: String?): Boolean =
    label?.trim()?.lowercase()?.contains("aura") == true

val AURA_DURATION_CHOICES = listOf(5, 10, 15, 20, 30, 45, 60)

fun formatAuraDuration(minutes: Int): String =
    if (minutes >= 60 && minutes % 60 == 0) "${minutes / 60}h" else "${minutes}m"

/**
 * Aura duration bands. Minutes, not the hour bands attack duration uses.
 * The cuts follow ICHD-3, which treats a typical aura as 5-60 minutes and
 * anything over an hour as worth a clinician's attention — so "60m+" has to
 * stay its own band rather than being averaged away.
 */
val AURA_DURATION_BUCKETS = listOf("< 5m", "5-10m", "10-20m", "20-30m", "30-60m", "60m+")

fun auraDurationBucket(minutes: Int): String = when {
    minutes < 5 -> "< 5m"
    minutes < 10 -> "5-10m"
    minutes < 20 -> "10-20m"
    minutes < 30 -> "20-30m"
    minutes < 60 -> "30-60m"
    else -> "60m+"
}

/** Display helper for aura moment timestamps. Same offset-ignoring read as the
 *  pain entries' formatIsoDdMmYyHm, so picker-set and shown times agree. */
internal fun formatAuraMomentIso(iso: String): String = try {
    val odt = runCatching { java.time.OffsetDateTime.parse(iso) }.getOrNull()
    val ldt = odt?.toLocalDateTime() ?: java.time.LocalDateTime.parse(iso)
    ldt.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy HH:mm"))
} catch (_: Exception) {
    iso
}

/** One already-recorded aura moment, shown as an "Earlier" row. */
data class AuraPrevEntry(
    val startAtIso: String?,
    val durationMinutes: Int?,
    val zones: List<String>,
)

// ── The sheet ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AuraDetailSheet(
    initialZones: List<String> = emptyList(),
    initialDurationMinutes: Int? = null,
    /** momentAtIso is the user-chosen time for this stage of the aura, or null
     *  for "now". Only offered when the caller stages moments (onAddMoment). */
    onSave: (zones: List<String>, durationMinutes: Int?, momentAtIso: String?) -> Unit,
    onRemove: (() -> Unit)? = null,
    onDismiss: () -> Unit,
    /** Zones already recorded at an earlier moment. When the user adds a
     *  second entry the sheet reopens empty, and these are what the report
     *  stages as the earlier stage of the aura. */
    previousEntries: List<AuraPrevEntry> = emptyList(),
    /** Called instead of onSave when the user says the aura moved: hands back
     *  this moment's zones so the caller can append an entry. */
    onAddMoment: ((zones: List<String>, durationMinutes: Int?, momentAtIso: String?) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val zones = remember { mutableStateListOf<String>().apply { addAll(initialZones) } }
    var duration by remember { mutableStateOf(initialDurationMinutes) }
    var momentAtIso by remember { mutableStateOf<String?>(null) }
    var customMode by remember { mutableStateOf(initialDurationMinutes != null && initialDurationMinutes !in AURA_DURATION_CHOICES) }
    var customText by remember {
        mutableStateOf(if (initialDurationMinutes != null && initialDurationMinutes !in AURA_DURATION_CHOICES) initialDurationMinutes.toString() else "")
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppTheme.BaseCardContainer.copy(alpha = 0.96f),
        contentColor = Color.White,
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .width(32.dp)
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                t("Aura"),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                t("Where in your vision did it appear? Tap the areas as you saw them through your own eyes."),
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            // Two eyes side by side
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                AuraZones.EYES.forEach { (eyeId, eyeLabel) ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        AuraEyeGrid(
                            eyeId = eyeId,
                            selected = zones,
                            onToggle = { id -> if (id in zones) zones.remove(id) else zones.add(id) }
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            eyeLabel,
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            if (previousEntries.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                previousEntries.forEachIndexed { i, prev ->
                    val atLabel = prev.startAtIso?.let { " · ${formatAuraMomentIso(it)}" } ?: ""
                    val durLabel = prev.durationMinutes?.let { " · ${formatAuraDuration(it)}" } ?: ""
                    Text(
                        t("Earlier (%1\$s)%2\$s%3\$s: %4\$s", i + 1, atLabel, durLabel, prev.zones.joinToString(", ") { AuraZones.label(it) }),
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            if (zones.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    (if (zones.size == 1) t("1 area selected") else t("%s areas selected", zones.size)),
                    color = AppTheme.AccentPurple,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                )
            }

            if (onAddMoment != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    t("When did it appear?"),
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                AppDateTimePicker(
                    label = momentAtIso?.let { "When: ${formatAuraMomentIso(it)}" } ?: t("Now (tap to change)")
                ) { momentAtIso = it }
            }

            Spacer(Modifier.height(18.dp))

            // Duration: per stage once the user starts staging moments; the
            // attack's overall duration is the sum of the stages.
            Text(
                if (previousEntries.isNotEmpty()) t("How long did this stage last?")
                else t("How long did the aura last?"),
                color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Unknown is the default: duration stays null and is excluded
                // from every duration statistic until the user picks a value.
                DurationChip(
                    text = t("Don't know yet"),
                    selected = !customMode && duration == null,
                    onClick = {
                        customMode = false
                        customText = ""
                        duration = null
                    }
                )
                AURA_DURATION_CHOICES.forEach { mins ->
                    val isSel = !customMode && duration == mins
                    DurationChip(
                        text = formatAuraDuration(mins),
                        selected = isSel,
                        onClick = {
                            customMode = false
                            duration = if (isSel) null else mins
                        }
                    )
                }
                DurationChip(
                    text = t("Custom"),
                    selected = customMode,
                    onClick = {
                        customMode = !customMode
                        if (!customMode) duration = null
                        else duration = customText.toIntOrNull()
                    }
                )
            }
            if (customMode) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = customText,
                    onValueChange = { v ->
                        customText = v.filter { it.isDigit() }.take(4)
                        duration = customText.toIntOrNull()
                    },
                    label = { Text(t("Minutes")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppTheme.AccentPurple,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedLabelColor = AppTheme.AccentPurple,
                        unfocusedLabelColor = AppTheme.SubtleTextColor,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Keeps the aura on the log, just without the detail.
                if (onAddMoment != null && zones.isNotEmpty()) {
                    TextButton(onClick = {
                        onAddMoment(zones.toList(), duration, momentAtIso)
                        momentAtIso = null
                        duration = null
                        customMode = false
                        customText = ""
                    }) {
                        Text(t("It moved — add another"), color = AppTheme.AccentPurple,
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
                TextButton(onClick = { onSave(emptyList(), null, null) }) {
                    Text(t("Skip for now"), color = AppTheme.SubtleTextColor)
                }
                Button(
                    onClick = { onSave(zones.toList(), duration, momentAtIso) },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                ) { Text(t("Save")) }
            }

            if (onRemove != null) {
                TextButton(onClick = onRemove) {
                    Text(
                        t("Remove aura from this log"),
                        color = AppTheme.AccentPink.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── One eye: drawn eye behind a 3x3 tap grid ───────────────────

@Composable
private fun AuraEyeGrid(
    eyeId: String,
    selected: List<String>,
    onToggle: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.25f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
    ) {
        // Decorative eye behind the grid
        Canvas(modifier = Modifier.fillMaxSize().padding(10.dp)) { drawAuraEye() }

        Column(Modifier.fillMaxSize().padding(4.dp)) {
            AuraZones.ROWS.forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    row.forEach { cell ->
                        val id = "${eyeId}_$cell"
                        val isSel = id in selected
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(2.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(
                                    if (isSel)
                                        Brush.radialGradient(
                                            colors = listOf(
                                                Color(0xFFCE9FFF).copy(alpha = 0.50f),
                                                Color(0xFFAA70EE).copy(alpha = 0.30f)
                                            )
                                        )
                                    else Brush.radialGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.015f),
                                            Color.White.copy(alpha = 0.015f)
                                        )
                                    )
                                )
                                .border(
                                    width = if (isSel) 1.5.dp else 1.dp,
                                    color = if (isSel) AppTheme.AccentPurple.copy(alpha = 0.8f)
                                    else Color.White.copy(alpha = 0.10f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onToggle(id) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSel) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFE0CCFF).copy(alpha = 0.9f))
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The eye that sits behind a zone grid — shared by the log sheet and the Insights heat-map. */
private fun DrawScope.drawAuraEye() {
    val w = size.width
    val h = size.height
    val midY = h / 2f

    val lid = Path().apply {
        moveTo(0f, midY)
        cubicTo(w * 0.25f, midY - h * 0.42f, w * 0.75f, midY - h * 0.42f, w, midY)
        cubicTo(w * 0.75f, midY + h * 0.42f, w * 0.25f, midY + h * 0.42f, 0f, midY)
        close()
    }
    drawPath(lid, color = Color.White.copy(alpha = 0.18f), style = Stroke(width = 3f))

    val irisR = h * 0.24f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                AppTheme.AccentPurple.copy(alpha = 0.30f),
                AppTheme.AccentPurple.copy(alpha = 0.10f)
            ),
            center = Offset(w / 2f, midY),
            radius = irisR
        ),
        radius = irisR,
        center = Offset(w / 2f, midY)
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.16f),
        radius = irisR,
        center = Offset(w / 2f, midY),
        style = Stroke(width = 2f)
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.20f),
        radius = irisR * 0.38f,
        center = Offset(w / 2f, midY)
    )
}

// ── Aura heat-map (Insights): both eyes, zone frequency as fill ─

@Composable
fun AuraHeatMap(
    auraZoneCounts: List<Pair<String, Int>>,
    totalAuraAttacks: Int,
    modifier: Modifier = Modifier,
    /** A single attack has no meaningful percentage — every zone it hit is
     *  100% of one — so the caller can ask for the zones alone. */
    showPercentages: Boolean = true,
) {
    val countsMap = auraZoneCounts.toMap()
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AuraZones.EYES.forEach { (eyeId, eyeLabel) ->
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.25f)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) { drawAuraEye() }

                    Column(Modifier.fillMaxSize().padding(3.dp)) {
                        AuraZones.ROWS.forEach { row ->
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                row.forEach { cell ->
                                    val count = countsMap["${eyeId}_$cell"] ?: 0
                                    // With staged entries the caller passes a
                                    // recency weight rather than a count, so
                                    // the later a zone appeared the deeper it
                                    // reads — same rule as the pain dots.
                                    val pct = if (totalAuraAttacks > 0) (count * 100 / totalAuraAttacks) else 0
                                    // Only hit zones are drawn — empty ones leave the eye bare.
                                    // Alpha capped so the eye still reads through a filled zone.
                                    val alpha = (pct / 100f).coerceIn(0.20f, 0.62f)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            // Without percentages this is a
                                            // marker, not a heat block, so it
                                            // sits in more and reads smaller.
                                            .padding(if (showPercentages) 1.5.dp else 6.dp)
                                            .then(
                                                if (pct > 0) Modifier
                                                    .clip(RoundedCornerShape(if (showPercentages) 5.dp else 8.dp))
                                                    .background(AppTheme.AccentPurple.copy(alpha = alpha))
                                                else Modifier
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (pct > 0 && showPercentages) {
                                            Text(
                                                "$pct%",
                                                color = Color.White,
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(t(eyeLabel), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun DurationChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) AppTheme.AccentPurple.copy(alpha = 0.40f)
                else Color.White.copy(alpha = 0.06f)
            )
            .border(
                1.dp,
                if (selected) AppTheme.AccentPurple.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.12f),
                RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            color = if (selected) Color.White else AppTheme.BodyTextColor,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
