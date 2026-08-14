# RideFlux HUD — R8/ProGuard rules
# Minification is enabled for release builds (see build.gradle.kts).
# These rules mirror :app's set; keep them aligned when either side
# changes.

# Hilt
-keepclasseswithmembers class * {
    @dagger.hilt.* <methods>;
}

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Kable (BLE)
-keep class com.juul.kable.** { *; }
-dontwarn com.juul.kable.**

# Rokid CXR SDK. libcxr-bridge-jni.so loads Caps and bridge members
# by exact JNI name; shrinking or renaming them aborts the process.
-keep class com.rokid.cxr.** { *; }
-dontwarn com.rokid.cxr.**

# Kotlin metadata / annotations
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }

# Prevent stripping of enum values used in when-expressions
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
