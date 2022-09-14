package com.dashx.sdk.data

import com.google.gson.annotations.SerializedName

data class ExternalAsset(
    val id: String,
    val externalColumnId: String,
    val status: String,
    val data: ExternalAssetData,
)

data class ExternalAssetData(
    val asset: AssetData?,
    val upload: AssetData?
)

data class AssetData(
    val status: String,
    var url: String,
    @SerializedName("playback_ids")
    val playbackIds: List<PlaybackData>
)

data class PlaybackData(
    val id: String?,
    val policy: String
)
