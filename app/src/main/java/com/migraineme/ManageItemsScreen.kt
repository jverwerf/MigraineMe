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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

private const val MANAGE_ITEMS_HERO_INFO =
    "Your personal pools. These are the lists that drive everything you can pick in the migraine wizard, Quick Log, and Daily Check-In: every Trigger, Prodrome, Medicine, Relief, Migraine type, Location, Activity, Missed activity, and Treatment side effect you might ever tag on a log lives here.\n\n" +
    "Defaults are pre-loaded based on what you said in AI Setup, but every list is fully editable. You can add new items, remove ones you'll never log, and set the severity weight on each one (HIGH / MILD / LOW / NONE). The weight drives the risk gauge: a HIGH trigger pushes the bucket up much more than a LOW one, and NONE means the item exists in the pool but doesn't contribute to your score.\n\n" +
    "Calendar opt-outs is the inverse list: events from your phone calendar that the app has tried to suggest and you've dismissed."

private val MANAGE_ITEM_INFO: Map<String, String> = mapOf(
    "Migraines" to "Migraine symptoms you can tag in the wizard or Quick Log: pain character, accompanying experiences (nausea, light sensitivity, etc.), and aura types. Add anything specific to your attacks. No severity weight; symptoms don't feed the gauge, they're for pattern tracking.",
    "Triggers" to "The things that push your bucket up. Add anything you've noticed setting you off. Severity weight (HIGH / MILD / LOW / NONE) determines how much each one contributes to your gauge. Most defaults can be auto-fired from wearable, weather, or nutrition data; star favourites for one-tap logging.",
    "Medicines" to "Everything you might log as a medicine: preventives, acutes, supplements. Set the category (triptan, NSAID, paracetamol, opioid, anti-emetic, supplement, etc.) so AI Recommendations can flag overuse correctly.",
    "Reliefs" to "Non-medicine things that help: ice pack, dark room, hot shower, lying down. The \"What Worked\" Insights card scores each one by its impact on attack severity and duration.",
    "Prodromes" to "Early warning signs you spot before an attack starts: yawning, neck stiffness, food cravings, mood shifts. Logging these pushes your bucket up like a trigger, because being in a prodrome means an attack is already on the way.",
    "Locations" to "Places you might tag with a migraine: home, work, specific rooms, outdoors. Useful for spotting environmental patterns.",
    "Activities" to "What you were doing around an attack: running, screen time, social events. Surfaces on \"What Were You Doing\" in Insights.",
    "Missed Activities" to "Things you skipped because of a migraine: a workout, a meeting, a meal, social plans. Surfaces on \"How Did It Impact You\" on Insights.",
    "Treatment side effects" to "Symptoms you want flagged as caused by a treatment regimen. The Daily Check-In side-effects page uses this list, and the Treatments efficacy score weighs side effects against benefit.",
    "Calendar opt-outs" to "Event titles you've told the Daily Check-In calendar page to ignore. Anything in here won't be suggested again going forward.",
)

