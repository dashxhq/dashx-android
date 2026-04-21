# Changelog

All notable changes to `dashx-android` are documented in this file. Format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), versions follow [SemVer](https://semver.org/).

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
  per-contact behaviour by SDK version). Mirrors the equivalent payload that
  `dashx-ios` has been sending since 1.3.0.
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
