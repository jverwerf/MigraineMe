package com.migraineme.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.migraineme.MainActivity

/** PendingIntents the home-screen widgets share. */
object WidgetIntents {

    /**
     * Opens the app, optionally on [route] (MainActivity's `navigate_to`, the
     * same door notification taps use). SINGLE_TOP so a running app gets the
     * route through onNewIntent instead of being recreated.
     */
    fun openApp(context: Context, requestCode: Int, route: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (route != null) putExtra("navigate_to", route)
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** The Premium face every widget shows without Premium; tap opens the paywall. */
    fun lockedViews(context: Context, requestCode: Int): android.widget.RemoteViews {
        val views = android.widget.RemoteViews(context.packageName, com.migraineme.R.layout.widget_migraineme_locked)
        views.setTextViewText(com.migraineme.R.id.widget_locked_title, com.migraineme.tSync("Premium"))
        views.setTextViewText(com.migraineme.R.id.widget_locked_sub, com.migraineme.tSync("Tap to unlock"))
        views.setOnClickPendingIntent(com.migraineme.R.id.widget_locked_root,
            openApp(context, requestCode, com.migraineme.Routes.PAYWALL))
        return views
    }
}
