# kotlinx.serialization : conserver les serializers générés
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { @kotlinx.serialization.Serializable <methods>; }

# Build stremio (R8) : ce que le fork garde en plus.
-keep class fr.moovie.tv.BuildConfig { *; }
-keepclassmembers class fr.moovie.tv.data.** { *; }
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
-dontwarn javax.management.**
