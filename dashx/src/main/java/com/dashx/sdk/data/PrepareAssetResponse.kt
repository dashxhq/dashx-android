package com.dashx.sdk.data

import kotlinx.serialization.Serializable

@Serializable
data class PrepareAssetResponse(
    val upload: UploadAssetUrl,
)

@Serializable
data class UploadAssetUrl(
    val url: String
)
