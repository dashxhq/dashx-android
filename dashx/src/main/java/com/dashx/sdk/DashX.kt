package com.dashx.sdk

import android.content.Context
import com.dashx.graphql.generated.JSON
import com.dashx.graphql.generated.enums.TrackNotificationStatus
import com.dashx.sdk.data.LibraryInfo
import com.google.gson.JsonObject
import java.io.File

object DashX {

    private val INSTANCE = DashXClient

    fun configure(
        context: Context,
        publicKey: String,
        baseURI: String? = null,
        targetEnvironment: String? = null,
        libraryInfo: LibraryInfo? = null
    ) {
        DashXClient.configure(context, publicKey, baseURI, targetEnvironment, libraryInfo)
    }

    fun identify(options: HashMap<String, String>? = null) {
        INSTANCE.getInstance().identify(options)
    }

    fun setIdentity(uid: String?, token: String?) {
        INSTANCE.getInstance().setIdentity(uid, token)
    }

    fun setIdentityToken(identityToken: String) {
        INSTANCE.getInstance().setIdentityToken(identityToken)
    }

    fun reset() {
        INSTANCE.getInstance().reset()
    }

    fun subscribe() {
        INSTANCE.getInstance().subscribe()
    }

    fun track(event: String, data: HashMap<String, String>? = hashMapOf()) {
        INSTANCE.getInstance().track(event, data)
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
        INSTANCE.getInstance()
            .addItemToCart(itemId, pricingId, quantity, reset, custom, onSuccess, onError)
    }

    fun saveStoredPreferences(
        preferenceData: JSON,
        onSuccess: (result: JsonObject) -> Unit,
        onError: (error: String) -> Unit
    ) {
        INSTANCE.getInstance().saveStoredPreferences(preferenceData, onSuccess, onError)
    }

    fun uploadExternalAsset(
        file: File,
        externalColumnId: String,
        onSuccess: (result: com.dashx.sdk.data.ExternalAsset) -> Unit,
        onError: (error: String) -> Unit
    ) {
        INSTANCE.getInstance().uploadExternalAsset(file, externalColumnId, onSuccess, onError)
    }

    fun fetchStoredPreferences(
        onSuccess: (result: JsonObject) -> Unit,
        onError: (error: String) -> Unit
    ) {
        INSTANCE.getInstance().fetchStoredPreferences(onSuccess, onError)
    }
}
/*
    fun fetchCart(
        onSuccess: (result: JsonObject) -> Unit,
        onError: (error: String) -> Unit
    ) {
        INSTANCE.fetchCart(onSuccess, onError)
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
        INSTANCE.searchContent(
            contentType,
            returnType,
            filter,
            order,
            limit,
            preview,
            language,
            fields,
            include,
            exclude,
            onSuccess,
            onError
        )
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
        INSTANCE.fetchContent(
            urn,
            preview,
            language,
            fields,
            include,
            exclude,
            onSuccess,
            onError
        )
    }

    fun trackNotification(id: String, status: TrackNotificationStatus) {
        INSTANCE.trackNotification(id, status)
    }

    fun getBaseUri(): String? {
        return INSTANCE.getBaseUri()
    }

    fun getPublicKey(): String? {
        return INSTANCE.getPublicKey()
    }

    fun getTargetEnvironment(): String? {
        return INSTANCE.getTargetEnvironment()
    }

    fun getIdentityToken(): String? {
        return INSTANCE.getIdentityToken()
    }
}

*/