package com.migraineme

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.google.firebase.FirebaseApp

class MigraineMeApp : Application(), Configuration.Provider {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        // Hydrate display-units prefs (°C/°F, m/ft) from storage before any
        // screen or formatter reads them. See UnitsPrefs.
        UnitsPrefs.init(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()
}
