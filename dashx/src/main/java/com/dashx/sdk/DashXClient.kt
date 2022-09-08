package com.dashx.sdk

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import com.dashx.graphql.generated.*
import com.dashx.graphql.generated.enums.ContactKind
import com.dashx.graphql.generated.enums.TrackNotificationStatus
import com.dashx.graphql.generated.inputs.*
import com.expediagroup.graphql.client.jackson.GraphQLClientJacksonSerializer
import com.expediagroup.graphql.client.ktor.GraphQLKtorClient
import com.expediagroup.graphql.client.serialization.GraphQLClientKotlinxSerializer
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.utils.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.*
import java.util.UUID
import java.util.concurrent.TimeUnit

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

    private var apolloClient = getGraphqlClient()

    private fun createApolloClient() {
        apolloClient = getGraphqlClient()
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

   /* private fun getApolloClient(): ApolloClient {
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
    }*/

    private fun getGraphqlClient(): GraphQLKtorClient {
        val httpClient = HttpClient(engineFactory = io.ktor.client.engine.okhttp.OkHttp) {
            engine {
                config {
                    connectTimeout(10, TimeUnit.SECONDS)
                    readTimeout(60, TimeUnit.SECONDS)
                    writeTimeout(60, TimeUnit.SECONDS)
                }
            }
            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.HEADERS
            }

            defaultRequest {
                publicKey?.let {
                    header("X-Public-Key", it)
                }

                targetEnvironment?.let {
                    header("X-Target-Environment", it)
                }

                identityToken?.let {
                    header("X-Identity-Token", it)
                }
            }
        }
        return GraphQLKtorClient(url = URL(baseURI ?: "https://api.dashx.com/graphql"), httpClient = httpClient, serializer = GraphQLClientKotlinxSerializer())
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



        val query = IdentifyAccount(variables = IdentifyAccount.Variables(IdentifyAccountInput(uid = uid,
            anonymousUid = anonymousUid,
            email = options[UserAttributes.EMAIL],
            phone = options[UserAttributes.PHONE],
            name = options[UserAttributes.NAME],
            firstName = options[UserAttributes.FIRST_NAME],
            lastName = options[UserAttributes.LAST_NAME])))
        runBlocking {
            val result = apolloClient.execute(query)
            println("\tquery without parameters result: $result")
        }

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

        val query = FetchContent(variables = FetchContent.Variables(FetchContentInput(
            contentType = contentType,
            content = content,
            preview = preview,
            language = language,
            fields = fields,
            include = include,
            exclude = exclude)))
        runBlocking {
            val result = apolloClient.execute(query)
            println("\tquery without parameters result: $result")
        }


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

        val query = SearchContent(variables = SearchContent.Variables(SearchContentInput(contentType = contentType, returnType = returnType)))
        runBlocking {
            val result = apolloClient.execute(query)
            println("\tquery without parameters result: $result")
        }
    }

    fun fetchCart(
        onSuccess: (result: JsonObject) -> Unit,
        onError: (error: String) -> Unit
    ) {

        val query = FetchCart(variables = FetchCart.Variables(FetchCartInput(accountUid!!)))
        runBlocking {
            val result = apolloClient.execute(query)
            println("\tquery without parameters result: $result")
        }
    }

    fun fetchStoredPreferences(
        onSuccess: (result: JsonObject) -> Unit,
        onError: (error: String) -> Unit
    ) {

        val httpClient = HttpClient(engineFactory = io.ktor.client.engine.okhttp.OkHttp) {
            engine {
                config {
                    connectTimeout(10, TimeUnit.SECONDS)
                    readTimeout(60, TimeUnit.SECONDS)
                    writeTimeout(60, TimeUnit.SECONDS)
                }
            }
            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.HEADERS
            }

            defaultRequest {
                publicKey?.let {
                    header("X-Public-Key", it)
                }

                targetEnvironment?.let {
                    header("X-Target-Environment", it)
                }

                identityToken?.let {
                    header("X-Identity-Token", it)
                }
            }
        }
        val ql = GraphQLKtorClient(url = URL("https://api.dashx-staging.com/graphql"), httpClient
        = httpClient, serializer = GraphQLClientKotlinxSerializer())

        val query = FetchStoredPreferences(variables = FetchStoredPreferences.Variables(FetchStoredPreferencesInput(accountUid!!)))
        val query1 = PrepareExternalAsset(variables = PrepareExternalAsset.Variables(PrepareExternalAssetInput("f03b20a8-2375-4f8d-bfbe-ce35141abe98")))
        runBlocking {
            val result = ql.execute(query1)
            println("\tquery without parameters result: $result")
        }
    }

    fun uploadExternalAsset(
        file: File, externalColumnId: String,
        onSuccess: (result: ExternalAsset) -> Unit,
        onError: (error: String) -> Unit
    ) {

        val query = PrepareExternalAsset(variables = PrepareExternalAsset.Variables(PrepareExternalAssetInput(accountUid!!)))
        runBlocking {
            val result = apolloClient.execute(query)
            println("\tquery without parameters result: $result")
        }

//        apolloClient.mutate(prepareExternalAssetMutation)
//            .enqueue(object : ApolloCall.Callback<PrepareExternalAssetMutation.Data>() {
//                override fun onResponse(response: Response<PrepareExternalAssetMutation.Data>) {
//                    val prepareExternalAssetResponse = response.data?.prepareExternalAsset
//
//                    if (!response.errors.isNullOrEmpty()) {
//                        val errors = response.errors?.map { e -> e.message }.toString()
//                        DashXLog.d(tag, errors)
//                        onError(errors)
//                        return
//                    }
//
//                    val url = (gson.fromJson(
//                        gson.toJsonTree(prepareExternalAssetResponse?.data).asJsonObject,
//                        PrepareExternalAssetResponse::class.java
//                    )).upload.url
//
//                    writeFileToUrl(file, url, prepareExternalAssetResponse?.id.toString(), onSuccess, onError)
//                }
//
//                override fun onFailure(e: ApolloException) {
//                    DashXLog.d(tag, e.message)
//                    e.printStackTrace()
//                }
//            })
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

        val query = ExternalAsset(variables = ExternalAsset.Variables(accountUid!!))
        runBlocking {
            val result = apolloClient.execute(query)

            println("\tquery without parameters result: $result")
        }
//        val externalAssetQuery = ExternalAssetQuery(id)

//        apolloClient.query(externalAssetQuery)
//            .enqueue(object : ApolloCall.Callback<ExternalAssetQuery.Data>() {
//                override fun onResponse(response: Response<ExternalAssetQuery.Data>) {
//                    val externalAssetResponse = response.data?.externalAsset
//
//                    if (!response.errors.isNullOrEmpty()) {
//                        val errors = response.errors?.map { e -> e.message }.toString()
//                        DashXLog.d(tag, errors)
//                        onError(errors)
//                        return
//                    }
//
//                    if (externalAssetResponse?.status != UploadConstants.READY && pollCounter <= UploadConstants.POLL_TIME_OUT) {
//                        runBlocking {
//                            delay(UploadConstants.POLL_INTERVAL)
//                            externalAsset(id, onSuccess, onError)
//                            pollCounter += 1
//                        }
//                    } else {
//                        pollCounter = 1
//                        val responseJsonObject = gson.toJson(externalAssetResponse)
//                        onSuccess(gson.fromJson(responseJsonObject, ExternalAsset::class.java))
//                    }
//                }
//
//                override fun onFailure(e: ApolloException) {
//                    DashXLog.d(tag, e.message)
//                    e.printStackTrace()
//                }
//            })
    }

    fun saveStoredPreferences(
        preferenceData: JSON,
        onSuccess: (result: JsonObject) -> Unit,
        onError: (error: String) -> Unit
    ) {

        val query = SaveStoredPreferences(variables = SaveStoredPreferences.Variables(SaveStoredPreferencesInput(accountUid!!, preferenceData)))
        runBlocking {
            val result = apolloClient.execute(query)
            println("\tquery without parameters result: $result")
        }

//        val saveStoredPreferencesInput = SaveStoredPreferencesInput(
//            accountUid = this.accountUid ?: "",
//            preferenceData
//        )
//        val saveStoredPreferencesMutation = SaveStoredPreferencesMutation(saveStoredPreferencesInput)
//
//        apolloClient
//            .mutate(saveStoredPreferencesMutation)
//            .enqueue(object : ApolloCall.Callback<SaveStoredPreferencesMutation.Data>() {
//                override fun onResponse(response: Response<SaveStoredPreferencesMutation.Data>) {
//                    val saveStoredPreferencesResponse = response.data?.saveStoredPreferences
//
//                    if (!response.errors.isNullOrEmpty()) {
//                        val errors = response.errors?.map { e -> e.message }.toString()
//                        DashXLog.d(tag, errors)
//                        onError(errors)
//                        return
//                    }
//                    onSuccess(gson.toJsonTree(saveStoredPreferencesResponse).asJsonObject)
//                }
//
//                override fun onFailure(e: ApolloException) {
//                    DashXLog.d(tag, e.message)
//                    e.printStackTrace()
//                }
//            })
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

        val query = FetchStoredPreferences(variables = FetchStoredPreferences.Variables(FetchStoredPreferencesInput(accountUid!!)))
        runBlocking {
            val result = apolloClient.execute(query)
            println("\tquery without parameters result: $result")
        }
    }

    fun track(event: String, data: HashMap<String, String>? = hashMapOf()) {
        val jsonData = data?.toMap()?.let { JSONObject(it) }.toString()

        val query = TrackEvent(variables = TrackEvent.Variables(TrackEventInput(accountAnonymousUid = accountAnonymousUid, accountUid = accountUid!!, data = jsonData, event = event)))
        runBlocking {
            val result = apolloClient.execute(query)
            println("\tquery without parameters result: $result")
        }
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

                val query = SubscribeContact(variables = SubscribeContact.Variables(
                    SubscribeContactInput(accountUid = accountUid,
                        accountAnonymousUid =accountAnonymousUid!!,
                        name = name,
                        kind = ContactKind.ANDROID,
                        value = deviceToken!!,
                        osName = "Android",
                        osVersion = Build.VERSION.RELEASE,
                        deviceManufacturer = Build.MANUFACTURER,
                        deviceModel = Build.MODEL)
                ))
                runBlocking {
                    val result = apolloClient.execute(query)
                    println("\tquery without parameters result: $result")
                }
            }
            else -> {
                DashXLog.d(tag, "Already subscribed: $deviceToken")
            }
        }
    }

    fun trackNotification(id: String, status: TrackNotificationStatus) {
        val currentTime = Instant.now().toString()

        val query = TrackNotification(variables = TrackNotification.Variables(TrackNotificationInput(id = id, status = status, timestamp = currentTime)))
        runBlocking {
            val result = apolloClient.execute(query)
            println("\tquery without parameters result: $result")
        }

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
