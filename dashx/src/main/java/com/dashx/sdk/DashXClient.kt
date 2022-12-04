package com.dashx.sdk

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import com.dashx.graphql.generated.*
import com.dashx.graphql.generated.enums.ContactKind
import com.dashx.graphql.generated.enums.TrackNotificationStatus
import com.dashx.graphql.generated.inputs.*
import com.dashx.sdk.data.LibraryInfo
import com.dashx.sdk.data.PrepareExternalAssetResponse
import com.dashx.sdk.utils.*
import com.expediagroup.graphql.client.serialization.GraphQLClientKotlinxSerializer
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.messaging.FirebaseMessaging
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject

class DashXClient {

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
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    companion object {
        private var INSTANCE: DashXClient = DashXClient()
        private val tag = DashXClient::class.java.simpleName

        fun configure(
            context: Context,
            publicKey: String,
            baseURI: String? = null,
            targetEnvironment: String? = null,
            libraryInfo: LibraryInfo? = null
        ): DashXClient {
            INSTANCE.init(context, publicKey, baseURI, targetEnvironment, libraryInfo)

            FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(OnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        DashXLog.d(tag, "FirebaseMessaging.getInstance().getToken() failed: $task.exception")
                        return@OnCompleteListener
                    }

                    val token = task.getResult()
                    token?.let { it -> INSTANCE.setDeviceToken(it) }
                    DashXLog.d(tag, "Firebase Initialised with: $token")
                });

            return INSTANCE
        }

