<p align="center">
    <br />
    <a href="https://dashx.com"><img src="https://raw.githubusercontent.com/dashxhq/dashx-brand-book/master/assets/logo-black-text-color-icon@2x.png" alt="DashX" height="40" /></a>
    <br />
    <br />
    <strong>Your All-in-One Product Stack</strong>
</p>

<div align="center">
  <h4>
    <a href="https://dashx.com">Website</a>
    <span> | </span>
    <a href="https://docs.dashx.com">Documentation</a>
  </h4>
</div>

<br />

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
    implementation 'com.dashx:dashx-android:1.5.0'
}
```

## Automatic events

The SDK can track some events for you. Enable them once, for example in
`Application.onCreate()` after `DashX.configure(...)`:

```kotlin
DashXActivityLifecycleCallbacks.enableActivityLifecycleTracking(this)
DashXActivityLifecycleCallbacks.enableScreenTracking(this)
```

| Event | When | Data |
|---|---|---|
| `Application Installed` | First launch after install | `version`, `build` |
| `Application Updated` | First launch after the build changes | `version`, `build`, `previous_build`, `previous_version` |
| `Application Opened` | Other launches, and each return to the foreground | `version`, `build`, `from_background` (`"true"` on a return to the foreground) |
| `Application Backgrounded` | The app leaves the foreground | `session_length` (seconds in the foreground) |
| `$screen` | An Activity resumes (screen tracking), or you call `DashX.screen()` | `$screen_name` plus any properties you pass |
| `Application Crashed` | An uncaught exception (`DashXExceptionHandler.enable()`) | `exception` |
| `Deep Link Opened` | `DashX.processDeepLink(uri)` or a notification tap that opens a URL | `url`, `timestamp`, `source` |
| `dx_notification_navigated` | A DashX notification is tapped | `type`, `timestamp`, `notification_id`, and `url`, `screen_name`, `screen_data` or `click_action` |

All values are strings. Lifecycle events are app-level: moving between Activities does not
track an open or a background.

Automatic screen tracking names each screen after the Activity's `android:label`, falling back
to its class name. Apps that navigate inside a single Activity (Fragments, Jetpack Compose
navigation) should track screens themselves:

```kotlin
DashX.screen("Checkout", hashMapOf("plan" to "pro"))
```

## Documentation

For detailed documentation, visit [Android SDK documentation](https://docs.dashx.com/sdks/client-side/android-sdk).
