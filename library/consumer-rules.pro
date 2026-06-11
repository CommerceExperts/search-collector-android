# Keep all SearchCollector public API classes
-keep class io.searchhub.collector.SearchCollector { *; }
-keep class io.searchhub.collector.model.** { *; }
-keep interface io.searchhub.collector.interfaces.** { *; }
-keep class io.searchhub.collector.impl.** { *; }

# kotlinx.serialization: keep @Serializable classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class io.searchhub.collector.model.**$$serializer { *; }
-keepclassmembers class io.searchhub.collector.model.** {
    *** Companion;
}
-keepclasseswithmembers class io.searchhub.collector.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
