package com.migraineme.widget

import android.content.Context
import com.migraineme.PremiumAccess
import com.migraineme.PremiumManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Every home-screen widget is Premium (Jordy 2026-10-29). Widgets can wake up
 * in a process where PremiumManager has not loaded yet, so the last answer the
 * app saw is kept in prefs. Unknown (never stored) counts as locked; the
 * app flips it the moment entitlement loads.
 */
object WidgetPremium {
    private const val PREFS = "widget_prefs"
    private const val KEY_ENTITLED = "entitled"

    fun isEntitled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENTITLED, false)

    /** Called once from the Application: mirrors every settled premium answer and refreshes the widgets. */
    fun watch(context: Context) {
        val appCtx = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            PremiumManager.state
                .map { it.access }
                .filter { it != PremiumAccess.LOADING }
                .map { it == PremiumAccess.ENTITLED }
                .distinctUntilChanged()
                .collect { entitled ->
                    val prefs = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    if (prefs.getBoolean(KEY_ENTITLED, false) != entitled || !prefs.contains(KEY_ENTITLED)) {
                        prefs.edit().putBoolean(KEY_ENTITLED, entitled).apply()
                        MigraineMeWidget.requestUpdate(appCtx)
                    }
                }
        }
    }
}
