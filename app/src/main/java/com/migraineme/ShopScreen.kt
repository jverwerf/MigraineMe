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

private enum class ShopAccess { OTC, PRESCRIPTION }

private data class ShopItem(
    val name: String,
    /** Matches a ReliefIcons key so the card shows the same art as the log screens. */
    val iconKey: String,
    val access: ShopAccess,
    /** One line on what the thing physically does. */
    val what: String,
    /** Honest read on the evidence, including when it is thin. */
    val evidence: String,
    /** Referral link, only where a partnership actually exists. */
    val link: String? = null,
    val linkNote: String? = null,
    /** Real product shot from the brand's own partner assets, shown in the card corner. */
    @DrawableRes val photo: Int? = null,
    /** Short, real bullets pulled from the brand's own review base. Optional —
     * a device with no review coverage yet simply renders no community block. */
    val communityPros: List<String>? = null,
    val communityCons: List<String>? = null,
    /** Short rating badge, e.g. "4.2/5", shown top-right of the community block. */
    val communityRating: String? = null,
    val communitySource: String? = null,
    val communitySourceURL: String? = null,
    /** Brand mark to use instead of a Relief icon, for partners we haven't
     * commissioned custom art for yet. Takes priority over iconKey lookup. */
    @DrawableRes val iconImage: Int? = null,
    /** A caution that belongs on its own, below the commission line, rather
     * than mixed into the migraine-relevance evidence paragraph above. */
    val safetyNote: String? = null
)

private data class ShopGroup(val title: String, val blurb: String, val devices: List<ShopItem>)

