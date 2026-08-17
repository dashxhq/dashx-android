# Changelog

All notable changes to `dashx-android` are documented in this file. Format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), versions follow [SemVer](https://semver.org/).

## [1.3.2] — 2026-08-17

### Fixed

Notification taps no longer crash the app when they arrive before `DashX.configure()`
has completed — e.g. a push that cold-starts the app straight into the SDK's
notification receiver, or a host that configures DashX from a coroutine/background
thread so `Application.onCreate()` returns before configuration finishes. Previously
this threw `NullPointerException: Configure SystemContext before accessing it.` from
`track()` during the receiver's `onCreate`.

- **`track()` / `trackEventBlocking()`** now no-op (with a log) when called before
  `configure()` instead of dereferencing an unbuilt `SystemContext`. `track()` also
  signals `onError(DashXError.NotConfigured())`, so the `trackAsync()` suspend wrapper
  fails fast instead of hanging forever.
- **Notification open / delivery / dismissal / navigation / deep-link tracking**
  (`dx_message` OPENED, DELIVERED, DISMISSED, `dx_notification_navigated`,
  `dx_deep_link_opened`) is now **persisted** when the SDK isn't configured yet and
  **replayed with its original timestamps** once `configure()` runs, so no event is
  lost on a cold-start tap. Navigation itself (deep link / rich landing / click action)
  still runs regardless.
- **`isConfigured`** is now backed by a dedicated flag, set only after every subsystem
  is initialized and cleared at the start of `shutdown()`, rather than inferred from a
  non-null context — closing a window where a concurrent notification could observe a
  half-initialized or torn-down SDK as ready.

## [1.3.1] — 2026-06-08

### Fixed

Crash hardening across the notification and system-context paths. Several
platform-API and remote-payload reads could throw and take down the host app
(including from a routine `track()`, which builds `SystemContext`
synchronously without a guard):

- **`subscribe()`** read the hidden `bluetooth_name` secure setting as a
  device-name fallback. On Android 12+ (API 31) that throws
  `SecurityException: Settings key: <bluetooth_name> is only readable to apps
  with targetSdkVersion lower than or equal to: 31`. The read is now wrapped
  and degrades to `null` (optional metadata). This was the reported
  production crash.
- **`SystemContext` network/device-state reads** now degrade to safe defaults
  instead of throwing:
  - `getBluetoothInfo` skipped the permission check entirely on API 31+ and
    called `adapter.isEnabled`, which throws `SecurityException` without
    `BLUETOOTH_CONNECT`; it now gates on `BLUETOOTH_CONNECT` (API 31+) vs
    legacy `BLUETOOTH`, null-checks the (possibly absent) adapter, and catches.
  - `getCarrierInfo` / `getWifiInfo` / `getCellularInfo` / `getLocationCoordinates`
    no longer force-cast system services that are null on devices lacking the
    hardware (non-telephony tablets, Android TV, Wear), and guard reads that
    can throw `SecurityException` / `IllegalArgumentException` (e.g. a missing
    `GPS_PROVIDER`).
  - `getIpHostAddresses` catches the `SocketException` that
    `NetworkInterface.getNetworkInterfaces()` can throw.
  - `getOsName` catches failures from reflecting over `Build.VERSION_CODES`.
- **Notification channel creation** (API 26+) called `Color.parseColor()` on
  the payload's `light_settings.color` without a guard — an invalid value
  threw `IllegalArgumentException` and aborted channel creation before the
  notification posted. Now caught, mirroring the existing pre-O `setLights`
  guard.
- **`DashXActivityLifecycleCallbacks.registerCallbacks`** force-cast the
  caller's `Context` to `Application` (`ClassCastException` when an
  `Activity`/`Service`/wrapped context was passed into lifecycle/screen
  tracking). It now resolves `applicationContext as? Application` and logs +
  no-ops if unavailable, leaving registration to retry on a later enable call.
- **`DashXBrowser.openRichLanding`** left the Custom Tabs `launchUrl()` path
  unguarded (only the `ACTION_VIEW` fallback was wrapped); a provider that was
  disabled/uninstalled after resolution threw `ActivityNotFoundException`
  through the notification rich-landing click flow. It is now wrapped and
  falls back to `ACTION_VIEW`.

## [1.3.0] — 2026-06-04

### Added

- **`subscribeContact` payload now carries device identifiers at the contact
  top level**: `userAgent`, `deviceUid` (`ANDROID_ID`), `deviceAdvertisingUid`
  (Google Advertising ID), and `isDeviceAdTrackingEnabled`. The advertising ID
  / consent is fetched asynchronously off the main thread via `AdvertisingIdClient`;
  subscribe proceeds without it and re-syncs once it resolves (see below).

### Changed

- **Subscribe/unsubscribe concurrency safety.** A generation counter
  (`subscribeGeneration`) plus an in-flight guard (`isUnsubscribeInFlight`)
  ensure an in-flight subscribe can't write `DEVICE_TOKEN` / version markers
  back after an `unsubscribe()`, and stale subscribe responses landing after
  unsubscribe are dropped rather than re-subscribing the device.
