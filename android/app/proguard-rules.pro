# UniFFI / JNA — accessed by reflection from native code.
-keep class uniffi.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# Compose runtime keeps these via plugin annotations; harmless redundancy.
-keep class androidx.compose.runtime.** { *; }
