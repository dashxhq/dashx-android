package com.dashx.sdk.data

data class PrepareAssetResponse(
    val upload: UploadAssetUrl,
)

data class UploadAssetUrl(
    val url: String
)
