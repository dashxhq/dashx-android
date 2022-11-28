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
    <a href="https://dashxdemo.com">Demos</a>
    <span> | </span>
    <a href="https://docs.dashx.com/developer">Documentation</a>
  </h4>
</div>

<br />

# dashx-android

_DashX SDK for Android_

## Install

- Add Maven Central repository to your `settings.gradle`:

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
    implementation 'com.dashx:dashx-android:1.0.8'
}
```

## Usage

For detailed usage, refer to the [documentation](https://docs.dashx.com/developer).

## Contributing

### Obtaining Graphql schema and generating Graphql operation

- To re-generate Kotlin models for your graphql operations, run:

```sh
./gradlew build
```
---

For example, if you want to generate code for `FetchContent`.

- Add request in `graphql` dir with following contents:

```graphql
query FetchContent($input: FetchContentInput!) {
  fetchContent(input: $input)
}
```

- Re-generate Kotlin models so it includes the `FetchContent` operation

```sh
$ ./gradlew build
```

- Now you can use FetchContent operation like so:

```kotlin
import com.dashx.graphql.generated.* // Note the package name

val query = FetchContent(variables = FetchContent.Variables(FetchContentInput(
            contentType = contentType,
            content = content,
            preview = preview,
            language = language,
            fields = fields,
            include = include,
            exclude = exclude)))

coroutineScope.launch {
     val result = graphqlClient.execute(query)

     if (!result.errors.isNullOrEmpty()) {
           val errors = result.errors?.toString() ?: ""
           DashXLog.e(tag, errors)
           onError(errors)
           return@launch
     }

     result.data?.fetchContent?.let { onSuccess(gson.toJsonTree(it).asJsonObject) }
}
```

### Publishing

DashX Android SDK uses [Maven](https://mvnrepository.com/) to serve build artifacts. This repository uses [GitHub Actions](https://github.com/features/actions) and any push to **main** will automatically publish to Maven. Here are the rough steps:

- Bump up the `version`, `versionCode` and `versionName` in **dashx/build.gradle**
- Commit the version bump to **develop** (`git push origin develop`)
- Merge the latest **develop** branch into **main** branch

### Testing Locally

Using Gradle's [Composite Builds](https://publicobject.com/2021/03/11/includebuild/) feature, you can test changes to the DashX Android SDK locally against your application:

- Clone the [dashx-android](https://github.com/dashxhq/dashx-android) repository into the same parent folder as your project.
- Create a `local.settings.gradle` file with the following code:
    ```groovy
    includeBuild('../dashx-android') {
        dependencySubstitution {
            substitute module('com.dashx:dashx-android') using project(':dashx')
        }
    }
    ```
- Add the following code in your `settings.gradle` file, which applies code from `local.settings.gradle` if it exists:
    ```groovy
    def localSettings = file('local.settings.gradle')
    if (localSettings.exists()) {
        apply(from: localSettings)
    }
    ```
- Add the `local.settings.gradle` file to `.gitignore`, so it won't be committed.

You can check [dashx-demo-android](https://github.com/dashxhq/dashx-demo-android) for an example.
