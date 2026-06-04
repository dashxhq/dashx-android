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

        // In-flight guard for [refreshSubscriptionDeviceInfo]. The persisted
        // [SHARED_PREFERENCES_KEY_SUBSCRIBED_AD_INFO_VERSION] only collapses
        // backfills AFTER one completes and writes the version; without this
        // flag, two ad-info fetches that resolve within the round-trip of the
        // first mutation could both fire. Cleared in both the success and
        // error branches of [runSubscribeMutation] so a transient network
        // failure doesn't permanently block a future backfill.
        private val isSubscriptionDeviceInfoRefreshInFlight = AtomicBoolean(false)

        // Latch for ad-info refresh requests that arrive while a previous
        // backfill is in flight. When [refreshSubscriptionDeviceInfo]'s CAS
        // fails (slot already claimed), we set this to true so the in-flight
        // backfill can re-trigger a fresh refresh on release — picking up
        // any ad-id / ATT consent change that landed during its round-trip.
        // Without this, the in-flight backfill's success writes
        // SUBSCRIBED_AD_INFO_VERSION = current and the dropped refresh is
        // lost; the new ad-id would then be stranded until SDK version bumps.
        private val subscriptionDeviceInfoRefreshPending = AtomicBoolean(false)

        // In-flight guard against the unsubscribe ↔ ad-info-backfill race.
        // [reset] calls [unsubscribe] and immediately rotates accountUid /
        // accountAnonymousUid; without this guard, a [getAdvertisingInfo]
        // completion that lands inside that window would call
        // [refreshSubscriptionDeviceInfo], read the still-present saved
        // token, and run a subscribe mutation under the NEW post-reset
        // identity — creating a stray contact for the old FCM token under
        // the new anonymous account. Belt-and-suspenders together with
        // the eager clearing of [SHARED_PREFERENCES_KEY_DEVICE_TOKEN] at
        // unsubscribe entry below.
        private val isUnsubscribeInFlight = AtomicBoolean(false)

        // Generation counter for unsubscribe state. Bumped at the start of
        // every [unsubscribe] call and inside [shutdown]. Every unsubscribe
        // captures the post-increment value as its `flightId` and only
        // clears [isUnsubscribeInFlight] when the captured `flightId` still
        // matches the current value — so a stale [Job.invokeOnCompletion]
        // from a cancelled-by-shutdown unsubscribe can't reset the flag for
        // a NEWER session's in-flight unsubscribe. Without this, the old
        // session's late cleanup could permit a refresh / subscribe to slip
        // past the new session's unsubscribe gate.
        private val unsubscribeFlightId = AtomicInteger(0)

        // Generation counter for push-subscription state. Bumped by
        // [unsubscribe] at entry. Each subscribe mutation captures the
        // generation at start; on response, if the current generation no
        // longer matches, the response is "stale" (an unsubscribe ran
        // through during this mutation's lifecycle) and must NOT write
        // subscribe cache markers — even if the unsubscribe has already
        // finished and cleared [isUnsubscribeInFlight]. Covers the
        // timeout-reopens-race case where a long-running subscribe response
        // arrives after unsubscribe has fully returned.
        private val subscribeGeneration = AtomicInteger(0)

        // Counter of currently in-flight subscribe mutations (normal AND
        // backfill). When the count transitions 0→1, [inFlightSubscribesIdle]
        // gets a fresh CompletableDeferred; when it transitions 1→0 (last
        // subscribe completes), the deferred is completed. [unsubscribe]
        // awaits this deferred BEFORE sending its backend mutation, so the
        // backend always processes subscribe → unsubscribe in that order
        // and can't accidentally end up Subscribed via reorder. Reading and
        // writing the deferred reference is guarded by [subscribeStateLock]
        // to keep the count + deferred consistent across threads.
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

            // Load stored identity and stand up the Apollo client BEFORE
            // SystemContext.configure(...). That call kicks off the async
            // getAdvertisingInfo() coroutine in [SystemContextUtils], whose
            // completion invokes [refreshSubscriptionDeviceInfo] — which
            // can fire a subscribe mutation. If the fetch resolves quickly
            // (cached Play Services result), that mutation could otherwise
            // run before accountUid / accountAnonymousUid / identityToken
            // are loaded and before createGraphqlClient() has plumbed the
            // configured publicKey + baseURI + targetEnvironment into Apollo,
            // sending a malformed or unauthenticated request.
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
                // Explicit transport timeouts. Apollo's defaults are not
                // contractually documented and can change between versions;
                // for mobile SDK code we want a deterministic upper bound so
                // [awaitInFlightSubscribesIdle] (and any other caller waiting
                // on a mutation/query) is guaranteed to make progress within
                // a known wall-clock budget even on flaky / hung networks.
                //
                // Values: 15s connect (slow Wi-Fi, captive portals), 30s
                // read (POST body + GraphQL response). The two timers are
                // sequential — connect must succeed before the read clock
                // starts — so the absolute worst-case wall clock for a
                // single mutation/query is up to ~45s (connect stalls the
                // full 15s, then read stalls the full 30s) before
                // [executeMutation]'s catch path converts the failure into
                // an onError invocation. Typical successful mutations
                // complete in well under one second.
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
                    // Convert non-cancellation Apollo failures into an
                    // onError invocation so callers see them. Under
                    // cancellation (shutdown), withContext below propagates
                    // CancellationException out of the catch — neither
                    // onError nor onSuccess fires, preserving the documented
                    // [shutdown] contract: "no callbacks fire for cancelled
                    // in-flight operations." Subscribe-state cleanup runs
                    // via [onFinally] / [Job.invokeOnCompletion] regardless.
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
            // [Job.invokeOnCompletion] fires exactly once when the launched
            // coroutine terminates — normal completion, exception, OR
            // cancellation. This gives subscribe-state callers (which pass
            // [onFinally]) a deterministic cleanup hook that runs even when
            // [shutdown] cancels the parent job during the callback dispatch.
            // Public callers that don't pass [onFinally] keep their old
            // "no callbacks after shutdown" behavior. The handler MUST NOT
            // throw — Job.invokeOnCompletion's contract — so wrap in
            // try/catch defensively.
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
                    // Parallel handling of [executeMutation]'s catch — see
                    // there for rationale.
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
            // Bump the subscribe generation so any in-flight subscribe
            // response that arrives post-shutdown is recognized as stale by
            // the post-response guard in [runSubscribeMutation] and skips
            // the cache write. Same mechanism as [unsubscribe] uses for
            // concurrent subscribes — matters here because the response
            // could otherwise land in the new SDK session and resurrect
            // subscribe state the consumer is tearing down.
            subscribeGeneration.incrementAndGet()

            coroutineJob.cancel()
            EventQueue.shared().stop()
            coroutineJob = SupervisorJob()
            coroutineScope = CoroutineScope(Dispatchers.IO + coroutineJob)

            // Bump [unsubscribeFlightId] BEFORE clearing [isUnsubscribeInFlight].
            // The new session's [unsubscribe] will capture a fresh post-bump
            // value as its flightId; any stale [Job.invokeOnCompletion] from
            // a cancelled-by-this-shutdown unsubscribe still holds its
            // pre-bump captured value, so its [clearUnsubscribeIfCurrent]
            // check will detect the mismatch and skip the clear — preventing
            // the late cleanup from corrupting the new session's
            // unsubscribe-in-flight flag.
            unsubscribeFlightId.incrementAndGet()

            // Reset the boolean state flags so the new SDK session starts
            // unblocked. These could otherwise leak across [configure] if
            // their normal cleanup mechanism never fires —
            // e.g. [isUnsubscribeInFlight] is set synchronously in
            // [unsubscribe] before the Firebase deleteToken() callback
            // fires; if that callback never arrives (Firebase teardown
            // mid-flight, app force-killed and restarted), the flag would
            // otherwise stay true across the new session and block every
            // subscribe()/refreshSubscriptionDeviceInfo() call via their
            // unsubscribe-in-flight guards. set(false) is idempotent —
            // and for [isUnsubscribeInFlight] specifically, the
            // [unsubscribeFlightId] bump above guards against stale
            // cancelled-Job clears corrupting the new session.
            isSubscriptionDeviceInfoRefreshInFlight.set(false)
            isUnsubscribeInFlight.set(false)
            // Drop any pending ad-info refresh latch — the new session will
            // fire its own [getAdvertisingInfo] on [configure] anyway, so a
            // pre-shutdown latch carrying over has no value and would just
            // cause one extra round-trip when the new session's first
            // backfill releases.
            subscriptionDeviceInfoRefreshPending.set(false)

            // NOT zeroing [inFlightSubscribeCount] or nulling
            // [inFlightSubscribesIdle] here. The [coroutineJob.cancel] above
            // propagates cancellation to every in-flight subscribe/unsubscribe
            // Job; their [Job.invokeOnCompletion] handlers fire and call
            // releaseMutation → endSubscribeMutation under
            // [subscribeStateLock], which decrements the counter exactly
            // once per Job and completes+nulls the deferred when the count
            // reaches 0. Manually zeroing the counter here would push it
            // negative when those handlers fire afterward —
            // beginSubscribeMutation's `getAndIncrement() == 0` deferred-
            // creation check would then return false for the new session's
            // first subscribe (since pre-increment value would be negative,
            // not 0), no new deferred would be created, and a subsequent
            // unsubscribe's [awaitInFlightSubscribesIdle] would read `null`
            // and skip its wait, corrupting backend ordering. Letting
            // cancellation's natural cleanup drain the counter keeps the
            // state machine consistent without needing generation-aware
            // begin/end logic.
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

            // Core cache-hit gate: skip the mutation only when the FCM token AND the
            // SDK version recorded on the contact already match what we'd send. This
            // covers the contact's device_uid + library metadata + user agent. Note
            // that the advertising-info enrichment is gated SEPARATELY (see
            // [refreshSubscriptionDeviceInfo] below) — the async [getAdvertisingInfo]
            // fetch must not block the core subscribe path, or apps with no Play
            // Services / slow ad-info startup would re-subscribe on every launch.
            if (savedToken == token && savedLibraryVersion == BuildConfig.VERSION_NAME) {
                DashXLog.d(tag, "Already subscribed: $savedToken")
                return
            }

            runSubscribeMutation(ctx, token)
        }

        /**
         * Marks the start of a subscribe mutation: increments the in-flight
         * count and, if this is the first concurrent subscribe, allocates a
         * fresh [CompletableDeferred] that [unsubscribe] will await before
         * sending its own backend mutation. Returns the [subscribeGeneration]
         * snapshot the caller must compare against in its response callback
         * to detect intervening unsubscribes.
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
         * Marks a subscribe mutation as finished. When the count returns to
         * zero, completes the idle deferred so any awaiting unsubscribe can
         * proceed. MUST be called from every exit path of
         * [runSubscribeMutation] (pre-flight guard, error callback, success
         * callback) or unsubscribe will hang on the deferred forever.
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
         * Suspends until every in-flight subscribe mutation (normal or
         * backfill, started before this call) completes.
         *
         * No coroutine-side timeout. Instead, the wait is bounded by the
         * explicit Apollo HTTP timeouts configured in [createApolloClient]
         * (15s connect + 30s read, sequential — up to ~45s combined), which
         * guarantee any single subscribe mutation resolves — successfully,
         * with a Network/GraphQL error, or via the catch path in
         * [executeMutation] that converts a thrown exception into an onError
         * invocation — within that budget. Once that resolves, the
         * [runSubscribeMutation] cleanup runs, decrements the in-flight count,
         * and completes the deferred this function awaits.
         *
         * A coroutine-side timeout was tried earlier and rejected: if it
         * fired while a subscribe was truly still in flight, the late
         * response could land after unsubscribe cleared
         * [isUnsubscribeInFlight] and write the token back, AND the backend
         * could process subscribe-after-unsubscribe. The [subscribeGeneration]
         * check on the response side closes the local cache half of that
         * race even if the Apollo timeout fires; waiting here for the
         * Apollo-level termination closes the backend-ordering half.
         */
        private suspend fun awaitInFlightSubscribesIdle() {
            val deferred = synchronized(subscribeStateLock) { inFlightSubscribesIdle }
            deferred?.await()
        }

        /**
         * Optional one-shot enrichment trigger. Called by [getAdvertisingInfo] once
         * the async advertising-ID fetch resolves (successfully or with Play
         * Services missing). If the device already has a saved FCM token and the
         * contact's advertising info hasn't been synced for the current SDK
         * version, runs a single subscribe mutation to backfill
         * `device_advertising_uid` + `is_device_ad_tracking_enabled` onto the
         * existing contact row. No-op otherwise — so multiple invocations during
         * normal SDK lifecycle (each [SystemContext] refresh call also runs
         * [getAdvertisingInfo]) collapse to at most one extra backend round-trip.
         */
        internal fun refreshSubscriptionDeviceInfo() {
            // Bail if unsubscribe is mid-flight — see [isUnsubscribeInFlight].
            // Combined with unsubscribe's eager clearing of the token below,
            // this guarantees we never re-subscribe a token the consumer is
            // actively tearing down, even if reset() rotates accountUid in
            // the same tick.
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
            // Claim the in-flight slot atomically — if another backfill is
            // already executing, latch [subscriptionDeviceInfoRefreshPending]
            // so the in-flight backfill's [releaseMutation] re-fires a
            // refresh on completion. Without this, an ad-id / ATT consent
            // change that lands mid-backfill would be lost: the in-flight
            // mutation (using the OLD ad-id) writes
            // SUBSCRIBED_AD_INFO_VERSION = current, and the next refresh
            // check sees the version match and returns early — stranding
            // the new ad-id locally until the SDK version bumps.
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
            // Register this mutation in the in-flight tracker BEFORE the
            // unsubscribe-flight gate. Order matters: if we read the flag
            // first and then increment, a concurrent unsubscribe between
            // those two steps would see count=0, skip awaitInFlightSubscribesIdle,
            // and send its backend mutation while ours is still in transit.
            // Incrementing first guarantees any unsubscribe that observes
            // !isUnsubscribeInFlight also observes count >= 1.
            val gen = beginSubscribeMutation()

            // Idempotent cleanup. Called from every exit path: pre-mutation
            // guard, executeMutation onError, post-response stale guard,
            // success-tail, AND the catch block if executeMutation throws
            // synchronously before producing any callback (Apollo can in
            // principle throw on serialization, configuration, or other
            // pre-flight errors). The [AtomicBoolean] makes a duplicate call
            // a no-op so we never double-decrement [inFlightSubscribeCount]
            // or release [isSubscriptionDeviceInfoRefreshInFlight] twice.
            // Without this guarantee, [awaitInFlightSubscribesIdle] could
            // hang the next [unsubscribe] forever when the count gets stuck.
            val released = AtomicBoolean(false)
            fun releaseMutation() {
                if (!released.compareAndSet(false, true)) return
                endSubscribeMutation()
                // Only the backfill path claims [isSubscriptionDeviceInfoRefreshInFlight],
                // so only the backfill path may release it — a normal subscribe
                // overlapping with an in-flight backfill must NOT clear the
                // guard out from under it.
                if (isAdInfoRefresh) {
                    isSubscriptionDeviceInfoRefreshInFlight.set(false)
                    // Pending re-trigger: if an ad-info change set
                    // [subscriptionDeviceInfoRefreshPending] = true while we
                    // were in flight, this mutation's payload is stale.
                    // Invalidate the version marker the success branch may
                    // have just written (so the re-fired refresh doesn't
                    // short-circuit on a version match) and re-fire.
                    // Atomically read+clear pending via [compareAndSet] so
                    // multiple coalesced pending requests collapse into one
                    // re-run.
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

            // Pre-mutation guard: abort if unsubscribe started between the
            // caller's gate check and now. Closes the window where a backfill
            // had already passed [refreshSubscriptionDeviceInfo]'s flag check
            // but hadn't yet hit the wire. Without this, the mutation would
            // create/refresh a contact at the backend that the in-progress
            // unsubscribe should be tearing down. This guard runs BEFORE the
            // try below because no Job is created if we early-return, so
            // [onFinally] / [Job.invokeOnCompletion] can't run — explicit
            // [releaseMutation] is the only cleanup path here.
            if (isUnsubscribeInFlight.get()) {
                DashXLog.d(tag, "Skipping subscribe mutation: unsubscribe is in flight.")
                releaseMutation()
                return
            }

            // Broad try wrapping ALL work after [beginSubscribeMutation] until
            // [executeMutation] either kicks off a Job (after which
            // [Job.invokeOnCompletion] guarantees [releaseMutation] runs) or
            // throws synchronously. Mechanical safety against a throw from
            // anywhere in metadata building, mutation construction, or the
            // launch dispatch itself — without this, [inFlightSubscribeCount]
            // could be stuck and [awaitInFlightSubscribesIdle] hang the next
            // [unsubscribe].
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

                // Once executeMutation has launched the Job, [releaseMutation]
                // runs exactly once via [Job.invokeOnCompletion] in
                // [executeMutation] — whether the response arrives, an error
                // arrives, or the Job is cancelled. The onSuccess / onError
                // lambdas here do business logic only; they no longer call
                // [releaseMutation] explicitly.
                executeMutation(
                    mutation,
                    onError = { error ->
                        DashXLog.e(tag, "Failed to subscribe: ${error.message}")
                    },
                    onFinally = ::releaseMutation
                ) { result ->
                    // Post-response guard: if unsubscribe ran (or completed!)
                    // between the time we sent this mutation and the time the
                    // response arrived, do NOT write the subscribe cache markers.
                    // We use the [subscribeGeneration] snapshot taken at entry
                    // rather than the transient [isUnsubscribeInFlight] flag, so
                    // a long-running subscribe response that lands AFTER
                    // unsubscribe has fully returned (and cleared the flag) is
                    // still recognized as stale. The mutation itself already hit
                    // the backend; [unsubscribe]'s [awaitInFlightSubscribesIdle]
                    // call waited for this callback before sending its own
                    // backend mutation, so the contact this subscribe touched
                    // will be torn down in order.
                    if (subscribeGeneration.get() != gen) {
                        DashXLog.d(
                            tag,
                            "Subscribe response stale (generation bumped by unsubscribe); skipping local cache write."
                        )
                        DashXLog.d(tag, result.data?.subscribeContact?.toString())
                        return@executeMutation
                    }
                    context?.let { c ->
                        getDashXSharedPreferences(c).edit().apply {
                            // Always commit the core cache markers — token + SDK version —
                            // so future same-token subscribe calls hit the gate.
                            putString(SHARED_PREFERENCES_KEY_DEVICE_TOKEN, token)
                            putString(
                                SHARED_PREFERENCES_KEY_SUBSCRIBED_LIBRARY_VERSION,
                                BuildConfig.VERSION_NAME
                            )
                            // Separately mark advertising info as synced for this SDK
                            // version, but ONLY when the async fetch has actually
                            // resolved (so refreshSubscriptionDeviceInfo will run once
                            // more after getAdvertisingInfo completes — at which point
                            // this branch fires and we stop backfilling).
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
                // Anything between [beginSubscribeMutation] and the
                // executeMutation Job creation threw. The Job was never
                // launched, so [Job.invokeOnCompletion] won't fire — clean
                // up explicitly here. [releaseMutation] is idempotent, so
                // double-firing (caught here AND via invokeOnCompletion
                // somehow) is harmless.
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

            // Claim the unsubscribe-in-flight guard and clear the local
            // subscribe cache eagerly — BEFORE the async FirebaseMessaging
            // deleteToken() callback fires. This closes the race where
            // [reset] rotates accountUid / accountAnonymousUid mid-unsubscribe
            // and a [getAdvertisingInfo] completion lands inside that window;
            // [refreshSubscriptionDeviceInfo] now sees the flag (or, even
            // without the flag, the null saved token) and short-circuits
            // instead of issuing a subscribe under the new identity. Matches
            // iOS, which clears its UserDefaults FCM-token key before
            // dispatching the unsubscribe mutation.
            //
            // Snapshot the identity NOW too so the unsubscribe mutation
            // below addresses the contact that was actually subscribed,
            // even if reset() rotates these companion fields before the
            // Firebase callback fires.
            // Capture this unsubscribe's `flightId` BEFORE setting the
            // in-flight flag, so every cleanup path below (executeMutation
            // onFinally, body catch, outer Job.invokeOnCompletion) can use
            // [clearUnsubscribeIfCurrent] to gate the clear on the
            // generation still matching. If [shutdown] runs while this
            // unsubscribe is mid-flight, it bumps [unsubscribeFlightId];
            // any stale cleanup from this Job that fires AFTER a new
            // session's unsubscribe captures its own flightId will see the
            // mismatch and skip — protecting the new session's
            // [isUnsubscribeInFlight] gate from being incorrectly cleared.
            val flightId = unsubscribeFlightId.incrementAndGet()
            isUnsubscribeInFlight.set(true)
            fun clearUnsubscribeIfCurrent() {
                if (unsubscribeFlightId.get() == flightId) {
                    isUnsubscribeInFlight.set(false)
                }
            }
            // Bump the subscribe generation so any in-flight subscribe
            // (normal OR backfill) recognizes itself as stale when its
            // response arrives and skips writing the subscribe cache back.
            // This is what plugs the timeout / late-response hole — even
            // if [awaitInFlightSubscribesIdle] somehow returned early or
            // the subscribe response is delivered after [isUnsubscribeInFlight]
            // is cleared, the generation snapshot taken at subscribe entry
            // won't match the current value and the post-response guard
            // will refuse the cache write.
            subscribeGeneration.incrementAndGet()
            getDashXSharedPreferences(ctx).edit().apply {
                remove(SHARED_PREFERENCES_KEY_DEVICE_TOKEN)
                remove(SHARED_PREFERENCES_KEY_SUBSCRIBED_LIBRARY_VERSION)
                remove(SHARED_PREFERENCES_KEY_SUBSCRIBED_AD_INFO_VERSION)
            }.apply()

            val uid = accountUid
            val anonymousUid = accountAnonymousUid

            // Dispatches the backend unsubscribe mutation. Deferred via
            // coroutine so it waits for any in-flight subscribe mutation
            // (normal OR ad-info backfill) started before unsubscribe began
            // to complete first — without that sequencing, the backend could
            // process subscribe AFTER unsubscribe (different worker instances,
            // no per-(uid, kind, value) lock) and leave the contact Subscribed.
            //
            // `reportResult` controls whether to invoke the consumer's
            // onSuccess/onError. We set it to `true` on the Firebase-success
            // path (the normal flow), and `false` on the Firebase-failure
            // path where we still attempt backend cleanup but have already
            // surfaced the Firebase error to the caller.
            fun sendBackendUnsubscribe(reportResult: Boolean) {
                // [handedOff] is an idempotent "cleanup has been taken care
                // of by someone" latch. The CAS-once semantics let three
                // separate paths race safely:
                //
                //  1. Body's catch — body started but threw before the
                //     handoff line (e.g. mutation construction failed).
                //  2. Outer Job.invokeOnCompletion — fires when the launched
                //     Job terminates by any means, INCLUDING when the body
                //     never started (scope was cancelled before dispatch,
                //     or got cancelled mid-launch). This is the safety net
                //     that catches what a body-internal try/finally cannot.
                //  3. The handoff `handedOff.set(true)` — body successfully
                //     dispatched executeMutation, whose own onFinally hook
                //     now owns clearing the flag.
                //
                // Without the outer invokeOnCompletion, a coroutineScope
                // that gets cancelled between the `.launch` returning a Job
                // and the body actually dispatching would never run the
                // body's try/finally — leaving isUnsubscribeInFlight stuck
                // true across the new SDK session created by shutdown().
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
                        // [onFinally] (registered via [Job.invokeOnCompletion]
                        // in executeMutation) clears [isUnsubscribeInFlight]
                        // for every termination path of THAT job — success,
                        // error, and cancellation. onSuccess/onError do
                        // consumer-reporting only. The clear is gated on
                        // [unsubscribeFlightId] still matching this
                        // unsubscribe's captured `flightId`, so a stale
                        // cancelled-Job onFinally that fires after [shutdown]
                        // bumped the id (and a new session may already be
                        // running) becomes a no-op.
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
                        // executeMutation returned normally — its Job is
                        // created and its onFinally is registered. Mark
                        // cleanup as handed off so the outer
                        // invokeOnCompletion below knows to skip.
                        handedOff.set(true)
                    } catch (t: Throwable) {
                        // Body started but threw before [handedOff] was set
                        // (e.g. cancellation during awaitInFlightSubscribesIdle,
                        // or a synchronous throw from executeMutation's launch
                        // dispatch). CAS makes the clear happen at most once
                        // across this path and the outer invokeOnCompletion;
                        // the clear itself is generation-gated via
                        // [clearUnsubscribeIfCurrent] so a stale firing
                        // after [shutdown] won't disturb a new session.
                        if (handedOff.compareAndSet(false, true)) {
                            clearUnsubscribeIfCurrent()
                        }
                        throw t
                    }
                }
                // Fires when the outer Job terminates by any means —
                // including the case where the body never executed at all
                // because the scope was cancelled before dispatch. CAS
                // ensures we only clear if no other path already did (body
                // catch, or successful handoff to executeMutation); the
                // clear is generation-gated via [clearUnsubscribeIfCurrent]
                // so a late cancellation that lands after [shutdown] has
                // bumped [unsubscribeFlightId] (and possibly a new session
                // has called unsubscribe) becomes a no-op.
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
                            "FirebaseMessaging.getInstance().deleteToken() failed: $message. " +
                                "Sending backend unsubscribe anyway to keep server-side state consistent."
                        )
                        // Best-effort backend cleanup: we already cleared the
                        // local subscribe cache at entry, so without this the
                        // backend contact would linger Subscribed and the
                        // caller would have no handle to retry (savedToken
                        // is gone). Don't report the backend result via the
                        // consumer callback — they're getting the Firebase
                        // error reported below, which preserves the existing
                        // failure-surface contract.
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
