package com.migraineme

import android.content.Intent
import android.net.Uri
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
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The Shop: things worth trying, shown next to the user's own data.
 *
 * The cards are not in this file any more. [ShopCatalogue] fetches them from
 * Postgres, which is what lets a card be edited, a partner added or an
 * affiliate link swapped without four code changes and a store release. The
 * server also decides which store a card points at and whether it appears in
 * the viewer's country at all, so nothing here knows about regions.
 */

@Composable
fun ShopScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var showInfo by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf<List<ShopCatalogue.Group>?>(null) }

    LaunchedEffect(LangPrefs.get()) {
        groups = ShopCatalogue.load(context)
    }

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
                    Icons.Outlined.Info, contentDescription = t("About the Shop"),
                    tint = AppTheme.SubtleTextColor, modifier = Modifier.size(18.dp)
                )
            }
        }

        Text(
            t("Practical kit for the parts of migraine you can do something about: light, noise and sleep. Every one of them has been through our own bad days first."),
            color = AppTheme.BodyTextColor,
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(20.dp))

        val loaded = groups
        when {
            loaded == null -> {
                // First open with a cold cache. A spinner beats an empty page
                // that looks like we sell nothing.
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = AppTheme.AccentPurple, strokeWidth = 2.dp)
                }
            }
            loaded.isEmpty() -> {
                Text(
                    t("Nothing to show here yet. Check back once you have a connection."),
                    color = AppTheme.SubtleTextColor,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            else -> loaded.forEach { group ->
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
                group.items.forEach { shopItem ->
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
        }

        // The Shop needs its own wording: the Insights disclaimer is about risk
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
                    t("About the Shop"),
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                Text(
                    t("This stuff is hard to judge from a website, because the marketing all sounds ") +
                        t("the same whether there are ten trials behind it or none.\n\n") +
                        t("Each card says what the thing physically does, how solid the evidence is, ") +
                        t("including where it is thin, and what buyers complain about. Anything that ") +
                        t("needs a prescription is marked.\n\n") +
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
private fun ShopCard(item: ShopCatalogue.Item, onOpenLink: (String) -> Unit) {
    // icon_key names art in the shared Brainy set, which ships in the APK, so
    // the icon draws with no network even when the photo below it has not
    // loaded. Older rows still carry relief keys, hence the fallbacks.
    val brainy = remember(item.iconKey) { BrainyLogManifest.ART[item.iconKey] }
    val drawable = remember(item.iconKey) { brainy ?: ReliefIcons.drawableForKey(item.iconKey) }
    val vector = remember(item.iconKey) { if (drawable != null) null else ReliefIcons.forKey(item.iconKey) }

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
                    item.name,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                // Only the ones that need a doctor say so. Printing "available
                // without a prescription" under every other card was noise.
                if (item.prescriptionOnly) {
                    Text(
                        t("Prescription only"),
                        color = AppTheme.AccentPink,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            // Real product shot sits opposite the Brainy icon so you can see what
            // the thing actually looks like without leaving the app.
            item.photoUrl?.let { photo ->
                AsyncImage(
                    model = photo,
                    contentScale = ContentScale.Crop,
                    contentDescription = t("%s product photo", item.name),
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Text(item.what, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(6.dp))

        Text(
            item.evidence.orEmpty(),
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall
        )

        if (item.pros.isNotEmpty() || item.cons.isNotEmpty()) {
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
                    item.rating?.let { rating ->
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                rating,
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            item.ratingSource?.let { source ->
                                Text(
                                    source,
                                    color = AppTheme.SubtleTextColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                    modifier = Modifier.clickable {
                                        item.ratingUrl?.let(onOpenLink)
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        item.pros.forEach { line ->
                            Row {
                                Text("+", color = Color(0xFF81C784), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.width(6.dp))
                                Text(line, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        item.cons.forEach { line ->
                            Row {
                                Text("–", color = AppTheme.AccentPink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.width(6.dp))
                                Text(line, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.labelSmall)
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
        }

        run {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppTheme.AccentPurple.copy(alpha = 0.16f))
                    .clickable { onOpenLink(item.url) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        t("Buy %s", item.name),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    // The code the partner gave us for our users, when there
                    // is one. Alpine pays commission and offers nothing, so
                    // its cards simply have no second line.
                    item.note?.let {
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
