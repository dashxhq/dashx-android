# dashx-android

_DashX SDK for Android_

## Install

The SDK is published to [Maven Central](https://central.sonatype.com/) (via Sonatype). Add the Maven Central repository in `settings.gradle` (or `settings.gradle.kts`):

```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
```

- Add `dashx-android` to your dependencies in your module-level `build.gradle`:

```groovy
dependencies {
    implementation 'com.dashx:dashx-android:1.0.15'
}
```

## Usage

For detailed usage, refer to the [documentation](https://docs.dashx.com).

### Configuration

Basic setup:

```kotlin
DashX.configure(context, "your-public-key")
```

### Push notifications (FCM)

The SDK can render DashX push notifications and automatically track **delivered / opened / dismissed** events.

- **Prerequisites**: your app must already be set up for Firebase Cloud Messaging (have `google-services.json` and the Google Services plugin configured as per Firebase docs).
- **Register the device token with DashX**:

```kotlin
DashX.configure(context, "your-public-key")
DashX.subscribe() // registers the current FCM token with DashX
```

- **Android 13+**: you must request the runtime notification permission (`POST_NOTIFICATIONS`) before notifications can be shown.

#### If your app already has a FirebaseMessagingService

Some apps have their own `FirebaseMessagingService`. In that case, you can remove the SDK’s service from manifest merging and delegate token updates to the SDK:

```xml
<!-- AndroidManifest.xml (app module) -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application>
        <service
            android:name="com.dashx.android.DashXFirebaseMessagingService"
            tools:node="remove" />
    </application>
</manifest>
```

Then forward token updates:

```kotlin
override fun onNewToken(token: String) {
    super.onNewToken(token)
    DashX.subscribe(token)
}
```

### Callback dispatcher

All `onSuccess` and `onError` callbacks from the SDK (e.g. `fetchRecord`, `fetchCart`, `uploadAsset`) are invoked on the **main thread** by default (`Dispatchers.Main.immediate`). You can update your UI directly from these callbacks without switching threads.

To use a different dispatcher (for example in tests), pass it at configuration time or change it later:

```kotlin
// At configuration
DashX.configure(
    context,
    "your-public-key",
    callbackDispatcher = Dispatchers.Unconfined  // or any CoroutineDispatcher
)

// Or change at any time
DashX.setCallbackDispatcher(Dispatchers.Unconfined)
```
