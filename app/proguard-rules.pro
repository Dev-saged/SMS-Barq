# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class dev.saged.barq.**$$serializer { *; }
-keepclassmembers class dev.saged.barq.** {
    *** Companion;
}
-keepclasseswithmembers class dev.saged.barq.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Receivers / services are referenced from the manifest and kept by AGP automatically.
# Glance widget receiver
-keep class dev.saged.barq.widget.** { *; }

# Enum names are persisted (Room, DataStore, backups) — keep them stable across builds.
-keepclassmembers enum dev.saged.barq.** { *; }
