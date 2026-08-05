-keep class libXray.** { *; }
-keep class go.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn libXray.**
-dontwarn go.**

# Keep data models and serialization
-keep class com.trivox.client.data.** { *; }
-keepclassmembers class com.trivox.client.data.** { *; }

# Keep native core adapters
-keep class com.trivox.client.core.** { *; }

# R8 Optimization & Code Shrinking
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''

