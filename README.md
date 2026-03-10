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
