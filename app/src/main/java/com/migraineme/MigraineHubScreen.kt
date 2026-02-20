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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
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

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {
            // Hero Card - Log Full Migraine
            HeroCard(
                modifier = Modifier.clickable { navController.navigate(Routes.LOG_MIGRAINE) }
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .drawBehind { drawMigraineIcon(Color(0xFFE091C8)) }
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    "Log Migraine",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )

                Text(
                    "Timing → Pain location → Prodromes → Triggers → Medicines → Reliefs → Notes → Review",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    "Tap to start →",
                    color = AppTheme.AccentPurple,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }

            // Quick Log Section Title
            BaseCard {
                Text(
                    "Quick Log",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    "Log a single item without a full migraine entry",
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Quick Log Cards Row 1 — Migraine symptoms first
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickLogCard(
                    modifier = Modifier.weight(1f),
                    title = "Symptoms",
                    subtitle = "Log a symptom",
                    iconColor = AppTheme.AccentPink,
                    drawIcon = { HubIcons.run { drawMigraineStarburst(it) } },
                    onClick = { navController.navigate(Routes.QUICK_LOG_MIGRAINE) }
                )

                QuickLogCard(
                    modifier = Modifier.weight(1f),
                    title = "Prodrome",
                    subtitle = "Log prodrome",
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
                    title = "Trigger",
                    subtitle = "Log a trigger",
                    iconColor = Color(0xFFFFB74D),
                    drawIcon = { drawTriggerIcon(it) },
                    onClick = { navController.navigate(Routes.QUICK_LOG_TRIGGER) }
                )

                QuickLogCard(
                    modifier = Modifier.weight(1f),
                    title = "Medicine",
                    subtitle = "Log a medicine",
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
                    title = "Relief",
                    subtitle = "Log a relief",
                    iconColor = Color(0xFF81C784),
                    drawIcon = { drawReliefIcon(it) },
                    onClick = { navController.navigate(Routes.QUICK_LOG_RELIEF) }
                )

                QuickLogCard(
                    modifier = Modifier.weight(1f),
                    title = "Activity",
                    subtitle = "Log activity",
                    iconColor = Color(0xFFFF8A65),
                    drawIcon = { HubIcons.run { drawActivityPulse(it) } },
                    onClick = { navController.navigate(Routes.QUICK_LOG_ACTIVITY) }
                )
            }
        }
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
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}


