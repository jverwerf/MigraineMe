package com.migraineme

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Quick Log Strip — sits above the gauge on the Home screen.
 *
 * Five icon buttons: Migraine, Trigger, Prodrome, Medicine, Relief.
 * Tap → shows favorites bottom sheet (skippable) → saves instantly with timestamp = now.
 * No extra input required. Straight into journal.
 */

// ─── Data types for the strip ───────────────────────────────────────

enum class QuickLogCategory(val label: String, val color: Color) {
    MIGRAINE("Migraine", Color(0xFFE57373)),
    TRIGGER("Trigger", Color(0xFFFFB74D)),
    PRODROME("Prodrome", Color(0xFF9575CD)),
    MEDICINE("Medicine", Color(0xFF4FC3F7)),
    RELIEF("Relief", Color(0xFF81C784));
}

data class QuickLogFavorite(
    val label: String,
    val iconKey: String? = null,
    val category: QuickLogCategory? = null,
)

// ─── Main Strip Composable ──────────────────────────────────────────

@Composable
fun QuickLogStrip(
    authVm: AuthViewModel,
    triggerVm: TriggerViewModel,
    medicineVm: MedicineViewModel,
    reliefVm: ReliefViewModel,
    prodromeVm: ProdromeViewModel,
    symptomVm: SymptomViewModel,
    onLogComplete: () -> Unit = {},   // callback to refresh home data
) {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val authState by authVm.state.collectAsState()

    // Load all pools on mount
    LaunchedEffect(authState.accessToken) {
        authState.accessToken?.let { token ->
            triggerVm.loadAll(token)
            medicineVm.loadAll(token)
            reliefVm.loadAll(token)
            prodromeVm.loadAll(token)
            symptomVm.loadAll(token)
        }
    }

    // Favorites from each VM
    val triggerPool by triggerVm.pool.collectAsState()
    val triggerFreq by triggerVm.frequent.collectAsState()
    val medicinePool by medicineVm.pool.collectAsState()
    val medicineFreq by medicineVm.frequent.collectAsState()
    val reliefPool by reliefVm.pool.collectAsState()
    val reliefFreq by reliefVm.frequent.collectAsState()
    val prodromePool by prodromeVm.pool.collectAsState()
    val prodromeFreq by prodromeVm.frequent.collectAsState()
    val symptomFavs by symptomVm.favorites.collectAsState()

    // Build favorite lists
    val triggerFavs = remember(triggerFreq, triggerPool) {
        triggerFreq.mapNotNull { pref ->
            triggerPool.find { it.id == pref.triggerId }?.let {
                QuickLogFavorite(it.label, it.iconKey, QuickLogCategory.TRIGGER)
            }
        }
    }
    val medicineFavs = remember(medicineFreq, medicinePool) {
        medicineFreq.mapNotNull { pref ->
            medicinePool.find { it.id == pref.medicineId }?.let {
                // Medicines use category as icon key (e.g. "Triptan", "Analgesic")
                QuickLogFavorite(it.label, it.category, QuickLogCategory.MEDICINE)
            }
        }
    }
    val reliefFavs = remember(reliefFreq, reliefPool) {
        reliefFreq.mapNotNull { pref ->
            reliefPool.find { it.id == pref.reliefId }?.let {
                QuickLogFavorite(it.label, it.iconKey, QuickLogCategory.RELIEF)
            }
        }
    }
    val prodromeFavs = remember(prodromeFreq, prodromePool) {
        prodromeFreq.mapNotNull { pref ->
            prodromePool.find { it.id == pref.prodromeId }?.let {
                QuickLogFavorite(it.label, it.iconKey, QuickLogCategory.PRODROME)
            }
        }
    }
    // Postdrome symptoms are recovery symptoms after an attack, not the attack
    // itself. Exclude them from migraine quick log (the wizard already does this).
    val migraineFavs = remember(symptomFavs) {
        symptomFavs.mapNotNull { pref ->
            pref.symptom?.takeIf { it.category != "Postdrome" }?.let { s ->
                QuickLogFavorite(s.label, s.iconKey, QuickLogCategory.MIGRAINE)
            }
        }
    }

    // Sheet state
    var activeCategory by remember { mutableStateOf<QuickLogCategory?>(null) }
    var saving by remember { mutableStateOf(false) }
    var savedLabel by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(false) }

    val infoText = "Tap any icon to log something in one tap with the timestamp set to right now. Use it mid-attack or whenever you don't have the bandwidth for a full log.\n\nIf you've starred favourites in your pools (your usual triggers, rescue medicines, etc.), the button opens a quick sheet of those so you can pick the specific one and confirm. No favourites set, or no time to choose? Tap \"Skip, just log Migraine\" (or \"Trigger\" / \"Medicine\" / etc.) and it saves immediately with a generic label.\n\nYou'll notice postdrome isn't here. That's on purpose: postdromes are the recovery symptoms that belong to a specific migraine, so we want them linked to an attack rather than floating on their own. We'll prompt you for them in your daily check-in whenever you have an open migraine, and you can also log them via the full wizard or by editing the migraine afterwards.\n\nFor a full attack log with timing, symptoms, pain location, prodromes and medicines, use the Log tab. Quick log is the shortcut for when you can't deal with all of that."

    // Confirmation toast
    LaunchedEffect(savedLabel) {
        if (savedLabel != null) {
            delay(1800)
            savedLabel = null
        }
    }

    fun favsFor(cat: QuickLogCategory): List<QuickLogFavorite> = when (cat) {
        QuickLogCategory.MIGRAINE -> migraineFavs
        QuickLogCategory.TRIGGER -> triggerFavs
        QuickLogCategory.PRODROME -> prodromeFavs
        QuickLogCategory.MEDICINE -> medicineFavs
        QuickLogCategory.RELIEF -> reliefFavs
    }

    fun doSave(cat: QuickLogCategory, label: String?) {
        val token = authState.accessToken ?: return
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val db = SupabaseDbService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
                    val now = Instant.now().toString()
                    when (cat) {
                        QuickLogCategory.MIGRAINE -> db.insertMigraine(
                            accessToken = token,
                            type = label ?: "Migraine",
                            severity = 5,
                            startAt = now,
                            endAt = null,
                            notes = null
                        )
                        QuickLogCategory.TRIGGER -> db.insertTrigger(
                            accessToken = token,
                            migraineId = null,
                            type = label ?: "Unknown",
                            startAt = now,
                            notes = null
                        )
                        QuickLogCategory.PRODROME -> db.insertProdrome(
                            accessToken = token,
                            migraineId = null,
                            type = label ?: "Unknown",
                            startAt = now,
                            notes = null
                        )
                        QuickLogCategory.MEDICINE -> db.insertMedicine(
                            accessToken = token,
                            migraineId = null,
                            name = label ?: "Unknown",
                            amount = null,
                            startAt = now,
                            notes = null
                        )
                        QuickLogCategory.RELIEF -> db.insertRelief(
                            accessToken = token,
                            migraineId = null,
                            type = label ?: "Unknown",
                            startAt = now,
                            notes = null
                        )
                    }
                    // Trigger correlation recompute for migraines
                    if (cat == QuickLogCategory.MIGRAINE) {
                        try { EdgeFunctionsService().triggerCorrelationCompute(ctx) }
                        catch (e: Exception) { e.printStackTrace() }
                    }
                }
                savedLabel = "${cat.label}: ${label ?: cat.label}"
                activeCategory = null
                onLogComplete()
            } catch (e: Exception) {
                Log.e("QuickLogStrip", "Save failed", e)
            } finally {
                saving = false
            }
        }
    }

    // ── UI ──

    Column {
        // Saved confirmation
        AnimatedVisibility(
            visible = savedLabel != null,
            enter = fadeIn() + slideInVertically(),
            exit = fadeOut() + slideOutVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .background(Color(0xFF2E7D32).copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Logged: ${savedLabel ?: ""}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                )
            }
        }

        // Quick log card
        Box(modifier = Modifier.fillMaxWidth()) {
            BaseCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Quick log",
                    color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    QuickLogCategory.entries.forEach { cat ->
                        QuickLogButton(
                            category = cat,
                            onClick = {
                                val favs = favsFor(cat)
                                if (favs.isEmpty()) {
                                    doSave(cat, cat.label)
                                } else {
                                    activeCategory = cat
                                }
                            }
                        )
                    }
                }
            }
            IconButton(
                onClick = { showInfo = true },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 10.dp, y = (-14).dp)
                    .size(34.dp)
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = "About Quick log",
                    tint = AppTheme.SubtleTextColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    // ── Bottom sheet for favorites ──
    if (activeCategory != null) {
        val cat = activeCategory!!
        val favs = favsFor(cat)

        QuickLogFavoritesSheet(
            category = cat,
            favorites = favs,
            saving = saving,
            onSelect = { label -> doSave(cat, label) },
            onSkip = { doSave(cat, cat.label) },
            onDismiss = { activeCategory = null }
        )
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text("Got it", color = AppTheme.AccentPurple)
                }
            },
            title = {
                Text("About Quick log", color = AppTheme.TitleColor,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            },
            text = {
                Text(infoText, color = AppTheme.BodyTextColor, style = MaterialTheme.typography.bodyMedium)
            },
            containerColor = AppTheme.BaseCardContainer
        )
    }
}

