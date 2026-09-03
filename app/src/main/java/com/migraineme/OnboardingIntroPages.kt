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
import androidx.compose.foundation.layout.fillMaxHeight
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
// Layout is the mock's 1080x2400 canvas converted to dp (2.625x): headline 112px
// = 43sp, hand 60px = 23sp, art 1250px tall = 52% of the screen height bleeding
// off one edge, pill 640x120px = 244x46dp centred 76dp above the bottom.

@Composable
fun ObWelcomePage(onNext: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = maxHeight
        Row(Modifier.fillMaxWidth().padding(top = 44.dp, end = 20.dp), horizontalArrangement = Arrangement.End) { LanguageFlagButton() }
        Column(Modifier.fillMaxWidth().padding(top = h * 0.137f).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ObHeadline(t("Welcome to\n*MigraineMe.*"), Modifier.fillMaxWidth(), size = 40.sp)
            Spacer(Modifier.height(h * 0.012f))
            ObHand(t("leave the tracking to me"), Modifier.fillMaxWidth(), size = 22.sp)
        }
        Image(
            painter = painterResource(R.drawable.brainy_guide),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomEnd,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = h * 0.125f)
                .size(width = h * 0.52f, height = h * 0.52f)
                .offset(x = 46.dp)
        )
        ObPillButton(t("Show me how it works"), onNext, Modifier.align(Alignment.BottomCenter).padding(bottom = h * 0.058f).width(244.dp).height(46.dp))
    }
}

// ── How It Works: one idea per screen ───────────────────────────────────────

private class HiwStep(val headline: String, val sub: String, val art: Int, val artLeft: Boolean)

private val hiwSteps = listOf(
    HiwStep("You log your\n*attacks.*", "that is the only thing you have to do", R.drawable.brainy_episodes, false),
    HiwStep("Triggers fill\nyour *bucket.*", "sleep, weather, stress, food...\nmost of it added automatically", R.drawable.ob_bucket, false),
    HiwStep("Near the top?\nIt says *HIGH.*", "your risk, checked every morning", R.drawable.brainy_risk, true),
    HiwStep("It estimates\n*7 days* ahead.", "so you can plan around it", R.drawable.brainy_archer, false),
    HiwStep("Every Monday\nit *learns* you.", "the more you log, the sharper it gets", R.drawable.brainy_gardener, true),
)

