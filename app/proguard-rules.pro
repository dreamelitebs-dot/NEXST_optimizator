# Add project specific ProGuard rules here.

# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class ru.booster.network.**$$serializer { *; }
-keepclassmembers class ru.booster.network.** {
    *** Companion;
}
-keepclasseswithmembers class ru.booster.network.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Hilt / Dagger
-dontwarn com.google.errorprone.annotations.**

# WireGuard / AmneziaWG native tunnel
-keep class com.wireguard.** { *; }
-keep class org.amnezia.** { *; }

# Compose
-dontwarn androidx.compose.**
