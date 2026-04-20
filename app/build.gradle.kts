plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp") version "1.9.24-1.0.20"  // For Room
    id("com.google.gms.google-services")  // Firebase
}

// Fix ListenableFuture classpath without duplicate classes:
// - remove the empty placeholder module if it appears
configurations.all {
    exclude(group = "com.google.guava", module = "listenablefuture")
}

// ── Load secrets from local.properties (gitignored — never committed) ──
fun localProp(key: String): String {
    val f = rootProject.file("local.properties")
    if (!f.exists()) return ""
    val match = f.readLines().firstOrNull { it.startsWith("$key=") } ?: return ""
    return match.substringAfter("=").trim().replace("\\", "\\\\")
}

android {
    namespace = "com.migraineme"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.migraineme"
        minSdk = 26
        targetSdk = 35
        versionCode = 33
        versionName = "1.0"

        // ── All keys loaded from local.properties ──
        buildConfigField("String", "SUPABASE_URL",
            "\"${localProp("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY",
            "\"${localProp("SUPABASE_ANON_KEY")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID",
            "\"${localProp("GOOGLE_WEB_CLIENT_ID")}\"")
        buildConfigField("String", "USDA_API_KEY",
            "\"${localProp("USDA_API_KEY")}\"")
        buildConfigField("String", "REVENUECAT_API_KEY",
            "\"${localProp("REVENUECAT_API_KEY")}\"")
        buildConfigField("String", "WHOOP_CLIENT_ID",
            "\"${localProp("WHOOP_CLIENT_ID")}\"")
        buildConfigField("String", "OURA_CLIENT_ID",
            "\"${localProp("OURA_CLIENT_ID")}\"")
        buildConfigField("String", "POLAR_CLIENT_ID",
            "\"${localProp("POLAR_CLIENT_ID")}\"")
        buildConfigField("String", "POLAR_CLIENT_SECRET",
            "\"${localProp("POLAR_CLIENT_SECRET")}\"")
        buildConfigField("String", "GARMIN_CLIENT_ID",
            "\"${localProp("GARMIN_CLIENT_ID")}\"")
    }

    signingConfigs {
        create("release") {
            val storeFilePath = localProp("RELEASE_STORE_FILE")
            if (storeFilePath.isNotBlank()) {
                storeFile = file(storeFilePath)
                storePassword = localProp("RELEASE_STORE_PASSWORD")
                keyAlias = localProp("RELEASE_KEY_ALIAS")
                keyPassword = localProp("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseStore = localProp("RELEASE_STORE_FILE")
            signingConfig = if (releaseStore.isNotBlank()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")

    implementation("io.ktor:ktor-client-android:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Provides com.google.common.util.concurrent.ListenableFuture (no duplicates)
    implementation("com.google.guava:guava:33.0.0-android")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    implementation("org.burnoutcrew.composereorderable:reorderable:0.9.6")

    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // Coil — async image loading for Compose
    implementation("io.coil-kt:coil-compose:2.5.0")

    // RevenueCat (pulls Google Play Billing Library transitively — don't add an explicit
    // billing dep, it causes a classpath clash that makes getOfferings() hang)
    implementation("com.revenuecat.purchases:purchases:8.25.0")
    implementation("com.revenuecat.purchases:purchases-ui:8.25.0")
}