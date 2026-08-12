package com.migraineme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

// ── Pain point data model ──────────────────────────────────────
data class PainPoint(
    val id: String,
    val label: String,
    val xPct: Float,   // 0..1 percentage position on image
    val yPct: Float,
    val view: PainView  // which view(s) this point appears on
)

enum class PainView { FRONT, BACK, BOTH }

// ── All pain points ────────────────────────────────────────────
val FRONT_PAIN_POINTS = listOf(
    PainPoint("vertex",          "Top of Head",       0.496f, 0.190f, PainView.BOTH),
    PainPoint("forehead_center", "Forehead Center",   0.499f, 0.257f, PainView.FRONT),
    PainPoint("forehead_left",   "Forehead Left",     0.341f, 0.257f, PainView.FRONT),
    PainPoint("forehead_right",  "Forehead Right",    0.654f, 0.253f, PainView.FRONT),
    PainPoint("brow_left",       "Left Brow",         0.386f, 0.312f, PainView.FRONT),
    PainPoint("brow_right",      "Right Brow",        0.624f, 0.315f, PainView.FRONT),
    PainPoint("temple_left",     "Left Temple",       0.241f, 0.332f, PainView.FRONT),
    PainPoint("temple_right",    "Right Temple",      0.756f, 0.338f, PainView.FRONT),
    PainPoint("eye_left",        "Left Eye",          0.354f, 0.365f, PainView.FRONT),
    PainPoint("eye_right",       "Right Eye",         0.656f, 0.365f, PainView.FRONT),
    PainPoint("ear_left",        "Left Ear",          0.249f, 0.402f, PainView.FRONT),
    PainPoint("ear_right",       "Right Ear",         0.744f, 0.402f, PainView.FRONT),
    PainPoint("nose_bridge",     "Nose Bridge",       0.496f, 0.360f, PainView.FRONT),
    PainPoint("sinus_left",      "Left Sinus",        0.391f, 0.410f, PainView.FRONT),
    PainPoint("sinus_right",     "Right Sinus",       0.609f, 0.410f, PainView.FRONT),
    PainPoint("jaw_left",        "Left Jaw / TMJ",    0.324f, 0.488f, PainView.FRONT),
    PainPoint("jaw_right",       "Right Jaw / TMJ",   0.680f, 0.492f, PainView.FRONT),
    PainPoint("teeth_left",      "Teeth Left",        0.448f, 0.492f, PainView.FRONT),
    PainPoint("teeth_right",     "Teeth Right",       0.555f, 0.492f, PainView.FRONT),
    PainPoint("neck_left",       "Neck Left",         0.365f, 0.595f, PainView.BOTH),
    PainPoint("neck_right",      "Neck Right",        0.637f, 0.598f, PainView.BOTH),
)

val BACK_PAIN_POINTS = listOf(
    PainPoint("vertex",            "Top of Head",         0.496f, 0.177f, PainView.BOTH),
    PainPoint("top_of_back_head_left",  "Top of Back Head Left",  0.345f, 0.222f, PainView.BACK),
    PainPoint("top_of_back_head_right", "Top of Back Head Right", 0.651f, 0.222f, PainView.BACK),
    PainPoint("occipital_center",  "Occipital Center",    0.500f, 0.285f, PainView.BACK),
    PainPoint("behind_ear_left",   "Behind Left Ear",     0.298f, 0.339f, PainView.BACK),
    PainPoint("behind_ear_right",  "Behind Right Ear",    0.712f, 0.339f, PainView.BACK),
    PainPoint("base_skull_left",   "Base of Skull Left",  0.383f, 0.420f, PainView.BACK),
    PainPoint("base_skull_center", "Base of Skull Center", 0.500f, 0.405f, PainView.BACK),
    PainPoint("base_skull_right",  "Base of Skull Right", 0.627f, 0.420f, PainView.BACK),
    PainPoint("neck_left",         "Neck Left",           0.380f, 0.528f, PainView.BOTH),
    PainPoint("neck_right",        "Neck Right",          0.625f, 0.528f, PainView.BOTH),
    PainPoint("upper_back_left",   "Upper Back Left",     0.275f, 0.613f, PainView.BACK),
    PainPoint("upper_back_right",  "Upper Back Right",    0.725f, 0.613f, PainView.BACK),
    PainPoint("upper_back_center", "Upper Back Center",   0.500f, 0.662f, PainView.BACK),
    PainPoint("shoulder_left",     "Left Shoulder",       0.090f, 0.667f, PainView.BACK),
    PainPoint("shoulder_right",    "Right Shoulder",      0.900f, 0.667f, PainView.BACK),
    PainPoint("center_back",       "Center / Lower Back", 0.500f, 0.780f, PainView.BACK),
)

