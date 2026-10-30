
# Contributing

## Obtaining Graphql schema and generating Graphql operation

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
import com.dashx.android.graphql.generated.* // Note the package name

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

     result.data?.fetchContent?.let { onSuccess(it) }
}
```

## Publishing

DashX Android SDK is published to [Maven Central](https://central.sonatype.com/artifact/com.dashx/dashx-android) as `com.dashx:dashx-android`. Every push to **main** will automatically publish the new version.

To release:

- Set `dashxAndroid` in **gradle/libs.versions.toml** to the new version. The published version and `BuildConfig.VERSION_NAME` both come from it.
- Add the version's entry to **CHANGELOG.md** and update the version in **README.md**'s install snippet.
- Merge the changes into **develop**, then merge **develop** into **main**. The push to **main** publishes.
- Tag the merge commit on **main** with the bare version and push the tag (`git tag 1.5.1 && git push origin 1.5.1`).

## Testing Locally

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
