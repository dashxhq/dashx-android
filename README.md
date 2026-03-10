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
    implementation 'com.dashx:dashx-android:1.1.0'
}
```

## Usage

For detailed usage, refer to the [documentation](https://docs.dashx.com).

### Configuration

Basic setup:

```kotlin
DashX.configure(context, "your-public-key")
```

### API reference

This section documents the public functions exposed by the SDK. Most apps only need a subset (configure + identify + content fetch + track + push subscribe).

#### `DashX.configure(...)`

Initializes the SDK. Call once (usually in `Application.onCreate()`).

```kotlin
DashX.configure(
    context = applicationContext,
    publicKey = "your-public-key",
    baseURI = null,               // optional override (defaults to https://api.dashx.com/graphql)
    targetEnvironment = null,     // optional
    callbackDispatcher = null     // optional; defaults to Dispatchers.Main.immediate
)
```

#### `DashX.setCallbackDispatcher(dispatcher)`

Sets the dispatcher used for **all `onSuccess`/`onError` callbacks** from the SDK. Default is `Dispatchers.Main.immediate`.

```kotlin
DashX.setCallbackDispatcher(Dispatchers.Unconfined)
```

#### `DashX.setIdentity(uid, token)` and `DashX.setIdentityToken(token)`

Associates SDK calls with an identified user and (optionally) an identity token used as `X-Identity-Token`.

- `setIdentity(uid, token)`: sets both the user id and token and persists them
- `setIdentityToken(token)`: updates only the identity token header

```kotlin
DashX.setIdentity(uid = "user_123", token = "jwt-or-session-token")
// or
DashX.setIdentityToken("jwt-or-session-token")
```

#### `DashX.identify(options)`

Sends an identify call to DashX. `options` should include any user attributes you have (uid/email/phone/name/etc).

```kotlin
DashX.identify(
    hashMapOf(
        "uid" to "user_123",
        "email" to "user@example.com",
        "first_name" to "Ava",
        "last_name" to "Singh"
    )
)
```

#### `DashX.reset()`

Clears the current identity state and generates a new anonymous id. Also triggers `DashX.unsubscribe()` for push.

```kotlin
DashX.reset()
```

#### `DashX.fetchRecord(...)`

Fetch a single CMS record by URN of form `{resource}/{recordId}`.

```kotlin
DashX.fetchRecord(
    urn = "blog/abc123",
    preview = true,
    language = null,
    fields = null,
    include = null,
    exclude = null,
    onSuccess = { recordJson -> /* ... */ },
    onError = { err -> /* ... */ }
)
```

#### `DashX.searchRecords(...)`

Search records in a resource with optional filter/order/limit and field projection options.

```kotlin
DashX.searchRecords(
    resource = "blog",
    filter = null,
    order = null,
    limit = 20,
    preview = true,
    language = null,
    fields = null,
    include = null,
    exclude = null,
    onSuccess = { records -> /* List<JsonObject> */ },
    onError = { err -> /* ... */ }
)
```

#### `DashX.fetchStoredPreferences(...)`

Fetch stored preferences for the current identified user.

- Requires: `DashX.setIdentity(uid, ...)` (otherwise returns `DashXError.NotIdentified`)

```kotlin
DashX.fetchStoredPreferences(
    onSuccess = { prefs -> /* ... */ },
    onError = { err -> /* ... */ }
)
```

#### `DashX.saveStoredPreferences(preferenceData, ...)`

Save stored preferences for the current identified user.

- Requires: `DashX.setIdentity(uid, ...)`

```kotlin
val preferenceData = kotlinx.serialization.json.buildJsonObject {
    put("push", true)
    put("email", false)
}

DashX.saveStoredPreferences(
    preferenceData = preferenceData,
    onSuccess = { result -> /* ... */ },
    onError = { err -> /* ... */ }
)
```

#### `DashX.uploadAsset(file, resource, attribute, ...)`

Uploads a local file as an Asset and returns the uploaded asset metadata.

```kotlin
DashX.uploadAsset(
    file = File("/path/to/file.jpg"),
    resource = "products",
    attribute = "image",
    onSuccess = { asset -> /* ... */ },
    onError = { err -> /* ... */ }
)
```

#### `DashX.track(event, data)`

Tracks a custom event. `data` is optional and is sent as JSON.

```kotlin
DashX.track("checkout_started", hashMapOf("cart_value" to "42.00"))
```

#### `DashX.trackAppStarted(fromBackground)`

Tracks one of: installed / updated / opened (and supports `from_background`).
Used by `DashXActivityLifecycleCallbacks` if you enable lifecycle tracking.

```kotlin
DashX.trackAppStarted(fromBackground = false)
```

#### `DashX.trackAppSession(elapsedTime)`

Tracks an app backgrounded/session-length style event. `elapsedTime` is in **milliseconds**.

```kotlin
DashX.trackAppSession(elapsedTime = 12_345.0)
```

#### `DashX.trackAppCrashed(exception)`

Tracks an app crash event (best-effort, synchronous with timeout).

```kotlin
DashX.trackAppCrashed(exception)
```

#### `DashX.screen(screenName, properties)`

Tracks a screen view. Used by `DashXActivityLifecycleCallbacks` if you enable screen tracking.

```kotlin
DashX.screen("Home", hashMapOf())
```

#### `DashX.trackMessage(id, status)`

Tracks message delivery/open/dismiss status for DashX pushes.

```kotlin
DashX.trackMessage("message-id", com.dashx.android.graphql.generated.type.TrackMessageStatus.OPENED)
```

#### `DashX.subscribe()` / `DashX.subscribe(token)` / `DashX.unsubscribe()`

Registers (or unregisters) the device’s FCM token with DashX.

- `subscribe()`: fetches the current FCM token and subscribes it
- `subscribe(token)`: subscribes a known token (useful if your app owns the FCM service)
- `unsubscribe()`: deletes the local FCM token and unregisters it in DashX

```kotlin
DashX.subscribe()
// or
DashX.subscribe(tokenFromYourService)
// and
DashX.unsubscribe()
```

#### `DashX.getBaseUri()` / `DashX.getPublicKey()` / `DashX.getTargetEnvironment()` / `DashX.getIdentityToken()`

Returns the current configured values (may be `null` if not configured/set).

### Activity/screen tracking helpers

#### `DashXActivityLifecycleCallbacks.enableActivityLifecycleTracking(context)`

Registers lifecycle callbacks and automatically calls `DashX.trackAppStarted(...)` and `DashX.trackAppSession(...)`.

```kotlin
DashXActivityLifecycleCallbacks.enableActivityLifecycleTracking(applicationContext)
```

#### `DashXActivityLifecycleCallbacks.enableScreenTracking(context)`

Registers lifecycle callbacks and tracks screen views (uses the Activity label).

```kotlin
DashXActivityLifecycleCallbacks.enableScreenTracking(applicationContext)
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