@Composable
fun ObHowItWorksPage(onDone: () -> Unit, onSkip: () -> Unit) {
    var idx by rememberSaveable { mutableIntStateOf(0) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = maxHeight
        Column(Modifier.fillMaxWidth().padding(top = h * 0.055f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(t("HOW IT WORKS"), style = ObStyle.label(14.sp).copy(letterSpacing = 1.5.sp))
            Spacer(Modifier.height(h * 0.016f))
            ObDots(hiwSteps.size, idx)
        }
        AnimatedContent(
            targetState = idx,
            transitionSpec = {
                if (targetState > initialState) slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
                else slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
            },
            label = "hiw",
            modifier = Modifier.fillMaxSize()
        ) { i ->
            val s = hiwSteps[i]
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().padding(top = h * 0.148f).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ObHeadline(t(s.headline), Modifier.fillMaxWidth(), size = 40.sp)
                    Spacer(Modifier.height(h * 0.012f))
                    ObHand(t(s.sub), Modifier.fillMaxWidth(), size = 22.sp)
                }
                if (s.art == R.drawable.ob_bucket) {
                    // Mock: bucket 880/1080 wide, bleeding 90px off the right, top at 1150/2400;
                    // four white tiles with real log icons dropping in from the left.
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val w = maxWidth
                        Image(
                            painter = painterResource(R.drawable.ob_bucket),
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = h * 0.479f)
                                .width(w * 0.815f)
                                .offset(x = w * 0.083f)
                        )
                        val tiles = listOf(
                            Triple(R.drawable.brainy_trig_stress, 0.111f, 0.358f),
                            Triple(R.drawable.brainy_trig_storm, 0.306f, 0.400f),
                            Triple(R.drawable.brainy_trig_caffeine, 0.102f, 0.4625f),
                            Triple(R.drawable.brainy_act_screen_time, 0.324f, 0.5125f),
                        )
                        tiles.forEach { (icon, fx, fy) ->
                            Box(
                                Modifier.offset(x = w * fx, y = h * fy).size(76.dp).background(Color.White, androidx.compose.foundation.shape.CircleShape),
                                contentAlignment = Alignment.Center
                            ) { Image(painterResource(icon), null, modifier = Modifier.size(46.dp)) }
                        }
                    }
                } else {
                    // The art bleeds off one edge, like the store screens (mock: ±140px = 53dp).
                    Image(
                        painter = painterResource(s.art),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        alignment = if (s.artLeft) Alignment.BottomStart else Alignment.BottomEnd,
                        modifier = Modifier
                            .align(if (s.artLeft) Alignment.BottomStart else Alignment.BottomEnd)
                            .padding(bottom = h * 0.108f)
                            .size(width = h * 0.52f, height = h * 0.52f)
                            .offset(x = if (s.artLeft) (-53).dp else 53.dp)
                    )
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = h * 0.058f),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            if (idx > 0) ObPillButton(t("Back"), { idx-- }, Modifier.width(84.dp).height(46.dp), filled = false)
            ObPillButton(t("Next"), { if (idx < hiwSteps.lastIndex) idx++ else onDone() }, Modifier.width(if (idx > 0) 175.dp else 244.dp).height(46.dp))
        }
        Text(
            t("Skip"), style = ObStyle.label(14.sp).copy(color = ObStyle.Muted),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = h * 0.012f).clickable { onSkip() }.padding(6.dp)
        )
    }
}

// ── Choice: tour / profile / straight in ────────────────────────────────────

@Composable
fun ObChoicePage(onTakeTour: () -> Unit, onSetUpProfile: () -> Unit, onGoToApp: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(8.dp))
        ObHeadline(t("How do you\nwant to *start?*"), Modifier.fillMaxWidth(), size = 32.sp)
        Spacer(Modifier.height(2.dp))
        ObHand(t("you can rerun any of this from Profile"), Modifier.fillMaxWidth(), size = 18.sp)
        Spacer(Modifier.height(10.dp))

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
        Spacer(Modifier.height(8.dp))
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
        Spacer(Modifier.height(8.dp))
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
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(ObStyle.CardFill)
            .border(if (filled) 2.dp else 1.dp, if (filled) ObStyle.Pink else ObStyle.CardLine.copy(alpha = 0.6f), shape)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(painterResource(pose), null, modifier = Modifier.size(46.dp))
            Column(Modifier.weight(1f)) {
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (filled) ObStyle.Pink else Color(0xFF5A3C82))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(badge.uppercase(), style = ObStyle.label(9.sp).copy(
                        color = if (filled) ObStyle.Ink else Color.White, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp))
                }
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(title, style = ObStyle.headline(19.sp))
                    Spacer(Modifier.width(8.dp))
                    Text(minutes, style = ObStyle.label(12.sp), modifier = Modifier.padding(bottom = 2.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        points.forEachIndexed { i, p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(16.dp).padding(top = 1.dp).border(1.dp, if (filled) ObStyle.Pink else ObStyle.CardLine, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = ObStyle.label(9.sp).copy(color = if (filled) ObStyle.Pink else ObStyle.CardLine, fontWeight = FontWeight.Bold))
                }
                Spacer(Modifier.width(8.dp))
                Text(p, style = ObStyle.body(12.sp), modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(miss, style = ObStyle.label(11.sp).copy(color = ObStyle.Miss))
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ObPillButton(button, onClick, Modifier.height(36.dp), filled = filled, textSize = 13.sp, vPad = 4.dp)
        }
    }
}
