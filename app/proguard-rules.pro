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

# llama.cpp native allocations: never strip Gson-like access (none used), keep exceptions
-keepattributes Exceptions
