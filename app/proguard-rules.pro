# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Hilt
-keepclasseswithmembers class * {
    @dagger.hilt.* <methods>;
}

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Room
-keep class * extends androidx.room.RoomDatabase {}
-keep @androidx.room.Entity class * { *; }
-keepclassmembers class * {
    @androidx.room.Dao <methods>;
}

# DataStore
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }

# Kable (BLE)
-keep class com.juul.kable.** { *; }
-dontwarn com.juul.kable.**

# Compose Navigation — keep argument types
-keepnames class * extends android.os.Parcelable {}
-keepnames class * extends java.io.Serializable {}

# Kotlin Serialization (if used in future)
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }

# Prevent stripping of enum values used in when-expressions
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
