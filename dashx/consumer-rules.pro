-keep class com.dashx.android.** { *; }
-keepattributes *Annotation*
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class com.dashx.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.dashx.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}