private val SHOP_GROUPS = listOf(
    ShopGroup(
        title = tSync("Nerve stimulation"),
        blurb = "Send a small electrical or magnetic signal to a nerve involved in migraine.",
        devices = listOf(
            ShopItem(
                name = "CEFALY",
                iconKey = "cefaly",
                access = ShopAccess.OTC,
                what = "Headband that stimulates the trigeminal nerve on the forehead. Separate acute and prevention programmes.",
                evidence = "The best studied device on this list. Randomised trials support both stopping an attack and reducing how often they come."
            ),
            ShopItem(
                name = "Nerivio",
                iconKey = "nerivio",
                access = ShopAccess.PRESCRIPTION,
                what = "Armband worn on the upper arm during an attack. Stimulates nerves in the arm to dampen pain signals.",
                evidence = "Randomised trial evidence for acute treatment, and later evidence for prevention. Needs a prescription."
            ),
            ShopItem(
                name = "gammaCore",
                iconKey = "gammacore",
                access = ShopAccess.PRESCRIPTION,
                what = "Handheld unit held against the neck to stimulate the vagus nerve.",
                evidence = "Trial evidence in migraine and stronger evidence in cluster headache. Needs a prescription."
            ),
            ShopItem(
                name = "sTMS (SAVI Dual)",
                iconKey = "stms",
                access = ShopAccess.PRESCRIPTION,
                what = "Device held at the back of the head that delivers single magnetic pulses.",
                evidence = "Studied for attacks with aura and for prevention. Bulkier and pricier than most. Needs a prescription."
            ),
            ShopItem(
                name = "Relivion",
                iconKey = "relivion",
                access = ShopAccess.PRESCRIPTION,
                what = "Headset that stimulates the trigeminal and occipital nerves at the same time.",
                evidence = "Newer than the others, with less independent data behind it so far. Needs a prescription."
            ),
            ShopItem(
                name = "Quell",
                iconKey = "quell",
                access = ShopAccess.OTC,
                what = "Band worn on the calf that delivers TENS-style stimulation.",
                evidence = "Built for chronic pain generally. Migraine-specific evidence is thin, so treat it as an experiment."
            )
        )
    ),
    ShopGroup(
        title = tSync("Light and glare"),
        blurb = "For light sensitivity between and during attacks.",
        devices = listOf(
            ShopItem(
                name = "Avulux glasses",
                iconKey = "avulux",
                access = ShopAccess.OTC,
                what = "Lenses that filter out most blue, amber and red light while letting green through.",
                evidence = "A randomised trial in migraine backs the filter approach. Worth knowing they are much darker than normal tinted glasses."
            ),
            ShopItem(
                name = "TheraSpecs",
                iconKey = "theraspecs",
                access = ShopAccess.OTC,
                what = "FL-41 tinted glasses, indoor and outdoor versions.",
                evidence = "FL-41 tint has been studied for light sensitivity for decades. Cheaper than Avulux and easier to wear all day."
            ),
            ShopItem(
                name = "Allay Lamp",
                iconKey = "allay",
                access = ShopAccess.OTC,
                what = "Lamp that emits a narrow band of green light you can sit and read under during an attack.",
                evidence = "Comes out of Harvard research showing green light is the least aggravating wavelength. Small studies only."
            )
        )
    ),
    ShopGroup(
        title = tSync("Prodrome management"),
        blurb = "For the early warning signs, before an attack fully arrives.",
        devices = listOf(
            ShopItem(
                name = "Breo See 7",
                iconKey = "breo_see7",
                access = ShopAccess.OTC,
                what = "Heated eye massager with hot and cold compress and gentle vibration, for eye strain and light sensitivity.",
                evidence = "Eases eye strain and light sensitivity with heat and gentle pressure around the eyes, right when an attack is building.",
                link = "https://us.breo.com/?ref=ME-SERIES",
                linkNote = "$25 off with code ME-SERIES",
                photo = R.drawable.device_photo_breo_see7,
                communityPros = listOf("Adjustable heat and pressure", "Easy returns, responsive support", "Good build quality"),
                communityCons = listOf("Tight at the sides at first", "Overuse can trigger a headache", "Units failing just past warranty"),
                communityRating = "4.2/5",
                communitySource = "Trustpilot, 137 reviews",
                communitySourceURL = "https://www.trustpilot.com/review/us.breo.com",
                iconImage = R.drawable.icon_breo,
                safetyNote = "Compression-based eye massagers raise pressure inside the eye, so anyone with glaucoma, ocular hypertension or a history of eye surgery should check with their doctor first."
            ),
            ShopItem(
                name = "Breo iDream 5S",
                iconKey = "breo_idream",
                access = ShopAccess.OTC,
                what = "Full-head massager with air pressure, kneading and heat across scalp, temples and eyes, run from your phone.",
                evidence = "Eases head and temple pressure with kneading and heat across the scalp, right where tension builds during an attack.",
                link = "https://us.breo.com/?ref=ME-SERIES",
                linkNote = "$25 off with code ME-SERIES",
                photo = R.drawable.device_photo_breo_idream,
                communityPros = listOf("Covers scalp, temples and eyes", "Detachable eye cover", "Good build quality"),
                communityCons = listOf("Bulky to wear", "Battery charging issues reported", "Shipping can be slow"),
                communityRating = "4.2/5",
                communitySource = "Trustpilot, 137 reviews",
                communitySourceURL = "https://www.trustpilot.com/review/us.breo.com",
                iconImage = R.drawable.icon_breo
            ),
            ShopItem(
                name = "Breo iNeck 3 Pro",
                iconKey = "breo_neck",
                access = ShopAccess.OTC,
                what = "Wraparound neck massager with deep kneading, air pressure and heat, controlled from your phone.",
                evidence = "Eases neck tension, one of the most common migraine triggers, with kneading and heat before it escalates.",
                link = "https://us.breo.com/?ref=ME-SERIES",
                linkNote = "$25 off with code ME-SERIES",
                photo = R.drawable.device_photo_breo_neck,
                communityPros = listOf("Kneading + heat combo", "Adjustable via the app", "Cordless, easy to wear", "Responsive customer service"),
                communityCons = listOf("Some units stop working early", "Battery charging issues reported", "Shipping can be slow"),
                communityRating = "4.2/5",
                communitySource = "Trustpilot, 137 reviews",
                communitySourceURL = "https://www.trustpilot.com/review/us.breo.com",
                iconImage = R.drawable.icon_breo
            )
        )
    ),
    ShopGroup(
        title = tSync("Nausea"),
        blurb = "For when the sickness is the worst part.",
        devices = listOf(
            ShopItem(
                name = "Reliefband",
                iconKey = "reliefband",
                access = ShopAccess.OTC,
                what = "Wristband that stimulates the median nerve to settle nausea.",
                evidence = "Cleared for nausea and vomiting rather than for migraine. Useful if sickness is the part that floors you."
            )
        )
    ),
    ShopGroup(
        title = tSync("Trigger management"),
        blurb = "Aimed at stress and recovery, which for a lot of people is the trigger rather than the attack.",
        devices = listOf(
            ShopItem(
                name = "Apollo Neuro",
                iconKey = "apollo",
                access = ShopAccess.OTC,
                what = "Silent, soothing vibrations you wear on your wrist or ankle. Calms you down in minutes, rebalances your nervous system and lifts your HRV.",
                evidence = "Built by neuroscientists, with published work on stress, sleep and HRV. Most useful if stress or broken sleep are among your triggers.",
                link = "https://apolloneuro.com/migraineme",
                linkNote = "$99 off, 30-day trial",
                photo = R.drawable.device_photo_apollo
            ),
            ShopItem(
                name = "CalmiGo",
                iconKey = "calmigo",
                access = ShopAccess.OTC,
                what = "Handheld device that paces your breathing and gives feedback as you go.",
                evidence = "Studied for stress and anxiety. Any migraine benefit is indirect, through the trigger rather than the attack."
            ),
            ShopItem(
                name = "HeartMath Inner Balance",
                iconKey = "heartmath",
                access = ShopAccess.OTC,
                what = "Ear or finger sensor that coaches heart rate variability breathing through an app.",
                evidence = "Biofeedback has real support as a preventive approach. This is one of several ways to do it, not the only one."
            )
        )
    ),
    ShopGroup(
        title = tSync("Heat and cold"),
        blurb = "The oldest idea on this page, done with a machine.",
        devices = listOf(
            ShopItem(
                name = "ThermaZone",
                iconKey = "thermazone",
                access = ShopAccess.OTC,
                what = "Circulates hot or cold water through a head or neck pad at a steady temperature.",
                evidence = "No migraine trials to speak of. It is a more controllable ice pack, and priced accordingly."
            )
        )
    )
)