@Composable
fun ManageItemsScreen(navController: NavController) {
    val scrollState = rememberScrollState()
    var showHeroInfo by remember { mutableStateOf(false) }
    var showInfoFor by remember { mutableStateOf<String?>(null) }

    ScrollFadeContainer(scrollState = scrollState) { scroll ->
        ScrollableScreenContent(scrollState = scroll, logoRevealHeight = 0.dp) {

            // Hero
            Box(modifier = Modifier.fillMaxWidth()) {
                HeroCard {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .drawBehind { HubIcons.run { drawMigraineStarburst(AppTheme.AccentPink) } }
                    )
                    Text("Manage Items", color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                    Text(
                        "Add, remove, or organise your triggers, medicines, reliefs and symptoms",
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
                IconButton(
                    onClick = { showHeroInfo = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 10.dp, y = (-14).dp)
                        .size(34.dp)
                ) {
                    Icon(Icons.Outlined.Info, contentDescription = "About Manage Items",
                        tint = AppTheme.SubtleTextColor, modifier = Modifier.size(20.dp))
                }
            }

            // Manage cards
            ManageItemRow(
                title = "Migraines",
                subtitle = "Pain character & accompanying experiences",
                iconColor = AppTheme.AccentPink,
                drawIcon = { HubIcons.run { drawMigraineStarburst(it) } },
                onClick = { navController.navigate(Routes.MANAGE_SYMPTOMS) },
                onInfo = { showInfoFor = "Migraines" }
            )

            ManageItemRow(
                title = "Triggers",
                subtitle = "Manage your trigger pool",
                iconColor = Color(0xFFFFB74D),
                drawIcon = { HubIcons.run { drawTriggerBolt(it) } },
                onClick = { navController.navigate(Routes.MANAGE_TRIGGERS) },
                onInfo = { showInfoFor = "Triggers" }
            )

            ManageItemRow(
                title = "Medicines",
                subtitle = "Manage your medicine pool",
                iconColor = Color(0xFF4FC3F7),
                drawIcon = { HubIcons.run { drawMedicinePill(it) } },
                onClick = { navController.navigate(Routes.MANAGE_MEDICINES) },
                onInfo = { showInfoFor = "Medicines" }
            )

            ManageItemRow(
                title = "Reliefs",
                subtitle = "Manage your relief pool",
                iconColor = Color(0xFF81C784),
                drawIcon = { HubIcons.run { drawReliefLeaf(it) } },
                onClick = { navController.navigate(Routes.MANAGE_RELIEFS) },
                onInfo = { showInfoFor = "Reliefs" }
            )

            ManageItemRow(
                title = "Prodromes",
                subtitle = "Early warning signs",
                iconColor = Color(0xFFCE93D8),
                drawIcon = { HubIcons.run { drawProdromeEye(it) } },
                onClick = { navController.navigate(Routes.MANAGE_PRODROMES) },
                onInfo = { showInfoFor = "Prodromes" }
            )

            ManageItemRow(
                title = "Locations",
                subtitle = "Where were you?",
                iconColor = Color(0xFF64B5F6),
                drawIcon = { HubIcons.run { drawLocationPin(it) } },
                onClick = { navController.navigate(Routes.MANAGE_LOCATIONS) },
                onInfo = { showInfoFor = "Locations" }
            )

            ManageItemRow(
                title = "Activities",
                subtitle = "What were you doing?",
                iconColor = Color(0xFFFF8A65),
                drawIcon = { HubIcons.run { drawActivityPulse(it) } },
                onClick = { navController.navigate(Routes.MANAGE_ACTIVITIES) },
                onInfo = { showInfoFor = "Activities" }
            )

            ManageItemRow(
                title = "Missed Activities",
                subtitle = "What did you miss?",
                iconColor = Color(0xFFEF9A9A),
                drawIcon = { HubIcons.run { drawMissedActivity(it) } },
                onClick = { navController.navigate(Routes.MANAGE_MISSED_ACTIVITIES) },
                onInfo = { showInfoFor = "Missed Activities" }
            )

            ManageItemRow(
                title = "Treatment side effects",
                subtitle = "Symptoms you flag as caused by your treatments",
                iconColor = AppTheme.AccentPurple,
                drawIcon = { HubIcons.run { drawCapsulePlus(it) } },
                onClick = { navController.navigate(Routes.MANAGE_TREATMENT_SIDE_EFFECTS) },
                onInfo = { showInfoFor = "Treatment side effects" }
            )

            ManageItemRow(
                title = "Calendar opt-outs",
                subtitle = "Titles you've undone from calendar",
                iconColor = Color(0xFF64B5F6),
                drawIcon = { HubIcons.run { drawCalendarWeek(it) } },
                onClick = { navController.navigate(Routes.MANAGE_CALENDAR_SKIPS) },
                onInfo = { showInfoFor = "Calendar opt-outs" }
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showHeroInfo) {
        AlertDialog(
            onDismissRequest = { showHeroInfo = false },
            containerColor = Color(0xFF1E0A2E),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(20.dp).drawBehind { HubIcons.run { drawMigraineStarburst(AppTheme.AccentPink) } })
                    Spacer(Modifier.width(8.dp))
                    Text("Manage Items", color = AppTheme.TitleColor,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                }
            },
            text = { Text(MANAGE_ITEMS_HERO_INFO, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { showHeroInfo = false }) { Text("Got it", color = AppTheme.AccentPurple) } }
        )
    }

    showInfoFor?.let { key ->
        AlertDialog(
            onDismissRequest = { showInfoFor = null },
            containerColor = Color(0xFF1E0A2E),
            title = {
                Text("About $key", color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            },
            text = { Text(MANAGE_ITEM_INFO[key] ?: "", color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = { TextButton(onClick = { showInfoFor = null }) { Text("Got it", color = AppTheme.AccentPurple) } }
        )
    }
}

@Composable
private fun ManageItemRow(
    title: String,
    subtitle: String,
    iconColor: Color,
    drawIcon: DrawScope.(Color) -> Unit,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onInfo: (() -> Unit)? = null
) {
    val actualColor = if (enabled) iconColor else iconColor.copy(alpha = 0.4f)

    Box(modifier = Modifier.fillMaxWidth()) {
        BaseCard(
            modifier = if (enabled) Modifier.clickable(onClick = onClick) else Modifier
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Round icon circle
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(actualColor.copy(alpha = 0.15f))
                        .border(1.5.dp, actualColor.copy(alpha = 0.3f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .drawBehind { drawIcon(actualColor) }
                    )
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        color = AppTheme.BodyTextColor,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        subtitle,
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = AppTheme.SubtleTextColor,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        if (onInfo != null) {
            IconButton(
                onClick = onInfo,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 10.dp, y = (-14).dp)
                    .size(28.dp)
            ) {
                Icon(Icons.Outlined.Info, contentDescription = "About $title",
                    tint = AppTheme.SubtleTextColor, modifier = Modifier.size(16.dp))
            }
        }
    }
}