val ALL_PAIN_POINTS_MAP: Map<String, String> by lazy {
    (FRONT_PAIN_POINTS + BACK_PAIN_POINTS)
        .distinctBy { it.id }
        .associate { it.id to it.label }
}

// ── Main screen composable ─────────────────────────────────────
@Composable
fun PainLocationScreen(
    navController: NavController,
    vm: LogViewModel,
    onClose: () -> Unit = {}
) {
    val draft by vm.draft.collectAsState()
    val scroll = rememberScrollState()

    ScrollFadeContainer(scrollState = scroll) { scrollState ->
        ScrollableScreenContent(scrollState = scrollState, logoRevealHeight = 0.dp) {

            // Top bar: ← Previous | Title | X Close
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Back"), tint = Color.White, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(t("Timing"), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = t("Close"), tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            // ── 1. Pain Hero Card ──
            HeroCard {
                Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = AppTheme.AccentPink, modifier = Modifier.size(40.dp))
                Text(t("Pain"), color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(
                    t("Rate the severity and mark where you feel it. Add another entry if the pain moved or peaked later"),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            WizardStepNav(onBack = { navController.popBackStack() }, onSkip = { navController.navigate(Routes.PRODROMES_LOG) })

            // ── 2. One card per timestamped pain entry ──
            draft.painEntries.forEachIndexed { index, entry ->
                PainEntryCard(
                    vm = vm,
                    entry = entry,
                    index = index,
                    canRemove = draft.painEntries.size > 1
                )
            }

            // ── 3. Add entry ──
            OutlinedButton(
                onClick = { vm.addPainEntry() },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.35f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple)
            ) { Text(t("+ Add pain entry"), fontWeight = FontWeight.SemiBold) }

            // Navigation
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = { navController.popBackStack() },
                    border = BorderStroke(1.dp, AppTheme.AccentPurple.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.AccentPurple)
                ) { Text(t("Back")) }
                Button(
                    onClick = { navController.navigate(Routes.PRODROMES_LOG) },
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.AccentPurple)
                ) { Text(t("Next")) }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── One timestamped pain entry: time + severity + head-map ─────
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PainEntryCard(
    vm: LogViewModel,
    entry: PainEntryDraft,
    index: Int,
    canRemove: Boolean
) {
    var showBack by rememberSaveable(entry.id) { mutableStateOf(false) }

    BaseCard {
        // Header: name + remove
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (index == 0) t("Pain at attack start") else t("Pain entry %s", index + 1),
                color = AppTheme.TitleColor,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.weight(1f))
            if (canRemove) {
                IconButton(onClick = { vm.removePainEntry(entry.id) }) {
                    Icon(Icons.Outlined.Close, contentDescription = t("Remove entry"), tint = AppTheme.AccentPink, modifier = Modifier.size(18.dp))
                }
            }
        }

        if (index > 0) {
            AppDateTimePicker(
                label = entry.startAtIso?.let { "When: ${formatIsoDdMmYyHm(it)}" } ?: t("Set time")
            ) { vm.setPainEntryTime(entry.id, it) }
            Spacer(Modifier.height(8.dp))
        }

        // Severity
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
            onValueChange = { v -> vm.setPainEntrySeverity(entry.id, v.toInt()) },
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

        // Pain locations
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
                        onClick = { vm.setPainEntryLocations(entry.id, entry.locations - id) },
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

        // Front / Back toggle
        ViewToggle(showBack = showBack, onToggle = { showBack = it })

        Spacer(Modifier.height(12.dp))

        // Image with overlay dots
        PainPointOverlay(
            imageRes = if (showBack) R.drawable.painpointsback else R.drawable.painpoints,
            points = if (showBack) BACK_PAIN_POINTS else FRONT_PAIN_POINTS,
            selected = entry.locations,
            onToggle = { id ->
                val next = if (id in entry.locations) entry.locations - id else entry.locations + id
                vm.setPainEntryLocations(entry.id, next)
            }
        )
    }
}

