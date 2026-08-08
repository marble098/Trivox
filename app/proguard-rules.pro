-keep class libXray.** { *; }
-keep class go.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn libXray.**
-dontwarn go.**

# TRIVOX_V19_NATIVE_WIREGUARD_LEAK_GUARD
-keep class com.wireguard.android.backend.GoBackend$VpnService { *; }
