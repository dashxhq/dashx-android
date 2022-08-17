package com.dashx.sdk

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import com.apollographql.apollo.ApolloCall
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.CustomTypeAdapter
import com.apollographql.apollo.api.CustomTypeValue
import com.apollographql.apollo.api.Input
import com.apollographql.apollo.api.Response
import com.apollographql.apollo.api.cache.http.HttpCachePolicy
import com.apollographql.apollo.cache.http.ApolloHttpCache
import com.apollographql.apollo.cache.http.DiskLruHttpCacheStore
import com.apollographql.apollo.exception.ApolloException
import com.dashx.*
import com.dashx.sdk.data.ExternalAsset
import com.dashx.sdk.data.PrepareExternalAssetResponse
import com.dashx.type.*
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.*

val DashX = DashXClient.getInstance()

class DashXClient {
    private val tag = DashXClient::class.java.simpleName

    // Setup variables
    private var baseURI: String? = null
    private var publicKey: String? = null
    private var targetEnvironment: String? = null

    // Account variables
    private var accountAnonymousUid: String? = null
    private var accountUid: String? = null
    private var deviceToken: String? = null
    private var identityToken: String? = null

    private var context: Context? = null

    private var mustSubscribe: Boolean = false

    private var pollCounter = 1
    private val gson by lazy { Gson() }

    companion object {

        private var INSTANCE: DashXClient = DashXClient()

        fun configure(
            context: Context,
            publicKey: String,
            baseURI: String? = null,
            targetEnvironment: String? = null,
        ): DashXClient {
            INSTANCE.init(context, publicKey, baseURI, targetEnvironment)
            return INSTANCE
        }

        @JvmName("getDashXInstance")
        fun getInstance(): DashXClient {
            try {
                return INSTANCE
            } catch (exception: Exception) {
                throw NullPointerException("Create DashXClient before accessing it.")
            }
        }
    }

    fun configure(context: Context, publicKey: String, baseURI: String? = null, targetEnvironment: String? = null): DashXClient =
        DashXClient.configure(context, publicKey, baseURI, targetEnvironment)

    private fun init(
        context: Context,
        publicKey: String,
        baseURI: String? = null,
        targetEnvironment: String? = null,
    ) {
        this.baseURI = baseURI
        this.publicKey = publicKey
        this.targetEnvironment = targetEnvironment
        this.context = context
        this.mustSubscribe = false

        loadFromStorage()
        createApolloClient()
    }

    private fun loadFromStorage() {
        val dashXSharedPreferences = getDashXSharedPreferences(context!!)
        accountUid = dashXSharedPreferences.getString(SHARED_PREFERENCES_KEY_ACCOUNT_UID, null)
        accountAnonymousUid = dashXSharedPreferences.getString(SHARED_PREFERENCES_KEY_ACCOUNT_ANONYMOUS_UID, null)
        identityToken = dashXSharedPreferences.getString(SHARED_PREFERENCES_KEY_IDENTITY_TOKEN, null)

        if (accountAnonymousUid.isNullOrEmpty()) {
            accountAnonymousUid = generateAccountAnonymousUid()
            saveToStorage()
        }
    }

    private fun saveToStorage() {
        getDashXSharedPreferences(context!!).edit().apply {
            putString(SHARED_PREFERENCES_KEY_ACCOUNT_UID, accountUid)
            putString(SHARED_PREFERENCES_KEY_ACCOUNT_ANONYMOUS_UID, accountAnonymousUid)
            putString(SHARED_PREFERENCES_KEY_IDENTITY_TOKEN, identityToken)
        }.apply()
    }

    private var apolloClient = getApolloClient()

    private fun createApolloClient() {
        apolloClient = getApolloClient()
    }

    fun setIdentityToken(identityToken: String) {
        this.identityToken = identityToken
        createApolloClient()
    }

    fun setDeviceToken(deviceToken: String) {
        this.deviceToken = deviceToken

        if (this.mustSubscribe) {
            subscribe()
        }
    }

