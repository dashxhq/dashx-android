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

- Add jitpack repository in your `settings.gradle`

```groovy
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url "https://jitpack.io" } // <- This
    }
}
```

- If you are using `gradle < 7` add this to your global `build.gradle`

```groovy
allprojects {
    repositories {
        // ...
        maven { url 'https://jitpack.io' }
    }
}
```

- Add `dashx-android` to your app dependencies:

```groovy
dependencies {
    implementation 'com.github.dashxhq:dashx-android:main-SNAPSHOT'
    // ...
}
```

## Usage

```kotlin
val dashXClient = DashXClient("Your Public Key")
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
dashXClient.identify(uid, hashMapOf("name" to "John Doe") /* identifyOptions */)
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
dashXClient.track(event, hashMapOf("page" to "index") /* trackData */)
```

`trackData` accepts `HashMap<String, String>`

### Fetch Content

```kotlin
dashXClient.fetchContent("contacts/user", language = "en_US", onSuccess = {
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
dashXClient.searchContent("contacts",
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
=======
For detailed usage, refer to the [documentation](https://docs.dashx.com/developer).

## Contributing

### Obtaining Graphql schema and generating Graphql operation

- Make sure to install Apollo CLI via npm:

```sh
$ npm i -g apollo
```
- In order to generate code, Apollo requires local copy of Graphql schema, to download that:

```sh
$ apollo schema:download --endpoint="https://api.dashx.com/graphql" app/src/main/graphql/com/dashx/schema.json
```

This will save a schama.json file in your ios directory.

- Add Graphql request in `src/main/graphql/com/dashx` dir.

- To re-generate Kotlin models for your graphql operations, run:

```sh
./gradlew build
```
---

For example, if you want to generate code for `FetchContent`.

- Download schema

```sh
$ apollo schema:download --endpoint="https://api.dashx.com/graphql" app/src/main/graphql/com/dashx/schema.json
```

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
import com.dashx.type.* // Note the package name

val fetchContentInput = FetchContentInput(
    contentType,
    content,
    Input.fromNullable(preview),
    Input.fromNullable(language),
    Input.fromNullable(fields),
    Input.fromNullable(include),
    Input.fromNullable(exclude)
)

val fetchContentQuery = FetchContentQuery(fetchContentInput)

apolloClient.query(fetchContentQuery)
    .enqueue(object : ApolloCall.Callback<FetchContentQuery.Data>() {
        override fun onFailure(e: ApolloException) {
            DashXLog.d(tag, "Could not get content for: $urn")
            onError(e.message ?: "")
            e.printStackTrace()
        }

        override fun onResponse(response: com.apollographql.apollo.api.Response<FetchContentQuery.Data>) {
            val content = response.data?.fetchContent
            if (!response.errors.isNullOrEmpty()) {
                val errors = response.errors?.map { e -> e.message }.toString()
                DashXLog.d(tag, errors)
                onError(errors)
                return
            }

            if (content != null) {
                onSuccess(content.asJsonObject)
            }

            DashXLog.d(tag, "Got content: $content")
        }
    })
```

## Publishing

DashX Android SDK uses [Jitpack](https://jitpack.io/) to serve build artifacts(`aar` file in this case). To publish new artifact:

- Go [here](https://jitpack.io/#dashxhq/dashx-android).
- Select the version you want to build, if it's not built already, and press *Get it*.
- Follow the steps to use the library.
