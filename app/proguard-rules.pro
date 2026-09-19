# JARVIS ProGuard rules
# JNI bridge is resolved by name from native code - keep everything.
-keep class com.jarvis.mobile.core.model.LlamaBridge { *; }
# The progress callback interface is a nested fun interface implemented by
# lambdas elsewhere - R8 renaming it breaks the native GetMethodID lookup
# ("no non-static method ...onProgress(IIII[B)V"). Keep the whole model
# package unobfuscated: it is the JNI boundary.
-keep class com.jarvis.mobile.core.model.** { *; }
-keepclassmembers class com.jarvis.mobile.** {
    void onProgress(int,int,int,int,byte[]);
}

# Accessibility service keeps references via reflection-free code but be safe.
-keep class com.jarvis.mobile.accessibility.** { *; }

# kotlinx.serialization (JsonElement usage) safety
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class com.jarvis.mobile.**$$serializer { *; }
-keepclassmembers class com.jarvis.mobile.** {
    *** Companion;
}
-keepclasseswithmembers class com.jarvis.mobile.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ML Kit text recognition
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text* { *; }

# Shizuku privileged-shell bridge (precision touch capture) - binder + AIDL surface
-keep class rikka.shizuku.** { *; }
-keep class dev.rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn moe.shizuku.**
-dontwarn rikka.**

# llama.cpp native allocations: never strip Gson-like access (none used), keep exceptions
-keepattributes Exceptions

# ----------------------------------------------------------------------------
# v2.1: built-in (in-app) privileged shell
# SelfHostServer is launched via `app_process ... com.jarvis.mobile.core.adb.SelfHostServer`
# from the installed APK - the class name and main() must survive R8 verbatim.
-keep class com.jarvis.mobile.core.adb.SelfHostServer {
    public static void main(java.lang.String[]);
    public static final int EXIT_MARKER;
    public static void writeArgv(java.io.DataOutputStream, java.lang.String[]);
}
# JNI entry points resolve by name - never rename or strip.
-keep class com.jarvis.mobile.core.adb.Spake2 { *; }
-keepclasseswithmembernames class * { native <methods>; }
# vvb2060 boringssl prefab: native only, but keep any residual reflection safe
-dontwarn io.github.vvb2060.ndk.**
