package com.dashx.android

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.api.Query
import com.apollographql.apollo.api.Mutation
import com.dashx.graphql.generated.AssetQuery
import com.dashx.graphql.generated.FetchCartQuery
import com.dashx.graphql.generated.FetchRecordQuery
import com.dashx.graphql.generated.FetchStoredPreferencesQuery
import com.dashx.graphql.generated.IdentifyAccountMutation
import com.dashx.graphql.generated.PrepareAssetMutation
import com.dashx.graphql.generated.SaveStoredPreferencesMutation
import com.dashx.graphql.generated.SearchRecordsQuery
import com.dashx.graphql.generated.SubscribeContactMutation
import com.dashx.graphql.generated.TrackEventMutation
import com.dashx.graphql.generated.TrackMessageMutation
import com.dashx.graphql.generated.UnsubscribeContactMutation
import com.dashx.graphql.generated.AddItemToCartMutation
import com.dashx.graphql.generated.type.AssetUploadStatus
import com.dashx.graphql.generated.type.ContactKind
import com.dashx.graphql.generated.type.FetchCartInput
import com.dashx.graphql.generated.type.FetchRecordInput
import com.dashx.graphql.generated.type.FetchStoredPreferencesInput
import com.dashx.graphql.generated.type.IdentifyAccountInput
import com.dashx.graphql.generated.type.PrepareAssetInput
import com.dashx.graphql.generated.type.SaveStoredPreferencesInput
import com.dashx.graphql.generated.type.SearchRecordsInput
import com.dashx.graphql.generated.type.SubscribeContactInput
import com.dashx.graphql.generated.type.SystemContextInput
import com.dashx.graphql.generated.type.TrackEventInput
import com.dashx.graphql.generated.type.TrackMessageInput
import com.dashx.graphql.generated.type.AddItemToCartInput
import com.dashx.graphql.generated.type.TrackMessageStatus
import com.dashx.graphql.generated.type.UnsubscribeContactInput
import com.dashx.android.data.LibraryInfo
import com.dashx.android.data.PrepareAssetResponse
import com.dashx.android.utils.*
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.messaging.FirebaseMessaging
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
import java.util.concurrent.TimeUnit
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
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

        @Volatile private var apolloClient = createApolloClient()

        private fun createGraphqlClient() {
            apolloClient = createApolloClient()
        }

        private fun createApolloClient(): ApolloClient {
            return ApolloClient.Builder()
                .serverUrl(baseURI ?: "https://api.dashx.com/graphql")
                .apply {
                    publicKey?.let { addHttpHeader("X-Public-Key", it) }
                    targetEnvironment?.let { addHttpHeader("X-Target-Environment", it) }
                    identityToken?.let { addHttpHeader("X-Identity-Token", it) }
                }
                .build()
        }

        private fun hasApolloErrors(
            errors: List<*>?,
            exception: Throwable?,
            onError: ((DashXError) -> Unit)? = null
        ): Boolean {
            exception?.let {
                val error = DashXError.GraphQLError(it.message ?: "")
                DashXLog.e(tag, it.message ?: "")
                onError?.invoke(error)
                return true
            }
            if (!errors.isNullOrEmpty()) {
                val errorsString = errors.toString()
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

        private fun <D : Mutation.Data> executeMutation(
            mutation: Mutation<D>,
            onError: ((DashXError) -> Unit)? = null,
            onSuccess: (ApolloResponse<D>) -> Unit
        ) {
            coroutineScope.launch {
                val response = apolloClient.mutation(mutation).execute()
                if (hasApolloErrors(response.errors, response.exception, onError)) return@launch
                onSuccess(response)
            }
        }

        private fun <D : Query.Data> executeQuery(
            query: Query<D>,
            onError: ((DashXError) -> Unit)? = null,
            onSuccess: (ApolloResponse<D>) -> Unit
        ) {
            coroutineScope.launch {
                val response = apolloClient.query(query).execute()
                if (hasApolloErrors(response.errors, response.exception, onError)) return@launch
                onSuccess(response)
            }
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

            val mutation = IdentifyAccountMutation(
                input = IdentifyAccountInput(
                    uid = Optional.Present(uid),
                    anonymousUid = Optional.Present(anonymousUid),
                    email = options[UserAttributes.EMAIL]?.let { Optional.Present(it) } ?: Optional.Absent,
                    phone = options[UserAttributes.PHONE]?.let { Optional.Present(it) } ?: Optional.Absent,
                    name = options[UserAttributes.NAME]?.let { Optional.Present(it) } ?: Optional.Absent,
                    firstName = options[UserAttributes.FIRST_NAME]?.let { Optional.Present(it) } ?: Optional.Absent,
                    lastName = options[UserAttributes.LAST_NAME]?.let { Optional.Present(it) } ?: Optional.Absent
                )
            )

            executeMutation(mutation) { result ->
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

            val query = FetchRecordQuery(
                input = FetchRecordInput(
                    recordId = recordId,
                    resource = Optional.Present(resource),
                    preview = preview?.let { Optional.Present(it) } ?: Optional.Absent,
                    language = language?.let { Optional.Present(it) } ?: Optional.Absent,
                    fields = fields?.let { Optional.Present(it) } ?: Optional.Absent,
                    include = include?.let { Optional.Present(it) } ?: Optional.Absent,
                    exclude = exclude?.let { Optional.Present(it) } ?: Optional.Absent
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
            val query = SearchRecordsQuery(
                input = SearchRecordsInput(
                    resource = resource,
                    filter = filter?.let { Optional.Present(it) } ?: Optional.Absent,
                    order = order?.let { Optional.Present(it) } ?: Optional.Absent,
                    limit = limit?.let { Optional.Present(it) } ?: Optional.Absent,
                    preview = preview?.let { Optional.Present(it) } ?: Optional.Absent,
                    language = language?.let { Optional.Present(it) } ?: Optional.Absent,
                    fields = fields?.let { Optional.Present(it) } ?: Optional.Absent,
                    include = include?.let { Optional.Present(it) } ?: Optional.Absent,
                    exclude = exclude?.let { Optional.Present(it) } ?: Optional.Absent
                )
            )

            executeQuery(query, onError) { result ->
                val records = result.data?.searchRecords ?: listOf()
                onSuccess(records)
            }
        }

        fun fetchCart(
            onSuccess: (result: FetchCartQuery.FetchCart) -> Unit,
            onError: (error: DashXError) -> Unit
        ) {
            val uid = accountUid ?: run {
                onError(DashXError.NotIdentified())
                return
            }

            val query = FetchCartQuery(input = FetchCartInput(accountUid = Optional.Present(uid)))

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
            onSuccess: (result: AddItemToCartMutation.AddItemToCart) -> Unit,
            onError: (error: DashXError) -> Unit
        ) {
            val uid = accountUid ?: run {
                onError(DashXError.NotIdentified())
                return
            }
            val mutation = AddItemToCartMutation(
                input = AddItemToCartInput(
                    accountUid = Optional.Present(uid),
                    itemId = itemId,
                    pricingId = pricingId,
                    quantity = quantity,
                    reset = reset
                )
            )

            executeMutation(mutation, onError) { result ->
                result.data?.addItemToCart?.let(onSuccess)
            }
        }

        fun fetchStoredPreferences(
            onSuccess: (result: FetchStoredPreferencesQuery.FetchStoredPreferences) -> Unit,
            onError: (error: DashXError) -> Unit
        ) {
            val uid = accountUid ?: run {
                onError(DashXError.NotIdentified())
                return
            }
            val query = FetchStoredPreferencesQuery(input = FetchStoredPreferencesInput(uid))

            executeQuery(query, onError) { result ->
                result.data?.fetchStoredPreferences?.let(onSuccess)
            }
        }

        fun saveStoredPreferences(
            preferenceData: JsonObject,
            onSuccess: (result: SaveStoredPreferencesMutation.SaveStoredPreferences) -> Unit,
            onError: (error: DashXError) -> Unit
        ) {
            val uid = accountUid ?: run {
                onError(DashXError.NotIdentified())
                return
            }
            val mutation = SaveStoredPreferencesMutation(
                input = SaveStoredPreferencesInput(
                    accountUid = uid,
                    preferenceData = Json.parseToJsonElement(preferenceData.toString()).jsonObject
                )
            )

            executeMutation(mutation, onError) { result ->
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

            val mutation = PrepareAssetMutation(
                input = PrepareAssetInput(
                    resource = Optional.Present(resource),
                    attribute = Optional.Present(attribute),
                    name = name,
                    size = size,
                    mimeType = mimeType
                )
            )

            coroutineScope.launch {
                val response = apolloClient.mutation(mutation).execute()
                if (hasApolloErrors(response.errors, response.exception, onError)) return@launch

                val prepareAssetResponse = response.data?.prepareAsset?.`data`?.let {
                    json.decodeFromJsonElement<PrepareAssetResponse>(it)
                }

                if (prepareAssetResponse?.upload != null) {
                    writeFileToUrl(
                        file,
                        prepareAssetResponse.upload.url,
                        response.data?.prepareAsset?.id?.toString() ?: "",
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
            val query = AssetQuery(id = id)
            val response = apolloClient.query(query).execute()

            if (hasApolloErrors(response.errors, response.exception, onError)) return

            val responseAsset = response.data?.asset
            if (responseAsset?.uploadStatus != AssetUploadStatus.UPLOADED && pollCounter.get() <= UploadConstants.POLL_TIME_OUT) {
                delay(UploadConstants.POLL_INTERVAL)
                pollCounter.incrementAndGet()
                asset(id, onSuccess, onError)
            } else {
                pollCounter.set(1)
                val responseJsonObject = JSONObject().apply {
                    put("id", responseAsset?.id?.toString())
                    put("resourceId", responseAsset?.resourceId?.toString())
                    put("attributeId", responseAsset?.attributeId?.toString())
                    put("uploadStatus", responseAsset?.uploadStatus?.rawValue)
                    put(DATA, responseAsset?.`data`?.let { JSONObject(it.toString()) })
                }

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

            val mutation = TrackEventMutation(
                input = TrackEventInput(
                    event = event,
                    accountUid = accountUid?.let { Optional.Present(it) } ?: Optional.Absent,
                    accountAnonymousUid = accountAnonymousUid?.let { Optional.Present(it) } ?: Optional.Absent,
                    data = jsonData?.let { Optional.Present(it) } ?: Optional.Absent,
                    systemContext = Optional.Present(systemContext)
                )
            )

            executeMutation(mutation) { result ->
                DashXLog.d(tag, result.data?.trackEvent?.toString())
            }
        }

        internal fun trackEventBlocking(event: String, data: HashMap<String, String>?, timeoutMs: Long = 3000) {
            val jsonData =
                data?.toMap()?.let { Json.parseToJsonElement(JSONObject(it).toString()).jsonObject }

            val systemContext = json.decodeFromString<SystemContextInput>(
                SystemContext.getInstance().fetchSystemContext().toString()
            )

            val mutation = TrackEventMutation(
                input = TrackEventInput(
                    event = event,
                    accountUid = accountUid?.let { Optional.Present(it) } ?: Optional.Absent,
                    accountAnonymousUid = accountAnonymousUid?.let { Optional.Present(it) } ?: Optional.Absent,
                    data = jsonData?.let { Optional.Present(it) } ?: Optional.Absent,
                    systemContext = Optional.Present(systemContext)
                )
            )

            try {
                runBlocking {
                    withTimeout(timeoutMs) {
                        apolloClient.mutation(mutation).execute()
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

            val mutation = TrackMessageMutation(
                input = TrackMessageInput(
                    id = id,
                    status = status,
                    timestamp = currentTime
                )
            )

            executeMutation(mutation) { result ->
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

                val mutation = SubscribeContactMutation(
                    input = SubscribeContactInput(
                        accountUid = accountUid?.let { Optional.Present(it) } ?: Optional.Absent,
                        accountAnonymousUid = accountAnonymousUid?.let { Optional.Present(it) } ?: Optional.Absent,
                        name = name?.let { Optional.Present(it) } ?: Optional.Absent,
                        kind = ContactKind.ANDROID,
                        value = newToken,
                        osName = Optional.Present("Android"),
                        osVersion = Build.VERSION.RELEASE.let { Optional.Present(it) },
                        deviceManufacturer = Optional.Present(Build.MANUFACTURER),
                        deviceModel = Optional.Present(Build.MODEL)
                    )
                )

                executeMutation(mutation, onError = { error ->
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

                    val mutation = UnsubscribeContactMutation(
                        input = UnsubscribeContactInput(
                            accountUid = uid?.let { Optional.Present(it) } ?: Optional.Absent,
                            accountAnonymousUid = anonymousUid?.let { Optional.Present(it) } ?: Optional.Absent,
                            value = savedToken
                        )
                    )

                    executeMutation(mutation, onError = { error ->
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
