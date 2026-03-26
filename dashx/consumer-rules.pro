# DashX SDK ProGuard/R8 Rules
# These rules are bundled with the SDK and automatically applied to consumer apps.

# Keep all public SDK classes and their public/protected members
-keep class com.dashx.sdk.** { public protected *; }

# Keep data classes used for serialization
-keepclassmembers class com.dashx.sdk.data.** {
    <fields>;
    <init>(...);
}

# Keep DashXFirebaseMessagingService (referenced in consumer's AndroidManifest.xml)
-keep class com.dashx.sdk.DashXFirebaseMessagingService { *; }

# Keep NotificationReceiver and NotificationDismissedReceiver (BroadcastReceivers)
-keep class com.dashx.sdk.NotificationReceiver { *; }
-keep class com.dashx.sdk.NotificationDismissedReceiver { *; }

# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class com.dashx.sdk.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Apollo GraphQL generated types
-keep class com.dashx.android.graphql.generated.** { *; }
-keepclassmembers class com.apollographql.apollo.** { *; }
-keep class com.apollographql.apollo.api.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
