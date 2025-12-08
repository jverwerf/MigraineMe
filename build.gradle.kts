plugins {
    id("com.android.application") version "8.6.0" apply false
    // Kotlin 1.9.x (stable K1 frontend)
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // ➕ Serialization plugin (needed for Ktor JSON)
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
}
