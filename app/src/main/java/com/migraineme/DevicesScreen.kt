package com.migraineme

import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Reference page for the devices MigraineMe can track.
 *
 * Only devices we have a live partnership with are shown — see [visibleGroups].
 * The rest keep their copy here so they appear the day their programme is
 * approved; adding a `link` is the only edit needed.
 *
 * Keep this list in step with [DeviceCatalog.LABELS] and the `Device` rows in
 * relief_templates — a device that can be logged should be explainable here.
 */

private enum class DeviceAccess { OTC, PRESCRIPTION }

private data class DeviceInfo(
    val name: String,
    /** Matches a ReliefIcons key so the card shows the same art as the log screens. */
    val iconKey: String,
    val access: DeviceAccess,
    /** One line on what the thing physically does. */
    val what: String,
    /** Honest read on the evidence, including when it is thin. */
    val evidence: String,
    /** Referral link, only where a partnership actually exists. */
    val link: String? = null,
    val linkNote: String? = null,
    /** Real product shot from the brand's own partner assets, shown in the card corner. */
    @DrawableRes val photo: Int? = null
)

private data class DeviceGroup(val title: String, val blurb: String, val devices: List<DeviceInfo>)

private val DEVICE_GROUPS = listOf(
    DeviceGroup(
        title = "Nerve stimulation",
        blurb = "Send a small electrical or magnetic signal to a nerve involved in migraine.",
        devices = listOf(
            DeviceInfo(
                name = "CEFALY",
                iconKey = "cefaly",
                access = DeviceAccess.OTC,
                what = "Headband that stimulates the trigeminal nerve on the forehead. Separate acute and prevention programmes.",
                evidence = "The best studied device on this list. Randomised trials support both stopping an attack and reducing how often they come."
            ),
            DeviceInfo(
                name = "Nerivio",
                iconKey = "nerivio",
                access = DeviceAccess.PRESCRIPTION,
                what = "Armband worn on the upper arm during an attack. Stimulates nerves in the arm to dampen pain signals.",
                evidence = "Randomised trial evidence for acute treatment, and later evidence for prevention. Needs a prescription."
            ),
            DeviceInfo(
                name = "gammaCore",
                iconKey = "gammacore",
                access = DeviceAccess.PRESCRIPTION,
                what = "Handheld unit held against the neck to stimulate the vagus nerve.",
                evidence = "Trial evidence in migraine and stronger evidence in cluster headache. Needs a prescription."
            ),
            DeviceInfo(
                name = "sTMS (SAVI Dual)",
                iconKey = "stms",
                access = DeviceAccess.PRESCRIPTION,
                what = "Device held at the back of the head that delivers single magnetic pulses.",
                evidence = "Studied for attacks with aura and for prevention. Bulkier and pricier than most. Needs a prescription."
            ),
            DeviceInfo(
                name = "Relivion",
                iconKey = "relivion",
                access = DeviceAccess.PRESCRIPTION,
                what = "Headset that stimulates the trigeminal and occipital nerves at the same time.",
                evidence = "Newer than the others, with less independent data behind it so far. Needs a prescription."
            ),
            DeviceInfo(
                name = "Quell",
                iconKey = "quell",
                access = DeviceAccess.OTC,
                what = "Band worn on the calf that delivers TENS-style stimulation.",
                evidence = "Built for chronic pain generally. Migraine-specific evidence is thin, so treat it as an experiment."
            )
        )
    ),
    DeviceGroup(
        title = "Light and glare",
        blurb = "For light sensitivity between and during attacks.",
        devices = listOf(
            DeviceInfo(
                name = "Avulux glasses",
                iconKey = "avulux",
                access = DeviceAccess.OTC,
                what = "Lenses that filter out most blue, amber and red light while letting green through.",
                evidence = "A randomised trial in migraine backs the filter approach. Worth knowing they are much darker than normal tinted glasses."
            ),
            DeviceInfo(
                name = "TheraSpecs",
                iconKey = "theraspecs",
                access = DeviceAccess.OTC,
                what = "FL-41 tinted glasses, indoor and outdoor versions.",
                evidence = "FL-41 tint has been studied for light sensitivity for decades. Cheaper than Avulux and easier to wear all day."
            ),
            DeviceInfo(
                name = "Allay Lamp",
                iconKey = "allay",
                access = DeviceAccess.OTC,
                what = "Lamp that emits a narrow band of green light you can sit and read under during an attack.",
                evidence = "Comes out of Harvard research showing green light is the least aggravating wavelength. Small studies only."
            )
        )
    ),
    DeviceGroup(
        title = "Nausea",
        blurb = "For when the sickness is the worst part.",
        devices = listOf(
            DeviceInfo(
                name = "Reliefband",
                iconKey = "reliefband",
                access = DeviceAccess.OTC,
                what = "Wristband that stimulates the median nerve to settle nausea.",
                evidence = "Cleared for nausea and vomiting rather than for migraine. Useful if sickness is the part that floors you."
            )
        )
    ),
    DeviceGroup(
        title = "Trigger management",
        blurb = "Aimed at stress and recovery, which for a lot of people is the trigger rather than the attack.",
        devices = listOf(
            DeviceInfo(
                name = "Apollo Neuro",
                iconKey = "apollo",
                access = DeviceAccess.OTC,
                what = "Silent, soothing vibrations you wear on your wrist or ankle. Calms you down in minutes, rebalances your nervous system and lifts your HRV.",
                evidence = "Built by neuroscientists, with published work on stress, sleep and HRV. Most useful if stress or broken sleep are among your triggers.",
                link = "https://apolloneuro.com/migraineme",
                linkNote = "$99 off, 30-day trial",
                photo = R.drawable.device_photo_apollo
            ),
            DeviceInfo(
                name = "CalmiGo",
                iconKey = "calmigo",
                access = DeviceAccess.OTC,
                what = "Handheld device that paces your breathing and gives feedback as you go.",
                evidence = "Studied for stress and anxiety. Any migraine benefit is indirect, through the trigger rather than the attack."
            ),
            DeviceInfo(
                name = "HeartMath Inner Balance",
                iconKey = "heartmath",
                access = DeviceAccess.OTC,
                what = "Ear or finger sensor that coaches heart rate variability breathing through an app.",
                evidence = "Biofeedback has real support as a preventive approach. This is one of several ways to do it, not the only one."
            )
        )
    ),
    DeviceGroup(
        title = "Heat and cold",
        blurb = "The oldest idea on this page, done with a machine.",
        devices = listOf(
            DeviceInfo(
                name = "ThermaZone",
                iconKey = "thermazone",
                access = DeviceAccess.OTC,
                what = "Circulates hot or cold water through a head or neck pad at a steady temperature.",
                evidence = "No migraine trials to speak of. It is a more controllable ice pack, and priced accordingly."
            )
        )
    )
)

