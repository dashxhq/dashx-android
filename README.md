<p align="center">
    <br />
    <a href="https://dashx.com"><img src="https://raw.githubusercontent.com/dashxhq/brand-book/master/assets/logo-black-text-color-icon@2x.png" alt="DashX" height="40" /></a>
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
    implementation 'com.dashx:dashx-android:1.1.7'
}
```

## Documentation

For detailed documentation, visit [Android SDK documentation](https://docs.dashx.com/sdks/client-side/android-sdk).

## Deep linking and push navigation

### `DashXNotificationListener`

Register optional hooks with **`DashX.registerNotificationListener`** / **`unregisterNotificationListener`**:

- **`onNotificationReceived`** — incoming FCM message with a parsed **`DashXPayload`** (foreground delivery path).
- **`onNotificationClicked`** — called when the user opens a notification (or an action). Return **`true`** to handle navigation yourself and skip the SDK default behavior (opening URLs, `click_action`, Custom Tabs for rich landing). Return **`false`** to let the SDK proceed.
- **`onNotificationDismissed`** — notification cleared from the shade.

### `DashX.processDeepLink(uri, source)`

Call this when you open a deep link outside the SDK (for example from your own App Link activity) to record the same `dx_deep_link_opened` analytics event the SDK uses for notification taps. Notification clicks that use the default URL flow already invoke this with `source = "notification"`.

### Payload fields (`DashXPayload`)

The SDK resolves a sealed **`NavigationAction`**: **`DeepLink`**, **`RichLanding`**, or **`Screen`**. Relevant FCM / data keys include:

| Key | Role |
|-----|------|
| `url` | Opens externally (or rich landing when `rich_landing` is true) |
| `screen_name` / `screen_data` | Structured in-app navigation |
| `rich_landing` | When true with a URL, opens in-app via Custom Tabs |
| `action_buttons` | Per-button `identifier`, `url`, `screen_name`, `screen_data`, `click_action`, `rich_landing` |
| `click_action` | Activity class name or intent action string (legacy / fallback) |

### App Links

For `https` links, configure [Android App Links](https://developer.android.com/training/app-links) (intent filters and Digital Asset Links) so taps open your app; use **`onNotificationClicked`** to map **`NavigationAction.Screen`** or custom URLs to your activities or `NavController`.
