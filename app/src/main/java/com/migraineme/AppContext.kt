// app/src/main/java/com/migraineme/AppContext.kt
package com.migraineme

import android.content.Context

object AppContext {
    lateinit var app: Context
        private set

    fun init(context: Context) {
        app = context.applicationContext
    }
}
