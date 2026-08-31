# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Retrofit
-keepattributes Signature, Exceptions, EnclosingMethod, InnerClasses
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-keepclassmembers class * {
    @retrofit2.http.* <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Moshi
-keepattributes *Annotation*
-keepclassmembers,allowshrinking,allowobfuscation class * {
    @com.squareup.moshi.JsonClass <fields>;
}
-keep @com.squareup.moshi.JsonClass class * { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
