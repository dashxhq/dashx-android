package com.dashx.sdk.data

import com.google.gson.annotations.SerializedName
import com.dashx.graphql.generated.enums.AssetUploadStatus

data class Asset(
    val id: String,
    val resourceId: String,
    val attributeId: String,
    val uploadStatus: AssetUploadStatus,
    val data: AssetData,
)

data class AssetData(
    val asset: UploadData?,
    val upload: UploadData?
)

data class UploadData(
    val status: String,
    var url: String,
    @SerializedName("playback_ids")
    val playbackIds: List<PlaybackData>
)

data class PlaybackData(
    val id: String?,
    val policy: String
)
