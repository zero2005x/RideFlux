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

# Rokid CXR SDK (JNI resolves these exact class and member names).
-keep class com.rokid.cxr.** { *; }
-keep class com.rokid.cxr.client.** { *; }
-dontwarn com.rokid.cxr.**

# OkHttp probes these optional JVM TLS providers reflectively. Android uses
# its platform TLS provider, so the provider jars are intentionally absent.
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE

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
