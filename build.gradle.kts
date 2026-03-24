plugins {
    id("com.android.application") version "8.6.0" apply false
    // Kotlin 2.0.x (K2 compiler)
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // ➕ Serialization plugin (needed for Ktor JSON)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    // Compose Compiler Gradle plugin (required for Kotlin 2.0+)
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // Firebase
    id("com.google.gms.google-services") version "4.4.2" apply false
}