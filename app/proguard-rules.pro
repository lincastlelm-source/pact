# Kotlinx serialization: keep generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.pact.coach.**$$serializer { *; }
-keepclassmembers class com.pact.coach.** {
    *** Companion;
}
-keepclasseswithmembers class com.pact.coach.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room generated implementations are referenced reflectively by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# Broadcast receivers / services are instantiated by the platform by name.
-keep class com.pact.coach.services.** { *; }
