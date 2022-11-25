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

- Add maven repository in your `settings.gradle`

```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
```

- If you are using `gradle < 7` add this to your global `build.gradle`

```groovy
allprojects {
    repositories {
        // ...
        mavenCentral()
    }
}
```

- Add `dashx-android` to your app dependencies:

```groovy
dependencies {
    implementation 'com.dashx:dashx-android:1.0.8'
    // ...
}
```

## Usage

```kotlin
val dashX = DashX.configure("Your Public Key")
```

DashXClient can be initialised with:

|Name|Type|
|:---:|:--:|
|**`publicKey`**|`String` _(Required)_ |
|**`accountType`**|`String`|
|**`baseURI`**|`String`|
|**`targetEnvironment`**|`String`|

### Identify User

```kotlin
DashX.identify(hashMapOf("name" to "John Doe") /* identifyOptions */)
```

`identifyOptions` can accept `HashMap<String, String>` with

|Name|Type|
|:---:|:--:|
|**`uid`**|`String`|
|**`anonymousUid`**|`String`|
|**`firstName`**|`String`|
|**`lastName`**|`String`|
|**`name`**|`String`|
|**`email`**|`String`|
|**`phone`**|`String`|

### Track Events

```kotlin
DashX.track(event, hashMapOf("page" to "index") /* trackData */)
```

`trackData` accepts `HashMap<String, String>`

### Fetch Content

```kotlin
DashX.fetchContent("email/welcome", language = "en_US", onSuccess = {
    println(it)
}, onError = {
    println(it)
})
```

`fetchContent` accepts following arguments

|Name|Type|Example|
|:--:|:--:|:-----:|
|**`preview`**|`Boolean`||
|**`language`**|`String`|`"en_US"`||
|**`fields`**|`List<String>`|`["character", "cast"]`||
|**`include`**|`List<String>`|`["character.createdBy", "character.birthDate"]`||
|**`exclude`**|`List<String>`|`["directors"]`||

### Search Content

```kotlin
DashX.searchContent("email",
    language = "en_US", returnType = "all",
    filter = hashMapOf("identifier_eq" to "welcome"),
    order = hashMapOf("created_at" to "DESC"),
    limit = 10,
    preview = true,
    onSuccess = {
        println(it)
    }, onError = {
        println(it)
    })
```

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
           val errors = result.errors?.map { e -> e.message }.toString()
           DashXLog.d(tag, errors)
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
