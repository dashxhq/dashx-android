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
    implementation 'com.dashx:dashx-android:1.3.2'
}
```

## Documentation

For detailed documentation, visit [Android SDK documentation](https://docs.dashx.com/sdks/client-side/android-sdk).

## In-app chat

Conversations are created by your backend, which returns a `(conversationId, chatIdentityId)` pair to the app. The SDK owns the realtime connection: it connects while a conversation is open and the app is foregrounded, reconnects with backoff, and reconciles missed messages after every reconnect.

Register a token provider once, in `Application.onCreate()` — the SDK calls it whenever it needs an identity token, including after the server rejects the current one:

```kotlin
DashX.setIdentityTokenProvider(uid, DashXTokenProvider.suspending { forceRefresh ->
    api.fetchDashXIdentityToken(forceRefresh)
})
```

Then open a conversation:

```kotlin
val lease = DashX.chat(chatIdentityId).openConversation(conversationId)

lease.addStateListener { state ->
    when (state) {
        is ConversationState.Loading -> showSpinner()
        is ConversationState.Ready -> render(state.messages)
        is ConversationState.Error -> showError(state.cause)
    }
}

lease.sendMessage(content, onSuccess = { }, onError = { })
lease.loadPreviousPage()
lease.setVisible(true) // marks messages read, suppresses this conversation's pushes

lease.close() // when the screen goes away
```

`lease.state` is also available as a `StateFlow` for coroutine hosts, and the connection itself is observable via `DashX.connectionState` / `DashX.addConnectionStateListener`.

### Using your own FirebaseMessagingService

The SDK registers a messaging service of its own. If your app already has one, remove the SDK's and delegate:

```xml
<service
    android:name="com.dashx.android.DashXFirebaseMessagingService"
    tools:node="remove" />
```

```kotlin
override fun onMessageReceived(message: RemoteMessage) {
    if (DashXPush.handleMessage(this, message)) return
    // not a DashX message — handle it yourself
}

override fun onNewToken(token: String) {
    DashXPush.onNewToken(token)
}
```

## Deep linking and push navigation

See the [Deep Linking & Push Navigation](https://docs.dashx.com/apps/messaging/deep-linking) guide for setup instructions, payload fields, and code examples.
