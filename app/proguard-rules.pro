# ═══════════════════════════════════════════════════════════════
# MigraineMe — ProGuard / R8 rules for release builds
# ═══════════════════════════════════════════════════════════════

# ── General ──
-keepattributes *Annotation*,InnerClasses,Signature,Exceptions
-keepattributes SourceFile,LineNumberTable   # Better crash reports
-renamesourcefileattribute SourceFile

# ── Kotlin Serialization ──
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationCollector

# Keep serializers for all @Serializable classes in our package
-keep,includedescriptorclasses class com.migraineme.**$$serializer { *; }
-keepclassmembers class com.migraineme.** {
    *** Companion;
}
-keepclasseswithmembers class com.migraineme.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep kotlinx.serialization core
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# ── OkHttp ──
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ── Ktor ──
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class kotlin.reflect.jvm.internal.** { *; }

# ── Room ──
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-dontwarn androidx.room.paging.**

# ── Firebase ──
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# ── Google Identity / Credentials ──
-keep class com.google.android.libraries.identity.googleid.** { *; }
-keep class androidx.credentials.** { *; }
-dontwarn androidx.credentials.**

# ── RevenueCat ──
-keep class com.revenuecat.purchases.** { *; }
-dontwarn com.revenuecat.purchases.**

# ── MPAndroidChart ──
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# ── Coil (image loading) ──
-keep class coil.** { *; }
-dontwarn coil.**

# ── Health Connect ──
-keep class androidx.health.connect.** { *; }
-dontwarn androidx.health.connect.**

# ── Compose (mostly handled by default, but just in case) ──
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# ── Compose Reorderable ──
-keep class org.burnoutcrew.composereorderable.** { *; }

# ── AndroidX Lifecycle ──
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**

# ── AndroidX Work Manager ──
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# ── AndroidX DataStore ──
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ── SLF4J (referenced by play-services-location, not needed at runtime) ──
-dontwarn org.slf4j.**

# ── Guava ──
-dontwarn com.google.common.**
-keep class com.google.common.util.concurrent.** { *; }

# ── Coroutines ──
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ── Kotlin ──
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# ── Keep all data classes in our app (prevents field stripping) ──
-keepclassmembers class com.migraineme.** {
    <fields>;
}

# ── Keep BuildConfig (we use it for API keys) ──
-keep class com.migraineme.BuildConfig { *; }

# ── Enums ──
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
