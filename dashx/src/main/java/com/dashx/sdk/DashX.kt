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
import com.apollographql.apollo.network.http.DefaultHttpEngine
import com.dashx.android.graphql.generated.AssetQuery
import com.dashx.android.graphql.generated.FetchRecordQuery
import com.dashx.android.graphql.generated.FetchStoredPreferencesQuery
import com.dashx.android.graphql.generated.IdentifyAccountMutation
import com.dashx.android.graphql.generated.PrepareAssetMutation
import com.dashx.android.graphql.generated.SaveStoredPreferencesMutation
import com.dashx.android.graphql.generated.SearchRecordsQuery
import com.dashx.android.graphql.generated.SubscribeContactMutation
import com.dashx.android.graphql.generated.TrackEventMutation
import com.dashx.android.graphql.generated.TrackMessageMutation
import com.dashx.android.graphql.generated.UnsubscribeContactMutation
import com.dashx.android.graphql.generated.type.AssetUploadStatus
import com.dashx.android.graphql.generated.type.ContactKind
import com.dashx.android.graphql.generated.type.FetchRecordInput
import com.dashx.android.graphql.generated.type.FetchStoredPreferencesInput
import com.dashx.android.graphql.generated.type.IdentifyAccountInput
import com.dashx.android.graphql.generated.type.PrepareAssetInput
import com.dashx.android.graphql.generated.type.SaveStoredPreferencesInput
import com.dashx.android.graphql.generated.type.SearchRecordsInput
import com.dashx.android.graphql.generated.type.SubscribeContactInput
import com.dashx.android.utils.SystemContextMapper
import com.dashx.android.graphql.generated.type.TrackEventInput
import com.dashx.android.graphql.generated.type.TrackMessageInput
import com.dashx.android.graphql.generated.type.TrackMessageStatus
import com.dashx.android.graphql.generated.type.JSON
import com.dashx.android.graphql.generated.type.UnsubscribeContactInput
import com.dashx.android.data.PrepareAssetResponse
import com.dashx.android.utils.*
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DashX {
    companion object {
        private var baseURI: String? = null
        private var publicKey: String? = null
        private var targetEnvironment: String? = null

        @Volatile private var accountAnonymousUid: String? = null
        @Volatile private var accountUid: String? = null
        internal val isIdentified get() = accountUid != null
        @Volatile private var identityToken: String? = null

        @Volatile private var context: Context? = null

        /**
         * [CoroutineDispatcher] on which [onSuccess] and [onError] callbacks are invoked.
         * Defaults to [Dispatchers.Main.immediate] so UI updates are safe from callbacks.
         * Set via [configure] or [setCallbackDispatcher].
         */
        @Volatile private var callbackDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate

        private val pollCounter = AtomicInteger(1)

        // Single-slot guard for [refreshSubscriptionDeviceInfo]. The version
        // marker only dedupes AFTER a backfill completes; this flag dedupes
        // backfills whose fetches resolve mid-flight.
        private val isSubscriptionDeviceInfoRefreshInFlight = AtomicBoolean(false)

        // Latched when the CAS above fails. The in-flight backfill consumes
        // this on release and re-runs — without it, an ad-id / consent change
        // landing mid-backfill is lost when the version marker is committed.
        private val subscriptionDeviceInfoRefreshPending = AtomicBoolean(false)

        // Set at [unsubscribe] entry; checked by [refreshSubscriptionDeviceInfo]
        // so a [getAdvertisingInfo] completion landing mid-[reset] can't
        // resurrect the contact under the rotated identity.
        private val isUnsubscribeInFlight = AtomicBoolean(false)

        // Bumped at [unsubscribe] entry and in [shutdown]. Every unsubscribe
        // captures the post-increment value and only clears [isUnsubscribeInFlight]
        // when it still matches — so a stale [Job.invokeOnCompletion] from a
        // cancelled-by-shutdown unsubscribe can't disturb a new session's flag.
        private val unsubscribeFlightId = AtomicInteger(0)

        // Bumped at [unsubscribe] / [shutdown] entry. Each subscribe mutation
        // captures the value at start; on response, a mismatch means a
        // concurrent unsubscribe ran through and the cache markers must NOT
        // be written — even if [isUnsubscribeInFlight] has already cleared.
        private val subscribeGeneration = AtomicInteger(0)

        // Tracks in-flight subscribe mutations (normal + backfill). On 0→1
        // [inFlightSubscribesIdle] gets a fresh deferred; on 1→0 it completes.
        // [unsubscribe] awaits this before sending its mutation so the backend
        // sees subscribe → unsubscribe in order. Reads/writes of the deferred
        // reference are guarded by [subscribeStateLock].
        private val inFlightSubscribeCount = AtomicInteger(0)
        @Volatile private var inFlightSubscribesIdle: CompletableDeferred<Unit>? = null
        private val subscribeStateLock = Any()
        private var coroutineJob = SupervisorJob()
        private var coroutineScope = CoroutineScope(Dispatchers.IO + coroutineJob)
        private val tag = DashX::class.java.simpleName
        private val json = Json { ignoreUnknownKeys = true }

        private val notificationListeners = CopyOnWriteArrayList<DashXNotificationListener>()

        fun registerNotificationListener(listener: DashXNotificationListener) {
            if (!notificationListeners.contains(listener)) {
                notificationListeners.add(listener)
            }
        }

        fun unregisterNotificationListener(listener: DashXNotificationListener) {
            notificationListeners.remove(listener)
        }

        internal fun dispatchNotificationReceived(payload: DashXPayload) {
            for (listener in notificationListeners) {
                listener.onNotificationReceived(payload)
            }
        }

        internal fun dispatchNotificationClicked(
            payload: DashXPayload,
            action: NavigationAction?,
            actionIdentifier: String? = null
        ): Boolean {
            var suppressDefault = false
            for (listener in notificationListeners) {
                if (listener.onNotificationClicked(payload, action, actionIdentifier)) {
                    suppressDefault = true
                }
            }
            return suppressDefault
        }

        internal fun dispatchNotificationDismissed(payload: DashXPayload) {
            for (listener in notificationListeners) {
                listener.onNotificationDismissed(payload)
            }
        }

        /** Configurable timeout for image downloads in notifications (ms). */
        var imageDownloadTimeoutMs: Int = 5000

        /** Configurable interval between asset upload status polls (ms). */
        var pollIntervalMs: Long = 3000

        /** Maximum number of asset upload polls before giving up. */
        var maxPollRetries: Int = 10

        fun configure(
            context: Context,
            publicKey: String,
            baseURI: String? = null,
            targetEnvironment: String? = null,
            callbackDispatcher: CoroutineDispatcher? = null
        ) {
            init(context, publicKey, baseURI, targetEnvironment, callbackDispatcher)
        }

        /**
         * Sets the dispatcher used for success/error callbacks. Useful for tests or when
         * you prefer callbacks on a specific thread (e.g. a single-thread executor).
         */
        fun setCallbackDispatcher(dispatcher: CoroutineDispatcher) {
            callbackDispatcher = dispatcher
        }

        private fun init(
            context: Context,
            publicKey: String,
            baseURI: String? = null,
            targetEnvironment: String? = null,
            callbackDispatcher: CoroutineDispatcher? = null
        ) {
            this.baseURI = baseURI
            this.publicKey = publicKey
            this.targetEnvironment = targetEnvironment
            this.context = context
            callbackDispatcher?.let { this.callbackDispatcher = it }

            // Identity + Apollo client MUST be ready before SystemContext —
            // a cached Play Services result lets [getAdvertisingInfo] fire
            // [refreshSubscriptionDeviceInfo] synchronously, which would
            // otherwise issue an unauthenticated mutation.
            loadFromStorage()
            createGraphqlClient()
            SystemContext.configure(context)
            configureEventQueue(context)
        }

        private fun configureEventQueue(context: Context) {
            val eventQueue = EventQueue.shared()
            eventQueue.configure(context)
            eventQueue.trackFunction = { event, dataJson, queuedUid, queuedAnonymousUid ->
                try {
                    val jsonData = dataJson?.let {
                        Json.parseToJsonElement(it).jsonObject
                    }
                    val systemContext = SystemContextMapper.toSystemContextInput(
                        SystemContext.getInstance().fetchSystemContext()
                    )
                    val mutation = TrackEventMutation(
                        input = TrackEventInput(
                            event = event,
                            accountUid = (queuedUid ?: accountUid)?.let { Optional.Present(it) } ?: Optional.Absent,
                            accountAnonymousUid = (queuedAnonymousUid ?: accountAnonymousUid)?.let { Optional.Present(it) } ?: Optional.Absent,
                            data = jsonData?.let { Optional.Present(it) } ?: Optional.Absent,
                            systemContext = Optional.Present(systemContext)
                        )
                    )
                    val response = apolloClient.mutation(mutation).execute()
                    response.errors.isNullOrEmpty() && response.exception == null
                } catch (_: Throwable) {
                    false
                }
            }
            eventQueue.flush()
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

        /** Recreated when identity/token changes (setIdentity, init). */
        @Volatile private var apolloClient = createApolloClient()

        private fun createGraphqlClient() {
            apolloClient = createApolloClient()
        }

        private fun createApolloClient(): ApolloClient {
            return ApolloClient.Builder()
                .serverUrl(baseURI ?: "https://api.dashx.com/graphql")
                // Explicit timeouts (15s connect + 30s read, sequential) bound
                // [awaitInFlightSubscribesIdle] to a known worst-case wall
                // clock on flaky networks. Apollo's defaults aren't documented.
                .httpEngine(DefaultHttpEngine(15_000L, 30_000L))
                .addCustomScalarAdapter(JSON.type, JsonObjectScalarAdapter)
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

        private fun <D : Mutation.Data> executeMutation(
            mutation: Mutation<D>,
            onError: ((DashXError) -> Unit)? = null,
            onFinally: (() -> Unit)? = null,
            onSuccess: (ApolloResponse<D>) -> Unit
        ) {
            val job = coroutineScope.launch {
                val response: ApolloResponse<D>
                try {
                    response = apolloClient.mutation(mutation).execute()
                } catch (t: Throwable) {
                    // Non-cancellation failures surface as onError. Under
                    // shutdown's cancellation, withContext propagates
                    // CancellationException out and the documented "no
                    // callbacks after cancel" contract holds; [onFinally]
                    // still runs via [Job.invokeOnCompletion].
                    val errorMsg = "Mutation execute failed: ${t.message ?: t::class.java.simpleName}"
                    DashXLog.e(tag, errorMsg)
                    withContext(callbackDispatcher) {
                        onError?.invoke(DashXError.NetworkError(errorMsg))
                    }
                    return@launch
                }
                withContext(callbackDispatcher) {
                    if (!hasApolloErrors(response.errors, response.exception, onError)) {
                        onSuccess(response)
                    }
                }
            }
            // [onFinally] runs on every termination (including cancellation),
            // giving subscribe-state callers a deterministic cleanup hook.
            // Public callers without [onFinally] preserve the "no callbacks
            // after shutdown" contract. Wrapped in try/catch per the
            // [Job.invokeOnCompletion] no-throw contract.
            onFinally?.let { hook ->
                job.invokeOnCompletion {
                    try {
                        hook()
                    } catch (e: Throwable) {
                        DashXLog.e(tag, "onFinally hook threw: ${e.message}")
                    }
                }
            }
        }

        private fun <D : Query.Data> executeQuery(
            query: Query<D>,
            onError: ((DashXError) -> Unit)? = null,
            onFinally: (() -> Unit)? = null,
            onSuccess: (ApolloResponse<D>) -> Unit
        ) {
            val job = coroutineScope.launch {
                val response: ApolloResponse<D>
                try {
                    response = apolloClient.query(query).execute()
                } catch (t: Throwable) {
                    // See [executeMutation]'s catch.
                    val errorMsg = "Query execute failed: ${t.message ?: t::class.java.simpleName}"
                    DashXLog.e(tag, errorMsg)
                    withContext(callbackDispatcher) {
                        onError?.invoke(DashXError.NetworkError(errorMsg))
                    }
                    return@launch
                }
                withContext(callbackDispatcher) {
                    if (!hasApolloErrors(response.errors, response.exception, onError)) {
                        onSuccess(response)
                    }
                }
            }
            onFinally?.let { hook ->
                job.invokeOnCompletion {
                    try {
                        hook()
                    } catch (e: Throwable) {
                        DashXLog.e(tag, "onFinally hook threw: ${e.message}")
                    }
                }
            }
        }

        private fun generateAccountAnonymousUid(): String {
            return UUID.randomUUID().toString()
        }

        fun identify(
            options: HashMap<String, String>? = null,
            onSuccess: (() -> Unit)? = null,
            onError: ((DashXError) -> Unit)? = null
        ) {
            if (options == null) {
                DashXLog.e(tag, "Cannot be called with null, pass options: object")
                onError?.let { coroutineScope.launch(callbackDispatcher) { it(DashXError.NetworkError("identify() requires a non-null options map")) } }
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

            executeMutation(mutation, onError) { result ->
                DashXLog.d(tag, result.data?.identifyAccount?.toString())
                onSuccess?.invoke()
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

        /**
         * Cancels all in-flight SDK operations and releases resources.
         * After calling this, [configure] must be called again before using the SDK.
         */
        fun shutdown() {
            // Mark in-flight subscribes stale before cancelling — late
            // responses landing in the new session would otherwise resurrect
            // cache markers the consumer is tearing down.
            subscribeGeneration.incrementAndGet()

            coroutineJob.cancel()
            EventQueue.shared().stop()
            coroutineJob = SupervisorJob()
            coroutineScope = CoroutineScope(Dispatchers.IO + coroutineJob)

            // Bump [unsubscribeFlightId] BEFORE clearing the flag so any
            // stale Job.invokeOnCompletion from this session can't clear
            // the new session's [isUnsubscribeInFlight].
            unsubscribeFlightId.incrementAndGet()

            // Reset latches the new session would otherwise inherit if a
            // Firebase callback never fires (teardown mid-flight, app killed).
            isSubscriptionDeviceInfoRefreshInFlight.set(false)
            isUnsubscribeInFlight.set(false)
            subscriptionDeviceInfoRefreshPending.set(false)

            // NOT zeroing [inFlightSubscribeCount] / [inFlightSubscribesIdle]:
            // cancellation propagates to in-flight Jobs whose
            // invokeOnCompletion handlers drain the counter naturally.
            // Manually zeroing would push it negative when those handlers
            // fire, breaking the 0→1 deferred-allocation invariant in
            // [beginSubscribeMutation] for the new session.
        }

        fun fetchRecord(
            urn: String,
            preview: Boolean? = null,
            language: String? = null,
            fields: List<JsonObject>? = null,
            include: List<JsonObject>? = null,
            exclude: List<JsonObject>? = null,
            onSuccess: (result: JsonObject) -> Unit,
            onError: (error: DashXError) -> Unit
        ) {
            val urnArray = urn.split('/')
            if (urnArray.size < 2 || urnArray[0].isEmpty() || urnArray[1].isEmpty()) {
                coroutineScope.launch(callbackDispatcher) {
                    onError(DashXError.NetworkError("URN must be of form: {resource}/{recordId}"))
                }
                return
            }
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
            preview: Boolean? = null,
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

        fun fetchStoredPreferences(
            onSuccess: (result: FetchStoredPreferencesQuery.FetchStoredPreferences) -> Unit,
            onError: (error: DashXError) -> Unit
        ) {
            val uid = accountUid ?: run {
                coroutineScope.launch(callbackDispatcher) { onError(DashXError.NotIdentified()) }
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
                coroutineScope.launch(callbackDispatcher) { onError(DashXError.NotIdentified()) }
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
                coroutineScope.launch(callbackDispatcher) { onError(DashXError.NotConfigured()) }
                return
            }
            val name = file.name
            val size = file.length().toInt()
            val uri = Uri.fromFile(file)
            val mimeType = ctx.contentResolver.getType(uri) ?: run {
                coroutineScope.launch(callbackDispatcher) {
                    onError(DashXError.AssetError("Could not determine MIME type for file"))
                }
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
                val hasErrors = withContext(callbackDispatcher) {
                    hasApolloErrors(response.errors, response.exception, onError)
                }
                if (hasErrors) return@launch
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

        fun fetchAsset(
            assetId: String,
            onSuccess: (result: com.dashx.android.data.Asset) -> Unit,
            onError: (error: DashXError) -> Unit
        ) {
            val query = AssetQuery(id = assetId)

            executeQuery(query, onError) { response ->
                val responseAsset = response.data?.asset
                val responseJsonObject = JSONObject().apply {
                    put("id", responseAsset?.id?.toString())
                    put("resourceId", responseAsset?.resourceId?.toString())
                    put("attributeId", responseAsset?.attributeId?.toString())
                    put("uploadStatus", responseAsset?.uploadStatus?.rawValue)
                    put(DATA, responseAsset?.`data`?.let { JSONObject(it.toString()) })
                }

                val decodedAsset = json.decodeFromString<com.dashx.android.data.Asset>(
                    responseJsonObject.toString()
                )

                val assetData = decodedAsset.data.asset
                if (assetData != null && assetData.url.isEmpty() && assetData.playbackIds.isNotEmpty()) {
                    assetData.url = generateMuxVideoUrl(assetData.playbackIds[0].id)
                }

                onSuccess(decodedAsset)
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
                        asset(
                            id,
                            onSuccess = { r -> coroutineScope.launch(callbackDispatcher) { onSuccess(r) } },
                            onError = { e -> coroutineScope.launch(callbackDispatcher) { onError(e) } }
                        )
                    } else {
                        withContext(callbackDispatcher) {
                            onError(DashXError.AssetError("Upload failed with HTTP ${connection.responseCode}"))
                        }
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
            val dispatchedOnError: (DashXError) -> Unit = { e ->
                coroutineScope.launch(callbackDispatcher) { onError(e) }
            }
            if (hasApolloErrors(response.errors, response.exception, dispatchedOnError)) return

            val responseAsset = response.data?.asset
            if (responseAsset?.uploadStatus != AssetUploadStatus.UPLOADED && pollCounter.get() <= maxPollRetries) {
                delay(pollIntervalMs)
                pollCounter.incrementAndGet()
                asset(id, onSuccess, dispatchedOnError)
            } else {
                pollCounter.set(1)
                val responseJsonObject = JSONObject().apply {
                    put("id", responseAsset?.id?.toString())
                    put("resourceId", responseAsset?.resourceId?.toString())
                    put("attributeId", responseAsset?.attributeId?.toString())
                    put("uploadStatus", responseAsset?.uploadStatus?.rawValue)
                    put(DATA, responseAsset?.`data`?.let { JSONObject(it.toString()) })
                }

                val decodedAsset = json.decodeFromString<com.dashx.android.data.Asset>(
                    responseJsonObject.toString()
                )

                val uploadAsset = decodedAsset.data.asset
                if (uploadAsset != null && uploadAsset.url.isEmpty() && uploadAsset.playbackIds.isNotEmpty()) {
                    uploadAsset.url = generateMuxVideoUrl(uploadAsset.playbackIds[0].id)
                }

                withContext(callbackDispatcher) { onSuccess(decodedAsset) }
            }
        }

        fun track(
            event: String,
            data: HashMap<String, String>? = hashMapOf(),
            onSuccess: (() -> Unit)? = null,
            onError: ((DashXError) -> Unit)? = null
        ) {
            val jsonData =
                data?.toMap()?.let { Json.parseToJsonElement(JSONObject(it).toString()).jsonObject }

            val systemContext = SystemContextMapper.toSystemContextInput(
                SystemContext.getInstance().fetchSystemContext()
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

            executeMutation(mutation, onError = { error ->
                val dataJsonStr = jsonData?.toString()
                EventQueue.shared().enqueue(event, dataJsonStr, accountUid, accountAnonymousUid)
                onError?.invoke(error)
            }) { result ->
                DashXLog.d(tag, result.data?.trackEvent?.toString())
                onSuccess?.invoke()
            }
        }

        internal fun trackEventBlocking(event: String, data: HashMap<String, String>?, timeoutMs: Long = 3000) {
            val jsonData =
                data?.toMap()?.let { Json.parseToJsonElement(JSONObject(it).toString()).jsonObject }

            val systemContext = SystemContextMapper.toSystemContextInput(
                SystemContext.getInstance().fetchSystemContext()
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

        fun trackMessage(
            id: String,
            status: TrackMessageStatus,
            onSuccess: (() -> Unit)? = null,
            onError: ((DashXError) -> Unit)? = null
        ) {
            val currentTime = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

            val mutation = TrackMessageMutation(
                input = TrackMessageInput(
                    id = id,
                    status = status,
                    timestamp = currentTime
                )
            )

            executeMutation(mutation, onError) { result ->
                DashXLog.d(tag, result.data?.trackMessage?.toString())
                onSuccess?.invoke()
            }
        }

        /**
         * Records a `dx_notification_navigated` event for every notification tap, regardless of
         * navigation type (deep link, screen, click action, rich landing, etc.).
         */
        internal fun trackNotificationNavigation(action: NavigationAction?, notificationId: String?) {
            val data = hashMapOf(
                "timestamp" to DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            )
            if (notificationId != null) {
                data["notification_id"] = notificationId
            }
            when (action) {
                is NavigationAction.DeepLink -> {
                    data["type"] = "deep_link"
                    data["url"] = action.url
                }
                is NavigationAction.Screen -> {
                    data["type"] = "screen"
                    data["screen_name"] = action.name
                    action.data?.let { data["screen_data"] = JSONObject(it).toString() }
                }
                is NavigationAction.RichLanding -> {
                    data["type"] = "rich_landing"
                    data["url"] = action.url
                }
                is NavigationAction.ClickAction -> {
                    data["type"] = "click_action"
                    data["click_action"] = action.action
                }
                null -> {
                    data["type"] = "default"
                }
            }
            track(EVENT_NOTIFICATION_NAVIGATED, data)
        }

        /**
         * Opens [url] in an in-app Custom Tabs browser (rich landing). Convenience wrapper around
         * [DashXBrowser.openRichLanding] for host apps that want to present a URL in-app outside
         * the notification flow.
         */
        fun openRichLanding(context: Context, url: String) {
            DashXBrowser.openRichLanding(context, url)
        }

        /**
         * Records a `dx_deep_link_opened` analytics event. Call when a deep link is opened (for example from a
         * push notification tap or an App Link); notification taps are tracked automatically by the SDK when
         * the default handling opens a URL.
         */
        fun processDeepLink(uri: Uri, source: String? = null) {
            val data = hashMapOf(
                "url" to uri.toString(),
                "timestamp" to DateTimeFormatter.ISO_INSTANT.format(Instant.now())
            )
            if (source != null) {
                data["source"] = source
            }
            track(EVENT_DEEP_LINK_OPENED, data)
        }

        private fun isFirebaseAvailable(): Boolean = try {
            Class.forName("com.google.firebase.messaging.FirebaseMessaging")
            true
        } catch (_: ClassNotFoundException) {
            false
        }

        fun subscribe() {
            if (!isFirebaseAvailable()) {
                DashXLog.e(tag, "Firebase is not available. Add firebase-messaging to your dependencies.")
                return
            }

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

                subscribe(newToken)
            })
        }

        /**
         * Subscribe this device for push notifications using an already-known FCM token.
         * Useful when integrating with an app's own [FirebaseMessagingService.onNewToken].
         */
        fun subscribe(token: String) {
            DashXLog.d(tag, "Subscribing with token.")

            val ctx = context ?: run {
                DashXLog.e(tag, "subscribe: context is null, configure() must be called first")
                return
            }

            val sharedPrefs = getDashXSharedPreferences(ctx)
            val savedToken = sharedPrefs.getString(SHARED_PREFERENCES_KEY_DEVICE_TOKEN, null)
            val savedLibraryVersion = sharedPrefs.getString(
                SHARED_PREFERENCES_KEY_SUBSCRIBED_LIBRARY_VERSION, null
            )

            // Cache-hit gate. Skip only when both token AND library version
            // match — otherwise an SDK upgrade never refreshes the contact's
            // device_uid / library metadata. Ad-info is gated separately so
            // the async [getAdvertisingInfo] doesn't block the core path.
            if (savedToken == token && savedLibraryVersion == BuildConfig.VERSION_NAME) {
                DashXLog.d(tag, "Already subscribed: $savedToken")
                return
            }

            runSubscribeMutation(ctx, token)
        }

        /**
         * Increments the in-flight count and, on 0→1, allocates the deferred
         * [unsubscribe] awaits. Returns the [subscribeGeneration] snapshot
         * the response handler compares against to detect intervening
         * unsubscribes.
         */
        private fun beginSubscribeMutation(): Int {
            synchronized(subscribeStateLock) {
                if (inFlightSubscribeCount.getAndIncrement() == 0) {
                    inFlightSubscribesIdle = CompletableDeferred()
                }
            }
            return subscribeGeneration.get()
        }

        /**
         * Decrements the in-flight count. On 1→0, completes the deferred
         * [awaitInFlightSubscribesIdle] is waiting on. MUST run on every
         * exit path of [runSubscribeMutation] or unsubscribe hangs.
         */
        private fun endSubscribeMutation() {
            synchronized(subscribeStateLock) {
                if (inFlightSubscribeCount.decrementAndGet() == 0) {
                    inFlightSubscribesIdle?.complete(Unit)
                    inFlightSubscribesIdle = null
                }
            }
        }

        /**
         * Suspends until every in-flight subscribe (normal or backfill,
         * started before this call) completes. Bounded by the Apollo HTTP
         * timeouts in [createApolloClient] (~45s worst case).
         *
         * A coroutine-side timeout would re-open the race this exists to
         * close: a subscribe response landing after unsubscribe cleared
         * [isUnsubscribeInFlight] could then write the token back AND the
         * backend could see subscribe-after-unsubscribe.
         */
        private suspend fun awaitInFlightSubscribesIdle() {
            val deferred = synchronized(subscribeStateLock) { inFlightSubscribesIdle }
            deferred?.await()
        }

        /**
         * Backfills `device_advertising_uid` + `is_device_ad_tracking_enabled`
         * onto the existing contact row. Called by [getAdvertisingInfo] once
         * the async fetch resolves. No-op when there's no saved token, the
         * version marker is current, or another backfill is in flight
         * (in which case the pending latch re-fires it on release).
         */
        internal fun refreshSubscriptionDeviceInfo() {
            if (isUnsubscribeInFlight.get()) return
            val ctx = context ?: return
            val sharedPrefs = getDashXSharedPreferences(ctx)
            val savedToken =
                sharedPrefs.getString(SHARED_PREFERENCES_KEY_DEVICE_TOKEN, null) ?: return
            val syncedAdInfoVersion = sharedPrefs.getString(
                SHARED_PREFERENCES_KEY_SUBSCRIBED_AD_INFO_VERSION, null
            )
            if (syncedAdInfoVersion == BuildConfig.VERSION_NAME) {
                return
            }
            if (!isSubscriptionDeviceInfoRefreshInFlight.compareAndSet(false, true)) {
                subscriptionDeviceInfoRefreshPending.set(true)
                return
            }
            DashXLog.d(tag, "Backfilling advertising info for existing subscription.")
            runSubscribeMutation(ctx, savedToken, isAdInfoRefresh = true)
        }

        private fun runSubscribeMutation(
            ctx: Context,
            token: String,
            isAdInfoRefresh: Boolean = false
        ) {
            // Increment-before-flag-read order: guarantees any unsubscribe
            // that observes !isUnsubscribeInFlight also observes count >= 1,
            // so [awaitInFlightSubscribesIdle] can't miss this mutation.
            val gen = beginSubscribeMutation()

            // Idempotent cleanup — guards against double-decrementing the
            // in-flight count when both onFinally and a synchronous catch
            // path try to release.
            val released = AtomicBoolean(false)
            fun releaseMutation() {
                if (!released.compareAndSet(false, true)) return
                endSubscribeMutation()
                if (isAdInfoRefresh) {
                    isSubscriptionDeviceInfoRefreshInFlight.set(false)
                    // Re-fire if an ad-info change latched a pending request
                    // during our round-trip. Invalidate the version marker
                    // first so the re-run doesn't short-circuit on the
                    // (now-stale) version the success branch just wrote.
                    if (subscriptionDeviceInfoRefreshPending.compareAndSet(true, false)) {
                        context?.let { c ->
                            getDashXSharedPreferences(c).edit()
                                .remove(SHARED_PREFERENCES_KEY_SUBSCRIBED_AD_INFO_VERSION)
                                .apply()
                        }
                        refreshSubscriptionDeviceInfo()
                    }
                }
            }

            // Closes the window where a backfill passed the caller's gate
            // but hadn't hit the wire yet. The early-return runs before any
            // Job is launched, so onFinally can't fire — explicit cleanup
            // is the only path here.
            if (isUnsubscribeInFlight.get()) {
                DashXLog.d(tag, "Skipping subscribe mutation: unsubscribe is in flight.")
                releaseMutation()
                return
            }

            // Catches synchronous throws from anywhere in mutation construction
            // before the Job is launched — otherwise the in-flight count would
            // stay incremented and the next unsubscribe would hang.
            try {
                val name = Settings.Global.getString(
                    ctx.contentResolver, Settings.Global.DEVICE_NAME
                ) ?: Settings.Secure.getString(ctx.contentResolver, "bluetooth_name")

                val packageInfo = runCatching { getPackageInfo(ctx) }.getOrNull()
                val appName = packageInfo?.applicationInfo?.loadLabel(ctx.packageManager)?.toString()
                val appVersion = packageInfo?.versionName
                val metadata = buildJsonObject {
                    put("app", buildJsonObject {
                        put("identifier", ctx.packageName)
                        appName?.let { put("name", it) }
                        appVersion?.let { put("version", it) }
                    })
                    put("library", buildJsonObject {
                        put("name", BuildConfig.LIBRARY_NAME)
                        put("version", BuildConfig.VERSION_NAME)
                    })
                }

                val mutation = SubscribeContactMutation(
                    input = SubscribeContactInput(
                        accountUid = accountUid?.let { Optional.Present(it) } ?: Optional.Absent,
                        accountAnonymousUid = accountAnonymousUid?.let { Optional.Present(it) } ?: Optional.Absent,
                        name = name?.let { Optional.Present(it) } ?: Optional.Absent,
                        kind = ContactKind.ANDROID,
                        value = token,
                        userAgent = getAppUserAgent().takeIf { it.isNotEmpty() }
                            ?.let { Optional.Present(it) } ?: Optional.Absent,
                        osName = Optional.Present("Android"),
                        osVersion = Optional.Present(Build.VERSION.RELEASE),
                        deviceManufacturer = Optional.Present(Build.MANUFACTURER),
                        deviceModel = Optional.Present(Build.MODEL),
                        deviceUid = getDeviceId(ctx).takeIf { it.isNotEmpty() }
                            ?.let { Optional.Present(it) } ?: Optional.Absent,
                        deviceAdvertisingUid = getStoredAdvertisingId(ctx).takeIf { it.isNotEmpty() }
                            ?.let { Optional.Present(it) } ?: Optional.Absent,
                        isDeviceAdTrackingEnabled = Optional.Present(isAdTrackingEnabled(ctx)),
                        metadata = Optional.Present(metadata)
                    )
                )

                executeMutation(
                    mutation,
                    onError = { error ->
                        DashXLog.e(tag, "Failed to subscribe: ${error.message}")
                    },
                    onFinally = ::releaseMutation
                ) { result ->
                    // Gate on generation rather than [isUnsubscribeInFlight]
                    // so a response landing AFTER unsubscribe has fully
                    // returned is still recognized as stale.
                    if (subscribeGeneration.get() != gen) {
                        DashXLog.d(tag, "Subscribe response stale; skipping local cache write.")
                        DashXLog.d(tag, result.data?.subscribeContact?.toString())
                        return@executeMutation
                    }
                    context?.let { c ->
                        getDashXSharedPreferences(c).edit().apply {
                            putString(SHARED_PREFERENCES_KEY_DEVICE_TOKEN, token)
                            putString(
                                SHARED_PREFERENCES_KEY_SUBSCRIBED_LIBRARY_VERSION,
                                BuildConfig.VERSION_NAME
                            )
                            // Hold off the ad-info marker until the async
                            // fetch resolves — otherwise the next refresh
                            // would short-circuit on a version match for
                            // empty ad-info.
                            if (hasAdvertisingInfoBeenFetched(c)) {
                                putString(
                                    SHARED_PREFERENCES_KEY_SUBSCRIBED_AD_INFO_VERSION,
                                    BuildConfig.VERSION_NAME
                                )
                            }
                        }.apply()
                    }
                    DashXLog.d(tag, result.data?.subscribeContact?.toString())
                }
            } catch (t: Throwable) {
                // No Job was launched, so onFinally won't fire — release
                // explicitly. [releaseMutation] is idempotent.
                DashXLog.e(tag, "Subscribe pre-dispatch failed: ${t.message}")
                releaseMutation()
            }
        }

        /**
         * Unsubscribe the current device's FCM token from DashX push.
         *
         * The optional [onSuccess] callback receives a [Boolean] indicating
         * whether the backend found and updated a matching subscribed
         * contact:
         *  - `true` — contact was found and unsubscribed.
         *  - `false` — non-error outcome meaning "no matching contact
         *    found"; typically the anonymous UID rotated since subscribe,
         *    the FCM token is stale, or the contact was already
         *    unsubscribed. From the end-user's perspective the device ends
         *    up unsubscribed in both cases; the boolean is useful for
         *    diagnostics and analytics.
         *
         * The optional [onError] callback fires for SDK-state problems
         * (Firebase dependency missing, `configure()` not yet called) and
         * transport failures (Firebase token-delete failure, GraphQL or
         * network errors). These are distinct from `success: false` so
         * callers can branch on the kind of outcome — and so [onSuccess]'s
         * boolean stays a clean signal for legitimate non-error outcomes
         * only. Both callbacks are nullable; pass either, both, or neither.
         */
        fun unsubscribe(
            onSuccess: ((Boolean) -> Unit)? = null,
            onError: ((DashXError) -> Unit)? = null,
        ) {
            if (!isFirebaseAvailable()) {
                DashXLog.e(tag, "Firebase is not available. Add firebase-messaging to your dependencies.")
                onError?.let {
                    coroutineScope.launch(callbackDispatcher) {
                        it(DashXError.NotConfigured("Firebase is not available. Add firebase-messaging to your dependencies."))
                    }
                }
                return
            }

            val ctx = context ?: run {
                DashXLog.e(tag, "unsubscribe: context is null, configure() must be called first")
                onError?.let {
                    coroutineScope.launch(callbackDispatcher) { it(DashXError.NotConfigured()) }
                }
                return
            }

            val savedToken = getDashXSharedPreferences(ctx).getString(
                SHARED_PREFERENCES_KEY_DEVICE_TOKEN, null
            )

            if (savedToken == null) {
                // Legitimate "nothing to unsubscribe" — same semantics as the
                // backend's "no matching contact" path. Surface as
                // success(false), not an error.
                DashXLog.d(tag, "unsubscribe() called without subscribing first")
                onSuccess?.let {
                    coroutineScope.launch(callbackDispatcher) { it(false) }
                }
                return
            }

            // Snapshot the flightId BEFORE setting the in-flight flag so
            // every cleanup path can gate its clear on the generation still
            // matching — protects the new session's flag from a stale
            // cancelled-Job onFinally after [shutdown].
            val flightId = unsubscribeFlightId.incrementAndGet()
            isUnsubscribeInFlight.set(true)
            fun clearUnsubscribeIfCurrent() {
                if (unsubscribeFlightId.get() == flightId) {
                    isUnsubscribeInFlight.set(false)
                }
            }
            // Bumped here too so an in-flight subscribe response landing
            // after [isUnsubscribeInFlight] clears still recognizes itself
            // as stale and skips the cache write.
            subscribeGeneration.incrementAndGet()
            // Clear local cache eagerly so a [getAdvertisingInfo] completion
            // landing during the async Firebase deleteToken roundtrip can't
            // see a stale saved token.
            getDashXSharedPreferences(ctx).edit().apply {
                remove(SHARED_PREFERENCES_KEY_DEVICE_TOKEN)
                remove(SHARED_PREFERENCES_KEY_SUBSCRIBED_LIBRARY_VERSION)
                remove(SHARED_PREFERENCES_KEY_SUBSCRIBED_AD_INFO_VERSION)
            }.apply()

            val uid = accountUid
            val anonymousUid = accountAnonymousUid

            // Sends the unsubscribe mutation after waiting for in-flight
            // subscribes to drain — required so the backend sees
            // subscribe → unsubscribe in order (no per-(uid, kind, value)
            // server-side lock).
            //
            // `reportResult` is false on the Firebase-failure path where
            // we still attempt backend cleanup but the consumer's onError
            // has already been called.
            fun sendBackendUnsubscribe(reportResult: Boolean) {
                // Three paths race to clear the flag; CAS lets exactly one
                // win:
                //   1. catch below — body threw before handoff
                //   2. outer invokeOnCompletion — Job cancelled before body
                //      ran (scope cancelled between .launch and dispatch)
                //   3. handedOff = true — executeMutation's onFinally now
                //      owns the clear
                val handedOff = AtomicBoolean(false)
                val job = coroutineScope.launch {
                    try {
                        awaitInFlightSubscribesIdle()
                        val mutation = UnsubscribeContactMutation(
                            input = UnsubscribeContactInput(
                                accountUid = uid?.let { Optional.Present(it) } ?: Optional.Absent,
                                accountAnonymousUid = anonymousUid?.let { Optional.Present(it) } ?: Optional.Absent,
                                value = savedToken
                            )
                        )
                        executeMutation(
                            mutation,
                            onError = { error ->
                                if (reportResult) onError?.invoke(error)
                            },
                            onFinally = { clearUnsubscribeIfCurrent() }
                        ) { result ->
                            val success = result.data?.unsubscribeContact?.success ?: false
                            DashXLog.d(tag, "Unsubscribed $savedToken (success=$success).")
                            if (reportResult) onSuccess?.invoke(success)
                        }
                        // executeMutation registered its onFinally — that now
                        // owns the clear.
                        handedOff.set(true)
                    } catch (t: Throwable) {
                        // Cancelled in awaitInFlightSubscribesIdle, or a
                        // synchronous throw from executeMutation's launch.
                        if (handedOff.compareAndSet(false, true)) {
                            clearUnsubscribeIfCurrent()
                        }
                        throw t
                    }
                }
                // Catches the case where the body never executed at all
                // (scope cancelled before dispatch) — what a body-internal
                // try/finally cannot.
                job.invokeOnCompletion {
                    if (handedOff.compareAndSet(false, true)) {
                        clearUnsubscribeIfCurrent()
                    }
                }
            }

            FirebaseMessaging.getInstance().deleteToken()
                .addOnCompleteListener(OnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        val message = task.exception?.message ?: "unknown error"
                        DashXLog.e(
                            tag,
                            "FirebaseMessaging.getInstance().deleteToken() failed: $message"
                        )
                        // Best-effort backend cleanup: local cache is already
                        // cleared, so without this the backend would linger
                        // Subscribed with no retry handle.
                        sendBackendUnsubscribe(reportResult = false)
                        onError?.let {
                            coroutineScope.launch(callbackDispatcher) {
                                it(DashXError.NetworkError("Firebase deleteToken failed: $message"))
                            }
                        }
                        return@OnCompleteListener
                    }
                    sendBackendUnsubscribe(reportResult = true)
                })
        }

        /** Manually flush the offline event queue. */
        fun flushEventQueue() {
            EventQueue.shared().flush()
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

        // ---- Suspend wrappers ----

        suspend fun identifyAsync(options: HashMap<String, String>? = null) =
            suspendCancellableCoroutine<Unit> { cont ->
                identify(options,
                    onSuccess = { cont.resumeWith(Result.success(Unit)) },
                    onError = { cont.resumeWith(Result.failure(DashXException(it))) }
                )
            }

        suspend fun trackAsync(event: String, data: HashMap<String, String>? = hashMapOf()) =
            suspendCancellableCoroutine<Unit> { cont ->
                track(event, data,
                    onSuccess = { cont.resumeWith(Result.success(Unit)) },
                    onError = { cont.resumeWith(Result.failure(DashXException(it))) }
                )
            }

        suspend fun trackMessageAsync(id: String, status: TrackMessageStatus) =
            suspendCancellableCoroutine<Unit> { cont ->
                trackMessage(id, status,
                    onSuccess = { cont.resumeWith(Result.success(Unit)) },
                    onError = { cont.resumeWith(Result.failure(DashXException(it))) }
                )
            }

        suspend fun fetchRecordAsync(
            urn: String,
            preview: Boolean? = null,
            language: String? = null,
            fields: List<JsonObject>? = null,
            include: List<JsonObject>? = null,
            exclude: List<JsonObject>? = null
        ) = suspendCancellableCoroutine<JsonObject> { cont ->
            fetchRecord(urn, preview, language, fields, include, exclude,
                onSuccess = { cont.resumeWith(Result.success(it)) },
                onError = { cont.resumeWith(Result.failure(DashXException(it))) }
            )
        }

        suspend fun searchRecordsAsync(
            resource: String,
            filter: JsonObject? = null,
            order: List<JsonObject>? = null,
            limit: Int? = null,
            preview: Boolean? = null,
            language: String? = null,
            fields: List<JsonObject>? = null,
            include: List<JsonObject>? = null,
            exclude: List<JsonObject>? = null
        ) = suspendCancellableCoroutine<List<JsonObject>> { cont ->
            searchRecords(resource, filter, order, limit, preview, language, fields, include, exclude,
                onSuccess = { cont.resumeWith(Result.success(it)) },
                onError = { cont.resumeWith(Result.failure(DashXException(it))) }
            )
        }
    }
}
