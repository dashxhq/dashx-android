package com.dashx.android

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import com.dashx.graphql.generated.*
import com.dashx.graphql.generated.enums.AssetUploadStatus
import com.dashx.graphql.generated.enums.ContactKind
import com.dashx.graphql.generated.enums.TrackMessageStatus
import com.dashx.graphql.generated.fetchstoredpreferences.FetchStoredPreferencesResponse
import com.dashx.graphql.generated.inputs.*
import com.dashx.graphql.generated.savestoredpreferences.SaveStoredPreferencesResponse
import com.dashx.android.data.LibraryInfo
import com.dashx.android.data.PrepareAssetResponse
import com.dashx.android.utils.*
import com.expediagroup.graphql.client.serialization.GraphQLClientKotlinxSerializer
import com.expediagroup.graphql.client.types.GraphQLClientRequest
import com.expediagroup.graphql.client.types.GraphQLClientResponse
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
import java.util.concurrent.atomic.AtomicInteger

class DashX {
    companion object {
        private var baseURI: String? = null
        private var publicKey: String? = null
        private var targetEnvironment: String? = null

        @Volatile private var accountAnonymousUid: String? = null
        @Volatile private var accountUid: String? = null
        @Volatile private var identityToken: String? = null

        @Volatile private var context: Context? = null

        private val pollCounter = AtomicInteger(1)
        private val coroutineScope = CoroutineScope(Dispatchers.IO)
        private val tag = DashX::class.java.simpleName
        private val json = Json { ignoreUnknownKeys = true }

        fun configure(
            context: Context,
            publicKey: String,
            baseURI: String? = null,
            targetEnvironment: String? = null,
            libraryInfo: LibraryInfo? = null
        ) {
            init(context, publicKey, baseURI, targetEnvironment, libraryInfo)
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
            val ctx = context ?: run {
                DashXLog.e(tag, "loadFromStorage: context is null, configure() must be called first")
                return
            }
            val dashXSharedPreferences = getDashXSharedPreferences(ctx)
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
            val ctx = context ?: run {
                DashXLog.e(tag, "saveToStorage: context is null, configure() must be called first")
                return
            }
            getDashXSharedPreferences(ctx).edit().apply {
                putString(SHARED_PREFERENCES_KEY_ACCOUNT_UID, accountUid)
                putString(SHARED_PREFERENCES_KEY_ACCOUNT_ANONYMOUS_UID, accountAnonymousUid)
                putString(SHARED_PREFERENCES_KEY_IDENTITY_TOKEN, identityToken)
            }.apply()
        }

        @Volatile private var graphqlClient = getGraphqlClient()

        private fun createGraphqlClient() {
            graphqlClient = getGraphqlClient()
        }

        private fun <D : Any> executeQuery(
            query: GraphQLClientRequest<D>,
            onError: ((DashXError) -> Unit)? = null,
            onSuccess: (GraphQLClientResponse<D>) -> Unit
        ) {
            coroutineScope.launch {
                val result = graphqlClient.execute(query)
                if (result.hasErrors(onError)) return@launch
                onSuccess(result)
            }
        }

        private fun <D> GraphQLClientResponse<D>.hasErrors(
            onError: ((DashXError) -> Unit)? = null
        ): Boolean {
            if (!errors.isNullOrEmpty()) {
                val errorsString = errors?.toString() ?: ""
                if (errorsString.isNotEmpty()) {
                    val error = DashXError.GraphQLError(errorsString)
                    DashXLog.e(tag, errorsString)
                    onError?.invoke(error)
                }
                return true
            }
            return false
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

            executeQuery(query) { result ->
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

        fun fetchRecord(
            urn: String,
            preview: Boolean? = true,
            language: String? = null,
            fields: List<JsonObject>? = null,
            include: List<JsonObject>? = null,
            exclude: List<JsonObject>? = null,
            onSuccess: (result: JsonObject) -> Unit,
            onError: (error: DashXError) -> Unit
        ) {
            if (!urn.contains('/')) {
                onError(DashXError.NetworkError("URN must be of form: {resource}/{recordId}"))
                return
            }

            val urnArray = urn.split('/')
            val resource = urnArray[0]
            val recordId = urnArray[1]

            val query = FetchRecord(
                variables = FetchRecord.Variables(
                    FetchRecordInput(
                        resource = resource,
                        recordId = recordId,
                        preview = preview,
                        language = language,
                        fields = fields,
                        include = include,
                        exclude = exclude
                    )
                )
            )

            executeQuery(query, onError) { result ->
                result.data?.fetchRecord?.let(onSuccess)
            }
        }

        fun searchRecords(
            resource: String,
            filter: JsonObject? = null,
            order: List<JsonObject>? = null,
            limit: Int? = null,
            preview: Boolean? = true,
            language: String? = null,
            fields: List<JsonObject>? = null,
            include: List<JsonObject>? = null,
            exclude: List<JsonObject>? = null,
            onSuccess: (result: List<JsonObject>) -> Unit,
            onError: (error: DashXError) -> Unit
        ) {
            val query = SearchRecords(
                variables = SearchRecords.Variables(
                    SearchRecordsInput(
                        resource = resource,
                        filter = filter,
                        order = order,
                        limit = limit,
                        preview = preview,
                        language = language,
                        fields = fields,
                        include = include,
                        exclude = exclude
                    )
                )
            )

            executeQuery(query, onError) { result ->
                val records = result.data?.searchRecords ?: listOf()
                onSuccess(records)
            }
        }

        fun fetchCart(
            onSuccess: (result: com.dashx.graphql.generated.fetchcart.Order) -> Unit,
            onError: (error: DashXError) -> Unit
        ) {
            val uid = accountUid ?: run {
                onError(DashXError.NotIdentified())
                return
            }

            val query = FetchCart(variables = FetchCart.Variables(FetchCartInput(uid)))

            executeQuery(query, onError) { result ->
                result.data?.fetchCart?.let(onSuccess)
            }
        }

        fun addItemToCart(
            itemId: String,
            pricingId: String,
            quantity: String,
            reset: Boolean,
            custom: JsonObject? = null,
            onSuccess: (result: com.dashx.graphql.generated.additemtocart.Order) -> Unit,
            onError: (error: DashXError) -> Unit
        ) {
            val uid = accountUid ?: run {
                onError(DashXError.NotIdentified())
                return
            }
            val query = AddItemToCart(
                variables = AddItemToCart.Variables(
                    AddItemToCartInput(
                        accountUid = uid,
                        itemId = itemId,
                        pricingId = pricingId,
                        quantity = quantity,
                        reset = reset
                    )
                )
            )

            executeQuery(query, onError) { result ->
                result.data?.let { onSuccess(it.addItemToCart) }
            }
        }

        fun fetchStoredPreferences(
            onSuccess: (result: FetchStoredPreferencesResponse) -> Unit,
            onError: (error: DashXError) -> Unit
        ) {
            val uid = accountUid ?: run {
                onError(DashXError.NotIdentified())
                return
            }
            val query = FetchStoredPreferences(
                variables = FetchStoredPreferences.Variables(FetchStoredPreferencesInput(uid))
            )

            executeQuery(query, onError) { result ->
                result.data?.fetchStoredPreferences?.let(onSuccess)
            }
        }

        fun saveStoredPreferences(
            preferenceData: JsonObject,
            onSuccess: (result: SaveStoredPreferencesResponse) -> Unit,
            onError: (error: DashXError) -> Unit
        ) {
            val uid = accountUid ?: run {
                onError(DashXError.NotIdentified())
                return
            }
            val query = SaveStoredPreferences(
                variables = SaveStoredPreferences.Variables(
                    SaveStoredPreferencesInput(
                        uid, Json.parseToJsonElement(preferenceData.toString()).jsonObject
                    )
                )
            )

            executeQuery(query, onError) { result ->
                result.data?.saveStoredPreferences?.let(onSuccess)
            }
        }

        fun uploadAsset(
            file: File,
            resource: String,
            attribute: String,
            onSuccess: (result: com.dashx.android.data.Asset) -> Unit,
            onError: (error: DashXError) -> Unit
        ) {
            val ctx = context ?: run {
                onError(DashXError.NotConfigured())
                return
            }
            val name = file.name
            val size = file.length().toInt()
            val uri = Uri.fromFile(file)
            val mimeType = ctx.contentResolver.getType(uri) ?: run {
                onError(DashXError.AssetError("Could not determine MIME type for file"))
                return
            }

            val query = PrepareAsset(
                variables = PrepareAsset.Variables(
                    PrepareAssetInput(
                        attribute = attribute,
                        name = name,
                        resource = resource,
                        mimeType = mimeType,
                        size = size,
                    )
                )
            )

            coroutineScope.launch {
                val result = graphqlClient.execute(query)
                if (result.hasErrors(onError)) return@launch

                val prepareAssetResponse = result.data?.prepareAsset?.data?.let {
                    json.decodeFromJsonElement<PrepareAssetResponse>(it)
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
            onSuccess: (result: com.dashx.android.data.Asset) -> Unit,
            onError: (error: DashXError) -> Unit
        ) {
            withContext(Dispatchers.IO) {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.apply {
                        doOutput = true
                        requestMethod = RequestType.PUT
                        setRequestProperty(
                            FileConstants.CONTENT_TYPE,
                            getFileContentType(context, file)
                        )
                        setRequestProperty("x-goog-meta-origin-id", id)
                    }

                    FileInputStream(file).use { fileInputStream ->
                        connection.outputStream.use { outputStream ->
                            fileInputStream.copyTo(outputStream)
                        }
                    }

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        asset(id, onSuccess, onError)
                    } else {
                        onError(DashXError.AssetError("Upload failed with HTTP ${connection.responseCode}"))
                    }
                } finally {
                    connection.disconnect()
                }
            }
        }

        private suspend fun asset(
            id: String,
            onSuccess: (result: com.dashx.android.data.Asset) -> Unit,
            onError: (error: DashXError) -> Unit
        ) {
            val query = Asset(variables = Asset.Variables(id))
            val result = graphqlClient.execute(query)

            if (result.hasErrors(onError)) return

            if (result.data?.asset?.uploadStatus != AssetUploadStatus.UPLOADED && pollCounter.get() <= UploadConstants.POLL_TIME_OUT) {
                delay(UploadConstants.POLL_INTERVAL)
                pollCounter.incrementAndGet()
                asset(id, onSuccess, onError)
            } else {
                pollCounter.set(1)
                val responseObject = result.data?.asset
                val externalDataJsonObject = responseObject?.data?.let { JSONObject(it) }
                val responseJsonObject = JSONObject(responseObject.toString())
                responseJsonObject.put(DATA, externalDataJsonObject)

                val asset = json.decodeFromString<com.dashx.android.data.Asset>(
                    responseJsonObject.toString()
                )

                val uploadAsset = asset.data.asset
                if (uploadAsset != null && uploadAsset.url.isEmpty() && uploadAsset.playbackIds.isNotEmpty()) {
                    uploadAsset.url = generateMuxVideoUrl(uploadAsset.playbackIds[0].id)
                }

                onSuccess(asset)
            }
        }

        fun track(event: String, data: HashMap<String, String>? = hashMapOf()) {
            val jsonData =
                data?.toMap()?.let { Json.parseToJsonElement(JSONObject(it).toString()).jsonObject }

            val systemContext = json.decodeFromString<SystemContextInput>(
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

            executeQuery(query) { result ->
                DashXLog.d(tag, result.data?.trackEvent?.toString())
            }
        }

        internal fun trackEventBlocking(event: String, data: HashMap<String, String>?, timeoutMs: Long = 3000) {
            val jsonData =
                data?.toMap()?.let { Json.parseToJsonElement(JSONObject(it).toString()).jsonObject }

            val systemContext = json.decodeFromString<SystemContextInput>(
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

            try {
                runBlocking {
                    withTimeout(timeoutMs) {
                        graphqlClient.execute(query)
                    }
                }
            } catch (e: Exception) {
                DashXLog.e(tag, "Failed to track event synchronously: ${e.message}")
            }
        }

        fun trackAppStarted(fromBackground: Boolean = false) {
            val context = context ?: return

            val packageInfo = getPackageInfo(context)

            val currentBuild = PackageInfoCompat.getLongVersionCode(packageInfo)

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
            trackEventBlocking(INTERNAL_EVENT_APP_CRASHED, eventProperties)
        }

        fun screen(screenName: String, properties: HashMap<String, String>?) {
            properties?.set("name", screenName)
            track(INTERNAL_EVENT_APP_SCREEN_VIEWED, properties)
        }

        fun trackMessage(id: String, status: TrackMessageStatus) {
            val currentTime = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

            val query = TrackMessage(
                variables = TrackMessage.Variables(
                    TrackMessageInput(
                        id = id, status = status, timestamp = currentTime
                    )
                )
            )

            executeQuery(query) { result ->
                DashXLog.d(tag, result.data?.trackMessage?.toString())
            }
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

                val ctx = context ?: run {
                    DashXLog.e(tag, "subscribe: context is null, configure() must be called first")
                    return@OnCompleteListener
                }

                val savedToken = getDashXSharedPreferences(ctx).getString(
                    SHARED_PREFERENCES_KEY_DEVICE_TOKEN, null
                )

                if (savedToken == newToken) {
                    DashXLog.d(tag, "Already subscribed: $savedToken")
                    return@OnCompleteListener
                }

                val name = Settings.Global.getString(
                    ctx.contentResolver, Settings.Global.DEVICE_NAME
                ) ?: Settings.Secure.getString(ctx.contentResolver, "bluetooth_name")

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

                executeQuery(query, onError = { error ->
                    DashXLog.e(tag, "Failed to subscribe: ${error.message}")
                }) { result ->
                    context?.let { c ->
                        getDashXSharedPreferences(c).edit().apply {
                            putString(SHARED_PREFERENCES_KEY_DEVICE_TOKEN, newToken)
                        }.apply()
                    }
                    DashXLog.d(tag, result.data?.subscribeContact?.toString())
                }
            })
        }

        fun unsubscribe() {
            val ctx = context ?: run {
                DashXLog.e(tag, "unsubscribe: context is null, configure() must be called first")
                return
            }
            val savedToken = getDashXSharedPreferences(ctx).getString(
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
                            tag,
                            "FirebaseMessaging.getInstance().deleteToken() failed: $task.exception"
                        )
                        return@OnCompleteListener
                    }

                    context?.let { c ->
                        getDashXSharedPreferences(c).edit().apply {
                            remove(SHARED_PREFERENCES_KEY_DEVICE_TOKEN)
                        }.apply()
                    }

                    val query = UnsubscribeContact(
                        variables = UnsubscribeContact.Variables(
                            UnsubscribeContactInput(
                                accountUid = uid,
                                accountAnonymousUid = anonymousUid,
                                value = savedToken
                            )
                        )
                    )

                    executeQuery(query, onError = { error ->
                        DashXLog.e(tag, "Failed to unsubscribe: ${error.message}")
                    }) { result ->
                        DashXLog.d(tag, "Unsubscribed $savedToken successfully.")
                        DashXLog.d(tag, result.data?.unsubscribeContact?.toString())
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
}
