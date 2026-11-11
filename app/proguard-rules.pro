# Android Studio / R8 / ProGuard rules for AI App (On-Device REA + Craft Anything + LiteRT-LM)
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod

# Keep kotlinx.serialization generated serializers
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep @kotlinx.serialization.Serializable class ** { *; }
-keepclassmembers class **$$serializer { *; }

# Keep on-device APK / ARSC / Smali / ApkSigner engines
-keep class com.reandroid.** { *; }
-keep class com.android.apksig.** { *; }
-keep class org.jf.** { *; }
-dontwarn org.jf.**
-dontwarn com.reandroid.**
-dontwarn com.google.common.**

# Keep Google LiteRT-LM on-device runtime
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
