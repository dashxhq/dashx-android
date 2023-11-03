package com.dashx.sdk.data

import com.dashx.graphql.generated.enums.AssetUploadStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Asset(
    val id: String,
    val resourceId: String,
    val attributeId: String,
    val uploadStatus: AssetUploadStatus,
    val data: AssetData,
)

@Serializable
data class AssetData(
    val asset: UploadData? = null,
    val upload: UploadData? = null
)

@Serializable
data class UploadData(
    val status: String,
    var url: String,
    @SerialName("playback_ids")
    val playbackIds: List<PlaybackData>
)

@Serializable
data class PlaybackData(
    val id: String? = null,
    val policy: String
)