- **Subscribe cache is now keyed on SDK version, not just the FCM token.** A
  new `subscribed_library_version` marker means an SDK upgrade re-subscribes
  instead of being short-circuited by an unchanged token. A separate
  `subscribed_ad_info_version` marker tracks advertising-info sync
  independently, so the core subscribe cache isn't blocked waiting on the
  async advertising fetch, and an advertising-ID or consent change invalidates
  only that marker and triggers a re-sync.
- **`refreshSubscriptionDeviceInfo()`** (internal) re-syncs the subscribed
  contact when device/advertising info changes after the initial subscribe.
- `getDeviceId()` coalesces a null `ANDROID_ID` (possible on some OEM builds)
  to `""` so callers can use `isNotEmpty()`.

### Fixed

- First-subscribe race during identity rotation / `reset()`: a first-time
  subscribe in flight (no saved token yet) could write the device token and
  version markers under the rotated identity. The generation counter is now
  bumped on rotation so that write is recognized as stale.

## [1.2.8] — 2026-04-23

### Added

- **`DashX.unsubscribe(onSuccess: ((Boolean) -> Unit)? = null, onError: ((DashXError) -> Unit)? = null)`** — optional terminal callbacks for the unsubscribe flow, matching the convention used by `identify`, `track`, and other public methods. Backend mutation `unsubscribeContact` changed its return type from `Contact!` to `UnsubscribeContactResponse!` (with `success: Boolean`); the SDK now forwards that value to callers. Purely additive — existing call sites calling `DashX.unsubscribe()` compile unchanged because both callbacks default to `null`.
  - **`onSuccess(true)`** — backend found and unsubscribed a matching contact.
  - **`onSuccess(false)`** — non-error outcome meaning "no matching contact found" (typically the anonymous UID rotated since subscribe, the FCM token is stale, the contact is already unsubscribed, or `unsubscribe()` was called on a device that never subscribed in this session). The device ends up unsubscribed in both cases; the boolean is useful for diagnostics and analytics.
  - **`onError(DashXError.NotConfigured)`** — Firebase Messaging dependency missing, or `configure()` not yet called. Distinct from `success: false` so callers can branch on SDK-misuse vs legitimate no-match.
  - **`onError(DashXError.NetworkError)`** — Firebase `deleteToken()` failure (cannot proceed to the GraphQL mutation when the FCM token can't be deleted locally first).
  - **`onError(DashXError.GraphQLError)`** — backend rejected the mutation (auth error, validation, etc.).

## [1.2.7] — 2026-04-21

### Added
- `DashXNotificationListener.onNotificationClicked` now has a 3-arg overload
  that receives the tapped button's `actionIdentifier: String?`. The value is
  `null` when the notification body itself was tapped, or the
  `ActionButton.identifier` when a specific action button was pressed.
  Consumers can now distinguish body taps from per-button taps without
  introspecting the resolved `NavigationAction`.

### Changed
- `DashX.dispatchNotificationClicked` and `NotificationProcessor.handleClick`
  thread the known action-button identifier through to listeners — the SDK
  already had this value from the tap intent extras, it just wasn't being
  surfaced.

### Backward compatibility
- The legacy 2-arg `onNotificationClicked(payload, action)` overload is kept
  as-is. The new 3-arg overload's default implementation delegates to it, so
  pre-1.2.7 implementations continue to work without recompilation. New code
  should override the 3-arg form.

## [1.2.6] — 2026-04-20

### Changed
- `DashX.subscribe()` now sends `ContactMetadata` in the `subscribeContact`
  mutation — `app.{identifier, name, version}` (host app identity, used by
  the backend to scope broadcasts via `FCMSettings.app_identifier`) and
  `library.{name, version}` (SDK slug + version, used by the backend to gate
  per-contact behaviour by SDK version).
- `SubscribeContactInput.metadata` is now typed as `JSON` (kotlinx
  `JsonObject`) rather than a fixed input object, matching the backend
  change that accepts arbitrary JSON for extensibility. `osName` / `osVersion`
  are no longer in the metadata payload because they're already captured at
  the contact top level.

## Earlier releases

For release history before 1.2.6, see git log on `develop` — notable points:

- **1.2.5** — `fetchAsset` API added.
- **1.2.4** — Fix for fatal crash caused by speed `Float` → `Double` conversion
  in `SystemContext`.
- **1.2.3** — Light-settings JSON deserialization fix.
- **1.2.2** — Graceful handling of backend numbers that arrive as `Int` where
  the SDK expected `Double`.
- **1.2.1** — `richLanding` boolean handling in notification payloads.
- **1.2.0** — Deep-linking support: typed `NavigationAction` (deep link,
  screen, rich landing, click action), `DashXNotificationListener` hook,
  `dx_deep_link_opened` / `dx_notification_navigated` analytics, auto-subscribe
  only after identity is set.
