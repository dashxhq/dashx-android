package com.dashx.sdk

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.apollographql.apollo.api.Input
import com.apollographql.apollo.exception.ApolloException
import com.apollographql.apollo.ApolloCall
import com.apollographql.apollo.ApolloClient
import com.dashx.FetchContentQuery
import com.dashx.IdentifyAccountMutation
import com.dashx.SearchContentQuery
import com.dashx.TrackEventMutation
import com.dashx.type.FetchContentInput
import com.dashx.type.IdentifyAccountInput
import com.dashx.type.SearchContentInput
import com.dashx.type.TrackEventInput
import okhttp3.*
import java.util.UUID


class DashXClient private constructor() {
    private val tag = DashXClient::class.java.simpleName

    // Setup variables
    private var baseURI: String = "https://api.dashx.com/graphql"
    private var publicKey: String? = null
    private var targetEnvironment: String? = null
    private var targetInstallation: String? = null

    // Account variables
    private var anonymousUid: String? = null
    private var uid: String? = null
    private var deviceToken: String? = null
    private var identityToken: String? = null
    private var accountType: String? = null

    var applicationContext: Context? = null

    private var apolloClient = getApolloClient()

    fun setBaseURI(baseURI: String) {
        this.baseURI = baseURI
    }

    fun createApolloClient() {
        apolloClient = getApolloClient()
    }

    fun setTargetEnvironment(targetEnvironment: String) {
        this.targetEnvironment = targetEnvironment
    }

    fun setTargetInstallation(targetInstallation: String) {
        this.targetEnvironment = targetInstallation
    }

    fun setAccountType(accountType: String) {
        this.accountType = accountType
    }

    fun setPublicKey(publicKey: String) {
        this.publicKey = publicKey
    }

    private fun getApolloClient(): ApolloClient {
        return ApolloClient.builder()
            .serverUrl(baseURI)
            .okHttpClient(OkHttpClient.Builder()
                .addInterceptor {
                    val requestBuilder = it.request().newBuilder()
                        .addHeader("X-Public-Key", publicKey!!)

                    if (targetEnvironment != null) {
                        requestBuilder.addHeader("X-Target-Environment", targetEnvironment!!)
                    }

                    if (targetInstallation != null) {
                        requestBuilder.addHeader("X-Target-Installation", targetInstallation!!)
                    }

                    if (identityToken != null) {
                        requestBuilder.addHeader("X-Identity-Token", identityToken!!)
                    }

                    return@addInterceptor it.proceed(requestBuilder.build())
                }
                .build())
            .build()
    }

    fun generateAnonymousUid(regenerate: Boolean = false) {
        val dashXSharedPreferences: SharedPreferences =
            getDashXSharedPreferences(applicationContext!!)
        val anonymousUid =
            dashXSharedPreferences.getString(SHARED_PREFERENCES_KEY_ANONYMOUS_UID, null)
        if (!regenerate && anonymousUid != null) {
            this.anonymousUid = anonymousUid
        } else {
            this.anonymousUid = UUID.randomUUID().toString()
            dashXSharedPreferences.edit()
                .putString(SHARED_PREFERENCES_KEY_ANONYMOUS_UID, this.anonymousUid)
                .apply()
        }
    }

    fun identify(uid: String?, options: IndentifyOptions?) {
        if (uid != null) {
            this.uid = uid
            DashXLog.d(tag, "Set Uid: $uid")
            return
        }

        if (options == null) {
            throw Exception("Cannot be called with null, either pass uid: string or options: object")
        }

        val identifyAccountInput = IdentifyAccountInput(
            Input.fromNullable(accountType),
            Input.fromNullable(uid),
            Input.fromNullable(anonymousUid),
            Input.fromNullable(options?.email),
            Input.fromNullable(options?.phone),
            Input.fromNullable(options?.name),
            Input.fromNullable(options?.firstName),
            Input.fromNullable(options?.lastName)
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

    fun reset() {
        uid = null
        generateAnonymousUid(regenerate = true)
    }

    fun fetchContent(urn: String, options: FetchContentOptions, callback: (result: Any) -> Unit) {
        if (!urn.contains('/')) {
            throw Exception("URN must be of form: {contentType}/{content}")
        }

        val urnArray = urn.split('/')
        val content = urnArray[1]
        val contentType = urnArray[0]

        val fetchContentInput = FetchContentInput(
            contentType,
            content,
            Input.fromNullable(options.preview),
            Input.fromNullable(options.language),
            Input.fromNullable(options.fields),
            Input.fromNullable(options.include),
            Input.fromNullable(options.exclude)
        )

        val fetchContentQuery = FetchContentQuery(fetchContentInput)

        apolloClient.query(fetchContentQuery).enqueue(object : ApolloCall.Callback<FetchContentQuery.Data>() {
            override fun onFailure(e: ApolloException) {
                DashXLog.d(tag, "Could not get content for: $urn")
                e.printStackTrace()
            }

            override fun onResponse(response: com.apollographql.apollo.api.Response<FetchContentQuery.Data>) {
                val content = response.data?.fetchContent
                if (content != null) {
                    callback(content)
                }
                DashXLog.d(tag, "Got content: $content")
            }
        })
    }

    fun searchContent(contentType: String, options: SearchContentOptions, callback: (result: List<Any>) -> Unit) {
        val searchContentInput = SearchContentInput(
            contentType,
            options.returnType ?: "all",
            Input.fromNullable(options.filter),
            Input.fromNullable(options.order),
            Input.fromNullable(options.limit),
            Input.fromNullable(options.preview),
            Input.fromNullable(options.language),
            Input.fromNullable(options.fields),
            Input.fromNullable(options.include),
            Input.fromNullable(options.exclude)
        )

        val searchContentQuery = SearchContentQuery(searchContentInput)

        apolloClient.query(searchContentQuery).enqueue(object : ApolloCall.Callback<SearchContentQuery.Data>() {
            override fun onFailure(e: ApolloException) {
                DashXLog.d(tag, "Could not get content for: $contentType")
                e.printStackTrace()
            }

            override fun onResponse(response: com.apollographql.apollo.api.Response<SearchContentQuery.Data>) {
                val content = response.data?.searchContent
                callback(content ?: listOf())
                DashXLog.d(tag, "Got content: $content")
            }
        })
    }

    fun track(event: String, data: HashMap<String, String>?) {
        if (accountType == null) {
            DashXLog.d(tag, "Account type not set. Aborting request")
            return
        }

        val trackEventInput = TrackEventInput(
            accountType!!,
            event,
            Input.fromNullable(uid),
            Input.fromNullable(anonymousUid),
            Input.fromNullable(data)
        )
        val trackEventMutation = TrackEventMutation(trackEventInput)

        apolloClient
            .mutate(trackEventMutation)
            .enqueue(object : ApolloCall.Callback<TrackEventMutation.Data>() {
                override fun onFailure(e: ApolloException) {
                    DashXLog.d(tag, "Could not track: $event $data")
                    e.printStackTrace()
                }

                override fun onResponse(response: com.apollographql.apollo.api.Response<TrackEventMutation.Data>) {
                    val trackResponse = response.data?.trackEvent
                    DashXLog.d(tag, "Sent event: $event, $trackResponse")
                }
            })
    }

    fun trackAppStarted(fromBackground: Boolean = false) {
        val context = applicationContext ?: return

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

    companion object {
        val instance: DashXClient by lazy {
            DashXClient()
        }
    }
}