/** Groups with at least one device we can actually link to. */
private val visibleGroups: List<DeviceGroup> =
    DEVICE_GROUPS.mapNotNull { group ->
        group.devices.filter { it.link != null }
            .takeIf { it.isNotEmpty() }
            ?.let { group.copy(devices = it) }
    }

@Composable
fun DevicesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var showInfo by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Devices",
                color = AppTheme.AccentPurple,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(bottom = 12.dp)
            )
            IconButton(
                onClick = { showInfo = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(28.dp)
            ) {
                Icon(
                    Icons.Outlined.Info, contentDescription = "About devices",
                    tint = AppTheme.SubtleTextColor, modifier = Modifier.size(18.dp)
                )
            }
        }

        Text(
            "Devices that complement or integrate with MigraineMe.",
            color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(20.dp))

        visibleGroups.forEach { group ->
            Text(
                group.title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                group.blurb,
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )
            group.devices.forEach { device ->
                DeviceCard(
                    device = device,
                    onOpenLink = { url ->
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    }
                )
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(14.dp))
        }

        // Devices needs its own wording: the Insights disclaimer is about risk
        // scores, which has nothing to do with this page. Same dismissible box.
        DevicesDisclaimerCard()

        Spacer(Modifier.height(32.dp))
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            containerColor = Color(0xFF241035),
            title = {
                Text(
                    "About devices",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Text(
                    "Migraine devices are hard to judge from a website, because the marketing all sounds " +
                        "the same whether there are ten trials behind it or none.\n\n" +
                        "This page lists what each one physically does and how solid the evidence is, " +
                        "including the ones where it's thin. Prescription devices are marked as such.\n\n" +
                        "Once you own one, logging it is the point: MigraineMe compares how your attacks go " +
                        "with and without it, so after a few weeks you have your own answer rather than the " +
                        "manufacturer's.",
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text("Got it", color = AppTheme.AccentPurple)
                }
            }
        )
    }
}

@Composable
private fun DeviceCard(device: DeviceInfo, onOpenLink: (String) -> Unit) {
    val drawable = remember(device.iconKey) { ReliefIcons.drawableForKey(device.iconKey) }
    val vector = remember(device.iconKey) { ReliefIcons.forKey(device.iconKey) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center
            ) {
                if (drawable != null) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(drawable),
                        contentDescription = null,
                        modifier = Modifier.size(30.dp)
                    )
                } else if (vector != null) {
                    Icon(vector, null, tint = AppTheme.AccentPurple, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    device.name,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    if (device.access == DeviceAccess.PRESCRIPTION) "Prescription only" else "Available without a prescription",
                    color = if (device.access == DeviceAccess.PRESCRIPTION) AppTheme.AccentPink else AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            // Real product shot sits opposite the Brainy icon so you can see what
            // the thing actually looks like without leaving the app.
            device.photo?.let { photo ->
                androidx.compose.foundation.Image(
                    painter = painterResource(photo),
                    contentDescription = "${device.name} product photo",
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Text(device.what, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(6.dp))

        Text(
            device.evidence,
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall
        )

        if (device.link != null) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppTheme.AccentPurple.copy(alpha = 0.16f))
                    .clickable { onOpenLink(device.link) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Visit ${device.name}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    device.linkNote?.let {
                        Text(it, color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Icon(
                    Icons.Outlined.OpenInNew, null,
                    tint = AppTheme.AccentPurple, modifier = Modifier.size(16.dp)
                )
            }
            Text(
                "We earn a commission.",
                color = AppTheme.SubtleTextColor.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/**
 * Dismissible disclaimer for this page. Matches [MedicalDisclaimerCard]'s look
 * and behaviour but says the things that matter here: we do not recommend
 * devices, prescription ones go through a doctor, and we take a commission.
 * Dismissal is per-device and never synced, so a fresh install always sees it.
 */
@Composable
private fun DevicesDisclaimerCard() {
    val ctx = LocalContext.current
    val prefs = remember {
        ctx.getSharedPreferences("medical_disclaimer", android.content.Context.MODE_PRIVATE)
    }
    var dismissed by remember { mutableStateOf(prefs.getBoolean("devices_dismissed", false)) }
    if (dismissed) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            "MigraineMe is not a medical device and does not recommend treatments. " +
                "Prescription devices need a conversation with your doctor. " +
                "We earn a commission on links to devices.",
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = {
                prefs.edit().putBoolean("devices_dismissed", true).apply()
                dismissed = true
            },
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Dismiss disclaimer",
                tint = AppTheme.SubtleTextColor,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
