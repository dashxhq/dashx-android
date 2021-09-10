# dashx-android

_DashX Android SDK_

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
|**`targetInstallation`**|`String`|
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

`searchContent` accepts following arguments

|Name|Type|Example|
|:--:|:--:|:-----:|
|**`returnType`**|`"all"` or `"one"`||
|**`filter`**|`HashMap<String, String>`|`["name_eq": "John"]`|
|**`order`**|`HashMap<String, String>`|`["created_at": "DESC"]`|
|**`limit`**|`Int`||
|**`preview`**|`Boolean`||
|**`language`**|`String`|`"en_US"`||
|**`fields`**|`List<String>`|`["character", "cast"]`||
|**`include`**|`List<String>`|`["character.createdBy", "character.birthDate"]`||
|**`exclude`**|`List<String>`|`["directors"]`||