    private fun getApolloClient(): ApolloClient {
        val file = File(context?.cacheDir, "dashXCache")
        val size: Long = 5 * 1024 * 1024
        val cacheStore = DiskLruHttpCacheStore(file, size)
        val gsonCustomTypeAdapter = object : CustomTypeAdapter<JsonElement> {
            override fun decode(value: CustomTypeValue<*>): JsonElement {
                return try {
                    gson.toJsonTree(value.value)
                } catch (e: java.lang.Exception) {
                    throw RuntimeException(e)
                }
            }

            override fun encode(value: JsonElement): CustomTypeValue<*> {
                return CustomTypeValue.GraphQLJsonObject(
                    gson.fromJson(
                        value,
                        Map::class.java
                    ) as Map<String, Any>
                )
            }
        }

        return ApolloClient.builder()
            .serverUrl(baseURI ?: "https://api.dashx.com/graphql")
            .addCustomTypeAdapter(CustomType.JSON, gsonCustomTypeAdapter)
            .httpCache(ApolloHttpCache(cacheStore))
            .defaultHttpCachePolicy(HttpCachePolicy.NETWORK_FIRST)
            .okHttpClient(OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val requestBuilder = chain.request().newBuilder()
                        .addHeader("X-Public-Key", publicKey!!)

                    targetEnvironment?.let {
                        requestBuilder.addHeader("X-Target-Environment", it)
                    }

                    if (identityToken != null) {
                        requestBuilder.addHeader("X-Identity-Token", identityToken!!)
                    }

                    return@addInterceptor chain.proceed(requestBuilder.build())
                }.build()).build()
    }

    fun generateAccountAnonymousUid(): String {
        return UUID.randomUUID().toString()
    }

    fun identify(options: HashMap<String, String>? = null) {

        if (options == null) {
            DashXLog.d(tag, "Cannot be called with null, pass options: object")
            return
        }

        val uid = if (options.containsKey(UserAttributes.UID)) {
            options[UserAttributes.UID]
        } else {
            this.accountUid
        }

        val anonymousUid = if (options.containsKey(UserAttributes.ANONYMOUS_UID)) {
            options[UserAttributes.ANONYMOUS_UID]
        } else {
            this.accountAnonymousUid
        }

        val identifyAccountInput = IdentifyAccountInput(
            uid = Input.fromNullable(uid),
            anonymousUid = Input.fromNullable(anonymousUid),
            email = Input.fromNullable(options[UserAttributes.EMAIL]),
            phone = Input.fromNullable(options[UserAttributes.PHONE]),
            name = Input.fromNullable(options[UserAttributes.NAME]),
            firstName = Input.fromNullable(options[UserAttributes.FIRST_NAME]),
            lastName = Input.fromNullable(options[UserAttributes.LAST_NAME])
        )
        val identifyAccountMutation = IdentifyAccountMutation(identifyAccountInput)

        apolloClient
            .mutate(identifyAccountMutation)
            .enqueue(object : ApolloCall.Callback<IdentifyAccountMutation.Data>() {
                override fun onFailure(e: ApolloException) {
                    DashXLog.d(tag, "Could not identify with: $uid $options")
                    e.printStackTrace()
                }

                override fun onResponse(response: Response<IdentifyAccountMutation.Data>) {
                    val identifyResponse = response.data?.identifyAccount
                    DashXLog.d(tag, "Sent identify: $identifyResponse")
                }
            })
    }

    fun setIdentity(uid: String?, token: String?) {
        this.accountUid = uid
        this.identityToken = token
        saveToStorage()

        createApolloClient()
    }

    fun reset() {
        accountUid = null
        identityToken = null
        accountAnonymousUid = generateAccountAnonymousUid()
        saveToStorage()
    }

    fun fetchContent(
        urn: String,
        preview: Boolean? = true,
        language: String? = null,
        fields: List<String>? = null,
        include: List<String>? = null,
        exclude: List<String>? = null,
        onSuccess: (result: JsonObject) -> Unit,
        onError: (error: String) -> Unit
    ) {
        if (!urn.contains('/')) {
            throw Exception("URN must be of form: {contentType}/{content}")
        }

        val urnArray = urn.split('/')
        val content = urnArray[1]
        val contentType = urnArray[0]

        val fetchContentInput = FetchContentInput(
            installationId = Input.fromNullable(null),
            contentType = Input.fromNullable(contentType),
            content = Input.fromNullable(content),
            preview = Input.fromNullable(preview),
            language = Input.fromNullable(language),
            fields = Input.fromNullable(fields),
            include = Input.fromNullable(include),
            exclude = Input.fromNullable(exclude)
        )

        val fetchContentQuery = FetchContentQuery(fetchContentInput)

        apolloClient.query(fetchContentQuery)
            .enqueue(object : ApolloCall.Callback<FetchContentQuery.Data>() {
                override fun onFailure(e: ApolloException) {
                    DashXLog.d(tag, "Could not get content for: $urn")
                    onError(e.message ?: "")
                    e.printStackTrace()
                }

                override fun onResponse(response: Response<FetchContentQuery.Data>) {
                    val fetchContentResponse = response.data?.fetchContent
                    if (!response.errors.isNullOrEmpty()) {
                        val errors = response.errors?.map { e -> e.message }.toString()
                        DashXLog.d(tag, errors)
                        onError(errors)
                        return
                    }

                    if (fetchContentResponse != null) {
                        onSuccess(gson.toJsonTree(fetchContentResponse).asJsonObject)
                    }

                    DashXLog.d(tag, "Got content: $content")
                }
            })
    }

    fun searchContent(
        contentType: String,
        returnType: String = "all",
        filter: JsonObject? = null,
        order: JsonObject? = null,
        limit: Int? = null,
        preview: Boolean? = true,
        language: String? = null,
        fields: List<String>? = null,
        include: List<String>? = null,
        exclude: List<String>? = null,
        onSuccess: (result: List<JsonObject>) -> Unit,
        onError: (error: String) -> Unit
    ) {
        val searchContentInput = SearchContentInput(
            contentType,
            returnType,
            Input.fromNullable(filter),
            Input.fromNullable(order),
            Input.fromNullable(limit),
            Input.fromNullable(preview),
            Input.fromNullable(language),
            Input.fromNullable(fields),
            Input.fromNullable(include),
            Input.fromNullable(exclude)
        )

        val searchContentQuery = SearchContentQuery(searchContentInput)

        apolloClient.query(searchContentQuery)
            .enqueue(object : ApolloCall.Callback<SearchContentQuery.Data>() {
                override fun onFailure(e: ApolloException) {
                    DashXLog.d(tag, "Could not get content for: $contentType")
                    e.printStackTrace()
                    onError(e.message ?: "")
                }

                override fun onResponse(response: Response<SearchContentQuery.Data>) {
                    val content = response.data?.searchContent
                    if (!response.errors.isNullOrEmpty()) {
                        val errors = response.errors?.map { e -> e.message }.toString()
                        DashXLog.d(tag, errors)
                        onError(errors)
                        return
                    }

                    val result = content ?: listOf()
                    onSuccess(result.map { gson.toJsonTree(it).asJsonObject })
                    DashXLog.d(tag, "Got content: $content")
                }
            })
    }

    fun fetchCart(
        onSuccess: (result: JsonObject) -> Unit,
        onError: (error: String) -> Unit
    ) {
        val fetchCartInput = FetchCartInput(
            Input.fromNullable(accountUid),
            Input.fromNullable(accountAnonymousUid),
        )
        val fetchCartQuery = FetchCartQuery(fetchCartInput)

        apolloClient
            .query(fetchCartQuery)
            .enqueue(object : ApolloCall.Callback<FetchCartQuery.Data>() {
                override fun onFailure(e: ApolloException) {
                    DashXLog.d(tag, e.message)
                    e.printStackTrace()
                }

                override fun onResponse(response: Response<FetchCartQuery.Data>) {
                    val fetchCartResponse = response.data?.fetchCart

                    if (!response.errors.isNullOrEmpty()) {
                        val errors = response.errors?.map { e -> e.message }.toString()
                        DashXLog.d(tag, errors)
                        onError(errors)
                        return
                    }
                    onSuccess(Gson().toJsonTree(fetchCartResponse).asJsonObject)
                }
            })
    }

    fun fetchStoredPreferences(
        onSuccess: (result: JsonObject) -> Unit,
        onError: (error: String) -> Unit
    ) {
        val fetchStoredPreferencesInput = FetchStoredPreferencesInput(this.accountUid ?: "")
        val fetchStoredPreferencesQuery = FetchStoredPreferencesQuery(fetchStoredPreferencesInput)

        apolloClient
            .query(fetchStoredPreferencesQuery)
            .enqueue(object : ApolloCall.Callback<FetchStoredPreferencesQuery.Data>() {
                override fun onResponse(response: Response<FetchStoredPreferencesQuery.Data>) {
                    val fetchStoredPreferencesResponse = response.data?.fetchStoredPreferences?.preferenceData

                    if (!response.errors.isNullOrEmpty()) {
                        val errors = response.errors?.map { e -> e.message }.toString()
                        DashXLog.d(tag, errors)
                        onError(errors)
                        return
                    }

                    onSuccess(gson.toJsonTree(fetchStoredPreferencesResponse).asJsonObject)
                }

                override fun onFailure(e: ApolloException) {
                    DashXLog.d(tag, e.message)
                    e.printStackTrace()
                }
            })
    }

    fun uploadExternalAsset(
        file: File, externalColumnId: String,
        onSuccess: (result: ExternalAsset) -> Unit,
        onError: (error: String) -> Unit
    ) {
        val prepareExternalAssetInput = PrepareExternalAssetInput(externalColumnId)

        val prepareExternalAssetMutation = PrepareExternalAssetMutation(prepareExternalAssetInput)

        apolloClient.mutate(prepareExternalAssetMutation)
            .enqueue(object : ApolloCall.Callback<PrepareExternalAssetMutation.Data>() {
                override fun onResponse(response: Response<PrepareExternalAssetMutation.Data>) {
                    val prepareExternalAssetResponse = response.data?.prepareExternalAsset

                    if (!response.errors.isNullOrEmpty()) {
                        val errors = response.errors?.map { e -> e.message }.toString()
                        DashXLog.d(tag, errors)
                        onError(errors)
                        return
                    }

                    val url = (gson.fromJson(
                        gson.toJsonTree(prepareExternalAssetResponse?.data).asJsonObject,
                        PrepareExternalAssetResponse::class.java
                    )).upload.url

                    writeFileToUrl(file, url, prepareExternalAssetResponse?.id.toString(), onSuccess, onError)
                }

                override fun onFailure(e: ApolloException) {
                    DashXLog.d(tag, e.message)
                    e.printStackTrace()
                }
            })
    }

    fun writeFileToUrl(
        file: File, url: String, id: String, onSuccess: (result: ExternalAsset) -> Unit,
        onError: (error: String) -> Unit
    ) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.apply {
            doOutput = true
            requestMethod = RequestType.PUT
            setRequestProperty(FileConstants.CONTENT_TYPE, getFileContentType(context, file))
            setRequestProperty("x-goog-meta-origin-id",id)
        }

        val outputStream = connection.outputStream
        val fileInputStream = FileInputStream(file)
        val boundaryBytes = getBytes(fileInputStream)
        outputStream.write(boundaryBytes)
        outputStream.close()

        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            externalAsset(id, onSuccess, onError)
        } else {
            onError(connection.errorStream.toString())
        }
    }

    fun externalAsset(
        id: String,
        onSuccess: (result: ExternalAsset) -> Unit,
        onError: (error: String) -> Unit
    ) {
        val externalAssetQuery = ExternalAssetQuery(id)

        apolloClient.query(externalAssetQuery)
            .enqueue(object : ApolloCall.Callback<ExternalAssetQuery.Data>() {
                override fun onResponse(response: Response<ExternalAssetQuery.Data>) {
                    val externalAssetResponse = response.data?.externalAsset

                    if (!response.errors.isNullOrEmpty()) {
                        val errors = response.errors?.map { e -> e.message }.toString()
                        DashXLog.d(tag, errors)
                        onError(errors)
                        return
                    }

                    if (externalAssetResponse?.status != UploadConstants.READY && pollCounter <= UploadConstants.POLL_TIME_OUT) {
                        runBlocking {
                            delay(UploadConstants.POLL_INTERVAL)
                            externalAsset(id, onSuccess, onError)
                            pollCounter += 1
                        }
                    } else {
                        pollCounter = 1
                        val responseJsonObject = gson.toJson(externalAssetResponse)
                        onSuccess(gson.fromJson(responseJsonObject, ExternalAsset::class.java))
                    }
                }

                override fun onFailure(e: ApolloException) {
                    DashXLog.d(tag, e.message)
                    e.printStackTrace()
                }
            })
    }

    fun saveStoredPreferences(
        preferenceData: Any,
        onSuccess: (result: JsonObject) -> Unit,
        onError: (error: String) -> Unit
    ) {

        val saveStoredPreferencesInput = SaveStoredPreferencesInput(
            accountUid = this.accountUid ?: "",
            preferenceData
        )
        val saveStoredPreferencesMutation = SaveStoredPreferencesMutation(saveStoredPreferencesInput)

        apolloClient
            .mutate(saveStoredPreferencesMutation)
            .enqueue(object : ApolloCall.Callback<SaveStoredPreferencesMutation.Data>() {
                override fun onResponse(response: Response<SaveStoredPreferencesMutation.Data>) {
                    val saveStoredPreferencesResponse = response.data?.saveStoredPreferences

                    if (!response.errors.isNullOrEmpty()) {
                        val errors = response.errors?.map { e -> e.message }.toString()
                        DashXLog.d(tag, errors)
                        onError(errors)
                        return
                    }
                    onSuccess(gson.toJsonTree(saveStoredPreferencesResponse).asJsonObject)
                }

                override fun onFailure(e: ApolloException) {
                    DashXLog.d(tag, e.message)
                    e.printStackTrace()
                }
            })
    }

    fun addItemToCart(
        itemId: String,
        pricingId: String,
        quantity: String,
        reset: Boolean,
        custom: JsonObject? = null,
        onSuccess: (result: JsonObject) -> Unit,
        onError: (error: String) -> Unit
    ) {
        val addItemToCartInput = AddItemToCartInput(
            Input.fromNullable(accountUid),
            Input.fromNullable(accountAnonymousUid),
            itemId,
            pricingId,
            quantity,
            reset,
            Input.fromNullable(custom)
        )
        val addItemToCartMutation = AddItemToCartMutation(addItemToCartInput)

        apolloClient
            .mutate(addItemToCartMutation)
            .enqueue(object : ApolloCall.Callback<AddItemToCartMutation.Data>() {
                override fun onFailure(e: ApolloException) {
                    DashXLog.d(tag, e.message)
                    e.printStackTrace()
                }

                override fun onResponse(response: Response<AddItemToCartMutation.Data>) {
                    val addItemToCartResponse = response.data?.addItemToCart

                    if (!response.errors.isNullOrEmpty()) {
                        val errors = response.errors?.map { e -> e.message }.toString()
                        DashXLog.d(tag, errors)
                        onError(errors)
                        return
                    }
                    onSuccess(gson.toJsonTree(addItemToCartResponse).asJsonObject)
                }
            })
    }

    fun track(event: String, data: HashMap<String, String>? = hashMapOf()) {
        val jsonData = gson.toJsonTree(data)

        val trackEventInput = TrackEventInput(
            event,
            Input.fromNullable(accountUid),
            Input.fromNullable(accountAnonymousUid),
            Input.fromNullable(jsonData)
        )
        val trackEventMutation = TrackEventMutation(trackEventInput)

        apolloClient
            .mutate(trackEventMutation)
            .enqueue(object : ApolloCall.Callback<TrackEventMutation.Data>() {
                override fun onFailure(e: ApolloException) {
                    DashXLog.d(tag, e.message)
                    e.printStackTrace()
                }

                override fun onResponse(response: Response<TrackEventMutation.Data>) {
                    val trackResponse = response.data?.trackEvent
                    DashXLog.d(tag, "Sent event: $event, $trackResponse")
                }
            })
    }

    fun trackAppStarted(fromBackground: Boolean = false) {
        val context = context ?: return

        val packageInfo = getPackageInfo(context)
        val currentBuild = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }

        fun saveBuildInPreferences() {
            val editor: SharedPreferences.Editor = getDashXSharedPreferences(context).edit()
            editor.putLong(SHARED_PREFERENCES_KEY_BUILD, currentBuild)
            editor.apply()
        }

        val eventProperties =
            hashMapOf("version" to packageInfo.versionName, "build" to currentBuild.toString())
        if (fromBackground) eventProperties.set("from_background", true.toString())

        when {
            getDashXSharedPreferences(context).getLong(
                SHARED_PREFERENCES_KEY_BUILD,
                Long.MIN_VALUE
            ) == Long.MIN_VALUE
            -> {
                track(INTERNAL_EVENT_APP_INSTALLED, eventProperties)
                saveBuildInPreferences()
            }
            getDashXSharedPreferences(context).getLong(
                SHARED_PREFERENCES_KEY_BUILD,
                Long.MIN_VALUE
            ) < currentBuild
            -> {
                track(INTERNAL_EVENT_APP_UPDATED, eventProperties)
                saveBuildInPreferences()
            }
            else -> track(INTERNAL_EVENT_APP_OPENED, eventProperties)
        }
    }

    fun trackAppSession(elapsedTime: Double) {
        val elapsedTimeRounded = elapsedTime / 1000
        val eventProperties = hashMapOf("session_length" to elapsedTimeRounded.toString())
        track(INTERNAL_EVENT_APP_BACKGROUNDED, eventProperties)
    }

    fun trackAppCrashed(exception: Throwable?) {
        val message = exception?.message
        val eventProperties = hashMapOf("exception" to (message ?: ""))
        track(INTERNAL_EVENT_APP_CRASHED, eventProperties)
    }

    fun screen(screenName: String, properties: HashMap<String, String>?) {
        properties?.set("name", screenName)
        track(INTERNAL_EVENT_APP_SCREEN_VIEWED, properties)
    }

    fun subscribe() {
        if (deviceToken == null) {
            DashXLog.d(tag, "subscribe called without deviceToken; deferring")
            this.mustSubscribe = true
            return
        }

        this.mustSubscribe = false

        fun saveDeviceToken(deviceToken: String) {
            val editor: SharedPreferences.Editor = getDashXSharedPreferences(context!!).edit()
            editor.putString(SHARED_PREFERENCES_KEY_DEVICE_TOKEN, deviceToken)
            editor.apply()
        }

        when {
            getDashXSharedPreferences(context!!).getString(
                SHARED_PREFERENCES_KEY_DEVICE_TOKEN,
                null
            ) != deviceToken
            -> {
                val name = Settings.Global.getString(
                    context?.getContentResolver(),
                    Settings.Global.DEVICE_NAME
                ) ?: Settings.Secure.getString(context?.getContentResolver(), "bluetooth_name")

                val subscribeContactInput = SubscribeContactInput(
                    accountUid = Input.fromNullable(accountUid),
                    accountAnonymousUid = Input.fromNullable(accountAnonymousUid!!),
                    name = Input.fromNullable(name),
                    kind = ContactKind.ANDROID,
                    value = deviceToken!!,
                    osName = Input.fromNullable("Android"),
                    osVersion = Input.fromNullable(Build.VERSION.RELEASE),
                    deviceManufacturer = Input.fromNullable(Build.MANUFACTURER),
                    deviceModel = Input.fromNullable(Build.MODEL)
                )
                val subscribeContactMutation = SubscribeContactMutation(subscribeContactInput)

                apolloClient
                    .mutate(subscribeContactMutation)
                    .enqueue(object : ApolloCall.Callback<SubscribeContactMutation.Data>() {
                        override fun onFailure(e: ApolloException) {
                            DashXLog.d(tag, e.message)
                            e.printStackTrace()
                        }

                        override fun onResponse(response: Response<SubscribeContactMutation.Data>) {
                            val subscribeContactResponse = response.data?.subscribeContact
                            if (subscribeContactResponse != null) {
                                saveDeviceToken(subscribeContactResponse.value)
                                DashXLog.d(tag, "Subscribed: $subscribeContactResponse")
                            } else if (response.errors != null) {
                                DashXLog.d(tag, "$response.errors")
                            }
                        }
                    })
            }
            else -> {
                DashXLog.d(tag, "Already subscribed: $deviceToken")
            }
        }
    }

    fun trackNotification(id: String, status: TrackNotificationStatus) {
        val currentTime = Instant.now().toString();

        val trackNotificationInput = TrackNotificationInput(
            id,
            status,
            currentTime
        )

        val trackNotificationMutation = TrackNotificationMutation(trackNotificationInput)

        apolloClient
            .mutate(trackNotificationMutation)
            .enqueue(object : ApolloCall.Callback<TrackNotificationMutation.Data>() {
                override fun onFailure(e: ApolloException) {
                    DashXLog.d(tag, e.message)
                    e.printStackTrace()
                }

                override fun onResponse(response: Response<TrackNotificationMutation.Data>) {
                    val trackNotificationResponse = response.data?.trackNotification
                    DashXLog.d(tag, "trackNotificationResponse: $trackNotificationResponse")
                }
            })
    }

    fun getBaseUri(): String? {
        return baseURI
    }

    fun getPublicKey(): String? {
        return publicKey
    }

    fun getTargetEnvironment(): String? {
        return targetEnvironment
    }

    fun getIdentityToken(): String? {
        return identityToken
    }
}
