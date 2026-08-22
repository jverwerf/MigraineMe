package com.migraineme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController

/* -- Custom hand-drawn icons for the hub -- */

private fun DrawScope.drawMigraineIcon(color: Color) {
    val w = size.width; val h = size.height
    val stroke = Stroke(w * 0.035f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    // Center pain point
    drawCircle(color.copy(alpha = 0.6f), radius = w * 0.08f, center = Offset(w * 0.5f, h * 0.5f), style = Fill)
    drawCircle(color, radius = w * 0.15f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(w * 0.03f, cap = StrokeCap.Round))
    // Radiating pain spikes - cardinal
    drawLine(color, Offset(w * 0.50f, h * 0.30f), Offset(w * 0.50f, h * 0.08f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.50f, h * 0.70f), Offset(w * 0.50f, h * 0.92f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.30f, h * 0.50f), Offset(w * 0.08f, h * 0.50f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.70f, h * 0.50f), Offset(w * 0.92f, h * 0.50f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
    // Radiating pain spikes - diagonal
    drawLine(color, Offset(w * 0.36f, h * 0.36f), Offset(w * 0.20f, h * 0.20f), strokeWidth = w * 0.03f, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.64f, h * 0.36f), Offset(w * 0.80f, h * 0.20f), strokeWidth = w * 0.03f, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.36f, h * 0.64f), Offset(w * 0.20f, h * 0.80f), strokeWidth = w * 0.03f, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.64f, h * 0.64f), Offset(w * 0.80f, h * 0.80f), strokeWidth = w * 0.03f, cap = StrokeCap.Round)
    // Pulsing ring
    drawCircle(color, radius = w * 0.24f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
}

private fun DrawScope.drawTriggerIcon(color: Color) {
    val w = size.width; val h = size.height
    // Lightning bolt
    val bolt = Path().apply {
        moveTo(w * 0.55f, h * 0.05f)
        lineTo(w * 0.30f, h * 0.45f)
        lineTo(w * 0.50f, h * 0.45f)
        lineTo(w * 0.28f, h * 0.95f)
        lineTo(w * 0.70f, h * 0.40f)
        lineTo(w * 0.50f, h * 0.40f)
        lineTo(w * 0.70f, h * 0.05f)
        close()
    }
    drawPath(bolt, color, style = Fill)
}

private fun DrawScope.drawMedicineIcon(color: Color) {
    val w = size.width; val h = size.height
    val stroke = Stroke(w * 0.07f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    // Pill capsule
    val r = w * 0.18f
    drawRoundRect(color, topLeft = Offset(w * 0.18f, h * 0.15f),
        size = androidx.compose.ui.geometry.Size(w * 0.64f, h * 0.70f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r), style = stroke)
    // Divider line in middle
    drawLine(color, Offset(w * 0.18f, h * 0.50f), Offset(w * 0.82f, h * 0.50f), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
    // Plus sign on top half
    drawLine(color, Offset(w * 0.50f, h * 0.25f), Offset(w * 0.50f, h * 0.40f), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.42f, h * 0.325f), Offset(w * 0.58f, h * 0.325f), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
}

private fun DrawScope.drawReliefIcon(color: Color) {
    val w = size.width; val h = size.height
    val stroke = Stroke(w * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    // Leaf shape
    val leaf = Path().apply {
        moveTo(w * 0.50f, h * 0.08f)
        cubicTo(w * 0.85f, h * 0.15f, w * 0.90f, h * 0.55f, w * 0.50f, h * 0.75f)
        cubicTo(w * 0.10f, h * 0.55f, w * 0.15f, h * 0.15f, w * 0.50f, h * 0.08f)
        close()
    }
    drawPath(leaf, color, style = stroke)
    // Leaf vein - center
    drawLine(color, Offset(w * 0.50f, h * 0.18f), Offset(w * 0.50f, h * 0.68f), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
    // Leaf veins - side
    drawLine(color, Offset(w * 0.50f, h * 0.35f), Offset(w * 0.34f, h * 0.26f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.50f, h * 0.35f), Offset(w * 0.66f, h * 0.26f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.50f, h * 0.50f), Offset(w * 0.30f, h * 0.42f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.50f, h * 0.50f), Offset(w * 0.70f, h * 0.42f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
    // Water droplet below
    val drop = Path().apply {
        moveTo(w * 0.50f, h * 0.78f)
        cubicTo(w * 0.44f, h * 0.85f, w * 0.38f, h * 0.92f, w * 0.50f, h * 0.98f)
        cubicTo(w * 0.62f, h * 0.92f, w * 0.56f, h * 0.85f, w * 0.50f, h * 0.78f)
        close()
    }
    drawPath(drop, color, style = Fill)
}

private fun DrawScope.drawProdromeIcon(color: Color) {
    val w = size.width; val h = size.height
    val stroke = Stroke(w * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    // Eye shape
    val top = Path().apply {
        moveTo(w * 0.05f, h * 0.50f)
        cubicTo(w * 0.25f, h * 0.15f, w * 0.75f, h * 0.15f, w * 0.95f, h * 0.50f)
    }
    val bottom = Path().apply {
        moveTo(w * 0.05f, h * 0.50f)
        cubicTo(w * 0.25f, h * 0.85f, w * 0.75f, h * 0.85f, w * 0.95f, h * 0.50f)
    }
    drawPath(top, color, style = stroke)
    drawPath(bottom, color, style = stroke)
    // Iris
    drawCircle(color, radius = w * 0.14f, center = Offset(w * 0.50f, h * 0.50f), style = stroke)
    // Pupil
    drawCircle(color, radius = w * 0.06f, center = Offset(w * 0.50f, h * 0.50f), style = Fill)
    // Sparkle lines (aura disturbance)
    drawLine(color, Offset(w * 0.80f, h * 0.15f), Offset(w * 0.88f, h * 0.08f), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.90f, h * 0.22f), Offset(w * 0.97f, h * 0.18f), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.85f, h * 0.08f), Offset(w * 0.92f, h * 0.12f), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
}

/* -- Screen -- */

@Composable
fun MigraineHubScreen(navController: NavController) {
    val scrollState = rememberScrollState()
    var showLogMigraineInfo by remember { mutableStateOf(false) }
    var showCheckInInfo by remember { mutableStateOf(false) }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {
            // Hero Card - Log Full Migraine
            Box(modifier = Modifier.fillMaxWidth()) {
                HeroCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate(Routes.TIMING) },
                    watermarkRes = R.drawable.brainy_migraines,
                    flipWatermark = true
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            t("Log Migraine"),
                            color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )

                        Text(
                            t("A guided entry of the whole attack. Skipping steps is fine, share as much as you care to."),
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Spacer(Modifier.height(6.dp))

                        Text(
                            t("Tap to start"),
                            color = AppTheme.AccentPurple,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
                IconButton(
                    onClick = { showLogMigraineInfo = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 10.dp, y = (-14).dp)
                        .size(34.dp)
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = t("About Log Migraine"),
                        tint = AppTheme.SubtleTextColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Daily Check-In
            Box(modifier = Modifier.fillMaxWidth()) {
                BaseCard(modifier = Modifier
                    .fillMaxWidth()
                    .clickable { navController.navigate(Routes.EVENING_CHECKIN) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.EditNote,
                            contentDescription = null,
                            tint = AppTheme.AccentPink,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                t("Daily Check-In"),
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                t("Review your day"),
                                color = AppTheme.SubtleTextColor,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                IconButton(
                    onClick = { showCheckInInfo = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 10.dp, y = (-14).dp)
                        .size(34.dp)
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = t("About Daily Check-In"),
                        tint = AppTheme.SubtleTextColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Food — the Log-side door onto the Diet screen. See LogFoodCard.kt.
            LogFoodCard(navController)

            // Quick Log Section Title
            var showQuickLogInfo by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                BaseCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            t("Quick Log"),
                            color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            t("Log a single item without a full migraine entry"),
                            color = AppTheme.SubtleTextColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                IconButton(
                    onClick = { showQuickLogInfo = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 10.dp, y = (-14).dp)
                        .size(34.dp)
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = t("About Quick Log"),
                        tint = AppTheme.SubtleTextColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (showQuickLogInfo) {
                AlertDialog(
                    onDismissRequest = { showQuickLogInfo = false },
                    confirmButton = {
                        TextButton(onClick = { showQuickLogInfo = false }) {
                            Text(t("Got it"), color = AppTheme.AccentPurple)
                        }
                    },
                    title = {
                        Text(t("About Quick Log"), color = AppTheme.TitleColor,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                    },
                    text = {
                        Text(LogQuickLogInfoCopy.text, color = AppTheme.BodyTextColor,
                            style = MaterialTheme.typography.bodyMedium)
                    },
                    containerColor = AppTheme.BaseCardContainer
                )
            }

            // Quick Log Section Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickLogCard(
                    modifier = Modifier.weight(1f),
                    title = t("Migraine"),
                    subtitle = t("Pain character, symptom"),
                    iconColor = AppTheme.AccentPink,
                    drawIcon = { HubIcons.run { drawMigraineStarburst(it) } },
                    onClick = { navController.navigate(Routes.QUICK_LOG_MIGRAINE) }
                )

                QuickLogCard(
                    modifier = Modifier.weight(1f),
                    title = t("Prodrome"),
                    subtitle = t("Log prodrome"),
                    iconColor = AppTheme.AccentPurple,
                    drawIcon = { drawProdromeIcon(it) },
                    onClick = { navController.navigate(Routes.QUICK_LOG_PRODROME) }
                )
            }

            // Quick Log Cards Row 2
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickLogCard(
                    modifier = Modifier.weight(1f),
                    title = t("Trigger"),
                    subtitle = t("Log a trigger"),
                    iconColor = Color(0xFFFFB74D),
                    drawIcon = { drawTriggerIcon(it) },
                    onClick = { navController.navigate(Routes.QUICK_LOG_TRIGGER) }
                )

                QuickLogCard(
                    modifier = Modifier.weight(1f),
                    title = t("Medicine"),
                    subtitle = t("Log a medicine"),
                    iconColor = Color(0xFF4FC3F7),
                    drawIcon = { drawMedicineIcon(it) },
                    onClick = { navController.navigate(Routes.QUICK_LOG_MEDICINE) }
                )
            }

            // Quick Log Cards Row 3
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickLogCard(
                    modifier = Modifier.weight(1f),
                    title = t("Relief"),
                    subtitle = t("Log a relief"),
                    iconColor = Color(0xFF81C784),
                    drawIcon = { drawReliefIcon(it) },
                    onClick = { navController.navigate(Routes.QUICK_LOG_RELIEF) }
                )

                // One card, two halves: what you did and what you didn't.
                // They are the same question asked both ways, so they share a
                // slot rather than the second one becoming a card of its own.
                SplitQuickLogCard(
                    modifier = Modifier.weight(1f),
                    leftTitle = t("Activity"),
                    leftSubtitle = t("What you did"),
                    leftColor = Color(0xFFFF8A65),
                    drawLeftIcon = { HubIcons.run { drawActivityPulse(it) } },
                    onLeftClick = { navController.navigate(Routes.QUICK_LOG_ACTIVITY) },
                    rightTitle = t("Missed"),
                    rightSubtitle = t("What you didn't"),
                    rightColor = Color(0xFFE8A0A0),
                    drawRightIcon = { HubIcons.run { drawMissedActivity(it) } },
                    onRightClick = { navController.navigate(Routes.QUICK_LOG_MISSED) },
                )
            }
        }
    }

    if (showLogMigraineInfo) {
        AlertDialog(
            onDismissRequest = { showLogMigraineInfo = false },
            confirmButton = {
                TextButton(onClick = { showLogMigraineInfo = false }) {
                    Text(t("Got it"), color = AppTheme.AccentPurple)
                }
            },
            title = {
                Text(t("About Log Migraine"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            },
            text = {
                Text(LogMigraineInfoCopy.text, color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium)
            },
            containerColor = AppTheme.BaseCardContainer
        )
    }

    if (showCheckInInfo) {
        AlertDialog(
            onDismissRequest = { showCheckInInfo = false },
            confirmButton = {
                TextButton(onClick = { showCheckInInfo = false }) {
                    Text(t("Got it"), color = AppTheme.AccentPurple)
                }
            },
            title = {
                Text(t("About Daily Check-In"), color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            },
            text = {
                Text(CheckInInfoCopy.text, color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodyMedium)
            },
            containerColor = AppTheme.BaseCardContainer
        )
    }
}

@Composable
private fun QuickLogCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    iconColor: Color,
    enabled: Boolean = true,
    drawIcon: DrawScope.(Color) -> Unit,
    onClick: () -> Unit
) {
    val actualColor = if (enabled) iconColor else AppTheme.SubtleTextColor.copy(alpha = 0.5f)

    BaseCard(
        modifier = modifier
            .then(
                if (enabled) Modifier.clickable(onClick = onClick)
                else Modifier
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Round icon circle
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(actualColor.copy(alpha = 0.15f))
                    .border(1.5.dp, actualColor.copy(alpha = 0.3f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .drawBehind { drawIcon(actualColor) }
                )
            }

            Spacer(Modifier.height(10.dp))

            Text(
                title,
                color = if (enabled) AppTheme.BodyTextColor else AppTheme.SubtleTextColor.copy(alpha = 0.5f),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )

            Text(
                subtitle,
                color = if (enabled) AppTheme.SubtleTextColor else AppTheme.SubtleTextColor.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Visible,
                softWrap = false
            )
        }
    }
}


/**
 * The Activity slot, split down the middle. Two tap targets in the space of
 * one card, so "what you did" and "what you didn't" stay one thought and the
 * grid keeps its pairs.
 */
@Composable
private fun SplitQuickLogCard(
    modifier: Modifier = Modifier,
    leftTitle: String,
    leftSubtitle: String,
    leftColor: Color,
    drawLeftIcon: DrawScope.(Color) -> Unit,
    onLeftClick: () -> Unit,
    rightTitle: String,
    rightSubtitle: String,
    rightColor: Color,
    drawRightIcon: DrawScope.(Color) -> Unit,
    onRightClick: () -> Unit,
) {
    BaseCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SplitHalf(Modifier.weight(1f), leftTitle, leftSubtitle, leftColor, drawLeftIcon, onLeftClick)
            Box(
                Modifier
                    .width(1.dp)
                    .height(104.dp)
                    .background(Color.White.copy(alpha = 0.10f))
            )
            SplitHalf(Modifier.weight(1f), rightTitle, rightSubtitle, rightColor, drawRightIcon, onRightClick)
        }
    }
}

@Composable
private fun SplitHalf(
    modifier: Modifier,
    title: String,
    subtitle: String,
    color: Color,
    drawIcon: DrawScope.(Color) -> Unit,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Same circle, spacing and type as QuickLogCard so the split card
        // stands exactly as tall as its neighbours in the grid.
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f))
                .border(1.5.dp, color.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(28.dp).drawBehind { drawIcon(color) })
        }

        Spacer(Modifier.height(10.dp))

        Text(
            title,
            color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1
        )
        Text(
            subtitle,
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
            textAlign = TextAlign.Center,
            // Half the width of a normal card, so this one wraps rather than
            // bleeding past its own edge.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

object LogQuickLogInfoCopy {
    val text: String get() = tSync("Still quick, but with a bit more choice. The home strip is one-tap-and-done; this Quick Log lets you pick any item from your full pool (not just your favourites), set the time to something other than right now, and add notes.\n\nFor medicines and reliefs you can also log the amount, how much it helped, and any side effects, so this is the right place when you've taken something specific and want it recorded properly.\n\nActivity logging lives here too. For a full attack with everything that goes around it (timing, symptoms, pain, prodromes, triggers, medicines, reliefs, locations, activities, postdromes, missed activities and notes), use the \"Log Migraine\" hero card above.")
}

object LogMigraineInfoCopy {
    val text: String get() = tSync("The full attack log. Tap to walk through every step that goes into recording a migraine: timing, paint-the-picture AI shortcut, symptoms, pain, prodromes, triggers, medicines, reliefs, locations, activities, postdromes, missed activities, notes, and a final review.\n\nUse this when you want the complete record of an attack and have a few minutes to fill it in. Every step is optional, so skip what doesn't apply.\n\nFor one-tap or single-item logging during an attack, use the Quick Log strip on the Home tab or the Quick Log section below.")
}

object CheckInInfoCopy {
    val text: String get() = tSync("A guided evening review of your day in one go. Walks you through a free-text note about how the day went, anything notable from your calendar, and a quick pass over your triggers, prodromes, medicines, reliefs and activities so nothing slips through.\n\nWhen you have an open migraine, it also asks about postdrome symptoms.\n\nIf you're on any treatments, it checks in about side effects from each one.\n\nUse it once a day to keep your log complete without remembering everything at the moment it happens.")
}
