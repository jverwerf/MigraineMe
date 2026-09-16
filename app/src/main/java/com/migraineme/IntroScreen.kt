// FILE: app/src/main/java/com/migraineme/IntroScreen.kt
package com.migraineme

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Has this device already been shown the pre-login intro?
 *
 * Deliberately device-scoped and not per-account: the whole point is that it
 * runs before anyone has an account. Survives sign-out, so someone who logs
 * out and back in is not pitched the app again.
 */
object IntroPrefs {
    private const val PREFS = "intro_prefs"
    private const val KEY_SEEN = "seen_intro"
    private const val KEY_SIGNED_IN = "ever_signed_in"
    private const val KEY_INIT = "initialised"

    /**
     * Decide, once, whether this install predates the pre-login intro.
     *
     * Without this, an existing user who happened to be signed out when they
     * took the update would be shown the pitch for an app they already use,
     * and then met by a login screen in its first-time state, which sends
     * "Continue with email" to the signup form. firstInstallTime differs from
     * lastUpdateTime on anything that has ever been updated, so an upgrade is
     * marked as already seen and already signed in, and only a genuinely fresh
     * install can reach the deck.
     *
     * Must run before the start destination is read.
     */
    fun ensureInitialised(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_INIT, false)) return
        val isUpgrade = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.firstInstallTime != info.lastUpdateTime
        } catch (_: Throwable) {
            // Cannot tell: treat as an upgrade. Showing the deck to someone who
            // has already paid for the app is the worse of the two mistakes.
            true
        }
        val editor = prefs.edit().putBoolean(KEY_INIT, true)
        if (isUpgrade) {
            editor.putBoolean(KEY_SEEN, true).putBoolean(KEY_SIGNED_IN, true)
        }
        editor.apply()
    }

    fun seen(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)

    fun markSeen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SEEN, true).apply()
    }

    /**
     * Has anyone ever completed a sign-in on this device?
     *
     * Kept here rather than read off SessionStore because SessionStore.clear()
     * wipes user_id on sign-out, which would make a returning user look like a
     * brand new one and greet them with "Let's get you set up" again.
     */
    fun hasEverSignedIn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SIGNED_IN, false)

    fun markSignedIn(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SIGNED_IN, true).apply()
    }
}

/**
 * The welcome + how-it-works deck, shown BEFORE the login screen.
 *
 * These two pages were already built for OnboardingScreen, which only runs
 * once someone has signed in — so nobody deciding whether to sign up had ever
 * seen the pitch. Both pages take lambdas only: no auth, no network, no view
 * model, which is why they can run with no session.
 *
 * The shell (flat background, edge-to-edge lattice, sliding AnimatedContent)
 * is copied from OnboardingScreen so the pages look identical either side of
 * the login screen. The third page, ObChoicePage, deliberately stays behind
 * the wall: it feeds the tour and AI setup, both of which need a session.
 */
@Composable
fun IntroScreen(onFinished: () -> Unit) {
    var idx by rememberSaveable { mutableIntStateOf(0) }

    val bgBrush = remember { Brush.verticalGradient(listOf(Color(0xFF1E1330), Color(0xFF1E1330))) }

    Box(Modifier.fillMaxSize().background(bgBrush)) {
        // Edge to edge: the Scaffold reserves the status and gesture bars, so the
        // lattice is pulled up and stretched past both, or two bands show.
        val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Box(Modifier.offset(y = -statusInset).width(maxWidth).height(maxHeight + statusInset + navInset + 48.dp)) {
                ObLatticeBackground(dim = false)
            }
        }

        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(20.dp))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = idx,
                    transitionSpec = {
                        if (targetState > initialState) slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
                        else slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
                    },
                    label = "intro"
                ) { i ->
                    when (i) {
                        0 -> ObWelcomePage(onNext = { idx = 1 })
                        else -> ObHowItWorksPage(onDone = onFinished, onSkip = onFinished)
                    }
                }
            }
        }
    }
}
