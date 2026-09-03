package com.migraineme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The three pages that open onboarding: Welcome → How It Works (5 screens) →
 * Choice (tour / profile / straight in). Pure presentation; every exit is a
 * callback the screen wires, so seeding, trial and completion rules stay
 * where they were.
 */

// ── Welcome ──────────────────────────────────────────────────────────────────

@Composable
fun ObWelcomePage(onNext: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Spacer(Modifier.height(44.dp))
            LanguageFlagButton()
        }
        Spacer(Modifier.height(48.dp))
        ObHeadline(t("Welcome to *MigraineMe.*"), Modifier.fillMaxWidth(), size = 38.sp)
        Spacer(Modifier.height(10.dp))
        ObHand(t("leave the tracking to me"), Modifier.fillMaxWidth(), size = 24.sp)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {
            Image(
                painter = painterResource(R.drawable.brainy_recs),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(0.8f).offset(x = 40.dp, y = 12.dp)
            )
        }
        ObPillButton(t("Show me how it works"), onNext, Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
    }
}

// ── How It Works: one idea per screen ───────────────────────────────────────

private class HiwStep(val headline: String, val sub: String, val art: Int, val artLeft: Boolean)

private val hiwSteps = listOf(
    HiwStep("You log your *attacks.*", "that is the only thing you have to do", R.drawable.brainy_migraines, false),
    HiwStep("Triggers fill your *bucket.*", "sleep, weather, stress, food...\nmost of it added automatically", R.drawable.ob_bucket, false),
    HiwStep("Near the top? It says *HIGH.*", "your risk, checked every morning", R.drawable.brainy_risk, true),
    HiwStep("It estimates *7 days* ahead.", "so you can plan around it", R.drawable.brainy_archer, false),
    HiwStep("Every Monday it *learns* you.", "the more you log, the sharper it gets", R.drawable.brainy_gardener, true),
)

@Composable
fun ObHowItWorksPage(onDone: () -> Unit, onSkip: () -> Unit) {
    var idx by rememberSaveable { mutableIntStateOf(0) }
    val step = hiwSteps[idx]

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(28.dp))
        Text(t("HOW IT WORKS"), style = ObStyle.label(12.sp).copy(letterSpacing = 1.5.sp))
        Spacer(Modifier.height(8.dp))
        ObDots(hiwSteps.size, idx)

        AnimatedContent(
            targetState = idx,
            transitionSpec = {
                if (targetState > initialState) slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
                else slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
            },
            label = "hiw",
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { i ->
            val s = hiwSteps[i]
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(28.dp))
                ObHeadline(t(s.headline), Modifier.fillMaxWidth(), size = 36.sp)
                Spacer(Modifier.height(10.dp))
                ObHand(t(s.sub), Modifier.fillMaxWidth(), size = 22.sp)
                // The art bleeds off one edge, like the store screens.
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val w = maxWidth
                    Image(
                        painter = painterResource(s.art),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        alignment = if (s.artLeft) Alignment.BottomStart else Alignment.BottomEnd,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 12.dp)
                            .offset(x = if (s.artLeft) -(w * 0.12f) else (w * 0.12f), y = 8.dp)
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (idx > 0) ObPillButton(t("Back"), { idx-- }, Modifier.weight(0.42f), filled = false)
            ObPillButton(t("Next"), { if (idx < hiwSteps.lastIndex) idx++ else onDone() }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            t("Skip"), style = ObStyle.label(14.sp).copy(color = ObStyle.Muted),
            modifier = Modifier.clickable { onSkip() }.padding(8.dp)
        )
        Spacer(Modifier.height(8.dp))
    }
}

// ── Choice: tour / profile / straight in ────────────────────────────────────

@Composable
fun ObChoicePage(onTakeTour: () -> Unit, onSetUpProfile: () -> Unit, onGoToApp: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(20.dp))
        ObHeadline(t("How do you want to *start?*"), Modifier.fillMaxWidth(), size = 32.sp)
        Spacer(Modifier.height(6.dp))
        ObHand(t("you can rerun any of this from Profile"), Modifier.fillMaxWidth(), size = 20.sp)
        Spacer(Modifier.height(18.dp))

        ObChoiceCard(
            pose = R.drawable.brainy_recs_small,
            badge = t("Recommended"),
            title = t("Take the tour"),
            minutes = t("~5 min"),
            points = listOf(
                t("Ten real screens, each explained in one card"),
                t("Where everything lives, tab by tab"),
                t("Ends with connecting your wearable and your profile"),
            ),
            miss = t("You miss nothing."),
            button = t("Show me the app"),
            filled = true,
            onClick = onTakeTour
        )
        Spacer(Modifier.height(12.dp))
        ObChoiceCard(
            pose = R.drawable.brainy_ask_small,
            badge = t("Minimal setup for the app to work"),
            title = t("Set up my profile"),
            minutes = t("~3 min"),
            points = listOf(
                t("A few questions about your migraines"),
                t("The AI drafts your triggers, thresholds and pool"),
                t("Connect your wearable at the end"),
            ),
            miss = t("You miss: the tour. Rerun it anytime from Profile."),
            button = t("Answer a few questions"),
            filled = false,
            onClick = onSetUpProfile
        )
        Spacer(Modifier.height(12.dp))
        ObChoiceCard(
            pose = R.drawable.brainy_migraines_small,
            badge = t("In an attack? This one"),
            title = t("Go to the app"),
            minutes = t("0 min"),
            points = listOf(
                t("Straight in, nothing to fill in"),
                t("Log your attacks, that is the only thing you have to do"),
                t("The AI proposes your profile every Monday, after about 5 attacks") + " · " + t("Premium"),
            ),
            miss = t("You miss: the tour, the AI head start and your wearable, until you open Settings."),
            button = t("Start now"),
            filled = false,
            onClick = onGoToApp
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ObChoiceCard(
    pose: Int, badge: String, title: String, minutes: String,
    points: List<String>, miss: String, button: String, filled: Boolean, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(ObStyle.CardFill)
            .border(if (filled) 2.dp else 1.dp, if (filled) ObStyle.Pink else ObStyle.CardLine.copy(alpha = 0.6f), shape)
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(pose), null, modifier = Modifier.size(56.dp))
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (filled) ObStyle.Pink else Color(0xFF5A3C82))
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(badge.uppercase(), style = ObStyle.label(10.sp).copy(
                        color = if (filled) ObStyle.Ink else Color.White, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp))
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(title, style = ObStyle.headline(22.sp))
                    Spacer(Modifier.width(8.dp))
                    Text(minutes, style = ObStyle.label(13.sp), modifier = Modifier.padding(bottom = 3.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        points.forEachIndexed { i, p -> ObNumberedPoint(i + 1, p, accent = if (filled) ObStyle.Pink else ObStyle.CardLine) }
        Spacer(Modifier.height(6.dp))
        Text(miss, style = ObStyle.label(13.sp).copy(color = ObStyle.Miss))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ObPillButton(button, onClick, filled = filled)
        }
    }
}
