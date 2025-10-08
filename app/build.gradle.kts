plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.migraineme"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.migraineme"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // 🔑 Supabase config into BuildConfig
        buildConfigField("String", "SUPABASE_URL", "\"https://qykflarpibofvffmzghi.supabase.co\"")
        buildConfigField(
            "String",
            "SUPABASE_ANON_KEY",
            "\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InF5a2ZsYXJwaWJvZnZmZm16Z2hpIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NTY0NjU5NDMsImV4cCI6MjA3MjA0MTk0M30.r3DHA2EKNvC_AraPs1gwgaBl_oEBpDrD1bwPfiuiSbM\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    // ---- Compose ----
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.compose.material:material-icons-extended")

    // ViewModel / lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")

    // ---- Ktor (2.x to match Kotlin 1.9) ----
    implementation("io.ktor:ktor-client-android:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // ---- Location + Tasks await ----
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // ---- WorkManager (daily job) ----
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // ---- DataStore (cache + saved location) ----
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ---- Charts ----
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // ---- Reorderable list ----
    implementation("org.burnoutcrew.composereorderable:reorderable:0.9.6")

}
