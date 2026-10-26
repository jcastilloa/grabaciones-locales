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

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Prevent code obfuscation to keep the original class, method, and field names.
-dontobfuscate

# Keep JNI entry points and SWIG callback classes used by the official MEGA SDK.
-keep class nz.mega.sdk.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-keep class org.jcastillo.grabaciones.integrations.mega.MegaUploadWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); *; }

# Keep JNI method names used from the native LAME bridge.
-keep class org.jcastillo.grabaciones.audio.LameMp3Encoder { *; }

# app_process enters the APK by this string-named Java main method, outside Android's normal launcher.
-keep class org.jcastillo.grabaciones.services.daemon.EmbeddedRecorderDaemon { public static void main(java.lang.String[]); *; }
-keep class org.jcastillo.grabaciones.services.shell.ShellService { *; }

# Conscrypt references legacy platform-specific classes for old Android releases.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl

# Keep the original class and method names for debugging on release builds.
-keepattributes LineNumberTable
-keepattributes SourceFile
-keepattributes Signature, InnerClasses, EnclosingMethod

# Strip all Log.v calls
-assumenosideeffects class android.util.Log {
  v(...);
}

# Also strip your our custom AppLogger verbose calls
-assumenosideeffects class org.jcastillo.grabaciones.utils.AppLogger {
  v(...);
  java.lang.String getCallerTag(); # Needed because this is used as a parameter the v() method above
}