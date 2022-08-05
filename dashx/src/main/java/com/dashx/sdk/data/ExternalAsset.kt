package com.dashx.sdk.data

data class ExternalAsset(
    val id: String,
    val externalColumnId: String,
    val status: String,
    val data: ExternalAssetData,
)

data class ExternalAssetData(
    val asset: AssetData,
    val upload: AssetData
)

data class AssetData(
    val status: String,
    val url: String
)