/** Groups with at least one device we can actually link to. */
private val visibleGroups: List<ShopGroup> =
    SHOP_GROUPS.mapNotNull { group ->
        group.devices.filter { it.link != null }
            .takeIf { it.isNotEmpty() }
            ?.let { group.copy(devices = it) }
    }

@Composable
fun ShopScreen(onBack: () -> Unit) {
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
                t("Shop"),
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
                    Icons.Outlined.Info, contentDescription = t("About devices"),
                    tint = AppTheme.SubtleTextColor, modifier = Modifier.size(18.dp)
                )
            }
        }

        Text(
            t("Practical kit for the parts of migraine you can do something about: light, noise and sleep. Every one of them has been through our own bad days first."),
            color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(14.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Text(
                t("We test what we recommend."),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                t("Every device on this page, we've tried ourselves — and negotiated a discount for MigraineMe users on each one."),
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(Modifier.height(20.dp))

        visibleGroups.forEach { group ->
            Text(
                group.title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                t(group.blurb),
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )
            group.devices.forEach { shopItem ->
                ShopCard(
                    item = shopItem,
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
        ShopDisclaimerCard()

        Spacer(Modifier.height(32.dp))
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            containerColor = Color(0xFF241035),
            title = {
                Text(
                    t("About devices"),
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Text(
                    t("Migraine devices are hard to judge from a website, because the marketing all sounds ") +
                        t("the same whether there are ten trials behind it or none.\n\n") +
                        t("This page lists what each one physically does and how solid the evidence is, ") +
                        t("including the ones where it's thin. Prescription devices are marked as such.\n\n") +
                        t("Once you own one, logging it is the point: MigraineMe compares how your attacks go ") +
                        t("with and without it, so after a few weeks you have your own answer rather than the ") +
                        t("manufacturer's."),
                    color = AppTheme.BodyTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(t("Got it"), color = AppTheme.AccentPurple)
                }
            }
        )
    }
}

@Composable
private fun ShopCard(item: ShopItem, onOpenLink: (String) -> Unit) {
    val drawable = remember(item.iconKey) { ReliefIcons.drawableForKey(item.iconKey) }
    val vector = remember(item.iconKey) { ReliefIcons.forKey(item.iconKey) }

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
                if (item.iconImage != null) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(item.iconImage),
                        contentDescription = null,
                        modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp))
                    )
                } else if (drawable != null) {
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
                    item.name,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                // Only the ones that need a doctor say so. Printing "available
                // without a prescription" under every other card was noise.
                if (item.access == ShopAccess.PRESCRIPTION) {
                    Text(
                        t("Prescription only"),
                        color = AppTheme.AccentPink,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            // Real product shot sits opposite the Brainy icon so you can see what
            // the thing actually looks like without leaving the app.
            item.photo?.let { photo ->
                androidx.compose.foundation.Image(
                    painter = painterResource(photo),
                    contentDescription = t("%s product photo", item.name),
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Text(t(item.what), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(6.dp))

        Text(
            t(item.evidence),
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall
        )

        if (item.communityPros != null || item.communityCons != null) {
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(1.dp, Color(0xFF81C784).copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(
                        t("WHAT PEOPLE REPORT"),
                        color = AppTheme.SubtleTextColor,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f)
                    )
                    item.communityRating?.let { rating ->
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                rating,
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            item.communitySource?.let { source ->
                                Text(
                                    t(source),
                                    color = AppTheme.SubtleTextColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                    modifier = Modifier.clickable {
                                        item.communitySourceURL?.let(onOpenLink)
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        item.communityPros?.forEach { line ->
                            Row {
                                Text("+", color = Color(0xFF81C784), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.width(6.dp))
                                Text(t(line), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        item.communityCons?.forEach { line ->
                            Row {
                                Text("–", color = AppTheme.AccentPink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.width(6.dp))
                                Text(t(line), color = AppTheme.BodyTextColor, style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }

        if (item.link != null) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppTheme.AccentPurple.copy(alpha = 0.16f))
                    .clickable { onOpenLink(item.link) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        t("Buy %s", item.name),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    item.linkNote?.let {
                        Text(t(it), color = AppTheme.SubtleTextColor, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Icon(
                    Icons.Outlined.OpenInNew, null,
                    tint = AppTheme.AccentPurple, modifier = Modifier.size(16.dp)
                )
            }
            Text(
                t("We earn a commission."),
                color = AppTheme.SubtleTextColor.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp)
            )
            item.safetyNote?.let {
                Text(
                    t(it),
                    color = AppTheme.SubtleTextColor.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
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
private fun ShopDisclaimerCard() {
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
            t("MigraineMe is not a medical device and does not recommend treatments. ") +
                t("Prescription devices need a conversation with your doctor. ") +
                t("We earn a commission on links to devices."),
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
                contentDescription = t("Dismiss disclaimer"),
                tint = AppTheme.SubtleTextColor,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
