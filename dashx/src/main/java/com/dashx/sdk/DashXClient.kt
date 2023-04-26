package com.dashx.sdk

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.dashx.graphql.generated.*
import com.dashx.graphql.generated.enums.AssetUploadStatus
import com.dashx.graphql.generated.enums.ContactKind
import com.dashx.graphql.generated.enums.TrackNotificationStatus
import com.dashx.graphql.generated.fetchstoredpreferences.FetchStoredPreferencesResponse
import com.dashx.graphql.generated.inputs.*
import com.dashx.graphql.generated.savestoredpreferences.SaveStoredPreferencesResponse
import com.dashx.sdk.data.LibraryInfo
import com.dashx.sdk.data.PrepareAssetResponse
import com.dashx.sdk.utils.*
import com.expediagroup.graphql.client.serialization.GraphQLClientKotlinxSerializer
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.messaging.FirebaseMessaging
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

class DashXClient {

    // Setup variables
    private var baseURI: String? = null
    private var publicKey: String? = null
    private var targetEnvironment: String? = null

    // Account variables
    private var accountAnonymousUid: String? = null
    private var accountUid: String? = null
    private var identityToken: String? = null

    private var context: Context? = null

    private var pollCounter = 1
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

            return INSTANCE
        }

        @JvmName("getDashXInstance")
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

        SystemContext.configure(context)
        SystemContext.setLibraryInfo(libraryInfo)
        loadFromStorage()
        createGraphqlClient()
    }

    private fun loadFromStorage() {
        val dashXSharedPreferences = getDashXSharedPreferences(context!!)
        accountUid = dashXSharedPreferences.getString(SHARED_PREFERENCES_KEY_ACCOUNT_UID, null)
        accountAnonymousUid =
            dashXSharedPreferences.getString(SHARED_PREFERENCES_KEY_ACCOUNT_ANONYMOUS_UID, null)
        identityToken =
            dashXSharedPreferences.getString(SHARED_PREFERENCES_KEY_IDENTITY_TOKEN, null)

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
                level = LogLevel.NONE
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

        return DashXGraphQLKtorClient(
            url = URL(baseURI ?: "https://api.dashx.com/graphql"),
            httpClient = httpClient,
            serializer = GraphQLClientKotlinxSerializer()
        )
    }

    private fun generateAccountAnonymousUid(): String {
        return UUID.randomUUID().toString()
    }

    fun identify(options: HashMap<String, String>? = null) {
        if (options == null) {
            DashXLog.e(tag, "Cannot be called with null, pass options: object")
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

        val query = IdentifyAccount(
            variables = IdentifyAccount.Variables(
                IdentifyAccountInput(
                    uid = uid,
                    anonymousUid = anonymousUid,
                    email = options[UserAttributes.EMAIL],
                    phone = options[UserAttributes.PHONE],
                    name = options[UserAttributes.NAME],
                    firstName = options[UserAttributes.FIRST_NAME],
                    lastName = options[UserAttributes.LAST_NAME]
                )
            )
        )

        coroutineScope.launch {
            val result = graphqlClient.execute(query)

            if (!result.errors.isNullOrEmpty()) {
                val errors = result.errors?.toString() ?: ""
                DashXLog.e(tag, errors)
                return@launch
            }

            DashXLog.d(tag, result.data?.identifyAccount?.toString())
        }

    }

    fun setIdentity(uid: String?, token: String?) {
        this.accountUid = uid
        this.identityToken = token
        saveToStorage()

        createGraphqlClient()
    }

    fun reset() {
        unsubscribe()

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

        val query = FetchContent(
            variables = FetchContent.Variables(
                FetchContentInput(
                    contentType = contentType,
                    content = content,
                    preview = preview,
                    language = language,
                    fields = fields,
                    include = include,
                    exclude = exclude
                )
            )
        )

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
        val query = SearchContent(
            variables = SearchContent.Variables(
                SearchContentInput(
                    contentType = contentType, returnType = returnType
                )
            )
        )

        coroutineScope.launch {
            val result = graphqlClient.execute(query)

            if (!result.errors.isNullOrEmpty()) {
                val errors = result.errors?.toString() ?: ""
                DashXLog.e(tag, errors)
                onError(errors)
                return@launch
            }

            val content = result.data?.searchContent ?: listOf()
            onSuccess(content)
        }
    }

    fun fetchCart(
        onSuccess: (result: com.dashx.graphql.generated.fetchcart.Order) -> Unit,
        onError: (error: String) -> Unit
    ) {

        val query = FetchCart(variables = FetchCart.Variables(FetchCartInput(accountUid!!)))

        coroutineScope.launch {
            val result = graphqlClient.execute(query)

            if (!result.errors.isNullOrEmpty()) {
                val errors = result.errors?.toString() ?: ""
                DashXLog.e(tag, errors)
                onError(errors)
                return@launch
            }

            result.data?.fetchCart?.let { onSuccess(it) }
        }
    }

    fun fetchStoredPreferences(
        onSuccess: (result: FetchStoredPreferencesResponse) -> Unit,
        onError: (error: String) -> Unit
    ) {
        val query = FetchStoredPreferences(
            variables = FetchStoredPreferences.Variables(FetchStoredPreferencesInput(accountUid!!))
        )

        coroutineScope.launch {
            val result = graphqlClient.execute(query)

            if (!result.errors.isNullOrEmpty()) {
                val errors = result.errors?.toString() ?: ""
                DashXLog.e(tag, errors)
                onError(errors)
                return@launch
            }

            result.data?.fetchStoredPreferences?.let { onSuccess(it) }
        }
    }

    fun uploadAsset(
        file: File,
        resource: String,
        attribute: String,
        onSuccess: (result: com.dashx.sdk.data.Asset) -> Unit,
        onError: (error: String) -> Unit
    ) {
        val name = file.name
        val size = file.length().toInt()
        val uri = Uri.fromFile(file)
        val mimeType = context!!.contentResolver.getType(uri)

        val query = PrepareAsset(
            variables = PrepareAsset.Variables(
                PrepareAssetInput(
                    resource, attribute, name, mimeType, size
                )
            )
        )

        coroutineScope.launch {
            val result = graphqlClient.execute(query)

            if (!result.errors.isNullOrEmpty()) {
                val errors = result.errors?.toString() ?: ""
                DashXLog.e(tag, errors)
                onError(errors)
                return@launch
            }

            val prepareAssetResponse = result.data?.prepareAsset?.data?.let {
                Json { ignoreUnknownKeys = true }.decodeFromJsonElement<PrepareAssetResponse>(
                    it
                )
            }

            if (prepareAssetResponse?.upload != null) {
                writeFileToUrl(
                    file,
                    prepareAssetResponse.upload.url,
                    result.data?.prepareAsset?.id ?: "",
                    onSuccess,
                    onError
                )
            }
        }
    }

    private suspend fun writeFileToUrl(
        file: File,
        url: String,
        id: String,
        onSuccess: (result: com.dashx.sdk.data.Asset) -> Unit,
        onError: (error: String) -> Unit
    ) {
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
                asset(id, onSuccess, onError)
            } else {
                onError(connection.errorStream.toString())
            }
        }
    }

    private suspend fun asset(
        id: String,
        onSuccess: (result: com.dashx.sdk.data.Asset) -> Unit,
        onError: (error: String) -> Unit
    ) {
        val query = Asset(variables = Asset.Variables(id))
        val result = graphqlClient.execute(query)

        if (!result.errors.isNullOrEmpty()) {
            val errors = result.errors?.toString() ?: ""
            DashXLog.e(tag, errors)
            onError(errors)
            return
        }

        if (result.data?.asset?.uploadStatus != AssetUploadStatus.UPLOADED && pollCounter <= UploadConstants.POLL_TIME_OUT) {
            delay(UploadConstants.POLL_INTERVAL)
            asset(id, onSuccess, onError)
            pollCounter += 1
        } else {
            pollCounter = 1
            val responseObject = result.data?.asset
            val externalDataJsonObject = responseObject?.data?.let { JSONObject(it) }
            val responseJsonObject = JSONObject(responseObject.toString())
            responseJsonObject.put(DATA, externalDataJsonObject)

            val asset =
                Json { ignoreUnknownKeys = true }.decodeFromString<com.dashx.sdk.data.Asset>(
                    responseJsonObject.toString()
                )

            if (asset.data.asset?.url == null && !asset.data.asset?.playbackIds.isNullOrEmpty()) {
                asset.data.asset?.url =
                    generateMuxVideoUrl(asset.data.asset?.playbackIds?.get(0)?.id)
            }

            onSuccess(asset)
        }
    }

    fun saveStoredPreferences(
        preferenceData: JsonObject,
        onSuccess: (result: SaveStoredPreferencesResponse) -> Unit,
        onError: (error: String) -> Unit
    ) {
        val query = SaveStoredPreferences(
            variables = SaveStoredPreferences.Variables(
                SaveStoredPreferencesInput(
                    accountUid!!, Json.parseToJsonElement(preferenceData.toString()).jsonObject
                )
            )
        )

        coroutineScope.launch {
            val result = graphqlClient.execute(query)

            if (!result.errors.isNullOrEmpty()) {
                val errors = result.errors?.toString() ?: ""
                DashXLog.e(tag, errors)
                onError(errors)
                return@launch
            }

            result.data?.saveStoredPreferences?.let { onSuccess(it) }
        }
    }

    fun addItemToCart(
        itemId: String,
        pricingId: String,
        quantity: String,
        reset: Boolean,
        custom: JsonObject? = null,
        onSuccess: (result: com.dashx.graphql.generated.additemtocart.Order) -> Unit,
        onError: (error: String) -> Unit
    ) {
        val query = AddItemToCart(
            variables = AddItemToCart.Variables(
                AddItemToCartInput(
                    accountUid = accountUid!!,
                    itemId = itemId,
                    pricingId = pricingId,
                    quantity = quantity,
                    reset = reset
                )
            )
        )

        coroutineScope.launch {
            val result = graphqlClient.execute(query)

            if (!result.errors.isNullOrEmpty()) {
                val errors = result.errors?.toString() ?: ""
                DashXLog.e(tag, errors)
                onError(errors)
                return@launch
            }

            result.data?.let { onSuccess(it.addItemToCart) }
        }
    }

    fun track(event: String, data: HashMap<String, String>? = hashMapOf()) {
        val jsonData =
            data?.toMap()?.let { Json.parseToJsonElement(JSONObject(it).toString()).jsonObject }

        val systemContext = Json {
            ignoreUnknownKeys = true
        }.decodeFromString<SystemContextInput>(
            SystemContext.getInstance().fetchSystemContext().toString()
        )

        val query = TrackEvent(
            variables = TrackEvent.Variables(
                TrackEventInput(
                    accountAnonymousUid = accountAnonymousUid,
                    accountUid = accountUid,
                    data = jsonData,
                    event = event,
                    systemContext = systemContext
                )
            )
        )

        coroutineScope.launch {
            val result = graphqlClient.execute(query)

            if (!result.errors.isNullOrEmpty()) {
                val errors = result.errors?.toString()
                DashXLog.e(tag, errors)
                return@launch
            }

            DashXLog.d(tag, result.data?.trackEvent?.toString())
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

        val eventProperties = hashMapOf(
            "version" to packageInfo.versionName.toString(), "build" to currentBuild.toString()
        )

        if (fromBackground) eventProperties["from_background"] = true.toString()

        when {
            getDashXSharedPreferences(context).getLong(
                SHARED_PREFERENCES_KEY_BUILD, Long.MIN_VALUE
            ) == Long.MIN_VALUE -> {
                track(INTERNAL_EVENT_APP_INSTALLED, eventProperties)
                saveBuildInPreferences()
            }

            getDashXSharedPreferences(context).getLong(
                SHARED_PREFERENCES_KEY_BUILD, Long.MIN_VALUE
            ) < currentBuild -> {
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
        FirebaseMessaging.getInstance().token.addOnCompleteListener(OnCompleteListener { task ->
            if (!task.isSuccessful) {
                DashXLog.e(
                    tag, "FirebaseMessaging.getInstance().getToken() failed: $task.exception"
                )
                return@OnCompleteListener
            }

            val newToken = task.result

            if (newToken == null) {
                DashXLog.e(tag, "Didn't receive any token from Firebase.")
                return@OnCompleteListener
            }

            DashXLog.d(tag, "New token generated: $newToken")

            val savedToken = getDashXSharedPreferences(context!!).getString(
                SHARED_PREFERENCES_KEY_DEVICE_TOKEN, null
            )

            if (savedToken == newToken) {
                DashXLog.d(tag, "Already subscribed: $savedToken")
                return@OnCompleteListener
            }

            val name = Settings.Global.getString(
                context?.contentResolver, Settings.Global.DEVICE_NAME
            ) ?: Settings.Secure.getString(context?.contentResolver, "bluetooth_name")

            val query = SubscribeContact(
                variables = SubscribeContact.Variables(
                    SubscribeContactInput(
                        accountUid = accountUid,
                        accountAnonymousUid = accountAnonymousUid,
                        name = name,
                        kind = ContactKind.ANDROID,
                        value = newToken,
                        osName = "Android",
                        osVersion = Build.VERSION.RELEASE,
                        deviceManufacturer = Build.MANUFACTURER,
                        deviceModel = Build.MODEL
                    )
                )
            )

            coroutineScope.launch {
                val result = graphqlClient.execute(query)

                if (!result.errors.isNullOrEmpty()) {
                    val errors = result.errors?.toString() ?: ""
                    DashXLog.e(tag, "Failed to subscribe: $errors")
                    return@launch
                } else {
                    getDashXSharedPreferences(context!!).edit().apply {
                        putString(SHARED_PREFERENCES_KEY_DEVICE_TOKEN, newToken)
                    }.apply()
                }

                DashXLog.d(tag, result.data?.subscribeContact?.toString())
            }
        })
    }

    fun unsubscribe() {
        val savedToken = getDashXSharedPreferences(context!!).getString(
            SHARED_PREFERENCES_KEY_DEVICE_TOKEN, null
        )

        if (savedToken == null) {
            DashXLog.e(tag, "unsubscribe() called without subscribing first")
            return
        }

        val uid = accountUid
        val anonymousUid = accountAnonymousUid

        FirebaseMessaging.getInstance().deleteToken()
            .addOnCompleteListener(OnCompleteListener { task ->
                if (!task.isSuccessful) {
                    DashXLog.e(
                        tag, "FirebaseMessaging.getInstance().deleteToken() failed: $task.exception"
                    )
                    return@OnCompleteListener
                }

                getDashXSharedPreferences(context!!).edit().apply {
                    remove(SHARED_PREFERENCES_KEY_DEVICE_TOKEN)
                }.apply()

                val query = UnsubscribeContact(
                    variables = UnsubscribeContact.Variables(
                        UnsubscribeContactInput(
                            accountUid = uid,
                            accountAnonymousUid = anonymousUid,
                            value = savedToken
                        )
                    )
                )

                coroutineScope.launch {
                    val result = graphqlClient.execute(query)

                    if (!result.errors.isNullOrEmpty()) {
                        val errors = result.errors?.toString() ?: ""
                        DashXLog.e(tag, "Failed to unsubscribe: $errors")
                        return@launch
                    } else {
                        DashXLog.d(tag, "Unsubscribed $savedToken successfully.")
                    }

                    DashXLog.d(tag, result.data?.unsubscribeContact?.toString())
                }
            })
    }

    fun trackNotification(id: String, status: TrackNotificationStatus) {
        val currentTime = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

        val query = TrackNotification(
            variables = TrackNotification.Variables(
                TrackNotificationInput(
                    id = id, status = status, timestamp = currentTime
                )
            )
        )

        coroutineScope.launch {
            val result = graphqlClient.execute(query)

            if (!result.errors.isNullOrEmpty()) {
                val errors = result.errors?.toString() ?: ""
                DashXLog.e(tag, errors)
                return@launch
            }

            DashXLog.d(tag, result.data?.trackNotification?.toString())
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
