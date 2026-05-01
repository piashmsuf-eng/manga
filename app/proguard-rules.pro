# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.android.gms.internal.mlkit_** { *; }

# Glide
-keep class com.bumptech.glide.** { *; }
-keep public class * extends com.bumptech.glide.module.AppGlideModule

# Keep Kotlin coroutines internals quiet
-dontwarn kotlinx.coroutines.**
