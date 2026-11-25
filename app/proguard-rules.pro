# DeviceGuard MDM release build (R8). Author: Sakthivel J
# Activities, services, receivers and the admin receiver are kept automatically from the manifest.

# Readable crash stack traces (line numbers) without the original file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Enum names are stored and parsed by name (bloat.csv categories, saved settings): never rename them.
-keepclassmembers enum com.devguard.mdm.** { *; }

# zxing: only the QR encoder is used; quiet warnings about optional classes it references.
-dontwarn com.google.zxing.**