//        @JvmName("getDashXInstance")
        fun getInstance(): DashXClient {
            if (INSTANCE.context == null) {
                throw NullPointerException("Configure DashXClient before accessing it.")
            }
            return INSTANCE
        }
    }

    private fun init(
        context: Context,
        publicKey: String,
        baseURI: String? = null,
        targetEnvironment: String? = null,
        libraryInfo: LibraryInfo? = null
    ) {
        this.baseURI = baseURI
        this.publicKey = publicKey
        this.targetEnvironment = targetEnvironment
        this.context = context
        this.mustSubscribe = false

        SystemContext.configure(context)
        SystemContext.setLibraryInfo(libraryInfo)
        loadFromStorage()
        createGraphqlClient()
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

    private var graphqlClient = getGraphqlClient()

    private fun createGraphqlClient() {
        graphqlClient = getGraphqlClient()
    }

    fun setIdentityToken(identityToken: String) {
        this.identityToken = identityToken
        createGraphqlClient()
    }

    fun setDeviceToken(deviceToken: String) {
        this.deviceToken = deviceToken

        if (this.mustSubscribe) {
            subscribe()
        }
    }

    private fun getGraphqlClient(): DashXGraphQLKtorClient {
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
        return DashXGraphQLKtorClient(url = URL(baseURI ?: "https://api.dashx.com/graphql"), httpClient = httpClient, serializer = GraphQLClientKotlinxSerializer())
    }

    private fun generateAccountAnonymousUid(): String {
        return UUID.randomUUID().toString()
    }

    fun identify(options: HashMap<String, String>? = null) {

        DashXLog.d(tag, "identify() called")

        if (options == null) {
            DashXLog.d(tag, "identify() cannot be called with null, pass options: object")
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

        coroutineScope.launch {
            val result = graphqlClient.execute(query)

            if (!result.errors.isNullOrEmpty()) {
                val errors = result.errors?.toString() ?: ""
                DashXLog.e(tag, "identify() failed: $errors")
                return@launch
            }

            val response =  result.data?.identifyAccount?.let { gson.toJsonTree(it) }.toString()
            DashXLog.i(tag, "identify() succeeded: $response")
        }

    }

    fun setIdentity(uid: String?, token: String?) {
        this.accountUid = uid
        this.identityToken = token
        saveToStorage()

        DashXLog.d(tag, "setIdentity() called")

        createGraphqlClient()
    }

    fun reset() {
        accountUid = null
        identityToken = null
        accountAnonymousUid = generateAccountAnonymousUid()

        DashXLog.d(tag, "reset() called")

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
        DashXLog.d(tag, "fetchContent() called")

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
        coroutineScope.launch {
            val result = graphqlClient.execute(query)

            if (!result.errors.isNullOrEmpty()) {
                val errors = result.errors?.toString() ?: ""
                DashXLog.e(tag, "fetchContent() failed: $errors")
                onError(errors)
                return@launch
            }

            val response = result.data?.fetchContent?.let { gson.toJsonTree(it) }.toString()
            DashXLog.i(tag, "fetchContent() succeeded: $response")

            result.data?.fetchContent?.let { onSuccess(gson.toJsonTree(it).asJsonObject) }
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
        DashXLog.d(tag, "searchContent() called")

        val query = SearchContent(variables = SearchContent.Variables(SearchContentInput(contentType = contentType, returnType = returnType)))
        coroutineScope.launch {
            val result = graphqlClient.execute(query)

            if (!result.errors.isNullOrEmpty()) {
                val errors = result.errors?.toString() ?: ""
                DashXLog.e(tag, "searchContent() failed: $errors")
                onError(errors)
                return@launch
            }

            val response = result.data?.searchContent.let { gson.toJsonTree(it) }.toString()
            DashXLog.i(tag, "searchContent() succeeded: $response")

            val content = result.data?.searchContent ?: listOf()
            onSuccess(content.map { gson.toJsonTree(it).asJsonObject })
        }
    }

    fun fetchCart(
        onSuccess: (result: JsonObject) -> Unit,
        onError: (error: String) -> Unit
    ) {
        DashXLog.d(tag, "fetchCart() called")

        val query = FetchCart(variables = FetchCart.Variables(FetchCartInput(accountUid!!)))
        coroutineScope.launch {
            val result = graphqlClient.execute(query)

            if (!result.errors.isNullOrEmpty()) {
                val errors = result.errors?.toString() ?: ""
                DashXLog.e(tag, "fetchCart() failed: $errors")
                onError(errors)
                return@launch
            }

            val response = result.data?.fetchCart.let { gson.toJsonTree(it) }.toString()
            DashXLog.i(tag, "fetchCart() succeeded: $response")

            result.data?.fetchCart?.let { onSuccess(gson.toJsonTree(it).asJsonObject) }
        }
    }

    fun fetchStoredPreferences(
        onSuccess: (result: JsonObject) -> Unit,
        onError: (error: String) -> Unit
    ) {
        DashXLog.d(tag, "fetchStoredPreferences() called")

        val query = FetchStoredPreferences(variables = FetchStoredPreferences.Variables(FetchStoredPreferencesInput(accountUid!!)))
        coroutineScope.launch {
            val result = graphqlClient.execute(query)

            if (!result.errors.isNullOrEmpty()) {
                val errors = result.errors?.toString() ?: ""
                DashXLog.e(tag, "fetchStoredPreferences() failed: $errors")
                onError(errors)
                return@launch
            }

            val response = result.data?.fetchStoredPreferences.let { gson.toJsonTree(it) }.toString()
            DashXLog.i(tag, "fetchStoredPreferences() succeeded: $response")

            result.data?.fetchStoredPreferences?.let { onSuccess(gson.toJsonTree(it).asJsonObject) }
        }
    }

    fun uploadExternalAsset(file: File,
                            externalColumnId: String,
                            onSuccess: (result: com.dashx.sdk.data.ExternalAsset) -> Unit,
                            onError: (error: String) -> Unit) {
        DashXLog.d(tag, "uploadExternalAsset() called")

        val query = PrepareExternalAsset(variables = PrepareExternalAsset.Variables(PrepareExternalAssetInput(externalColumnId)))

        coroutineScope.launch {
            val result = graphqlClient.execute(query)

            if (!result.errors.isNullOrEmpty()) {
                val errors = result.errors?.toString() ?: ""
                DashXLog.e(tag, "uploadExternalAsset() failed: $errors")
                onError(errors)
                return@launch
            }

            val response = result.data?.prepareExternalAsset?.data.let { gson.toJsonTree(it) }.toString()
            DashXLog.i(tag, "uploadExternalAsset() succeeded: $response")

            val url = (gson.fromJson(result.data?.prepareExternalAsset?.data, PrepareExternalAssetResponse::class.java)).upload.url
            writeFileToUrl(file, url, result.data?.prepareExternalAsset?.id ?: "", onSuccess, onError)
        }
    }

    private suspend fun writeFileToUrl(file: File,
                                       url: String,
                                       id: String,
                                       onSuccess: (result: com.dashx.sdk.data.ExternalAsset) -> Unit,
                                       onError: (error: String) -> Unit) {
        withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.apply {
                doOutput = true
                requestMethod = RequestType.PUT
                setRequestProperty(FileConstants.CONTENT_TYPE, getFileContentType(context, file))
                setRequestProperty("x-goog-meta-origin-id", id)
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
    }

    private suspend fun externalAsset(id: String,
                                      onSuccess: (result: com.dashx.sdk.data.ExternalAsset) -> Unit,
                                      onError: (error: String) -> Unit) {

        val query = ExternalAsset(variables = ExternalAsset.Variables(id))
        val result = graphqlClient.execute(query)
        if (!result.errors.isNullOrEmpty()) {
            val errors = result.errors?.toString() ?: ""
            DashXLog.e(tag, errors)
            onError(errors)
            return
        }

        if (result.data?.externalAsset?.status != UploadConstants.READY && pollCounter <= UploadConstants.POLL_TIME_OUT) {
            delay(UploadConstants.POLL_INTERVAL)
            externalAsset(id, onSuccess, onError)
            pollCounter += 1

        } else {
            pollCounter = 1
            val responseObject = result.data?.externalAsset
            val externalDataJsonObject = responseObject?.data?.let { JSONObject(it) }
            val responseJsonObject = JSONObject(gson.toJson(responseObject))
            responseJsonObject.put(DATA, externalDataJsonObject)

            val externalAsset = gson.fromJson(responseJsonObject.toString(), com.dashx.sdk.data.ExternalAsset::class.java)
            if (externalAsset.data.asset?.url == null && !externalAsset.data.asset?.playbackIds.isNullOrEmpty()) {
                externalAsset.data.asset?.url = generateMuxVideoUrl(externalAsset.data.asset?.playbackIds?.get(0)?.id)
            }
            onSuccess(externalAsset)
        }
    }

    fun saveStoredPreferences(
        preferenceData: JSON,
        onSuccess: (result: JsonObject) -> Unit,
        onError: (error: String) -> Unit
    ) {
        DashXLog.d(tag, "saveStoredPreferences() called")

        val query = SaveStoredPreferences(variables = SaveStoredPreferences.Variables(SaveStoredPreferencesInput(accountUid!!, preferenceData)))
        coroutineScope.launch {
            val result = graphqlClient.execute(query)

            if (!result.errors.isNullOrEmpty()) {
                val errors = result.errors?.toString() ?: ""
                DashXLog.e(tag, "saveStoredPreferences() failed: $errors")
                onError(errors)
                return@launch
            }

            val response = result.data?.saveStoredPreferences
            DashXLog.i(tag, "saveStoredPreferences() succeeded: $response")

            onSuccess(gson.toJsonTree(result.data?.saveStoredPreferences).asJsonObject)
        }
    }

    fun addItemToCart(itemId: String,
                      pricingId: String,
                      quantity: String,
                      reset: Boolean,
                      custom: JsonObject? = null,
                      onSuccess: (result: JsonObject) -> Unit,
                      onError: (error: String) -> Unit) {
        DashXLog.d(tag, "addItemToCart() called")

        val query = AddItemToCart(variables = AddItemToCart.Variables(AddItemToCartInput(accountUid = accountUid!!, itemId = itemId, pricingId = pricingId, quantity = quantity, reset = reset)))
        coroutineScope.launch {
            val result = graphqlClient.execute(query)

            if (!result.errors.isNullOrEmpty()) {
                val errors = result.errors?.toString() ?: ""
                DashXLog.e(tag, "addItemToCart() failed: $errors")
                onError(errors)
                return@launch
            }

            val response = result.data?.addItemToCart.let { gson.toJsonTree(it) }.toString()
            DashXLog.i(tag, "addItemToCart() succeeded: $response")

            result.data?.addItemToCart.let { onSuccess(gson.toJsonTree(it).asJsonObject) }
        }
    }

    fun track(event: String, data: HashMap<String, String>? = hashMapOf()) {
        val jsonData = data?.toMap()?.let { JSONObject(it).toString() }

        DashXLog.d(tag, "track() called")

        val query = TrackEvent(variables = TrackEvent.Variables(TrackEventInput(accountAnonymousUid = accountAnonymousUid, accountUid = accountUid!!, data = jsonData, event = event, systemContext = gson.fromJson(SystemContext.getInstance().fetchSystemContext().toString(), SystemContextInput::class.java))))

        coroutineScope.launch {
            val result = graphqlClient.execute(query)

            if (!result.errors.isNullOrEmpty()) {
                val errors = result.errors?.toString()
                DashXLog.e(tag, "track() failed: $errors")
                return@launch
            }

            val response = result.data?.trackEvent.let { gson.toJsonTree(it) }.toString()
            DashXLog.i(tag, "track() succeeded: $response")
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
        DashXLog.d(tag, "subscribe() called")

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
                coroutineScope.launch {
                    val result = graphqlClient.execute(query)

                    if (!result.errors.isNullOrEmpty()) {
                        val errors = result.errors?.toString() ?: ""
                        DashXLog.e(tag, errors)
                        DashXLog.e(tag, "subscribe() failed $errors")
                        return@launch
                    }

                    val subscribeCallResponse = result.data?.subscribeContact.let { gson.toJsonTree(it) }.toString()
                    DashXLog.i(tag, "subscribe() succeeded: $subscribeCallResponse")

                    result.data?.subscribeContact.let { gson.toJsonTree(it) }
                }
            }
            else -> {
                DashXLog.d(tag, "Already subscribed: $deviceToken")
            }
        }
    }

    fun trackNotification(id: String, status: TrackNotificationStatus) {
        DashXLog.d(tag, "trackNotification() called")
        val currentTime = Instant.now().toString()

        val query = TrackNotification(variables = TrackNotification.Variables(TrackNotificationInput(id = id, status = status, timestamp = currentTime)))
        coroutineScope.launch {
            val result = graphqlClient.execute(query)

            if (!result.errors.isNullOrEmpty()) {
                val errors = result.errors?.toString() ?: ""
                DashXLog.e(tag, "trackNotification() failed: $errors")
                return@launch
            }

            val response = result.data?.trackNotification.let { gson.toJsonTree(it) }.toString()
            DashXLog.i(tag, "trackNotification() succeeded: $response")

            result.data?.trackNotification.let { gson.toJsonTree(it) }
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
