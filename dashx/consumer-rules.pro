-keep class com.dashx.android.** { *; }
-keepattributes *Annotation*
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class com.dashx.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.dashx.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Firebase is an optional (compileOnly) dependency
-dontwarn com.google.firebase.messaging.FirebaseMessaging
-dontwarn com.google.firebase.messaging.FirebaseMessagingService
-dontwarn com.google.firebase.messaging.RemoteMessage
-dontwarn com.google.android.gms.tasks.OnCompleteListener
-dontwarn com.google.android.gms.tasks.Task
