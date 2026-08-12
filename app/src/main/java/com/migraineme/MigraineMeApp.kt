package com.migraineme

import android.app.Application
import com.google.firebase.FirebaseApp

class MigraineMeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        // Hydrate display-units prefs (°C/°F, m/ft) from storage before any
        // screen or formatter reads them. See UnitsPrefs.
        UnitsPrefs.init(this)
        // Same contract for display language: canonical data stays English,
        // we translate at the render boundary. See LangPrefs.
        LangPrefs.init(this)
    }
}
