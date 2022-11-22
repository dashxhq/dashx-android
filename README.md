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

- Add this under application tag in Manifest.xml file
```kotlin
<meta-data android:name="com.google.android.gms.version" android:value="@integer/google_play_services_version" />
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
DashX.identify(uid, hashMapOf("name" to "John Doe") /* identifyOptions */)
```

`identifyOptions` can accept `HashMap<String, String>` with

|Name|Type|
|:---:|:--:|
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
DashX.fetchContent("contacts/user", language = "en_US", onSuccess = {
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
DashX.searchContent("contacts",
    language = "en_US", returnType = "all",
    filter = hashMapOf("name_eq" to "John"),
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

## Publishing

DashX Android SDK uses [Maven](https://mvnrepository.com/) to serve build artifacts. This repository uses [GitHub Actions](https://github.com/features/actions) and any push to **main** will automatically publish to Maven. Here are the rough steps:

- Bump up the `version`, `versionCode` and `versionName` in **dashx/build.gradle**
- Commit the version bump to **develop** (`git push origin develop`)
- Merge the latest **develop** branch into **main** branch
