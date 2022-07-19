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
    implementation 'com.dashx:dashx-android:1.0.0'
    // ...
}
```

## Usage

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

DashX Android SDK uses [Maven](https://mvnrepository.com/) to serve build artifacts(`aar` file in this case). To publish new artifact:

- Raise a pull request and got that merged.
- Merging to main branch will publish the SDK into maven using CI/CD
