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
import com.dashx.type.*
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import java.io.File
import java.time.Instant
import java.util.*

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

    companion object {

        private var INSTANCE: DashXClient? = null

        fun createInstance(
            context: Context,
            publicKey: String,
            baseURI: String? = null,
            targetEnvironment: String? = null,
        ): DashXClient {
            if (INSTANCE == null) {
                INSTANCE = DashXClient()
                INSTANCE?.init(context, publicKey, baseURI, targetEnvironment)
            }
            return INSTANCE!!
        }

        @JvmName("getDashXInstance")
        fun getInstance(): DashXClient {
            try {
                return INSTANCE!!
            } catch (exception: Exception) {
                throw NullPointerException("Create DashXClient before accessing it.")
            }
        }
    }

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

        loadIdentity()
        createApolloClient()
    }

    private fun loadIdentity() {
        getDashXSharedPreferences(context!!).apply {
            if (contains(SHARED_PREFERENCES_KEY_ACCOUNT_UID)) {
                this@DashXClient.accountUid = getString(SHARED_PREFERENCES_KEY_ACCOUNT_UID, "")
            }
            if (contains(SHARED_PREFERENCES_KEY_IDENTITY_TOKEN)) {
                this@DashXClient.identityToken =
                    getString(SHARED_PREFERENCES_KEY_IDENTITY_TOKEN, "")
            }
        }
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
                    Gson().toJsonTree(value.value)
                } catch (e: java.lang.Exception) {
                    throw RuntimeException(e)
                }
            }

            override fun encode(value: JsonElement): CustomTypeValue<*> {
                return CustomTypeValue.GraphQLJsonObject(
                    Gson().fromJson(
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
                }
                .build())
            .build()
    }

    fun generateAnonymousUid(regenerate: Boolean = false) {
        val dashXSharedPreferences: SharedPreferences =
            getDashXSharedPreferences(context!!)
        val anonymousUid =
            dashXSharedPreferences.getString(SHARED_PREFERENCES_KEY_ANONYMOUS_UID, null)
        if (!regenerate && anonymousUid != null) {
            this.accountAnonymousUid = anonymousUid
        } else {
            this.accountAnonymousUid = UUID.randomUUID().toString()
            dashXSharedPreferences.edit()
                .putString(SHARED_PREFERENCES_KEY_ANONYMOUS_UID, this.accountAnonymousUid)
                .apply()
        }
    }

    fun identify(options: HashMap<String, String>? = null) {

        if (options == null) {
            DashXLog.d(tag, "Cannot be called with null, pass options: object")
            return
        }

        val uid = if (options.containsKey("uid")) {
            options["uid"]
        } else {
            this.accountUid
        }

        val anonymousUid = if (options.containsKey("anonymousUid")) {
            options["anonymousUid"]
        } else {
            this.accountAnonymousUid
        }

        val identifyAccountInput = IdentifyAccountInput(
            Input.fromNullable(uid),
            Input.fromNullable(anonymousUid),
            Input.fromNullable(options["email"]),
            Input.fromNullable(options["phone"]),
            Input.fromNullable(options["name"]),
            Input.fromNullable(options["firstName"]),
            Input.fromNullable(options["lastName"])
        )
        val identifyAccountMutation = IdentifyAccountMutation(identifyAccountInput)

        apolloClient
            .mutate(identifyAccountMutation)
            .enqueue(object : ApolloCall.Callback<IdentifyAccountMutation.Data>() {
                override fun onFailure(e: ApolloException) {
                    DashXLog.d(tag, "Could not identify with: $uid $options")
                    e.printStackTrace()
                }

                override fun onResponse(response: com.apollographql.apollo.api.Response<IdentifyAccountMutation.Data>) {
                    val identifyResponse = response.data?.identifyAccount
                    DashXLog.d(tag, "Sent identify: $identifyResponse")
                }
            })
    }

    fun setIdentity(uid: String?, token: String?) {
        this.accountUid = uid
        this.identityToken = token

        getDashXSharedPreferences(context!!).edit().apply {
            putString(SHARED_PREFERENCES_KEY_ACCOUNT_UID, uid)
            putString(SHARED_PREFERENCES_KEY_IDENTITY_TOKEN, token)
        }.apply()

        createApolloClient()
    }

    fun reset() {
        getDashXSharedPreferences(context!!).edit().apply {
            remove(SHARED_PREFERENCES_KEY_ACCOUNT_UID)
            remove(SHARED_PREFERENCES_KEY_IDENTITY_TOKEN)
        }.apply()
        accountUid = null
        identityToken = null
        generateAnonymousUid(regenerate = true)
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

                override fun onResponse(response: com.apollographql.apollo.api.Response<FetchContentQuery.Data>) {
                    val fetchContentResponse = response.data?.fetchContent
                    if (!response.errors.isNullOrEmpty()) {
                        val errors = response.errors?.map { e -> e.message }.toString()
                        DashXLog.d(tag, errors)
                        onError(errors)
                        return
                    }

                    if (fetchContentResponse != null) {
                        onSuccess(Gson().toJsonTree(fetchContentResponse).asJsonObject)
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

                override fun onResponse(response: com.apollographql.apollo.api.Response<SearchContentQuery.Data>) {
                    val content = response.data?.searchContent
                    if (!response.errors.isNullOrEmpty()) {
                        val errors = response.errors?.map { e -> e.message }.toString()
                        DashXLog.d(tag, errors)
                        onError(errors)
                        return
                    }

                    val result = content ?: listOf()
                    onSuccess(result.map { Gson().toJsonTree(it).asJsonObject })
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

                override fun onResponse(response: com.apollographql.apollo.api.Response<FetchCartQuery.Data>) {
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
                    val fetchStoredPreferencesResponse =
                        response.data?.fetchStoredPreferences?.preferenceData

                    if (!response.errors.isNullOrEmpty()) {
                        val errors = response.errors?.map { e -> e.message }.toString()
                        DashXLog.d(tag, errors)
                        onError(errors)
                        return
                    }

                    onSuccess(Gson().toJsonTree(fetchStoredPreferencesResponse).asJsonObject)
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
            this.accountUid ?: "",
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

                    onSuccess(Gson().toJsonTree(saveStoredPreferencesResponse).asJsonObject)
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

                override fun onResponse(response: com.apollographql.apollo.api.Response<AddItemToCartMutation.Data>) {
                    val addItemToCartResponse = response.data?.addItemToCart

                    if (!response.errors.isNullOrEmpty()) {
                        val errors = response.errors?.map { e -> e.message }.toString()
                        DashXLog.d(tag, errors)
                        onError(errors)
                        return
                    }

                    onSuccess(Gson().toJsonTree(addItemToCartResponse).asJsonObject)
                }
            })
    }

    fun track(event: String, data: HashMap<String, String>? = hashMapOf()) {
        val jsonData = Gson().toJsonTree(data)

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

                override fun onResponse(response: com.apollographql.apollo.api.Response<TrackEventMutation.Data>) {
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

                        override fun onResponse(response: com.apollographql.apollo.api.Response<SubscribeContactMutation.Data>) {
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

                override fun onResponse(response: com.apollographql.apollo.api.Response<TrackNotificationMutation.Data>) {
                    val trackNotificationResponse = response.data?.trackNotification
                    DashXLog.d(tag, "trackNotificationResponse: $trackNotificationResponse")
                }
            })
    }
}