// ─── Single Quick Log Button ────────────────────────────────────────

@Composable
private fun QuickLogButton(
    category: QuickLogCategory,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(58.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(category.color.copy(alpha = 0.15f))
                .border(1.dp, category.color.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .drawBehind {
                        HubIcons.run {
                            when (category) {
                                QuickLogCategory.MIGRAINE -> drawMigraineStarburst(category.color)
                                QuickLogCategory.TRIGGER -> drawTriggerBolt(category.color)
                                QuickLogCategory.PRODROME -> drawProdromeEye(category.color)
                                QuickLogCategory.MEDICINE -> drawMedicinePill(category.color)
                                QuickLogCategory.RELIEF -> drawReliefLeaf(category.color)
                            }
                        }
                    }
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            category.label,
            color = AppTheme.SubtleTextColor,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ─── Favorites Bottom Sheet ─────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun QuickLogFavoritesSheet(
    category: QuickLogCategory,
    favorites: List<QuickLogFavorite>,
    saving: Boolean,
    onSelect: (String) -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AppTheme.BaseCardContainer.copy(alpha = 0.92f),
        contentColor = Color.White,
        dragHandle = {
            Box(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                contentAlignment = Alignment.Center
            ) {
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
            // Header
            Text(
                "Quick ${category.label}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap a favourite to log it now",
                color = AppTheme.SubtleTextColor,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(16.dp))

            // Favorites grid — circle icons like the rest of the app
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                favorites.forEach { fav ->
                    FavoriteCircleButton(
                        label = fav.label,
                        iconKey = fav.iconKey,
                        category = category,
                        enabled = !saving,
                        onClick = { onSelect(fav.label) }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Skip button — logs with default label
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss, enabled = !saving) {
                    Text("Cancel", color = AppTheme.SubtleTextColor)
                }
                Button(
                    onClick = onSkip,
                    enabled = !saving,
                    colors = ButtonDefaults.buttonColors(containerColor = category.color.copy(alpha = 0.8f))
                ) {
                    Text(
                        if (saving) "Saving…" else "Skip — just log \"${category.label}\"",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FavoriteCircleButton(
    label: String,
    iconKey: String?,
    category: QuickLogCategory,
    enabled: Boolean,
    onClick: () -> Unit
) {
    // Resolve icon from the appropriate icon set
    // Try iconKey first, then fall back to label-based matching
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = when (category) {
        QuickLogCategory.TRIGGER -> TriggerIcons.forKey(iconKey) ?: TriggerIcons.forKey(label.lowercase())
        QuickLogCategory.PRODROME -> ProdromeIcons.forKey(iconKey) ?: ProdromeIcons.forKey(label.lowercase())
        QuickLogCategory.MEDICINE -> MedicineIcons.forKey(iconKey) ?: MedicineIcons.forKey(label)
        QuickLogCategory.RELIEF -> ReliefIcons.forKey(iconKey) ?: ReliefIcons.forKey(label.lowercase())
        QuickLogCategory.MIGRAINE -> SymptomIcons.forLabel(label, iconKey)
    }

    val bg = category.color.copy(alpha = 0.15f)
    val border = category.color.copy(alpha = 0.40f)
    val iconTint = Color.White
    val textColor = AppTheme.BodyTextColor

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(bg)
                .border(1.5.dp, border, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(24.dp))
            } else {
                Text(
                    label.take(2).uppercase(),
                    color = iconTint,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            color = textColor,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
