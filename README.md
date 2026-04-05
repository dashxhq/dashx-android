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
    <a href="https://docs.dashx.com/developer">Documentation</a>
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
