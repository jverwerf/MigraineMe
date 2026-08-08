plugins {
    // 8.9.x is the minimum that accepts compileSdk 36 (Play requires target 36 from Aug 31 2026)
    id("com.android.application") version "8.9.3" apply false
    // Kotlin 1.9.x (stable K1 frontend)
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // ➕ Serialization plugin (needed for Ktor JSON)
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
    // Firebase
    id("com.google.gms.google-services") version "4.4.2" apply false
}