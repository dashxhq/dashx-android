package com.dashx.sdk.data

import com.google.gson.annotations.SerializedName

data class ExternalAssetResponse(
    @SerializedName("data")
    val data: ExternalAsset
)

data class ExternalAsset(
    @SerializedName("id")
    val id: String,
    @SerializedName("externalColumnId")
    val externalColumnId: String,
    @SerializedName("status")
    val status: String,
    @SerializedName("externalAssetData")
    val externalAssetData: ExternalAssetData,
)

data class ExternalAssetData(
    @SerializedName("asset")
    val asset: AssetData,
    @SerializedName("upload")
    val upload: AssetData
)

data class AssetData(
    @SerializedName("status")
    val status: String,
    @SerializedName("url")
    val url: String
)
