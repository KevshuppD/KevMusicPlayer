# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Preserve the line number information and source files for debugging release stack traces
-keepattributes SourceFile,LineNumberTable

# Keep Room entities, DAOs, databases, and serialization models
-keep class com.kevshupp.kevmusicplayer.data.** { *; }
-keep class com.kevshupp.kevmusicplayer.playback.** { *; }

# Preserve anything annotated with Room, Moshi, or Serialization annotations
-keepclassmembers class * {
    @androidx.room.* <fields>;
    @androidx.room.* <methods>;
    @kotlinx.serialization.Serializable *;
}
-keep class * {
    @androidx.room.Database *;
    @androidx.room.Dao *;
    @androidx.room.Entity *;
}

# Ignore jaudiotagger Java SE Desktop references in Android builds
-dontwarn javax.swing.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**

# Keep jaudiotagger library classes, interfaces, and all members (especially reflection-instantiated constructors)
-keep class org.jaudiotagger.** { *; }
-keep interface org.jaudiotagger.** { *; }

# OkHttp3 Platform and TLS Rules for secure HTTPS network requests in release mode
-keepattributes Signature, InnerClasses, AnnotationDefault, EnclosingMethod
-keepclassmembers class * extends javax.net.ssl.SSLSocket { *; }
-keepclassmembers class * extends javax.net.ssl.SSLSocketFactory { *; }
-keepclassmembers class * extends javax.net.ssl.X509TrustManager { *; }

# Keep OkHttp classes
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**