// ── Front/Back toggle ──────────────────────────────────────────
@Composable
private fun ViewToggle(showBack: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ToggleButton(t("Front"), !showBack, { onToggle(false) }, Modifier.weight(1f))
        ToggleButton(t("Back"), showBack, { onToggle(true) }, Modifier.weight(1f))
    }
}

@Composable
private fun ToggleButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(
        if (selected) AppTheme.AccentPurple else Color.Transparent,
        label = "toggleBg"
    )
    val fg by animateColorAsState(
        if (selected) Color.White else AppTheme.SubtleTextColor,
        label = "toggleFg"
    )

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        color = bg,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier
                .padding(vertical = 10.dp)
                .fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = fg,
            fontSize = 14.sp
        )
    }
}

// ── Image + dots overlay (ORIGINAL style) ──────────────────────
// Internal: the evening check-in's "pain update" card reuses it.
@Composable
internal fun PainPointOverlay(
    imageRes: Int,
    points: List<PainPoint>,
    selected: List<String>,
    onToggle: (String) -> Unit
) {
    val density = LocalDensity.current
    var imageSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
    ) {
        // Head image
        Image(
            painter = painterResource(imageRes),
            contentDescription = t("Pain location diagram"),
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { imageSize = it.size }
        )

        // Overlay dots
        if (imageSize.width > 0 && imageSize.height > 0) {
            points.forEach { point ->
                val isSelected = point.id in selected

                // Convert percentage to dp offset
                val xPx = point.xPct * imageSize.width
                val yPx = point.yPct * imageSize.height
                val xDp = with(density) { xPx.toDp() }
                val yDp = with(density) { yPx.toDp() }

                val dotSize = 30.dp

                // Pulsing glow for selected dots
                val infiniteTransition = rememberInfiniteTransition(label = t("pulse_%s", point.id))
                val glowAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 0.7f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = EaseInOutSine),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = t("glow_%s", point.id)
                )

                Box(
                    modifier = Modifier
                        .offset(
                            x = xDp - dotSize / 2,
                            y = yDp - dotSize / 2
                        )
                        .size(dotSize)
                        .then(
                            if (isSelected) Modifier.shadow(
                                elevation = 8.dp,
                                shape = CircleShape,
                                ambientColor = Color(0xFFB880FF).copy(alpha = glowAlpha),
                                spotColor = Color(0xFFB880FF).copy(alpha = glowAlpha)
                            ) else Modifier
                        )
                        .clip(CircleShape)
                        .background(
                            if (isSelected)
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFFCE9FFF).copy(alpha = 0.80f),
                                        Color(0xFFAA70EE).copy(alpha = 0.55f)
                                    )
                                )
                            else
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF7B5EA8).copy(alpha = 0.55f),
                                        Color(0xFF6A4D96).copy(alpha = 0.40f)
                                    )
                                )
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onToggle(point.id) },
                    contentAlignment = Alignment.Center
                ) {
                    // Inner dot ring
                    Box(
                        modifier = Modifier
                            .size(if (isSelected) 14.dp else 10.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected)
                                    Color(0xFFE0CCFF).copy(alpha = 0.90f)
                                else
                                    Color(0xFF8B6FBB).copy(alpha = 0.70f)
                            )
                    )
                }
            }
        }
    }
}


// Local copy — the same helper is private in EditMigraineScreen/ReviewLogScreen/TimingScreen.
private fun formatIsoDdMmYyHm(iso: String?): String {
    if (iso.isNullOrBlank()) return "-"
    return try {
        val odt = runCatching { java.time.OffsetDateTime.parse(iso) }.getOrNull()
        val ldt = odt?.toLocalDateTime() ?: java.time.LocalDateTime.parse(iso)
        ldt.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy HH:mm"))
    } catch (_: Exception) {
        "-"
    }
}
